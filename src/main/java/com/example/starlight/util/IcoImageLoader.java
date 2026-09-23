package com.example.starlight.util;

import javafx.scene.image.Image;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 用于加载 .ico 格式图标文件的工具类。
 * JavaFX 的 Image 类不直接支持 .ico 格式，
 * 因此需要从 .ico 文件中解析出内嵌的 PNG 图像数据。
 */
public class IcoImageLoader {

    private static final int PNG_SIGNATURE = 0x89504E47; // PNG 文件头的前4个字节

    /**
     * 从 .ico 文件中读取第一个/最大的 PNG 图像并创建 JavaFX Image。
     *
     * @param icoStream .ico 文件的输入流
     * @return 解析出的 JavaFX Image
     * @throws IOException 如果读取失败或不支持的格式
     */
    public static Image loadIcoAsImage(InputStream icoStream) throws IOException {
        DataInputStream dis = new DataInputStream(icoStream);
        try {
            // --- ICO 文件头 (6 bytes) ---
            dis.readUnsignedShort(); // reserved, 必须为 0
            int type = dis.readUnsignedShort(); // 1 = 图标, 2 = 光标
            int count = dis.readUnsignedShort(); // 图像数量

            if (type != 1) {
                throw new IOException("Not a valid ICO icon file");
            }
            if (count == 0) {
                throw new IOException("No image data in ICO file");
            }

            // --- 读取目录项，找到最大的图像 ---
            byte[][] entries = new byte[count][16];
            for (int i = 0; i < count; i++) {
                dis.readFully(entries[i]);
            }

            int bestIndex = 0;
            int bestWidth = 0;
            int bestHeight = 0;

            for (int i = 0; i < count; i++) {
                byte[] entry = entries[i];
                int w = entry[0] & 0xFF;
                int h = entry[1] & 0xFF;
                // 0 表示 256 像素
                int width = w == 0 ? 256 : w;
                int height = h == 0 ? 256 : h;
                if (width * height > bestWidth * bestHeight) {
                    bestWidth = width;
                    bestHeight = height;
                    bestIndex = i;
                }
            }

            byte[] bestEntry = entries[bestIndex];
            // size (4 bytes, little-endian)
            int size = (bestEntry[7] << 24) | (bestEntry[6] << 16)
                     | (bestEntry[5] << 8) | (bestEntry[4] & 0xFF);
            // offset (4 bytes, little-endian)
            int offset = (bestEntry[11] << 24) | (bestEntry[10] << 16)
                       | (bestEntry[9] << 8) | (bestEntry[8] & 0xFF);

            // --- 读取图像数据 ---
            byte[] imageData = new byte[size];
            // 跳过到 offset 位置
            int skipBytes = offset - 6 - count * 16;
            if (skipBytes > 0) {
                dis.skipBytes(skipBytes);
            }
            dis.readFully(imageData);

            // 检查是否为 PNG 格式（前4字节为 0x89504E47）
            int signature = ((imageData[0] & 0xFF) << 24)
                          | ((imageData[1] & 0xFF) << 16)
                          | ((imageData[2] & 0xFF) << 8)
                          | (imageData[3] & 0xFF);

            if (signature == PNG_SIGNATURE) {
                // 直接作为 PNG 创建 Image
                return new Image(new ByteArrayInputStream(imageData));
            } else {
                // 如果是 BMP 格式（以 BITMAPINFOHEADER 开头），尝试尝试用 PNG 包装
                // 现代 .ico 文件通常包含 PNG 数据，这里我们只支持 PNG
                throw new IOException("Image format in ICO not supported, only ICO files with embedded PNG are supported");
            }
        } finally {
            dis.close();
        }
    }
}
