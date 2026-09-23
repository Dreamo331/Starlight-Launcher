/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.util;

import com.example.starlight.auth.AccountManager.Account;
import com.example.starlight.auth.offline.Skin;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Minecraft 头像生成器
 * 复刻自 HMCL (Hello Minecraft! Launcher) TexturesLoader.drawAvatar：
 *   - 第一笔：绘制皮肤上 (8,8) 位置 8x8 的不透明"脸部"块，居中缩小留出 size/18 边距
 *   - 第二笔：绘制皮肤上 (40,8) 位置 8x8 的"帽子/头发"覆盖层（含透明像素），铺满整个画布
 *   - 关闭图像平滑，保持 Minecraft 像素风格
 */
public final class AvatarGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(AvatarGenerator.class);

    private AvatarGenerator() {
    }

    /**
     * 在画布上绘制 Minecraft 头像（完全复刻 HMCL 的绘制方式）。
     *
     * @param g          目标画布的 GraphicsContext（调用前应 clearRect 清空）
     * @param skin       皮肤图片，宽必须为 64 的整数倍
     * @param size       头像边长（像素）
     * @param scale      皮肤缩放系数 = 皮肤宽度 / 64
     * @param faceOffset 脸部内缩边距 = round(size / 18.0)
     */
    public static void drawAvatar(GraphicsContext g, Image skin, int size, int scale, int faceOffset) {
        g.setImageSmoothing(false);
        // 第一笔：不透明"脸部"层（皮肤坐标 8,8 起 8x8 块），居中缩小，留出边距
        g.drawImage(skin,
                8 * scale, 8 * scale, 8 * scale, 8 * scale,
                faceOffset, faceOffset, size - 2 * faceOffset, size - 2 * faceOffset);
        // 第二笔：半透明"帽子/头发"覆盖层（皮肤坐标 40,8 起 8x8 块），铺满整个画布
        g.drawImage(skin,
                40 * scale, 8 * scale, 8 * scale, 8 * scale,
                0, 0, size, size);
    }

    /**
     * 由皮肤图片生成指定边长的头像图片。
     * 注意：本方法内部使用 Canvas.snapshot，必须在 JavaFX 应用线程调用。
     */
    public static WritableImage generateAvatar(Image skin, int size) {
        if (skin == null || skin.isError()) {
            return null;
        }

        Canvas canvas = new Canvas(size, size);
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, size, size);

        int scale = (int) skin.getWidth() / 64;
        int faceOffset = (int) Math.round(size / 18.0);

        drawAvatar(g, skin, size, scale, faceOffset);

        // snapshot 默认背景为白色，必须显式设置透明背景，否则透明像素会变白
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        return canvas.snapshot(params, null);
    }

    /**
     * 将 JavaFX 图片逐像素写入 BufferedImage 并保存为 PNG（不依赖 javafx.swing 模块）。
     */
    public static void savePng(Image image, Path output) throws IOException {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        BufferedImage buf = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = image.getPixelReader();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                buf.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        ImageIO.write(buf, "png", output.toFile());
    }

    /**
     * 加载账号配置的皮肤图片（后台线程调用，内部含网络/文件 IO）。
     * 无皮肤配置（DEFAULT）或加载失败时返回 null。
     */
    public static Image loadAccountSkin(Account account) {
        if (account == null) return null;
        try {
            Skin skin = account.getSkin();
            if (skin == null || skin.getType() == Skin.Type.DEFAULT) return null;
            Skin.LoadedSkin loaded = skin.load(account.name);
            return loaded != null && loaded.hasSkin() ? loaded.getSkin().getImage() : null;
        } catch (Exception e) {
            LOG.warn("Failed to load account skin: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 按 UUID 哈希从 9 款内置皮肤中选择默认皮肤（复刻 HMCL TexturesLoader.getDefaultSkin 的选择逻辑）。
     * 资源加载失败时返回 null。
     */
    public static Image getDefaultSkin(UUID uuid) {
        if (uuid == null) return null;
        Skin.Type[] builtins = Skin.getBuiltinTypes();
        Skin.Type type = builtins[Math.floorMod(uuid.hashCode(), builtins.length)];
        Skin.LoadedSkin loaded = new Skin(type, null, null, null, null).load("");
        return loaded != null && loaded.hasSkin() ? loaded.getSkin().getImage() : null;
    }

    /**
     * 解析账号 UUID（32 位无连字符格式），失败时返回 null。
     */
    public static UUID parseUuid(String id) {
        if (id == null || id.isEmpty()) return null;
        try {
            String s = id.replace("-", "");
            return UUID.fromString(s.replaceFirst(
                    "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
