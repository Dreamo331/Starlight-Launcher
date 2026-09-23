/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.auth.offline;

import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * 离线皮肤纹理缓存与工具类
 * 参考 HMCL 的 auth.offline.Texture
 */
public final class SkinTexture {
    private final String hash;
    private final Image image;

    public SkinTexture(String hash, Image image) {
        this.hash = requireNonNull(hash);
        this.image = requireNonNull(image);
    }

    public String getHash() {
        return hash;
    }

    public Image getImage() {
        return image;
    }

    private static final Map<String, SkinTexture> textures = new HashMap<>();

    public static boolean hasTexture(String hash) {
        return textures.containsKey(hash);
    }

    public static SkinTexture getTexture(String hash) {
        return textures.get(hash);
    }

    private static String computeTextureHash(Image img) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        PixelReader reader = img.getPixelReader();
        int width = (int) img.getWidth();
        int height = (int) img.getHeight();
        byte[] buf = new byte[4096];

        putInt(buf, 0, width);
        putInt(buf, 4, height);
        int pos = 8;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                putInt(buf, pos, reader.getArgb(x, y));
                if (buf[pos + 0] == 0) {
                    buf[pos + 1] = buf[pos + 2] = buf[pos + 3] = 0;
                }
                pos += 4;
                if (pos == buf.length) {
                    pos = 0;
                    digest.update(buf, 0, buf.length);
                }
            }
        }
        if (pos > 0) {
            digest.update(buf, 0, pos);
        }

        return HexFormat.of().formatHex(digest.digest());
    }

    private static void putInt(byte[] array, int offset, int x) {
        array[offset + 0] = (byte) (x >> 24 & 0xff);
        array[offset + 1] = (byte) (x >> 16 & 0xff);
        array[offset + 2] = (byte) (x >> 8 & 0xff);
        array[offset + 3] = (byte) (x >> 0 & 0xff);
    }

    public static SkinTexture loadTexture(InputStream in) throws IOException {
        if (in == null) return null;
        Image img;
        try (InputStream is = in) {
            img = new Image(is);
        }

        if (img.isError()) {
            throw new IOException("No image found", img.getException());
        }

        // 等待后台加载完成（JavaFX Image 从 InputStream 构造是异步的）
        waitForImage(img);

        if (img.isError()) {
            throw new IOException("Image loading failed", img.getException());
        }

        return loadTexture(img);
    }

    /**
     * 等待 JavaFX Image 后台加载完成
     */
    private static void waitForImage(Image image) {
        // 已加载完成或出错，无需等待
        if (image.getProgress() >= 1.0 || image.isError()) return;

        // 轮询等待加载完成（最多等待 5 秒，本地图片通常 <10ms）
        for (int i = 0; i < 500; i++) {
            if (image.getProgress() >= 1.0) return;
            if (image.isError()) return;
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public static SkinTexture loadTexture(Image image) {
        if (image == null) return null;

        // 等待后台加载完成
        waitForImage(image);
        if (image.isError()) return null;

        String hash = computeTextureHash(image);

        SkinTexture existent = textures.get(hash);
        if (existent != null) {
            return existent;
        }

        SkinTexture texture = new SkinTexture(hash, image);
        existent = textures.putIfAbsent(hash, texture);

        if (existent != null) {
            return existent;
        }
        return texture;
    }

    public static void clearCache() {
        textures.clear();
    }
}
