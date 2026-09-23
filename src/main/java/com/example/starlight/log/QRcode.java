/*
 * QRcode.java
 *
 * 这个类负责生成分享错误日志链接的二维码。
 * java log.QRcode "错误日志链接" "保存文件名.png"
 */

package com.example.starlight.log;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;

public class QRcode {

    // 定义二维码的尺寸
    private static final int QR_CODE_SIZE = 300;

    // 主方法，程序入口
    public static void main(String[] args) throws IOException {

        //从sendlogs类获取返回链接参数
        String logFilePath = args[0];
        String serverUrl = args[1];
        // 验证命令行参数是否正确
        //if (args.length < 2) {
        //System.err.println("错误：缺少必要参数！");
        //System.exit(1);
        //}
        // 获取目标 URL 和输出文件名
        String targetUrl = sendlogs.uploadLogFile(logFilePath, serverUrl);
        String outputFileName = args[2];

        // 生成二维码并保存到指定文件
        try {
            BufferedImage qrImage = generateQRCodeImage(targetUrl);
            File outputFile = new File(outputFileName);
            ImageIO.write(qrImage, "png", outputFile);
            System.out.println("QR code generated successfully!");
            System.out.println("Link: " + targetUrl);
            System.out.println("Saved to: " + outputFile.getAbsolutePath());
        } catch (WriterException | IOException e) {
            System.err.println("QR code generation failed: " + e.getMessage());
            System.exit(1);
        }
    }

    // 生成二维码图像的方法
    public static BufferedImage generateQRCodeImage(String url) throws WriterException, IOException {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("URL cannot be empty");
        }

        // 设置二维码生成的参数
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");

        // 生成二维码矩阵
        BitMatrix bitMatrix = new MultiFormatWriter().encode(
                url,
                BarcodeFormat.QR_CODE,
                QR_CODE_SIZE,
                QR_CODE_SIZE,
                hints
        );

        // 将二维码矩阵转换为图像并返回
        return MatrixToImageWriter.toBufferedImage(bitMatrix);
    }
}