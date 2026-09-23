package com.example.starlight.newui.page;

import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.ModsApi.CurseForgeAPI;
import com.example.starlight.ModsApi.CurseForgeRemoteModRepository;
import com.example.starlight.ModsApi.LocalizedRemoteModRepository;
import com.example.starlight.ModsApi.ModCategories;
import com.example.starlight.ModsApi.ModLoaderType;
import com.example.starlight.ModsApi.ModSearchQuery;
import com.example.starlight.ModsApi.ModrinthRemoteModRepository;
import com.example.starlight.ModsApi.RemoteMod;
import com.example.starlight.ModsApi.RemoteModDetail;
import com.example.starlight.ModsApi.ModTranslations;
import com.example.starlight.ModsApi.RemoteModRepository;
import com.example.starlight.download.DownloadProvider;
import com.example.starlight.download.DownloadSettings;
import com.example.starlight.download.GameResourceCompleter;
import com.example.starlight.download.HttpDownloadEngine;
import com.example.starlight.gui.UIGeneralControlClass;
import com.example.starlight.main.ConfigManager;
import com.example.starlight.modpack.ModpackManifestParser;
import com.example.starlight.modpack.PackFile;
import com.example.starlight.modpack.PackManifest;
import com.example.starlight.newui.AppConfig;
import com.example.starlight.newui.MarkdownRenderer;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.download.DownloadTaskCard;
import com.example.starlight.newui.download.DownloadTaskManager;
import com.example.starlight.newui.download.ModHit;
import com.example.starlight.newui.download.ModInstallTargetDialog;
import com.example.starlight.newui.download.RemoteCacheService;
import com.example.starlight.newui.download.SearchCacheEntry;
import com.example.starlight.modfile.ModFileFormatter;
import com.example.starlight.newui.ui.InertiaScrollSupport;
import com.example.starlight.newui.ui.IconService;
import com.example.starlight.newui.ui.PageKit;
import com.example.starlight.newui.ui.SkeletonFactory;
import com.example.starlight.util.ArchiveUtils;
import com.example.starlight.util.FormatUtils;
import com.example.starlight.util.GameDirInitializer;
import com.example.starlight.util.ResourceScanner;
import com.example.starlight.version.InstallEngine;
import com.example.starlight.version.LoaderInstallEngine;
import com.example.starlight.version.VersionDownloadService;
import com.example.starlight.version.VersionMatcher;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javafx.animation.FadeTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 下载中心：版本下载 / 整合包 / 模组 / 资源包 / 光影包 / 数据包 / 世界，
 * 以及版本安装配置页、资源详情页、选择版本页与各类安装流程。
 *
 * <p>从 LauncherView 抽离（方法体逐字搬运，逻辑、样式类与内联样式均未改动）。
 * 页面状态（下载导航按钮、分类缓存、版本清单缓存、当前详情/选版本/版本配置上下文、
 * 灯箱遮罩等）随页面迁入本类；主壳保留 {@code downloadPage} 单例，
 * 保证「详情页 / 选版本页 / 版本配置页」的上下文在切页后仍然有效。
 */
public final class DownloadCenterPage {

    private final LauncherContext host;
    private final DownloadTaskManager downloads;
    /** 下载中心远程数据缓存（搜索结果 / 项目版本列表 / 项目详情） */
    private final RemoteCacheService remoteCache = new RemoteCacheService();

    /** 下载中心左侧分类按钮引用（供资源页“搜索资源”跳转到对应分类） */
    private final List<Button> downloadNavButtons = new ArrayList<>();
    /** 下载页「分类」下拉的选项缓存：key = 仓库标签 + 资源类型，避免每次切页都重新请求分类接口 */
    private final Map<String, List<ModCategoryOption>> modCategoryCache = new ConcurrentHashMap<>();
    /** 「游戏版本」筛选下拉的选项（来自版本清单，首次进下载页时异步加载一次） */
    private List<String> downloadVersionOptions = new ArrayList<>();

    public DownloadCenterPage(LauncherContext ctx, DownloadTaskManager downloads) {
        this.host = ctx;
        this.downloads = downloads;
    }

    /** 下载页左侧分类按钮（供资源页「搜索资源」跳转到对应分类） */
    public List<Button> navButtons() {
        return downloadNavButtons;
    }

    /** 资源详情页上下文是否仍在（下载管理页「返回」据此回退到下载列表） */
    public boolean hasDetailContext() {
        return currentModDetail != null;
    }

    /** 选版本页上下文是否仍在（下载管理页「返回」据此回退到下载列表） */
    public boolean hasVersionPickContext() {
        return currentVersionPick != null;
    }

    public Node buildCenter() {
        HBox layout = new HBox(20);
        layout.setPadding(new Insets(0, 4, 0, 0));

        VBox nav = new VBox(4);
        nav.setPrefWidth(160);
        nav.setMinWidth(160);
        nav.setMaxWidth(160);
        nav.setMaxHeight(Double.MAX_VALUE);
        nav.getStyleClass().add("download-nav");
        Label title = new Label("下载中心");
        title.getStyleClass().add("download-nav-title");
        nav.getChildren().add(title);

        StackPane contentArea = new StackPane();
        contentArea.setPadding(new Insets(0, 0, 0, 16));
        HBox.setHgrow(contentArea, Priority.ALWAYS);

        // 侧边栏顺序：游戏版本 → 模组 → 整合包 → 数据包 → 资源包 → 光影包 → 世界
        String[][] types = {
                {"dl_versions", "游戏版本", "version-select"},
                {"dl_mods", "模组", "puzzle"},
                {"dl_modpacks", "整合包", "package"},
                {"dl_datapacks", "数据包", "file"},
                {"dl_resourcepacks", "资源包", "image"},
                {"dl_shaders", "光影包", "sun"},
                {"dl_worlds", "世界", "world"}
        };

        // 整合包面板额外提供「导入本地整合包」入口（支持 .mrpack 与 CurseForge zip），
        // 这一行也交给 buildContentPanel 放进同一张工具条卡片里
        VBox modpackPanel = new VBox(10);
        HBox importRow = new HBox(10);
        importRow.setAlignment(Pos.CENTER_LEFT);
        Button importBtn = AppIcons.button("folder", "导入本地整合包");
        importBtn.getStyleClass().add("btn-primary");
        importBtn.setMinWidth(Region.USE_PREF_SIZE);
        importBtn.setOnAction(e -> importLocalModpack());
        Label importHint = new Label("支持 Modrinth .mrpack 与 CurseForge 整合包 zip（.zip）");
        importHint.setStyle("-fx-font-size: 11px; -fx-text-fill: -sl-text-dim;");
        importRow.getChildren().addAll(importBtn, importHint);
        modpackPanel.getChildren().add(
                buildContentPanel("整合包", RemoteModRepository.Type.MODPACK,
                        List.of(new ModSource("Modrinth", ModrinthRemoteModRepository.MODPACKS),
                                new ModSource("CurseForge", CurseForgeRemoteModRepository.MODPACKS)),
                        importRow));

        Node[] panels = {
                buildDownloadVersionsPanel(),
                buildContentPanel("模组", RemoteModRepository.Type.MOD,
                        List.of(new ModSource("Modrinth", ModrinthRemoteModRepository.MODS),
                                new ModSource("CurseForge", CurseForgeRemoteModRepository.MODS))),
                modpackPanel,
                buildContentPanel("数据包", RemoteModRepository.Type.CUSTOMIZATION,
                        List.of(new ModSource("Modrinth", ModrinthRemoteModRepository.DATAPACKS),
                                new ModSource("CurseForge", CurseForgeRemoteModRepository.DATAPACKS))),
                buildContentPanel("资源包", RemoteModRepository.Type.RESOURCE_PACK,
                        List.of(new ModSource("Modrinth", ModrinthRemoteModRepository.RESOURCE_PACKS),
                                new ModSource("CurseForge", CurseForgeRemoteModRepository.RESOURCE_PACKS))),
                buildContentPanel("光影包", RemoteModRepository.Type.SHADER_PACK,
                        List.of(new ModSource("Modrinth", ModrinthRemoteModRepository.SHADER_PACKS),
                                new ModSource("CurseForge", CurseForgeRemoteModRepository.SHADER_PACKS))),
                buildDownloadWorldsPanel()
        };

        ScrollPane contentScroll = new ScrollPane(contentArea);
        // 统一挂接惯性滚动（阻尼 + fling）；「游戏版本」固定页的 vbar 策略与 fitToHeight 逻辑保持不变
        PageKit.configureScrollPane(host, contentScroll);
        HBox.setHgrow(contentScroll, Priority.ALWAYS);

        // 「游戏版本」这一页整页固定：交给 ScrollPane 的 fitToHeight 把面板压成视口高度
        // （筛选卡片不上滚，滚动只发生在版本列表内部，见 buildDownloadVersionsPanel），
        // 同时关掉外层滚动条 —— 这一页不允许整页滚，滚动条只该出现在版本列表上。
        // 注意别改成「把面板 prefHeight 绑定到 viewportBounds」：那是在布局过程中改布局输入，
        // 会和滚动条的显隐互相触发，右侧滚动条会反复开关（页面左右抖动）。
        // 代价：视口小于面板最小高度（约 406px）时底部会被裁掉，窗口最小高度 600+ 保证了用不到。
        final Node versionsPanel = panels[0];
        final ScrollPane.ScrollBarPolicy normalVbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED;

        for (int i = 0; i < types.length; i++) {
            Button btn = new Button(types[i][1]);
            btn.getStyleClass().add("sidebar-item");
            btn.setMaxWidth(Double.MAX_VALUE);
            AppIcons.apply(btn, types[i][2], 15, null);
            final int idx = i;
            btn.setOnAction(e -> {
                contentArea.getChildren().setAll(panels[idx]);
                boolean fixedPage = panels[idx] == versionsPanel;
                contentScroll.setFitToHeight(fixedPage);
                contentScroll.setVbarPolicy(fixedPage
                        ? ScrollPane.ScrollBarPolicy.NEVER : normalVbarPolicy);
                nav.getChildren().stream()
                        .filter(n -> n instanceof Button)
                        .forEach(n -> n.getStyleClass().remove("selected"));
                btn.getStyleClass().add("selected");
            });
            downloadNavButtons.add(btn);
            nav.getChildren().add(btn);
        }

        contentArea.getChildren().add(panels[0]);
        contentScroll.setFitToHeight(true);                       // 初始显示的就是「游戏版本」
        contentScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        ((Button) nav.getChildren().get(1)).getStyleClass().add("selected");

        layout.getChildren().addAll(nav, contentScroll);
        VBox.setVgrow(layout, Priority.ALWAYS);
        return layout;
    }

    // ==================== 下载中心：游戏版本 ====================

    private List<VersionDownloadService.VersionInfo> lastVersionInfos;

    /** 版本页分类标签：key + 显示名（顺序与 VersePc2 版本页一致） */
    private static final String[][] CATEGORY_TABS = {
            {"release", "正式版"}, {"snapshot", "快照"}, {"april", "愚人节版"}, {"old", "远古版"}
    };

    /** 分类标签 key → 版本分类枚举 */
    private static VersionDownloadService.VersionCategory categoryOf(String key) {
        return switch (key == null ? "" : key) {
            case "snapshot" -> VersionDownloadService.VersionCategory.SNAPSHOT;
            case "april" -> VersionDownloadService.VersionCategory.APRIL;
            case "old" -> VersionDownloadService.VersionCategory.OLD;
            default -> VersionDownloadService.VersionCategory.RELEASE;
        };
    }

    /**
     * 游戏版本下载页（版本选择页）。
     *
     * <p>结构：搜索栏 → 分类标签（正式版 / 快照 / 愚人节版 / 远古版）→ <b>可滚动的版本行列表</b>。
     * <b>左键单击任意版本行</b>即进入该版本的<b>下载配置页</b>
     * （版本信息 + 版本文件夹名 + 原版/加载器选择 + 开始安装）。
     */
    private Node buildDownloadVersionsPanel() {
        VBox root = new VBox(12);
        root.setPadding(new Insets(0, 4, 0, 0));

        Label titleLabel = new Label("游戏版本");
        titleLabel.getStyleClass().add("content-title");
        root.getChildren().add(titleLabel);

        // 工具条：搜索 + 刷新
        HBox toolbar = new HBox(8);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        TextField searchField = new TextField();
        searchField.setPromptText("搜索版本，如 1.20.1");
        searchField.getStyleClass().add("input-field");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        Button searchBtn = AppIcons.button("search", "搜索");
        searchBtn.getStyleClass().add("btn-primary");
        Button refreshBtn = AppIcons.button("refresh", "刷新");
        refreshBtn.getStyleClass().add("btn-primary");
        toolbar.getChildren().addAll(searchField, searchBtn, refreshBtn);

        // 分类标签栏
        HBox tabBar = new HBox(6);
        tabBar.setAlignment(Pos.CENTER_LEFT);

        Label countLabel = new Label();
        countLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-dim;");

        // 版本列表：占满筛选卡片以下的剩余高度 —— 整页固定不滚，滚动只发生在列表内部
        // （本页在外层 ScrollPane 上开了 fitToHeight，见 buildCenter）
        ListView<VersionDownloadService.VersionInfo> versionList = new ListView<>();
        versionList.getStyleClass().add("version-list");
        versionList.setPrefHeight(VERSION_LIST_HEIGHT);
        versionList.setMinHeight(200);
        versionList.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(versionList, Priority.ALWAYS);
        versionList.setFocusTraversable(false);
        // 虚拟化列表接入惯性滚动（阻尼手感与页面滚动一致）；SmoothScroll=false 时保持原生滚动
        InertiaScrollSupport.installListView(host, versionList);
        versionList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(VersionDownloadService.VersionInfo v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                setText(null);
                setGraphic(buildVersionRowGraphic(v));
                // 左键单击单元格即进入下载配置页（点「下载配置」按钮时按钮先消费事件，不会重复触发）
                setOnMouseClicked(e -> {
                    if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                        openVersionConfigPage(v);
                    }
                });
            }
        });

        // 「向下加载更多」：贴在本批列表下方；还有没铺完的版本时才出现
        Button moreBtn = new Button();
        moreBtn.getStyleClass().add("btn-primary");
        moreBtn.setStyle("-fx-padding: 6 20; -fx-font-size: 12px; -fx-background-radius: 18;");
        moreBtn.setMinWidth(Region.USE_PREF_SIZE);
        moreBtn.setMaxWidth(Region.USE_PREF_SIZE);
        HBox moreRow = new HBox(moreBtn);
        moreRow.setAlignment(Pos.CENTER);
        moreRow.setVisible(false);
        moreRow.setManaged(false);

        final String[] currentTab = {CATEGORY_TABS[0][0]};
        final List<VersionDownloadService.VersionInfo>[] allRef = new List[]{List.of()};
        /** 当前筛选（分类 + 关键词）后的完整版本列表：点「向下加载更多」时按它继续取下一批 */
        final List<VersionDownloadService.VersionInfo>[] filteredRef = new List[]{List.of()};
        final Button[] tabButtons = new Button[CATEGORY_TABS.length];

        // 搜索/切标签后统一由它重绘（读取当前关键词与当前标签）
        final Runnable[] redraw = new Runnable[1];
        redraw[0] = () -> {
            String kw = searchField.getText().trim();
            List<VersionDownloadService.VersionInfo> base = allRef[0];
            if (base.isEmpty()) return;
            List<VersionDownloadService.VersionInfo> filtered = kw.isEmpty()
                    ? base : VersionDownloadService.searchVersions(base, kw);
            fillVersionList(versionList, countLabel, moreBtn, moreRow, filtered, currentTab[0], filteredRef);
        };

        for (int i = 0; i < CATEGORY_TABS.length; i++) {
            final String key = CATEGORY_TABS[i][0];
            Button tab = new Button(CATEGORY_TABS[i][1]);
            // 与资源详情页标签栏同一套样式：选中为实心蓝底白字，一眼能看出当前分类
            tab.getStyleClass().add("detail-tab");
            tab.setOnAction(e -> {
                currentTab[0] = key;
                for (Button b : tabButtons) {
                    if (b != null) b.getStyleClass().remove("selected");
                }
                tab.getStyleClass().add("selected");
                redraw[0].run();
            });
            tabButtons[i] = tab;
            tabBar.getChildren().add(tab);
        }
        tabButtons[0].getStyleClass().add("selected");

        // 搜索栏 + 分类标签 + 计数提示装进同一张卡片（标题「游戏版本」留在卡片外）
        VBox filterCard = PageKit.toolbarCard(toolbar, tabBar, countLabel);
        // 筛选卡片锁定固有高度：空间不足时只压缩下面的版本列表，筛选区始终完整可见
        filterCard.setMinHeight(Region.USE_PREF_SIZE);
        root.getChildren().add(filterCard);
        root.getChildren().addAll(versionList, moreRow);

        searchField.setOnAction(e -> searchBtn.fire());
        searchBtn.setOnAction(e -> redraw[0].run());
        refreshBtn.setOnAction(e -> {
            // 重新拉取清单期间先收起上一批留下的「向下加载更多」
            updateVersionMoreRow(moreBtn, moreRow, 0, 0);
            // 刷新语义 = 强制重新联网拉清单，绕过 5 分钟内存缓存
            VersionDownloadService.invalidateManifestCache();
            loadDownloadVersions(versionList, countLabel, infos -> {
                allRef[0] = infos;
                redraw[0].run();
            });
        });

        loadDownloadVersions(versionList, countLabel, infos -> {
            allRef[0] = infos;
            redraw[0].run();
        });
        return root;
    }

    /** 版本区的一行提示文案 */
    private static Label versionHint(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: -sl-text-dim; -fx-padding: 20;");
        return l;    }

    /** 异步加载版本清单，完成后把结果交给 {@code onLoaded} 在 FX 线程渲染 */
    private void loadDownloadVersions(ListView<VersionDownloadService.VersionInfo> versionList, Label countLabel,
                                      java.util.function.Consumer<List<VersionDownloadService.VersionInfo>> onLoaded) {
        versionList.getItems().clear();
        if (countLabel != null) countLabel.setText("");
        // 版本列表为空时 ListView 会显示这个占位内容；加载期间用骨架屏微光。
        // 外面套一层带裁剪的容器：placeholder 本身不受 ListView 裁剪，列表比骨架屏矮时
        // （窗口被压小）骨架行会画到列表区域之外、压住上面的筛选卡片，这里裁掉。
        versionList.setPlaceholder(clippedPlaceholder(SkeletonFactory.buildListSkeleton(4)));
        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            try {
                List<VersionDownloadService.VersionInfo> infos = VersionDownloadService.fetchVersionManifest();
                lastVersionInfos = infos;
                Platform.runLater(() -> {
                    if (infos.isEmpty()) {
                        versionList.setPlaceholder(errorPlaceholder("版本清单加载失败，请检查网络后重试", null,
                                () -> loadDownloadVersions(versionList, countLabel, onLoaded)));
                        return;
                    }
                    onLoaded.accept(infos);
                    // 加载完成后换成空态提示：列表为空是因为「没有匹配」，而不是还在加载
                    versionList.setPlaceholder(skeletonHint("没有匹配的版本"));
                    VersionDownloadService.preloadPopularVersions(infos);
                });
            } catch (Exception e) {
                Platform.runLater(() -> versionList.setPlaceholder(
                        errorPlaceholder("版本清单加载失败", String.valueOf(e.getMessage()),
                                () -> loadDownloadVersions(versionList, countLabel, onLoaded))));
            }
        });
    }

    /**
     * ListView 的「加载失败」占位：统一错误卡片（bug 图标 + 重载/复制按钮）套裁剪容器。
     * 重载动作由调用方注入；detail 为 null 时不显示错误详情行。
     */
    private static StackPane errorPlaceholder(String title, String detail, Runnable onReload) {
        return clippedPlaceholder(PageKit.buildErrorState(title, detail, onReload));
    }

    /** 列表占位用的提示文字（加载失败 / 空态），与骨架屏交替使用 */
    private static Label skeletonHint(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: -sl-text-dim; -fx-padding: 24;");
        return l;
    }

    /**
     * 给列表占位内容套一层「按自身尺寸裁剪 + 顶部对齐」的容器。
     *
     * <p>ListView 的 placeholder 画在列表内容区里但不受其裁剪，占位内容比列表高时
     * 会溢到列表外（压住上方的筛选卡片）。套上裁剪后超出部分被裁掉，只可能少显示几行，
     * 不会再画到别的控件上。
     */
    private static StackPane clippedPlaceholder(Node content) {
        StackPane holder = new StackPane(content);
        holder.setAlignment(Pos.TOP_LEFT);
        holder.setMinWidth(0);
        holder.setMinHeight(0);
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
        clip.widthProperty().bind(holder.widthProperty());
        clip.heightProperty().bind(holder.heightProperty());
        holder.setClip(clip);
        return holder;
    }

    /** 版本分类显示名 */
    private static String categoryLabel(VersionDownloadService.VersionCategory category) {
        if (category == null) return "版本";
        return switch (category) {
            case RELEASE -> "正式版";
            case SNAPSHOT -> "快照";
            case OLD -> "远古版";
            case APRIL -> "愚人节版";
            default -> "版本";
        };
    }

    /** 版本列表的可视高度：固定高度 + 内部滚动，避免整页被上百行版本撑长 */
    private static final double VERSION_LIST_HEIGHT = 430;

    /**
     * 单个版本行的内容（类型色块 + 版本号 + 「类型 · 发布日期」+「下载配置」按钮）。
     * <p>作为 {@link ListView} 单元格的 graphic，本身不承担 hover / 选中样式。
     */
    private HBox buildVersionRowGraphic(VersionDownloadService.VersionInfo v) {
        HBox row = new HBox(12);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("settings-card");
        // settings-card 自带 hover 位移；列表里由单元格统一给反馈，这里固定住
        row.setStyle("-fx-cursor: hand; -fx-translate-y: 0;");

        // 版本类型图标：正式版草方块 / 快照命令方块 / 愚人节金块 / 远古圆石
        ImageView icon = IconService.versionIconView(v.getCategory(), 34);
        StackPane iconBox = new StackPane(icon);
        iconBox.setMinSize(40, 40);
        iconBox.setPrefSize(40, 40);
        iconBox.setMaxSize(40, 40);

        VBox info = new VBox(3);
        HBox.setHgrow(info, Priority.ALWAYS);
        Label idLabel = new Label(v.getId());
        idLabel.getStyleClass().add("settings-card-title");
        Label meta = new Label(categoryLabel(v.getCategory()) + "  ·  "
                + VersionDownloadService.formatDate(v.getReleaseTime()));
        meta.getStyleClass().add("settings-card-desc");
        info.getChildren().addAll(idLabel, meta);

        Button configBtn = AppIcons.button("sliders", "下载配置");
        configBtn.getStyleClass().add("btn-primary");
        configBtn.setStyle("-fx-padding: 4 14; -fx-font-size: 12px;");
        configBtn.setOnAction(e -> openVersionConfigPage(v));

        row.getChildren().addAll(iconBox, info, configBtn);
        return row;
    }

    /**
     * 填充版本列表：固定高度、可滚动、虚拟化渲染。
     * <p><b>一次只放入 {@value #VERSION_LIST_PAGE} 个版本</b>（快照分类有 700+ 个，
     * 一次全铺出来会让首屏卡顿），还有剩余时在列表下方亮出「向下加载更多」按钮，
     * 由它把下一批追加进 {@code list}；{@code filteredOut} 记录当前筛选后的完整列表供追加时取用。
     * <p><b>左键单击任意一行</b>进入该版本的下载配置页。
     */
    private static void fillVersionList(ListView<VersionDownloadService.VersionInfo> list, Label countLabel,
                                 Button moreBtn, HBox moreRow,
                                 List<VersionDownloadService.VersionInfo> versions, String categoryKey,
                                 List<VersionDownloadService.VersionInfo>[] filteredOut) {
        list.getItems().clear();
        filteredOut[0] = List.of();
        if (versions == null || versions.isEmpty()) {
            if (countLabel != null) countLabel.setText("没有找到匹配的版本");
            updateVersionMoreRow(moreBtn, moreRow, 0, 0);
            return;
        }
        List<VersionDownloadService.VersionInfo> filtered =
                VersionDownloadService.filterByCategory(versions, categoryOf(categoryKey));
        if (filtered.isEmpty()) {
            if (countLabel != null) countLabel.setText("该分类下暂无版本");
            updateVersionMoreRow(moreBtn, moreRow, 0, 0);
            return;
        }
        filteredOut[0] = filtered;
        int first = Math.min(VERSION_LIST_PAGE, filtered.size());
        list.getItems().setAll(filtered.subList(0, first));
        list.scrollTo(0);
        // 「向下加载更多」的行为也在这里接管：点一次追加一批，并滚到新出现的第一行
        moreBtn.setOnAction(e -> {
            int shown = list.getItems().size();
            int to = Math.min(shown + VERSION_LIST_PAGE, filteredOut[0].size());
            if (to <= shown) return;
            list.getItems().addAll(filteredOut[0].subList(shown, to));
            list.scrollTo(shown);
            updateVersionMoreRow(moreBtn, moreRow, to, filteredOut[0].size());
        });
        if (countLabel != null) {
            countLabel.setText(filtered.size() > VERSION_LIST_PAGE
                    ? "共 " + filtered.size() + " 个版本 · 每次显示 " + first + " 个，点下方按钮继续往下加载"
                    : "共 " + filtered.size() + " 个版本 · 左键单击任意版本进入它的下载配置");
        }
        updateVersionMoreRow(moreBtn, moreRow, first, filtered.size());
    }

    /** 刷新「向下加载更多」按钮：还有剩余时显示并更新进度，否则整行隐藏 */
    private static void updateVersionMoreRow(Button moreBtn, HBox moreRow, int shown, int total) {
        boolean hasMore = shown < total;
        if (moreBtn != null) {
            moreBtn.setText("向下加载更多（已显示 " + shown + " / " + total + "）");
        }
        if (moreRow != null) {
            moreRow.setVisible(hasMore);
            moreRow.setManaged(hasMore);
        }
    }

    /** 当前正在配置的游戏版本（null 表示未进入下载配置页） */
    private VersionDownloadService.VersionInfo currentVersionConfig;

    /** 进入某个版本的下载配置页（主窗口内整页，左键单击版本行触发） */
    private void openVersionConfigPage(VersionDownloadService.VersionInfo v) {
        currentVersionConfig = v;
        // 仍属于「下载」标签，保持顶部导航高亮与侧边栏隐藏状态一致
        host.selectDownloadTab();
        host.hideSidebar();
        host.switchToPage("downloadVersionConfig");
    }

    /** 下载配置页「返回」：回到版本选择列表 */
    private void goBackFromVersionConfig() {
        host.switchToPage("download");
    }

    /**
     * 版本下载配置页：版本信息 + 版本文件夹名 + 组件选择 + 开始安装。
     */
    public Node buildVersionConfig() {
        VersionDownloadService.VersionInfo v = currentVersionConfig;

        VBox root = new VBox(12);
        root.setPadding(new Insets(8, 8, 8, 0));
        VBox.setVgrow(root, Priority.ALWAYS);

        Button backBtn = AppIcons.button("back", "返回");
        backBtn.getStyleClass().add("back-btn");
        backBtn.setOnAction(e -> goBackFromVersionConfig());
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        Label titleLabel = new Label(v == null ? "下载配置" : "下载配置 - " + v.getId());
        titleLabel.getStyleClass().add("home-card-title");
        header.getChildren().addAll(backBtn, titleLabel);
        root.getChildren().add(header);

        if (v == null) {
            root.getChildren().add(versionHint("请先在「游戏版本」里左键单击一个版本。"));
            return root;
        }

        // 选中的组件由配置区写入、底部按钮读取（按钮固定页面底部，不随内容滚动）
        final VersionDownloadService.LoaderVersion[] selection = {null};

        Node config = buildVersionInstallConfig(v, selection);

        // 配置区放进滚动容器：加载器多、展开的版本列表长时可以整页向下滚
        ScrollPane scroll = new ScrollPane(config);
        PageKit.configureScrollPane(host, scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        root.getChildren().add(scroll);

        // 底部固定的「开始下载」（对应 PCL2 悬浮在底部的胶囊按钮）
        HBox footer = new HBox(10);
        footer.setAlignment(Pos.CENTER);
        footer.setPadding(new Insets(6, 0, 4, 0));
        Button installBtn = AppIcons.button("download", "开始下载");
        installBtn.getStyleClass().add("btn-primary");
        installBtn.setStyle("-fx-padding: 8 34; -fx-font-size: 14px; -fx-background-radius: 22;");
        installBtn.setOnAction(e -> installGameVersion(v, selection[0], currentVersionConfigDirName));
        footer.getChildren().add(installBtn);
        root.getChildren().add(footer);
        return root;
    }

    /** 版本文件夹名输入框的当前内容（由配置区写入，底部「开始下载」读取） */
    private String currentVersionConfigDirName;

    /**
     * 在 {@code <gameDir>/versions} 下找一个未被占用的版本文件夹名。
     *
     * <p>直接沿用版本号做文件夹名时，重复安装同一个版本会覆盖掉原有版本
     * （包括自定义过的加载器/设置）；这里发现同名就依次尝试 {@code -2 / -3 …}。
     */
    private static String suggestFreeVersionDir(String gameDir, String base) {
        String name = (base == null || base.isBlank()) ? "version" : base.trim();
        try {
            Path versionsDir = Paths.get(gameDir, "versions");
            if (!Files.exists(versionsDir.resolve(name))) return name;
            for (int i = 2; i <= 99; i++) {
                String candidate = name + "-" + i;
                if (!Files.exists(versionsDir.resolve(candidate))) return candidate;
            }
        } catch (Exception ignored) {
        }
        return name + "-" + System.currentTimeMillis();
    }

    /**
     * 版本的安装配置区：版本号与类型信息、可自定义的版本文件夹名、组件列表
     * （原版 / Forge / NeoForge / Fabric / OptiFine / … 一行一个，点开展开该加载器的版本列表）。
     *
     * <p><b>加载器行进入页面就全部显示</b>（用固定名单先渲染），各加载器的版本列表随后异步查询填充，
     * 查询期间行上显示「正在查询…」；这样切到本页不会有"空白等着"的感觉。
     *
     * @param selectionOut 输出参数：当前选中的加载器版本（null = 原版），供底部按钮读取
     */
    private Node buildVersionInstallConfig(VersionDownloadService.VersionInfo v,
                                           VersionDownloadService.LoaderVersion[] selectionOut) {
        VBox body = new VBox(12);

        // ==================== 版本信息 ====================
        HBox head = new HBox(12);
        head.setAlignment(Pos.CENTER_LEFT);
        StackPane iconBox = new StackPane(IconService.versionIconView(v.getCategory(), 40));
        iconBox.setMinSize(48, 48);
        iconBox.setPrefSize(48, 48);
        iconBox.setMaxSize(48, 48);
        VBox headInfo = new VBox(4);
        Label idLabel = new Label(v.getId());
        idLabel.getStyleClass().add("config-version-title");
        Label metaLabel = new Label(categoryLabel(v.getCategory()) + "  ·  "
                + VersionDownloadService.formatDate(v.getReleaseTime()));
        metaLabel.getStyleClass().add("config-version-meta");
        headInfo.getChildren().addAll(idLabel, metaLabel);
        head.getChildren().addAll(iconBox, headInfo);
        body.getChildren().add(head);

        // ==================== 版本文件夹名 ====================
        HBox dirRow = new HBox(8);
        dirRow.setAlignment(Pos.CENTER_LEFT);
        Label dirLabel = new Label("版本文件夹名:");
        dirLabel.getStyleClass().add("config-field-label");
        // 目录已存在时自动改用「版本号-2 / -3…」，避免和已有版本撞名覆盖
        String configGameDir = host.config().getOrDefault("GameDir", ".minecraft");
        String suggestedDir = suggestFreeVersionDir(configGameDir, v.getId());
        TextField dirField = new TextField(suggestedDir);
        dirField.setPromptText("留空使用版本号");
        dirField.getStyleClass().add("input-field");
        HBox.setHgrow(dirField, Priority.ALWAYS);
        Tooltip.install(dirField, new Tooltip("自定义保存游戏文件的文件夹名，如 " + v.getId() + "-联机版"));
        currentVersionConfigDirName = suggestedDir;
        dirField.textProperty().addListener((o, ov, nv) -> currentVersionConfigDirName = nv);
        dirRow.getChildren().addAll(dirLabel, dirField);
        body.getChildren().add(dirRow);

        if (!suggestedDir.equals(v.getId())) {
            Label dupHint = new Label("已存在同名版本文件夹，自动改为「" + suggestedDir
                    + "」以免覆盖原有版本（可手动修改）");
            dupHint.setWrapText(true);
            dupHint.setStyle("-fx-font-size: 11px; -fx-text-fill: #b45309;");
            body.getChildren().add(dupHint);
        }

        body.getChildren().add(new Separator());

        // ==================== 组件列表（PCL2 式：一行一个加载器，点开展开版本列表） ====================
        Label sectionTitle = new Label("选择要安装的组件");
        sectionTitle.getStyleClass().add("config-section-title");

        VBox componentList = new VBox(6);
        Label componentHint = new Label("正在查询各加载器的可用版本...");
        componentHint.getStyleClass().add("config-hint");
        body.getChildren().addAll(sectionTitle, componentHint, componentList);

        // 选中状态：null = 原版；expanded = 当前展开的加载器名（同时只展开一个，和 PCL2 一致）
        final VersionDownloadService.LoaderVersion[] selected = {null};
        final String[] expanded = {null};
        // 该 MC 版本下已安装的加载器（用于把状态显示成「已安装」而不是「可以添加」）
        final String[] installedLoader = {host.detectLoaderForVersion(
                host.config().getOrDefault("GameDir", ".minecraft"), v.getId())};
        /** 加载器名 → 该加载器的可用版本；null 表示还没查询回来 */
        final Map<String, List<VersionDownloadService.LoaderVersion>> groups = new LinkedHashMap<>();
        final boolean[] queried = {false};

        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            componentList.getChildren().clear();
            selectionOut[0] = selected[0];

            // ① 原版行：始终可选、可点，没有可展开的版本列表。
            //    versionCount 传 -1：表示「可用但没有版本列表」，区别于 0（该加载器无可用版本 → 置灰不可点）
            componentList.getChildren().add(buildComponentRow(
                    "原版（Vanilla）", -1, selected[0] == null ? "已选择" : "可以直接下载",
                    selected[0] == null, false, false, () -> {
                        selected[0] = null;
                        expanded[0] = null;
                        render[0].run();
                    }));

            // ② 加载器行：查询未回来前先按固定名单占位，回来后用实际结果替换
            List<String> names = queried[0]
                    ? new ArrayList<>(groups.keySet())
                    : new ArrayList<>(KNOWN_LOADERS);

            for (String name : names) {
                List<VersionDownloadService.LoaderVersion> versions = groups.get(name);
                boolean known = versions != null && !versions.isEmpty();

                // 状态文案：已选 → 版本号；已安装 → 已安装；未查到 → 正在查询；无用版本 → 无；否则 → 可以添加
                String status;
                boolean isChosen = selected[0] != null && name.equals(selected[0].getLoaderName());
                if (isChosen) {
                    status = selected[0].getVersion();
                } else if (!queried[0]) {
                    status = "正在查询…";
                } else if (VersionMatcher.matchesInstalledLoader(installedLoader[0], name)) {
                    status = "已安装";
                } else if (known) {
                    status = "可以添加";
                } else {
                    status = "无";
                }

                // versionCount 传 -1 表示「数量未知（查询中）」，此时也允许点开看进度
                int count = known ? versions.size() : (queried[0] ? 0 : -1);
                final String loaderName = name;
                final boolean isExpanded = loaderName.equals(expanded[0]);
                VBox row = buildComponentRow(loaderName, count, status, isChosen, true, isExpanded, () -> {
                    expanded[0] = loaderName.equals(expanded[0]) ? null : loaderName;
                    render[0].run();
                });
                componentList.getChildren().add(row);

                if (isExpanded) {
                    if (known) {
                        componentList.getChildren().add(buildLoaderVersionsPanel(
                                loaderName, versions, selected[0], choice -> {
                                    selected[0] = choice;
                                    expanded[0] = null;
                                    render[0].run();
                                }));
                    } else {
                        Label waiting = new Label("正在查询 " + loaderName + " 的版本列表...");
                        waiting.getStyleClass().addAll("config-hint", "loader-version-item");
                        componentList.getChildren().add(waiting);
                    }
                }
            }

            if (queried[0] && groups.isEmpty()) {
                Label none = new Label("该版本暂无可用加载器，可直接下载原版");
                none.getStyleClass().add("config-hint");
                componentList.getChildren().add(none);
            }

            // 选中 Fabric 时提醒装 Fabric API：Fabric 加载器本身不含任何 API，
            // 绝大多数 Fabric 模组（Sodium、Iris、REI…）缺少它会直接启动崩溃
            boolean fabricChosen = selected[0] != null && "Fabric".equalsIgnoreCase(selected[0].getLoaderName());
            boolean fabricApiKnown = groups.containsKey("FabricAPI") && !groups.get("FabricAPI").isEmpty();
            if (fabricChosen && fabricApiKnown) {
                Label apiHint = new Label("Fabric 加载器本身不含任何 API，绝大多数 Fabric 模组需要额外安装 "
                        + "Fabric API 才能正常运行 —— 可在上方「FabricAPI」一行单独选版本安装。");
                apiHint.setWrapText(true);
                apiHint.setStyle("-fx-font-size: 11px; -fx-text-fill: #b45309; -fx-padding: 2 0 0 2;");
                componentList.getChildren().add(apiHint);
            }
        };

        // 先渲染一次：加载器行（含原版）立刻出现，版本列表随后异步填充
        render[0].run();

        // ==================== 异步查询组件清单 ====================
        VersionDownloadService.fetchAllLoaderVersionsListAsync(v.getId())
                .thenAcceptAsync(list -> {
                    // 按加载器分组并按固定顺序排列（与原版启动器的习惯一致）
                    Map<String, List<VersionDownloadService.LoaderVersion>> byName = new LinkedHashMap<>();
                    if (list != null) {
                        for (VersionDownloadService.LoaderVersion lv : list) {
                            byName.computeIfAbsent(lv.getLoaderName(), k -> new ArrayList<>()).add(lv);
                        }
                    }
                    Map<String, List<VersionDownloadService.LoaderVersion>> ordered = new LinkedHashMap<>();
                    // 先放固定名单里的加载器（即便这次没查到也保留一行，显示为「无」）
                    for (String want : KNOWN_LOADERS) {
                        List<VersionDownloadService.LoaderVersion> got = byName.remove(want);
                        ordered.put(want, got == null ? List.of() : got);
                    }
                    ordered.putAll(byName);   // 其余加载器（Quilt / OptiFine / FabricAPI…）追加在后面
                    groups.clear();
                    groups.putAll(ordered);
                    queried[0] = true;

                    long withVersions = ordered.values().stream().filter(l -> !l.isEmpty()).count();
                    componentHint.setText(withVersions == 0
                            ? "该版本暂无可用加载器，可直接下载原版"
                            : "选择一个加载器展开版本列表，或直接下载原版");
                    render[0].run();
                }, Platform::runLater)
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        componentHint.setText("组件查询失败（" + ex.getMessage() + "），仍可直接下载原版");
                        componentHint.setStyle("-fx-text-fill: #ef4444;");
                        queried[0] = true;
                        render[0].run();
                    });
                    return null;
                });

        return body;
    }

    /**
     * 进入页面时先渲染出来的加载器名单（不等查询结果）。
     * <p>顺序与原版启动器的习惯一致：Forge / NeoForge / Fabric / OptiFine，
     * 查询结果里多出来的加载器（Quilt、FabricAPI…）再追加在后面。
     */
    private static final List<String> KNOWN_LOADERS =
            List.of("Forge", "NeoForge", "Fabric", "OptiFine");


    /** 是否在界面上忽略 Quilt 加载器（高级设置的开关） */
    private boolean ignoreQuilt() {
        return host.config() != null && Boolean.parseBoolean(
                host.config().getOrDefault("IgnoreQuiltLoader", "false"));
    }

    /** 加载器筛选下拉的选项（开了「忽略 Quilt」就不列 Quilt） */
    private List<String> loaderFilterOptions() {
        return ignoreQuilt()
                ? List.of("全部", "Fabric", "Forge", "NeoForge")
                : List.of("全部", "Fabric", "Forge", "NeoForge", "Quilt");
    }

    /** 过滤掉要忽略的加载器（用于版本行的「加载器: …」显示） */
    private List<ModLoaderType> visibleLoaders(List<ModLoaderType> loaders) {
        if (loaders == null) return List.of();
        if (!ignoreQuilt()) return loaders;
        return loaders.stream()
                .filter(l -> l != ModLoaderType.QUILT)
                .collect(Collectors.toList());
    }
    /**
     * 组件列表里的一行（PCL2 风格）：左侧加载器名，右侧状态文案 + 折叠箭头。
     *
     * @param versionCount 可用版本数，0 表示该加载器没有可用版本（置灰、不可展开）
     * @param chosen       是否是当前选中的那一项
     * @param expandable   false 时右侧不显示箭头（原版行）
     * @param expanded     该行当前是否处于展开状态（决定箭头方向）
     */
    private VBox buildComponentRow(String name, int versionCount, String status, boolean chosen,
                                   boolean expandable, boolean expanded, Runnable onClick) {
        // versionCount == -1 表示「数量未知（正在查询）」，此时也允许点开看进度
        boolean usable = versionCount != 0;

        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().addAll("settings-card", "component-row");
        if (usable) row.getStyleClass().add("usable"); else row.getStyleClass().add("disabled");
        if (chosen) row.getStyleClass().add("chosen");

        // 加载器图标（原版用草方块）
        String iconName = "原版（Vanilla）".equals(name) ? null : name;
        StackPane iconBox = new StackPane("原版（Vanilla）".equals(name)
                ? IconService.versionIconView(VersionDownloadService.VersionCategory.RELEASE, 26)
                : IconService.loaderIconView(iconName, 26));
        iconBox.setMinSize(34, 34);
        iconBox.setPrefSize(34, 34);
        iconBox.setMaxSize(34, 34);
        if (!usable) iconBox.setOpacity(0.45);

        Label nameLabel = new Label(name);
        nameLabel.getStyleClass().add("component-name");
        HBox.setHgrow(nameLabel, Priority.ALWAYS);

        Label statusLabel = new Label(status == null ? "" : status);
        statusLabel.getStyleClass().add("component-status");
        if (chosen) statusLabel.getStyleClass().add("chosen");
        row.getChildren().addAll(iconBox, nameLabel, statusLabel);

        if (expandable && usable) {
            Label chevron = new Label(expanded ? "︿" : "﹀");
            chevron.getStyleClass().add("component-chevron");
            row.getChildren().add(chevron);
        }

        if (usable) {
            row.setOnMouseClicked(e -> {
                if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) onClick.run();
            });
        }
        return new VBox(row);
    }

    /**
     * 展开后的版本列表（PCL2 风格）：顶部「最新版」推荐项，下面「全部版本 (N)」列表。
     * <p>列表本身不再套内层滚动条 —— 整个配置页是一个滚动区，靠页面向下滑动查看，
     * 避免出现「页面里再套一个滚动区」的双层滚动。
     * <p>点击任意一项即选中该加载器版本，并收起展开区。
     */
    private VBox buildLoaderVersionsPanel(String loaderName,
                                          List<VersionDownloadService.LoaderVersion> versions,
                                          VersionDownloadService.LoaderVersion current,
                                          java.util.function.Consumer<VersionDownloadService.LoaderVersion> onPick) {
        VBox panel = new VBox(6);
        panel.getStyleClass().add("settings-card");
        panel.setStyle("-fx-padding: 12 16; -fx-translate-y: 0; -fx-background-color: rgba(127,127,127,0.07);");

        // 最新版推荐
        VersionDownloadService.LoaderVersion newest = versions.get(0);
        panel.getChildren().add(buildLoaderVersionItem(loaderName, newest, "最新版", current, onPick));
        panel.getChildren().add(new Separator());

        Label allTitle = new Label("全部版本 (" + versions.size() + ")");
        allTitle.getStyleClass().add("config-panel-title");
        panel.getChildren().add(allTitle);

        VBox list = new VBox(2);
        for (VersionDownloadService.LoaderVersion lv : versions) {
            if (lv == newest) continue;   // 最新版已经在上面单独列出
            list.getChildren().add(buildLoaderVersionItem(loaderName, lv, "", current, onPick));
        }
        if (list.getChildren().isEmpty()) {
            Label onlyOne = new Label("该加载器当前只有一个版本");
            onlyOne.getStyleClass().add("config-hint");
            list.getChildren().add(onlyOne);
        }
        panel.getChildren().add(list);
        return panel;
    }

    /** 展开区里的单个版本条目：加载器图标 + 版本号 + 说明，选中时高亮 */
    private HBox buildLoaderVersionItem(String loaderName,
                                        VersionDownloadService.LoaderVersion lv, String caption,
                                        VersionDownloadService.LoaderVersion current,
                                        java.util.function.Consumer<VersionDownloadService.LoaderVersion> onPick) {
        boolean chosen = current != null && lv.getVersion() != null
                && lv.getVersion().equals(current.getVersion())
                && String.valueOf(lv.getLoaderName()).equals(String.valueOf(current.getLoaderName()));

        HBox item = new HBox(10);
        item.setAlignment(Pos.CENTER_LEFT);
        item.getStyleClass().add("loader-version-item");
        if (chosen) item.getStyleClass().add("chosen");

        StackPane iconBox = new StackPane(IconService.loaderIconView(loaderName, 22));
        iconBox.setMinSize(26, 26);
        iconBox.setPrefSize(26, 26);
        iconBox.setMaxSize(26, 26);

        VBox info = new VBox(1);
        Label ver = new Label(lv.getVersion() == null ? "" : lv.getVersion());
        ver.getStyleClass().add("loader-version-name");
        info.getChildren().add(ver);
        if (caption != null && !caption.isBlank()) {
            Label cap = new Label(caption);
            cap.getStyleClass().add("loader-version-caption");
            info.getChildren().add(cap);
        }
        HBox.setHgrow(info, Priority.ALWAYS);
        item.getChildren().addAll(iconBox, info);

        item.setOnMouseClicked(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) onPick.accept(lv);
        });
        return item;
    }

    /**
     * 安装游戏版本（原版 + 可选加载器），全程走统一下载进度弹窗。
     *
     * <p>自定义文件夹名时，{@link InstallEngine} 会同时把原版基底保留在标准版本目录，
     * 以便加载器（Fabric 等）能按标准版本号继承，这一点与改造前一致。
     */
    private void installGameVersion(VersionDownloadService.VersionInfo v,
                                    VersionDownloadService.LoaderVersion loader, String folderName) {
        String gameDir = host.config().getOrDefault("GameDir", ".minecraft");
        // 校验并规范化自定义文件夹名：含非法字符或为空时回退版本号
        String dirName = (folderName == null || folderName.isBlank()
                || folderName.matches(".*[\\\\/:*?\"<>|].*")) ? v.getId() : folderName.trim();
        String loaderName = loader != null ? loader.getLoaderName() : "原版";
        final VersionDownloadService.LoaderVersion finalLoader = loader;
        final String finalDirName = dirName;
        DownloadTaskCard dialog = downloads.open("安装 " + dirName
                + (loader != null ? " + " + loaderName + " " + loader.getVersion() : ""));
        // 失败后可一键重试
        dialog.setRetryAction(() -> installGameVersion(v, finalLoader, finalDirName));
        dialog.stage("准备安装...");
        dialog.logLine("目标版本目录: " + gameDir + "/versions/" + dirName, true);
        if (loader != null) {
            dialog.logLine("加载器: " + loaderName + " " + loader.getVersion(), true);
            if (loader.getDownloadUrl() != null && !loader.getDownloadUrl().isBlank()) {
                dialog.logLine("加载器安装器: " + loader.getDownloadUrl(), true);
            }
        }
        dialog.beginFile("客户端与依赖库");

        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            try {
                boolean ok = InstallEngine.installVanilla(v, gameDir, VersionDownloadService.getDownloadProvider(),
                        (pct, msg) -> {
                            checkDownloadCancelled(dialog);
                            // pct < 0 表示「只记一行日志」，不动进度条
                            if (pct >= 0) dialog.progress(pct);
                            dialog.message(msg);
                            // 把每个阶段也写进文件列表，失败时能看出卡在哪一步
                            if (msg != null && !msg.isBlank()) dialog.logLine(msg, true);
                        }, dirName);
                if (!ok) throw new IOException("原版安装失败");
                dialog.finishFile(true);

                if (loader == null) {
                    initIsolationAfterInstall(gameDir, dirName, dialog);
                    dialog.finish(true, dirName + " 安装完成", null);
                    Platform.runLater(() -> host.ui().toast(dirName + " 安装完成"));
                    return;
                }

                dialog.stage("安装 " + loaderName + " " + loader.getVersion() + "...");
                dialog.beginFile(loaderName + " " + loader.getVersion());
                java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                final boolean[] okRef = {false};
                final String[] errRef = {null};
                UIGeneralControlClass.installLoaderAsync(v.getId(),
                        loader.getLoaderName().toLowerCase(java.util.Locale.ROOT),
                        loader.getVersion(), gameDir, host.config().getOrDefault("JavaPath", "java"),
                        (pct, msg) -> {
                            // pct < 0 表示「只记一行日志」，不动进度条
                            if (pct >= 0) dialog.progress(pct);
                            dialog.message(msg);
                            // 安装器的每一行输出都记进文件列表：失败时真正的原因就在这里，
                            // 以前只显示在会一闪而过的消息行上，等于没日志
                            if (msg != null && !msg.isBlank()) dialog.logLine(msg, true);
                        },
                        new UIGeneralControlClass.ResultCallback<Boolean>() {
                            @Override
                            public void onSuccess(Boolean result) {
                                okRef[0] = result != null && result;
                                latch.countDown();
                            }

                            @Override
                            public void onError(String error) {
                                errRef[0] = error;
                                latch.countDown();
                            }
                        }, dirName);
                if (!latch.await(30, java.util.concurrent.TimeUnit.MINUTES)) {
                    throw new IOException(loaderName + " 安装超时");
                }
                dialog.finishFile(okRef[0]);
                if (!okRef[0]) {
                    throw new IOException(loaderName + " 安装失败"
                            + (errRef[0] != null ? ": " + errRef[0] : ""));
                }
                initIsolationAfterInstall(gameDir, dirName, dialog);
                dialog.finish(true, dirName + " + " + loaderName + " 安装完成", null);
                Platform.runLater(() -> host.ui().toast(dirName + " + " + loaderName + " 安装完成"));
            } catch (Exception ex) {
                boolean cancelled = HttpDownloadEngine.CANCELLED_MESSAGE.equals(ex.getMessage());
                // 失败原因同时写进文件列表，卡片上能直接看到，不用去翻日志文件
                dialog.logLine("失败: " + ex, false);
                if (ex.getCause() != null) dialog.logLine("原因: " + ex.getCause(), false);
                dialog.finish(false, cancelled ? "已取消安装" : "安装失败: " + ex.getMessage(), null);
            }
        });
    }

    /**
     * 安装完成后按「版本隔离」开关预建 versions/&lt;dirName&gt;/ 下的隔离目录树
     * （mods/saves/config 等），对齐 PCL2：隔离环境开箱可见，可先放模组存档再启动。
     */
    private void initIsolationAfterInstall(String gameDir, String dirName, DownloadTaskCard dialog) {
        if (!"true".equalsIgnoreCase(host.config().getOrDefault("VersionIsolation", "false"))) return;
        GameDirInitializer.InitResult init =
                GameDirInitializer.initializeVersionIsolation(Path.of(gameDir), dirName);
        if (init.created() > 0) {
            dialog.logLine("已初始化版本隔离目录: versions/" + dirName + "（" + init.created() + " 个子目录）", true);
        }
        if (!init.ok()) dialog.logLine(init.summary(), false);
    }

    // ==================== 下载中心：远程内容（Modrinth / CurseForge） ====================

    /**
     * 下载中心的数据源选项：显示名 + 对应的远程仓库实现。
     * 一个面板可挂多个数据源（如「模组」同时支持 Modrinth 与 CurseForge），
     * 由工具条上的「数据源」下拉框切换。
     */
    private record ModSource(String label, RemoteModRepository repo) {
    }

    /** 下载页每页条目数：与 VersePc2 模组页保持一致（15 条/页） */
    private static final int REMOTE_PAGE_SIZE = 15;

    /**
     * 下载页「分类」下拉的一个选项。
     * <p>两个数据源的分类体系不同：Modrinth 用字符串名（{@code technology}），
     * CurseForge 用数字 ID，因此同一个选项同时携带两种表示，切换来源时按需取用。
     */
    private record ModCategoryOption(String label, String modrinthName, int curseForgeId) {
        static ModCategoryOption all() {
            return new ModCategoryOption("全部", "", -1);
        }
    }

    /** 下载页「排序」下拉的显示名（与 VersePc2 模组页一致） */
    private static final String[] SORT_LABELS = {"相关度", "下载量", "更新时间", "最新发布"};

    /** 排序显示名 → 排序枚举；「相关度」返回 null，表示不传 index 交给后端相关度排序 */
    private static RemoteModRepository.SortType sortTypeOf(String label) {
        if (label == null) return null;
        return switch (label) {
            case "下载量" -> RemoteModRepository.SortType.TOTAL_DOWNLOADS;
            case "更新时间" -> RemoteModRepository.SortType.LAST_UPDATED;
            case "最新发布" -> RemoteModRepository.SortType.DATE_CREATED;
            default -> null;
        };
    }

    /** 取下拉框选中项；未选中或为「全部」占位时返回空串 */
    private static String comboValue(ComboBox<String> combo) {
        String v = combo == null ? null : combo.getSelectionModel().getSelectedItem();
        return v == null || "全部".equals(v) ? "" : v;
    }

    /** 从「加载器」下拉的显示名反查加载器键（{@code Fabric} → {@code fabric}） */
    private static String loaderKeyOf(String label) {
        if (label == null || label.isBlank() || "全部".equals(label)) return "";
        for (ModCategories.LoaderOption o : ModCategories.loaderOptions()) {
            if (o.label().equalsIgnoreCase(label)) return o.key();
        }
        return "";
    }

    /**
     * 版本的加载器是否命中筛选。
     * <p>{@link ModLoaderType} 的枚举名与加载器键并非一一对应
     * （{@code NEO_FORGED} 对应键 {@code neoforge}），这里统一取前 4 个字符比较即可区分
     * fabric / forge / neoforge / quilt 四者。
     */
    private static boolean matchesLoaderFilter(RemoteMod.Version v, String loaderLabel) {
        String key = loaderKeyOf(loaderLabel);
        if (key.isEmpty()) return true;
        if (v.getLoaders() == null) return false;
        String prefix = key.substring(0, Math.min(4, key.length()));
        return v.getLoaders().stream()
                .anyMatch(l -> l.name().toLowerCase(java.util.Locale.ROOT).startsWith(prefix));
    }

    /** 组装一个筛选维度的小组合：标签 + 下拉框 */
    private HBox buildFilterGroup(String labelText, ComboBox<String> combo, double width) {
        Label label = new Label(labelText);
        label.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-dim;");
        // 不设最小宽度时，横向空间不足会把标签压成省略号（「来源」→「…」）
        label.setMinWidth(Region.USE_PREF_SIZE);
        combo.setPrefWidth(width);
        combo.setMinWidth(Math.min(width, 90));
        // 选项多时把候选列表放高一点，默认只露几行会让人以为「显示不全」
        combo.setVisibleRowCount(16);
        combo.getStyleClass().add("select-field");
        HBox box = new HBox(6, label, combo);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setMinWidth(Region.USE_PREF_SIZE);
        return box;
    }

    /**
     * 远程资源面板（整合包/模组/资源包/光影包/数据包共用）。
     *
     * <p>界面结构对齐 VersePc2 的 {@code page-mods}：<b>搜索栏</b>（关键词 + 搜索 + 刷新）、
     * <b>筛选行</b>（来源 / 加载器 / 游戏版本 / 分类 / 排序）、<b>行式结果列表</b>
     * （图标 + 名称与来源徽章 + 描述 + 下载量·作者·分类 + 详情/安装）、<b>分页器</b>。
     * 视觉上沿用本项目的卡片与按钮样式类，不另起一套配色。
     *
     * @param panelTitle 面板标题
     * @param type       资源类型（决定安装目标目录）
     * @param sources    可选数据源列表
     */
    private Node buildContentPanel(String panelTitle, RemoteModRepository.Type type,
                                   List<ModSource> sources) {
        return buildContentPanel(panelTitle, type, sources, new Node[0]);
    }

    /**
     * 同上。
     *
     * @param extraToolbarRows 额外排进工具条卡片的行（排在搜索栏之前），
     *                         例如整合包面板的「导入本地整合包」入口
     */
    private Node buildContentPanel(String panelTitle, RemoteModRepository.Type type,
                                   List<ModSource> sources, Node... extraToolbarRows) {
        VBox root = new VBox(12);
        root.setPadding(new Insets(0, 4, 0, 0));

        // 模组/整合包数据源统一包一层「中文搜索」装饰器：
        // 中文查询会被翻译成英文检索词再查后端，并用中文名对结果重排；
        // 资源包/光影包/世界没有中文名字典，wrap() 会原样返回，行为与改造前完全一致。
        List<ModSource> localizedSources = new ArrayList<>(sources.size());
        for (ModSource s : sources) {
            localizedSources.add(new ModSource(s.label(), LocalizedRemoteModRepository.wrap(s.repo())));
        }
        final List<ModSource> effectiveSources = localizedSources;
        final boolean supportsChinese = LocalizedRemoteModRepository.supportsChinese(type);
        final String targetGameDir = host.config().getOrDefault("GameDir", ".minecraft");
        final String currentVersion = host.config().getOrDefault("Version", "");

        Label titleLabel = new Label(panelTitle);
        titleLabel.getStyleClass().add("content-title");
        root.getChildren().add(titleLabel);

        // ==================== ① 搜索栏 ====================
        TextField searchField = new TextField();
        searchField.setPromptText(supportsChinese
                ? "搜索" + panelTitle + "（支持中英文，如「机械动力」）"
                : "搜索" + panelTitle + "...");
        searchField.getStyleClass().add("input-field");
        if (supportsChinese) {
            searchField.setTooltip(new Tooltip("支持中文名搜索：输入「机械动力」会自动翻译为 Create 去检索，"
                    + "并优先展示收录了中文名的结果"));
        }
        Button searchBtn = AppIcons.button("search", "搜索");
        searchBtn.getStyleClass().add("btn-primary");
        Button refreshBtn = AppIcons.button("refresh", "刷新");
        refreshBtn.getStyleClass().add("btn-primary");
        HBox searchBar = new HBox(8, searchField, searchBtn, refreshBtn);
        searchBar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(searchField, Priority.ALWAYS);

        // ==================== ② 筛选行 ====================
        ComboBox<String> sourceCombo = new ComboBox<>();
        sourceCombo.getItems().add("全部");
        for (ModSource s : effectiveSources) sourceCombo.getItems().add(s.label());
        // 只有一个数据源时去掉「全部」（没有可选之分），否则默认「全部」跨源搜索
        if (effectiveSources.size() <= 1) {
            sourceCombo.getItems().remove("全部");
        }
        sourceCombo.getSelectionModel().select(0);

        ComboBox<String> loaderCombo = new ComboBox<>();
        for (ModCategories.LoaderOption o : ModCategories.loaderOptions()) loaderCombo.getItems().add(o.label());
        loaderCombo.getSelectionModel().select(0);

        ComboBox<String> versionCombo = new ComboBox<>();
        versionCombo.getItems().add("全部");
        versionCombo.getSelectionModel().select(0);

        ComboBox<String> categoryCombo = new ComboBox<>();
        categoryCombo.getItems().add("全部");
        categoryCombo.getSelectionModel().select(0);

        ComboBox<String> sortCombo = new ComboBox<>();
        sortCombo.getItems().addAll(SORT_LABELS);
        sortCombo.getSelectionModel().select(0);

        // 加载器/分类对资源包、光影包、世界没有意义，隐藏以免误导
        boolean loaderRelevant = type == RemoteModRepository.Type.MOD || type == RemoteModRepository.Type.MODPACK;
        // 用 FlowPane 而不是 HBox：窗口窄时筛选组会自动换到下一行，
        // 而不是把整页撑得比视口还宽（那会把行卡片右侧的「详情/安装」挤到视口外面去）
        FlowPane filterRow = new FlowPane(12, 8);
        filterRow.setAlignment(Pos.CENTER_LEFT);
        filterRow.getChildren().addAll(
                buildFilterGroup("来源", sourceCombo, 110),
                buildFilterGroup("游戏版本", versionCombo, 110));
        if (loaderRelevant) {
            filterRow.getChildren().add(buildFilterGroup("加载器", loaderCombo, 110));
        }
        filterRow.getChildren().addAll(
                buildFilterGroup("分类", categoryCombo, 130),
                buildFilterGroup("排序", sortCombo, 110));

        // 搜索栏 + 筛选行（+ 额外的工具行）装进同一张卡片（标题留在卡片外，与其它页面同一套版式）
        Node[] toolbarRows = new Node[extraToolbarRows.length + 2];
        System.arraycopy(extraToolbarRows, 0, toolbarRows, 0, extraToolbarRows.length);
        toolbarRows[extraToolbarRows.length] = searchBar;
        toolbarRows[extraToolbarRows.length + 1] = filterRow;
        root.getChildren().add(PageKit.toolbarCard(toolbarRows));

        // ==================== ③ 结果列表 + 分页 ====================
        VBox listContainer = new VBox(6);
        root.getChildren().add(listContainer);

        HBox pager = new HBox(10);
        pager.setAlignment(Pos.CENTER);
        Button prevBtn = AppIcons.button("arrow-left", "上一页");
        prevBtn.getStyleClass().add("btn-primary");
        Button nextBtn = AppIcons.button("arrow-right", "下一页 ");
        nextBtn.getStyleClass().add("btn-primary");
        Label pageLabel = new Label("第 1 页");
        pageLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-dim; -fx-padding: 0 8;");
        pager.getChildren().addAll(prevBtn, pageLabel, nextBtn);
        root.getChildren().add(pager);

        // ==================== 状态 ====================
        final ModSearchQuery query = new ModSearchQuery().setPageSize(REMOTE_PAGE_SIZE);
        final int[] pageOffset = {0};
        /**
         * 当前已选中的数据源。默认与来源下拉框的初始选中项保持一致：
         * 有多个数据源时下拉默认选中「全部」，此时应同时查询全部而不是只查第一个。
         */
        final List<ModSource>[] activeSelection = new List[]{
                effectiveSources.size() > 1 ? List.copyOf(effectiveSources) : List.of(effectiveSources.get(0))};
        /** 分类选项（按来源异步加载后缓存） */
        final List<ModCategoryOption>[] categoryOptions = new List[]{List.of(ModCategoryOption.all())};

        // 游戏版本下拉：异步用版本清单填充（只取正式版前 30 个，与 VersePc2 一致）
        loadDownloadVersionOptions(versionCombo);

        // 分类下拉：按当前来源异步加载
        Runnable reloadCategories = () -> loadCategoryOptions(sourceCombo, effectiveSources, type,
                categoryCombo, categoryOptions);

        // ==================== 搜索执行 ====================
        /** 点「刷新」时置位，用于绕过内存缓存强制重新拉取 */
        final boolean[] forceRefresh = {false};

        // 失败卡片的「重新加载」需要引用 load 自身：用单元素数组桥接（lambda 自引用在初始化中非法）
        final Runnable[] loadRef = new Runnable[1];
        Runnable load = () -> {
            query.setQuery(searchField.getText())
                    .setGameVersion(comboValue(versionCombo))
                    .setLoader(loaderRelevant ? loaderKeyOf(comboValue(loaderCombo)) : "")
                    .setSortType(sortTypeOf(sortCombo.getSelectionModel().getSelectedItem()))
                    .setPageOffset(pageOffset[0]);
            int catIndex = categoryCombo.getSelectionModel().getSelectedIndex();
            ModCategoryOption cat = (catIndex > 0 && catIndex < categoryOptions[0].size())
                    ? categoryOptions[0].get(catIndex) : ModCategoryOption.all();
            query.setCategory(cat.modrinthName()).setCategoryId(cat.curseForgeId());

            final ModSearchQuery snapshot = query.copy();
            final List<ModSource> repos = activeSelection[0];
            final String cacheKey = RemoteCacheService.searchCacheKey(panelTitle, snapshot,
                    repos.stream().map(ModSource::label).collect(Collectors.toList()));
            final boolean force = forceRefresh[0];
            forceRefresh[0] = false;

            // 内存缓存命中：直接渲染，不再请求网络（翻回上一页、切回原筛选条件时秒开）
            if (!force) {
                SearchCacheEntry cached = remoteCache.getSearch(cacheKey);
                if (cached != null) {
                    renderModHits(listContainer, pageLabel, prevBtn, nextBtn, pageOffset[0],
                            cached.hits(), cached.totalPages(), repos.size() > 1, type, panelTitle,
                            targetGameDir, currentVersion);
                    return;
                }
            }

            listContainer.getChildren().clear();
            // 加载中用骨架屏微光占位，而不是一行「正在加载...」文字
            listContainer.getChildren().add(SkeletonFactory.buildListSkeleton(4));

            UIGeneralControlClass.ASYNC_POOL.submit(() -> {
                try {
                    // 每个来源各取一页，再交错合并 —— 这样「来源=全部」时两个站点的结果都能进首屏
                    List<List<ModHit>> buckets = new ArrayList<>();
                    int pages = 1;
                    int failed = 0;
                    for (ModSource src : repos) {
                        try {
                            RemoteModRepository.SearchResult r =
                                    src.repo().search(VersionDownloadService.getDownloadProvider(), snapshot);
                            List<RemoteMod> items = r.getResults().collect(Collectors.toList());
                            List<ModHit> bucket = new ArrayList<>(items.size());
                            for (RemoteMod m : items) bucket.add(new ModHit(m, src.repo()));
                            buckets.add(bucket);
                            pages = Math.max(pages, r.getTotalPages());
                        } catch (Exception e) {
                            failed++;
                            System.err.println("[DL] " + src.label() + " 搜索失败: " + e.getMessage());
                        }
                    }
                    List<ModHit> merged = new ArrayList<>();
                    if (buckets.size() <= 1) {
                        if (!buckets.isEmpty()) merged.addAll(buckets.get(0));
                    } else {
                        int maxSize = buckets.stream().mapToInt(List::size).max().orElse(0);
                        for (int i = 0; i < maxSize; i++) {
                            for (List<ModHit> bucket : buckets) {
                                if (i < bucket.size()) merged.add(bucket.get(i));
                            }
                        }
                    }
                    // 至少有一个来源成功才写缓存，避免把「网络故障导致的空结果」缓存下来
                    if (failed < repos.size()) {
                        remoteCache.putSearch(cacheKey, List.copyOf(merged), pages);
                    }
                    final int totalPages = pages;
                    final int failCount = failed;
                    Platform.runLater(() -> {
                        listContainer.getChildren().clear();
                        if (merged.isEmpty() && failCount == repos.size()) {
                            listContainer.getChildren().add(PageKit.buildErrorState(
                                    "加载失败，请检查网络后重试", null, loadRef[0]));
                            pageLabel.setText("第 " + (pageOffset[0] + 1) + " / " + totalPages + " 页");
                            return;
                        }
                        renderModHits(listContainer, pageLabel, prevBtn, nextBtn, pageOffset[0],
                                merged, totalPages, repos.size() > 1, type, panelTitle,
                                targetGameDir, currentVersion);
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        listContainer.getChildren().clear();
                        listContainer.getChildren().add(PageKit.buildErrorState(
                                "加载失败", String.valueOf(ex.getMessage()), loadRef[0]));
                    });
                }
            });
        };
        loadRef[0] = load;

        // ==================== 事件绑定 ====================
        searchField.setOnAction(e -> searchBtn.fire());
        searchBtn.setOnAction(e -> { pageOffset[0] = 0; load.run(); });
        refreshBtn.setOnAction(e -> {
            // 刷新 = 清掉当前面板的搜索缓存并强制重新拉取
            remoteCache.clear();
            forceRefresh[0] = true;
            load.run();
        });
        prevBtn.setOnAction(e -> { if (pageOffset[0] > 0) { pageOffset[0]--; load.run(); } });
        nextBtn.setOnAction(e -> { pageOffset[0]++; load.run(); });
        // 筛选条件变化即触发搜索（与 VersePc2 的下拉框即时生效行为一致）
        versionCombo.setOnAction(e -> { pageOffset[0] = 0; load.run(); });
        if (loaderRelevant) {
            loaderCombo.setOnAction(e -> { pageOffset[0] = 0; load.run(); });
        }
        sortCombo.setOnAction(e -> { pageOffset[0] = 0; load.run(); });
        categoryCombo.setOnAction(e -> {
            // 分类下拉的选项列表由来源决定，切换过程中会重建，忽略索引越界的中间态
            if (categoryCombo.getSelectionModel().getSelectedIndex() < 0) return;
            pageOffset[0] = 0;
            load.run();
        });
        sourceCombo.setOnAction(e -> {
            String sel = sourceCombo.getSelectionModel().getSelectedItem();
            List<ModSource> picked = new ArrayList<>();
            if (sel == null || "全部".equals(sel)) {
                picked.addAll(effectiveSources);
            } else {
                for (ModSource s : effectiveSources) {
                    if (s.label().equals(sel)) picked.add(s);
                }
            }
            if (picked.isEmpty()) picked.addAll(effectiveSources);
            activeSelection[0] = List.copyOf(picked);
            pageOffset[0] = 0;
            reloadCategories.run();
            load.run();
        });

        reloadCategories.run();
        load.run();
        return root;
    }

    /** 搜索结果来源徽章文案（CF / MR），未知来源返回空串 */
    private static String sourceBadgeOf(RemoteModRepository repo) {
        String label = repo == null ? "" : repo.getClass().getSimpleName();
        if (label.contains("CurseForge")) return "CF";
        if (label.contains("Modrinth")) return "MR";
        return "";
    }

    /** 渲染一页搜索结果（缓存命中与网络返回共用同一段渲染逻辑） */
    private void renderModHits(VBox listContainer, Label pageLabel, Button prevBtn, Button nextBtn,
                               int pageOffset, List<ModHit> hits, int totalPages, boolean showBadge,
                               RemoteModRepository.Type type, String panelTitle,
                               String gameDir, String currentVersion) {
        listContainer.getChildren().clear();
        pageLabel.setText("第 " + (pageOffset + 1) + " / " + totalPages + " 页");
        prevBtn.setDisable(pageOffset <= 0);
        nextBtn.setDisable(pageOffset + 1 >= totalPages);
        if (hits.isEmpty()) {
            Label empty = new Label("没有找到相关资源");
            empty.setStyle("-fx-text-fill: -sl-text-dim; -fx-padding: 20;");
            listContainer.getChildren().add(empty);
            return;
        }
        for (ModHit hit : hits) {
            listContainer.getChildren().add(createRemoteModRow(hit.mod(), hit.repo(),
                    type, panelTitle, gameDir, currentVersion, showBadge));
        }
    }

    /**
     * 游戏版本下拉框：从版本清单填充条目。
     * <p>兼容旧调用点，等价于填充「全部」+ 版本号列表。
     */
    private void loadDownloadVersionOptions(ComboBox<String> versionCombo) {
        String current = host.config().getOrDefault("Version", "");
        java.util.function.Consumer<List<String>> fill = versions -> {
            versionCombo.getItems().setAll("全部");
            if (!current.isBlank()) versionCombo.getItems().add(current);
            for (String v : versions) {
                if (!versionCombo.getItems().contains(v)) versionCombo.getItems().add(v);
            }
            versionCombo.getSelectionModel().select(0);
        };
        if (!downloadVersionOptions.isEmpty()) {
            fill.accept(downloadVersionOptions);
            return;
        }
        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            try {
                List<VersionDownloadService.VersionInfo> infos = VersionDownloadService.fetchVersionManifest();
                List<String> releases = VersionDownloadService
                        .filterByCategory(infos, VersionDownloadService.VersionCategory.RELEASE)
                        .stream().limit(30).map(VersionDownloadService.VersionInfo::getId)
                        .collect(Collectors.toList());
                downloadVersionOptions = releases;
                Platform.runLater(() -> fill.accept(releases));
            } catch (Exception ignored) {
                // 版本清单拉取失败时保留下拉里的「全部」，不影响其它筛选维度
            }
        });
    }

    /**
     * 异步加载「分类」下拉选项。
     * <p>来源为 Modrinth（或「全部」）时用 Modrinth 的分类接口并把名称译成中文；
     * 来源为 CurseForge 时改用其分类接口（带 classId 过滤），因为两者的分类 ID 体系不通用。
     */
    private void loadCategoryOptions(ComboBox<String> sourceCombo, List<ModSource> sources,
                                     RemoteModRepository.Type type,
                                     ComboBox<String> categoryCombo,
                                     List<ModCategoryOption>[] holder) {
        String sel = sourceCombo.getSelectionModel().getSelectedItem();
        ModSource target = null;
        for (ModSource s : sources) {
            if (sel != null && sel.equals(s.label())) {
                target = s;
                break;
            }
        }
        boolean curseForge = target != null && sourceBadgeOf(target.repo()).equals("CF");
        if (target == null) {
            // 「全部」：分类以 Modrinth 为准（CurseForge 的数字 ID 无法跨源表达）
            for (ModSource s : sources) {
                if (sourceBadgeOf(s.repo()).equals("MR")) {
                    target = s;
                    break;
                }
            }
            if (target == null && !sources.isEmpty()) target = sources.get(0);
        }
        if (target == null) return;
        final ModSource repoSource = target;
        final boolean useCurseForge = curseForge;
        String cacheKey = repoSource.label() + "#" + type + "#" + useCurseForge;

        List<ModCategoryOption> cached = modCategoryCache.get(cacheKey);
        if (cached != null) {
            applyCategoryOptions(categoryCombo, holder, cached);
            return;
        }
        // 先占位，避免加载期间选中项与列表不一致
        applyCategoryOptions(categoryCombo, holder, List.of(ModCategoryOption.all()));
        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            try {
                List<ModCategoryOption> options = new ArrayList<>();
                options.add(ModCategoryOption.all());
                for (RemoteModRepository.Category c : repoSource.repo().getCategoriesForType()) {
                    String name = c.getId();
                    if (name == null || name.isBlank()) continue;
                    if (useCurseForge) {
                        int id = -1;
                        if (c.getSelf() instanceof JsonObject obj && obj.has("id")
                                && obj.get("id").isJsonPrimitive()) {
                            try { id = obj.get("id").getAsInt(); } catch (Exception ignored) {}
                        }
                        options.add(new ModCategoryOption(name, "", id));
                    } else {
                        options.add(new ModCategoryOption(ModCategories.zhCategory(name), name, -1));
                    }
                }
                modCategoryCache.put(cacheKey, options);
                Platform.runLater(() -> applyCategoryOptions(categoryCombo, holder, options));
            } catch (Exception e) {
                System.err.println("[DL] 分类加载失败: " + e.getMessage());
            }
        });
    }

    /** 把分类选项写入下拉框（保持「全部」为默认选中项） */
    private void applyCategoryOptions(ComboBox<String> categoryCombo,
                                      List<ModCategoryOption>[] holder,
                                      List<ModCategoryOption> options) {
        holder[0] = options;
        String previous = categoryCombo.getSelectionModel().getSelectedItem();
        List<String> labels = options.stream().map(ModCategoryOption::label).collect(Collectors.toList());
        categoryCombo.getItems().setAll(labels);
        // 切源后原选项可能不存在（中文名 ↔ 英文名），存在则保留选中，否则回到「全部」
        if (previous != null && labels.contains(previous)) {
            categoryCombo.getSelectionModel().select(previous);
        } else if (!categoryCombo.getItems().isEmpty()) {
            categoryCombo.getSelectionModel().select(0);
        }
    }

    /** meta 行的一个小标签（下载量 / 作者 / 分类） */
    private static Label metaLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 11px; -fx-text-fill: -sl-text-dim;");
        return l;
    }

    /** 带图标的 meta 小标签（下载量用 download、作者用 heart） */
    private static Label metaLabel(String icon, String text) {
        Label l = metaLabel(text);
        AppIcons.apply(l, icon, 12, null);
        return l;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * 远程资源行卡片（对应 VersePc2 模组页的 {@code .mod-item}）。
     *
     * <p>横向排布：48px 图标 → 名称（+ 来源徽章 CF/MR）→ 描述 → 下载量·作者·分类 → 「详情」「安装」。
     * 整行可点，点击进入模组详情页；样式沿用本项目的 {@code settings-card}（含 hover 浮起效果）。
     */
    private HBox createRemoteModRow(RemoteMod mod, RemoteModRepository repo, RemoteModRepository.Type type,
                                    String panelTitle, String gameDir, String currentVersion,
                                    boolean showSourceBadge) {
        HBox row = new HBox(14);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("settings-card");
        row.setStyle("-fx-cursor: hand;");

        ImageView iconView = new ImageView();
        iconView.setFitWidth(48);
        iconView.setFitHeight(48);
        iconView.setStyle("-fx-background-color: #e2e8f0; -fx-background-radius: 8;");
        if (mod.getIconUrl() != null && !mod.getIconUrl().isEmpty()) {
            // 异步加载图标：icon_url 多为 JavaFX 不支持的 WebP 缩略图（..._96.webp），
            // 先归一化为同目录 icon.png 原图，再按下载源走 mcimirror 镜像 / 官方 CDN；
            // 带 UA、超时与 HTTP/1.1 降级，全部失败时保留占位灰底
            loadRemoteIconAsync(iconView, mod.getIconUrl(), 48,
                    LocalizedRemoteModRepository.localizedTitle(mod, type));
        } else {
            // 没有封面地址也给出首字头像，避免一行行都是同款灰块
            loadRemoteIconAsync(iconView, null, 48,
                    LocalizedRemoteModRepository.localizedTitle(mod, type));
        }

        VBox info = new VBox(3);
        info.getStyleClass().add("settings-card-info");
        // 让「名称/描述」这一列承担压缩：minWidth 置 0 后它可以被压窄并省略，
        // 右侧的「详情/安装」按固有宽度保留，不会被挤出视口
        info.setMinWidth(0);
        HBox.setHgrow(info, Priority.ALWAYS);

        HBox titleRow = new HBox(6);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        // 命中中文名字典时显示中文标题（形如「机械动力 (Create)」），否则回退原始标题
        // 标题按「高级设置 → Mod 管理样式」决定显示译名还是文件名
        Label title = new Label(ModFileFormatter.titleByStyle(host.config() == null ? null : host.config().get("ModDisplayStyle"), mod, type, false));
        title.getStyleClass().add("settings-card-title");
        title.setTextOverrun(OverrunStyle.ELLIPSIS);
        titleRow.getChildren().add(title);
        if (showSourceBadge) {
            String badge = sourceBadgeOf(repo);
            if (!badge.isEmpty()) {
                Label badgeLabel = new Label(badge);
                badgeLabel.setStyle("-fx-font-size: 10px; -fx-padding: 1 5; -fx-background-radius: 3;"
                        + ("CF".equals(badge)
                        ? " -fx-background-color: rgba(241,100,54,0.16); -fx-text-fill: #f16436;"
                        : " -fx-background-color: rgba(76,175,80,0.16); -fx-text-fill: #4caf50;"));
                titleRow.getChildren().add(badgeLabel);
            }
        }
        info.getChildren().add(titleRow);

        Label desc = new Label(nullToEmpty(mod.getDescription()));
        desc.getStyleClass().add("settings-card-desc");
        desc.setMaxWidth(Double.MAX_VALUE);
        desc.setTextOverrun(OverrunStyle.ELLIPSIS);
        info.getChildren().add(desc);

        HBox meta = new HBox(12);
        meta.setAlignment(Pos.CENTER_LEFT);
        meta.getChildren().add(metaLabel("download", FormatUtils.formatCompactNumber(mod.getDownloads())));
        meta.getChildren().add(metaLabel("heart", (mod.getAuthor() == null || mod.getAuthor().isBlank()
                ? "未知作者" : mod.getAuthor())));
        List<String> cats = mod.getCategories();
        if (cats != null && !cats.isEmpty()) {
            meta.getChildren().add(metaLabel(cats.stream().limit(3).map(ModCategories::zhCategory)
                    .collect(Collectors.joining(", "))));
        }
        info.getChildren().add(meta);

        Button detailBtn = AppIcons.button("info", "详情");
        detailBtn.getStyleClass().add("btn-primary");
        detailBtn.setStyle("-fx-padding: 4 14; -fx-font-size: 12px;");
        detailBtn.setOnAction(e -> openRemoteModDetailPage(mod, repo, type, panelTitle, gameDir, currentVersion));

        Button installBtn = AppIcons.button("download", "安装");
        installBtn.getStyleClass().add("btn-primary");
        installBtn.setStyle("-fx-padding: 4 14; -fx-font-size: 12px;");
        installBtn.setOnAction(e -> openVersionPickerPage(mod, repo, type, panelTitle, gameDir, currentVersion));

        HBox actions = new HBox(8, detailBtn, installBtn);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setMinWidth(Region.USE_PREF_SIZE);
        // 图标同样按固有宽度保留，避免被压扁
        iconView.setManaged(true);
        HBox.setHgrow(iconView, Priority.NEVER);

        row.getChildren().addAll(iconView, info, actions);
        // 整行可点进详情（按钮自身消费了点击事件，不会冒泡到这里，不会误触发）
        row.setOnMouseClicked(e -> openRemoteModDetailPage(mod, repo, type, panelTitle, gameDir, currentVersion));
        return row;
    }

    // ==================== 远程图标加载（实现见 newhost.ui().host.ui().IconService） ====================

    /** 远程图标/头像加载（下载源取自当前配置） */
    private void loadRemoteIconAsync(ImageView iconView, String originalUrl, double size, String fallbackText) {
        IconService.loadRemoteIconAsync(iconView, originalUrl, size, fallbackText,
                host.config() != null ? host.config().getOrDefault("DownloadSource", "BMCLAPI") : "BMCLAPI");
    }

    private void loadRemoteIconAsync(ImageView iconView, String originalUrl, double size) {
        loadRemoteIconAsync(iconView, originalUrl, size, null);
    }
    /** 版本发布通道徽章（对应 VersePc2 版本行上的 stable / beta / alpha 标签） */
    private static Label releaseBadge(RemoteMod.VersionType type) {
        String text;
        String style;
        switch (type == null ? RemoteMod.VersionType.Release : type) {
            case Beta -> {
                text = "测试版";
                style = " -fx-background-color: rgba(255,152,0,0.18); -fx-text-fill: #f59e0b;";
            }
            case Alpha -> {
                text = "内测版";
                style = " -fx-background-color: rgba(239,83,80,0.18); -fx-text-fill: #ef5350;";
            }
            default -> {
                text = "稳定版";
                style = " -fx-background-color: rgba(76,175,80,0.18); -fx-text-fill: #4caf50;";
            }
        }
        Label badge = new Label(text);
        badge.setStyle("-fx-font-size: 10px; -fx-padding: 1 6; -fx-background-radius: 4;" + style);
        return badge;
    }

    /**
     * 「选择版本」页的上下文：点列表行的「安装」时记录，由 {@link #buildDownloadVersionPickerPage()} 读取。
     */
    private record VersionPickContext(RemoteMod mod, RemoteModRepository repo, RemoteModRepository.Type type,
                                      String panelTitle, String gameDir, String currentVersion) {
    }

    /** 当前正在浏览的「选择版本」页（null 表示未进入） */
    private VersionPickContext currentVersionPick;
    /** 「选择版本」页「返回」按钮的目标页面 */
    private String versionPickBackKey = "download";

    /**
     * 进入「选择版本」页（主窗口内整页，不再是弹窗）。
     * <p>由列表行的「安装」按钮触发；「返回」回到触发它的那一页。
     */
    private void openVersionPickerPage(RemoteMod mod, RemoteModRepository repo, RemoteModRepository.Type type,
                                       String panelTitle, String gameDir, String currentVersion) {
        currentVersionPick = new VersionPickContext(mod, repo, type, panelTitle, gameDir, currentVersion);
        versionPickBackKey = (host.currentPageKey() == null || "downloadVersionPick".equals(host.currentPageKey()))
                ? "download" : host.currentPageKey();
        // 仍属于「下载」标签，保持顶部导航高亮与侧边栏隐藏状态一致
        host.selectDownloadTab();
        host.hideSidebar();
        host.switchToPage("downloadVersionPick");
    }

    /** 「选择版本」页「返回」 */
    private void goBackFromVersionPick() {
        String target = versionPickBackKey;
        if ("downloadDetail".equals(target) && currentModDetail == null) target = "download";
        host.switchToPage(target == null ? "download" : target);
    }

    /**
     * 「选择版本」页（对应 VersePc2 模组详情页的「版本」标签页）。
     *
     * <p>顶部是「← 返回」+ 资源名，下面是游戏版本 / 加载器两个筛选下拉与版本列表：
     * 每行含发布通道徽章、版本名、游戏版本 · 加载器 · 发布时间、文件名，以及「下载」按钮。
     */
    public Node buildVersionPicker() {
        VersionPickContext ctx = currentVersionPick;
        VBox root = new VBox(12);
        root.setPadding(new Insets(8, 8, 8, 0));
        VBox.setVgrow(root, Priority.ALWAYS);

        Button backBtn = AppIcons.button("back", "返回");
        backBtn.getStyleClass().add("back-btn");
        backBtn.setOnAction(e -> goBackFromVersionPick());
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        if (ctx != null) {
            Label titleLabel = new Label("选择版本 - "
                    + LocalizedRemoteModRepository.localizedTitle(ctx.mod(), ctx.type()));
            titleLabel.getStyleClass().add("home-card-title");
            header.getChildren().addAll(backBtn, titleLabel);
        } else {
            header.getChildren().add(backBtn);
        }
        root.getChildren().add(header);

        if (ctx == null) {
            Label tip = new Label("请先在下载中心选择要安装的资源。");
            tip.setStyle("-fx-text-fill: -sl-text-dim; -fx-padding: 20;");
            root.getChildren().add(tip);
            return root;
        }

        ComboBox<String> verFilter = new ComboBox<>();
        verFilter.getItems().add("全部");
        verFilter.getSelectionModel().select(0);
        ComboBox<String> loaderFilter = new ComboBox<>();
        loaderFilter.getItems().addAll(loaderFilterOptions());
        loaderFilter.getSelectionModel().select(0);
        HBox filterRow = new HBox(12,
                buildFilterGroup("游戏版本", verFilter, 140),
                buildFilterGroup("加载器", loaderFilter, 120));
        filterRow.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(filterRow);

        Label countLabel = new Label();
        countLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-dim;");
        root.getChildren().add(countLabel);

        VBox list = new VBox(6);
        // 加载期间用骨架屏微光占位
        list.getChildren().add(SkeletonFactory.buildListSkeleton(4));
        ScrollPane scroll = new ScrollPane(list);
        PageKit.configureScrollPane(host, scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        root.getChildren().add(scroll);

        loadPickerVersions(list, countLabel, verFilter, loaderFilter, ctx);
        return root;
    }

    /**
     * 版本选择页的版本列表：骨架占位 → 异步加载 → 渲染 / 失败卡片
     * （「重新加载」复用本方法可重入；列表命中内存缓存时切走再切回不会重新请求）。
     */
    private void loadPickerVersions(VBox list, Label countLabel, ComboBox<String> verFilter,
                                    ComboBox<String> loaderFilter, VersionPickContext ctx) {
        list.getChildren().clear();
        list.getChildren().add(SkeletonFactory.buildListSkeleton(4));
        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            try {
                List<RemoteMod.Version> versions = remoteCache.loadVersions(ctx.repo(), ctx.mod(), false);
                Platform.runLater(() -> {
                    if (versions.isEmpty()) {
                        list.getChildren().clear();
                        list.getChildren().add(skeletonHint("该资源暂无可用版本"));
                        countLabel.setText("");
                        return;
                    }
                    // 游戏版本下拉用版本列表里实际出现过的版本填充，避免给出查不到结果的选项
                    java.util.Set<String> gameVersions = new java.util.LinkedHashSet<>();
                    for (RemoteMod.Version v : versions) {
                        if (v.getGameVersions() != null) gameVersions.addAll(v.getGameVersions());
                    }
                    verFilter.getItems().setAll("全部");
                    verFilter.getItems().addAll(gameVersions);

                    Runnable render = () -> {
                        String gv = comboValue(verFilter);
                        String loaderLabel = comboValue(loaderFilter);
                        List<RemoteMod.Version> filtered = versions.stream()
                                .filter(v -> gv.isEmpty() || (v.getGameVersions() != null
                                        && v.getGameVersions().contains(gv)))
                                .filter(v -> matchesLoaderFilter(v, loaderLabel))
                                .collect(Collectors.toList());
                        list.getChildren().clear();
                        countLabel.setText("共 " + filtered.size() + " 个版本（总 " + versions.size() + " 个）");
                        if (filtered.isEmpty()) {
                            Label none = new Label("没有符合筛选条件的版本");
                            none.setStyle("-fx-text-fill: -sl-text-dim; -fx-padding: 10;");
                            list.getChildren().add(none);
                            return;
                        }
                        for (RemoteMod.Version ver : filtered) {
                            list.getChildren().add(createRemoteVersionItem(ver, ctx.repo(), ctx.type(),
                                    ctx.panelTitle(), ctx.gameDir(), ctx.currentVersion(), true));
                        }
                    };
                    verFilter.setOnAction(e -> render.run());
                    loaderFilter.setOnAction(e -> render.run());
                    render.run();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    list.getChildren().clear();
                    list.getChildren().add(PageKit.buildErrorState(
                            "版本加载失败", String.valueOf(ex.getMessage()),
                            () -> loadPickerVersions(list, countLabel, verFilter, loaderFilter, ctx)));
                });
            }
        });
    }

    /**
     * 单个远程版本项。
     *
     * @param withInstall true 时在行尾显示「下载」按钮（版本选择弹窗用）；
     *                    false 时只展示信息（详情页版本列表由外层统一提供操作）
     */
    private VBox createRemoteVersionItem(RemoteMod.Version ver, RemoteModRepository repo,
                                         RemoteModRepository.Type type, String panelTitle,
                                         String gameDir, String currentVersion, boolean withInstall) {
        VBox item = new VBox(5);
        item.getStyleClass().add("mod-version-row");

        HBox top = new HBox(8);
        top.setAlignment(Pos.CENTER_LEFT);
        top.getChildren().add(releaseBadge(ver.getVersionType()));
        Label name = new Label(nullToEmpty(ver.getName()).isBlank() ? ver.getVersion() : ver.getName());
        name.getStyleClass().add("mod-version-name");
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        HBox.setHgrow(name, Priority.ALWAYS);
        top.getChildren().add(name);
        Label date = new Label(formatVersionDate(ver.getDatePublished()));
        date.getStyleClass().add("mod-version-date");
        top.getChildren().add(date);
        if (withInstall) {
            Button dlBtn = AppIcons.button("download", "下载");
            dlBtn.getStyleClass().add("btn-primary");
            dlBtn.setStyle("-fx-padding: 3 12; -fx-font-size: 11px;");
            dlBtn.setOnAction(e -> installRemoteContent(ver, repo, type, panelTitle, gameDir, currentVersion));
            top.getChildren().add(dlBtn);
        }
        item.getChildren().add(top);

        List<String> parts = new ArrayList<>();
        if (ver.getGameVersions() != null && !ver.getGameVersions().isEmpty()) {
            List<String> gv = ver.getGameVersions();
            parts.add("游戏版本: " + (gv.size() > 6 ? String.join(", ", gv.subList(0, 6)) + " 等 " + gv.size() + " 个"
                    : String.join(", ", gv)));
        }
        if (ver.getLoaders() != null && !ver.getLoaders().isEmpty()) {
            parts.add("加载器: " + visibleLoaders(ver.getLoaders()).stream()

                    .filter(l -> l != ModLoaderType.UNKNOWN)
                    .map(Enum::name).collect(Collectors.joining(", ")));
        }
        if (ver.getFile() != null && ver.getFile().getFilename() != null) {
            parts.add(ver.getFile().getFilename());
        }
        if (!parts.isEmpty()) {
            Label meta = new Label(String.join("  ·  ", parts));
            meta.setWrapText(true);
            meta.getStyleClass().add("mod-version-meta");
            item.getChildren().add(meta);
        }

        // 依赖提示：必需依赖缺失是最常见的「装了模组却启动崩溃」的原因，
        // 这里标出数量，并且可以点开看具体是哪些前置（名称按需从数据源解析）
        long required = ver.getDependencies() == null ? 0 : ver.getDependencies().stream()
                .filter(d -> d.getType() == RemoteMod.DependencyType.REQUIRED).count();
        long optional = ver.getDependencies() == null ? 0 : ver.getDependencies().stream()
                .filter(d -> d.getType() == RemoteMod.DependencyType.OPTIONAL).count();
        if (required > 0 || optional > 0) {
            String base = "依赖: " + required + " 个必需" + (optional > 0 ? "，" + optional + " 个可选" : "");
            Label deps = new Label(base + "　点击展开查看");
            deps.getStyleClass().add("mod-version-deps");
            deps.setStyle("-fx-cursor: hand;");
            deps.setMinWidth(Region.USE_PREF_SIZE);

            VBox depPanel = new VBox(3);
            depPanel.setVisible(false);
            depPanel.setManaged(false);

            deps.setOnMouseClicked(e -> {
                boolean show = !depPanel.isVisible();
                depPanel.setVisible(show);
                depPanel.setManaged(show);
                deps.setText(base + (show ? "　点击收起" : "　点击展开查看"));
                if (show && depPanel.getChildren().isEmpty()) {
                    fillDependencyPanel(depPanel, ver, repo, gameDir, currentVersion);
                }
            });
            item.getChildren().addAll(deps, depPanel);
        }
        return item;
    }

    /**
     * 展开后填充前置依赖列表：逐个把依赖 id 解析成可读名称。
     *
     * <p>解析要联网，所以先放「正在解析…」占位，结果回来后替换；
     * 单个依赖解析失败不影响其它条目。
     */
    private void fillDependencyPanel(VBox panel, RemoteMod.Version ver, RemoteModRepository repo,
                                     String gameDir, String currentVersion) {
        Label loading = new Label("正在解析前置模组...");
        loading.getStyleClass().add("config-hint");
        panel.getChildren().add(loading);

        List<RemoteMod.Dependency> deps = ver.getDependencies() == null
                ? List.of() : new ArrayList<>(ver.getDependencies());
        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            List<Node> rows = new ArrayList<>();
            for (RemoteMod.Dependency d : deps) {
                String kind = switch (d.getType()) {
                    case REQUIRED -> "必需";
                    case OPTIONAL -> "可选";
                    case INCOMPATIBLE -> "不兼容";
                    case EMBEDDED -> "已内置";
                    default -> "其它";
                };
                String color = switch (d.getType()) {
                    case REQUIRED -> "#dc2626";
                    case OPTIONAL -> "#2563eb";
                    case INCOMPATIBLE -> "#b45309";
                    default -> "#6b7280";
                };
                String title = d.getId();
                try {
                    RemoteMod depMod = d.load(VersionDownloadService.getDownloadProvider());
                    if (depMod != null && depMod.getTitle() != null && !depMod.getTitle().isBlank()) {
                        // 命中中文字典时补一个中文名，如「Fabric API（Fabric API）」
                        title = LocalizedRemoteModRepository.localizedTitle(depMod);
                    }
                } catch (Exception ignored) {
                    // 解析不到就退回显示原始 id，不影响其它依赖
                }
                Label row = new Label("[" + kind + "] " + title);
                row.setWrapText(true);
                row.setStyle("-fx-font-size: 11px; -fx-text-fill: " + color + "; -fx-padding: 0 0 0 12;");
                rows.add(row);
            }
            Platform.runLater(() -> {
                panel.getChildren().clear();
                if (rows.isEmpty()) {
                    Label none = new Label("该版本没有声明前置依赖");
                    none.getStyleClass().add("config-hint");
                    panel.getChildren().add(none);
                } else {
                    panel.getChildren().addAll(rows);
                }
            });
        });
    }

    /** 版本发布时间显示：{@code 2026-01-02} */
    private static String formatVersionDate(java.time.Instant instant) {
        if (instant == null) return "";
        try {
            return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
                    .withZone(java.time.ZoneId.systemDefault()).format(instant);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 下载并安装选中的远程版本（模组 / 资源包 / 光影包 / 世界 / 整合包）。
     * <p>所有分支统一走 {@link DownloadTaskCard}，不再在列表行内显示进度条。
     */
    private void installRemoteContent(RemoteMod.Version ver, RemoteModRepository repo,
                                      RemoteModRepository.Type type, String panelTitle,
                                      String gameDir, String currentVersion) {
        installRemoteContent(ver, repo, type, panelTitle, gameDir, currentVersion, null);
    }

    /**
     * @param modsDir 已确认的模组安装目录；为 null 且类型是模组时，先弹「选择安装位置」问用户，
     *                选完再带着结果回调进来（重试时传入上次选定的目录，不再重复问）
     */
    private void installRemoteContent(RemoteMod.Version ver, RemoteModRepository repo,
                                      RemoteModRepository.Type type, String panelTitle,
                                      String gameDir, String currentVersion, Path modsDir) {
        if (ver.getFile() == null || ver.getFile().getUrl() == null) {
            // CurseForge 上作者可关闭「允许第三方分发」，此时接口不会返回直链
            host.ui().toast("该版本没有可下载的文件（CurseForge 资源若未开放第三方分发，请前往官网下载）");
            return;
        }
        // 模组先问清楚装到哪个 mods 文件夹：放错位置游戏不会加载，用户很难自己发现
        if (type == RemoteModRepository.Type.MOD && modsDir == null) {
            String what = "模组" + (ver.getName() == null || ver.getName().isBlank()
                    ? "" : "「" + ver.getName() + "」");
            ModInstallTargetDialog.show(host, gameDir, currentVersion, what,
                    dir -> installRemoteContent(ver, repo, type, panelTitle, gameDir, currentVersion, dir));
            return;
        }
        String title = "安装 " + panelTitle + " · " + (ver.getName() == null ? ver.getVersion() : ver.getName());
        DownloadTaskCard dialog = downloads.open(title);
        // 失败后可一键重试（沿用本次选定的安装位置，不再弹一次路径选择）
        dialog.setRetryAction(() ->
                installRemoteContent(ver, repo, type, panelTitle, gameDir, currentVersion, modsDir));

        if (type == RemoteModRepository.Type.MODPACK) {
            installModpack(ver, gameDir, dialog);
            return;
        }
        if (type == RemoteModRepository.Type.WORLD) {
            installWorld(ver, gameDir, panelTitle, dialog);
            return;
        }

        Path targetDir;
        if (type == RemoteModRepository.Type.MOD) {
            targetDir = modsDir;
        } else if (type == RemoteModRepository.Type.RESOURCE_PACK) {
            targetDir = Paths.get(gameDir, "resourcepacks");
        } else if (type == RemoteModRepository.Type.SHADER_PACK) {
            targetDir = Paths.get(gameDir, "shaderpacks");
        } else if (type == RemoteModRepository.Type.CUSTOMIZATION) {
            // 数据包按版本存放：<gameDir>/saves/<世界>/datapacks 才是最终位置，
            // 但用户往往还没建世界，这里先落到全局 datapacks 目录，由用户自行放入世界
            targetDir = Paths.get(gameDir, "datapacks");
        } else {
            host.ui().toast("该分类暂不支持直接下载");
            return;
        }

        String filename = ver.getFile().getFilename();
        // 净化文件名：拒绝路径分隔与非法字符（防目录穿越）
        if (filename == null || filename.isBlank()) {
            host.ui().toast("该版本文件名为空");
            return;
        }
        filename = filename.replace('\\', '_').replace('/', '_')
                .replaceAll("[\\x00-\\x1f<>:\"|?*]", "_");
        if (filename.equals(".") || filename.equals("..") || filename.contains("..")) {
            host.ui().toast("文件名不合法: " + filename);
            return;
        }
        // 按「高级设置 → 文件名格式」重写落盘文件名（模组/整合包才有意义）
        if (type == RemoteModRepository.Type.MOD || type == RemoteModRepository.Type.MODPACK) {
            String template = host.config() == null ? null : host.config().get("ModFileNameFormat");
            RemoteMod titleMod = null;
            try { titleMod = currentModForTitle(ver); } catch (Exception ignored) {}
            String formatted = ModFileFormatter.applyFileNameFormat(template, filename, ver, type, titleMod);
            if (formatted != null && !formatted.isBlank()) filename = formatted;
        }
        final String safeFilename = filename;
        final Path destDir = targetDir;
        final RemoteMod.Version finalVer = ver;

        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            try {
                Files.createDirectories(destDir);
                Path dest = destDir.resolve(safeFilename);
                dialog.stage("下载 " + safeFilename);
                dialog.logLine("来源: " + sourceBadgeOf(repo) + "　版本: " + finalVer.getVersion(), true);
                dialog.logLine("直链: " + finalVer.getFile().getUrl(), true);
                dialog.logLine("目标: " + dest, true);
                dialog.beginFile(safeFilename);

                // 先探测文件大小，进度弹窗据此把百分比换算成实时速度
                long size = HttpDownloadEngine.probeFileSize(HttpDownloadEngine.HTTP_CLIENT, ver.getFile().getUrl());
                dialog.totalBytes(size);
                if (size > 0) dialog.logLine("文件大小: " + FormatUtils.formatFileSize(size), true);

                HttpDownloadEngine.downloadContentFile(finalVer, dest, dialog::progress, dialog.cancelFlag());
                dialog.finishFile(true);
                dialog.finish(true, panelTitle + "「" + finalVer.getName() + "」已安装到 " + destDir, null);
                Platform.runLater(() -> host.ui().toast(panelTitle + "「" + finalVer.getName() + "」安装完成"));
            } catch (Exception ex) {
                boolean cancelled = HttpDownloadEngine.CANCELLED_MESSAGE.equals(ex.getMessage());
                dialog.finishFile(false);
                dialog.logLine("失败: " + ex, false);
                if (ex.getCause() != null) dialog.logLine("原因: " + ex.getCause(), false);
                dialog.finish(false, cancelled ? "已取消下载" : "下载失败: " + ex.getMessage(), null);
            }
        });
    }


    // ==================== 下载中心：详情页（描述 / 图库 / 版本 / 依赖） ====================

    /** 详情页图片灯箱的遮罩层（与详情弹窗并存，点击任意处关闭） */
    private StackPane lightboxOverlay;

    /** 详情页的统计小格：{@code 12.3万} 这样的「图标 + 文案」 */
    private static Label detailStat(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-dim;");
        return l;
    }

    /** 带图标的详情统计小格（下载量用 download、关注数用 heart） */
    private static Label detailStat(String icon, String text) {
        Label l = detailStat(text);
        AppIcons.apply(l, icon, 13, null);
        return l;
    }

    /**
     * 下载详情页的上下文：点列表行进入详情页时记录，由 {@link #buildDownloadDetailPage()} 读取。
     * <p>页面每次访问都重建，因此上下文用字段保存，而不是通过构造参数层层传递。
     */
    private record ModDetailContext(RemoteMod mod, RemoteModRepository repo, RemoteModRepository.Type type,
                                    String panelTitle, String gameDir, String currentVersion) {
    }

    /** 当前正在浏览的下载详情（null 表示未进入详情页） */
    private ModDetailContext currentModDetail;

    /**
     * 进入下载详情页（主窗口内的独立页面，不再是弹窗）。
     *
     * <p>对应 VersePc2 的 {@code page-mod-detail}：列表页点行 → 整页切换到详情 → 左上角「返回」回列表。
     */
    private void openRemoteModDetailPage(RemoteMod mod, RemoteModRepository repo,
                                         RemoteModRepository.Type type, String panelTitle,
                                         String gameDir, String currentVersion) {
        currentModDetail = new ModDetailContext(mod, repo, type, panelTitle, gameDir, currentVersion);
        // 详情页仍属于「下载」标签，保持顶部导航高亮与侧边栏隐藏状态一致
        host.selectDownloadTab();
        host.hideSidebar();
        host.switchToPage("downloadDetail");
    }

    /**
     * 下载详情页（对应 VersePc2 的 {@code page-mod-detail}）。
     *
     * <p>结构：顶部「返回下载中心」+ 面包屑，下方为头部（64px 图标 + 标题 + 简介 +
     * 下载量/关注数/更新时间 + 分类标签 + 外链按钮）与三个标签页（<b>描述</b>／<b>图库</b>／<b>版本</b>）。
     *
     * <p>详情接口失败时降级为列表页已有的信息（标题、简介、图标），不会白屏。
     */
    public Node buildDetail() {
        ModDetailContext ctx = currentModDetail;
        if (ctx == null) {
            // 兜底：没有上下文时回到下载中心，避免出现空白页
            Node cached = host.cachedPage("download");
            return cached != null ? cached : buildCenter();
        }

        VBox root = new VBox(12);
        root.setPadding(new Insets(8, 8, 8, 0));
        VBox.setVgrow(root, Priority.ALWAYS);

        // ===== 顶部：返回 + 面包屑 =====
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        Button backBtn = AppIcons.button("back", "返回下载中心");
        backBtn.getStyleClass().add("community-action-btn");
        backBtn.setStyle("-fx-padding: 6 14; -fx-font-size: 12px;");
        backBtn.setOnAction(e -> host.switchToPage("download"));
        Label crumb = new Label(ctx.panelTitle() + " / 详情");
        crumb.getStyleClass().add("home-card-title");
        header.getChildren().addAll(backBtn, crumb);
        root.getChildren().add(header);

        // ===== 主体：加载中用骨架屏微光占位 → 数据到达后填充头部与标签页 =====
        VBox body = new VBox(10);
        VBox.setVgrow(body, Priority.ALWAYS);
        body.getChildren().add(SkeletonFactory.buildDetailSkeleton());
        root.getChildren().add(body);

        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            // 详情与版本列表都走内存缓存：第二次进同一个模组的详情页是秒开的
            final RemoteModDetail detail = remoteCache.loadDetail(ctx.repo(), ctx.mod(), false);
            List<RemoteMod.Version> versions = List.of();
            try {
                versions = remoteCache.loadVersions(ctx.repo(), ctx.mod(), false);
            } catch (Exception e) {
                System.err.println("[DL] 版本列表加载失败: " + e.getMessage());
            }
            final List<RemoteMod.Version> finalVersions = versions;
            Platform.runLater(() -> buildRemoteModDetailBody(body, ctx.mod(), ctx.repo(), ctx.type(),
                    ctx.panelTitle(), ctx.gameDir(), ctx.currentVersion(), detail, finalVersions));
        });
        return root;
    }

    /** 用加载到的详情数据填充详情面板（在 FX 线程执行） */
    private void buildRemoteModDetailBody(VBox body, RemoteMod mod, RemoteModRepository repo,
                                          RemoteModRepository.Type type, String panelTitle,
                                          String gameDir, String currentVersion,
                                          RemoteModDetail detail, List<RemoteMod.Version> versions) {
        body.getChildren().clear();

        // ==================== 头部：图标 + 简介（上行），操作按钮（下行横向） ====================
        HBox headerTop = new HBox(14);
        headerTop.setAlignment(Pos.TOP_LEFT);

        ImageView icon = new ImageView();
        icon.setFitWidth(64);
        icon.setFitHeight(64);
        icon.setStyle("-fx-background-color: #e2e8f0; -fx-background-radius: 10;");
        String iconUrl = detail.getIconUrl() != null && !detail.getIconUrl().isBlank()
                ? detail.getIconUrl() : mod.getIconUrl();
        if (iconUrl != null && !iconUrl.isEmpty()) {
            loadRemoteIconAsync(icon, iconUrl, 64,
                    LocalizedRemoteModRepository.localizedTitle(mod, type));
        }
        headerTop.getChildren().add(icon);

        VBox info = new VBox(6);
        HBox.setHgrow(info, Priority.ALWAYS);

        Label title = new Label(ModFileFormatter.titleByStyle(host.config() == null ? null : host.config().get("ModDisplayStyle"), mod, type, true));
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: 700;");
        title.setWrapText(true);
        info.getChildren().add(title);

        if (detail.getDescription() != null && !detail.getDescription().isBlank()) {
            Label desc = new Label(detail.getDescription());
            desc.setWrapText(true);
            desc.setStyle("-fx-font-size: 13px; -fx-text-fill: -sl-text-dim;");
            info.getChildren().add(desc);
        }

        HBox stats = new HBox(16);
        stats.setAlignment(Pos.CENTER_LEFT);
        long downloads = detail.getDownloads() > 0 ? detail.getDownloads() : mod.getDownloads();
        stats.getChildren().add(detailStat("download", FormatUtils.formatCompactNumber((int) Math.min(Integer.MAX_VALUE, downloads))));
        if (detail.getFollowers() > 0) {
            stats.getChildren().add(detailStat("heart", FormatUtils.formatCompactNumber((int) Math.min(Integer.MAX_VALUE, detail.getFollowers()))));
        }
        String author = detail.getAuthor() != null && !detail.getAuthor().isBlank()
                ? detail.getAuthor() : mod.getAuthor();
        if (author != null && !author.isBlank()) {
            stats.getChildren().add(detailStat("作者 " + author));
        }
        if (detail.getUpdated() != null && !detail.getUpdated().isBlank()) {
            stats.getChildren().add(detailStat("更新 " + detail.getUpdated().substring(0,
                    Math.min(10, detail.getUpdated().length()))));
        }
        if (detail.getLicense() != null && !detail.getLicense().isBlank()) {
            stats.getChildren().add(detailStat("许可 " + detail.getLicense()));
        }
        info.getChildren().add(stats);

        // 分类标签（Modrinth 分类名译成中文再展示）
        List<String> cats = detail.getCategories().isEmpty() ? mod.getCategories() : detail.getCategories();
        if (cats != null && !cats.isEmpty()) {
            FlowPane tags = new FlowPane(6, 6);
            for (String c : cats.stream().limit(10).collect(Collectors.toList())) {
                Label tag = new Label(ModCategories.zhCategory(c));
                tag.setStyle("-fx-font-size: 11px; -fx-padding: 2 8; -fx-background-radius: 4;"
                        + " -fx-background-color: rgba(59,130,246,0.12); -fx-text-fill: #3b82f6;");
                tags.getChildren().add(tag);
            }
            info.getChildren().add(tags);
        }
        headerTop.getChildren().add(info);

        // 操作按钮：放在简介（图标 + 标题 + 描述 + 统计 + 标签）下方，横向排布；按钮较多时自动换行
        FlowPane headerActions = new FlowPane(8, 8);
        headerActions.setAlignment(Pos.CENTER_LEFT);
        String pageUrl = detail.getPageUrl() != null && !detail.getPageUrl().isBlank()
                ? detail.getPageUrl() : mod.getPageUrl();
        if (pageUrl != null && !pageUrl.isBlank()) {
            Button openBtn = AppIcons.button("external-link", "打开主页");
            openBtn.getStyleClass().add("btn-primary");
            openBtn.setStyle("-fx-padding: 4 12; -fx-font-size: 12px;");
            openBtn.setOnAction(e -> host.openWebUrl(pageUrl));
            headerActions.getChildren().add(openBtn);
        }
        // 命中中文名字典且收录了百科编号时，保留本项目的「MC百科」直达入口
        ModTranslations.Mod translation = LocalizedRemoteModRepository.findTranslation(mod, type);
        if (translation != null && translation.hasChineseName() && !translation.getMcmod().isEmpty()) {
            String mcmodUrl = ModTranslations.getTranslationsByAddonType(type).getMcmodUrl(translation);
            if (!mcmodUrl.isEmpty()) {
                Button mcmodBtn = AppIcons.button("external-link", "MC百科");
                mcmodBtn.getStyleClass().add("btn-primary");
                mcmodBtn.setStyle("-fx-padding: 4 12; -fx-font-size: 12px;");
                mcmodBtn.setTooltip(new Tooltip(mcmodUrl));
                mcmodBtn.setOnAction(e -> host.openWebUrl(mcmodUrl));
                headerActions.getChildren().add(mcmodBtn);
            }
        }
        for (String[] link : new String[][]{
                {"源码", detail.getSourceUrl()}, {"问题反馈", detail.getIssuesUrl()},
                {"Wiki", detail.getWikiUrl()}, {"Discord", detail.getDiscordUrl()}}) {
            if (link[1] == null || link[1].isBlank()) continue;
            Button b = new Button(link[0]);
            b.getStyleClass().add("btn-primary");
            b.setStyle("-fx-padding: 4 12; -fx-font-size: 12px;");
            b.setOnAction(e -> host.openWebUrl(link[1]));
            headerActions.getChildren().add(b);
        }
        if (!versions.isEmpty()) {
            Button installLatest = AppIcons.button("download", "安装最新版");
            installLatest.getStyleClass().add("btn-primary");
            installLatest.setStyle("-fx-padding: 4 12; -fx-font-size: 12px;");
            installLatest.setOnAction(e -> {
                host.ui().closeModal();
                installRemoteContent(versions.get(0), repo, type, panelTitle, gameDir, currentVersion);
            });
            headerActions.getChildren().add(installLatest);
        }

        VBox header = new VBox(12, headerTop, headerActions);
        body.getChildren().add(header);

        body.getChildren().add(new Separator());

        // ==================== 标签页 ====================
        StackPane tabContent = new StackPane();
        tabContent.setAlignment(Pos.TOP_LEFT);
        tabContent.setMinHeight(Region.USE_PREF_SIZE);

        Map<String, Node> pages = new LinkedHashMap<>();
        pages.put("描述", buildModDescriptionTab(detail));
        pages.put("图库", buildModGalleryTab(detail));
        pages.put("版本", buildModVersionsTab(mod, repo, type, panelTitle, gameDir,
                currentVersion, versions));

        HBox tabBar = new HBox(6);
        tabBar.setAlignment(Pos.CENTER_LEFT);
        final Button[] tabButtons = new Button[pages.size()];
        int index = 0;
        for (Map.Entry<String, Node> e : pages.entrySet()) {
            Button tab = new Button(e.getKey() + (e.getKey().equals("版本") ? " (" + versions.size() + ")" : ""));
            tab.getStyleClass().add("detail-tab");
            final int tabIndex = index;
            tab.setOnAction(ev -> {
                tabContent.getChildren().setAll(e.getValue());
                for (Button b : tabButtons) {
                    if (b != null) b.getStyleClass().remove("selected");
                }
                tabButtons[tabIndex].getStyleClass().add("selected");
            });
            tabButtons[index] = tab;
            tabBar.getChildren().add(tab);
            index++;
        }
        tabButtons[0].getStyleClass().add("selected");
        tabContent.getChildren().add(pages.get("描述"));
        body.getChildren().addAll(tabBar, tabContent);
    }

    /** 「描述」标签页：用 WebView 渲染 Markdown / HTML 正文 */
    private Node buildModDescriptionTab(RemoteModDetail detail) {
        if (!detail.hasBody()) {
            VBox empty = new VBox(8);
            empty.setPadding(new Insets(16));
            Label tip = new Label("该资源没有提供详细描述。");
            tip.setStyle("-fx-text-fill: -sl-text-dim;");
            empty.getChildren().add(tip);
            if (detail.getDescription() != null && !detail.getDescription().isBlank()) {
                Label desc = new Label(detail.getDescription());
                desc.setWrapText(true);
                empty.getChildren().add(desc);
            }
            return empty;
        }
        boolean dark = "dark".equalsIgnoreCase(host.currentTheme());
        javafx.scene.web.WebView web = new javafx.scene.web.WebView();        web.setPrefHeight(400);
        web.setContextMenuEnabled(false);
        // 正文里的链接一律交给系统浏览器打开，避免在 WebView 里跳走丢失详情页
        web.getEngine().locationProperty().addListener((o, ov, nv) -> {
            if (nv != null && (nv.startsWith("http://") || nv.startsWith("https://"))) {
                host.openWebUrl(nv);
            }
        });
        String html = detail.getBodyFormat() == RemoteModDetail.BodyFormat.HTML
                ? MarkdownRenderer.htmlDocument(detail.getBody(), dark)
                : MarkdownRenderer.toDocument(detail.getBody(), dark);
        web.getEngine().loadContent(html);
        return web;
    }

    /** 「图库」标签页：缩略图网格，点击放大查看（不套内层滚动条，滚整个页面） */
    private Node buildModGalleryTab(RemoteModDetail detail) {
        List<String> images = detail.getGallery();
        if (images == null || images.isEmpty()) {
            VBox empty = new VBox(8);
            empty.setPadding(new Insets(16));
            Label tip = new Label("该资源没有提供图库截图。");
            tip.getStyleClass().add("config-hint");
            empty.getChildren().add(tip);
            return empty;
        }
        FlowPane grid = new FlowPane(12, 12);
        grid.setPadding(new Insets(10));
        for (String url : images) {
            ImageView iv = new ImageView();
            iv.setFitWidth(200);
            iv.setFitHeight(120);
            iv.setPreserveRatio(true);
            iv.setStyle("-fx-background-color: #e2e8f0; -fx-background-radius: 8; -fx-cursor: hand;");
            loadRemoteIconAsync(iv, url, 200);
            iv.setOnMouseClicked(e -> showImageLightbox(url));
            grid.getChildren().add(iv);
        }
        return grid;
    }

    /** 「版本」标签页：游戏版本 / 加载器筛选 + 版本列表 + 依赖一键下载 */
    private Node buildModVersionsTab(RemoteMod mod, RemoteModRepository repo, RemoteModRepository.Type type,
                                     String panelTitle, String gameDir, String currentVersion,
                                     List<RemoteMod.Version> versions) {
        VBox box = new VBox(10);

        if (versions.isEmpty()) {
            Label none = new Label("该资源没有可用版本（可能是网络问题，请稍后重试）");
            none.setStyle("-fx-text-fill: -sl-text-dim; -fx-padding: 16;");
            box.getChildren().add(none);
            return box;
        }

        // 「游戏版本」拆成 大版本 + 子版本 两级下拉：
        // 大版本是 1.20 / 1.21 这样的一级版本，子版本是 1.20.1 / 1.20.2 这样的具体版本。
        // 先选大版本能把子版本列表收窄，选版本时不用在几十个版本号里翻。
        java.util.Set<String> gameVersions = new java.util.LinkedHashSet<>();
        for (RemoteMod.Version v : versions) {
            if (v.getGameVersions() != null) gameVersions.addAll(v.getGameVersions());
        }

        List<String> majors = gameVersions.stream()
                .map(VersionMatcher::majorVersionOf)
                .filter(s -> !s.isEmpty())
                .distinct()
                .sorted(VersionMatcher::compareVersionDesc)
                .collect(Collectors.toList());

        ComboBox<String> majorFilter = new ComboBox<>();
        majorFilter.getItems().add("全部");
        majorFilter.getItems().addAll(majors);
        majorFilter.getSelectionModel().select(0);

        ComboBox<String> minorFilter = new ComboBox<>();
        minorFilter.getItems().add("全部");
        minorFilter.getSelectionModel().select(0);

        ComboBox<String> loaderFilter = new ComboBox<>();
        loaderFilter.getItems().addAll(loaderFilterOptions());
        loaderFilter.getSelectionModel().select(0);

        HBox filterRow = new HBox(12,
                buildFilterGroup("大版本", majorFilter, 150),
                buildFilterGroup("子版本", minorFilter, 190),
                buildFilterGroup("加载器", loaderFilter, 140));
        filterRow.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().add(filterRow);

        Label countLabel = new Label();
        countLabel.getStyleClass().add("config-hint");
        box.getChildren().add(countLabel);

        // 版本列表不再套内层滚动条：滚整个详情页
        VBox list = new VBox(6);
        box.getChildren().add(list);

        Runnable render = () -> {
            String major = comboValue(majorFilter);
            String minor = comboValue(minorFilter);
            String loaderLabel = comboValue(loaderFilter);
            List<RemoteMod.Version> filtered = versions.stream()
                    .filter(v -> VersionMatcher.matchesGameVersionFilter(v, major, minor))
                    .filter(v -> matchesLoaderFilter(v, loaderLabel))
                    .collect(Collectors.toList());
            String scope = !minor.isEmpty() ? minor : (!major.isEmpty() ? major + " 系列" : "全部");
            countLabel.setText("共 " + filtered.size() + " / " + versions.size() + " 个版本（" + scope
                    + "）· 每次显示 " + VERSION_LIST_PAGE + " 个，可点底部按钮继续往下加载");
            if (filtered.isEmpty()) {
                list.getChildren().clear();
                Label none = new Label("没有符合筛选条件的版本");
                none.getStyleClass().add("config-hint");
                list.getChildren().add(none);
                return;
            }
            // 每次只铺 20 条，其余由列表底部的「向下加载更多」按钮按需追加
            list.getChildren().clear();
            renderVersionPage(list, filtered, 0, repo, type, panelTitle, gameDir, currentVersion);
        };

        // 切换大版本时重建子版本列表：只列出该大版本下的具体版本号
        Runnable refreshMinors = () -> {
            String major = comboValue(majorFilter);
            String previous = minorFilter.getSelectionModel().getSelectedItem();
            List<String> minors = new ArrayList<>();
            minors.add("全部");
            if (!major.isEmpty()) {
                gameVersions.stream()
                        .filter(gv -> major.equals(VersionMatcher.majorVersionOf(gv)))
                        .sorted(VersionMatcher::compareVersionDesc)
                        .forEach(minors::add);
            }
            minorFilter.getItems().setAll(minors);
            minorFilter.getSelectionModel().select(minors.contains(previous) ? previous : "全部");
        };

        majorFilter.setOnAction(e -> {
            refreshMinors.run();
            render.run();
        });
        minorFilter.setOnAction(e -> render.run());
        loaderFilter.setOnAction(e -> render.run());
        refreshMinors.run();
        render.run();
        return box;
    }

    /** 版本列表每页条数：一次只渲染这么多，其余靠列表底部的「向下加载更多」按钮追加 */
    private static final int VERSION_LIST_PAGE = 20;

    /**
     * 渲染版本列表的一页，并在还有剩余时于列表底部放一个「向下加载更多」按钮。
     *
     * <p>热门模组有上千个版本，一次铺开会很卡；这里每次只渲染 {@value #VERSION_LIST_PAGE} 条，
     * 用户点一次按钮再往下追加一页，不点就不会继续渲染。
     *
     * @param from 本页起始下标
     */
    private void renderVersionPage(VBox list, List<RemoteMod.Version> filtered, int from,
                                   RemoteModRepository repo, RemoteModRepository.Type type,
                                   String panelTitle, String gameDir, String currentVersion) {
        // 先摘掉上一次留下的「加载更多」行
        if (!list.getChildren().isEmpty()) {
            Node last = list.getChildren().get(list.getChildren().size() - 1);
            if (last.getStyleClass().contains("version-more-row")) {
                list.getChildren().remove(last);
            }
        }

        int to = Math.min(from + VERSION_LIST_PAGE, filtered.size());
        for (int i = from; i < to; i++) {
            list.getChildren().add(createVersionListEntry(
                    filtered.get(i), repo, type, panelTitle, gameDir, currentVersion));
        }

        if (to < filtered.size()) {
            Button more = new Button("向下加载更多（已显示 " + to + " / " + filtered.size() + "）");
            more.getStyleClass().add("btn-primary");
            more.setStyle("-fx-padding: 6 20; -fx-font-size: 12px; -fx-background-radius: 18;");
            more.setMinWidth(Region.USE_PREF_SIZE);
            more.setMaxWidth(Region.USE_PREF_SIZE);
            more.setOnAction(e -> renderVersionPage(list, filtered, to,
                    repo, type, panelTitle, gameDir, currentVersion));
            HBox row = new HBox(more);
            row.setAlignment(Pos.CENTER);
            row.getStyleClass().add("version-more-row");
            list.getChildren().add(row);
        }
    }

    /** 版本列表里的一条（含「下载必需依赖」按钮） */
    private VBox createVersionListEntry(RemoteMod.Version ver, RemoteModRepository repo,
                                        RemoteModRepository.Type type, String panelTitle,
                                        String gameDir, String currentVersion) {
        VBox item = createRemoteVersionItem(ver, repo, type, panelTitle, gameDir, currentVersion, true);
        long required = ver.getDependencies() == null ? 0 : ver.getDependencies().stream()
                .filter(d -> d.getType() == RemoteMod.DependencyType.REQUIRED).count();
        if (required > 0 && repo.getType() == RemoteModRepository.Type.MOD) {
            Button depBtn = new Button("下载必需依赖 (" + required + ")");
            depBtn.getStyleClass().add("btn-primary");
            depBtn.setStyle("-fx-padding: 3 10; -fx-font-size: 11px;");
            depBtn.setMinWidth(Region.USE_PREF_SIZE);
            depBtn.setOnAction(e -> installDependencies(ver, repo, gameDir, currentVersion));
            HBox depRow = new HBox(8, depBtn);
            depRow.setAlignment(Pos.CENTER_LEFT);
            item.getChildren().add(depRow);
        }
        return item;
    }

    /** 全屏查看图库图片：点击任意位置关闭 */
    private void showImageLightbox(String url) {
        if (lightboxOverlay != null) {
            host.rootPane().getChildren().remove(lightboxOverlay);
            lightboxOverlay = null;
        }
        StackPane overlay = new StackPane();
        overlay.setStyle("-fx-background-color: rgba(0,0,0,0.82);");
        overlay.prefWidthProperty().bind(host.rootPane().widthProperty());
        overlay.prefHeightProperty().bind(host.rootPane().heightProperty());

        ImageView big = new ImageView();
        big.setPreserveRatio(true);
        big.setFitWidth(Math.max(320, host.rootPane().getWidth() * 0.8));
        big.setFitHeight(Math.max(240, host.rootPane().getHeight() * 0.8));
        overlay.getChildren().add(big);
        overlay.setOnMouseClicked(e -> {
            host.rootPane().getChildren().remove(overlay);
            lightboxOverlay = null;
        });
        loadRemoteIconAsync(big, url, Math.max(320, host.rootPane().getWidth() * 0.8));

        lightboxOverlay = overlay;
        host.rootPane().getChildren().add(overlay);
        overlay.toFront();
    }

    /**
     * 一键下载某个版本的全部「必需依赖」（对应 VersePc2 版本列表里的依赖一键下载）。
     *
     * <p>解析策略：对每个必需依赖项目取版本列表，优先挑与目标版本的游戏版本 + 加载器都匹配的
     * 最新一个版本，找不到匹配项时回退到该项目的最新版本，保证「装了主模组也能跑起来」。
     */
    private void installDependencies(RemoteMod.Version ver, RemoteModRepository repo,
                                     String gameDir, String currentVersion) {
        installDependencies(ver, repo, gameDir, currentVersion, null);
    }

    /**
     * @param modsDir 已确认的安装目录；为 null 时先弹「选择安装位置」，
     *                与主模组走同一套选择流程（选完再带着结果回调进来）
     */
    private void installDependencies(RemoteMod.Version ver, RemoteModRepository repo,
                                     String gameDir, String currentVersion, Path modsDir) {
        List<RemoteMod.Dependency> required = ver.getDependencies() == null ? List.of()
                : ver.getDependencies().stream()
                .filter(d -> d.getType() == RemoteMod.DependencyType.REQUIRED)
                .collect(Collectors.toList());
        if (required.isEmpty()) {
            host.ui().toast("该版本没有必需依赖");
            return;
        }
        if (modsDir == null) {
            String what = "必需依赖（随 " + (ver.getName() == null || ver.getName().isBlank()
                    ? ver.getVersion() : ver.getName()) + "）";
            ModInstallTargetDialog.show(host, gameDir, currentVersion, what,
                    dir -> installDependencies(ver, repo, gameDir, currentVersion, dir));
            return;
        }
        DownloadTaskCard dialog = downloads.open(
                "下载依赖 · " + (ver.getName() == null ? ver.getVersion() : ver.getName()));
        dialog.stage("解析依赖列表...");

        // modsDir 是参数且从未被改写，可直接被下面的异步任务捕获
        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            int ok = 0;
            int failed = 0;
            try {
                Files.createDirectories(modsDir);
                int index = 0;
                for (RemoteMod.Dependency dep : required) {
                    checkDownloadCancelled(dialog);
                    index++;
                    try {
                        RemoteMod depMod = dep.load(VersionDownloadService.getDownloadProvider());
                        if (depMod == null) {
                            failed++;
                            continue;
                        }
                        List<RemoteMod.Version> depVersions = dep.getSource()
                                .getRemoteVersionsById(VersionDownloadService.getDownloadProvider(),
                                        depMod.getSlug())
                                .collect(Collectors.toList());
                        if (depVersions.isEmpty()) {
                            failed++;
                            continue;
                        }
                        RemoteMod.Version pick = pickBestDependencyVersion(depVersions, ver);
                        if (pick.getFile() == null || pick.getFile().getUrl() == null) {
                            failed++;
                            continue;
                        }
                        String fileName = ArchiveUtils.sanitizeDownloadFileName(pick.getFile().getFilename());
                        if (fileName == null) {
                            failed++;
                            continue;
                        }
                        dialog.stage("依赖 " + index + "/" + required.size() + "：" + depMod.getTitle());
                        dialog.beginFile(fileName);
                        dialog.totalBytes(HttpDownloadEngine.probeFileSize(HttpDownloadEngine.HTTP_CLIENT, pick.getFile().getUrl()));
                        HttpDownloadEngine.downloadContentFile(pick, modsDir.resolve(fileName), dialog::progress, dialog.cancelFlag());
                        dialog.finishFile(true);
                        ok++;
                    } catch (Exception e) {
                        if (HttpDownloadEngine.CANCELLED_MESSAGE.equals(e.getMessage())) throw e;
                        dialog.finishFile(false);
                        dialog.logLine("依赖 " + index + " 失败: " + e.getMessage(), false);
                        failed++;
                    }
                }
                dialog.finish(true, "依赖下载完成：成功 " + ok + " 个"
                        + (failed > 0 ? "，失败 " + failed + " 个" : ""), null);
                Platform.runLater(() -> host.ui().toast("依赖已下载到 " + modsDir));
            } catch (Exception ex) {
                boolean cancelled = HttpDownloadEngine.CANCELLED_MESSAGE.equals(ex.getMessage());
                dialog.finish(false, cancelled ? "已取消下载" : "依赖下载失败: " + ex.getMessage(), null);
            }
        });
    }

    /** 在依赖项目的版本列表中挑与主版本匹配度最高的一版：游戏版本命中优先，其次加载器命中 */
    private static RemoteMod.Version pickBestDependencyVersion(List<RemoteMod.Version> candidates,
                                                               RemoteMod.Version main) {
        List<String> gv = main.getGameVersions() == null ? List.of() : main.getGameVersions();
        List<ModLoaderType> loaders = main.getLoaders() == null ? List.of() : main.getLoaders();
        for (RemoteMod.Version v : candidates) {
            boolean gvOk = v.getGameVersions() != null && !java.util.Collections.disjoint(v.getGameVersions(), gv);
            boolean loaderOk = v.getLoaders() != null && !java.util.Collections.disjoint(v.getLoaders(), loaders);
            if (gvOk && loaderOk) return v;
        }
        for (RemoteMod.Version v : candidates) {
            if (v.getGameVersions() != null && !java.util.Collections.disjoint(v.getGameVersions(), gv)) return v;
        }
        return candidates.get(0);
    }

    // ==================== 下载中心：世界（CurseForge） ====================

    /**
     * 安装世界存档：下载 zip → 解压 → 复制到 {@code saves/<世界名>}。
     * <p>CurseForge 的世界资源是一个 zip，存在两种常见结构：直接是存档内容
     * （顶层即 {@code level.dat} 等），或包一层同名文件夹。这里自动识别：
     * 若解压结果只有一个子目录，则以其为世界根。
     *
     * @param ver 选中的版本（含 zip 直链）
     */
    private void installWorld(RemoteMod.Version ver, String gameDir, String panelTitle,
                              DownloadTaskCard dialog) {
        dialog.stage("下载世界存档...");
        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            Path tempDir = null;
            try {
                tempDir = Files.createTempDirectory("sl-world-");
                Path zipFile = tempDir.resolve("world.zip");
                String zipName = ver.getFile() != null && ver.getFile().getFilename() != null
                        ? ver.getFile().getFilename() : "world.zip";
                dialog.beginFile(zipName);
                dialog.totalBytes(ver.getFile() == null ? -1
                        : HttpDownloadEngine.probeFileSize(HttpDownloadEngine.HTTP_CLIENT, ver.getFile().getUrl()));
                HttpDownloadEngine.downloadContentFile(ver, zipFile, dialog::progress, dialog.cancelFlag());
                dialog.finishFile(true);

                dialog.stage("正在解压...");
                Path extractDir = tempDir.resolve("extracted");
                Files.createDirectories(extractDir);
                ArchiveUtils.unzipTo(zipFile, extractDir);

                // 定位世界根目录：单层包裹时下钻一层
                Path worldRoot = extractDir;
                try (var list = Files.list(extractDir)) {
                    List<Path> children = list.collect(Collectors.toList());
                    if (children.size() == 1 && Files.isDirectory(children.get(0))) {
                        worldRoot = children.get(0);
                    }
                }

                Path savesDir = Paths.get(gameDir, "saves");
                Files.createDirectories(savesDir);
                Path dest = ArchiveUtils.uniqueDir(savesDir, ArchiveUtils.sanitizeWorldName(ver.getName()));
                ArchiveUtils.copyDirectory(worldRoot, dest);

                final boolean hasLevelDat = Files.exists(dest.resolve("level.dat"));
                dialog.finish(true, panelTitle + "「" + ver.getName() + "」已解压到 " + dest
                        + (hasLevelDat ? "" : "（未发现 level.dat，可能不是标准存档）"), null);
                Platform.runLater(() -> host.ui().toast(panelTitle + "「" + ver.getName() + "」下载完成"));
            } catch (Exception ex) {
                boolean cancelled = HttpDownloadEngine.CANCELLED_MESSAGE.equals(ex.getMessage());
                dialog.finish(false, cancelled ? "已取消下载" : "下载失败: " + ex.getMessage(), null);
            } finally {
                if (tempDir != null) {
                    try {
                        ArchiveUtils.deleteRecursively(tempDir);
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    /**
     * 世界存档面板：数据源为 CurseForge（classId 17）。
     * 下载的 zip 由 {@link #installWorld} 解压到 {@code saves/} 目录。
     */
    private Node buildDownloadWorldsPanel() {
        VBox root = new VBox(12);
        root.getChildren().add(buildContentPanel("世界", RemoteModRepository.Type.WORLD,
                List.of(new ModSource("CurseForge", CurseForgeRemoteModRepository.WORLDS))));

        Label hint = new Label("说明：世界存档来自 CurseForge。下载后会自动解压到存档目录（saves）；"
                + "若压缩包内含单一顶层文件夹，则以该文件夹名作为存档名，否则以资源名命名。");
        hint.setWrapText(true);
        hint.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-dim;");
        root.getChildren().add(hint);
        return root;
    }

    // ==================== 下载中心：整合包安装（Modrinth .mrpack / CurseForge zip） ====================

    /**
     * 从远程版本下载并安装整合包。
     * <p>流程：下载整合包本体 → 交给 {@link #installModpackArchive} 完成解压、装原版/加载器、
     * 生成版本 JSON、复制 overrides、下载清单内文件。
     */
    private void installModpack(RemoteMod.Version ver, String gameDir, DownloadTaskCard dialog) {
        String url = ver.getFile() != null ? ver.getFile().getUrl() : null;
        if (url == null) {
            host.ui().toast("该整合包没有可下载的文件");
            return;
        }
        String packName = ver.getName() == null ? ver.getVersion() : ver.getName();
        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            Path tempDir = null;
            try {
                tempDir = Files.createTempDirectory("sl-pack-");
                Path archive = tempDir.resolve("pack.zip");
                dialog.stage("下载整合包主体...");
                dialog.beginFile("整合包主体");
                dialog.logLine("整合包地址: " + url, true);
                dialog.totalBytes(HttpDownloadEngine.probeFileSize(
                        HttpDownloadEngine.HTTP_CLIENT, HttpDownloadEngine.firstContentCandidate(url)));
                // 镜像优先（mcimirror）→ 官方直链兜底，整合包主体通常几十 MB，国内直连 CDN 很慢
                HttpDownloadEngine.downloadWithMirrors(url, archive, dialog::progress, dialog.cancelFlag());
                dialog.finishFile(true);
                dialog.logLine("整合包主体已下载（" + FormatUtils.formatFileSize(Files.size(archive)) + "）", true);
                installModpackArchive(archive, packName, gameDir, dialog);
            } catch (Exception ex) {
                boolean cancelled = HttpDownloadEngine.CANCELLED_MESSAGE.equals(ex.getMessage());
                dialog.logLine("失败: " + ex, false);
                dialog.finish(false, cancelled ? "已取消安装" : "安装失败: " + ex.getMessage(), null);
            } finally {
                if (tempDir != null) {
                    try { ArchiveUtils.deleteRecursively(tempDir); } catch (Exception ignored) {}
                }
            }
        });
    }

    /**
     * 「导入本地整合包」：选择本地的 {@code .mrpack} 或 CurseForge 整合包 {@code .zip} 并安装。
     * <p>走的是和在线安装完全相同的流程，只是跳过「下载整合包主体」这一步。
     */
    private void importLocalModpack() {
        FileChooser fc = new FileChooser();
        fc.setTitle("选择本地整合包文件");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("整合包 (*.mrpack, *.zip)", "*.mrpack", "*.zip"),
                new FileChooser.ExtensionFilter("全部文件", "*.*"));
        File chosen = fc.showOpenDialog(host.stage());
        if (chosen == null) return;

        String gameDir = host.config().getOrDefault("GameDir", ".minecraft");
        String packName = chosen.getName().replaceAll("(?i)\\.(mrpack|zip)$", "");
        DownloadTaskCard dialog = downloads.open("导入整合包 " + packName);
        dialog.stage("读取本地整合包...");
        dialog.logLine("本地文件: " + chosen.getAbsolutePath(), true);
        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            try {
                installModpackArchive(chosen.toPath(), packName, gameDir, dialog);
            } catch (Exception ex) {
                dialog.logLine("失败: " + ex, false);
                dialog.finish(false, "导入失败: " + ex.getMessage(), null);
            }
        });
    }

    /**
     * 安装一个整合包压缩包（远程下载来的或用户本地导入的都走这里）。
     *
     * <p>同时支持两种清单格式：
     * <ul>
     *   <li><b>Modrinth {@code .mrpack}</b>：{@code modrinth.index.json}，
     *       文件带 {@code path} 与 {@code downloads[]} 直链；</li>
     *   <li><b>CurseForge 整合包 {@code .zip}</b>：{@code manifest.json}，
     *       只给出 {@code files[].projectID/fileID}，需要再查 CurseForge API 换直链
     *       （用批量接口一次查完，避免几百次单独请求）。</li>
     * </ul>
     */
    private void installModpackArchive(Path archive, String packName, String gameDir,
                                       DownloadTaskCard dialog) throws Exception {
        Path tempDir = Files.createTempDirectory("sl-pack-x-");
        try {
            dialog.stage("解压整合包...");
            Path extractDir = tempDir.resolve("extracted");
            Files.createDirectories(extractDir);
            ArchiveUtils.unzipTo(archive, extractDir);

            ModpackManifestParser.Progress progress = new ModpackManifestParser.Progress() {
                @Override public void stage(String text) { dialog.stage(text); }
                @Override public void logLine(String text, boolean highlight) { dialog.logLine(text, highlight); }
            };
            PackManifest manifest = ModpackManifestParser.parse(extractDir, progress);
            String mcVersion = resolveMcVersion(manifest.mcVersionRaw(), gameDir);
            if (mcVersion == null) {
                throw new IOException("无法在版本清单中找到兼容的 Minecraft 版本："
                        + manifest.mcVersionRaw());
            }
            String packId = ModpackManifestParser.sanitizePackId(packName);
            dialog.logLine("Minecraft: " + mcVersion + "（清单声明 " + manifest.mcVersionRaw() + "）", true);
            if (manifest.loaderType() != null) {
                dialog.logLine("加载器: " + manifest.loaderType() + " " + manifest.loaderVersion(), true);
            }
            dialog.logLine("清单内文件: " + manifest.files().size() + " 个", true);

            // ===== 1. 确保原版已安装 =====
            Path mcVerDir = Paths.get(gameDir, "versions", mcVersion);
            boolean mcInstalled = Files.isDirectory(mcVerDir)
                    && Files.exists(mcVerDir.resolve(mcVersion + ".json"));
            if (mcInstalled) {
                dialog.logLine("原版 " + mcVersion + " 已安装，跳过", true);
            } else {
                dialog.stage("安装原版 " + mcVersion + "...");
                dialog.beginFile("原版 " + mcVersion);
                VersionDownloadService.VersionInfo vi = findVersionInfo(mcVersion);
                if (vi == null) throw new IOException("无法在版本清单中找到 " + mcVersion);
                boolean ok = InstallEngine.installVanilla(vi, gameDir,
                        VersionDownloadService.getDownloadProvider(),
                        (pct, msg) -> {
                            checkDownloadCancelled(dialog);
                            if (pct >= 0) dialog.progress(pct);
                            dialog.message(msg);
                        });
                dialog.finishFile(ok);
                if (!ok) throw new IOException("原版 " + mcVersion + " 安装失败");
            }

            // ===== 2. 加载器 =====
            String inheritsFrom = mcVersion;
            if (manifest.loaderType() != null) {
                String loaderType = manifest.loaderType();
                String installedId = VersionMatcher.findLoaderVersionId(gameDir, mcVersion, loaderType);
                if (installedId != null) {
                    dialog.logLine(loaderType + " 已安装，跳过", true);
                    inheritsFrom = installedId;
                } else {
                    dialog.stage("安装 " + loaderType + " " + manifest.loaderVersion() + "...");
                    dialog.beginFile(loaderType + " " + manifest.loaderVersion());
                    java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                    final boolean[] okRef = {false};
                    final String[] errRef = {null};
                    UIGeneralControlClass.installLoaderAsync(mcVersion, loaderType,
                            manifest.loaderVersion(), gameDir,
                            host.config().getOrDefault("JavaPath", "java"),
                            (pct, msg) -> {
                                // pct < 0 = 只记一行日志（安装器原始输出），不动进度条
                                if (pct >= 0) dialog.progress(pct);
                                dialog.message(msg);
                                if (msg != null && !msg.isBlank()) dialog.logLine(msg, true);
                            },
                            new UIGeneralControlClass.ResultCallback<Boolean>() {
                                @Override
                                public void onSuccess(Boolean result) {
                                    okRef[0] = result != null && result;
                                    latch.countDown();
                                }

                                @Override
                                public void onError(String error) {
                                    errRef[0] = error;
                                    latch.countDown();
                                }
                            });
                    if (!latch.await(10, java.util.concurrent.TimeUnit.MINUTES)) {
                        throw new IOException(loaderType + " 安装超时");
                    }
                    dialog.finishFile(okRef[0]);
                    if (!okRef[0]) {
                        throw new IOException(loaderType + " 安装失败"
                                + (errRef[0] != null ? ": " + errRef[0] : ""));
                    }
                    String installed = VersionMatcher.findLoaderVersionId(gameDir, mcVersion, loaderType);
                    if (installed == null) throw new IOException(loaderType + " 安装后未找到版本记录");
                    inheritsFrom = installed;
                }
            }

            // ===== 3. 生成 versions/<packId> 版本 JSON =====
            // 壳 JSON（仅 id/inheritsFrom/time/releaseTime/type）作为整合包标记：
            // 启动与资源补全侧会沿 inheritsFrom 链解析出完整视图，不再依赖本文件自身完整；
            // jar 从底层版本复制一份，避免外部工具（无继承解析能力）无法识别
            Path packDir = Paths.get(gameDir, "versions", packId);
            Files.createDirectories(packDir);
            JsonObject packJson = new JsonObject();
            packJson.addProperty("id", packId);
            packJson.addProperty("inheritsFrom", inheritsFrom);
            packJson.addProperty("time", java.time.Instant.now().toString());
            packJson.addProperty("releaseTime", java.time.Instant.now().toString());
            packJson.addProperty("type", "release");
            Files.writeString(packDir.resolve(packId + ".json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(packJson),
                    java.nio.charset.StandardCharsets.UTF_8);
            Path baseJar = Paths.get(gameDir, "versions", inheritsFrom, inheritsFrom + ".jar");
            if (Files.exists(baseJar)) {
                Files.copy(baseJar, packDir.resolve(packId + ".jar"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            // ===== 4. 复制 overrides/ =====
            Path overrides = extractDir.resolve(manifest.overridesDir());
            if (Files.isDirectory(overrides)) {
                dialog.stage("复制整合包配置...");
                dialog.logLine("覆盖目录: " + manifest.overridesDir(), true);
                ArchiveUtils.copyDirectory(overrides, packDir);
            }

            // ===== 5. 下载清单内的文件 =====
            List<PackFile> files = manifest.files();
            if (!files.isEmpty()) {
                dialog.stage("下载整合包文件...");
                dialog.beginFile("整合包文件（共 " + files.size() + " 个）");

                int total = files.size();
                java.util.concurrent.atomic.AtomicInteger doneFiles =
                        new java.util.concurrent.atomic.AtomicInteger(0);
                java.util.concurrent.atomic.AtomicInteger failedFiles =
                        new java.util.concurrent.atomic.AtomicInteger(0);
                java.util.concurrent.atomic.AtomicReference<IOException> dlError =
                        new java.util.concurrent.atomic.AtomicReference<>(null);
                List<java.util.concurrent.Callable<Void>> dlTasks = new java.util.ArrayList<>();

                for (PackFile pf : files) {
                    Path dest = packDir.resolve(pf.relativePath()).normalize();
                    if (!dest.startsWith(packDir)) continue;                 // 路径穿越防护
                    if (Files.exists(dest)) {                                // 已有（overrides 里带来的）
                        doneFiles.incrementAndGet();
                        continue;
                    }
                    Files.createDirectories(dest.getParent());
                    final String fileUrl = pf.url();
                    dlTasks.add(() -> {
                        try {
                            // 清单内文件同样是 cdn.modrinth.com / edge.forgecdn.net 直链，走镜像优先
                            HttpDownloadEngine.downloadWithMirrors(fileUrl, dest,
                                    pct -> {
                                        // 进度按「已完成文件数 + 当前文件百分比」折算成整包进度。
                                        // 注意 doneFiles 必须在任务结束时自增，否则进度永远停在 0。
                                        int done = doneFiles.get();
                                        int overall = (int) ((done + pct / 100.0) * 100 / total);
                                        dialog.progress(Math.max(0, Math.min(99, overall)));
                                        dialog.message("下载整合包文件 "
                                                + Math.min(done + 1, total) + "/" + total);
                                    }, dialog.cancelFlag());
                        } catch (IOException e) {
                            failedFiles.incrementAndGet();
                            dlError.compareAndSet(null, e);
                        } finally {
                            doneFiles.incrementAndGet();
                        }
                        return null;
                    });
                }

                if (!dlTasks.isEmpty()) {
                    java.util.concurrent.ExecutorService filePool =
                            java.util.concurrent.Executors.newFixedThreadPool(Math.min(4, dlTasks.size()), r -> {
                                Thread t = new Thread(r, "sl-dl-file");
                                t.setDaemon(true);
                                return t;
                            });
                    try {
                        for (java.util.concurrent.Future<Void> f : filePool.invokeAll(dlTasks)) {
                            f.get();
                        }
                    } finally {
                        filePool.shutdownNow();
                    }
                }
                IOException err = dlError.get();
                dialog.finishFile(err == null);
                if (err != null) {
                    throw new IOException("整合包文件下载失败（已成功 "
                            + (total - failedFiles.get()) + "/" + total + "）: " + err.getMessage(), err);
                }
                dialog.progress(100);
                dialog.logLine("全部 " + total + " 个文件就绪", true);
            }

            dialog.finish(true, "整合包已安装到 versions/" + packId, null);
            Platform.runLater(() -> host.ui().toast("整合包「" + packName + "」安装完成"));
        } finally {
            try { ArchiveUtils.deleteRecursively(tempDir); } catch (Exception ignored) {}
        }
    }

    /** 从版本反查所属资源（用于取译名）；取不到返回 null */
    private RemoteMod currentModForTitle(RemoteMod.Version ver) {
        if (currentModDetail != null) return currentModDetail.mod();
        if (currentVersionPick != null) return currentVersionPick.mod();
        return null;
    }

    /** 解析整合包声明的 MC 版本（支持 "1.20.x" 通配，优先已安装/清单精确匹配） */
    private String resolveMcVersion(String raw, String gameDir) {
        Path versionsDir = Paths.get(gameDir, "versions");
        if (Files.isDirectory(versionsDir)) {
            try (var stream = Files.list(versionsDir)) {
                List<Path> dirs = stream.filter(Files::isDirectory).collect(Collectors.toList());
                for (Path d : dirs) {
                    String name = d.getFileName().toString();
                    if (Files.exists(d.resolve(name + ".json")) && VersionMatcher.matchesVersion(name, raw)) return name;
                }
            } catch (Exception ignored) {}
        }
        List<VersionDownloadService.VersionInfo> pool = lastVersionInfos;
        if (pool == null || pool.isEmpty()) {
            try {
                pool = VersionDownloadService.fetchVersionManifest();
                lastVersionInfos = pool;
            } catch (Exception e) {
                return null;
            }
        }
        for (VersionDownloadService.VersionInfo vi : pool) {
            if (VersionMatcher.matchesVersion(vi.getId(), raw)) return vi.getId();
        }
        return null;
    }

    /** 在版本清单中查找指定版本 */
    private VersionDownloadService.VersionInfo findVersionInfo(String mcVersion) {
        List<VersionDownloadService.VersionInfo> pool = lastVersionInfos;
        if (pool == null || pool.isEmpty()) {
            try {
                pool = VersionDownloadService.fetchVersionManifest();
                lastVersionInfos = pool;
            } catch (Exception e) {
                return null;
            }
        }
        for (VersionDownloadService.VersionInfo vi : pool) {
            if (vi.getId().equals(mcVersion)) return vi;
        }
        return null;
    }

    // ==================== 下载工具 ====================

    /**
     * 在无法逐步中断的流程（如 {@link InstallEngine} 的分阶段安装）的进度回调里调用，
     * 用户取消时抛出让整条调用链自然退出。
     */
    private void checkDownloadCancelled(DownloadTaskCard task) {
        if (task != null && task.isCancelled()) {
            throw new IllegalStateException(HttpDownloadEngine.CANCELLED_MESSAGE);
        }
    }
}


