package com.example.starlight.version;

import com.example.starlight.version.VersionDownloadService.LoaderVersion;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NeoForgeProvider implements LoaderProvider {
    private static final String NEOFORGE_MAVEN_METADATA =
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml";

    /**
     * 全局 metadata 缓存：这个文件是<b>全 NeoForge 版本列表</b>（1835 个版本），
     * 与 MC 版本无关。旧实现每次查配置页都重新拉一遍 66.5KB，而官方源实测
     * 3/3 全部超时（21~23s）、镜像 5.9s~15s 大幅波动——NeoForge 经常查不到
     * 就是这个原因。这里缓存一次，后续查询（含不同 MC 版本）直接复用。
     *
     * <p>TTL 15 分钟：NeoForge 发版频率低，15 分钟内的重复查询命中缓存即可秒回；
     * 过期后由下一次查询异步刷新。失败不写缓存，方便立即重试。
     */
    private static volatile String cachedMetadata;
    private static volatile long cachedMetadataAt;

    private static final long METADATA_TTL_MS = 15 * 60_000L;

    public String name() { return "NeoForge"; }
    public String icon() { return ""; }
    public String fallbackUrl() { return "https://github.com/neoforged/NeoForge/releases"; }

    /** 取全局 metadata（带 15 分钟缓存，命中直接复用，未命中联网拉取并写缓存） */
    private String fetchMetadata() {
        String cached = cachedMetadata;
        if (cached != null && System.currentTimeMillis() - cachedMetadataAt < METADATA_TTL_MS) {
            return cached;
        }
        String xml = VersionDownloadService.fetchString(NEOFORGE_MAVEN_METADATA);
        if (xml != null) {
            cachedMetadata = xml;
            cachedMetadataAt = System.currentTimeMillis();
        }
        return xml;
    }

    public LoaderVersion fetchVersion(String mcVersion) {
        try {
            // NeoForge 版本号格式: {mcMajor}{mcMinor}.{mcPatch}.{build}
            // MC 1.21.1  -> 21.1.xxx
            // MC 1.21    -> 21.0.xxx
            // MC 1.20.6  -> 20.6.xxx
            String versionPrefix = getNeoForgePrefix(mcVersion);
            if (versionPrefix == null) return new LoaderVersion(name(), null, false, null);

            String xml = fetchMetadata();
            if (xml == null) return new LoaderVersion(name(), null, false, null);

            return parseLatestVersion(xml, versionPrefix);
        } catch (Exception ignored) {}
        return new LoaderVersion(name(), null, false, null);
    }

    /**
     * 从 MC 版本号推导 NeoForge 版本前缀。
     *
     * <p>NeoForge 的版本号直接沿用 MC 版本的「主.次」：
     * <ul>
     *   <li>MC {@code 1.21.4} → NeoForge {@code 21.4.x}</li>
     *   <li>MC {@code 1.20.1} → NeoForge {@code 20.1.x}</li>
     *   <li>MC {@code 26.2}   → NeoForge {@code 26.2.x}（新版 MC 版本号本身就是 26.2）</li>
     * </ul>
     *
     * <p>旧实现算的是 {@code major * 10 + minor}，MC 1.21 会得到 {@code 31}，
     * 前缀 {@code 31.4} 在元数据里一个都匹配不上，于是 NeoForge 永远查不到版本。
     */
    private String getNeoForgePrefix(String mcVersion) {
        if (mcVersion == null || mcVersion.isBlank()) return null;
        String v = mcVersion.trim();
        if (v.startsWith("1.")) v = v.substring(2);   // 1.21.4 -> 21.4
        String[] parts = v.split("\\.");
        if (parts.length == 0) return null;
        if (!parts[0].matches("\\d+")) return null;
        if (parts.length == 1) return parts[0] + ".0";
        if (!parts[1].matches("\\d+")) return null;
        return parts[0] + "." + parts[1];
    }

    /**
     * 从 Maven metadata XML 中解析匹配 MC 版本的最新 NeoForge 版本
     */
    private LoaderVersion parseLatestVersion(String xml, String versionPrefix) {
        // 简单 XML 解析：提取所有 version 标签内容
        Pattern pattern = Pattern.compile("<version>([^<]+)</version>");
        Matcher matcher = pattern.matcher(xml);
        String latestMatch = null;
        while (matcher.find()) {
            String ver = matcher.group(1);
            // 匹配前缀，如 "21.1."
            if (ver.startsWith(versionPrefix + ".")) {
                if (latestMatch == null || compareVersions(ver, latestMatch) > 0) {
                    latestMatch = ver;
                }
            }
        }
        if (latestMatch != null) {
            String dlUrl = "https://maven.neoforged.net/releases/net/neoforged/neoforge/"
                    + latestMatch + "/neoforge-" + latestMatch + "-installer.jar";
            return new LoaderVersion(name(), latestMatch, true, dlUrl);
        }
        return new LoaderVersion(name(), null, false, null);
    }

    /** 比较版本号，返回 a - b 的正负 */
    private int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int na = i < pa.length ? Integer.parseInt(pa[i]) : 0;
            int nb = i < pb.length ? Integer.parseInt(pb[i]) : 0;
            if (na != nb) return na - nb;
        }
        return 0;
    }

    @Override
    public List<LoaderVersion> fetchVersions(String mcVersion) {
        List<LoaderVersion> list = new ArrayList<>();
        try {
            String versionPrefix = getNeoForgePrefix(mcVersion);
            if (versionPrefix == null) return list;

            // 复用缓存：metadata 与 MC 版本无关，只拉一次
            String xml = fetchMetadata();
            if (xml == null) return list;

            Pattern pattern = Pattern.compile("<version>([^<]+)</version>");
            Matcher matcher = pattern.matcher(xml);
            while (matcher.find()) {
                String ver = matcher.group(1);
                if (ver.startsWith(versionPrefix + ".")) {
                    String dlUrl = "https://maven.neoforged.net/releases/net/neoforged/neoforge/"
                            + ver + "/neoforge-" + ver + "-installer.jar";
                    list.add(new LoaderVersion(name(), ver, true, dlUrl));
                }
            }
            // 元数据是按时间升序的，先全收下来再按版本号从新到旧排，
            // 否则界面上「最新版」会取到最老的那个
            VersionDownloadService.sortLoaderVersionsDesc(list);
            if (list.size() > 60) {
                list = new ArrayList<>(list.subList(0, 60));
            }
        } catch (Exception ignored) {}
        return list;
    }

    public String getInstallerUrl(String mcVersion, String loaderVersion) {
        return "https://maven.neoforged.net/releases/net/neoforged/neoforge/"
                + loaderVersion + "/neoforge-" + loaderVersion + "-installer.jar";
    }
}
