package com.example.starlight.newui;

import javafx.application.Platform;
import javafx.stage.Stage;

/**
 * 启动器可见性：游戏启动后启动器窗口怎么处理（设置 → 高级设置 → 启动器可见性）。
 *
 * <pre>
 * 游戏启动后仍保持不变              keep          游戏期间启动器照常显示（默认）
 * 游戏启动后立即关闭                close         游戏窗口一就绪就关掉启动器
 * 游戏启动后隐藏，退出后自动关闭    hide_close    游戏期间隐藏，游戏退出时结束启动器
 * 游戏启动后隐藏，退出后重新打开    hide_restore  游戏期间隐藏，游戏退出后自动弹出
 * 游戏启动后最小化                  minimize      游戏期间最小化，游戏退出后还原
 * </pre>
 *
 * <p>「关闭」走启动器统一的退出路径：先 {@link Platform#exit()}（与点标题栏关闭按钮一致，
 * 会跑 JavaFX 的关窗清理：色盲辅助等伴生工具随启动器一起收掉），随后结束进程。
 * 之所以还要显式结束进程：游戏用的 stdout/stderr 读取线程不是守护线程，只靠 JavaFX 退出
 * 的话启动器进程会一直挂到游戏结束（表现为「窗口关了但进程还在」，用户会当成 bug）。
 * 游戏是本启动器的子进程，启动器结束不影响它继续运行。
 *
 * <p>本类只做窗口动作，不读配置：策略值由调用方（{@link LauncherView}）在启动游戏时读一次，
 * 启动与退出两个时机用同一个值，避免中途改配置导致「隐藏了却不还原」。
 */
public final class LauncherVisibility {

    /** 配置键 */
    public static final String KEY = "LauncherVisibility";

    public static final String KEEP = "keep";
    public static final String CLOSE = "close";
    public static final String HIDE_CLOSE = "hide_close";
    public static final String HIDE_RESTORE = "hide_restore";
    public static final String MINIMIZE = "minimize";

    /** 选项：{值, 界面文案}（顺序即下拉框顺序，与需求给出的五项一致） */
    public static final String[][] OPTIONS = {
            {KEEP, "游戏启动后仍保持不变"},
            {CLOSE, "游戏启动后立即关闭"},
            {HIDE_CLOSE, "游戏启动后隐藏，游戏退出后自动关闭"},
            {HIDE_RESTORE, "游戏启动后隐藏，游戏退出后重新打开"},
            {MINIMIZE, "游戏启动后最小化"}
    };

    /**
     * 是否已经进入「关闭启动器」流程。
     *
     * <p>退出策略下 JavaFX 运行时已被关掉，后台启动线程此后再调 {@code Platform.runLater}
     * 会抛 IllegalStateException（并写进启动器崩溃日志）。界面侧的回调据此提前返回，
     * 既避免这个假崩溃，也不再往已经没人看的界面上刷状态。
     */
    private static volatile boolean exitRequested = false;

    /** 本轮启动开始时复位退出标记（见 {@link #resetExitRequested()}） */
    public static void resetExitRequested() {
        exitRequested = false;
    }

    /** 是否已进入关闭流程 */
    public static boolean isExitRequested() {
        return exitRequested;
    }

    private LauncherVisibility() {
    }

    /** 规范化策略值：无法识别时回落到「保持不变」（保持既有行为） */
    public static String normalize(String raw) {
        if (raw == null) return KEEP;
        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        for (String[] option : OPTIONS) {
            if (option[0].equals(v)) return v;
        }
        return KEEP;
    }

    /** 策略值 → 界面文案 */
    public static String labelOf(String value) {
        String v = normalize(value);
        for (String[] option : OPTIONS) {
            if (option[0].equals(v)) return option[1];
        }
        return OPTIONS[0][1];
    }

    /** 界面文案 → 策略值（未知文案返回「保持不变」） */
    public static String valueOfLabel(String label) {
        if (label != null) {
            for (String[] option : OPTIONS) {
                if (option[1].equals(label.trim())) return option[0];
            }
        }
        return KEEP;
    }

    /**
     * 游戏窗口就绪：按策略处理启动器窗口（必须在 JavaFX 线程调用）。
     *
     * @return 本次是否真的隐藏 / 最小化 / 关闭了启动器（退出策略据此决定要不要还原）
     */
    public static boolean onGameStarted(Stage stage, String mode) {
        if (stage == null) return false;
        switch (normalize(mode)) {
            case CLOSE:
                exitLauncher();
                return true;
            case HIDE_CLOSE:
            case HIDE_RESTORE:
                stage.hide();
                return true;
            case MINIMIZE:
                stage.setIconified(true);
                return true;
            default:
                return false;
        }
    }

    /**
     * 游戏退出：收尾启动器窗口（必须在 JavaFX 线程调用）。
     *
     * @param launcherHandled 启动时是否真的隐藏 / 最小化 / 关闭过启动器
     *                        （为 false 时什么都不做：尤其是启动失败的回调，
     *                         不能因为没启动成功反而把启动器关掉）
     */
    public static void onGameExited(Stage stage, String mode, boolean launcherHandled) {
        if (stage == null || !launcherHandled) return;
        switch (normalize(mode)) {
            case HIDE_CLOSE:
                // 游戏期间一直藏着，游戏结束就没必要再留着启动器
                exitLauncher();
                break;
            case HIDE_RESTORE:
                showAndFocus(stage);
                break;
            case MINIMIZE:
                // 还原最小化：用户下一次操作大概率就是看「游戏已退出 / 崩溃诊断」这类提示
                stage.setIconified(false);
                showAndFocus(stage);
                break;
            default:
                // keep：启动器一直显示着，无需处理；close：启动器早已退出
                break;
        }
    }

    /** 显示并置前（隐藏过 / 最小化过的窗口不会自动抢焦点） */
    private static void showAndFocus(Stage stage) {
        if (!stage.isShowing()) stage.show();
        stage.toFront();
        stage.requestFocus();
    }

    /**
     * 关闭启动器：先走 JavaFX 标准退出（关窗清理照常执行），再结束进程。
     *
     * <p>必须结束进程：游戏 stdout/stderr 的读取线程不是守护线程，只靠 {@code Platform.exit()}
     * 的话 JVM 会一直挂到游戏结束 —— 用户看到的就是「启动器关了但进程还在」，游戏一退才消失。
     * 插件子进程、色盲矫正工具等已有 JVM shutdownHook 负责收掉，游戏是独立子进程不受影响。
     *
     * <p>延迟到下一个 JavaFX 脉冲再退出：本方法常常是在「游戏启动完成」回调里被同步调用的，
     * 立即 exit 会把同一批还没跑完的界面收尾（Toast、动画）一起带走。
     */
    private static void exitLauncher() {
        if (exitRequested) return;
        exitRequested = true;
        // 兜底：JavaFX 线程若已不可用（极端情况），直接结束进程，不留僵尸窗口
        try {
            Platform.runLater(() -> {
                Platform.exit();
                terminateProcess();
            });
        } catch (Throwable t) {
            Platform.exit();
            terminateProcess();
        }
    }

    /** 结束启动器进程（0 = 正常退出，与用户点关闭后再无后续动作等价） */
    private static void terminateProcess() {
        try {
            System.exit(0);
        } catch (Throwable ignored) {
            // Runtime.halt 仅在 System.exit 被 SecurityManager 挡到时才需要，正常环境走不到
        }
    }
}
