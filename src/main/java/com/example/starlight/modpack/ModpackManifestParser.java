package com.example.starlight.modpack;

import com.example.starlight.ModsApi.CurseForgeAPI;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 整合包清单解析（从 LauncherView 抽离，逻辑与原实现保持一致）。
 *
 * <p>支持两种格式并统一成 {@link PackManifest}：
 * <ul>
 *   <li><b>Modrinth {@code .mrpack}</b>：读 {@code modrinth.index.json}（依赖表 + files 列表）；</li>
 *   <li><b>CurseForge {@code .zip}</b>：读 {@code manifest.json}，其中的 {@code projectID/fileID}
 *       通过批量接口换成直链（作者关闭第三方分发时跳过并记录日志）。</li>
 * </ul>
 *
 * <p>解析过程的「阶段文案 / 日志行」通过 {@link Progress} 回调上报，
 * 因此本类不依赖下载进度弹窗等界面类型。
 */
public final class ModpackManifestParser {

    private ModpackManifestParser() {
    }

    /**
     * 解析过程的进度回报（由下载进度弹窗适配）。
     * <p>把界面回调抽象成接口，使解析逻辑与 JavaFX 解耦。
     */
    public interface Progress {
        /** 阶段文案（如「向 CurseForge 解析 N 个文件的下载地址...」） */
        void stage(String text);

        /** 日志行；highlight=true 时高亮显示 */
        void logLine(String text, boolean highlight);
    }

    /** 将整合包名转换为安全的版本 id */
    public static String sanitizePackId(String name) {
        String id = name.trim()
                .replaceAll("[^\\p{L}\\p{N}._-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (id.isEmpty()) id = "modpack";
        if (id.length() > 40) id = id.substring(0, 40);
        return id.toLowerCase(java.util.Locale.ROOT);
    }

    /** Modrinth 清单里的加载器依赖键 → 内部加载器类型 */
    private static final String[][] MODPACK_LOADER_KEYS = {
            {"fabric-loader", "fabric"},
            {"quilt-loader", "quilt"},
            {"neoforge", "neoforge"},
            {"forge", "forge"}
    };

    /**
     * 解析整合包清单（两种格式统一成 {@link PackManifest}）。
     *
     * <p>CurseForge 的 {@code manifest.json} 只给出 {@code projectID/fileID}，
     * 这里用批量接口换成直链；作者关闭第三方分发时拿不到直链，会跳过并在日志里列出。
     */
    public static PackManifest parse(Path extractDir, Progress progress) throws IOException {
        // ---- Modrinth .mrpack ----
        Path mrIndex = extractDir.resolve("modrinth.index.json");
        if (Files.exists(mrIndex)) {
            JsonObject index = new Gson().fromJson(
                    Files.readString(mrIndex, java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);
            if (index == null) throw new IOException("modrinth.index.json 解析失败");
            JsonObject deps = index.getAsJsonObject("dependencies");
            if (deps == null || !deps.has("minecraft")) {
                throw new IOException("整合包未声明 Minecraft 版本");
            }
            String loaderType = null, loaderVersion = null;
            for (String[] lm : MODPACK_LOADER_KEYS) {
                if (deps.has(lm[0])) {
                    loaderType = lm[1];
                    loaderVersion = deps.get(lm[0]).getAsString();
                    break;
                }
            }
            List<PackFile> files = new ArrayList<>();
            JsonArray arr = index.getAsJsonArray("files");
            if (arr != null) {
                for (JsonElement e : arr) {
                    if (e == null || !e.isJsonObject()) continue;
                    JsonObject o = e.getAsJsonObject();
                    String path = o.has("path") ? o.get("path").getAsString() : null;
                    JsonArray urls = o.getAsJsonArray("downloads");
                    if (path == null || path.isBlank() || urls == null || urls.isEmpty()) continue;
                    files.add(new PackFile(path, urls.get(0).getAsString()));
                }
            }
            progress.logLine("清单格式: Modrinth .mrpack", true);
            return new PackManifest(deps.get("minecraft").getAsString(),
                    loaderType, loaderVersion, "overrides", files);
        }

        // ---- CurseForge zip ----
        Path cfManifest = extractDir.resolve("manifest.json");
        if (Files.exists(cfManifest)) {
            JsonObject m = new Gson().fromJson(
                    Files.readString(cfManifest, java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);
            if (m == null) throw new IOException("manifest.json 解析失败");
            JsonObject mc = m.getAsJsonObject("minecraft");
            if (mc == null || !mc.has("version")) throw new IOException("整合包未声明 Minecraft 版本");
            String mcVersionRaw = mc.get("version").getAsString();

            // modLoaders 形如 [{"id":"neoforge-21.1.72","primary":true}]
            String loaderType = null, loaderVersion = null;
            JsonArray loaders = mc.getAsJsonArray("modLoaders");
            if (loaders != null) {
                for (JsonElement e : loaders) {
                    if (e == null || !e.isJsonObject()) continue;
                    JsonObject o = e.getAsJsonObject();
                    boolean primary = !o.has("primary") || o.get("primary").getAsBoolean();
                    if (!primary || !o.has("id")) continue;
                    String id = o.get("id").getAsString();
                    int dash = id.indexOf('-');
                    if (dash <= 0) continue;
                    loaderType = id.substring(0, dash).toLowerCase(java.util.Locale.ROOT);
                    loaderVersion = id.substring(dash + 1);
                    break;
                }
            }

            String overridesDir = m.has("overrides") ? m.get("overrides").getAsString() : "overrides";
            List<PackFile> files = resolveCurseForgePackFiles(m, progress);
            progress.logLine("清单格式: CurseForge manifest.json", true);
            return new PackManifest(mcVersionRaw, loaderType, loaderVersion,
                    (overridesDir == null || overridesDir.isBlank()) ? "overrides" : overridesDir, files);
        }

        throw new IOException("无法识别的整合包格式（既没有 modrinth.index.json，也没有 manifest.json）");
    }

    /**
     * 把 CurseForge 清单里的 {@code projectID/fileID} 换成直链。
     * <p>用 {@code POST /v1/mods/files} 批量查询（一次可达 1000 个），
     * 避免几百个文件逐个请求导致的缓慢与限流。
     */
    private static List<PackFile> resolveCurseForgePackFiles(JsonObject manifest, Progress progress)
            throws IOException {
        List<PackFile> out = new ArrayList<>();
        JsonArray arr = manifest.getAsJsonArray("files");
        if (arr == null || arr.isEmpty()) return out;

        // 收集 fileID（CurseForge 的 fileID 全局唯一，可批量查）
        List<Integer> ids = new ArrayList<>();
        for (JsonElement e : arr) {
            if (e == null || !e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            if (o.has("fileID")) {
                try { ids.add(o.get("fileID").getAsInt()); } catch (Exception ignored) {}
            }
        }
        if (ids.isEmpty()) return out;

        progress.stage("向 CurseForge 解析 " + ids.size() + " 个文件的下载地址...");
        List<JsonObject> metas;
        try {
            metas = CurseForgeAPI.getFilesByIds(ids);
        } catch (IOException e) {
            // 整批文件都不存在时 CurseForge 返回 404（例如整合包引用了已下架的文件）。
            // 这不该让整个整合包安装失败：原版、加载器、overrides 仍然可用。
            if (String.valueOf(e.getMessage()).contains("404")) {
                progress.logLine("CurseForge 未能解析清单文件（可能引用了已下架的文件）: " + e.getMessage(), false);
                return out;
            }
            throw e;
        }
        Map<Integer, JsonObject> byId = new HashMap<>();
        for (JsonObject meta : metas) {
            if (meta.has("id")) {
                try { byId.put(meta.get("id").getAsInt(), meta); } catch (Exception ignored) {}
            }
        }

        int noUrl = 0;
        for (int fileId : ids) {
            JsonObject meta = byId.get(fileId);
            if (meta == null) { noUrl++; continue; }
            String fileName = meta.has("fileName") ? meta.get("fileName").getAsString() : null;
            if (fileName == null || fileName.isBlank()) { noUrl++; continue; }
            String dl = (meta.has("downloadUrl") && !meta.get("downloadUrl").isJsonNull())
                    ? meta.get("downloadUrl").getAsString() : null;
            if (dl == null || dl.isBlank()) {
                noUrl++;
                progress.logLine("跳过（作者未开放第三方下载）: " + fileName, false);
                continue;
            }
            // CurseForge 清单里的文件都是 mod，统一放进 mods/
            out.add(new PackFile("mods/" + fileName, dl));
        }
        if (noUrl > 0) {
            progress.logLine("有 " + noUrl + " 个文件无法获取直链（通常因作者关闭了第三方分发），"
                    + "整合包仍会安装，但这些 mod 需要自行补装", false);
        }
        return out;
    }
}
