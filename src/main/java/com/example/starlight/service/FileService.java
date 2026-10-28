/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.service;


import com.example.starlight.util.I18N;
import com.example.starlight.model.ActionResult;
import com.example.starlight.model.LauncherProfileInfo;
import com.example.starlight.model.NativesStatus;
import com.example.starlight.model.CallbackInterfaces;
import com.example.starlight.main.NativesManager;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.example.starlight.startmc.LaunchInfo;
import com.example.starlight.startmc.launcher.VanillaLauncher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

/**
 * 原生库、options.txt、launcher_profiles.json 等文件管理服务
 */
public class FileService {

    // 原生库管理 //

    /** 检查原生库状态 */
    public static NativesStatus checkNativesStatus(String gameDir, String version) {
        Path verDir = Paths.get(gameDir, "versions", version);
        if (!Files.isDirectory(verDir)) return new NativesStatus(false, 0, Collections.emptyList());
        try (Stream<Path> stream = Files.list(verDir)) {
            List<Path> nativeDirs = stream.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().contains("natives")).toList();
            for (Path dir : nativeDirs) {
                try (Stream<Path> files = Files.list(dir)) {
                    List<String> dlls = files.filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith(".dll") || n.endsWith(".so") || n.endsWith(".dylib");
                    }).map(p -> p.getFileName().toString()).sorted().toList();
                    if (!dlls.isEmpty()) return new NativesStatus(true, dlls.size(), dlls);
                }
            }
        } catch (IOException ignored) {}
        return new NativesStatus(false, 0, Collections.emptyList());
    }

    /** 补全原生库（异步） */
    public static void complementNativesAsync(String gameDir, String version,
                                               CallbackInterfaces.ProgressCallback onProgress,
                                               CallbackInterfaces.ResultCallback<Boolean> onResult) {
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                onProgress.onProgress(10, I18N.get("service.t0005"));
                Path jsonPath = Paths.get(gameDir, "versions", version, version + ".json");
                if (!Files.exists(jsonPath)) {
                    onResult.onError(I18N.get("service.t0006"));
                    return;
                }
                String jsonStr = Files.readString(jsonPath, StandardCharsets.UTF_8);
                JsonObject versionJson = new Gson().fromJson(jsonStr, JsonObject.class);

                onProgress.onProgress(30, I18N.get("service.t0007"));
                LaunchInfo info = new LaunchInfo();
                info.setGameDirPath(Path.of(gameDir));
                info.setVersion(version);
                info.setJavaPath("java");
                // 隔离开关按该版本的有效配置读取，与正式启动链路同源
                info.setVersionIsolation(com.example.starlight.config.VersionConfigManager
                        .effectiveVersionIsolation(com.example.starlight.main.ConfigManager
                                .readConfig(), gameDir, version));
                VanillaLauncher launcher = new VanillaLauncher(info);
                launcher.initPaths();
                launcher.setVersionJson(versionJson);

                onProgress.onProgress(60, I18N.get("service.t0008"));
                boolean ok = launcher.processNatives();
                if (!ok) {
                    onResult.onError(I18N.get("service.t0009"));
                    return;
                }

                onProgress.onProgress(90, I18N.get("service.t0010"));
                runNativeCleanup(gameDir, version);

                onProgress.onProgress(100, I18N.get("service.t0011"));
                onResult.onSuccess(true);
            } catch (JsonSyntaxException | IOException e) {
                onResult.onError(I18N.get("service.p0069", e.getMessage()));
            }
        });
    }

    /** 在启动前检查并补全原生库（严格判定，补全失败返回 false） */
    public static boolean checkAndCompleteNatives(String gameDir, String version, String versionJson) {
        return NativesManager.checkAndCompleteNatives(gameDir, version, versionJson, false);
    }

    // options.txt 管理 //

    /** 读取 options.txt */
    public static Map<String, String> readOptionsTxt(String gameDir) {
        Path optFile = Paths.get(gameDir, "options.txt");
        if (!Files.exists(optFile)) return Collections.emptyMap();
        Map<String, String> options = new LinkedHashMap<>();
        try {
            List<String> lines = Files.readAllLines(optFile, StandardCharsets.UTF_8);
            for (String line : lines) {
                int colon = line.indexOf(':');
                if (colon > 0) options.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
        } catch (IOException ignored) {}
        return options;
    }

    /** 修改 options.txt 中的某个值 */
    public static ActionResult updateOptionsTxt(String gameDir, String key, String newValue) {
        Path optFile = Paths.get(gameDir, "options.txt");
        if (!Files.exists(optFile)) return ActionResult.fail(I18N.get("service.t0012"));
        try {
            List<String> lines = Files.readAllLines(optFile, StandardCharsets.UTF_8);
            boolean found = false;
            for (int i = 0; i < lines.size(); i++) {
                int colon = lines.get(i).indexOf(':');
                if (colon > 0 && lines.get(i).substring(0, colon).trim().equals(key)) {
                    lines.set(i, key + ":" + newValue);
                    found = true;
                    break;
                }
            }
            if (!found) lines.add(key + ":" + newValue);
            Files.write(optFile, lines, StandardCharsets.UTF_8);
            return ActionResult.ok(I18N.get("service.p0070", key + " = " + newValue));
        } catch (IOException e) {
            return ActionResult.fail(I18N.get("service.p0071", e.getMessage()));
        }
    }

    // launcher_profiles.json 管理 //

    /** 读取启动器档案信息 */
    public static LauncherProfileInfo readLauncherProfiles(String gameDir) {
        Path profFile = Paths.get(gameDir, "launcher_profiles.json");
        if (!Files.exists(profFile)) return null;
        try {
            String jsonStr = Files.readString(profFile, StandardCharsets.UTF_8);
            JsonObject root = new Gson().fromJson(jsonStr, JsonObject.class);
            String username = "", displayName = "";
            int accountCount = 0;
            if (root.has("authenticationDatabase")) {
                JsonObject authDb = root.getAsJsonObject("authenticationDatabase");
                accountCount = authDb.size();
                for (String key : authDb.keySet()) {
                    JsonObject acct = authDb.getAsJsonObject(key);
                    if (acct.has("username")) username = acct.get("username").getAsString();
                    if (acct.has("displayName")) displayName = acct.get("displayName").getAsString();
                    break;
                }
            }
            Map<String, String> profiles = new LinkedHashMap<>();
            if (root.has("profiles")) {
                JsonObject profObj = root.getAsJsonObject("profiles");
                for (String key : profObj.keySet()) {
                    JsonObject prof = profObj.getAsJsonObject(key);
                    String name = prof.has("name") ? prof.get("name").getAsString() : key;
                    String lastVer = prof.has("lastVersionId") ? prof.get("lastVersionId").getAsString() : I18N.get("service.t0013");
                    profiles.put(name, lastVer);
                }
            }
            return new LauncherProfileInfo(username, displayName, accountCount, profiles);
        } catch (JsonSyntaxException | IOException e) {
            return null;
        }
    }

    // 文件系统工具 //

    /** 打开文件/目录（操作系统默认程序） */
    public static ActionResult openInExplorer(String path) {
        try {
            java.io.File file = new java.io.File(path);
            if (!file.exists()) return ActionResult.fail(I18N.get("service.p0072", path));
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) Runtime.getRuntime().exec(new String[]{"explorer.exe", "/select,", file.getAbsolutePath()});
            else if (os.contains("mac")) Runtime.getRuntime().exec(new String[]{"open", file.getParent()});
            else Runtime.getRuntime().exec(new String[]{"xdg-open", file.getParent()});
            return ActionResult.ok(I18N.get("service.t0014"));
        } catch (IOException e) {
            return ActionResult.fail(I18N.get("service.p0073", e.getMessage()));
        }
    }

    /** 打开目录 */
    public static ActionResult openDirectory(String path) {
        try {
            java.io.File dir = new java.io.File(path);
            if (!dir.isDirectory()) return ActionResult.fail(I18N.get("service.p0074", path));
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) Runtime.getRuntime().exec(new String[]{"explorer.exe", dir.getAbsolutePath()});
            else if (os.contains("mac")) Runtime.getRuntime().exec(new String[]{"open", dir.getAbsolutePath()});
            else Runtime.getRuntime().exec(new String[]{"xdg-open", dir.getAbsolutePath()});
            return ActionResult.ok(I18N.get("service.t0015"));
        } catch (IOException e) {
            return ActionResult.fail(I18N.get("service.p0075", e.getMessage()));
        }
    }

    /** 打开 URL（默认浏览器） */
    public static ActionResult openUrl(String url) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) Runtime.getRuntime().exec(new String[]{"cmd", "/c", "start", url});
            else if (os.contains("mac")) Runtime.getRuntime().exec(new String[]{"open", url});
            else Runtime.getRuntime().exec(new String[]{"xdg-open", url});
            return ActionResult.ok(I18N.get("service.t0016"));
        } catch (IOException e) {
            return ActionResult.fail(I18N.get("service.p0076", e.getMessage()));
        }
    }

    // 内部 //

    private static void runNativeCleanup(String gameDir, String version) {
        Path verDir = Paths.get(gameDir, "versions", version);
        try (Stream<Path> stream = Files.list(verDir)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().contains("natives"))
                    .forEach(FileService::cleanupNativesDir);
        } catch (IOException ignored) {}
    }

    private static void cleanupNativesDir(Path nativesDir) {
        if (!Files.isDirectory(nativesDir)) return;
        try {
            List<Path> allDlls;
            try (Stream<Path> walk = Files.walk(nativesDir)) {
                allDlls = walk.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".dll"))
                        .sorted((a, b) -> b.toString().length() - a.toString().length()).toList();
            }
            if (allDlls.isEmpty()) return;
            Map<String, Path> keepMap = new HashMap<>();
            for (Path dll : allDlls) {
                String name = dll.getFileName().toString();
                try { long size = Files.size(dll);
                    Path existing = keepMap.get(name);
                    if (existing == null || Files.size(existing) < size) keepMap.put(name, dll);
                } catch (IOException e) { keepMap.putIfAbsent(name, dll); }
            }
            String[] removePatterns = {"twitch", "avutil", "libmp3lame", "libmfxsw64", "swresample", "jtracy", "SAPIWrapper"};
            for (String p : removePatterns) keepMap.entrySet().removeIf(e -> e.getKey().toLowerCase().contains(p));
            for (Path dll : allDlls) {
                Path kept = keepMap.get(dll.getFileName().toString());
                if (kept != null && !dll.equals(kept)) Files.deleteIfExists(dll);
            }
            for (Map.Entry<String, Path> entry : keepMap.entrySet()) {
                Path dll = entry.getValue();
                Path parent = dll.getParent();
                if (parent != null && !parent.equals(nativesDir))
                    Files.move(dll, nativesDir.resolve(entry.getKey()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            try (Stream<Path> walk = Files.walk(nativesDir)) {
                walk.sorted((a, b) -> b.toString().length() - a.toString().length())
                        .filter(Files::isDirectory).filter(p -> !p.equals(nativesDir))
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
            }
        } catch (IOException ignored) {}
    }
}
