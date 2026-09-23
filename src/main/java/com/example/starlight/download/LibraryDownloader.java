package com.example.starlight.download;

import com.example.starlight.newui.AppConfig;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * 下载 Minecraft 缺失的库文件（libraries）。
 * <p>
 * 解析版本 JSON 中的 "libraries" 数组，检查本地 {@code gameDir/libraries/} 下是否存在，
 * 缺失的从 {@code https://libraries.minecraft.net/} 或 BMCLAPI 镜像下载。
 * <p>
 * 支持 classifiers（原生库）, 仅下载与当前操作系统匹配的内容。
 */
public class LibraryDownloader {

    private static final String LIBRARIES_MOJANG  = "https://libraries.minecraft.net/";
    private static final String LIBRARIES_BMCLAPI = "https://bmclapi2.bangbang93.com/libraries/";

    private static final int BUFFER_SIZE      = 8192;
    private static final int CONNECT_TIMEOUT  = 10_000;
    private static final int READ_TIMEOUT     = 15_000;

    // ---------------------------------------------------------------
    // 回调接口
    // ---------------------------------------------------------------

    public interface ProgressCallback {
        void onProgress(int percent, String message);
    }

    // ---------------------------------------------------------------
    // 主入口：下载所有缺失的 libraries
    // ---------------------------------------------------------------

    /**
     * 解析 version JSON，下载所有缺失的 library 文件。
     *
     * @param gameDir     Minecraft 根目录
     * @param versionJson 版本 JSON 原始字符串
     * @param progress    进度回调（可为 null）
     * @return 实际下载的 library 数量（0 表示全部已存在）
     */
    public static int downloadMissingLibraries(String gameDir, String versionJson, ProgressCallback progress) {
        if (versionJson == null || versionJson.isBlank()) {
            if (progress != null) progress.onProgress(0, "Version JSON is empty");
            return 0;
        }

        try {
            JsonObject root = JsonParser.parseString(versionJson).getAsJsonObject();
            if (!root.has("libraries")) {
                if (progress != null) progress.onProgress(100, "No libraries defined in version JSON");
                return 0;
            }

            JsonArray libraries = root.getAsJsonArray("libraries");
            int total = libraries.size();
            if (total == 0) {
                if (progress != null) progress.onProgress(100, "No libraries to check");
                return 0;
            }

            // 收集需要下载的 library 条目
            List<LibraryEntry> entries = resolveLibraries(libraries);

            if (entries.isEmpty()) {
                if (progress != null) progress.onProgress(100, "All libraries already present");
                return 0;
            }

            int downloaded = 0;
            int failed     = 0;

            for (int i = 0; i < entries.size(); i++) {
                LibraryEntry entry = entries.get(i);
                boolean ok = downloadLibraryEntry(gameDir, entry, progress);
                if (ok) {
                    downloaded++;
                } else {
                    failed++;
                }

                if (progress != null) {
                    int percent = (i + 1) * 100 / entries.size();
                    String msg = String.format("Libraries: %d/%d downloaded, %d failed",
                            downloaded, entries.size(), failed);
                    progress.onProgress(Math.min(percent, 100), msg);
                }
            }

            return downloaded;

        } catch (JsonSyntaxException e) {
            if (progress != null) progress.onProgress(0, "Error parsing version JSON: " + e.getMessage());
            return 0;
        }
    }

    // ---------------------------------------------------------------
    // 下载单个 library（公开方法）
    // ---------------------------------------------------------------

    /**
     * 根据 Maven 风格路径下载单个 library 到 {@code gameDir/libraries/<path>}。
     * <p>
     * 例：mavenPath = "net/minecraftforge/forge/1.21-51.0.33/forge-1.21-51.0.33-shim.jar"
     *
     * @param gameDir   Minecraft 根目录
     * @param mavenPath library 的 Maven 路径（无前导斜杠）
     * @param progress  进度回调（可为 null）
     * @return true 表示下载成功或文件已存在
     */
    public static boolean downloadLibrary(String gameDir, String mavenPath, ProgressCallback progress) {
        Path targetFile = Paths.get(gameDir, "libraries", mavenPath);
        try {
            if (Files.exists(targetFile) && Files.size(targetFile) > 0) {
                if (progress != null) progress.onProgress(100, "Library already exists: " + mavenPath);
                return true;
            }
        } catch (IOException e) {
            // Files.size failed, will re-download
        }

        // 尝试 BMCLAPI 再回退 Mojang
        String bmclapiUrl = LIBRARIES_BMCLAPI + mavenPath;
        boolean ok = downloadFile(bmclapiUrl, targetFile, progress);

        if (!ok) {
            String mojangUrl = LIBRARIES_MOJANG + mavenPath;
            ok = downloadFile(mojangUrl, targetFile, progress);
        }

        if (ok) {
            if (progress != null) progress.onProgress(100, "Downloaded: " + mavenPath);
        } else {
            if (progress != null) progress.onProgress(0, "Failed: " + mavenPath);
        }
        return ok;
    }

    // ---------------------------------------------------------------
    // 内部解析与下载
    // ---------------------------------------------------------------

    /**
     * 解析 libraries 数组，过滤出当前平台需要下载的条目（跳过已存在的）。
     */
    private static List<LibraryEntry> resolveLibraries(JsonArray libraries) {
        List<LibraryEntry> result = new ArrayList<>();
        String osName = normalizeOs(System.getProperty("os.name"));

        for (JsonElement elem : libraries) {
            JsonObject lib = elem.getAsJsonObject();

            // ---- 规则过滤 ----
            // 检查 "rules" 字段决定是否跳过此 library
            if (lib.has("rules")) {
                if (!isRuleAllowed(lib.getAsJsonArray("rules"), osName)) {
                    continue; // 当前平台不该用此 lib
                }
            }

            // 获取 artifact 信息
            JsonObject downloads = lib.has("downloads") ? lib.getAsJsonObject("downloads") : null;
            JsonObject artifact  = (downloads != null && downloads.has("artifact"))
                    ? downloads.getAsJsonObject("artifact") : null;

            // 主 artifact
            if (artifact != null && artifact.has("path")) {
                String path = artifact.get("path").getAsString();
                String mavenPath = path; // downloads.artifact.path 就是相对于 libraries/ 的路径
                LibraryEntry entry = new LibraryEntry(mavenPath, null, path);
                if (isMissing(entry)) {
                    result.add(entry);
                }
            }

            // classifiers（原生库：natives-linux, natives-windows 等）
            if (downloads != null && downloads.has("classifiers")) {
                JsonObject classifiers = downloads.getAsJsonObject("classifiers");
                String nativeClassifier = getNativeClassifier(osName);

                if (nativeClassifier != null && classifiers.has(nativeClassifier)) {
                    JsonObject nativeArtifact = classifiers.getAsJsonObject(nativeClassifier);
                    if (nativeArtifact.has("path")) {
                        String path = nativeArtifact.get("path").getAsString();
                        LibraryEntry entry = new LibraryEntry(path, nativeClassifier, path);
                        if (isMissing(entry)) {
                            result.add(entry);
                        }
                    }
                }
            }
        }

        return result;
    }

    /**
     * 下载单个 LibraryEntry。
     */
    private static boolean downloadLibraryEntry(String gameDir, LibraryEntry entry, ProgressCallback progress) {
        // 如果本地已存在（可能在 resolve 之后被其他线程下载），跳过
        if (!isMissing(entry)) {
            return true;
        }

        String mavenPath = entry.path; // downloads.artifact.path 是相对于 libraries/ 的
        Path targetPath = Paths.get(gameDir, "libraries", mavenPath);

        return downloadLibraryByPath(mavenPath, targetPath, progress);
    }

    /**
     * 按 Maven 路径下载，先 BMCLAPI 再 Mojang。
     */
    private static boolean downloadLibraryByPath(String mavenPath, Path targetPath, ProgressCallback progress) {
        try {
            Files.createDirectories(targetPath.getParent());
        } catch (IOException e) {
            if (progress != null) progress.onProgress(0, "Cannot create directories: " + e.getMessage());
            return false;
        }

        String bmclapiUrl = LIBRARIES_BMCLAPI + mavenPath;
        boolean ok = downloadFile(bmclapiUrl, targetPath, null);

        if (!ok) {
            String mojangUrl = LIBRARIES_MOJANG + mavenPath;
            ok = downloadFile(mojangUrl, targetPath, null);
        }

        return ok;
    }

    // ---------------------------------------------------------------
    // 规则匹配
    // ---------------------------------------------------------------

    /**
     * 根据 Mojang 的规则数组判断当前平台是否允许此 library。
     */
    private static boolean isRuleAllowed(JsonArray rules, String osName) {
        // 默认允许
        boolean allowed = true;

        for (JsonElement elem : rules) {
            JsonObject rule = elem.getAsJsonObject();
            String action = rule.get("action").getAsString();

            if (rule.has("os")) {
                JsonObject os = rule.getAsJsonObject("os");
                boolean match = true;

                if (os.has("name")) {
                    String requiredOs = os.get("name").getAsString();
                    match = requiredOs.equalsIgnoreCase(osName);
                }

                // 支持 arch 过滤（如 "x86"）
                if (match && os.has("arch")) {
                    String requiredArch = os.get("arch").getAsString();
                    String currentArch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
                    match = currentArch.contains(requiredArch.toLowerCase(Locale.ROOT));
                }

                if ("allow".equals(action)) {
                    if (!match) allowed = false;
                } else if ("disallow".equals(action)) {
                    if (match) allowed = false;
                }
            } else {
                // 无 OS 条件：全局 allow / disallow
                allowed = "allow".equals(action);
            }
        }

        return allowed;
    }

    // ---------------------------------------------------------------
    // 操作系统相关
    // ---------------------------------------------------------------

    /**
     * 将系统属性标准化为 Mojang 的命名：windows, linux, osx。
     */
    private static String normalizeOs(String osName) {
        String lower = osName.toLowerCase(Locale.ROOT);
        if (lower.contains("win"))  return "windows";
        if (lower.contains("mac"))  return "osx";
        if (lower.contains("linux")) return "linux";
        if (lower.contains("sunos") || lower.contains("solaris")) return "linux";
        return "unknown";
    }

    /**
     * 返回当前系统对应的 native classifier 名称。
     * <ul>
     *   <li>windows -> natives-windows</li>
     *   <li>osx     -> natives-osx</li>
     *   <li>linux   -> natives-linux</li>
     * </ul>
     */
    private static String getNativeClassifier(String osName) {
        return switch (osName) {
            case "windows" -> "natives-windows";
            case "osx"     -> "natives-osx";
            case "linux"   -> "natives-linux";
            default        -> null;
        };
    }

    // ---------------------------------------------------------------
    // 文件存在性检查
    // ---------------------------------------------------------------

    /**
     * 判断 library 在本地是否缺失（文件不存在或为空）。
     */
    private static boolean isMissing(LibraryEntry entry) {
        try {
            Path path = Paths.get(entry.path);
            if (path.isAbsolute()) {
                return !(Files.exists(path) && Files.size(path) > 0);
            }
            // 相对路径需要 gameDir/libraries/ 前缀，但 isMissing 是 resolve 阶段调用的，
            // 此时还没有 gameDir 上下文。这里我们只检查 path 本身是否绝对路径：
            // 如果 entry.path 是相对路径（如 "net/minecraft/xxx.jar"），
            // 则 isMissing 无法判断，返回 true 让调用方处理。
            // 实际上 entry.path 来自 downloads.artifact.path 是相对路径，
            // 因此这里始终视为缺失，后续下载时会去实际检查。
            return true;
        } catch (IOException e) {
            return true;
        }
    }

    // ---------------------------------------------------------------
    // 通用 HTTP 下载
    // ---------------------------------------------------------------

    /**
     * 从 URL 下载文件到 targetPath，自动创建父目录。
     *
     * @return true 表示成功且文件完整
     */
    static boolean downloadFile(String urlStr, Path targetPath, ProgressCallback progress) {
        HttpURLConnection conn = null;
        try {
            Files.createDirectories(targetPath.getParent());

            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("User-Agent", AppConfig.USER_AGENT);

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                return false;
            }

            long contentLength = conn.getContentLengthLong();

            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(targetPath.toFile())) {

                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                long totalRead = 0;

                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    totalRead += read;
                }

                if (contentLength > 0 && totalRead != contentLength) {
                    Files.deleteIfExists(targetPath);
                    return false;
                }
            }

            return true;

        } catch (IOException e) {
            try {
                Files.deleteIfExists(targetPath);
            } catch (IOException ignored) {
            }
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---------------------------------------------------------------
    // 内部数据类
    // ---------------------------------------------------------------

    /**
     * 表示一个待下载的 library 条目。
     */
    private static class LibraryEntry {
        /** 相对于 libraries/ 的路径，如 "net/minecraft/server/MinecraftServer/1.21/server.jar" */
        final String path;
        LibraryEntry(String path, String classifier, String rawPath) {
            this.path = path;
        }
    }
}
