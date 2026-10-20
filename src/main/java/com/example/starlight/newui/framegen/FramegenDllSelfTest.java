/**
 * framegen DLL 端到端自检（纯 Java，无 JavaFX 依赖）。
 *
 * 验证链条：System.load(framegen.dll) → nativeStart(目标窗口 PID)
 *          → nativePoll 拿到递增的渲染帧数 → nativeStop → 进程干净退出
 *
 * 用法（在 starlight-launcher 目录）：
 *   java -cp "target\classes;<依赖>" com.example.starlight.newui.framegen.FramegenDllSelfTest <目标PID>
 *
 * 不传 PID 时会自己起一个测试窗口（需要先构建 framegen-testwindow.exe）。
 */
package com.example.starlight.newui.framegen;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class FramegenDllSelfTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== framegen.dll 端到端自检 ===\n");

        // ---- 1. 定位 DLL ----
        Path dll = FramegenNative.dllPath();
        check("DLL 文件存在", Files.isRegularFile(dll), dll.toString());
        if (!Files.isRegularFile(dll)) {
            System.out.println("请先构建并部署：build.ps1 -Deploy");
            finish();
            return;
        }
        System.out.println("  DLL: " + dll + " (" + Files.size(dll) + " bytes)");

        // ---- 2. 加载 ----
        boolean loaded = FramegenNative.ensureLoaded();
        check("DLL 加载成功", loaded, FramegenNative.loadError());
        if (!loaded) {
            finish();
            return;
        }
        System.out.println("  版本: " + FramegenNative.version());

        // ---- 3. 准备目标窗口 ----
        Process target = null;
        int pid;
        if (args.length > 0) {
            pid = Integer.parseInt(args[0]);
            System.out.println("  使用外部目标 PID: " + pid);
        } else {
            Path tw = Paths.get("..", "Frame-Interpolation", "Frame-Interpolation",
                    "build", "framegen-testwindow.exe").normalize();
            if (!Files.isRegularFile(tw)) {
                tw = Paths.get("build", "framegen-testwindow.exe").toAbsolutePath().normalize();
            }
            check("测试窗口可执行文件存在", Files.isRegularFile(tw), tw.toString());
            if (!Files.isRegularFile(tw)) {
                finish();
                return;
            }
            target = new ProcessBuilder(tw.toString(),
                    "--pattern", "pan_v", "--fps", "60",
                    "--width", "1280", "--height", "720")
                    .redirectErrorStream(true).start();
            Thread.sleep(1500);
            pid = (int) target.pid();
            System.out.println("  已启动测试窗口 PID: " + pid);
        }

        try {
            // ---- 4. 启动插值 ----
            int rc = FramegenEngine.start(pid, 1, 120,
                    FramegenEngine.Rect.ZERO);
            check("nativeStart 返回 0", rc == 0,
                    "rc=" + rc + " err=" + FramegenEngine.lastError());
            if (rc != 0) {
                finish();
                return;
            }

            // ---- 5. 采样 ----
            System.out.println("\n  采样 4 秒...");
            long prevCaptured = -1;
            long prevRendered = -1;
            boolean capturedGrew = false;
            boolean renderedGrew = false;
            FramegenStats last = FramegenStats.EMPTY;

            for (int i = 0; i < 20; i++) {
                Thread.sleep(200);
                FramegenStats st = FramegenEngine.poll();
                last = st;

                if (prevCaptured >= 0 && st.capturedFrames > prevCaptured) {
                    capturedGrew = true;
                }
                if (prevRendered >= 0 && st.renderedFrames > prevRendered) {
                    renderedGrew = true;
                }
                prevCaptured = st.capturedFrames;
                prevRendered = st.renderedFrames;

                if (i % 5 == 0) {
                    System.out.printf("    t=%.1fs  cap=%d render=%d (real=%d interp=%d) "
                                    + "renderFps=%.1f interpMs=%.2f %dx%d%n",
                            (i + 1) * 0.2, st.capturedFrames, st.renderedFrames,
                            st.realFrames, st.interpFrames, st.renderFps,
                            st.interpMs, st.width, st.height);
                }
            }

            System.out.println();
            check("捕获帧数在增长", capturedGrew,
                    "captured=" + last.capturedFrames);
            check("渲染帧数在增长", renderedGrew,
                    "rendered=" + last.renderedFrames);
            check("渲染帧数 >= 捕获帧数（插值生效）",
                    last.renderedFrames >= last.capturedFrames,
                    last.renderedFrames + " vs " + last.capturedFrames);
            check("读到了分辨率", last.width > 0 && last.height > 0,
                    last.width + "x" + last.height);
            check("读到了显示器刷新率", last.displayHz > 0,
                    String.valueOf(last.displayHz));
            check("正在呈现", last.presenting, "presenting=" + last.presenting);
            check("帧率在合理范围", last.renderFps > 1.0 && last.renderFps < 600.0,
                    String.valueOf(last.renderFps));

            System.out.println("\n  最终快照: " + last);

            // ---- 6. 停止 ----
            FramegenEngine.stop();
            Thread.sleep(300);
            check("nativeStop 后不再运行", !FramegenEngine.isRunning(), "");

            FramegenStats afterStop = FramegenEngine.poll();
            check("停止后计数归零", afterStop.renderedFrames == 0,
                    "rendered=" + afterStop.renderedFrames);

        } finally {
            FramegenEngine.stop();
            if (target != null) {
                target.destroyForcibly();
            }
        }

        finish();
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            System.out.println("  [PASS] " + name
                    + (detail == null || detail.isEmpty() ? "" : "  (" + detail + ")"));
            passed++;
        } else {
            System.out.println("  [FAIL] " + name
                    + (detail == null || detail.isEmpty() ? "" : "  (" + detail + ")"));
            failed++;
        }
    }

    private static void finish() {
        System.out.println();
        if (failed == 0) {
            System.out.println("全部通过 ✔  (" + passed + " 项)");
            System.exit(0);
        } else {
            System.out.println("失败 " + failed + " 项 ✘");
            System.exit(1);
        }
    }
}
