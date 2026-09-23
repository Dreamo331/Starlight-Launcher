package com.example.starlight.download;

import com.example.starlight.newui.AppConfig;
import com.example.starlight.util.DebugLog;
import com.example.starlight.util.VersionUtils;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minecraft 游戏资源补全器
 * <p>
 * 负责补全游戏运行所需的所有资源文件：
 * <ul>
 *   <li>资源文件 (assets) 的下载与 SHA-1 校验</li>
 *   <li>库文件 (libraries) 的下载与校验</li>
 *   <li>客户端 jar (versions/&lt;版本&gt;/&lt;版本&gt;.jar) 的下载与校验</li>
 *   <li>资产索引文件 (asset index) 的下载</li>
 *   <li>虚拟/旧版资源处理 (pre-1.6)</li>
 * </ul>
 * <p>
 * 特性：
 * <ul>
 *   <li>多线程并发下载，自动控制并发数</li>
 *   <li>多镜像源回退 (Mojang → BMCLAPI)</li>
 *   <li>SHA-1 完整性校验，确保文件正确</li>
 *   <li>详细进度回调，支持百分比和消息</li>
 *   <li>差异更新——仅下载缺失或损坏的文件</li>
 *   <li>支持取消（启动流程的「取消启动」按钮经此把取消传播到资源补全）</li>
 *   <li>支持 pre-1.6 旧版资源和 virtual 虚拟资源</li>
 *   <li>所有补全入口先经  解析
 *       {@code inheritsFrom} 继承链——整合包壳 JSON（仅 id/inheritsFrom/time/releaseTime/type）
 *       不再退化到 legacy 资产索引或空库列表</li>
 * </ul>
 */
public class GameResourceCompleter {
    private static final Logger log = LoggerFactory.getLogger(GameResourceCompleter.class);
    private static final Gson GSON = new Gson();
    private static final String OBJECTS_MOJANG = "https://resources.download.minecraft.net/%s/%s";
    private static final String OBJECTS_BMCLAPI = "https://bmclapi2.bangbang93.com/assets/%s";
    private static final String INDEX_MOJANG = "https://launchermeta.mojang.com/v1/packages/%s/%s.json";
    private static final String LIB_MOJANG = "https://libraries.minecraft.net/";
    private static final String LIB_BMCLAPI = "https://bmclapi2.bangbang93.com/libraries/";
    private static final int CONNECT_TIMEOUT_SEC = 10;
    private static final int READ_TIMEOUT_SEC = 30;
    private static final int BUFFER_SIZE = 8192;
    private static DownloadProvider downloadProvider = new BMCLAPIDownloadProvider("https://bmclapi2.bangbang93.com");
    private static final ConcurrentHashMap<String, ReentrantLock> COMPLETION_LOCKS = new ConcurrentHashMap<>();
    private static final AtomicLong TEMP_FILE_SEQ = new AtomicLong();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10L)).followRedirects(HttpClient.Redirect.NORMAL).executor(Executors.newCachedThreadPool()).build();

    public static void setDownloadProvider(DownloadProvider provider) {
        if (provider != null) {
            downloadProvider = provider;
        }
    }

    public static DownloadProvider getDownloadProvider() {
        return downloadProvider;
    }

    private static boolean withCompletionLock(String gameDir, String versionId, CompletionBody body) {
        String key;
        try {
            key = Paths.get(gameDir, new String[0]).toAbsolutePath().normalize().toString();
        }
        catch (Exception e) {
            key = gameDir;
        }
        ReentrantLock lock = COMPLETION_LOCKS.computeIfAbsent(key, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            log.info("已有资源补全在执行，本次等待其完成: versionId={}, gameDir={}", versionId, key);
            try {
                lock.lockInterruptibly();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("等待其它资源补全时被中断: versionId={}", versionId);
                return false;
            }
        }
        try {
            return body.run();
        }
        finally {
            lock.unlock();
        }
    }

    private static Path uniqueTempFile(Path targetPath) {
        long pid = ProcessHandle.current().pid();
        long seq = TEMP_FILE_SEQ.incrementAndGet();
        return targetPath.resolveSibling(String.valueOf(targetPath.getFileName()) + "." + pid + "-" + seq + ".tmp");
    }

    public static boolean completeAll(String gameDir, String versionId, ProgressCallback progress) {
        return completeAll(gameDir, versionId, progress, null);
    }

    public static boolean completeAll(String gameDir, String versionId, ProgressCallback progress, AtomicBoolean cancel) {
        return withCompletionLock(gameDir, versionId, () -> completeAllLocked(gameDir, versionId, progress, cancel));
    }

    private static boolean completeAllLocked(String gameDir, String versionId, ProgressCallback progress, AtomicBoolean cancel) {
        JsonObject root;
        report(progress, 0, "开始补全资源: " + versionId);
        log.info("Start completing resources: versionId={}, gameDir={}", versionId, gameDir);
        String versionJson = readVersionJson(gameDir, versionId);
        if (versionJson == null) {
            report(progress, 0, "未找到版本 JSON: " + versionId);
            return false;
        }
        // 整合包等版本是壳 JSON（只有 id/inheritsFrom/time/releaseTime/type）：
        // 先沿继承链合并为自包含视图，避免资产退化到 legacy 索引、库列表为空被当成「无需下载」
        try {
            root = VersionUtils.resolveInheritedJson(java.nio.file.Path.of(gameDir), versionId,
                    GSON.fromJson(versionJson, JsonObject.class));
        }
        catch (Exception e) {
            report(progress, 0, "版本 JSON 解析失败: " + e.getMessage());
            return false;
        }
        report(progress, 5, "开始补全资产文件...");
        boolean assetsOk = completeAssets(gameDir, root, wrapProgress(progress, 5, 50), cancel);
        if (!assetsOk) {
            log.warn("Asset completion has failed items, continuing with library files...");
        }
        if (isCancelled(cancel)) {
            report(progress, 100, "已取消资源补全: " + versionId);
            log.info("Resource completion cancelled after assets: versionId={}", versionId);
            return false;
        }
        report(progress, 52, "开始补全库文件...");
        boolean libsOk = completeLibraries(gameDir, root, wrapProgress(progress, 52, 90), true, true, cancel);
        if (isCancelled(cancel)) {
            report(progress, 100, "已取消资源补全: " + versionId);
            log.info("Resource completion cancelled after libraries: versionId={}", versionId);
            return false;
        }
        report(progress, 92, "正在校验客户端 JAR...");
        boolean jarOk = completeVersionJar(gameDir, versionId, root, wrapProgress(progress, 92, 100), cancel);
        if (isCancelled(cancel)) {
            report(progress, 100, "已取消资源补全: " + versionId);
            return false;
        }
        boolean allOk = assetsOk && libsOk && jarOk;
        if (allOk) {
            report(progress, 100, "资源补全完成: " + versionId);
            log.info("Resource completion finished: versionId={}", versionId);
        } else {
            report(progress, 100, "资源补全完成（部分失败）: " + versionId);
            log.warn("Resource completion finished (partial failures): versionId={}", versionId);
        }
        return allOk;
    }

    public static boolean completeAssetsOnly(String gameDir, String versionId, ProgressCallback progress) {
        return completeAssetsOnly(gameDir, versionId, progress, null);
    }

    public static boolean completeAssetsOnly(String gameDir, String versionId, ProgressCallback progress, AtomicBoolean cancel) {
        return withCompletionLock(gameDir, versionId, () -> completeAssetsOnlyLocked(gameDir, versionId, progress, cancel));
    }

    private static boolean completeAssetsOnlyLocked(String gameDir, String versionId, ProgressCallback progress, AtomicBoolean cancel) {
        JsonObject root;
        String versionJson = readVersionJson(gameDir, versionId);
        if (versionJson == null) {
            report(progress, 0, "未找到版本 JSON: " + versionId);
            return false;
        }
        // 整合包等版本是壳 JSON（只有 id/inheritsFrom/time/releaseTime/type）：
        // 先沿继承链合并为自包含视图，避免资产退化到 legacy 索引、库列表为空被当成「无需下载」
        try {
            root = VersionUtils.resolveInheritedJson(java.nio.file.Path.of(gameDir), versionId,
                    GSON.fromJson(versionJson, JsonObject.class));
        }
        catch (Exception e) {
            report(progress, 0, "版本 JSON 解析失败: " + e.getMessage());
            return false;
        }
        return completeAssets(gameDir, root, progress, cancel);
    }

    public static boolean completeLibrariesOnly(String gameDir, String versionId, ProgressCallback progress) {
        return completeLibrariesOnly(gameDir, versionId, progress, null);
    }

    public static boolean completeLibrariesOnly(String gameDir, String versionId, ProgressCallback progress, AtomicBoolean cancel) {
        return withCompletionLock(gameDir, versionId, () -> completeLibrariesOnlyLocked(gameDir, versionId, progress, cancel));
    }

    private static boolean completeLibrariesOnlyLocked(String gameDir, String versionId, ProgressCallback progress, AtomicBoolean cancel) {
        JsonObject root;
        String versionJson = readVersionJson(gameDir, versionId);
        if (versionJson == null) {
            report(progress, 0, "未找到版本 JSON: " + versionId);
            return false;
        }
        // 整合包等版本是壳 JSON（只有 id/inheritsFrom/time/releaseTime/type）：
        // 先沿继承链合并为自包含视图，避免资产退化到 legacy 索引、库列表为空被当成「无需下载」
        try {
            root = VersionUtils.resolveInheritedJson(java.nio.file.Path.of(gameDir), versionId,
                    GSON.fromJson(versionJson, JsonObject.class));
        }
        catch (Exception e) {
            report(progress, 0, "版本 JSON 解析失败: " + e.getMessage());
            return false;
        }
        return completeLibraries(gameDir, root, progress, true, true, cancel);
    }

    public static boolean completeNativeJarsOnly(String gameDir, String versionId, ProgressCallback progress) {
        return completeNativeJarsOnly(gameDir, versionId, progress, null);
    }

    public static boolean completeNativeJarsOnly(String gameDir, String versionId, ProgressCallback progress, AtomicBoolean cancel) {
        return withCompletionLock(gameDir, versionId, () -> completeNativeJarsOnlyLocked(gameDir, versionId, progress, cancel));
    }

    private static boolean completeNativeJarsOnlyLocked(String gameDir, String versionId, ProgressCallback progress, AtomicBoolean cancel) {
        JsonObject root;
        String versionJson = readVersionJson(gameDir, versionId);
        if (versionJson == null) {
            report(progress, 0, "未找到版本 JSON: " + versionId);
            return false;
        }
        // 整合包等版本是壳 JSON（只有 id/inheritsFrom/time/releaseTime/type）：
        // 先沿继承链合并为自包含视图，避免资产退化到 legacy 索引、库列表为空被当成「无需下载」
        try {
            root = VersionUtils.resolveInheritedJson(java.nio.file.Path.of(gameDir), versionId,
                    GSON.fromJson(versionJson, JsonObject.class));
        }
        catch (Exception e) {
            report(progress, 0, "版本 JSON 解析失败: " + e.getMessage());
            return false;
        }
        return completeLibraries(gameDir, root, progress, false, true, cancel);
    }

    public static boolean completeVersionJarOnly(String gameDir, String versionId, ProgressCallback progress) {
        return completeVersionJarOnly(gameDir, versionId, progress, null);
    }

    public static boolean completeVersionJarOnly(String gameDir, String versionId, ProgressCallback progress, AtomicBoolean cancel) {
        return withCompletionLock(gameDir, versionId, () -> completeVersionJarOnlyLocked(gameDir, versionId, progress, cancel));
    }

    private static boolean completeVersionJarOnlyLocked(String gameDir, String versionId, ProgressCallback progress, AtomicBoolean cancel) {
        JsonObject root;
        String versionJson = readVersionJson(gameDir, versionId);
        if (versionJson == null) {
            report(progress, 0, "未找到版本 JSON: " + versionId);
            return false;
        }
        // 整合包等版本是壳 JSON（只有 id/inheritsFrom/time/releaseTime/type）：
        // 先沿继承链合并为自包含视图，避免资产退化到 legacy 索引、库列表为空被当成「无需下载」
        try {
            root = VersionUtils.resolveInheritedJson(java.nio.file.Path.of(gameDir), versionId,
                    GSON.fromJson(versionJson, JsonObject.class));
        }
        catch (Exception e) {
            report(progress, 0, "版本 JSON 解析失败: " + e.getMessage());
            return false;
        }
        return completeVersionJar(gameDir, versionId, root, progress, cancel);
    }

    private static boolean completeVersionJar(String gameDir, String versionId, JsonObject root, ProgressCallback progress, AtomicBoolean cancel) {
        Path targetJar = Paths.get(gameDir, "versions", versionId, versionId + ".jar");
        VersionJarSource source = resolveClientJarSource(gameDir, versionId, root);
        if (source == null) {
            String parentId = getJsonString(root, "inheritsFrom");
            if (parentId != null && copyJarFromParent(gameDir, parentId, targetJar)) {
                log.info("Version jar copied from parent: {} -> {}", parentId, targetJar);
                report(progress, 100, "客户端 JAR 已从父版本复制");
                return true;
            }
            log.warn("Version JSON has no downloads.client, cannot complete client jar: versionId={}", versionId);
            report(progress, 100, "未找到客户端 JAR 的下载地址，已跳过");
            return true;
        }
        AssetTask task = new AssetTask(versionId + ".jar", source.sha1, source.size, targetJar, false, source.url);
        if (isFileValid(task)) {
            report(progress, 100, "客户端 JAR 已就绪");
            return true;
        }
        if (isCancelled(cancel)) {
            report(progress, 100, "已取消客户端 JAR 下载");
            return false;
        }
        report(progress, 10, "正在下载客户端 JAR: " + versionId + ".jar");
        if (downloadClientJar(task, source.versionId, cancel)) {
            log.info("Version jar completion finished: {}", targetJar);
            report(progress, 100, "客户端 JAR 补全完成");
            return true;
        }
        log.warn("Version jar download failed: {}", targetJar);
        report(progress, 100, (isCancelled(cancel) ? "已取消客户端 JAR 下载" : "客户端 JAR 下载失败: " + versionId + ".jar"));
        return false;
    }

    private static VersionJarSource resolveClientJarSource(String gameDir, String versionId, JsonObject startJson) {
        HashSet<String> visited = new HashSet<>();
        String currentId = versionId;
        JsonObject current = startJson;
        while (current != null && currentId != null && visited.add(currentId)) {
            JsonObject downloads = current.has("downloads") && current.get("downloads").isJsonObject() ? current.getAsJsonObject("downloads") : null;
            JsonObject client = downloads != null && downloads.has("client") && downloads.get("client").isJsonObject() ? downloads.getAsJsonObject("client") : null;
            String url = getJsonString(client, "url");
            if (url != null) {
                int size = client.has("size") ? client.get("size").getAsInt() : -1;
                return new VersionJarSource(currentId, url, getJsonString(client, "sha1"), size);
            }
            String parentId = getJsonString(current, "inheritsFrom");
            if (parentId == null) {
                return null;
            }
            String parentJson = readVersionJson(gameDir, parentId);
            if (parentJson == null) {
                return null;
            }
            try {
                current = GSON.fromJson(parentJson, JsonObject.class);
            }
            catch (Exception e) {
                return null;
            }
            currentId = parentId;
        }
        return null;
    }

    private static boolean downloadClientJar(AssetTask task, String mcVersion, AtomicBoolean cancel) {
        ArrayList<URI> candidates = new ArrayList<>(downloadProvider.getVersionClientCandidates(mcVersion));
        candidates.addAll(downloadProvider.injectURLWithCandidates(task.url));
        URI original = URI.create(task.url);
        if (!candidates.contains(original)) {
            candidates.add(original);
        }
        boolean downloaded = false;
        for (URI candidate : candidates) {
            if (downloaded || isCancelled(cancel)) break;
            downloaded = downloadFile(candidate.toString(), task.targetFile, task.hash, cancel);
        }
        if (isCancelled(cancel) && !downloaded) {
            return false;
        }
        if (!downloaded) {
            return false;
        }
        if (task.hash != null && !verifySha1(task.targetFile, task.hash)) {
            log.warn("SHA-1 verification failed: {} (expected: {})", task.targetFile, task.hash);
            try {
                Files.deleteIfExists(task.targetFile);
            }
            catch (IOException iOException) {
                // empty catch block
            }
            return false;
        }
        return true;
    }

    private static boolean copyJarFromParent(String gameDir, String parentId, Path targetJar) {
        Path parentJar = Paths.get(gameDir, "versions", parentId, parentId + ".jar");
        if (!Files.isRegularFile(parentJar) || Files.exists(targetJar)) {
            return false;
        }
        try {
            Files.createDirectories(targetJar.getParent());
            Files.copy(parentJar, targetJar, StandardCopyOption.REPLACE_EXISTING);
            return true;
        }
        catch (IOException e) {
            log.warn("Copy parent jar failed: {} -> {} ({})", parentJar, targetJar, e.getMessage());
            return false;
        }
    }

    private static boolean completeAssets(String gameDir, JsonObject root, ProgressCallback progress, AtomicBoolean cancel) {
        JsonObject assetIndex = root.getAsJsonObject("assetIndex");
        if (assetIndex == null) {
            report(progress, 0, "版本 JSON 无 assetIndex 字段，使用 legacy 索引");
            return downloadAndProcessLegacyAssets(gameDir, progress, cancel);
        }
        String assetId = getJsonString(assetIndex, "id");
        String sha1 = getJsonString(assetIndex, "sha1");
        String indexUrl = getJsonString(assetIndex, "url");
        if (assetId == null) {
            report(progress, 0, "资产索引信息不完整");
            return false;
        }
        report(progress, 5, "下载资产索引: " + assetId);
        Path indexFile = downloadAssetIndex(gameDir, assetId, sha1, indexUrl, cancel);
        if (indexFile == null) {
            report(progress, 5, (isCancelled(cancel) ? "已取消资产补全" : "资产索引下载失败: " + assetId));
            return false;
        }
        report(progress, 10, "开始校验资产文件...");
        return downloadAssetObjects(gameDir, indexFile, assetId, wrapProgress(progress, 10, 100), cancel);
    }

    private static Path downloadAssetIndex(String gameDir, String assetId, String sha1, String indexUrl, AtomicBoolean cancel) {
        Path indexesDir = Paths.get(gameDir, "assets", "indexes");
        Path targetFile = indexesDir.resolve(assetId + ".json");
        try {
            Files.createDirectories(indexesDir);
            if (Files.exists(targetFile) && Files.size(targetFile) > 0L) {
                if (sha1 != null && verifySha1(targetFile, sha1)) {
                    log.debug("Asset index exists and is valid: {}", assetId);
                    return targetFile;
                }
                log.debug("Asset index needs update: {}", assetId);
            }
            boolean downloaded = false;
            List<URI> indexCandidates = downloadProvider.getAssetIndexCandidates(assetId, sha1);
            for (URI candidate : indexCandidates) {
                if (downloaded || isCancelled(cancel)) break;
                downloaded = downloadFile(candidate.toString(), targetFile, null, cancel);
            }
            if (!downloaded && log.isDebugEnabled()) {
                log.debug("Mirror index download failed, trying Mojang official source: {}", assetId);
            }
            if (!(downloaded || isCancelled(cancel) || indexUrl == null || indexUrl.isBlank())) {
                downloaded = downloadFile(indexUrl, targetFile, null, cancel);
            }
            if (!downloaded && !isCancelled(cancel) && sha1 != null) {
                String mojangUrl = String.format(INDEX_MOJANG, sha1, assetId);
                downloaded = downloadFile(mojangUrl, targetFile, null, cancel);
            }
            if (!downloaded) {
                log.error("Asset index download failed: assetId={}", assetId);
                return null;
            }
            if (sha1 != null && !verifySha1(targetFile, sha1)) {
                log.warn("Asset index SHA-1 verification failed, keeping downloaded file: {}", assetId);
            }
            log.info("Asset index downloaded: {}", assetId);
            return targetFile;
        }
        catch (IOException e) {
            log.error("Asset index download error: assetId={}", assetId, e);
            return null;
        }
    }

    private static boolean downloadAssetObjects(String gameDir, Path indexFile, String assetId, ProgressCallback progress, AtomicBoolean cancel) {
        try {
            Set<Map.Entry<String, JsonElement>> entries;
            int total;
            JsonObject objects;
            String json = Files.readString(indexFile);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            boolean isVirtual = false;
            if (root.has("objects")) {
                objects = root.getAsJsonObject("objects");
            } else if (root.has("virtual")) {
                objects = root.getAsJsonObject("virtual");
                isVirtual = true;
            } else {
                report(progress, 0, "索引文件无 objects/virtual 字段: " + assetId);
                return false;
            }
            if (root.has("virtual") && root.get("virtual").isJsonPrimitive() && root.get("virtual").getAsBoolean()) {
                isVirtual = true;
                if (root.has("objects")) {
                    objects = root.getAsJsonObject("objects");
                }
            }
            if ((total = (entries = objects.entrySet()).size()) == 0) {
                report(progress, 100, "无需下载资产文件: " + assetId);
                return true;
            }
            Path objectsDir = Paths.get(gameDir, "assets", "objects");
            LinkedHashMap<Path, AssetTask> tasksByTarget = new LinkedHashMap<>(total);
            for (Map.Entry<String, JsonElement> entry : entries) {
                int size;
                String virtualPath = entry.getKey();
                JsonObject obj = entry.getValue().getAsJsonObject();
                String hash = getJsonString(obj, "hash");
                int n = size = obj.has("size") ? obj.get("size").getAsInt() : -1;
                if (hash == null || hash.length() < 2) {
                    log.warn("Skipping invalid asset entry: {} (hash={})", virtualPath, hash);
                    continue;
                }
                String firstTwo = hash.substring(0, 2);
                Path objectFile = objectsDir.resolve(firstTwo).resolve(hash);
                if (tasksByTarget.containsKey(objectFile)) continue;
                tasksByTarget.put(objectFile, new AssetTask(virtualPath, hash, size, objectFile, isVirtual));
            }
            ArrayList<AssetTask> tasks = new ArrayList<>(tasksByTarget.values());
            if (tasks.size() < total) {
                log.info("资产对象去重: 索引 {} 共 {} 条，其中 {} 条与其它条目指向同一文件，实际下载 {} 个对象", assetId, total, total - tasks.size(), tasks.size());
            }
            int uniqueTotal = tasks.size();
            int downloaded = downloadConcurrently(tasks, objectsDir, progress, uniqueTotal, "资产文件", OBJECTS_MOJANG, OBJECTS_BMCLAPI, cancel);
            if (!isCancelled(cancel) && isVirtual && tasks.stream().anyMatch(t -> t.downloaded)) {
                handleVirtualResources(gameDir, indexFile);
            }
            boolean allOk = downloaded == tasks.size();
            log.info("Asset file completion finished: {}/{}, assetId={}", downloaded, tasks.size(), assetId);
            return allOk;
        }
        catch (IOException e) {
            log.error("Asset object download error: assetId={}", assetId, e);
            report(progress, 0, "资产对象下载异常: " + e.getMessage());
            return false;
        }
    }

    private static boolean downloadAndProcessLegacyAssets(String gameDir, ProgressCallback progress, AtomicBoolean cancel) {
        report(progress, 10, "使用 legacy 资产索引...");
        Path indexFile = downloadAssetIndex(gameDir, "legacy", null, null, cancel);
        if (indexFile == null && !isCancelled(cancel)) {
            indexFile = downloadAssetIndex(gameDir, "pre-1.6", null, null, cancel);
        }
        if (indexFile == null) {
            report(progress, 0, isCancelled(cancel) ? "已取消资产补全" : "无法获取旧版资产索引");
            return false;
        }
        return downloadAssetObjects(gameDir, indexFile, "legacy", wrapProgress(progress, 20, 100), cancel);
    }

    private static void handleVirtualResources(String gameDir, Path indexFile) {
        Path virtualDir = Paths.get(gameDir, "assets", "virtual", "legacy");
        try {
            JsonObject objects;
            String json = Files.readString(indexFile);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            JsonObject jsonObject = objects = root.has("objects") ? root.getAsJsonObject("objects") : null;
            if (objects == null) {
                return;
            }
            Files.createDirectories(virtualDir);
            int copied = 0;
            for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
                String virtualPath = entry.getKey();
                JsonObject obj = entry.getValue().getAsJsonObject();
                String hash = getJsonString(obj, "hash");
                if (hash == null) continue;
                Path source = Paths.get(gameDir, "assets", "objects", hash.substring(0, 2), hash);
                Path target = virtualDir.resolve(virtualPath);
                if (!Files.exists(source) || Files.exists(target)) continue;
                Files.createDirectories(target.getParent());
                Files.copy(source, target);
                ++copied;
            }
            if (copied > 0) {
                log.info("Virtual resource copy finished: {} files", copied);
            }
        }
        catch (IOException e) {
            log.warn("Virtual resource copy error", e);
        }
    }

    private static boolean completeLibraries(String gameDir, JsonObject root, ProgressCallback progress, boolean includeArtifacts, boolean includeClassifiers, AtomicBoolean cancel) {
        JsonArray libraries = root.getAsJsonArray("libraries");
        if (libraries == null || libraries.size() == 0) {
            report(progress, 100, "无需下载库文件");
            return true;
        }
        String osName = normalizeOs(System.getProperty("os.name"));
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        Path libDir = Paths.get(gameDir, "libraries");
        ArrayList<AssetTask> tasks = new ArrayList<>();
        for (JsonElement elem : libraries) {
            String nativeClassifier;
            JsonObject classifiers;
            JsonObject artifact;
            JsonObject downloads;
            JsonObject lib = elem.getAsJsonObject();
            if (!isLibraryAllowed(lib, osName, arch) || (downloads = lib.getAsJsonObject("downloads")) == null) continue;
            if (includeArtifacts && (artifact = downloads.getAsJsonObject("artifact")) != null) {
                addLibraryTask(tasks, libDir, artifact, null);
            }
            if (!includeClassifiers || (classifiers = downloads.getAsJsonObject("classifiers")) == null || (nativeClassifier = getNativeClassifier(osName)) == null || !classifiers.has(nativeClassifier)) continue;
            JsonObject nativeArtifact = classifiers.getAsJsonObject(nativeClassifier);
            addLibraryTask(tasks, libDir, nativeArtifact, nativeClassifier);
        }
        if (tasks.isEmpty()) {
            report(progress, 100, "所有库文件已就绪");
            return true;
        }
        report(progress, 5, "开始下载库文件: " + tasks.size() + " 个");
        int downloaded = downloadConcurrently(tasks, libDir, wrapProgress(progress, 5, 100), tasks.size(), "库文件", LIB_MOJANG, LIB_BMCLAPI, cancel);
        boolean allOk = downloaded == tasks.size();
        log.info("Library file completion finished: {}/{}", downloaded, tasks.size());
        return allOk;
    }

    private static void addLibraryTask(List<AssetTask> tasks, Path libDir, JsonObject artifact, String classifier) {
        int size;
        String path = getJsonString(artifact, "path");
        String url = getJsonString(artifact, "url");
        String sha1 = getJsonString(artifact, "sha1");
        int n = size = artifact.has("size") ? artifact.get("size").getAsInt() : -1;
        if (path == null) {
            return;
        }
        Path targetFile = libDir.resolve(path);
        String taskKey = classifier != null ? path + "!" + classifier : path;
        tasks.add(new AssetTask(taskKey, sha1, size, targetFile, false, url));
    }

    private static int downloadConcurrently(List<AssetTask> tasks, Path baseDir, ProgressCallback progress, int totalEstimate, String taskLabel, String primaryUrl, String fallbackUrl, AtomicBoolean cancel) {
        if (tasks.isEmpty()) {
            return 0;
        }
        int total = totalEstimate;
        ArrayList<AssetTask> pending = new ArrayList<>();
        ArrayList<AssetTask> alreadyDone = new ArrayList<>();
        if (VerifySettings.isParallelVerifyEnabled() && tasks.size() > 1) {
            verifyInParallel(tasks, alreadyDone, pending, total, taskLabel, progress, cancel);
        } else {
            verifySerially(tasks, alreadyDone, pending, total, taskLabel, progress, cancel);
        }
        int preExisting = alreadyDone.size();
        int needDownload = pending.size();
        log.info("{}: existing {}/{}, need to download {}", taskLabel, preExisting, total, needDownload);
        if (isCancelled(cancel)) {
            report(progress, 100, taskLabel + " 已取消");
            return preExisting;
        }
        if (needDownload == 0) {
            report(progress, 100, taskLabel + " 全部已存在");
            return tasks.size();
        }
        int threadCount = Math.min(needDownload, DownloadSettings.getDownloadThreads());
        if (threadCount < 1) {
            threadCount = 1;
        }
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger completed = new AtomicInteger(preExisting);
        AtomicInteger failedCount = new AtomicInteger(0);
        try {
            ArrayList<CompletableFuture<Void>> futures = new ArrayList<>(needDownload);
            for (AssetTask task : pending) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    if (isCancelled(cancel)) {
                        return;
                    }
                    boolean ok = downloadSingleAsset(task, primaryUrl, fallbackUrl, cancel);
                    if (ok) {
                        task.downloaded = true;
                        int done = completed.incrementAndGet();
                        updateBatchProgress(progress, done, total, taskLabel, failedCount.get());
                    } else if (!isCancelled(cancel)) {
                        int fail = failedCount.incrementAndGet();
                        log.warn("{} download failed: {}", taskLabel, task.virtualPath);
                        updateBatchProgress(progress, completed.get(), total, taskLabel, fail);
                    }
                }, executor);
                futures.add(future);
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30L, TimeUnit.MINUTES);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("{} download interrupted", taskLabel);
        }
        catch (Exception e) {
            log.error("{} download error", taskLabel, e);
        }
        finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5L, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            }
            catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        int totalDownloaded = completed.get();
        int totalFailed = failedCount.get();
        if (isCancelled(cancel)) {
            report(progress, 100, String.format("%s: 已取消，%d/%d 已就绪", taskLabel, totalDownloaded, total));
            log.info("{} cancelled: {}/{} ready", taskLabel, totalDownloaded, total);
            return totalDownloaded;
        }
        report(progress, 100, String.format("%s: %d/%d 完成, %d 失败", taskLabel, totalDownloaded, total, totalFailed));
        return totalDownloaded;
    }

    private static int downloadConcurrently(List<AssetTask> tasks, Path baseDir, ProgressCallback progress, int totalEstimate, String taskLabel, AtomicBoolean cancel) {
        return downloadConcurrently(tasks, baseDir, progress, totalEstimate, taskLabel, OBJECTS_MOJANG, OBJECTS_BMCLAPI, cancel);
    }

    private static void verifyInParallel(List<AssetTask> tasks, List<AssetTask> alreadyDone, List<AssetTask> pending, int total, String taskLabel, ProgressCallback progress, AtomicBoolean cancel) {
        int threads = Math.min(tasks.size(), Math.max(1, DownloadSettings.getDownloadThreads()));
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "verify-worker");
            t.setDaemon(true);
            return t;
        });
        boolean[] valid = new boolean[tasks.size()];
        AtomicInteger checked = new AtomicInteger(0);
        AtomicInteger lastReportedPct = new AtomicInteger(-1);
        try {
            ArrayList<CompletableFuture<Void>> futures = new ArrayList<>(tasks.size());
            int i = 0;
            while (i < tasks.size()) {
                int idx = i++;
                futures.add(CompletableFuture.runAsync(() -> {
                    int pct;
                    valid[idx] = !isCancelled(cancel) && isFileValid(tasks.get(idx));
                    int done = checked.incrementAndGet();
                    int n = pct = total > 0 ? done * 100 / total : 100;
                    if (pct != lastReportedPct.getAndSet(pct)) {
                        report(progress, pct, taskLabel + " 校验中: " + done + "/" + total);
                    }
                }, pool));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30L, TimeUnit.MINUTES);
        }
        catch (Exception e) {
            log.error("{} parallel verify failed, fallback to serial", taskLabel, e);
            verifySerially(tasks, alreadyDone, pending, total, taskLabel, progress, cancel);
            return;
        }
        finally {
            pool.shutdownNow();
        }
        for (int i = 0; i < valid.length; ++i) {
            if (valid[i]) {
                alreadyDone.add(tasks.get(i));
                continue;
            }
            pending.add(tasks.get(i));
        }
    }

    private static void verifySerially(List<AssetTask> tasks, List<AssetTask> alreadyDone, List<AssetTask> pending, int total, String taskLabel, ProgressCallback progress, AtomicBoolean cancel) {
        int checked = 0;
        int lastReportedPct = -1;
        for (AssetTask task : tasks) {
            int pct;
            if (isCancelled(cancel)) {
                pending.add(task);
            } else if (isFileValid(task)) {
                alreadyDone.add(task);
            } else {
                pending.add(task);
            }
            if ((pct = total > 0 ? ++checked * 100 / total : 100) == lastReportedPct) continue;
            lastReportedPct = pct;
            report(progress, pct, taskLabel + " 校验中: " + checked + "/" + total);
        }
    }

    private static boolean isFileValid(AssetTask task) {
        Path file = task.targetFile;
        if (!Files.exists(file)) {
            return false;
        }
        try {
            if (task.size > 0 && Files.size(file) != task.size) {
                return false;
            }
            if (VerifySettings.isFastVerifyEnabled()) {
                if (task.size > 0) {
                    return true;
                }
                if (task.hash == null || task.hash.isBlank()) {
                    return true;
                }
            }
            if (task.hash != null && !task.hash.isBlank()) {
                return verifySha1(file, task.hash);
            }
            return task.size > 0;
        }
        catch (IOException e) {
            return false;
        }
    }

    private static boolean downloadSingleAsset(AssetTask task, String primaryUrlTemplate, String fallbackUrlTemplate, AtomicBoolean cancel) {
        Path targetFile = task.targetFile;
        try {
            if (isCancelled(cancel)) {
                return false;
            }
            Files.createDirectories(targetFile.getParent());
            String url = task.url;
            ArrayList<URI> candidates = new ArrayList<>();
            if (url != null) {
                candidates.addAll(downloadProvider.injectURLWithCandidates(url));
                if (!candidates.contains(URI.create(url))) {
                    candidates.add(URI.create(url));
                }
            } else if (primaryUrlTemplate != null && task.hash != null && task.hash.length() >= 2) {
                URI bmclForm;
                String firstTwo = task.hash.substring(0, 2);
                candidates.addAll(downloadProvider.getAssetObjectCandidates(task.hash));
                URI mojangForm = URI.create(String.format(primaryUrlTemplate, firstTwo, task.hash));
                if (!candidates.contains(mojangForm)) {
                    candidates.add(mojangForm);
                }
                if (fallbackUrlTemplate != null && !candidates.contains(bmclForm = URI.create(String.format(fallbackUrlTemplate, task.hash)))) {
                    candidates.add(bmclForm);
                }
            }
            boolean downloaded = false;
            for (URI candidate : candidates) {
                if (downloaded || isCancelled(cancel)) break;
                downloaded = downloadFile(candidate.toString(), targetFile, task.hash, cancel);
            }
            if (downloaded) {
                if (task.hash != null && !verifySha1(targetFile, task.hash)) {
                    log.warn("SHA-1 verification failed: {} (expected: {})", task.virtualPath, task.hash);
                    try {
                        Files.deleteIfExists(targetFile);
                    }
                    catch (IOException iOException) {
                        // empty catch block
                    }
                    return false;
                }
                return true;
            }
            return false;
        }
        catch (IOException e) {
            log.error("Download failed: {} - {}", task.virtualPath, e.getMessage());
            return false;
        }
    }

    private static boolean isLibraryAllowed(JsonObject lib, String osName, String arch) {
        if (!lib.has("rules")) {
            return true;
        }
        JsonArray rules = lib.getAsJsonArray("rules");
        boolean allowed = false;
        for (JsonElement elem : rules) {
            JsonObject rule = elem.getAsJsonObject();
            String action = getJsonString(rule, "action");
            JsonObject os = rule.getAsJsonObject("os");
            boolean matches = true;
            if (os != null) {
                String requiredArch;
                String requiredOs = getJsonString(os, "name");
                if (requiredOs != null) {
                    matches = requiredOs.equalsIgnoreCase(osName);
                }
                if (matches && os.has("arch") && (requiredArch = getJsonString(os, "arch")) != null) {
                    matches = arch.contains(requiredArch.toLowerCase(Locale.ROOT));
                }
            }
            if ("allow".equals(action)) {
                if (!matches) continue;
                allowed = true;
                continue;
            }
            if (!"disallow".equals(action) || !matches) continue;
            allowed = false;
        }
        return allowed;
    }

    private static String normalizeOs(String osName) {
        String lower = osName.toLowerCase(Locale.ROOT);
        if (lower.contains("win")) {
            return "windows";
        }
        if (lower.contains("mac")) {
            return "osx";
        }
        if (lower.contains("linux")) {
            return "linux";
        }
        if (lower.contains("sunos") || lower.contains("solaris")) {
            return "linux";
        }
        return "unknown";
    }

    private static String getNativeClassifier(String osName) {
        return switch (osName) {
            case "windows" -> "natives-windows";
            case "osx" -> "natives-osx";
            case "linux" -> "natives-linux";
            default -> null;
        };
    }

    static boolean downloadFile(String urlStr, Path targetPath, String expectedSha1) {
        return downloadFile(urlStr, targetPath, expectedSha1, null);
    }

    static boolean downloadFile(String urlStr, Path targetPath, String expectedSha1, AtomicBoolean cancel) {
        HttpRequest request;
        if (isCancelled(cancel)) {
            return false;
        }
        try {
            request = HttpRequest.newBuilder().uri(URI.create(urlStr)).timeout(Duration.ofSeconds(30L)).header("User-Agent", AppConfig.USER_AGENT).GET().build();
        }
        catch (IllegalArgumentException e) {
            log.warn("Invalid URL: {}", urlStr);
            return false;
        }
        Path tempFile = null;
        try {
            boolean downloaded;
            HttpResponse<InputStream> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
            DebugLog.http(true, "GET", urlStr, 0, 0L, null, -1L);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                DebugLog.http(false, null, urlStr, response.statusCode(), 0L, null, -1L);
                log.debug("HTTP {}: {}", response.statusCode(), urlStr);
                return false;
            }
            tempFile = uniqueTempFile(targetPath);
            try {
                Files.createDirectories(targetPath.getParent());
            }
            catch (IOException e) {
                log.error("Cannot create directory: {}", targetPath.getParent());
                return false;
            }
            boolean cancelledMidway = false;
            try (InputStream in = response.body();
                 OutputStream out = Files.newOutputStream(tempFile);){
                int read;
                byte[] buffer = new byte[8192];
                long totalRead = 0L;
                long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                while ((read = in.read(buffer)) != -1) {
                    if (isCancelled(cancel)) {
                        cancelledMidway = true;
                        break;
                    }
                    out.write(buffer, 0, read);
                    totalRead += read;
                }
                DebugLog.http(false, null, urlStr, response.statusCode(), totalRead, cancelledMidway ? "[已取消]" : "[二进制文件]", -1L);
                if (cancelledMidway) {
                    downloaded = false;
                } else if (contentLength > 0L && totalRead != contentLength) {
                    log.warn("Incomplete download: {} (expected {} bytes, actual {} bytes)", urlStr, contentLength, totalRead);
                    downloaded = false;
                } else {
                    downloaded = true;
                }
            }
            if (downloaded) {
                if (expectedSha1 != null && !expectedSha1.isBlank() && !verifySha1(tempFile, expectedSha1)) {
                    if (Files.exists(targetPath) && verifySha1(targetPath, expectedSha1)) {
                        log.debug("临时文件校验未通过但目标文件已就绪，直接采用: {}", targetPath);
                        Files.deleteIfExists(tempFile);
                        return true;
                    }
                    log.warn("SHA-1 verification failed: {} (expected: {})", urlStr, expectedSha1);
                    Files.deleteIfExists(tempFile);
                    return false;
                }
                Files.move(tempFile, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return true;
            }
            Files.deleteIfExists(tempFile);
            return false;
        }
        catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                }
                catch (IOException iOException) {
                    // empty catch block
                }
            }
            return false;
        }
    }

    public static String sha1(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            if (VerifySettings.isStreamingSha1Enabled()) {
                byte[] buffer = new byte[65536];
                try (InputStream in = Files.newInputStream(file);){
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        digest.update(buffer, 0, read);
                    }
                }
            } else {
                byte[] fileBytes = Files.readAllBytes(file);
                digest.update(fileBytes);
            }
            return bytesToHex(digest.digest());
        }
        catch (NoSuchFileException e) {
            log.debug("SHA-1 skipped, file missing: {}", file);
            return null;
        }
        catch (IOException | NoSuchAlgorithmException e) {
            log.error("SHA-1 calculation failed: {}", file, e);
            return null;
        }
    }

    static boolean verifySha1(Path file, String expectedHash) {
        String actual = sha1(file);
        if (actual == null) {
            return false;
        }
        boolean match = actual.equalsIgnoreCase(expectedHash);
        if (!match && log.isTraceEnabled()) {
            log.trace("SHA-1 mismatch: expected={}, actual={}", expectedHash, actual);
        }
        return match;
    }

    private static String readVersionJson(String gameDir, String versionId) {
        Path versionJsonPath = Paths.get(gameDir, "versions", versionId, versionId + ".json");
        try {
            if (Files.exists(versionJsonPath)) {
                return Files.readString(versionJsonPath);
            }
            log.warn("Version JSON does not exist: {}", versionJsonPath);
            return null;
        }
        catch (IOException e) {
            log.error("Failed to read version JSON: {}", versionJsonPath, e);
            return null;
        }
    }

    private static String getJsonString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) {
            return null;
        }
        JsonElement element = obj.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        String value = element.getAsString();
        return value.isEmpty() ? null : value;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static void report(ProgressCallback callback, int percent, String message) {
        if (callback != null) {
            callback.onProgress(Math.min(percent, 100), message);
        }
    }

    private static boolean isCancelled(AtomicBoolean cancel) {
        return cancel != null && cancel.get();
    }

    private static void updateBatchProgress(ProgressCallback progress, int completed, int total, String label, int failed) {
        if (progress == null) {
            return;
        }
        int percent = total > 0 ? Math.min(completed * 100 / total, 100) : 0;
        String msg = String.format("%s: %d/%d (失败: %d)", label, completed, total, failed);
        progress.onProgress(percent, msg);
    }

    private static ProgressCallback wrapProgress(ProgressCallback parent, int rangeStart, int rangeEnd) {
        if (parent == null) {
            return null;
        }
        return (percent, message) -> {
            int mapped = rangeStart + percent * (rangeEnd - rangeStart) / 100;
            parent.onProgress(Math.min(mapped, 100), message);
        };
    }

    private static interface CompletionBody {
        public boolean run();
    }

    public static interface ProgressCallback {
        public void onProgress(int var1, String var2);
    }

    private static class VersionJarSource {
        final String versionId;
        final String url;
        final String sha1;
        final int size;

        VersionJarSource(String versionId, String url, String sha1, int size) {
            this.versionId = versionId;
            this.url = url;
            this.sha1 = sha1;
            this.size = size;
        }
    }

    private static class AssetTask {
        final String virtualPath;
        final String hash;
        final int size;
        final Path targetFile;
        final boolean isVirtual;
        final String url;
        volatile boolean downloaded;

        AssetTask(String virtualPath, String hash, int size, Path targetFile, boolean isVirtual) {
            this(virtualPath, hash, size, targetFile, isVirtual, null);
        }

        AssetTask(String virtualPath, String hash, int size, Path targetFile, boolean isVirtual, String url) {
            this.virtualPath = virtualPath;
            this.hash = hash;
            this.size = size;
            this.targetFile = targetFile;
            this.isVirtual = isVirtual;
            this.url = url;
            this.downloaded = false;
        }
    }
}

