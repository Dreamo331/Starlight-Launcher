package com.example.starlight.newui;

import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.Controllers.PageView;
import com.example.starlight.auth.AccountManager;
import com.example.starlight.config.GameDirManager;
import com.example.starlight.colorblind.ColorBlindOverlayManager;
import com.example.starlight.crash.CrashDiagnosticData;
import com.example.starlight.crash.CrashReportParser;
import com.example.starlight.launch.LaunchRecord;
import com.example.starlight.launch.LaunchRecordStore;
import com.example.starlight.listsaves.listsaves;
import com.example.starlight.download.DownloadSettings;
import com.example.starlight.download.VerifySettings;
import com.example.starlight.download.DownloadProvider;
import com.example.starlight.download.GameResourceCompleter;
import com.example.starlight.download.HttpDownloadEngine;
import com.example.starlight.slan.SLanConfig;
import com.example.starlight.slan.SLanManager;
import com.example.starlight.slan.SLanServerChecker;
import com.example.starlight.service.ParallelLaunchManager;
import com.example.starlight.service.SilentLoginManager;
import com.example.starlight.auth.AccountManager.Account;
import com.example.starlight.gui.UIGeneralControlClass;
import com.example.starlight.gui.UIGeneralControlClass.LaunchConfig;
import com.example.starlight.gui.UIGeneralControlClass.ResultCallback;
import com.example.starlight.listjava.FindAllJavaWindows;
import com.example.starlight.listjava.JavaCacheManager;
import com.example.starlight.loginmicrosoft.MinecraftAuthLauncherDeviceCode;
import com.example.starlight.loginmicrosoft.MinecraftAuthLauncherDeviceCode.DeviceCodeInfo;
import com.example.starlight.modfile.ModFileFormatter;
import com.example.starlight.modpack.ModpackManifestParser;
import com.example.starlight.modpack.PackFile;
import com.example.starlight.modpack.PackManifest;
import com.example.starlight.ModsApi.CurseForgeAPI;
import com.example.starlight.ModsApi.CurseForgeRemoteModRepository;
import com.example.starlight.ModsApi.LocalModMetadata;
import com.example.starlight.ModsApi.LocalizedRemoteModRepository;
import com.example.starlight.ModsApi.ModCategories;
import com.example.starlight.ModsApi.ModDictionaryManager;
import com.example.starlight.ModsApi.ModLoaderType;
import com.example.starlight.ModsApi.ModSearchQuery;
import com.example.starlight.ModsApi.ModTranslations;
import com.example.starlight.ModsApi.ModrinthRemoteModRepository;
import com.example.starlight.ModsApi.RemoteMod;
import com.example.starlight.ModsApi.RemoteModDetail;
import com.example.starlight.ModsApi.RemoteModRepository;
import com.example.starlight.version.InstallEngine;
import com.example.starlight.version.LoaderInstallEngine;
import com.example.starlight.version.VersionDownloadService;
import com.example.starlight.version.VersionManifest;
import com.example.starlight.version.VersionMatcher;
import com.example.starlight.plugin.PluginManager;
import com.example.starlight.plugin.PluginMetadata;
import com.example.starlight.main.ConfigManager;
import com.example.starlight.community.CommunityApi;
import com.example.starlight.community.CommunityApi.ApiResult;
import com.example.starlight.community.CommunityApi.CheckinResult;
import com.example.starlight.community.CommunityApi.CommunityComment;
import com.example.starlight.community.CommunityApi.CommunityPost;
import com.example.starlight.community.CommunityApi.CommunityUser;
import com.example.starlight.update.UpdateInstaller;
import com.example.starlight.newui.download.ModHit;
import com.example.starlight.newui.download.RemoteCacheService;
import com.example.starlight.newui.download.SearchCacheEntry;
import com.example.starlight.newui.download.DownloadTaskCard;
import com.example.starlight.newui.download.DownloadTaskManager;
import com.example.starlight.newui.ui.IconService;
import com.example.starlight.newui.page.settings.AboutPage;
import com.example.starlight.newui.page.settings.AccessibilityPage;
import com.example.starlight.newui.page.settings.ColorBlindPage;
import com.example.starlight.newui.page.CommunityPage;
import com.example.starlight.newui.framegen.FrameGenCoordinator;
import com.example.starlight.newui.login.LoginCoordinator;
import com.example.starlight.newui.ui.LaunchOverlay;
import com.example.starlight.newui.page.settings.DevPage;
import com.example.starlight.newui.ui.FabMenu;
import com.example.starlight.newui.ui.ThemeManager;
import com.example.starlight.newui.ui.UiEffects;
import com.example.starlight.newui.update.UpdateCoordinator;
import com.example.starlight.newui.page.HomePage;
import com.example.starlight.newui.page.DownloadCenterPage;
import com.example.starlight.newui.page.MemoryPage;
import com.example.starlight.newui.page.ModManagerPage;
import com.example.starlight.newui.page.settings.AdvancedPage;
import com.example.starlight.newui.page.settings.GameDirPage;
import com.example.starlight.newui.page.settings.JvmPage;
import com.example.starlight.newui.page.NetworkCheckPage;
import com.example.starlight.newui.page.ResourcesPage;
import com.example.starlight.newui.page.VersionSelectPage;
import com.example.starlight.newui.page.MultiplayerPage;
import com.example.starlight.newui.page.settings.AccountPage;
import com.example.starlight.newui.page.settings.DownloadSettingsPage;
import com.example.starlight.newui.page.settings.FeedbackPage;
import com.example.starlight.newui.page.settings.GameAccountPage;
import com.example.starlight.newui.page.settings.ModPage;
import com.example.starlight.newui.page.settings.MultiplayerSettingsPage;
import com.example.starlight.newui.page.settings.ProxySettingsPage;
import com.example.starlight.newui.page.settings.LanguagePage;
import com.example.starlight.newui.page.settings.LicensePage;
import com.example.starlight.newui.page.settings.MainUiPage;
import com.example.starlight.newui.page.settings.SponsorPage;
import com.example.starlight.newui.page.settings.ThemePage;
import com.example.starlight.newui.page.settings.VersionSettingsPage;
import com.example.starlight.newui.ui.PageKit;
import com.example.starlight.newui.ui.SkeletonFactory;
import com.example.starlight.newui.ui.UiService;
import com.example.starlight.util.ArchiveUtils;
import com.example.starlight.util.AvatarGenerator;
import com.example.starlight.util.DebugLog;
import com.example.starlight.util.FeedbackEmailSender;
import com.example.starlight.util.FormatUtils;
import com.example.starlight.util.I18N;
import com.example.starlight.util.MojangAvatarFetcher;
import com.example.starlight.util.ProxyConfig;
import com.example.starlight.util.ResourceScanner;
import com.example.starlight.util.RemoteImageLoader;
import com.example.starlight.util.SystemInfoMonitor;
import com.example.starlight.util.UpdateChecker;
import javafx.beans.value.ChangeListener;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import org.starlight.terracotta.*;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.awt.Desktop;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.nio.file.*;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import com.google.gson.*;
import com.startgame.launcher.LoaderDetector;

/**
 * 新版主界面 —— 独立可调用类，不依赖 FXML
 * 仿 LauncherController + Launcher.fxml
 */
public class LauncherView implements PageView, LauncherContext {

    private ExecutorService executor; // 异步任务执行器
    private final StackPane rootPane;
    private final BorderPane contentPane;
    private StackPane centerStack;
    private final VBox mainContent;
    private final VBox sidebar;
    private final ScrollPane sidebarScroll;
    private final Button homeTab, downloadTab, resourcesTab, newsTab, multiplayerTab, settingsTab, modTab;
    /** 标题栏右上角的窗口按钮容器（样式可切换，见 rebuildWindowControls） */
    private final HBox windowControls = new HBox(8);
    private final Stage stage;

    private double xOffset, yOffset;
    private final Map<String, Node> pageCache = new HashMap<>();
    private Node currentPage;
    private Button currentSidebarItem;
    private Button currentTab;
    /** 「模组」页：已安装模组列表容器与数量标签（模组页重建时更新引用） */
    /** Java 虚拟机与内存设置页（实现在 newui.page.settings.JvmPage） */
    private JvmPage jvmPage;
    /** 游戏目录设置页（实现在 newui.page.settings.GameDirPage） */
    private GameDirPage gameDirPage;
    /** 高级设置页（实现在 newui.page.settings.AdvancedPage） */
    private AdvancedPage advancedPage;
    /** 版本独立设置页（实现在 newui.page.settings.VersionSettingsPage） */
    private VersionSettingsPage versionSettingsPage;
    /** 顶部导航「模组」页（实现在 newui.page.ModManagerPage） */
    private ModManagerPage modManagerPage;
    /** 当前页面 key（用于「返回上一页」这类需要知道来源的场景） */
    private String currentPageKey;
    /** 下载中心页（版本/整合包/模组/资源/光影/世界，实现在 newui.page.DownloadCenterPage） */
    private DownloadCenterPage downloadPage;
    /** 下载任务管理：任务列表、下载管理页、右下角悬浮按钮（实现见 newui.download.DownloadTaskManager） */
    private final DownloadTaskManager downloads;

    /** 下载中心远程数据缓存：搜索结果 / 项目版本列表 / 项目详情（实现见 newui.download.RemoteCacheService） */
    private final RemoteCacheService remoteCache = new RemoteCacheService();

    /** 资源管理页（存档 / 崩溃报告 / 截图 / 日志）与截图管理页（实现在 newui.page.ResourcesPage） */
    private ResourcesPage resourcesPage;
    /** 版本选择页（实现在 newui.page.VersionSelectPage） */
    private VersionSelectPage versionSelectPage;
    /** 网络检测页（实现在 newui.page.NetworkCheckPage） */
    private NetworkCheckPage networkCheckPage;
    /** 内存管理页与内存优化卡片（实现在 newui.page.MemoryPage） */
    private MemoryPage memoryPage;
    private Map<String, String> configCache;
    /** 通用界面服务：Toast / 遮罩弹窗 / 确认-提示-输入面板（实现见 newui.ui.UiService） */
    private final UiService ui;

    /** 帧生成器协调器（引擎 / 托盘 / 设置页，实现在 newui.framegen.FrameGenCoordinator） */
    private FrameGenCoordinator frameGen;

    // 开发者选项：调试日志显示区与实时监听器（页面重建时先移除旧监听，防止重复注册）
    private TextArea devLogArea;
    /** 开发者选项页与调试日志区（实现在 newui.page.settings.DevPage） */
    private DevPage devPage;


    /** 首页：快捷启动 / 游戏信息 / 系统监控（实现在 newui.page.HomePage） */
    private HomePage homePage;

    // 最小化标志
    private boolean hasBeenIconified = false;

    /** 社区页（登录 / 签到 / 发帖 / 评论 / 点赞，实现在 newui.page.CommunityPage） */
    private final CommunityPage communityPage;
    /** 联机页（陶瓦 / FRP / EasyTier，实现在 newui.page.MultiplayerPage） */
    private final MultiplayerPage multiplayerPage;

    /** 游戏启动动画弹窗（实现在 newui.ui.LaunchOverlay） */
    private final LaunchOverlay overlay;
    /** 登录流程（微软设备码 / 第三方 / 离线，实现在 newui.login.LoginCoordinator） */
    private final LoginCoordinator login;
    /** 主题与背景管理（实现在 newui.ui.ThemeManager） */
    private final ThemeManager themeMgr;
    /** 更新流程（检查 / 升级对话框 / 自替换，实现在 newui.update.UpdateCoordinator） */
    private final UpdateCoordinator updater;

    /** 右下角统一悬浮操作按钮（FAB）：主按钮 + 飞出的功能子按钮；有可用功能时才显示 */
    private FabMenu fab;
    /** 「关闭游戏进程」在 FAB 里的槽位（右上，红色危险样式） */
    private static final int FAB_STOP_SLOT = 2;
    /** 悬浮按钮显隐巡检：进程可能在启动器不知情时结束（被任务管理器结束等），靠回调不够 */
    private Timeline gameStopFabWatch;

    // ==================== 窗口控制 ====================

    private void handleMaximize() {
        stage.setMaximized(!stage.isMaximized());
    }
    private void handleClose() { Platform.exit(); }

    /** 最小化：先播放缩小淡出动画，动画结束再真正最小化 */
    private void minimizeWindow() {
        ScaleTransition st = new ScaleTransition(Duration.millis(150), rootPane);
        st.setToX(0.3); st.setToY(0.3);
        FadeTransition ft = new FadeTransition(Duration.millis(150), rootPane);
        ft.setToValue(0.0);
        ParallelTransition pt = new ParallelTransition(st, ft);
        pt.setOnFinished(ev -> {
            rootPane.setScaleX(1.0);
            rootPane.setScaleY(1.0);
            rootPane.setOpacity(1.0);
            stage.setIconified(true);
        });
        pt.play();
    }

    /**
     * 是否使用仿 macOS 的三点式窗口按钮（配置 {@code MacStyleWindowButtons}，默认开启）。
     * 关闭时改为「减号 + 叉号」两个按钮。
     *
     * <p>构造期（{@link #loadConfig()} 之前）配置表还是 null，这时按默认值「开启」处理；
     * 读配置后由 {@link #applyWindowButtonStyle()} 再按保存的值刷一次。
     */
    private boolean macStyleWindowButtons() {
        if (configCache == null) return true;
        return !"false".equalsIgnoreCase(configCache.getOrDefault("MacStyleWindowButtons", "true"));
    }

    /**
     * 重建标题栏右上角的窗口按钮。
     *
     * <p>仿 macOS 三点式：黄点=最小化 / 绿点=最大化 / 红点=关闭（与改动前一致）；
     * 关闭该开关后：减号=最小化 / 叉号=关闭（不含最大化）。
     * 两种样式只用样式类区分（{@code win-macos} / {@code win-classic}），具体外观见 style.css。
     */
    private void rebuildWindowControls() {
        boolean macStyle = macStyleWindowButtons();
        windowControls.getChildren().clear();
        windowControls.getStyleClass().removeAll("win-macos", "win-classic");
        windowControls.getStyleClass().add(macStyle ? "win-macos" : "win-classic");

        Button minBtn = createWindowBtn("minimize");
        minBtn.setOnAction(e -> minimizeWindow());
        windowControls.getChildren().add(minBtn);

        if (macStyle) {
            Button maxBtn = createWindowBtn("maximize");
            maxBtn.setOnAction(e -> handleMaximize());
            windowControls.getChildren().add(maxBtn);
        }

        Button closeBtn = createWindowBtn("close");
        closeBtn.setOnAction(e -> handleClose());
        windowControls.getChildren().add(closeBtn);

        // 右侧图标按钮微交互（hover 上移放大、按下缩回）；按钮每次重建都要重新装一遍
        windowControls.getChildren().forEach(com.example.starlight.newui.ui.NavEffects::installNavButton);
    }

    // ==================== 构造 ====================

    public LauncherView(Stage stage) {
        this.stage = stage;

        rootPane = new StackPane();
        this.ui = new UiService(rootPane);
        this.communityPage = new CommunityPage(this);
        this.multiplayerPage = new MultiplayerPage(this);
        this.downloads = new DownloadTaskManager(rootPane, ui, new DownloadTaskManager.Host() {
            @Override public String currentPageKey() { return currentPageKey; }
            @Override public void switchToPage(String key) { LauncherView.this.switchToPage(key); }
            @Override public boolean hasDetailContext() { return downloadPage != null && downloadPage.hasDetailContext(); }
            @Override public boolean hasVersionPickContext() { return downloadPage != null && downloadPage.hasVersionPickContext(); }
        }, this);

        // ===== 页面实例（共享单例：跨切页保留页面状态与缓存语义） =====
        this.downloadPage = new DownloadCenterPage(this, downloads);
        this.resourcesPage = new ResourcesPage(this);
        this.versionSelectPage = new VersionSelectPage(this);
        this.networkCheckPage = new NetworkCheckPage(this);
        this.memoryPage = new MemoryPage(this);
        this.jvmPage = new JvmPage(this);
        this.gameDirPage = new GameDirPage(this);
        this.advancedPage = new AdvancedPage(this);
        this.versionSettingsPage = new VersionSettingsPage(this);
        this.modManagerPage = new ModManagerPage(this);
        this.homePage = new HomePage(this);
        this.frameGen = new FrameGenCoordinator(this);
        this.overlay = new LaunchOverlay(this);
        this.updater = new UpdateCoordinator(this);
        this.login = new LoginCoordinator(this);
        this.themeMgr = new ThemeManager(this);
        this.devPage = new DevPage(this);
        contentPane = new BorderPane();
        rootPane.getStyleClass().addAll("root", "light");
        rootPane.setStyle("-fx-background-radius: 12; -fx-border-radius: 12;");
        rootPane.getChildren().add(contentPane);

        // ===== Top: 标题栏 + Tab栏 =====
        VBox topContainer = new VBox();
        topContainer.getStyleClass().add("top-container");

        // 窗口控制按钮（样式由「仿 macOS 三点式窗口按钮」开关决定，见 rebuildWindowControls）
        windowControls.setAlignment(Pos.CENTER_RIGHT);
        windowControls.setPadding(new Insets(8, 14, 0, 0));
        windowControls.getStyleClass().add("window-controls");
        rebuildWindowControls();

        // Tab 栏
        HBox tabBar = new HBox(6);
        tabBar.setAlignment(Pos.CENTER);
        tabBar.setPadding(new Insets(6, 0, 4, 0));
        tabBar.getStyleClass().add("tab-bar");

        homeTab = createTab("首页", "homeTab", true);
        downloadTab = createTab("下载", "downloadTab", false);
        resourcesTab = createTab("资源", "resourcesTab", false);
        newsTab = createTab("社区", "newsTab", false);
        multiplayerTab = createTab("联机", "multiplayerTab", false);
        settingsTab = createTab("设置", "settingsTab", false);
        // 模组页：默认隐藏，由「首页快速管理MOD」开关控制（loadConfig 后调用 updateModTabVisibility 同步显隐）
        modTab = createTab("模组", "modTab", false);
        modTab.setVisible(false);
        modTab.setManaged(false);

        tabBar.getChildren().addAll(homeTab, downloadTab, resourcesTab, newsTab, multiplayerTab, settingsTab, modTab);
        topContainer.getChildren().addAll(windowControls, tabBar);

        // 顶部导航动效：弹性滑动指示条 + 页签水波纹 + 图标微交互（滚动收缩由切页时 watchScroll 接）
        com.example.starlight.newui.ui.NavEffects.install(tabBar);

        currentTab = homeTab;

        // ===== Center: 主体布局 =====
        HBox mainBody = new HBox();
        mainBody.getStyleClass().add("main-body");

        // 侧边栏
        sidebarScroll = new ScrollPane();
        PageKit.configureScrollPane(this, sidebarScroll);
        sidebarScroll.setVisible(false);
        sidebarScroll.setManaged(false);

        sidebar = new VBox();
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(200);
        sidebar.setMinWidth(200);
        sidebar.setMaxWidth(200);

        buildSidebar();

        sidebarScroll.setContent(sidebar);

        // 主内容区域
        mainContent = new VBox();
        mainContent.getStyleClass().add("main-content");
        mainContent.setMaxWidth(Double.MAX_VALUE);
        mainContent.setMaxHeight(Double.MAX_VALUE);
        // 固定 prefWidth 为 0：内容再宽也只按可用宽度分配（grow 拉伸），
        // 避免页面内容 prefWidth 过大时 HBox 空间不足、压缩侧边栏导致页面被撑大
        mainContent.setPrefWidth(0);
        HBox.setHgrow(mainContent, Priority.ALWAYS);
        VBox.setVgrow(mainContent, Priority.ALWAYS);

        // 标题栏拖拽
        topContainer.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            xOffset = stage.getX() - e.getScreenX();
            yOffset = stage.getY() - e.getScreenY();
        });
        topContainer.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (!stage.isMaximized()) {
                stage.setX(e.getScreenX() + xOffset);
                stage.setY(e.getScreenY() + yOffset);
            }
        });

        mainBody.getChildren().addAll(sidebarScroll, mainContent);
        centerStack = new StackPane(mainBody);
        contentPane.setTop(topContainer);
        contentPane.setCenter(centerStack);

        // 启动动画改为集成式弹窗展示（见 buildLaunchOverlay），随 rootPane 弹出、覆盖全窗口

        // 从最小化恢复时播放展开动画（使用标志位避免首次显示时触发）
        stage.iconifiedProperty().addListener((obs, wasIconified, isNowIconified) -> {
            if (isNowIconified) {
                hasBeenIconified = true;
            } else if (wasIconified && !isNowIconified && hasBeenIconified) {
                rootPane.setScaleX(0.3);
                rootPane.setScaleY(0.3);
                rootPane.setOpacity(0.0);

                ScaleTransition st = new ScaleTransition(Duration.millis(200), rootPane);
                st.setToX(1.0);
                st.setToY(1.0);

                FadeTransition ft = new FadeTransition(Duration.millis(200), rootPane);
                ft.setToValue(1.0);

                ParallelTransition pt = new ParallelTransition(st, ft);
                pt.play();
            }
        });

        // ===== 右下角统一悬浮操作按钮（FAB）：下载管理 / 关闭游戏进程 都收进它 =====
        buildFab();

        // 构造期 applyTheme 时 Scene 还没建好，挂不上深色补充样式表；场景就绪后再同步一次
        rootPane.sceneProperty().addListener((o, ov, nv) -> themeMgr.syncDarkExtraStylesheet());

        // ===== 全局界面动效：按钮水波纹 + 下拉框箭头/面板动画 =====
        // 装在根节点上（内部等场景就绪后挂场景级过滤器），各处 new 出来的按钮与下拉框自动生效
        UiEffects.install(rootPane);

        // ===== 初始化页面 =====
        loadConfig();        // 根据「首页快速管理MOD」开关同步顶部导航栏「模组」页显隐
        updateModTabVisibility();
        // 应用语言配置（I18N 资源包；NEWUI 界面文本为硬编码，切换仅对 I18N 体系生效）
        String lang = configCache.getOrDefault("Language", "zh_CN");
        switch (lang) {
            case "en_US" -> I18N.setLocale(java.util.Locale.ENGLISH);
            case "ja_JP" -> I18N.setLocale(java.util.Locale.JAPANESE);
            default -> I18N.setLocale(java.util.Locale.CHINA);
        }
        // 先设置 currentTheme，确保 buildAllPages 中 buildThemePage 能正确标识选中项
        themeMgr.setCurrentTheme(configCache.getOrDefault("Theme", "light"));
        buildAllPages();
        // 重置 currentTheme 确保 applyTheme 被正常执行（绕过早期返回检查）
        String initialTheme = themeMgr.currentTheme();
        themeMgr.setCurrentTheme("");
        applyTheme(initialTheme);
        // 应用个性化配置（背景图片 / 高对比度 / 侧边栏显隐 / 窗口按钮样式）
        applyBackgroundImage();
        applyHighContrast();
        applySidebarVisibility();
        // 按钮在构造时已建过一次（那时还没读配置），这里按配置再刷一次，
        // 否则「仿 macOS 三点式窗口按钮」关闭后重启不会生效
        applyWindowButtonStyle();
        initColorBlindSupport();
        switchToPage("home");
        homePage.startSystemMonitor();

        // ===== 初始化游戏启动动画弹窗内容 =====
        overlay.build();

        // ===== 自动检查更新：进入首页后延迟检测，发现新版本时在首页弹出提示 =====
        autoCheckForUpdates();

        // ===== 帧生成器模块：历史启用过系统托盘或保存过倍率时随启动器常驻；否则首次打开帧生成页时懒加载 =====
        initFrameGenModule();
    }

    // ==================== 色盲辅助 ====================

    /**
     * 色盲辅助启动初始化：按「记住当前配置」决定是否把类型/强度/窗口标题重置为默认值，
     * 按「启动后自动开启」就绪总开关。这里不拉起矫正工具 —— 它只在游戏启动时才生效。
     */
    private void initColorBlindSupport() {
        try {
            // 重置/自动启用都会直接改配置文件，回写配置缓存保证界面显示与磁盘一致
            configCache.putAll(ColorBlindOverlayManager.bootstrapAtLauncherStartup());
        } catch (Throwable t) {
            System.err.println("[ColorBlind] 启动初始化失败: " + t);
        }
    }

    // ==================== 游戏启动动画 ====================

    /**
     * 标题栏窗口按钮。
     *
     * <p>仿 macOS 三点式（开关默认开启）是纯色圆点，本身不放图标；
     * 关闭开关后是「减号 / 叉号」，图标颜色跟随按钮文字色，深浅主题都看得清。
     *
     * @param role {@code minimize} / {@code maximize} / {@code close}，同时作为样式类
     */
    private Button createWindowBtn(String role) {
        Button btn = new Button();
        btn.getStyleClass().addAll("window-btn", role);
        btn.setCursor(javafx.scene.Cursor.HAND);
        if (macStyleWindowButtons()) {
            btn.setMinSize(12, 12);
            btn.setMaxSize(12, 12);
        } else {
            // 经典样式：尺寸与图标（减号=最小化，叉号=关闭）都按 CSS 里的 .win-classic 走
            btn.setMinSize(30, 24);
            btn.setMaxSize(30, 24);
            AppIcons.apply(btn, "close".equals(role) ? "close" : "minus", 15, null);
        }
        return btn;
    }

    /** 顶部标签页的图标：按标签文案取 */
    private static String tabIcon(String text) {
        return switch (text) {
            case "首页" -> "home";
            case "下载" -> "download";
            case "资源" -> "library";
            case "社区" -> "people";
            case "联机" -> "wifi";
            case "设置" -> "gear";
            case "模组" -> "puzzle";
            default -> null;
        };
    }

    private Button createTab(String text, String id, boolean selected) {
        Button btn = new Button(text);
        btn.setId(id);
        AppIcons.apply(btn, tabIcon(text), 15, null);
        btn.getStyleClass().add("tab-item");
        if (selected) btn.getStyleClass().add("selected");
        btn.setOnAction(this::switchTab);
        btn.setCursor(javafx.scene.Cursor.HAND);
        return btn;
    }

    /** 侧边栏各项图标（键与 buildSidebar 里的 id 一致） */
    private static final java.util.Map<String, String> SIDEBAR_ICONS = java.util.Map.ofEntries(
            java.util.Map.entry("sidebarAccount", "user"),
            java.util.Map.entry("sidebarAbout", "help"),
            java.util.Map.entry("sidebarLicense", "scale"),
            java.util.Map.entry("sidebarGameAccount", "user-cog"),
            java.util.Map.entry("sidebarMod", "puzzle"),
            java.util.Map.entry("sidebarJvm", "cpu"),
            java.util.Map.entry("sidebarGameDir", "folder"),
            java.util.Map.entry("sidebarVersionSettings", "sliders"),
            java.util.Map.entry("sidebarAdvanced", "gear"),
            java.util.Map.entry("sidebarTheme", "palette"),
            java.util.Map.entry("sidebarMainUi", "layout"),
            java.util.Map.entry("sidebarLanguage", "languages"),
            java.util.Map.entry("sidebarAccessibility", "accessibility"),
            java.util.Map.entry("sidebarColorBlind", "view"),
            java.util.Map.entry("sidebarDownload", "download"),
            java.util.Map.entry("sidebarProxy", "network"),
            java.util.Map.entry("sidebarMultiplayer", "wifi"),
            java.util.Map.entry("sidebarFeedback", "send"),
            java.util.Map.entry("sidebarSponsor", "heart"),
            java.util.Map.entry("sidebarDev", "code")
    );

    private void buildSidebar() {
        String[][] sections = {
                {"通用", "sidebarAccount", "Account", "sidebarAbout", "关于", "sidebarLicense", "版权"},
                {"游戏", "sidebarGameAccount", "游戏账户档案", "sidebarMod", "模组", "sidebarJvm", "Java虚拟机与内存", "sidebarGameDir", "游戏目录", "sidebarVersionSettings", "版本独立设置", "sidebarAdvanced", "高级设置"},
                {"个性化", "sidebarTheme", "主题与背景", "sidebarMainUi", "主界面", "sidebarLanguage", "语言", "sidebarAccessibility", "辅助功能", "sidebarColorBlind", "色盲辅助"},
                {"网络", "sidebarDownload", "下载", "sidebarProxy", "代理"},
                {"联机", "sidebarMultiplayer", "联机设置"},
                {"其他", "sidebarFeedback", "服务与反馈", "sidebarSponsor", "赞助我们", "sidebarDev", "开发者选项"}
        };

        for (String[] section : sections) {
            Label secTitle = new Label(section[0]);
            secTitle.getStyleClass().add("sidebar-section-title");
            sidebar.getChildren().add(secTitle);

            for (int i = 1; i < section.length; i += 2) {
                String id = section[i];
                String text = section[i + 1];
                Button btn = new Button(text);
                btn.setId(id);
                AppIcons.apply(btn, SIDEBAR_ICONS.get(id), 15, null);
                btn.getStyleClass().add("sidebar-item");
                btn.setMaxWidth(Double.MAX_VALUE);
                btn.setOnAction(this::selectSidebarItem);
                btn.setCursor(javafx.scene.Cursor.HAND);
                sidebar.getChildren().add(btn);
            }
        }
    }

    private void buildAllPages() {
        pageCache.put("home", buildHomePage());
        // 下载页首次访问时延迟构建（避免启动时立即发起 Modrinth/Mojang 网络请求）
        // 设置子页面统一通过 rebuildSettingsPages 构建（侧边栏显隐影响页面导航形态）
        rebuildSettingsPages();
        pageCache.put("resources", buildResourcesPage());
        pageCache.put("news", buildNewsPage());
        pageCache.put("multiplayer", buildMultiplayerPage());
        pageCache.put("versionSelect", buildVersionSelectPage());
        pageCache.put("networkCheck", buildNetworkCheckPage());
        pageCache.put("memory", buildMemoryPage());
    }

    // [DL-PROBE] 调试探针：-Dstarlight.auto.download=true 时启动后自动进入下载页
    {
        if (Boolean.getBoolean("starlight.auto.download")) {
            Thread probeT = new Thread(() -> {
                try { Thread.sleep(8000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                Platform.runLater(() -> switchToPage("download"));
            }, "auto-enter-download");
            probeT.setDaemon(true);
            probeT.start();
        }
    }

    @Override
    public void switchToPage(String key) {
        DebugLog.log("页面刷新: " + key);
        Node newPage;
        // 首页和截图页面每次访问都实时重建，确保读取最新配置
        if ("screenshots".equals(key) || "home".equals(key)) {
            newPage = "home".equals(key) ? buildHomePage() : buildScreenshotsPage();
            pageCache.put(key, newPage);
        } else if ("postPublish".equals(key)) {
            // 发帖页每次访问重建，保证输入框干净（发布成功后跳回社区页）
            newPage = buildPostPublishPage();
            pageCache.put(key, newPage);
        } else if ("postDetail".equals(key)) {
            // 帖子详情页每次访问重建，展示 currentDetailPost 指向的帖子
            newPage = buildPostDetailPage();
            pageCache.put(key, newPage);
        } else if ("downloadDetail".equals(key)) {
            // 下载详情页：每次访问重建，展示 currentModDetail 指向的资源（主窗口内整页，不是弹窗）
            newPage = buildDownloadDetailPage();
            pageCache.put(key, newPage);
        } else if ("downloadVersionPick".equals(key)) {
            // 选择版本页：每次访问重建，展示 currentVersionPick 指向的资源版本列表
            newPage = buildDownloadVersionPickerPage();
            pageCache.put(key, newPage);
        } else if ("downloadVersionConfig".equals(key)) {
            // 版本下载配置页：每次访问重建，展示 currentVersionConfig 指向的版本
            newPage = buildVersionConfigPage();
            pageCache.put(key, newPage);
        } else if ("downloadManager".equals(key)) {
            // 下载管理页：列出全部下载任务，每次访问重建以反映最新状态
            newPage = downloads.buildManagerPage();
        } else if ("download".equals(key)) {
            // 下载中心延迟构建：首次访问时构建并缓存，避免启动期网络请求
            newPage = pageCache.get("download");
            if (newPage == null) {
                long t0 = System.nanoTime();
                newPage = buildDownloadPage();
                System.err.println("[DL-PROBE] buildDownloadPage build time: " + ((System.nanoTime() - t0) / 1_000_000) + "ms (FX thread)");
                pageCache.put("download", newPage);
            }
        } else if ("mod".equals(key)) {
            // 顶部导航「模组」页：内容暂空（返回按钮 + 页面名称）
            newPage = pageCache.get("mod");
            if (newPage == null) {
                newPage = buildTopModPage();
                pageCache.put("mod", newPage);
            }
        } else if ("frameGen".equals(key)) {
            // 帧生成器页面：首次访问时构建并缓存（构建同时懒启动帧生成引擎）
            newPage = pageCache.get("frameGen");
            if (newPage == null) {
                newPage = buildFrameGenPage();
                pageCache.put("frameGen", newPage);
            }
        } else {
            newPage = pageCache.get(key);
            if (newPage == null) {
                Label label = new Label("页面开发中: " + key);
                label.setStyle("-fx-text-fill: inherit; -fx-font-size: 16px;");
                newPage = new StackPane(label);
            }
        }

        mainContent.getChildren().clear();

        // 资源页、下载中心页、联机页、社区页、帖子详情页、版本选择页自带内部滚动区，不包装外层ScrollPane
        // （版本选择页左右两栏各自独立滚动，套上外层滚动条会导致两栏一起被滑动）
        // 版本独立设置页（sidebarVersionSettings）同样自带内部滚动区：内容滚动 + 底部固定操作栏
        // （恢复默认 / 保存设置），套外层滚动条会把固定栏一起滚走。
        // 注意：downloadDetail（资源详情页）**在**这个列表之外——它整页套外层滚动条，
        //       内部各标签页都不再自带滚动区（见 buildModGalleryTab / buildModVersionsTab）。
        boolean noScroll = "home".equals(key) || "resources".equals(key) || "multiplayer".equals(key)
                || "download".equals(key) || "downloadVersionPick".equals(key)
                || "downloadVersionConfig".equals(key)
                || "downloadManager".equals(key) || "news".equals(key)
                || "postDetail".equals(key) || "versionSelect".equals(key)
                || "sidebarVersionSettings".equals(key);
        if (noScroll) {
            mainContent.getChildren().add(newPage);
            newPage.setOpacity(0);
            newPage.setTranslateY(6);
            FadeTransition ft = new FadeTransition(Duration.millis(200), newPage);
            ft.setToValue(1.0);
            TranslateTransition tt = new TranslateTransition(Duration.millis(200), newPage);
            tt.setToY(0);
            new ParallelTransition(ft, tt).play();
        } else {
            ScrollPane scrollPane = new ScrollPane(newPage);
            PageKit.configureScrollPane(this, scrollPane);
            mainContent.getChildren().add(scrollPane);
            // 滚动超过 20px 时顶部导航栏收缩（自带内部滚动区的页面没有外层 ScrollPane，不参与）
            com.example.starlight.newui.ui.NavEffects.watchScroll(scrollPane);

            newPage.setOpacity(0);
            newPage.setTranslateY(6);
            FadeTransition ft = new FadeTransition(Duration.millis(200), newPage);
            ft.setToValue(1.0);
            TranslateTransition tt = new TranslateTransition(Duration.millis(200), newPage);
            tt.setToY(0);
            new ParallelTransition(ft, tt).play();
        }

        currentPage = newPage;
        currentPageKey = key;
        // 悬浮「下载管理」按钮的显隐取决于当前页与任务状态，切页后刷新一次
        downloads.updateFab();
    }

    private void switchTab(javafx.event.ActionEvent event) {
        Button tab = (Button) event.getSource();
        if (currentTab != null) currentTab.getStyleClass().remove("selected");
        tab.getStyleClass().add("selected");
        currentTab = tab;

        String id = tab.getId();
        boolean isSettings = "settingsTab".equals(id);
        boolean showSidebar = !"false".equalsIgnoreCase(configCache.getOrDefault("ShowSidebar", "true"));
        sidebarScroll.setVisible(isSettings && showSidebar);
        sidebarScroll.setManaged(isSettings && showSidebar);

        if (isSettings) {
            switchToPage("sidebarTheme");
            highlightSidebar("sidebarTheme");
        } else if ("homeTab".equals(id)) {
            switchToPage("home");
        } else if ("downloadTab".equals(id)) {
            switchToPage("download");
        } else if ("resourcesTab".equals(id)) {
            switchToPage("resources");
        } else if ("newsTab".equals(id)) {
            switchToPage("news");
        } else if ("multiplayerTab".equals(id)) {
            switchToPage("multiplayer");
        } else if ("modTab".equals(id)) {
            switchToPage("mod");
        }
    }

    private void selectSidebarItem(javafx.event.ActionEvent event) {
        Button item = (Button) event.getSource();
        highlightSidebar(item.getId());
        switchToPage(item.getId());
    }

    private void highlightSidebar(String id) {
        if (currentSidebarItem != null) currentSidebarItem.getStyleClass().remove("selected");
        for (Node n : sidebar.getChildrenUnmodifiable()) {
            if (n instanceof Button && id.equals(n.getId())) {
                n.getStyleClass().add("selected");
                currentSidebarItem = (Button) n;
                break;
            }
        }
    }

    // ==================== 页面构建 ====================

    /** 重建首页缓存；若用户当前停留在首页则立即刷新显示（存档列表、最近游玩、系统信息等实时数据） */
    @Override
    public void refreshHomePage() {
        pageCache.put("home", buildHomePage());
        if (currentTab == homeTab) {
            mainContent.getChildren().clear();
            mainContent.getChildren().add(pageCache.get("home"));
            currentPage = pageCache.get("home");
        }
    }

    private Node buildHomePage() {
        return homePage.build();
    }

    // ==================== 页面脚手架（实现见 newui.ui.PageKit） ====================

    private VBox createSettingsPage(String title) {
        return PageKit.settingsPage(this, title);
    }

    private VBox createSettingsPage(String title, String backTarget) {
        return PageKit.settingsPage(this, title, backTarget);
    }

    private HBox createSettingsCard(String title, String desc, Node control) {
        return PageKit.settingsCard(title, desc, control);
    }

    private CheckBox createToggle(boolean value) {
        return PageKit.toggle(value);
    }
    /** 重建全部设置子页面（侧边栏显隐切换后，页面导航形态随之变化） */
    @Override
    public void rebuildSettingsPages() {
        // 先移除旧调试日志监听器（devPage 重建时会重新注册，避免重复输出）
        devPage.detachLogListener();
        for (String[] item : PageKit.SETTINGS_NAV_ITEMS) {
            switch (item[0]) {
                case "sidebarAccount" -> pageCache.put(item[0], buildAccountPage());
                case "sidebarAbout" -> pageCache.put(item[0], buildAboutPage());
                case "sidebarLicense" -> pageCache.put(item[0], buildLicensePage());
                case "sidebarGameAccount" -> pageCache.put(item[0], buildGameAccountPage());
                case "sidebarMod" -> pageCache.put(item[0], buildModPage());
                case "sidebarJvm" -> pageCache.put(item[0], buildJvmPage());
                case "sidebarGameDir" -> pageCache.put(item[0], buildGameDirPage());
                case "sidebarVersionSettings" -> pageCache.put(item[0], versionSettingsPage.build());
                case "sidebarAdvanced" -> pageCache.put(item[0], buildAdvancedPage());
                case "sidebarTheme" -> pageCache.put(item[0], buildThemePage());
                case "sidebarMainUi" -> pageCache.put(item[0], buildMainUiPage());
                case "sidebarLanguage" -> pageCache.put(item[0], buildLanguagePage());
                case "sidebarAccessibility" -> pageCache.put(item[0], buildAccessibilityPage());
                case "sidebarColorBlind" -> pageCache.put(item[0], buildColorBlindPage());
                case "sidebarDownload" -> pageCache.put(item[0], buildDownloadSettingsPage());
                case "sidebarProxy" -> pageCache.put(item[0], buildProxySettingsPage());
                case "sidebarMultiplayer" -> pageCache.put(item[0], buildMultiplayerSettingsPage());
                case "sidebarFeedback" -> pageCache.put(item[0], buildFeedbackPage());
                case "sidebarSponsor" -> pageCache.put(item[0], buildSponsorPage());
                case "sidebarDev" -> pageCache.put(item[0], buildDevPage());
            }
        }
    }

    @Override
    public void switchToSettings(String id) {
        if (currentTab != null) currentTab.getStyleClass().remove("selected");
        settingsTab.getStyleClass().add("selected");
        currentTab = settingsTab;
        boolean showSidebar = !"false".equalsIgnoreCase(configCache.getOrDefault("ShowSidebar", "true"));
        sidebarScroll.setVisible(showSidebar);
        sidebarScroll.setManaged(showSidebar);
        highlightSidebar(id);
        switchToPage(id);
    }

    // ===== 设置页面 =====

    private Node buildAccountPage() {
        return new AccountPage(this).build();
    }

    private Node buildAboutPage() {
        return new AboutPage(this).build();
    }

    private Node buildLicensePage() {
        return new LicensePage(this).build();
    }

    private Node buildGameAccountPage() {
        return new GameAccountPage(this).build();
    }

    /** 账号数据变化后刷新「游戏账户档案」与「登录状态」页缓存；若用户正停留在账号档案页则原地刷新显示 */
    @Override
    public void refreshAccountPages() {
        Node oldCached = pageCache.get("sidebarGameAccount");
        pageCache.put("sidebarAccount", buildAccountPage());
        pageCache.put("sidebarGameAccount", buildGameAccountPage());
        // 当前显示的正是旧缓存实例（用户停留在此页）时，原地刷新为新页面
        if (oldCached != null && currentPage == oldCached) {
            switchToPage("sidebarGameAccount");
        }
    }

    private Node buildJvmPage() {
        return jvmPage.build();
    }

    private Node buildGameDirPage() {
        return gameDirPage.build();
    }

    private Node buildAdvancedPage() {
        return advancedPage.build();
    }

    @Override
    public Node buildMemoryOptimizeCard() {
        return memoryPage.buildOptimizeCard();
    }

    private Node buildMultiplayerSettingsPage() {
        return new MultiplayerSettingsPage(this).build();
    }

    private Node buildThemePage() {
        return new ThemePage(this).build();
    }

    private Node buildModPage() {
        return new ModPage(this).build();
    }

    /** 顶部导航栏「模组」页：上传 .jar 模组文件并复制到 Starlight-Launcher\Mod，展示已安装模组列表 */
    private Node buildTopModPage() {
        return modManagerPage.build();
    }

    @Override
    public void openWebUrl(String url) {
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
        } catch (Exception e) {
            ui.toast("打开链接失败: " + e.getMessage());
        }
    }

    @Override
    public DownloadTaskManager downloadTasks() {
        return downloads;
    }


    @Override
    public void updateModTabVisibility() {
        if (modTab == null || configCache == null) return;
        boolean show = "true".equalsIgnoreCase(configCache.getOrDefault("HomeQuickManageMod", "false"));
        modTab.setVisible(show);
        modTab.setManaged(show);
        // 开关关闭且当前停留在模组页时，切回首页标签
        if (!show && currentTab == modTab) {
            if (currentTab != null) currentTab.getStyleClass().remove("selected");
            homeTab.getStyleClass().add("selected");
            currentTab = homeTab;
            switchToPage("home");
        }
    }

    private Node buildMainUiPage() {
        return new MainUiPage(this).build();
    }

    private Node buildLanguagePage() {
        return new LanguagePage(this).build();
    }

    private Node buildAccessibilityPage() {
        return new AccessibilityPage(this).build();
    }

    private Node buildColorBlindPage() {
        return new ColorBlindPage(this).build();
    }

    private Node buildDownloadSettingsPage() {
        return new DownloadSettingsPage(this).build();
    }

    private Node buildProxySettingsPage() {
        return new ProxySettingsPage(this).build();
    }

    // ===== 下载中心 =====

    /**
     * 下载中心主页：左侧分类导航 + 右侧内容区（参考 HMCL DownloadPage 的六类结构）
     */
    private Node buildDownloadPage() {
        return downloadPage.buildCenter();
    }

    private Node buildDownloadDetailPage() {
        return downloadPage.buildDetail();
    }

    private Node buildDownloadVersionPickerPage() {
        return downloadPage.buildVersionPicker();
    }

    private Node buildVersionConfigPage() {
        return downloadPage.buildVersionConfig();
    }

    private Node buildFeedbackPage() {
        return new FeedbackPage(this).build();
    }

    private Node buildSponsorPage() {
        return new SponsorPage(this).build();
    }

    private Node buildDevPage() {
        return devPage.build();
    }


    private Node buildResourcesPage() {
        return resourcesPage.build();
    }

    private Node buildScreenshotsPage() {
        return resourcesPage.buildScreenshots();
    }

    /** 打开本地文件/文件夹（系统默认应用） */
    @Override
    public void openFile(String path) {
        try {
            Desktop.getDesktop().open(new File(path));
        } catch (Exception ex) {
            ui.toast("打开失败: " + ex.getMessage());
        }
    }

    /** 删除确认面板（异步回调风格）：确认后执行 onConfirm */
    @Override
    public void confirmDelete(String message, Runnable onConfirm) {
        ui.confirm("确认删除", message, "删除", true, onConfirm);
    }

    /**
     * 目录筛选（下拉框）：把「全部 + 全局 + 各版本隔离目录」收进一个下拉框。
     * <p>资源管理工具下各子页（存档 / 崩溃报告 / 截图 / 模组·资源包·光影包）统一用它；
     * 目录多时比一排按钮省地方，也让页面更整齐。
     */

    /** 切换到下载中心并选中指定分类（0=游戏版本 1=整合包 2=模组 3=资源包 4=光影包 5=数据包 6=世界） */
    @Override
    public void openDownloadCategory(int idx) {
        if (currentTab != null) currentTab.getStyleClass().remove("selected");
        downloadTab.getStyleClass().add("selected");
        currentTab = downloadTab;
        switchToPage("download");
        // 下载页首次访问时延迟构建，下一帧再触发分类按钮
        Platform.runLater(() -> {
            List<Button> nav = downloadPage.navButtons();
            if (idx >= 0 && idx < nav.size()) {
                nav.get(idx).fire();
            }
        });
    }


    private Node buildNewsPage() {
        return communityPage.buildNews();
    }

    private VBox buildPostPublishPage() {
        return communityPage.buildPostPublish();
    }

    private Node buildPostDetailPage() {
        return communityPage.buildPostDetail();
    }

    private Node buildMultiplayerPage() {
        return multiplayerPage.build();
    }

    private Node buildVersionSelectPage() {
        return versionSelectPage.build();
    }

    private Node buildNetworkCheckPage() {
        return networkCheckPage.build();
    }

    private Node buildMemoryPage() {
        return memoryPage.build();
    }

    // ===== 配置管理 =====

    @Override
    public void loadConfig() {
        configCache = new HashMap<>(ConfigManager.readConfig());
        // 合并客户端配置
        Map<String, String> clientCfg = ConfigManager.readClientConfig();
        configCache.putAll(clientCfg);
        if (configCache.isEmpty()) {
            Map<String, String> cfg = UIGeneralControlClass.readConfig();
            configCache.putAll(cfg);
        }
        applyDownloadConfig();
        // 启动时检测当前游戏目录：未初始化则补齐骨架，launcher_profiles.json 缺失/不合法则重新生成
        ensureActiveGameDirHealth();
        // 应用代理设置到启动器自身网络（系统代理 / HTTP 代理）
        ProxyConfig.applyToSystem(configCache);
        // 恢复开发者选项开关：调试模式 / 中文日志（无配置项时使用默认值）
        AppConfig.DEBUG_MODE = "true".equalsIgnoreCase(configCache.getOrDefault("DebugMode", "false"));
        AppConfig.CHINESE_LOG = !"false".equalsIgnoreCase(configCache.getOrDefault("ChineseLog", "true"));
    }

    /**
     * 将「下载源 / 并发下载数」配置应用到全局下载组件：
     * 版本清单与安装引擎（VersionDownloadService）、启动资源补全（GameResourceCompleter）、
     * 加载器安装（LoaderInstallEngine）、分块下载线程数（DownloadSettings）
     */
    private void applyDownloadConfig() {
        String source = configCache.getOrDefault("DownloadSource", "BMCLAPI");
        DownloadProvider provider = VersionDownloadService.applyDownloadSource(source);
        GameResourceCompleter.setDownloadProvider(provider);
        LoaderInstallEngine.setDownloadProvider(provider);
        VersionManifest.setDownloadProvider(provider);
        int threads = PageKit.parseIntSafe(configCache.getOrDefault("DownloadThreads", "4"), DownloadSettings.DEFAULT_THREADS);
        DownloadSettings.setDownloadThreads(threads);
    }

    /**
     * 启动时对当前选中目录做一次健康检测（后台执行，不阻塞 UI）：
     * 未初始化的目录补齐标准骨架；{@code launcher_profiles.json} 缺失或结构不合法时重新生成。
     * 仅在确实修复了内容时才记录日志，正常目录零打扰。
     */
    private void ensureActiveGameDirHealth() {
        String dir = GameDirManager.activePath(configCache);
        if (dir == null || dir.isBlank()) return;
        Path path = Path.of(GameDirManager.resolve(dir));
        com.example.starlight.util.GameDirInitializer.InitResult r =
                com.example.starlight.util.GameDirInitializer.detectAndFix(path);
        if (r.ok() && (r.created() > 0 || r.filesCreated() > 0)) {
            System.out.println("[GameDir] 启动检测: 已修复 " + path + " (" + r.summary() + ")");
        } else if (!r.ok()) {
            System.err.println("[GameDir] 启动检测: " + path + " " + r.summary());
        }
    }

    @Override
    public void saveConfig() {
        UIGeneralControlClass.saveConfig(configCache);
        // 同步保存客户端个性化配置
        ConfigManager.saveClientConfig(configCache);
        // 设置页保存后重建缓存页面：否则再次进入仍显示旧值，用户再次保存会把旧值写回覆盖新配置
        rebuildSettingsPages();
    }

    /** 截图目录：优先读配置项，其余按版本隔离规则推导（实现在 ResourceScanner） */
    @Override
    public String screenshotDir() {
        return getScreenshotDir();
    }

    private String getScreenshotDir() {
        return ResourceScanner.resolveScreenshotDir(
                configCache.get("ScreenshotDir"),
                configCache.getOrDefault("GameDir", ".minecraft"),
                "true".equalsIgnoreCase(configCache.getOrDefault("VersionIsolation", "false")),
                configCache.getOrDefault("Version", ""));
    }

    // ==================== 右下角统一悬浮操作按钮（FAB） ====================

    /**
     * 构建右下角统一 FAB：一个 52px 主按钮，展开后飞出「下载管理 / 关闭游戏进程」等子按钮。
     *
     * <p>原先两者是两个各自独立的悬浮按钮（下载管理是带文字的胶囊、关闭游戏进程是 48px 红圆），
     * 同时出现时得靠 {@code downloads.liftFab(...)} 上下错开。现在合并进一个 FAB：
     * 角落只占一个 52px 圆，功能按需出现在飞出的子按钮上，也就不再需要让位逻辑。
     */
    private void buildFab() {
        fab = new FabMenu();
        // 上扇形槽位：0=左上（下载管理，见 DownloadTaskManager.FAB_SLOT）1=正上 2=右上（关闭游戏进程，红色危险样式）
        fab.setItem(2, "power", "关闭游戏进程", true, this::openGameProcessDialog);
        downloads.buildFab(fab);

        StackPane.setAlignment(fab.node(), Pos.BOTTOM_RIGHT);
        // 右 margin 54（原 26）：右上子按钮展开外探 ~26px，不抬会裁出窗口右缘（见 FabMenu 类注释）
        StackPane.setMargin(fab.node(), new Insets(0, 54, 26, 0));
        rootPane.getChildren().add(fab.node());

        // 巡检：进程可能在启动器不知情时结束（外部结束进程、游戏自身退出等），只靠启动回调会漏
        gameStopFabWatch = new Timeline(new KeyFrame(Duration.seconds(2), e -> syncGameStopFab()));
        gameStopFabWatch.setCycleCount(Animation.INDEFINITE);
        gameStopFabWatch.play();
    }

    /** 按「游戏进程是否还在跑」切换「关闭游戏进程」子按钮的可用性（不可用时不出现在 FAB 里） */
    private void syncGameStopFab() {
        if (fab == null) return;
        fab.setItemEnabled(FAB_STOP_SLOT, UIGeneralControlClass.isGameRunning());
    }

    /**
     * 点悬浮按钮：打开「关闭游戏进程」弹窗。
     *
     * <p>1 个实例 → 单进程确认框；多个实例 → 列表（勾选批量关闭，或点行尾 ✕ 单独关闭）。
     * 每次新建一个面板实例（面板内部要往弹窗卡片里挂自己这棵树）。
     */
    private void openGameProcessDialog() {
        new GameProcessPanel(this, ui, ui::toast, ui::closeModal).show();
    }

    @Override
    public void launchGame() {
        launchGame(null);
    }

    @Override
    public void launchGame(LaunchConfig presetConfig) {
        // 每次启动重置取消标记（上次取消的残留状态不能影响本次启动）
        overlay.resetCancelled();

        // 高级设置「启动游戏前优化一次」：先把各进程工作集交还系统，给游戏腾出物理内存。
        // 放在启动动画之前同步跑一次，避免和游戏加载抢内存。
        if (PageKit.boolConfig(this, "OptimizeMemoryBeforeLaunch", false)) {
            try {
                com.example.starlight.util.MemoryOptimizer.optimize();
            } catch (Throwable ignored) {
                // 优化失败不影响启动
            }
        }

        final LaunchConfig config;
        final String version;
        if (presetConfig != null) {
            config = presetConfig;
            version = config.version;
        } else {
            version = configCache.getOrDefault("Version", "");
            if (version.isEmpty()) {
                ui.toast("请先选择游戏版本");
                switchToSettings("sidebarGameDir");
                return;
            }
            config = UIGeneralControlClass.getLaunchConfig();
            if (UIGeneralControlClass.isLoggedIn()) {
                UIGeneralControlClass.applyCurrentAccount(config);
            }
        }

        // 检测加载器类型（用于记录）
        String loaderType = detectLoaderForVersion(config.gameDir, config.version);

        // 显示启动动画
        overlay.show("版本: " + version);

        // 实际启动游戏（异步，进度回调驱动真实进度）
        UIGeneralControlClass.launchMinecraftAsync(config,
                (percent, message) -> Platform.runLater(() -> {
                    overlay.onProgress(percent, message);
                }),
                new UIGeneralControlClass.ResultCallback<>() {
                    @Override
                    public void onSuccess(UIGeneralControlClass.LaunchResult result) {
                        Platform.runLater(() -> {
                            // 游戏进程已结束（无论正常/崩溃/被关）→ 收起「关闭游戏进程」悬浮按钮
                            syncGameStopFab();
                            // 通知帧生成器：游戏进程已退出（权威信号，取消/正常/崩溃退出都执行）
                            fgNotifyGameStopped();
                            // 色盲辅助：目标窗口已消失，矫正工具一并关闭
                            ColorBlindOverlayManager.notifyGameStopped();
                            // 用户主动结束（取消启动 / 从关闭进程弹窗关掉）：不再弹提示与崩溃面板。
                            // stoppedByUser 是按实例记的，多实例下关掉一个不会连累另一个的崩溃提示
                            if (overlay.isCancelledByUser() || result.stoppedByUser) {
                                overlay.hide();
                                refreshHomePage();
                                return;
                            }
                            if (result.isNormal()) {
                                ui.toast("游戏已退出");
                            } else {
                                // 异常退出：弹出集成式崩溃诊断面板（替代独立 GameCrashWindow）
                                showGameCrashPanel(result);
                            }
                            overlay.hide();
                            // 游戏退出后刷新首页：存档列表（快速进入存档）、最近游玩等实时数据更新
                            refreshHomePage();
                        });
                    }
                    @Override
                    public void onError(String error) {
                        Platform.runLater(() -> {
                            // 启动失败：进程没起来（或刚起就死），悬浮按钮不该留在界面上
                            syncGameStopFab();
                            // 色盲辅助：启动失败（含被取消）时游戏窗口不会出现，矫正工具不该留在后台
                            ColorBlindOverlayManager.notifyGameStopped();
                            // 用户主动取消：后台检查点回调的「启动已取消」不再误报为启动失败
                            if (overlay.isCancelledByUser()) {
                                overlay.hide();
                                return;
                            }
                            ui.toast("启动失败: " + error);
                            overlay.hide();
                        });
                    }
                },
                () -> Platform.runLater(() -> {
                    // 通知帧生成器：游戏已由启动器拉起（权威信号，进程扫描可能因启动器差异而滞后/失败）
                    fgNotifyGameStarted(config, version);

                    // 游戏已运行 → 右下角显示「关闭游戏进程」悬浮按钮（进程引用此时已就绪）
                    syncGameStopFab();

                    // 色盲辅助：游戏窗口已出现 → 按配置拉起 ColorBlindOverlay（只抓游戏窗口）
                    ColorBlindOverlayManager.notifyGameStarted();

                    // 游戏窗口已出现 → 保存本次启动记录
                    LaunchRecordStore.save(config, loaderType);

                    // 关闭启动动画（进度补满 + 对勾转场 + 文案收尾）
                    overlay.markSuccess();

                    Timeline exitTimer = new Timeline(new KeyFrame(Duration.seconds(1.5), ev -> {
                        overlay.hide();
                        // 启动记录已保存，刷新首页 UI
                        refreshHomePage();
                    }));
                    exitTimer.play();
                })
        );
    }

    // ===== 崩溃诊断面板（集成在启动器内，替代独立 GameCrashWindow / AIDiagnosisWindow） =====

    /** 游戏异常退出：收集崩溃数据并弹出诊断面板 */
    private void showGameCrashPanel(UIGeneralControlClass.LaunchResult result) {
        final LaunchConfig config = UIGeneralControlClass.getLaunchConfig();
        CrashDiagnosticData data = GameCrashPanel.collectCrashData(config, result.exitCode, result.errorLogPath, result.launchStartTime);
        showCrashPanel(data, config);
    }

    /** 重新显示崩溃诊断面板（AI 面板关闭后返回时使用已收集的数据） */
    private void showGameCrashPanel(CrashDiagnosticData data) {
        showCrashPanel(data, null);
    }

    private void showCrashPanel(CrashDiagnosticData data, LaunchConfig config) {
        GameCrashPanel panel = new GameCrashPanel(data,
                () -> launchGame(config != null ? config : null),
                ui::toast,
                () -> showAIDiagnosisPanel(data),
                ui::closeModal);
        ui.modal("游戏崩溃诊断", panel, 780, 540);
    }

    /** AI 诊断面板：从崩溃面板打开，关闭后回到崩溃面板 */
    private void showAIDiagnosisPanel(CrashDiagnosticData data) {
        AIDiagnosisPanel panel = new AIDiagnosisPanel(data, ui::toast,
                () -> showGameCrashPanel(data));
        ui.modal("AI 诊断分析", panel, 780, 540);
    }

    // ===== 快捷工具 =====

    @Override
    public void handleQuickTool(String toolName) {
        switch (toolName) {
            case "内存" -> switchToPage("memory");
            case "网络" -> switchToPage("networkCheck");
            case "截图" -> switchToPage("screenshots");
            case "设置" -> switchToSettings("sidebarJvm");
            case "帧生成" -> switchToPage("frameGen");
            case "色盲辅助" -> switchToSettings("sidebarColorBlind");
        }
    }

    // ===== 系统监控 =====

    private Node buildSystemStats() {
        return homePage.buildSystemStats();
    }

    @Override
    public void microsoftLogin() {
        login.microsoftLogin();
    }

    @Override
    public void thirdPartyLogin() {
        login.thirdPartyLogin();
    }

    @Override
    public void applyColorBlindMode() {
        //色盲辅助：总开关变化时立即作用于矫正工具进程（游戏未运行时只记录状态，等游戏启动再拉起）
        boolean on = "true".equalsIgnoreCase(configCache.getOrDefault(
                ColorBlindOverlayManager.KEY_MODE, "false"));
        DebugLog.log("[ColorBlind] " + ColorBlindOverlayManager.applyMasterSwitch(on));
        // 关总开关时 applyMasterSwitch() 会把「启动后自动开启」一起关掉，这里必须同步进配置缓存：
        // saveConfig() 会把整份缓存写回磁盘，缓存里还留着旧的 true 就会把它又写回去
        if (!on) {
            configCache.put(ColorBlindOverlayManager.KEY_AUTO_ENABLE, "false");
        }
    }

    @Override
    public void offlineLogin() {
        login.offlineLogin();
    }

    // ===== 设置页功能 =====
    /** 检查更新：委托给 UpdateChecker（星光MC社区更新 API + 版本比对），本类只负责展示结果 */
    @Override
    public void checkForUpdates() {
        updater.check();
    }

    private void autoCheckForUpdates() {
        updater.autoCheck();
    }
    @Override
    public void applySidebarVisibility() {
        boolean show = !"false".equalsIgnoreCase(configCache.getOrDefault("ShowSidebar", "true"));
        boolean inSettings = currentTab == settingsTab;
        sidebarScroll.setVisible(show && inSettings);
        sidebarScroll.setManaged(show && inSettings);
    }

    /** 应用「仿 macOS 三点式窗口按钮」开关：按配置重建标题栏右上角的按钮 */
    @Override
    public void applyWindowButtonStyle() {
        rebuildWindowControls();
    }

    private void initFrameGenModule() {
        frameGen.init();
    }

    private Node buildFrameGenPage() {
        return frameGen.buildPage();
    }

    private void fgNotifyGameStarted(UIGeneralControlClass.LaunchConfig config, String version) {
        frameGen.notifyGameStarted(config, version);
    }

    private void fgNotifyGameStopped() {
        frameGen.notifyGameStopped();
    }

        @Override
    public void clearLocalModCache() {
        modManagerPage.clearCache();
    }

    @Override
    public void chooseBackgroundImage(Label infoLabel) {
        themeMgr.chooseBackgroundImage(infoLabel);
    }

    @Override
    public void applyBackgroundImage() {
        themeMgr.applyBackgroundImage();
    }

    @Override
    public void applyHighContrast() {
        themeMgr.applyHighContrast();
    }

    @Override
    public void applyTheme(String theme) {
        themeMgr.applyTheme(theme);
    }

    @Override
    public String currentTheme() {
        return themeMgr.currentTheme();
    }

    @Override
    public String currentSidebarItemId() {
        return currentSidebarItem == null ? null : currentSidebarItem.getId();
    }

    @Override
    public String detectLoaderForVersion(String gameDir, String version) {
        return versionSelectPage.detectLoaderForVersion(gameDir, version);
    }

    @Override
    public void preheatJvmAsync() {
        memoryPage.preheatJvmAsync();
    }

    @Override
    public String switchGameDir(String path, String reloadPage) {
        return gameDirPage.switchGameDir(path, reloadPage);
    }

    @Override
    public void refreshModList() {
        modManagerPage.refreshList();
    }

    @Override
    public void selectHomeTab() {
        if (currentTab != null) currentTab.getStyleClass().remove("selected");
        homeTab.getStyleClass().add("selected");
        currentTab = homeTab;
    }

    @Override
    public void refreshDependentPageCaches() {
        pageCache.put("versionSelect", buildVersionSelectPage());
        pageCache.put("resources", buildResourcesPage());
        pageCache.put("home", buildHomePage());
    }



    @Override
    public void cachePage(String key, Node page) {
        pageCache.put(key, page);
    }

    @Override
    public void refreshHomeCache() {
        pageCache.put("home", buildHomePage());
    }

    @Override
    public void hideSidebar() {
        sidebarScroll.setVisible(false);
        sidebarScroll.setManaged(false);
    }

    @Override
    public String currentPageKey() {
        return currentPageKey;
    }

    @Override
    public Node cachedPage(String key) {
        return pageCache.get(key);
    }

    @Override
    public void selectDownloadTab() {
        if (currentTab != downloadTab) {
            if (currentTab != null) currentTab.getStyleClass().remove("selected");
            downloadTab.getStyleClass().add("selected");
            currentTab = downloadTab;
        }
    }

    @Override
    public StackPane rootPane() {
        return rootPane;
    }

@Override
public Stage stage() {
    return stage;
}

@Override
public Map<String, String> config() {
    return configCache;
}

@Override
public UiService ui() {
    return ui;
}

@Override
public Node getView() {
    return rootPane;
}
}

