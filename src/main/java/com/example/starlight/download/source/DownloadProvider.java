package com.example.starlight.download.source;

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

    /**
     * 获取资源文件（asset object）下载候选地址。
     *
     * <p><b>参数必须是完整的 asset object location，形如 {@code <sha1前两位>/<sha1>}</b>
     * （如 {@code 2d/2d888ac14e51d7cab64b5e735456d25bf000720e}），<b>不是裸 hash</b>。
     *
     * <p>原因：BMCLAPI 的 assets 路径**完全镜像官方目录结构**，只换域名与前缀。实测对照：
     * <pre>
     *   官方   https://resources.download.minecraft.net/2d/2d888ac…   → 200, 605451 字节
     *   BMCLAPI https://bmclapi2.bangbang93.com/assets/2d/2d888ac…  → 200, 605451 字节
     *   BMCLAPI https://bmclapi2.bangbang93.com/assets/2d888ac…     → 404   ← 漏掉前两位目录
     * </pre>
     * 历史 Bug：调用方传了裸 hash，导致镜像端每个资产都 404（早期表现也可能是 200 + 0 字节），
     * 于是每个文件都要白付一次失败才回落官方源。
     */
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
