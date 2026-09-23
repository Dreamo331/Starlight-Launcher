package com.example.starlight.version;

import com.example.starlight.version.VersionDownloadService.LoaderVersion;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

public class FabricProvider implements LoaderProvider {
    public String name() { return "Fabric"; }
    public String icon() { return ""; }
    public String fallbackUrl() { return "https://fabricmc.net/"; }

    public LoaderVersion fetchVersion(String mcVersion) {
        try {
            String json = VersionDownloadService.fetchString(
                    "https://meta.fabricmc.net/v2/versions/loader/" + mcVersion);
            if (json == null) return new LoaderVersion(name(), null, false, null);
            JsonArray arr = new Gson().fromJson(json, JsonArray.class);
            if (arr != null && arr.size() > 0) {
                // 优先返回稳定版
                for (int i = 0; i < arr.size(); i++) {
                    JsonObject entry = arr.get(i).getAsJsonObject();
                    JsonObject loader = entry.getAsJsonObject("loader");
                    if (loader != null) {
                        boolean stable = VersionDownloadService.getJsonBoolean(loader, "stable");
                        String ver = VersionDownloadService.getJsonString(loader, "version");
                        if (stable && ver != null) {
                            return new LoaderVersion(name(), ver, true, "https://fabricmc.net/use/installer/");
                        }
                    }
                }
                // 没有稳定版，返回第一个
                JsonObject firstLoader = arr.get(0).getAsJsonObject().getAsJsonObject("loader");
                String ver = VersionDownloadService.getJsonString(firstLoader, "version");
                return new LoaderVersion(name(), ver, true, "https://fabricmc.net/use/installer/");
            }
        } catch (Exception ignored) {}
        return new LoaderVersion(name(), null, false, null);
    }

    @Override
    public List<LoaderVersion> fetchVersions(String mcVersion) {
        List<LoaderVersion> list = new ArrayList<>();
        try {
            String json = VersionDownloadService.fetchString(
                    "https://meta.fabricmc.net/v2/versions/loader/" + mcVersion);
            JsonArray arr = new Gson().fromJson(json, JsonArray.class);
            if (arr != null) {
                int maxShow = 20;
                for (int i = 0; i < arr.size() && i < maxShow; i++) {
                    JsonObject entry = arr.get(i).getAsJsonObject();
                    JsonObject loader = entry.getAsJsonObject("loader");
                    if (loader != null) {
                        String ver = VersionDownloadService.getJsonString(loader, "version");
                        if (ver != null) {
                            list.add(new LoaderVersion(name(), ver, true,
                                    "https://fabricmc.net/use/installer/"));
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    public String getInstallerUrl(String mcVersion, String loaderVersion) {
        return "https://fabricmc.net/use/installer/";
    }
}
