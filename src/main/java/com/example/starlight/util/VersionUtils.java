package com.example.starlight.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 版本管理工具类
 *
 * 提供版本隐藏/显示、合并版本 JSON、获取已安装版本列表等功能。
 * 参考 PCL2/HMCL 的版本隔离策略：
 * - 安装加载器后，创建自包含的合并 JSON（Forge 风格）
 * - 原版本被标记为隐藏，启动器版本列表不显示
 */
public class VersionUtils {

    private static final Logger LOG = LoggerFactory.getLogger(VersionUtils.class);
    private static final Gson GSON = new Gson();

    /** 隐藏标记文件名 */
    private static final String HIDDEN_MARKER = ".hidden";

    // ================================================================
    //  隐藏 / 显示版本
    // ================================================================

    /**
     * 标记版本为隐藏（创建 .hidden 标记文件）
     * 参考 PCL2 的版本隐藏策略
     */
    public static void hideVersion(Path versionDir) {
        if (!Files.isDirectory(versionDir)) return;
        Path marker = versionDir.resolve(HIDDEN_MARKER);
        if (!Files.exists(marker)) {
            try {
                Files.writeString(marker, "Hidden by Starlight Launcher - used as base for modded version");
            } catch (IOException e) {
                LOG.warn("Failed to create hide marker {}: {}", marker, e.getMessage());
            }
        }
    }

    /**
     * 取消版本隐藏
     */
    public static void showVersion(Path versionDir) {
        if (!Files.isDirectory(versionDir)) return;
        try {
            Files.deleteIfExists(versionDir.resolve(HIDDEN_MARKER));
        } catch (IOException e) {
            LOG.warn("Failed to remove hide marker: {}", e.getMessage());
        }
    }

    /**
     * 判断版本是否被隐藏
     * 检测策略（按优先级）：
     * 1. 存在 .hidden 标记文件
     * 2. version.json 中有 "_hidden": true 字段
     */
    public static boolean isVersionHidden(Path versionDir) {
        if (!Files.isDirectory(versionDir)) return true; // 不存在的目录视为隐藏

        // 策略1：.hidden 标记文件
        if (Files.exists(versionDir.resolve(HIDDEN_MARKER))) return true;

        // 策略2：JSON 中的 _hidden 字段
        Path jsonPath = versionDir.resolve(versionDir.getFileName() + ".json");
        if (Files.exists(jsonPath)) {
            try {
                String content = Files.readString(jsonPath, StandardCharsets.UTF_8);
                JsonObject json = GSON.fromJson(content, JsonObject.class);
                if (json.has("_hidden") && json.get("_hidden").getAsBoolean()) return true;
            } catch (Exception e) {
                // 解析失败则不隐藏
            }
        }

        return false;
    }

    /**
     * 获取已安装的可见版本列表
     * @param gameDir .minecraft 目录
     * @return 可见版本 ID 列表（按名称排序）
     */
    public static List<String> getVisibleVersions(Path gameDir) {
        List<String> versions = new ArrayList<>();
        Path versionsDir = gameDir.resolve("versions");
        if (!Files.isDirectory(versionsDir)) return versions;

        try (var stream = Files.list(versionsDir)) {
            stream.filter(Files::isDirectory)
                  .filter(dir -> !isVersionHidden(dir))
                  .map(dir -> dir.getFileName().toString())
                  .sorted(Comparator.reverseOrder()) // 最新版本在前
                  .forEach(versions::add);
        } catch (IOException e) {
            LOG.warn("Failed to scan version directory: {}", e.getMessage());
        }

        return versions;
    }

    /**
     * 获取所有已安装的版本列表（含隐藏的）
     */
    public static List<String> getAllVersions(Path gameDir) {
        List<String> versions = new ArrayList<>();
        Path versionsDir = gameDir.resolve("versions");
        if (!Files.isDirectory(versionsDir)) return versions;

        try (var stream = Files.list(versionsDir)) {
            stream.filter(Files::isDirectory)
                  .map(dir -> dir.getFileName().toString())
                  .sorted(Comparator.reverseOrder())
                  .forEach(versions::add);
        } catch (IOException e) {
            LOG.warn("Failed to scan version directory: {}", e.getMessage());
        }

        return versions;
    }

    // ================================================================
    //  合并版本 JSON（创建自包含版本）
    // ================================================================

    /**
     * 创建加载器版本 ID
     */
    public static String buildLoaderVersionId(String mcVersion, String loaderType, String loaderVersion) {
        return switch (loaderType.toLowerCase()) {
            case "forge" -> mcVersion + "-forge-" + loaderVersion;
            case "fabric" -> "fabric-loader-" + loaderVersion + "-" + mcVersion;
            case "neoforge" -> mcVersion + "-neoforge-" + loaderVersion;
            case "quilt" -> "quilt-loader-" + loaderVersion + "-" + mcVersion;
            default -> mcVersion + "-" + loaderType + "-" + loaderVersion;
        };
    }

    /**
     * 为 Fabric/Quilt 创建合并版本 JSON（自包含，不依赖 inheritsFrom）
     *
     * Fabric 的 version JSON 默认使用 inheritsFrom 继承原版库列表。
     * 此方法将原版的 libraries、downloads、assetIndex 等合并到 Fabric JSON 中，
     * 生成一个自包含的 JSON（类似 Forge 风格），使启动器只显示一个版本入口。
     *
     * @param loaderVersionId 加载器版本 ID（如 fabric-loader-0.19.2-1.21）
     * @param mcVersion       原版版本号（如 1.21）
     * @param gameDir         .minecraft 目录
     * @return 如果成功返回 true
     */
    public static boolean createMergedVersionJson(String loaderVersionId, String mcVersion, Path gameDir) {
        Path versionsDir = gameDir.resolve("versions");
        Path loaderDir = versionsDir.resolve(loaderVersionId);
        Path loaderJsonPath = loaderDir.resolve(loaderVersionId + ".json");
        Path mcDir = versionsDir.resolve(mcVersion);
        Path mcJsonPath = mcDir.resolve(mcVersion + ".json");

        if (!Files.exists(loaderJsonPath)) {
            LOG.warn("Loader JSON not found: {}", loaderJsonPath);
            return false;
        }
        if (!Files.exists(mcJsonPath)) {
            LOG.warn("Vanilla JSON not found: {}", mcJsonPath);
            return false;
        }

        try {
            JsonObject loaderJson = GSON.fromJson(Files.readString(loaderJsonPath, StandardCharsets.UTF_8), JsonObject.class);
            JsonObject mcJson = GSON.fromJson(Files.readString(mcJsonPath, StandardCharsets.UTF_8), JsonObject.class);

            // 检查是否需要合并（只有有 inheritsFrom 的需要）
            if (!loaderJson.has("inheritsFrom")) {
                LOG.info("Loader JSON is self-contained, no merge needed: {}", loaderVersionId);
                return true;
            }

            // ---- 构建合并后的 JSON ----
            JsonObject merged = new JsonObject();

            // ID
            merged.addProperty("id", loaderVersionId);

            // Type
            merged.addProperty("type", mcJson.has("type") ? mcJson.get("type").getAsString() : "release");

            // Main class（使用加载器的）
            if (loaderJson.has("mainClass")) {
                merged.add("mainClass", loaderJson.get("mainClass"));
            } else if (mcJson.has("mainClass")) {
                merged.add("mainClass", mcJson.get("mainClass"));
            }

            // 合并 libraries：原版库 + 加载器库
            JsonArray mergedLibs = new JsonArray();
            if (mcJson.has("libraries")) {
                for (var lib : mcJson.getAsJsonArray("libraries")) {
                    mergedLibs.add(lib);
                }
            }
            if (loaderJson.has("libraries")) {
                for (var lib : loaderJson.getAsJsonArray("libraries")) {
                    mergedLibs.add(lib);
                }
            }
            merged.add("libraries", mergedLibs);

            // 继承原版的 downloads（客户端 JAR 下载信息）
            inheritJsonField(merged, mcJson, "downloads");

            // 继承原版的 assetIndex
            inheritJsonField(merged, mcJson, "assetIndex");

            // 继承原版的 javaVersion
            inheritJsonField(merged, mcJson, "javaVersion");

            // 继承原版的 arguments（如果加载器没有的话）
            if (!loaderJson.has("arguments") && mcJson.has("arguments")) {
                merged.add("arguments", mcJson.get("arguments"));
            } else if (loaderJson.has("arguments")) {
                merged.add("arguments", loaderJson.get("arguments"));
            }

            // 继承原版的 minecraftArguments（旧版格式）
            inheritJsonField(merged, mcJson, "minecraftArguments");

            // 保留加载器的 releaseTime / time
            inheritJsonField(merged, loaderJson, "releaseTime");
            inheritJsonField(merged, loaderJson, "time");

            // 保留原版的 minimumLauncherVersion
            inheritJsonField(merged, mcJson, "minimumLauncherVersion");

            // ---- 删除 inheritsFrom 字段（自包含版本不需要） ----
            merged.remove("inheritsFrom");

            // ---- 写入合并后的 JSON ----
            Files.writeString(loaderJsonPath, GSON.toJson(merged), StandardCharsets.UTF_8);
            LOG.info("Created merged version JSON: {} (merged from {} + {})", loaderVersionId, mcVersion, loaderVersionId);

            // ---- 复制客户端 JAR（如果不存在） ----
            Path mcJarPath = mcDir.resolve(mcVersion + ".jar");
            Path loaderJarPath = loaderDir.resolve(loaderVersionId + ".jar");
            if (Files.exists(mcJarPath) && !Files.exists(loaderJarPath)) {
                Files.copy(mcJarPath, loaderJarPath, StandardCopyOption.COPY_ATTRIBUTES);
                LOG.info("Copied client JAR: {} -> {}", mcJarPath, loaderJarPath);
            }

            return true;

        } catch (IOException e) {
            LOG.error("Failed to create merged version JSON", e);
            return false;
        }
    }

    /**
     * 沿 {@code inheritsFrom} 继承链解析出「自包含」的版本 JSON 视图（<b>仅内存合并，不改磁盘文件</b>）。
     *
     * <p>本启动器安装的整合包版本只写 {@code id / inheritsFrom / time / releaseTime / type}
     * 五个字段的「壳 JSON」，加载器版本也只带加载器自己的 libraries —— 它们依赖
     * {@code inheritsFrom} 链向上（壳 → 加载器 → 原版）补齐资产索引、库列表与客户端下载信息。
     * 此前资源补全与启动准备直接读壳 JSON 本体：无 {@code assetIndex} 会退化到 legacy 索引、
     * {@code libraries} 为空被当作「无需下载」，全新游戏目录装的整合包因此缺资产缺库。
     *
     * <p>合并语义与启动库 {@code BaseLauncher.resolveInheritsFrom} 一致（参考 HMCL）：<br>
     * libraries 父版本在前、子版本在后；{@code assetIndex / assets / javaVersion /
     * complianceLevel / mainClass / minecraftArguments / downloads} 子版本缺省时继承父版本；
     * {@code arguments.game / jvm} 父子数组拼接（父在前）。带 visited 集合防循环引用，
     * 父版本 JSON 缺失时按原样返回（行为与旧版一致，不抛错）。
     *
     * @param gameDir   游戏根目录（.minecraft）
     * @param versionId 版本文件夹名（也用于定位 {@code versions/<versionId>/<versionId>.json}）
     * @param root      已解析的版本 JSON（可以是壳 JSON 或任意带 inheritsFrom 的版本）
     * @return 合并后的自包含视图；入参无继承链时原样返回
     */
    public static JsonObject resolveInheritedJson(Path gameDir, String versionId, JsonObject root) {
        if (root == null) return null;
        return resolveInheritedJson(gameDir, root, new java.util.LinkedHashSet<>());
    }

    /** 内部递归：{@code visited} 记录已展开的父版本 id，防止循环引用 */
    private static JsonObject resolveInheritedJson(Path gameDir, JsonObject json, java.util.Set<String> visited) {
        if (json == null || !json.has("inheritsFrom")) return json;
        String parentId;
        try {
            parentId = json.get("inheritsFrom").getAsString();
        } catch (Exception e) {
            return json;   // inheritsFrom 不是字符串：忽略，不影响后续流程
        }
        if (parentId == null || parentId.isBlank() || !visited.add(parentId)) return json;

        Path parentPath = gameDir.resolve("versions").resolve(parentId).resolve(parentId + ".json");
        if (!Files.exists(parentPath)) {
            LOG.warn("Inherited version JSON not found: {}", parentPath);
            return json;
        }
        JsonObject parent;
        try {
            parent = GSON.fromJson(Files.readString(parentPath, StandardCharsets.UTF_8), JsonObject.class);
        } catch (Exception e) {
            LOG.warn("Failed to read inherited version JSON: {}", parentPath, e);
            return json;
        }
        if (parent == null) return json;
        parent = resolveInheritedJson(gameDir, parent, visited);

        JsonObject merged = json.deepCopy();

        // ---- 合并 libraries：父版本在前、子版本在后 ----
        JsonArray libs = new JsonArray();
        if (parent.has("libraries") && parent.get("libraries").isJsonArray()) {
            parent.getAsJsonArray("libraries").forEach(libs::add);
        }
        if (merged.has("libraries") && merged.get("libraries").isJsonArray()) {
            merged.getAsJsonArray("libraries").forEach(libs::add);
        }
        if (libs.size() > 0) merged.add("libraries", libs);

        // ---- 关键字段：子版本缺省时继承父版本 ----
        // downloads 也一并继承：壳 JSON 没有 downloads.client，客户端 jar 下载依赖它
        for (String key : new String[]{"assetIndex", "assets", "javaVersion", "complianceLevel",
                "mainClass", "minecraftArguments", "downloads"}) {
            if (!merged.has(key) && parent.has(key)) merged.add(key, parent.get(key));
        }

        mergeInheritedArguments(merged, parent);
        // 产出的是自包含视图：移除继承标记，避免调用方（如启动库 setVersionJson）
        // 对同一棵继承树二次解析、把父版本 libraries 重复拼一遍
        merged.remove("inheritsFrom");
        return merged;
    }

    /**
     * 合并父/子版本的 {@code arguments.game / jvm} 数组（父在前、子在后），
     * 与启动库 {@code BaseLauncher.mergeArguments} 的语义一致。
     */
    private static void mergeInheritedArguments(JsonObject child, JsonObject parent) {
        if (!parent.has("arguments") || !parent.get("arguments").isJsonObject()) return;
        if (!child.has("arguments")) {
            child.add("arguments", parent.getAsJsonObject("arguments").deepCopy());
            return;
        }
        if (!child.get("arguments").isJsonObject()) return;

        JsonObject childArgs = child.getAsJsonObject("arguments");
        JsonObject parentArgs = parent.getAsJsonObject("arguments");
        for (String section : new String[]{"game", "jvm"}) {
            if (!parentArgs.has(section) || !parentArgs.get(section).isJsonArray()) continue;
            JsonArray arr = parentArgs.getAsJsonArray(section).deepCopy();
            if (childArgs.has(section) && childArgs.get(section).isJsonArray()) {
                childArgs.getAsJsonArray(section).forEach(arr::add);
            }
            childArgs.add(section, arr);
        }
    }

    /**
     * 按 {@code group:artifact[:classifier]} 对版本 JSON 的 libraries 去重（<b>不含版本号</b>），
     * <b>后出现的覆盖先出现的</b>（与 {@code inheritsFrom} 合并语义一致），并原地改写传入的 JSON。
     *
     * <p>合并型版本 JSON 里同一 artifact 常有多个版本并存 —— 实测 HNT（1.7.10 Forge 整合包）
     * 同时有 {@code guava:15.0} 与 {@code guava:17.0}，而 FML 的 AccessTransformer 需要
     * guava 16+ 的 {@code CharSource.readLines(LineProcessor)}；类路径按出现顺序先命中 15.0
     * 就会在启动时报 {@code NoSuchMethodError}。PCL2 装的包能跑，正是因为它做了同样的去重。
     *
     * <p>键里保留 classifier：1.20+ 的版本 JSON 会把同一个 artifact 按平台列成多条
     * （{@code org.lwjgl:lwjgl:natives-windows} 等），只按 {@code group:artifact} 去重会把
     * natives 条目一起删掉，现代版本反而起不来。
     *
     * @return 去重后的 libraries 条目数；JSON 无 libraries 时返回 -1（未改动）
     */
    public static int deduplicateLibraries(JsonObject versionJson) {
        if (versionJson == null || !versionJson.has("libraries")
                || !versionJson.get("libraries").isJsonArray()) {
            return -1;
        }
        JsonArray libraries = versionJson.getAsJsonArray("libraries");
        java.util.LinkedHashMap<String, com.google.gson.JsonElement> byArtifact = new java.util.LinkedHashMap<>();
        int original = 0;
        for (com.google.gson.JsonElement element : libraries) {
            original++;
            if (!element.isJsonObject()) continue;
            JsonObject lib = element.getAsJsonObject();
            if (!lib.has("name") || !lib.get("name").isJsonPrimitive()) continue;
            byArtifact.put(libraryKey(lib.get("name").getAsString()), element);
        }
        if (byArtifact.size() == original) return original;   // 无重复，保持原样不动

        JsonArray deduped = new JsonArray();
        for (com.google.gson.JsonElement element : byArtifact.values()) deduped.add(element);
        versionJson.add("libraries", deduped);
        LOG.info("Deduplicated libraries: {} -> {}", original, deduped.size());
        return deduped.size();
    }

    /**
     * libraries 去重键：{@code group:artifact} + 可选的 {@code :classifier}，<b>去掉版本号</b>。
     * 坐标形如 {@code group:artifact:version[:classifier][@ext]}，取不到版本的退化为整串。
     */
    private static String libraryKey(String coordinate) {
        String[] parts = coordinate.split(":");
        if (parts.length < 3) return coordinate;
        StringBuilder key = new StringBuilder(parts[0]).append(':').append(parts[1]);
        for (int i = 3; i < parts.length; i++) key.append(':').append(parts[i]);
        return key.toString();
    }

    /**
     * 合并完成后：隐藏原版版本
     */
    public static void hideBaseVersionAfterMerge(String mcVersion, Path gameDir) {
        Path mcDir = gameDir.resolve("versions").resolve(mcVersion);
        if (Files.isDirectory(mcDir)) {
            hideVersion(mcDir);
            LOG.info("Vanilla version hidden: {}", mcVersion);
        }
    }

    // ================================================================
    //  内部工具
    // ================================================================

    /**
     * 如果目标 JSON 缺少某字段，从源 JSON 继承
     */
    private static void inheritJsonField(JsonObject target, JsonObject source, String key) {
        if (!target.has(key) && source.has(key)) {
            target.add(key, source.get(key));
        }
    }

    /**
     * 清理空目录
     */
    public static void cleanEmptyDirs(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .filter(Files::isDirectory)
                .forEach(p -> {
                    try {
                        try (var files = Files.list(p)) {
                            if (files.findAny().isEmpty()) {
                                Files.delete(p);
                            }
                        }
                    } catch (IOException ignored) {}
                });
        } catch (IOException ignored) {}
    }
}
