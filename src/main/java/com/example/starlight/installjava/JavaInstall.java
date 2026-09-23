package com.example.starlight.installjava;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * JDK自动下载工具 - 多镜像源版
 * 支持JDK8、JDK17、JDK21自动下载
 * 
 * 使用方法：
 * java -jar JavaInstall.jar jdk8 [下载目录]    - 下载JDK 8
 * java -jar JavaInstall.jar jdk17 [下载目录]   - 下载JDK 17
 * java -jar JavaInstall.jar jdk21 [下载目录]   - 下载JDK 21
 */
public class JavaInstall {
    
    // 硬编码下载地址（主源：清华镜像）
    private static final String JDK8_URL = "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/8/jdk/x64/windows/OpenJDK8U-jdk_x64_windows_hotspot_8u482b08.zip";
    private static final String JDK17_URL = "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/OpenJDK17U-jdk_x64_windows_hotspot_17.0.18_8.zip";
    private static final String JDK21_URL = "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk/x64/windows/OpenJDK21U-jdk_x64_windows_hotspot_21.0.10_7.zip";
    
    // 备用源：华为云镜像
    private static final String JDK8_BACKUP = "https://repo.huaweicloud.com/openjdk/8/jdk8u392-b08/OpenJDK8U-jdk_x64_windows_hotspot_8u392b08.zip";
    private static final String JDK17_BACKUP = "https://repo.huaweicloud.com/openjdk/17/jdk-17.0.9_9/OpenJDK17U-jdk_x64_windows_hotspot_17.0.9_9.zip";
    private static final String JDK21_BACKUP = "https://repo.huaweicloud.com/openjdk/21/jdk-21.0.1_12/OpenJDK21U-jdk_x64_windows_hotspot_21.0.1_12.zip";
    
    // 文件名
    private static final String JDK8_FILE = "OpenJDK8U-jdk_x64_windows_hotspot_8u482b08.zip";
    private static final String JDK17_FILE = "OpenJDK17U-jdk_x64_windows_hotspot_17.0.18_8.zip";
    private static final String JDK21_FILE = "OpenJDK21U-jdk_x64_windows_hotspot_21.0.10_7.zip";
    
    // 下载目录（可通过命令行指定）
    private static File downloadDir = new File(".");
    
    // 是否启用JSON格式输出（适合图形化面板解析）
    private static boolean jsonOutput = false;
    
    // 是否启用详细日志
    private static boolean verbose = true;

    // 输出流：可注入（被启动器内嵌调用时捕获进度输出），默认控制台
    private static PrintStream output = System.out;

    /** 注入输出流（null 恢复为控制台）。被启动器内嵌调用时用于捕获输出，避免重定向全局 System.out */
    public static void setOutput(PrintStream out) {
        output = out != null ? out : System.out;
    }

    /** 是否以内嵌方式运行（被启动器进程内调用）：为 true 时失败抛异常而非 System.exit，避免杀死宿主 JVM */
    private static boolean isEmbedded() {
        return Boolean.getBoolean("starlight.embed");
    }
    
    public static void main(String[] args) {
        // 检查命令行参数
        if (args.length == 0) {
            printUsage();
            System.out.println("\n[WARNING] Running this JAR directly does nothing.");
            System.out.println("[INFO] Specify the JDK version and download directory via command line.");
            System.out.println("[INFO] For GUI support, use the --json parameter");
            return;
        }
        
        // 解析参数
        String command = null;
        String customDir = null;
        
        for (String arg1 : args) {
            String arg = arg1.toLowerCase();
            if (arg.equals("--json")) {
                jsonOutput = true;
            } else if (arg.equals("--quiet") || arg.equals("-q")) {
                verbose = false;
            } else if (command == null && !arg.startsWith("--") && !arg.startsWith("-")) {
                command = arg1;
            } else if (customDir == null && !arg.startsWith("--") && !arg.startsWith("-")) {
                customDir = arg1;
            }
        }
        
        // 设置下载目录
        if (customDir != null) {
            downloadDir = new File(customDir);
            if (!downloadDir.exists()) {
                downloadDir.mkdirs();
            }
        }
        
        if (command == null) {
            printError("No download command specified");
            return;
        }
        
        // 执行命令
        switch (command.toLowerCase()) {
            case "jdk8", "8" -> downloadJDK("JDK 8", JDK8_URL, JDK8_BACKUP, JDK8_FILE);
            case "jdk17", "17" -> downloadJDK("JDK 17", JDK17_URL, JDK17_BACKUP, JDK17_FILE);
            case "jdk21", "21" -> downloadJDK("JDK 21", JDK21_URL, JDK21_BACKUP, JDK21_FILE);
            case "list" -> listAvailableVersions();
            case "help", "-h", "--help" -> printUsage();
            default -> {
                printError("Unknown command: " + command);
                printUsage();
                if (isEmbedded()) {
                    throw new IllegalArgumentException("未知命令: " + command);
                }
                System.exit(1);
            }
        }
    }
    
    /**
     * 打印使用说明
     */
    private static void printUsage() {
        log("╔════════════════════════════════════════════════════════╗");
        log("║           JDK自动下载工具 (JavaInstall v3.1)            ║");
        log("║              使用多镜像源加速                           ║");
        log("╚════════════════════════════════════════════════════════╝");
        log("");
        log("用法: java -jar JavaInstall.jar [选项] <命令> [下载目录]");
        log("");
        log("选项:");
        log("  --json          启用JSON格式输出（适合图形化面板）");
        log("  --quiet, -q     静默模式，只输出关键信息");
        log("");
        log("命令:");
        log("  jdk8, 8         下载 JDK 8 (LTS)");
        log("  jdk17, 17       下载JDK 17 (LTS)");
        log("  jdk21, 21       下载JDK 21 (LTS)");
        log("  list            列出所有可用版本");
        log("  help            显示此帮助信息");
        log("");
        log("示例:");
        log("  java -jar JavaInstall.jar jdk17");
        log("  java -jar JavaInstall.jar jdk8 D:\\Java\\JDK");
        log("  java -jar JavaInstall.jar --json jdk21 /opt/java");
        log("");
        log("当前默认下载目录: " + downloadDir.getAbsolutePath());
    }
    
    /**
     * 列出可用版本
     */
    private static void listAvailableVersions() {
        log("可用的JDK版本:");
        log("  • JDK 8  - Java 8 (长期支持版)");
        log("  • JDK 17 - Java 17 (长期支持版)");
        log("  • JDK 21 - Java 21 (长期支持版)");
    }
    
    /**
     * 下载指定JDK（带备用源）
     */
    @SuppressWarnings("CallToPrintStackTrace")
    private static void downloadJDK(String jdkName, String primaryUrl, String backupUrl, String fileName) {
        printProgress("START", jdkName, "Starting download of " + jdkName, 0);
        
        File outputFile = new File(downloadDir, fileName);
        File extractDir = new File(downloadDir, jdkName.replace(" ", "_"));
        
        printProgress("INFO", jdkName, "Primary source: " + primaryUrl, 0);
        printProgress("INFO", jdkName, "Backup source: " + backupUrl, 0);
        printProgress("INFO", jdkName, "Target file: " + outputFile.getAbsolutePath(), 0);
        printProgress("INFO", jdkName, "Extract directory: " + extractDir.getAbsolutePath(), 0);
        
        // 检查文件是否已存在
        if (outputFile.exists()) {
            long fileSize = outputFile.length();
            printProgress("EXIST", jdkName, "File already exists (" + formatFileSize(fileSize) + "), skipping download", 100);
        } else {
            // 尝试主源下载
            boolean success = downloadFromUrl(primaryUrl, outputFile, jdkName);
            
            // 如果失败，尝试备用源
            if (!success) {
                printProgress("RETRY", jdkName, "Primary source failed, trying backup source...", 0);
                success = downloadFromUrl(backupUrl, outputFile, jdkName);
            }
            
            if (!success) {
                printProgress("ERROR", jdkName, "All download sources failed", 0);
                if (isEmbedded()) {
                    throw new IllegalStateException("JDK 下载失败（主源与备用源均不可用）: " + jdkName);
                }
                System.exit(1);
            }
        }
        
        // 解压文件
        printProgress("EXTRACT", jdkName, "Extracting " + fileName + "...", 0);
        try {
            unzip(outputFile, extractDir, jdkName);
            printProgress("SUCCESS", jdkName, "Extraction complete! Install path: " + extractDir.getAbsolutePath(), 100);
            
            // 验证安装
            verifyInstallation(extractDir, jdkName);
            
        } catch (IOException e) {
            printProgress("ERROR", jdkName, "Extraction failed: " + e.getMessage(), 0);
            e.printStackTrace();
        }
    }
    
    /**
     * 从指定URL下载文件，带实时进度
     */
    private static boolean downloadFromUrl(String urlString, File outputFile, String jdkName) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            
            // 设置请求头（模拟浏览器）
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
            connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            connection.setRequestProperty("Accept-Encoding", "gzip, deflate, br");
            connection.setRequestProperty("Connection", "keep-alive");
            connection.setRequestProperty("Upgrade-Insecure-Requests", "1");
            
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(120000); // 增加读取超时时间
            connection.setInstanceFollowRedirects(true);
            
            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                printProgress("WARN", jdkName, "HTTP error code: " + responseCode + " (" + urlString + ")", 0);
                return false;
            }
            
            long fileSize = connection.getContentLengthLong();
            String contentType = connection.getContentType();
            printProgress("META", jdkName, String.format("File size: %s | Type: %s | Source: %s",
                formatFileSize(fileSize), contentType, url.getHost()), 0);
            
            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream out = new FileOutputStream(outputFile)) {
                
                byte[] buffer = new byte[8192];
                long downloaded = 0;
                long startTime = System.currentTimeMillis();
                long lastPrintTime = 0;
                int lastPercent = 0;
                
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    downloaded += bytesRead;
                    
                    long currentTime = System.currentTimeMillis();
                    int currentPercent = fileSize > 0 ? (int) ((downloaded * 100) / fileSize) : 0;
                    
                    // 每500ms或每1%更新一次进度
                    if (currentTime - lastPrintTime > 500 || currentPercent > lastPercent) {
                        double speed = downloaded / ((currentTime - startTime) / 1000.0 + 0.001);
                        String speedStr = formatFileSize((long) speed) + "/s";
                        String eta = calculateETA(fileSize, downloaded, startTime, currentTime);
                        
                        String detail = String.format("已下载: %s / %s (%d%%) | 速度: %s | 预计剩余: %s",
                            formatFileSize(downloaded), formatFileSize(fileSize), currentPercent, speedStr, eta);
                        
                        printProgress("DOWNLOAD", jdkName, detail, currentPercent);
                        
                        lastPrintTime = currentTime;
                        lastPercent = currentPercent;
                    }
                }
                out.flush();
                
                long totalTime = (System.currentTimeMillis() - startTime) / 1000;
                printProgress("COMPLETE", jdkName, 
                    String.format("下载完成！总大小: %s | 用时: %d秒", formatFileSize(downloaded), totalTime), 100);
            }
            
            return true;
            
        } catch (IOException e) {
            printProgress("ERROR", jdkName, "Download error: " + e.getMessage(), 0);
            if (outputFile.exists()) {
                outputFile.delete();
            }
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
    
    /**
     * 解压ZIP文件，带进度显示
     */
    private static void unzip(File zipFile, File destDir, String jdkName) throws IOException {
        if (!destDir.exists()) {
            destDir.mkdirs();
        }
        
        long totalSize = zipFile.length();
        long extractedSize = 0;
        int fileCount = 0;
        long lastPrintTime = System.currentTimeMillis();
        
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File newFile = new File(destDir, entry.getName());
                
                if (entry.isDirectory()) {
                    newFile.mkdirs();
                } else {
                    new File(newFile.getParent()).mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(newFile)) {
                        byte[] buffer = new byte[1024];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                            extractedSize += len;
                        }
                    }
                    fileCount++;
                }
                zis.closeEntry();
                
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastPrintTime > 100) {
                    int percent = (int) ((extractedSize * 100) / (totalSize * 2));
                    printProgress("EXTRACT", jdkName, 
                        String.format("正在解压: %s | 已处理 %d 个文件", entry.getName(), fileCount), 
                        Math.min(percent, 99));
                    lastPrintTime = currentTime;
                }
            }
        }
        
        printProgress("EXTRACTED", jdkName, String.format("Extraction complete! %d files total", fileCount), 100);
    }
    
    /**
     * 验证安装
     */
    private static void verifyInstallation(File extractDir, String jdkName) {
        File[] files = extractDir.listFiles();
        if (files == null) return;
        
        for (File file : files) {
            if (file.isDirectory()) {
                File javaExe = new File(file, "bin/java.exe");
                if (!javaExe.exists()) {
                    javaExe = new File(file, "bin/java");
                }
                
                if (javaExe.exists()) {
                    try {
                        Process process = new ProcessBuilder(javaExe.getAbsolutePath(), "-version")
                                .redirectErrorStream(true)
                                .start();
                        
                        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                        StringBuilder versionInfo = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            versionInfo.append(line).append("; ");
                        }
                        process.waitFor();
                        
                        printProgress("VERIFY", jdkName, "Version verification: " + versionInfo.toString(), 100);
                    } catch (IOException | InterruptedException e) {
                        printProgress("WARN", jdkName, "Version verification failed: " + e.getMessage(), 100);
                    }
                    break;
                }
            }
        }
    }
    
    /**
     * 计算预计剩余时间
     */
    private static String calculateETA(long totalSize, long downloaded, long startTime, long currentTime) {
        if (totalSize <= 0 || downloaded <= 0) return "计算中...";
        
        long elapsed = currentTime - startTime;
        double speed = (double) downloaded / elapsed;
        long remaining = (long) ((totalSize - downloaded) / speed);
        
        if (remaining < 1000) return remaining + "ms";
        if (remaining < 60000) return (remaining / 1000) + "秒";
        return String.format("%d分%d秒", remaining / 60000, (remaining % 60000) / 1000);
    }
    
    /**
     * 格式化文件大小
     */
    private static String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.2f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.2f MB", size / (1024.0 * 1024));
        return String.format("%.2f GB", size / (1024.0 * 1024 * 1024));
    }
    
    /**
     * 输出进度信息（支持普通格式和JSON格式）
     */
    private static void printProgress(String status, String task, String message, int percent) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        
        if (jsonOutput) {
            String json = String.format(
                "{\"timestamp\":\"%s\",\"status\":\"%s\",\"task\":\"%s\",\"message\":\"%s\",\"percent\":%d}",
                timestamp, status, escapeJson(task), escapeJson(message), percent
            );
            output.println(json);
        } else {
            if (verbose) {
                output.printf("[%s] [%s] [%s] %s%n", timestamp, status, task, message);
            } else if (percent % 10 == 0 || status.equals("ERROR") || status.equals("SUCCESS")) {
                output.printf("[%s] %s%n", status, message);
            }
        }
    }
    
    /**
     * 转义JSON字符串
     */
    private static String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
    
    /**
     * 普通日志输出
     */
    private static void log(String message) {
        if (verbose && !jsonOutput) {
            output.println(message);
        }
    }
    
    /**
     * 错误输出
     */
    private static void printError(String message) {
        if (jsonOutput) {
            printProgress("ERROR", "System", message, 0);
        } else {
            output.println("[ERROR] " + message);
        }
    }
}
