package com.example.starlight.newui.framegen;

import com.example.starlight.config.AppDirs;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 帧插值引擎的 Java 门面：包装 {@link FramegenNative}，负责参数校验、
 * 日志路径准备与状态快照拉取。
 *
 * <p>刻意做成<b>无状态 + 静态</b>：原生侧的会话本来就是进程级单例，
 * Java 侧再包一层对象只会制造「谁该负责 stop」的歧义。
 *
 * <p><b>线程安全</b>：所有方法都可以从任意线程调用。{@link #poll()}
 * 只读原生侧的无锁原子槽，不阻塞。
 */
public final class FramegenEngine {

    /** 覆盖层矩形：由调用方从游戏窗口位置填入 */
    public record Rect(int left, int top, int right, int bottom) {
        public static final Rect ZERO = new Rect(0, 0, 0, 0);

        public boolean isEmpty() {
            return right <= left || bottom <= top;
        }

        public int width() {
            return right - left;
        }

        public int height() {
            return bottom - top;
        }
    }

    private static final double[] POLL_BUFFER = new double[FramegenNative.SLOT_COUNT];
    private static final Object POLL_LOCK = new Object();

    private FramegenEngine() {
    }

    /** DLL 是否可用（可用不代表已启动） */
    public static boolean isAvailable() {
        return FramegenNative.ensureLoaded();
    }

    public static boolean isRunning() {
        return FramegenNative.isRunning();
    }

    public static String version() {
        return FramegenNative.version();
    }

    public static String describe() {
        return FramegenNative.describeInstallation();
    }

    /**
     * 启动帧插值。
     *
     * @param gamePid   目标游戏进程 PID
     * @param quality   0=blend 1=blockmatch 2=neural(降级)
     * @param targetFps 期望输出帧率
     * @param overlay   是否作为游戏窗口上的覆盖层（true 时 rect 生效）
     * @param rect      覆盖层矩形；为空时由原生侧读游戏窗口矩形
     * @return 结果码，0 表示成功
     */
    public static int start(int gamePid, int quality, int targetFps,
                            boolean overlay, Rect rect) {
        if (!isAvailable()) {
            return -100;
        }
        if (gamePid <= 0) {
            return -102;
        }

        final Rect r = rect == null ? Rect.ZERO : rect;
        final String logPath = prepareLogPath();

        final int rc = FramegenNative.start(gamePid, quality, targetFps, overlay,
                r.left(), r.top(), r.right(), r.bottom(), logPath);
        if (rc != 0) {
            System.err.println("[FrameGen] start 失败 rc=" + rc
                    + " err=" + FramegenNative.lastError()
                    + " (" + describe() + ")");
        }
        return rc;
    }

    /** 便捷重载：覆盖层贴住给定矩形 */
    public static int start(int gamePid, int quality, int targetFps, Rect rect) {
        return start(gamePid, quality, targetFps, true, rect);
    }

    public static void stop() {
        FramegenNative.stop();
    }

    /**
     * 拉取一份状态快照。永远不会返回 null，失败时返回
     * {@link FramegenStats#EMPTY}。
     */
    public static FramegenStats poll() {
        if (!FramegenNative.isAvailable()) {
            return FramegenStats.EMPTY;
        }
        synchronized (POLL_LOCK) {
            try {
                if (FramegenNative.nativePoll(POLL_BUFFER) != 0) {
                    return FramegenStats.EMPTY;
                }
                return FramegenStats.fromSlots(POLL_BUFFER);
            } catch (Throwable t) {
                System.err.println("[FrameGen] poll 失败: " + t);
                return FramegenStats.EMPTY;
            }
        }
    }

    // ---- 运行期控制 ----

    public static void setQuality(int quality) {
        if (FramegenNative.isAvailable()) {
            FramegenNative.nativeSetQuality(quality);
        }
    }

    public static void setTargetFps(int fps) {
        if (FramegenNative.isAvailable()) {
            FramegenNative.nativeSetTargetFps(fps);
        }
    }

    /** 竞技模式：关掉插值但保留呈现，画面不会冻结 */
    public static void setInterpolating(boolean on) {
        if (FramegenNative.isAvailable()) {
            FramegenNative.nativeSetInterpolating(on);
        }
    }

    /** 游戏窗口被拖动 / 缩放时同步覆盖层位置 */
    public static void setOverlayRect(Rect rect) {
        if (rect == null || rect.isEmpty() || !FramegenNative.isAvailable()) {
            return;
        }
        FramegenNative.nativeSetOverlayRect(
                rect.left(), rect.top(), rect.right(), rect.bottom());
    }

    /** 请求一次像素探测（下一次 poll 后能看到 captureAvgLuma 等字段） */
    public static void requestProbe() {
        if (FramegenNative.isAvailable()) {
            try {
                FramegenNative.nativeRequestProbe();
            } catch (Throwable t) {
                System.err.println("[FrameGen] requestProbe 失败: " + t);
            }
        }
    }

    /** 诊断：清成品红并跳过绘制 */
    public static void setDebugFill(boolean on) {
        if (FramegenNative.isAvailable()) {
            try {
                FramegenNative.nativeSetDebugFill(on);
            } catch (Throwable t) {
                System.err.println("[FrameGen] setDebugFill 失败: " + t);
            }
        }
    }

    public static String lastError() {
        return FramegenNative.lastError();
    }

    private static String prepareLogPath() {
        try {
            final Path dir = AppDirs.framegenDir();
            Files.createDirectories(dir);
            return dir.resolve("framegen-dll.log").toString();
        } catch (Exception e) {
            // 写不了日志不影响功能，原生侧会退回只写 stdout
            return "";
        }
    }
}
