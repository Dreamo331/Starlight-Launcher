package com.example.starlight.version;

import com.example.starlight.download.BMCLAPIDownloadProvider;
import com.example.starlight.download.DownloadProvider;
import com.example.starlight.newui.AppConfig;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoaderInstallEngine {

    private static final Logger LOG = LoggerFactory.getLogger(LoaderInstallEngine.class);
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

    /** 当前下载提供者（默认 BMCLAPI，由 LauncherView 启动时按「下载源」配置应用） */
    private static DownloadProvider downloadProvider = new BMCLAPIDownloadProvider(BMCLAPI);

    /**
     * 设置下载提供者（设置页切换下载源时由 LauncherView 调用，立即生效）
     */
    public static void setDownloadProvider(DownloadProvider provider) {
        if (provider != null) {
            downloadProvider = provider;
        }
    }

    /**
     * 获取当前下载提供者
     */
    public static DownloadProvider getDownloadProvider() {
        return downloadProvider;
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
        if (mcVersion == null || mcVersion.isEmpty()) {
            error(progress, "MC version cannot be empty");
            return false;
        }
        if (gameDir == null) gameDir = ".minecraft";
        if (javaPath == null || javaPath.isEmpty()) javaPath = "java";

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
                case "fabric" -> installFabric(mcVersion, loaderVersion, gamePath, progress, folderName);
                case "forge" -> installForge(mcVersion, loaderVersion, gamePath, javaPath, progress, folderName);
                case "neoforge" -> installNeoForge(mcVersion, loaderVersion, gamePath, javaPath, progress, folderName);
                default -> {
                    error(progress, "Unsupported loader type: " + loaderType);
                    yield false;
                }
            };
        } catch (Exception e) {
            LOG.error("Installation failed", e);
            error(progress, "Installation failed: " + e.getMessage());
            return false;
        }
    }

    private static boolean installFabric(String mcVersion, String loaderVersion,
                                          Path gameDir, ProgressCallback progress) throws Exception {
        return installFabric(mcVersion, loaderVersion, gameDir, progress, null);
    }

    private static boolean installFabric(String mcVersion, String loaderVersion,
                                          Path gameDir, ProgressCallback progress,
                                          String folderName) throws Exception {
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
            Path libDir = gameDir.resolve("libraries");
            report(progress, 15, "Downloading library files (0/" + total + ")...");

            for (int i = 0; i < total; i++) {
                JsonObject lib = libraries.get(i).getAsJsonObject();
                String name = getJsonString(lib, "name");
                String url = getJsonString(lib, "url");
                if (name == null || url == null) {
                    completed++;
                    continue;
                }
                boolean ok = downloadMavenLibrary(name, url, libDir, null);
                if (ok) completed++;
                else errors++;
                int pct = 15 + (completed * 60 / Math.max(total, 1));
                report(progress, Math.min(pct, 75),
                        "下载库文件: " + completed + "/" + total + (errors > 0 ? " (" + errors + " 错误)" : ""));
            }
            if (errors > 0) {
                LOG.warn("Fabric library download finished with {} failures", errors);
            }
        }

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
                                         String folderName) throws Exception {
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

        if (!downloadFile(installerUrl, installerJar, (String) null)) {
            String mirrorUrl = BMCLAPI + "/maven/net/minecraftforge/forge/"
                    + mcVersion + "-" + loaderVersion + "/forge-"
                    + mcVersion + "-" + loaderVersion + "-installer.jar";
            if (!downloadFile(mirrorUrl, installerJar, (String) null)) {
                error(progress, "Forge installer download failed");
                return false;
            }
        }

        // 自定义版本文件夹名：Forge 安装器要求原版位于标准版本目录，临时创建副本
        boolean tempVanillaCopy = false;
        if (useCustomDir) {
            tempVanillaCopy = ensureVanillaAtStandardDir(gameDir, mcVersion, dirName);
        }

        report(progress, 30, "Running Forge installer...");
        String[] cmd = {javaPath, "-jar", installerJar.toAbsolutePath().toString(),
                "--installClient", gameDir.toAbsolutePath().toString()};

        boolean installed = runInstallerProcess(cmd, progress);
        if (!installed) {
            error(progress, "Forge installer execution failed");
            cleanTempDir(tempDir);
            removeTempVanillaCopy(gameDir, mcVersion);
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
                removeTempVanillaCopy(gameDir, mcVersion);
                return false;
            }
            if (!expectedDir.getFileName().toString().equals(dirName)) {
                if (!renameVersionDirTo(expectedDir, dirName, progress)) {
                    cleanTempDir(tempDir);
                    removeTempVanillaCopy(gameDir, mcVersion);
                    return false;
                }
                expectedDir = expectedDir.resolveSibling(dirName);
                expectedVersionId = dirName;
            }
        }

        LOG.info("Forge installation complete, version dir: {}", expectedDir);
        cleanTempDir(tempDir);
        removeTempVanillaCopy(gameDir, mcVersion);
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
     * 将父版本 JSON 的内容合并进子版本（libraries + 缺失字段），并移除 inheritsFrom。
     * 用于自定义版本文件夹名场景：原版被安装到自定义目录，加载器版本不再依赖标准目录。
     * 父 JSON 优先从标准目录（versions/&lt;parentId&gt;/）读取，回退到自定义目录（versions/&lt;dirName&gt;/）。
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
        // 缺失字段从父版本继承
        for (String key : new String[]{"arguments", "assets", "assetIndex",
                "javaVersion", "complianceLevel", "minecraftArguments"}) {
            if (!child.has(key) && parent.has(key)) {
                child.add(key, parent.get(key));
            }
        }
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
                                            String folderName) throws Exception {
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

        if (!downloadFile(installerUrl, installerJar, (String) null)) {
            error(progress, "NeoForge installer download failed");
            return false;
        }

        // 自定义版本文件夹名：NeoForge 安装器要求原版位于标准版本目录，临时创建副本
        boolean tempVanillaCopy = false;
        if (useCustomDir) {
            tempVanillaCopy = ensureVanillaAtStandardDir(gameDir, mcVersion, dirName);
        }

        report(progress, 30, "Running NeoForge installer...");
        String[] cmd = {javaPath, "-jar", installerJar.toAbsolutePath().toString(),
                "--install-client", gameDir.toAbsolutePath().toString()};

        boolean installed = runInstallerProcess(cmd, progress);
        if (!installed) {
            error(progress, "NeoForge installer execution failed");
            cleanTempDir(tempDir);
            removeTempVanillaCopy(gameDir, mcVersion);
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
                removeTempVanillaCopy(gameDir, mcVersion);
                return false;
            }
            if (!expectedDir.getFileName().toString().equals(dirName)) {
                if (!renameVersionDirTo(expectedDir, dirName, progress)) {
                    cleanTempDir(tempDir);
                    removeTempVanillaCopy(gameDir, mcVersion);
                    return false;
                }
                expectedDir = expectedDir.resolveSibling(dirName);
                expectedVersionId = dirName;
            }
        }

        LOG.info("NeoForge installation complete, version dir: {}", expectedDir);
        cleanTempDir(tempDir);
        removeTempVanillaCopy(gameDir, mcVersion);
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

    private static boolean runInstallerProcess(String[] cmd, ProgressCallback progress) {
        try {
            LOG.info("Running installer: {}", String.join(" ", cmd));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            // 保留最后若干行输出：安装器失败时真正的出错原因就写在里面，
            // 以前只记 exit code，用户只能看到一句「加载器安装失败」，无从排查
            java.util.ArrayDeque<String> tail = new java.util.ArrayDeque<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        tail.addLast(line.trim());
                        while (tail.size() > 20) tail.removeFirst();
                        if (progress != null) {
                            report(progress, -1, line);
                        }
                    }
                }
            }
            int exitCode = process.waitFor();
            LOG.info("Installer exit code: {}", exitCode);
            if (exitCode != 0) {
                StringBuilder sb = new StringBuilder("安装器退出码 " + exitCode);
                for (String l : tail) sb.append("\n").append(l);
                LOG.error("Installer failed: {}", sb);
                // 把安装器的实际输出回传给界面，而不是只报一句失败
                error(progress, "安装器退出码 " + exitCode + "，输出末尾：\n" + String.join("\n", tail));
                return false;
            }
            return true;
        } catch (Exception e) {
            LOG.error("Failed to run installer", e);
            error(progress, "无法运行安装器: " + e);
            return false;
        }
    }

    private static boolean downloadMavenLibrary(String name, String baseUrl,
                                                 Path libDir, String expectedSha1) {
        try {
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
            return downloadFile(url, dest, expectedSha1);
        } catch (Exception e) {
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
        // 按当前下载源注入 URL（BMCLAPI/MCBBS 镜像替换），注入源失败后回退原始 URL
        String injectedUrl = downloadProvider.injectURL(url);
        if (downloadFileDirect(injectedUrl, dest, expectedSha1)) {
            return true;
        }
        if (!injectedUrl.equals(url)) {
            return downloadFileDirect(url, dest, expectedSha1);
        }
        return false;
    }

    /** 单 URL 下载（3 次重试 + SHA-1 校验） */
    private static boolean downloadFileDirect(String url, Path dest, String expectedSha1) {
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofMinutes(5))
                            .GET()
                            .build();
                    HttpResponse<InputStream> resp = HTTP.send(req,
                            HttpResponse.BodyHandlers.ofInputStream());
                    if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                        try (InputStream in = resp.body(); OutputStream out = Files.newOutputStream(dest)) {
                            in.transferTo(out);
                        }
                        if (expectedSha1 != null && !verifySha1(dest, expectedSha1)) {
                            Files.deleteIfExists(dest);
                            continue;
                        }
                        return true;
                    } else {
                        LOG.warn("HTTP {}: {}", resp.statusCode(), url);
                    }
                } catch (IOException e) {
                    LOG.warn("Download attempt {}/3 failed: {}", attempt + 1, url);
                }
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            LOG.error("Download failed: {}", url);
        }
        return false;
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
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", AppConfig.USER_AGENT)
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            return resp.statusCode() == 200 ? resp.body() : null;
        } catch (Exception e) {
            LOG.warn("HTTP request failed: {}", url);
            return null;
        }
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
}
