package com.example.starlight.download.pcl;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PCL 引擎的单个下载任务（一个目标文件）。
 *
 * <p>对标 PCL2 的 {@code NetFile}（{@code ModNet.vb}）：一个文件对应一个任务，
 * 任务内部再拆成若干 {@link PclPiece} 段由全局线程池并发下载，全部完成后合并。
 *
 * <h3>与旧引擎 {@code AssetTask} 的区别</h3>
 * <ul>
 *   <li><b>不分段的判据是「值不值得分」</b>：小于 {@link PclDownloadManager#NO_SPLIT_THRESHOLD}
 *       或大小未知、或该 host 属于单线程黑名单时，整文件单连接一次下完；</li>
 *   <li><b>哈希只算一次</b>：单连接路径边下边算（内存缓冲 + Digest），分段路径在合并时顺带算，
 *       不存在旧引擎「临时文件算一遍、移动后再对目标文件算一遍」的两次整文件重读。</li>
 * </ul>
 */
public final class PclFileTask {

    /** 可读名（日志 / 诊断；通常用虚拟路径或 maven 路径） */
    public final String label;

    /** 落盘目标路径 */
    public final Path target;

    /** 期望字节数；{@code -1} 表示未知（未知即不分段） */
    public final long size;

    /** 期望 SHA-1；可为 null（此时不做内容校验，只校验长度） */
    public final String sha1;

    /** 候选源地址（按优先级排列，最后一个通常是官方源） */
    public final List<String> candidates;

    // ---- 以下为引擎内部状态（同包可见） ----

    /** 本任务的所有段（段类型定义在调度器内部，见 {@link PclDownloadManager.PclPiece}） */
    final List<PclDownloadManager.PclPiece> pieces = new CopyOnWriteArrayList<>();

    /** 尚未完成的段数（减到 0 的那个线程负责合并） */
    final AtomicInteger remainingPieces = new AtomicInteger();

    /** 已成功写盘的字节数（进度统计用） */
    final AtomicLong writtenBytes = new AtomicLong();

    /** 当前候选源游标（换源时递增） */
    final AtomicInteger sourceCursor = new AtomicInteger();

    /** 每个候选源的连续失败次数（用于自适应超时：min(max(connectAvg,15s)*(1+failCount),30s)） */
    final AtomicInteger sourceFailCount = new AtomicInteger();

    /** 本任务最近一次建连耗时（ms），未连接过为 -1 */
    volatile long lastConnectMs = -1L;

    /** 是否已成功完成（文件就绪且校验通过） */
    volatile boolean success = false;

    /** 是否已判定失败（所有候选源耗尽或超时放弃） */
    volatile boolean failed = false;

    /** 最近一次失败原因（诊断 / 日志用；成功时为 null） */
    volatile String lastError = null;

    /** 是否已开始过（用于调度器区分 WAITING / DOWNLOADING） */
    volatile boolean started = false;

    /** 该源不支持 Range（对带 Range 的请求回了 200 全量）→ 本任务放弃分段，改整文件单连接 */
    volatile boolean rangeUnsupported = false;

    /** 是否已因 Range 不支持而回退过一次（避免反复重启） */
    volatile boolean rangeRestarted = false;

    /** 分段序号计数器（分裂/重启时取新段号，保证分片文件名不冲突） */
    private final AtomicInteger pieceSeq = new AtomicInteger();

    /** 累计创建过的段数（诊断用：区分「真的分段了」与「单连接下完」） */
    private final AtomicInteger piecesCreated = new AtomicInteger();

    /** 取下一个段号 */
    int nextPieceIndex() {
        return pieceSeq.getAndIncrement();
    }

    /** 记录新建了一个段（由调度器调用） */
    void countPiece() {
        piecesCreated.incrementAndGet();
    }

    /** 累计创建过的段数：1 = 单段；&gt;1 = 确实走了分段并发 */
    public int getPiecesCreated() {
        return piecesCreated.get();
    }

    /** 分段临时目录（仅分段任务需要） */
    volatile Path tempDir;

    public PclFileTask(String label, Path target, long size, String sha1, List<String> candidates) {
        this.label = label;
        this.target = target;
        this.size = size;
        this.sha1 = sha1;
        this.candidates = candidates == null ? List.of() : new ArrayList<>(candidates);
    }

    /** 是否已完成（成功或失败都算终态） */
    public boolean isFinished() {
        return success || failed;
    }

    /** 是否成功（文件就绪且校验通过） */
    public boolean isSuccess() {
        return success;
    }

    /** 是否失败 */
    public boolean isFailed() {
        return failed;
    }

    /** 是否采用单连接整文件下载（不分段） */
    boolean isSingleStream() {
        return PclDownloadManager.shouldSkipSplit(size, candidates);
    }

    /** 已写入字节 */
    public long getWrittenBytes() {
        return writtenBytes.get();
    }

    /** 最近一次失败原因（成功时为 null；诊断 / 日志用） */
    public String getLastError() {
        return lastError;
    }

    /** 本任务拆出的段数（诊断用） */
    public int getPieceCount() {
        return pieces.size();
    }
}