package com.example.starlight.download;

import java.net.URI;
import java.util.List;

/**
 * Mojang 官方下载提供者（参考 HMCL 架构设计）
 * 直接使用 Mojang 官方源，不进行 URL 替换
 */
public class MojangDownloadProvider implements DownloadProvider {

    public MojangDownloadProvider() {
    }

    @Override
    public List<URI> getVersionListURLs() {
        return List.of(URI.create("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"));
    }

    @Override
    public List<URI> getAssetObjectCandidates(String assetObjectLocation) {
        return List.of(URI.create("https://resources.download.minecraft.net/" + assetObjectLocation));
    }

    @Override
    public String injectURL(String baseURL) {
        return baseURL;
    }

    @Override
    public int getConcurrency() {
        return 6;
    }
}
