package com.example.starlight.download.source;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * BMCLAPI 镜像下载提供者（参考 HMCL 架构设计）
 * 支持完整的 URL 替换规则表和回退源
 */
public class BMCLAPIDownloadProvider implements DownloadProvider {

    private final String apiRoot;

    /** 主要替换规则 */
    private final List<ReplacementRule> replacement;

    /** 回退替换规则（主规则未命中时使用） */
    private final List<ReplacementRule> fallbackReplacement;

    public BMCLAPIDownloadProvider(String apiRoot) {
        this.apiRoot = apiRoot;

        this.replacement = List.of(
                rule("https://bmclapi2.bangbang93.com", apiRoot),
                rule("https://launchermeta.mojang.com", apiRoot),
                rule("https://piston-meta.mojang.com", apiRoot),
                rule("https://piston-data.mojang.com", apiRoot),
                rule("https://launcher.mojang.com", apiRoot),
                rule("https://libraries.minecraft.net", apiRoot + "/libraries"),
                rule("http://files.minecraftforge.net/maven", apiRoot + "/maven"),
                rule("https://files.minecraftforge.net/maven", apiRoot + "/maven"),
                rule("https://maven.minecraftforge.net", apiRoot + "/maven"),
                rule("https://maven.neoforged.net/releases/", apiRoot + "/maven/"),
                rule("https://meta.fabricmc.net", apiRoot + "/fabric-meta"),
                rule("https://maven.fabricmc.net", apiRoot + "/maven"),
                rule("http://dl.liteloader.com/versions", apiRoot + "/maven"),
                rule("https://repo1.maven.org/maven2", "https://mirrors.cloud.tencent.com/nexus/repository/maven-public"),
                rule("https://repo.maven.apache.org/maven2", "https://mirrors.cloud.tencent.com/nexus/repository/maven-public")
        );

        this.fallbackReplacement = List.of(
                rule("https://api.modrinth.com", "https://mod.mcimirror.top/modrinth"),
                rule("https://cdn.modrinth.com", "https://mod.mcimirror.top"),
                rule("https://api.curseforge.com", "https://mod.mcimirror.top/curseforge"),
                rule("https://edge.forgecdn.net", "https://mod.mcimirror.top")
        );
    }

    public String getApiRoot() {
        return apiRoot;
    }

    @Override
    public List<URI> getVersionListURLs() {
        return List.of(URI.create(apiRoot + "/mc/game/version_manifest.json"));
    }

    /**
     * BMCLAPI 的 assets 端点镜像官方目录结构，因此 {@code assetObjectLocation} 必须是
     * <b>{@code <sha1前两位>/<sha1>}</b>（实测：漏掉前两位目录会 404）。
     *
     * @param assetObjectLocation 形如 {@code 2d/2d888ac14e51d7cab64b5e735456d25bf000720e}
     */
    @Override
    public List<URI> getAssetObjectCandidates(String assetObjectLocation) {
        if (assetObjectLocation == null || assetObjectLocation.isBlank()) {
            return List.of();
        }
        // 容错：调用方若误传裸 hash（无 '/'），自动补上前两位，避免再次踩 404 的坑
        String location = assetObjectLocation.indexOf('/') >= 0
                ? assetObjectLocation
                : assetObjectLocation.substring(0, Math.min(2, assetObjectLocation.length()))
                        + "/" + assetObjectLocation;
        return List.of(URI.create(apiRoot + "/assets/" + location));
    }

    @Override
    public List<URI> getAssetIndexCandidates(String assetId, String sha1) {
        return List.of(URI.create(apiRoot + "/assets/indexes/" + assetId + ".json"));
    }

    @Override
    public List<URI> getVersionClientCandidates(String mcVersion) {
        return List.of(URI.create(apiRoot + "/version/" + mcVersion + "/client"));
    }

    @Override
    public String injectURL(String baseURL) {
        return applyRules(replacement, baseURL);
    }

    @Override
    public List<URI> injectURLWithCandidates(String baseURL) {
        String injected = applyRules(replacement, baseURL);
        if (injected.equals(baseURL)) {
            // 主规则未命中，尝试回退规则
            String fallback = applyRules(fallbackReplacement, baseURL);
            if (fallback.equals(baseURL)) {
                return List.of(URI.create(baseURL));
            } else {
                return List.of(URI.create(baseURL), URI.create(fallback));
            }
        } else {
            // 镜像优先、官方原始 URL 兜底（对标 PCL DlSourceOrder 的 MirrorUrls.Union(OfficialUrls)）
            return List.of(URI.create(injected), URI.create(baseURL));
        }
    }

    @Override
    public int getConcurrency() {
        return Math.max(Runtime.getRuntime().availableProcessors() * 2, 6);
    }

    // 内部工具 //

    private static String applyRules(List<ReplacementRule> rules, String url) {
        for (ReplacementRule rule : rules) {
            if (url.startsWith(rule.from)) {
                return rule.to + url.substring(rule.from.length());
            }
        }
        return url;
    }

    private static ReplacementRule rule(String from, String to) {
        return new ReplacementRule(from, to);
    }

    private static final class ReplacementRule {
        final String from;
        final String to;

        ReplacementRule(String from, String to) {
            this.from = from;
            this.to = to;
        }
    }
}
