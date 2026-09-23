/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.version;

import com.example.starlight.version.VersionDownloadService.LoaderVersion;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Quilt 加载器版本查询提供器
 * 使用 Quilt Meta API: https://meta.quiltmc.org/v3/versions/loader/{mc_version}
 * 返回格式与 Fabric Loader API 一致
 */
public class QuiltProvider implements LoaderProvider {
    private static final String QUILT_META = "https://meta.quiltmc.org/v3/versions/loader";

    public String name() { return "Quilt"; }
    public String icon() { return ""; }
    public String fallbackUrl() { return "https://quiltmc.org/"; }

    @Override
    public LoaderVersion fetchVersion(String mcVersion) {
        List<LoaderVersion> all = fetchVersions(mcVersion);
        return all.isEmpty()
                ? new LoaderVersion(name(), null, false, null)
                : all.get(0);
    }

    @Override
    public List<LoaderVersion> fetchVersions(String mcVersion) {
        List<LoaderVersion> list = new ArrayList<>();
        try {
            String json = VersionDownloadService.fetchString(
                    QUILT_META + "/" + mcVersion);
            if (json == null) return list;

            JsonArray arr = new Gson().fromJson(json, JsonArray.class);
            if (arr == null) return list;

            int maxShow = 20;
            for (int i = 0; i < arr.size() && i < maxShow; i++) {
                JsonObject entry = arr.get(i).getAsJsonObject();
                if (entry == null) continue;

                // Quilt loader 信息在 "loader" 字段中
                JsonObject loader = entry.getAsJsonObject("loader");
                if (loader == null) continue;

                String ver = VersionDownloadService.getJsonString(loader, "version");
                if (ver == null) continue;

                String launchMetaUrl = QUILT_META + "/" + mcVersion + "/" + ver;
                list.add(new LoaderVersion(name(), ver, true, launchMetaUrl));
            }
        } catch (Exception ignored) {}
        return list;
    }

    @Override
    public String getInstallerUrl(String mcVersion, String loaderVersion) {
        return QUILT_META + "/" + mcVersion + "/" + loaderVersion;
    }
}
