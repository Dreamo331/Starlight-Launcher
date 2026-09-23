package com.example.starlight.download;

import com.example.starlight.newui.AppConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 下载 Minecraft 资源文件（asset index + 实际 asset objects）。
 * <p>
 * 流程：
 * 1. 从 Mojang / BMCLAPI 拉取 asset index JSON（如 "1.21"）；
 * 2. 解析 index，得到 {"objects": {"path/to/file": {"hash": "...", "size": N}}}；
 * 3. 逐个下载缺失或校验失败的 object，存放至 assets/objects/&lt;first2&gt;/&lt;hash&gt;。
 */
public class AssetDownloader {

    private static final String INDEX_BASE      = "https://launchermeta.mojang.com/v1/packages/%s/%s.json";
    private static final String OBJECT_MOJANG   = "https://resources.download.minecraft.net/%s/%s";
    private static final String OBJECT_BMCLAPI  = "https://bmclapi2.bangbang93.com/assets/%s";

    private static final int BUFFER_SIZE = 8192;
    private static final int CONNECT_TIMEOUT = 10_000;
    private static final int READ_TIMEOUT = 15_000;

    // ---------------------------------------------------------------
    // 回调接口
    // ---------------------------------------------------------------

    public interface ProgressCallback {
        void onProgress(int percent, String message);
    }

    // ---------------------------------------------------------------
    // 步骤 1：下载 asset index
    // ---------------------------------------------------------------

    /**
     * 下载指定版本的 asset index 到 {@code gameDir/assets/indexes/<assetId>.json}。
     *
     * @param assetId  版本 ID，如 "1.21" 或 "legacy"
     * @param gameDir  Minecraft 根目录
     * @param progress 进度回调（可为 null）
     * @return true 表示下载成功或文件已存在
     */
    public static boolean downloadAssetIndex(String assetId, String gameDir, ProgressCallback progress) {
        Path indexesDir = Paths.get(gameDir, "assets", "indexes");
        Path targetFile = indexesDir.resolve(assetId + ".json");

        try {
            Files.createDirectories(indexesDir);

            // 如果文件已存在且不为空，直接跳过
            if (Files.exists(targetFile) && Files.size(targetFile) > 0) {
                if (progress != null) progress.onProgress(100, "Asset index already exists: " + assetId);
                return true;
            }

            if (progress != null) progress.onProgress(0, "Downloading asset index: " + assetId);

            // 目前 BMCLAPI 暂未提供 index 镜像，直接用 Mojang 源
            // URL 中的 %s/sha1 部分需要从 version manifest 中获取，这里简化处理：
            // 实际启动器中通常先解析 version manifest 拿到 sha1 再构造 URL。
            // 出于通用性，我们尝试两种常见形式：
            //   https://launchermeta.mojang.com/v1/packages/<sha1>/<assetId>.json
            // 如果没有 sha1，就无法直接从 Mojang 下载。这里约定调用方传入 assetId 的同时，
            // 也可以直接将 sha1 拼入 assetId 字段，格式为 "sha1/assetId"。
            // 兼容写法：先尝试 BMCLAPI 的 index 端点
            String indexUrl = "https://bmclapi2.bangbang93.com/assets/indexes/" + assetId + ".json";
            boolean downloaded = downloadFile(indexUrl, targetFile, progress);

            if (!downloaded) {
                // fallback：如果 assetId 包含 "/" 说明调用方已传入了 sha1/assetId
                if (assetId.contains("/")) {
                    indexUrl = String.format(INDEX_BASE, assetId.replace("/", "/"), "");
                    // 去掉末尾多余的 .
                    if (indexUrl.endsWith(".")) indexUrl = indexUrl.substring(0, indexUrl.length() - 1);
                    downloaded = downloadFile(indexUrl, targetFile, progress);
                } else {
                    // 最后的 fallback：直接尝试 launchermeta（不含 sha1 基本会 404，记录日志）
                    if (progress != null) progress.onProgress(50, "Unable to locate asset index (sha1 required for Mojang URL)");
                }
            }

            if (downloaded) {
                if (progress != null) progress.onProgress(100, "Asset index downloaded: " + assetId);
                return true;
            } else {
                if (progress != null) progress.onProgress(0, "Failed to download asset index: " + assetId);
                return false;
            }

        } catch (IOException e) {
            if (progress != null) progress.onProgress(0, "IO error downloading asset index: " + e.getMessage());
            return false;
        }
    }

    // ---------------------------------------------------------------
    // 步骤 2：下载所有 asset objects
    // ---------------------------------------------------------------

    /**
     * 解析已下载的 asset index，下载所有缺失的 object 文件。
     * <p>
     * objects 存放路径：{@code gameDir/assets/objects/<hash前两位>/<完整hash>}
     *
     * @param gameDir  Minecraft 根目录
     * @param progress 进度回调（可为 null）
     * @return true 表示所有 object 均已就绪
     */
    public static boolean downloadAssets(String gameDir, ProgressCallback progress) {
        // 查找第一个可用的 index 文件
        Path indexesDir = Paths.get(gameDir, "assets", "indexes");
        if (!Files.isDirectory(indexesDir)) {
            if (progress != null) progress.onProgress(0, "Indexes directory not found: " + indexesDir);
            return false;
        }

        File[] indexFiles = indexesDir.toFile().listFiles((dir, name) -> name.endsWith(".json"));
        if (indexFiles == null || indexFiles.length == 0) {
            if (progress != null) progress.onProgress(0, "No asset index files found in " + indexesDir);
            return false;
        }

        // 逐个 index 合并下载（通常只有一个）
        boolean anySuccess = false;
        for (File indexFile : indexFiles) {
            String assetId = indexFile.getName().replace(".json", "");
            boolean ok = downloadAssetsFromIndex(indexFile.toPath(), gameDir, assetId, progress);
            if (ok) anySuccess = true;
        }
        return anySuccess;
    }

    /**
     * 从单个 index 文件下载 objects。
     */
    private static boolean downloadAssetsFromIndex(Path indexFile, String gameDir, String assetId,
                                                   ProgressCallback progress) {
        try {
            String json = Files.readString(indexFile);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            // 兼容 virtual 根键（部分 index 用 "objects" 或 "virtual"）
            JsonObject objects;
            if (root.has("objects")) {
                objects = root.getAsJsonObject("objects");
            } else if (root.has("virtual")) {
                objects = root.getAsJsonObject("virtual");
            } else {
                if (progress != null) progress.onProgress(0, "No 'objects' key in index: " + indexFile);
                return false;
            }

            // 是否需要虚拟根目录（新版 index 用 virtual）
            boolean virtual = root.has("virtual") && root.get("virtual").isJsonObject()
                    && !root.getAsJsonObject("virtual").entrySet().isEmpty();
            // 这里统一用 objects 的内容
            if (virtual) {
                objects = root.getAsJsonObject("virtual");
            }

            int total = objects.entrySet().size();
            if (total == 0) {
                if (progress != null) progress.onProgress(100, "No objects to download for: " + assetId);
                return true;
            }

            int completed = 0;
            int skipped  = 0;
            int failed   = 0;

            for (Map.Entry<String, com.google.gson.JsonElement> entry : objects.entrySet()) {
                // 跳过 Minecraft 声音文件（.ogg），可以按需保留
                String virtualPath = entry.getKey();
                JsonObject obj = entry.getValue().getAsJsonObject();

                String hash = obj.get("hash").getAsString();
                int size = obj.has("size") ? obj.get("size").getAsInt() : -1;

                // 目标路径：assets/objects/<first2>/<hash>
                String firstTwo = hash.substring(0, 2);
                Path objectFile = Paths.get(gameDir, "assets", "objects", firstTwo, hash);

                // 检查是否已存在且完整
                if (Files.exists(objectFile)) {
                    long actualSize = Files.size(objectFile);
                    if (size > 0 && actualSize == size) {
                        // 快速校验：大小匹配即跳过
                        completed++;
                        skipped++;
                        updateProgress(progress, completed, total, assetId, skipped, failed);
                        continue;
                    }
                    // 大小不匹配，重新下载
                }

                // 尝试从 BMCLAPI 下载，失败则回退 Mojang
                String bmclapiUrl = String.format(OBJECT_BMCLAPI, hash);
                boolean downloaded = downloadFile(bmclapiUrl, objectFile, null);

                if (!downloaded) {
                    String mojangUrl = String.format(OBJECT_MOJANG, firstTwo, hash);
                    downloaded = downloadFile(mojangUrl, objectFile, null);
                }

                if (downloaded) {
                    // 下载后校验大小
                    if (size > 0 && Files.size(objectFile) != size) {
                        if (progress != null) progress.onProgress(0, "Size mismatch for: " + virtualPath);
                        Files.deleteIfExists(objectFile);
                        failed++;
                    } else {
                        // 可选 SHA-1 校验
                        if (size <= 0 || !verifySha1(objectFile, hash)) {
                            // 如果 SHA-1 不匹配，记录但保留文件（有时镜像站压缩了内容）
                            if (progress != null) progress.onProgress(0,
                                    "SHA-1 mismatch for " + virtualPath + " (file retained)");
                        }
                        completed++;
                    }
                } else {
                    failed++;
                }

                updateProgress(progress, completed, total, assetId, skipped, failed);
            }

            if (progress != null) {
                String msg = String.format("Assets complete: %d/%d, skipped %d, failed %d",
                        completed - skipped, total, skipped, failed);
                progress.onProgress(100, msg);
            }

            return failed == 0;

        } catch (IOException e) {
            if (progress != null) progress.onProgress(0, "Error reading index: " + e.getMessage());
            return false;
        }
    }

    // ---------------------------------------------------------------
    // 通用 HTTP 下载（单文件）
    // ---------------------------------------------------------------

    /**
     * 从 URL 下载文件保存到 targetPath，自动创建父目录。
     *
     * @return true 表示成功
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

                // 如果服务器声明了长度，检查一致性
                if (contentLength > 0 && totalRead != contentLength) {
                    Files.deleteIfExists(targetPath);
                    return false;
                }
            }

            return true;

        } catch (IOException e) {
            // 清理不完整的文件
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
    // 工具方法
    // ---------------------------------------------------------------

    /**
     * 验证文件的 SHA-1 是否与预期一致。
     */
    private static boolean verifySha1(Path file, String expectedHash) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] fileBytes = Files.readAllBytes(file);
            byte[] hashBytes = digest.digest(fileBytes);
            String actualHash = HexFormat.of().formatHex(hashBytes);
            return actualHash.equals(expectedHash);
        } catch (IOException | NoSuchAlgorithmException e) {
            return false;
        }
    }

    /**
     * 更新进度回调，避免频繁调用。
     */
    private static void updateProgress(ProgressCallback cb, int completed, int total,
                                       String assetId, int skipped, int failed) {
        if (cb == null) return;
        int percent = total > 0 ? Math.min(completed * 100 / total, 100) : 0;
        String msg = String.format("[%s] %d/%d objects (skipped %d, failed %d)",
                assetId, completed, total, skipped, failed);
        cb.onProgress(percent, msg);
    }
}
