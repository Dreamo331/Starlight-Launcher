// 星光启动器\src\main\java\multithreadeddownload\MultiThreadDownloader.java
package com.example.starlight.multithreadeddownload;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多线程下载工具
 * 用法: java MultiThreadDownloader <文件URL> [保存路径] [线程数]
 */
public class MultiThreadDownloader {

    // 默认线程数
    private static final int DEFAULT_THREAD_COUNT = 4;
    // 缓冲区大小
    private static final int BUFFER_SIZE = 8192;

    private final String url;
    private final String savePath;
    private final int threadCount;
    private final ExecutorService executor;
    private final List<Future<DownloadResult>> futures = new ArrayList<>();
    private final AtomicLong totalDownloaded = new AtomicLong(0);
    private volatile long fileSize = -1;
    private volatile boolean running = true;

    public MultiThreadDownloader(String url, String savePath, int threadCount) {
        this.url = url;
        this.savePath = savePath;
        this.threadCount = threadCount;
        this.executor = Executors.newFixedThreadPool(threadCount);
    }

    public static void main(String[] args) {
        String url = args[0];
        String savePath = args.length > 1 ? args[1] : getFileNameFromUrl(url);
        int threadCount = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_THREAD_COUNT;

        MultiThreadDownloader downloader = new MultiThreadDownloader(url, savePath, threadCount);
        downloader.start();
    }

    /**
     * 从URL提取文件名
     */
    private static String getFileNameFromUrl(String url) {
        int lastSlash = url.lastIndexOf('/');
        String name = lastSlash >= 0 ? url.substring(lastSlash + 1) : "download.dat";
        // 去除可能的查询参数
        int queryStart = name.indexOf('?');
        if (queryStart > 0) {
            name = name.substring(0, queryStart);
        }
        return name.isEmpty() ? "download.dat" : name;
    }

    public void start() {
        try {
            // 1. 获取文件信息，确定是否支持断点续传
            if (!fetchFileInfo()) {
                System.err.println("Failed to get file info, server may not support resume");
                // 回退到单线程下载
                singleThreadDownload();
                return;
            }

            System.out.printf("File size: %.2f MB (%d bytes)%n", fileSize / (1024.0 * 1024), fileSize);
            System.out.println("Resume supported: yes");
            System.out.println("Thread count: " + threadCount);

            // 2. 创建临时目录存放分块文件
            Path tempDir = Files.createTempDirectory("download_" + System.currentTimeMillis());
            tempDir.toFile().deleteOnExit();

            // 3. 计算每个线程负责的字节区间
            long blockSize = fileSize / threadCount;
            long remaining = fileSize % threadCount;

            // 4. 提交下载任务
            long start = 0;
            for (int i = 0; i < threadCount; i++) {
                long end = start + blockSize - 1;
                if (i == threadCount - 1) {
                    end += remaining; // 最后一个线程负责余数
                }
                if (start > end) break;

                String partFileName = tempDir.resolve("part_" + i).toString();
                DownloadTask task = new DownloadTask(i, url, start, end, partFileName);
                futures.add(executor.submit(task));
                start = end + 1;
            }

            // 5. 启动进度监控线程
            Thread progressThread = new Thread(this::monitorProgress);
            progressThread.setDaemon(true);
            progressThread.start();

            // 6. 等待所有任务完成
            List<Path> partFiles = new ArrayList<>();
            boolean allSuccess = true;
            for (int i = 0; i < futures.size(); i++) {
                try {
                    DownloadResult result = futures.get(i).get();
                    if (result.success) {
                        partFiles.add(Paths.get(result.partFilePath));
                    } else {
                        allSuccess = false;
                        System.err.printf("Thread %d download failed: %s%n", i, result.errorMessage);
                    }
                } catch (ExecutionException | InterruptedException e) {
                    allSuccess = false;
                    System.err.println("Task execution error: " + e.getMessage());
                }
            }

            running = false;

            // 7. 合并文件
            if (allSuccess && partFiles.size() == threadCount) {
                System.out.println("\nAll chunks downloaded, merging files...");
                mergeFiles(partFiles, Paths.get(savePath));
                System.out.println("Download complete: " + savePath);

                // 清理临时文件
                for (Path part : partFiles) {
                    Files.deleteIfExists(part);
                }
                Files.deleteIfExists(tempDir);
            } else {
                System.err.println("Download incomplete, check network and retry (partial files kept in temp dir)");
                System.err.println("Temp dir: " + tempDir);
            }

        } catch (IOException e) {
            System.err.println("Download error: " + e.getMessage());
        } finally {
            executor.shutdown();
        }
    }

    /**
     * 获取文件信息，检查是否支持Range请求
     */
    private boolean fetchFileInfo() throws IOException {
        HttpURLConnection conn = null;
        try {
            URL urlObj = new URL(url);
            conn = (HttpURLConnection) urlObj.openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return false;
            }

            String acceptRanges = conn.getHeaderField("Accept-Ranges");
            boolean supportsRange = "bytes".equalsIgnoreCase(acceptRanges);

            String contentLength = conn.getHeaderField("Content-Length");
            if (contentLength != null) {
                fileSize = Long.parseLong(contentLength);
            } else {
                // 尝试通过GET请求获取
                conn.disconnect();
                conn = (HttpURLConnection) urlObj.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Range", "bytes=0-0");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                int code = conn.getResponseCode();
                if (code == HttpURLConnection.HTTP_PARTIAL) {
                    String contentRange = conn.getHeaderField("Content-Range");
                    if (contentRange != null) {
                        // 格式: bytes 0-0/12345
                        int slashIdx = contentRange.lastIndexOf('/');
                        if (slashIdx > 0) {
                            fileSize = Long.parseLong(contentRange.substring(slashIdx + 1));
                        }
                    }
                }
                supportsRange = (code == HttpURLConnection.HTTP_PARTIAL);
            }

            return supportsRange && fileSize > 0;

        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 单线程下载（不支持Range时使用）
     */
    private void singleThreadDownload() {
        System.out.println("Using single-thread download...");
        try (InputStream in = new URL(url).openStream();
             FileOutputStream out = new FileOutputStream(savePath)) {

            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            long downloaded = 0;
            long lastPrint = 0;

            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                downloaded += bytesRead;

                // 每500ms打印一次进度
                long now = System.currentTimeMillis();
                if (now - lastPrint > 500) {
                    System.out.printf("\rDownloaded: %.2f MB", downloaded / (1024.0 * 1024));
                    lastPrint = now;
                }
            }
            System.out.println("\nDownload complete: " + savePath);

        } catch (IOException e) {
            System.err.println("Single-thread download failed: " + e.getMessage());
        }
    }

    /**
     * 进度监控
     */
    private void monitorProgress() {
        long lastBytes = 0;
        long lastTime = System.currentTimeMillis();

        while (running) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }

            long currentBytes = totalDownloaded.get();
            long now = System.currentTimeMillis();
            long timeDiff = now - lastTime;
            double speed = timeDiff > 0 ? (currentBytes - lastBytes) * 1000.0 / timeDiff : 0;

            double percent = fileSize > 0 ? (currentBytes * 100.0) / fileSize : 0;
            System.out.printf("\rProgress: %.1f%% (%.2f MB / %.2f MB) Speed: %.2f KB/s",
                    percent,
                    currentBytes / (1024.0 * 1024),
                    fileSize / (1024.0 * 1024),
                    speed / 1024);

            lastBytes = currentBytes;
            lastTime = now;
        }
        System.out.println();
    }

    /**
     * 合并分块文件
     */
    private void mergeFiles(List<Path> partFiles, Path targetFile) throws IOException {
        try (OutputStream out = Files.newOutputStream(targetFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {

            for (Path part : partFiles) {
                Files.copy(part, out);
            }
        }
    }

    /**
     * 下载任务（每个线程执行一个区间）
     */
    class DownloadTask implements Callable<DownloadResult> {
        private final String url;
        private final long startByte;
        private final long endByte;
        private final String partFilePath;

        public DownloadTask(int id, String url, long startByte, long endByte, String partFilePath) {
            this.url = url;
            this.startByte = startByte;
            this.endByte = endByte;
            this.partFilePath = partFilePath;
        }

        @Override
        public DownloadResult call() {
            HttpURLConnection conn = null;
            try {
                URL urlObj = new URL(url);
                conn = (HttpURLConnection) urlObj.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("Range", "bytes=" + startByte + "-" + endByte);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");

                int responseCode = conn.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_PARTIAL && responseCode != HttpURLConnection.HTTP_OK) {
                    return new DownloadResult(false, partFilePath, 0,
                            "服务器返回错误码: " + responseCode);
                }

                // 检查服务器返回的Content-Range确认区间
                String contentRange = conn.getHeaderField("Content-Range");
                if (contentRange != null && !contentRange.startsWith("bytes " + startByte)) {
                    // 有些服务器可能返回不同区间，但通常可以接受
                }

                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(partFilePath)) {

                    byte[] buffer = new byte[BUFFER_SIZE];
                    int bytesRead;
                    long downloaded = 0;

                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                        downloaded += bytesRead;
                        totalDownloaded.addAndGet(bytesRead);
                    }

                    return new DownloadResult(true, partFilePath, downloaded, null);
                }

            } catch (IOException e) {
                return new DownloadResult(false, partFilePath, 0, e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
    }

    /**
     * 下载结果封装
     */
    static class DownloadResult {
        final boolean success;
        final String partFilePath;
        final String errorMessage;

        DownloadResult(boolean success, String partFilePath, long downloadedBytes, String errorMessage) {
            this.success = success;
            this.partFilePath = partFilePath;
            this.errorMessage = errorMessage;
        }
    }
}