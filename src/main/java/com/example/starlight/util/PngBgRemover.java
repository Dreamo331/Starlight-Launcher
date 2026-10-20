/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.util;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * PNG 去背景 —— 纯本地处理，供启动器 UI「皮肤 / 披风文件去背景」勾选框使用。
 *
 * <p>逻辑要点（复刻网页版 deepseek_html_20261001_b2dc57.html）：</p>
 * <ol>
 *   <li>超过 maxPixels 的图片先等比降采样；</li>
 *   <li>自动识别背景色 = 四角各取 min(W,H)*3% 的方块，跳过已有透明像素，求 RGB 均值；</li>
 *   <li>计算每像素与背景色的 RGB 欧氏距离 d（截断到 0..255）；</li>
 *   <li>inner = tol * 0.5，span = tol - inner；
 *       d &lt;= inner → alpha 系数 f = 0；d &gt;= tol → f = 1；否则 f = (d - inner) / span；
 *       out.alpha = round(原 alpha * f)（RGB 保持不变）；</li>
 *   <li>flood 模式只处理与画布四边连通的、d &lt; tol 的区域；global 模式全图所有相近颜色都处理；</li>
 *   <li>可选裁剪掉四周 alpha == 0 的空白。</li>
 * </ol>
 */
public final class PngBgRemover {

    private static final int DEFAULT_MAX_PIXELS = 8_000_000;
    private static final int TOL_MIN = 1;
    private static final int TOL_MAX = 160;

    /**
     * 去背景（字节级）：输入 PNG 字节，输出处理后的 PNG 字节。
     *
     * @param pngBytes   源 PNG 字节（非空）
     * @param tolerance  容差 1..160（UI 用 32）
     * @param globalMode true=全图匹配(global)；false=边缘连通(flood)
     * @param crop       是否裁剪四周 alpha==0 的透明边
     * @return 处理后的 PNG 字节；任何失败（读图失败/写出失败）返回原字节原样
     */
    public static byte[] removeBackgroundToPngBytes(byte[] pngBytes, int tolerance, boolean globalMode, boolean crop) {
        if (pngBytes == null || pngBytes.length == 0) return pngBytes;
        int tol = Math.max(TOL_MIN, Math.min(TOL_MAX, tolerance));
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(pngBytes));
            if (src == null) return pngBytes;

            src = downscaleIfNeeded(src, DEFAULT_MAX_PIXELS);

            final int w = src.getWidth();
            final int h = src.getHeight();
            if (w <= 0 || h <= 0) return pngBytes;

            int[] px = src.getRGB(0, 0, w, h, null, 0, w);
            int[] bg = autoDetectBg(px, w, h);
            int[] out = removeBackground(px, w, h, bg, tol, globalMode);

            int cx = 0, cy = 0, cw = w, ch = h;
            if (crop) {
                int[] box = boundingBox(out, w, h);
                if (box != null) {
                    cx = box[0];
                    cy = box[1];
                    cw = box[2];
                    ch = box[3];
                }
            }

            BufferedImage outImg = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
            outImg.setRGB(0, 0, cw, ch, out, cx + cy * w, w);

            ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(64, pngBytes.length));
            if (!ImageIO.write(outImg, "png", baos)) return pngBytes;
            return baos.toByteArray();
        } catch (IOException e) {
            return pngBytes;
        }
    }

    // 去背景核心 //
    private static int[] removeBackground(int[] px, int w, int h, int[] bg,
                                          int tol, boolean globalMode) {
        final int n = w * h;
        final int br = bg[0], bgc = bg[1], bb = bg[2];
        final double inner = tol * 0.5;
        final double span = tol - inner;   // tol >= 1，span >= 0.5，不会除零

        int[] out = px.clone();

        // 1) 每像素与背景色的 RGB 距离
        int[] dist = new int[n];
        for (int i = 0; i < n; i++) {
            int c = px[i];
            int dr = ((c >> 16) & 255) - br;
            int dg = ((c >> 8) & 255) - bgc;
            int db = (c & 255) - bb;
            int d = (int) Math.sqrt((double) dr * dr + (double) dg * dg + (double) db * db);
            if (d > 255) d = 255;
            dist[i] = d;
        }

        if (globalMode) {
            // 2a) 全图匹配
            for (int i = 0; i < n; i++) {
                out[i] = applyAlpha(out[i], dist[i], inner, tol, span);
            }
        } else {
            // 2b) 边缘连通：四边向内洪泛填充
            byte[] visited = new byte[n];
            int[] stack = new int[n];
            int sp = 0;

            for (int x = 0; x < w; x++) {
                sp = seed(x, visited, stack, sp, dist, tol);
                sp = seed((h - 1) * w + x, visited, stack, sp, dist, tol);
            }
            for (int y = 0; y < h; y++) {
                sp = seed(y * w, visited, stack, sp, dist, tol);
                sp = seed(y * w + w - 1, visited, stack, sp, dist, tol);
            }

            while (sp > 0) {
                int i = stack[--sp];
                int x = i % w;
                if (x > 0)     sp = seed(i - 1, visited, stack, sp, dist, tol);
                if (x < w - 1) sp = seed(i + 1, visited, stack, sp, dist, tol);
                if (i >= w)    sp = seed(i - w, visited, stack, sp, dist, tol);
                if (i < n - w) sp = seed(i + w, visited, stack, sp, dist, tol);
            }

            for (int i = 0; i < n; i++) {
                if (visited[i] != 0) {
                    out[i] = applyAlpha(out[i], dist[i], inner, tol, span);
                }
            }
        }
        return out;
    }

    private static int seed(int i, byte[] visited, int[] stack, int sp, int[] dist, int tol) {
        if (visited[i] == 0 && dist[i] < tol) {
            visited[i] = 1;
            stack[sp++] = i;
        }
        return sp;
    }

    /** 按距离计算透明度系数并写回 alpha 通道，RGB 不变。 */
    private static int applyAlpha(int argb, int d, double inner, int tol, double span) {
        double f;
        if (d <= inner) {
            f = 0;
        } else if (d >= tol) {
            f = 1;
        } else {
            f = (d - inner) / span;
        }
        if (f >= 1) return argb;   // 保持不变

        int a = (argb >>> 24) & 255;
        int na = (int) (a * f + 0.5);
        return (na << 24) | (argb & 0x00FFFFFF);
    }

    // 自动识别背景色 //
    private static int[] autoDetectBg(int[] px, int w, int h) {
        int s = Math.max(1, (int) Math.round(Math.min(w, h) * 0.03));
        int[][] corners = {{0, 0}, {w - s, 0}, {0, h - s}, {w - s, h - s}};

        long r = 0, g = 0, b = 0, cnt = 0;
        for (int[] c : corners) {
            for (int y = c[1]; y < c[1] + s; y++) {
                for (int x = c[0]; x < c[0] + s; x++) {
                    int p = px[y * w + x];
                    if (((p >>> 24) & 255) < 8) continue;   // 跳过已有透明像素
                    r += (p >> 16) & 255;
                    g += (p >> 8) & 255;
                    b += p & 255;
                    cnt++;
                }
            }
        }
        if (cnt == 0) return new int[]{255, 255, 255};
        return new int[]{
                (int) Math.round((double) r / cnt),
                (int) Math.round((double) g / cnt),
                (int) Math.round((double) b / cnt)
        };
    }

    // 裁剪框 //
    /** @return {x, y, w, h}；若整图全透明返回 null。 */
    private static int[] boundingBox(int[] argb, int w, int h) {
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0, i = 0; y < h; y++) {
            for (int x = 0; x < w; x++, i++) {
                if ((argb[i] >>> 24) != 0) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < 0) return null;
        return new int[]{minX, minY, maxX - minX + 1, maxY - minY + 1};
    }

    // 降采样 //
    private static BufferedImage downscaleIfNeeded(BufferedImage src, int maxPixels) {
        long total = (long) src.getWidth() * src.getHeight();
        if (maxPixels <= 0 || total <= maxPixels) return src;

        double k = Math.sqrt((double) maxPixels / (double) total);
        int nw = Math.max(1, (int) Math.round(src.getWidth() * k));
        int nh = Math.max(1, (int) Math.round(src.getHeight() * k));

        BufferedImage dst = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();

        return dst;
    }

    // CLI（保留，供独立调用） //
    public static void main(String[] args) {
        Options opt;
        try {
            opt = Options.parse(args);
        } catch (IllegalArgumentException ex) {
            System.err.println("参数错误: " + ex.getMessage());
            System.err.println();
            printUsage();
            System.exit(2);
            return;
        }

        if (opt.help) {
            printUsage();
            return;
        }

        try {
            run(opt);
        } catch (IOException ex) {
            System.err.println("错误: " + ex.getMessage());
            System.exit(1);
        }
    }

    private static void run(Options opt) throws IOException {
        BufferedImage src = ImageIO.read(opt.input);
        if (src == null) {
            throw new IOException("无法识别该图片格式: " + opt.input);
        }

        src = downscaleIfNeeded(src, opt.maxPixels);

        final int w = src.getWidth();
        final int h = src.getHeight();
        if (w <= 0 || h <= 0) {
            throw new IOException("图片尺寸非法");
        }
        final long n = (long) w * h;

        int[] px = src.getRGB(0, 0, w, h, null, 0, w);

        boolean auto = "auto".equalsIgnoreCase(opt.bgSpec);
        int[] bg = auto ? autoDetectBg(px, w, h) : parseColor(opt.bgSpec);

        System.out.printf("图像      : %d x %d (%,d 像素)%n", w, h, n);
        System.out.printf("背景色    : rgb(%d, %d, %d)%s%n", bg[0], bg[1], bg[2], auto ? "  (自动识别)" : "");
        System.out.printf("容差      : %d%n", opt.tolerance);
        System.out.printf("模式      : %s%n", "global".equals(opt.mode) ? "全图匹配" : "边缘连通");
        System.out.printf("裁剪透明边: %s%n", opt.crop ? "是" : "否");

        int[] out = removeBackground(px, w, h, bg, opt.tolerance, "global".equals(opt.mode));

        int cx = 0, cy = 0, cw = w, ch = h;
        if (opt.crop) {
            int[] box = boundingBox(out, w, h);
            if (box != null) {
                cx = box[0];
                cy = box[1];
                cw = box[2];
                ch = box[3];
            }
        }

        BufferedImage outImg = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
        outImg.setRGB(0, 0, cw, ch, out, cx + cy * w, w);

        File parent = opt.output.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建输出目录: " + parent);
        }
        if (!ImageIO.write(outImg, "png", opt.output)) {
            throw new IOException("PNG 写出失败: " + opt.output);
        }

        System.out.printf("输出      : %s  (%d x %d)%n", opt.output.getPath(), cw, ch);
    }

    private static int[] parseColor(String spec) {
        String s = spec.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.matches("[0-9a-fA-F]{6}")) {
            return new int[]{
                    Integer.parseInt(s.substring(0, 2), 16),
                    Integer.parseInt(s.substring(2, 4), 16),
                    Integer.parseInt(s.substring(4, 6), 16)
            };
        }
        String[] parts = s.split("[,，\\s]+");
        if (parts.length == 3) {
            int[] c = new int[3];
            for (int i = 0; i < 3; i++) {
                try {
                    c[i] = Integer.parseInt(parts[i]);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("无法解析背景色: " + spec);
                }
                if (c[i] < 0 || c[i] > 255) {
                    throw new IllegalArgumentException("颜色分量需在 0..255: " + spec);
                }
            }
            return c;
        }
        throw new IllegalArgumentException("无法解析背景色: " + spec + "（支持 auto / #RRGGBB / r,g,b）");
    }

    private static final class Options {
        File input;
        File output;
        String bgSpec = "auto";
        int tolerance = 32;
        String mode = "flood";
        boolean crop;
        int maxPixels = DEFAULT_MAX_PIXELS;
        boolean help;

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "-h":
                    case "--help":
                        o.help = true;
                        break;
                    case "-o":
                    case "--output":
                        o.output = new File(value(args, ++i, a));
                        break;
                    case "-b":
                    case "--bg":
                        o.bgSpec = value(args, ++i, a);
                        break;
                    case "-t":
                    case "--tolerance":
                        o.tolerance = intValue(value(args, ++i, a), a);
                        break;
                    case "-m":
                    case "--mode":
                        o.mode = value(args, ++i, a).toLowerCase(Locale.ROOT);
                        break;
                    case "-c":
                    case "--crop":
                        o.crop = true;
                        break;
                    case "--max-pixels":
                        o.maxPixels = intValue(value(args, ++i, a), a);
                        break;
                    default:
                        if (a.startsWith("-") && a.length() > 1) {
                            throw new IllegalArgumentException("未知选项: " + a);
                        }
                        if (o.input != null) {
                            throw new IllegalArgumentException("只支持一个输入文件");
                        }
                        o.input = new File(a);
                }
            }

            if (o.help) return o;

            if (o.input == null) {
                throw new IllegalArgumentException("缺少输入图片路径");
            }
            if (!o.input.isFile()) {
                throw new IllegalArgumentException("输入文件不存在: " + o.input);
            }
            if (o.tolerance < TOL_MIN || o.tolerance > TOL_MAX) {
                throw new IllegalArgumentException("容差需在 " + TOL_MIN + ".." + TOL_MAX + " 之间");
            }
            if (!"flood".equals(o.mode) && !"global".equals(o.mode)) {
                throw new IllegalArgumentException("模式只能是 flood 或 global");
            }
            if (o.maxPixels < 0) {
                throw new IllegalArgumentException("--max-pixels 不能为负数");
            }
            if (o.output == null) {
                o.output = new File(stripExt(o.input.getName()) + "-transparent.png");
            }
            return o;
        }

        private static String value(String[] args, int idx, String flag) {
            if (idx >= args.length) {
                throw new IllegalArgumentException("选项 " + flag + " 缺少参数");
            }
            return args[idx];
        }

        private static int intValue(String s, String flag) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("选项 " + flag + " 需要一个整数，收到: " + s);
            }
        }

        private static String stripExt(String name) {
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            return base.isEmpty() ? "image" : base;
        }
    }

    private static void printUsage() {
        System.out.println("PNG 去背景 —— 纯本地处理");
        System.out.println();
        System.out.println("用法:");
        System.out.println("  java com.example.starlight.util.PngBgRemover <input.png> [options]");
        System.out.println();
        System.out.println("选项:");
        System.out.println("  -o, --output <file>      输出 PNG 路径 (默认: <输入名>-transparent.png)");
        System.out.println("  -b, --bg <color>         背景色: auto | #RRGGBB | r,g,b   (默认: auto)");
        System.out.println("  -t, --tolerance <n>      容差 1..160                     (默认: 32)");
        System.out.println("  -m, --mode <mode>        flood = 边缘连通 | global = 全图匹配 (默认: flood)");
        System.out.println("  -c, --crop               裁掉四周的透明边缘");
        System.out.println("      --max-pixels <n>     超过该像素数自动降采样 (默认: 8000000，0 = 不限制)");
        System.out.println("  -h, --help               显示本帮助");
        System.out.println();
        System.out.println("示例:");
        System.out.println("  java com.example.starlight.util.PngBgRemover logo.png -c");
        System.out.println("  java com.example.starlight.util.PngBgRemover photo.jpg -b \"#00ff00\" -t 48 -m global -o out.png");
        System.out.println("  java com.example.starlight.util.PngBgRemover icon.png -b 255,255,255 -t 20");
    }

    private PngBgRemover() {
    }
}
