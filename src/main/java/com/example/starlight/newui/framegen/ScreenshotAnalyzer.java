package com.example.starlight.newui.framegen;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

/**
 * 分析截图里覆盖层区域的像素，判定「黑屏」是否真的修好了。
 *
 * 只看计数器或只看原生探针都不够：
 *   · 计数在涨     → 只说明 Present 被调用了
 *   · 纹理有像素   → 只说明捕获/插值产出了内容
 *   · 屏幕上有内容 → 才是用户真正看到的东西
 * 所以必须抓屏幕。
 *
 * 用法：
 *   java ...ScreenshotAnalyzer <png> <x> <y> <w> <h>
 */
public final class ScreenshotAnalyzer {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("用法: ScreenshotAnalyzer <png> [x y w h]");
            return;
        }
        final Path png = Path.of(args[0]);
        final BufferedImage img = ImageIO.read(png.toFile());
        if (img == null) {
            System.out.println("无法读取图片: " + png);
            return;
        }

        Rectangle roi;
        if (args.length >= 5) {
            roi = new Rectangle(Integer.parseInt(args[1]), Integer.parseInt(args[2]),
                    Integer.parseInt(args[3]), Integer.parseInt(args[4]));
        } else {
            roi = new Rectangle(0, 0, img.getWidth(), img.getHeight());
        }
        // 夹到图像范围内
        roi = roi.intersection(new Rectangle(0, 0, img.getWidth(), img.getHeight()));

        long sum = 0;
        int count = 0;
        int nonBlack = 0;
        int maxLuma = 0;
        // 统计颜色多样性：如果整块是同一个值，说明是纯色块（黑/白都算异常）
        int distinct = 0;
        int lastSample = -1;

        final int step = 2;
        for (int y = roi.y; y < roi.y + roi.height; y += step) {
            for (int x = roi.x; x < roi.x + roi.width; x += step) {
                final int rgb = img.getRGB(x, y);
                final int r = (rgb >> 16) & 0xFF;
                final int g = (rgb >> 8) & 0xFF;
                final int b = rgb & 0xFF;
                final int luma = (int) (0.299 * r + 0.587 * g + 0.114 * b);
                sum += luma;
                if (luma > maxLuma) maxLuma = luma;
                if (luma > 8) nonBlack++;
                if (luma != lastSample) {
                    distinct++;
                    lastSample = luma;
                }
                count++;
            }
        }

        final double avg = count > 0 ? (double) sum / count : 0.0;
        final double nonBlackPct = count > 0 ? 100.0 * nonBlack / count : 0.0;

        System.out.printf("图片      : %dx%d%n", img.getWidth(), img.getHeight());
        System.out.printf("分析区域  : (%d,%d) %dx%d%n", roi.x, roi.y, roi.width, roi.height);
        System.out.printf("平均亮度  : %.1f%n", avg);
        System.out.printf("最大亮度  : %d%n", maxLuma);
        System.out.printf("非黑占比  : %.1f%%%n", nonBlackPct);
        System.out.printf("亮度跳变次数: %d%n", distinct);

        System.out.println();
        if (nonBlackPct < 5.0) {
            System.out.println("判定: 纯黑 —— 覆盖层没有画出内容（黑屏未修复）");
            System.exit(1);
        } else if (distinct < 10) {
            System.out.println("判定: 单色块 —— 有内容但不像正常画面，需要人工确认");
            System.exit(2);
        } else {
            System.out.println("判定: 有真实画面内容 ✔（非黑且亮度分布丰富）");
            System.exit(0);
        }
    }
}