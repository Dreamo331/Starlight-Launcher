package com.example.starlight.download.pcl;


import com.example.starlight.util.I18N;
import com.example.starlight.download.DownloadProviderHolder;
import com.example.starlight.download.VerifySettings;
import com.example.starlight.download.mod.SourceHealth;
import com.example.starlight.download.source.DownloadProvider;
import com.example.starlight.util.VersionUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * PCL 引擎的资源补全入口（2026-10-21）：决定「要下哪些文件」，具体怎么下交给
 * {@link PclDownloadManager}。
 *
 * <p>本类的解析部分（版本 JSON 继承链 / 资产索引 / 库规则）与旧引擎
 * {@code download.task.GameResourceCompleter} 保持同一套判定口径，
 * 差异只体现在「下载怎么执行」上——这样切换引擎不会改变「该不该下这个文件」的语义。
 *
 * <h3>与旧引擎的三点行为差异（有意为之）</h3>
 * <ol>
 *   <li><b>三阶段共享一个线程池</b>：资产 / 库 / 版本 JAR 同时提交，
 *       共享 {@link PclDownloadManager} 的全局并发配额，而不是各自开池相加；</li>
 *   <li><b>不做端点预探测</b>：PCL2 也没有这一步。健康表在后端按 host+目录前缀记账，
 *       坏源在重试几次后自然被跳过（探测反而会因「假路径与真实分发链路不同」而误判，旧引擎已踩过）；</li>
 *   <li><b>校验只做一次</b>：见 {@link PclDownloadManager} 的哈希策略。</li>
 * </ol>
 */
public final class PclResourceCompleter {

    private static final Logger log = LoggerFactory.getLogger(PclResourceCompleter.class);
    private static final Gson GSON = new Gson();

    private static final String OBJECTS_MOJANG = "https://resources.download.minecraft.net/%s/%s";
    private static final String OBJECTS_BMCLAPI = "https://bmclapi2.bangbang93.com/assets/%s/%s";
    private static final String LIB_MOJANG = "https://libraries.minecraft.net/";
    private static final String LIB_BMCLAPI = "https://bmclapi2.bangbang93.com/libraries/";

    /** 整批超时：与旧引擎同量级（30 分钟） */
    private static final long BATCH_TIMEOUT_MS = 30L * 60 * 1000L;

    private PclResourceCompleter() {
    }

    /** 进度回调（与旧引擎同签名语义：0~100 + 文本） */
    public interface ProgressCallback {
        void onProgress(int percent, String message);
    }

    /**
     * 补全一个版本的全部资源（资产 + 库 + 客户端 JAR）。
     *
     * @param gameDir   游戏根目录
     * @param versionId 版本 ID
     * @return true = 三项全部成功
     */
    public static boolean completeAll(String gameDir, String versionId,
                                      ProgressCallback progress, AtomicBoolean cancel) {
        JsonObject root = readResolvedVersionJson(gameDir, versionId, progress);
        if (root == null) return false;

        report(progress, 1, I18N.get("pcl.t0009"));

        // ---- 一次性构建三份待下载清单 ----
        List<PclFileTask> assetTasks = collectAssetTasks(gameDir, root, cancel);
        List<PclFileTask> libTasks = collectLibraryTasks(gameDir, root, true, true);
        PclFileTask jarTask = buildVersionJarTask(gameDir, versionId, root);

        List<PclFileTask> all = new ArrayList<>(assetTasks.size() + libTasks.size() + 2);
        all.addAll(assetTasks);
        all.addAll(libTasks);
        if (jarTask != null) all.add(jarTask);

        if (all.isEmpty()) {
            report(progress, 100, I18N.get("pcl.t0010"));
            log.info("PCL engine: nothing to download, versionId={}", versionId);
            return true;
        }

        long totalBytes = 0L;
        for (PclFileTask t : all) {
            if (t.size > 0) totalBytes += t.size;
        }
        log.info("PCL engine: versionId={}, 资产 {} 个 / 库 {} 个 / JAR {}，待下载 {} 个文件，{} MB",
                versionId, assetTasks.size(), libTasks.size(), jarTask == null ? 0 : 1,
                all.size(), totalBytes / (1024 * 1024));
        report(progress, 3, String.format(I18N.get("pcl.t0011"),
                all.size(), totalBytes / (1024 * 1024)));

        // ---- 进度上报线程：按已写字节算百分比 ----
        final long totalSnapshot = totalBytes;
        final AtomicBoolean stopReporter = new AtomicBoolean(false);
        Thread reporter = new Thread(() -> {
            while (!stopReporter.get()) {
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (stopReporter.get()) return;
                long done = 0L;
                int finished = 0;
                for (PclFileTask t : all) {
                    done += t.getWrittenBytes();
                    if (t.isFinished()) finished++;
                }
                int pct = totalSnapshot > 0
                        ? (int) Math.min(99L, done * 100L / totalSnapshot)
                        : (finished * 100 / Math.max(1, all.size()));
                report(progress, Math.max(3, pct), String.format(
                        I18N.get("pcl.t0012"),
                        finished, all.size(), done / 1048576.0, totalSnapshot / 1048576.0));
            }
        }, "pcl-progress");
        reporter.setDaemon(true);
        reporter.start();

        boolean ok;
        try {
            ok = PclDownloadManager.get().runAll(all, cancel, BATCH_TIMEOUT_MS);
        } finally {
            stopReporter.set(true);
            reporter.interrupt();
        }

        int succeeded = 0;
        for (PclFileTask t : all) {
            if (t.success) succeeded++;
        }
        // 批级健康核算：让坏的 host 段在下一批被跳过
        SourceHealth.getInstance().advanceBatch();

        if (cancel != null && cancel.get()) {
            report(progress, 100, I18N.get("pcl.t0013"));
            log.info("PCL engine cancelled: versionId={}, {}/{} ready", versionId, succeeded, all.size());
            return false;
        }
        if (ok) {
            report(progress, 100, I18N.get("pcl.t0014"));
            log.info("PCL engine finished: versionId={}, {}/{} files", versionId, succeeded, all.size());
        } else {
            report(progress, 100, String.format(I18N.get("pcl.t0015"), succeeded, all.size()));
            log.warn("PCL engine finished with failures: versionId={}, {}/{} files",
                    versionId, succeeded, all.size());
        }
        return ok;
    }

    /** 只补资产（供设置页/插件按需调用） */
    public static boolean completeAssetsOnly(String gameDir, String versionId,
                                             ProgressCallback progress, AtomicBoolean cancel) {
        JsonObject root = readResolvedVersionJson(gameDir, versionId, progress);
        if (root == null) return false;
        List<PclFileTask> tasks = collectAssetTasks(gameDir, root, cancel);
        return PclDownloadManager.get().runAll(tasks, cancel, BATCH_TIMEOUT_MS);
    }

    /** 只补库（含 natives classifier） */
    public static boolean completeNativeJarsOnly(String gameDir, String versionId,
                                                 ProgressCallback progress, AtomicBoolean cancel) {
        JsonObject root = readResolvedVersionJson(gameDir, versionId, progress);
        if (root == null) return false;
        List<PclFileTask> tasks = collectLibraryTasks(gameDir, root, false, true);
        return PclDownloadManager.get().runAll(tasks, cancel, BATCH_TIMEOUT_MS);
    }

    // ---- 版本 JSON ----

    private static JsonObject readResolvedVersionJson(String gameDir, String versionId,
                                                      ProgressCallback progress) {
        Path jsonPath = Paths.get(gameDir, "versions", versionId, versionId + ".json");
        if (!Files.isRegularFile(jsonPath)) {
            report(progress, 0, I18N.get("pcl.p0021", versionId));
            log.warn("PCL engine: version json missing: {}", jsonPath);
            return null;
        }
        try {
            String raw = Files.readString(jsonPath);
            JsonObject root = GSON.fromJson(raw, JsonObject.class);
            // 壳 JSON（整合包）要沿 inheritsFrom 合并，否则库列表/资产索引会退化
            return VersionUtils.resolveInheritedJson(Paths.get(gameDir), versionId, root);
        } catch (Exception e) {
            report(progress, 0, I18N.get("pcl.p0022", e.getMessage()));
            log.warn("PCL engine: version json parse failed: {}", e.toString());
            return null;
        }
    }

    // ---- 资产 ----

    private static List<PclFileTask> collectAssetTasks(String gameDir, JsonObject root,
                                                       AtomicBoolean cancel) {
        List<PclFileTask> result = new ArrayList<>();
        JsonObject assetIndex = root.getAsJsonObject("assetIndex");
        if (assetIndex == null) {
            log.warn("PCL engine: 版本 JSON 无 assetIndex，跳过资产补全");
            return result;
        }
        String assetId = str(assetIndex, "id");
        String sha1 = str(assetIndex, "sha1");
        String indexUrl = str(assetIndex, "url");
        if (assetId == null) return result;

        // 1) 资产索引本身
        Path indexesDir = Paths.get(gameDir, "assets", "indexes");
        Path indexFile = indexesDir.resolve(assetId + ".json");
        if (!Files.isRegularFile(indexFile) || sizeOf(indexFile) <= 0) {
            downloadAssetIndex(indexFile, assetId, sha1, indexUrl, cancel);
        }
        if (!Files.isRegularFile(indexFile)) {
            log.warn("PCL engine: 资产索引不可用，跳过资产补全: {}", assetId);
            return result;
        }

        // 2) 索引里的对象
        JsonObject objects;
        try {
            JsonObject json = GSON.fromJson(Files.readString(indexFile), JsonObject.class);
            objects = json.has("objects") ? json.getAsJsonObject("objects") : null;
            if (objects == null && json.has("virtual")) {
                // legacy 索引用 virtual：走虚拟目录映射太重量级，此处只补 objects 形态
                objects = null;
            }
        } catch (Exception e) {
            log.warn("PCL engine: 资产索引解析失败: {}", e.toString());
            return result;
        }
        if (objects == null || objects.isEmpty()) return result;

        DownloadProvider provider = DownloadProviderHolder.get();
        Path objectsDir = Paths.get(gameDir, "assets", "objects");
        // 按目标路径去重：同一 hash 的多个虚拟路径只下一次（PCL 靠 LocalPath 作 key 天然去重）
        Map<Path, PclFileTask> byTarget = new LinkedHashMap<>(objects.size());
        int dup = 0;

        for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
            if (cancel != null && cancel.get()) break;
            JsonObject obj = entry.getValue().getAsJsonObject();
            String hash = str(obj, "hash");
            if (hash == null || hash.length() < 2) continue;
            long size = obj.has("size") ? obj.get("size").getAsLong() : -1L;

            String firstTwo = hash.substring(0, 2);
            Path target = objectsDir.resolve(firstTwo).resolve(hash);
            if (byTarget.containsKey(target)) {
                dup++;
                continue;
            }
            if (isAlreadyThere(target, size)) continue;

            List<String> candidates = new ArrayList<>();
            for (URI uri : provider.getAssetObjectCandidates(firstTwo + "/" + hash)) {
                addCandidate(candidates, uri.toString());
            }
            addCandidate(candidates, String.format(OBJECTS_MOJANG, firstTwo, hash));
            addCandidate(candidates, String.format(OBJECTS_BMCLAPI, firstTwo, hash));

            byTarget.put(target, new PclFileTask(entry.getKey(), target, size, hash, candidates));
        }
        if (dup > 0) {
            log.info("PCL engine: 资产去重 {} 条（同一 hash 指向同一文件）", dup);
        }
        result.addAll(byTarget.values());
        return result;
    }

    /** 下载资产索引（候选 + 官方 URL 兜底），失败不抛异常 */
    private static void downloadAssetIndex(Path indexFile, String assetId, String sha1,
                                           String indexUrl, AtomicBoolean cancel) {
        try {
            Files.createDirectories(indexFile.getParent());
        } catch (IOException e) {
            return;
        }
        List<String> candidates = new ArrayList<>();
        for (URI uri : DownloadProviderHolder.get().getAssetIndexCandidates(assetId, sha1)) {
            addCandidate(candidates, uri.toString());
        }
        if (indexUrl != null && !indexUrl.isBlank()) addCandidate(candidates, indexUrl);

        for (String url : candidates) {
            if (cancel != null && cancel.get()) return;
            HttpURLConnection conn = null;
            InputStream in = null;
            boolean keepAlive = false;
            try {
                conn = PclHttp.openGet(url, 15_000, 30_000, null);
                if (conn.getResponseCode() != 200) {
                    PclHttp.abort(conn);
                    conn = null;
                    continue;
                }
                in = conn.getInputStream();
                Files.createDirectories(indexFile.getParent());
                Files.copy(in, indexFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                keepAlive = true;
                SourceHealth.getInstance().recordSuccess(url);
                log.info("PCL engine: 资产索引已下载 {}", assetId);
                return;
            } catch (IOException e) {
                // 换下一个候选
            } finally {
                if (keepAlive) {
                    PclHttp.finishKeepAlive(in);
                } else {
                    if (in != null) {
                        try {
                            in.close();
                        } catch (IOException ignored) {
                        }
                    }
                    PclHttp.abort(conn);
                }
            }
        }
    }

    // ---- 库 ----

    private static List<PclFileTask> collectLibraryTasks(String gameDir, JsonObject root,
                                                         boolean includeArtifacts,
                                                         boolean includeClassifiers) {
        List<PclFileTask> result = new ArrayList<>();
        JsonArray libraries = root.getAsJsonArray("libraries");
        if (libraries == null || libraries.isEmpty()) return result;

        String osName = normalizeOs(System.getProperty("os.name"));
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        Path libDir = Paths.get(gameDir, "libraries");
        Map<Path, PclFileTask> byTarget = new LinkedHashMap<>();

        for (JsonElement elem : libraries) {
            JsonObject lib = elem.getAsJsonObject();
            if (!isLibraryAllowed(lib, osName, arch)) continue;
            JsonObject downloads = lib.getAsJsonObject("downloads");
            if (downloads == null) continue;

            if (includeArtifacts && downloads.has("artifact")) {
                addLibraryEntry(byTarget, libDir, downloads.getAsJsonObject("artifact"));
            }
            if (includeClassifiers && downloads.has("classifiers")) {
                String classifier = getNativeClassifier(osName);
                JsonObject classifiers = downloads.getAsJsonObject("classifiers");
                if (classifier != null && classifiers != null && classifiers.has(classifier)) {
                    addLibraryEntry(byTarget, libDir, classifiers.getAsJsonObject(classifier));
                }
            }
        }
        result.addAll(byTarget.values());
        return result;
    }

    private static void addLibraryEntry(Map<Path, PclFileTask> byTarget, Path libDir,
                                        JsonObject artifact) {
        if (artifact == null) return;
        String path = str(artifact, "path");
        if (path == null || path.isBlank()) return;
        long size = artifact.has("size") ? artifact.get("size").getAsLong() : -1L;
        String sha1 = str(artifact, "sha1");
        String url = str(artifact, "url");

        Path target = libDir.resolve(path);
        if (byTarget.containsKey(target)) return;
        if (isAlreadyThere(target, size)) return;

        List<String> candidates = new ArrayList<>();
        if (url != null && !url.isBlank()) {
            for (URI uri : DownloadProviderHolder.get().injectURLWithCandidates(url)) {
                addCandidate(candidates, uri.toString());
            }
        }
        addCandidate(candidates, LIB_BMCLAPI + path);
        addCandidate(candidates, LIB_MOJANG + path);
        byTarget.put(target, new PclFileTask(path, target, size, sha1, candidates));
    }

    /** 库规则：Mojang 的 rules 数组语义（allow / disallow + os 匹配） */
    private static boolean isLibraryAllowed(JsonObject lib, String osName, String arch) {
        if (!lib.has("rules")) return true;
        JsonArray rules = lib.getAsJsonArray("rules");
        boolean allowed = false;
        for (JsonElement elem : rules) {
            JsonObject rule = elem.getAsJsonObject();
            String action = str(rule, "action");
            JsonObject os = rule.getAsJsonObject("os");
            boolean matches = true;
            if (os != null) {
                String requiredOs = str(os, "name");
                if (requiredOs != null) matches = requiredOs.equalsIgnoreCase(osName);
                String requiredArch = str(os, "arch");
                if (matches && requiredArch != null) {
                    matches = arch.contains(requiredArch.toLowerCase(Locale.ROOT));
                }
            }
            if ("allow".equals(action)) {
                if (!matches) continue;
                allowed = true;
            } else if ("disallow".equals(action) && matches) {
                allowed = false;
            }
        }
        return allowed;
    }

    private static String normalizeOs(String osName) {
        String lower = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (lower.contains("win")) return "windows";
        if (lower.contains("mac")) return "osx";
        if (lower.contains("linux")) return "linux";
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

    // ---- 版本 JAR ----

    private static PclFileTask buildVersionJarTask(String gameDir, String versionId, JsonObject root) {
        Path target = Paths.get(gameDir, "versions", versionId, versionId + ".jar");
        // 沿继承链找 downloads.client（整合包的壳 JSON 自己没有）
        String currentId = versionId;
        JsonObject current = root;
        java.util.Set<String> visited = new java.util.HashSet<>();
        String url = null;
        String sha1 = null;
        long size = -1L;
        while (current != null && currentId != null && visited.add(currentId)) {
            JsonObject downloads = current.getAsJsonObject("downloads");
            if (downloads != null && downloads.has("client")) {
                JsonObject client = downloads.getAsJsonObject("client");
                url = str(client, "url");
                sha1 = str(client, "sha1");
                size = client.has("size") ? client.get("size").getAsLong() : -1L;
                if (url != null) break;
            }
            String parentId = str(current, "inheritsFrom");
            if (parentId == null) break;
            Path parentJson = Paths.get(gameDir, "versions", parentId, parentId + ".json");
            if (!Files.isRegularFile(parentJson)) break;
            try {
                current = GSON.fromJson(Files.readString(parentJson), JsonObject.class);
            } catch (Exception e) {
                break;
            }
            currentId = parentId;
        }
        if (url == null) return null;
        if (isAlreadyThere(target, size)) return null;

        List<String> candidates = new ArrayList<>();
        for (URI uri : DownloadProviderHolder.get().getVersionClientCandidates(versionId)) {
            addCandidate(candidates, uri.toString());
        }
        for (URI uri : DownloadProviderHolder.get().injectURLWithCandidates(url)) {
            addCandidate(candidates, uri.toString());
        }
        addCandidate(candidates, url);
        return new PclFileTask(versionId + ".jar", target, size, sha1, candidates);
    }

    // ---- 工具 ----

    /** 文件已存在且大小匹配即视为就绪（PCL {@code McAssetsFixList} 同款判据，不做哈希） */
    private static boolean isAlreadyThere(Path target, long expectedSize) {
        try {
            if (!Files.isRegularFile(target)) return false;
            if (expectedSize <= 0) return Files.size(target) > 0;
            return Files.size(target) == expectedSize;
        } catch (IOException e) {
            return false;
        }
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return -1L;
        }
    }

    /** 候选去重（保持顺序）：同一地址不重复排队 */
    private static void addCandidate(List<String> candidates, String url) {
        if (url == null || url.isBlank()) return;
        if (!candidates.contains(url)) candidates.add(url);
    }

    private static String str(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return null;
        JsonElement e = obj.get(key);
        if (!e.isJsonPrimitive()) return null;
        String v = e.getAsString();
        return v == null || v.isEmpty() ? null : v;
    }

    private static void report(ProgressCallback cb, int percent, String msg) {
        if (cb != null) {
            try {
                cb.onProgress(Math.max(0, Math.min(100, percent)), msg);
            } catch (Throwable ignored) {
                // UI 回调异常不能影响下载
            }
        }
    }

    /** 供诊断：当前是否启用 PCL 引擎的 VerifySettings 兼容（保留调用点，避免未用告警） */
    static boolean streamingSha1() {
        return VerifySettings.isStreamingSha1Enabled();
    }
}