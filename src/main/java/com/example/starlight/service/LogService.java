/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.service;

import com.example.starlight.config.Endpoints;
import com.example.starlight.log.sendlogs;
import com.example.starlight.log.QRcode;
import com.example.starlight.model.ActionResult;
import com.google.zxing.WriterException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Random;

/**
 * 日志上传与二维码服务
 */
public class LogService {

    private static final String DEFAULT_SERVER_URL = Endpoints.logUploadUrl();

    /** 上传日志并生成二维码 */
    public static ActionResult uploadLogAndGenerateQR(String logFilePath, String serverUrl) {
        try {
            if (serverUrl == null || serverUrl.isEmpty()) serverUrl = DEFAULT_SERVER_URL;
            String resultUrl = sendlogs.uploadLogFile(logFilePath, serverUrl);
            BufferedImage qrImage = QRcode.generateQRCodeImage(resultUrl);
            String fileName = new SimpleDateFormat("yyyyMMdd").format(new Date())
                    + String.format("%03d", new Random().nextInt(1000)) + ".png";
            File outputFile = new File(fileName);
            ImageIO.write(qrImage, "png", outputFile);
            return ActionResult.ok("上传成功", new LogUploadResult(resultUrl, qrImage, outputFile.getAbsolutePath()));
        } catch (WriterException | IOException e) {
            return ActionResult.fail("上传失败: " + e.getMessage());
        }
    }

    /** 仅上传日志 */
    public static ActionResult uploadLog(String logFilePath, String serverUrl) {
        try {
            if (serverUrl == null || serverUrl.isEmpty()) serverUrl = DEFAULT_SERVER_URL;
            String resultUrl = sendlogs.uploadLogFile(logFilePath, serverUrl);
            return ActionResult.ok("上传成功", resultUrl);
        } catch (IOException e) {
            return ActionResult.fail("上传失败: " + e.getMessage());
        }
    }

    /** 日志上传结果 */
    public static class LogUploadResult {
        public final String url;
        public final BufferedImage qrImage;
        public final String qrFilePath;
        public LogUploadResult(String url, BufferedImage qrImage, String qrFilePath) {
            this.url = url;
            this.qrImage = qrImage;
            this.qrFilePath = qrFilePath;
        }
    }
}
