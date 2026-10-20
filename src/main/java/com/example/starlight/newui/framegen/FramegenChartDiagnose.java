/**
 * 诊断：开发者页图表为什么拿不到数据。
 *
 * 逐个环节验证，把「哪一层断了」打印出来：
 *   1. DLL 是否存在 / 能否加载
 *   2. GameInstanceRegistry 有没有记录游戏 PID
 *   3. DevPage.currentGamePid() 实际返回什么
 *   4. nativeStart 能否成功
 *   5. poll 出来的 18 个槽位是否真的有值
 *
 * 用法（在 starlight-launcher 目录）：
 *   java -cp "target\classes;<依赖>" \
 *     com.example.starlight.newui.framegen.FramegenChartDiagnose
 */
package com.example.starlight.newui.framegen;

import com.example.starlight.service.GameInstanceRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class FramegenChartDiagnose {

    public static void main(String[] args) throws Exception {
        System.out.println("=== 开发者页帧率图表 诊断 ===\n");

        // ---- 1. DLL ----
        Path dll = FramegenNative.dllPath();
        System.out.println("[1] DLL 路径: " + dll);
        System.out.println("    存在: " + Files.isRegularFile(dll));
        boolean loaded = FramegenNative.ensureLoaded();
        System.out.println("    加载: " + loaded
                + (loaded ? " (版本 " + FramegenNative.version() + ")" : " 原因: "
                + FramegenNative.loadError()));

        // ---- 2. 游戏 PID 来源 ----
        System.out.println("\n[2] GameInstanceRegistry（启动器跟踪游戏进程的事实来源）");
        List<GameInstanceRegistry.Instance> alive = GameInstanceRegistry.aliveInstances();
        System.out.println("    存活实例数: " + alive.size());
        for (GameInstanceRegistry.Instance ins : alive) {
            System.out.println("      pid=" + ins.pid + " 版本=" + ins.version
                    + " 用户=" + ins.userName);
        }
        System.out.println("    hasRunning(): " + GameInstanceRegistry.hasRunning());

        // ---- 3. 各种「取 PID」方式的对比 ----
        System.out.println("\n[3] 取 PID 的各种方式对比");
        System.out.println("    GameInstanceRegistry.aliveInstances()[0].pid = "
                + (alive.isEmpty() ? "无" : String.valueOf(alive.get(0).pid)));
        System.out.println("    （DevPage.currentGamePid() 早先读配置键 GamePid/LastGamePid，"
                + "启动器根本不写这些键 → 恒为 -1）");

        // ---- 4. 尝试启动 ----
        System.out.println("\n[4] 尝试启动插值");
        if (!loaded) {
            System.out.println("    跳过：DLL 不可用");
            return;
        }
        int pid = -1;
        if (args.length > 0) {
            pid = Integer.parseInt(args[0]);
            System.out.println("    使用命令行 PID: " + pid);
        } else {
            final GameProcessLocator.Target t = GameProcessLocator.locate();
            System.out.println("    GameProcessLocator: valid=" + t.isValid()
                    + " pid=" + t.pid() + " 来源=" + t.source());
            System.out.println("    描述: " + t.description());
            if (t.isValid()) {
                pid = t.pid();
            }
        }

        if (pid <= 0) {
            System.out.println("    没有可用的游戏 PID —— 这就是图表拿不到数据的直接原因");
            System.out.println("    请先启动 Minecraft，或手动传一个 PID 参数");
            return;
        }

        int rc = FramegenEngine.start(pid, 1, 120, FramegenEngine.Rect.ZERO);
        System.out.println("    nativeStart rc=" + rc
                + (rc != 0 ? "  err=" + FramegenEngine.lastError() : ""));
        if (rc != 0) {
            return;
        }

        // ---- 5. 看槽位 ----
        System.out.println("\n[5] 采样槽位（18 个）");
        double[] buf = new double[FramegenNative.SLOT_COUNT];
        for (int i = 0; i < 10; i++) {
            Thread.sleep(300);
            int prc = FramegenNative.nativePoll(buf);
            if (prc != 0) {
                System.out.println("    nativePoll 失败 rc=" + prc);
                break;
            }
            System.out.printf("    t=%.1fs  captured=%.0f rendered=%.0f real=%.0f "
                            + "interp=%.0f renderFps=%.1f interpMs=%.2f "
                            + "displayHz=%.0f %dx%d running=%.0f presenting=%.0f%n",
                    (i + 1) * 0.3,
                    buf[FramegenNative.S_CAPTURED_FRAMES],
                    buf[FramegenNative.S_RENDERED_FRAMES],
                    buf[FramegenNative.S_REAL_FRAMES],
                    buf[FramegenNative.S_INTERP_FRAMES],
                    buf[FramegenNative.S_RENDER_FPS],
                    buf[FramegenNative.S_INTERP_MS],
                    buf[FramegenNative.S_DISPLAY_HZ],
                    (int) buf[FramegenNative.S_WIDTH],
                    (int) buf[FramegenNative.S_HEIGHT],
                    buf[FramegenNative.S_RUNNING],
                    buf[FramegenNative.S_PRESENTING]);
        }

        // ---- 6. 通过门面再确认 ----
        FramegenStats st = FramegenEngine.poll();
        System.out.println("\n[6] FramegenEngine.poll() = " + st);
        System.out.println("    渲染帧率 = " + st.renderFps
                + "（图表画的就是这个值）");

        FramegenEngine.stop();
        System.out.println("\n[7] 已停止");
    }
}
