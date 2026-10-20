package com.example.starlight.download.task;

import com.example.starlight.download.DownloadProviderHolder;
import com.example.starlight.download.DownloadSettings;
import com.example.starlight.download.VerifySettings;
import com.example.starlight.download.mod.FileVerifier;
import com.example.starlight.download.mod.ChunkOutcome;
import com.example.starlight.download.mod.ChunkedDownloader;
import com.example.starlight.download.mod.RetryQueue;
import com.example.starlight.download.mod.SourceHealth;
import com.example.starlight.download.mod.SourceRanking;
import com.example.starlight.download.mod.VerifyStateCache;
import com.example.starlight.download.source.DownloadProvider;
import com.example.starlight.newui.AppConfig;
import com.example.starlight.util.DebugLog;
import com.example.starlight.util.VersionUtils;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
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
    /**
     * 资产对象下载模板。
     *
     * <p><b>两个模板都必须带「前两位目录」</b>：BMCLAPI 的 assets 端点完全镜像官方目录结构。
     * 实测对照（hash=2d888ac1…）：
     * <pre>
     *   官方   https://resources.download.minecraft.net/2d/2d888ac…  → 200 (605451 B)
     *   BMCLAPI https://bmclapi2.bangbang93.com/assets/2d/2d888ac… → 200 (605451 B)
     *   BMCLAPI https://bmclapi2.bangbang93.com/assets/2d888ac…    → 404（旧模板，漏了前两位）
     * </pre>
     * 旧模板漏了前两位目录，导致镜像端每个资产都失败一次，白付一次往返才回落官方源。
     */
    private static final String OBJECTS_MOJANG = "https://resources.download.minecraft.net/%s/%s";
    private static final String OBJECTS_BMCLAPI = "https://bmclapi2.bangbang93.com/assets/%s/%s";
    private static final String INDEX_MOJANG = "https://launchermeta.mojang.com/v1/packages/%s/%s.json";
    private static final String LIB_MOJANG = "https://libraries.minecraft.net/";
    private static final String LIB_BMCLAPI = "https://bmclapi2.bangbang93.com/libraries/";
    private static final int CONNECT_TIMEOUT_SEC = 10;
    /**
     * 重定向最大跳数（手动跟随，因为要逐跳统计并复用 Range 续传）。
     */
    private static final int MAX_REDIRECT_HOPS = 5;

    /**
     * 「最后候选源」的读超时放宽倍数。
     *
     * <p>依据 XMCL 的设计（`download.ts`）：<i>a slow-but-working connection is never a hard
     * failure on the last source — commit to finishing</i>。最后一条能用的路即使慢，也不该被
     * 反复掐断，否则会陷入"每次都差一点"的死循环。放宽而不是取消，是为了防止它彻底卡死。
     */
    private static final int LAST_RESORT_TIMEOUT_FACTOR = 2;
    /** 读缓冲：64KB（与 download.mod 的 DownloadPiece.BUFFER_SIZE 对齐，减少小文件系统调用） */
    private static final int BUFFER_SIZE = 64 * 1024;

    // 2026-09-30 下载等待池（批内延迟重试）参数 //
    /** 最大重试轮次（不含主波次；下载等待池方案 §2.2） */
    private static final int MAX_RETRY_ROUNDS = 2;

    /** 等待池容量上限：超出部分直接计失败，防止内存膨胀（方案 §4-6） */
    private static final int RETRY_QUEUE_CAPACITY = 2000;

    /** 整批（含全部重试轮）总时限：30 分钟（沿用原 allOf().get(30, MINUTES) 语义） */
    private static final long BATCH_TIMEOUT_MS = 30L * 60 * 1000L;

    /** 重试轮最小执行时间：批级剩余时间不足（退避 + 此值）则跳过本轮重试（方案 §2.2） */
    private static final long MIN_RETRY_EXECUTE_MS = 5_000L;

    /**
     * 批内慢源快速败退阈值（2026-09-30 增强 A）：某源本批 slowCount ≥ 此值且不是最后可用候选时，
     * 后续文件直接跳过它（不再每个文件都白付一个首试停滞判定）。
     * 前 3 次失败用于探测，之后的文件从其它候选源下载；重试轮里它仍可能被再次尝试（排序已靠后）。
     */
    private static final int SLOW_SOURCE_SKIP_THRESHOLD = 3;

    // 2026-09-30 A/B 双池（大文件分块并发）参数 //
    /**
     * 大文件阈值：size ≥ 1MB 的资产（sounds/music 的 .ogg、unifont.zip 等）进入 B 池，
     * 用 {@link ChunkedDownloader} 分块并发下载；其余进 A 池按原单连接流程下载。
     */
    private static final int BIG_FILE_THRESHOLD = 1024 * 1024;

    /** B 池（大文件）文件级并发线程数：独立于 A 池，避免大文件占满小文件线程 */
    private static final int BIG_POOL_THREADS = 4;
    // JUDGE-5：原私有静态 downloadProvider 字段收敛到 download 根目录的 DownloadProviderHolder，
    // 默认 BMCLAPI 的创建逻辑移入 Holder 的懒加载，本类不再直接持有/实例化 DownloadProvider。
    private static final ConcurrentHashMap<String, ReentrantLock> COMPLETION_LOCKS = new ConcurrentHashMap<>();
    private static final AtomicLong TEMP_FILE_SEQ = new AtomicLong();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10L)).followRedirects(HttpClient.Redirect.NORMAL).executor(Executors.newCachedThreadPool()).build();

    public static void setDownloadProvider(DownloadProvider provider) {
        // 委托 Holder 写入；保留原有的非空校验语义
        if (provider != null) {
            DownloadProviderHolder.set(provider);
        }
    }

    public static DownloadProvider getDownloadProvider() {
        // 委托 Holder 读取；Holder.get() 懒加载默认 BMCLAPI，不会返回 null
        return DownloadProviderHolder.get();
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
        loadVerifyCache(gameDir);
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
        loadVerifyCache(gameDir);
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
        loadVerifyCache(gameDir);
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
        loadVerifyCache(gameDir);
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
        loadVerifyCache(gameDir);
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
        ArrayList<URI> candidates = new ArrayList<>(DownloadProviderHolder.get().getVersionClientCandidates(mcVersion));
        candidates.addAll(DownloadProviderHolder.get().injectURLWithCandidates(task.url));
        URI original = URI.create(task.url);
        if (!candidates.contains(original)) {
            candidates.add(original);
        }
        boolean downloaded = false;
        for (int i = 0; i < candidates.size(); i++) {
            if (downloaded || isCancelled(cancel)) break;
            URI candidate = candidates.get(i);
            if (SourceHealth.getInstance().isBlocked(candidate.toString())) {
                continue;
            }
            // 与资产路径一致：最后一条可用候选放宽停滞判定
            DownloadOutcome outcome = downloadFile(candidate.toString(), task.targetFile, task.hash, cancel,
                    isLastAvailableCandidate(candidates, i));
            if (outcome == DownloadOutcome.OK) {
                downloaded = true;
            } else if (outcome == DownloadOutcome.CANCELLED) {
                break;
            }
            // RETRY / FAIL：客户端 JAR 是批外单文件下载，保持原有「继续下一候选」语义（不做等待池）
        }
        if (isCancelled(cancel) && !downloaded) {
            return false;
        }
        if (!downloaded) {
            return false;
        }
        if (task.hash != null && !task.hash.isBlank() && !verifySha1(task.targetFile, task.hash)) {
            log.warn("SHA-1 verification failed: {} (expected: {})", task.targetFile, task.hash);
            try {
                Files.deleteIfExists(task.targetFile);
            }
            catch (IOException iOException) {
                // empty catch block
            }
            verifyCacheInvalidate(task.targetFile);
            return false;
        }
        // 客户端 JAR 太大，必须登记校验状态，否则下次启动又要对它整文件算一遍 SHA-1
        verifyCacheMark(task.targetFile);
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
            List<URI> indexCandidates = DownloadProviderHolder.get().getAssetIndexCandidates(assetId, sha1);
            for (URI candidate : indexCandidates) {
                if (downloaded || isCancelled(cancel)) break;
                DownloadOutcome outcome = downloadFile(candidate.toString(), targetFile, null, cancel);
                if (outcome == DownloadOutcome.OK) {
                    downloaded = true;
                } else if (outcome == DownloadOutcome.CANCELLED) {
                    break;
                }
                // RETRY / FAIL：索引单文件下载保持原有语义，继续下一候选源（不做等待池）
            }
            if (!downloaded && log.isDebugEnabled()) {
                log.debug("Mirror index download failed, trying Mojang official source: {}", assetId);
            }
            if (!(downloaded || isCancelled(cancel) || indexUrl == null || indexUrl.isBlank())) {
                DownloadOutcome outcome = downloadFile(indexUrl, targetFile, null, cancel);
                if (outcome == DownloadOutcome.OK) {
                    downloaded = true;
                } else if (outcome == DownloadOutcome.CANCELLED) {
                    // 取消：保持未下载状态，由外层返回 null
                }
            }
            if (!downloaded && !isCancelled(cancel) && sha1 != null) {
                String mojangUrl = String.format(INDEX_MOJANG, sha1, assetId);
                DownloadOutcome outcome = downloadFile(mojangUrl, targetFile, null, cancel);
                downloaded = outcome == DownloadOutcome.OK;
            }
            if (!downloaded) {
                log.error("Asset index download failed: assetId={}", assetId);
                return null;
            }
            // 索引是「这个版本要下哪些资产」的唯一依据，空/坏索引会让整条资产流程空转
            // （objects 为空 → 报告"补全完成"但一个资产都没下）。这里明确判失败。
            if (Files.size(targetFile) == 0L) {
                log.error("资产索引内容为空，判定下载失败: assetId={}, file={}", assetId, targetFile);
                Files.deleteIfExists(targetFile);
                return null;
            }
            if (!FileVerifier.isJson(targetFile)) {
                log.error("资产索引不是合法 JSON（可能拿到了错误页），判定下载失败: assetId={}, file={}",
                        assetId, targetFile);
                Files.deleteIfExists(targetFile);
                return null;
            }
            if (sha1 != null && !verifySha1(targetFile, sha1)) {
                // 以前这里只 warn 并继续用坏索引 —— 会静默退化成"没有资产要补全"。
                // 索引可重新下载，宁可直接判失败让上层重试一次。
                log.warn("资产索引 SHA-1 校验失败，判定下载失败: assetId={}", assetId);
                Files.deleteIfExists(targetFile);
                return null;
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
                handleVirtualResources(gameDir, indexFile, progress, cancel);
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

    /**
     * 把已下载的资产对象复制到 {@code assets/virtual/legacy} 的虚拟目录结构下。
     *
     * <h3>为什么必须上报进度（曾经的"卡在后期"元凶）</h3>
     * 本方法是<b>逐文件同步复制</b>，且历史版本里<b>完全没有进度上报、也不检查取消</b>。
     * 它在 {@link #downloadAssetObjects} 里被排在全部资产下载<b>之后</b>调用，于是：
     * <pre>
     *   下载阶段跑完 → 进度条已经到 100% → 之后静默复制上万个文件 → 界面看起来"卡住不动"
     * </pre>
     * 旧版（1.6 及以前）的 legacy 索引条目可达数千至数万，复制过程可能持续很久，
     * 而用户只能看到一个不再变化的 100% 进度条，完全无法判断是在干活还是真死了。
     *
     * @param progress 父进度回调（会被映射到 95~100 区间，避免与下载阶段抢进度）
     * @param cancel   取消标志；复制中途可被取消
     */
    private static void handleVirtualResources(String gameDir, Path indexFile,
                                               ProgressCallback progress, AtomicBoolean cancel) {
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
            int total = objects.size();
            int copied = 0;
            int scanned = 0;
            long lastReportAt = 0L;
            report(progress, 95, "正在整理虚拟资源目录（共 " + total + " 条待检查）...");
            for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
                if (isCancelled(cancel)) {
                    report(progress, 100, "已取消虚拟资源整理");
                    log.info("Virtual resource copy cancelled: copied {} of {}", copied, total);
                    return;
                }
                scanned++;
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

                // 每 2 秒或每 200 个文件回报一次，让界面能看出"在推进、推进了多少"
                long now = System.currentTimeMillis();
                if (now - lastReportAt >= 2_000L || scanned % 200 == 0) {
                    lastReportAt = now;
                    int pct = total > 0 ? 95 + Math.min(5, scanned * 5 / total) : 100;
                    report(progress, pct, String.format("正在整理虚拟资源目录: %d/%d 已检查，%d 已复制",
                            scanned, total, copied));
                }
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
        // 2026-09-30 A/B 双池：按 size 阈值拆小文件池(A)与大文件池(B)，两池并行下载
        List<AssetTask> smallPending = new ArrayList<>(needDownload);
        List<AssetTask> bigPending = new ArrayList<>();
        for (AssetTask t : pending) {
            if (t.size >= BIG_FILE_THRESHOLD) {
                bigPending.add(t);
            } else {
                smallPending.add(t);
            }
        }
        int smallThreads = Math.max(1, Math.min(smallPending.size(), DownloadSettings.getDownloadThreads()));
        int bigThreads = Math.max(1, Math.min(bigPending.size(), BIG_POOL_THREADS));
        ExecutorService smallExecutor = Executors.newFixedThreadPool(smallThreads);
        ExecutorService bigExecutor = bigPending.isEmpty() ? null : Executors.newFixedThreadPool(bigThreads);
        AtomicInteger completed = new AtomicInteger(preExisting);
        AtomicInteger failedCount = new AtomicInteger(0);
        // 卡住诊断：记录"最后一次有文件完成"的时刻与当时在跑的路径。
        // 历史问题是"下载到后期卡住"却看不出卡在哪个文件、哪个阶段 —— 有了这个
        // 每 20 秒如果进度不动就打一行"卡在哪些文件"，一眼能定位。
        AtomicLong lastProgressAt = new AtomicLong(System.currentTimeMillis());
        AtomicInteger lastProgressCount = new AtomicInteger(preExisting + 0);
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sl-batch-watchdog");
            t.setDaemon(true);
            return t;
        });
        AtomicBoolean batchDone = new AtomicBoolean(false);
        // 每 20 秒检查一次：进度超过 1 分钟没动，就把"还没完成的文件"打出来
        watchdog.scheduleAtFixedRate(() -> {
            if (batchDone.get()) return;
            long idle = System.currentTimeMillis() - lastProgressAt.get();
            if (idle < 60_000L) return;
            List<String> stuck = new ArrayList<>();
            synchronized (pending) {
                for (AssetTask t : pending) {
                    if (!t.downloaded) {
                        // 2026-09-30 增强 C：附上文件大小，卡住时可直接看出是「大文件在慢源上慢慢下」
                        // 还是「小文件在等停滞判定」
                        stuck.add(t.virtualPath + (t.size > 0 ? " (" + (t.size / 1024) + "KB)" : ""));
                    }
                    if (stuck.size() >= 20) break;
                }
            }
            log.warn("[{}] 已 {}s 无文件完成（完成 {}/{}），仍在等待: {}{}",
                    taskLabel, idle / 1000L, lastProgressCount.get(), total,
                    stuck.isEmpty() ? "（无）" : String.join(", ", stuck),
                    stuck.size() >= 20 ? " …" : "");
        }, 20, 20, TimeUnit.SECONDS);
        try {
            long batchDeadline = System.currentTimeMillis() + BATCH_TIMEOUT_MS;
            // 批内等待池（2026-09-30 下载等待池方案 §2.2）：可重试失败先入池，主波次结束后延迟重试
            RetryQueue<AssetTask> retryQueue = new RetryQueue<>(RETRY_QUEUE_CAPACITY, cancel);
            // 最终失败任务集合：重试仍失败才记健康表（§2.3）的核算对象
            List<AssetTask> finallyFailed = Collections.synchronizedList(new ArrayList<>());

            // 主波次：A 池（小文件）与 B 池（大文件）并行 //
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            List<AssetTask> waveRetryable = Collections.synchronizedList(new ArrayList<>());
            futures.addAll(submitWaveTasks(smallPending, smallExecutor, primaryUrl, fallbackUrl, cancel,
                    completed, failedCount, lastProgressAt, lastProgressCount, progress, total, taskLabel,
                    finallyFailed, waveRetryable));
            if (bigExecutor != null) {
                futures.addAll(submitWaveTasks(bigPending, bigExecutor, primaryUrl, fallbackUrl, cancel,
                        completed, failedCount, lastProgressAt, lastProgressCount, progress, total, taskLabel,
                        finallyFailed, waveRetryable));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(BATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            // 等待池：退避 + 延迟重试轮 //
            if (!isCancelled(cancel)) {
                offerAllToQueue(waveRetryable, retryQueue, failedCount, finallyFailed);
            }
            if (!retryQueue.isEmpty()) {
                log.info("[{}] {} 个文件进入等待池，开始延迟重试", taskLabel, retryQueue.size());
                report(progress, Math.min(99, completed.get() * 100 / total),
                        taskLabel + ": " + retryQueue.size() + " 个文件进入等待池，开始重试");
            }
            for (int round = 1; round <= MAX_RETRY_ROUNDS; round++) {
                if (retryQueue.isEmpty() || isCancelled(cancel)) {
                    break;
                }
                long remaining = batchDeadline - System.currentTimeMillis();
                long backoff = RetryQueue.backoffMs(round);
                if (remaining < backoff + MIN_RETRY_EXECUTE_MS) {
                    log.warn("[{}] 批级剩余时间不足（{}ms），跳过第 {} 轮重试（{} 个文件将计为最终失败）",
                            taskLabel, remaining, round, retryQueue.size());
                    break;
                }
                log.info("[{}] 第 {} 轮重试开始，共 {} 个文件（退避 {}ms）", taskLabel, round, retryQueue.size(), backoff);
                try {
                    // 等待发生在批主线程，不占线程池槽位（§2.2）；抖动让各任务错峰重试
                    Thread.sleep(backoff);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (isCancelled(cancel)) {
                    break;
                }
                List<AssetTask> roundTasks = retryQueue.drain();
                List<AssetTask> roundSmall = new ArrayList<>();
                List<AssetTask> roundBig = new ArrayList<>();
                for (AssetTask task : roundTasks) {
                    if (task.size >= BIG_FILE_THRESHOLD) {
                        roundBig.add(task);
                    } else {
                        roundSmall.add(task);
                    }
                }
                List<AssetTask> roundRetryable = Collections.synchronizedList(new ArrayList<>());
                ArrayList<CompletableFuture<Void>> roundFutures = new ArrayList<>(roundTasks.size());
                roundFutures.addAll(submitWaveTasks(roundSmall, smallExecutor, primaryUrl, fallbackUrl, cancel,
                        completed, failedCount, lastProgressAt, lastProgressCount, progress, total, taskLabel,
                        finallyFailed, roundRetryable));
                if (bigExecutor != null && !roundBig.isEmpty()) {
                    roundFutures.addAll(submitWaveTasks(roundBig, bigExecutor, primaryUrl, fallbackUrl, cancel,
                            completed, failedCount, lastProgressAt, lastProgressCount, progress, total, taskLabel,
                            finallyFailed, roundRetryable));
                }
                long roundWait = Math.max(1L, batchDeadline - System.currentTimeMillis());
                CompletableFuture.allOf(roundFutures.toArray(new CompletableFuture[0]))
                        .get(roundWait, TimeUnit.MILLISECONDS);
                if (!isCancelled(cancel)) {
                    offerAllToQueue(roundRetryable, retryQueue, failedCount, finallyFailed);
                }
                log.info("[{}] 第 {} 轮重试结束，剩余 {} 个", taskLabel, round, retryQueue.size());
            }

            // 最终核算 //
            if (!isCancelled(cancel)) {
                int leftover = retryQueue.size();
                if (leftover > 0) {
                    for (AssetTask t : retryQueue.drain()) {
                        finallyFailed.add(t);
                    }
                    failedCount.addAndGet(leftover);
                    log.warn("[{}] 等待池重试轮次耗尽或时间不足，{} 个文件计为最终失败", taskLabel, leftover);
                }
                // 重试仍失败才记健康表（§2.3）：0B → recordEmpty、停滞 → recordTimeout、
                // 其余可重试失败 → 批级慢源标记（连续 3 批触发额外拉黑）
                for (AssetTask t : finallyFailed) {
                    recordFinalFailures(t);
                }
                SourceHealth.getInstance().advanceBatch();
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("{} download interrupted", taskLabel);
        }
        catch (Exception e) {
            // TimeoutException 也走这里：说明有一批文件在 30 分钟内没跑完。
            // 以前只是静默记一行 error，用户侧完全没有反馈 —— 现在明确点名。
            log.error("{} 批处理未在 30 分钟内完成（可能仍有文件卡住）: {}", taskLabel, e.toString());
        }
        finally {
            batchDone.set(true);
            watchdog.shutdownNow();
            smallExecutor.shutdown();
            if (bigExecutor != null) {
                bigExecutor.shutdown();
            }
            try {
                if (!smallExecutor.awaitTermination(5L, TimeUnit.SECONDS)) {
                    smallExecutor.shutdownNow();
                }
                if (bigExecutor != null && !bigExecutor.awaitTermination(5L, TimeUnit.SECONDS)) {
                    bigExecutor.shutdownNow();
                }
            }
            catch (InterruptedException e) {
                smallExecutor.shutdownNow();
                if (bigExecutor != null) {
                    bigExecutor.shutdownNow();
                }
                Thread.currentThread().interrupt();
            }
        }
        int totalDownloaded = completed.get();
        int totalFailed = failedCount.get();
        // 2026-09-30 增强 C：打印本批慢文件清单，区分「大文件被慢源限速」与「小文件在等停滞」
        logSlowFiles(tasks, taskLabel);
        // 一批文件处理完：把本批新登记的校验状态落盘（M2）。
        // 放在这里而不是更外层，是因为 assets/libraries/natives/legacy 四条路径都汇聚到本方法。
        flushVerifyCache();
        // 2026-09-30 增强（源健康持久化）：把本批「最近成功源」经验落盘，
        // 下次启动补全不再冷启动探测，前期文件直接优先历史快源
        SourceHealth.getInstance().persistIfDirty();
        if (isCancelled(cancel)) {
            report(progress, 100, String.format("%s: 已取消，%d/%d 已就绪", taskLabel, totalDownloaded, total));
            log.info("{} cancelled: {}/{} ready", taskLabel, totalDownloaded, total);
            return totalDownloaded;
        }
        report(progress, 100, String.format("%s: %d/%d 完成, %d 失败", taskLabel, totalDownloaded, total, totalFailed));
        return totalDownloaded;
    }

    /**
     * 把一个任务子集提交到指定线程池执行主波次 / 重试轮（2026-09-30 A/B 双池）。
     *
     * <p>A 池（小文件）与 B 池（大文件）共用同一组进度 / 失败计数器，进度条两池合并连续推进；
     * 取消标志两池共同轮询，可重试失败统一进入 {@code waveRetryable}，最终失败进入
     * {@code finallyFailed} 供批级核算（recordFinalFailures / advanceBatch）。
     */
    private static ArrayList<CompletableFuture<Void>> submitWaveTasks(List<AssetTask> poolTasks,
                                                                     ExecutorService executor,
                                                                     String primaryUrl, String fallbackUrl,
                                                                     AtomicBoolean cancel,
                                                                     AtomicInteger completed, AtomicInteger failedCount,
                                                                     AtomicLong lastProgressAt, AtomicInteger lastProgressCount,
                                                                     ProgressCallback progress, int total, String taskLabel,
                                                                     List<AssetTask> finallyFailed, List<AssetTask> waveRetryable) {
        ArrayList<CompletableFuture<Void>> futures = new ArrayList<>(poolTasks.size());
        for (AssetTask task : poolTasks) {
            futures.add(CompletableFuture.runAsync(() -> {
                if (isCancelled(cancel)) {
                    return;
                }
                DownloadOutcome outcome = downloadSingleAsset(task, primaryUrl, fallbackUrl, cancel);
                applyBatchOutcome(outcome, task, cancel, completed, failedCount, lastProgressAt,
                        lastProgressCount, progress, total, taskLabel, finallyFailed, waveRetryable);
            }, executor));
        }
        return futures;
    }

    /**
     * 批级单任务结果的统一落账（2026-09-30 下载等待池方案）：
     * OK → 计成功；CANCELLED → 忽略（取消不计数）；FAIL → 计失败并记入最终核算集合；
     * 可重试（RETRY）→ 进入重试列表（等待池），等待后续轮次重试。
     */
    private static void applyBatchOutcome(DownloadOutcome outcome, AssetTask task, AtomicBoolean cancel,
                                          AtomicInteger completed, AtomicInteger failedCount,
                                          AtomicLong lastProgressAt, AtomicInteger lastProgressCount,
                                          ProgressCallback progress, int total, String taskLabel,
                                          List<AssetTask> finallyFailed, List<AssetTask> retryableOut) {
        if (outcome == DownloadOutcome.OK) {
            task.downloaded = true;
            int done = completed.incrementAndGet();
            lastProgressCount.set(done);
            lastProgressAt.set(System.currentTimeMillis());
            updateBatchProgress(progress, done, total, taskLabel, failedCount.get());
        } else if (outcome == DownloadOutcome.CANCELLED) {
            // 取消：不计数、不入池
        } else if (outcome == DownloadOutcome.FAIL) {
            if (!isCancelled(cancel)) {
                finallyFailed.add(task);
                int fail = failedCount.incrementAndGet();
                log.warn("{} download failed: {}", taskLabel, task.virtualPath);
                lastProgressAt.set(System.currentTimeMillis());
                updateBatchProgress(progress, completed.get(), total, taskLabel, fail);
            }
        } else if (outcome.retryable) {
            // RETRY：进入等待池，等待后续轮次重试
            if (!isCancelled(cancel)) {
                retryableOut.add(task);
            }
        }
    }

    /** 把一批可重试任务入池；容量溢出部分直接计失败（方案 §4-6） */
    private static void offerAllToQueue(List<AssetTask> retryable, RetryQueue<AssetTask> queue,
                                        AtomicInteger failedCount, List<AssetTask> finallyFailed) {
        for (AssetTask task : retryable) {
            if (!queue.offer(task)) {
                failedCount.incrementAndGet();
                finallyFailed.add(task);
                log.warn("等待池容量上限（{}）已满，{} 直接计为失败", RETRY_QUEUE_CAPACITY, task.virtualPath);
            }
        }
    }

    /**
     * 最终失败核算（方案 §2.3）：只有「重试仍失败」的任务才把源记入健康表——
     * 200-0B → recordEmpty、停滞超时 → recordTimeout、其余可重试失败 → markSlowBatch
     * （连续 3 批被标记的源由 SourceHealth.advanceBatch 额外拉黑 120s）。
     */
    private static void recordFinalFailures(AssetTask task) {
        SourceHealth health = SourceHealth.getInstance();
        for (String url : task.emptyUrls) {
            health.recordEmpty(url, "资源补全");
        }
        for (String url : task.stallUrls) {
            health.recordTimeout(url, "资源补全");
        }
        for (String url : task.retryableFailUrls) {
            health.markSlowBatch(url);
        }
    }

    /**
     * 2026-09-30 增强 C：打印本批「慢文件」清单 —— 实际下载过且大于 1MB 的文件，
     * 按下载耗时降序取前 20 条，标注文件大小与耗时。
     * 用于区分两种慢形态：
     * <ul>
     *   <li><b>大文件被慢源限速</b>：清单里集中出现大文件、耗时与体积成正比 → 换源/跳过无济于事，
     *       需要二期把大文件切到可中断的分块并发下载；</li>
     *   <li><b>小文件在等停滞判定</b>：清单里几乎为空、但批整体慢 → 增强 A（慢源跳过）+ B（首试 5s）
     *       已根治，无需再处理。</li>
     * </ul>
     */
    private static void logSlowFiles(List<AssetTask> tasks, String taskLabel) {
        long slowThresholdBytes = 1024L * 1024L; // 1MB
        List<AssetTask> slow = new ArrayList<>();
        for (AssetTask t : tasks) {
            if (t.downloaded && t.downloadCostMs > 0 && t.size > slowThresholdBytes) {
                slow.add(t);
            }
        }
        if (slow.isEmpty()) {
            return;
        }
        slow.sort((a, b) -> Long.compare(b.downloadCostMs, a.downloadCostMs));
        if (slow.size() > 20) {
            slow = new ArrayList<>(slow.subList(0, 20));
        }
        log.info("[{}] 慢文件清单（下载耗时降序，前 {} 条，用于区分限速型与等待型）:",
                taskLabel, slow.size());
        for (AssetTask t : slow) {
            log.info("  - {} ({} MB, {} ms)", t.virtualPath,
                    String.format(java.util.Locale.ROOT, "%.1f", t.size / (1024.0 * 1024.0)),
                    t.downloadCostMs);
        }
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

    /**
     * 文件有效性判定（补全的核心判定，M2 在此插入"校验状态缓存"这一级）。
     *
     * <p>判定顺序（成本递增，任一级否定即返回 false）：
     * <ol>
     *   <li>文件不存在 → 无效；</li>
     *   <li><b>大小不符</b> → 无效（必重下，不看缓存）；</li>
     *   <li><b>快速校验</b>开启且有 size → 有效（用户显式选择"不要哈希"，优先级最高）；</li>
     *   <li><b>校验状态缓存</b>命中（上次验过且 size+mtime 未变）→ 有效，<b>跳过哈希计算</b>；</li>
     *   <li>否则算 SHA-1 比对。</li>
     * </ol>
     * 第 4 级是 M2 的收益点：二次补全时绝大多数文件都命中它，校验阶段从"读完全部文件"
     * 降到"只 stat 一遍"。
     */
    private static boolean isFileValid(AssetTask task) {
        Path file = task.targetFile;
        if (!Files.exists(file)) {
            verifyCacheInvalidate(file);
            return false;
        }
        try {
            if (task.size > 0 && Files.size(file) != task.size) {
                // 尺寸不符说明文件被改动过，缓存条目作废
                verifyCacheInvalidate(file);
                return false;
            }
            if (VerifySettings.isFastVerifyEnabled()) {
                if (task.size > 0) {
                    verifyCacheMark(file);
                    return true;
                }
                if (task.hash == null || task.hash.isBlank()) {
                    verifyCacheMark(file);
                    return true;
                }
            }
            // ★ M2：上次校验通过且之后没被改动 → 直接信，不读文件算哈希
            VerifyStateCache cache = verifyCache;
            if (cache != null && VerifySettings.isStateCacheEnabled() && cache.isFresh(file)) {
                return true;
            }
            if (task.hash != null && !task.hash.isBlank()) {
                boolean ok = verifySha1(file, task.hash);
                if (ok) {
                    verifyCacheMark(file);
                } else {
                    verifyCacheInvalidate(file);
                }
                return ok;
            }
            boolean ok = task.size > 0;
            if (ok) {
                verifyCacheMark(file);
            }
            return ok;
        }
        catch (IOException e) {
            return false;
        }
    }

    // 校验状态缓存（M2） //
    /** 当前补全流程使用的校验状态缓存；由补全入口按 gameDir 装载 */
    private static volatile VerifyStateCache verifyCache;
    /** 已装载缓存对应的游戏目录，用于判断是否需要重新装载 */
    private static volatile String verifyCacheGameDir;

    /**
     * 为某游戏目录装载校验状态缓存。
     *
     * <p>幂等：同一目录重复调用不重复读盘，所以可以放心在每个补全入口都调用一次；
     * 目录变了才重新装载（多游戏目录场景）。
     */
    private static void loadVerifyCache(String gameDir) {
        if (!VerifySettings.isStateCacheEnabled()) {
            verifyCache = null;
            verifyCacheGameDir = null;
            return;
        }
        if (gameDir != null && gameDir.equals(verifyCacheGameDir) && verifyCache != null) {
            return;
        }
        try {
            verifyCache = VerifyStateCache.loadFor(Path.of(gameDir));
            verifyCacheGameDir = gameDir;
            log.debug("校验状态缓存已就绪: gameDir={}, 条目={}", gameDir, verifyCache.size());
        }
        catch (Exception e) {
            // 缓存不可用只影响加速，绝不影响补全
            verifyCache = null;
            verifyCacheGameDir = null;
            log.debug("校验状态缓存装载失败，本次不做缓存: {}", e.toString());
        }
    }

    /** 登记"该文件此刻有效"；缓存被禁用时是空操作 */
    private static void verifyCacheMark(Path file) {
        VerifyStateCache c = verifyCache;
        if (c != null && VerifySettings.isStateCacheEnabled()) {
            c.markValidated(file);
        }
    }

    /** 作废某文件的校验记录；缓存被禁用时是空操作 */
    private static void verifyCacheInvalidate(Path file) {
        VerifyStateCache c = verifyCache;
        if (c != null) {
            c.invalidate(file);
        }
    }

    /** 把缓存落盘（补全结束时调用一次） */
    private static void flushVerifyCache() {
        VerifyStateCache c = verifyCache;
        if (c != null) {
            c.flush();
        }
    }

    private static DownloadOutcome downloadSingleAsset(AssetTask task, String primaryUrlTemplate, String fallbackUrlTemplate, AtomicBoolean cancel) {
        Path targetFile = task.targetFile;
        try {
            if (isCancelled(cancel)) {
                return DownloadOutcome.CANCELLED;
            }
            long startMs = System.currentTimeMillis();
            Files.createDirectories(targetFile.getParent());
            String url = task.url;
            List<URI> candidates = new ArrayList<>();
            if (url != null) {
                candidates.addAll(DownloadProviderHolder.get().injectURLWithCandidates(url));
                if (!candidates.contains(URI.create(url))) {
                    candidates.add(URI.create(url));
                }
            } else if (primaryUrlTemplate != null && task.hash != null && task.hash.length() >= 2) {
                String firstTwo = task.hash.substring(0, 2);
                // assetObjectLocation 必须是「前两位/hash」—— BMCLAPI 的 assets 端点镜像官方目录结构。
                // 传裸 hash 会让镜像每个资产都 404（历史 Bug）。
                String objectLocation = firstTwo + "/" + task.hash;
                candidates.addAll(DownloadProviderHolder.get().getAssetObjectCandidates(objectLocation));
                URI mojangForm = URI.create(String.format(primaryUrlTemplate, firstTwo, task.hash));
                if (!candidates.contains(mojangForm)) {
                    candidates.add(mojangForm);
                }
                if (fallbackUrlTemplate != null) {
                    URI fallbackForm = URI.create(String.format(fallbackUrlTemplate, firstTwo, task.hash));
                    if (!candidates.contains(fallbackForm)) {
                        candidates.add(fallbackForm);
                    }
                }
            }
            // 2026-09-30 下载等待池方案 §2.4：按健康状态 / 历史连接耗时 / slowCount 动态排序。
            // 重试波次再次进入时基于最新数据重新排序，天然优先尝试当前最优源。
            candidates = SourceRanking.sortCandidates(candidates);
            boolean downloaded = false;
            boolean retryableThisRun = false;
            candidateLoop:
            for (int i = 0; i < candidates.size(); i++) {
                if (downloaded || isCancelled(cancel)) break;
                URI candidate = candidates.get(i);
                String candidateUrl = candidate.toString();
                // 容器级健康表：某地址段连续 404 / 超时 / 空正文后直接跳过，
                // 否则每个文件都要在同一个死源上白付一次失败
                // （真实故障：bmclapi 的 /assets/<sha1> 对每个资产都 404 或返回 0 字节）
                if (SourceHealth.getInstance().isBlocked(candidateUrl)) {
                    continue;
                }
                // SHA-1 重试必须强制更换源（§2.1）：跳过已产出错误数据的候选
                if (task.sha1Excluded.contains(candidateUrl)) {
                    continue;
                }
                // ★ M3：判断这条候选是不是"最后一个还没被拉黑的候选"。
                // 若是，downloadFile 会放宽停滞判定 —— 慢但能用的最后一条路必须让它下完，
                // 否则会陷入"每次都被掐断、永远差一点"的死循环。
                boolean lastResort = isLastAvailableCandidate(candidates, i);
                // 2026-09-30 增强 A：批内慢源快速败退 —— 该源本批已多次首见失败（slowCount ≥ 阈值）
                // 且不是最后可用候选时，后续文件直接跳过，避免每个文件都白付一次停滞等待。
                // 重试轮里它排序已靠后，仍保留被再次尝试的机会；lastResort 例外保证唯一可用源能下完。
                if (!lastResort
                        && SourceHealth.getInstance().getSlowCount(candidateUrl) >= SLOW_SOURCE_SKIP_THRESHOLD) {
                    log.debug("本批慢源（slowCount≥{}）跳过: {}（任务 {}）",
                            SLOW_SOURCE_SKIP_THRESHOLD, candidateUrl, task.virtualPath);
                    continue;
                }
                // 2026-09-30 A/B 双池：大文件（≥1MB）走分块并发，小文件保持单连接
                DownloadOutcome outcome = (task.size >= BIG_FILE_THRESHOLD)
                        ? downloadBigFile(candidateUrl, targetFile, task.size, task.hash, cancel, lastResort)
                        : downloadFile(candidateUrl, targetFile, task.hash, cancel, lastResort);
                switch (outcome) {
                    case OK -> downloaded = true;
                    case CANCELLED -> {
                        return DownloadOutcome.CANCELLED;
                    }
                    case FAIL -> {
                        // 404/410 等不可重试：继续尝试其它候选源
                    }
                    case RETRY_SHA1 -> {
                        // SHA-1 不符只允许重试一次且必须换源（§2.1）；重试后仍不符即判死
                        if (task.sha1AlreadyRetried) {
                            return DownloadOutcome.FAIL;
                        }
                        task.sha1AlreadyRetried = true;
                        task.sha1Excluded.add(candidateUrl);
                        retryableThisRun = true;
                        break candidateLoop; // 换源交给下一轮重试波次
                    }
                    default -> {
                        // RETRY_EMPTY / RETRY_STALL / RETRY_INCOMPLETE / RETRY_HTTP / RETRY_CONNECTION：
                        // 记录失败种类供批级最终核算（重试仍失败才记健康表，§2.3），继续尝试下一个候选源
                        task.retryableFailUrls.add(candidateUrl);
                        if (outcome == DownloadOutcome.RETRY_EMPTY) {
                            task.emptyUrls.add(candidateUrl);
                        } else if (outcome == DownloadOutcome.RETRY_STALL) {
                            task.stallUrls.add(candidateUrl);
                        }
                        retryableThisRun = true;
                    }
                }
            }
            if (isCancelled(cancel)) {
                return DownloadOutcome.CANCELLED;
            }
            if (!downloaded) {
                // 所有候选都被健康表跳过时留一条线索，避免只剩「下载失败」看不出原因
                log.debug("资产候选全部不可用或被跳过: {} (hash={})", task.virtualPath, task.hash);
            }
            if (downloaded) {
                if (task.hash != null && !task.hash.isBlank() && !verifySha1(targetFile, task.hash)) {
                    log.warn("SHA-1 verification failed: {} (expected: {})", task.virtualPath, task.hash);
                    try {
                        Files.deleteIfExists(targetFile);
                    }
                    catch (IOException iOException) {
                        // empty catch block
                    }
                    verifyCacheInvalidate(targetFile);
                    return DownloadOutcome.FAIL;
                }
                // ★ M2：刚下载并校验通过的文件登记进缓存，否则下次补全仍会对它算一遍 SHA-1
                verifyCacheMark(targetFile);
                // 2026-09-30 增强 C：记录本任务下载耗时（慢文件清单用）
                task.downloadCostMs = System.currentTimeMillis() - startMs;
                return DownloadOutcome.OK;
            }
            // 任务级结果：存在可重试失败 → 入等待池；否则（如全部 404）→ 不可重试失败
            return retryableThisRun ? DownloadOutcome.RETRY : DownloadOutcome.FAIL;
        }
        catch (IOException e) {
            log.error("Download failed: {} - {}", task.virtualPath, e.getMessage());
            return DownloadOutcome.FAIL;
        }
    }

    /**
     * 大文件（≥ {@link #BIG_FILE_THRESHOLD}）下载：优先 {@link ChunkedDownloader} 分块并发；
     * 源不支持 Range 时回退单连接 {@link #downloadFile}，保证兼容不退化（2026-09-30 A/B 双池方案）。
     *
     * <p>分块合并成功后做与单连接一致的成功记账（recordSuccess / recordSuccessCost），
     * SHA-1 校验失败返回 {@link DownloadOutcome#RETRY_SHA1}（强制换源重试一次），
     * 其余语义（长度不符、停滞、0B 等）由 ChunkedDownloader 归并为可重试失败，进入等待池。
     */
    private static DownloadOutcome downloadBigFile(String urlStr, Path targetPath, int expectedSize,
                                                   String expectedSha1, AtomicBoolean cancel, boolean lastResort) {
        if (isCancelled(cancel)) {
            return DownloadOutcome.CANCELLED;
        }
        long startMs = System.currentTimeMillis();
        ChunkOutcome chunk = ChunkedDownloader.download(urlStr, targetPath, expectedSize, cancel, lastResort);
        switch (chunk) {
            case OK -> {
                if (expectedSha1 != null && !expectedSha1.isBlank() && !verifySha1(targetPath, expectedSha1)) {
                    log.warn("SHA-1 verification failed: {} (expected: {})", targetPath, expectedSha1);
                    deleteQuietly(targetPath);
                    verifyCacheInvalidate(targetPath);
                    return DownloadOutcome.RETRY_SHA1;
                }
                SourceHealth.getInstance().recordSuccess(urlStr);
                SourceHealth.getInstance().recordSuccessCost(urlStr, System.currentTimeMillis() - startMs);
                return DownloadOutcome.OK;
            }
            case CANCELLED -> {
                return DownloadOutcome.CANCELLED;
            }
            case NO_RANGE -> {
                // 源不支持 Range：回退单连接下载（与普通小文件同一路径）
                return downloadFile(urlStr, targetPath, expectedSha1, cancel, lastResort);
            }
            case FAIL -> {
                return DownloadOutcome.FAIL;
            }
            default -> {
                // RETRY：分片停滞 / 超时 / 长度不符 / 连接失败 → 入等待池换源重试
                return DownloadOutcome.RETRY;
            }
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

    static DownloadOutcome downloadFile(String urlStr, Path targetPath, String expectedSha1) {
        return downloadFile(urlStr, targetPath, expectedSha1, null);
    }

    static DownloadOutcome downloadFile(String urlStr, Path targetPath, String expectedSha1, AtomicBoolean cancel) {
        return downloadFile(urlStr, targetPath, expectedSha1, cancel, false);
    }

    /**
     * 下载单个文件到临时文件，校验通过后原子替换目标。
     *
     * <h3>为什么这里用 HttpURLConnection 而不是 HttpClient</h3>
     * 补全链路其余部分（元数据、版本 JSON、SHA-1 探测）仍用 {@link #HTTP_CLIENT}，
     * 但<b>文件正文传输必须有一个真实的读超时</b>，而 {@code java.net.http.HttpClient} 没有
     * socket read timeout。以下四种"绕路"方案均已实测失败（服务端发完响应头就卡死）：
     * <ol>
     *   <li>{@code Thread.interrupt()} 工作线程 → 阻塞中的 read 120s 后仍未返回；</li>
     *   <li>从看门狗线程 {@code close()} 掉 body InputStream → 同样无法唤醒 read；</li>
     *   <li>{@code BodyHandlers.ofFile} + {@code HttpRequest.timeout} → timeout 不覆盖正文阶段；</li>
     *   <li>{@code sendAsync} + {@code future.cancel(true)} → 返回 false，传输照旧挂着。</li>
     * </ol>
     * {@link java.net.HttpURLConnection#setReadTimeout(int)} 实测有效：卡死时按设定值抛
     * {@link java.net.SocketTimeoutException}，于是"连得上但不干活"的源能被<b>及时换掉</b>，
     * 而不是永久占住固定线程池里的一个工作线程。
     *
     * <h3>多源语义完整保留</h3>
     * 本方法只负责"把这一条 URL 弄到手"，候选源的遍历、跳过、换源全部由调用方
     * {@link #downloadSingleAsset} 的候选循环负责；本方法返回
     * {@link DownloadOutcome#RETRY_EMPTY} / {@link DownloadOutcome#RETRY_STALL} 等即代表"这一源
     * 疑似慢/瞬时失败（可重试）"，返回 {@link DownloadOutcome#FAIL} 代表"这一源确定不行"。
     *
     * <h3>2026-09-30 下载等待池调整</h3>
     * 首见 0B / 停滞只累加 {@link SourceHealth#recordSlow} 软计数，<b>不再当场调用
     * recordEmpty / recordTimeout</b>；「重试仍失败」才由批级最终核算调用正式计数（见
     * {@link #recordFinalFailures}）。
     *
     * @param lastResort 当前候选是否为<b>最后一个未拉黑的候选</b>。若为 true，
     *                   读超时放宽容忍度（见 {@link #LAST_RESORT_TIMEOUT_FACTOR}）——
     *                   慢但能用的最后一条路不该被反复掐断。
     */
    static DownloadOutcome downloadFile(String urlStr, Path targetPath, String expectedSha1,
                                        AtomicBoolean cancel, boolean lastResort) {
        if (isCancelled(cancel)) {
            return DownloadOutcome.CANCELLED;
        }
        Path tempFile = null;
        HttpURLConnection conn = null;
        long totalRead = 0L;
        long startMs = System.currentTimeMillis();
        try {
            String current = urlStr;
            int hop = 0;
            while (true) {
                URL url = new URL(current);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(DownloadSettings.getConnectTimeoutMs());
                // ★ 停滞看门狗的本体：单次 read 超过该时长无字节即抛 SocketTimeoutException
                conn.setReadTimeout(readTimeoutMs(lastResort));
                conn.setRequestProperty("User-Agent", AppConfig.USER_AGENT);
                if (totalRead > 0) {
                    // 中途换源后续传（content-addressed 资源，任何镜像给的字节都一致）
                    conn.setRequestProperty("Range", "bytes=" + totalRead + "-");
                }

                // 「发送」日志必须在真正发请求之前记：否则会与响应日志同毫秒相邻，读起来像发了两次
                DebugLog.http(true, "GET", current, 0, 0L, null, -1L);
                int code = conn.getResponseCode();

                if (code >= 300 && code < 400) {
                    String location = conn.getHeaderField("Location");
                    disconnect(conn);
                    conn = null;
                    if (location == null || location.isBlank() || ++hop > MAX_REDIRECT_HOPS) {
                        DebugLog.http(false, null, current, code, 0L, "重定向异常", -1L);
                        log.warn("重定向失败 (HTTP {}, {} 跳): {}", code, hop, urlStr);
                        return DownloadOutcome.FAIL;
                    }
                    current = new URL(url, sanitizeRedirect(location)).toString();
                    continue;
                }
                if (code < 200 || code >= 300) {
                    DebugLog.http(false, null, current, code, 0L, null, -1L);
                    log.debug("HTTP {}: {}", code, current);
                    recordHttpFailure(current, code);
                    // 404/410 为「资源确定不存在」→ 不可重试；其余（5xx 等）→ 可重试入等待池
                    return (code == 404 || code == 410)
                            ? DownloadOutcome.FAIL
                            : DownloadOutcome.RETRY_HTTP;
                }

                long contentLength = conn.getContentLengthLong();
                tempFile = uniqueTempFile(targetPath);
                Files.createDirectories(targetPath.getParent());

                boolean cancelledMidway = false;
                try (InputStream in = conn.getInputStream();
                     OutputStream out = totalRead > 0
                             ? Files.newOutputStream(tempFile, StandardOpenOption.CREATE,
                                     StandardOpenOption.WRITE)   // 续传：接着已有字节往后写
                             : Files.newOutputStream(tempFile)) {
                    int read;
                    byte[] buffer = new byte[BUFFER_SIZE];
                    while ((read = in.read(buffer)) != -1) {
                        if (isCancelled(cancel)) {
                            cancelledMidway = true;
                            break;
                        }
                        out.write(buffer, 0, read);
                        com.example.starlight.util.NetTrafficStats.recordBytes(read); // 全局流量统计
                        totalRead += read;
                    }
                }
                DebugLog.http(false, null, current, code, totalRead,
                        cancelledMidway ? "[已取消]" : "[二进制文件]", -1L);
                if (cancelledMidway) {
                    Files.deleteIfExists(tempFile);
                    return DownloadOutcome.CANCELLED;
                }
                // 「200 但 0 字节」：镜像服务活着但供不出内容（真实故障形态）。
                // 这类响应通常不带 Content-Length，下面的长度校验拦不住，必须单独判定。
                // 只在该文件确实应当有内容时判定（调用方给了期望 SHA-1），避免误杀合法空文件。
                if (totalRead == 0L && expectedSha1 != null && !expectedSha1.isBlank()) {
                    log.warn("源返回 200 但正文为 0 字节，入等待池重试: {}", current);
                    // 2026-09-30：首见只记软计数（§2.3），重试仍失败才由批级核算 recordEmpty
                    SourceHealth.getInstance().recordSlow(current, "资源补全");
                    Files.deleteIfExists(tempFile);
                    return DownloadOutcome.RETRY_EMPTY;
                }
                if (contentLength > 0L && totalRead != contentLength) {
                    log.warn("Incomplete download: {} (expected {} bytes, actual {} bytes)",
                            current, contentLength, totalRead);
                    Files.deleteIfExists(tempFile);
                    return DownloadOutcome.RETRY_INCOMPLETE;
                }
                break;
            }

            // 只有「内容真的到手且校验通过」才记成功：清零健康表计数并解禁该地址段，
            // 镜像恢复后自动切回，不用等冷却期结束
            if (expectedSha1 != null && !expectedSha1.isBlank() && !verifySha1(tempFile, expectedSha1)) {
                if (Files.exists(targetPath) && verifySha1(targetPath, expectedSha1)) {
                    log.debug("临时文件校验未通过但目标文件已就绪，直接采用: {}", targetPath);
                    Files.deleteIfExists(tempFile);
                    return DownloadOutcome.OK;
                }
                log.warn("SHA-1 verification failed: {} (expected: {})", urlStr, expectedSha1);
                Files.deleteIfExists(tempFile);
                // SHA-1 不符只允许重试一次且必须换源（由 downloadSingleAsset 强制排除该源，§2.1）
                return DownloadOutcome.RETRY_SHA1;
            }
            SourceHealth.getInstance().recordSuccess(urlStr);
            // 2026-09-30 §2.4：记录最近一次成功下载耗时/时间戳，供候选源动态排序
            SourceHealth.getInstance().recordSuccessCost(current, System.currentTimeMillis() - startMs);
            Files.move(tempFile, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return DownloadOutcome.OK;
        }
        catch (InterruptedIOException e) {
            // SocketTimeoutException 继承自 InterruptedIOException：命中停滞看门狗。
            // 此时工作线程【没有】被中断（本机制不依赖线程中断），所以不需要清中断位。
            long stalledSec = readTimeoutMs(lastResort) / 1000L;
            log.warn("源停滞（{}s 无数据）{}，入等待池重试: {}", stalledSec,
                    lastResort ? "[最后候选，已放宽]" : "", urlStr);
            // 2026-09-30：首见只记软计数（§2.3），重试仍失败才由批级核算 recordTimeout
            SourceHealth.getInstance().recordSlow(urlStr, "资源补全");
            deleteQuietly(tempFile);
            return DownloadOutcome.RETRY_STALL;
        }
        catch (IOException e) {
            log.debug("下载失败: {} ({})", urlStr, e.toString());
            deleteQuietly(tempFile);
            // 连接异常：可重试（§2.1），入等待池
            return DownloadOutcome.RETRY_CONNECTION;
        }
        finally {
            disconnect(conn);
        }
    }

    /** 读超时：最后候选放宽，避免把唯一可用但偏慢的源反复掐断 */
    private static int readTimeoutMs(boolean lastResort) {
        int base = DownloadSettings.getStallTimeoutMs();
        // 2026-09-30 增强 B（停滞判定分级）：只有「最后可用候选」才用 DownloadStallTimeoutSec × 放大系数，
        // 前面的候选统一用更快的首试判定（默认 5s），坏源代价从 15s 降到 5s。
        return lastResort ? base * LAST_RESORT_TIMEOUT_FACTOR : DownloadSettings.getFirstTryStallMs();
    }

    /**
     * 这条候选（下标 index）是否是候选列表里<b>最后一个尚未被健康表拉黑的</b>候选。
     *
     * <p>用于 M3「committed 最后候选源」：只有真正走到最后一条路时才放宽停滞判定。
     * 多源语义不变 —— 候选列表、顺序、遍历、跳过逻辑全是原来的，本方法只做"还有没有备胎"的判断。
     *
     * <p>注意：{@code index} 之后若全被拉黑，则本條就是"最后一个可用的"，需要放宽；
     * 若后面还有可用候选，则本條照常严格判定（失败了还有得换）。
     */
    private static boolean isLastAvailableCandidate(List<URI> candidates, int index) {
        for (int j = index + 1; j < candidates.size(); j++) {
            if (!SourceHealth.getInstance().isBlocked(candidates.get(j).toString())) {
                return false;
            }
        }
        return true;
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        }
        catch (IOException ignored) {
            // 清理失败不影响主流程，下次同路径下载会覆盖
        }
    }

    private static void disconnect(HttpURLConnection c) {
        if (c != null) {
            try {
                c.disconnect();
            }
            catch (Exception ignored) {
                // disconnect 失败无补救手段
            }
        }
    }

    /**
     * 修复重定向 Location 里的非 ASCII 字符。
     *
     * <p>部分镜像的 Location 头会直接给出未编码的中文，直接构造 {@link URL} 会抛
     * {@code MalformedURLException}。这里<b>只对非 ASCII 字符</b>做 UTF-8 百分号编码，
     * 其余原样保留 —— 不能整体 URL-encode，否则会把 {@code ://}、{@code %} 等已正确的部分也破坏掉。
     */
    private static String sanitizeRedirect(String location) {
        if (location == null || location.isBlank()) return location;
        boolean ascii = true;
        for (int i = 0; i < location.length(); i++) {
            if (location.charAt(i) > 127) {
                ascii = false;
                break;
            }
        }
        if (ascii) return location;
        StringBuilder sb = new StringBuilder(location.length() + 16);
        for (byte b : location.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int v = b & 0xFF;
            if (v < 0x80) {
                sb.append((char) v);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit((v >> 4) & 0xF, 16)))
                  .append(Character.toUpperCase(Character.forDigit(v & 0xF, 16)));
            }
        }
        return sb.toString();
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

    /**
     * 按 HTTP 状态码把失败归类进容器级健康表。
     *
     * <p>只有「确定是源的问题」的状态码才计数：
     * <ul>
     *   <li>404 / 410 —— 该地址段在镜像上不存在；</li>
     *   <li>其余（5xx 等）—— 可能是对方抽风或本地网络抖动，交回重试，不拉黑。</li>
     * </ul>
     */
    private static void recordHttpFailure(String urlStr, int status) {
        if (status == 404 || status == 410) {
            SourceHealth.getInstance().record404(urlStr, status, "资源补全");
        } else {
            SourceHealth.getInstance().recordOtherFailure(urlStr);
        }
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

    /**
     * 单条下载结果分类（2026-09-30 下载等待池方案 §2.1）。
     *
     * <ul>
     *   <li>{@link #OK}：内容到手且校验通过；</li>
     *   <li>{@link #RETRY}：任务级「可重试」——至少一个候选源产生可重试失败，入等待池；</li>
     *   <li>{@link #RETRY_EMPTY} / {@link #RETRY_STALL} / {@link #RETRY_INCOMPLETE} /
     *       {@link #RETRY_HTTP} / {@link #RETRY_CONNECTION} / {@link #RETRY_SHA1}：
     *       源级「可重试失败」具体种类（downloadFile 出口）；</li>
     *   <li>{@link #FAIL}：不可重试（404/410、重定向异常等）；</li>
     *   <li>{@link #CANCELLED}：用户取消（不计数、不入池）。</li>
     * </ul>
     */
    public enum DownloadOutcome {
        OK(false),
        RETRY(true),
        RETRY_EMPTY(true),
        RETRY_STALL(true),
        RETRY_INCOMPLETE(true),
        RETRY_HTTP(true),
        RETRY_CONNECTION(true),
        RETRY_SHA1(true),
        FAIL(false),
        CANCELLED(false);

        /** 是否属于「可重试失败」（可入等待池） */
        public final boolean retryable;

        DownloadOutcome(boolean retryable) {
            this.retryable = retryable;
        }
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
        /** 本任务最近一次成功下载的耗时（ms，2026-09-30 增强 C：慢文件清单用；0 = 未实际下载） */
        volatile long downloadCostMs;

        // 2026-09-30 下载等待池：跨重试轮次累积的任务级状态 //
        /** 本任务可重试失败的源集合（最终失败时用于 markSlowBatch / recordEmpty / recordTimeout，§2.3） */
        final Set<String> retryableFailUrls = ConcurrentHashMap.newKeySet();
        /** 200-0B 失败的源（最终失败时记 recordEmpty） */
        final Set<String> emptyUrls = ConcurrentHashMap.newKeySet();
        /** 停滞读超时的源（最终失败时记 recordTimeout） */
        final Set<String> stallUrls = ConcurrentHashMap.newKeySet();
        /** SHA-1 校验失败过的源（重试强制换源，§2.1） */
        final Set<String> sha1Excluded = ConcurrentHashMap.newKeySet();
        /** SHA-1 是否已重试过一次（只允许一次，§2.1） */
        volatile boolean sha1AlreadyRetried;

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

