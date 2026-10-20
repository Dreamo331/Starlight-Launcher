package com.example.starlight.download.mod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 本地已存在文件复用（对标 PCL CheckExistingFiles）。
 *
 * <p>下载前扫描已有 MC 目录（各版本文件夹），对每个有校验信息的文件，按
 * 「同相对路径 + 同大小 + SHA1」查找已有文件；命中则<b>复制</b>到目标（进度权重只算 0.2
 * 而非网络文件的 1.0），<b>不进网络队列</b>。
 *
 * <p>并发：每 40 个文件一个检查线程、最多 8 线程（PCL 同款）。
 * 复用 {@link FileVerifier} 判定候选文件是否真的一致。
 */
public final class LocalFileReuse {

    /** 每个检查线程处理的文件数 */
    public static final int FILES_PER_THREAD = 40;

    /** 检查线程上限 */
    public static final int MAX_CHECK_THREADS = 8;

    private LocalFileReuse() {
    }

    /** 单个复用查询：相对路径 + 期望大小 + 期望 SHA1 */
    public static final class ReuseQuery {
        public final Path relativePath;
        public final long expectedSize; // -1 未知
        public final String expectedSha1;
        /** 命中的已有文件（执行后回填）；未命中为 null */
        public volatile Path matchedSource;

        public ReuseQuery(Path relativePath, long expectedSize, String expectedSha1) {
            this.relativePath = relativePath;
            this.expectedSize = expectedSize;
            this.expectedSha1 = expectedSha1;
        }
    }

    /**
     * 在各 MC 根目录下查找「同相对路径 + 同大小 + SHA1」的已有文件。
     *
     * @param mcRoots      已有 MC 目录列表（.minecraft 及其各版本文件夹）
     * @param relativePath 相对路径
     * @param expectedSize 期望大小（-1 不卡大小）
     * @param expectedSha1 期望 SHA1（40 位十六进制）
     * @return 命中的已有文件路径；未命中为空
     */
    public static Optional<Path> findOne(List<Path> mcRoots, Path relativePath, long expectedSize, String expectedSha1) {
        for (Path root : mcRoots) {
            Path candidate = root.resolve(relativePath);
            if (!Files.isRegularFile(candidate)) continue;
            // 先大小粗筛，再 SHA1 精校（FileVerifier 复用）
            if (expectedSize >= 0) {
                try {
                    if (Files.size(candidate) != expectedSize) continue;
                } catch (IOException e) {
                    continue;
                }
            }
            if (FileVerifier.verify(candidate, expectedSize, 0, expectedSha1).isEmpty()) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * 批量复用：并发扫描所有查询，命中者复制到 {@code targetDir.resolve(relativePath)}。
     *
     * @param mcRoots  已有 MC 目录列表
     * @param queries  待查文件列表（matchedSource 回填命中源）
     * @param targetDir 复用文件复制到的目标根目录
     * @return 命中并完成复制的查询列表
     */
    public static List<ReuseQuery> reuseAll(List<Path> mcRoots, List<ReuseQuery> queries, Path targetDir)
            throws InterruptedException, IOException {
        List<ReuseQuery> hits = new ArrayList<>();
        if (queries == null || queries.isEmpty()) return hits;
        // 每 40 个文件一个检查线程，最多 8 线程
        int threads = Math.min(MAX_CHECK_THREADS, Math.max(1, (queries.size() + FILES_PER_THREAD - 1) / FILES_PER_THREAD));
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "sl-local-reuse");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Exception> errors = new ArrayList<>();
            for (ReuseQuery q : queries) {
                pool.submit(() -> {
                    try {
                        Optional<Path> hit = findOne(mcRoots, q.relativePath, q.expectedSize, q.expectedSha1);
                        if (hit.isPresent()) {
                            q.matchedSource = hit.get();
                            Path dest = targetDir.resolve(q.relativePath);
                            Files.createDirectories(dest.getParent());
                            Files.copy(q.matchedSource, dest,
                                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                            synchronized (hits) {
                                hits.add(q);
                            }
                        }
                    } catch (IOException e) {
                        synchronized (errors) {
                            errors.add(e);
                        }
                    }
                    return null;
                });
            }
            pool.shutdown();
            if (!pool.awaitTermination(5, TimeUnit.MINUTES)) {
                pool.shutdownNow();
            }
        } finally {
            pool.shutdownNow();
        }
        return hits;
    }
}
