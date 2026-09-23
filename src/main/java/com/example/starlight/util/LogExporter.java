/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.util;

import com.example.starlight.crash.CrashDiagnosticData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 日志导出工具 — 将崩溃相关日志打包为 .zip 文件
 * 参考 HMCL 的 LogExporter 实现思路
 */
public class LogExporter {

    private static final Logger log = LoggerFactory.getLogger(LogExporter.class);

    /**
     * 导出崩溃诊断数据及相关日志到 .zip 文件
     *
     * @param data      崩溃诊断数据
     * @param outputDir 输出目录
     * @return 生成的 .zip 文件绝对路径，失败返回 null
     */
    public static String exportCrashLogs(CrashDiagnosticData data, String outputDir) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss"));
        String zipName = "crash-report-" + timestamp + ".zip";
        Path zipPath = Paths.get(outputDir, zipName);

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath.toFile()))) {
            // 1. 写入 crash 摘要信息
            addZipEntry(zos, "crash_summary.txt", data.buildSummaryText());

            // 2. 写入启动命令
            if (data.getLaunchCommand() != null) {
                addZipEntry(zos, "launch_command.txt", data.getLaunchCommand());
            }

            // 3. 写入 crash-report 原文
            if (data.getCrashReportContent() != null) {
                addZipEntry(zos, "crash_report_raw.txt", data.getCrashReportContent());
            } else if (data.getCrashReportPath() != null) {
                addFileToZip(zos, Paths.get(data.getCrashReportPath()), "crash_report_raw.txt");
            }

            // 4. 写入 latest.log 末尾
            if (data.getLatestLogTail() != null && !data.getLatestLogTail().isEmpty()) {
                StringBuilder logContent = new StringBuilder();
                for (String line : data.getLatestLogTail()) {
                    logContent.append(line).append(System.lineSeparator());
                }
                addZipEntry(zos, "latest_log_tail.txt", logContent.toString());
            }

            // 5. 直接复制 latest.log 原文
            if (data.getLatestLogPath() != null) {
                addFileToZip(zos, Paths.get(data.getLatestLogPath()), "latest.log");
            }

            log.info("Crash log exported {}", zipPath.toAbsolutePath());
            return zipPath.toAbsolutePath().toString();

        } catch (IOException e) {
            log.error("Failed to export crash log", e);
            return null;
        }
    }

    /**
     * 将文本内容写入 ZIP 条目
     */
    private static void addZipEntry(ZipOutputStream zos, String entryName, String content) throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
        zos.putNextEntry(entry);
        zos.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    /**
     * 将外部文件复制到 ZIP 中
     */
    private static void addFileToZip(ZipOutputStream zos, Path filePath, String entryName) throws IOException {
        if (!Files.exists(filePath) || !Files.isReadable(filePath)) {
            log.warn("File does not exist or is not readable, skipped: {}", filePath);
            return;
        }
        ZipEntry entry = new ZipEntry(entryName);
        zos.putNextEntry(entry);
        Files.copy(filePath, zos);
        zos.closeEntry();
    }

    /**
     * 在资源管理器中打开指定文件
     */
    public static void openInExplorer(String filePath) {
        try {
            File file = new File(filePath);
            if (file.exists()) {
                Runtime.getRuntime().exec("explorer.exe /select,\"" + file.getAbsolutePath() + "\"");
            } else {
                // 如果文件不存在，打开父目录
                String parent = file.getParent();
                if (parent != null) {
                    Runtime.getRuntime().exec("explorer.exe \"" + parent + "\"");
                }
            }
        } catch (IOException e) {
            log.warn("Failed to open Explorer");
        }
    }
}
