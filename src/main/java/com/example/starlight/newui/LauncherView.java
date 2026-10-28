package com.example.starlight.newui;

import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.Controllers.PageView;
import com.example.starlight.auth.AccountManager;
import com.example.starlight.config.GameDirManager;
import com.example.starlight.config.LegacyVersionMigration;
import com.example.starlight.config.SelectedVersion;
import com.example.starlight.config.VersionConfigManager;
import com.example.starlight.colorblind.ColorBlindOverlayManager;
import com.example.starlight.crash.CrashDiagnosticData;
import com.example.starlight.crash.CrashReportParser;
import com.example.starlight.launch.LaunchRecord;
import com.example.starlight.launch.LaunchRecordStore;
import com.example.starlight.listsaves.listsaves;
import com.example.starlight.download.DownloadSettings;
import com.example.starlight.download.VerifySettings;
import com.example.starlight.download.mod.DownloadCoordinator;
import com.example.starlight.download.mod.HostPolicy;
import com.example.starlight.download.source.DownloadProvider;
import com.example.starlight.download.task.GameResourceCompleter;
import com.example.starlight.download.engine.HttpDownloadEngine;
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
import com.example.starlight.download.task.InstallEngine;
import com.example.starlight.download.task.LoaderInstallEngine;
import com.example.starlight.version.VersionDownloadService;
import com.example.starlight.download.task.VersionManifest;
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
import com.example.starlight.newui.page.CommunityPage;
import com.example.starlight.newui.framegen.FrameGenCoordinator;
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
import com.example.starlight.newui.ui.NavSearchPill;
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
import com.example.starlight.startmc.launcher.LoaderDetector;

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
    /** 顶部导航栏左侧的搜索胶囊：点击展开、失焦收起（实现在 newui.ui.NavSearchPill） */
    private NavSearchPill navSearch;
    /** 本次按压是否落在搜索胶囊里：标签栏的窗口拖拽要让位，否则在输入框里拖动选词会连窗口一起拖跑 */
    private boolean windowDragDisabled;
    /** 搜索胶囊的左边距（px） */
    private static final double SEARCH_LEFT_INSET = 12;
    /** 胶囊展开后与第一个页签之间至少留的缝隙（px） */
    private static final double SEARCH_TAB_GAP = 10;
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
    /** 当前生效的节日（构造期计算；HomePage 用 FestivalCalendar.current() 独立判定，两者一致） */
    private com.example.starlight.newui.festival.Festival activeFestival;
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

    // 启动状态广播：只驱动首页启动按钮的收圈 / 加载环 / 对勾动效，不参与启动流程 //
    private final List<Consumer<LauncherContext.LaunchState>> launchStateListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** 当前启动状态（null=未启动 / 已复位；页面重建时用它立即同步新按钮） */
    private LauncherContext.LaunchState launchState;

    // 最小化标志
    private boolean hasBeenIconified = false;

    /** 社区页（登录 / 签到 / 发帖 / 评论 / 点赞，实现在 newui.page.CommunityPage） */
    private final CommunityPage communityPage;
    /** 联机页（陶瓦 / SakuraFrp / SLAN，实现在 newui.page.MultiplayerPage） */
    private final MultiplayerPage multiplayerPage;

    /** 游戏启动动画弹窗（实现在 newui.ui.LaunchOverlay） */
    private final LaunchOverlay overlay;
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

    /**
     * 「启动器可见性」本次是否已经真的隐藏 / 最小化 / 关闭了启动器。
     *
     * <p>游戏退出（或启动失败）时据此决定要不要还原窗口：「立即关闭」策略下若启动失败，
     * 启动器其实还开着，这时不该被后续回调关掉。
     */
    private final java.util.concurrent.atomic.AtomicBoolean launcherHiddenByVisibility =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // 窗口控制 //

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

    // 构造 //

    public LauncherView(Stage stage) {
        this.stage = stage;

        // 语言必须在**任何界面控件构建之前**应用：
        // 顶部标签（createTab）、侧边栏（buildSidebar）、搜索胶囊（NavSearchPill）
        // 都在构造函数早期创建，若等到后面的 buildAllPages 才切语言，
        // 这些控件会永久停留在默认语言（表现为「英文模式下导航仍是中文」）。
        // 这里只读配置取 Language 一个键，不触发 loadConfig 的磁盘副作用。
        applyLanguageEarly();

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

        // 页面实例（共享单例：跨切页保留页面状态与缓存语义） //
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
        this.themeMgr = new ThemeManager(this);
        this.devPage = new DevPage(this);
        contentPane = new BorderPane();
        rootPane.getStyleClass().addAll("root", "light");
        rootPane.setStyle("-fx-background-radius: 12; -fx-border-radius: 12;");
        rootPane.getChildren().add(contentPane);

        // Top: 标题栏 + Tab栏 //
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

        homeTab = createTab(I18N.get("shell.t0001"), "homeTab", true);
        downloadTab = createTab(I18N.get("shell.t0002"), "downloadTab", false);
        resourcesTab = createTab(I18N.get("shell.t0003"), "resourcesTab", false);
        newsTab = createTab(I18N.get("shell.t0004"), "newsTab", false);
        multiplayerTab = createTab(I18N.get("shell.t0005"), "multiplayerTab", false);
        settingsTab = createTab(I18N.get("shell.t0006"), "settingsTab", false);
        // 模组页：默认隐藏，由「首页快速管理MOD」开关控制（loadConfig 后调用 updateModTabVisibility 同步显隐）
        modTab = createTab(I18N.get("shell.t0007"), "modTab", false);
        modTab.setVisible(false);
        modTab.setManaged(false);

        tabBar.getChildren().addAll(homeTab, downloadTab, resourcesTab, newsTab, multiplayerTab, settingsTab, modTab);

        // 搜索胶囊：叠在标签栏左侧、垂直居中。页签位置保持不动 —— tabBar 仍旧铺满整行、
        // 内容居中，胶囊不参与页签的居中计算，只是浮在它左上方的一层控件。
        navSearch = new NavSearchPill();
        // 回车 / 点 Bing 标识：拉起系统默认浏览器搜（见 search.BingSearchUtil，只拼链接不发请求）
        navSearch.setOnSubmit(keyword -> {
            if (!com.example.starlight.search.BingSearchUtil.search(keyword)) {
                ui.toast(I18N.get("shell.k0001", keyword), "warning");
            }
        });
        StackPane tabRow = new StackPane(tabBar, navSearch);
        StackPane.setAlignment(navSearch, Pos.CENTER_LEFT);
        StackPane.setMargin(navSearch, new Insets(0, 0, 0, SEARCH_LEFT_INSET));

        // 展开宽度按「首页页签左边界 - 留白」实时封顶：窗口很窄、或打开了「模组」页签时，
        // 展开的输入框也不会盖住第一个导航按钮
        Runnable clampSearchWidth = () -> navSearch.setMaxExpandedWidth(
                homeTab.getLayoutX() - SEARCH_LEFT_INSET - SEARCH_TAB_GAP);
        tabBar.widthProperty().addListener((o, a, b) -> clampSearchWidth.run());
        tabBar.layoutBoundsProperty().addListener((o, a, b) -> clampSearchWidth.run());
        homeTab.layoutXProperty().addListener((o, a, b) -> clampSearchWidth.run());

        // 点到胶囊外面也收起：页面空白处不是可聚焦控件，光等输入框失焦会一直停在展开态
        rootPane.sceneProperty().addListener((obs, oldScene, scene) -> {
            if (scene == null || scene.getProperties().containsKey("starlight.navSearchBlur")) return;
            scene.getProperties().put("starlight.navSearchBlur", Boolean.TRUE);
            scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
                if (navSearch.isExpanded() && !isInsideNavSearch(e.getTarget())) navSearch.collapse();
            });
        });

        topContainer.getChildren().addAll(windowControls, tabRow);

        // 顶部导航动效：弹性滑动指示条 + 页签水波纹 + 图标微交互（滚动收缩由切页时 watchScroll 接）
        com.example.starlight.newui.ui.NavEffects.install(tabBar);

        currentTab = homeTab;

        // Center: 主体布局 //
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

        // 标题栏拖拽（搜索胶囊里的按压不算拖窗口，见 windowDragDisabled）
        topContainer.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            windowDragDisabled = isInsideNavSearch(e.getTarget());
            if (windowDragDisabled) return;
            xOffset = stage.getX() - e.getScreenX();
            yOffset = stage.getY() - e.getScreenY();
        });
        topContainer.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (windowDragDisabled || stage.isMaximized()) return;
            stage.setX(e.getScreenX() + xOffset);
            stage.setY(e.getScreenY() + yOffset);
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

        // 右下角统一悬浮操作按钮（FAB）：下载管理 / 关闭游戏进程 都收进它 //
        buildFab();

        // 构造期 applyTheme 时 Scene 还没建好，挂不上深色补充样式表；场景就绪后再同步一次
        rootPane.sceneProperty().addListener((o, ov, nv) -> themeMgr.syncDarkExtraStylesheet());

        // 全局界面动效：按钮水波纹 + 下拉框箭头/面板动画 //
        // 装在根节点上（内部等场景就绪后挂场景级过滤器），各处 new 出来的按钮与下拉框自动生效
        UiEffects.install(rootPane);

        // 初始化页面 //
        loadConfig();        // 根据「首页快速管理MOD」开关同步顶部导航栏「模组」页显隐
        updateModTabVisibility();
        // 语言在构造函数最前面已应用（applyLanguageEarly）。这里再按最新配置同步一次：
        // 极少数情况下 loadConfig 会合并客户端配置覆盖 Language，保持幂等即可。
        I18N.applyConfig(configCache.getOrDefault("Language", "zh_CN"));
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

        // 节日挂饰（中秋测试期写死）：底层夜空压在内容下、顶部灯笼浮在内容上，全窗生效、鼠标穿透。
        // 用户设置了壁纸/自定义背景色时底层夜空不生效（见 FestivalBackdrop 与壁纸优先级的处理）。
        installFestivalDecorations();

        // 初始化游戏启动动画弹窗内容 //
        overlay.build();

        // 自动检查更新：进入首页后延迟检测，发现新版本时在首页弹出提示 //
        autoCheckForUpdates();

        // 帧生成器模块：历史启用过系统托盘或保存过倍率时随启动器常驻；否则首次打开帧生成页时懒加载 //
        initFrameGenModule();
    }

    // 色盲辅助 //

    /**
     * 节日效果入口：计算活动节日 → 按节日加载壁纸（自适应铺满 + 可读性遮罩）
     * → 挂顶部灯笼（按节日配色）。无节日时保持默认背景。
     *
     * <p>壁纸约定：{@code src/main/resources/wallpaper/<id>.png|jpg|jpeg|webp}；
     * 未命中时回退用户壁纸 / 主题渐变（不报错）。
     */
    private void installFestivalDecorations() {
        if (!com.example.starlight.newui.festival.FestivalCalendar.isEnabled(configCache)) {
            return; // 开关关闭：不挂任何节日装饰
        }
        activeFestival = com.example.starlight.newui.festival.FestivalCalendar.current();
        if (activeFestival == null) {
            return; // 无节日：不挂任何节日装饰
        }
        // 1) 节日壁纸：优先于默认渐变，但用户自定义壁纸（BackgroundImage）优先
        applyFestivalWallpaper(activeFestival.id);
        // 2) 顶部灯笼（按节日配色）
        rootPane.getChildren().add(
                com.example.starlight.newui.ui.FestivalDecorations.topOverlay(
                        activeFestival.lanternA, activeFestival.lanternB));
    }

    /**
     * 按节日 id 从 classpath 资源加载壁纸并自适应铺满。
     *
     * <p>优先级：用户壁纸（BackgroundImage）> 节日壁纸（wallpaper/）> 主题渐变。
     * 图片用 preserveRatio=false + 绑定窗口宽高做到「自适应页面」（拉伸铺满），
     * 与现有 ThemeManager.applyBackgroundImage 的方式一致；叠一层可读性遮罩保证文字对比度。
     */
    private void applyFestivalWallpaper(String festivalId) {
        // 用户已设壁纸时不覆盖
        String userBg = configCache == null ? "" : configCache.getOrDefault("BackgroundImage", "");
        if (!userBg.isBlank()) return;

        String[] exts = {"png", "jpg", "jpeg", "webp"};
        java.io.InputStream in = null;
        for (String ext : exts) {
            in = getClass().getResourceAsStream("/wallpaper/" + festivalId + "." + ext);
            if (in != null) break;
        }
        if (in == null) return; // 该节日无壁纸，保持默认背景

        try {
            Image img = new Image(in);
            if (img.isError()) return;
            ImageView view = new ImageView(img);
            view.setPreserveRatio(false);
            view.setSmooth(true);
            view.fitWidthProperty().bind(rootPane.widthProperty());
            view.fitHeightProperty().bind(rootPane.heightProperty());
            view.setOpacity(1.0);
            rootPane.getChildren().add(0, view);
            view.toBack();

            // 可读性遮罩（浅色提亮 / 深色压暗），保证节日壁纸上的文字清晰
            javafx.scene.layout.Region overlay = new javafx.scene.layout.Region();
            overlay.setMouseTransparent(true);
            overlay.prefWidthProperty().bind(rootPane.widthProperty());
            overlay.prefHeightProperty().bind(rootPane.heightProperty());
            boolean dark = "dark".equals(currentTheme());
            overlay.setStyle(dark
                    ? "-fx-background-color: rgba(0,0,0,0.30);"
                    : "-fx-background-color: rgba(255,255,255,0.18);");
            rootPane.getChildren().add(1, overlay);
        } catch (Exception ignored) {
            // 加载失败保持默认背景
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
    }

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

    // 游戏启动动画 //

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

    /**
     * 顶部标签页的图标：按标签 id 取。
     *
     * <p>旧实现按<b>标签文案</b>匹配（switch 中文常量），既不能换语言（文案一变就全落 default），
     * 也无法用 i18n 键做 case 标签（case 要求编译期常量）。id 是稳定标识，永不被翻译。
     */
    private static String tabIcon(String id) {
        return switch (id) {
            case "homeTab" -> "home";
            case "downloadTab" -> "download";
            case "resourcesTab" -> "library";
            case "newsTab" -> "people";
            case "multiplayerTab" -> "wifi";
            case "settingsTab" -> "gear";
            case "modTab" -> "puzzle";
            default -> null;
        };
    }

    private Button createTab(String text, String id, boolean selected) {
        Button btn = new Button(text);
        btn.setId(id);
        AppIcons.apply(btn, tabIcon(id), 15, null);
        btn.getStyleClass().add("tab-item");
        if (selected) btn.getStyleClass().add("selected");
        btn.setOnAction(this::switchTab);
        btn.setCursor(javafx.scene.Cursor.HAND);
        return btn;
    }

    /** 事件目标是否落在顶部搜索胶囊里（含其子节点）：落在里面就让开窗口拖拽，交给输入框自己处理 */
    private boolean isInsideNavSearch(Object target) {
        if (!(target instanceof Node node) || navSearch == null) return false;
        for (Node cur = node; cur != null; cur = cur.getParent()) {
            if (cur == navSearch) return true;
        }
        return false;
    }

    /** 侧边栏各项图标（键与 buildSidebar 里的 id 一致；旧 id 的图标供深链高亮兜底） */
    private static final java.util.Map<String, String> SIDEBAR_ICONS = java.util.Map.ofEntries(
            java.util.Map.entry("sidebarAccount", "user"),
            java.util.Map.entry("sidebarAbout", "help"),
            java.util.Map.entry("sidebarLicense", "scale"),
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

    /** 设置搜索框当前关键词（输入即过滤侧边栏导航项；空 = 不过滤） */
    private String settingsSearchKeyword = "";

    /** 最近修改的设置项（最多 5 条，key = 导航 id，value = 设置项名）；由各页保存动作上报 */
    private static final java.util.Deque<String[]> RECENT_SETTINGS = new java.util.ArrayDeque<>();
    /** 最近修改列表最多保留条数（重构 §4.5） */
    private static final int RECENT_SETTINGS_MAX = 5;

    /**
     * 记录一次设置修改（供侧边栏「最近修改」展示）。
     * 同一导航 id 连续修改只刷新条目位置不重复添加；超出上限移除最旧一条。
     * 页面类通过 {@code LauncherContext} 不直接持有本列表——由 LauncherView 提供静态入口，
     * 页面保存时调用 {@link #recordRecentSetting(String, String)} 即可。
     */
    public static void recordRecentSetting(String navId, String settingName) {
        if (navId == null || settingName == null) return;
        RECENT_SETTINGS.removeIf(e -> e[0].equals(navId) && e[1].equals(settingName));
        RECENT_SETTINGS.addFirst(new String[]{navId, settingName});
        while (RECENT_SETTINGS.size() > RECENT_SETTINGS_MAX) {
            RECENT_SETTINGS.removeLast();
        }
    }

    /** 重建侧边栏的导航项显隐（搜索过滤）与「最近修改」列表内容 */
    private void refreshSidebarSearch() {
        for (Node n : sidebar.getChildrenUnmodifiable()) {
            if (!(n instanceof Button btn)) continue;
            String id = btn.getId();
            if (id == null || !id.startsWith("sidebar")) continue;
            boolean visible = settingsSearchKeyword.isEmpty()
                    || navLabelOf(id).contains(settingsSearchKeyword)
                    || id.toLowerCase().contains(settingsSearchKeyword.toLowerCase());
            btn.setVisible(visible);
            btn.setManaged(visible);
        }
        // 分组标题：组内导航项全部被过滤掉时隐藏该组标题
        int idx = 0;
        int sectionCount = PageKit.SETTINGS_NAV_SECTIONS.length;
        for (Node n : sidebar.getChildrenUnmodifiable()) {
            if (!(n instanceof Label) || !(n.getStyleClass().contains("sidebar-section-title"))) continue;
            if (idx >= sectionCount) break;
            int size = PageKit.SETTINGS_NAV_SECTION_SIZES[idx];
            boolean anyVisible = false;
            for (int k = 0; k < size; k++) {
                int itemIndex = sectionOffset(idx) + k;
                if (itemIndex >= PageKit.SETTINGS_NAV_ITEMS.length) break;
                String id = PageKit.SETTINGS_NAV_ITEMS[itemIndex][0];
                Node item = sidebar.lookup("#" + id);
                if (item != null && item.isVisible()) {
                    anyVisible = true;
                    break;
                }
            }
            n.setVisible(anyVisible);
            n.setManaged(anyVisible);
            idx++;
        }
        Label recentTitle = (Label) sidebar.lookup("#sidebarRecentTitle");
        if (recentTitle != null) {
            boolean show = settingsSearchKeyword.isEmpty() && !RECENT_SETTINGS.isEmpty();
            recentTitle.setVisible(show);
            recentTitle.setManaged(show);
        }
        VBox recentBox = (VBox) sidebar.lookup("#sidebarRecentBox");
        if (recentBox != null) {
            recentBox.setVisible(settingsSearchKeyword.isEmpty());
            recentBox.setManaged(settingsSearchKeyword.isEmpty());
            rebuildRecentItems(recentBox);
        }
    }

    /** 第 sectionIdx 个分组第一个导航项在 SETTINGS_NAV_ITEMS 中的下标（带越界防御） */
    private static int sectionOffset(int sectionIdx) {
        int off = 0;
        int max = Math.min(sectionIdx, PageKit.SETTINGS_NAV_SECTION_SIZES.length);
        for (int i = 0; i < max; i++) off += PageKit.SETTINGS_NAV_SECTION_SIZES[i];
        return Math.min(off, PageKit.SETTINGS_NAV_ITEMS.length);
    }

    /** 导航 id → 显示名（当前语言下现取，见 PageKit.navLabel） */
    private static String navLabelOf(String id) {
        return PageKit.navLabel(id);
    }

    /** 重绘「最近修改」子列表（保序：最新在上；跳转经 switchToSettings 统一走 NavRouter） */
    private void rebuildRecentItems(VBox box) {
        box.getChildren().clear();
        for (String[] entry : RECENT_SETTINGS) {
            Button recent = new Button(navLabelOf(entry[0]) + " · " + entry[1]);
            recent.getStyleClass().addAll("sidebar-item", "sidebar-recent-item");
            recent.setMaxWidth(Double.MAX_VALUE);
            recent.setOnAction(e -> switchToSettings(entry[0]));
            box.getChildren().add(recent);
        }
    }

    private void buildSidebar() {
        // 顶部：设置搜索框（重构 §4.5：输入即过滤导航项） //
        TextField searchField = PageKit.searchField(I18N.get("shell.t0015"), settingsSearchKeyword, kw -> {
            settingsSearchKeyword = kw == null ? "" : kw;
            refreshSidebarSearch();
        });
        searchField.getStyleClass().add("sidebar-search");
        searchField.setMaxWidth(Double.MAX_VALUE);
        sidebar.getChildren().add(searchField);

        // 分组渲染：单一数据源（PageKit.SETTINGS_NAV_ITEMS + 分组切分数组），与下拉导航共用（§7） //
        String[][] sections = splitSections();
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

    /** 把 SETTINGS_NAV_ITEMS 按分组大小切回「组名 + 键值对」结构（保留 buildSidebar 原消费形态） */
    private static String[][] splitSections() {
        // 防御：分组数总和与清单长度不一致时按清单实际长度截断，绝不再越界（启动崩溃兜底）
        int total = 0;
        for (int size : PageKit.SETTINGS_NAV_SECTION_SIZES) total += size;
        int itemCount = Math.min(total, PageKit.SETTINGS_NAV_ITEMS.length);
        String[][] result = new String[PageKit.SETTINGS_NAV_SECTIONS.length][];
        int offset = 0;
        for (int s = 0; s < PageKit.SETTINGS_NAV_SECTIONS.length; s++) {
            int size = PageKit.SETTINGS_NAV_SECTION_SIZES[s];
            String[] row = new String[1 + size * 2];
            // 这里做一次译文解析：切分结果只用于当次渲染，不缓存在静态数组里，
            // 所以不会把语言冻结在类初始化那一刻。
            row[0] = PageKit.sectionLabel(s);
            for (int k = 0; k < size; k++) {
                if (offset + k >= itemCount) break;
                String[] item = PageKit.SETTINGS_NAV_ITEMS[offset + k];
                row[1 + k * 2] = item[0];
                row[2 + k * 2] = I18N.get(item[1]);
            }
            result[s] = row;
            offset += size;
        }
        return result;
    }

    private void buildAllPages() {
        // 只构建启动即需的轻量页面，重页面（资源/社区/联机/版本选择）改为首次访问时懒加载：
        // 这些页面构建期会同步做磁盘 IO（存档 NBT 解析、版本 JSON 读取）或发起网络请求，
        // 在启动期一次性同步构建会阻塞 FX 线程，低配机上直接表现为窗口未响应。
        pageCache.put("home", buildHomePage());
        // 设置子页面统一通过 rebuildSettingsPages 构建（侧边栏显隐影响页面导航形态）
        rebuildSettingsPages();
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
        } else if ("resources".equals(key)) {
            // 资源页延迟构建：构建期会同步扫描存档（NBT 解析），首次点击时才构建
            newPage = pageCache.get("resources");
            if (newPage == null) {
                newPage = buildResourcesPage();
                pageCache.put("resources", newPage);
            }
        } else if ("news".equals(key)) {
            newPage = pageCache.get("news");
            if (newPage == null) {
                newPage = buildNewsPage();
                pageCache.put("news", newPage);
            }
        } else if ("multiplayer".equals(key)) {
            newPage = pageCache.get("multiplayer");
            if (newPage == null) {
                newPage = buildMultiplayerPage();
                pageCache.put("multiplayer", newPage);
            }
        } else if ("versionSelect".equals(key)) {
            newPage = pageCache.get("versionSelect");
            if (newPage == null) {
                newPage = buildVersionSelectPage();
                pageCache.put("versionSelect", newPage);
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
                Label label = new Label(I18N.get("shell.k0002", key));
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
        // 账户页（sidebarAccount）也自带内部滚动区（账号列表滚动、卡片面板切换），
        // 且卡片要始终居中显示——套外层滚动条后内容按自身首选高排版、拿不到整页高度就没法居中。
        // 游戏目录页（sidebarGameDir）同理：整页就是一个居中面板，列表自己滚动。
        boolean noScroll = "home".equals(key) || "resources".equals(key) || "multiplayer".equals(key)
                || "download".equals(key) || "downloadVersionPick".equals(key)
                || "downloadVersionConfig".equals(key)
                || "downloadManager".equals(key) || "news".equals(key)
                || "postDetail".equals(key) || "versionSelect".equals(key)
                || "sidebarAccount".equals(key)
                || "sidebarGameDir".equals(key)
                || "sidebarVersionSettings".equals(key);
        if (noScroll) {
            // mainContent 是 VBox：不设 vgrow 的话子页面只拿首选高。账户页和游戏目录页要靠整页高度
            // 把卡片垂直居中，拿不到高度就只能顶在内容区上方。
            if ("sidebarAccount".equals(key) || "sidebarGameDir".equals(key)) {
                VBox.setVgrow(newPage, Priority.ALWAYS);
            }
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

    // 页面构建 //

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

    // 页面脚手架（实现见 newui.ui.PageKit） //

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
                // 账户页（重构 §6.1）；sidebarGameAccount 已从侧边栏移除，该旧 id 仅作深链兼容保留
                case "sidebarAccount", "sidebarGameAccount" -> pageCache.put(item[0], buildGameAccountPage());
                // LicensePage 并入 AboutPage（重构 §6.2）
                case "sidebarAbout" -> pageCache.put(item[0], buildAboutPage());
                case "sidebarJvm" -> pageCache.put(item[0], buildJvmPage());
                // ModPage 并入 GameDirPage（重构 §6.4）
                case "sidebarGameDir" -> pageCache.put(item[0], buildGameDirPage());
                case "sidebarVersionSettings" -> pageCache.put(item[0], versionSettingsPage.build());
                case "sidebarAdvanced" -> pageCache.put(item[0], buildAdvancedPage());
                case "sidebarTheme" -> pageCache.put(item[0], buildThemePage());
                // LanguagePage 并入 MainUiPage（重构 §6.8）
                case "sidebarMainUi" -> pageCache.put(item[0], buildMainUiPage());
                case "sidebarAccessibility" -> pageCache.put(item[0], buildAccessibilityPage());
                case "sidebarDownload" -> pageCache.put(item[0], buildDownloadSettingsPage());
                case "sidebarProxy" -> pageCache.put(item[0], buildProxySettingsPage());
                case "sidebarMultiplayer" -> pageCache.put(item[0], buildMultiplayerSettingsPage());
                case "sidebarFeedback" -> pageCache.put(item[0], buildFeedbackPage());
                case "sidebarSponsor" -> pageCache.put(item[0], buildSponsorPage());
                case "sidebarDev" -> pageCache.put(item[0], buildDevPage());
            }
        }
        // 旧导航 id 的深链兼容缓存：switchToPage(旧id) 时先经 NavRouter 重定向，
        // 但保险起见也把旧 id 指到目标页缓存，任何直连旧 id 的代码路径都不会踩空
        pageCache.put("sidebarLicense", pageCache.get("sidebarAbout"));
        pageCache.put("sidebarMod", pageCache.get("sidebarGameDir"));
        pageCache.put("sidebarLanguage", pageCache.get("sidebarMainUi"));
        pageCache.put("sidebarColorBlind", pageCache.get("sidebarAccessibility"));
        // 「游戏账户档案」已并入「账户」：旧 id 指到同一实例，历史深链不会落空
        pageCache.put("sidebarGameAccount", pageCache.get("sidebarAccount"));
    }

    @Override
    public void switchToSettings(String id) {
        // NavRouter（重构 §5.2 / §7）：旧导航 id 重定向到新归属页（深链与页内跳转统一入口）
        String target = com.example.starlight.newui.ui.NavRouter.resolve(id);
        if (!target.equals(id)) {
            ui.toast(I18N.get("shell.navRedirected", navLabelOf(target) + "」"));
        }
        id = target;
        if (currentTab != null) currentTab.getStyleClass().remove("selected");
        settingsTab.getStyleClass().add("selected");
        currentTab = settingsTab;
        boolean showSidebar = !"false".equalsIgnoreCase(configCache.getOrDefault("ShowSidebar", "true"));
        sidebarScroll.setVisible(showSidebar);
        sidebarScroll.setManaged(showSidebar);
        highlightSidebar(id);
        switchToPage(id);
    }

    // 设置页面 //
    // 注：AccountPage / LicensePage / ModPage / LanguagePage 已并入其它页面（重构 §6.1/§6.2/§6.4/§6.8），
    // 对应旧导航 id 由 NavRouter 重定向；AccountPage 等 4 个页面类文件保留但不再被引用，
    // 后续清理任务可删除。

    private Node buildAboutPage() {
        return new AboutPage(this).build();
    }

    private Node buildGameAccountPage() {
        return new GameAccountPage(this).build();
    }

    /** 账号数据变化后刷新「账户」页缓存（重构 §6.1）；用户正停留在该页时原地刷新 */
    @Override
    public void refreshAccountPages() {
        Node oldCached = pageCache.get("sidebarAccount");
        if (oldCached == null) oldCached = pageCache.get("sidebarGameAccount");
        // 两个 key 指向同一实例：旧导航 id 的历史引用仍能命中，刷新后也不会出现两份不一致的页面
        Node fresh = buildGameAccountPage();
        pageCache.put("sidebarAccount", fresh);
        pageCache.put("sidebarGameAccount", fresh);
        // 当前显示的正是旧缓存实例（用户停留在此页）时，原地刷新为新页面
        if (oldCached != null && currentPage == oldCached) {
            switchToPage("sidebarAccount");
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

    /** 顶部导航栏「模组」页：上传 .jar 模组文件并复制到 Starlight-Launcher\Mod，展示已安装模组列表 */
    private Node buildTopModPage() {
        return modManagerPage.build();
    }

    @Override
    public void openWebUrl(String url) {
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
        } catch (Exception e) {
            ui.toast(I18N.get("shell.k0003", e.getMessage()));
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

    private Node buildAccessibilityPage() {
        return new AccessibilityPage(this).build();
    }

    private Node buildDownloadSettingsPage() {
        return new DownloadSettingsPage(this).build();
    }

    private Node buildProxySettingsPage() {
        return new ProxySettingsPage(this).build();
    }

    // 下载中心 //

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
            ui.toast(I18N.get("shell.k0004", ex.getMessage()));
        }
    }

    /** 删除确认面板（异步回调风格）：确认后执行 onConfirm */
    @Override
    public void confirmDelete(String message, Runnable onConfirm) {
        ui.confirm(I18N.get("shell.t0016"), message, I18N.get("shell.t0017"), true, onConfirm);
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

    // 配置管理 //

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
        // 版本选择的唯一真源是「各游戏目录内的 starlight.json」（ini 不再保存 Version）：
        // 先做一次旧 ini 值的幂等迁移，再把当前活动目录的选择同步进内存镜像。
        // 镜像不是真源，只用于兜底尚未收口的读取点，永不落盘。
        LegacyVersionMigration.runOnce();
        SelectedVersion.syncCache(configCache);
        applyDownloadConfig();
        // 启动时检测当前游戏目录：未初始化则补齐骨架，launcher_profiles.json 缺失/不合法则重新生成
        ensureActiveGameDirHealth();
        // 应用代理设置到启动器自身网络（系统代理 / HTTP 代理）
        ProxyConfig.applyToSystem(configCache);
        // 恢复开发者选项开关：调试模式 / 中文日志（无配置项时使用默认值）
        AppConfig.DEBUG_MODE = "true".equalsIgnoreCase(configCache.getOrDefault("DebugMode", "false"));
        AppConfig.CHINESE_LOG = !"false".equalsIgnoreCase(configCache.getOrDefault("ChineseLog", "true"));
        DebugLog.applyMode(AppConfig.DEBUG_MODE); // 同步 logback root 级别（DEBUG/INFO）
    }

    /**
     * 构造函数最早期应用界面语言。
     *
     * <p>顶部标签、侧边栏、搜索胶囊在构造期就创建并取值，必须赶在它们之前。
     * 这里只读配置里的 Language 一个键，不调用 {@link #loadConfig()}：
     * 后者会做磁盘扫描 / 迁移 / 网络代理应用，早期调用既慢又有副作用。
     */
    private void applyLanguageEarly() {
        String lang = "zh_CN";
        try {
            lang = ConfigManager.readConfig().getOrDefault("Language", "zh_CN");
            // 客户端配置优先级更高（与 loadConfig 的合并顺序一致）
            Map<String, String> clientCfg = ConfigManager.readClientConfig();
            if (clientCfg.containsKey("Language")) lang = clientCfg.get("Language");
        } catch (Exception ignored) {
            // 配置读不出来就用默认语言，绝不因为语言问题阻断启动
        }
        I18N.applyConfig(lang);
    }

    /**
     * 将「下载源 / 并发下载数 / 高级下载设置」配置应用到全局下载组件：
     * 版本清单与安装引擎（VersionDownloadService）、启动资源补全（GameResourceCompleter）、
     * 加载器安装（LoaderInstallEngine）；线程数、分块阈值、分裂点、最小碎片、慢速触发线、
     * 限速统一由 {@link DownloadSettings#applyAll(Map)} 一次性压到 download.mod。
     */
    private void applyDownloadConfig() {
        String source = configCache.getOrDefault("DownloadSource", "BMCLAPI");
        DownloadProvider provider = VersionDownloadService.applyDownloadSource(source);
        GameResourceCompleter.setDownloadProvider(provider);
        LoaderInstallEngine.setDownloadProvider(provider);
        VersionManifest.setDownloadProvider(provider);
        // 下载参数：缺失键回落默认值（老配置文件无需迁移），限速一并压到全局令牌桶
        DownloadSettings.applyAll(configCache);
        DownloadCoordinator.getInstance().setThreadLimit(DownloadSettings.getDownloadThreads());
        // 2026-10-20：资源补全的全局文件并发配额同样按配置初始化（三阶段并行后共用一份）
        GameResourceCompleter.applyGlobalConcurrency();
        // 2026-10-21：PCL 引擎的 HTTP 层必须在任何 HttpURLConnection 创建之前初始化
        // （http.maxConnections 由 JDK 的 KeepAliveCache 在类初始化时读取一次，晚设无效）。
        // 这里在启动早期调用，早于版本清单等首批网络请求。
        com.example.starlight.download.pcl.PclDownloadManager.get().applyConcurrency();
        // 单线程 Host 黑名单用户片段（开发者选项页持久化）启动即注入，不依赖是否打开过该页
        String singleThreadHosts = configCache.getOrDefault("SingleThreadHosts", "");
        if (singleThreadHosts != null && !singleThreadHosts.isBlank()) {
            for (String frag : singleThreadHosts.split("\\|")) {
                if (!frag.isBlank()) HostPolicy.addSingleThreadHost(frag.trim());
            }
        }
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
        // 隔离按当前版本的有效配置判定（版本独立设置页可单版本开关）
        // 目录与版本都走真源：版本读的是该游戏目录自己的 starlight.json
        String gameDir = GameDirManager.activePath(configCache);
        String version = SelectedVersion.of(gameDir);
        return ResourceScanner.resolveScreenshotDir(
                configCache.get("ScreenshotDir"),
                gameDir,
                VersionConfigManager.effectiveVersionIsolation(configCache, gameDir, version),
                version);
    }

    // 右下角统一悬浮操作按钮（FAB） //

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
        fab.setItem(2, "power", I18N.get("shell.t0018"), true, this::openGameProcessDialog);
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

    /**
     * 按「游戏进程是否还在跑」切换「关闭游戏进程」子按钮的可用性（不可用时不出现在 FAB 里）。
     *
     * <p>启动动画还开着时一律不放出：这一刻游戏窗口还没（或刚刚）出现，右下角先挂一个
     * 「关闭游戏进程」既误导又容易被误点。真正放出的时机是「游戏窗口已出现 + 启动动画已收起」，
     * 由启动成功回调里 exitTimer 的收尾调用一次。
     */
    private void syncGameStopFab() {
        if (fab == null) return;
        fab.setItemEnabled(FAB_STOP_SLOT,
                UIGeneralControlClass.isGameRunning() && !overlay.isLaunching());
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

    // 启动状态广播（首页启动按钮动效） //

    @Override
    public Runnable addLaunchStateListener(Consumer<LauncherContext.LaunchState> listener) {
        if (listener == null) return () -> { };
        launchStateListeners.add(listener);
        // 页面刚重建（刷新首页）时立即同步当前状态：启动中的按钮不会闪回常态
        if (launchState != null) listener.accept(launchState);
        return () -> launchStateListeners.remove(listener);
    }

    /** 广播启动状态：只驱动界面动效，任何异常都不能中断启动流程 */
    private void fireLaunchState(LauncherContext.LaunchState state) {
        launchState = state;
        for (Consumer<LauncherContext.LaunchState> listener : launchStateListeners) {
            try {
                listener.accept(state);
            } catch (Exception ignored) {
                // 按钮动效失败不影响启动
            }
        }
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

        // 启动前把真源（该游戏目录内的 starlight.json）刷进内存镜像：
        // 防止界面/后续读取点残留上一个目录留下的版本
        SelectedVersion.syncCache(configCache);

        final LaunchConfig config;
        final String version;
        if (presetConfig != null) {
            config = presetConfig;
            version = config.version;
        } else {
            // 版本选择真源：当前游戏目录内的 starlight.json（不是 ini，也不是残留的内存值）
            version = SelectedVersion.of(configCache);
            if (version.isEmpty()) {
                ui.toast(I18N.get("shell.t0019"));
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

        // P0 防护：GraalVM 运行时启动前给一次明确选择（用户选完会回调 launchGame 重新进入）
        if (!passGraalVmGuard(config, loaderType)) return;

        // 启动器可见性（高级设置）：策略在启动时读一次，游戏启动与退出两个时机都用它，
        // 避免中途改配置导致「已经隐藏了却不还原」
        final String visibilityMode = LauncherVisibility.normalize(
                configCache.getOrDefault(LauncherVisibility.KEY, LauncherVisibility.KEEP));
        launcherHiddenByVisibility.set(false);
        // 上一轮启动若走过「关闭启动器」又没真退出成（极端情况），清掉标记避免本次误判已退出
        LauncherVisibility.resetExitRequested();

        // 启动按钮进入「启动中」：收成圆形、转起加载环（只改按钮外观，弹窗照常弹出）
        fireLaunchState(LauncherContext.LaunchState.STARTED);
        // 显示启动动画
        overlay.show(I18N.get("shell.k0005", version));

        // 实际启动游戏（异步，进度回调驱动真实进度）
        UIGeneralControlClass.launchMinecraftAsync(config,
                (percent, message) -> {
                    // 可见性策略要求游戏启动后关闭启动器：JavaFX 运行时正在/已经关掉，
                    // 此时后台线程再往界面上刷进度会抛 IllegalStateException（写进崩溃日志），
                    // 直接跳过——界面已经没人看了
                    if (LauncherVisibility.isExitRequested()) return;
                    Platform.runLater(() -> overlay.onProgress(percent, message));
                },
                new UIGeneralControlClass.ResultCallback<>() {
                    @Override
                    public void onSuccess(UIGeneralControlClass.LaunchResult result) {
                        // 「游戏启动后立即关闭」策略下进程早已退出；极端竞态下（退出还没走完
                        // 游戏就退了）JavaFX 运行时已关闭，再 runLater 会抛异常，直接跳过
                        if (LauncherVisibility.isExitRequested()) return;
                        Platform.runLater(() -> {
                            // 启动器可见性：先按策略收尾（隐藏过 / 最小化过要还原，
                            // 「隐藏后自动关闭」在这里退出启动器），再弹提示与崩溃面板
                            applyLauncherVisibilityOnExit(visibilityMode);
                            // 游戏进程已结束（无论正常/崩溃/被关）→ 收起「关闭游戏进程」悬浮按钮
                            syncGameStopFab();
                            // 通知帧生成器：游戏进程已退出（权威信号，取消/正常/崩溃退出都执行）
                            fgNotifyGameStopped();
                            // 色盲辅助：目标窗口已消失，矫正工具一并关闭
                            ColorBlindOverlayManager.notifyGameStopped();
                            // 用户主动结束（取消启动 / 从关闭进程弹窗关掉）：不再弹提示与崩溃面板。
                            // stoppedByUser 是按实例记的，多实例下关掉一个不会连累另一个的崩溃提示
                            if (overlay.isCancelledByUser() || result.stoppedByUser) {
                                // 用户主动结束：启动按钮从「启动中」展开回胶囊
                                fireLaunchState(LauncherContext.LaunchState.FAILED);
                                overlay.hide();
                                refreshHomePage();
                                return;
                            }
                            if (result.isNormal()) {
                                ui.toast(I18N.get("shell.t0020"));
                            } else {
                                // 异常退出：弹出集成式崩溃诊断面板（替代独立 GameCrashWindow）
                                showGameCrashPanel(result);
                            }
                            overlay.hide();
                            // 游戏已退出：按钮复位，刷新首页时不要重播对勾
                            launchState = null;
                            // 游戏退出后刷新首页：存档列表（快速进入存档）、最近游玩等实时数据更新
                            refreshHomePage();
                        });
                    }
                    @Override
                    public void onError(String error) {
                        if (LauncherVisibility.isExitRequested()) return;
                        Platform.runLater(() -> {
                            // 启动器可见性：启动失败时启动器通常还没被隐藏（隐藏发生在游戏窗口
                            // 就绪之后），这里只在确实隐藏/最小化过的情况下还原窗口，绝不额外退出
                            applyLauncherVisibilityOnExit(visibilityMode);
                            // 启动失败：进程没起来（或刚起就死），悬浮按钮不该留在界面上
                            syncGameStopFab();
                            // 色盲辅助：启动失败（含被取消）时游戏窗口不会出现，矫正工具不该留在后台
                            ColorBlindOverlayManager.notifyGameStopped();
                            // 启动失败 / 取消：启动按钮从「启动中」展开回胶囊
                            fireLaunchState(LauncherContext.LaunchState.FAILED);
                            // 用户主动取消：后台检查点回调的「启动已取消」不再误报为启动失败
                            if (overlay.isCancelledByUser()) {
                                overlay.hide();
                                return;
                            }
                            ui.toast(I18N.get("shell.k0006", error));
                            overlay.hide();
                        });
                    }
                },
                () -> Platform.runLater(() -> {
                    // 迟到的「游戏已启动」回调必须丢弃：检测线程不响应取消时会在取消后 25~60 秒
                    // 补报一次。不挡掉就会给启动按钮补一个对勾、写一条假的「最近游玩」、
                    // 误拉起色盲辅助，甚至按启动器可见性策略把启动器隐藏 / 最小化 / 关闭。
                    if (overlay.isCancelledByUser() || launchState != LauncherContext.LaunchState.STARTED) return;

                    // 通知帧生成器：游戏已由启动器拉起（权威信号，进程扫描可能因启动器差异而滞后/失败）
                    fgNotifyGameStarted(config, version);

                    // 「关闭游戏进程」不在这里放出：启动动画还要停 1.5 秒才收起，
                    // 按交互要求等它收掉之后再显示（见下面 exitTimer 的收尾）

                    // 色盲辅助：游戏窗口已出现 → 按配置拉起 ColorBlindOverlay（只抓游戏窗口）
                    ColorBlindOverlayManager.notifyGameStarted();

                    // 游戏窗口已出现 → 保存本次启动记录
                    LaunchRecordStore.save(config, loaderType);

                    // 关闭启动动画（进度补满 + 对勾转场 + 文案收尾）
                    overlay.markSuccess();

                    // 启动成功：启动按钮也补一个对勾（弹窗 1.5s 后关闭，重建的首页按钮会同步到该状态）
                    fireLaunchState(LauncherContext.LaunchState.SUCCEEDED);

                    Timeline exitTimer = new Timeline(new KeyFrame(Duration.seconds(1.5), ev -> {
                        overlay.hide();
                        // 先清状态再重建首页：反过来的话 refreshHomePage() 时 launchState 还是
                        // SUCCEEDED，重建出来的新按钮会被立即刷成对勾（对勾又粘住不放）
                        launchState = null;
                        refreshHomePage();
                        // 启动动画已收起（overlay.hide() 之前已把 isLaunching 置 false）、
                        // 游戏窗口确实在跑 → 这一刻才放出「关闭游戏进程」
                        syncGameStopFab();
                    }));
                    exitTimer.play();

                    // 启动器可见性（高级设置）：游戏窗口已就绪，按策略隐藏 / 最小化 / 关闭启动器。
                    // 放在最后：先让启动动画走完自己的收尾，再动窗口
                    if (LauncherVisibility.onGameStarted(stage, visibilityMode)) {
                        launcherHiddenByVisibility.set(true);
                    }
                })
        );
    }

    /**
     * 游戏退出 / 启动失败时收尾「启动器可见性」：还原窗口、或按「隐藏后自动关闭」退出启动器。
     *
     * <p>只在启动时确实动过窗口（{@link #launcherHiddenByVisibility}）才执行，执行后复位 ——
     * 「立即关闭」策略下启动失败时启动器其实还开着，不能被这里的回调顺手关掉。
     */
    private void applyLauncherVisibilityOnExit(String visibilityMode) {
        if (!launcherHiddenByVisibility.getAndSet(false)) return;
        LauncherVisibility.onGameExited(stage, visibilityMode, true);
    }

    // GraalVM 防护（P0） //

    /** 用户已选择「仍用这个 GraalVM 运行时」时记录的 java 路径：同一个运行时不再重复询问 */
    private static final String KEY_GRAAL_GUARD_SKIP = "GraalVmGuardSkip";

    /**
     * GraalVM 启动前的一次确认。不静默改用户的 Java（换运行时影响所有版本），也不硬拦
     * （有人就是要用 GraalVM），但必须把「已知会无 Java 堆栈地原生崩溃」这件事摆在启动现场。
     *
     * @return true = 继续启动；false = 已弹出选择框，用户选完会重新进入 {@link #launchGame(LaunchConfig)}
     */
    private boolean passGraalVmGuard(LaunchConfig config, String loaderType) {
        String javaPath = config == null ? null : config.javaPath;
        if (!com.example.starlight.startmc.launcher.PlatformUtils.isGraalVmRuntime(javaPath)) return true;
        // 已明确选过「仍用」：这个运行时不再打扰
        if (javaPath.equals(configCache.getOrDefault(KEY_GRAAL_GUARD_SKIP, ""))) return true;
        showGraalVmGuard(config, loaderType, findNonGraalJdk());
        return false;
    }

    /** 在已扫描到的 Java 里挑一个非 GraalVM 的标准 JDK：优先 21，其次 17，再其次更新的版本 */
    private static String findNonGraalJdk() {
        try {
            String best = null;
            int bestScore = -1;
            for (FindAllJavaWindows.JavaEntry entry : JavaCacheManager.getAvailable()) {
                if (entry == null || !entry.isJDK) continue;
                if (com.example.starlight.startmc.launcher.PlatformUtils
                        .isGraalVmRuntime(entry.homePath)) continue;
                if (entry.majorVersion < 17) continue;
                // 21 是文档给出的兼容基线；17 次之；更高的标准 JDK 仍比 GraalVM 稳
                int score = entry.majorVersion == 21 ? 30 : entry.majorVersion == 17 ? 20 : 10;
                if (score > bestScore) {
                    bestScore = score;
                    best = entry.getJavaExePath();
                }
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    /** java.exe 路径 → 人读的短名（取 JAVA_HOME 目录名，如 graalvm-25.2.4 / jdk-21.0.5） */
    private static String shortJavaName(String javaExe) {
        try {
            java.nio.file.Path exe = java.nio.file.Paths.get(javaExe);
            java.nio.file.Path home = exe.getParent() != null ? exe.getParent().getParent() : null;
            return home != null && home.getFileName() != null
                    ? home.getFileName().toString() : javaExe;
        } catch (Exception e) {
            return javaExe;
        }
    }

    /** 三选一：改用标准 JDK / 仍用 GraalVM（不再提示）/ 取消本次启动 */
    private void showGraalVmGuard(LaunchConfig config, String loaderType, String candidate) {
        VBox body = new VBox(10);
        body.setAlignment(Pos.CENTER);
        body.setPadding(new Insets(22, 24, 18, 24));

        Label title = new Label(I18N.get("shell.t0021"));
        title.getStyleClass().add("modal-card-title");

        Label desc = new Label(I18N.get("shell.p0028", config.version, loaderType));
        desc.getStyleClass().add("modal-desc");
        desc.setWrapText(true);
        desc.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);

        Button useJdk = new Button(candidate != null
                ? I18N.get("shell.r0001", shortJavaName(candidate))
                : I18N.get("shell.t0022"));
        useJdk.getStyleClass().add("modal-btn-ok");
        useJdk.setMaxWidth(Double.MAX_VALUE);
        useJdk.setDisable(candidate == null);
        useJdk.setOnAction(e -> {
            // 同时关掉「自动选择 Java」：否则启动流程会用自动挑的那个（很可能还是 GraalVM）
            configCache.put("JavaPath", candidate);
            configCache.put("AutoJava", "false");
            configCache.put(KEY_GRAAL_GUARD_SKIP, "");
            saveConfig();
            config.javaPath = candidate;
            ui.closeModal();
            ui.toast(I18N.get("shell.k0007", shortJavaName(candidate)));
            launchGame(config);
        });

        Button keep = new Button(I18N.get("shell.t0023"));
        keep.getStyleClass().add("modal-btn-cancel");
        keep.setMaxWidth(Double.MAX_VALUE);
        keep.setOnAction(e -> {
            configCache.put(KEY_GRAAL_GUARD_SKIP, config.javaPath);
            saveConfig();
            ui.closeModal();
            launchGame(config);
        });

        Button cancel = new Button(I18N.get("shell.t0024"));
        cancel.getStyleClass().add("modal-btn-cancel");
        cancel.setMaxWidth(Double.MAX_VALUE);
        cancel.setOnAction(e -> {
            ui.closeModal();
            ui.toast(I18N.get("shell.t0025"));
        });

        body.getChildren().addAll(title, desc, useJdk, keep, cancel);
        ui.modal("", body, 460, -1, false);
    }

    // 崩溃诊断面板（集成在启动器内，替代独立 GameCrashWindow / AIDiagnosisWindow） //

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
                ui::closeModal,
                stage);
        ui.modal(I18N.get("shell.t0026"), panel, 780, 540);
    }

    /** AI 诊断面板：从崩溃面板打开，关闭后回到崩溃面板 */
    private void showAIDiagnosisPanel(CrashDiagnosticData data) {
        AIDiagnosisPanel panel = new AIDiagnosisPanel(data, ui::toast,
                () -> showGameCrashPanel(data),
                // 未配置 AI 服务时面板上的「去 AI 配置」：关掉弹窗直接跳到高级设置页
                () -> {
                    ui.closeModal();
                    switchToSettings("sidebarAdvanced");
                });
        ui.modal(I18N.get("shell.t0027"), panel, 780, 540);
    }

    // 快捷工具 //

    @Override
    public void handleQuickTool(String toolName) {
        // 派发键是稳定 id（图标名），不是显示文案——见 HomePage 快捷工具格子的注释。
        // 旧实现按中文文案 switch，一旦界面语言变化，所有入口都会静默失效。
        switch (toolName == null ? "" : toolName) {
            case "memory" -> switchToPage("memory");
            case "globe" -> switchToPage("networkCheck");
            case "clapper" -> switchToPage("screenshots");
            case "wrench" -> switchToSettings("sidebarJvm");
            case "bolt" -> switchToPage("frameGen");
            case "view" -> switchToSettings("sidebarColorBlind");
            default -> { }
        }
    }

    // 系统监控 //

    private Node buildSystemStats() {
        return homePage.buildSystemStats();
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

    // 设置页功能 //
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
    public com.example.starlight.newui.ui.CustomThemeColor customThemeColor() {
        return themeMgr.customColor();
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

