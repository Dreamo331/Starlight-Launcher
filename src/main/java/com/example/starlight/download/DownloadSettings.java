package com.example.starlight.download;

/**
 * 全局下载设置（运行时生效，供各下载组件共享）
 * <p>
 * 管理「并发下载线程数」：
 * - 默认 4，与 starlight-client.ini 中 DownloadThreads 配置保持一致
 * - 设置页保存后通过 {@link #setDownloadThreads(int)} 立即生效，无需重启
 * <p>
 * 下载源由各组件持有各自的 DownloadProvider（VersionDownloadService /
 * GameResourceCompleter / LoaderInstallEngine），切换时统一调用
 * {@link com.example.starlight.version.VersionDownloadService#applyDownloadSource(String)} 后同步分发。
 */
public final class DownloadSettings {

    /** 并发下载线程数默认值 */
    public static final int DEFAULT_THREADS = 4;

    /** 允许的最小/最大并发线程数 */
    public static final int MIN_THREADS = 1;
    public static final int MAX_THREADS = 16;

    /** 当前生效的并发下载线程数（volatile：设置页可能在任意线程切换） */
    private static volatile int downloadThreads = DEFAULT_THREADS;

    private DownloadSettings() {
    }

    /** 获取当前并发下载线程数 */
    public static int getDownloadThreads() {
        return downloadThreads;
    }

    /** 设置并发下载线程数（自动限制在 MIN~MAX 范围内） */
    public static void setDownloadThreads(int threads) {
        downloadThreads = Math.max(MIN_THREADS, Math.min(MAX_THREADS, threads));
    }
}
