package com.example.starlight.util;

import com.example.starlight.download.HttpDownloadEngine;
import com.example.starlight.newui.AppConfig;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javafx.scene.image.Image;

/**
 * 远程图片与占位头像加载（从 LauncherView 抽离，逻辑与原实现保持一致）。
 *
 * <p>职责：
 * <ul>
 *   <li>图标候选 URL 构建（原 URL + 同目录 {@code icon.png/jpg/jpeg} + mcimirror 镜像改写，
 *       候选优先级由当前下载源决定）；</li>
 *   <li>远程图片字节拉取（HTTP/2 → HTTP/1.1 降级、带 UA 与超时、进程内字节缓存）；</li>
 *   <li>字节解码为 JavaFX {@link Image}（含 WebP 无损解码分支）；</li>
 *   <li>名称首字占位头像生成（按名称哈希稳定取色）。</li>
 * </ul>
 *
 * <p>只负责「取图 / 解码 / 生成图片」，把图片设置到 {@code ImageView} 的一步仍留在界面层，
 * 因此本类不依赖 UI 控件，可在任意后台线程调用。
 */
public final class RemoteImageLoader {

    private RemoteImageLoader() {
    }

    /** 取图标字节：命中进程内缓存直接返回；未命中则下载并按上限缓存，失败返回 null */
    public static byte[] fetchIconBytesCached(String url) {
        byte[] data = MODRINTH_ICON_CACHE.get(url);
        if (data != null) return data;
        // 负面缓存：刚失败的候选短时间内不重试。之前失败不记录，同一个 404/超时候选
        // 每次渲染都完整重走一遍候选链，一抖就是整屏图标变占位
        Long failedAt = ICON_FAILURE_CACHE.get(url);
        if (failedAt != null) {
            if (System.currentTimeMillis() - failedAt < ICON_FAILURE_TTL_MS) return null;
            ICON_FAILURE_CACHE.remove(url, failedAt);
        }
        data = fetchRemoteImageBytes(url);
        if (data != null) {
            MODRINTH_ICON_CACHE.put(url, data);
            ICON_FAILURE_CACHE.remove(url);
        } else {
            ICON_FAILURE_CACHE.put(url, System.currentTimeMillis());
        }
        return data;
    }

    /**
     * 记录一次「字节拿到了但解不出图」的候选（如只有损 WebP 封面）。
     * 解码失败是确定性的，短时间内不必再为同一个 URL 重复解码。
     */
    public static void recordIconDecodeFailure(String url) {
        if (url != null) ICON_FAILURE_CACHE.put(url, System.currentTimeMillis());
    }

    /** Modrinth 图标字节缓存（URL → 图片字节），翻页/重复展示时避免重复请求。
     *  LRU 淘汰最旧条目——之前是超限整体 clear()，会把刚加载好的图标整批清掉 */
    private static final Map<String, byte[]> MODRINTH_ICON_CACHE =
            Collections.synchronizedMap(new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > MODRINTH_ICON_CACHE_MAX;
                }
            });
    /** 图标失败负面缓存（URL → 失败时刻），TTL 内直接跳过该候选 */
    private static final Map<String, Long> ICON_FAILURE_CACHE = new ConcurrentHashMap<>();
    /** 负面缓存时长：网络抖动或 404 的候选 5 分钟内不重试 */
    private static final long ICON_FAILURE_TTL_MS = 5 * 60_000L;
    /** 缓存上限，超限按 LRU 淘汰最旧条目 */
    private static final int MODRINTH_ICON_CACHE_MAX = 512;
    /** 单个图标请求读取超时（秒） */
    private static final int MODRINTH_ICON_TIMEOUT_SEC = 8;
    /** 镜像候选的读取超时（秒）：镜像常态更快，挂掉时也得更早放弃 */
    private static final int MIRROR_ICON_TIMEOUT_SEC = 5;

    /** 占位头像的配色盘（按名称哈希稳定取色，同一个资源每次颜色一致） */
    private static final String[] AVATAR_COLORS = {
            "#4c6ef5", "#12b886", "#f76707", "#ae3ec9", "#e8590c",
            "#1c7ed6", "#2f9e44", "#d6336c", "#5f3dc4", "#0b7285"
    };

    /** 取用于头像显示的首字：中文取第一个汉字，英文取首字母大写 */
    public static String avatarInitial(String text) {
        if (text == null) return "";
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                return Character.toUpperCase(c) == c ? String.valueOf(c) : String.valueOf(c).toUpperCase();
            }
        }
        return "";
    }

    public static javafx.scene.paint.Color avatarColor(String text) {
        int h = text == null ? 0 : Math.abs(text.hashCode());
        return javafx.scene.paint.Color.web(AVATAR_COLORS[h % AVATAR_COLORS.length]);
    }


    /** 在 FX 线程上画一张「底色 + 首字」的方形位图（Canvas.snapshot 必须在 FX 线程调用） */
    public static Image makeInitialAvatar(String initial, double size, javafx.scene.paint.Color bg) {
        double s = Math.max(16, size);
        javafx.scene.canvas.Canvas canvas = new javafx.scene.canvas.Canvas(s, s);
        javafx.scene.canvas.GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(bg);
        g.fillRoundRect(0, 0, s, s, s * 0.22, s * 0.22);
        g.setFill(javafx.scene.paint.Color.WHITE);
        g.setFont(javafx.scene.text.Font.font("System", javafx.scene.text.FontWeight.BOLD, s * 0.5));
        g.setTextAlign(javafx.scene.text.TextAlignment.CENTER);
        g.setTextBaseline(javafx.geometry.VPos.CENTER);
        g.fillText(initial, s / 2, s / 2);
        javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
        params.setFill(javafx.scene.paint.Color.TRANSPARENT);
        return canvas.snapshot(params, null);
    }

    /**
     * 依据当前下载源构造去重后的图标候选 URL 列表。
     * 镜像优先级：BMCLAPI / MCBBS（镜像源）→ mcimirror 在前；Mojang（官方源）→ 官方 CDN 在前。
     */
    public static List<String> buildIconCandidates(String originalUrl, String downloadSource) {
        List<String> originals = directoryIconCandidates(originalUrl);
        List<String> mirrors = new ArrayList<>();
        String mirroredUrl = toModrinthMirror(originalUrl);
        if (mirroredUrl != null) mirrors.addAll(directoryIconCandidates(mirroredUrl));


        boolean mirrorPreferred = !"Mojang".equalsIgnoreCase(downloadSource);
        List<String> first = mirrorPreferred ? mirrors : originals;
        List<String> second = mirrorPreferred ? originals : mirrors;

        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> ordered = new ArrayList<>(first.size() + second.size());
        for (String u : first) if (seen.add(u)) ordered.add(u);
        for (String u : second) if (seen.add(u)) ordered.add(u);
        return ordered;
    }

    /**
     * 将单个图标 URL 展开为候选列表：
     * <ul>
     *   <li>原 URL 放在最前 —— 现在自带 VP8L 解码器，WebP 缩略图可以直接用，
     *       而且它体积往往比 {@code icon.png} 更小（如 4.8KB vs 35KB）；</li>
     *   <li>其后是同一目录下的 {@code icon.png / icon.jpg / icon.jpeg}，
     *       用于原 URL 取不到或不是 WebP/PNG 的情况。</li>
     * </ul>
     */
    private static List<String> directoryIconCandidates(String url) {
        List<String> out = new ArrayList<>();
        int slash = url.lastIndexOf('/');
        String dir = slash > 0 ? url.substring(0, slash + 1) : url;
        out.add(url);
        for (String name : new String[]{"icon.png", "icon.jpg", "icon.jpeg"}) {
            String candidate = dir + name;
            if (!out.contains(candidate)) out.add(candidate);
        }
        return out;
    }

    /** 依据现有镜像规则将 cdn.modrinth.com 改写为 mod.mcimirror.top（无命中返回 null） */
    private static String toModrinthMirror(String url) {
        String[] prefixes = {"https://cdn.modrinth.com", "http://cdn.modrinth.com"};
        for (String p : prefixes) {
            if (url.startsWith(p)) {
                return "https://mod.mcimirror.top" + url.substring(p.length());
            }
        }
        return null;
    }

    /** 下载远程图片字节：HTTP/2 → HTTP/1.1 降级，带 UA 与超时；成功返回非空字节数组，否则 null */
    private static byte[] fetchRemoteImageBytes(String url) {
        java.net.http.HttpClient[] clients = {HttpDownloadEngine.HTTP_CLIENT, HttpDownloadEngine.HTTP_CLIENT_FALLBACK};
        int timeoutSec = isMcimMirrorUrl(url) ? MIRROR_ICON_TIMEOUT_SEC : MODRINTH_ICON_TIMEOUT_SEC;
        for (java.net.http.HttpClient client : clients) {
            try {
                java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(url))
                        .timeout(java.time.Duration.ofSeconds(timeoutSec))
                        .header("User-Agent", AppConfig.USER_AGENT)
                        .header("Accept", "image/png,image/jpeg,image/gif,image/bmp,image/*;q=0.8,*/*;q=0.5")
                        .GET()
                        .build();
                java.net.http.HttpResponse<byte[]> resp = client.send(req,
                        java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    byte[] body = resp.body();
                    if (body != null && body.length > 0) return body;
                }
            } catch (Exception ignored) {
                // 尝试 HTTP/1.1 客户端或下一候选
            }
        }
        return null;
    }

    /** 是否为 MCIM 镜像域名的 URL（镜像候选用更短的读取超时） */
    private static boolean isMcimMirrorUrl(String url) {
        return url != null && url.startsWith("https://mod.mcimirror.top");
    }

    /** 解码图片并等待后台解码完成；解码失败 / 空图返回 null */
    /**
     * 把远程图片字节解码成 JavaFX {@link Image}。
     *
     * <p>JavaFX 与 {@code ImageIO} 都<b>不支持 WebP</b>，而 Modrinth 的封面如今普遍是
     * VP8L 无损 WebP，直接交给 JavaFX 必然失败。这里先判断 WebP 魔数，命中则用自带的
     * {@link com.example.starlight.util.WebPLosslessDecoder} 转成 ARGB 位图再交给 JavaFX；
     * 其余格式仍走原来的 JavaFX 解码路径。
     */
    public static Image decodeRemoteImage(byte[] data, double size) {
        try {
            if (com.example.starlight.util.WebPLosslessDecoder.isSupported(data)) {
                java.awt.image.BufferedImage bi =
                        com.example.starlight.util.WebPLosslessDecoder.decode(data);
                if (bi == null) return null;
                return convertToFxImage(bi, size);
            }
            Image img = new Image(new ByteArrayInputStream(data), size, size, true, true);
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                if (img.isError()) return null;
                if (img.getProgress() >= 1.0) {
                    return (img.getWidth() > 0 && img.getHeight() > 0) ? img : null;
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            // 极端超时：交由 JavaFX 后台继续解码，出错时仅不显示，不影响 UI
            return img.isError() ? null : img;
        } catch (Exception ex) {
            return null;
        }
    }

    /** BufferedImage → JavaFX Image（按需等比缩放，用像素缓冲直接拷贝，避免逐像素 setPixel 的开销） */
    private static Image convertToFxImage(java.awt.image.BufferedImage src, double size) {
        java.awt.image.BufferedImage work = src;
        int w = src.getWidth(), h = src.getHeight();
        if (size > 0 && (w > size || h > size)) {
            double scale = size / Math.max((double) w, (double) h);
            int nw = Math.max(1, (int) Math.round(w * scale));
            int nh = Math.max(1, (int) Math.round(h * scale));
            java.awt.image.BufferedImage scaled =
                    new java.awt.image.BufferedImage(nw, nh, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, nw, nh, null);
            g.dispose();
            work = scaled;
            w = nw;
            h = nh;
        } else if (work.getType() != java.awt.image.BufferedImage.TYPE_INT_ARGB) {
            java.awt.image.BufferedImage copy =
                    new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = copy.createGraphics();
            g.drawImage(work, 0, 0, null);
            g.dispose();
            work = copy;
        }
        int[] pixels = work.getRGB(0, 0, w, h, null, 0, w);
        javafx.scene.image.WritableImage fx = new javafx.scene.image.WritableImage(w, h);
        fx.getPixelWriter().setPixels(0, 0, w, h,
                javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, w);
        return fx;
    }
}
