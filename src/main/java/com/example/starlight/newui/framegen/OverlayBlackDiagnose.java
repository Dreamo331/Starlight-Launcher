package com.example.starlight.newui.framegen;


import com.example.starlight.util.I18N;
/**
 * 判定「覆盖层全黑」到底断在哪一环。
 *
 * 三种可能，用像素探测区分：
 *   A. 捕获帧就是黑的        → captureAvgLuma ≈ 0
 *      → WGC 没拿到内容 / 目标窗口最小化 / 权限问题
 *   B. 捕获正常但屏幕是黑的  → captureAvgLuma 正常，但截图全黑
 *      → 呈现管线坏了（着色器/交换链/尺寸）
 *   C. 捕获与呈现都正常，只是我们看错了地方
 *
 * 用法：
 *   java -cp target\classes ...OverlayBlackDiagnose <目标PID>
 */
public final class OverlayBlackDiagnose {

    public static void main(String[] args) throws Exception {
        System.out.println("=== 覆盖层黑屏诊断 ===\n");

        if (!FramegenNative.ensureLoaded()) {
            System.out.println("DLL 不可用: " + FramegenNative.describeInstallation());
            return;
        }

        GameProcessLocator.Target t = args.length > 0
                ? new GameProcessLocator.Target(Integer.parseInt(args[0]),
                        I18N.get("framegen.t0075"), GameProcessLocator.Source.SCAN)
                : GameProcessLocator.locate();
        System.out.println("[1] 目标: pid=" + t.pid() + " " + t.description());
        if (!t.isValid()) {
            System.out.println("    没有目标进程");
            return;
        }

        int rc = FramegenEngine.start(t.pid(), 1, 120, FramegenEngine.Rect.ZERO);
        System.out.println("[2] nativeStart rc=" + rc
                + (rc != 0 ? " err=" + FramegenEngine.lastError() : ""));
        if (rc != 0) {
            return;
        }

        // 等管线稳定
        Thread.sleep(1500);

        System.out.println("\n[3] 请求像素探测...");
        FramegenEngine.requestProbe();
        Thread.sleep(1200);

        FramegenStats st = FramegenEngine.poll();

        System.out.println("\n[4] 结果");
        System.out.printf("    捕获帧 : avgLuma=%.1f  maxLuma=%.1f  非黑像素=%d%%%n",
                st.captureAvgLuma, st.captureMaxLuma, st.captureNonBlackPct);
        System.out.printf("    插值帧 : avgLuma=%.1f  非黑像素=%d%%%n",
                st.interpAvgLuma, st.interpNonBlackPct);
        System.out.printf("    计数器 : captured=%d rendered=%d real=%d interp=%d%n",
                st.capturedFrames, st.renderedFrames, st.realFrames, st.interpFrames);
        System.out.printf("    画面   : %dx%d  displayHz=%.0f targetHz=%.0f%n",
                st.width, st.height, st.displayHz, st.targetHz);
        System.out.println("    probeDone=" + st.probeDone);
        System.out.println();
        System.out.println("    呈现管线健康度:");
        System.out.println("      presentOk   = " + st.presentOk);
        System.out.println("      presentFail = " + st.presentFail
                + "  (lastHr=0x" + Long.toHexString(st.lastPresentHr) + ")");
        System.out.println("      drawFail    = " + st.drawFail);
        System.out.println("      srvFail     = " + st.srvFail);

        System.out.println("\n[5] 判定");
        if (!st.probeDone) {
            System.out.println("    ✘ 探测没执行（probeDone=false），检查原生侧是否在跑");
        } else if (st.captureAvgLuma < 2.0) {
            System.out.println("    ✘ A: 捕获帧本身全黑 —— WGC 没拿到内容");
            System.out.println("       可能原因：目标窗口被最小化 / 权限不一致 / 独占全屏");
        } else if (st.interpAvgLuma >= 0 && st.interpAvgLuma < 2.0) {
            System.out.println("    ✘ B1: 捕获正常但插值帧全黑 —— 插值 CS 输出有问题");
        } else {
            System.out.println("    ✔ 捕获与插值纹理都有内容（avgLuma="
                    + String.format("%.1f", st.captureAvgLuma) + "）");
            System.out.println("      → 黑屏发生在最后一步：Draw 到后台缓冲 或 交换链呈现");
            System.out.println("      → 重点查光栅化状态 / 输入布局 / 视口 / 着色器绑定");
        }

        FramegenEngine.stop();
        System.out.println("\n[6] 已停止");
    }
}
