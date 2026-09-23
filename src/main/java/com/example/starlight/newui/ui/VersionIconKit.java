package com.example.starlight.newui.ui;

import com.example.starlight.version.VersionDownloadService;
import com.startgame.launcher.LoaderDetector;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javafx.scene.Node;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * 已安装版本的识别与图标：判定加载器类型、是否整合包，并按判定结果给出图标节点。
 *
 * <p>版本选择页的版本列表与首页「版本选择」卡片共用本类，保证两处的判定和图标一致。
 *
 * <h3>整合包判定标准（唯一依据：{@link #isModpack}）</h3>
 * 只认<b>本启动器安装的整合包</b>：版本 JSON 里<b>有 {@code inheritsFrom} 且没有 {@code mainClass}</b>
 * —— 这是 {@code DownloadCenterPage.installModpackArchive} 创建包版本目录时写出的格式；
 * 原版与 Fabric / Forge / NeoForge 的版本 JSON 都带 {@code mainClass}，所以不会误判。
 *
 * <p><b>外部启动器（PCL2 等）安装的整合包不在识别范围内</b>：它们的版本目录里没有任何
 * 「这是整合包」的标记 —— 实测把 PCL2 装的包与同一个游戏版本的普通 Forge 安装逐项对比，
 * 版本 JSON 结构一致（一样带 {@code mainClass}），{@code PCL/Setup.ini} 也只差
 * {@code VersionApiCode} / {@code VersionOriginal*} 几个与包无关的字段；
 * 而按名字推断（例如「名字不是版本号形式且有 mods」）会随用户的命名习惯大量误判，
 * 故本类不做名字推断，这些版本仍按底层加载器归类。
 */
public final class VersionIconKit {

    private VersionIconKit() {
    }

    /** 一个已安装版本的展示信息：版本文件夹名 + 加载器 + 继承的父版本号（无则空串）+ 是否整合包 */
    public record Info(String dirName, String loader, String base, boolean modpack) {
    }

    /** 读取一个已安装版本：一次读盘同时拿到加载器、父版本与是否整合包 */
    public static Info read(String gameDir, String version) {
        JsonObject json = readVersionJson(gameDir, version);
        if (json == null) return new Info(version, "", "", false);

        String base = "";
        try {
            if (json.has("inheritsFrom")) base = json.get("inheritsFrom").getAsString();
        } catch (Exception ignored) {
            // inheritsFrom 不是字符串时忽略，不影响加载器探测
        }

        return new Info(version, detectLoader(json, version), base, isModpack(json));
    }

    /**
     * 统一的加载器判定入口：先走 {@link #refineLoader} 补判，再兜底核心 jar 的
     * {@link LoaderDetector}。启动（{@code GameLauncherService} / {@code UIGeneralControlClass}）、
     * 崩溃诊断（{@code GameCrashPanel}）、加载器查找（{@code VersionMatcher}）与版本列表都必须
     * 共用本方法 —— 之前启动路径漏了补判，1.7.10/1.12.2 这类 mainClass 为
     * {@code net.minecraft.launchwrapper.Launch} 的 Forge 版本被当成原版启动，
     * 结果 Forge 整合包进游戏直接 ClassNotFound。
     */
    public static String detectLoader(JsonObject json, String version) {
        String refined = refineLoader(json);
        if (refined != null) return refined;
        try {
            return LoaderDetector.detect(json, version);
        } catch (Exception e) {
            return LoaderDetector.VANILLA;
        }
    }

    /**
     * 是否整合包 —— 唯一判据：版本 JSON 有 {@code inheritsFrom} 且没有 {@code mainClass}。
     * <p>外部启动器安装的整合包不在此列（原因见类注释）。
     */
    public static boolean isModpack(JsonObject versionJson) {
        return versionJson != null && versionJson.has("inheritsFrom") && !versionJson.has("mainClass");
    }

    /** 读取版本目录下的 {@code <版本文件夹名>.json}；文件不存在或无法解析时返回 null */
    private static JsonObject readVersionJson(String gameDir, String version) {
        try {
            Path jsonPath = Paths.get(gameDir, "versions", version, version + ".json");
            if (!Files.exists(jsonPath)) return null;
            return JsonParser.parseString(Files.readString(jsonPath)).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 加载器补判：{@link LoaderDetector} 的主类名单不完整，且它在核心 jar 里（本工程改不到），
     * 这里用版本 JSON 里更确定的证据补齐 —— 只在能得出确定结论时返回结果，其余情况返回 null
     * 交回 LoaderDetector（含按版本名兜底）。
     *
     * <p>补的几档（括号内是实测踩到的问题）：
     * <ul>
     *   <li>{@code org.quiltmc…} → Quilt（LoaderDetector 先判 Fabric 的 {@code KnotClient}，
     *       而 Quilt 的真实主类正是 {@code org.quiltmc.loader.impl.launch.knot.KnotClient}，
     *       不先拦下来 Quilt 会被算成 Fabric）；</li>
     *   <li>mainClass 或 libraries 含 {@code net.neoforged} → NeoForge（NeoForge 21.1 与 Forge
     *       Modern 共用 {@code BootstrapLauncher} 主类，只能靠 libraries 区分，否则 NeoForge
     *       被算成 Forge (Modern)）；</li>
     *   <li>mainClass 含 {@code launchwrapper}（忽略大小写）→ Forge (Legacy)
     *       —— LoaderDetector 里写成 {@code "LaunchWrapper"}，而真实主类是
     *       {@code net.minecraft.launchwrapper.Launch}，永远匹配不上，于是 1.7.10 / 1.12.2 的
     *       Forge 版本会掉到「按名字」甚至「无关键词 → Vanilla」；</li>
     *   <li>{@code cpw.mods.modlauncher} / {@code BootstrapLauncher} / {@code ForgeBootstrap}
     *       → Forge (Modern) —— modlauncher 这一档（1.13~1.16.x）LoaderDetector 没有列，
     *       会退回按名字判断，而名字规则以 1.17 为界，把 1.16.x 错判成 Legacy；</li>
     *   <li>{@code net.fabricmc…} / {@code KnotClient} → Fabric。</li>
     * </ul>
     */
    private static String refineLoader(JsonObject json) {
        if (json == null) return null;
        String mainClass = jsonString(json, "mainClass").toLowerCase(Locale.ROOT);
        if (mainClass.isEmpty()) return null;

        if (mainClass.contains("quiltmc")) return LoaderDetector.QUILT;
        if (mainClass.contains("net.neoforged") || hasLibraryPrefix(json, "net.neoforged")) {
            return LoaderDetector.NEOFORGE;
        }
        if (mainClass.contains("launchwrapper")) return LoaderDetector.FORGE_LEGACY;
        if (mainClass.contains("bootstraplauncher") || mainClass.contains("modlauncher")
                || mainClass.contains("forgebootstrap")) {
            return LoaderDetector.FORGE_MODERN;
        }
        if (mainClass.contains("fabricmc") || mainClass.contains("knotclient")) return LoaderDetector.FABRIC;
        // 原版（net.minecraft.client.main.Main 等）与没见过的加载器：交回原检测
        return null;
    }

    /** 读字符串字段；字段不存在或类型不对时空串 */
    private static String jsonString(JsonObject json, String key) {
        try {
            return json.has(key) ? json.get(key).getAsString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** libraries 里是否有以指定前缀开头的依赖（用于区分共用主类的 NeoForge / Forge Modern） */
    private static boolean hasLibraryPrefix(JsonObject json, String prefix) {
        try {
            if (!json.has("libraries") || !json.get("libraries").isJsonArray()) return false;
            for (JsonElement element : json.getAsJsonArray("libraries")) {
                if (element.isJsonObject()
                        && jsonString(element.getAsJsonObject(), "name").startsWith(prefix)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // 结构异常时按「没有」处理
        }
        return false;
    }

    /** 整合包图标（与下载中心「整合包」分类同一个 SVG 图标） */
    public static Node modpackIcon(double size) {
        return AppIcons.icon("package", size);
    }

    /**
     * 加载器图标：原版与未知加载器用版本图标（草方块），加载器用各自的 PNG
     * （Forge → forge.png、Fabric → Fabric.png、NeoForge → NeoForge.png、Quilt → quilt.png）。
     */
    public static Node loaderIcon(String loader, double size) {
        if (loader == null || loader.isBlank() || LoaderDetector.VANILLA.equals(loader)) {
            return IconService.versionIconView(VersionDownloadService.VersionCategory.RELEASE, size);
        }
        return IconService.loaderIconView(loader, size);
    }

    /** 版本图标：整合包 → 包图标；其余 → 按加载器 */
    public static Node icon(Info info, double size) {
        if (info == null) return loaderIcon(null, size);
        return info.modpack() ? modpackIcon(size) : loaderIcon(info.loader(), size);
    }
}
