package com.example.starlight.download;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Minecraft 下载提供者接口（参考 HMCL 架构设计）
 * 抽象官方/镜像下载源，支持 URL 注入替换
 */
public interface DownloadProvider {

    /** 获取版本清单 URL 列表 */
    List<URI> getVersionListURLs();

    /** 获取资源文件下载候选地址 */
    List<URI> getAssetObjectCandidates(String assetObjectLocation);

    /**
     * 获取资产索引文件（asset index）下载候选地址
     * <p>
     * 默认返回 Mojang 官方地址（需 sha1）；镜像源可重写为各自的索引端点。
     *
     * @param assetId 资产 ID，如 "1.21"
     * @param sha1    索引文件 SHA-1（可为 null）
     */
    default List<URI> getAssetIndexCandidates(String assetId, String sha1) {
        if (sha1 == null || sha1.isBlank()) {
            return List.of();
        }
        return List.of(URI.create("https://launchermeta.mojang.com/v1/packages/" + sha1 + "/" + assetId + ".json"));
    }

    /**
     * 获取客户端 jar 的下载候选地址
     * <p>
     * 默认返回空列表（官方源直接用版本 JSON 里 downloads.client 的 URL）；
     * 镜像源可重写为各自的客户端端点。
     *
     * @param mcVersion 版本 JSON 中声明 downloads.client 的版本 ID（即原版 MC 版本号）
     */
    default List<URI> getVersionClientCandidates(String mcVersion) {
        return List.of();
    }

    /**
     * 注入替换原始 URL
     * 由于 Mojang/Forge 的 JSON 中写死了许多 URL，此方法提供替换机制
     */
    String injectURL(String baseURL);

    /** 注入并返回候选 URL 列表（含回退） */
    default List<URI> injectURLWithCandidates(String baseURL) {
        return List.of(URI.create(injectURL(baseURL)));
    }

    /** 批量注入多个 URL */
    default List<URI> injectURLsWithCandidates(List<String> urls) {
        LinkedHashSet<URI> result = new LinkedHashSet<>();
        for (String url : urls) {
            result.addAll(injectURLWithCandidates(url));
        }
        return List.copyOf(result);
    }

    /** 最大下载并发数 */
    int getConcurrency();
}
