/*
 * sendlogs.java
 * 
 * 这个类负责发送错误日志到服务器并生成一个链接，然后调用QRcode类生成二维码，
 * 文件名格式为“日期+三位随机数字.png”，用户可以通过扫描二维码直接访问链接。
 * 
 * 使用示例：
 * java sendlogs "log文件完整路径" "日志上传地址（默认值见 /assets/endpoints.json 的 services.logUploadUrl）"
 */

package com.example.starlight.log;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Random;

import javax.imageio.ImageIO;

import com.google.zxing.WriterException;

public class sendlogs {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: java log.sendlogs <log file path> <server URL>");
            System.exit(1);
        }

        String logFilePath = args[0];
        String serverUrl = args[1];

        try {
            // 上传日志并获取服务器返回的链接
            String resultUrl = uploadLogFile(logFilePath, serverUrl);
            System.out.println("Upload successful, result link: " + resultUrl);

            // 生成二维码文件名：当前日期 + 三位随机数字 + .png
            String fileName = generateFileName();
            // 调用QRcode类生成二维码并保存
            BufferedImage qrImage = QRcode.generateQRCodeImage(resultUrl);
            File outputFile = new File(fileName);
            ImageIO.write(qrImage, "png", outputFile);
            System.out.println("QR code generated successfully!");
            System.out.println("Link: " + resultUrl);
            System.out.println("Saved to: " + outputFile.getAbsolutePath());

        } catch (IOException e) {
            System.err.println("Upload failed: " + e.getMessage());
            System.exit(1);
        } catch (WriterException e) {
            System.err.println("QR code generation failed: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * 生成二维码文件名，格式：yyyyMMdd + 三位随机数字 + .png
     */
    private static String generateFileName() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
        String dateStr = sdf.format(new Date());
        Random random = new Random();
        int randomNum = random.nextInt(1000); // 0-999
        String randomStr = String.format("%03d", randomNum);
        return dateStr + randomStr + ".png";
    }

    /**
     * 上传日志文件到服务器，返回服务器提供的链接地址
     */
    public static String uploadLogFile(String logFilePath, String serverUrl) throws IOException {
        File logFile = new File(logFilePath);
        if (!logFile.exists()) {
            throw new IOException("File does not exist: " + logFilePath);
        }

        // 构建 multipart/form-data 请求体
        String boundary = "----LogUploadBoundary" + System.currentTimeMillis();
        String lineFeed = "\r\n";

        URL url = new URL(serverUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (OutputStream os = conn.getOutputStream();
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(os, "UTF-8"), true);
             FileInputStream fis = new FileInputStream(logFile)) {

            // 写入文件字段
            writer.append("--").append(boundary).append(lineFeed);
            writer.append("Content-Disposition: form-data; name=\"logFile\"; filename=\"")
                  .append(logFile.getName()).append("\"").append(lineFeed);
            writer.append("Content-Type: ").append(Files.probeContentType(logFile.toPath()) 
                  != null ? Files.probeContentType(logFile.toPath()) : "application/octet-stream")
                  .append(lineFeed);
            writer.append(lineFeed);
            writer.flush();

            // 写入文件内容
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }
            os.flush();

            // 结束标记
            writer.append(lineFeed);
            writer.append("--").append(boundary).append("--").append(lineFeed);
            writer.flush();
        }

        int responseCode = conn.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                return response.toString();
            }
        } else {
            throw new IOException("Server error, HTTP " + responseCode);
        }
    }
}