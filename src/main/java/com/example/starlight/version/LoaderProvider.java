package com.example.starlight.version;

import com.example.starlight.version.VersionDownloadService.LoaderVersion;
import java.util.List;

/** 加载器统一接口 */
public interface LoaderProvider {
    String name();                           // Forge/Fabric/NeoForge/OptiFine
    String icon();                           // 显示图标emoji
    String fallbackUrl();                    // 无法获取时的官网链接
    LoaderVersion fetchVersion(String mcVersion);  // 查询某MC版本的加载器版本（最新版）

    /**
     * 查询某MC版本的所有可用加载器版本列表（用于弹窗选择多个版本）
     * 默认实现只返回最新版本，可被覆盖以提供完整列表
     */
    default List<LoaderVersion> fetchVersions(String mcVersion) {
        LoaderVersion single = fetchVersion(mcVersion);
        return single.isAvailable() ? List.of(single) : List.of();
    }

    String getInstallerUrl(String mcVersion, String loaderVersion); // 安装器下载链接
}
