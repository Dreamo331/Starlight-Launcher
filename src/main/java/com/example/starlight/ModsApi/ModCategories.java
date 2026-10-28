package com.example.starlight.ModsApi;


import com.example.starlight.util.I18N;
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
        MODRINTH_CATEGORY_ZH.put("adventure", I18N.get("modsapi.t0007"));
        MODRINTH_CATEGORY_ZH.put("cursed", I18N.get("modsapi.t0008"));
        MODRINTH_CATEGORY_ZH.put("decoration", I18N.get("modsapi.t0009"));
        MODRINTH_CATEGORY_ZH.put("equipment", I18N.get("modsapi.t0010"));
        MODRINTH_CATEGORY_ZH.put("food", I18N.get("modsapi.t0011"));
        MODRINTH_CATEGORY_ZH.put("library", I18N.get("modsapi.t0012"));
        MODRINTH_CATEGORY_ZH.put("magic", I18N.get("modsapi.t0013"));
        MODRINTH_CATEGORY_ZH.put("optimization", I18N.get("modsapi.t0014"));
        MODRINTH_CATEGORY_ZH.put("storage", I18N.get("modsapi.t0015"));
        MODRINTH_CATEGORY_ZH.put("technology", I18N.get("modsapi.t0016"));
        MODRINTH_CATEGORY_ZH.put("transportation", I18N.get("modsapi.t0017"));
        MODRINTH_CATEGORY_ZH.put("utility", I18N.get("modsapi.t0018"));
        MODRINTH_CATEGORY_ZH.put("world-gen", I18N.get("modsapi.t0019"));
        MODRINTH_CATEGORY_ZH.put("game-mechanics", I18N.get("modsapi.t0020"));
        MODRINTH_CATEGORY_ZH.put("social", I18N.get("modsapi.t0021"));
        MODRINTH_CATEGORY_ZH.put("automation", I18N.get("modsapi.t0022"));
        MODRINTH_CATEGORY_ZH.put("biomes", I18N.get("modsapi.t0023"));
        MODRINTH_CATEGORY_ZH.put("blocks", I18N.get("modsapi.t0024"));
        MODRINTH_CATEGORY_ZH.put("bosses", "Boss");
        MODRINTH_CATEGORY_ZH.put("building", I18N.get("modsapi.t0025"));
        MODRINTH_CATEGORY_ZH.put("chat", I18N.get("modsapi.t0026"));
        MODRINTH_CATEGORY_ZH.put("combat", I18N.get("modsapi.t0027"));
        MODRINTH_CATEGORY_ZH.put("dimensions", I18N.get("modsapi.t0028"));
        MODRINTH_CATEGORY_ZH.put("economy", I18N.get("modsapi.t0029"));
        MODRINTH_CATEGORY_ZH.put("entities", I18N.get("modsapi.t0030"));
        MODRINTH_CATEGORY_ZH.put("environment", I18N.get("modsapi.t0031"));
        MODRINTH_CATEGORY_ZH.put("farming", I18N.get("modsapi.t0032"));
        MODRINTH_CATEGORY_ZH.put("hud", "HUD");
        MODRINTH_CATEGORY_ZH.put("items", I18N.get("modsapi.t0033"));
        MODRINTH_CATEGORY_ZH.put("management", I18N.get("modsapi.t0034"));
        MODRINTH_CATEGORY_ZH.put("map", I18N.get("modsapi.t0035"));
        MODRINTH_CATEGORY_ZH.put("minigame", I18N.get("modsapi.t0036"));
        MODRINTH_CATEGORY_ZH.put("mobs", I18N.get("modsapi.t0037"));
        MODRINTH_CATEGORY_ZH.put("modded", I18N.get("modsapi.t0038"));
        MODRINTH_CATEGORY_ZH.put("models", I18N.get("modsapi.t0039"));
        MODRINTH_CATEGORY_ZH.put("multimedia", I18N.get("modsapi.t0040"));
        MODRINTH_CATEGORY_ZH.put("performance", I18N.get("modsapi.t0041"));
        MODRINTH_CATEGORY_ZH.put("quests", I18N.get("modsapi.t0042"));
        MODRINTH_CATEGORY_ZH.put("redstone", I18N.get("modsapi.t0043"));
        MODRINTH_CATEGORY_ZH.put("server", I18N.get("modsapi.t0044"));
        MODRINTH_CATEGORY_ZH.put("skin", I18N.get("modsapi.t0045"));
        MODRINTH_CATEGORY_ZH.put("sound", I18N.get("modsapi.t0046"));
        MODRINTH_CATEGORY_ZH.put("structures", I18N.get("modsapi.t0047"));
        MODRINTH_CATEGORY_ZH.put("tweaks", I18N.get("modsapi.t0048"));
        MODRINTH_CATEGORY_ZH.put("vanilla-like", I18N.get("modsapi.t0049"));
        MODRINTH_CATEGORY_ZH.put("8x-", "8x-");
        MODRINTH_CATEGORY_ZH.put("16x", "16x");
        MODRINTH_CATEGORY_ZH.put("32x", "32x");
        MODRINTH_CATEGORY_ZH.put("64x", "64x");
        MODRINTH_CATEGORY_ZH.put("128x", "128x");
        MODRINTH_CATEGORY_ZH.put("256x", "256x");
        MODRINTH_CATEGORY_ZH.put("512x+", "512x+");
        MODRINTH_CATEGORY_ZH.put("animation", I18N.get("modsapi.t0050"));
        MODRINTH_CATEGORY_ZH.put("core-shaders", I18N.get("modsapi.t0051"));
        MODRINTH_CATEGORY_ZH.put("compatibility", I18N.get("modsapi.t0052"));
        MODRINTH_CATEGORY_ZH.put("cartoon", I18N.get("modsapi.t0053"));
        MODRINTH_CATEGORY_ZH.put("fantasy", I18N.get("modsapi.t0054"));
        MODRINTH_CATEGORY_ZH.put("medieval", I18N.get("modsapi.t0055"));
        MODRINTH_CATEGORY_ZH.put("modern", I18N.get("modsapi.t0056"));
        MODRINTH_CATEGORY_ZH.put("photo-realistic", I18N.get("modsapi.t0057"));
        MODRINTH_CATEGORY_ZH.put("semi-realistic", I18N.get("modsapi.t0058"));
        MODRINTH_CATEGORY_ZH.put("simplistic", I18N.get("modsapi.t0059"));
        MODRINTH_CATEGORY_ZH.put("traditional", I18N.get("modsapi.t0060"));
        MODRINTH_CATEGORY_ZH.put("pbr", "PBR");
        MODRINTH_CATEGORY_ZH.put("colored-lighting", I18N.get("modsapi.t0061"));
        MODRINTH_CATEGORY_ZH.put("path-tracing", I18N.get("modsapi.t0062"));
        MODRINTH_CATEGORY_ZH.put("reflections", I18N.get("modsapi.t0063"));
        MODRINTH_CATEGORY_ZH.put("shadows", I18N.get("modsapi.t0064"));
        MODRINTH_CATEGORY_ZH.put("volumetric-light", I18N.get("modsapi.t0065"));
        MODRINTH_CATEGORY_ZH.put("datapack", I18N.get("modsapi.t0066"));
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

    // 加载器 //

    /**
     * 加载器筛选项：值（Modrinth facet 用的小写键）+ 显示名。
     * <p>顺序沿用 VersePc2 模组页的加载器下拉：全部 / Fabric / Forge / NeoForge / Quilt。
     */
    public record LoaderOption(String key, String label) {
    }

    private static final List<LoaderOption> LOADER_OPTIONS = List.of(
            new LoaderOption("", I18N.get("modsapi.t0067")),
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
        if (key == null || key.isBlank()) return I18N.get("modsapi.t0068");
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
