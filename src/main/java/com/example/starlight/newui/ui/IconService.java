package com.example.starlight.newui.ui;

import com.example.starlight.gui.UIGeneralControlClass;
import com.example.starlight.util.RemoteImageLoader;
import com.example.starlight.version.VersionDownloadService;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 界面图标服务：应用图标读取与缓存、版本 / 加载器图标、远程资源图标（含首字头像兜底）。
 *
 * <p>从 LauncherView 抽离（方法体逐字搬运）：图标资源固定取自 {@code /images/<文件名>}，
 * 缺失或解码失败时返回 null / 回退纯色块，与原来行为一致；内联样式与样式类名未改动。
 *
 * <p>远程图标加载的「下载源」由调用方以参数传入（原来是直读配置表），
 * 其余解码 / 缓存逻辑复用 {@link RemoteImageLoader}，不依赖 JavaFX 之外的框架。
 */
public final class IconService {

    private IconService() {
    }

    /** 版本类型色块的样式片段（正式版绿 / 快照蓝 / 愚人节橙 / 远古灰）；作为图标缺失时的兜底 */
    public static String versionIconStyle(VersionDownloadService.VersionCategory category) {
        if (category == null) return " -fx-background-color: rgba(148,163,184,0.35);";
        return switch (category) {
            case RELEASE -> " -fx-background-color: rgba(76,175,80,0.35);";
            case SNAPSHOT -> " -fx-background-color: rgba(59,130,246,0.35);";
            case APRIL -> " -fx-background-color: rgba(245,158,11,0.35);";
            case OLD -> " -fx-background-color: rgba(148,163,184,0.35);";
            default -> " -fx-background-color: rgba(148,163,184,0.35);";
        };
    }

    // ==================== 下载页图标（src/main/resources/images） ====================

    /** 已加载的界面图标缓存：文件名 → Image（图标会被反复复用，避免重复读盘） */
    private static final Map<String, Image> APP_IMAGE_CACHE = new ConcurrentHashMap<>();

    /**
     * 读取 {@code src/main/resources/images} 下的图标。
     *
     * @return 图片；文件缺失或解码失败时返回 null（调用方回退到纯色块，不显示裂图）
     */
    public static Image appImage(String fileName) {
        if (fileName == null || fileName.isBlank()) return null;
        Image cached = APP_IMAGE_CACHE.get(fileName);
        if (cached != null) return cached;
        try (java.io.InputStream in = IconService.class.getResourceAsStream("/images/" + fileName)) {
            if (in == null) {
                APP_IMAGE_CACHE.put(fileName, null);
                return null;
            }
            Image img = new Image(in);
            if (img.isError()) return null;
            APP_IMAGE_CACHE.put(fileName, img);
            return img;
        } catch (Exception e) {
            return null;
        }
    }

    /** 版本类型对应的图标文件名：正式版草方块 / 快照命令方块 / 愚人节金块 / 远古圆石 */
    public static String versionIconFile(VersionDownloadService.VersionCategory category) {
        if (category == null) return "grass.png";
        return switch (category) {
            case RELEASE -> "grass.png";
            case SNAPSHOT -> "CommandBlock.png";
            case APRIL -> "GoldBlock.png";
            case OLD -> "CobbleStone.png";
            default -> "grass.png";
        };
    }

    /** 加载器对应的图标文件名；没有专属图标的加载器返回 null（如 QSL） */
    public static String loaderIconFile(String loaderName) {
        if (loaderName == null || loaderName.isBlank()) return null;
        String n = loaderName.toLowerCase(java.util.Locale.ROOT);
        // 顺序敏感：NeoForge 含 "forge"、OptiFabric 含 "fabric"，必须最先判掉
        if (n.contains("neoforge")) return "NeoForge.png";
        if (n.contains("optifabric")) return "OptiFabric.png";
        if (n.contains("optifine")) return "OptiFine.png";
        if (n.contains("quilt")) return "quilt.png";
        if (n.contains("fabricapi")) return "Fabric.png";
        if (n.contains("fabric")) return "Fabric.png";
        if (n.contains("forge")) return "forge.png";
        return null;
    }

    /** 版本类型图标视图（居中，pixelated 放大不糊） */
    public static ImageView versionIconView(VersionDownloadService.VersionCategory category, double size) {
        ImageView iv = new ImageView();
        iv.setFitWidth(size);
        iv.setFitHeight(size);
        iv.setPreserveRatio(true);
        iv.setSmooth(false);
        Image img = appImage(versionIconFile(category));
        if (img != null) {
            iv.setImage(img);
        } else {
            iv.setStyle("-fx-background-radius: 8;" + versionIconStyle(category));
        }
        return iv;
    }

    /** 加载器图标视图；没有图标时回退为蓝色圆角块 */
    public static ImageView loaderIconView(String loaderName, double size) {
        ImageView iv = new ImageView();
        iv.setFitWidth(size);
        iv.setFitHeight(size);
        iv.setPreserveRatio(true);
        iv.setSmooth(false);
        Image img = appImage(loaderIconFile(loaderName));
        if (img != null) {
            iv.setImage(img);
        } else {
            iv.setStyle("-fx-background-radius: 6; -fx-background-color: rgba(59,130,246,0.25);");
        }
        return iv;
    }


    /**
     * 后台加载 Modrinth 卡片图标并设置到 ImageView（不阻塞 FX 线程）。
     * <p>
     * 说明：Modrinth 搜索返回的 {@code icon_url} 如今多为 CDN 生成的 <b>WebP</b> 缩略图
     * （形如 {@code https://cdn.modrinth.com/data/<id>/<hash>_96.webp}）；实测 JavaFX 17
     * 与 {@code ImageIO} 都<b>无法解码 WebP</b>，直接加载必然失败（封面变灰块）。
     * 因此这里先把 URL 展开为同目录下可被 JavaFX 解码的 {@code icon.png / icon.jpg} 候选
     * （绝大多数项目都有），再按当前下载源顺序尝试 mcimirror 镜像与官方 CDN。
     * <ul>
     *   <li>候选顺序跟随当前下载源：BMCLAPI / MCBBS 时优先走 mcimirror 镜像（国内可直连），
     *       Mojang 官方源优先官方 CDN；任一候选失败自动尝试下一个；</li>
     *   <li>传输使用带 UA / 超时的 HttpClients，并做 HTTP/2 → HTTP/1.1 降级
     *       （Modrinth CDN 与 HTTP/2 兼容性差的已知问题）；</li>
     *   <li>全部候选失败时（例如只有 WebP 封面、连 {@code icon.png} 都是 404 的项目），
     *       用名称首字生成一个带底色的头像占位，而不是留一块空白灰块。</li>
     * </ul>
     *
     * @param fallbackText 取不到图时用于生成占位头像的文字（通常传资源标题，可为 null）
     */
    public static void loadRemoteIconAsync(ImageView iconView, String originalUrl, double size, String fallbackText, String downloadSource) {
        if (iconView == null) return;
        if (originalUrl == null || originalUrl.isBlank()) {
            applyInitialAvatar(iconView, fallbackText, size);
            return;
        }

        List<String> candidates = RemoteImageLoader.buildIconCandidates(originalUrl, downloadSource);

        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            Image image = null;
            for (String url : candidates) {
                byte[] data = RemoteImageLoader.fetchIconBytesCached(url);
                if (data == null) continue;    // 网络失败或刚失败过的候选（负面缓存内）
                image = RemoteImageLoader.decodeRemoteImage(data, size);
                if (image != null) break;
                // 字节拿到但解不出（如只有损 WebP）：记入负面缓存，别每次渲染都重试
                RemoteImageLoader.recordIconDecodeFailure(url);
            }
            final Image result = image;
            Platform.runLater(() -> {
                if (result != null) {
                    iconView.setImage(result);
                } else {
                    // 全部候选失败：给一个有辨识度的首字头像，而不是空白灰块
                    applyInitialAvatar(iconView, fallbackText, size);
                }
            });
        });
    }

    /**
     * 用名称首字生成占位头像（底色 + 白色首字）并设置到 ImageView。
     * <p>封面取不到时用它兜底：仍是方形图标区域，但能一眼区分不同资源。
     */
    public static void applyInitialAvatar(ImageView iconView, String text, double size) {
        if (iconView == null) return;
        String label = RemoteImageLoader.avatarInitial(text);
        if (label.isEmpty()) return;                      // 连名字都没有就保持灰底
        iconView.setImage(RemoteImageLoader.makeInitialAvatar(label, size, RemoteImageLoader.avatarColor(text)));
    }
}
