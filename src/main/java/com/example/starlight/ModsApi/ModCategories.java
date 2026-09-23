package com.example.starlight.ModsApi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 下载中心的分类 / 加载器中文名映射表。
 *
 * <p>从 VersePc2 的 {@code js/app/mod-browse.js} 中 {@code MODRINTH_CATEGORY_ZH} 与
 * 模组页筛选栏的「加载器」下拉原样移植，用于把后端返回的英文分类名（{@code technology}）
 * 显示为中文（{@code 科技}），并统一加载器筛选用的键与显示名。
 */
public final class ModCategories {

    private ModCategories() {
    }

    /** Modrinth 分类名 → 中文显示名（覆盖面包含模组/整合包/资源包/光影包/数据包通用分类） */
    private static final Map<String, String> MODRINTH_CATEGORY_ZH = new LinkedHashMap<>();

    static {
        MODRINTH_CATEGORY_ZH.put("adventure", "冒险");
        MODRINTH_CATEGORY_ZH.put("cursed", "诅咒");
        MODRINTH_CATEGORY_ZH.put("decoration", "装饰");
        MODRINTH_CATEGORY_ZH.put("equipment", "装备");
        MODRINTH_CATEGORY_ZH.put("food", "食物");
        MODRINTH_CATEGORY_ZH.put("library", "前置库");
        MODRINTH_CATEGORY_ZH.put("magic", "魔法");
        MODRINTH_CATEGORY_ZH.put("optimization", "优化");
        MODRINTH_CATEGORY_ZH.put("storage", "存储");
        MODRINTH_CATEGORY_ZH.put("technology", "科技");
        MODRINTH_CATEGORY_ZH.put("transportation", "交通");
        MODRINTH_CATEGORY_ZH.put("utility", "实用");
        MODRINTH_CATEGORY_ZH.put("world-gen", "世界生成");
        MODRINTH_CATEGORY_ZH.put("game-mechanics", "游戏机制");
        MODRINTH_CATEGORY_ZH.put("social", "社交");
        MODRINTH_CATEGORY_ZH.put("automation", "自动化");
        MODRINTH_CATEGORY_ZH.put("biomes", "生物群系");
        MODRINTH_CATEGORY_ZH.put("blocks", "方块");
        MODRINTH_CATEGORY_ZH.put("bosses", "Boss");
        MODRINTH_CATEGORY_ZH.put("building", "建筑");
        MODRINTH_CATEGORY_ZH.put("chat", "聊天");
        MODRINTH_CATEGORY_ZH.put("combat", "战斗");
        MODRINTH_CATEGORY_ZH.put("dimensions", "维度");
        MODRINTH_CATEGORY_ZH.put("economy", "经济");
        MODRINTH_CATEGORY_ZH.put("entities", "实体");
        MODRINTH_CATEGORY_ZH.put("environment", "环境");
        MODRINTH_CATEGORY_ZH.put("farming", "农业");
        MODRINTH_CATEGORY_ZH.put("hud", "HUD");
        MODRINTH_CATEGORY_ZH.put("items", "物品");
        MODRINTH_CATEGORY_ZH.put("management", "管理");
        MODRINTH_CATEGORY_ZH.put("map", "地图");
        MODRINTH_CATEGORY_ZH.put("minigame", "小游戏");
        MODRINTH_CATEGORY_ZH.put("mobs", "生物");
        MODRINTH_CATEGORY_ZH.put("modded", "模组化");
        MODRINTH_CATEGORY_ZH.put("models", "模型");
        MODRINTH_CATEGORY_ZH.put("multimedia", "多媒体");
        MODRINTH_CATEGORY_ZH.put("performance", "性能");
        MODRINTH_CATEGORY_ZH.put("quests", "任务");
        MODRINTH_CATEGORY_ZH.put("redstone", "红石");
        MODRINTH_CATEGORY_ZH.put("server", "服务器");
        MODRINTH_CATEGORY_ZH.put("skin", "皮肤");
        MODRINTH_CATEGORY_ZH.put("sound", "声音");
        MODRINTH_CATEGORY_ZH.put("structures", "结构");
        MODRINTH_CATEGORY_ZH.put("tweaks", "调整");
        MODRINTH_CATEGORY_ZH.put("vanilla-like", "原版风格");
        MODRINTH_CATEGORY_ZH.put("8x-", "8x-");
        MODRINTH_CATEGORY_ZH.put("16x", "16x");
        MODRINTH_CATEGORY_ZH.put("32x", "32x");
        MODRINTH_CATEGORY_ZH.put("64x", "64x");
        MODRINTH_CATEGORY_ZH.put("128x", "128x");
        MODRINTH_CATEGORY_ZH.put("256x", "256x");
        MODRINTH_CATEGORY_ZH.put("512x+", "512x+");
        MODRINTH_CATEGORY_ZH.put("animation", "动画");
        MODRINTH_CATEGORY_ZH.put("core-shaders", "核心着色器");
        MODRINTH_CATEGORY_ZH.put("compatibility", "兼容性");
        MODRINTH_CATEGORY_ZH.put("cartoon", "卡通");
        MODRINTH_CATEGORY_ZH.put("fantasy", "奇幻");
        MODRINTH_CATEGORY_ZH.put("medieval", "中世纪");
        MODRINTH_CATEGORY_ZH.put("modern", "现代");
        MODRINTH_CATEGORY_ZH.put("photo-realistic", "写实");
        MODRINTH_CATEGORY_ZH.put("semi-realistic", "半写实");
        MODRINTH_CATEGORY_ZH.put("simplistic", "简约");
        MODRINTH_CATEGORY_ZH.put("traditional", "传统");
        MODRINTH_CATEGORY_ZH.put("pbr", "PBR");
        MODRINTH_CATEGORY_ZH.put("colored-lighting", "彩色光照");
        MODRINTH_CATEGORY_ZH.put("path-tracing", "光线追踪");
        MODRINTH_CATEGORY_ZH.put("reflections", "反射");
        MODRINTH_CATEGORY_ZH.put("shadows", "阴影");
        MODRINTH_CATEGORY_ZH.put("volumetric-light", "体积光");
        MODRINTH_CATEGORY_ZH.put("datapack", "数据包");
    }

    /** Modrinth 分类名 → 中文显示名；未收录时原样返回 */
    public static String zhCategory(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String zh = MODRINTH_CATEGORY_ZH.get(raw.toLowerCase(java.util.Locale.ROOT));
        return zh != null ? zh : raw;
    }

    /** 全部 Modrinth 分类名（供分类下拉静态构建，顺序与 VersePc2 保持一致） */
    public static List<String> modrinthCategoryNames() {
        return List.copyOf(MODRINTH_CATEGORY_ZH.keySet());
    }

    // ==================== 加载器 ====================

    /**
     * 加载器筛选项：值（Modrinth facet 用的小写键）+ 显示名。
     * <p>顺序沿用 VersePc2 模组页的加载器下拉：全部 / Fabric / Forge / NeoForge / Quilt。
     */
    public record LoaderOption(String key, String label) {
    }

    private static final List<LoaderOption> LOADER_OPTIONS = List.of(
            new LoaderOption("", "全部"),
            new LoaderOption("fabric", "Fabric"),
            new LoaderOption("forge", "Forge"),
            new LoaderOption("neoforge", "NeoForge"),
            new LoaderOption("quilt", "Quilt")
    );

    public static List<LoaderOption> loaderOptions() {
        return LOADER_OPTIONS;
    }

    /** 加载器键 → 显示名（{@code ""} → 「全部」） */
    public static String loaderLabel(String key) {
        if (key == null || key.isBlank()) return "全部";
        for (LoaderOption o : LOADER_OPTIONS) {
            if (o.key().equalsIgnoreCase(key)) return o.label();
        }
        return key;
    }

    /** 加载器键 → CurseForge 的 modLoaderType 常量；无法识别时返回 {@link CurseForgeAPI#LOADER_ANY} */
    public static int loaderId(String key) {
        if (key == null || key.isBlank()) return CurseForgeAPI.LOADER_ANY;
        return switch (key.toLowerCase(java.util.Locale.ROOT)) {
            case "forge" -> CurseForgeAPI.LOADER_FORGE;
            case "fabric" -> CurseForgeAPI.LOADER_FABRIC;
            case "quilt" -> CurseForgeAPI.LOADER_QUILT;
            case "neoforge" -> CurseForgeAPI.LOADER_NEOFORGE;
            default -> CurseForgeAPI.LOADER_ANY;
        };
    }
}
