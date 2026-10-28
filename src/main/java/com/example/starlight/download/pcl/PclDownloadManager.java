package com.example.starlight.download.pcl;


import com.example.starlight.util.I18N;
import com.example.starlight.download.DownloadSettings;
import com.example.starlight.download.mod.HostPolicy;
import com.example.starlight.download.mod.SourceHealth;
import com.example.starlight.util.DebugLog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PCL 引擎的全局下载调度器（对标 PCL2 的 {@code NetManager}，{@code ModNet.vb:1727-1773}）。
 *
 * <h3>与旧引擎的四个结构性差异</h3>
 * <ol>
 *   <li><b>连接复用</b>：成功路径不 {@code disconnect()}，连接由 JDK keep-alive 池回收（见 {@link PclHttp}）。
 *       旧引擎每请求一次 TCP+TLS 握手，3500 个小文件就是 3500 次握手，
 *       而一个小文件本身只要几十毫秒就能传完 —— 开工率因此塌到约 10%；</li>
 *   <li><b>全局单一并发配额</b>：整批文件共享一个线程池（默认 64，对齐 PCL2 的
 *       {@code ToolDownloadThread+1}），而不是旧引擎「小文件池 32 + 大文件池 4」；</li>
 *   <li><b>几何分裂分块</b>：只有「最大段剩余 &gt; 256KB」才在本段的<b>最后 40%</b> 切出新段
 *       （PCL {@code ModNet.vb:861-862}），段数随推进自然增长；且仅当线程池确有闲置线程时才切；</li>
 *   <li><b>速率型停滞判定</b>：数据在动就不掐（PCL {@code ModNet.vb:1025-1028}：包间隔 &gt;5s
 *       <b>且</b> 该间隔内不足 1KB/s 才断开），配合自适应读超时
 *       {@code min(max(connectAvg,15s)×(1+失败数), 30s)}。</li>
 * </ol>
 *
 * <h3>哈希只算一次</h3>
 * 单连接路径边下边 digest；分段路径在合并分片时顺带 digest。
 * 因此每个文件是「一次读 + 一次写」，而旧引擎是「一次写 + 两次整文件重读校验」。
 *
 * <h3>并发调用</h3>
 * 本类是单例，{@link #runAll} 可被多个阶段（资产 / 库 / 版本 JAR）同时调用：
 * 它们共享同一个线程池与同一份并发配额，但各自只等自己的任务，互不干扰。
 */
public final class PclDownloadManager {

    private static final Logger log = LoggerFactory.getLogger(PclDownloadManager.class);

    // ---- 配置常量（对齐 PCL2） ----

    /** 小于此值不分段（PCL {@code NetFile.IsNoSplit}：{@code FileSize < 1MB}） */
    public static final long NO_SPLIT_THRESHOLD = 1024L * 1024L;

    /** 最小碎片：段剩余量小于此值不再分裂（PCL {@code FilePieceLimit = 256KB}） */
    private static final long PIECE_LIMIT_BYTES = 256L * 1024L;

    /** 新段取本段剩余量的最后 40%（PCL {@code DownloadEnd - DownloadUndone * 0.4}） */
    private static final double SPLIT_TAIL_RATIO = 0.4d;

    /** 每个候选源的连续重试次数（PCL：重试 3 次） */
    private static final int RETRY_PER_SOURCE = 3;

    /** 重试退避基数（PCL：{@code 500ms × 重试次数}） */
    private static final long RETRY_BACKOFF_BASE_MS = 500L;

    /** 建连超时 */
    private static final int CONNECT_TIMEOUT_MS = 15_000;

    /** 读超时下限（PCL：{@code max(ConnectAverage, 15000)}） */
    private static final int TIMEOUT_FLOOR_MS = 15_000;

    /** 读超时上限（PCL：30s） */
    private static final int TIMEOUT_CEIL_MS = 30_000;

    /** 慢速判定窗口：包间隔超过此值且窗口内不足 1KB/s 才断开（PCL：5000ms + 1KB/s） */
    private static final long SLOW_GAP_MS = 5_000L;

    /** 读缓冲 16KB，对齐 PCL 的 {@code New Byte(16384)} */
    private static final int READ_BUFFER = 16 * 1024;

    /** 小文件走内存缓冲的上限，超过改走临时文件 */
    private static final long MEMORY_BUFFER_LIMIT = 1024L * 1024L;

    // ---- 单例 ----

    private static final PclDownloadManager INSTANCE = new PclDownloadManager();

    public static PclDownloadManager get() {
        return INSTANCE;
    }

    // ---- 运行态 ----

    /** 待领段队列（只存「未被认领」的段；出队即独占） */
    private final ConcurrentLinkedDeque<PclPiece> ready = new ConcurrentLinkedDeque<>();

    /** 正在工作的线程数（含自己），用于判断是否还有闲置线程可以拿来分裂 */
    private final AtomicInteger busyWorkers = new AtomicInteger();

    /** 累计下载字节（诊断用；跨阶段累加） */
    private final AtomicLong totalDownloaded = new AtomicLong();

    private volatile ExecutorService pool;
    private volatile int poolThreads;
    private final Object poolLock = new Object();

    private PclDownloadManager() {
    }

    // ---- 对外 API ----

    /**
     * 提交一批任务并等待全部完成（可与其它阶段的 runAll 并发调用）。
     *
     * @param tasks     待下载文件；调用方须已过滤掉本地已存在的文件
     * @param cancel    取消标志，可为 null
     * @param timeoutMs 本批超时；0 或负数表示不限时
     * @return true = 本批全部成功
     */
    public boolean runAll(List<PclFileTask> tasks, AtomicBoolean cancel, long timeoutMs) {
        if (tasks == null || tasks.isEmpty()) return true;
        PclHttp.init();
        ensurePool(DownloadSettings.getPclThreads());

        // 只保留尚未进入终态的任务
        List<PclFileTask> pending = new ArrayList<>(tasks.size());
        for (PclFileTask t : tasks) {
            if (t.isFinished()) continue;
            pending.add(t);
        }
        if (pending.isEmpty()) return allOk(tasks);

        for (PclFileTask t : pending) {
            if (!t.started) createFirstPiece(t);
        }

        long deadline = timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : Long.MAX_VALUE;
        try {
            while (true) {
                if (anyCancelled(cancel)) break;
                if (System.currentTimeMillis() > deadline) {
                    for (PclFileTask t : pending) finishTask(t, false);
                    break;
                }
                boolean allDone = true;
                for (PclFileTask t : pending) {
                    if (!t.isFinished()) {
                        allDone = false;
                        break;
                    }
                }
                if (allDone) break;
                Thread.sleep(20L); // 与 PCL 的 20ms 调度粒度一致
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (anyCancelled(cancel)) {
            for (PclFileTask t : pending) finishTask(t, false);
        }
        return allOk(tasks);
    }

    /** 累计下载字节（诊断用） */
    public long getTotalDownloadedBytes() {
        return totalDownloaded.get();
    }

    /** 当前正在工作的线程数（诊断用） */
    public int getBusyWorkers() {
        return busyWorkers.get();
    }

    /** 当前并发配额 */
    public int getConcurrency() {
        return poolThreads;
    }

    /**
     * 按当前设置调整并发（设置页改完立即调用）。
     *
     * <p>换池时会先关闭旧池；旧池的工作线程在 {@code downloadPiece} 返回后自行退出，
     * 不会出现「新旧两批工作线程同时消费同一个队列」导致并发翻倍的问题。
     */
    public void applyConcurrency() {
        // 启动时最先调用到这里：init() 必须在任何 HttpURLConnection 创建前跑，
        // 否则 http.maxConnections 已被 KeepAliveCache 固化成 JDK 默认的 5
        PclHttp.init();
        PclHttp.refreshConnectionLimit();
        ensurePool(DownloadSettings.getPclThreads());
    }

    // ---- 分段判据 ----

    /**
     * 是否放弃分段（整文件单连接）。三个条件任一成立即不分段（PCL {@code NetFile.IsNoSplit}）：
     * <ul>
     *   <li>大小未知（{@code <=0}）：无从切分；</li>
     *   <li>小于 {@link #NO_SPLIT_THRESHOLD}：切了不划算，PCL 对小文件走内存缓冲；</li>
     *   <li><b>全部候选源都在单线程黑名单里</b>（{@link HostPolicy}，如 bmclapi / github）：
     *       这些源多连反而触发风控。PCL 同样把 bmclapi 排除在分段之外（{@code ModNet.vb:853-854}），
     *       而旧引擎没有这一步 —— 它把 bmclapi 的文件切成 8 段，
     *       而 bmclapi 的资产端点是 302 分发的，等于同时撞 8 个不同的第三方节点。</li>
     * </ul>
     */
    static boolean shouldSkipSplit(long size, List<String> candidates) {
        if (size <= 0 || size < NO_SPLIT_THRESHOLD) return true;
        if (candidates == null || candidates.isEmpty()) return true;
        for (String url : candidates) {
            String host = hostOf(url);
            if (host != null && !HostPolicy.isSingleThreadHost(host)) {
                return false; // 存在可分段的源
            }
        }
        return true;
    }

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean allOk(List<PclFileTask> tasks) {
        for (PclFileTask t : tasks) {
            if (!t.success) return false;
        }
        return true;
    }

    private static boolean anyCancelled(AtomicBoolean cancel) {
        return cancel != null && cancel.get();
    }

    // ---- 线程池 ----

    private void ensurePool(int threads) {
        synchronized (poolLock) {
            int want = Math.max(1, threads);
            if (pool != null && poolThreads == want && !pool.isShutdown()) return;
            ExecutorService old = pool;
            poolThreads = want;
            ExecutorService fresh = Executors.newFixedThreadPool(want, r -> {
                Thread t = new Thread(r, "pcl-dl-worker");
                t.setDaemon(true);
                // PCL 把下载线程设为 BelowNormal（ModNet.vb:871），避免抢游戏/UI 的 CPU
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });
            pool = fresh;
            if (old != null) old.shutdown();
            for (int i = 0; i < want; i++) {
                fresh.submit(() -> workerLoop(fresh));
            }
        }
    }

    // ---- 工作线程 ----

    /**
     * 工作线程主循环：不断认领段，直到本池被替换/关闭。
     *
     * @param myPool 本线程所属的池；用于判断自己是否已被淘汰
     *               （不能读共享的 {@code pool} 字段，否则换池后旧线程会继续消费新池的队列）
     */
    private void workerLoop(ExecutorService myPool) {
        while (!myPool.isShutdown()) {
            PclPiece piece = null;
            try {
                piece = pollPiece();
                if (piece == null) {
                    Thread.sleep(5L);
                    continue;
                }
                busyWorkers.incrementAndGet();
                try {
                    downloadPiece(piece);
                } finally {
                    busyWorkers.decrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                // 工作线程绝不能因单段异常退出，否则并发度永久下降
                if (piece != null) {
                    piece.claimed.set(false);
                    if (!piece.task.isFinished() && !myPool.isShutdown()) {
                        ready.addFirst(piece);
                    }
                }
            }
        }
    }

    /** 认领一个未被认领的段（出队即独占） */
    private PclPiece pollPiece() {
        while (true) {
            PclPiece p = ready.pollFirst();
            if (p == null) return null;
            if (p.finished || p.task.isFinished()) continue;
            if (p.claimed.compareAndSet(false, true)) return p;
        }
    }

    // ---- 段下载核心 ----

    private void downloadPiece(PclPiece piece) {
        PclFileTask task = piece.task;

        // 本条线程可用 → 若池里还有闲置线程，就按 PCL 规则把本段尾巴切出去
        maybeSplit(piece);

        if (task.isFinished() || piece.task.isFinished()) {
            piece.claimed.set(false);
            return;
        }

        long from = piece.absolutePos();
        if (from > piece.end) {
            onPieceComplete(piece);
            return;
        }

        String url = nextCandidate(task, piece);
        if (url == null) {
            finishTask(task, false);
            piece.claimed.set(false);
            return;
        }

        boolean single = task.isSingleStream() || task.rangeUnsupported;
        // 可分段的文件一律带「闭合 Range」：只取本段需要的区间，服务端不会整份回传。
        // （单连接路径不带 Range，整文件一次下完。）
        String rangeHeader = single ? null : ("bytes=" + from + "-" + piece.end);

        HttpURLConnection conn = null;
        InputStream in = null;
        boolean keepAlive = false;
        try {
            long connectStart = System.currentTimeMillis();
            conn = PclHttp.openGet(url, CONNECT_TIMEOUT_MS, readTimeoutMs(task), rangeHeader);
            int code = conn.getResponseCode();
            task.lastConnectMs = System.currentTimeMillis() - connectStart;

            // 手动跟随重定向：Range 语义不能被自动跳转破坏（且要能识别最终落点）
            int hops = 0;
            while (code >= 300 && code < 400) {
                String location = conn.getHeaderField("Location");
                PclHttp.abort(conn);
                conn = null;
                if (location == null || location.isBlank() || ++hops > PclHttp.MAX_REDIRECT_HOPS) {
                    failPiece(piece, I18N.get("pcl.t0001"));
                    return;
                }
                url = new URL(new URL(url), PclHttp.sanitizeRedirect(location)).toString();
                conn = PclHttp.openGet(url, CONNECT_TIMEOUT_MS, readTimeoutMs(task), rangeHeader);
                code = conn.getResponseCode();
            }

            if (code == 404 || code == 410) {
                // 该源确定没有这个对象：记健康表并换源
                SourceHealth.getInstance().record404(url, code, I18N.get("pcl.t0002"));
                PclHttp.abort(conn);
                conn = null;
                failPiece(piece, "HTTP " + code);
                return;
            }
            if (code != 200 && code != 206) {
                PclHttp.abort(conn);
                conn = null;
                failPiece(piece, "HTTP " + code);
                return;
            }

            // 源不支持 Range：对带 Range 的请求回了 200 全量（对任意起始点都成立）
            if (rangeHeader != null && code == 200) {
                PclHttp.abort(conn);
                conn = null;
                task.rangeUnsupported = true;
                restartSingleStream(task);
                piece.claimed.set(false);
                return;
            }

            long contentLength = conn.getContentLengthLong();
            // 分段长度自检：206 的 Content-Length 必须等于「本段区间长度」。
            // 注意不能写成「文件总长 − 起始点」——那个等式只在「段终点=文件末尾」时成立，
            // 而分裂会产出中间段（这正是 2026-10-22 实测中 4 个 ≥1MB 文件报
            // 「分段长度不符」的原因）。
            if (code == 206 && contentLength > 0) {
                long expected = piece.end - from + 1;
                if (contentLength != expected) {
                    PclHttp.abort(conn);
                    conn = null;
                    failPiece(piece, I18N.get("pclm.r0001", expected, contentLength));
                    return;
                }
            }

            in = conn.getInputStream();

            if (single) {
                downloadWholeFile(task, piece, in, contentLength);
            } else {
                downloadSegment(piece, in);
            }

            if (task.isFinished() || piece.task.isFinished()) {
                // 任务在下载途中被判失败（例如全源耗尽）：本段结果作废
                PclHttp.abort(conn);
                conn = null;
                piece.claimed.set(false);
                return;
            }

            keepAlive = true;
            SourceHealth.getInstance().recordSuccess(url);
            SourceHealth.getInstance().recordSuccessCost(url, System.currentTimeMillis() - connectStart);
            task.sourceFailCount.set(0);
            onPieceComplete(piece);
        } catch (InterruptedIOException timeout) {
            // 读超时 = 数据完全不动 → 换源（PCL「操作超时，无数据」）
            SourceHealth.getInstance().recordSlow(url, I18N.get("pcl.t0003"));
            failPiece(piece, I18N.get("pcl.t0004"));
        } catch (IOException io) {
            failPiece(piece, I18N.get("pcl.p0017", io.getMessage()));
        } catch (Exception other) {
            failPiece(piece, I18N.get("pcl.p0018", other));
        } finally {
            if (keepAlive) {
                // 关键：成功路径归还连接供复用，不回笼重连
                PclHttp.finishKeepAlive(in);
            } else {
                closeQuietly(in);
                PclHttp.abort(conn);
            }
        }
    }

    /** 段级下载：写分片临时文件，支持断点续传（失败后从 {@code start+done} 继续） */
    private void downloadSegment(PclPiece piece, InputStream in) throws IOException {
        Path tempFile = piece.tempFile;
        Files.createDirectories(tempFile.getParent());
        long already = piece.done.get();
        try (RandomAccessFile raf = new RandomAccessFile(tempFile.toFile(), "rw")) {
            if (already <= 0) {
                raf.setLength(0);        // 首段从上一次运行的残留里开始：清掉
            }
            raf.seek(already);

            byte[] buffer = new byte[READ_BUFFER];
            long lastDataAt = System.currentTimeMillis();
            long pos = piece.absolutePos();
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (piece.task.isFinished()) return;
                long room = piece.end - pos + 1;
                if (room <= 0) break;
                int writeLen = (int) Math.min(read, room);
                raf.write(buffer, 0, writeLen);
                pos += writeLen;
                piece.done.addAndGet(writeLen);
                piece.task.writtenBytes.addAndGet(writeLen);
                totalDownloaded.addAndGet(writeLen);
                com.example.starlight.util.NetTrafficStats.recordBytes(writeLen);
                long now = System.currentTimeMillis();
                checkSlow(lastDataAt, now, writeLen);
                lastDataAt = now;
            }
        }
    }

    /** 整文件单连接下载：小文件走内存缓冲 + 边下边算哈希；大文件走临时文件 */
    private void downloadWholeFile(PclFileTask task, PclPiece piece, InputStream in,
                                   long contentLength) throws IOException {
        MessageDigest digest = newDigest();
        long expected = task.size > 0 ? task.size : contentLength;
        boolean memory = expected > 0 && expected <= MEMORY_BUFFER_LIMIT;

        Files.createDirectories(task.target.getParent());
        Path tempFile = memory ? null : uniqueTemp(task.target);
        ByteArrayOutputStream memoryOut = memory
                ? new ByteArrayOutputStream(expected > 0 ? (int) expected : 8192) : null;
        OutputStream out = memory ? memoryOut
                : Files.newOutputStream(tempFile, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);

        long total = 0L;
        long lastDataAt = System.currentTimeMillis();
        boolean abandoned = false;
        try (InputStream stream = in) {
            byte[] buffer = new byte[READ_BUFFER];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                if (task.isFinished()) {
                    // 任务已被判失败（例如全源耗尽）：本段结果作废，不能留下半截临时文件
                    abandoned = true;
                    break;
                }
                out.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                total += read;
                piece.done.addAndGet(read);
                totalDownloaded.addAndGet(read);
                com.example.starlight.util.NetTrafficStats.recordBytes(read);
                long now = System.currentTimeMillis();
                checkSlow(lastDataAt, now, read);
                lastDataAt = now;
            }
        } finally {
            if (!memory) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
        if (abandoned) {
            if (tempFile != null) Files.deleteIfExists(tempFile);
            return;
        }

        if (total == 0L) {
            if (tempFile != null) Files.deleteIfExists(tempFile);
            throw new IOException(I18N.get("pcl.t0005"));
        }
        if (expected > 0 && total != expected) {
            if (tempFile != null) Files.deleteIfExists(tempFile);
            throw new IOException(I18N.get("pclm.r0002", expected, total));
        }
        // 内容校验：哈希在下载时已算好，这是唯一一次校验，不需要重读文件
        if (task.sha1 != null && !task.sha1.isBlank()) {
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equalsIgnoreCase(task.sha1)) {
                if (tempFile != null) Files.deleteIfExists(tempFile);
                throw new IOException(I18N.get("pcl.t0006"));
            }
        }

        if (memory) {
            Files.write(task.target, memoryOut.toByteArray());
        } else {
            Files.move(tempFile, task.target, StandardCopyOption.REPLACE_EXISTING);
        }
        task.writtenBytes.addAndGet(total);
    }

    /** 慢速判定（PCL {@code ModNet.vb:1025-1028}）：包间隔 &gt;5s 且该间隔内不足 1KB/s 才断开 */
    private void checkSlow(long lastDataAt, long now, int justRead) throws IOException {
        long gap = now - lastDataAt;
        if (gap > SLOW_GAP_MS && gap > justRead) {
            throw new IOException(I18N.get("pclm.r0003", gap, justRead));
        }
    }

    /** 段完成：段计数减一，减到 0 的线程负责收尾（合并或直接成功） */
    private void onPieceComplete(PclPiece piece) {
        PclFileTask task = piece.task;
        piece.claimed.set(false);
        if (task.isFinished() || piece.finished) return;

        piece.finished = true;
        int left = task.remainingPieces.decrementAndGet();
        if (left > 0) return;

        if (task.isSingleStream() || task.rangeUnsupported) {
            finishTask(task, true);          // 整文件已在下载时校验通过
        } else {
            mergeAndFinish(task);
        }
    }

    /** 分段任务：按序合并分片，并在合并过程中算哈希（不再额外重读整文件） */
    private void mergeAndFinish(PclFileTask task) {
        Path merged = null;
        try {
            Files.createDirectories(task.target.getParent());
            merged = uniqueTemp(task.target);
            MessageDigest digest = newDigest();
            long total = 0L;

            List<PclPiece> ordered = new ArrayList<>(task.pieces);
            ordered.sort((a, b) -> Long.compare(a.start, b.start));

            try (OutputStream out = Files.newOutputStream(merged,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[64 * 1024];
                for (PclPiece p : ordered) {
                    if (p.tempFile == null || !Files.isRegularFile(p.tempFile)) {
                        throw new IOException(I18N.get("pcl.p0019", p.index));
                    }
                    try (InputStream fin = Files.newInputStream(p.tempFile)) {
                        int n;
                        while ((n = fin.read(buffer)) != -1) {
                            out.write(buffer, 0, n);
                            digest.update(buffer, 0, n);
                            total += n;
                        }
                    }
                }
            }

            if (task.size > 0 && total != task.size) {
                task.lastError = I18N.get("pclm.r0004", task.size, total);
                Files.deleteIfExists(merged);
                finishTask(task, false);
                return;
            }
            if (task.sha1 != null && !task.sha1.isBlank()) {
                String actual = HexFormat.of().formatHex(digest.digest());
                if (!actual.equalsIgnoreCase(task.sha1)) {
                    task.lastError = I18N.get("pcl.t0007");
                    Files.deleteIfExists(merged);
                    finishTask(task, false);
                    return;
                }
            }
            Files.move(merged, task.target, StandardCopyOption.REPLACE_EXISTING);
            task.writtenBytes.addAndGet(total);
            finishTask(task, true);
        } catch (Exception e) {
            task.lastError = I18N.get("pcl.p0020", e);
            if (merged != null) {
                try {
                    Files.deleteIfExists(merged);
                } catch (IOException ignored) {
                }
            }
            finishTask(task, false);
        }
    }

    /** 置为终态并清理分片（幂等；重复调用不会重复计数） */
    private void finishTask(PclFileTask task, boolean ok) {
        synchronized (task) {
            if (task.isFinished()) return;
            task.success = ok;
            task.failed = !ok;
        }
        if (!ok) {
            log.warn("PCL 引擎下载失败: {}（{}）", task.label,
                    task.lastError == null ? I18N.get("pcl.t0016") : task.lastError);
        }
        for (PclPiece p : task.pieces) {
            if (p.tempFile != null) {
                try {
                    Files.deleteIfExists(p.tempFile);
                } catch (IOException ignored) {
                }
            }
        }
        task.pieces.clear();
        ready.removeIf(p -> p.task == task);
    }

    // ---- 分裂 ----

    /** 按 PCL 规则判断是否分裂；仅当池中确有闲置线程时执行 */
    private void maybeSplit(PclPiece piece) {
        PclFileTask task = piece.task;
        if (task.isSingleStream() || task.rangeUnsupported || task.isFinished()) return;
        // busyWorkers 已含本线程：只有当还有别的线程闲着时，切出来的尾巴才有人下
        if (busyWorkers.get() >= poolThreads) return;

        long remaining = piece.end - piece.absolutePos() + 1;
        if (remaining <= PIECE_LIMIT_BYTES) return;

        long tail = (long) (remaining * SPLIT_TAIL_RATIO);   // 取最后 40%
        if (tail <= 0 || remaining - tail <= 0) return;

        long newStart = piece.end - tail + 1;
        long oldEnd = piece.end;
        piece.end = newStart - 1;

        PclPiece tailPiece = new PclPiece(task, task.nextPieceIndex(), newStart, oldEnd);
        task.pieces.add(tailPiece);
        task.countPiece();
        task.remainingPieces.incrementAndGet();
        ready.addFirst(tailPiece);
    }

    // ---- 首段与重启 ----

    private void createFirstPiece(PclFileTask task) {
        long end = task.size > 0 ? task.size - 1 : Long.MAX_VALUE;
        PclPiece first = new PclPiece(task, task.nextPieceIndex(), 0, end);
        task.pieces.add(first);
        task.countPiece();
        task.remainingPieces.set(1);
        task.started = true;
        ready.addFirst(first);
    }

    /** 该源不支持 Range：丢弃分段进度，退回整文件单连接重来（每个任务只做一次） */
    private void restartSingleStream(PclFileTask task) {
        synchronized (task) {
            if (task.rangeRestarted) return;
            task.rangeRestarted = true;
        }
        for (PclPiece p : task.pieces) {
            if (p.tempFile != null) {
                try {
                    Files.deleteIfExists(p.tempFile);
                } catch (IOException ignored) {
                }
            }
        }
        task.pieces.clear();
        ready.removeIf(p -> p.task == task);
        task.remainingPieces.set(0);
        createFirstPiece(task);
    }

    // ---- 源选择与失败处理 ----

    /** 选下一个可用候选源；尝试次数用尽或已被健康表拉黑就换下一个，全部用尽返回 null */
    private String nextCandidate(PclFileTask task, PclPiece piece) {
        while (piece.sourceIdx < task.candidates.size()) {
            String url = task.candidates.get(piece.sourceIdx);
            boolean exhausted = piece.attempts.get() >= RETRY_PER_SOURCE;
            if (!exhausted && !SourceHealth.getInstance().isBlocked(url)) {
                return url;
            }
            piece.sourceIdx++;
            piece.attempts.set(0);
        }
        return null;
    }

    private void failPiece(PclPiece piece, String reason) {
        PclFileTask task = piece.task;
        if (task.isFinished()) {
            piece.claimed.set(false);
            return;
        }
        task.lastError = reason;
        piece.attempts.incrementAndGet();
        task.sourceFailCount.incrementAndGet();
        DebugLog.http(false, null, task.label, 0, 0L, "[PCL引擎] " + reason, -1L);
        log.debug("PCL 引擎重试: {} ({}) 源[{}] 第 {} 次", task.label,
                reason, piece.sourceIdx, piece.attempts.get());
        piece.claimed.set(false);
        // 重新入队；是否换源由下次的 nextCandidate 决定，全部用尽时它会返回 null 并判失败
        reEnqueueWithBackoff(piece);
    }

    /** 失败后退避重入队（{@code 500ms × 尝试次数}，上限 5s，对齐 PCL 的重试退避） */
    private void reEnqueueWithBackoff(PclPiece piece) {
        int attempt = Math.max(1, piece.attempts.get());
        long backoff = Math.min(5_000L, RETRY_BACKOFF_BASE_MS * attempt);
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!piece.task.isFinished() && (pool == null || !pool.isShutdown())) {
            ready.addFirst(piece);
        }
    }

    /** 自适应读超时：{@code min(max(connectAvg,15s) × (1+该源失败数), 30s)}（PCL {@code ModNet.vb:909}） */
    private int readTimeoutMs(PclFileTask task) {
        long connectAvg = task.lastConnectMs > 0 ? task.lastConnectMs : TIMEOUT_FLOOR_MS;
        long base = Math.max(TIMEOUT_FLOOR_MS, connectAvg);
        long scaled = base * (1L + Math.max(0, task.sourceFailCount.get()));
        return (int) Math.min(TIMEOUT_CEIL_MS, Math.max(TIMEOUT_FLOOR_MS, scaled));
    }

    // ---- 工具 ----

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(I18N.get("pcl.t0008"), e);
        }
    }

    private static Path uniqueTemp(Path target) {
        return target.resolveSibling(target.getFileName() + ".pcl-tmp-"
                + Thread.currentThread().getId() + "-" + System.nanoTime());
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) return;
        try {
            in.close();
        } catch (IOException ignored) {
        }
    }

    // ---- 段 ----

    /**
     * 一个字节区间（PCL 的 {@code DownloadStart~DownloadEnd} 概念）。
     *
     * <p>段被认领后独占：只有持有它的线程写它，因此 {@link #done} 单调递增，
     * 也就是断点续传的起点 —— 失败重试从 {@code start + done} 继续，而不是从 0 重来。
     */
    static final class PclPiece {
        final PclFileTask task;
        final int index;

        /** 段起点（固定） */
        final long start;

        /** 段终点（含）；分裂后会缩小 */
        volatile long end;

        /** 本段已下字节（从 start 起连续） */
        final AtomicLong done = new AtomicLong();

        /** 是否已被某线程认领 */
        final AtomicBoolean claimed = new AtomicBoolean(false);

        /** 是否已完成 */
        volatile boolean finished = false;

        /** 当前候选源下标 */
        volatile int sourceIdx = 0;

        /** 在本候选源上的连续尝试次数 */
        final AtomicInteger attempts = new AtomicInteger();

        /** 分片临时文件；单连接路径为 null */
        final Path tempFile;

        PclPiece(PclFileTask task, int index, long start, long end) {
            this.task = task;
            this.index = index;
            this.start = start;
            this.end = end;
            this.tempFile = task.isSingleStream()
                    ? null
                    : task.target.resolveSibling(task.target.getFileName() + ".pcl." + index + ".part");
        }

        /** 下一个待下载的绝对偏移 */
        long absolutePos() {
            return start + done.get();
        }
    }
}