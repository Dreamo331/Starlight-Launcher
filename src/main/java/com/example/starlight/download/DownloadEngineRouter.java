package com.example.starlight.download;

import com.example.starlight.download.pcl.PclResourceCompleter;
import com.example.starlight.download.task.GameResourceCompleter;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 下载引擎分流器（2026-10-21）。
 *
 * <p>项目现在有两套资源补全引擎，由设置项 {@link DownloadSettings#KEY_ENGINE} 切换，
 * 下一次补全即生效：
 * <ul>
 *   <li><b>{@link DownloadSettings#ENGINE_STARLIGHT}</b>（默认）：原引擎
 *       {@link GameResourceCompleter}。行为与本次改造前完全一致 —— 任何调用点都可以安全回退。</li>
 *   <li><b>{@link DownloadSettings#ENGINE_PCL}</b>：新引擎 {@link PclResourceCompleter}，
 *       对标 PCL2 的逻辑（全局单一并发配额 + 连接复用 + 几何分裂分块 + 速率型停滞判定）。</li>
 * </ul>
 *
 * <p>分流点集中在本类，是为了让「保留原引擎 + 新增引擎」这件事只有一个判断入口：
 * 调用方（启动准备 / 插件 API / 旧 GUI / DownloadService）一律走这里，
 * 而不是各自判断该调哪个引擎。
 */
public final class DownloadEngineRouter {

    private DownloadEngineRouter() {
    }

    /** 当前是否使用 PCL 引擎 */
    public static boolean isPclEngine() {
        return DownloadSettings.isPclEngine();
    }

    /** 当前引擎名（诊断 / 日志用） */
    public static String currentEngineName() {
        return DownloadSettings.getEngine();
    }

    /**
     * 补全一个版本的全部资源（资产 + 库 + 客户端 JAR）。
     *
     * <p>两套引擎的进度回调签名一致（{@code (percent, message)}），此处做一次适配，
     * 调用方不需要知道当前跑的是哪套引擎。
     */
    public static boolean completeAll(String gameDir, String versionId,
                                      GameResourceCompleter.ProgressCallback progress,
                                      AtomicBoolean cancel) {
        if (isPclEngine()) {
            return PclResourceCompleter.completeAll(gameDir, versionId, adapt(progress), cancel);
        }
        return GameResourceCompleter.completeAll(gameDir, versionId, progress, cancel);
    }

    /**
     * 补全一个版本的全部资源（不带取消标志的便捷重载）。
     *
     * <p>{@link com.example.starlight.download.task.GameResourceCompleter#completeAll(String, String, GameResourceCompleter.ProgressCallback)}
     * 的历史调用方（{@code DownloadService} / 旧 GUI）用的是三参数形式，这里一并保留。
     */
    public static boolean completeAll(String gameDir, String versionId,
                                      GameResourceCompleter.ProgressCallback progress) {
        return completeAll(gameDir, versionId, progress, null);
    }

    /** 只补原生库（natives classifier jar） */
    public static boolean completeNativeJarsOnly(String gameDir, String versionId,
                                                 GameResourceCompleter.ProgressCallback progress,
                                                 AtomicBoolean cancel) {
        if (isPclEngine()) {
            return PclResourceCompleter.completeNativeJarsOnly(gameDir, versionId, adapt(progress), cancel);
        }
        return GameResourceCompleter.completeNativeJarsOnly(gameDir, versionId, progress, cancel);
    }

    /** 只补资产 */
    public static boolean completeAssetsOnly(String gameDir, String versionId,
                                             GameResourceCompleter.ProgressCallback progress,
                                             AtomicBoolean cancel) {
        if (isPclEngine()) {
            return PclResourceCompleter.completeAssetsOnly(gameDir, versionId, adapt(progress), cancel);
        }
        return GameResourceCompleter.completeAssetsOnly(gameDir, versionId, progress, cancel);
    }

    /** 只补库文件 */
    public static boolean completeLibrariesOnly(String gameDir, String versionId,
                                                GameResourceCompleter.ProgressCallback progress,
                                                AtomicBoolean cancel) {
        if (isPclEngine()) {
            // PCL 引擎的库补全与 natives 走同一条解析路径（includeArtifacts=true）
            return PclResourceCompleter.completeNativeJarsOnly(gameDir, versionId, adapt(progress), cancel);
        }
        return GameResourceCompleter.completeLibrariesOnly(gameDir, versionId, progress, cancel);
    }

    /** 只补客户端 JAR */
    public static boolean completeVersionJarOnly(String gameDir, String versionId,
                                                 GameResourceCompleter.ProgressCallback progress,
                                                 AtomicBoolean cancel) {
        // 版本 JAR 是单文件，两套引擎的差异可忽略；统一交给原引擎，避免为单文件重复实现一套候选逻辑
        return GameResourceCompleter.completeVersionJarOnly(gameDir, versionId, progress, cancel);
    }

    /**
     * 把原引擎的回调适配成 PCL 引擎的回调。
     *
     * @return 适配后的回调；入参为 null 时返回 null（两套引擎都容忍空回调）
     */
    private static PclResourceCompleter.ProgressCallback adapt(
            final GameResourceCompleter.ProgressCallback source) {
        if (source == null) return null;
        return source::onProgress;
    }
}