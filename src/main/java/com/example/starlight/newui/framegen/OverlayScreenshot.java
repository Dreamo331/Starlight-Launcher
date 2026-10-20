package com.example.starlight.newui.framegen;

import com.example.starlight.service.GameInstanceRegistry;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 复现「覆盖层黑屏」并截图存证。
 *
 * 流程：启动 DLL 覆盖层 → 等几秒 → 用 Robot 截全屏 → 存 PNG
 * 这样能直观看到：黑的是我们的窗口，还是别的东西。
 *
 * 用法：
 *   java -cp target\classes com.example.starlight.newui.framegen.OverlayScreenshot
 *   可选参数：<输出png路径> <等待秒数>
 */
public final class OverlayScreenshot {

    public static void main(String[] args) throws Exception {
        final Path out = Path.of(args.length > 0
                ? args[0]
                : System.getProperty("java.io.tmpdir") + "\\framegen-overlay.png");
        final int waitSec = args.length > 1 ? Integer.parseInt(args[1]) : 4;

        System.out.println("=== 覆盖层截图复现 ===");

        // 1) 目标：命令行给了 PID 就直接用（便于拿测试窗口复现）
        GameProcessLocator.Target t;
        if (args.length > 2) {
            final int pid = Integer.parseInt(args[2]);
            t = new GameProcessLocator.Target(pid, "命令行指定 PID " + pid,
                    GameProcessLocator.Source.SCAN);
        } else {
            t = GameProcessLocator.locate();
        }
        System.out.println("[1] 目标: valid=" + t.isValid() + " pid=" + t.pid()
                + " " + t.description());
        if (!t.isValid()) {
            System.out.println("    没有 Minecraft 在跑，先启动游戏");
            return;
        }

        // 2) 截图（覆盖层出现前）
        final Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        Robot robot = new Robot();
        Path before = out.resolveSibling(stripExt(out.getFileName().toString()) + "-before.png");
        save(robot, screen, before);
        System.out.println("[2] 覆盖层前截图: " + before);

        // 3) 启动覆盖层
        int rc = FramegenEngine.start(t.pid(), 1, 120, FramegenEngine.Rect.ZERO);
        System.out.println("[3] nativeStart rc=" + rc
                + (rc != 0 ? " err=" + FramegenEngine.lastError() : ""));
        if (rc != 0) {
            return;
        }

        if (System.getProperty("framegen.debugfill", "false").equals("true")) {
            System.out.println("[3.5] 诊断模式：清成品红，跳过绘制");
            FramegenEngine.setDebugFill(true);
            Thread.sleep(600);
        }

        // 4) 等覆盖层稳定后截图
        Thread.sleep(waitSec * 1000L);
        FramegenStats st = FramegenEngine.poll();
        System.out.println("[4] 快照: " + st);

        save(robot, screen, out);
        System.out.println("[5] 覆盖层后截图: " + out);

        // 5) 采样几轮，确认计数在涨（说明 Present 在跑）
        for (int i = 0; i < 3; i++) {
            Thread.sleep(300);
            FramegenStats s = FramegenEngine.poll();
            System.out.printf("    t=%.1fs captured=%d rendered=%d real=%d interp=%d%n",
                    (i + 1) * 0.3, s.capturedFrames, s.renderedFrames,
                    s.realFrames, s.interpFrames);
        }

        FramegenEngine.stop();
        System.out.println("[6] 已停止");
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static void save(Robot robot, Rectangle screen, Path path) throws Exception {
        BufferedImage img = robot.createScreenCapture(screen);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        ImageIO.write(img, "png", path.toFile());
    }
}
