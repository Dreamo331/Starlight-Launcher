package com.example.starlight.download;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * MCBBS 镜像下载提供者（参考 HMCL 架构设计）
 * <p>
 * 替换规则与 BMCLAPI 一致，镜像根为 download.mcbbs.net。
 * 注意：MCBBS 镜像站已停止服务，选择该源时下载会自动回退到
 * 原始 URL / BMCLAPI（各下载组件均内置回退机制），不会导致下载失败。
 */
public class MCBBSDownloadProvider implements DownloadProvider {

    private final String apiRoot;

    /** 主要替换规则 */
    private final List<ReplacementRule> replacement;

    /** 回退替换规则（主规则未命中时使用） */
    private final List<ReplacementRule> fallbackReplacement;

    public MCBBSDownloadProvider() {
        this("https://download.mcbbs.net");
    }

    public MCBBSDownloadProvider(String apiRoot) {
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

    @Override
    public List<URI> getAssetObjectCandidates(String assetObjectLocation) {
        return List.of(URI.create(apiRoot + "/assets/" + assetObjectLocation));
    }

    @Override
    public List<URI> getAssetIndexCandidates(String assetId, String sha1) {
        return List.of(URI.create(apiRoot + "/assets/indexes/" + assetId + ".json"));
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
            return List.of(URI.create(injected));
        }
    }

    @Override
    public int getConcurrency() {
        return Math.max(Runtime.getRuntime().availableProcessors() * 2, 6);
    }

    // ==================== 内部工具 ====================

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
