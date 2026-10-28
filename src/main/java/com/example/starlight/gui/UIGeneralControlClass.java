package com.example.starlight.gui;


import com.example.starlight.util.I18N;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import com.example.starlight.auth.AccountManager;
import com.example.starlight.auth.AccountManager.Account;
import com.example.starlight.auth.AuthlibInjectorSupport;
import com.example.starlight.auth.ExternalLoginAuth;
import com.example.starlight.auth.offline.LocalYggdrasilServer;
import com.example.starlight.auth.offline.LocalCapeProxy;
import com.example.starlight.auth.offline.OfflineSkinManager;
import com.example.starlight.auth.offline.Skin;
import com.example.starlight.config.AppDirs;
import com.example.starlight.config.Endpoints;
import com.example.starlight.config.GameDirManager;
import com.example.starlight.config.SelectedVersion;
import com.example.starlight.config.VersionConfigManager;
import com.example.starlight.crash.CrashReportParser;
import com.example.starlight.download.task.AssetDownloader;
import com.example.starlight.download.DownloadEngineRouter;
import com.example.starlight.download.DownloadSettings;
import com.example.starlight.download.task.GameResourceCompleter;
import com.example.starlight.download.task.LibraryDownloader;
import com.example.starlight.gamebat.MinecraftLauncherBuilder;
import com.example.starlight.service.GameInstanceRegistry;
import com.example.starlight.service.GameLauncherService;
import com.example.starlight.service.ParallelLaunchManager;
import com.example.starlight.service.SilentLoginManager;
import com.example.starlight.download.task.JavaInstall;
import com.example.starlight.lang.GameLanguage;
import com.example.starlight.lang.GameOptions;
import com.example.starlight.listjava.FindAllJavaWindows;
import com.example.starlight.listjava.JavaCacheManager;
import com.example.starlight.listsaves.listsaves;
import com.example.starlight.log.QRcode;
import com.example.starlight.log.sendlogs;
import com.example.starlight.loginmicrosoft.MinecraftAuthLauncherDeviceCode;
import com.example.starlight.longcatapi.LongCatChat;
import com.example.starlight.main.NativesManager;
import com.example.starlight.download.DownloadService;
import com.example.starlight.model.CallbackInterfaces;
import com.example.starlight.newui.ui.VersionIconKit;
import com.example.starlight.saves.SaveBackupRestore;
import com.example.starlight.util.LaunchLog;
import com.example.starlight.util.LegacyLaunchArgs;
import com.example.starlight.util.ProxyConfig;
import com.example.starlight.util.VanillaVersion;
import com.example.starlight.util.VersionUtils;
import com.example.starlight.download.task.LoaderInstallEngine;
import com.example.starlight.download.task.VersionManifest;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.zxing.WriterException;
import com.example.starlight.startmc.LaunchInfo;
import com.example.starlight.startmc.launcher.BaseLauncher;
import com.example.starlight.startmc.launcher.FabricLauncher;
import com.example.starlight.startmc.launcher.ForgeLegacyLauncher;
import com.example.starlight.startmc.launcher.ForgeModernLauncher;
import com.example.starlight.startmc.launcher.LoaderDetector;
import com.example.starlight.startmc.launcher.VanillaLauncher;
import javafx.event.ActionEvent;

public final class UIGeneralControlClass {

    /** 日志：启动路径的加载器判定 / AutoJava 结果 / 类路径去重都记在这里，便于排查启动异常 */
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(UIGeneralControlClass.class);

    // 0.  常量 & 内部类定义 //

    /** 配置目录名（仅历史遗留常量；实际路径一律走 {@link AppDirs}） */
    @Deprecated
    private static final String CONFIG_DIR = "Starlight-Launcher";
    /** 配置文件名（仅历史遗留常量；实际路径一律走 {@link AppDirs#starlightIni()}） */
    @Deprecated
    private static final String CONFIG_FILE = CONFIG_DIR + "/starlight.ini";
    /** 日志目录（仍留在工作目录，不随用户数据迁移） */
    private static final String LOG_DIR = "logs";
    /** 默认服务器 URL */
    private static final String DEFAULT_SERVER_URL = Endpoints.logUploadUrl();

    /**
     * 获取有效的服务器 URL（优先从配置读取，否则使用默认值）
     */
    public static String getEffectiveServerUrl() {
        Map<String, String> cfg = readConfig();
        String url = cfg.getOrDefault("ServerUrl", "").trim();
        return url.isEmpty() ? DEFAULT_SERVER_URL : url;
    }

    /**
     * 设置自定义服务器 URL
     */
    public static ActionResult setServerUrl(String url) {
        Map<String, String> cfg = readConfig();
        cfg.put("ServerUrl", url);
        return saveConfig(cfg);
    }

    /** 线程池：用于异步任务 */
    public static final ExecutorService ASYNC_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ui-control-worker");
        t.setDaemon(true);
        return t;
    });

    static {
        // 注册 JVM 关闭钩子，确保线程池在应用退出时正确清理
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            ASYNC_POOL.shutdownNow();
            try {
                if (!ASYNC_POOL.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    // 强制关闭
                }
            } catch (InterruptedException e) {
                ASYNC_POOL.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }, "async-pool-shutdown"));
    }

    // 通用回调接口 //

    /** 通用进度回调（0-100） */
    public interface ProgressCallback { void onProgress(int percent, String message); }

    /** 通用结果回调 */
    public interface ResultCallback<T> {
        void onSuccess(T result);
        void onError(String error);

        default void CopyAddress(ActionEvent actionEvent1) {}
    }

    /** 启动取消标志 */
    /**
     * 启动取消标志位（「取消启动」按钮置位）。除在启动各阶段之间检查外，还直接传给
     * {@code ParallelLaunchManager.prepare} / {@code GameResourceCompleter.completeAll}，
     * 让正在进行的资源下载随取消立即停止（此前取消只挡后续阶段，下载会继续跑完）。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean launchCancelled =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // 统一结果包装类 //

    /** 通用操作结果 */
    public static class ActionResult {
        public final boolean success;
        public final String message;
        public final Object data;
        public ActionResult(boolean success, String message) { this(success, message, null); }
        public ActionResult(boolean success, String message, Object data) {
            this.success = success; this.message = message; this.data = data;
        }
        public static ActionResult ok(String msg) { return new ActionResult(true, msg); }
        public static ActionResult ok(String msg, Object data) { return new ActionResult(true, msg, data); }
        public static ActionResult fail(String msg) { return new ActionResult(false, msg); }
    }

    /** Java 安装信息 */
    public static class JavaInfo {
        public final String homePath;
        public final String version;
        public JavaInfo(String homePath, String version) { this.homePath = homePath; this.version = version; }
    }

    /** 存档信息 */
    public static class SaveInfo {
        public final String worldName;
        public final String folderName;
        public final String path;
        public final String version;
        public SaveInfo(String worldName, String folderName, String path, String version) {
            this.worldName = worldName; this.folderName = folderName; this.path = path; this.version = version;
        }
    }

    /** 微软登录结果 */
    public static class MicrosoftAuthResult {
        public final String accessToken;
        public final String refreshToken;
        public final String uuid;
        public final String username;
        public final String deviceCode;      // 仅首次登录时有值
        public final String verificationUri; // 仅首次登录时有值
        public MicrosoftAuthResult(String accessToken, String refreshToken, String uuid, String username) {
            this(accessToken, refreshToken, uuid, username, null, null);
        }
        public MicrosoftAuthResult(String accessToken, String refreshToken, String uuid, String username,
                                   String deviceCode, String verificationUri) {
            this.accessToken = accessToken; this.refreshToken = refreshToken;
            this.uuid = uuid; this.username = username;
            this.deviceCode = deviceCode; this.verificationUri = verificationUri;
        }
    }

    /** AI 分析结果 */
    public static class AIAnalysisResult {
        public final String rawJson;
        public final String chineseReason;
        public final String englishReason;
        public AIAnalysisResult(String rawJson, String chineseReason, String englishReason) {
            this.rawJson = rawJson; this.chineseReason = chineseReason; this.englishReason = englishReason;
        }
    }

    /** 日志上传结果 */
    public static class LogUploadResult {
        public final String url;
        public final BufferedImage qrImage;
        public final String qrFilePath;
        public LogUploadResult(String url, BufferedImage qrImage, String qrFilePath) {
            this.url = url; this.qrImage = qrImage; this.qrFilePath = qrFilePath;
        }
    }

    /** 启动配置 */
    public static class LaunchConfig {
        public String gameDir = ".minecraft";
        public String version = "";
        public String javaPath = "java";
        public String userName = "Player";
        public String uuid = "00000000-0000-0000-0000-000000000000";
        public String accessToken = "dummy_token";
        public String userType = "msa";
        public String authServer = "";
        public int minMemory = 2048;
        public int maxMemory = 4096;
        public int windowWidth = 854;
        public int windowHeight = 480;
        public boolean fullscreen = false;
        public boolean versionIsolation = false;
        public String jvmArgs = "";
        public String gameArgs = "";
        public String preLaunchCommand = "";
        public String postExitCommand = "";
        /** 自定义游戏窗口标题（空 = 保持游戏默认标题，见 GameProcessTweaks） */
        public String windowTitle = "";
        /** 游戏进程优先级（low / below_normal / normal / above_normal / high / realtime） */
        public String processPriority = "normal";

        /** 从 ini 配置 Map 构造 */
        public static LaunchConfig fromIni(Map<String, String> ini) {
            LaunchConfig c = new LaunchConfig();
            c.gameDir   = ini.getOrDefault("GameDir", ".minecraft");
            c.version   = ini.getOrDefault("Version", "");
            c.javaPath  = ini.getOrDefault("JavaPath", "java");
            c.userName  = ini.getOrDefault("UserName", "Player");
            c.uuid      = ini.getOrDefault("UUID", "00000000-0000-0000-0000-000000000000");
            c.accessToken = ini.getOrDefault("AccessToken", "dummy_token");
            c.userType  = ini.getOrDefault("UserType", "msa");
            c.authServer = ini.getOrDefault("AuthServer", "");
            c.minMemory = parseIntSafe(ini.getOrDefault("MinMemory", "2048"), 2048);
            c.maxMemory = parseIntSafe(ini.getOrDefault("MaxMemory", "4096"), 4096);
            c.windowWidth  = parseIntSafe(ini.getOrDefault("WindowWidth", "854"), 854);
            c.windowHeight = parseIntSafe(ini.getOrDefault("WindowHeight", "480"), 480);
            c.fullscreen = "true".equalsIgnoreCase(ini.getOrDefault("Fullscreen", "false"));
            c.versionIsolation = "true".equalsIgnoreCase(ini.getOrDefault("VersionIsolation", "false"));
            c.jvmArgs  = ini.getOrDefault("JvmArgs", "");
            c.gameArgs = ini.getOrDefault("GameArgs", "");
            c.preLaunchCommand = ini.getOrDefault("PreLaunchCommand", "");
            c.postExitCommand  = ini.getOrDefault("PostExitCommand", "");
            c.windowTitle = ini.getOrDefault("GameWindowTitle", "");
            c.processPriority = ini.getOrDefault("GameProcessPriority", "normal");
            return c;
        }
    }

    /** 游戏启动结果 */
    public static class LaunchResult {
        public final int exitCode;
        public final long elapsedSeconds;
        public final String errorLogPath;
        public final long launchStartTime;   // 本次启动开始时间戳(ms)，用于日志新鲜度校验
        /** 本次实例是否被用户从「关闭游戏进程」弹窗主动结束（用于抑制崩溃诊断面板） */
        public final boolean stoppedByUser;

        public LaunchResult(int exitCode, long elapsedSeconds, String errorLogPath, long launchStartTime) {
            this(exitCode, elapsedSeconds, errorLogPath, launchStartTime, false);
        }

        public LaunchResult(int exitCode, long elapsedSeconds, String errorLogPath, long launchStartTime,
                            boolean stoppedByUser) {
            this.exitCode = exitCode; this.elapsedSeconds = elapsedSeconds; this.errorLogPath = errorLogPath;
            this.launchStartTime = launchStartTime;
            this.stoppedByUser = stoppedByUser;
        }
        public boolean isNormal() { return exitCode == 0; }
    }

    /** 模组信息 */
    public static class ModInfo {
        public final String name;
        public final String path;
        public final boolean enabled;    // true=启用, false=禁用(.disabled)
        public final String origin;      // "[全局]" 或 "[版本]"
        public ModInfo(String name, String path, boolean enabled, String origin) {
            this.name = name; this.path = path; this.enabled = enabled; this.origin = origin;
        }
    }

    /** 资源包/光影包信息 */
    public static class PackInfo {
        public final String name;
        public final String path;
        public final boolean active;     // 是否已启用
        public PackInfo(String name, String path, boolean active) {
            this.name = name; this.path = path; this.active = active;
        }
    }

    /** 配置文件信息 */
    public static class ConfigFileInfo {
        public final String name;
        public final String path;
        public final long size;
        public ConfigFileInfo(String name, String path, long size) {
            this.name = name; this.path = path; this.size = size;
        }
    }

    /** 原生库状态 */
    public static class NativesStatus {
        public final boolean ready;
        public final int fileCount;
        public final List<String> fileNames;
        public NativesStatus(boolean ready, int fileCount, List<String> fileNames) {
            this.ready = ready; this.fileCount = fileCount; this.fileNames = fileNames;
        }
    }

    /** 启动器档案信息 */
    public static class LauncherProfileInfo {
        public final String username;
        public final String displayName;
        public final int accountCount;
        public final Map<String, String> profiles;  // profile名 -> 版本ID
        public LauncherProfileInfo(String username, String displayName, int accountCount,
                                    Map<String, String> profiles) {
            this.username = username; this.displayName = displayName;
            this.accountCount = accountCount; this.profiles = profiles;
        }
    }


    // Ⅰ.  配置管理（INI 读写） //

    /** 1.1 读取全部配置（路径统一由 AppDirs 解析：默认 ~/.starlight，--portable 回落工作目录） */
    public static Map<String, String> readConfig() {
        Map<String, String> config = new HashMap<>();
        Path configPath = AppDirs.starlightIni();
        if (!Files.exists(configPath)) return config;
        try {
            List<String> lines = Files.readAllLines(configPath, StandardCharsets.UTF_8);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(";") || line.startsWith("#") || line.startsWith("[")) continue;
                int eq = line.indexOf('=');
                if (eq > 0) config.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        } catch (IOException ignored) {}
        return config;
    }

    /** 1.2 读取单个配置值 */
    public static String getConfigValue(String key) {
        return readConfig().getOrDefault(key, "");
    }

    /** 1.3 获取 LaunchConfig 对象（UI 友好） */
    public static LaunchConfig getLaunchConfig() {
        Map<String, String> ini = readConfig();
        // 版本独立配置：全局 ini + 当前版本覆盖（config.overrides.json）合并后再构造，
        // 账号等运行时字段不参与覆盖，由 applyCurrentAccount / 启动链路注入
        String gameDir = GameDirManager.activePath(ini);
        // 版本选择的真源是「当前游戏目录内的 starlight.json」，不是 ini 里的 Version
        // （这条是启动主路径：切目录后必须启动该目录自己记录的版本）
        String version = SelectedVersion.of(gameDir);
        return getLaunchConfigFor(gameDir, version);
    }

    /**
     * 1.3b 获取「指定游戏目录 + 指定版本」的 LaunchConfig。
     *
     * <p>按该版本自身的独立设置（config.overrides.json）合成，供「最近游玩」等
     * 需要启动非当前选中版本的入口使用（修复：从最近游玩启动其他版本时
     * 误用当前选中版本的独立配置）。
     */
    public static LaunchConfig getLaunchConfigFor(String gameDir, String version) {
        Map<String, String> ini = readConfig();
        Map<String, String> effective = VersionConfigManager.buildEffectiveIni(ini, gameDir, version);
        effective.put("GameDir", gameDir);
        effective.put("Version", version);
        return LaunchConfig.fromIni(effective);
    }

    /** 1.4 保存配置 */
    public static ActionResult saveConfig(Map<String, String> config) {
        try {
            Path configDir = AppDirs.root();
            if (!Files.exists(configDir)) Files.createDirectories(configDir);
            Path configPath = AppDirs.starlightIni();

            List<String> oldLines = new ArrayList<>();
            if (Files.exists(configPath)) oldLines = Files.readAllLines(configPath, StandardCharsets.UTF_8);

            String[] launcherKeys = {
                // 注意：没有 Version——版本选择存在各游戏目录内的 starlight.json（见 SelectedVersion）
                "GameDir", "JavaPath", "AutoJava", "JavaPathHistory",
                "MinMemory", "MaxMemory", "WindowWidth", "WindowHeight",
                "Fullscreen", "VersionIsolation", "JvmArgs", "GameArgs", "GameLanguage",
                "PreLaunchCommand", "PostExitCommand",
                // 高级设置：自定义游戏窗口标题 / 进程优先级 / 游戏启动后的启动器可见性
                // （未登记的话点「保存」写不进 starlight.ini，重启就丢）
                "GameWindowTitle", "GameProcessPriority", "LauncherVisibility",
                "ServerUrl", "GameFolders", "GameDirNames", "ParallelLaunch",
                "ParallelVerify", "StreamingSha1", "FastVerify", "SilentLoginMode",
                "ProxyType", "ProxyHost", "ProxyPort", "ProxyUser", "ProxyPass"
            };

            List<String> newLines = new ArrayList<>();
            boolean inLauncherSection = false;
            boolean hasLauncherSection = false;
            Set<String> seenLauncherKeys = new HashSet<>();

            for (String line : oldLines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    if (inLauncherSection) {
                        for (String key : launcherKeys) {
                            if (!seenLauncherKeys.contains(key))
                                newLines.add(key + "=" + config.getOrDefault(key, ""));
                        }
                        // Removed unnecessary assignment
                    }
                    inLauncherSection = trimmed.equalsIgnoreCase("[Launcher]");
                    if (inLauncherSection) { hasLauncherSection = true; seenLauncherKeys.clear(); }
                    newLines.add(line);
                    continue;
                }
                if (inLauncherSection) {
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        String key = line.substring(0, eq).trim();
                        // 旧版遗留的 Version 行一律丢弃：版本选择真源是游戏目录内的 starlight.json。
                        // 必须显式丢行——下面 getOrDefault 分支会把缺失键写成字面量 "null"。
                        if ("Version".equals(key)) continue;
                        seenLauncherKeys.add(key);
                        newLines.add(key + "=" + config.getOrDefault(key, config.get(key)));
                    } else {
                        newLines.add(line);
                    }
                } else {
                    newLines.add(line);
                }
            }
            if (inLauncherSection) {
                for (String key : launcherKeys) {
                    if (!seenLauncherKeys.contains(key))
                        newLines.add(key + "=" + config.getOrDefault(key, ""));
                }
            }
            if (!hasLauncherSection) {
                if (!newLines.isEmpty() && !newLines.get(newLines.size() - 1).isEmpty()) newLines.add("");
                newLines.add("[Launcher]");
                for (String key : launcherKeys)
                    newLines.add(key + "=" + config.getOrDefault(key, ""));
            }

            Files.write(configPath, newLines, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return ActionResult.ok(I18N.get("gui.p0065", configPath.toAbsolutePath()));
        } catch (IOException e) {
            return ActionResult.fail(I18N.get("gui.p0066", e.getMessage()));
        }
    }

    /** 1.5 保存单个配置值 */
    public static ActionResult setConfigValue(String key, String value) {
        Map<String, String> config = readConfig();
        config.put(key, value);
        return saveConfig(config);
    }

    /** 1.6 检查配置是否完备（是否可以快速启动） */
    public static boolean isConfigReady() {
        Map<String, String> cfg = readConfig();
        return !SelectedVersion.of(cfg).isEmpty();
    }


    // Ⅱ.  游戏启动 / 停止 //

    /**
     * 最近一次启动对应实例的 PID。
     *
     * <p>「取消启动」只针对它 —— 多实例下按「所有正在启动的实例」来杀会误伤已经在玩的那个。
     */
    private static volatile long launchingPid = -1L;

    /**
     * 最近一次启动<b>实际使用</b>的 java.exe 路径及其版本 ID。
     *
     * <p>AutoJava 是在启动线程里覆盖 {@code config.javaPath} 的，而崩溃诊断面板拿到的是
     * 重新从 starlight.ini 读出来的配置（见 {@code LauncherView.showGameCrashPanel}），
     * 读配置只能看到手写的那条陈旧路径（如 java8），于是 26.2 崩溃时会显示「Java: 1.8.0_482」，
     * 而实际用的是自动选出的 Java 25。这里把真实值留在内存里，供诊断显示引用。
     */
    private static volatile String lastLaunchJavaPath = null;
    private static volatile String lastLaunchJavaVersion = null;

    /**
     * 最近一次启动的<b>实例快照</b>：真正启动的版本 / 目录 / 加载器 / Java / 隔离开关。
     *
     * <p>崩溃诊断此前只能拿到「当前选中版本」的配置（{@code LauncherView.showGameCrashPanel}
     * 重新 {@code getLaunchConfig()}），于是从「最近游玩」或插件启动非当前选中版本时，
     * 崩溃面板会把「当前版本」的元数据（26w14a Vanilla）与「实际崩溃版本」的旧 crash-report
     * （1.12.2 Forge）拼到一起，造成「版本隔离失效」的假象。这里把真实启动信息留在内存里，
     * 供崩溃诊断按「实际启动的版本」回读崩溃报告与 Java 版本。
     */
    public static final class LaunchSnapshot {
        public final String version;
        public final String gameDir;
        public final String loader;           // LoaderDetector 常量（Vanilla/Forge (Legacy)/...）
        public final String javaPath;          // 实际使用的 java.exe（含 AutoJava 覆盖结果）
        public final boolean versionIsolation;
        public final long launchStartTime;     // 本次启动开始时间戳(ms)，崩溃报告按此过滤新鲜度
        public LaunchSnapshot(String version, String gameDir, String loader, String javaPath,
                              boolean versionIsolation, long launchStartTime) {
            this.version = version;
            this.gameDir = gameDir;
            this.loader = loader;
            this.javaPath = javaPath;
            this.versionIsolation = versionIsolation;
            this.launchStartTime = launchStartTime;
        }
    }

    private static volatile LaunchSnapshot lastLaunchSnapshot = null;

    /** 记录本次启动实际使用的 Java（由启动线程在 AutoJava 解析完成后调用） */
    public static void recordLaunchJavaPath(String version, String javaPath) {
        lastLaunchJavaPath = javaPath;
        lastLaunchJavaVersion = version;
    }

    /**
     * 记录本次启动的实例快照（在加载器判定完成后调用，此时版本/目录/Java/隔离开关均已确定）。
     * 启动开始时间稍后在 {@link #launchMinecraftAsync} 内补齐，这里先以当前时间为占位。
     */
    public static void recordLaunchSnapshot(String version, String gameDir, String loader,
                                            String javaPath, boolean versionIsolation) {
        lastLaunchSnapshot = new LaunchSnapshot(version, gameDir, loader, javaPath,
                versionIsolation, System.currentTimeMillis());
    }

    /** 更新最近一次启动快照的启动开始时间（游戏进程真正启动前写入，供崩溃报告新鲜度过滤）。 */
    public static void markLaunchSnapshotStartTime(long launchStartTime) {
        LaunchSnapshot snap = lastLaunchSnapshot;
        if (snap == null) return;
        lastLaunchSnapshot = new LaunchSnapshot(snap.version, snap.gameDir, snap.loader,
                snap.javaPath, snap.versionIsolation, launchStartTime);
    }

    /** 取最近一次启动的实例快照（可能为 null） */
    public static LaunchSnapshot getLastLaunchSnapshot() {
        return lastLaunchSnapshot;
    }

    /**
     * 取最近一次启动实际使用的 java.exe 路径
     *
     * @param version 崩溃诊断针对的版本 ID；与最近一次启动的版本不符时返回 null（避免张冠李戴）
     * @return 实际使用的路径，无记录或版本不符时返回 null（调用方回退到配置里的 JavaPath）
     */
    public static String getLastLaunchJavaPath(String version) {
        if (lastLaunchJavaPath == null || version == null || !version.equals(lastLaunchJavaVersion)) {
            return null;
        }
        return lastLaunchJavaPath;
    }

    /**
     * 2.1 启动 Minecraft 游戏（异步）
     * @param config  启动配置
     * @param onProgress  进度回调（UI 线程外部，需自行 Platform.runLater）
     * @param onResult    结果回调
     */
    public static void launchMinecraftAsync(LaunchConfig config,
                                             ProgressCallback onProgress,
                                             ResultCallback<LaunchResult> onResult) {
        launchMinecraftAsync(config, onProgress, onResult, null);
    }

    /**
     * 2.1a 启动 Minecraft 游戏（异步，带游戏窗口检测回调）
     * @param config         启动配置
     * @param onProgress     进度回调
     * @param onResult       结果回调
     * @param onGameStarted  游戏窗口就绪回调（检测到游戏进程/日志就绪时调用，用于关闭启动动画）
     */
    public static void launchMinecraftAsync(LaunchConfig config,
                                             ProgressCallback onProgress,
                                             ResultCallback<LaunchResult> onResult,
                                             Runnable onGameStarted) {
        ASYNC_POOL.submit(() -> {
            // 本次启动对应实例的 PID（-1 = 进程还没起来）。放在 try 外面，退出与异常两条路径都要用它注销实例
            final long[] gamePidRef = new long[]{-1L};
            // 本地披风服务器引用：同样放在 try 外面，退出与异常两条路径都要用它停止服务器
            final java.util.concurrent.atomic.AtomicReference<LocalYggdrasilServer> capeServerRef =
                    new java.util.concurrent.atomic.AtomicReference<>(null);
            // 本地披风代理引用（劫持 OptiFine s.optifine.net 披风下载）：与档案服务器同生命周期
            final java.util.concurrent.atomic.AtomicReference<LocalCapeProxy> capeProxyRef =
                    new java.util.concurrent.atomic.AtomicReference<>(null);
            try {
                // 每次启动重置取消标志
                launchCancelled.set(false);

                // Token 过期自动刷新（两种静默登录模式） //
                // 模式一「启动游戏时静默登录」：每次启动游戏时检查并静默刷新过期令牌（默认）；
                // 模式二「开启启动器静默登录」：启动器启动时已在后台刷新，此处保留过期检查（幂等）
                AccountManager.Account currentAccount = AccountManager.getCurrentAccount();
                AccountManager.Account refreshedAccount = SilentLoginManager.refreshIfNeeded(
                        (pct, msg) -> onProgress.onProgress(pct, msg));
                if (refreshedAccount != null) {
                    config.accessToken = refreshedAccount.accessToken;
                    config.uuid = refreshedAccount.id;
                    config.userName = refreshedAccount.name;
                    config.userType = "msa";
                }
                // 离线皮肤（PCL2 风格）：离线账号按皮肤配置微调传给游戏的 UUID（Steve/Alex 模型
                // 由 UUID 决定）；以账号基础 UUID 为起点，幂等，不改动账号本身
                if (currentAccount != null && currentAccount.type == AccountManager.AccountType.OFFLINE) {
                    String resolvedUuid = OfflineSkinManager.resolveUuid(currentAccount.id, currentAccount.getSkin());
                    if (resolvedUuid != null && !resolvedUuid.isEmpty()) {
                        config.uuid = resolvedUuid;
                    }
                }

                // 启动流程日志（PCL 标准）：预检测 / 登录方式 //
                if (config.userName != null && !config.userName.isEmpty()) {
                    boolean isMsa = "msa".equalsIgnoreCase(config.userType);
                    LaunchLog.info(isMsa ? "登录方式：正版（" + config.userName + "）"
                                        : "登录方式：离线（" + config.userName + "）");
                }

                // 第三方（外置登录）账号：注入认证服务器参数（authlib-injector / minecraft.api.*） //
                injectThirdPartyAuth(config);

                // 代理：注入系统代理 / HTTP 代理 JVM 参数（参照 HMCL DefaultLauncher） //
                injectProxyArgs(config);

                // 自动选择 Java（参考 HMCL findSuitableJava：按游戏版本匹配本机安装） //
                // AutoJava 开启时以自动选择为准（与设置页 UI 一致：开启时手动控件禁用），
                // 避免手动配置的高版本 Java（如 25）被用于只支持旧版本 Java 的游戏（如 1.18）导致崩溃；
                // 自动选择找不到合适 Java 时才回退到当前配置（手动/系统默认）。
                // 版本独立设置可仅对该版本关闭自动选择（effectiveAutoJava），让手动 java.path 生效。
                boolean autoJavaOn = VersionConfigManager.effectiveAutoJava(
                        readConfig(), config.gameDir, config.version);
                if (autoJavaOn) {
                    onProgress.onProgress(5, I18N.get("gui.t0001"));
                    String autoJava = autoSelectJavaPath(config.version, config.gameDir);
                    if (autoJava != null) {
                        config.javaPath = autoJava;
                        LOG.info("AutoJava selected: {}", autoJava);
                        LaunchLog.info(I18N.get("gui.p0067", autoJava));
                        onProgress.onProgress(6, I18N.get("gui.p0068", autoJava));
                    } else {
                        LOG.warn("AutoJava found no suitable Java, falling back to: {}", config.javaPath);
                        LaunchLog.warn(I18N.get("gui.t0002"));
                        onProgress.onProgress(6, I18N.get("gui.t0003"));
                    }
                }

                // 本次启动最终用哪个 Java 记在内存里：崩溃诊断面板只能重新读配置，
                // 读不到上面 AutoJava 的覆盖结果，不记下来就会显示成配置里那条陈旧路径
                recordLaunchJavaPath(config.version, config.javaPath);

                onProgress.onProgress(7, I18N.get("gui.t0004"));
                LaunchLog.info(I18N.get("gui.t0005"));
                LaunchInfo info = buildLaunchInfo(config);
                Path jsonPath = info.resolveVersionDir().resolve(config.version + ".json");
                if (!Files.exists(jsonPath)) {
                    onResult.onError(I18N.get("gui.p0069", jsonPath));
                    return;
                }
                String jsonString = Files.readString(jsonPath);
                JsonObject versionJson = new Gson().fromJson(jsonString, JsonObject.class);
                // 整合包等版本是壳 JSON（只有 id/inheritsFrom/time/releaseTime/type）：
                // 先沿继承链合并为自包含视图，再去重/判加载器/补老参数，
                // 后续资源补全、natives 提取、启动库参数构建都拿到完整 libraries/assetIndex
                versionJson = VersionUtils.resolveInheritedJson(java.nio.file.Path.of(config.gameDir), config.version, versionJson);
                jsonString = versionJson.toString();
                // 合并型 JSON 里同一 artifact 可能有多个版本并存（HNT 同时有 guava 15.0 与 17.0），
                // 不去重时类路径先命中旧版，FML 启动即 NoSuchMethodError
                if (VersionUtils.deduplicateLibraries(versionJson) >= 0) {
                    jsonString = versionJson.toString();
                }
                LOG.info("Launch version {} with java: {}", config.version, config.javaPath);
                // 统一判定入口（含本工程补判）：裸 LoaderDetector 会把 mainClass=launchwrapper.Launch
                // 的 Forge 版本误判成 Vanilla，导致 Forge 整合包被裸启
                String loader = VersionIconKit.detectLoader(versionJson, config.version);
                info.setLoaderType(loader);
                // 记录本次启动的实例快照（真正启动的版本/目录/加载器/Java/隔离），供崩溃诊断回读，
                // 避免从「最近游玩」启动非当前选中版本时崩溃面板串到当前版本的元数据
                recordLaunchSnapshot(config.version, config.gameDir, loader,
                        config.javaPath, config.versionIsolation);
                // 核心 jar 构建参数时会漏掉 --userProperties（1.7.x/1.12.x 的 Main 必填），补上
                java.util.List<String> injectedArgs = LegacyLaunchArgs.apply(info, versionJson);
                if (!injectedArgs.isEmpty()) {
                    LOG.info("Injected legacy game args: {}", injectedArgs);
                }
                onProgress.onProgress(20, I18N.get("gui.p0070", loader));
                LaunchLog.info(I18N.get("gui.p0071", loader));
                if (launchCancelled.get()) { onResult.onError(I18N.get("gui.t0006")); return; }

                // 资源准备（并行加速开启时：原生库检查与资源补全同时进行，缩短启动时间） //
                // 关闭时保持原串行流程：先补全资源（资产+库），再检查原生库
                onProgress.onProgress(25, I18N.get("gui.t0007"));
                LaunchLog.info(I18N.get("gui.t0008"));
                if (ParallelLaunchManager.isEnabled()) {
                    boolean prepOk = ParallelLaunchManager.prepare(config.gameDir, config.version, jsonString,
                            (pct, msg) -> onProgress.onProgress(Math.min(25 + (int) (pct * 0.35), 60), msg),
                            launchCancelled);
                    if (!prepOk) {
                        if (launchCancelled.get()) { onResult.onError(I18N.get("gui.t0009")); return; }
                        onResult.onError(I18N.get("gui.t0010"));
                        return;
                    }
                } else {
                    onProgress.onProgress(25, I18N.get("gui.t0011"));
                    GameResourceCompleter.ProgressCallback resourceProgress = (pct, msg) -> {
                        int mapped = 25 + (int) (pct * 0.25);
                        onProgress.onProgress(Math.min(mapped, 50), msg);
                    };
                    // 2026-10-21：经分流器调用，设置里选 PCL 引擎时走新引擎
                    DownloadEngineRouter.completeAll(config.gameDir, config.version, resourceProgress,
                            launchCancelled);
                }
                if (launchCancelled.get()) { onResult.onError(I18N.get("gui.t0012")); return; }

                BaseLauncher launcher;
                switch (loader) {
                    case LoaderDetector.VANILLA       -> launcher = new VanillaLauncher(info);
                    case LoaderDetector.FABRIC, LoaderDetector.QUILT -> launcher = new FabricLauncher(info);
                    case LoaderDetector.FORGE_LEGACY  -> launcher = new ForgeLegacyLauncher(info);
                    case LoaderDetector.FORGE_MODERN, LoaderDetector.NEOFORGE -> launcher = new ForgeModernLauncher(info);
                    default -> { onResult.onError(I18N.get("gui.p0072", loader)); return; }
                }

                // 取消判定器：把「取消启动」标志位交给基类，由它负责两件事 ——
                // ① 进程在「检查点已过、还没起来」的窗口期起来时立即补杀；
                // ② 退出日志与归因不再把强制结束误报成「游戏异常退出 (代码: 1)」
                launcher.setCancelChecker(launchCancelled::get);

                // 方案一：在 initLogging() 之前采集已有日志文件 //
                // 确保 initLogging() 创建的新日志文件不会被过滤掉
                final java.nio.file.Path logDir = java.nio.file.Paths.get(LOG_DIR);
                final java.util.Set<String> existingLogs;
                if (java.nio.file.Files.isDirectory(logDir)) {
                    java.util.Set<String> logs = new java.util.HashSet<>();
                    try (var stream = java.nio.file.Files.list(logDir)) {
                        stream.filter(p -> p.getFileName().toString().startsWith("minecraft_launch_"))
                              .map(p -> p.getFileName().toString())
                              .forEach(logs::add);
                    } catch (java.io.IOException e) {
                        System.err.println("[UIGeneralControlClass] Failed to scan log directory: " + e.getMessage());
                    }
                    existingLogs = logs;
                } else {
                    existingLogs = java.util.Collections.emptySet();
                }

                onProgress.onProgress(50, I18N.get("gui.t0013"));
                LaunchLog.info(I18N.get("gui.t0014"));
                launcher.initPaths();
                launcher.initLogging(Paths.get(LOG_DIR));
                launcher.setVersionJson(versionJson);

                onProgress.onProgress(60, I18N.get("gui.t0015"));
                LaunchLog.info(I18N.get("gui.t0016"));
                boolean nativesOk = NativesManager.checkAndCompleteNatives(config.gameDir, config.version, jsonString, false);
                if (!nativesOk) {
                    onResult.onError(I18N.get("gui.t0017"));
                    return;
                }
                boolean processOk = launcher.processNatives();
                if (!processOk) {
                    onResult.onError(I18N.get("gui.t0018"));
                    return;
                }
                if (launchCancelled.get()) { onResult.onError(I18N.get("gui.t0019")); return; }

                // 应用游戏内设置（语言 / 全屏 → options.txt；取该版本合并后的有效值） //
                LaunchLog.info(I18N.get("gui.t0020"));
                Map<String, String> launchEffective = VersionConfigManager.buildEffectiveIni(
                        readConfig(), config.gameDir, config.version);
                String langCode = launchEffective.getOrDefault("GameLanguage", "");
                if (langCode != null && !langCode.isEmpty()) {
                    GameLanguage.setLanguage(config.gameDir, config.version,
                            config.versionIsolation, langCode);
                }
                // 全屏模式：改为写入 options.txt 的 fullscreen 字段（与游戏内设置一致）
                boolean launchFullscreen = "true".equalsIgnoreCase(
                        launchEffective.getOrDefault("Fullscreen", "false"));
                GameOptions.setFullscreen(config.gameDir, config.version,
                        config.versionIsolation, launchFullscreen);

                // 离线皮肤资源包（PCL2 风格：自定义皮肤以资源包覆盖默认贴图，
                // 无需本地皮肤服务器 / authlib-injector）
                if (currentAccount != null && currentAccount.type == AccountManager.AccountType.OFFLINE) {
                    onProgress.onProgress(75, I18N.get("gui.t0021"));
                    LaunchLog.info(I18N.get("gui.t0022"));
                    try {
                        Skin skin = currentAccount.getSkin();
                        VanillaVersion vanilla = VanillaVersion.resolve(
                                java.nio.file.Path.of(config.gameDir), config.version);
                        OfflineSkinManager.applySkinPack(skin, currentAccount.name,
                                config.gameDir, config.version, config.versionIsolation, vanilla);
                    } catch (Exception e) {
                        LOG.warn("Failed to apply offline skin resource pack: {}", e.getMessage());
                        LaunchLog.warn(I18N.get("gui.p0073", e.getMessage()));
                    }
                    // 披风：本地 Yggdrasil 档案服务器 + authlib-injector（仅当配置了披风；
                    // 无披风保持纯 PCL2 机制）。原版/纯 OptiFine 客户端只从会话档案读披风。
                    try {
                        Skin.LoadedSkin loaded = OfflineSkinManager.loadForLaunch(
                                currentAccount.getSkin(), currentAccount.name);
                        LocalYggdrasilServer capeServer = LocalYggdrasilServer.start(
                                loaded, config.uuid, currentAccount.name);
                        if (capeServer != null) {
                            capeServerRef.set(capeServer);
                            List<String> injectArgs = new ArrayList<>(
                                    AuthlibInjectorSupport.buildLaunchJvmArgs(capeServer.getApiBase()));
                            // 本地披风代理：SOCKS5 劫持 OptiFine s.optifine.net 披风下载
                            //（1.17.1 纯 OptiFine 本地玩家披风唯一原生通道；--proxyHost 由
                            // Main 解析为 SOCKS 代理，HttpTexture 走 HttpPipeline SOCKS 隧道）
                            LocalCapeProxy capeProxy = LocalCapeProxy.start(
                                    OfflineSkinManager.capePngBytes(loaded), currentAccount.name);
                            if (capeProxy != null) {
                                capeProxyRef.set(capeProxy);
                                // CapeUrlPatchAgent：CapeUtils 类加载时把
                                // http://s.optifine.net 重写为本地披风服务器地址
                                String agentArg = capeProxy.agentJvmArg();
                                if (agentArg != null) {
                                    injectArgs.add(agentArg);
                                }
                            }
                            String extra = String.join(" ", injectArgs);
                            config.jvmArgs = (config.jvmArgs == null || config.jvmArgs.isBlank())
                                    ? extra : config.jvmArgs.trim() + " " + extra;
                            // buildLaunchInfo 已把 jvmArgs 快照进 info（披风服务器启动之前），
                            // 注入参数必须同步回 info，否则 Forge Legacy / Vanilla 启动器读到的是
                            // 不含 -javaagent 的旧列表（日志显示有、实际进程没有）
                            info.setJvmArgs(com.example.starlight.startmc.LaunchInfo
                                    .splitCommandLine(config.jvmArgs));
                            // 1.17+ 本地玩家皮肤/披风直注入：SkinManager 对本地玩家直接取
                            // Session profile 属性（--profileProperties），不走会话档案查询
                            java.util.List<String> pp = OfflineSkinManager.profilePropertiesArgs(
                                    capeServer, config.version);
                            if (!pp.isEmpty()) {
                                java.util.List<String> ga = new java.util.ArrayList<>();
                                if (info.getGameArgs() != null) ga.addAll(info.getGameArgs());
                                ga.addAll(pp);
                                info.setGameArgs(ga);
                                LaunchLog.info(I18N.get("gui.t0023"));
                            }
                            LaunchLog.info(I18N.get("gui.p0074", capeServer.getApiBase()));
                        }
                    } catch (Exception e) {
                        LOG.warn("Failed to start local cape server: {}", e.getMessage());
                        LaunchLog.warn(I18N.get("gui.p0075", e.getMessage()));
                    }
                }

                onProgress.onProgress(80, I18N.get("gui.p0076", config.version + " ..."));
                long startTime = System.currentTimeMillis();
                // 崩溃报告新鲜度过滤用「本次启动开始时间」；这里回填到快照里
                markLaunchSnapshotStartTime(startTime);

                // 启动参数区块（PCL 标准：参数获取开始 / 分隔线 / 每行一个参数） //
                LaunchLog.info(I18N.get("gui.t0024"));
                if (versionJson != null && versionJson.has("mainClass")) {
                    LaunchLog.info(versionJson.get("mainClass").getAsString());
                }
                LaunchLog.info("Java：" + config.javaPath);
                LaunchLog.info(I18N.get("gui.p0077", config.minMemory + " MB"));
                LaunchLog.info(I18N.get("gui.p0078", config.maxMemory + " MB"));
                LaunchLog.info(I18N.get("gui.p0079", config.version));
                LaunchLog.info(I18N.get("gui.p0080", config.gameDir));
                LaunchLog.info(I18N.get("gui.p0081", (config.versionIsolation ? I18N.get("gui.t0061") : I18N.get("gui.t0062"))));
                if (config.windowWidth > 0 && config.windowHeight > 0) {
                    LaunchLog.info(I18N.get("gui.p0082", config.windowWidth + " x " + config.windowHeight));
                }
                // 进程微调（高级设置）：只有真的设置了才占日志行，默认值不打扰
                if (config.windowTitle != null && !config.windowTitle.isBlank()) {
                    LaunchLog.info(I18N.get("gui.p0083", config.windowTitle));
                }
                if (config.processPriority != null && !"normal".equalsIgnoreCase(config.processPriority)) {
                    LaunchLog.info(I18N.get("gui.p0084", com.example.starlight.util.GameProcessTweaks
                            .priorityLabel(config.processPriority)));
                }
                if (config.jvmArgs != null && !config.jvmArgs.isEmpty()) {
                    LaunchLog.info(I18N.get("gui.p0085", config.jvmArgs));
                }
                if (config.gameArgs != null && !config.gameArgs.isEmpty()) {
                    LaunchLog.info(I18N.get("gui.p0086", config.gameArgs));
                }
                LaunchLog.info(I18N.get("gui.t0025"));

                if (launchCancelled.get()) { onResult.onError(I18N.get("gui.t0026")); return; }

                // 注册进程回调：登记到实例表（「关闭游戏进程」弹窗据此列出所有实例）
                launcher.setOnProcessStarted(process -> {
                    launchingPid = process.pid();
                    gamePidRef[0] = process.pid();
                    GameInstanceRegistry.register(new GameInstanceRegistry.Instance(
                            process.pid(), config.version, config.gameDir, config.userName, loader, startTime));
                    LaunchLog.info(I18N.get("gui.p0087", config.javaPath + "（PID " + process.pid() + "）"));
                });

                // 游戏窗口检测线程（在 launcher.launch() 之前启动） //
                // 方案一：logDir/existingLogs 已在 initLogging() 之前采集（见上方）
                if (onGameStarted != null) {
                    final long launcherPid = ProcessHandle.current().pid();

                    // 记录已有 Java 进程（排除启动器自身）
                    final java.util.Set<Long> existingJavaPids = ProcessHandle.allProcesses()
                            .filter(p -> p.isAlive() && p.pid() != launcherPid)
                            .filter(p -> {
                                var cmd = p.info().command();
                                return cmd.isPresent() && (cmd.get().toLowerCase().endsWith("java.exe")
                                        || cmd.get().toLowerCase().endsWith("javaw.exe")
                                        || cmd.get().toLowerCase().endsWith("java"));
                            })
                            .map(ProcessHandle::pid)
                            .collect(java.util.stream.Collectors.toSet());

                    long deadline = System.currentTimeMillis() + 60_000;
                    final java.util.concurrent.atomic.AtomicBoolean processFound = new java.util.concurrent.atomic.AtomicBoolean(false);
                    final java.util.concurrent.atomic.AtomicLong processFoundTime = new java.util.concurrent.atomic.AtomicLong(0);
                    final java.util.concurrent.atomic.AtomicReference<java.nio.file.Path> logFileRef = new java.util.concurrent.atomic.AtomicReference<>(null);
                    final java.util.concurrent.atomic.AtomicInteger lastContentLen = new java.util.concurrent.atomic.AtomicInteger(0);

                    ASYNC_POOL.submit(() -> {
                        // 唯一上报入口：取消后一律不再上报「游戏已启动」。
                        // 否则取消启动会在 25~60 秒后补一次该回调，导致首页按钮补一个对勾、
                        // 写一条假的「最近游玩」、误拉起色盲辅助，甚至按可见性策略隐藏/关闭启动器。
                        // 声明在 try 之外：超时/异常兜底那一次也要走同一个入口。
                        final Runnable signalGameStarted = () -> {
                            if (launchCancelled.get()) return;
                            onGameStarted.run();
                        };
                        try {
                            try { Thread.sleep(1500); } catch (InterruptedException e) {
                                Thread.currentThread().interrupt(); return;
                            }

                            while (System.currentTimeMillis() < deadline) {
                                // 用户已取消：检测线程没必再等（原来会一直轮询到 25~60 秒超时才退出）
                                if (launchCancelled.get()) return;
                                // Phase 1: 等待新 Java 进程出现
                                if (!processFound.get()) {
                                    boolean found = ProcessHandle.allProcesses().anyMatch(p -> {
                                        if (!p.isAlive() || p.pid() == launcherPid) return false;
                                        if (existingJavaPids.contains(p.pid())) return false;
                                        return p.info().command()
                                                .filter(cmd -> cmd.toLowerCase().endsWith("java.exe")
                                                        || cmd.toLowerCase().endsWith("javaw.exe")
                                                        || cmd.toLowerCase().endsWith("java"))
                                                .isPresent();
                                    });
                                    if (found) {
                                        processFound.set(true);
                                        processFoundTime.set(System.currentTimeMillis());
                                        LaunchLog.info("[UIGeneralControlClass] Game Java process detected started");
                                    }
                                }

                                // Phase 2: 日志文件 tail（PCL 方式）
                                if (logFileRef.get() == null) {
                                    try (var stream = java.nio.file.Files.list(logDir)) {
                                        java.nio.file.Path found = stream
                                                .filter(p -> p.getFileName().toString().startsWith("minecraft_launch_"))
                                                .filter(p -> !existingLogs.contains(p.getFileName().toString()))
                                                .max(java.util.Comparator.comparingLong(p -> {
                                                    try { return java.nio.file.Files.getLastModifiedTime(p).toMillis(); }
                                                    catch (java.io.IOException e) { return 0L; }
                                                }))
                                                .orElse(null);
                                        if (found != null) logFileRef.set(found);
                                    } catch (java.io.IOException ignored) {}
                                }

                                java.nio.file.Path logFile = logFileRef.get();
                                if (logFile != null) {
                                    try {
                                        String content = java.nio.file.Files.readString(logFile);
                                        if (content.length() > lastContentLen.get()) {
                                            String newContent = content.substring(lastContentLen.get());
                                            lastContentLen.set(content.length());

                                            if (newContent.contains("Setting user:")
                                                    || newContent.toLowerCase().contains("lwjgl version")
                                                    || newContent.contains("OpenAL initialized")
                                                    || newContent.contains("Starting up SoundSystem")
                                                    || (newContent.contains("Created")
                                                            && newContent.contains("textures")
                                                            && newContent.contains("atlas"))) {
                                                String reason = newContent.contains("Setting user:") ? "User set"
                                                        : newContent.toLowerCase().contains("lwjgl version") ? "LWJGL version confirmed"
                                                        : newContent.contains("OpenAL initialized") ? "OpenAL initialized"
                                                        : newContent.contains("Starting up SoundSystem") ? "SoundSystem started"
                                                        : "Textures loaded";
                                                System.out.println("[UIGeneralControlClass] Game ready signal detected (" + reason + "), closing splash");
                                                signalGameStarted.run();
                                                return;
                                            }
                                        }
                                    } catch (java.io.IOException ignored) {}
                                }

                                // 后备方案 A：进程检测成功后 25 秒强制关闭
                                if (processFound.get() && System.currentTimeMillis() > processFoundTime.get() + 25_000) {
                                    System.out.println("[UIGeneralControlClass] Game process started 25s ago, force-closing splash");
                                    signalGameStarted.run();
                                    return;
                                }

                                // 后备方案 B：日志文件已找到但无就绪信号
                                if (logFile != null && System.currentTimeMillis() > deadline - 20_000) {
                                    System.out.println("[UIGeneralControlClass] No ready signal in log, force-closing splash before timeout");
                                    signalGameStarted.run();
                                    return;
                                }

                                try { Thread.sleep(200); } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt(); return;
                                }
                            }
                            System.out.println("[UIGeneralControlClass] Game window detection timeout (60s), force-closing splash");
                        } catch (Exception e) {
                            System.err.println("[UIGeneralControlClass] Game window detection thread error: " + e.getMessage());
                        }
                        // 超时 / 异常兜底：同样走唯一上报入口，取消后不再补报「游戏已启动」
                        signalGameStarted.run();
                    });
                }

                // 阻塞等待游戏退出
                int exitCode = launcher.launch();
                LocalYggdrasilServer capeServerDone = capeServerRef.getAndSet(null);
                if (capeServerDone != null) capeServerDone.stop();
                LocalCapeProxy capeProxyDone = capeProxyRef.getAndSet(null);
                if (capeProxyDone != null) capeProxyDone.stop();
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                onProgress.onProgress(100, I18N.get("uigc.r0001", elapsed));

                // 启动结束日志（PCL 标准：~ 基础参数 ~ / ~ 登录参数 ~ / 开始启动结束处理） //
                LaunchLog.info(I18N.get("gui.t0027"));
                LaunchLog.info(I18N.get("gui.p0088", config.version));
                LaunchLog.info(I18N.get("gui.p0089", config.maxMemory + " MB"));
                LaunchLog.info(I18N.get("gui.p0090", config.gameDir));
                LaunchLog.info(I18N.get("gui.p0091", (config.versionIsolation ? I18N.get("gui.t0063") : I18N.get("gui.t0064"))));
                LaunchLog.info(I18N.get("gui.p0092", config.javaPath));
                LaunchLog.info("");
                LaunchLog.info(I18N.get("gui.t0028"));
                LaunchLog.info(I18N.get("gui.p0093", (config.userName == null ? "" : config.userName)));
                LaunchLog.info("AccessToken：" + LaunchLog.mask(config.accessToken));
                LaunchLog.info("UUID：" + LaunchLog.mask(config.uuid));
                // i18n-lock: 不要翻译此行中文——解析启动日志输出，翻译即功能失效
                LaunchLog.info("登录方式：" + ("msa".equalsIgnoreCase(config.userType) ? "正版" : "离线"));
                LaunchLog.info("");
                LaunchLog.info(I18N.get("gui.t0029"));
                LaunchLog.info("游戏已退出，运行时长：" + elapsed + "秒");

                // 读「是否被用户主动关闭」要在注销之前：注销后实例对象就查不到了。
                // 该标记决定退出时是否弹崩溃诊断面板（主动关闭不算崩溃）
                long gamePid = gamePidRef[0];
                boolean stoppedByUser = false;
                if (gamePid > 0) {
                    GameInstanceRegistry.Instance instance = GameInstanceRegistry.find(gamePid);
                    stoppedByUser = instance != null && instance.isStoppedByUser();
                }
                unregisterGameInstance(gamePid);

                // 查找错误日志（传入启动开始时间，排除启动前遗留的旧日志）
                // 取消启动不算崩溃：不查错误日志，否则会把无关的旧 error log 挂到这次结果上
                //（崩溃诊断面板虽已被抑制，但结果对象里的脏数据会留着误导后续诊断）
                boolean cancelledByUser = stoppedByUser || launcher.isCancelledByUser();
                String errorLog = null;
                if (exitCode != 0 && !cancelledByUser) {
                    errorLog = findLatestErrorLog(config.gameDir, startTime);
                }
                onResult.onSuccess(new LaunchResult(exitCode, elapsed, errorLog, startTime, cancelledByUser));

            } catch (JsonSyntaxException | IOException | InterruptedException e) {
                LocalYggdrasilServer capeServerErr = capeServerRef.getAndSet(null);
                if (capeServerErr != null) capeServerErr.stop();
                LocalCapeProxy capeProxyErr = capeProxyRef.getAndSet(null);
                if (capeProxyErr != null) capeProxyErr.stop();
                unregisterGameInstance(gamePidRef[0]);
                LaunchLog.error(I18N.get("gui.p0094", e.getMessage()));
                onResult.onError(I18N.get("gui.p0095", e.getMessage()));
            }
        });
    }

    /**
     * 2.2 生成 .bat 启动脚本
     * @return 生成的文件路径，失败返回错误信息
     */
    public static ActionResult generateBatScript(LaunchConfig config) {
        try {
            String cmd = MinecraftLauncherBuilder.buildLaunchCommand(
                config.gameDir + "/versions/" + config.version + "/" + config.version + ".json",
                config.gameDir, config.version, config.javaPath,
                config.userName, config.uuid, config.accessToken,
                config.gameArgs, config.jvmArgs, "microsoft");

            String batchPath = "launch_" + config.version + ".bat";
            try (PrintWriter writer = new PrintWriter(batchPath, "UTF-8")) {
                writer.println("chcp 65001>nul");
                writer.println("@echo off");
                writer.println(I18N.get("gui.p0096", config.version));
                writer.println(I18N.get("gui.t0030"));
                writer.println("cd /D \"" + config.gameDir + "\"");
                writer.println();
                writer.println(cmd);
                writer.println();
                writer.println("pause");
            }
            return ActionResult.ok(I18N.get("gui.t0031"), new File(batchPath).getAbsolutePath());
        } catch (Exception e) {
            return ActionResult.fail(I18N.get("gui.p0097", e.getMessage()));
        }
    }

    /** 2.3 强制停止正在运行的游戏（关闭**全部**本启动器拉起的实例） */
    public static void stopGame() {
        launchCancelled.set(true);
        // 同步通知 GameLauncherService 取消（StartControllers 启动路径走的是 GameLauncherService）
        GameLauncherService.stopGame();
        // 实例表里登记的全部杀掉（含各自拉起的子进程）
        GameInstanceRegistry.killAll();
    }

    /**
     * 2.3a 只取消「正在启动的这一个」实例（启动动画上的「取消启动」用）。
     *
     * <p>多实例下不能顺手把别的实例也杀了：用户可能已经开着另一个游戏在玩。
     */
    public static void stopLaunchingInstance() {
        // 进程还没起来时会走启动流程里的取消检查点（launchCancelled）
        launchCancelled.set(true);
        LaunchLog.info(I18N.get("gui.t0032"));
        long pid = launchingPid;
        if (pid > 0) {
            // 先标记「用户主动结束」，否则非 0 退出码会被当成崩溃
            GameInstanceRegistry.Instance instance = GameInstanceRegistry.find(pid);
            if (instance != null) instance.markStoppedByUser();
            GameInstanceRegistry.killPid(pid);
        }
    }

    /** 按 PID 注销实例并清理「最近启动」标记（退出与异常两条路径共用） */
    private static void unregisterGameInstance(long pid) {
        if (pid <= 0) return;
        GameInstanceRegistry.unregister(pid);
        if (launchingPid == pid) launchingPid = -1L;
    }

    /** 2.4 是否有本启动器拉起的游戏在运行（「关闭游戏进程」悬浮按钮据此显隐，支持多实例） */
    public static boolean isGameRunning() {
        return GameInstanceRegistry.hasRunning();
    }


    // Ⅲ.  微软账号登录 //

    /**
     * 3.1 首次微软登录（设备码流，异步）
     * 需要用户在浏览器中访问 verificationUri 并输入 deviceCode
     */
    public static void microsoftLoginAsync(ResultCallback<MicrosoftAuthResult> callback) {
        ASYNC_POOL.submit(() -> {
            try {
                MinecraftAuthLauncherDeviceCode auth = new MinecraftAuthLauncherDeviceCode();
                auth.startAuth();
                // MinecraftAuthLauncherDeviceCode 内部将结果写入文件，
                // 所以我们从文件中读取
                MicrosoftAuthResult result = readMicrosoftAuthResult();
                if (result != null) {
                    callback.onSuccess(result);
                } else {
                    callback.onError(I18N.get("gui.t0033"));
                }
            } catch (Exception e) {
                callback.onError(I18N.get("gui.p0098", e.getMessage()));
            }
        });
    }

    /**
     * 3.2 刷新微软令牌（静默刷新，异步）
     */
    public static void microsoftRefreshAsync(ResultCallback<MicrosoftAuthResult> callback) {
        ASYNC_POOL.submit(() -> {
            try {
                // 优先通过 AccountManager 刷新，确保 accounts.json 同步更新
                Account acc = AccountManager.getCurrentAccount();
                if (acc != null && acc.refreshToken != null && !acc.refreshToken.isEmpty()) {
                    Account updated = AccountManager.refreshMicrosoftSync(acc.refreshToken);
                    MicrosoftAuthResult result = new MicrosoftAuthResult(
                        updated.accessToken, updated.refreshToken, updated.id, updated.name);
                    callback.onSuccess(result);
                } else {
                    // 降级：直接刷新（无 AccountManager 时）
                    MinecraftAuthLauncherDeviceCode auth = new MinecraftAuthLauncherDeviceCode();
                    auth.refreshAndSave();
                    MicrosoftAuthResult result = readMicrosoftAuthResult();
                    callback.onSuccess(result != null ? result
                        : new MicrosoftAuthResult("", "", "", ""));
                }
            } catch (Exception e) {
                callback.onError(I18N.get("gui.p0099", e.getMessage()));
            }
        });
    }

    /** 3.3 检查是否有可用账号 */
    public static boolean isLoggedIn() {
        Account acc = AccountManager.getCurrentAccount();
        return acc != null;
    }

    /** 3.4 获取当前登录玩家名 */
    public static String getLoggedInUserName() {
        Account acc = AccountManager.getCurrentAccount();
        return acc != null ? acc.name : "";
    }

    /** 3.5 获取当前账号 UUID */
    public static String getLoggedInUUID() {
        Account acc = AccountManager.getCurrentAccount();
        return acc != null ? acc.id : "";
    }

    /** 3.6 获取当前账号类型 */
    public static String getCurrentUserType() {
        Account acc = AccountManager.getCurrentAccount();
        return acc != null ? acc.getUserType() : "mojang";
    }

    /** 3.7 获取当前账号 AccessToken */
    public static String getCurrentAccessToken() {
        Account acc = AccountManager.getCurrentAccount();
        return acc != null ? acc.accessToken : "";
    }


    // Ⅳ.  Java 管理 //

    /** 4.1 查找本机所有 Java 安装 */
    public static List<JavaInfo> findAllJava() {
        List<JavaInfo> result = new ArrayList<>();
        // 使用改进后的直接 API
        List<FindAllJavaWindows.JavaEntry> entries = FindAllJavaWindows.findAll();
        for (FindAllJavaWindows.JavaEntry entry : entries) {
            result.add(new JavaInfo(entry.getJavaExePath(), entry.version));
        }
        // 去重（按路径去重）
        Set<String> seen = new HashSet<>();
        result.removeIf(j -> !seen.add(j.homePath.toLowerCase()));
        return result;
    }

    /**
     * 4.1a 自动选择最合适的 Java 路径（参考 HMCL JavaManager.findSuitableJava）。
     * 根据游戏版本确定所需最低 Java 主版本，从本机已安装的 Java 中
     * 选择满足要求且主版本最小的（HMCL chooseJava：更接近推荐版本）；
     * 找不到时返回 null，由调用方回退到现有配置。
     */
    public static String autoSelectJavaPath(String versionId, String gameDir) {
        int requiredMajor = getRequiredJavaMajorFromJson(gameDir, versionId);
        // 优先读扫描缓存（JavaCacheManager），缓存失效时自动重新全盘扫描并写回 json
        List<FindAllJavaWindows.JavaEntry> entries = JavaCacheManager.getAvailable();
        FindAllJavaWindows.JavaEntry best = null;
        for (FindAllJavaWindows.JavaEntry entry : entries) {
            if (entry.majorVersion < requiredMajor) continue;
            if (best == null || entry.majorVersion < best.majorVersion) best = entry;
        }
        return best != null ? best.getJavaExePath() : null;
    }

    /**
     * 读取某版本所需的 Java 主版本：<b>优先用 version.json 的权威字段
     * {@code javaVersion.majorVersion}</b>（现代版本必带），读不到时退回按版本名猜。
     *
     * <p>版本名是自定义中文名（如「自然之需YSM兼容整合」）时，旧实现用正则从版本名猜
     * 猜不出 MC 版本 → 落到「需要 Java 8」，于是 1.21.11 的 Fabric 整合包被用 Java 8 启动，
     * Fabric Loader 报「需要 java 21」。json 里其实写死了 {@code javaVersion: {majorVersion: 21}}，
     * 直接读它即可，覆盖整合包 / 自定义命名 / 周版本快照等所有无法从名字猜的场景。
     */
    public static int getRequiredJavaMajorFromJson(String gameDir, String versionId) {
        // 沿继承链找到根版本 JSON，读其 javaVersion.majorVersion
        String current = versionId;
        Set<String> visited = new HashSet<>();
        while (current != null && !current.isEmpty() && visited.add(current)) {
            Path json = Paths.get(gameDir == null ? "" : gameDir, "versions", current, current + ".json");
            if (!Files.isRegularFile(json)) break;
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
                if (obj != null && obj.has("javaVersion") && obj.get("javaVersion").isJsonObject()) {
                    JsonObject jv = obj.getAsJsonObject("javaVersion");
                    if (jv.has("majorVersion") && jv.get("majorVersion").isJsonPrimitive()) {
                        int major = parseIntSafe(jv.get("majorVersion").getAsString(), 0);
                        if (major > 0) return major;
                    }
                }
                if (obj == null || !obj.has("inheritsFrom")) break; // 根版本
                current = obj.get("inheritsFrom").getAsString();
            } catch (Exception e) {
                break;
            }
        }
        // 兜底：按版本名猜（旧逻辑）
        return getRequiredJavaMajor(resolveRealMcVersion(gameDir, versionId));
    }

    /** 沿 version.json 的 inheritsFrom 继承链解析真实 Minecraft 版本（链根 = 无 inheritsFrom 的版本） */
    public static String resolveRealMcVersion(String gameDir, String versionId) {
        Set<String> visited = new HashSet<>();
        String current = versionId;
        while (current != null && !current.isEmpty() && visited.add(current)) {
            Path json = Paths.get(gameDir, "versions", current, current + ".json");
            if (!Files.isRegularFile(json)) break;
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
                if (obj == null || !obj.has("inheritsFrom")) return current; // 根版本即真实 MC 版本
                current = obj.get("inheritsFrom").getAsString();
            } catch (Exception e) {
                break;
            }
        }
        // 链解析失败时回退到版本名前缀提取（如 "26.2-Fabric 0.19.3" → "26.2"）
        return current != null ? current : extractMcVersion(versionId);
    }

    /**
     * 根据 MC 版本返回所需最低 Java 主版本（参考 HMCL GameJavaVersion.getMinimumJavaVersion）：
     * 26.1+ → 25，1.20.5+ → 21，1.18+ → 17，1.17+ → 16，其余 → 8
     */
    public static int getRequiredJavaMajor(String mcVersion) {
        // 防御：完整版本名（如 "26.2-Fabric 0.19.3"）只取 MC 版本号部分再比较
        mcVersion = extractMcVersion(mcVersion);
        if (compareMcVersion(mcVersion, "26.1") >= 0) return 25;
        if (compareMcVersion(mcVersion, "1.20.5") >= 0) return 21;
        if (compareMcVersion(mcVersion, "1.18") >= 0) return 17;
        if (compareMcVersion(mcVersion, "1.17") >= 0) return 16;
        return 8;
    }

    /**
     * 提取版本 ID 开头的 MC 版本号（如 "1.20.4-fabric-0.15.0" → "1.20.4"）。
     *
     * <p>周版本快照（如 {@code 26w14a}）没有「数字.数字」结构，旧实现原样返回后会被
     * {@link #compareMcVersion} 当非数字段（按 0）比较，一路落到「需要 Java 8」，
     * 导致自动选择 Java 在快照版本上静默失效。这里把快照按年份归入对应发布线，
     * 使 {@link #getRequiredJavaMajor} 能正确判定（26w* → 26.x → Java 25，等）。
     */
    public static String extractMcVersion(String versionId) {
        if (versionId == null) return "";
        String trimmed = versionId.trim();
        // 周版本快照：YYwNN[letter]（如 26w14a / 25w14a）→ 归入对应发布线
        java.util.regex.Matcher w = java.util.regex.Pattern.compile("^(\\d{2})w\\d{2}[a-z]?")
                .matcher(trimmed);
        if (w.find()) {
            int year = parseIntSafe(w.group(1), 0);
            if (year >= 26) return "26.1";
            if (year == 25) return "1.21";
            if (year == 24) return "1.20.5";
            if (year == 23) return "1.20";
            if (year == 22) return "1.19";
            if (year == 21) return "1.18";
            if (year == 20) return "1.17";
            return "1.16";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+\\.\\d+(?:\\.\\d+)?)")
                .matcher(trimmed);
        return m.find() ? m.group(1) : trimmed;
    }

    /** 逐段数字比较版本号（a >= b 返回 >= 0），非数字段按 0 处理 */
    public static int compareMcVersion(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int na = i < pa.length ? parseIntSafe(pa[i], 0) : 0;
            int nb = i < pb.length ? parseIntSafe(pb[i], 0) : 0;
            if (na != nb) return Integer.compare(na, nb);
        }
        return 0;
    }

    /** 4.2 安装 Java JDK（异步） */
    public static void installJavaAsync(String version, String downloadDir,
                                         ProgressCallback onProgress,
                                         ResultCallback<String> onResult) {
        ASYNC_POOL.submit(() -> {
            try {
                // 内嵌模式：JavaInstall 失败时抛异常而非 System.exit，避免杀死启动器进程
                System.setProperty("starlight.embed", "true");
                ByteArrayOutputStream baos = new ByteArrayOutputStream() {
                    private int lastPercent = -1;
                    @Override
                    public synchronized void write(byte[] b, int off, int len) {
                        super.write(b, off, len);
                        String chunk = new String(b, off, len, StandardCharsets.UTF_8);
                        // 尝试提取百分比
                        int pIdx = chunk.indexOf('%');
                        if (pIdx > 0) {
                            for (int i = pIdx - 1; i >= 0; i--) {
                                if (!Character.isDigit(chunk.charAt(i))) {
                                    String pStr = chunk.substring(i + 1, pIdx);
                                    try {
                                        int pct = Integer.parseInt(pStr);
                                        if (pct != lastPercent) {
                                            lastPercent = pct;
                                            onProgress.onProgress(pct, chunk.trim());
                                        }
                                    } catch (NumberFormatException ignored) {}
                                    break;
                                }
                            }
                        }
                        // i18n-lock: 不要翻译此行中文——解析启动日志输出，翻译即功能失效
                        if (chunk.contains("ERROR") || chunk.contains("失败") || chunk.contains("完成")) {
                            onProgress.onProgress(lastPercent < 0 ? 50 : lastPercent, chunk.trim());
                        }
                    }
                };
                // 只注入 JavaInstall 自身的输出流，不重定向全局 System.out
                PrintStream captured = new PrintStream(baos, true, StandardCharsets.UTF_8);
                JavaInstall.setOutput(captured);

                List<String> args = new ArrayList<>(List.of(version, "--json"));
                if (downloadDir != null && !downloadDir.isEmpty()) args.add(downloadDir);
                JavaInstall.main(args.toArray(String[]::new));

                captured.flush();
                onProgress.onProgress(100, I18N.get("gui.t0034"));
                onResult.onSuccess(I18N.get("uigc.r0002", version));

            } catch (Exception e) {
                onResult.onError(I18N.get("gui.p0100", e.getMessage()));
            } finally {
                // 恢复默认输出，避免影响后续调用
                JavaInstall.setOutput(System.out);
            }
        });
    }

    /** 4.3 列出可安装的 JDK 版本 */
    public static List<String> getAvailableJdkVersions() {
        return List.of("jdk8", "jdk17", "jdk21");
    }


    // Ⅴ.  存档管理 //

    /**
     * 5.1 列出 Minecraft 存档
     * @param mcDir  .minecraft 目录路径
     * @param versionName  版本名称
     */
    public static List<SaveInfo> listSaves(String mcDir, String versionName) {
        String savesDir = mcDir + "/saves";
        String verDir = mcDir + "/versions/" + versionName;

        // 拦截 System.out / System.err 输出
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try {
            List<String> capturedLines = new ArrayList<>();
            PrintStream capturingPs = new PrintStream(new OutputStream() {
                final StringBuilder sb = new StringBuilder();
                @Override
                public void write(int b) {
                    sb.append((char) b);
                    if (b == '\n') { capturedLines.add(sb.toString().stripTrailing()); sb.setLength(0); }
                }
                @Override
                public void write(byte[] b, int off, int len) {
                    for (int i = off; i < off + len; i++) write(b[i]);
                }
            }, true, StandardCharsets.UTF_8);

            System.setOut(capturingPs);
            System.setErr(capturingPs);

            listsaves.main(new String[]{savesDir, verDir});

            System.out.flush();
            System.err.flush();

            return parseSavesFromOutput(capturedLines, versionName);

        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    /** 5.2 快速列出存档（自动从配置读取路径） */
    public static List<SaveInfo> listSaves() {
        Map<String, String> cfg = readConfig();
        String gameDir = cfg.getOrDefault("GameDir", ".minecraft");
        String version = SelectedVersion.of(gameDir);
        if (version.isEmpty()) return Collections.emptyList();
        return listSaves(gameDir, version);
    }


    /**
     * 5.3 列出最近游玩的存档（按最后修改时间倒序，可选 limit）。
     * 自动适配版本隔离（同时扫描全局 saves 与当前版本 saves），
     * 直接遍历目录并解析 level.dat 显示名，不依赖控制台输出链路。
     */
    public static List<SaveInfo> listRecentSaves(int limit) {
        Map<String, String> cfg = readConfig();
        String gameDir = cfg.getOrDefault("GameDir", ".minecraft");
        String version = SelectedVersion.of(gameDir);
        boolean versionIsolation = "true".equalsIgnoreCase(cfg.getOrDefault("VersionIsolation", "false"));

        List<File> roots = new ArrayList<>();
        roots.add(new File(gameDir, "saves"));
        if (versionIsolation && version != null && !version.isEmpty()) {
            roots.add(new File(gameDir, "versions" + File.separator + version + File.separator + "saves"));
        }

        List<SaveInfo> saves = new ArrayList<>();
        for (File root : roots) {
            if (!root.isDirectory()) continue;
            File[] dirs = root.listFiles(File::isDirectory);
            if (dirs == null) continue;
            for (File dir : dirs) {
                File levelDat = new File(dir, "level.dat");
                if (!levelDat.isFile()) continue;
                String worldName = listsaves.parseLevelName(dir);
                if (worldName == null || worldName.isEmpty()) worldName = dir.getName();
                // 存档真实版本（level.dat Data.Version.Name），解析失败回退到当前配置版本
                String levelVersion = listsaves.parseLevelVersion(dir);
                if (levelVersion == null || levelVersion.isEmpty()) levelVersion = version;
                saves.add(new SaveInfo(worldName, dir.getName(), dir.getAbsolutePath(), levelVersion));
            }
        }
        // 按最近修改（最后游玩）时间倒序
        saves.sort((a, b) -> Long.compare(new File(b.path).lastModified(), new File(a.path).lastModified()));
        if (limit > 0 && saves.size() > limit) {
            return new ArrayList<>(saves.subList(0, limit));
        }
        return saves;
    }

    // Ⅶ.  日志管理（上传 + 二维码） //

    /** 7.1 上传日志并生成二维码 */
    public static ActionResult uploadLogAndGenerateQR(String logFilePath, String serverUrl) {
        try {
            if (serverUrl == null || serverUrl.isEmpty()) serverUrl = DEFAULT_SERVER_URL;
            String resultUrl = sendlogs.uploadLogFile(logFilePath, serverUrl);
            BufferedImage qrImage = QRcode.generateQRCodeImage(resultUrl);

            String fileName = new SimpleDateFormat("yyyyMMdd").format(new Date())
                            + String.format("%03d", new Random().nextInt(1000)) + ".png";
            File outputFile = new File(fileName);
            ImageIO.write(qrImage, "png", outputFile);

            return ActionResult.ok(I18N.get("gui.t0035"),
                new LogUploadResult(resultUrl, qrImage, outputFile.getAbsolutePath()));
        } catch (WriterException | IOException e) {
            return ActionResult.fail(I18N.get("gui.p0101", e.getMessage()));
        }
    }

    /** 7.2 仅上传日志，获取链接 */
    public static ActionResult uploadLog(String logFilePath, String serverUrl) {
        try {
            if (serverUrl == null || serverUrl.isEmpty()) serverUrl = DEFAULT_SERVER_URL;
            String resultUrl = sendlogs.uploadLogFile(logFilePath, serverUrl);
            return ActionResult.ok(I18N.get("gui.t0036"), resultUrl);
        } catch (IOException e) {
            return ActionResult.fail(I18N.get("gui.p0102", e.getMessage()));
        }
    }

    /** 7.3 仅生成二维码图片 */
    public static BufferedImage generateQRImage(String url) throws WriterException, IOException {
        return QRcode.generateQRCodeImage(url);
    }

    /**
     * 7.4 查找最新的错误日志文件
     * 优先采用启动器自身 logs/ 下的 minecraft_error_*.log / jvm_error_*.log（本次启动的 JVM stderr，
     * 时间戳文件名天然新鲜，无误读风险）；其次才搜索游戏目录，其中 latest.log 修改时间早于
     * launchStartTime 视为旧日志不采用。
     * @param launchStartTime 本次启动开始时间戳(ms)
     */
    public static String findLatestErrorLog(String gameDir, long launchStartTime) {
        // 优先搜索启动器自身的 logs/ 目录下的 minecraft_error_*.log（包含 JVM stderr，每次启动独立命名）
        Path launcherLogs = Paths.get(LOG_DIR);
        if (Files.isDirectory(launcherLogs)) {
            try (Stream<Path> stream = Files.list(launcherLogs)) {
                String found = stream
                    .filter(p -> p.getFileName().toString().matches("(minecraft_error_|jvm_error_).*\\.log"))
                    .sorted(Comparator.<Path, Long>comparing(p -> {
                        try { return Files.getLastModifiedTime(p).toMillis(); }
                        catch (IOException e) { return 0L; }
                    }).reversed())
                    .findFirst()
                    .map(p -> p.toAbsolutePath().toString())
                    .orElse(null);
                if (found != null) return found;
            } catch (IOException ignored) {}
        }

        // 在游戏日志目录查找
        Path logsDir = Paths.get(gameDir, "logs");
        if (!Files.isDirectory(logsDir)) logsDir = Paths.get(gameDir);
        if (!Files.isDirectory(logsDir)) {
            logsDir = launcherLogs;
        }
        if (!Files.isDirectory(logsDir)) return null;

        Path finalLogsDir = logsDir;
        try (Stream<Path> stream = Files.list(finalLogsDir)) {
            List<Path> candidatesList = stream
                .filter(p -> p.getFileName().toString().endsWith(".log"))
                .filter(p -> p.getFileName().toString().contains("error")
                          || p.getFileName().toString().contains("crash")
                          || (p.getFileName().toString().equals("latest.log")
                              && isLogFresh(p, launchStartTime)))
                .sorted(Comparator.<Path, Long>comparing(p -> {
                    try { return Files.getLastModifiedTime(p).toMillis(); }
                    catch (IOException e) { return 0L; }
                }).reversed())
                .toList();

            if (!candidatesList.isEmpty()) return candidatesList.get(0).toAbsolutePath().toString();

            // 兜底：取最新的 .log（同样排除启动开始前遗留的旧 latest.log）
            List<Path> allLogs = Files.list(finalLogsDir)
                .filter(p -> p.getFileName().toString().endsWith(".log"))
                .filter(p -> !(p.getFileName().toString().equals("latest.log")
                        && !isLogFresh(p, launchStartTime)))
                .sorted(Comparator.<Path, Long>comparing(p -> {
                    try { return Files.getLastModifiedTime(p).toMillis(); }
                    catch (IOException e) { return 0L; }
                }).reversed())
                .toList();
            if (!allLogs.isEmpty()) return allLogs.get(0).toAbsolutePath().toString();
        } catch (IOException ignored) {}
        return null;
    }

    /** 判断日志文件是否在本次启动开始之后被写入（避免误读上一次启动留下的旧日志） */
    private static boolean isLogFresh(Path p, long launchStartTime) {
        try { return Files.getLastModifiedTime(p).toMillis() >= launchStartTime; }
        catch (IOException e) { return false; }
    }


    // Ⅷ.  AI 分析错误日志 //

    /**
     * 8.1 AI 分析错误日志（异步）
     */
    public static void aiAnalyzeLogAsync(String logFilePath,
                                          ProgressCallback onProgress,
                                          ResultCallback<AIAnalysisResult> callback) {
        ASYNC_POOL.submit(() -> {
            try {
                onProgress.onProgress(10, I18N.get("gui.t0037"));
                File logFile = new File(logFilePath);
                if (!logFile.exists()) {
                    callback.onError(I18N.get("gui.p0103", logFilePath));
                    return;
                }

                onProgress.onProgress(30, I18N.get("gui.t0038"));
                String logContent = Files.readString(logFile.toPath(), StandardCharsets.UTF_8);
                String question = """
                                  \u8bf7\u8be6\u7ec6\u9605\u8bfb\u8fd9\u4e2aMinecraft\u9519\u8bef\u65e5\u5fd7\uff0c\u6709\u4ec0\u4e48\u95ee\u9898\uff0c\u56de\u7b54\u8bf7\u4ee5json\u683c\u5f0f\uff0c\u76f4\u63a5\u7ed9\u6211json\u5185\u5bb9\uff0c\u683c\u5f0f\u4e25\u683c\u6309\u7167json\u6587\u4ef6\u7684\u683c\u5f0f\uff0c\u6709zh-cn\u5b57\u6bb5\uff08\u662f\u4e2d\u6587\u9519\u8bef\u539f\u56e0\u663e\u793a\uff09\uff0c\u8fd8\u8981\u6709\u82f1\u6587\u7684\u9519\u8bef\u539f\u56e0\u663e\u793a
                                  
                                  \u65e5\u5fd7\u5185\u5bb9\u5982\u4e0b\uff1a
                                  """ + logContent;

                String response = LongCatChat.chat(question);
                response = removeMarkdownCodeBlocks(response);

                onProgress.onProgress(80, I18N.get("gui.t0039"));

                // 尝试从 JSON 中提取中英文原因
                String chineseReason = "";
                String englishReason = "";
                try {
                    JsonObject json = new Gson().fromJson(response, JsonObject.class);
                    if (json.has("zh-cn")) chineseReason = json.get("zh-cn").getAsString();
                    if (json.has("en")) englishReason = json.get("en").getAsString();
                    if (json.has("english")) englishReason = json.get("english").getAsString();
                } catch (JsonSyntaxException ignored) {}

                // 导出到文件
                String outputPath = "ai_response.json";
                exportResponseToJson(response, outputPath, 4);

                onProgress.onProgress(100, I18N.get("gui.t0040"));
                callback.onSuccess(new AIAnalysisResult(response, chineseReason, englishReason));

            } catch (IOException e) {
                callback.onError(I18N.get("gui.p0104", e.getMessage()));
            }
        });
    }

    /** 8.2 同步 AI 分析（返回原始 JSON 字符串） */
    public static ActionResult aiAnalyzeLogSync(String logFilePath) {
        File logFile = new File(logFilePath);
        if (!logFile.exists()) return ActionResult.fail(I18N.get("gui.t0041"));
        try {
            String logContent = Files.readString(logFile.toPath(), StandardCharsets.UTF_8);
            String question = """
                              \u8bf7\u8be6\u7ec6\u9605\u8bfb\u8fd9\u4e2aMinecraft\u9519\u8bef\u65e5\u5fd7\uff0c\u6709\u4ec0\u4e48\u95ee\u9898\uff0c\u56de\u7b54\u8bf7\u4ee5json\u683c\u5f0f\uff0c\u76f4\u63a5\u7ed9\u6211json\u5185\u5bb9\uff0c\u683c\u5f0f\u4e25\u683c\u6309\u7167json\u6587\u4ef6\u7684\u683c\u5f0f
                              
                              \u65e5\u5fd7\u5185\u5bb9\u5982\u4e0b\uff1a
                              """ + logContent;
            String response = LongCatChat.chat(question);
            response = removeMarkdownCodeBlocks(response);
            return ActionResult.ok(I18N.get("gui.t0042"), response);
        } catch (IOException e) {
            return ActionResult.fail(I18N.get("gui.p0105", e.getMessage()));
        }
    }


    // Ⅸ.  多线程下载 //

    /**
     * 9.1 下载文件（异步，带进度）
     */
    public static void downloadFileAsync(String url, String savePath, int threadCount,
                                          ProgressCallback onProgress,
                                          ResultCallback<String> onResult) {
        // JUDGE-2：删除原 MultiThreadDownloader + System.out 劫持实现，统一委托 download 根目录门面；
        // threadCount 为兼容占位，实际线程数由 HttpDownloadEngine 内部策略决定
        DownloadService.downloadFileAsync(url, savePath, threadCount,
                (pct, msg) -> { if (onProgress != null) onProgress.onProgress(pct, msg); },
                new CallbackInterfaces.ResultCallback<String>() {
                    @Override public void onSuccess(String result) {
                        if (onResult != null) onResult.onSuccess(result);
                    }
                    @Override public void onError(String error) {
                        if (onResult != null) onResult.onError(error);
                    }
                });
    }


    // Ⅹ.  模组 / 资源包 / 光影 / 配置文件管理 //
    // 10. 模组管理 //
    /** 10.1 列出所有模组 */
    public static List<ModInfo> listMods(String gameDir, String version) {
        List<ModInfo> mods = new ArrayList<>();
        Path modsDir = Paths.get(gameDir, "mods");
        Path versionModsDir = version.isEmpty() ? null : Paths.get(gameDir, "versions", version, "mods");

        if (Files.isDirectory(modsDir)) scanModDir(modsDir, I18N.get("gui.t0043"), mods);
        if (versionModsDir != null && Files.isDirectory(versionModsDir)) scanModDir(versionModsDir, I18N.get("gui.t0044"), mods);

        mods.sort(Comparator.comparing(m -> m.name));
        return mods;
    }

    /** 10.2 切换模组启用/禁用 */
    public static ActionResult toggleMod(String modPath) {
        try {
            Path path = Paths.get(modPath);
            String fname = path.getFileName().toString();
            Path newPath;
            if (fname.endsWith(".disabled")) {
                String newName = fname.substring(0, fname.length() - 9);
                newPath = path.resolveSibling(newName);
                Files.move(path, newPath);
                return ActionResult.ok(I18N.get("gui.p0106", newName));
            } else {
                newPath = path.resolveSibling(fname + ".disabled");
                Files.move(path, newPath);
                return ActionResult.ok(I18N.get("gui.p0107", fname));
            }
        } catch (IOException e) {
            return ActionResult.fail(I18N.get("gui.p0108", e.getMessage()));
        }
    }

    /** 10.3 获取模组目录路径 */
    public static String getModsDir(String gameDir, String version) {
        Path dir = Paths.get(gameDir, "versions", version, "mods");
        if (Files.isDirectory(dir)) return dir.toAbsolutePath().toString();
        dir = Paths.get(gameDir, "mods");
        return dir.toAbsolutePath().toString();
    }


    // 11. 资源包管理 //
    /** 11.1 列出资源包 */
    public static List<PackInfo> listResourcePacks(String gameDir) {
        Path rpDir = Paths.get(gameDir, "resourcepacks");
        if (!Files.isDirectory(rpDir)) return Collections.emptyList();

        Set<String> activePacks = readActiveResourcePacks(gameDir);

        List<PackInfo> packs = new ArrayList<>();
        try (Stream<Path> stream = Files.list(rpDir)) {
            stream
                .filter(p -> Files.isDirectory(p) || p.getFileName().toString().endsWith(".zip"))
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .forEach(p -> {
                    String name = p.getFileName().toString();
                    boolean active = activePacks.contains(name)
                        || activePacks.contains(name.replace(".zip", ""));
                    packs.add(new PackInfo(name, p.toAbsolutePath().toString(), active));
                });
        } catch (IOException ignored) {}
        return packs;
    }


    // 12. 光影包管理 //
    /** 12.1 列出光影包 */
    public static List<PackInfo> listShaderPacks(String gameDir) {
        Path shaderDir = Paths.get(gameDir, "shaderpacks");
        if (!Files.isDirectory(shaderDir)) return Collections.emptyList();

        List<PackInfo> packs = new ArrayList<>();
        try (Stream<Path> stream = Files.list(shaderDir)) {
            stream
                .filter(p -> Files.isDirectory(p) || p.getFileName().toString().endsWith(".zip"))
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .forEach(p -> packs.add(new PackInfo(p.getFileName().toString(),
                    p.toAbsolutePath().toString(), false)));
        } catch (IOException ignored) {}
        return packs;
    }


    // 13. 模组配置文件管理 //
    /** 13.1 列出模组配置文件 */
    public static List<ConfigFileInfo> listConfigFiles(String gameDir) {
        Path cfgDir = Paths.get(gameDir, "config");
        if (!Files.isDirectory(cfgDir)) return Collections.emptyList();

        List<ConfigFileInfo> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(cfgDir)) {
            stream
                .filter(p -> !Files.isDirectory(p))
                .filter(p -> {
                    String n = p.getFileName().toString().toLowerCase();
                    return n.endsWith(".cfg") || n.endsWith(".toml")
                        || n.endsWith(".json") || n.endsWith(".txt")
                        || n.endsWith(".yaml") || n.endsWith(".yml");
                })
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .forEach(p -> {
                    try {
                        files.add(new ConfigFileInfo(p.getFileName().toString(),
                            p.toAbsolutePath().toString(), Files.size(p)));
                    } catch (IOException e) {
                        files.add(new ConfigFileInfo(p.getFileName().toString(),
                            p.toAbsolutePath().toString(), 0));
                    }
                });
        } catch (IOException ignored) {}
        return files;
    }

    /** 13.2 读取配置文件内容 */
    public static ActionResult readConfigFileContent(String filePath) {
        try {
            String content = Files.readString(Paths.get(filePath), StandardCharsets.UTF_8);
            return ActionResult.ok(I18N.get("gui.t0045"), content);
        } catch (IOException e) {
            return ActionResult.fail(I18N.get("gui.p0109", e.getMessage()));
        }
    }


    // Ⅺ.  原生库管理 //
    /** 11.1 检查原生库状态 */
    public static NativesStatus checkNativesStatus(String gameDir, String version) {
        Path verDir = Paths.get(gameDir, "versions", version);
        if (!Files.isDirectory(verDir)) return new NativesStatus(false, 0, Collections.emptyList());

        try (Stream<Path> stream = Files.list(verDir)) {
            List<Path> nativeDirs = stream
                .filter(Files::isDirectory)
                .filter(p -> p.getFileName().toString().contains("natives"))
                .toList();

            for (Path dir : nativeDirs) {
                try (Stream<Path> files = Files.list(dir)) {
                    List<String> dlls = files
                        .filter(p -> {
                            String n = p.getFileName().toString().toLowerCase();
                            return n.endsWith(".dll") || n.endsWith(".so") || n.endsWith(".dylib");
                        })
                        .map(p -> p.getFileName().toString())
                        .sorted()
                        .toList();
                    if (!dlls.isEmpty()) {
                        return new NativesStatus(true, dlls.size(), dlls);
                    }
                }
            }
        } catch (IOException ignored) {}
        return new NativesStatus(false, 0, Collections.emptyList());
    }

    /** 11.2 补全原生库（异步） */
    public static void complementNativesAsync(String gameDir, String version,
                                               ProgressCallback onProgress,
                                               ResultCallback<Boolean> onResult) {
        ASYNC_POOL.submit(() -> {
            try {
                onProgress.onProgress(10, I18N.get("gui.t0046"));
                Path jsonPath = Paths.get(gameDir, "versions", version, version + ".json");
                if (!Files.exists(jsonPath)) {
                    onResult.onError(I18N.get("gui.t0047"));
                    return;
                }

                String jsonStr = Files.readString(jsonPath, StandardCharsets.UTF_8);
                JsonObject versionJson = new Gson().fromJson(jsonStr, JsonObject.class);

                onProgress.onProgress(30, I18N.get("gui.t0048"));
                LaunchInfo info = new LaunchInfo();
                info.setGameDirPath(Path.of(gameDir));
                info.setVersion(version);
                info.setJavaPath("java");
                // 隔离开关走版本有效配置而非硬编码：processNatives 目前虽不读它，
                // 但任何后续接入隔离语义的启动库逻辑都不应在这里被静默关掉
                info.setVersionIsolation(VersionConfigManager.effectiveVersionIsolation(
                        readConfig(), gameDir, version));

                VanillaLauncher launcher = new VanillaLauncher(info);
                launcher.initPaths();
                launcher.setVersionJson(versionJson);

                onProgress.onProgress(60, I18N.get("gui.t0049"));
                boolean ok = launcher.processNatives();
                if (!ok) {
                    onResult.onError(I18N.get("gui.t0050"));
                    return;
                }

                onProgress.onProgress(90, I18N.get("gui.t0051"));
                runNativeCleanup(gameDir, version);

                onProgress.onProgress(100, I18N.get("gui.t0052"));
                onResult.onSuccess(true);

            } catch (JsonSyntaxException | IOException e) {
                onResult.onError(I18N.get("gui.p0110", e.getMessage()));
            }
        });
    }


    // Ⅻ.  游戏设置编辑 (options.txt) //
    /** 12.1 读取 options.txt */
    public static Map<String, String> readOptionsTxt(String gameDir) {
        Path optFile = Paths.get(gameDir, "options.txt");
        if (!Files.exists(optFile)) return Collections.emptyMap();

        Map<String, String> options = new LinkedHashMap<>();
        try {
            List<String> lines = Files.readAllLines(optFile, StandardCharsets.UTF_8);
            for (String line : lines) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    options.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
                }
            }
        } catch (IOException ignored) {}
        return options;
    }

    /** 12.2 修改 options.txt 中的某个值 */
    public static ActionResult updateOptionsTxt(String gameDir, String key, String newValue) {
        Path optFile = Paths.get(gameDir, "options.txt");
        if (!Files.exists(optFile)) return ActionResult.fail(I18N.get("gui.t0053"));

        try {
            List<String> lines = Files.readAllLines(optFile, StandardCharsets.UTF_8);
            boolean found = false;
            for (int i = 0; i < lines.size(); i++) {
                int colon = lines.get(i).indexOf(':');
                if (colon > 0 && lines.get(i).substring(0, colon).trim().equals(key)) {
                    lines.set(i, key + ":" + newValue);
                    found = true;
                    break;
                }
            }
            if (!found) {
                lines.add(key + ":" + newValue);
            }
            Files.write(optFile, lines, StandardCharsets.UTF_8);
            return ActionResult.ok(I18N.get("gui.p0111", key + " = " + newValue));
        } catch (IOException e) {
            return ActionResult.fail(I18N.get("gui.p0112", e.getMessage()));
        }
    }


    // XIII.  启动器档案信息 (launcher_profiles.json) //
    /** 13.1 读取启动器档案信息 */
    public static LauncherProfileInfo readLauncherProfiles(String gameDir) {
        Path profFile = Paths.get(gameDir, "launcher_profiles.json");
        if (!Files.exists(profFile)) return null;

        try {
            String jsonStr = Files.readString(profFile, StandardCharsets.UTF_8);
            JsonObject root = new Gson().fromJson(jsonStr, JsonObject.class);

            String username = "";
            String displayName = "";
            int accountCount = 0;

            if (root.has("authenticationDatabase")) {
                JsonObject authDb = root.getAsJsonObject("authenticationDatabase");
                accountCount = authDb.size();
                for (String key : authDb.keySet()) {
                    JsonObject acct = authDb.getAsJsonObject(key);
                    if (acct.has("username")) username = acct.get("username").getAsString();
                    if (acct.has("displayName")) displayName = acct.get("displayName").getAsString();
                    break;
                }
            }

            Map<String, String> profiles = new LinkedHashMap<>();
            if (root.has("profiles")) {
                JsonObject profObj = root.getAsJsonObject("profiles");
                for (String key : profObj.keySet()) {
                    JsonObject prof = profObj.getAsJsonObject(key);
                    String name = prof.has("name") ? prof.get("name").getAsString() : key;
                    String lastVer = prof.has("lastVersionId") ? prof.get("lastVersionId").getAsString() : I18N.get("gui.t0054");
                    profiles.put(name, lastVer);
                }
            }

            return new LauncherProfileInfo(username, displayName, accountCount, profiles);

        } catch (JsonSyntaxException | IOException e) {
            return null;
        }
    }


    // XIV.  文件系统工具 //
    /** 14.1 打开文件/目录（操作系统默认程序） */
    public static ActionResult openInExplorer(String path) {
        try {
            File file = new File(path);
            if (!file.exists()) return ActionResult.fail(I18N.get("gui.p0113", path));
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                Runtime.getRuntime().exec(new String[]{"explorer.exe", "/select,", file.getAbsolutePath()});
            } else if (os.contains("mac")) {
                Runtime.getRuntime().exec(new String[]{"open", file.getParent()});
            } else {
                Runtime.getRuntime().exec(new String[]{"xdg-open", file.getParent()});
            }
            return ActionResult.ok(I18N.get("gui.t0055"));
        } catch (IOException e) {
            return ActionResult.fail(I18N.get("gui.p0114", e.getMessage()));
        }
    }

    /** 14.2 打开目录 */
    public static ActionResult openDirectory(String path) {
        try {
            File dir = new File(path);
            if (!dir.isDirectory()) return ActionResult.fail(I18N.get("gui.p0115", path));
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                Runtime.getRuntime().exec(new String[]{"explorer.exe", dir.getAbsolutePath()});
            } else if (os.contains("mac")) {
                Runtime.getRuntime().exec(new String[]{"open", dir.getAbsolutePath()});
            } else {
                Runtime.getRuntime().exec(new String[]{"xdg-open", dir.getAbsolutePath()});
            }
            return ActionResult.ok(I18N.get("gui.t0056"));
        } catch (IOException e) {
            return ActionResult.fail(I18N.get("gui.p0116", e.getMessage()));
        }
    }

    /** 14.3 打开 URL（默认浏览器） */
    public static ActionResult openUrl(String url) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                Runtime.getRuntime().exec(new String[]{"cmd", "/c", "start", url});
            } else if (os.contains("mac")) {
                Runtime.getRuntime().exec(new String[]{"open", url});
            } else {
                Runtime.getRuntime().exec(new String[]{"xdg-open", url});
            }
            return ActionResult.ok(I18N.get("gui.t0057"));
        } catch (IOException e) {
            return ActionResult.fail(I18N.get("gui.p0117", e.getMessage()));
        }
    }


    // XV.  版本管理（查询 + 下载） //
    /** 15.1 获取 Minecraft 版本列表 */
    public static List<VersionManifest.VersionEntry> fetchVersionList() {
        return VersionManifest.fetchVersionList();
    }

    /** 15.2 按类型筛选版本 */
    public static List<VersionManifest.VersionEntry> filterVersionsByType(
            List<VersionManifest.VersionEntry> versions, String type) {
        return VersionManifest.filterByType(versions, type);
    }

    /** 15.3 下载指定版本（异步） */
    public static void downloadVersionAsync(String versionId, String gameDir,
                                            ProgressCallback progress, ResultCallback<Boolean> onResult) {
        ASYNC_POOL.submit(() -> {
            VersionManifest.ProgressCallback adapter =
                (pct, msg) -> { if (progress != null) progress.onProgress(pct, msg); };
            boolean ok = VersionManifest.downloadVersion(versionId, gameDir, adapter);
            if (ok && onResult != null) onResult.onSuccess(true);
            else if (!ok && onResult != null) onResult.onError(I18N.get("gui.p0118", versionId));
        });
    }


    // XVI.  资源文件 & 库文件下载 //
    /** 16.1 下载资源索引文件 */
    public static void downloadAssetIndexAsync(String assetId, String gameDir,
                                               ProgressCallback progress, ResultCallback<Boolean> onResult) {
        ASYNC_POOL.submit(() -> {
            AssetDownloader.ProgressCallback adapter =
                (pct, msg) -> { if (progress != null) progress.onProgress(pct, msg); };
            boolean ok = AssetDownloader.downloadAssetIndex(assetId, gameDir, adapter);
            if (ok && onResult != null) onResult.onSuccess(true);
            else if (!ok && onResult != null) onResult.onError(I18N.get("gui.t0058"));
        });
    }

    /** 16.2 下载所有资源文件 */
    public static void downloadAssetsAsync(String gameDir,
                                           ProgressCallback progress, ResultCallback<Boolean> onResult) {
        ASYNC_POOL.submit(() -> {
            AssetDownloader.ProgressCallback adapter =
                (pct, msg) -> { if (progress != null) progress.onProgress(pct, msg); };
            boolean ok = AssetDownloader.downloadAssets(gameDir, adapter);
            if (ok && onResult != null) onResult.onSuccess(true);
            else if (!ok && onResult != null) onResult.onError(I18N.get("gui.t0059"));
        });
    }

    /** 16.3 补全缺失的库文件 */
    public static int downloadMissingLibraries(String gameDir, String versionJson,
                                               ProgressCallback progress) {
        LibraryDownloader.ProgressCallback adapter =
            (pct, msg) -> { if (progress != null) progress.onProgress(pct, msg); };
        return LibraryDownloader.downloadMissingLibraries(gameDir, versionJson, adapter);
    }

    /** 16.4 补全所有游戏资源（资产文件 + 库文件，异步） */
    public static void completeGameResourcesAsync(String gameDir, String versionId,
                                                   ProgressCallback onProgress,
                                                   ResultCallback<Boolean> onResult) {
        ASYNC_POOL.submit(() -> {
            try {
                boolean ok = DownloadEngineRouter.completeAll(gameDir, versionId,
                    (pct, msg) -> { if (onProgress != null) onProgress.onProgress(pct, msg); });
                if (onResult != null) {
                    if (ok) onResult.onSuccess(true);
                    else onResult.onSuccess(false);
                }
            } catch (Exception e) {
                if (onResult != null) onResult.onError(I18N.get("gui.p0119", e.getMessage()));
            }
        });
    }








    // XVII.  崩溃报告解析 //
    /** 17.1 解析崩溃报告文件 */
    public static CrashReportParser.CrashInfo parseCrashReport(String filePath) {
        return CrashReportParser.parse(filePath);
    }

    /** 17.2 查找最新崩溃报告 */
    public static String findLatestCrashReport(String gameDir) {
        return CrashReportParser.findLatestCrashReport(gameDir);
    }

    /** 17.3 摘要崩溃报告 */
    public static String summarizeCrashReport(String filePath) {
        return CrashReportParser.summarize(filePath);
    }


    // XVIII.  存档备份与恢复 //
    /** 18.1 备份单个存档 */
    public static SaveBackupRestore.ActionResult backupSave(String worldPath, String backupDir) {
        return SaveBackupRestore.backupSave(worldPath, backupDir);
    }

    /** 18.2 备份所有存档 */
    public static java.util.List<String> backupAllSaves(String gameDir, String backupDir) {
        return SaveBackupRestore.backupAllSaves(gameDir, backupDir);
    }

    /** 18.3 列出备份 */
    public static java.util.List<String> listBackups(String backupDir) {
        return SaveBackupRestore.listBackups(backupDir);
    }

    /** 18.4 恢复备份 */
    public static SaveBackupRestore.ActionResult restoreBackup(String backupPath, String gameDir) {
        return SaveBackupRestore.restoreBackup(backupPath, gameDir);
    }


    // XIX.  外置登录 (LittleSkin / Blessing Skin 等) //
    /** 19.1 外置登录 */
    public static ExternalLoginAuth.AuthResult externalLogin(String authServer, String email, String password) {
        return ExternalLoginAuth.login(authServer, email, password);
    }

    /** 19.2 保存外置登录结果 */
    public static void saveExternalAuthResult(ExternalLoginAuth.AuthResult result) {
        ExternalLoginAuth.saveAuthResult(result);
    }

    /** 19.3 读取外置登录结果 */
    public static ExternalLoginAuth.AuthResult readExternalAuthResult() {
        return ExternalLoginAuth.readAuthResult();
    }

    /** 19.4 获取 LittleSkin 的 JVM 参数 */
    public static String getLittleSkinJvmArgs() {
        return "-Dminecraft.api.auth.host=https://littleskin.cn/api/yggdrasil " +
               "-Dminecraft.api.session.host=https://littleskin.cn/api/yggdrasil " +
               "-Dminecraft.api.services.host=https://littleskin.cn/api/yggdrasil";
    }


    // XX.  多账号管理 //
    /** 20.1 列出所有账号 */
    public static List<Account> listAccounts() {
        return AccountManager.listAccounts();
    }

    /** 20.2 获取当前账号 */
    public static Account getCurrentAccount() {
        return AccountManager.getCurrentAccount();
    }

    /** 20.3 切换当前账号 */
    public static boolean switchAccount(String id) {
        return AccountManager.setCurrentAccount(id);
    }

    /** 20.4 创建离线账号 */
    public static Account loginOffline(String playerName) {
        return AccountManager.loginOffline(playerName);
    }

    /** 20.5 微软登录（异步） */
    public static void loginMicrosoftAsync(ResultCallback<Account> callback) {
        ASYNC_POOL.submit(() -> {
            try {
                Account account = AccountManager.loginMicrosoftSync();
                callback.onSuccess(account);
            } catch (Exception e) {
                callback.onError(I18N.get("gui.p0120", e.getMessage()));
            }
        });
    }

    /** 20.6 刷新微软令牌（异步） */
    public static void refreshMicrosoftAsync(String refreshToken, ResultCallback<Account> callback) {
        ASYNC_POOL.submit(() -> {
            try {
                Account account = AccountManager.refreshMicrosoftSync(refreshToken);
                callback.onSuccess(account);
            } catch (Exception e) {
                callback.onError(I18N.get("gui.p0121", e.getMessage()));
            }
        });
    }

    /** 20.7 第三方登录（异步） */
    public static void loginThirdPartyAsync(String authServer, String email, String password,
                                            ResultCallback<Account> callback) {
        ASYNC_POOL.submit(() -> {
            try {
                Account account = AccountManager.loginThirdPartySync(authServer, email, password);
                callback.onSuccess(account);
            } catch (Exception e) {
                callback.onError(I18N.get("gui.p0122", e.getMessage()));
            }
        });
    }

    /** 20.8 删除账号 */
    public static boolean removeAccount(String id) {
        return AccountManager.removeAccount(id);
    }

    /** 20.9 根据当前账号构造 LaunchConfig 的 userType */
    public static void applyCurrentAccount(LaunchConfig config) {
        Account acc = getCurrentAccount();
        if (acc != null) {
            config.userName = acc.name;
            config.uuid = acc.id;
            // 离线皮肤（PCL2 风格）：离线账号按皮肤配置微调 UUID，让游戏按期望模型渲染默认皮肤
            if (acc.type == AccountManager.AccountType.OFFLINE) {
                String resolvedUuid = OfflineSkinManager.resolveUuid(acc.id, acc.getSkin());
                if (resolvedUuid != null && !resolvedUuid.isEmpty()) {
                    config.uuid = resolvedUuid;
                }
            }
            config.accessToken = acc.accessToken;
            config.userType = acc.getUserType();
            config.authServer = acc.authServer != null ? acc.authServer : "";
        }
    }

    /**
     * 20.10 第三方账号启动注入（参考 HMCL AuthlibInjectorAccount.getLaunchArguments）：
     * 当前账号为 THIRD_PARTY 且携带 authServer 时，注入认证服务器参数。
     * 若 config 未携带 authServer（如崩溃重启动的 presetConfig 场景），自动从当前账号补齐。
     * 在 ASYNC_POOL 线程中执行（launchMinecraftAsync 内调用），下载 jar 不会阻塞 UI。
     */
    private static void injectThirdPartyAuth(LaunchConfig config) {
        if (config.authServer == null || config.authServer.isBlank()) {
            Account acc = AccountManager.getCurrentAccount();
            if (acc != null && acc.type == AccountManager.AccountType.THIRD_PARTY
                    && acc.authServer != null && !acc.authServer.isBlank()) {
                config.authServer = acc.authServer;
            }
        }
        if (config.authServer == null || config.authServer.isBlank()) return;

        // 已注入过则跳过（避免崩溃重启动重复注入；authServer 只出现在认证注入参数中）
        if (config.jvmArgs != null
                && (config.jvmArgs.contains(config.authServer)
                    || config.jvmArgs.contains("-Dminecraft.api.auth.host=" + config.authServer))) {
            return;
        }

        List<String> args = AuthlibInjectorSupport.buildLaunchJvmArgs(config.authServer);
        if (args.isEmpty()) return;
        String extra = String.join(" ", args);
        config.jvmArgs = (config.jvmArgs == null || config.jvmArgs.isBlank())
                ? extra : config.jvmArgs.trim() + " " + extra;
    }

    /**
     * 20.11 代理启动注入（参照 HMCL DefaultLauncher.buildJVMArgs）：
     * 按配置注入系统代理（-Djava.net.useSystemProxies=true）或 HTTP 代理参数。
     * 仅在配置了代理类型时生效；HTTP 代理时同时覆盖 BaseLauncher 默认的系统代理标志。
     */
    private static void injectProxyArgs(LaunchConfig config) {
        Map<String, String> cfg = readConfig();
        String proxyType = cfg.getOrDefault(ProxyConfig.KEY_TYPE, "");
        if (proxyType == null || proxyType.isBlank()) return; // 未配置代理，保持默认（系统代理）
        // 已注入过则跳过（避免崩溃重启动重复注入）
        if (config.jvmArgs != null && config.jvmArgs.contains("-Dhttp.proxyHost=")) return;

        List<String> args = ProxyConfig.buildJvmArgs(cfg);
        if (args.isEmpty()) return;
        String extra = String.join(" ", args);
        config.jvmArgs = (config.jvmArgs == null || config.jvmArgs.isBlank())
                ? extra : config.jvmArgs.trim() + " " + extra;
    }

    private static LaunchInfo buildLaunchInfo(LaunchConfig c) {
        LaunchInfo info = new LaunchInfo();
        info.setGameDirPath(Path.of(c.gameDir));
        info.setVersion(c.version);
        info.setJavaPath(c.javaPath);
        info.setUserName(c.userName);
        info.setUuid(c.uuid);
        info.setAccessToken(c.accessToken);
        info.setUserType(c.userType);
        info.setMinMemoryMB(c.minMemory);
        info.setMaxMemoryMB(c.maxMemory);
        info.setWindowWidth(c.windowWidth);
        info.setWindowHeight(c.windowHeight);
        // 全屏模式改由 options.txt 控制（启动前由 GameOptions 写入 fullscreen 字段）：
        // 固定传 false，确保核心启动器不再向游戏命令追加 --fullscreen，保持纯 options.txt 行为
        info.setFullscreen(false);
        info.setVersionIsolation(c.versionIsolation);
        // 按引号分组切分：-javaagent/-D 里可能含带空格的 Windows 用户目录路径
        info.setJvmArgs(com.example.starlight.startmc.LaunchInfo.splitCommandLine(c.jvmArgs));
        if (!c.gameArgs.isEmpty()) info.setGameArgs(Arrays.asList(c.gameArgs.split("\\s+")));
        if (!c.preLaunchCommand.isEmpty()) info.setPreLaunchCommand(c.preLaunchCommand);
        if (!c.postExitCommand.isEmpty())  info.setPostExitCommand(c.postExitCommand);
        // 进程微调（高级设置）：自定义窗口标题 + 进程优先级，在 BaseLauncher 启动进程后应用
        info.setWindowTitle(c.windowTitle);
        info.setProcessPriority(c.processPriority);
        return info;
    }

    private static void scanModDir(Path dir, String origin, List<ModInfo> list) {
        try (Stream<Path> stream = Files.list(dir)) {
            stream
                .filter(p -> p.getFileName().toString().endsWith(".jar")
                          || p.getFileName().toString().endsWith(".disabled"))
                .forEach(p -> {
                    String name = p.getFileName().toString();
                    boolean enabled = !name.endsWith(".disabled");
                    if (!enabled) name = name.substring(0, name.length() - 9);
                    list.add(new ModInfo(name, p.toAbsolutePath().toString(), enabled, origin));
                });
        } catch (IOException ignored) {}
    }

    private static Set<String> readActiveResourcePacks(String gameDir) {
        Path optionsFile = Paths.get(gameDir, "options.txt");
        if (!Files.exists(optionsFile)) return Collections.emptySet();

        Set<String> active = new HashSet<>();
        try {
            List<String> opts = Files.readAllLines(optionsFile, StandardCharsets.UTF_8);
            for (String line : opts) {
                if (line.startsWith("resourcePacks:")) {
                    String json = line.substring("resourcePacks:".length()).trim();
                    if (json.startsWith("[")) {
                        com.google.gson.JsonArray arr = new Gson().fromJson(json, com.google.gson.JsonArray.class);
                        for (var el : arr) {
                            String packPath = el.getAsString();
                            String packName = Paths.get(packPath).getFileName().toString();
                            if (!packName.isEmpty()) active.add(packName);
                        }
                    }
                    break;
                }
            }
        } catch (IOException ignored) {}
        return active;
    }

    private static List<SaveInfo> parseSavesFromOutput(List<String> lines, String versionName) {
        List<SaveInfo> saves = new ArrayList<>();
        String currentVersion = versionName;
        String currentName = null;
        String currentPath = null;

        for (String line : lines) {
            if (line.startsWith("Game version:")) {
                currentVersion = line.substring(line.indexOf(':') + 1).trim();
            } else if (line.startsWith("Save name:")) {
                currentName = line.substring(line.indexOf(':') + 1).trim();
            } else if (line.startsWith("Save path:")) {
                currentPath = line.substring(line.indexOf(':') + 1).trim();
                if (currentName != null && currentPath != null) {
                    saves.add(new SaveInfo(currentName, new File(currentPath).getName(), currentPath, currentVersion));
                    currentName = null;
                    //currentPath = null;
                }
            }
        }
        return saves;
    }

    private static MicrosoftAuthResult readMicrosoftAuthResult() {
        Path loginJson = AppDirs.loginJson();
        if (!Files.exists(loginJson)) return null;
        try {
            String content = Files.readString(loginJson, StandardCharsets.UTF_8);
            Gson gson = new Gson();
            JsonObject json = gson.fromJson(content, JsonObject.class);
            return new MicrosoftAuthResult(
                getJsonStr(json, "accessToken"),
                getJsonStr(json, "refreshToken"),
                getJsonStr(json, "uuid"),
                getJsonStr(json, "username"));
        } catch (JsonSyntaxException | IOException e) {
            return null;
        }
    }

    private static String getJsonStr(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
    }

    private static boolean checkAndCompleteNatives(String gameDir, String version, String versionJson, boolean verbose) {
        return NativesManager.checkAndCompleteNatives(gameDir, version, versionJson, verbose);
    }

    private static void runNativeCleanup(String gameDir, String version) {
        Path verDir = Paths.get(gameDir, "versions", version);
        try (Stream<Path> stream = Files.list(verDir)) {
            stream
                .filter(Files::isDirectory)
                .filter(p -> p.getFileName().toString().contains("natives"))
                .forEach(dir -> cleanupNativesDir(dir));
        } catch (IOException ignored) {}
    }

    private static void cleanupNativesDir(Path nativesDir) {
        if (!Files.isDirectory(nativesDir)) return;
        try {
            List<Path> allDlls;
            try (Stream<Path> walk = Files.walk(nativesDir)) {
                allDlls = walk
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".dll"))
                    .sorted((a, b) -> b.toString().length() - a.toString().length())
                    .toList();
            }
            if (allDlls.isEmpty()) return;

            Map<String, Path> keepMap = new HashMap<>();
            for (Path dll : allDlls) {
                String name = dll.getFileName().toString();
                try {
                    long size = Files.size(dll);
                    Path existing = keepMap.get(name);
                    if (existing == null || Files.size(existing) < size) keepMap.put(name, dll);
                } catch (IOException e) {
                    keepMap.putIfAbsent(name, dll);
                }
            }

            String[] removePatterns = {"twitch", "avutil", "libmp3lame", "libmfxsw64", "swresample", "jtracy", "SAPIWrapper"};
            for (String p : removePatterns) {
                keepMap.entrySet().removeIf(e -> e.getKey().toLowerCase().contains(p));
            }

            for (Path dll : allDlls) {
                Path kept = keepMap.get(dll.getFileName().toString());
                if (kept != null && !dll.equals(kept)) Files.deleteIfExists(dll);
            }

            for (Map.Entry<String, Path> entry : keepMap.entrySet()) {
                Path dll = entry.getValue();
                Path parent = dll.getParent();
                if (parent != null && !parent.equals(nativesDir)) {
                    Files.move(dll, nativesDir.resolve(entry.getKey()), StandardCopyOption.REPLACE_EXISTING);
                }
            }

            try (Stream<Path> walk = Files.walk(nativesDir)) {
                walk.sorted((a, b) -> b.toString().length() - a.toString().length())
                    .filter(Files::isDirectory)
                    .filter(p -> !p.equals(nativesDir))
                    .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
            }
        } catch (IOException ignored) {}
    }

    private static String removeMarkdownCodeBlocks(String content) {
        if (content == null) return null;
        String trimmed = content.trim();
        if (trimmed.startsWith("```json")) trimmed = trimmed.substring(7).trim();
        else if (trimmed.startsWith("```")) trimmed = trimmed.substring(3).trim();
        if (trimmed.endsWith("```")) trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
        return trimmed;
    }

    private static void exportResponseToJson(String response, String filePath, int indentSize) throws IOException {
        String formatted = formatJsonWithIndent(response, indentSize);
        Files.writeString(Paths.get(filePath), formatted, StandardCharsets.UTF_8);
    }

    private static String formatJsonWithIndent(String json, int indentSize) {
        if (json == null || json.trim().isEmpty()) return json;
        StringBuilder result = new StringBuilder();
        int indentLevel = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) { result.append(c); escaped = false; continue; }
            if (c == '\\') { result.append(c); escaped = true; continue; }
            if (c == '"') { result.append(c); inString = !inString; continue; }
            if (inString) { result.append(c); continue; }
            switch (c) {
                case '{', '[' -> { result.append(c).append('\n'); result.append(" ".repeat(++indentLevel * indentSize)); }
                case '}', ']' -> { result.append('\n'); result.append(" ".repeat(--indentLevel * indentSize)); result.append(c); }
                case ',' -> { result.append(c).append('\n'); result.append(" ".repeat(indentLevel * indentSize)); }
                case ':' -> result.append(c).append(' ');
                default -> { if (!Character.isWhitespace(c)) result.append(c); }
            }
        }
        return result.toString();
    }

    private static int parseIntSafe(String s, int defaultValue) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return defaultValue; }
    }


    // XXI.  加载器一键安装 //
    /** 21.1 一键安装加载器（异步）
     *  @param mcVersion   Minecraft 原版版本号（如 1.21.1）
     *  @param loaderType  加载器类型（fabric / forge / neoforge）
     *  @param loaderVersion 加载器版本号（传 null 自动获取最新版）
     *  @param gameDir     .minecraft 目录路径
     *  @param javaPath    Java 可执行文件路径（Forge/NeoForge 需要运行安装器）
     *  @param progress    进度回调
     *  @param onResult    结果回调 */
    public static void installLoaderAsync(String mcVersion, String loaderType,
                                           String loaderVersion, String gameDir,
                                           String javaPath,
                                           ProgressCallback progress,
                                           ResultCallback<Boolean> onResult) {
        installLoaderAsync(mcVersion, loaderType, loaderVersion, gameDir, javaPath,
                progress, onResult, null);
    }

    /**
     * 一键安装加载器（异步，支持自定义版本文件夹名）
     * @param folderName 自定义版本文件夹名（null/空使用标准目录；仅 Fabric 支持）
     */
    public static void installLoaderAsync(String mcVersion, String loaderType,
                                           String loaderVersion, String gameDir,
                                           String javaPath,
                                           ProgressCallback progress,
                                           ResultCallback<Boolean> onResult,
                                           String folderName) {
        installLoaderAsync(mcVersion, loaderType, loaderVersion, gameDir, javaPath,
                progress, onResult, folderName, null);
    }

    /**
     * 一键安装加载器（异步，支持取消）。
     *
     * <p>{@code cancel} 置位后：库文件下载立即中断、安装器子进程被强杀，
     * 结果回调会尽快到达（{@code onError}），调用方据此清理未完成的版本目录。
     *
     * @param folderName 自定义版本文件夹名（null/空使用标准目录；仅 Fabric 支持）
     * @param cancel     取消标志（可为 null 表示不可取消）
     */
    public static void installLoaderAsync(String mcVersion, String loaderType,
                                           String loaderVersion, String gameDir,
                                           String javaPath,
                                           ProgressCallback progress,
                                           ResultCallback<Boolean> onResult,
                                           String folderName, AtomicBoolean cancel) {
        ASYNC_POOL.submit(() -> {
            try {
                LoaderInstallEngine.ProgressCallback adapter = (pct, msg) -> {
                    if (progress != null) progress.onProgress(pct, msg);
                };
                boolean ok = LoaderInstallEngine.installLoader(
                    mcVersion, loaderType, loaderVersion, gameDir, javaPath, adapter, folderName, cancel);
                if (ok && onResult != null) onResult.onSuccess(true);
                else if (!ok && onResult != null) onResult.onError(I18N.get("gui.t0060"));
            } catch (Throwable t) {
                // 兜底：确保回调必达，避免调用方 latch 等待超时或按钮永久禁用
                if (onResult != null) onResult.onError(I18N.get("gui.p0123", t.getMessage()));
            }
        });
    }

    /** 21.2 安装 Fabric 加载器（简便方法） */
    public static void installFabricAsync(String mcVersion, String gameDir,
                                           ProgressCallback progress,
                                           ResultCallback<Boolean> onResult) {
        installLoaderAsync(mcVersion, "fabric", null, gameDir, null, progress, onResult);
    }

    /** 21.3 安装 Forge 加载器（简便方法） */
    public static void installForgeAsync(String mcVersion, String loaderVersion,
                                          String gameDir, String javaPath,
                                          ProgressCallback progress,
                                          ResultCallback<Boolean> onResult) {
        installLoaderAsync(mcVersion, "forge", loaderVersion, gameDir, javaPath, progress, onResult);
    }

    /** 21.4 安装 NeoForge 加载器（简便方法） */
    public static void installNeoForgeAsync(String mcVersion, String loaderVersion,
                                             String gameDir, String javaPath,
                                             ProgressCallback progress,
                                             ResultCallback<Boolean> onResult) {
        installLoaderAsync(mcVersion, "neoforge", loaderVersion, gameDir, javaPath, progress, onResult);
    }
}
