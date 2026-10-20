package com.example.starlight.download;

import com.example.starlight.download.source.BMCLAPIDownloadProvider;
import com.example.starlight.download.source.DownloadProvider;
import com.example.starlight.download.source.MCBBSDownloadProvider;
import com.example.starlight.download.source.MojangDownloadProvider;

/**
 * 下载源单一持有者（JUDGE-5 收敛点）。
 *
 * <p>原先分散在 GameResourceCompleter / VersionManifest / LoaderInstallEngine /
 * VersionDownloadService 四处的 DownloadProvider 静态持有，统一收敛到本类。
 * 默认懒加载为 BMCLAPI 镜像；通过 {@link #set(DownloadProvider)} 切换，
 * 通过 {@link #createForSource(String)} 按源名构造新实例。
 */
public final class DownloadProviderHolder {

    /** 默认下载源为 BMCLAPI 镜像根地址 */
    private static final String BMCLAPI_BASE = "https://bmclapi2.bangbang93.com";

    /** 全局唯一的下载提供者；volatile + 双检锁保证懒加载线程安全，初始为 null */
    private static volatile DownloadProvider provider;

    private DownloadProviderHolder() {
    }

    /**
     * 获取当前下载提供者；首次调用时懒加载为默认的 BMCLAPI 源。
     */
    public static DownloadProvider get() {
        DownloadProvider p = provider;
        if (p == null) {
            synchronized (DownloadProviderHolder.class) {
                p = provider;
                if (p == null) {
                    p = new BMCLAPIDownloadProvider(BMCLAPI_BASE);
                    provider = p;
                }
            }
        }
        return p;
    }

    /**
     * 设置全局下载提供者（切换下载源后调用）。
     */
    public static void set(DownloadProvider p) {
        provider = p;
    }

    /**
     * 按源名称构造对应的下载提供者（与 VersionDownloadService.applyDownloadSource 一致，大小写不敏感）。
     *
     * @param source 源名："Mojang" / "BMCLAPI" / "MCBBS"
     * @return 对应源的下载提供者；未知值回退 BMCLAPI
     */
    public static DownloadProvider createForSource(String source) {
        if ("Mojang".equalsIgnoreCase(source)) {
            return new MojangDownloadProvider();
        } else if ("MCBBS".equalsIgnoreCase(source)) {
            return new MCBBSDownloadProvider();
        }
        // "BMCLAPI" 与其他未知值统一回退到 BMCLAPI 镜像
        return new BMCLAPIDownloadProvider(BMCLAPI_BASE);
    }
}
