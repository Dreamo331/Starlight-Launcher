/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.service;

import com.example.starlight.model.*;
import com.example.starlight.listsaves.listsaves;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * 模组管理服务
 */
public class ModService {

    /** 列出所有模组 */
    public static List<ModInfo> listMods(String gameDir, String version) {
        List<ModInfo> mods = new ArrayList<>();
        Path globalDir = Paths.get(gameDir, "mods");
        Path versionDir = version.isEmpty() ? null : Paths.get(gameDir, "versions", version, "mods");
        if (Files.isDirectory(globalDir)) scanModDir(globalDir, "[全局]", mods);
        if (versionDir != null && Files.isDirectory(versionDir)) scanModDir(versionDir, "[版本]", mods);
        mods.sort(Comparator.comparing(m -> m.name));
        return mods;
    }

    /** 切换模组启用/禁用 */
    public static ActionResult toggleMod(String modPath) {
        try {
            Path path = Paths.get(modPath);
            String fname = path.getFileName().toString();
            Path newPath;
            if (fname.endsWith(".disabled")) {
                newPath = path.resolveSibling(fname.substring(0, fname.length() - 9));
                Files.move(path, newPath);
                return ActionResult.ok("已启用 " + newPath.getFileName());
            } else {
                newPath = path.resolveSibling(fname + ".disabled");
                Files.move(path, newPath);
                return ActionResult.ok("已禁用 " + fname);
            }
        } catch (IOException e) {
            return ActionResult.fail("操作失败: " + e.getMessage());
        }
    }

    /** 获取模组目录 */
    public static String getModsDir(String gameDir, String version) {
        Path dir = Paths.get(gameDir, "versions", version, "mods");
        if (Files.isDirectory(dir)) return dir.toAbsolutePath().toString();
        return Paths.get(gameDir, "mods").toAbsolutePath().toString();
    }

    /** 列出资源包 */
    public static List<PackInfo> listResourcePacks(String gameDir) {
        Path rpDir = Paths.get(gameDir, "resourcepacks");
        if (!Files.isDirectory(rpDir)) return Collections.emptyList();
        Set<String> activePacks = readActiveResourcePacks(gameDir);
        List<PackInfo> packs = new ArrayList<>();
        try (Stream<Path> stream = Files.list(rpDir)) {
            stream.filter(p -> Files.isDirectory(p) || p.getFileName().toString().endsWith(".zip"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        boolean active = activePacks.contains(name) || activePacks.contains(name.replace(".zip", ""));
                        packs.add(new PackInfo(name, p.toAbsolutePath().toString(), active));
                    });
        } catch (IOException ignored) {}
        return packs;
    }

    /** 列出光影包 */
    public static List<PackInfo> listShaderPacks(String gameDir) {
        Path shaderDir = Paths.get(gameDir, "shaderpacks");
        if (!Files.isDirectory(shaderDir)) return Collections.emptyList();
        List<PackInfo> packs = new ArrayList<>();
        try (Stream<Path> stream = Files.list(shaderDir)) {
            stream.filter(p -> Files.isDirectory(p) || p.getFileName().toString().endsWith(".zip"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> packs.add(new PackInfo(p.getFileName().toString(), p.toAbsolutePath().toString(), false)));
        } catch (IOException ignored) {}
        return packs;
    }

    /** 列出模组配置文件 */
    public static List<ConfigFileInfo> listConfigFiles(String gameDir) {
        Path cfgDir = Paths.get(gameDir, "config");
        if (!Files.isDirectory(cfgDir)) return Collections.emptyList();
        List<ConfigFileInfo> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(cfgDir)) {
            stream.filter(p -> !Files.isDirectory(p))
                    .filter(p -> { String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith(".cfg") || n.endsWith(".toml") || n.endsWith(".json")
                                || n.endsWith(".txt") || n.endsWith(".yaml") || n.endsWith(".yml"); })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> {
                        try { files.add(new ConfigFileInfo(p.getFileName().toString(), p.toAbsolutePath().toString(), Files.size(p))); }
                        catch (IOException e) { files.add(new ConfigFileInfo(p.getFileName().toString(), p.toAbsolutePath().toString(), 0)); }
                    });
        } catch (IOException ignored) {}
        return files;
    }

    /** 读取配置文件内容 */
    public static ActionResult readConfigFileContent(String filePath) {
        try {
            String content = Files.readString(Paths.get(filePath), StandardCharsets.UTF_8);
            return ActionResult.ok("读取成功", content);
        } catch (IOException e) {
            return ActionResult.fail("读取失败: " + e.getMessage());
        }
    }

    // ================================================================
    //  内部
    // ================================================================

    private static void scanModDir(Path dir, String origin, List<ModInfo> list) {
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".jar")
                            || p.getFileName().toString().endsWith(".disabled"))
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        boolean enabled = !name.endsWith(".disabled");
                        if (!enabled) name = name.substring(0, name.length() - 9);
                        list.add(new ModInfo(name, p.toAbsolutePath().toString(), enabled, origin));
                    });
        } catch (IOException ignored) {}
    }

    private static Set<String> readActiveResourcePacks(String gameDir) {
        Path optionsFile = Paths.get(gameDir, "options.txt");
        if (!Files.exists(optionsFile)) return Collections.emptySet();
        Set<String> active = new HashSet<>();
        try {
            List<String> opts = Files.readAllLines(optionsFile, StandardCharsets.UTF_8);
            for (String line : opts) {
                if (line.startsWith("resourcePacks:")) {
                    String json = line.substring("resourcePacks:".length()).trim();
                    if (json.startsWith("[")) {
                        com.google.gson.JsonArray arr = new com.google.gson.Gson().fromJson(json, com.google.gson.JsonArray.class);
                        for (var el : arr) {
                            String packName = Paths.get(el.getAsString()).getFileName().toString();
                            if (!packName.isEmpty()) active.add(packName);
                        }
                    }
                    break;
                }
            }
        } catch (IOException ignored) {}
        return active;
    }
}
