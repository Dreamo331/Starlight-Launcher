package com.example.starlight.pluginapi.provider;

import com.example.starlight.pluginapi.ApiProvider;
import com.example.starlight.pluginapi.ApiRequest;
import com.example.starlight.version.VersionManifest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 功能类（包装）：{@code version/manifest} —— 拉取官方版本清单。
 *
 * <p>包装既有 {@link VersionManifest}，不改动原有逻辑；只向外提供 JSON 数据，
 * UI 展示完全由插件负责。
 *
 * <p>无 Query 参数。版本条目手动构造成蛇形命名键（id/type/release_time/url），
 * 避免直接序列化 {@link VersionManifest.VersionEntry} 产生驼峰字段。
 */
public final class VersionManifestApiProvider implements ApiProvider {

    @Override
    public String apiId() {
        return "version/manifest";
    }

    @Override
    public String apiVersion() {
        return "1.0.0";
    }

    @Override
    public byte[] handle(ApiRequest request) throws Exception {
        List<VersionManifest.VersionEntry> entries = VersionManifest.fetchVersionList();
        List<Map<String, Object>> versions = new ArrayList<>();
        for (VersionManifest.VersionEntry entry : entries) {
            versions.add(ProviderSupport.map(
                    "id", entry.getId(),
                    "type", entry.getType(),
                    "release_time", entry.getReleaseTime(),
                    "url", entry.getUrl()));
        }
        return ProviderSupport.json(ProviderSupport.map(
                "count", versions.size(),
                "versions", versions));
    }
}
