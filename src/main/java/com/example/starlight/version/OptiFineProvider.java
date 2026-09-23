package com.example.starlight.version;

import com.example.starlight.version.VersionDownloadService.LoaderVersion;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * OptiFine 加载器。
 *
 * <p>数据来自 BMCLAPI 的 {@code /optifine/{mcVersion}}，返回的是一个 JSON 数组，
 * 每一项形如：
 * <pre>
 * {"mcversion":"1.20.1","patch":"I5","type":"HD_U","filename":"OptiFine_1.20.1_HD_U_I5.jar", ...}
 * </pre>
 * <b>响应里没有 {@code version} 字段</b>——旧实现只读这个字段，于是永远取到 null，
 * 界面上 OptiFine 一直显示「无」。这里改用 {@code type + "_" + patch} 拼出版本号
 * （与 OptiFine 官方文件名 {@code OptiFine_1.20.1_HD_U_I5.jar} 中的写法一致），
 * 下载直链走 BMCLAPI 代理（OptiFine 官网禁止直链，BMCLAPI 提供了可取用的镜像）。
 */
public class OptiFineProvider implements LoaderProvider {

    public String name() { return "OptiFine"; }
    public String icon() { return ""; }
    public String fallbackUrl() { return "https://optifine.net/downloads"; }

    public LoaderVersion fetchVersion(String mcVersion) {
        List<LoaderVersion> list = fetchVersions(mcVersion);
        return list.isEmpty() ? new LoaderVersion(name(), null, false, null) : list.get(0);
    }

    @Override
    public List<LoaderVersion> fetchVersions(String mcVersion) {
        List<LoaderVersion> list = new ArrayList<>();
        try {
            String json = VersionDownloadService.fetchString(
                    VersionDownloadService.BMCLAPI_BASE + "/optifine/" + mcVersion);
            if (json == null) return list;
            JsonArray arr = new Gson().fromJson(json, JsonArray.class);
            if (arr == null) return list;
            for (int i = 0; i < arr.size(); i++) {
                if (arr.get(i) == null || !arr.get(i).isJsonObject()) continue;
                JsonObject o = arr.get(i).getAsJsonObject();
                String type = VersionDownloadService.getJsonString(o, "type");
                String patch = VersionDownloadService.getJsonString(o, "patch");
                if (type == null || patch == null) continue;
                String display = type + "_" + patch;              // 例如 HD_U_I5
                String url = VersionDownloadService.BMCLAPI_BASE
                        + "/optifine/" + mcVersion + "/" + type + "/" + patch;
                list.add(new LoaderVersion(name(), display, true, url));
            }
            // 同 MC 版本下可能同时有 HD_U / pre 等多个渠道，按 patch 从新到旧排
            VersionDownloadService.sortLoaderVersionsDesc(list);
            if (list.size() > 40) {
                list = new ArrayList<>(list.subList(0, 40));
            }
        } catch (Exception ignored) {}
        return list;
    }

    public String getInstallerUrl(String mcVersion, String loaderVersion) {
        // loaderVersion 形如 HD_U_I5
        String[] parts = loaderVersion == null ? new String[0] : loaderVersion.split("_", 2);
        if (parts.length == 2) {
            return VersionDownloadService.BMCLAPI_BASE
                    + "/optifine/" + mcVersion + "/" + parts[0] + "/" + parts[1];
        }
        return fallbackUrl();
    }
}
