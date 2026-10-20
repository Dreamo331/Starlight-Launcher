/** (C) Copyright 2026 Starlight. All rights reserved. */
// 启动器抽象基类 //
// 提供所有加载器公用的基础设施:
//   - 日志系统
//   - 类路径 (classpath) 构建
//   - JVM 基础参数构建
//   - 游戏参数构建
//   - 进程启动与监控
//
// 子类只需实现 launch() 方法，填充特定加载器的启动逻辑。
package com.example.starlight.startmc.launcher;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.example.starlight.startmc.LaunchInfo;
import com.example.starlight.startmc.LaunchInfo.LoaderType;
import com.example.starlight.util.GameDirInitializer;
import com.example.starlight.util.GameProcessTweaks;
import com.example.starlight.util.VersionJsonMerger;

/**
 * 加载器基类（抽象模板方法模式）
 *
 * @param <T> 启动返回值类型（通常为 Integer 退出码）
 */
public abstract class BaseLauncher {

    // 日志常量 //

    /** 线程安全的日志时间戳格式器（替换原 SimpleDateFormat，多线程并发调用不再有竞态） */
    protected static final DateTimeFormatter LOG_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 线程安全的日志文件名时间戳格式器（仅 initLogging 使用） */
    protected static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /** Natives DLL 最低有效数量（低于此值认为不完整） */
    protected static final int MIN_REQUIRED_DLLS = 5;

    /** stdout/stderr 内存缓冲区上限（字符数，约 1MB）；超限丢弃最早内容，防止长时间挂机撑爆内存 */
    protected static final int MAX_LOG_BUFFER_CHARS = 1024 * 1024;

    /**
     * Natives 提取目录名称（保持稳定，以便后续启动时检测复用）
     * 参考 HMCL getNativeDirectory() 使用的固定命名约定
     */
    protected static final String NATIVES_EXTRACT_DIR = "natives-extracted";

    // 字段 //

    /** 启动配置信息 */
    protected final LaunchInfo launchInfo;

    /** 运行时推导路径 */
    protected Path gameDirPath;
    protected Path verDir;
    protected Path jarPath;
    protected Path assetsDir;
    protected Path libsRoot;
    protected Path nativesPath;

    /** 版本 JSON 解析结果 */
    protected JsonObject versionJson;
    protected String mainClass;
    protected String assetIndex;

    /** 日志 Writer */
    protected BufferedWriter logWriter;
    protected BufferedWriter errorLogWriter;
    protected Path logFile;
    protected Path errorLogFile;

    /** JVM stderr 内存缓冲区（实时捕获，类似 PCL2/HMCL 机制） */
    private final StringBuilder stderrBuffer = new StringBuilder(512);

    /** 游戏进程对象（startProcessWithMonitoring 中设置，供外部监控） */
    private volatile Process gameProcess = null;

    /** 游戏进程 PID（startProcessWithMonitoring 中设置，供外部监控） */
    private volatile long gamePid = -1;

    /** JVM stdout 内存缓冲区（实时捕获，类似 PCL2 日志进度检测） */
    protected final StringBuilder stdoutBuffer = new StringBuilder(512);

    /**
     * 进程启动回调（供外部捕获游戏进程引用，用于取消启动监控）
     * 在 startProcessWithMonitoring 中设置 gameProcess 后立即触发
     */
    private Consumer<Process> onProcessStarted;

    /** 占位符替换用的键值对（参考 HMCL getConfigurations()） */
    protected Map<String, String> placeholderKeys;

    // 进程控制 //

    /**
     * 注册游戏进程启动回调
     * 在 startProcessWithMonitoring 成功启动进程后会立即调用此回调，
     * 供外部（如 GameLauncherService）捕获 Process 引用用于取消启动。
     *
     * @param callback 接收已启动的 Process 对象；可在任意线程中调用
     */
    public void setOnProcessStarted(Consumer<Process> callback) {
        this.onProcessStarted = callback;
    }

    /**
     * 强制终止正在运行的游戏进程
     * 调用 Process.destroyForcibly() 立即终止，供"取消启动"等场景使用。
     * 若未启动进程或进程已退出则静默无操作。
     */
    public void destroyGameProcess() {
        Process p = this.gameProcess;
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
        }
    }

    // 构造 //

    public BaseLauncher(LaunchInfo launchInfo) {
        this.launchInfo = launchInfo;
    }

    // 子类需实现的抽象方法 //

    /**
     * 执行加载器特定的启动逻辑
     * @return 游戏进程退出码
     * @throws IOException          进程启动失败
     * @throws InterruptedException 进程等待中断
     */
    public abstract int launch() throws IOException, InterruptedException;

    // 初始化（由外部调用） //

    /**
     * 初始化日志系统
     * @param logDir 日志输出目录
     */
    public void initLogging(Path logDir) throws IOException {
        Files.createDirectories(logDir);

        String timestamp = FILE_TIMESTAMP.format(LocalDateTime.now());
        logFile = logDir.resolve("minecraft_launch_" + timestamp + ".log");
        errorLogFile = logDir.resolve("minecraft_error_" + timestamp + ".log");

        // 先创建第一个 Writer，再创建第二个；若第二个失败则关闭第一个避免资源泄漏
        BufferedWriter lw = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        try {
            errorLogWriter = Files.newBufferedWriter(errorLogFile, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            lw.close();
            throw e;
        }
        logWriter = lw;
    }

    /**
     * 初始化运行时路径（在解析 version.json 后调用）
     */
    public void initPaths() {
        gameDirPath = launchInfo.getGameDirPath();
        verDir = launchInfo.resolveVersionDir();
        jarPath = verDir.resolve(launchInfo.getVersion() + ".jar");
        assetsDir = launchInfo.resolveAssetsDir();
        libsRoot = launchInfo.resolveLibrariesRoot();
    }

    /**
     * 设置解析后的 version.json，并自动解析 inheritsFrom 继承链（参考 HMCL Version.resolve()）
     * Fabric/Forge/NeoForge 的 version JSON 通过 inheritsFrom 继承自父 Minecraft 版本，
     * 子版本仅包含加载器特有的 libraries 和参数，大部分 libraries 在父版本中。
     */
    public void setVersionJson(JsonObject json) {
        // 递归解析继承链：合并父版本的 libraries、assetIndex、mainClass 等
        this.versionJson = resolveInheritsFrom(json, new HashSet<>());
        // 注意：mainClass 必须从合并后的 versionJson 读取。
        // 部分 Fabric/Forge 子版本 JSON 不写 mainClass，靠 inheritsFrom 从父版本继承
        this.mainClass = versionJson.has("mainClass") ? versionJson.get("mainClass").getAsString() : "";
        this.assetIndex = (versionJson.has("assetIndex") && versionJson.get("assetIndex").isJsonObject())
                ? versionJson.getAsJsonObject("assetIndex").get("id").getAsString()
                : "legacy";
    }

    /**
     * 递归解析 version.json 的 inheritsFrom 继承链（参考 HMCL Version.resolve() + merge()）
     *
     * @param json         当前版本的 JSON
     * @param resolvedIds  已解析的版本 ID 集合（防止循环依赖）
     * @return 合并了父版本 libraries 后的完整 JSON
     */
    private JsonObject resolveInheritsFrom(JsonObject json, Set<String> resolvedIds) {
        if (!json.has("inheritsFrom")) return json;

        String parentId = json.get("inheritsFrom").getAsString();
        if (!resolvedIds.add(parentId)) {
            writeLog("检测到循环依赖: " + resolvedIds + ", 跳过父版本 " + parentId, "WARN");
            return json;
        }

        // 加载父版本 JSON
        Path parentJsonPath = verDir.resolveSibling(parentId).resolve(parentId + ".json");
        if (!Files.exists(parentJsonPath)) {
            writeLog("父版本 JSON 不存在: " + parentJsonPath + " (inheritsFrom=" + parentId + ")", "WARN");
            return json;
        }

        try {
            String parentContent = Files.readString(parentJsonPath, StandardCharsets.UTF_8);
            JsonObject parentJson = new Gson().fromJson(parentContent, JsonObject.class);

            // 递归解析父版本的继承链
            parentJson = resolveInheritsFrom(parentJson, resolvedIds);

            // 合并 libraries：父版本的 + 当前版本的 //
            JsonArray mergedLibs = new JsonArray();
            if (parentJson.has("libraries")) {
                for (JsonElement lib : parentJson.getAsJsonArray("libraries")) {
                    mergedLibs.add(lib);
                }
            }
            if (json.has("libraries")) {
                for (JsonElement lib : json.getAsJsonArray("libraries")) {
                    mergedLibs.add(lib);
                }
            }
            json.add("libraries", mergedLibs);

            // 合并其他字段：标量父有子无才继承，arguments 数组按父前子后拼接 //
            VersionJsonMerger.inheritMissing(json, parentJson, "assetIndex", "assets",
                    "minecraftArguments", "mainClass");
            VersionJsonMerger.mergeArguments(json, parentJson);
            VersionJsonMerger.inheritMissing(json, parentJson, "javaVersion", "complianceLevel");

            writeLog("已合并父版本 '" + parentId + "' 的 libraries 到 " + json.get("id").getAsString(), "INFO");

        } catch (IOException e) {
            writeLog("读取父版本 JSON 失败: " + e.getMessage(), "WARN");
        }

        return json;
    }

    /**
     * 构建占位符替换键值对（参考 HMCL getConfigurations()）
     * 在 buildJvmBase / buildGameArgsFromJson 之前调用
     */
    protected void buildPlaceholderKeys() {
        String effectiveGameDir = launchInfo.resolveEffectiveGameDir().toString();
        String nativesDirStr = (nativesPath != null) ? nativesPath.toString() : "";

        Map<String, String> keys = new HashMap<>();
        keys.put("${version_name}", launchInfo.getVersion());
        keys.put("${assets_root}", assetsDir.toString());
        keys.put("${assets_index_name}", assetIndex);
        keys.put("${auth_access_token}", launchInfo.getAccessToken());
        keys.put("${auth_session}", launchInfo.getAccessToken());
        keys.put("${auth_uuid}", launchInfo.getUuid());
        keys.put("${auth_player_name}", launchInfo.getUserName());
        keys.put("${user_type}", launchInfo.getUserType());
        keys.put("${version_type}", "StarlightLauncher");
        keys.put("${game_directory}", effectiveGameDir);
        keys.put("${library_directory}", libsRoot.toString());
        keys.put("${natives_directory}", nativesDirStr);
        keys.put("${launcher_name}", "StarlightLauncher");
        keys.put("${launcher_version}", "2.0.0");
        keys.put("${resolution_width}", String.valueOf(launchInfo.getWindowWidth()));
        keys.put("${resolution_height}", String.valueOf(launchInfo.getWindowHeight()));
        keys.put("${classpath_separator}", PlatformUtils.getClassPathSeparator());
        // HMCL 扩展占位符
        keys.put("${profile_name}", "Minecraft");
        keys.put("${user_properties}", "{}");
        keys.put("${language}", Locale.getDefault().toLanguageTag());
        this.placeholderKeys = Collections.unmodifiableMap(keys);
    }

    /**
     * 替换 Minecraft 参数中的占位符 ${...}
     * 参考 HMCL StringArgument.toString() + Pattern ${}
     */
    protected String replacePlaceholders(String str) {
        if (placeholderKeys == null || str == null) return str;
        String result = str;
        Pattern pattern = Pattern.compile("\\$\\{(.*?)\\}");
        Matcher m = pattern.matcher(str);
        while (m.find()) {
            String entry = m.group(0); // 完整 ${xxx}
            String replacement = placeholderKeys.get(entry);
            if (replacement != null) {
                result = result.replace(entry, replacement);
            }
        }
        return result;
    }

    // 版本隔离处理 //

    /**
     * 按当前版本的有效隔离开关准备隔离目录树（mods/saves/config 等）。
     *
     * <p>隔离目录的预建只在安装入口做过一次，且读的是全局开关。用户事后对某个版本
     * 单独打开隔离、或全局开关后开时，版本目录下并不会有这些子目录。与其因为
     * 「目录不存在」中止启动，不如在这里补齐；只有创建失败才真正中止，
     * 因为隔离目录不可写意味着游戏数据会落到全局目录。
     *
     * @throws IOException 隔离目录创建失败
     */
    protected void prepareIsolation() throws IOException {
        if (!launchInfo.isVersionIsolation()) return;

        GameDirInitializer.InitResult init = GameDirInitializer.initializeVersionIsolation(
                launchInfo.getGameDirPath(), launchInfo.getVersion());
        if (!init.ok()) {
            throw new IOException("版本隔离目录创建失败，已中止启动以免游戏数据写入全局目录: " + init.summary());
        }
        if (init.created() > 0) {
            writeLog("已补齐版本隔离目录: " + init.created() + " 个子目录", "INFO");
        }
    }

    // Natives 处理 //

    /**
     * 处理 Natives DLL：查找已提取的目录，必要时从 JAR 中提取
     * 与 NativesRepairTool 互补:
     *   - NativesRepairTool 确保 DLL 文件存在
     *   - 此方法确保 DLL 目录路径正确设置
     *
     * @return true = natives 就绪，false = 提取/补全失败（调用方应终止启动）
     */
    public boolean processNatives() {
        // 第一步：尝试查找已有 natives 目录 //
        // 将稳定的提取目录名称也纳入搜索范围，避免每次都重新提取
        List<Path> candidates = new ArrayList<>();
        for (String dirName : PlatformUtils.getNativesDirCandidates()) {
            candidates.add(verDir.resolve(dirName));
        }
        candidates.add(verDir.resolve("natives"));
        // 关键：将实际使用的提取目录加入候选列表，使后续启动可直接复用
        candidates.add(verDir.resolve(NATIVES_EXTRACT_DIR));

        // 清理 JNA 等运行时残留的 *.dll.x / *.dll.tmp 临时文件，避免污染复用目录
        for (Path dir : candidates) {
            if (Files.isDirectory(dir)) {
                try (java.util.stream.Stream<Path> files = Files.list(dir)) {
                    files.filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith(".x") || n.endsWith(".tmp");
                    }).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
                } catch (IOException ignored) {
                }
            }
        }

        // 复用前校验版本指纹：目录里必须留有上次提取时写入的指纹，且与当前 version.json
        // 声明的 native 库集合一致。此前只数 DLL 个数，会把别的版本留下的旧 LWJGL DLL 复用掉，
        // 导致游戏启动报 "Incompatible Java and native library versions"（26w14a 需要 LWJGL 3.4.1，
        // 却复用了 natives-extracted 里旧版的 lwjgl.dll）。
        String expectedFingerprint = nativesFingerprint();
        for (Path dir : candidates) {
            if (Files.exists(dir)) {
                try (java.util.stream.Stream<Path> files = Files.list(dir)) {
                    String nativeExt = PlatformUtils.getNativeExtension();
                    long dllCount = files.filter(p -> p.toString().endsWith(nativeExt)).count();
                    if (dllCount >= MIN_REQUIRED_DLLS) {
                        // 版本指纹校验：无指纹（别的启动器提取/历史残留）或指纹不符 → 视为过期重提
                        if (expectedFingerprint == null || !expectedFingerprint.equals(readNativesFingerprint(dir))) {
                            writeLog("natives 目录版本不符或缺少指纹，重新提取: " + dir, "WARN");
                            deleteRecursively(dir);
                            continue;
                        }
                        nativesPath = dir;
                        writeLog("找到完整的 natives 目录: " + nativesPath + " (" + dllCount + " 个" + nativeExt + ")", "SUCCESS");
                        launchInfo.setNativesDir(nativesPath);
                        buildPlaceholderKeys();
                        return true;
                    } else if (dllCount > 0) {
                        writeLog("发现不完整的 natives 目录: " + dir + " (只有 " + dllCount + " 个" + nativeExt + ")", "WARN");
                        // 不完整时删除，后续重新提取（参考 HMCL cleanDirectoryQuietly）
                        deleteRecursively(dir);
                    }
                } catch (IOException e) {
                    writeLog("检查 natives 目录失败: " + e.getMessage(), "WARN");
                }
            }
        }

        // 第二步：从 Native JAR 中提取 DLL //
        // 参考 HMCL DefaultLauncher.decompressNatives() + Unzipper
        writeLog("从 native JAR 文件中提取 DLL...", "WARN");
        Path extractDir = verDir.resolve(NATIVES_EXTRACT_DIR);

        try {
            if (Files.exists(extractDir)) deleteRecursively(extractDir);
            Files.createDirectories(extractDir);

            // 收集所有 native library jar（含 extract 排除规则）
            List<NativeJarInfo> nativeJars = new ArrayList<>();

            // 方式1: 从 version.json 的 libraries 中解析（更精确，支持 extract 规则）
            if (versionJson != null && versionJson.has("libraries")) {
                JsonArray libraries = versionJson.getAsJsonArray("libraries");
                for (JsonElement libElem : libraries) {
                    JsonObject lib = libElem.getAsJsonObject();

                    if (!CompatibilityHelper.appliesToCurrentEnvironment(
                            lib.has("rules") ? lib.getAsJsonArray("rules") : null)) {
                        continue;
                    }

                    boolean isNative = false;

                    // 检查 natives 映射（Mojang 1.20.1 及更早格式）
                    if (lib.has("natives")) {
                        JsonObject natives = lib.getAsJsonObject("natives");
                        String osName = PlatformUtils.getOsRuleName();
                        if (natives.has(osName)) {
                            isNative = true;
                        }
                    }

                    // 检查 downloads.classifiers（另一旧格式）
                    if (!isNative && lib.has("downloads") && lib.getAsJsonObject("downloads").has("classifiers")) {
                        JsonObject classifiers = lib.getAsJsonObject("downloads").getAsJsonObject("classifiers");
                        String nativeKeyword = "natives-" + PlatformUtils.getOsRuleName();
                        for (String key : classifiers.keySet()) {
                            if (key.startsWith(nativeKeyword)) {
                                isNative = true;
                                break;
                            }
                        }
                    }

                    // 检查 name 里的 natives classifier（1.21.5+/26w 快照新格式：
                    // 每条 native 是独立 library，name 形如 "org.lwjgl:lwjgl:3.4.1:natives-windows"，
                    // downloads.artifact.path 直接是 ...-natives-windows.jar，无 natives 映射、无 classifiers）
                    if (!isNative && lib.has("name")) {
                        String libName = lib.get("name").getAsString();
                        String nativeKeyword = "natives-" + PlatformUtils.getOsRuleName();
                        if (libName.contains(":" + nativeKeyword) || libName.contains(":" + nativeKeyword + "-")) {
                            // 架构过滤：natives-windows(无后缀) / -arm64 / -x86 只取与当前架构匹配的
                            // （如 x86_64 机器只取 natives-windows 与 natives-windows-x86_64，
                            //   不取 natives-windows-arm64 或 natives-windows-x86）
                            isNative = nativeClassifierMatchesArch(libName);
                        }
                    }

                    if (isNative) {
                        // 关键：必须解析 natives jar 路径，而非主 jar 路径
                        String path = resolveNativeLibraryPath(lib);
                        if (path != null) {
                            Path libPath = libsRoot.resolve(path);
                            if (Files.exists(libPath)) {
                                // 获取 extract 排除规则（参考 HMCL ExtractRules）
                                List<String> excludes = new ArrayList<>();
                                if (lib.has("extract") && lib.getAsJsonObject("extract").has("exclude")) {
                                    JsonArray excludeArr = lib.getAsJsonObject("extract").getAsJsonArray("exclude");
                                    for (JsonElement ex : excludeArr) {
                                        excludes.add(ex.getAsString());
                                    }
                                }
                                nativeJars.add(new NativeJarInfo(libPath, excludes));
                            }
                        }
                    }
                }
            }

            // 方式2: 回退到文件名搜索（当 JSON 解析未找到时）
            if (nativeJars.isEmpty() && Files.exists(libsRoot)) {
                String nativeJarKeyword = PlatformUtils.getNativeJarKeyword();
                String currentArch = PlatformUtils.getArch();
                // Files.walk 返回的 Stream 持有 DirectoryStream，必须 try-with-resources 关闭，避免句柄泄漏
                try (java.util.stream.Stream<Path> walk = Files.walk(libsRoot)) {
                    walk.filter(p -> p.toString().endsWith(".jar"))
                        .filter(p -> {
                            String name = p.getFileName().toString();
                            // 架构匹配完全交给 isArchMatch 精确判断，
                            // 一刀切排除 "x86" 会连带排除 natives-windows-x86_64
                            return name.contains(nativeJarKeyword)
                                && isArchMatch(name, currentArch);
                        })
                        .forEach(p -> nativeJars.add(new NativeJarInfo(p, new ArrayList<>())));
                }
            }

            writeLog("找到 " + nativeJars.size() + " 个 native JAR 文件", "INFO");

            if (nativeJars.isEmpty()) {
                writeLog("错误: 找不到任何 native JAR，无法补全原生库，终止启动", "ERROR");
                return false;
            }

            int extractedCount = 0;
            Set<String> extractedNames = new HashSet<>();

            for (NativeJarInfo jarInfo : nativeJars) {
                Path jar = jarInfo.jarPath;
                List<String> excludes = jarInfo.excludes;
                try (ZipFile zip = new ZipFile(jar.toFile())) {
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        String name = entry.getName();
                        String nativeExt = PlatformUtils.getNativeExtension();
                        String fileName = Paths.get(name).getFileName().toString();

                        if (entry.isDirectory()) continue;
                        if (!name.endsWith(nativeExt)) continue;
                        if (name.contains("x86") && !name.contains("x86_64") && !name.contains("amd64")) continue;
                        if (extractedNames.contains(fileName)) continue;

                        // 应用 extract 排除规则（参考 HMCL ExtractRules.shouldExtract()）
                        boolean excluded = false;
                        for (String exclude : excludes) {
                            if (fileName.startsWith(exclude) || name.startsWith(exclude)) {
                                excluded = true;
                                break;
                            }
                        }
                        if (excluded) continue;

                        // 跳过 .sha1, .git 等文件（参考 HMCL Unzipper 过滤）
                        if (PlatformUtils.isExcludedNativeFile(fileName)) continue;

                        Path dest = extractDir.resolve(fileName);
                        try (InputStream is = zip.getInputStream(entry)) {
                            Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
                        }
                        extractedCount++;
                        extractedNames.add(fileName);
                        writeLog("  提取: " + fileName + " <- " + jar.getFileName(), "INFO");
                    }
                } catch (IOException e) {
                    writeLog("提取失败 " + jar.getFileName() + ": " + e.getMessage(), "WARN");
                }
            }

            writeLog("提取完成: " + extractedCount + " 个 " + PlatformUtils.getNativeExtension() + " 文件", "SUCCESS");
            if (extractedCount < MIN_REQUIRED_DLLS) {
                writeLog("错误: 提取的 Native 文件数量不足 (" + extractedCount + " 个 < " + MIN_REQUIRED_DLLS + ")，终止启动", "ERROR");
                return false;
            }

            nativesPath = extractDir;
            launchInfo.setNativesDir(nativesPath);
            // 写版本指纹，供下次启动复用前校验（避免跨版本复用旧 LWJGL DLL）
            writeNativesFingerprint(nativesPath);

            long dllCount;
            try (java.util.stream.Stream<Path> files = Files.list(nativesPath)) {
                dllCount = files.filter(p -> p.toString().endsWith(PlatformUtils.getNativeExtension())).count();
            }
            writeLog("Natives 路径: " + nativesPath + " (" + dllCount + " 个 " + PlatformUtils.getNativeExtension() + ")", "INFO");

        } catch (IOException e) {
            writeLog("提取 natives 失败: " + e.getMessage(), "ERROR");
            return false;
        }

        buildPlaceholderKeys();
        return true;
    }

    /**
     * Native JAR 信息内部类（含 extract 排除规则）
     * 参考 HMCL Library.getExtract() + ExtractRules
     */
    protected static class NativeJarInfo {
        final Path jarPath;
        final List<String> excludes;

        NativeJarInfo(Path jarPath, List<String> excludes) {
            this.jarPath = jarPath;
            this.excludes = excludes;
        }
    }

    /**
     * OS 规则兼容性检测（参考 HMCL CompatibilityRule + OSRestriction）
     */
    protected static class CompatibilityHelper {
        static boolean appliesToCurrentEnvironment(JsonArray rules) {
            if (rules == null || rules.size() == 0) return true;

            boolean allowed = false;
            for (JsonElement ruleElem : rules) {
                JsonObject rule = ruleElem.getAsJsonObject();
                String action = rule.get("action").getAsString();
                if (rule.has("os")) {
                    JsonObject os = rule.getAsJsonObject("os");
                    String osName = os.get("name").getAsString();
                    String currentOs = PlatformUtils.getOsRuleName();
                    boolean osMatch = osName.equals(currentOs);

                    boolean versionMatch = true;
                    if (os.has("version")) {
                        String versionPattern = os.get("version").getAsString();
                        String osVersion = System.getProperty("os.version", "");
                        versionMatch = osVersion.matches(versionPattern);
                    }

                    if (osMatch && versionMatch) {
                        if ("allow".equals(action)) allowed = true;
                        else if ("disallow".equals(action)) allowed = false;
                    }
                } else {
                    if ("allow".equals(action)) allowed = true;
                    else if ("disallow".equals(action)) allowed = false;
                }
            }
            return allowed;
        }
    }

    /**
     * 检查文件名是否匹配当前架构
     * @param name        JAR 文件名
     * @param currentArch 当前架构（如 "x86_64", "arm64"）
     */
    private static boolean isArchMatch(String name, String currentArch) {
        // 文件名不包含任何架构标识视为通用，匹配所有架构
        if (!name.contains("x86") && !name.contains("arm") && !name.contains("aarch64")) {
            return true;
        }
        // 有架构标识时必须精确匹配当前架构
        if ("arm64".equals(currentArch)) {
            return name.contains("arm64") || name.contains("aarch64");
        }
        return name.contains(currentArch);
    }

    /**
     * 判断 library name 里的 natives classifier 是否匹配当前架构（新格式专用）。
     *
     * <p>新格式 native 条目（如 {@code org.lwjgl:lwjgl:3.4.1:natives-windows-arm64}）把
     * 架构信息放在 classifier 末尾：{@code natives-windows}（无后缀 = x86_64 通用）、
     * {@code natives-windows-arm64}、{@code natives-windows-x86}（32 位）。
     * 这里按当前架构过滤，避免把 arm64/x86 的 native 库也解压进来。
     */
    private boolean nativeClassifierMatchesArch(String libName) {
        String currentArch = PlatformUtils.getArch();
        String classifier = libName.substring(libName.lastIndexOf(':') + 1);
        // 无架构后缀（如 natives-windows）→ 视为 x86_64 通用
        if (classifier.endsWith("-arm64") || classifier.endsWith("-aarch64")) {
            return "arm64".equals(currentArch) || "aarch64".equals(currentArch);
        }
        if (classifier.endsWith("-x86_64") || classifier.endsWith("-amd64")) {
            return "x86_64".equals(currentArch) || "amd64".equals(currentArch);
        }
        if (classifier.endsWith("-x86")) {
            return "x86".equals(currentArch);
        }
        // 无后缀（natives-windows）：x86_64 机器用，arm64 机器用 arm64 后缀条目
        return !"arm64".equals(currentArch) && !"aarch64".equals(currentArch);
    }


    // 类路径构建 //

    /**
     * 收集版本 JSON 中声明的所有 libraries 文件路径
     *
     * @param includeVersionJar 是否包含版本 JAR（versions/<ver>/<ver>.jar）
     * @return 类路径条目列表（绝对路径字符串）
     */
    protected List<String> getLibraries(boolean includeVersionJar) {
        List<String> libs = new ArrayList<>();

        if (versionJson != null && versionJson.has("libraries")) {
            JsonArray libraries = versionJson.getAsJsonArray("libraries");
            for (JsonElement libElem : libraries) {
                JsonObject lib = libElem.getAsJsonObject();

                // 规则检查（OS 过滤，参考 HMCL CompatibilityRule） //
                if (!CompatibilityHelper.appliesToCurrentEnvironment(
                        lib.has("rules") ? lib.getAsJsonArray("rules") : null)) {
                    continue;
                }

                // 跳过 native libraries（它们不在 classpath 中） //
                //   1. 老格式（1.16 及更早，以及原版 1.17~1.20 未精简的 version.json）：
                //      同一条目同时带 natives 映射 + downloads.artifact（主 jar）
                //      + downloads.classifiers（natives jar）。主 jar 必须进 classpath，
                //      只有 classifiers 里的 natives jar 用于提取 DLL，不进 classpath。
                //   2. 新格式：natives 以独立 classifier 条目存在（downloads.classifiers 且无 artifact），
                //      或条目只有 name 指向 natives jar。
                //   3. LWJGL 3（1.17+）的 natives 条目自身带 downloads.artifact，
                //      但该 artifact 本身就是 *-natives-*.jar（classifier 含 natives-）。
                // 判定：name 坐标带 natives- classifier，或 artifact.path 含 "natives-" 时视为 native-only 跳过；
                //      否则带 downloads.artifact 的条目保留进 classpath。
                if (isNativeLibrary(lib)) continue;

                // 解析路径 //
                String path = resolveLibraryPath(lib);
                if (path != null && !path.isEmpty()) {
                    Path libPath = libsRoot.resolve(path);
                    if (Files.exists(libPath)) {
                        libs.add(libPath.toString());
                    }
                }
            }
        }

        // 去重
        libs = libs.stream().distinct().collect(Collectors.toList());

        if (includeVersionJar && Files.exists(jarPath)) {
            libs.add(jarPath.toString());
        }

        return libs;
    }

    /**
     * 判断该 library 条目是否为「native-only」条目（不进 classpath，只用于提取 DLL）。
     *
     * <p>覆盖三种格式：
     * <ol>
     *   <li>name 坐标带 {@code natives-} classifier（LWJGL 3 / 1.17+ 新格式，条目自身即 natives jar）；</li>
     *   <li>artifact.path 含 {@code natives-}（与上一条同源的另一形态，直接看路径更稳）；</li>
     *   <li>老格式（1.16 及更早）：无主 artifact，且带 {@code natives} 映射或
     *       {@code downloads.classifiers} 含 {@code natives-} 前缀。</li>
     * </ol>
     * <b>注意</b>：老格式中「{@code natives} 映射 + 主 artifact」同存的条目（如 lwjgl 2.x）主 jar
     * 仍必须进 classpath，只有其 classifiers 里的 natives jar 不进。因此第 3 条只在
     * <b>没有主 artifact</b> 时生效，避免把老格式主 jar 误跳过。
     * 参考 HMCL {@code Library.isNative()} 的判定口径。
     */
    private boolean isNativeLibrary(JsonObject lib) {
        // 新格式：name 坐标 group:artifact:version:natives-xxx
        if (lib.has("name")) {
            String name = lib.get("name").getAsString();
            String[] parts = name.split(":");
            if (parts.length >= 4 && parts[3].startsWith("natives-")) {
                return true;
            }
        }
        JsonObject downloads = lib.has("downloads") && lib.getAsJsonObject("downloads").isJsonObject()
                ? lib.getAsJsonObject("downloads") : null;
        // 新格式：artifact 路径本身含 natives-（条目只有一个 natives jar）
        if (downloads != null && downloads.has("artifact")
                && downloads.getAsJsonObject("artifact").isJsonObject()) {
            JsonObject artifact = downloads.getAsJsonObject("artifact");
            if (artifact.has("path")) {
                String path = artifact.get("path").getAsString();
                if (path.contains("natives-")) {
                    return true;
                }
            }
        }
        // 老格式：无主 artifact 才检查 natives 映射 / classifiers 前缀
        boolean hasMainArtifact = downloads != null && downloads.has("artifact");
        if (!hasMainArtifact) {
            if (lib.has("natives")) {
                return true;
            }
            if (downloads != null && downloads.has("classifiers")
                    && downloads.getAsJsonObject("classifiers").isJsonObject()) {
                JsonObject classifiers = downloads.getAsJsonObject("classifiers");
                for (String key : classifiers.keySet()) {
                    if (key.startsWith("natives-")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 从 library JSON 对象中解析相对路径
     */
    private String resolveLibraryPath(JsonObject lib) {
        // 优先使用 downloads.artifact.path
        if (lib.has("downloads") && lib.getAsJsonObject("downloads").has("artifact")) {
            JsonObject artifact = lib.getAsJsonObject("downloads").getAsJsonObject("artifact");
            if (artifact.has("path")) {
                return artifact.get("path").getAsString();
            }
        }

        // 降级：从 name 字段推导 Maven 路径
        if (lib.has("name")) {
            String name = lib.get("name").getAsString();
            String[] parts = name.split(":");
            if (parts.length >= 3) {
                String group = parts[0].replace('.', '/');
                String artifact = parts[1];
                String version = parts[2];
                String classifier = parts.length >= 4 ? "-" + parts[3] : "";
                return group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + classifier + ".jar";
            }
        }
        return null;
    }

    /**
     * 解析 native 库 JAR 的相对路径
     * 与 resolveLibraryPath 的区别：优先使用 downloads.classifiers["natives-windows"] 的 path，
     * 而不是 downloads.artifact.path（主 jar 不含 DLL）。
     * 参考 HMCL Library.getDownload() 中 classifier 的解析逻辑。
     */
    private String resolveNativeLibraryPath(JsonObject lib) {
        String osName = PlatformUtils.getOsRuleName();

        if (lib.has("downloads") && lib.getAsJsonObject("downloads").has("classifiers")) {
            JsonObject classifiers = lib.getAsJsonObject("downloads").getAsJsonObject("classifiers");

            // 优先按 natives 映射（如 "natives" : { "windows": "natives-windows" }）找 classifier 键
            // classifiers 中可能出现 "${arch}" 占位符（如 "natives-windows-${arch}"），
            // 需替换为 64/32 后才能命中 classifiers 的实际键名
            String classifierKey = null;
            if (lib.has("natives") && lib.get("natives").isJsonObject()) {
                JsonObject natives = lib.getAsJsonObject("natives");
                if (natives.has(osName) && natives.get(osName).isJsonPrimitive()) {
                    classifierKey = natives.get(osName).getAsString()
                            .replace("${arch}", PlatformUtils.is64Bit() ? "64" : "32");
                }
            }
            // 降级：使用约定名 natives-<os>
            if (classifierKey == null) {
                classifierKey = "natives-" + osName;
            }

            if (classifiers.has(classifierKey)) {
                JsonObject cl = classifiers.getAsJsonObject(classifierKey);
                if (cl.has("path")) {
                    return cl.get("path").getAsString();
                }
            }
        }

        // 新格式（1.21.5+/26w 快照）：native 库本身是独立 library，downloads.artifact.path
        // 直接就是 ...-natives-windows.jar（不在 classifiers 下）。此时 name 含 :natives-<os> 段。
        if (lib.has("downloads") && lib.getAsJsonObject("downloads").has("artifact")) {
            JsonObject artifact = lib.getAsJsonObject("downloads").getAsJsonObject("artifact");
            if (artifact.has("path")) {
                String path = artifact.get("path").getAsString();
                String nativeKeyword = "natives-" + osName;
                // 仅当 artifact 路径本身是 natives jar（而非主 jar）时采用，避免误把主 jar 当 native
                if (path.contains(nativeKeyword)) {
                    return path;
                }
            }
        }

        // 降级：从 name 字段推导（group:artifact:version[:classifier]）
        if (lib.has("name")) {
            String name = lib.get("name").getAsString();
            String[] parts = name.split(":");
            if (parts.length >= 3) {
                String group = parts[0].replace('.', '/');
                String artifact = parts[1];
                String version = parts[2];
                if (parts.length >= 4) {
                    return group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + "-" + parts[3] + ".jar";
                }
                return group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + "-natives-" + osName + ".jar";
            }
        }
        return null;
    }

    /**
     * 将类路径列表格式化为平台适配的字符串
     * Windows 为 ";", Linux/macOS 为 ":"
     */
    protected String formatClassPath(List<String> libs) {
        return String.join(PlatformUtils.getClassPathSeparator(), libs);
    }

    // Natives 版本指纹（防止跨版本复用旧 DLL） //

    /** 指纹标记文件名（写在 natives 目录内，记录该目录提取自哪组 native 库） */
    private static final String NATIVES_FINGERPRINT_FILE = ".starlight-natives-fingerprint";

    /**
     * 计算当前 version.json 声明的 native 库「版本指纹」。
     *
     * <p>指纹 = 当前版本所需全部 native jar 的相对路径（相对 libraries 根）按字典序排序后拼接，
     * 再做 SHA-256。LWJGL 版本一变，jar 路径里的版本号段就变，指纹随之变化，
     * 从而阻止复用旧版本提取的 DLL（旧实现只数 DLL 个数，会把旧 LWJGL 复用导致
     * "Incompatible Java and native library versions" 崩溃）。
     *
     * @return 指纹（十六进制），无 native 库时返回 null
     */
    private String nativesFingerprint() {
        if (versionJson == null || !versionJson.has("libraries")) return null;
        List<String> paths = new ArrayList<>();
        JsonArray libraries = versionJson.getAsJsonArray("libraries");
        for (JsonElement libElem : libraries) {
            if (!libElem.isJsonObject()) continue;
            JsonObject lib = libElem.getAsJsonObject();
            if (!CompatibilityHelper.appliesToCurrentEnvironment(
                    lib.has("rules") ? lib.getAsJsonArray("rules") : null)) {
                continue;
            }
            boolean isNative = false;
            if (lib.has("natives")) {
                JsonObject natives = lib.getAsJsonObject("natives");
                if (natives.has(PlatformUtils.getOsRuleName())) isNative = true;
            }
            if (!isNative && lib.has("downloads") && lib.getAsJsonObject("downloads").has("classifiers")) {
                JsonObject classifiers = lib.getAsJsonObject("downloads").getAsJsonObject("classifiers");
                String nativeKeyword = "natives-" + PlatformUtils.getOsRuleName();
                for (String key : classifiers.keySet()) {
                    if (key.startsWith(nativeKeyword)) { isNative = true; break; }
                }
            }
            // 新格式：name 含 :natives-<os> 段（与 processNatives 的判定保持一致，含架构过滤）
            if (!isNative && lib.has("name")) {
                String libName = lib.get("name").getAsString();
                String nativeKeyword = "natives-" + PlatformUtils.getOsRuleName();
                if (libName.contains(":" + nativeKeyword) || libName.contains(":" + nativeKeyword + "-")) {
                    isNative = nativeClassifierMatchesArch(libName);
                }
            }
            if (!isNative) continue;
            String path = resolveNativeLibraryPath(lib);
            if (path != null) paths.add(path);
        }
        if (paths.isEmpty()) return null;
        Collections.sort(paths);
        String joined = String.join("\n", paths);
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(joined.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            // 哈希不可用时退回原文（仍能区分不同版本，只是较长）
            return joined;
        }
    }

    /** 读取 natives 目录内的版本指纹；无标记文件时返回 null */
    private String readNativesFingerprint(Path nativesDir) {
        Path marker = nativesDir.resolve(NATIVES_FINGERPRINT_FILE);
        if (!Files.isRegularFile(marker)) return null;
        try {
            return Files.readString(marker, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    /** 把当前版本指纹写入 natives 目录（提取完成后调用） */
    private void writeNativesFingerprint(Path nativesDir) {
        String fingerprint = nativesFingerprint();
        if (fingerprint == null) return;
        try {
            Files.writeString(nativesDir.resolve(NATIVES_FINGERPRINT_FILE), fingerprint,
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            writeLog("写入 natives 指纹失败: " + e.getMessage(), "WARN");
        }
    }


    // JVM 基础参数 //

    /**
     * 构建通用的 JVM 基础参数
     * 参考 HMCL DefaultLauncher.generateCommandLine() + CommandBuilder
     *
     * 包含:
     *   - 内存设置（-Xms, -Xmx）
     *   - GC 调优（G1GC）
     *   - 编码设置
     *   - Log4j 安全修复
     *   - JIT 编译优化
     *   - macOS 特定参数
     *   - 代理设置
     */
    /** 游戏 JVM 主版本缓存（-1 = 未检测），避免每次构建参数重复启动 java -version 进程 */
    private int gameJavaMajorCache = -1;

    /**
     * 检测游戏 JVM 的主版本号（运行 javaPath -version 解析）。
     *
     * 注意：JVM 参数是传给游戏进程的，必须匹配游戏 JVM 而非启动器自身 JVM
     * （PlatformUtils.getJavaVersion() 返回的是启动器进程的版本）。
     * JDK 9+ 才有 code cache 分段选项（NonNMethodCodeHeapSize 等），Java 8 不识别
     * 会导致 "Unrecognized VM option" 启动失败；--enable-native-access 同理（Java 17+，
     * 随 JEP 412 引入，Java 16 传入会报 Unrecognized option 直接启动失败）。
     */
    protected int getGameJavaMajorVersion() {
        if (gameJavaMajorCache > 0) return gameJavaMajorCache;
        int major = PlatformUtils.getJavaVersion();  // 兜底：检测失败时与启动器一致
        String javaExec = launchInfo.getJavaPath();
        if (javaExec != null && !javaExec.isBlank()) {
            try {
                Process process = new ProcessBuilder(javaExec, "-version")
                        .redirectErrorStream(true).start();
                // 带超时等待：java -version 输出很小（远小于管道缓冲区），先等退出再读不会死锁
                if (process.waitFor(5, TimeUnit.SECONDS)) {
                    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    Matcher m = Pattern.compile("\"([0-9]+)(?:\\.([0-9]+))?").matcher(output);
                    if (m.find()) {
                        int first = Integer.parseInt(m.group(1));
                        major = (first == 1) ? 8 : first;  // "1.8.0_482" -> 8
                    }
                } else {
                    process.destroyForcibly();
                    // 超时不阻断启动，使用兜底值
                }
            } catch (Exception ignored) {
                // 检测失败不阻断启动，回退默认值
            }
        }
        gameJavaMajorCache = major;
        return major;
    }

    protected List<String> buildJvmBase() {
        List<String> base = new ArrayList<>();

        // 内存设置（先校验：min > max 会让 JVM 启动即报错，提前纠正并告警） //
        int minMem = launchInfo.getMinMemoryMB();
        int maxMem = launchInfo.getMaxMemoryMB();
        if (minMem > maxMem) {
            writeLog("检测到最小内存(" + minMem + "M)大于最大内存(" + maxMem + "M)，已交换两者", "WARN");
            int t = minMem; minMem = maxMem; maxMem = t;
        }
        base.add("-Xms" + minMem + "M");
        base.add("-Xmx" + maxMem + "M");

        // 编码设置（参考 HMCL） //
        Charset encoding = StandardCharsets.UTF_8;
        base.add("-Dfile.encoding=" + encoding.name());
        base.add("-Dstdout.encoding=" + encoding.name());
        base.add("-Dstderr.encoding=" + encoding.name());

        // Log4j 安全修复（参考 HMCL） //
        base.add("-Djava.rmi.server.useCodebaseOnly=true");
        base.add("-Dcom.sun.jndi.rmi.object.trustURLCodebase=false");
        base.add("-Dcom.sun.jndi.cosnaming.object.trustURLCodebase=false");
        base.add("-Dlog4j2.formatMsgNoLookups=true");

        // FML 证书跳过 //
        base.add("-Dfml.ignoreInvalidMinecraftCertificates=true");
        base.add("-Dfml.ignorePatchDiscrepancies=true");

        // 解锁实验性 VM 选项（参考 HMCL CommandBuilder.addUnstableDefault） //
        base.add("-XX:+UnlockExperimentalVMOptions");
        base.add("-XX:+UnlockDiagnosticVMOptions");

        // GC 调优（参考 HMCL G1GC 参数） //
        base.add("-XX:+UseG1GC");
        base.add("-XX:-UseAdaptiveSizePolicy");
        base.add("-XX:-OmitStackTraceInFastThrow");
        base.add("-XX:G1MixedGCCountTarget=5");
        base.add("-XX:G1NewSizePercent=20");
        base.add("-XX:G1ReservePercent=20");
        base.add("-XX:MaxGCPauseMillis=50");
        base.add("-XX:G1HeapRegionSize=32m");

        // 大内存优化（64 位 + >4GB 内存时启用，参考 HMCL） //
        if (PlatformUtils.is64Bit() && PlatformUtils.getTotalMemoryBytes() > 4L * 1024 * 1024 * 1024) {
            base.add("-XX:-DontCompileHugeMethods");
            base.add("-XX:MaxNodeLimit=240000");
            base.add("-XX:NodeLimitFudgeFactor=8000");
            base.add("-XX:TieredCompileTaskTimeout=10000");
            base.add("-XX:ReservedCodeCacheSize=400M");
            // 以下为 JDK 9+ 的 code cache 分段选项，Java 8 不识别（Unrecognized VM option），
            // 需按游戏 JVM 版本条件化添加
            if (getGameJavaMajorVersion() >= 9) {
                base.add("-XX:NonNMethodCodeHeapSize=12M");
                base.add("-XX:ProfiledCodeHeapSize=194M");
                base.add("-XX:NmethodSweepActivity=1");
            }
        }

        // JVM 其他参数 //
        base.add("-Djdk.lang.Process.allowAmbiguousCommands=true");
        base.add("-XX:HeapDumpPath=minecraft_launcher.heapdump");

        // Natives / JAR 路径 //
        String nativesPathStr = (nativesPath != null) ? nativesPath.toString() : verDir.toString();
        String jarPathStr = jarPath.toString();

        base.add("-Djava.library.path=" + nativesPathStr);
        base.add("-Djna.tmpdir=" + nativesPathStr);
        base.add("-Dorg.lwjgl.system.SharedLibraryExtractPath=" + nativesPathStr);
        base.add("-Dio.netty.native.workdir=" + nativesPathStr);
        base.add("-Dminecraft.client.jar=" + jarPathStr);

        // 系统代理（参考 HMCL） //
        base.add("-Djava.net.useSystemProxies=true");

        // macOS 特定参数（参考 HMCL） //
        if (PlatformUtils.isMacOS()) {
            base.add("-Xdock:name=Minecraft " + launchInfo.getVersion());
            Path icnsPath = assetsDir.resolve("icons/minecraft.icns");
            if (Files.exists(icnsPath)) {
                base.add("-Xdock:icon=" + icnsPath.toString());
            }
        }

        // 非 Windows 设置 user.home //
        if (!PlatformUtils.isWindows() && launchInfo.getGameDirPath() != null
                && launchInfo.getGameDirPath().getParent() != null) {
            base.add("-Duser.home=" + launchInfo.getGameDirPath().getParent().toString());
        }

        // Java 版本特定参数（基于游戏 JVM 版本，而非启动器自身 JVM） //
        int javaVersion = getGameJavaMajorVersion();
        if (javaVersion == 16) {
            base.add("--illegal-access=permit");
        }

        // 额外 JVM 参数（过滤非选项参数，防止裸参数被 JVM 当作主类） //
        List<String> extraJvm = launchInfo.getJvmArgs();
        if (extraJvm != null) {
            for (String arg : extraJvm) {
                if (arg == null || arg.isBlank()) continue;
                if (arg.startsWith("-")) {
                    base.add(arg);
                } else {
                    writeLog("忽略非 JVM 选项参数: " + arg, "WARN");
                }
            }
        }

        // JNA native access (Java 17+, 消除 restricted method 警告) //
        // 基于游戏 JVM 版本：--enable-native-access 随 JEP 412 在 Java 17 引入，
        // Java 8/16 不识别该选项，误传会报 "Unrecognized option" 直接启动失败
        if (getGameJavaMajorVersion() >= 17) {
            base.add("--enable-native-access=ALL-UNNAMED");
        }

        return base;
    }

    // 游戏参数 //

    /**
     * 构建标准游戏参数（适用于 Vanilla / Fabric / Forge Legacy）
     * 参考 HMCL 的标准参数约定
     */
    protected List<String> buildGameArgs(String effectiveGameDir) {
        List<String> args = new ArrayList<>();
        args.add("--username");    args.add(launchInfo.getUserName());
        args.add("--version");     args.add(launchInfo.getVersion());
        args.add("--gameDir");     args.add(effectiveGameDir);
        args.add("--assetsDir");   args.add(assetsDir.toString());
        args.add("--assetIndex");  args.add(assetIndex);
        args.add("--uuid");        args.add(launchInfo.getUuid());
        args.add("--accessToken"); args.add(launchInfo.getAccessToken());
        args.add("--userType");    args.add(launchInfo.getUserType());
        args.add("--versionType"); args.add("StarlightLauncher");

        // HMCL 参考：has_custom_resolution feature
        if (launchInfo.getWindowWidth() > 0 && launchInfo.getWindowHeight() > 0) {
            args.add("--width");       args.add(String.valueOf(launchInfo.getWindowWidth()));
            args.add("--height");      args.add(String.valueOf(launchInfo.getWindowHeight()));
        }

        if (launchInfo.isFullscreen()) args.add("--fullscreen");

        // 额外游戏参数
        List<String> extraGame = launchInfo.getGameArgs();
        if (extraGame != null) {
            args.addAll(extraGame);
        }

        return args;
    }

    /**
     * 从 version.json 的 arguments.game 字段构建参数（Forge Modern 专用）
     * 支持占位符替换 ${...} 和规则 (rules) 检查
     *
     * 自动过滤的黑名单参数:
     *   - --demo: Minecraft 演示模式参数，不应传入正式版游戏
     *   - --Dream（大小写不敏感）: Forge 安装器写入的无效参数，不传递给游戏进程
     *   - 占位符解析后为空字符串的参数
     */
    protected List<String> buildGameArgsFromJson(String effectiveGameDir) {
        List<String> args = new ArrayList<>();

        // 构建 features（参考 HMCL getFeatures()）
        Map<String, Boolean> features = new HashMap<>();
        features.put("has_custom_resolution",
                launchInfo.getWindowWidth() > 0 && launchInfo.getWindowHeight() > 0);

        // 标记：最近一个被过滤的黑名单选项（用于连带跳过其后继的值参数）
        String lastFilteredOption = null;

        if (versionJson.has("arguments") && versionJson.get("arguments").isJsonObject()) {
            JsonObject argsObj = versionJson.getAsJsonObject("arguments");
            if (argsObj.has("game")) {
                JsonArray gameArgs = argsObj.getAsJsonArray("game");
                for (JsonElement elem : gameArgs) {
                    if (elem.isJsonPrimitive()) {
                        String str = elem.getAsString();

                        // 1. 黑名单选项的值区跳过：持续跳过其所有后续值，
                        // 直到遇到下一个 "--" 开头的选项为止
                        if (lastFilteredOption != null) {
                            if (!str.startsWith("--")) {
                                continue;
                            }
                            lastFilteredOption = null;
                        }

                        // 2. 跳过黑名单参数 //
                        if (isBlacklistedArg(str)) {
                            lastFilteredOption = str;
                            continue;
                        }

                        // 使用新的占位符替换方式
                        String resolved = (placeholderKeys != null)
                                ? replacePlaceholders(str)
                                : replacePlaceholders(str, effectiveGameDir);

                        // 3. 跳过解析后仍包含未识别占位符 ${} 的参数 //
                        if (resolved != null && resolved.contains("${")) {
                            if (!str.startsWith("--") && !args.isEmpty()
                                && args.get(args.size() - 1).startsWith("--")) {
                                args.remove(args.size() - 1);
                            }
                            continue;
                        }

                        // 4. 跳过空值 //
                        if (resolved == null || resolved.isEmpty()) continue;

                        args.add(resolved);
                    } else if (elem.isJsonObject()) {
                        // 处理 RuledArgument（带规则的参数，参考 HMCL RuledArgument + CompatibilityRule）
                        JsonObject obj = elem.getAsJsonObject();
                        boolean allow = rulesAllow(obj.has("rules") ? obj.getAsJsonArray("rules") : null, features);

                        if (allow && obj.has("value")) {
                            if (obj.get("value").isJsonPrimitive()) {
                                String val = obj.get("value").getAsString();
                                if (isBlacklistedArg(val)) continue;
                                String resolved = (placeholderKeys != null)
                                        ? replacePlaceholders(val)
                                        : replacePlaceholders(val, effectiveGameDir);
                                if (resolved != null && !resolved.isEmpty()
                                    && !resolved.contains("${")) {
                                    args.add(resolved);
                                }
                            } else if (obj.get("value").isJsonArray()) {
                                for (JsonElement v : obj.getAsJsonArray("value")) {
                                    String val = v.getAsString();
                                    if (isBlacklistedArg(val)) continue;
                                    String resolved = (placeholderKeys != null)
                                            ? replacePlaceholders(val)
                                            : replacePlaceholders(val, effectiveGameDir);
                                    if (resolved != null && !resolved.isEmpty()
                                        && !resolved.contains("${")) {
                                        args.add(resolved);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            args.addAll(buildGameArgs(effectiveGameDir));
        }

        // 全屏参数：version JSON 中不包含 --fullscreen，需按启动器设置附加
        if (launchInfo.isFullscreen()) args.add("--fullscreen");

        // 额外游戏参数
        List<String> extraGame = launchInfo.getGameArgs();
        if (extraGame != null) {
            args.addAll(extraGame);
        }

        // 必填参数兜底：畸形 JSON（arguments.game 为空数组）会一个参数都产不出来 //
        ensureRequiredGameArgs(args, effectiveGameDir);

        return args;
    }

    /**
     * 评估 Mojang 规则数组（rules）在当前环境是否放行。
     *
     * <p>Mojang 规范：默认拒绝；逐条匹配，<b>最后一条匹配的规则</b>决定结果。
     * 一条规则「匹配」= 其 OS 约束（name/arch）与 features 约束同时满足。
     * 与 HMCL {@code CompatibilityRule.appliesToCurrentEnvironment} 语义一致。
     *
     * @param rules    规则数组，可为 null（无规则时默认放行）
     * @param features 特性表（has_custom_resolution 等）
     * @return 是否放行
     */
    protected boolean rulesAllow(JsonArray rules, Map<String, Boolean> features) {
        if (rules == null || rules.size() == 0) return true;
        boolean allow = false;
        String currentOs = PlatformUtils.getOsRuleName();
        String currentArch = PlatformUtils.getArch();
        for (JsonElement ruleElem : rules) {
            JsonObject rule = ruleElem.getAsJsonObject();
            String action = rule.has("action") ? rule.get("action").getAsString() : "";
            boolean matches = true;

            if (rule.has("os") && rule.get("os").isJsonObject()) {
                JsonObject os = rule.getAsJsonObject("os");
                if (os.has("name") && !os.get("name").getAsString().equalsIgnoreCase(currentOs)) {
                    matches = false;
                }
                if (matches && os.has("arch")) {
                    String requiredArch = os.get("arch").getAsString();
                    matches = currentArch.contains(requiredArch.toLowerCase(Locale.ROOT));
                }
            }

            if (matches && rule.has("features") && rule.get("features").isJsonObject()) {
                JsonObject ruleFeatures = rule.getAsJsonObject("features");
                for (String key : ruleFeatures.keySet()) {
                    Boolean expected = ruleFeatures.get(key).getAsBoolean();
                    Boolean actual = features == null ? null : features.get(key);
                    if (!Objects.equals(expected, actual)) {
                        matches = false;
                        break;
                    }
                }
            }

            if (matches) {
                allow = "allow".equals(action);
            }
        }
        return allow;
    }

    /**
     * 从 version.json 的 {@code arguments.jvm} 字段构建 JVM 参数（Forge/NeoForge 1.17+）。
     *
     * <p>Forge 1.17+ 的 version.json 自带 {@code arguments.jvm}，其中包含本版本必需的
     * {@code --add-opens} / {@code --add-exports} / {@code -Dxxx} / {@code -p ${library_directory}/...}。
     * 此前这些参数全部被硬编码在 ForgeModernLauncher，换一个 Forge 小版本就可能漏参导致
     * {@code BootstrapLauncher} 起不来。这里像 game 参数一样解析 jvm 参数（含规则与占位符替换），
     * 解析结果由调用方追加到 JVM 参数区。
     *
     * @param classpath 已构建好的类路径字符串（用于替换 {@code ${classpath}} 占位符），可为 null
     * @return 解析出的 JVM 参数列表；version.json 无 arguments.jvm 时为空列表
     */
    protected List<String> buildJvmArgsFromJson(String classpath) {
        List<String> args = new ArrayList<>();
        if (versionJson == null || !versionJson.has("arguments")
                || !versionJson.get("arguments").isJsonObject()) {
            return args;
        }
        JsonObject argsObj = versionJson.getAsJsonObject("arguments");
        if (!argsObj.has("jvm") || !argsObj.get("jvm").isJsonArray()) {
            return args;
        }
        // features 表（jvm 规则同样可能引用 has_custom_resolution）
        Map<String, Boolean> features = new HashMap<>();
        features.put("has_custom_resolution",
                launchInfo.getWindowWidth() > 0 && launchInfo.getWindowHeight() > 0);

        for (JsonElement elem : argsObj.getAsJsonArray("jvm")) {
            if (elem.isJsonPrimitive()) {
                String str = elem.getAsString();
                String resolved = resolveJvmArg(str, classpath);
                if (resolved != null) args.add(resolved);
            } else if (elem.isJsonObject()) {
                JsonObject obj = elem.getAsJsonObject();
                if (!rulesAllow(obj.has("rules") ? obj.getAsJsonArray("rules") : null, features)) {
                    continue;
                }
                if (!obj.has("value")) continue;
                if (obj.get("value").isJsonPrimitive()) {
                    String resolved = resolveJvmArg(obj.get("value").getAsString(), classpath);
                    if (resolved != null) args.add(resolved);
                } else if (obj.get("value").isJsonArray()) {
                    for (JsonElement v : obj.getAsJsonArray("value")) {
                        String resolved = resolveJvmArg(v.getAsString(), classpath);
                        if (resolved != null) args.add(resolved);
                    }
                }
            }
        }
        return args;
    }

    /** 替换单个 JVM 参数占位符（含 ${classpath}）；解析后为空或仍含 ${} 时返回 null（调用方跳过） */
    private String resolveJvmArg(String raw, String classpath) {
        String resolved = (placeholderKeys != null)
                ? replacePlaceholders(raw)
                : replacePlaceholders(raw, launchInfo.resolveEffectiveGameDir().toString());
        if (classpath != null) {
            resolved = resolved.replace("${classpath}", classpath);
        }
        if (resolved == null || resolved.isEmpty() || resolved.contains("${")) {
            return null;
        }
        return resolved;
    }

    /**
     * 必填游戏参数的兜底补齐。
     *
     * <p>判据是「参数够不够」，而不是「arguments 对象在不在」：加载器的 profile 常自带一个
     * game 为空数组的 arguments，按对象是否存在兜底会让命令里既没有 --gameDir 也没有账号
     * 参数，游戏退回工作目录读取全局 mods，版本隔离静默失效。
     *
     * <p>只补缺失项，已存在的 flag 不重复追加（重复参数由游戏侧按后者覆盖）。
     */
    private void ensureRequiredGameArgs(List<String> args, String effectiveGameDir) {
        List<String> missing = new ArrayList<>();
        addIfMissing(args, missing, "--username", launchInfo.getUserName());
        addIfMissing(args, missing, "--version", launchInfo.getVersion());
        addIfMissing(args, missing, "--gameDir", effectiveGameDir);
        addIfMissing(args, missing, "--assetsDir", assetsDir.toString());
        addIfMissing(args, missing, "--assetIndex", assetIndex);
        addIfMissing(args, missing, "--uuid", launchInfo.getUuid());
        addIfMissing(args, missing, "--accessToken", launchInfo.getAccessToken());
        addIfMissing(args, missing, "--userType", launchInfo.getUserType());
        addIfMissing(args, missing, "--versionType", "StarlightLauncher");

        if (!missing.isEmpty()) {
            writeLog("版本 JSON 缺少游戏参数，已用启动器默认值补齐: " + String.join("、", missing)
                    + "；该版本可能由旧版安装器生成，建议重新安装该版本", "WARN");
        }
    }

    /** 追加缺失的 flag 及其取值；该 flag 已存在时不动，缺失项记入 missing 供日志汇总 */
    private static void addIfMissing(List<String> args, List<String> missing, String flag, String value) {
        if (args.contains(flag)) return;
        args.add(flag);
        args.add(value == null ? "" : value);
        missing.add(flag);
    }

    /**
     * 判断字符串是否为黑名单参数（大小写不敏感）
     *
     * 过滤的参数:
     *   - --demo: Minecraft 演示模式参数
     *   - --Dream / --dream: Forge 安装器写入的无效参数
     *   - --quickPlayPath / --quickPlaySingleplayer / --quickPlayMultiplayer / --quickPlayRealms:
     *     自动进入存档的参数，因占位符无法解析而被移除
     */
    private static boolean isBlacklistedArg(String arg) {
        if (arg == null) return false;
        String lower = arg.toLowerCase();
        return lower.equals("--demo")
            || lower.equals("--dream")
            || lower.equals("--quickplaypath")
            || lower.equals("--quickplaysingleplayer")
            || lower.equals("--quickplaymultiplayer")
            || lower.equals("--quickplayrealms");
    }

    /**
     * 替换 Minecraft 游戏参数占位符
     */
    protected String replacePlaceholders(String str, String effectiveGameDir) {
        return str
            .replace("${version_name}", launchInfo.getVersion())
            .replace("${assets_root}", assetsDir.toString())
            .replace("${assets_index_name}", assetIndex)
            .replace("${auth_access_token}", launchInfo.getAccessToken())
            .replace("${auth_uuid}", launchInfo.getUuid())
            .replace("${auth_player_name}", launchInfo.getUserName())
            .replace("${user_type}", launchInfo.getUserType())
            .replace("${version_type}", "StarlightLauncher")
            .replace("${game_directory}", effectiveGameDir)
            .replace("${library_directory}", libsRoot.toString())
            .replace("${natives_directory}", nativesPath != null ? nativesPath.toString() : "")
            .replace("${launcher_name}", "Universal")
            .replace("${launcher_version}", "1.0")
            .replace("${resolution_width}", String.valueOf(launchInfo.getWindowWidth()))
            .replace("${resolution_height}", String.valueOf(launchInfo.getWindowHeight()));
    }

    // 进程启动与监控 //

    /**
     * 进程工作目录：与 --gameDir 取同一个值，隔离时是版本目录，否则是游戏根目录。
     *
     * <p>只改 --gameDir 不改工作目录时，游戏自身在参数缺失场景下的回退、以及任何按相对
     * 路径写盘的代码都会落回全局目录，隔离会静默失效。
     */
    protected Path resolveWorkingDir() {
        Path effective = launchInfo.resolveEffectiveGameDir();
        return effective != null ? effective : gameDirPath;
    }

    /** 从启动参数中取 --gameDir 的取值，供日志核对；未注入时返回「缺失」 */
    private static String gameDirArgOf(List<String> arguments) {
        int index = arguments.indexOf("--gameDir");
        if (index < 0 || index + 1 >= arguments.size()) return "缺失";
        return arguments.get(index + 1);
    }

    /**
     * 启动游戏进程并持续监控输出
     * 参考 HMCL DefaultLauncher.launch() + StreamPump + ExitWaiter
     *
     * @param javaPath  Java 可执行文件路径
     * @param arguments 完整启动参数列表
     * @param envVars   附加环境变量
     * @param startTime 启动计时起点
     * @return 进程退出码
     */
    protected int startProcessWithMonitoring(
            String javaPath, List<String> arguments,
            Map<String, String> envVars, long startTime)
            throws IOException, InterruptedException {

        // 构建完整命令
        List<String> fullCommand = new ArrayList<>();
        fullCommand.add(javaPath);
        fullCommand.addAll(arguments);

        Path workingDir = resolveWorkingDir();

        ProcessBuilder pb = new ProcessBuilder(fullCommand);
        pb.directory(workingDir.toFile());
        pb.environment().putAll(envVars);
        pb.redirectErrorStream(false);

        // 完整启动命令（控制台 + 日志）：调试皮肤/披风注入等启动问题时可直接整条复现
        String fullCommandLine = String.join(" ", fullCommand);
        System.out.println(colorize("INFO", String.format("[%s] [INFO] [完整启动命令] %s",
                LOG_TIMESTAMP.format(LocalDateTime.now()), fullCommandLine)));
        writeLog("完整启动命令: " + fullCommandLine, "INFO");
        // 隔离是否真的生效，看这三项：工作目录与 --gameDir 应同值且指向版本目录
        writeLog("工作目录: " + workingDir
                + " | 版本隔离: " + (launchInfo.isVersionIsolation() ? "是" : "否")
                + " | --gameDir: " + gameDirArgOf(arguments), "INFO");

        Process process = pb.start();
        this.gameProcess = process;
        this.gamePid = process.pid();
        long pid = process.pid();
        writeLog("游戏进程已启动 (PID: " + pid + ")，开始监控...", "INFO");

        // 触发进程启动回调（供外部捕获进程引用用于取消启动等）
        if (onProcessStarted != null) {
            try {
                onProcessStarted.accept(process);
            } catch (Exception e) {
                writeLog("进程启动回调异常: " + e.getMessage(), "WARN");
            }
        }

        // 进程微调（设置 → 高级设置）：自定义游戏窗口标题 + 进程优先级 //
        // 放在这里而不是各启动入口：新版 UI 与插件 API 两条启动路径都经过本方法，不会漏
        try {
            // 标题模板支持 {version} / {username} 变量，在启动时用本次的实际值展开
            String title = GameProcessTweaks.expandTitle(launchInfo.getWindowTitle(),
                    launchInfo.getVersion(), launchInfo.getUserName());
            GameProcessTweaks.applyOnProcessStarted(pid, title, launchInfo.getProcessPriority());
        } catch (Throwable t) {
            writeLog("应用进程微调失败: " + t.getMessage(), "WARN");
        }

        // 启动流读取线程（参考 HMCL StreamPump） //
        ExecutorService executor = Executors.newFixedThreadPool(3);

        // 标准输出流泵
        executor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (Thread.currentThread().isInterrupted()) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    writeLogToFile("[STDOUT] " + line, "INFO");
                    appendLogBuffer(stdoutBuffer, line);
                }
            } catch (IOException ignored) {}
        });

        // 错误输出流泵
        executor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (Thread.currentThread().isInterrupted()) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    writeLogToFile("[STDERR] " + line, "WARN");
                    // 同时保留到内存缓冲区（类似 PCL2 实时捕获）
                    appendLogBuffer(stderrBuffer, line);
                }
            } catch (IOException ignored) {}
        });

        // 监控线程（每 10 秒检查进程状态，仅在状态变化时写日志，避免长时间运行撑爆日志）
        ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
        final java.util.concurrent.atomic.AtomicBoolean lastAlive = new java.util.concurrent.atomic.AtomicBoolean(true);
        monitor.scheduleAtFixedRate(() -> {
            try {
                boolean alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
                if (alive != lastAlive.getAndSet(alive)) {
                    writeLogToFile("[MONITOR] PID " + pid + (alive ? " - 运行中" : " - 已退出"), "INFO");
                }
            } catch (Exception ignored) {}
        }, 10, 10, TimeUnit.SECONDS);

        int exitCode = process.waitFor();

        // 优雅关闭
        monitor.shutdown();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
            if (!monitor.awaitTermination(5, TimeUnit.SECONDS)) {
                monitor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            monitor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // 将 JVM stderr 写入错误日志文件（供崩溃诊断使用）
        synchronized (stderrBuffer) {
            if (stderrBuffer.length() > 0 && errorLogWriter != null) {
                try {
                    errorLogWriter.write(stderrBuffer.toString());
                    errorLogWriter.flush();
                } catch (IOException ignored) {}
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        String durStr = String.format("%02d:%02d:%02d",
            TimeUnit.MILLISECONDS.toHours(duration),
            TimeUnit.MILLISECONDS.toMinutes(duration) % 60,
            TimeUnit.MILLISECONDS.toSeconds(duration) % 60);

        // 退出码分析（参考 HMCL ExitWaiter） //
        String exitMessage = analyzeExitCode(exitCode, durStr);
        writeLog(exitMessage, exitCode == 0 ? "INFO" : "WARN");

        return exitCode;
    }

    /**
     * 分析进程退出码，返回描述信息
     * 参考 HMCL ExitWaiter 的退出类型判断逻辑
     */
    protected String analyzeExitCode(int exitCode, String duration) {
        if (exitCode == 0) {
            return "游戏已退出，运行时长: " + duration + "，退出码: 0 (正常)";
        }

        if (exitCode == 1) {
            return "游戏异常退出 (代码: " + exitCode + ")，运行时长: " + duration
                + " - 可能是 JVM 启动失败或游戏崩溃";
        }

        if (exitCode == 137 && PlatformUtils.isUnixLike()) {
            return "游戏被 SIGKILL 终止 (代码: " + exitCode + ")，运行时长: " + duration
                + " - 可能因内存不足被系统杀死";
        }

        return "游戏异常退出 (代码: " + exitCode + ")，运行时长: " + duration;
    }

    /**
     * 简化版进程启动（自动处理环境变量和计时）
     */
    protected int startProcessWithMonitoring(String javaPath, List<String> arguments)
            throws IOException, InterruptedException {
        return startProcessWithMonitoring(javaPath, arguments, buildDefaultEnv(), System.currentTimeMillis());
    }

    /**
     * 构建默认环境变量
     * 参考 HMCL DefaultLauncher.getEnvVars()
     */
    protected Map<String, String> buildDefaultEnv() {
        Map<String, String> env = new HashMap<>();
        env.putAll(PlatformUtils.getPlatformEnvVars(
            launchInfo.getGameDirPath() != null && launchInfo.getGameDirPath().getParent() != null
                ? launchInfo.getGameDirPath().getParent().toString()
                : ""));
        env.put("INST_NAME", launchInfo.getVersion());
        env.put("INST_ID", launchInfo.getVersion());
        env.put("INST_DIR", verDir.toString());
        env.put("INST_MC_DIR", launchInfo.getGameDir());
        env.put("INST_JAVA", launchInfo.getJavaPath());

        String loaderType = launchInfo.getLoaderType();
        if (LoaderDetector.isFabricLike(loaderType)) env.put("INST_FABRIC", "1");
        if (LoaderDetector.isForge(loaderType))     env.put("INST_FORGE", "1");
        if (LoaderDetector.NEOFORGE.equals(loaderType)) env.put("INST_NEOFORGE", "1");
        if (LoaderDetector.QUILT.equals(loaderType))    env.put("INST_QUILT", "1");

        return env;
    }

    // Mods 目录处理 //

    /**
     * 获取有效的 mods 目录路径（考虑版本隔离）
     */
    protected Path resolveModsDir() {
        Path versionModsDir = verDir.resolve("mods");
        Path globalModsDir = gameDirPath.resolve("mods");

        if (launchInfo.isVersionIsolation()) {
            if (Files.exists(versionModsDir)) {
                writeLog("版本隔离已启用，使用版本专属 mods 目录: " + versionModsDir, "INFO");
                return versionModsDir;
            }
            try {
                Files.createDirectories(versionModsDir);
                writeLog("版本隔离已启用，已创建 mods 目录: " + versionModsDir, "INFO");
                return versionModsDir;
            } catch (IOException e) {
                writeLog("创建版本 mods 目录失败，回退到全局目录", "WARN");
            }
        }
        return globalModsDir;
    }

    // 日志工具 //

    /**
     * 写入带时间戳和级别的日志（控制台带 ANSI 颜色，文件纯文本）
     */
    protected void writeLog(String message, String level) {
        String timestamp = LOG_TIMESTAMP.format(LocalDateTime.now());
        String entry = String.format("[%s] [%s] %s", timestamp, level, message);
        System.out.println(colorize(level, entry));
        if (logWriter != null) {
            // 日志 Writer 被 stdout 泵 / stderr 泵 / 监控线程 / 主线程并发访问，
            // 必须加锁避免行交错与 BufferedWriter 的并发写崩溃
            synchronized (logWriter) {
                try {
                    logWriter.write(entry);
                    logWriter.newLine();
                    logWriter.flush();
                } catch (IOException e) {
                    // 日志写入失败时降级到 stderr 输出，避免信息完全丢失
                    System.err.println("[日志写入失败] " + e.getMessage() + " | 原日志: " + entry);
                }
            }
        }
    }

    /**
     * 按级别给控制台行着色：TRACE 青 / DEBUG 蓝 / INFO、SUCCESS 绿 / WARN 黄 / ERROR 红 / FATAL 红底加粗
     */
    private static String colorize(String level, String entry) {
        String color = switch (level == null ? "" : level.toUpperCase()) {
            case "TRACE" -> "\u001B[36m";
            case "DEBUG" -> "\u001B[34m";
            case "INFO", "SUCCESS" -> "\u001B[32m";
            case "WARN" -> "\u001B[33m";
            case "ERROR" -> "\u001B[31m";
            case "FATAL", "CRITICAL" -> "\u001B[1;41m";
            default -> "";
        };
        if (color.isEmpty()) return entry;
        return color + entry + "\u001B[0m";
    }

    /**
     * 写入纯文本日志（默认 INFO 级别）
     */
    protected void writeLogToFile(String message) {
        writeLogToFile(message, "INFO");
    }

    /**
     * 写入纯文本日志，带指定级别前缀
     */
    protected void writeLogToFile(String message, String level) {
        String timestamp = LOG_TIMESTAMP.format(LocalDateTime.now());
        String entry = String.format("[%s] [%s] %s", timestamp, level, message);
        if (logWriter != null) {
            synchronized (logWriter) {
                try {
                    logWriter.write(entry);
                    logWriter.newLine();
                    logWriter.flush();
                } catch (IOException e) {
                    System.err.println("[日志写入失败] " + e.getMessage() + " | 原日志: " + entry);
                }
            }
        }
    }

    /**
     * 记录异常堆栈到错误日志
     */
    protected void writeErrorLog(String context, Exception e) {
        if (errorLogWriter != null) {
            synchronized (errorLogWriter) {
                try {
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    e.printStackTrace(pw);
                    errorLogWriter.write(String.format("[%s] [ERROR] %s%n", LOG_TIMESTAMP.format(LocalDateTime.now()), context));
                    errorLogWriter.write(sw.toString());
                    errorLogWriter.newLine();
                    errorLogWriter.flush();
                } catch (IOException ignored) {}
            }
        }
    }

    /**
     * 获取 JVM 进程的 stderr 输出内容（用于崩溃诊断）
     * 类似 PCL2/HMCL 实时捕获错误输出的机制，无需等待文件写入即可获取
     *
     * @return 输出内容，尚无输出时返回空字符串（调用方无需判空）
     */
    public String getStderrOutput() {
        synchronized (stderrBuffer) {
            return stderrBuffer.toString();
        }
    }

    /**
     * 获取游戏进程对象（如未启动返回 null）
     * 类似 HMCL ManagedProcess.getProcess() 机制
     */
    public Process getGameProcess() {
        return gameProcess;
    }

    /**
     * 获取游戏进程 PID（如未启动返回 -1）
     * 类似 HMCL ManagedProcess 的 PID 获取机制
     */
    public long getGamePid() {
        return gamePid;
    }

    /**
     * 获取游戏进程的 stdout 输出内容（用于实时日志进度检测）
     * 类似 PCL2 ModWatcher LogReceived 机制
     *
     * @return 输出内容，尚无输出时返回空字符串（调用方无需判空）
     */
    public String getGameStdout() {
        synchronized (stdoutBuffer) {
            return stdoutBuffer.toString();
        }
    }

    /**
     * 追加一行到日志内存缓冲区，带容量上限保护。
     *
     * <p>游戏长时间挂机时 stdout/stderr 会持续增长，无上限会吃掉几十 MB 内存；
     * 超过 {@link #MAX_LOG_BUFFER_CHARS} 时丢弃最早的整块内容，保留最近输出（环形缓冲语义）。
     */
    private static void appendLogBuffer(StringBuilder buffer, String line) {
        synchronized (buffer) {
            if (buffer.length() + line.length() + 1 > MAX_LOG_BUFFER_CHARS) {
                int drop = buffer.length() / 2;
                if (drop > 0) buffer.delete(0, drop);
            }
            buffer.append(line).append('\n');
        }
    }

    /**
     * 关闭日志 Writer
     */
    public void closeLog() {
        try {
            if (logWriter != null) logWriter.close();
            if (errorLogWriter != null) errorLogWriter.close();
        } catch (IOException ignored) {}
    }

    // 文件系统工具 //

    /**
     * 递归删除目录及所有子文件
     */
    protected void deleteRecursively(Path path) throws IOException {
        if (Files.exists(path)) {
            try (java.util.stream.Stream<Path> walk = Files.walk(path)) {
                walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.delete(p); } catch (IOException e) {
                            // 删除失败（DLL 被占用等）至少留一条日志，方便定位后续 copy 报错的根因
                            writeLog("删除失败: " + p + " (" + e.getMessage() + ")", "WARN");
                        }
                    });
            }
        }
    }

    // 启动前后命令 //

    /**
     * 执行启动前系统命令（阻塞等待完成）
     */
    protected void executePreLaunchCommand() {
        String cmd = launchInfo.getPreLaunchCommand();
        if (cmd != null && !cmd.isEmpty()) {
            writeLog("执行启动前命令: " + cmd, "INFO");
            Process p = null;
            try {
                p = PlatformUtils.createShellProcessBuilder(cmd).start();
                boolean finished = p.waitFor(30, TimeUnit.SECONDS);
                if (!finished) {
                    // 超时未退出：强杀残留进程，避免「启动前命令没跑完就启动游戏」违背语义
                    p.destroyForcibly();
                    writeLog("启动前命令 30 秒未完成，已强制终止并继续启动", "WARN");
                }
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                if (p != null) p.destroyForcibly();
                writeLog("启动前命令执行失败: " + e.getMessage(), "WARN");
            }
        }
    }

    /**
     * 执行退出后系统命令（非阻塞触发）
     */
    protected void executePostExitCommand() {
        String cmd = launchInfo.getPostExitCommand();
        if (cmd != null && !cmd.isEmpty()) {
            writeLog("执行退出后命令: " + cmd, "INFO");
            try {
                PlatformUtils.createShellProcessBuilder(cmd).start();
            } catch (IOException e) {
                writeLog("退出后命令执行失败: " + e.getMessage(), "WARN");
            }
        }
    }
}
