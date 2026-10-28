package com.example.starlight.download.mod;


import com.example.starlight.util.I18N;
import com.example.starlight.download.DownloadSettings;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单文件任务（对标 PCL NetFile）。
 *
 * <p>持有：一组有序 {@link DownloadSource}（由 {@link DownloadSourceChain} 管理）+
 * {@link List DownloadPiece} 分块列表（PCL 用链表，Java 用 List）。
 *
 * <p><b>状态机</b>：WAITING → DOWNLOADING → MERGING → SUCCESS / FAILED / CANCELLED。
 *
 * <p><b>首线程探测</b>：第一个线程不带 Range，直接 GET 全量响应，从 Content-Length 确定总大小；
 * 先做大小校验（&lt; MinSize 或 ≠ 期望大小直接抛错切源）。后续线程一律带 Range。
 *
 * <p><b>动态再分裂</b>：调度器发现全局速度不达标时调用 {@link #tryBeginThread()}——
 * 在剩余量最大且未完成 &gt; 最小碎片（{@link DownloadSettings#getPieceLimitBytes()}，
 * 默认 256KB）的分块上，从已完成点之后按分裂点（{@link DownloadSettings#getSplitRatio()}，
 * 默认 60%）处切出新分块。
 *
 * <p><b>合并</b>：单线程串行拼接；合并前校验 Σpiece.done == fileSize（未知大小除外）；
 * 先写 {@code dest.tmp}，{@link FileVerifier} 校验通过后 {@link java.nio.file.Files#move} ATOMIC_MOVE
 * 替换目标，避免半成品目标文件。
 *
 * <p><b>合并重试</b>：最多 3 次、500ms × 次数退避；3 次后删除残文件，把当前源标记失败，
 * 整体换源重下（Retried 只允许整体重试一轮）。
 *
 * <p>分块临时文件落 {@code {gameDir}/.download-cache/{fileHash}/piece-{序号}.part}。
 */
public final class DownloadFileTask {

    /**
     * 二次分裂最小碎片（256KB，PCL FilePieceLimit）。
     * <p>实际生效值取 {@link DownloadSettings#getPieceLimitBytes()}（设置页可调）；
     * 本常量仅作为出厂默认值的说明与兜底引用。
     */
    public static final long FILE_PIECE_LIMIT = 256L * 1024L;

    /**
     * 分裂点位置：大块剩余量的 60% 处（PCL 同款）。
     * <p>实际生效值取 {@link DownloadSettings#getSplitRatio()}（设置页可调）。
     */
    public static final double SPLIT_RATIO = 0.6;

    /**
     * 才值得分块的门槛：4MB（沿用现有 Java 引擎 MIN_CHUNK_SIZE）。
     * <p>实际生效值取 {@link DownloadSettings#getMultithreadThresholdBytes()}（设置页可调）。
     */
    public static final long MULTITHREAD_THRESHOLD = 4L * 1024L * 1024L;

    /** 文件大小未知的标记 */
    public static final long SIZE_UNKNOWN = -1L;

    /** 任务状态 */
    public enum State {
        WAITING, DOWNLOADING, MERGING, SUCCESS, FAILED, CANCELLED
    }

    /** 目标文件路径 */
    private final Path target;

    /** 游戏根目录（.download-cache 落点） */
    private final Path gameDir;

    /** fileHash（.download-cache/{fileHash} 目录名；由上层按 URL/校验信息生成） */
    private final String fileHash;

    /** 期望大小（-1 = 不校验大小） */
    private final long expectedSize;

    /** 最小大小（首线程 Content-Length 先卡这个） */
    private final long minSize;

    /** 期望 Hash（MD5 32 / SHA1 40，自动识别）；null 不校验 */
    private final String expectedHash;

    /** 源链 */
    private final DownloadSourceChain chain;

    /** 分块列表（同步包装：播种循环与下载线程并发增删） */
    private final List<DownloadPiece> pieces = Collections.synchronizedList(new ArrayList<>());

    /** 分块临时目录 */
    private final Path partDir;

    private volatile State state = State.WAITING;
    private volatile long fileSize = SIZE_UNKNOWN;
    /** 已整体重试过一轮（PCL Retried） */
    private volatile boolean retried = false;
    /** 任务级累计已下字节（用于进度与全局速度统计） */
    private final AtomicLong totalBytes = new AtomicLong(0);
    /** 仍在跑的分块线程数 */
    private final AtomicInteger activePieceThreads = new AtomicInteger(0);
    /** 准备中（刚建未起）的分块数，播种循环用 */
    private final AtomicInteger preparingPieces = new AtomicInteger(0);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile Throwable lastError;

    private final DownloadCoordinator coordinator;

    public DownloadFileTask(Path target, Path gameDir, String fileHash,
                            long expectedSize, long minSize, String expectedHash,
                            DownloadSourceChain chain, DownloadCoordinator coordinator) {
        this.target = target;
        this.gameDir = gameDir;
        this.fileHash = fileHash == null || fileHash.isBlank()
                ? Integer.toHexString(target.hashCode()) : fileHash;
        this.expectedSize = expectedSize;
        this.minSize = minSize;
        this.expectedHash = expectedHash;
        this.chain = chain;
        this.coordinator = coordinator;
        this.partDir = gameDir.resolve(".download-cache").resolve(this.fileHash);
    }

    public Path getTarget() {
        return target;
    }

    public State getState() {
        return state;
    }

    public long getFileSize() {
        return fileSize;
    }

    public long getTotalBytes() {
        return totalBytes.get();
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) return;
        // 立即打断各分块：断开正在进行的连接，阻塞中的 read 马上返回，
        // 不用等读超时/当前分块下完（「取消下载」要立刻生效）
        synchronized (pieces) {
            for (DownloadPiece p : pieces) {
                p.abort();
            }
        }
    }

    public Throwable getLastError() {
        return lastError;
    }

    public List<DownloadPiece> getPieces() {
        return Collections.unmodifiableList(pieces);
    }

    /** 聚合进度 0~1（未知大小按 totalBytes 保守估算） */
    public double progress() {
        long total = totalBytes.get();
        if (state == State.SUCCESS) return 1.0;
        if (fileSize > 0) return Math.max(0.0, Math.min(1.0, (double) total / fileSize));
        return 0.0;
    }

    // 主流程 //
    /** 由 DownloadCoordinator 提交到全局线程池执行 */
    public void run() {
        if (cancelled.get()) {
            state = State.CANCELLED;
            return;
        }
        state = State.DOWNLOADING;
        try {
            ensureFirstPiece();
            waitAllPieces();
            if (cancelled.get()) {
                state = State.CANCELLED;
                return;
            }
            mergeWithRetry();
            state = State.SUCCESS;
            cleanupPartDir();
        } catch (Exception e) {
            // 对标 PCL ModNet「文件下载失败，正在自动重试」：断流 / 静默断连是按连接随机的
            // （BMCLAPI 实测吐出 329B 后挂死），换全部源失败后清分块整任务重试一轮新连接，
            // 通常即可拿到完整文件；合并路径的重试语义见 mergeWithRetry（同一 retried 门闩，只重试一轮）
            if (!retried && !cancelled.get()) {
                retried = true;
                resetPieces();
                state = State.DOWNLOADING;
                try {
                    ensureFirstPiece();
                    waitAllPieces();
                    if (cancelled.get()) {
                        state = State.CANCELLED;
                        return;
                    }
                    mergeWithRetry();
                    state = State.SUCCESS;
                    cleanupPartDir();
                } catch (Exception e2) {
                    handleFailure(e2);
                }
            } else {
                handleFailure(e);
            }
        } finally {
            // 终态必须移出去重表：否则同路径重装/重试会被 submit 的 putIfAbsent 静默丢弃，
            // 调用方轮询自己的新实例永远 WAITING（表现为 0% 且零网络流量）
            if (state == State.SUCCESS || state == State.FAILED || state == State.CANCELLED) {
                coordinator.forget(this);
            }
        }
    }

    /** 首线程：不带 Range 探测总大小并下载首段 */
    private synchronized void ensureFirstPiece() throws IOException {
        if (!pieces.isEmpty()) return;
        Files.createDirectories(partDir);
        DownloadSource src = chain.nextSource(true);
        chain.advanceFirstThreadCursor();
        if (src == null) throw new IOException(I18N.get("mod.p0009", target));
        DownloadPiece piece = new DownloadPiece(0, 0, -1,
                partDir.resolve("piece-0.part"), true);
        piece.setMountedSource(src);
        pieces.add(piece);
        preparingPieces.incrementAndGet();
        startPiece(piece);
    }

    /**
     * 动态再分裂（由调度器 20ms 播种循环调用）。
     * 在剩余量最大且未完成 &gt; 最小碎片的分块上，从已完成点之后按分裂点比例切出新分块。
     *
     * @return 是否真的新起了线程
     */
    public boolean tryBeginThread() {
        if (state != State.DOWNLOADING || cancelled.get()) return false;
        if (pieces.isEmpty()) return false;

        synchronized (pieces) {
            // 找剩余量最大的未完成分块
            DownloadPiece biggest = null;
            long biggestRemaining = 0;
            for (DownloadPiece p : pieces) {
                if (p.getState() == DownloadPiece.State.DONE) continue;
                long rem = p.remaining(fileSize);
                if (rem > biggestRemaining) {
                    biggestRemaining = rem;
                    biggest = p;
                }
            }
            if (biggest == null || biggestRemaining <= DownloadSettings.getPieceLimitBytes()) return false;
            // 大小未知或过小不分裂
            if (fileSize > 0 && fileSize < DownloadSettings.getMultithreadThresholdBytes()) return false;

            long splitAt = biggest.getStart()
                    + biggest.getDone()
                    + (long) (biggestRemaining * DownloadSettings.getSplitRatio());
            if (splitAt >= (biggest.getEnd() < 0 ? Long.MAX_VALUE : biggest.getEnd())) return false;

            // 选源：命中 HostPolicy 只单线程 host 则不追加
            DownloadSource src = chain.nextSource(false);
            if (src == null) return false;
            if (HostPolicy.isSingleThreadHost(src.getHost())) return false;
            if (src.isSingleThread() || chain.isInSourcesOnce(src)) return false;

            int newIndex = pieces.size();
            long oldEnd = biggest.getEnd();
            biggest.setEnd(splitAt - 1);
            DownloadPiece fresh = new DownloadPiece(newIndex, splitAt, oldEnd,
                    partDir.resolve("piece-" + newIndex + ".part"), false);
            fresh.setMountedSource(src);
            pieces.add(fresh);
            preparingPieces.incrementAndGet();
            startPiece(fresh);
            return true;
        }
    }

    /** 起一个分块线程：交给协调器全局池，并计数 */
    private void startPiece(DownloadPiece piece) {
        coordinator.executePiece(this, piece);
    }

    /** 分块线程结束回调（成功/失败都算） */
    void onPieceFinished(DownloadPiece piece, Throwable error) {
        activePieceThreads.decrementAndGet();
        preparingPieces.decrementAndGet();
        synchronized (this) {
            notifyAll();
        }
    }

    /** 等待所有分块结束（含 FAILED 计数；异常由 Piece 抛到协调器后回写到这里） */
    private void waitAllPieces() throws InterruptedException, IOException {
        while (!cancelled.get()) {
            boolean allDone;
            synchronized (pieces) {
                allDone = true;
                for (DownloadPiece p : pieces) {
                    DownloadPiece.State s = p.getState();
                    if (s != DownloadPiece.State.DONE && s != DownloadPiece.State.FAILED && s != DownloadPiece.State.CANCELLED) {
                        allDone = false;
                        break;
                    }
                }
            }
            if (allDone) break;
            synchronized (this) {
                wait(200);
            }
        }
        // 任一 FAILED 分块 → 抛错走换源/整体重下
        synchronized (pieces) {
            for (DownloadPiece p : pieces) {
                if (p.getState() == DownloadPiece.State.FAILED) {
                    throw new IOException(I18N.get("mod.p0010", p));
                }
            }
        }
    }

    // 分块回调（DownloadPiece 调用） //
    /** 全局速度统计 + 任务进度上报 */
    void onBytes(int n) {
        totalBytes.addAndGet(n);
        coordinator.recordBytes(n);
    }

    /** 首线程探测到 Content-Length；做大小校验 */
    void onFileSizeKnown(long contentLength, DownloadSource src) throws IOException {
        if (contentLength > 0) {
            this.fileSize = contentLength;
            // 大小校验：< MinSize 或 ≠ 期望大小 → 切源
            if (contentLength < minSize
                    || (expectedSize >= 0 && contentLength != expectedSize)) {
                src.recordFailure(new IOException(I18N.get("mod.p0011", contentLength)));
                chain.markDisabled(src, I18N.get("mod.t0001"));
                throw new IOException(I18N.get("mod.p0012", target));
            }
        }
    }

    /** 服务器忽略 Range：该源降级单线程，重起首线程落到下一源 */
    void onRangeNotSupported(DownloadSource src) {
        chain.downgradeToSingleThread(src);
    }

    /** HTTP 错误码：按 §2.2 禁用源 */
    void onSourceHttpError(DownloadSource src, int status) {
        src.recordFailure(new IOException("HTTP " + status));
        if (src.shouldDisableOnHttpStatus(status)) {
            chain.markDisabled(src, "HTTP " + status);
        }
    }

    // 合并 //
    /** 合并：最多 N 次（默认 3）、退避 基数×次数（默认 500ms）；用尽后删残文件、换源整体重下一轮 */
    private void mergeWithRetry() throws IOException, InterruptedException {
        IOException lastErr = null;
        int maxAttempts = RetryPolicy.mergeMaxAttempts();
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                doMerge();
                return;
            } catch (IOException e) {
                lastErr = e;
                if (attempt < maxAttempts - 1) {
                    Thread.sleep(RetryPolicy.mergeBackoffMs(attempt));
                }
            }
        }
        // 3 次仍失败：删残文件，把当前源标记失败，整体换源重下一轮
        cleanupPartDir();
        DownloadSource cur = chain.nextSource(false);
        if (cur != null) chain.markDisabled(cur, I18N.get("mod.t0002"));
        if (!retried) {
            retried = true;
            resetPieces();
            state = State.DOWNLOADING;
            run(); // 整体重下一轮
            return;
        }
        throw lastErr != null ? lastErr : new IOException(I18N.get("mod.t0003"));
    }

    /** 单线程串行合并：Σdone==fileSize 校验 → 写 dest.tmp → FileVerifier 校验 → ATOMIC_MOVE */
    private void doMerge() throws IOException {
        // Σpiece.done == fileSize 校验（未知大小除外）
        if (fileSize > 0) {
            long sum = 0;
            synchronized (pieces) {
                for (DownloadPiece p : pieces) sum += p.getDone();
            }
            if (sum != fileSize) {
                throw new IOException(I18N.get("mod.p0013", sum + " != fileSize=" + fileSize));
            }
        }
        state = State.MERGING;
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.createDirectories(target.getParent());
        try (OutputStream out = Files.newOutputStream(tmp,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            synchronized (pieces) {
                // 按 start 排序串行拼接（PCL 链表顺序）
                List<DownloadPiece> ordered = new ArrayList<>(pieces);
                ordered.sort((a, b) -> Long.compare(a.getStart(), b.getStart()));
                for (DownloadPiece p : ordered) {
                    try (InputStream in = Files.newInputStream(p.getPartFile())) {
                        in.transferTo(out);
                    }
                }
            }
        }
        // 合并后完整 Hash 校验
        Optional<String> verifyErr = FileVerifier.verify(tmp, expectedSize, minSize, expectedHash);
        if (verifyErr.isPresent()) {
            Files.deleteIfExists(tmp);
            throw new IOException(I18N.get("mod.p0014", verifyErr.get()));
        }
        // 校验通过后原子替换目标
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 整体重置：清空分块，全部源降级单线程（Retried 已置位，只整体重下一轮） */
    private void resetPieces() {
        synchronized (pieces) {
            pieces.clear();
        }
        chain.resetToSingleThread();
        fileSize = SIZE_UNKNOWN;
        totalBytes.set(0);
    }

    private void handleFailure(Exception e) {
        this.lastError = e;
        state = cancelled.get() ? State.CANCELLED : State.FAILED;
    }

    private void cleanupPartDir() {
        try {
            if (Files.isDirectory(partDir)) {
                try (var stream = Files.walk(partDir)) {
                    stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
                }
            }
        } catch (IOException ignored) {
        }
    }

    @Override
    public String toString() {
        return "DownloadFileTask{" + target + ", state=" + state + ", size=" + fileSize
                + ", pieces=" + pieces.size() + ", progress=" + (int) (progress() * 100) + "%}";
    }
}
