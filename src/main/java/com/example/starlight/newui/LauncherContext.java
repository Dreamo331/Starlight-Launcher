package com.example.starlight.newui;

import com.example.starlight.newui.ui.UiService;

import javafx.scene.control.Label;
import javafx.stage.Stage;

import java.util.Map;

/**
 * 主界面上下文：页面类与主壳之间的唯一契约。
 *
 * <p>页面类只依赖本接口，不直接依赖 {@link LauncherView}，避免拆类时产生循环依赖。
 * 配置读写仍由主壳负责持久化；主题 / 背景 / 侧边栏等「应用」动作也由主壳执行。
 */
public interface LauncherContext {

    /** 主窗口（文件选择器 / 目录选择器的 owner） */
    Stage stage();

    /** 当前配置表（可读可写；写后需调用 {@link #saveConfig()} 才会落盘） */
    Map<String, String> config();

    /** 把当前配置表写回磁盘 */
    void saveConfig();

    /** 切换主页面（key 见 LauncherView.switchToPage） */
    void switchToPage(String key);

    /** 切换到某个设置子页面（同时高亮侧边栏项） */
    void switchToSettings(String id);

    /** 通用界面服务（Toast / 弹窗 / 确认-输入面板） */
    UiService ui();

    /** 场景根节点（弹窗挂载点；也用于 {@code lookup} 按 CSS id 查找页面内控件） */
    javafx.scene.layout.StackPane rootPane();

    /** 当前页面 key（尚未切页时为 null） */
    String currentPageKey();

    /** 读取已缓存的页面节点（未缓存返回 null） */
    javafx.scene.Node cachedPage(String key);

    /** 把顶部/侧边导航切到「下载中心」标签（已在该标签时不重复处理） */
    void selectDownloadTab();

    /** 隐藏设置侧边栏（进入下载中心等整页页面时使用） */
    void hideSidebar();

    /** 用系统浏览器打开网页地址（失败仅提示） */
    void openWebUrl(String url);

    /**
     * 下载任务管理器（任务登记到下载管理页与右下角悬浮按钮，见 {@link DownloadTaskManager}）。
     * <p>无主壳的环境（离屏预览等）返回 null，调用方需自行兜底。
     */
    default com.example.starlight.newui.download.DownloadTaskManager downloadTasks() {
        return null;
    }

    /** 探测指定游戏目录下某版本已安装的加载器名（无则返回原版/未知） */
    String detectLoaderForVersion(String gameDir, String version);

    /** 当前截图目录（优先配置项，其次按版本隔离规则推导） */
    String screenshotDir();

    /** 删除确认面板：确认后执行 onConfirm */
    void confirmDelete(String message, Runnable onConfirm);

    /** 切换到下载中心并选中指定分类（0=游戏版本 1=整合包 2=模组 3=资源包 4=光影包 5=数据包 6=世界） */
    void openDownloadCategory(int idx);

    /** 写入页面缓存（页面内部重建自身或刷新首页缓存时使用） */
    void cachePage(String key, javafx.scene.Node page);

    /** 重建并缓存首页（切换游戏目录等场景后刷新「最近游玩」） */
    void refreshHomeCache();

    /** 切换当前游戏目录（path 为目标目录，reloadPage 非空时切换后重建该页），返回提示文案 */
    String switchGameDir(String path, String reloadPage);

    /** 预热 JVM：后台扫描本机 Java 运行时（供 JVM 设置页与内存页复用） */
    void preheatJvmAsync();

    /** 游戏目录变更后重建按目录扫描的页面缓存（版本选择 / 资源 / 首页） */
    void refreshDependentPageCaches();

    /** 重建首页缓存；若当前停留在首页则原地刷新显示 */
    void refreshHomePage();

    /** 「内存优化」卡片（实现在 newui.page.MemoryPage，供高级设置页嵌入） */
    javafx.scene.Node buildMemoryOptimizeCard();

    /** 把导航切到「首页」标签（顶部标签选中态同步） */
    void selectHomeTab();

    /** 启动游戏（使用当前配置） */
    void launchGame();

    /** 用指定配置启动游戏（首页「最近游玩」直接进存档等场景） */
    void launchGame(com.example.starlight.gui.UIGeneralControlClass.LaunchConfig presetConfig);

    /** 首页快捷工具：按名称执行对应操作（启动器日志 / 崩溃报告 / 截图 / 存档 / 游戏目录） */
    void handleQuickTool(String toolName);

    /** 便捷 Toast（等价于 {@code ui().toast(msg)}） */
    default void toast(String msg) {
        ui().toast(msg);
    }

    // ==================== 主壳状态与「立即应用」动作 ====================

    /** 当前主题 key（light / dark / system） */
    String currentTheme();

    /** 应用主题：light / dark / system */
    void applyTheme(String theme);

    /** 应用背景图片（读取配置后立即生效） */
    void applyBackgroundImage();

    /** 选择背景图片文件并应用，同时把文件名回显到 infoLabel */
    void chooseBackgroundImage(Label infoLabel);

    /** 应用侧边栏显隐（读取配置后立即生效） */
    void applySidebarVisibility();

    /** 应用窗口按钮样式（仿 macOS 三点式 / 减号+叉号，读取配置后立即重建标题栏按钮） */
    void applyWindowButtonStyle();

    /** 应用高对比度（读取配置后立即生效） */
    void applyHighContrast();

    /** 重建全部设置子页面（侧边栏显隐变化、配置变更后调用） */
    void rebuildSettingsPages();

    /** 当前选中的侧边栏项 id（未选中返回 null） */
    String currentSidebarItemId();

    /** 手动检查更新 */
    void checkForUpdates();

    /** 顶部导航栏「模组」页显隐（依配置 HomeQuickManageMod 同步） */
    void updateModTabVisibility();

    /** 清空本地模组的译名与指纹缓存（字典更新后强制重新识别） */
    void clearLocalModCache();

    /** 重新扫描并渲染本地模组列表 */
    void refreshModList();

    /** 用系统默认程序打开文件或文件夹（失败仅提示） */
    void openFile(String path);

    // ==================== 账号相关 ====================

    /** 重新从磁盘加载配置表（账号切换等场景后调用） */
    void loadConfig();

    /** 账号数据变化后重建「Account / 游戏账户档案」页缓存，正停留在该页时原地刷新 */
    void refreshAccountPages();

    /** 微软登录（设备码流） */
    void microsoftLogin();

    /** 离线登录 */
    void offlineLogin();

    /** 第三方（外置登录）账号登录 */
    void thirdPartyLogin();

    /** 应用色盲辅助模式（读取配置后立即生效） */
    void applyColorBlindMode();
}

