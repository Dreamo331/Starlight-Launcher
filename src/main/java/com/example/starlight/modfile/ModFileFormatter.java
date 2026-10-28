package com.example.starlight.modfile;


import com.example.starlight.util.I18N;
import com.example.starlight.ModsApi.LocalizedRemoteModRepository;
import com.example.starlight.ModsApi.ModTranslations;
import com.example.starlight.ModsApi.RemoteMod;
import com.example.starlight.ModsApi.RemoteModRepository;

import java.io.File;

/**
 * 模组文件名 / 标题 / 指纹工具（从 LauncherView 抽离，逻辑与原实现保持一致）。
 *
 * <p>职责：
 * <ul>
 *   <li>落盘文件名模板渲染（{@code {name}} / {@code {file}} / {@code {ver}} 占位符 + 非法字符净化）；</li>
 *   <li>「Mod 管理样式」决定列表标题 / 详情标题显示译名还是文件名；</li>
 *   <li>中文译名短名提取（{@code 机械动力 (Create)} → {@code 机械动力}）；</li>
 *   <li>本地模组搜索关键词匹配（文件名 / modId / 中文名 / 英文名 / 缩写）；</li>
 *   <li>文件指纹（最后修改时间 + 大小，用于本地模组元数据缓存失效判断）。</li>
 * </ul>
 *
 * <p>配置项由调用方从配置表读出后以参数传入，本类不直接读配置，
 * 因此不依赖 JavaFX，也不依赖主界面状态。
 */
public final class ModFileFormatter {

    private ModFileFormatter() {
    }

    /** 模组下载文件名格式（{name}=译名或原名，{file}=原始文件名，{ver}=版本号） */
    public static final String[] FILENAME_FORMATS = {
            "[{name}] {file}",
            "{file}",
            "[{name}] {file}-{ver}",
            "{name}-{file}"
    };
    /**
     * 文件名格式示例（界面展示用）。
     *
     * <p>不能写成 {@code static final String = I18N.get(...)}：字段在<b>类加载时</b>
     * 求值，语言还没切过来就被固定住，切到英文后这里仍是中文。
     */
    public static String filenameSample() {
        return I18N.get("modfile.t0001");
    }

    // 显示样式：配置里存的是下面的**稳定 key**，不是界面文案。
    // 旧实现把中文文案直接当配置值和 switch 分派依据——界面语言一变，
    // switch 全部落到 default，用户的样式设置静默失效。

    /** 详情显示译名、列表显示文件名 */
    public static final String STYLE_DETAIL_LOCALIZED = "detailLocalized";
    /** 详情显示文件名、列表显示译名 */
    public static final String STYLE_DETAIL_RAW = "detailRaw";
    /** 只显示译名 */
    public static final String STYLE_LOCALIZED_ONLY = "localizedOnly";

    /** 全部样式 key（顺序即下拉顺序） */
    public static final String[] DISPLAY_STYLE_KEYS = {
            STYLE_DETAIL_LOCALIZED, STYLE_DETAIL_RAW, STYLE_LOCALIZED_ONLY
    };

    /** style key -> i18n 键（显示名） */
    public static String styleLabelKey(String styleKey) {
        if (STYLE_DETAIL_RAW.equals(styleKey)) return "modfile.styleDetailRaw";
        if (STYLE_LOCALIZED_ONLY.equals(styleKey)) return "modfile.styleLocalizedOnly";
        return "modfile.styleDetailLocalized";
    }

    /** 把旧的（中文文案）或新的配置值统一收敛成 style key */
    public static String normalizeStyle(String rawStyle) {
        if (rawStyle == null || rawStyle.isBlank()) return DISPLAY_STYLE_KEYS[0];
        for (String k : DISPLAY_STYLE_KEYS) {
            if (k.equalsIgnoreCase(rawStyle)) return k;
        }
        // 兼容历史配置：旧值就是中文文案
        String zh1 = I18N.get("modfile.styleDetailLocalized");
        String zh2 = I18N.get("modfile.styleDetailRaw");
        String zh3 = I18N.get("modfile.styleLocalizedOnly");
        if (zh2.equals(rawStyle)) return STYLE_DETAIL_RAW;
        if (zh3.equals(rawStyle)) return STYLE_LOCALIZED_ONLY;
        if (zh1.equals(rawStyle)) return STYLE_DETAIL_LOCALIZED;
        return DISPLAY_STYLE_KEYS[0];
    }

    /**
     * 判断模组文件是否匹配搜索关键词：文件名 / modId / 中文名 / 英文名 / 缩写 任一命中即可。
     * 传入的 keyword 需已转小写。
     */
    public static boolean matchesKeyword(String fileName, ModTranslations.Mod translation, String keyword) {
        if (fileName.toLowerCase(java.util.Locale.ROOT).contains(keyword)) return true;
        if (translation == null) return false;

        if (containsIgnoreCase(translation.getName(), keyword)
                || containsIgnoreCase(translation.getSubname(), keyword)
                || containsIgnoreCase(translation.getAbbr(), keyword)
                || containsIgnoreCase(translation.getCurseforge(), keyword)) {
            return true;
        }
        for (String modId : translation.getModIds()) {
            if (containsIgnoreCase(modId, keyword)) return true;
        }
        return false;
    }

    private static boolean containsIgnoreCase(String text, String lowerKeyword) {
        return text != null && !text.isEmpty()
                && text.toLowerCase(java.util.Locale.ROOT).contains(lowerKeyword);
    }

    /** 文件指纹：最后修改时间 + 大小，用于判断缓存是否失效 */
    public static String fingerprintOf(File file) {
        return file.lastModified() + ":" + file.length();
    }

    /**
     * 按「高级设置 → 文件名格式」生成落盘的模组文件名。
     *
     * <p>模板占位符：{@code {name}} = 中文译名（字典没收录时退回原名）、
     * {@code {file}} = 原始文件名（含扩展名）、{@code {ver}} = 版本号。
     * 生成结果会再净化一次非法字符，并保证扩展名不丢。
     *
     * @return 新文件名；开关为默认值且模板无占位符时返回 {@code null}（表示不改名）
     */
    public static String applyFileNameFormat(String template, String original, RemoteMod.Version ver,
                                            RemoteModRepository.Type type, RemoteMod titleMod) {
        if (template == null || template.isBlank()) return null;

        int dot = original.lastIndexOf('.');
        String ext = dot > 0 ? original.substring(dot) : "";
        String stem = dot > 0 ? original.substring(0, dot) : original;

        // 译名：只能从中文字典取。取不到时**不改名**（返回 null），
        // 否则 {name} 会退回文件名主体，生成「[sodium-xxx] sodium-xxx.jar」这种重复丑名
        String displayName = null;
        try {
            if (titleMod != null) displayName = localizedShortName(titleMod, type);
        } catch (Exception ignored) {
            // 取不到译名就不改名
        }
        if (displayName == null || displayName.isBlank()) return null;

        String out = template
                .replace("{name}", displayName)
                .replace("{file}", stem)
                .replace("{ver}", String.valueOf(ver.getVersion()));
        out = out.replace('\\', '_').replace('/', '_')
                .replaceAll("[\\x00-\\x1f<>:\"|?*]", "_")
                .replaceAll("\\s+", " ")
                .trim();
        if (out.isEmpty() || out.equals(stem)) return null;
        // 模板里已含扩展名就不重复追加
        return out.toLowerCase(java.util.Locale.ROOT).endsWith(ext.toLowerCase(java.util.Locale.ROOT))
                ? out : out + ext;
    }

    /** 取资源的中文短名（字典没收录时返回原名） */
    private static String localizedShortName(RemoteMod mod, RemoteModRepository.Type type) {
        String full = LocalizedRemoteModRepository.localizedTitle(mod, type);
        if (full == null || full.isBlank()) return null;
        // localizedTitle 形如「机械动力 (Create)」，取括号前的中文名
        int paren = full.indexOf(" (");
        return paren > 0 ? full.substring(0, paren) : full;
    }

    /**
     * 按「高级设置 → Mod 管理样式」决定模组标题显示什么。
     *
     * @param style 「Mod 管理样式」配置值，null 时使用默认样式
     * @param forDetail true=详情页标题，false=列表行标题
     */
    public static String titleByStyle(String style, RemoteMod mod, RemoteModRepository.Type type, boolean forDetail) {
        style = normalizeStyle(style);
        String localized = LocalizedRemoteModRepository.localizedTitle(mod, type);
        String raw = mod.getTitle() == null ? mod.getSlug() : mod.getTitle();
        return switch (style) {
            case STYLE_DETAIL_LOCALIZED -> forDetail ? localized : raw;
            case STYLE_LOCALIZED_ONLY -> localized;
            default -> forDetail ? raw : localized;
        };
    }
}
