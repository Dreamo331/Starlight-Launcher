package com.example.starlight.service;

import com.example.starlight.config.StarlightConfig;
import com.example.starlight.download.GameResourceCompleter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 并行启动准备管理器（开关由「设置 → 高级设置 → 并行资源准备」控制，默认开启）
 * <p>
 * 开启后启动流程变为：
 * <ol>
 *   <li>预下载当前平台的 natives classifier jar（少量文件，保证后续提取无竞态）</li>
 *   <li>并行执行「游戏资源补全」（资产 + 库文件）与「原生库检查提取」，互不冲突：</li>
 *   <li>下载采用临时文件 + 原子重命名，已完整的 natives jar 不会被二次下载，</li>
 *   <li>资源补全写 assets/libraries 目录，原生库提取写 versions/&lt;版本&gt;/natives-* 目录。</li>
 * </ol>
 * 从而将原生库的下载/提取时间与资源补全重叠，缩短启动等待时间。
 */
public final class ParallelLaunchManager {

    /** 配置键（starlight.ini [Launcher] 段，默认开启） */
    public static final String CONFIG_KEY = "ParallelLaunch";

    private static final Logger log = LoggerFactory.getLogger(ParallelLaunchManager.class);

    /** 并行准备线程池（守护线程，不阻止 JVM 退出） */
    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "parallel-launch-worker");
        t.setDaemon(true);
        return t;
    });

    private ParallelLaunchManager() {}

    /** 开关是否开启（默认开启，仅显式配置为 false 时关闭） */
    public static boolean isEnabled() {
        return !"false".equalsIgnoreCase(StarlightConfig.get(CONFIG_KEY));
    }

    /**
     * 并行准备游戏启动所需资源
     * <p>
     * 阶段 1：预下载 natives classifier jar（0~8%）
     * 阶段 2：并行执行 资源补全(completeAll) 与 原生库检查提取(checkAndCompleteNatives)（8~100%）
     *
     * @param gameDir     Minecraft 游戏根目录
     * @param version     版本 ID
     * @param versionJson 版本 JSON 原文（原生库提取内部解析使用）
     * @param progress    进度回调（可为 null）
     * @return true = 原生库就绪（资源补全部分失败不阻塞启动，与串行行为一致）
     */
    public static boolean prepare(String gameDir, String version, String versionJson,
                                  GameResourceCompleter.ProgressCallback progress) {
        return prepare(gameDir, version, versionJson, progress, null);
    }

    /**
     * 带取消的并行准备：{@code cancel} 置位后资源补全立即停止（原生库提取是本地解压，不受影响）。
     *
     * @param cancel 取消标志，可为 null
     */
    public static boolean prepare(String gameDir, String version, String versionJson,
                                  GameResourceCompleter.ProgressCallback progress,
                                  java.util.concurrent.atomic.AtomicBoolean cancel) {
        // 阶段 1：预下载 natives jar，保证后续原生库提取与资源补全并行时无文件竞态
        report(progress, 0, "正在预下载原生库...");
        boolean jarsOk = GameResourceCompleter.completeNativeJarsOnly(gameDir, version,
                wrap(progress, 0, 8), cancel);
        if (!jarsOk) {
            if (cancel != null && cancel.get()) {
                report(progress, 0, "已取消启动");
                log.info("Parallel preparation cancelled during native jars");
                return false;
            }
            report(progress, 0, "原生库下载失败，请检查网络连接");
            log.error("Native jars download failed, abort parallel preparation");
            return false;
        }

        // 阶段 2：资源补全与原生库检查提取并行执行
        report(progress, 8, "正在并行补全资源与原生库...");
        CompletableFuture<Boolean> resources = CompletableFuture.supplyAsync(() ->
                GameResourceCompleter.completeAll(gameDir, version, wrap(progress, 8, 60), cancel), POOL);
        CompletableFuture<Boolean> natives = CompletableFuture.supplyAsync(() ->
                FileService.checkAndCompleteNatives(gameDir, version, versionJson), POOL);

        boolean nativesOk;
        try {
            CompletableFuture.allOf(resources, natives).get(30, TimeUnit.MINUTES);
            nativesOk = natives.get();
            if (!nativesOk) {
                log.error("Natives check failed in parallel preparation");
            }
        } catch (Exception e) {
            // 打印异常类型与原因：ExecutionException 的 getMessage() 常为 null，只看 message 会丢信息
            log.error("Parallel preparation interrupted: {}", e, e);
            nativesOk = false;
        }

        if (cancel != null && cancel.get()) {
            report(progress, 100, "已取消启动");
            log.info("Parallel preparation cancelled");
            return false;
        }

        report(progress, 100, "资源准备完成");
        return nativesOk;
    }

    /** 将子进度映射到父进度的区间内 */
    private static GameResourceCompleter.ProgressCallback wrap(
            GameResourceCompleter.ProgressCallback parent, int start, int end) {
        if (parent == null) return null;
        return (pct, msg) -> {
            int mapped = start + (pct * (end - start) / 100);
            parent.onProgress(Math.min(mapped, 100), msg);
        };
    }

    private static void report(GameResourceCompleter.ProgressCallback callback, int percent, String message) {
        if (callback != null) callback.onProgress(Math.min(percent, 100), message);
    }
}
