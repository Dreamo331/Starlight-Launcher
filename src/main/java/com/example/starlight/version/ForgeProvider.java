package com.example.starlight.version;

import com.example.starlight.version.VersionDownloadService.LoaderVersion;
import com.google.gson.Gson;
import com.google.gson.JsonArray;

import java.util.ArrayList;
import java.util.List;

public class ForgeProvider implements LoaderProvider {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(ForgeProvider.class);

    public String name() { return "Forge"; }
    public String icon() { return ""; }
    public String fallbackUrl() { return "https://files.minecraftforge.net/"; }

    public LoaderVersion fetchVersion(String mcVersion) {
        try {
            String json = VersionDownloadService.fetchString(
                    VersionDownloadService.BMCLAPI_BASE + "/forge/minecraft/" + mcVersion);
            if (json == null) return new LoaderVersion(name(), null, false, null);
            JsonArray arr = new Gson().fromJson(json, JsonArray.class);
            if (arr != null && arr.size() > 0) {
                String ver = VersionDownloadService.getJsonString(arr.get(0).getAsJsonObject(), "version");
                return new LoaderVersion(name(), ver, true, getInstallerUrl(mcVersion, ver));
            }
        } catch (Exception e) {
            LOG.warn("Forge version query failed for {}: {}", mcVersion, e.getMessage());
        }
        return new LoaderVersion(name(), null, false, null);
    }

    @Override
    public List<LoaderVersion> fetchVersions(String mcVersion) {
        List<LoaderVersion> list = new ArrayList<>();
        try {
            String json = VersionDownloadService.fetchString(
                    VersionDownloadService.BMCLAPI_BASE + "/forge/minecraft/" + mcVersion);
            if (json == null) return list;
            JsonArray arr = new Gson().fromJson(json, JsonArray.class);
            if (arr != null) {
                // 截断从 30 放宽到 200：老 Forge 推荐版（如 1.7.10 的 10.13.x）排在第 31 位
                // 开外，此前永远搜不到；BMCLAPI 返回的全量 JSON 只有几十 KB
                int maxShow = 200;
                for (int i = 0; i < arr.size() && i < maxShow; i++) {
                    String ver = VersionDownloadService.getJsonString(arr.get(i).getAsJsonObject(), "version");
                    if (ver != null) {
                        list.add(new LoaderVersion(name(), ver, true, getInstallerUrl(mcVersion, ver)));
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Forge version list query failed for {}: {}", mcVersion, e.getMessage());
        }
        return list;
    }

    public String getInstallerUrl(String mcVersion, String loaderVersion) {
        return "https://maven.minecraftforge.net/net/minecraftforge/forge/"
                + mcVersion + "-" + loaderVersion + "/forge-"
                + mcVersion + "-" + loaderVersion + "-installer.jar";
    }
}
