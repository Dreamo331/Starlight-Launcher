package com.example.starlight.newui.framegen;


import com.example.starlight.util.I18N;
/**
 * 一次帧插值运行的状态快照。
 *
 * <p>不可变对象，由 {@link FramegenEngine#poll()} 从原生侧拉取后构造。
 * 字段含义见 {@link FramegenNative} 的槽位索引。
 *
 * <p><b>关于「渲染帧数」与「显示帧数」的区别</b>：
 * <ul>
 *   <li>{@link #renderedFrames} / {@link #renderFps} —— <b>渲染出来并提交呈现的帧数</b>，
 *       包含真实帧与插值帧。这是我们真正生成、送到交换链的画面数量。</li>
 *   <li>显示器实际刷新的帧数受 {@link #displayHz} 限制。60Hz 屏上即使渲染 120fps，
 *       眼睛也只能看到 60 帧。因此 {@code targetHz} 会被钳制并如实上报。</li>
 * </ul>
 * 开发者页的图表按需求显示的是<b>渲染帧数</b>，同时把显示上限画成参考线，
 * 两者对照才能看出「渲染出来了但屏幕吃不下」这种情况。
 */
public final class FramegenStats {

    /** 从未启动过 / 已停止 */
    public static final FramegenStats EMPTY = new FramegenStats();

    /** 从游戏窗口捕获到的累计帧数 */
    public final long capturedFrames;
    /** 渲染并提交呈现的累计帧数（真实帧 + 插值帧） */
    public final long renderedFrames;
    /** 其中属于原始捕获帧的部分 */
    public final long realFrames;
    /** 其中属于插值生成帧的部分 */
    public final long interpFrames;
    public final long droppedFrames;

    /** 捕获速率（原生侧按秒采样） */
    public final double captureFps;
    /** 渲染速率（真实帧 + 插值帧） */
    public final double renderFps;
    /** 每帧插值耗时（毫秒） */
    public final double interpMs;

    /** 呈现窗口所在显示器的实际刷新率 */
    public final double displayHz;
    /** 钳制后的实际输出节奏 */
    public final double targetHz;

    /** 实际生效的质量档位 */
    public final int quality;
    public final int rebuildCount;
    public final int width;
    public final int height;
    public final boolean running;
    public final boolean presenting;
    public final boolean closed;
    public final boolean overlayMode;

    // ---- 诊断：像素探测结果（-1 表示还没探测过）----
    /** 捕获帧的平均亮度（0..255）；接近 0 说明根本没捕到内容 */
    public final double captureAvgLuma;
    public final double captureMaxLuma;
    /** 捕获帧里非黑像素的百分比 */
    public final int captureNonBlackPct;
    /** 插值帧的平均亮度 */
    public final double interpAvgLuma;
    public final int interpNonBlackPct;
    public final boolean probeDone;

    // ---- 呈现管线健康度 ----
    public final long presentOk;
    public final long presentFail;
    public final long drawFail;
    public final long srvFail;
    public final long lastPresentHr;

    private FramegenStats() {
        this(0L, 0L, 0L, 0L, 0L, 0.0, 0.0, 0.0, 0.0, 0.0,
                0, 0, 0, 0, false, false, false, false,
                -1.0, -1.0, -1, -1.0, -1, false,
                0L, 0L, 0L, 0L, 0L);
    }

    private FramegenStats(long capturedFrames, long renderedFrames,
                          long realFrames, long interpFrames, long droppedFrames,
                          double captureFps, double renderFps, double interpMs,
                          double displayHz, double targetHz,
                          int quality, int rebuildCount, int width, int height,
                          boolean running, boolean presenting, boolean closed,
                          boolean overlayMode,
                          double captureAvgLuma, double captureMaxLuma,
                          int captureNonBlackPct, double interpAvgLuma,
                          int interpNonBlackPct, boolean probeDone,
                          long presentOk, long presentFail, long drawFail,
                          long srvFail, long lastPresentHr) {
        this.capturedFrames = capturedFrames;
        this.renderedFrames = renderedFrames;
        this.realFrames = realFrames;
        this.interpFrames = interpFrames;
        this.droppedFrames = droppedFrames;
        this.captureFps = captureFps;
        this.renderFps = renderFps;
        this.interpMs = interpMs;
        this.displayHz = displayHz;
        this.targetHz = targetHz;
        this.quality = quality;
        this.rebuildCount = rebuildCount;
        this.width = width;
        this.height = height;
        this.running = running;
        this.presenting = presenting;
        this.closed = closed;
        this.overlayMode = overlayMode;
        this.captureAvgLuma = captureAvgLuma;
        this.captureMaxLuma = captureMaxLuma;
        this.captureNonBlackPct = captureNonBlackPct;
        this.interpAvgLuma = interpAvgLuma;
        this.interpNonBlackPct = interpNonBlackPct;
        this.probeDone = probeDone;
        this.presentOk = presentOk;
        this.presentFail = presentFail;
        this.drawFail = drawFail;
        this.srvFail = srvFail;
        this.lastPresentHr = lastPresentHr;
    }

    /** 从原生 poll 的定长数组构造 */
    static FramegenStats fromSlots(double[] v) {
        return new FramegenStats(
                (long) v[FramegenNative.S_CAPTURED_FRAMES],
                (long) v[FramegenNative.S_RENDERED_FRAMES],
                (long) v[FramegenNative.S_REAL_FRAMES],
                (long) v[FramegenNative.S_INTERP_FRAMES],
                (long) v[FramegenNative.S_DROPPED_FRAMES],
                v[FramegenNative.S_CAPTURE_FPS],
                v[FramegenNative.S_RENDER_FPS],
                v[FramegenNative.S_INTERP_MS],
                v[FramegenNative.S_DISPLAY_HZ],
                v[FramegenNative.S_TARGET_HZ],
                (int) v[FramegenNative.S_QUALITY],
                (int) v[FramegenNative.S_REBUILD_COUNT],
                (int) v[FramegenNative.S_WIDTH],
                (int) v[FramegenNative.S_HEIGHT],
                v[FramegenNative.S_RUNNING] != 0,
                v[FramegenNative.S_PRESENTING] != 0,
                v[FramegenNative.S_CLOSED] != 0,
                v[FramegenNative.S_OVERLAY_MODE] != 0,
                v[FramegenNative.S_CAPTURE_AVG_LUMA],
                v[FramegenNative.S_CAPTURE_MAX_LUMA],
                (int) v[FramegenNative.S_CAPTURE_NONBLACK_PCT],
                v[FramegenNative.S_INTERP_AVG_LUMA],
                (int) v[FramegenNative.S_INTERP_NONBLACK_PCT],
                v[FramegenNative.S_PROBE_DONE] != 0,
                (long) v[FramegenNative.S_PRESENT_OK],
                (long) v[FramegenNative.S_PRESENT_FAIL],
                (long) v[FramegenNative.S_DRAW_FAIL],
                (long) v[FramegenNative.S_SRV_FAIL],
                (long) v[FramegenNative.S_LAST_PRESENT_HR]);
    }

    /** 插值帧占总渲染帧的比例（0..1）；没有渲染过时返回 0 */
    public double interpolationRatio() {
        if (renderedFrames <= 0) {
            return 0.0;
        }
        return (double) interpFrames / (double) renderedFrames;
    }

    /**
     * 渲染帧率相对显示器刷新率的「浪费倍数」。
     * &gt; 1 表示渲染出来的帧比屏幕能显示的更多，多出来的部分看不见。
     */
    public double overshootFactor() {
        if (displayHz <= 0.0) {
            return 0.0;
        }
        return renderFps / displayHz;
    }

    public String qualityName() {
        switch (quality) {
            case 0:
                return I18N.get("framegen.t0068");
            case 1:
                return I18N.get("framegen.t0069");
            case 2:
                return I18N.get("framegen.t0070");
            default:
                return I18N.get("framegen.p0145", quality + ")");
        }
    }

    @Override
    public String toString() {
        return String.format(
                "captured=%d rendered=%d (real=%d interp=%d) drop=%d "
                        + "captureFps=%.1f renderFps=%.1f interpMs=%.2f "
                        + "displayHz=%.0f targetHz=%.0f quality=%d %dx%d%s",
                capturedFrames, renderedFrames, realFrames, interpFrames, droppedFrames,
                captureFps, renderFps, interpMs,
                displayHz, targetHz, quality, width, height,
                running ? " running" : " idle");
    }
}
