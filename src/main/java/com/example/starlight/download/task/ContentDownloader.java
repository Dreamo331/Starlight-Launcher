package com.example.starlight.download.task;

import com.example.starlight.ModsApi.RemoteMod;
import com.example.starlight.download.engine.HttpDownloadEngine;
import com.example.starlight.modpack.PackFile;
import com.example.starlight.util.ArchiveUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;

/**
 * 下载中心（DownloadCenterPage）内嵌下载编排的抽取类（UI_EMBED-1）。
 *
 * <p>把原先直连 {@link HttpDownloadEngine} 的下载编排（模组 / 资源包 / 光影包 / 数据包本体、
 * 依赖批量下载、世界存档下载解压、整合包主体、整合包清单内文件并发下载）统一平移到本类，
 * 全部为无状态静态方法。UI 侧只保留对话框 / 进度条 / 取消 / 页面刷新。
 *
 * <p>设计约定：
 * <ul>
 *   <li>下载参数（{@link RemoteMod.Version} / URL / 目标 {@link Path} / 目录等）全部由参数传入，
 *       不从任何 UI 状态读取；</li>
 *   <li>进度通过 {@link IntConsumer}（0-100）回调，取消通过 {@link AtomicBoolean} 传入；</li>
 *   <li>阶段文案（如「正在解压...」）通过 {@link Consumer}{@code <String>} 回调到 UI，
 *       避免本类依赖 newui/gui 包；</li>
 *   <li>失败 / 取消以异常上抛（取消时 message 为 {@link #CANCELLED_MESSAGE}），由 UI 区分处理。</li>
 * </ul>
 *
 * <p>本类<b>不 import 任何 newui/gui 包</b>，可在任意后台线程调用。
 */
public final class ContentDownloader {

    private ContentDownloader() {
    }

    /** 下载取消的统一异常文案（透传 {@link HttpDownloadEngine}，供 UI catch 区分「取消」与「失败」） */
    public static final String CANCELLED_MESSAGE = HttpDownloadEngine.CANCELLED_MESSAGE;

    /**
     * 探测远程文件大小（供任务卡 {@code totalBytes} / 速度显示）。
     *
     * @param url 文件直链
     * @return 文件总大小；探测失败或 url 为空时返回 -1
     */
    public static long probeSize(String url) {
        if (url == null) {
            return -1;
        }
        return HttpDownloadEngine.probeFileSize(HttpDownloadEngine.HTTP_CLIENT, url);
    }

    /**
     * 探测整合包主体大小：与真实下载一致，优先探测镜像首选候选（{@code firstContentCandidate}）。
     *
     * @param url 整合包主体直链
     * @return 文件总大小；探测失败时返回 -1
     */
    public static long probeMirrorSize(String url) {
        return HttpDownloadEngine.probeFileSize(HttpDownloadEngine.HTTP_CLIENT,
                HttpDownloadEngine.firstContentCandidate(url));
    }

    /**
     * 下载模组 / 资源包 / 光影包 / 数据包本体。
     *
     * <p>对应原 {@code DownloadCenterPage.installRemoteContent} 的下载段：
     * 镜像优先（mcimirror）→ 官方直链兜底。
     *
     * @param ver      选中的版本（其 {@code file.url} 为官方直链）
     * @param dest     目标文件
     * @param progress 0-100 进度回调，可为 null
     * @param cancel   取消标志，可为 null
     */
    public static void downloadContent(RemoteMod.Version ver, Path dest,
                                       IntConsumer progress, AtomicBoolean cancel) throws IOException {
        HttpDownloadEngine.downloadContentFile(ver, dest, progress, cancel);
    }

    /**
     * 世界存档：下载 zip → 解压到 {@code saves/<世界名>}，返回最终存档目录。
     *
     * <p>对应原 {@code DownloadCenterPage.installWorld} 的下载 + 解压编排：
     * <ul>
     *   <li>zip 下载到临时目录后，经 {@code stageCb} 回调「正在解压...」再解压；</li>
     *   <li>解压结果若只有一个子目录则下钻一层（兼容「包一层同名文件夹」的 zip 结构）；</li>
     *   <li>复制到 {@code saves/} 下按存档名去重命名。</li>
     * </ul>
     * 临时目录在返回前自动清理。
     *
     * @param ver      选中的世界版本（含 zip 直链）
     * @param gameDir  游戏根目录
     * @param progress 0-100 进度回调
     * @param cancel   取消标志
     * @param stageCb  阶段文案回调（下载完成、开始解压时调用），可为 null
     * @return 世界存档最终落盘目录
     */
    public static Path installWorld(RemoteMod.Version ver, String gameDir,
                                    IntConsumer progress, AtomicBoolean cancel,
                                    Consumer<String> stageCb) throws IOException {
        Path tempDir = Files.createTempDirectory("sl-world-");
        try {
            Path zipFile = tempDir.resolve("world.zip");
            // 下载世界存档 zip
            HttpDownloadEngine.downloadContentFile(ver, zipFile, progress, cancel);

            if (stageCb != null) {
                stageCb.accept("正在解压...");
            }
            Path extractDir = tempDir.resolve("extracted");
            Files.createDirectories(extractDir);
            ArchiveUtils.unzipTo(zipFile, extractDir);

            // 定位世界根目录：单层包裹时下钻一层
            Path worldRoot = extractDir;
            try (var list = Files.list(extractDir)) {
                List<Path> children = list.collect(Collectors.toList());
                if (children.size() == 1 && Files.isDirectory(children.get(0))) {
                    worldRoot = children.get(0);
                }
            }

            Path savesDir = Paths.get(gameDir, "saves");
            Files.createDirectories(savesDir);
            Path dest = ArchiveUtils.uniqueDir(savesDir, ArchiveUtils.sanitizeWorldName(ver.getName()));
            ArchiveUtils.copyDirectory(worldRoot, dest);
            return dest;
        } finally {
            try {
                ArchiveUtils.deleteRecursively(tempDir);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 下载整合包主体（.mrpack / zip）：镜像优先 → 官方直链兜底。
     *
     * <p>对应原 {@code DownloadCenterPage.installModpack} 的下载段（{@code downloadWithMirrors}）。
     *
     * @param url      整合包主体直链
     * @param dest     目标 zip 文件
     * @param progress 0-100 进度回调
     * @param cancel   取消标志
     */
    public static void downloadModpackBody(String url, Path dest,
                                           IntConsumer progress, AtomicBoolean cancel) throws IOException {
        HttpDownloadEngine.downloadWithMirrors(url, dest, progress, cancel);
    }

    /**
     * 下载整合包清单内的全部文件：固定 4 线程并发（原并发逻辑原样搬入）。
     *
     * <p>对应原 {@code DownloadCenterPage.installModpackArchive} 第 5 节的并发下载块：
     * <ul>
     *   <li>路径穿越防护：目标必须位于 {@code packDir} 之下；</li>
     *   <li>已存在的文件（overrides 带来的）直接计为完成，不再下载；</li>
     *   <li>每个文件走 {@code downloadWithMirrors}（cdn.modrinth / forgecdn 直链，镜像优先）；</li>
     *   <li>整包进度 =（已完成文件数 + 当前文件百分比）/ 文件总数，折算到 0-99；</li>
     *   <li>任一文件失败时记录首个错误并抛 {@link IOException}（含已成功计数）。</li>
     * </ul>
     *
     * @param files    清单内文件列表
     * @param packDir  整合包版本目录（目标根）
     * @param progress 整包 0-100 进度回调
     * @param cancel   取消标志
     * @param fileCb   每文件进度文案回调（「下载整合包文件 x/total」），可为 null
     */
    public static void downloadModpackFiles(List<PackFile> files, Path packDir,
                                            IntConsumer progress, AtomicBoolean cancel,
                                            Consumer<String> fileCb) throws IOException, InterruptedException {
        int total = files.size();
        AtomicInteger doneFiles = new AtomicInteger(0);
        AtomicInteger failedFiles = new AtomicInteger(0);
        AtomicReference<IOException> dlError = new AtomicReference<>(null);
        List<Callable<Void>> dlTasks = new ArrayList<>();

        for (PackFile pf : files) {
            Path dest = packDir.resolve(pf.relativePath()).normalize();
            if (!dest.startsWith(packDir)) {
                continue; // 路径穿越防护
            }
            if (Files.exists(dest)) { // 已有（overrides 里带来的）
                doneFiles.incrementAndGet();
                continue;
            }
            Files.createDirectories(dest.getParent());
            final String fileUrl = pf.url();
            dlTasks.add(() -> {
                try {
                    // 清单内文件同样是 cdn.modrinth.com / edge.forgecdn.net 直链，走镜像优先
                    HttpDownloadEngine.downloadWithMirrors(fileUrl, dest,
                            pct -> {
                                // 进度按「已完成文件数 + 当前文件百分比」折算成整包进度。
                                // 注意 doneFiles 必须在任务结束时自增，否则进度永远停在 0。
                                int done = doneFiles.get();
                                int overall = (int) ((done + pct / 100.0) * 100 / total);
                                if (progress != null) {
                                    progress.accept(Math.max(0, Math.min(99, overall)));
                                }
                                if (fileCb != null) {
                                    fileCb.accept("下载整合包文件 " + Math.min(done + 1, total) + "/" + total);
                                }
                            }, cancel);
                } catch (IOException e) {
                    failedFiles.incrementAndGet();
                    dlError.compareAndSet(null, e);
                } finally {
                    doneFiles.incrementAndGet();
                }
                return null;
            });
        }

        if (!dlTasks.isEmpty()) {
            ExecutorService filePool = Executors.newFixedThreadPool(Math.min(4, dlTasks.size()), r -> {
                Thread t = new Thread(r, "sl-dl-file");
                t.setDaemon(true);
                return t;
            });
            try {
                for (Future<Void> f : filePool.invokeAll(dlTasks)) {
                    try {
                        f.get();
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof IOException ioe) {
                            dlError.compareAndSet(null, ioe);
                        } else {
                            dlError.compareAndSet(null, new IOException("整合包文件下载线程异常: " + cause, cause));
                        }
                    }
                }
            } finally {
                filePool.shutdownNow();
            }
        }
        IOException err = dlError.get();
        if (err != null) {
            throw new IOException("整合包文件下载失败（已成功 "
                    + (total - failedFiles.get()) + "/" + total + "）: " + err.getMessage(), err);
        }
    }
}
