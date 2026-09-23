/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.service;

import com.example.starlight.gui.UIGeneralControlClass;
import com.example.starlight.model.JavaInfo;
import com.example.starlight.installjava.JavaInstall;
import com.example.starlight.listjava.FindAllJavaWindows;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Java 运行时管理服务 — 查找与安装JDK
 */
public class JavaService {

    /** 查找本机所有Java 安装 */
    public static List<JavaInfo> findAllJava() {
        // 直接使用结构化 API（FindAllJavaWindows.findAll），
        // 不重定向全局 System.out、不解析控制台文本，避免影响启动器其他线程输出
        List<JavaInfo> result = new ArrayList<>();
        for (FindAllJavaWindows.JavaEntry entry : FindAllJavaWindows.findAll()) {
            result.add(new JavaInfo(entry.homePath, entry.version));
        }
        return result;
    }

    /** 异步安装 Java */
    public static void installJavaAsync(String version, String downloadDir,
                                         com.example.starlight.model.CallbackInterfaces.ProgressCallback onProgress,
                                         com.example.starlight.model.CallbackInterfaces.ResultCallback<String> onResult) {
        // 使用启动器专用线程池执行，避免长时间下载占用 ForkJoinPool 公共线程
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                // 内嵌模式：JavaInstall 失败时抛异常而非 System.exit，避免杀死启动器进程
                System.setProperty("starlight.embed", "true");
                ByteArrayOutputStream baos = new ByteArrayOutputStream() {
                    private int lastPercent = -1;
                    @Override
                    public synchronized void write(byte[] b, int off, int len) {
                        super.write(b, off, len);
                        String chunk = new String(b, off, len, StandardCharsets.UTF_8);
                        int pIdx = chunk.indexOf('%');
                        if (pIdx > 0) {
                            for (int i = pIdx - 1; i >= 0; i--) {
                                if (!Character.isDigit(chunk.charAt(i))) {
                                    try {
                                        int pct = Integer.parseInt(chunk.substring(i + 1, pIdx));
                                        if (pct != lastPercent) { lastPercent = pct; onProgress.onProgress(pct, chunk.trim()); }
                                    } catch (NumberFormatException ignored) {}
                                    break;
                                }
                            }
                        }
                        if (chunk.contains("ERROR") || chunk.contains("失败") || chunk.contains("完成")) {
                            onProgress.onProgress(lastPercent < 0 ? 50 : lastPercent, chunk.trim());
                        }
                    }
                };
                // 只注入 JavaInstall 自身的输出流，不重定向全局 System.out
                PrintStream captured = new PrintStream(baos, true, StandardCharsets.UTF_8);
                JavaInstall.setOutput(captured);
                List<String> args = new ArrayList<>(List.of(version, "--json"));
                if (downloadDir != null && !downloadDir.isEmpty()) args.add(downloadDir);
                JavaInstall.main(args.toArray(String[]::new));
                captured.flush();
                onProgress.onProgress(100, "安装完成");
                onResult.onSuccess("JDK " + version + " 安装完成");
            } catch (Exception e) {
                onResult.onError("安装失败: " + e.getMessage());
            } finally {
                // 恢复默认输出，避免影响后续调用
                JavaInstall.setOutput(System.out);
            }
        }, UIGeneralControlClass.ASYNC_POOL);
    }

    /** 列出可安装的 JDK 版本 */
    public static List<String> getAvailableJdkVersions() {
        return List.of("jdk8", "jdk17", "jdk21");
    }
}
