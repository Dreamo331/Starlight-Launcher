package com.example.starlight.newui.framegen;


import com.example.starlight.util.I18N;
import com.example.starlight.config.AppDirs;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * framegen.dll 的 JNI 绑定。
 *
 * <p><b>为什么是原生 JNI 而不是 JNA</b>：本启动器最终会打包成 GraalVM Native
 * Image 单 exe（见 pom 的 gluonfx 配置）。JNA 依赖运行期反射与动态代理，
 * 在 native-image 下需要额外一大套 reflection 配置；而原生 JNI 只需要在
 * pom 的 {@code <jniList>} 里注册本类即可，没有运行期反射面。
 *
 * <p><b>为什么是轮询而不是回调</b>：如果让原生线程回调 Java（AttachCurrentThread
 * + 反射方法调用），native-image 下同样需要大量注册且容易崩。这里改成 Java 侧
 * 定时调 {@link #poll(double[])} 拉一份定长数组——原生侧永远只写自己的内存。
 *
 * <p><b>DLL 定位顺序</b>：
 * <ol>
 *   <li>{@code -Dstarlight.framegen.dll=<路径>} 显式指定</li>
 *   <li>{@code ~/.starlight/framegen/framegen.dll}</li>
 *   <li>{@code System.loadLibrary("framegen")}（依赖 java.library.path）</li>
 * </ol>
 *
 * <p>所有 native 方法都是静态的，且本类只做转发，不含业务逻辑。
 */
public final class FramegenNative {

    /** poll() 输出数组的长度，必须与 C++ 侧 Slot 枚举一致 */
    public static final int SLOT_COUNT = 29;

    // 槽位索引（与 JniExports.cpp 的 Slot 枚举逐项对应）
    public static final int S_CAPTURED_FRAMES = 0;
    public static final int S_RENDERED_FRAMES = 1;
    public static final int S_REAL_FRAMES = 2;
    public static final int S_INTERP_FRAMES = 3;
    public static final int S_DROPPED_FRAMES = 4;
    public static final int S_CAPTURE_FPS = 5;
    public static final int S_RENDER_FPS = 6;
    public static final int S_INTERP_MS = 7;
    public static final int S_DISPLAY_HZ = 8;
    public static final int S_TARGET_HZ = 9;
    public static final int S_QUALITY = 10;
    public static final int S_REBUILD_COUNT = 11;
    public static final int S_WIDTH = 12;
    public static final int S_HEIGHT = 13;
    public static final int S_RUNNING = 14;
    public static final int S_PRESENTING = 15;
    public static final int S_CLOSED = 16;
    public static final int S_OVERLAY_MODE = 17;

    // ---- 诊断槽位 ----
    public static final int S_CAPTURE_AVG_LUMA = 18;
    public static final int S_CAPTURE_MAX_LUMA = 19;
    public static final int S_CAPTURE_NONBLACK_PCT = 20;
    public static final int S_INTERP_AVG_LUMA = 21;
    public static final int S_INTERP_NONBLACK_PCT = 22;
    public static final int S_PROBE_DONE = 23;

    // ---- 呈现管线健康度 ----
    public static final int S_PRESENT_OK = 24;
    public static final int S_PRESENT_FAIL = 25;
    public static final int S_DRAW_FAIL = 26;
    public static final int S_SRV_FAIL = 27;
    public static final int S_LAST_PRESENT_HR = 28;

    private static final AtomicBoolean LOADED = new AtomicBoolean(false);
    private static volatile String loadError = "";

    private FramegenNative() {
    }

    /**
     * 确保 DLL 已加载。可重复调用，只在第一次真正加载。
     *
     * @return 是否可用
     */
    public static boolean ensureLoaded() {
        if (LOADED.get()) {
            return true;
        }
        synchronized (FramegenNative.class) {
            if (LOADED.get()) {
                return true;
            }
            try {
                loadFromBestSource();
                LOADED.set(true);
                loadError = "";
                return true;
            } catch (Throwable t) {
                loadError = describe(t);
                System.err.println("[FrameGen] framegen.dll 加载失败: " + loadError);
                return false;
            }
        }
    }

    private static void loadFromBestSource() {
        // 1) 显式指定的路径
        final String explicit = System.getProperty("starlight.framegen.dll", "");
        if (!explicit.isEmpty()) {
            final Path p = Path.of(explicit);
            if (Files.isRegularFile(p)) {
                System.load(p.toAbsolutePath().toString());
                System.out.println("[FrameGen] 已加载 " + p);
                return;
            }
            throw new UnsatisfiedLinkError(I18N.get("framegen.p0144", explicit));
        }

        // 2) 启动器数据目录
        final Path dll = dllPath();
        if (Files.isRegularFile(dll)) {
            System.load(dll.toAbsolutePath().toString());
            System.out.println("[FrameGen] 已加载 " + dll);
            return;
        }

        // 3) java.library.path
        System.loadLibrary("framegen");
        System.out.println("[FrameGen] 已通过 java.library.path 加载 framegen");
    }

    /** DLL 的预期位置（随启动器数据目录） */
    public static Path dllPath() {
        return AppDirs.framegenDir().resolve("framegen.dll");
    }

    public static boolean isAvailable() {
        return LOADED.get();
    }

    /** 最近一次加载失败的原因（成功时为 ""） */
    public static String loadError() {
        return loadError;
    }

    private static String describe(Throwable t) {
        String msg = t.getMessage();
        final StringBuilder sb = new StringBuilder(t.getClass().getSimpleName());
        if (msg != null && !msg.isEmpty()) {
            sb.append(": ").append(msg);
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------
    // native 方法（名字必须与 C++ 导出符号完全一致）
    // -----------------------------------------------------------------

    /**
     * 启动插值宿主。
     *
     * @param pid       目标游戏进程 PID
     * @param quality   0=blend 1=blockmatch 2=neural(降级为 1)
     * @param targetFps 期望输出帧率（会按显示器刷新率钳制）
     * @param overlay   true=覆盖在游戏窗口上的置顶层
     * @param rect      覆盖层矩形（overlay=true 时有效；传 0 则由原生侧读窗口矩形）
     * @param logPath   日志路径，可空
     * @return 0 成功；-1 初始化失败；-2 启动超时
     */
    public static native int nativeStart(int pid, int quality, int targetFps,
                                         boolean overlay,
                                         int rectLeft, int rectTop,
                                         int rectRight, int rectBottom,
                                         String logPath);

    public static native void nativeStop();

    /**
     * 拉取统计快照，写入 out 的前 {@value #SLOT_COUNT} 个元素。
     *
     * @return 0 成功；-1 参数错误
     */
    public static native int nativePoll(double[] out);

    public static native void nativeSetQuality(int quality);

    public static native void nativeSetTargetFps(int targetFps);

    public static native void nativeSetInterpolating(boolean on);

    public static native void nativeSetOverlayRect(int l, int t, int r, int b);

    /** 请求原生侧做一次像素探测（把捕获帧/插值帧读回 CPU 统计亮度） */
    public static native void nativeRequestProbe();

    /** 诊断：把后台缓冲清成品红并跳过绘制，用于隔离交换链问题 */
    public static native void nativeSetDebugFill(boolean on);

    public static native String nativeLastError();

    public static native boolean nativeIsRunning();

    public static native String nativeVersion();

    // -----------------------------------------------------------------
    // 便捷包装：DLL 不可用时全部安全降级，绝不抛异常
    // -----------------------------------------------------------------

    public static int start(int pid, int quality, int targetFps, boolean overlay,
                            int l, int t, int r, int b, String logPath) {
        if (!ensureLoaded()) {
            return -100;
        }
        try {
            return nativeStart(pid, quality, targetFps, overlay, l, t, r, b, logPath);
        } catch (Throwable e) {
            loadError = describe(e);
            return -101;
        }
    }

    public static void stop() {
        if (!LOADED.get()) {
            return;
        }
        try {
            nativeStop();
        } catch (Throwable e) {
            System.err.println("[FrameGen] nativeStop 失败: " + describe(e));
        }
    }

    public static boolean isRunning() {
        if (!LOADED.get()) {
            return false;
        }
        try {
            return nativeIsRunning();
        } catch (Throwable e) {
            return false;
        }
    }

    public static String lastError() {
        if (!LOADED.get()) {
            return loadError;
        }
        try {
            final String e = nativeLastError();
            return e == null ? "" : e;
        } catch (Throwable t) {
            return describe(t);
        }
    }

    public static String version() {
        if (!LOADED.get()) {
            return "";
        }
        try {
            return nativeVersion();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 供诊断：DLL 是否存在、路径在哪 */
    public static String describeInstallation() {
        final Path p = dllPath();
        final boolean exists = Files.isRegularFile(p);
        return "dll=" + p + " (exists=" + exists + ", loaded=" + LOADED.get() + ")"
                + (loadError.isEmpty() ? "" : ", error=" + loadError);
    }

    /** 供诊断：DLL 文件大小，便于确认版本 */
    public static long dllSize() {
        try (InputStream ignored = null) {
            final Path p = dllPath();
            return Files.isRegularFile(p) ? Files.size(p) : -1L;
        } catch (Exception e) {
            return -1L;
        }
    }
}
