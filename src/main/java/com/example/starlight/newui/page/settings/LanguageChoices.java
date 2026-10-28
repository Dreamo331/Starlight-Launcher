package com.example.starlight.newui.page.settings;

import com.example.starlight.newui.LauncherContext;
import com.example.starlight.util.I18N;

/**
 * 「显示语言」的可选项与切换逻辑（唯一真源）。
 *
 * <p>主界面页与旧语言页都从这里取，避免两处各写一份 {@code "简体中文".equals(...)} 的三元表达式。
 *
 * <p>下拉项承载的是<b>语言代码</b>而不是显示名：旧实现存显示名、再用中文字面量反推代码，
 * 界面语言一旦切换，反推必然失配，配置会被写成错误的代码（而且是静默的）。语言代码是稳定标识。
 *
 * <p>只支持简体中文 / English。历史配置里的 ja_JP 由 {@link I18N#localeOf(String)} 统一回落中文。
 */
final class LanguageChoices {

    /** 支持的语言代码，顺序即下拉框顺序 */
    static final String[] CODES = {"zh_CN", "en_US"};

    private LanguageChoices() {
    }

    /** 把任意配置值收敛到受支持的代码之一 */
    static String normalize(String code) {
        for (String c : CODES) {
            if (c.equalsIgnoreCase(code)) return c;
        }
        return "zh_CN";
    }

    /** 语言代码 → 显示名（各语言的自称，避免「中文 / Chinese」混排） */
    static String displayName(String code) {
        if ("en_US".equalsIgnoreCase(code)) return "English";
        return I18N.get("setting.t0001");
    }

    /**
     * 选中某项后落盘。
     *
     * <p>界面语言为「重启生效」：框架在构建期取值，运行期整窗重建代价高且会丢状态，
     * 所以这里只写配置并如实提示，不假装已经切换。
     */
    static void apply(LauncherContext ctx, String code) {
        String normalized = normalize(code);
        String previous = ctx.config().getOrDefault("Language", "zh_CN");
        if (normalized.equalsIgnoreCase(previous)) return;
        ctx.config().put("Language", normalized);
        ctx.saveConfig();
        ctx.toast(I18N.get("lang.switchNeedsRestart"));
    }
}
