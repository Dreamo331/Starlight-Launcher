package com.example.starlight.download.task;


import com.example.starlight.util.I18N;
import com.example.starlight.download.DownloadProviderHolder;
import com.example.starlight.download.source.DownloadProvider;
import com.example.starlight.newui.AppConfig;
import com.example.starlight.util.VersionJsonMerger;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoaderInstallEngine {

    private static final Logger LOG = LoggerFactory.getLogger(LoaderInstallEngine.class);

    /** 取消文案统一取下载引擎常量：上层按 message 判断「是取消还是失败」 */
    private static final String CANCELLED_MESSAGE =
            com.example.starlight.download.engine.HttpDownloadEngine.CANCELLED_MESSAGE;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Gson GSON = new Gson();

    private static final String FABRIC_META = "https://meta.fabricmc.net/v2";
    private static final String FABRIC_MAVEN = "https://maven.fabricmc.net/";
    private static final String FORGE_MAVEN = "https://maven.minecraftforge.net";
    private static final String NEOFORGE_MAVEN = "https://maven.neoforged.net";
    private static final String BMCLAPI = "https://bmclapi2.bangbang93.com";

    /**
     * 设置下载提供者（设置页切换下载源时由 LauncherView 调用，立即生效）——JUDGE-5 委托 DownloadProviderHolder
     */
    public static void setDownloadProvider(DownloadProvider provider) {
        if (provider != null) {
            DownloadProviderHolder.set(provider);
        }
    }

    /**
     * 获取当前下载提供者——JUDGE-5 委托 DownloadProviderHolder
     */
    public static DownloadProvider getDownloadProvider() {
        return DownloadProviderHolder.get();
    }

    public interface ProgressCallback {
        void onProgress(int pct, String msg);
    }

    public static boolean installLoader(String mcVersion, String loaderType,
                                         String loaderVersion, String gameDir,
                                         String javaPath, ProgressCallback progress) {
        return installLoader(mcVersion, loaderType, loaderVersion, gameDir, javaPath, progress, null);
    }

    /**
     * 安装加载器（支持自定义版本文件夹名）
     * @param folderName 自定义版本文件夹名（null/空使用标准目录；Forge/NeoForge 安装完成后
     *                   会将安装结果重命名为自定义目录并合并原版内容，保持版本自包含）
     */
    public static boolean installLoader(String mcVersion, String loaderType,
                                         String loaderVersion, String gameDir,
                                         String javaPath, ProgressCallback progress,
                                         String folderName) {
        return installLoader(mcVersion, loaderType, loaderVersion, gameDir, javaPath,
                progress, folderName, null);
    }

    /**
     * 安装加载器（支持取消）。
     *
     * <p>{@code cancel} 置位后：库文件下载每读 64KB 立即中断、安装器子进程被强杀，
     * 方法抛出取消异常（不会等到当前文件/当前安装器跑完），由调用方清理未完成的版本目录。
     *
     * @param folderName 自定义版本文件夹名（null/空使用标准目录；Forge/NeoForge 安装完成后
     *                   会将安装结果重命名为自定义目录并合并原版内容，保持版本自包含）
     * @param cancel     取消标志（可为 null 表示不可取消）
     * @throws IllegalStateException 用户取消，message 为 {@link HttpDownloadEngine#CANCELLED_MESSAGE}
     */
    public static boolean installLoader(String mcVersion, String loaderType,
                                         String loaderVersion, String gameDir,
                                         String javaPath, ProgressCallback progress,
                                         String folderName, AtomicBoolean cancel) {
        if (mcVersion == null || mcVersion.isEmpty()) {
            error(progress, "MC version cannot be empty");
            return false;
        }
        if (gameDir == null) gameDir = ".minecraft";
        if (javaPath == null || javaPath.isEmpty()) javaPath = "java";
        throwIfCancelled(cancel);

        Path gamePath = Path.of(gameDir);
        try {
            Files.createDirectories(gamePath);
        } catch (IOException e) {
            error(progress, "Failed to create game directory: " + e.getMessage());
            return false;
        }

        // 自定义版本文件夹名时，原版保存在 versions/<folderName>/ 下，检查对应目录
        String dirName = (folderName == null || folderName.isBlank()) ? mcVersion : folderName.trim();
        Path versionJson = gamePath.resolve("versions").resolve(dirName).resolve(dirName + ".json");
        Path clientJar = gamePath.resolve("versions").resolve(dirName).resolve(dirName + ".jar");
        if (!Files.exists(versionJson) || !Files.exists(clientJar)) {
            error(progress, "Vanilla " + mcVersion + " is not installed, please download the vanilla game first");
            return false;
        }

        String type = loaderType.toLowerCase(Locale.ROOT);
        report(progress, 0, "Starting installation of " + loaderType + " " + mcVersion + " ...");

        try {
            return switch (type) {
                case "fabric" -> installFabric(mcVersion, loaderVersion, gamePath, progress, folderName, cancel);
                case "forge" -> installForge(mcVersion, loaderVersion, gamePath, javaPath, progress, folderName, cancel);
                case "neoforge" -> installNeoForge(mcVersion, loaderVersion, gamePath, javaPath, progress, folderName, cancel);
                // FabricAPI / QSL 不是加载器，是 mod jar（对标 HMCL FabricAPIInstallTask）：
                // 安装 = 下载 jar 放进 mods 目录（直链从 Provider 按版本号反查）
                case "fabricapi", "qsl" -> installModProvider(mcVersion, type, loaderVersion, gamePath, progress, cancel);
                default -> {
                    error(progress, "Unsupported loader type: " + loaderType);
                    yield false;
                }
            };
        } catch (Exception e) {
            // 取消不是安装失败：必须穿透给调用方，否则会变成「失败」并且不清理未完成目录
            if (isCancellation(e)) throw (RuntimeException) e;
            LOG.error("Installation failed", e);
            error(progress, "Installation failed: " + e.getMessage());
            return false;
        }
    }

    private static boolean installFabric(String mcVersion, String loaderVersion,
                                          Path gameDir, ProgressCallback progress) throws Exception {
        return installFabric(mcVersion, loaderVersion, gameDir, progress, null, null);
    }

    /**
     * 安装 FabricAPI / QSL 这类「mod 形态的组件」：下载 jar 放进 {@code mods/} 目录。
     *
     * <p>此前下载中心把这类行当加载器传进来，{@code installLoader} 直接报
     * {@code Unsupported loader type: fabricapi}（2026-10-23 21:14 日志实录）。
     * 对标 HMCL 的 {@code FabricAPIInstallTask}：它们不是加载器，只是下载 mod jar。
     *
     * <p>下载直链按版本号从对应 Provider 反查（FabricAPIProvider / QSLProvider 内部
     * 有 5 分钟实例缓存，UI 刚查询过列表时直接命中，不再打一遍 Modrinth）。
     */
    private static boolean installModProvider(String mcVersion, String type, String loaderVersion,
                                              Path gameDir, ProgressCallback progress,
                                              AtomicBoolean cancel) throws Exception {
        if (loaderVersion == null || loaderVersion.isBlank()) {
            error(progress, I18N.get("lie.r0001", type));
            return false;
        }
        String providerName = type.equals("fabricapi") ? "FabricAPI" : "QSL";
        String url = null;
        for (var p : com.example.starlight.version.VersionDownloadService.LOADER_PROVIDERS) {
            if (!providerName.equals(p.name())) continue;
            for (var lv : p.fetchVersions(mcVersion)) {
                if (loaderVersion.equals(lv.getVersion()) && lv.getDownloadUrl() != null
                        && lv.getDownloadUrl().startsWith("http")) {
                    url = lv.getDownloadUrl();
                    break;
                }
            }
            if (url != null) break;
        }
        if (url == null) {
            error(progress, I18N.get("lie.r0002", providerName, loaderVersion));
            return false;
        }

        report(progress, 10, "Downloading " + providerName + " " + loaderVersion + " ...");
        Path modsDir = gameDir.resolve("mods");
        Files.createDirectories(modsDir);
        String fileName = url.substring(url.lastIndexOf('/') + 1);
        if (fileName.isBlank()) fileName = type + "-" + loaderVersion + ".jar";
        Path dest = modsDir.resolve(fileName);
        // 同名文件已存在时先删再下：覆盖旧版本，避免 mods 里堆出两个版本导致游戏崩溃
        Files.deleteIfExists(dest);

        boolean ok = downloadFile(url, dest, (String) null, cancel);
        if (!ok) {
            throwIfCancelled(cancel);
            error(progress, I18N.get("lie.r0003", providerName, url));
            return false;
        }
        report(progress, 100, providerName + " " + loaderVersion + " 安装完成（已放入 mods 目录）");
        return true;
    }

    private static boolean installFabric(String mcVersion, String loaderVersion,
                                          Path gameDir, ProgressCallback progress,
                                          String folderName, AtomicBoolean cancel) throws Exception {
        throwIfCancelled(cancel);
        if (loaderVersion == null || loaderVersion.isEmpty()) {
            report(progress, 2, "Querying latest Fabric loader version...");
            loaderVersion = fetchLatestFabricLoader(mcVersion);
            if (loaderVersion == null) {
                error(progress, "Failed to fetch latest Fabric version");
                return false;
            }
            LOG.info("Latest Fabric loader version: {}", loaderVersion);
        }

        report(progress, 5, "Fetching Fabric profile info...");
        String profileUrl = FABRIC_META + "/versions/loader/" + mcVersion + "/" + loaderVersion + "/profile/json";
        String profileJson = fetchString(profileUrl);
        if (profileJson == null) {
            error(progress, "Failed to fetch Fabric profile JSON");
            return false;
        }

        JsonObject profile = GSON.fromJson(profileJson, JsonObject.class);
        String versionId = GSON.<String>fromJson(profile.get("id"), String.class);
        if (versionId == null || versionId.isEmpty()) {
            error(progress, "Fabric profile JSON missing id field");
            return false;
        }
        // 自定义版本目录：改写 profile 的 id，保证 json/jar 文件名与目录名一致
        String dirName = (folderName == null || folderName.isBlank()) ? versionId : folderName.trim();
        boolean customDir = !dirName.equals(versionId);

        report(progress, 10, "Creating version directory...");
        Path versionDir = gameDir.resolve("versions").resolve(dirName);
        Files.createDirectories(versionDir);

        JsonArray libraries = profile.getAsJsonArray("libraries");
        if (libraries != null && libraries.size() > 0) {
            int total = libraries.size();
            int completed = 0;
            int errors = 0;
            List<String> failedLibs = new ArrayList<>();
            Path libDir = gameDir.resolve("libraries");
            report(progress, 15, "Downloading library files (0/" + total + ")...");

            for (int i = 0; i < total; i++) {
                // 每个库之间检查取消：不必等当前库下完才发现用户点了取消
                throwIfCancelled(cancel);
                JsonObject lib = libraries.get(i).getAsJsonObject();
                String name = getJsonString(lib, "name");
                String url = getJsonString(lib, "url");
                if (name == null || url == null) {
                    completed++;
                    continue;
                }
                boolean ok = downloadMavenLibrary(name, url, libDir, null, cancel);
                if (ok) completed++;
                else {
                    errors++;
                    failedLibs.add(name);
                }
                int pct = 15 + (completed * 60 / Math.max(total, 1));
                report(progress, Math.min(pct, 75),
                        "下载库文件: " + completed + "/" + total + (errors > 0 ? " (" + errors + " 错误)" : ""));
            }
            if (errors > 0) {
                // 库下载失败 = 版本残缺 = 启动必崩，绝不能当「安装成功」报出去
                // （2026-10-22 21:49 日志：sponge-mixin 3 次重试全败，仍报了 100% 完成）
                LOG.warn("Fabric library download finished with {} failures: {}", errors, failedLibs);
                error(progress, "库文件下载失败 " + errors + "/" + total + "：\n"
                        + String.join("\n", failedLibs)
                        + "\n请检查网络或切换下载源后重试。");
                return false;
            }
        }

        throwIfCancelled(cancel);
        report(progress, 80, "Writing version config...");
        if (customDir) {
            // 自定义版本目录：将原版 json 内容合并进 profile（自包含），
            // 并改写 id 与目录名一致，避免 inheritsFrom 指向不存在的标准目录
            String parentId = getJsonString(profile, "inheritsFrom");
            if (parentId != null) {
                absorbParentIntoChild(profile, gameDir, parentId, dirName);
            }
            profile.remove("id");
            profile.addProperty("id", dirName);
            profileJson = GSON.toJson(profile);
        }
        Path profileJsonPath = versionDir.resolve(dirName + ".json");
        Files.writeString(profileJsonPath, profileJson);

        report(progress, 85, "Processing client JAR...");
        String inheritsFrom = getJsonString(profile, "inheritsFrom");
        if (inheritsFrom != null) {
            Path parentJar = gameDir.resolve("versions").resolve(inheritsFrom).resolve(inheritsFrom + ".jar");
            Path targetJar = versionDir.resolve(dirName + ".jar");
            if (Files.exists(parentJar) && !Files.exists(targetJar)) {
                Files.copy(parentJar, targetJar, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }

        report(progress, 100, "Fabric " + dirName + " installation complete!");
        LOG.info("Fabric installation complete: {}", dirName);
        return true;
    }

    private static String fetchLatestFabricLoader(String mcVersion) {
        try {
            String json = fetchString(FABRIC_META + "/versions/loader/" + mcVersion);
            if (json == null) return null;
            JsonArray arr = GSON.fromJson(json, JsonArray.class);
            if (arr == null || arr.size() == 0) return null;
            for (var element : arr) {
                JsonObject entry = element.getAsJsonObject();
                JsonObject loader = entry.getAsJsonObject("loader");
                if (loader != null) {
                    boolean stable = getJsonBoolean(loader, "stable");
                    if (stable) {
                        return getJsonString(loader, "version");
                    }
                }
            }
            JsonObject first = arr.get(0).getAsJsonObject();
            return getJsonString(first.getAsJsonObject("loader"), "version");
        } catch (Exception e) {
            LOG.error("Failed to fetch latest Fabric version", e);
            return null;
        }
    }

    private static boolean installForge(String mcVersion, String loaderVersion,
                                         Path gameDir, String javaPath,
                                         ProgressCallback progress,
                                         String folderName, AtomicBoolean cancel) throws Exception {
        throwIfCancelled(cancel);
        String dirName = (folderName == null || folderName.isBlank()) ? mcVersion : folderName.trim();
        boolean useCustomDir = dirName != null && !dirName.isBlank() && !dirName.equals(mcVersion);
        if (loaderVersion == null || loaderVersion.isEmpty()) {
            report(progress, 2, "Fetching latest Forge version...");
            loaderVersion = fetchLatestForgeVersion(mcVersion);
            if (loaderVersion == null) {
                error(progress, "Failed to fetch Forge version for " + mcVersion);
                return false;
            }
            LOG.info("Latest Forge version: {}", loaderVersion);
        }

        report(progress, 5, "Downloading Forge installer...");
        String installerUrl = FORGE_MAVEN + "/net/minecraftforge/forge/"
                + mcVersion + "-" + loaderVersion + "/forge-"
                + mcVersion + "-" + loaderVersion + "-installer.jar";

        Path tempDir = gameDir.resolve(".starlight_temp");
        Files.createDirectories(tempDir);
        Path installerJar = tempDir.resolve("forge-installer-" + loaderVersion + ".jar");

        if (!downloadFile(installerUrl, installerJar, (String) null, cancel)) {
            throwIfCancelled(cancel);
            String mirrorUrl = BMCLAPI + "/maven/net/minecraftforge/forge/"
                    + mcVersion + "-" + loaderVersion + "/forge-"
                    + mcVersion + "-" + loaderVersion + "-installer.jar";
            if (!downloadFile(mirrorUrl, installerJar, (String) null, cancel)) {
                error(progress, "Forge installer download failed");
                return false;
            }
        }

        // 自定义版本文件夹名：Forge 安装器要求原版位于标准版本目录，临时创建副本
        boolean tempVanillaCopy = false;
        if (useCustomDir) {
            tempVanillaCopy = ensureVanillaAtStandardDir(gameDir, mcVersion, dirName);
        }

        // 安装器子进程直连官方 maven、不受镜像候选链保护：先把 profile 声明的库
        // 经镜像链预下载进本地仓库，子进程下载时命中缓存，弱网下不再超时失败
        preloadInstallerLibraries(installerJar, gameDir, progress);

        report(progress, 30, "Running Forge installer...");
        String[] cmd = {javaPath, "-jar", installerJar.toAbsolutePath().toString(),
                "--installClient", gameDir.toAbsolutePath().toString()};

        boolean installed = runInstallerProcess(cmd, progress, cancel);
        if (!installed) {
            error(progress, "Forge installer execution failed");
            cleanTempDir(tempDir);
            // 只删本次为安装器临时创建的原版副本，绝不能碰用户本来就有的标准版本目录
            if (tempVanillaCopy) removeTempVanillaCopy(gameDir, mcVersion);
            throwIfCancelled(cancel);
            return false;
        }

        report(progress, 85, "Verifying installation result...");
        String expectedVersionId = mcVersion + "-forge-" + loaderVersion;
        Path expectedDir = gameDir.resolve("versions").resolve(expectedVersionId);
        if (!Files.exists(expectedDir) || !Files.exists(expectedDir.resolve(expectedVersionId + ".json"))) {
            expectedVersionId = mcVersion + "-Forge_" + loaderVersion;
            expectedDir = gameDir.resolve("versions").resolve(expectedVersionId);
        }
        if (!Files.exists(expectedDir) || !Files.exists(expectedDir.resolve(expectedVersionId + ".json"))) {
            expectedVersionId = "forge-" + mcVersion + "-" + loaderVersion;
            expectedDir = gameDir.resolve("versions").resolve(expectedVersionId);
        }
        if (!Files.exists(expectedDir)) {
            expectedDir = findVersionDirByPattern(gameDir, mcVersion, "forge", loaderVersion);
        }

        // 自定义版本文件夹名：将安装结果重命名为 dirName 并改写 json id
        if (useCustomDir) {
            if (expectedDir == null || !Files.exists(expectedDir)) {
                error(progress, "Forge installation finished but cannot locate version directory");
                cleanTempDir(tempDir);
                if (tempVanillaCopy) removeTempVanillaCopy(gameDir, mcVersion);
                return false;
            }
            if (!expectedDir.getFileName().toString().equals(dirName)) {
                if (!renameVersionDirTo(expectedDir, dirName, progress)) {
                    cleanTempDir(tempDir);
                    if (tempVanillaCopy) removeTempVanillaCopy(gameDir, mcVersion);
                    return false;
                }
                expectedDir = expectedDir.resolveSibling(dirName);
                expectedVersionId = dirName;
            }
        }

        LOG.info("Forge installation complete, version dir: {}", expectedDir);
        cleanTempDir(tempDir);
        if (tempVanillaCopy) removeTempVanillaCopy(gameDir, mcVersion);
        report(progress, 100, "Forge " + expectedVersionId + " installation complete!");
        return true;
    }

    /** 将版本目录重命名为 dirName，并改写 json 的 id 保持一致 */
    private static boolean renameVersionDirTo(Path srcDir, String dirName, ProgressCallback progress) {
        try {
            Path target = srcDir.resolveSibling(dirName);
            if (Files.exists(target)) {
                error(progress, "Version directory already exists: " + dirName);
                return false;
            }
            String oldName = srcDir.getFileName().toString();
            // 改写 json 的 id
            Path jsonFile = srcDir.resolve(oldName + ".json");
            if (Files.exists(jsonFile)) {
                String content = Files.readString(jsonFile);
                JsonObject root = GSON.fromJson(content, JsonObject.class);
                if (root != null) {
                    root.addProperty("id", dirName);
                    // 自定义目录：合并父版本内容并移除继承，避免 inheritsFrom 指向不存在的标准目录
                    String parentId = getJsonString(root, "inheritsFrom");
                    if (parentId != null) {
                        absorbParentIntoChild(root, srcDir.getParent().getParent(), parentId, dirName);
                    }
                    Files.writeString(srcDir.resolve(dirName + ".json"), root.toString());
                    Files.deleteIfExists(jsonFile);
                }
            }
            // 重命名 jar
            Path oldJar = srcDir.resolve(oldName + ".jar");
            Path newJar = srcDir.resolve(dirName + ".jar");
            if (Files.exists(oldJar) && !Files.exists(newJar)) {
                Files.move(oldJar, newJar, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(srcDir, target);
            report(progress, 90, "Version directory renamed to " + dirName);
            return true;
        } catch (Exception e) {
            LOG.error("Failed to rename version directory", e);
            error(progress, "Failed to rename version directory: " + e.getMessage());
            return false;
        }
    }

    /**
     * 将父版本 JSON 的内容合并进子版本（libraries 拼接 + 标量继承 + arguments 拼接），
     * 并移除 inheritsFrom。用于自定义版本文件夹名场景：原版被安装到自定义目录，
     * 加载器版本不再依赖标准目录。
     *
     * <p>父 JSON 优先从标准目录（versions/&lt;parentId&gt;/）读取，回退到自定义目录（versions/&lt;dirName&gt;/）。
     *
     * @return 是否成功找到并合并父版本 JSON
     */
    private static boolean absorbParentIntoChild(JsonObject child, Path gameDir,
                                                  String parentId, String altDirName) {
        JsonObject parent = null;
        // 优先标准目录
        Path standard = gameDir.resolve("versions").resolve(parentId).resolve(parentId + ".json");
        if (Files.exists(standard)) {
            try {
                parent = GSON.fromJson(Files.readString(standard), JsonObject.class);
            } catch (IOException ignored) {}
        }
        // 回退：自定义目录中的原版 JSON
        if (parent == null && altDirName != null && !altDirName.equals(parentId)) {
            Path custom = gameDir.resolve("versions").resolve(altDirName).resolve(altDirName + ".json");
            if (Files.exists(custom)) {
                try {
                    parent = GSON.fromJson(Files.readString(custom), JsonObject.class);
                } catch (IOException ignored) {}
            }
        }
        if (parent == null) {
            LOG.warn("Cannot locate parent version JSON (parentId={}, altDirName={}), loader version may not launch", parentId, altDirName);
            return false;
        }
        // 合并 libraries：父版本在前，子版本在后
        JsonArray merged = new JsonArray();
        if (parent.has("libraries")) {
            parent.getAsJsonArray("libraries").forEach(merged::add);
        }
        if (child.has("libraries")) {
            child.getAsJsonArray("libraries").forEach(merged::add);
        }
        child.add("libraries", merged);
        // 标量字段父有子无才继承；arguments 必须单独走数组拼接 !!!
        // Fabric 等加载器的 profile 自带 game 为空数组的 arguments，若按标量判据跳过，
        // 原版的 --username / --gameDir 等游戏参数会全部丢失，隔离路径也就无从传递。
        VersionJsonMerger.inheritMissing(child, parent, "assets", "assetIndex",
                "javaVersion", "complianceLevel", "minecraftArguments");
        VersionJsonMerger.mergeArguments(child, parent);
        child.remove("inheritsFrom");
        return true;
    }

    /**
     * Forge/NeoForge 官方安装器要求原版存在于标准版本目录（versions/&lt;mcVersion&gt;/）。
     * 自定义文件夹名时原版在自定义目录，此方法临时将原版 json/jar 链接（或复制）到标准目录。
     * @return 是否创建了临时副本（安装完成后应调用 removeTempVanillaCopy 清理）
     */
    private static boolean ensureVanillaAtStandardDir(Path gameDir, String mcVersion, String dirName) {
        Path stdDir = gameDir.resolve("versions").resolve(mcVersion);
        if (Files.exists(stdDir.resolve(mcVersion + ".json"))) return false; // 标准目录已有原版
        Path customDir = gameDir.resolve("versions").resolve(dirName);
        Path customJson = customDir.resolve(dirName + ".json");
        if (!Files.exists(customJson)) return false;
        try {
            Files.createDirectories(stdDir);
            linkOrCopy(customJson, stdDir.resolve(mcVersion + ".json"));
            Path customJar = customDir.resolve(dirName + ".jar");
            if (Files.exists(customJar)) {
                linkOrCopy(customJar, stdDir.resolve(mcVersion + ".jar"));
            }
            LOG.info("Temporarily linked vanilla to standard dir for installer: {}", stdDir);
            return true;
        } catch (IOException e) {
            LOG.warn("Failed to temporarily copy vanilla to standard dir: {}", e.getMessage());
            return false;
        }
    }

    /** 清理 ensureVanillaAtStandardDir 创建的临时原版副本（仅删除本方法创建的文件，安全） */
    private static void removeTempVanillaCopy(Path gameDir, String mcVersion) {
        try {
            Path stdDir = gameDir.resolve("versions").resolve(mcVersion);
            Files.deleteIfExists(stdDir.resolve(mcVersion + ".json"));
            Files.deleteIfExists(stdDir.resolve(mcVersion + ".jar"));
            try (var stream = Files.list(stdDir)) {
                if (stream.findAny().isEmpty()) {
                    Files.deleteIfExists(stdDir);
                }
            } catch (IOException ignored) {}
        } catch (IOException ignored) {}
    }

    /** 优先硬链接（省空间、瞬时完成），失败则回退为复制 */
    private static void linkOrCopy(Path src, Path dst) throws IOException {
        try {
            Files.createLink(dst, src);
        } catch (UnsupportedOperationException | IOException e) {
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    private static String fetchLatestForgeVersion(String mcVersion) {
        try {
            String json = fetchString(BMCLAPI + "/forge/minecraft/" + mcVersion);
            if (json == null) return null;
            JsonArray arr = GSON.fromJson(json, JsonArray.class);
            if (arr == null || arr.size() == 0) return null;
            return getJsonString(arr.get(0).getAsJsonObject(), "version");
        } catch (Exception e) {
            LOG.error("Failed to fetch latest Forge version", e);
            return null;
        }
    }

    private static boolean installNeoForge(String mcVersion, String loaderVersion,
                                            Path gameDir, String javaPath,
                                            ProgressCallback progress,
                                            String folderName, AtomicBoolean cancel) throws Exception {
        throwIfCancelled(cancel);
        String dirName = (folderName == null || folderName.isBlank()) ? mcVersion : folderName.trim();
        boolean useCustomDir = dirName != null && !dirName.isBlank() && !dirName.equals(mcVersion);
        if (loaderVersion == null || loaderVersion.isEmpty()) {
            report(progress, 2, "Fetching latest NeoForge version...");
            loaderVersion = fetchLatestNeoForgeVersion(mcVersion);
            if (loaderVersion == null) {
                error(progress, "Failed to fetch NeoForge version for " + mcVersion);
                return false;
            }
            LOG.info("Latest NeoForge version: {}", loaderVersion);
        }

        report(progress, 5, "Downloading NeoForge installer...");
        String installerUrl = NEOFORGE_MAVEN + "/releases/net/neoforged/neoforge/"
                + loaderVersion + "/neoforge-" + loaderVersion + "-installer.jar";

        Path tempDir = gameDir.resolve(".starlight_temp");
        Files.createDirectories(tempDir);
        Path installerJar = tempDir.resolve("neoforge-installer-" + loaderVersion + ".jar");

        if (!downloadFile(installerUrl, installerJar, (String) null, cancel)) {
            throwIfCancelled(cancel);
            error(progress, "NeoForge installer download failed");
            return false;
        }

        // 自定义版本文件夹名：NeoForge 安装器要求原版位于标准版本目录，临时创建副本
        boolean tempVanillaCopy = false;
        if (useCustomDir) {
            tempVanillaCopy = ensureVanillaAtStandardDir(gameDir, mcVersion, dirName);
        }

        // 同 installForge：安装器子进程的网络不受候选链保护，先经镜像链预下载库
        preloadInstallerLibraries(installerJar, gameDir, progress);

        report(progress, 30, "Running NeoForge installer...");
        String[] cmd = {javaPath, "-jar", installerJar.toAbsolutePath().toString(),
                "--install-client", gameDir.toAbsolutePath().toString()};

        boolean installed = runInstallerProcess(cmd, progress, cancel);
        if (!installed) {
            error(progress, "NeoForge installer execution failed");
            cleanTempDir(tempDir);
            // 同上：只删本次临时创建的原版副本，不碰用户已有的标准版本目录
            if (tempVanillaCopy) removeTempVanillaCopy(gameDir, mcVersion);
            throwIfCancelled(cancel);
            return false;
        }

        report(progress, 85, "Verifying installation result...");
        String expectedVersionId = mcVersion + "-neoforge-" + loaderVersion;
        Path expectedDir = gameDir.resolve("versions").resolve(expectedVersionId);
        if (!Files.exists(expectedDir) || !Files.exists(expectedDir.resolve(expectedVersionId + ".json"))) {
            expectedDir = findVersionDirByPattern(gameDir, mcVersion, "neoforge", loaderVersion);
        }

        // 自定义版本文件夹名：将安装结果重命名为 dirName 并改写 json id
        if (useCustomDir) {
            if (expectedDir == null || !Files.exists(expectedDir)) {
                error(progress, "NeoForge installation finished but cannot locate version directory");
                cleanTempDir(tempDir);
                if (tempVanillaCopy) removeTempVanillaCopy(gameDir, mcVersion);
                return false;
            }
            if (!expectedDir.getFileName().toString().equals(dirName)) {
                if (!renameVersionDirTo(expectedDir, dirName, progress)) {
                    cleanTempDir(tempDir);
                    if (tempVanillaCopy) removeTempVanillaCopy(gameDir, mcVersion);
                    return false;
                }
                expectedDir = expectedDir.resolveSibling(dirName);
                expectedVersionId = dirName;
            }
        }

        LOG.info("NeoForge installation complete, version dir: {}", expectedDir);
        cleanTempDir(tempDir);
        if (tempVanillaCopy) removeTempVanillaCopy(gameDir, mcVersion);
        report(progress, 100, "NeoForge " + (expectedDir != null ? expectedDir.getFileName().toString() : loaderVersion) + " installation complete!");
        return true;
    }

    private static String fetchLatestNeoForgeVersion(String mcVersion) {
        try {
            String metaUrl = NEOFORGE_MAVEN + "/releases/net/neoforged/neoforge/maven-metadata.xml";
            String xml = fetchString(metaUrl);
            if (xml == null) return null;
            int releaseStart = xml.indexOf("<release>");
            int releaseEnd = xml.indexOf("</release>");
            if (releaseStart >= 0 && releaseEnd > releaseStart) {
                return xml.substring(releaseStart + "<release>".length(), releaseEnd).trim();
            }
            int lastVerStart = xml.lastIndexOf("<version>");
            int lastVerEnd = xml.lastIndexOf("</version>");
            if (lastVerStart >= 0 && lastVerEnd > lastVerStart) {
                return xml.substring(lastVerStart + "<version>".length(), lastVerEnd).trim();
            }
            return null;
        } catch (Exception e) {
            LOG.error("Failed to fetch latest NeoForge version", e);
            return null;
        }
    }

    private static Path findVersionDirByPattern(Path gameDir, String mcVersion,
                                                 String loaderName, String loaderVersion) {
        try {
            Path versionsDir = gameDir.resolve("versions");
            if (!Files.isDirectory(versionsDir)) return null;
            try (var stream = Files.list(versionsDir)) {
                return stream
                        .filter(Files::isDirectory)
                        .filter(d -> {
                            String name = d.getFileName().toString().toLowerCase(Locale.ROOT);
                            return name.contains(mcVersion)
                                    && name.contains(loaderName)
                                    && (loaderVersion == null || name.contains(loaderVersion));
                        })
                        .findFirst()
                        .orElse(null);
            }
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 运行加载器安装器子进程。
     *
     * <p>输出由独立线程读取（安装器的原始输出要回传给界面），主线程每 200ms 检查一次取消：
     * 用户点「取消下载」时立即 {@link Process#destroyForcibly()}，不必等安装器自己跑完。
     */
    private static boolean runInstallerProcess(String[] cmd, ProgressCallback progress, AtomicBoolean cancel) {
        Process process = null;
        try {
            LOG.info("Running installer: {}", String.join(" ", cmd));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            process = pb.start();
            final Process proc = process;
            // 保留最后若干行输出：安装器失败时真正的出错原因就写在里面，
            // 以前只记 exit code，用户只能看到一句「加载器安装失败」，无从排查
            java.util.ArrayDeque<String> tail = new java.util.ArrayDeque<>();
            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isBlank()) continue;
                        synchronized (tail) {
                            tail.addLast(line.trim());
                            while (tail.size() > 20) tail.removeFirst();
                        }
                        if (progress != null) {
                            try {
                                report(progress, -1, line);
                            } catch (RuntimeException ignored) {
                                // 进度回调异常（界面已关闭等）不能拖死输出读取
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // 进程被强杀时读线程会拿到流关闭异常，属正常收尾
                }
            }, "sl-loader-installer-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            // 200ms 轮询：取消置位就强杀安装器进程，不再干等它自己结束
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (isCancelled(cancel)) {
                    process.destroyForcibly();
                    readerThread.join(1000);
                    return false;
                }
            }
            readerThread.join(2000);
            int exitCode = process.exitValue();
            LOG.info("Installer exit code: {}", exitCode);
            if (exitCode != 0) {
                String tailText;
                synchronized (tail) {
                    tailText = String.join("\n", tail);
                }
                LOG.error("Installer failed: exit={} tail={}", exitCode, tailText);
                // 把安装器的实际输出回传给界面，而不是只报一句失败
                error(progress, "安装器退出码 " + exitCode + "，输出末尾：\n" + tailText);
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            if (process != null) process.destroyForcibly();
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            LOG.error("Failed to run installer", e);
            if (process != null) process.destroyForcibly();
            error(progress, I18N.get("task.p0107", e));
            return false;
        }
    }

    private static boolean downloadMavenLibrary(String name, String baseUrl,
                                                 Path libDir, String expectedSha1,
                                                 AtomicBoolean cancel) {
        try {
            throwIfCancelled(cancel);
            String path = resolveMavenPath(name);
            Path dest = libDir.resolve(path);
            if (Files.exists(dest)) {
                if (expectedSha1 == null || verifySha1(dest, expectedSha1)) {
                    return true;
                }
                Files.delete(dest);
            }
            Files.createDirectories(dest.getParent());
            String url = baseUrl.endsWith("/") ? baseUrl + path : baseUrl + "/" + path;
            return downloadFile(url, dest, expectedSha1, cancel);
        } catch (Exception e) {
            if (isCancellation(e)) throw (RuntimeException) e;
            LOG.warn("Failed to download library file: {} - {}", name, e.getMessage());
            return false;
        }
    }

    public static String resolveMavenPath(String name) {
        if (name == null || name.isEmpty()) return null;
        String group, artifact, version, classifier = null, ext = "jar";
        int atIdx = name.indexOf('@');
        if (atIdx >= 0) {
            ext = name.substring(atIdx + 1);
            name = name.substring(0, atIdx);
        }
        String[] parts = name.split(":");
        if (parts.length < 3) return null;
        group = parts[0].replace('.', '/');
        artifact = parts[1];
        version = parts[2];
        if (parts.length >= 4) classifier = parts[3];
        String fileName;
        if (classifier != null) fileName = artifact + "-" + version + "-" + classifier + "." + ext;
        else fileName = artifact + "-" + version + "." + ext;
        return group + "/" + artifact + "/" + version + "/" + fileName;
    }

    private static boolean downloadFile(String url, Path dest, String expectedSha1) {
        return downloadFile(url, dest, expectedSha1, null);
    }

    /**
     * 按候选链下载：镜像优先、官方兜底，失败在候选间<b>轮转</b>重试。
     *
     * <p>旧实现是「同一条镜像 URL 连打 3 遍再切官方」——镜像瞬时抖动时等于把失败
     * 重演 3 次（2026-10-22 21:44~21:49 日志：sponge-mixin 在 BMCLAPI 上 3 次 attempt
     * 全败耗时 5 分钟，切官方后同样失败，而两源当天实测均可用）。现在每次失败立刻
     * 换下一候选，总尝试次数 4 次，单次失败代价从「最坏 5 分钟」降到「最坏 1 分钟」。
     */
    private static boolean downloadFile(String url, Path dest, String expectedSha1,
                                        AtomicBoolean cancel) {
        throwIfCancelled(cancel);
        List<String> candidates = new ArrayList<>();
        for (URI u : DownloadProviderHolder.get().injectURLWithCandidates(url)) {
            String s = u.toString();
            if (!candidates.contains(s)) candidates.add(s);
        }
        if (!candidates.contains(url)) candidates.add(url);

        int maxAttempts = Math.max(4, candidates.size() * 2);
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            throwIfCancelled(cancel);
            String cand = candidates.get(attempt % candidates.size());
            if (downloadFileDirect(cand, dest, expectedSha1, cancel)) {
                return true;
            }
            if (attempt < maxAttempts - 1) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(CANCELLED_MESSAGE);
                }
            }
        }
        LOG.error("Download failed after {} attempts across {} candidates: {}",
                maxAttempts, candidates.size(), url);
        return false;
    }

    /** 单文件 body 读取总时限：库文件/mod jar 都是 MB 级，正常链路几秒完成，60s 是宽松兜底 */
    private static final long DOWNLOAD_BODY_TIMEOUT_MS = 60_000;

    /** 安装链路专用的 body 读取线程池（读循环放后台，主线程轮询取消与停滞；缓存线程防并行安装时排队） */
    private static final java.util.concurrent.ExecutorService DOWNLOAD_IO_POOL =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "sl-loader-install-io");
                t.setDaemon(true);
                return t;
            });

    /**
     * 单 URL 下载一次尝试（含 SHA-1 校验）。
     *
     * <p>与旧实现的差异：
     * <ul>
     *   <li><b>停滞兜底</b>：body 读取放后台线程，主线程轮询 60s 总时限——
     *       {@code InputStream.read()} 在对端不发数据时会无限阻塞，旧实现的
     *       5 分钟 {@code HttpRequest.timeout} 只管到响应头，日志里一次 attempt
     *       干等 1 分钟以上的根因就在这；</li>
     *   <li><b>残缺防护</b>：先写 {@code .sl-part} 临时文件，SHA-1 通过后才原子
     *       move 成正式文件——旧实现直接写目标文件，中途失败留下残缺 jar 还会被
     *       后续「文件已存在」判定误认为可用；</li>
     *   <li>重试职责上移到 {@link #downloadFile} 的候选轮转，本方法不再自带 3 次循环。</li>
     * </ul>
     */
    private static boolean downloadFileDirect(String url, Path dest, String expectedSha1,
                                              AtomicBoolean cancel) {
        throwIfCancelled(cancel);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<InputStream> resp;
        try {
            resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception e) {
            if (isCancelled(cancel)) throw new IllegalStateException(CANCELLED_MESSAGE);
            LOG.warn("Download connect failed: {} - {}", url, e.getMessage());
            return false;
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            LOG.warn("HTTP {}: {}", resp.statusCode(), url);
            return false;
        }

        Path tmp = dest.resolveSibling(dest.getFileName() + ".sl-part");
        java.util.concurrent.Future<Boolean> readTask = DOWNLOAD_IO_POOL.submit(() -> {
            try (InputStream in = resp.body(); OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (isCancelled(cancel)) throw new IllegalStateException(CANCELLED_MESSAGE);
                    out.write(buf, 0, n);
                }
            }
            return true;
        });

        long deadline = System.currentTimeMillis() + DOWNLOAD_BODY_TIMEOUT_MS;
        try {
            while (true) {
                if (isCancelled(cancel)) {
                    closeQuietly(resp);
                    readTask.cancel(true);
                    throw new IllegalStateException(CANCELLED_MESSAGE);
                }
                try {
                    if (readTask.get(200, TimeUnit.MILLISECONDS)) break;
                } catch (java.util.concurrent.TimeoutException te) {
                    if (System.currentTimeMillis() > deadline) {
                        // 关流让阻塞中的 read() 立刻抛异常退出，不再干等
                        closeQuietly(resp);
                        readTask.cancel(true);
                        LOG.warn("Download body timed out after {}ms: {}", DOWNLOAD_BODY_TIMEOUT_MS, url);
                        Files.deleteIfExists(tmp);
                        return false;
                    }
                } catch (java.util.concurrent.ExecutionException ee) {
                    Throwable cause = ee.getCause();
                    if (isCancellation(cause)) throw (RuntimeException) cause;
                    LOG.warn("Download read failed: {} - {}", url,
                            cause == null ? "null" : cause.getMessage());
                    Files.deleteIfExists(tmp);
                    return false;
                }
            }
            if (expectedSha1 != null && !verifySha1(tmp, expectedSha1)) {
                Files.deleteIfExists(tmp);
                return false;
            }
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (InterruptedException ie) {
            // 等待读循环时被中断（引擎关闭等）：清残件、恢复中断标记，按失败收尾
            closeQuietly(resp);
            readTask.cancel(true);
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException ioe) {
            // 残件清理/原子移动失败：下载本身可能已成功，但目标不可用，按失败处理
            LOG.warn("Download finalize failed: {} - {}", url, ioe.getMessage());
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
            return false;
        } finally {
            closeQuietly(resp);
        }
    }

    private static void closeQuietly(HttpResponse<InputStream> resp) {
        try {
            resp.body().close();
        } catch (IOException ignored) {
        }
    }

    /** 预下载并行度：8 线程。库文件全是 MB 级小文件，瓶颈在 RTT 而非带宽 */
    private static final int PRELOAD_PARALLELISM = 8;

    /**
     * 预下载安装器声明需要的库文件（从 installer jar 内的 {@code install_profile.json} 读取）。
     *
     * <p>Forge/NeoForge 官方安装器子进程内部直连官方 maven，不走启动器的镜像候选链——
     * 国内直连 {@code maven.minecraftforge.net} 一抖整个安装就失败（2026-10-22 22:14 日志：
     * roimfs/securemodules 连续 Read timed out 后报 "Forge installer execution failed"）。
     * 把 profile 声明的库提前经镜像链下进 {@code libraries/}，安装器下载时命中本地缓存，
     * 网络控制权回到启动器手里。读不出 profile 时静默跳过（尽力而为的加速，不是硬依赖）。
     *
     * <p>实现细节：
     * <ul>
     *   <li><b>8 线程并行</b>：库文件全是几十 KB ~ 几 MB 的小文件，瓶颈在每条的连接
     *       建立与 RTT 而非带宽，串行几十个库要一两分钟，并行后收敛到秒级；</li>
     *   <li>官方兜底 URL 用 {@code FORGE_MAVEN + path}（{@code /net/minecraftforge/...}，
     *       官方 maven 没有 {@code /maven/} 前缀，带前缀实测 404）；</li>
     *   <li>单条失败不阻断其余预下载，也不阻断安装——安装器子进程会再试官方源。</li>
     * </ul>
     */
    private static void preloadInstallerLibraries(Path installerJar, Path gameDir,
                                                  ProgressCallback progress) {
        List<String[]> libs = new ArrayList<>();
        try (ZipFile zf = new ZipFile(installerJar.toFile())) {
            var entry = zf.getEntry("install_profile.json");
            if (entry == null) return;
            String profileJson = new String(zf.getInputStream(entry).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            JsonObject profile = GSON.fromJson(profileJson, JsonObject.class);
            JsonArray arr = profile == null ? null : profile.getAsJsonArray("libraries");
            if (arr == null) return;
            for (var el : arr) {
                if (el == null || !el.isJsonObject()) continue;
                JsonObject lib = el.getAsJsonObject();
                String name = getJsonString(lib, "name");
                if (name == null) continue;
                String url = null;
                JsonObject dl = lib.getAsJsonObject("download");
                if (dl != null) url = getJsonString(dl, "url");
                if (url == null || url.isBlank()) url = FORGE_MAVEN + "/" + resolveMavenPath(name);
                libs.add(new String[]{name, url});
            }
        } catch (Exception e) {
            LOG.info("Skip installer library preload: {}", e.getMessage());
            return;
        }
        if (libs.isEmpty()) return;
        report(progress, 20, "并行预下载安装器库文件 " + libs.size() + " 个（镜像加速）...");
        Path libDir = gameDir.resolve("libraries");
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(PRELOAD_PARALLELISM, r -> {
                    Thread t = new Thread(r, "sl-installer-preload");
                    t.setDaemon(true);
                    return t;
                });
        try {
            List<java.util.concurrent.Future<Boolean>> futures = new ArrayList<>();
            for (String[] lib : libs) {
                futures.add(pool.submit(() ->
                        // 预下载阶段不传 cancel：取消由安装器子进程响应，这里中断反而引入半截状态
                        downloadMavenLibrary(lib[0], lib[1], libDir, null, null)));
            }
            int done = 0;
            for (var f : futures) {
                try {
                    if (f.get()) done++;
                } catch (java.util.concurrent.ExecutionException ee) {
                    LOG.warn("Preload task failed: {}", ee.getMessage());
                }
            }
            LOG.info("Installer library preload: {}/{} via mirror chain", done, libs.size());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            LOG.info("Installer library preload interrupted");
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean downloadFile(String url, Path dest, String[] mirrors) {
        if (downloadFile(url, dest, (String) null)) return true;
        if (mirrors != null) {
            for (String mirror : mirrors) {
                String mirrorUrl = url.replace("https://maven.fabricmc.net", mirror)
                        .replace("https://maven.minecraftforge.net", mirror)
                        .replace("https://maven.neoforged.net", mirror)
                        .replace("https://libraries.minecraft.net", mirror);
                if (!mirrorUrl.equals(url) && downloadFile(mirrorUrl, dest, (String) null)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean verifySha1(Path file, String expected) {
        if (expected == null || expected.isEmpty()) return true;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] data = Files.readAllBytes(file);
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            String actual = sb.toString();
            boolean match = actual.equalsIgnoreCase(expected);
            if (!match) {
                LOG.warn("SHA-1: expected={}, actual={}", expected, actual);
            }
            return match;
        } catch (Exception e) {
            return false;
        }
    }

    private static void cleanTempDir(Path tempDir) {
        try {
            if (Files.exists(tempDir)) {
                try (var stream = Files.walk(tempDir)) {
                    stream.sorted(Comparator.reverseOrder())
                            .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
                }
            }
        } catch (IOException ignored) {}
    }

    private static String fetchString(String url) {
        // 镜像优先、官方兜底（与 VersionDownloadService.fetchString 同一策略）：
        // 选中源注入后的镜像候选在前，官方原始 URL 在后，逐个尝试，20s/候选。
        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
        for (java.net.URI u : DownloadProviderHolder.get().injectURLWithCandidates(url)) {
            candidates.add(u.toString());
        }
        candidates.add(url);
        for (String cand : candidates) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(cand))
                        .header("User-Agent", AppConfig.USER_AGENT)
                        .timeout(Duration.ofSeconds(20))
                        .GET()
                        .build();
                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
                if (resp.statusCode() == 200) return resp.body();
            } catch (Exception e) {
                LOG.warn("HTTP request failed: {}", cand);
            }
        }
        return null;
    }

    private static String getJsonString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) return null;
        var e = obj.get(key);
        if (e == null || !e.isJsonPrimitive()) return null;
        String val = e.getAsString();
        return val.isEmpty() ? null : val;
    }

    private static boolean getJsonBoolean(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) return false;
        var e = obj.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }

    /**
     * 上报进度或一行日志。
     *
     * <p>{@code pct < 0} 表示「只输出一行日志，不改变进度」——安装器的原始输出就走这条路。
     * 以前这种情况下只往 stdout 打印，根本没有回调给界面，于是安装器报的错用户在卡片上
     * 一行都看不到，只能得到一句笼统的「加载器安装失败」。
     */
    private static void report(ProgressCallback cb, int pct, String msg) {
        if (cb != null) {
            cb.onProgress(pct, msg);
        }
        if (pct >= 0) LOG.info("[{}%] {}", pct, msg);
        else LOG.info("[Installer] {}", msg);
    }

    private static void error(ProgressCallback cb, String msg) {
        if (cb != null) cb.onProgress(0, msg);
        LOG.error(msg);
    }

    /** 取消标志是否已置位（null 视为不可取消） */
    private static boolean isCancelled(AtomicBoolean cancel) {
        return cancel != null && cancel.get();
    }

    /** 取消后立刻抛出让安装链路退出；文案与下载引擎统一，供上层识别「取消」而不是「失败」 */
    private static void throwIfCancelled(AtomicBoolean cancel) {
        if (isCancelled(cancel)) {
            throw new IllegalStateException(CANCELLED_MESSAGE);
        }
    }

    /** 是否为调用方取消（见 {@link HttpDownloadEngine#CANCELLED_MESSAGE}） */
    private static boolean isCancellation(Throwable e) {
        return e instanceof RuntimeException && CANCELLED_MESSAGE.equals(e.getMessage());
    }
}
