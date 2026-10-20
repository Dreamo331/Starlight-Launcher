package com.example.starlight.newui.ui;

import java.util.Map;

/**
 * 新旧设置页导航 ID 路由（重构 §5.2 / §7 NavRouter）。
 *
 * <p>侧边栏压缩为 6 组 16 项后，四个旧 id 从导航清单移除，但深链与页内跳转必须继续可达：
 * 所有 {@code switchToSettings(id)} 调用先过 {@link #resolve}——命中的是活 id 原样返回，
 * 命中的是旧 id 则重定向到新归属页。
 */
public final class NavRouter {

    private NavRouter() {
    }

    /**
     * 旧导航 id → 新归属（深链兼容映射，重构 §5.2）。
     *
     * <p>值含义：map[id] = 新的目标导航 id。调用方（LauncherView.switchToSettings）
     * 重定向后可按需追加「高亮/展开」动作。
     */
    public static final Map<String, String> LEGACY_REDIRECTS = Map.of(
            // 版权页 → 关于页（版权内容并入关于页的许可卡片）
            "sidebarLicense", "sidebarAbout",
            // 模组页 → 游戏目录页（模组管理并入游戏目录页折叠卡）
            "sidebarMod", "sidebarGameDir",
            // 语言页 → 主界面页（显示语言并入主界面页）
            "sidebarLanguage", "sidebarMainUi",
            // 色盲辅助页 → 辅助功能页（色盲辅助降为辅助功能页内的子配置入口）。
            // 注意：辅助功能页的「详细配置」按钮仍以本 id 跳转，路由后高亮辅助功能项即可。
            "sidebarColorBlind", "sidebarAccessibility"
    );

    /** 旧 id 是否需要重定向 */
    public static boolean isLegacy(String id) {
        return id != null && LEGACY_REDIRECTS.containsKey(id);
    }

    /**
     * 解析导航 id：活 id 原样返回；旧 id 返回重定向目标；未知 id 原样返回
     * （由调用方决定「页面开发中」兜底，行为与改造前一致）。
     */
    public static String resolve(String id) {
        if (id == null) return null;
        return LEGACY_REDIRECTS.getOrDefault(id, id);
    }
}
