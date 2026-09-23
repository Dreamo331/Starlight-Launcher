/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.version;

import com.example.starlight.version.VersionDownloadService.LoaderVersion;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Fabric API 版本查询提供器
 * 通过 Modrinth API 获取 Fabric API 的版本列表
 * Fabric API 在 Modrinth 上的项目 ID 是 P7dR8mSH
 * API: https://api.modrinth.com/v2/project/P7dR8mSH/version
 *
 * 每个版本包含:
 * - version_number: 版本号
 * - game_versions: 支持的 MC 版本数组
 * - loaders: ["fabric"]
 * - files[0].url: 下载地址
 */
public class FabricAPIProvider implements LoaderProvider {
    private static final String MODRINTH_API = "https://api.modrinth.com/v2/project/P7dR8mSH/version";

    public String name() { return "FabricAPI"; }
    public String icon() { return ""; }
    public String fallbackUrl() { return "https://modrinth.com/mod/fabric-api"; }

    /** 缓存所有版本，避免重复请求 */
    private List<ModrinthVersion> cachedAllVersions;
    private long cacheTime;

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
            List<ModrinthVersion> allVersions = getAllVersions();
            if (allVersions == null) return list;

            for (ModrinthVersion mv : allVersions) {
                // 检查是否支持目标 MC 版本
                boolean supportsMc = false;
                if (mv.gameVersions != null) {
                    for (String gv : mv.gameVersions) {
                        if (gv.equals(mcVersion)) {
                            supportsMc = true;
                            break;
                        }
                    }
                }
                if (!supportsMc) continue;

                // 检查加载器类型是否为 fabric
                boolean isFabric = false;
                if (mv.loaders != null) {
                    for (String loader : mv.loaders) {
                        if ("fabric".equalsIgnoreCase(loader)) {
                            isFabric = true;
                            break;
                        }
                    }
                }
                if (!isFabric) continue;

                String downloadUrl = null;
                if (mv.files != null && !mv.files.isEmpty()) {
                    downloadUrl = mv.files.get(0).url;
                }
                if (downloadUrl == null) downloadUrl = fallbackUrl();

                list.add(new LoaderVersion(name(), mv.versionNumber, true, downloadUrl));
            }
        } catch (Exception ignored) {}
        return list;
    }

    private List<ModrinthVersion> getAllVersions() {
        // 简单缓存，5分钟内有效
        if (cachedAllVersions != null && System.currentTimeMillis() - cacheTime < 300_000) {
            return cachedAllVersions;
        }
        try {
            String json = VersionDownloadService.fetchString(MODRINTH_API);
            if (json == null) return null;

            JsonArray arr = new Gson().fromJson(json, JsonArray.class);
            if (arr == null) return null;

            List<ModrinthVersion> result = new ArrayList<>();
            for (JsonElement elem : arr) {
                JsonObject obj = elem.getAsJsonObject();
                ModrinthVersion mv = new ModrinthVersion();
                mv.versionNumber = VersionDownloadService.getJsonString(obj, "version_number");
                mv.name = VersionDownloadService.getJsonString(obj, "name");

                // 解析 game_versions
                if (obj.has("game_versions")) {
                    JsonArray gvArr = obj.getAsJsonArray("game_versions");
                    mv.gameVersions = new ArrayList<>();
                    for (JsonElement gv : gvArr) {
                        mv.gameVersions.add(gv.getAsString());
                    }
                }

                // 解析 loaders
                if (obj.has("loaders")) {
                    JsonArray lArr = obj.getAsJsonArray("loaders");
                    mv.loaders = new ArrayList<>();
                    for (JsonElement l : lArr) {
                        mv.loaders.add(l.getAsString());
                    }
                }

                // 解析 files
                if (obj.has("files")) {
                    JsonArray fArr = obj.getAsJsonArray("files");
                    mv.files = new ArrayList<>();
                    for (JsonElement fe : fArr) {
                        JsonObject fObj = fe.getAsJsonObject();
                        ModrinthFile mf = new ModrinthFile();
                        mf.url = VersionDownloadService.getJsonString(fObj, "url");
                        mf.filename = VersionDownloadService.getJsonString(fObj, "filename");
                        if (mf.url != null) {
                            mv.files.add(mf);
                        }
                    }
                }

                if (mv.versionNumber != null) {
                    result.add(mv);
                }
            }

            cachedAllVersions = result;
            cacheTime = System.currentTimeMillis();
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String getInstallerUrl(String mcVersion, String loaderVersion) {
        return fallbackUrl();
    }

    /** Modrinth API 返回的版本数据结构 */
    private static class ModrinthVersion {
        String versionNumber;
        String name;
        List<String> gameVersions;
        List<String> loaders;
        List<ModrinthFile> files;
    }

    private static class ModrinthFile {
        String url;
        String filename;
    }
}
