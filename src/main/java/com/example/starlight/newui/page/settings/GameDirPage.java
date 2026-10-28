package com.example.starlight.newui.page.settings;

import com.example.starlight.config.GameDirManager;
import com.example.starlight.config.SelectedVersion;
import com.example.starlight.gui.UIGeneralControlClass;
import com.example.starlight.launch.LaunchRecord;
import com.example.starlight.launch.LaunchRecordStore;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.ui.CubicBezierInterpolator;
import com.example.starlight.newui.ui.PageKit;
import com.example.starlight.util.FormatUtils;
import com.example.starlight.util.GameDirInitializer;
import com.example.starlight.util.I18N;
import com.example.starlight.util.ResourceScanner;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.DirectoryChooser;
import javafx.util.Duration;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 设置 → 游戏目录页。
 *
 * <p>版式与交互对齐 {@code references/html/Contents.html}：
 * 居中白色面板、顶部标题/胶囊搜索/添加按钮、当前路径摘要栏、4 行定高单选列表、
 * 双击进入详情视图、添加视图、行内重命名，以及面板入场/列表逐条入场/选中滑条/
 * 悬停操作/校验抖动/视图高度过渡等动画。
 *
 * <p>颜色不照搬 HTML 的固定浅色，而是沿用启动器主题（light / dark / high-contrast），
 * 深色与高对比度样式在 {@code fxml/css/style.css} 的 {@code .gamedir-*} 段里。
 *
 * <p>目录的增删改仍走 {@link GameDirManager}；切换当前目录仍写 {@code GameDir}。
 * 本页只在内存里更新列表与选中态，不重挂整页，因此动画不会被页面重建打断。
 */
public final class GameDirPage {

    private final LauncherContext host;

    // 参考稿尺寸（HTML 变量） //
    private static final double PANEL_MAX_WIDTH = 860;
    private static final double HEADER_HEIGHT = 64;
    private static final double SUMMARY_HEIGHT = 44;
    private static final double ITEM_HEIGHT = 71;
    private static final double ITEM_EDIT_HEIGHT = 56;
    private static final double LIST_HEIGHT = ITEM_HEIGHT * 4;
    private static final double LIST_VIEW_HEIGHT = SUMMARY_HEIGHT + LIST_HEIGHT;
    private static final double SEARCH_COLLAPSED_WIDTH = 32;
    private static final double SEARCH_EXPANDED_WIDTH = 220;

    /** cubic-bezier(0.25, 1, 0.5, 1) */
    private static final Interpolator EASE_SMOOTH = new CubicBezierInterpolator(0.25, 1, 0.5, 1);
    /** cubic-bezier(0.34, 1.56, 0.64, 1)：选中滑条 / 圆点回弹 */
    private static final Interpolator EASE_OUT_BACK = new CubicBezierInterpolator(0.34, 1.56, 0.64, 1);

    /** 占用空间缓存：key = 目录路径，value = {字节数, 写入时刻}；避免每次打开详情都全盘遍历 */
    private static final Map<String, long[]> DISK_SIZE_CACHE = new ConcurrentHashMap<>();
    private static final long DISK_SIZE_TTL_MS = 60_000L;

    // 页面结构 //
    private Node pageRoot;
    private boolean selfSaveInProgress;

    /**
     * 上一次「增删改游戏目录」是否成功。
     *
     * <p>旧实现靠 {@code msg.startsWith("已添加目录")} 判断成败——把用户可见文案当成
     * 控制流标志。界面语言一换，startsWith 永远为 false，成功分支会静默失效
     * （列表不刷新、行不退出编辑态、配置不回读）。这里改为显式状态位，文案只负责显示。
     */
    private boolean lastOpOk;
    private VBox panel;
    private HBox header;
    private Label headerTitle;
    private Button backButton;
    private HBox headerActions;
    private HBox searchPill;
    private Label searchIcon;
    private TextField searchField;
    private Button searchClear;

    private HBox summaryBar;
    private Label summaryPath;
    private ScrollPane listScroll;
    private VBox listBox;
    private StackPane listHost;
    private VBox emptyState;
    private VBox searchEmptyState;
    private Label searchEmptyText;
    private VBox listView;

    private VBox detailView;
    private Label detailName;
    private Label detailPath;
    private Label detailVersions;
    private Label detailSpace;
    private Label detailLast;
    private HBox detailBadge;
    private Label detailBadgeText;

    private VBox addView;
    private TextField addPathField;
    private Label addNamePreview;

    private StackPane bodyHost;
    private Node activeView;
    private boolean listMode = true;
    private Timeline heightTimeline;
    private boolean heightAnimating;

    // 数据与列表 //
    private List<GameDirManager.GameDirEntry> entries = new ArrayList<>();
    private final List<DirRow> rows = new ArrayList<>();
    private String activePath = "";
    private String filterKeyword = "";

    // 搜索 / 动画状态 //
    private boolean searchExpanded;
    private PauseTransition searchCollapseTimer;
    private boolean rowsEntrancePending;
    private boolean panelEnterPlayed;
    private int detailRequestSeq;

    public GameDirPage(LauncherContext host) {
        this.host = host;
    }

    /**
     * 构建「设置 → 游戏目录」页。
     *
     * <p>外层仍是设置页外壳（保留侧边栏隐藏时的下拉导航），只摘掉重复的页面大标题——
     * 标题已经由面板自己的头部承担。
     */
    public Node build() {
        // host.saveConfig() 会重建全部设置页缓存（LauncherView.saveConfig → rebuildSettingsPages），
        // 其中也包括本页。本页切换目录/改名/增删时由自己就地刷新，如果在这里另建一份面板，
        // 正在显示的旧面板就会和缓存里的新面板脱节，后续动画全部作用在不可见的节点上。
        // 因此「本页触发的保存」期间直接复用已构建的根节点，由调用方继续做就地刷新。
        if (selfSaveInProgress && pageRoot != null) {
            return pageRoot;
        }
        resetRuntimeState();
        VBox root = PageKit.settingsPage(host, I18N.get("gamedir.t0001"));
        PageKit.removePageTitle(root);

        panel = buildPanel();
        StackPane wrap = new StackPane(panel);
        // 最简单的方式：让包裹层拿满页面高度，用 StackPane 的 CENTER 对齐把面板居中。
        // LauncherView 已把本页加入 noScroll 并设置 VBox.vgrow，外层不再套 ScrollPane。
        wrap.setAlignment(Pos.CENTER);
        wrap.setMaxWidth(Double.MAX_VALUE);
        wrap.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(wrap, Priority.ALWAYS);
        root.getChildren().add(wrap);

        refreshFromConfig(true);
        showView(listView, "list", false);
        installSceneAnimation();
        pageRoot = root;
        return root;
    }

    /** 同一个 GameDirPage 实例可能被重复 build（侧边栏显隐变化会重建全部设置页），先清掉上一次的运行期状态 */
    private void resetRuntimeState() {
        if (searchCollapseTimer != null) {
            searchCollapseTimer.stop();
            searchCollapseTimer = null;
        }
        if (heightTimeline != null) {
            heightTimeline.stop();
            heightTimeline = null;
        }
        heightAnimating = false;
        activeView = null;
        listMode = true;
        panelEnterPlayed = false;
        rowsEntrancePending = false;
        searchExpanded = false;
        filterKeyword = "";
        detailRequestSeq++;
        rows.clear();
        entries = new ArrayList<>();
        activePath = "";
    }

    // =====================================================================
    // 面板骨架
    // =====================================================================

    private VBox buildPanel() {
        VBox p = new VBox();
        p.getStyleClass().add("gamedir-panel");
        p.setMinWidth(0);
        p.setPrefWidth(PANEL_MAX_WIDTH);
        p.setMaxWidth(PANEL_MAX_WIDTH);
        p.setMinHeight(Region.USE_PREF_SIZE);
        p.setMaxHeight(Region.USE_PREF_SIZE);
        p.setFillWidth(true);

        header = buildHeader();
        bodyHost = new StackPane();
        bodyHost.getStyleClass().add("gamedir-body-host");
        bodyHost.setAlignment(Pos.TOP_LEFT);
        bodyHost.setMinHeight(0);
        bodyHost.setPrefHeight(LIST_VIEW_HEIGHT);
        bodyHost.setMaxHeight(Region.USE_PREF_SIZE);

        // 视图切换时高度会缩到比旧内容更小，裁掉溢出的部分，避免内容盖住页脚
        Rectangle bodyClip = new Rectangle();
        bodyClip.setArcWidth(24);
        bodyClip.setArcHeight(24);
        bodyClip.widthProperty().bind(bodyHost.widthProperty());
        bodyClip.heightProperty().bind(bodyHost.heightProperty());
        bodyHost.setClip(bodyClip);

        listView = buildListView();
        detailView = buildDetailView();
        addView = buildAddView();
        detailView.setVisible(false);
        detailView.setManaged(false);
        addView.setVisible(false);
        addView.setManaged(false);
        bodyHost.getChildren().addAll(listView, detailView, addView);

        bodyHost.widthProperty().addListener((obs, oldW, newW) -> {
            if (activeView == null || activeView == listView || heightAnimating) return;
            Platform.runLater(() -> {
                if (heightAnimating || activeView == null || activeView == listView) return;
                double h = measureViewHeight(activeView);
                if (Math.abs(h - bodyHost.getPrefHeight()) > 1) bodyHost.setPrefHeight(h);
            });
        });

        p.getChildren().addAll(header, bodyHost);
        return p;
    }

    private HBox buildHeader() {
        headerTitle = new Label(I18N.get("gamedir.t0002"));
        headerTitle.getStyleClass().add("gamedir-header-title");

        backButton = new Button(I18N.get("gamedir.t0003"));
        backButton.getStyleClass().add("gamedir-back-top");
        backButton.setFocusTraversable(false);
        backButton.setMouseTransparent(true);
        AppIcons.apply(backButton, "arrow-left", 20, null);
        setVisibleManaged(backButton, false);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        searchPill = buildSearchPill();
        Button addButton = buildAddButton();
        headerActions = new HBox(12, searchPill, addButton);
        headerActions.setAlignment(Pos.CENTER_RIGHT);

        HBox h = new HBox(10, headerTitle, backButton, spacer, headerActions);
        h.getStyleClass().add("gamedir-header");
        h.setAlignment(Pos.CENTER_LEFT);
        h.setPadding(new Insets(0, 24, 0, 24));
        h.setMinHeight(HEADER_HEIGHT);
        h.setPrefHeight(HEADER_HEIGHT);
        h.setMaxHeight(HEADER_HEIGHT);

        // 详情 / 添加视图时整条头部是「返回列表」按钮（与参考稿一致）
        h.setOnMouseClicked(e -> {
            if (!listMode) showView(listView, "list", true);
        });
        return h;
    }

    private HBox buildSearchPill() {
        HBox pill = new HBox();
        pill.getStyleClass().add("gamedir-search");
        pill.setAlignment(Pos.CENTER_LEFT);
        pill.setSpacing(7);
        pill.setPadding(new Insets(0, 7, 0, 7));
        pill.setMinWidth(SEARCH_COLLAPSED_WIDTH);
        pill.setPrefWidth(SEARCH_COLLAPSED_WIDTH);
        pill.setMaxWidth(SEARCH_EXPANDED_WIDTH);
        pill.setMinHeight(32);
        pill.setPrefHeight(32);
        pill.setMaxHeight(32);

        // 圆形胶囊在展开/收起时会裁掉溢出的输入框，避免文字压到面板外
        Rectangle clip = new Rectangle();
        clip.setArcWidth(32);
        clip.setArcHeight(32);
        clip.widthProperty().bind(pill.widthProperty());
        clip.heightProperty().bind(pill.heightProperty());
        pill.setClip(clip);

        searchIcon = new Label();
        searchIcon.getStyleClass().add("gamedir-search-icon");
        searchIcon.setMinSize(16, 16);
        searchIcon.setPrefSize(16, 16);
        searchIcon.setMaxSize(16, 16);
        searchIcon.setMouseTransparent(true);
        AppIcons.apply(searchIcon, "search", 16, null);

        searchField = new TextField();
        searchField.getStyleClass().add("gamedir-search-input");
        searchField.setPromptText(I18N.get("gamedir.t0004"));
        searchField.setMinWidth(0);
        searchField.setPrefWidth(0);
        searchField.setMaxWidth(Double.MAX_VALUE);
        searchField.setOpacity(0);
        searchField.setMouseTransparent(true);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.textProperty().addListener((obs, oldText, newText) -> {
            updateSearchClear();
            applyFilter();
        });
        searchField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                searchField.clear();
                setSearchExpanded(false, true);
            }
        });
        searchField.focusedProperty().addListener((obs, wasFocused, focused) -> {
            if (focused) return;
            if (searchCollapseTimer != null) searchCollapseTimer.stop();
            searchCollapseTimer = new PauseTransition(Duration.millis(150));
            searchCollapseTimer.setOnFinished(e -> {
                if (!searchField.isFocused() && searchField.getText().isBlank()) {
                    setSearchExpanded(false, true);
                }
            });
            searchCollapseTimer.play();
        });

        searchClear = new Button("×");
        searchClear.getStyleClass().add("gamedir-search-clear");
        searchClear.setFocusTraversable(false);
        searchClear.setMinSize(18, 18);
        searchClear.setPrefSize(18, 18);
        searchClear.setMaxSize(18, 18);
        setVisibleManaged(searchClear, false);
        searchClear.setOnAction(e -> {
            searchField.clear();
            searchField.requestFocus();
        });

        pill.getChildren().addAll(searchIcon, searchField, searchClear);
        pill.setOnMouseClicked(e -> {
            if (!searchExpanded) {
                setSearchExpanded(true, true);
            } else if (!isInside(searchField, e.getTarget())) {
                searchField.requestFocus();
            }
        });
        return pill;
    }

    private Button buildAddButton() {
        Button b = new Button(I18N.get("gamedir.t0005"));
        b.getStyleClass().add("gamedir-add-btn");
        b.setMinWidth(Region.USE_PREF_SIZE);
        b.setFocusTraversable(false);
        AppIcons.apply(b, "plus", 14, null);
        b.setOnAction(e -> showAddView());
        installPressScale(b, 0.96);
        return b;
    }

    private VBox buildListView() {
        Label folderIcon = new Label();
        folderIcon.getStyleClass().add("gamedir-summary-icon");
        folderIcon.setMinSize(16, 16);
        folderIcon.setPrefSize(16, 16);
        folderIcon.setMaxSize(16, 16);
        folderIcon.setMouseTransparent(true);
        AppIcons.apply(folderIcon, "folder", 16, null);

        summaryPath = new Label(I18N.get("gamedir.t0006"));
        summaryPath.getStyleClass().add("gamedir-summary-path");
        summaryPath.setMinWidth(0);
        summaryPath.setMaxWidth(Double.MAX_VALUE);
        summaryPath.setTextOverrun(OverrunStyle.ELLIPSIS);
        HBox.setHgrow(summaryPath, Priority.ALWAYS);
        Tooltip pathTip = new Tooltip();
        pathTip.textProperty().bind(summaryPath.textProperty());
        summaryPath.setTooltip(pathTip);

        summaryBar = new HBox(12, folderIcon, summaryPath);
        summaryBar.getStyleClass().add("gamedir-summary");
        summaryBar.setAlignment(Pos.CENTER_LEFT);
        summaryBar.setPadding(new Insets(0, 24, 0, 24));
        summaryBar.setMinHeight(SUMMARY_HEIGHT);
        summaryBar.setPrefHeight(SUMMARY_HEIGHT);
        summaryBar.setMaxHeight(SUMMARY_HEIGHT);

        listBox = new VBox();
        listBox.getStyleClass().add("gamedir-list");

        listScroll = new ScrollPane(listBox);
        listScroll.getStyleClass().add("gamedir-list-scroll");
        listScroll.setFitToWidth(true);
        listScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        listScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        listScroll.setMinHeight(LIST_HEIGHT);
        listScroll.setPrefHeight(LIST_HEIGHT);
        listScroll.setMaxHeight(LIST_HEIGHT);
        listScroll.setFocusTraversable(false);
        listScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        PageKit.attachInertia(host, listScroll);

        emptyState = buildEmptyState("folder", I18N.get("gamedir.t0007"), false);
        searchEmptyState = buildEmptyState("search", I18N.get("gamedir.t0008"), true);
        setVisibleManaged(emptyState, false);
        setVisibleManaged(searchEmptyState, false);

        listHost = new StackPane(listScroll, emptyState, searchEmptyState);
        listHost.getStyleClass().add("gamedir-list-host");
        listHost.setAlignment(Pos.CENTER);
        listHost.setMinHeight(LIST_HEIGHT);
        listHost.setPrefHeight(LIST_HEIGHT);
        listHost.setMaxHeight(LIST_HEIGHT);

        VBox view = new VBox(summaryBar, listHost);
        view.getStyleClass().add("gamedir-list-view");
        view.setFillWidth(true);
        return view;
    }

    private VBox buildEmptyState(String iconName, String text, boolean searchVariant) {
        Label icon = new Label();
        icon.getStyleClass().add("gamedir-empty-icon");
        icon.setMinSize(40, 40);
        icon.setPrefSize(40, 40);
        icon.setMaxSize(40, 40);
        icon.setMouseTransparent(true);
        AppIcons.apply(icon, iconName, 40, null);

        Label label = new Label(text);
        label.getStyleClass().add("gamedir-empty-text");
        label.setWrapText(true);
        label.setMaxWidth(420);
        label.setAlignment(Pos.CENTER);
        if (searchVariant) searchEmptyText = label;

        VBox box = new VBox(12, icon, label);
        box.getStyleClass().add("gamedir-empty");
        box.setAlignment(Pos.CENTER);
        box.setMinHeight(LIST_HEIGHT);
        box.setPrefHeight(LIST_HEIGHT);
        box.setMaxHeight(LIST_HEIGHT);
        return box;
    }

    // =====================================================================
    // 详情视图
    // =====================================================================

    private VBox buildDetailView() {
        Label detailIcon = new Label();
        detailIcon.getStyleClass().add("gamedir-detail-icon");
        detailIcon.setMinSize(32, 32);
        detailIcon.setPrefSize(32, 32);
        detailIcon.setMaxSize(32, 32);
        detailIcon.setMouseTransparent(true);
        AppIcons.apply(detailIcon, "folder", 32, null);

        StackPane iconWrap = new StackPane(detailIcon);
        iconWrap.getStyleClass().add("gamedir-detail-icon-wrap");
        iconWrap.setMinSize(64, 64);
        iconWrap.setPrefSize(64, 64);
        iconWrap.setMaxSize(64, 64);

        detailName = new Label();
        detailName.getStyleClass().add("gamedir-detail-title");
        detailName.setWrapText(true);
        detailName.setMinWidth(0);
        detailName.setMaxWidth(Double.MAX_VALUE);

        Region badgeDot = new Region();
        badgeDot.getStyleClass().add("gamedir-detail-badge-dot");
        badgeDot.setMinSize(6, 6);
        badgeDot.setPrefSize(6, 6);
        badgeDot.setMaxSize(6, 6);

        detailBadgeText = new Label(I18N.get("gamedir.t0009"));
        detailBadgeText.getStyleClass().add("gamedir-detail-badge-text");
        detailBadge = new HBox(6, badgeDot, detailBadgeText);
        detailBadge.getStyleClass().add("gamedir-detail-badge");
        detailBadge.setAlignment(Pos.CENTER_LEFT);
        // VBox 默认 fillWidth 会把子节点拉满整行；锁成固有宽度才是参考稿里的紧凑胶囊徽标
        detailBadge.setMinWidth(Region.USE_PREF_SIZE);
        detailBadge.setMaxWidth(Region.USE_PREF_SIZE);

        VBox titleInfo = new VBox(6, detailName, detailBadge);
        titleInfo.setAlignment(Pos.CENTER_LEFT);
        titleInfo.setMinWidth(0);
        titleInfo.setMaxHeight(Region.USE_PREF_SIZE);
        HBox.setHgrow(titleInfo, Priority.ALWAYS);

        HBox headerCard = new HBox(20, iconWrap, titleInfo);
        headerCard.getStyleClass().add("gamedir-detail-header-card");
        headerCard.setAlignment(Pos.CENTER_LEFT);

        Label pathLabel = new Label(I18N.get("gamedir.t0010"));
        pathLabel.getStyleClass().add("gamedir-detail-label");
        detailPath = new Label();
        detailPath.getStyleClass().add("gamedir-detail-path");
        detailPath.setWrapText(true);
        detailPath.setMinWidth(0);
        detailPath.setMaxWidth(Double.MAX_VALUE);
        VBox pathCard = new VBox(8, pathLabel, detailPath);
        pathCard.getStyleClass().add("gamedir-detail-path-card");

        detailVersions = detailValueLabel(null);
        detailSpace = detailValueLabel(null);
        detailLast = detailValueLabel("gamedir-detail-grid-value-time");

        HBox grid = new HBox(12,
                detailGridItem(I18N.get("gamedir.t0011"), detailVersions),
                detailGridItem(I18N.get("gamedir.t0012"), detailSpace),
                detailGridItem(I18N.get("gamedir.t0013"), detailLast));
        for (Node item : grid.getChildren()) HBox.setHgrow(item, Priority.ALWAYS);

        VBox view = new VBox(24, headerCard, pathCard, grid);
        view.getStyleClass().add("gamedir-detail");
        view.setPadding(new Insets(24));
        view.setFillWidth(true);
        view.setMaxWidth(Double.MAX_VALUE);
        return view;
    }

    private Label detailValueLabel(String extraClass) {
        Label l = new Label("—");
        l.getStyleClass().add("gamedir-detail-grid-value");
        if (extraClass != null) l.getStyleClass().add(extraClass);
        l.setMinWidth(0);
        l.setMaxWidth(Double.MAX_VALUE);
        l.setWrapText(true);
        return l;
    }

    private VBox detailGridItem(String labelText, Label value) {
        Label l = new Label(labelText);
        l.getStyleClass().add("gamedir-detail-grid-label");
        VBox box = new VBox(6, l, value);
        box.getStyleClass().add("gamedir-detail-grid-item");
        box.setPadding(new Insets(16));
        box.setMinWidth(0);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    // =====================================================================
    // 添加视图
    // =====================================================================

    private VBox buildAddView() {
        Label pathLabel = new Label(I18N.get("gamedir.t0014"));
        pathLabel.getStyleClass().add("gamedir-form-label");

        addPathField = new TextField();
        addPathField.getStyleClass().add("gamedir-input");
        addPathField.setPromptText(I18N.get("gamedir.t0015"));
        addPathField.setMinWidth(0);
        addPathField.setPrefHeight(40);
        addPathField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(addPathField, Priority.ALWAYS);
        addPathField.textProperty().addListener((obs, oldText, newText) -> {
            addPathField.getStyleClass().remove("gamedir-error");
            updateAddNamePreview();
        });
        addPathField.setOnAction(e -> commitAddDirectory());

        Button browse = new Button(I18N.get("gamedir.t0016"));
        browse.getStyleClass().add("gamedir-browse-btn");
        browse.setMinWidth(Region.USE_PREF_SIZE);
        browse.setFocusTraversable(false);
        AppIcons.apply(browse, "folder", 16, null);
        browse.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle(I18N.get("gamedir.t0017"));
            File f = dc.showDialog(host.stage());
            if (f != null) {
                addPathField.setText(f.getAbsolutePath());
                addPathField.positionCaret(addPathField.getText().length());
            }
        });
        installPressScale(browse, 0.96);

        HBox pathGroup = new HBox(8, addPathField, browse);
        pathGroup.getStyleClass().add("gamedir-path-group");
        pathGroup.setAlignment(Pos.CENTER_LEFT);

        VBox pathGroupBox = new VBox(8, pathLabel, pathGroup);
        pathGroupBox.getStyleClass().add("gamedir-form-group");

        Label nameLabel = new Label(I18N.get("gamedir.t0018"));
        nameLabel.getStyleClass().add("gamedir-form-label");

        Label previewIcon = new Label();
        previewIcon.getStyleClass().add("gamedir-name-preview-icon");
        previewIcon.setMinSize(20, 20);
        previewIcon.setPrefSize(20, 20);
        previewIcon.setMaxSize(20, 20);
        previewIcon.setMouseTransparent(true);
        AppIcons.apply(previewIcon, "folder", 20, null);

        addNamePreview = new Label(I18N.get("gamedir.t0019"));
        addNamePreview.getStyleClass().add("gamedir-name-preview-text");
        addNamePreview.getStyleClass().add("gamedir-name-preview-empty");
        addNamePreview.setMinWidth(0);
        addNamePreview.setMaxWidth(Double.MAX_VALUE);
        addNamePreview.setTextOverrun(OverrunStyle.ELLIPSIS);

        HBox namePreview = new HBox(12, previewIcon, addNamePreview);
        namePreview.getStyleClass().add("gamedir-name-preview");
        namePreview.setAlignment(Pos.CENTER_LEFT);
        namePreview.setPadding(new Insets(16));

        VBox nameGroup = new VBox(8, nameLabel, namePreview);
        nameGroup.getStyleClass().add("gamedir-form-group");

        Button save = new Button(I18N.get("gamedir.t0020"));
        save.getStyleClass().add("gamedir-save-new");
        save.setMinWidth(Region.USE_PREF_SIZE);
        save.setOnAction(e -> commitAddDirectory());
        installPressScale(save, 0.96);

        HBox actions = new HBox(save);
        actions.getStyleClass().add("gamedir-form-actions");
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox view = new VBox(24, pathGroupBox, nameGroup, actions);
        view.getStyleClass().add("gamedir-add");
        view.setPadding(new Insets(24));
        view.setFillWidth(true);
        view.setMaxWidth(Double.MAX_VALUE);
        return view;
    }

    // =====================================================================
    // 视图切换 / 高度动画
    // =====================================================================

    private void showDetailView(GameDirManager.GameDirEntry entry) {
        cancelAllEdits();
        resetSearch();
        detailRequestSeq++;
        boolean active = GameDirManager.samePath(entry.getPath(), activePath);
        detailName.setText(entry.getName());
        detailPath.setText(entry.getDisplayPath());
        detailBadgeText.setText(active ? I18N.get("gamedir.t0021") : I18N.get("gamedir.t0022"));
        detailBadge.getStyleClass().remove("gamedir-detail-badge-inactive");
        if (!active) detailBadge.getStyleClass().add("gamedir-detail-badge-inactive");
        fillDetailData(entry, detailRequestSeq);
        showView(detailView, "detail", true);
    }

    private void showAddView() {
        cancelAllEdits();
        resetSearch();
        addPathField.setText("");
        addPathField.getStyleClass().remove("gamedir-error");
        updateAddNamePreview();
        showView(addView, "add", true);
        PauseTransition focus = new PauseTransition(Duration.millis(120));
        focus.setOnFinished(e -> addPathField.requestFocus());
        focus.play();
    }

    private void showView(Node view, String mode, boolean animate) {
        listMode = "list".equals(mode);
        setMode(mode);
        if (view == listView) {
            updateSummary();
        }

        Node old = activeView;
        if (old == view) {
            if (animate) animateBodyHeight(measureViewHeight(view));
            return;
        }

        view.setVisible(true);
        view.setManaged(true);
        view.setOpacity(animate ? 0 : 1);
        view.toFront();

        if (old != null && animate) {
            FadeTransition out = new FadeTransition(Duration.millis(150), old);
            out.setToValue(0);
            out.setOnFinished(e -> {
                if (activeView != old) {
                    old.setVisible(false);
                    old.setManaged(false);
                }
            });
            out.play();

            FadeTransition in = new FadeTransition(Duration.millis(200), view);
            in.setToValue(1);
            in.setDelay(Duration.millis(60));
            in.play();
        } else if (old != null) {
            old.setVisible(false);
            old.setManaged(false);
        }

        activeView = view;
        double target = measureViewHeight(view);
        if (animate) {
            animateBodyHeight(target);
        } else {
            if (heightTimeline != null) heightTimeline.stop();
            heightAnimating = false;
            bodyHost.setPrefHeight(target);
        }
    }

    private void setMode(String mode) {
        boolean list = "list".equals(mode);
        boolean detail = "detail".equals(mode);
        boolean add = "add".equals(mode);

        panel.getStyleClass().removeAll("is-detail", "is-add");
        if (detail) panel.getStyleClass().add("is-detail");
        if (add) panel.getStyleClass().add("is-add");

        setVisibleManaged(headerTitle, list);
        setVisibleManaged(backButton, !list);
        setVisibleManaged(headerActions, list);
        header.setCursor(list ? Cursor.DEFAULT : Cursor.HAND);
    }

    private void animateBodyHeight(double target) {
        if (heightTimeline != null) heightTimeline.stop();
        double start = bodyHost.getHeight();
        if (start <= 0) start = bodyHost.getPrefHeight();
        if (Math.abs(start - target) < 0.5) {
            bodyHost.setPrefHeight(target);
            heightAnimating = false;
            return;
        }
        heightAnimating = true;
        heightTimeline = new Timeline(new KeyFrame(Duration.millis(400),
                new KeyValue(bodyHost.prefHeightProperty(), Math.max(1, target), EASE_SMOOTH)));
        heightTimeline.setOnFinished(e -> {
            heightAnimating = false;
            bodyHost.setPrefHeight(Math.max(1, target));
        });
        heightTimeline.play();
    }

    private double measureViewHeight(Node view) {
        if (view == listView) return LIST_VIEW_HEIGHT;
        double width = bodyHost != null && bodyHost.getWidth() > 0
                ? bodyHost.getWidth() : PANEL_MAX_WIDTH;
        if (view instanceof Region region) {
            region.applyCss();
            double h = region.prefHeight(width);
            if (h > 0) return Math.ceil(h);
        }
        return LIST_VIEW_HEIGHT;
    }

    // =====================================================================
    // 列表渲染 / 搜索
    // =====================================================================

    private void refreshFromConfig(boolean animateEntrance) {
        entries = GameDirManager.load(host.config());
        activePath = GameDirManager.activePath(host.config());
        renderList(animateEntrance);
        updateSummary();
    }

    private void renderList(boolean animateEntrance) {
        for (DirRow row : rows) {
            if (row.clickTimer != null) row.clickTimer.stop();
        }
        rows.clear();
        listBox.getChildren().clear();

        for (int i = 0; i < entries.size(); i++) {
            DirRow row = createRow(entries.get(i), i == entries.size() - 1);
            rows.add(row);
            listBox.getChildren().add(row.node);
        }

        applyFilter();
        updateSelection(false);

        if (animateEntrance) {
            for (DirRow row : rows) {
                row.node.setOpacity(0);
                row.node.setTranslateY(-10);
            }
            if (panel != null && panel.getScene() != null) {
                playRowsEntrance();
            } else {
                rowsEntrancePending = true;
            }
        } else {
            for (DirRow row : rows) {
                row.node.setOpacity(1);
                row.node.setTranslateY(0);
            }
        }
    }

    private DirRow createRow(GameDirManager.GameDirEntry entry, boolean last) {
        DirRow row = new DirRow(entry);

        row.hoverBg = new Region();
        row.hoverBg.getStyleClass().add("gamedir-item-hover");
        row.hoverBg.setOpacity(0);
        row.hoverBg.setMouseTransparent(true);

        row.selectedBg = new Region();
        row.selectedBg.getStyleClass().add("gamedir-item-selected");
        row.selectedBg.setOpacity(0);
        row.selectedBg.setMouseTransparent(true);

        row.newHighlight = new Region();
        row.newHighlight.getStyleClass().add("gamedir-new-highlight");
        row.newHighlight.setOpacity(0);
        row.newHighlight.setMouseTransparent(true);

        row.accent = new Region();
        row.accent.getStyleClass().add("gamedir-item-accent");
        row.accent.setMinWidth(3);
        row.accent.setPrefWidth(3);
        row.accent.setMaxWidth(3);
        row.accent.setMaxHeight(Double.MAX_VALUE);
        row.accent.setScaleY(0);
        row.accent.setMouseTransparent(true);

        row.radio = new StackPane();
        row.radio.getStyleClass().add("gamedir-radio");
        row.radio.setMinSize(16, 16);
        row.radio.setPrefSize(16, 16);
        row.radio.setMaxSize(16, 16);
        row.radioDot = new Region();
        row.radioDot.getStyleClass().add("gamedir-radio-dot");
        row.radioDot.setMinSize(6, 6);
        row.radioDot.setPrefSize(6, 6);
        row.radioDot.setMaxSize(6, 6);
        row.radioDot.setScaleX(0);
        row.radioDot.setScaleY(0);
        row.radio.getChildren().add(row.radioDot);

        row.title = new Label(entry.getName());
        row.title.getStyleClass().add("gamedir-item-title");
        row.title.setMinWidth(0);
        row.title.setMaxWidth(Double.MAX_VALUE);
        row.title.setTextOverrun(OverrunStyle.ELLIPSIS);

        row.desc = new Label();
        row.desc.getStyleClass().add("gamedir-item-desc");
        row.desc.setMinWidth(0);
        row.desc.setMaxWidth(Double.MAX_VALUE);
        row.desc.setTextOverrun(OverrunStyle.ELLIPSIS);
        fillDescAsync(row);

        VBox content = new VBox(3, row.title, row.desc);
        content.getStyleClass().add("gamedir-item-info");
        content.setMinWidth(0);
        content.setMaxWidth(Double.MAX_VALUE);
        // HBox 默认 fillHeight：不锁最大高度的话 VBox 会被拉满整行，
        // 标题/说明就顶在行首，和垂直居中的单选圆点错位。
        content.setMaxHeight(Region.USE_PREF_SIZE);
        content.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(content, Priority.ALWAYS);

        Region badgeDot = new Region();
        badgeDot.getStyleClass().add("gamedir-badge-dot");
        badgeDot.setMinSize(5, 5);
        badgeDot.setPrefSize(5, 5);
        badgeDot.setMaxSize(5, 5);
        Label badgeText = new Label(I18N.get("gamedir.t0023"));
        badgeText.getStyleClass().add("gamedir-badge-text");
        row.badge = new HBox(4, badgeDot, badgeText);
        row.badge.getStyleClass().add("gamedir-badge");
        row.badge.setAlignment(Pos.CENTER_LEFT);
        row.badge.setOpacity(0);
        row.badge.setScaleX(0.85);
        row.badge.setScaleY(0.85);

        HBox status = new HBox(row.badge);
        status.getStyleClass().add("gamedir-status");
        status.setAlignment(Pos.CENTER_RIGHT);
        status.setMinWidth(64);
        status.setPrefWidth(64);
        status.setMaxWidth(64);
        row.status = status;

        Button rename = iconButton("edit", I18N.get("gamedir.t0024"), false, () -> startEdit(row));
        Button open = iconButton("folder-open", I18N.get("gamedir.t0025"), false, () -> {
            if (!row.entry.exists()) {
                host.ui().toast(I18N.get("gamedir.k0001", row.entry.getDisplayPath()));
                return;
            }
            host.openFile(row.entry.getPath());
        });
        Button remove = iconButton("trash", I18N.get("gamedir.t0026"), true, () -> confirmRemove(row));
        row.actions = new HBox(2, rename, open, remove);
        row.actions.getStyleClass().add("gamedir-actions");
        row.actions.setAlignment(Pos.CENTER_RIGHT);
        row.actions.setOpacity(0);
        row.actions.setTranslateX(5);
        row.actions.setMouseTransparent(true);

        row.content = new HBox(12, row.radio, content, status, row.actions);
        row.content.getStyleClass().add("gamedir-item-content");
        row.content.setAlignment(Pos.CENTER_LEFT);
        row.content.setPadding(new Insets(0, 24, 0, 24));
        row.content.setMaxWidth(Double.MAX_VALUE);
        row.content.setMaxHeight(Double.MAX_VALUE);

        Label editLabel = new Label(I18N.get("gamedir.t0027"));
        editLabel.getStyleClass().add("gamedir-edit-label");
        row.editInput = new TextField(entry.getName());
        row.editInput.getStyleClass().add("gamedir-edit-input");
        row.editInput.setMinWidth(0);
        row.editInput.setPrefHeight(34);
        row.editInput.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(row.editInput, Priority.ALWAYS);
        Button save = new Button(I18N.get("gamedir.t0028"));
        save.getStyleClass().add("gamedir-edit-save");
        save.setMinWidth(Region.USE_PREF_SIZE);
        save.setFocusTraversable(false);
        Button cancel = new Button(I18N.get("gamedir.t0029"));
        cancel.getStyleClass().add("gamedir-edit-cancel");
        cancel.setMinWidth(Region.USE_PREF_SIZE);
        cancel.setFocusTraversable(false);
        save.setOnAction(e -> commitEdit(row));
        cancel.setOnAction(e -> cancelEdit(row));
        row.editInput.setOnAction(e -> commitEdit(row));
        row.editInput.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) cancelEdit(row);
        });
        installPressScale(save, 0.96);
        installPressScale(cancel, 0.96);

        row.editForm = new HBox(10, editLabel, row.editInput, save, cancel);
        row.editForm.getStyleClass().add("gamedir-edit-form");
        row.editForm.setAlignment(Pos.CENTER_LEFT);
        row.editForm.setPadding(new Insets(0, 24, 0, 24));
        row.editForm.setMaxWidth(Double.MAX_VALUE);
        row.editForm.setMaxHeight(Double.MAX_VALUE);
        setVisibleManaged(row.editForm, false);

        row.node = new StackPane(row.hoverBg, row.selectedBg, row.newHighlight,
                row.accent, row.content, row.editForm);
        row.node.getStyleClass().add("gamedir-item");
        if (last) row.node.getStyleClass().add("gamedir-item-last");
        row.node.setAlignment(Pos.CENTER_LEFT);
        row.node.setMinHeight(ITEM_HEIGHT);
        row.node.setPrefHeight(ITEM_HEIGHT);
        row.node.setMaxHeight(Region.USE_PREF_SIZE);
        row.node.setMaxWidth(Double.MAX_VALUE);

        row.node.setOnMouseEntered(e -> {
            if (row.editing) return;
            animateOpacity(row.hoverBg, 1, 160);
            if (row.selected) setActionsVisible(row, true);
            else setActionsVisible(row, true);
        });
        row.node.setOnMouseExited(e -> {
            if (row.editing) return;
            animateOpacity(row.hoverBg, 0, 160);
            if (!row.selected) setActionsVisible(row, false);
        });
        row.node.setOnMouseClicked(e -> {
            if (row.editing || isInteractive(e.getTarget())) return;
            if (e.getClickCount() >= 2) {
                if (row.clickTimer != null) row.clickTimer.stop();
                showDetailView(row.entry);
                return;
            }
            if (row.clickTimer != null) row.clickTimer.stop();
            row.clickTimer = new PauseTransition(Duration.millis(200));
            row.clickTimer.setOnFinished(ev -> selectDirectory(row));
            row.clickTimer.play();
        });

        return row;
    }

    private Button iconButton(String icon, String tip, boolean danger, Runnable action) {
        Button b = new Button();
        b.getStyleClass().add("gamedir-icon-btn");
        if (danger) b.getStyleClass().add("gamedir-icon-btn-danger");
        b.setMinSize(30, 30);
        b.setPrefSize(30, 30);
        b.setMaxSize(30, 30);
        b.setFocusTraversable(false);
        AppIcons.apply(b, icon, 16, null);
        b.setTooltip(new Tooltip(tip));
        b.setOnAction(e -> action.run());
        installPressScale(b, 0.9);
        return b;
    }

    private void fillDescAsync(DirRow row) {
        GameDirManager.GameDirEntry entry = row.entry;
        String displayPath = entry.getDisplayPath();
        if (!entry.exists()) {
            row.desc.setText(I18N.get("gamedir.p0049", displayPath));
            return;
        }
        Integer cached = GameDirManager.cachedVersionCount(entry.getPath());
        if (cached != null) {
            row.desc.setText(displayPath + (cached > 0
                    ? I18N.get("gamedir.r0001", cached) : I18N.get("gamedir.t0030")));
            return;
        }
        row.desc.setText(I18N.get("gamedir.p0050", displayPath));
        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            int versions = entry.countVersions();
            GameDirManager.putVersionCount(entry.getPath(), versions);
            Platform.runLater(() -> {
                if (row.desc == null) return;
                row.desc.setText(displayPath + (versions > 0
                        ? I18N.get("gamedir.r0002", versions) : I18N.get("gamedir.t0031")));
            });
        });
    }

    private void applyFilter() {
        filterKeyword = searchField == null ? "" : searchField.getText().trim();
        String lower = filterKeyword.toLowerCase(Locale.ROOT);
        int visible = 0;
        for (DirRow row : rows) {
            boolean match = row.editing || lower.isEmpty()
                    || (row.entry.getName() + " " + row.entry.getDisplayPath())
                    .toLowerCase(Locale.ROOT).contains(lower);
            setVisibleManaged(row.node, match);
            if (match) visible++;
        }

        boolean empty = entries.isEmpty();
        boolean noMatch = !empty && visible == 0 && !lower.isEmpty();
        setVisibleManaged(listScroll, !empty && !noMatch);
        setVisibleManaged(emptyState, empty);
        setVisibleManaged(searchEmptyState, noMatch);
        if (noMatch && searchEmptyText != null) {
            searchEmptyText.setText(I18N.get("gamedir.p0051", filterKeyword));
        }
    }

    private void updateSearchClear() {
        boolean hasText = searchField != null && !searchField.getText().isBlank();
        setVisibleManaged(searchClear, hasText && searchExpanded);
    }

    private void setSearchExpanded(boolean expand, boolean animate) {
        if (searchExpanded == expand) {
            if (expand && searchField != null) searchField.requestFocus();
            return;
        }
        searchExpanded = expand;
        searchPill.getStyleClass().remove("expanded");
        if (expand) searchPill.getStyleClass().add("expanded");
        searchPill.setCursor(expand ? Cursor.TEXT : Cursor.HAND);
        updateSearchClear();

        double target = expand ? SEARCH_EXPANDED_WIDTH : SEARCH_COLLAPSED_WIDTH;
        Timeline widthAnim = new Timeline(new KeyFrame(
                animate ? Duration.millis(expand ? 350 : 300) : Duration.ZERO,
                new KeyValue(searchPill.prefWidthProperty(), target, EASE_SMOOTH)));
        widthAnim.play();

        Timeline iconAnim = new Timeline(new KeyFrame(
                animate ? Duration.millis(350) : Duration.ZERO,
                new KeyValue(searchIcon.translateXProperty(), expand ? 5 : 0, EASE_SMOOTH)));
        iconAnim.play();

        if (expand) {
            searchField.setMouseTransparent(false);
            FadeTransition in = new FadeTransition(Duration.millis(180), searchField);
            in.setToValue(1);
            in.setDelay(Duration.millis(100));
            in.play();
            PauseTransition focus = new PauseTransition(Duration.millis(50));
            focus.setOnFinished(e -> {
                if (searchExpanded) searchField.requestFocus();
            });
            focus.play();
        } else {
            searchField.setMouseTransparent(true);
            FadeTransition out = new FadeTransition(Duration.millis(120), searchField);
            out.setToValue(0);
            out.play();
            if (!searchField.getText().isEmpty()) searchField.clear();
            updateSearchClear();
            applyFilter();
        }
    }

    private void resetSearch() {
        if (searchField == null) return;
        if (searchExpanded) {
            if (!searchField.getText().isEmpty()) searchField.clear();
            setSearchExpanded(false, false);
        } else {
            searchField.clear();
        }
        updateSearchClear();
        applyFilter();
    }

    // =====================================================================
    // 选中 / 切换当前目录
    // =====================================================================

    private void selectDirectory(DirRow row) {
        if (row.editing) return;
        if (GameDirManager.samePath(row.entry.getPath(), activePath)) return;

        int beforeCount = entries.size();
        String msg = doSwitchGameDir(row.entry.getPath(), null);
        entries = GameDirManager.load(host.config());
        activePath = GameDirManager.activePath(host.config());

        if (entries.size() != beforeCount) {
            refreshFromConfig(false);
        } else {
            updateSelection(true);
            updateSummary();
        }
        host.ui().toast(msg);
    }

    private void updateSelection(boolean animate) {
        for (DirRow row : rows) {
            setRowSelected(row, GameDirManager.samePath(row.entry.getPath(), activePath), animate);
        }
        updateSummary();
    }

    private void setRowSelected(DirRow row, boolean selected, boolean animate) {
        row.selected = selected;
        row.node.getStyleClass().remove("selected");
        if (selected) row.node.getStyleClass().add("selected");

        if (animate) {
            animateOpacity(row.selectedBg, selected ? 1 : 0, 200);
            animateScaleY(row.accent, selected ? 1 : 0, 250, EASE_OUT_BACK);
            animateScale(row.radioDot, selected ? 1 : 0, 250, EASE_OUT_BACK);
            animateOpacity(row.badge, selected ? 1 : 0, 200);
            animateScale(row.badge, selected ? 1 : 0.85, 200, EASE_OUT_BACK);
        } else {
            row.selectedBg.setOpacity(selected ? 1 : 0);
            row.accent.setScaleY(selected ? 1 : 0);
            row.radioDot.setScaleX(selected ? 1 : 0);
            row.radioDot.setScaleY(selected ? 1 : 0);
            row.badge.setOpacity(selected ? 1 : 0);
            row.badge.setScaleX(selected ? 1 : 0.85);
            row.badge.setScaleY(selected ? 1 : 0.85);
        }
        setActionsVisible(row, selected || row.node.isHover());
    }

    private void updateSummary() {
        if (summaryPath == null) return;
        summaryPath.setText(activePath == null || activePath.isBlank()
                ? I18N.get("gamedir.t0032") : GameDirManager.resolve(activePath));
    }

    private void setActionsVisible(DirRow row, boolean visible) {
        row.actions.setMouseTransparent(!visible);
        animateOpacity(row.actions, visible ? 1 : 0, 150);
        animateTranslateX(row.actions, visible ? 0 : 5, 150);
    }

    // =====================================================================
    // 行内重命名
    // =====================================================================

    private void startEdit(DirRow row) {
        if (row.editing) return;
        for (DirRow other : rows) {
            if (other != row && other.editing) cancelEdit(other);
        }
        row.editing = true;
        row.node.getStyleClass().add("editing");
        row.editInput.setText(row.entry.getName());
        row.editInput.getStyleClass().remove("gamedir-error");
        setVisibleManaged(row.content, false);
        setVisibleManaged(row.editForm, true);
        row.editForm.setOpacity(0);
        row.editForm.setTranslateX(10);
        animateOpacity(row.editForm, 1, 200);
        animateTranslateX(row.editForm, 0, 200);
        animateOpacity(row.hoverBg, 0, 120);
        animateOpacity(row.selectedBg, 0, 120);
        animateScaleY(row.accent, 0, 120, EASE_SMOOTH);

        row.node.setMinHeight(ITEM_EDIT_HEIGHT);
        Timeline resize = new Timeline(new KeyFrame(Duration.millis(250),
                new KeyValue(row.node.prefHeightProperty(), ITEM_EDIT_HEIGHT, EASE_SMOOTH)));
        resize.play();

        PauseTransition focus = new PauseTransition(Duration.millis(100));
        focus.setOnFinished(e -> {
            row.editInput.requestFocus();
            row.editInput.selectAll();
        });
        focus.play();
    }

    private void commitEdit(DirRow row) {
        String name = row.editInput.getText() == null ? "" : row.editInput.getText().trim();
        String sanitized = GameDirManager.sanitizeName(name);
        if (sanitized.isEmpty()) {
            showEditError(row);
            return;
        }
        if (sanitized.equals(row.entry.getName())) {
            cancelEdit(row);
            return;
        }
        String msg = renameGameDir(row.entry.getPath(), sanitized);
        if (lastOpOk) {
            // GameDirManager.store 会对重名追加 (2)：以落盘后的实际名称为准，避免行内显示与配置不一致
            List<GameDirManager.GameDirEntry> latest = GameDirManager.load(host.config());
            int index = GameDirManager.indexOf(latest, row.entry.getPath());
            String actualName = index >= 0 ? latest.get(index).getName() : sanitized;
            entries = latest;
            row.entry = new GameDirManager.GameDirEntry(actualName, row.entry.getPath());
            row.title.setText(actualName);
            cancelEdit(row);
            applyFilter();
            host.ui().toast(I18N.get("gamedir.renamedToast", actualName));
        } else {
            host.ui().toast(msg);
        }
    }

    private void cancelEdit(DirRow row) {
        if (row == null || !row.editing) return;
        row.editing = false;
        row.node.getStyleClass().remove("editing");
        setVisibleManaged(row.editForm, false);
        setVisibleManaged(row.content, true);
        row.editInput.getStyleClass().remove("gamedir-error");
        row.editInput.setTranslateX(0);

        Timeline resize = new Timeline(new KeyFrame(Duration.millis(250),
                new KeyValue(row.node.prefHeightProperty(), ITEM_HEIGHT, EASE_SMOOTH)));
        resize.setOnFinished(e -> row.node.setMinHeight(ITEM_HEIGHT));
        resize.play();

        setRowSelected(row, GameDirManager.samePath(row.entry.getPath(), activePath), false);
        if (row.node.isHover()) animateOpacity(row.hoverBg, 1, 160);
    }

    private void showEditError(DirRow row) {
        row.editInput.getStyleClass().add("gamedir-error");
        row.editInput.requestFocus();
        Timeline shake = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(row.editInput.translateXProperty(), 0)),
                new KeyFrame(Duration.millis(80), new KeyValue(row.editInput.translateXProperty(), -4)),
                new KeyFrame(Duration.millis(160), new KeyValue(row.editInput.translateXProperty(), 4)),
                new KeyFrame(Duration.millis(240), new KeyValue(row.editInput.translateXProperty(), -4)),
                new KeyFrame(Duration.millis(320), new KeyValue(row.editInput.translateXProperty(), 4)),
                new KeyFrame(Duration.millis(400), new KeyValue(row.editInput.translateXProperty(), 0)));
        shake.play();
        PauseTransition clear = new PauseTransition(Duration.millis(800));
        clear.setOnFinished(e -> row.editInput.getStyleClass().remove("gamedir-error"));
        clear.play();
    }

    private void cancelAllEdits() {
        for (DirRow row : rows) {
            if (row.editing) cancelEdit(row);
        }
    }

    // =====================================================================
    // 添加目录
    // =====================================================================

    private void updateAddNamePreview() {
        if (addNamePreview == null) return;
        String path = addPathField.getText() == null ? "" : addPathField.getText().trim();
        while (path.endsWith("\\") || path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isEmpty()) {
            addNamePreview.setText(I18N.get("gamedir.t0033"));
            addNamePreview.getStyleClass().add("gamedir-name-preview-empty");
            return;
        }
        String[] parts = path.split("[/\\\\]");
        String folder = parts.length == 0 ? "" : parts[parts.length - 1];
        if (folder.isEmpty()) folder = I18N.get("gamedir.t0034");
        addNamePreview.setText(folder);
        addNamePreview.getStyleClass().remove("gamedir-name-preview-empty");
    }

    private void commitAddDirectory() {
        String path = addPathField.getText() == null ? "" : addPathField.getText().trim();
        if (path.isEmpty()) {
            addPathField.getStyleClass().add("gamedir-error");
            addPathField.requestFocus();
            shake(addPathField);
            PauseTransition clear = new PauseTransition(Duration.millis(800));
            clear.setOnFinished(e -> addPathField.getStyleClass().remove("gamedir-error"));
            clear.play();
            return;
        }
        addPathField.getStyleClass().remove("gamedir-error");
        String msg = addGameDir(path);
        if (!lastOpOk) {
            host.ui().toast(msg);
            return;
        }

        resetSearch();
        refreshFromConfig(true);
        showView(listView, "list", true);
        host.ui().toast(msg);

        PauseTransition highlight = new PauseTransition(Duration.millis(420));
        highlight.setOnFinished(e -> {
            for (DirRow row : rows) {
                if (GameDirManager.samePath(row.entry.getPath(), path)) {
                    row.newHighlight.setOpacity(1);
                    FadeTransition fade = new FadeTransition(Duration.millis(500), row.newHighlight);
                    fade.setToValue(0);
                    fade.play();
                    break;
                }
            }
            Timeline scroll = new Timeline(new KeyFrame(Duration.millis(300),
                    new KeyValue(listScroll.vvalueProperty(), 1.0, EASE_SMOOTH)));
            scroll.play();
        });
        highlight.play();
    }

    private void confirmRemove(DirRow row) {
        if (entries.size() <= 1) {
            host.ui().toast(I18N.get("gamedir.t0035"));
            return;
        }
        host.confirmDelete(I18N.get("gamedir.p0052", row.entry.getName()), () -> {
            String msg = removeGameDir(row.entry.getPath());
            if (!lastOpOk) {
                host.ui().toast(msg);
                return;
            }
            row.node.setMouseTransparent(true);
            FadeTransition fade = new FadeTransition(Duration.millis(250), row.node);
            fade.setToValue(0);
            TranslateTransition slide = new TranslateTransition(Duration.millis(250), row.node);
            slide.setToX(-20);
            ParallelTransition exit = new ParallelTransition(fade, slide);
            exit.setOnFinished(e -> {
                refreshFromConfig(false);
                host.ui().toast(msg);
            });
            exit.play();
        });
    }

    // =====================================================================
    // 详情数据
    // =====================================================================

    private void fillDetailData(GameDirManager.GameDirEntry entry, int seq) {
        if (!entry.exists()) {
            detailVersions.setText("—");
            detailSpace.setText("—");
            detailLast.setText("—");
            return;
        }

        Integer cached = GameDirManager.cachedVersionCount(entry.getPath());
        if (cached != null) {
            detailVersions.setText(I18N.get("gamedir.p0053", cached));
        } else {
            detailVersions.setText(I18N.get("gamedir.t0036"));
            UIGeneralControlClass.ASYNC_POOL.submit(() -> {
                int count = entry.countVersions();
                GameDirManager.putVersionCount(entry.getPath(), count);
                Platform.runLater(() -> {
                    if (seq != detailRequestSeq) return;
                    detailVersions.setText(I18N.get("gamedir.p0054", count));
                    adjustDetailHeight();
                });
            });
        }

        long[] sizeCache = DISK_SIZE_CACHE.get(entry.getPath());
        if (sizeCache != null && System.currentTimeMillis() - sizeCache[1] < DISK_SIZE_TTL_MS) {
            detailSpace.setText(FormatUtils.formatFileSize(sizeCache[0]));
        } else {
            detailSpace.setText(I18N.get("gamedir.t0037"));
            UIGeneralControlClass.ASYNC_POOL.submit(() -> {
                long computed;
                try {
                    computed = directorySize(Paths.get(entry.getPath()));
                } catch (Exception ex) {
                    computed = -1L;
                }
                final long size = computed;
                if (size >= 0) {
                    DISK_SIZE_CACHE.put(entry.getPath(), new long[]{size, System.currentTimeMillis()});
                }
                Platform.runLater(() -> {
                    if (seq != detailRequestSeq) return;
                    detailSpace.setText(size >= 0 ? FormatUtils.formatFileSize(size) : I18N.get("gamedir.t0038"));
                    adjustDetailHeight();
                });
            });
        }

        detailLast.setText(I18N.get("gamedir.t0039"));
        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            String text = lastAccessText(entry);
            Platform.runLater(() -> {
                if (seq != detailRequestSeq) return;
                detailLast.setText(text);
                adjustDetailHeight();
            });
        });
    }

    private void adjustDetailHeight() {
        if (activeView != detailView || heightAnimating) return;
        Platform.runLater(() -> {
            if (activeView != detailView || heightAnimating) return;
            double target = measureViewHeight(detailView);
            if (Math.abs(target - bodyHost.getPrefHeight()) > 1) bodyHost.setPrefHeight(target);
        });
    }

    private static long directorySize(Path root) {
        if (root == null || !Files.isDirectory(root)) return -1L;
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (Exception ignored) {
                            return 0L;
                        }
                    })
                    .sum();
        } catch (Exception e) {
            return -1L;
        }
    }

    private String lastAccessText(GameDirManager.GameDirEntry entry) {
        try {
            for (LaunchRecord rec : LaunchRecordStore.load()) {
                if (rec != null && rec.gameDir != null && !rec.gameDir.isBlank()
                        && rec.launchTime != null && !rec.launchTime.isBlank()
                        && GameDirManager.samePath(rec.gameDir, entry.getPath())) {
                    return rec.launchTime;
                }
            }
            Path path = Paths.get(entry.getPath());
            if (Files.isDirectory(path)) {
                FileTime time = Files.getLastModifiedTime(path);
                return time.toInstant().atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            }
        } catch (Exception ignored) {
            // 读不到就显示占位符，不影响页面
        }
        return "—";
    }

    // =====================================================================
    // 目录增删改（不重挂页面，动画不中断）
    // =====================================================================

    /**
     * 加入列表并切换为当前游戏目录（已存在则只切换）。
     *
     * @param reloadPage 切换后需要重新挂载的页面；传 null 表示由本页自己刷新
     */
    public String switchGameDir(String path, String reloadPage) {
        String msg = doSwitchGameDir(path, reloadPage);
        // 从版本选择页等外部入口切换后，设置页缓存还是旧内容：这里就地刷新一次，
        // 下次进入「游戏目录」时看到的就是新的当前目录与列表。
        if (reloadPage != null && panel != null) {
            // 外部入口切换目录后旧搜索条件可能把新的当前目录过滤掉，回到本页时先清空搜索
            resetSearch();
            refreshFromConfig(false);
            if (!listMode) showView(listView, "list", false);
        }
        return msg;
    }

    private String doSwitchGameDir(String path, String reloadPage) {
        String target = path == null ? "" : path.trim();
        if (target.isEmpty()) return I18N.get("gamedir.t0040");

        String before = GameDirManager.activePath(host.config());
        List<GameDirManager.GameDirEntry> entries = GameDirManager.load(host.config());
        boolean existed = GameDirManager.containsPath(entries, target);
        if (!existed) {
            entries.add(new GameDirManager.GameDirEntry(GameDirManager.suggestName(target, entries), target));
        }
        GameDirManager.store(host.config(), entries, target);
        applyGameDirChanges(before, reloadPage);

        StringBuilder msg = new StringBuilder(existed ? I18N.get("gamedir.t0041") : I18N.get("gamedir.t0042"));
        GameDirInitializer.InitResult health = detectAndFix(target);
        if (health != null) {
            if (health.ok() && (health.created() > 0 || health.filesCreated() > 0)) {
                msg.append("；").append(health.created() > 0
                        ? I18N.get("gamedir.t0043")
                        : I18N.get("gamedir.t0044"));
            } else if (!health.ok()) {
                msg.append("；").append(health.summary());
            }
        }
        // 版本选择随目录走：这里要读的是「目标目录自己的记录」，
        // 而不是切走前那个目录留下的版本（旧实现会拿全局版本去校验本目录，导致误报）
        String recorded = SelectedVersion.recorded(target);
        if (recorded.isEmpty()) {
            msg.append(I18N.get("gamedir.t0045"));
        } else if (ResourceScanner.versionInstalledIn(target, recorded)) {
            msg.append(I18N.get("gamedir.t0046")).append(recorded);
        } else {
            msg.append(I18N.get("gamedir.t0047")).append(recorded).append(I18N.get("gamedir.t0048"));
        }
        return msg.toString();
    }

    /** 仅把目录加入多目录列表，不改变当前使用的目录；返回提示文案 */
    private String addGameDir(String path) {
        lastOpOk = false;
        String target = path == null ? "" : path.trim();
        if (target.isEmpty()) return I18N.get("gamedir.errEmptyPath");

        String before = GameDirManager.activePath(host.config());
        List<GameDirManager.GameDirEntry> entries = GameDirManager.load(host.config());
        if (GameDirManager.containsPath(entries, target)) return I18N.get("gamedir.errAlreadyListed");
        entries.add(new GameDirManager.GameDirEntry(GameDirManager.suggestName(target, entries), target));
        GameDirManager.store(host.config(), entries, before);
        applyDirectoryListChanges();
        lastOpOk = true;

        String msg = I18N.get("gamedir.added");
        GameDirInitializer.InitResult health = detectAndFix(target);
        if (health != null) {
            if (health.ok() && (health.created() > 0 || health.filesCreated() > 0)) {
                msg += I18N.get("gamedir.addedSuffix",
                        I18N.get(health.created() > 0
                                ? "gamedir.addedSkeleton"
                                : "gamedir.addedProfilesRegenerated"));
            } else if (!health.ok()) {
                msg += I18N.get("gamedir.addedFailSuffix", health.summary());
            }
        }
        return msg;
    }

    /** 重命名目录显示名称（只改名称，不改路径）；返回提示文案 */
    private String renameGameDir(String path, String newName) {
        lastOpOk = false;
        List<GameDirManager.GameDirEntry> entries = GameDirManager.load(host.config());
        int index = GameDirManager.indexOf(entries, path);
        if (index < 0) return I18N.get("gamedir.errNotInList");
        String name = GameDirManager.sanitizeName(newName);
        if (name.isEmpty()) return I18N.get("gamedir.errEmptyName");

        String before = GameDirManager.activePath(host.config());
        entries.set(index, new GameDirManager.GameDirEntry(name, entries.get(index).getPath()));
        GameDirManager.store(host.config(), entries, before);
        applyDirectoryListChanges();
        lastOpOk = true;
        return I18N.get("gamedir.renamed", name);
    }

    /** 从多目录列表移除目录（只删管理记录，不动磁盘文件；至少保留一项）；返回提示文案 */
    private String removeGameDir(String path) {
        lastOpOk = false;
        List<GameDirManager.GameDirEntry> entries = GameDirManager.load(host.config());
        if (entries.size() <= 1) return I18N.get("gamedir.errKeepAtLeastOne");
        int index = GameDirManager.indexOf(entries, path);
        if (index < 0) return I18N.get("gamedir.errNotInList");

        String before = GameDirManager.activePath(host.config());
        entries.remove(index);
        // store 会校正 GameDir：移除的若是当前目录，自动切换到列表中第一项
        GameDirManager.store(host.config(), entries, before);
        applyDirectoryListChanges();
        lastOpOk = true;
        return I18N.get("gamedir.removed");
    }

    private GameDirInitializer.InitResult detectAndFix(String target) {
        try {
            return GameDirInitializer.detectAndFix(Paths.get(target));
        } catch (Exception e) {
            return null;
        }
    }

    /** 保存目录列表变更（新增 / 重命名 / 移除）：列表本身变了，依赖目录列表的页面缓存必须重建 */
    private void applyDirectoryListChanges() {
        saveConfigInPlace();
        host.refreshDependentPageCaches();
    }

    /** 保存目录切换：当前目录变化时重建按目录扫描的页面缓存，最后按需重新挂载 reloadPage */
    private void applyGameDirChanges(String beforeActive, String reloadPage) {
        // 目录是先经 GameDirManager.store 写进配置的，这里已能读到「新目录自己的版本记录」。
        // 立刻刷进内存镜像：首页版本卡 / 截图目录 / 插帧等读取点随之切到新目录的版本
        SelectedVersion.syncCache(host.config());
        saveConfigInPlace();
        if (!GameDirManager.samePath(beforeActive, GameDirManager.activePath(host.config()))) {
            host.refreshDependentPageCaches();
        }
        if (reloadPage != null && !reloadPage.isEmpty()) host.switchToPage(reloadPage);
    }

    /**
     * 保存配置，并让本次 {@code rebuildSettingsPages()} 复用本页已构建的根节点。
     *
     * <p>启动器的 {@code saveConfig()} 会重建所有设置页缓存（防止其它页显示旧值）。
     * 本页的目录操作要求「动画不中断」，所以保存期间由 {@link #build()} 返回同一个根节点，
     * 其它设置页照常重建；保存结束后由调用方就地刷新列表/选中态。
     */
    private void saveConfigInPlace() {
        selfSaveInProgress = true;
        try {
            host.saveConfig();
        } finally {
            selfSaveInProgress = false;
        }
    }

    // =====================================================================
    // 通用动画 / 事件工具
    // =====================================================================

    private void installSceneAnimation() {
        if (panel == null) return;
        final VBox thisPanel = panel;
        thisPanel.setOpacity(0);
        thisPanel.setTranslateY(10);
        thisPanel.setScaleX(0.98);
        thisPanel.setScaleY(0.98);
        thisPanel.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) return;
            Platform.runLater(() -> {
                if (panel != thisPanel) return;
                playPanelEnterOnce();
                if (rowsEntrancePending) {
                    rowsEntrancePending = false;
                    playRowsEntrance();
                }
            });
        });
        Platform.runLater(() -> {
            if (panel != thisPanel || thisPanel.getScene() == null) return;
            playPanelEnterOnce();
            if (rowsEntrancePending) {
                rowsEntrancePending = false;
                playRowsEntrance();
            }
        });
    }

    private void playPanelEnterOnce() {
        if (panelEnterPlayed || panel == null || panel.getScene() == null) return;
        panelEnterPlayed = true;
        panel.setOpacity(0);
        panel.setTranslateY(10);
        panel.setScaleX(0.98);
        panel.setScaleY(0.98);

        FadeTransition fade = new FadeTransition(Duration.millis(400), panel);
        fade.setToValue(1);
        TranslateTransition move = new TranslateTransition(Duration.millis(400), panel);
        move.setToY(0);
        move.setInterpolator(EASE_SMOOTH);
        ScaleTransition scale = new ScaleTransition(Duration.millis(400), panel);
        scale.setToX(1);
        scale.setToY(1);
        scale.setInterpolator(EASE_SMOOTH);

        ParallelTransition enter = new ParallelTransition(fade, move, scale);
        enter.setOnFinished(e -> {
            panel.setOpacity(1);
            panel.setTranslateY(0);
            panel.setScaleX(1);
            panel.setScaleY(1);
        });
        enter.play();
    }

    private void playRowsEntrance() {
        int visibleIndex = 0;
        for (DirRow row : rows) {
            if (!row.node.isVisible()) {
                row.node.setOpacity(1);
                row.node.setTranslateY(0);
                continue;
            }
            row.node.setOpacity(0);
            row.node.setTranslateY(-10);
            Duration delay = Duration.millis(Math.min(visibleIndex * 30L, 300L));
            FadeTransition fade = new FadeTransition(Duration.millis(300), row.node);
            fade.setToValue(1);
            fade.setDelay(delay);
            TranslateTransition move = new TranslateTransition(Duration.millis(300), row.node);
            move.setToY(0);
            move.setDelay(delay);
            move.setInterpolator(EASE_OUT_BACK);
            new ParallelTransition(fade, move).play();
            visibleIndex++;
        }
    }

    private void animateOpacity(Node node, double target, double millis) {
        if (node == null) return;
        FadeTransition ft = new FadeTransition(Duration.millis(millis), node);
        ft.setToValue(target);
        ft.setInterpolator(EASE_SMOOTH);
        ft.play();
    }

    private void animateScale(Node node, double target, double millis, Interpolator interpolator) {
        if (node == null) return;
        ScaleTransition st = new ScaleTransition(Duration.millis(millis), node);
        st.setToX(target);
        st.setToY(target);
        st.setInterpolator(interpolator);
        st.play();
    }

    private void animateScaleY(Node node, double target, double millis, Interpolator interpolator) {
        if (node == null) return;
        Timeline t = new Timeline(new KeyFrame(Duration.millis(millis),
                new KeyValue(node.scaleYProperty(), target, interpolator)));
        t.play();
    }

    private void animateTranslateX(Node node, double target, double millis) {
        if (node == null) return;
        TranslateTransition tt = new TranslateTransition(Duration.millis(millis), node);
        tt.setToX(target);
        tt.setInterpolator(EASE_SMOOTH);
        tt.play();
    }

    private void installPressScale(Node node, double pressedScale) {
        if (node == null) return;
        node.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> animateScale(node, pressedScale, 100, EASE_SMOOTH));
        node.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> animateScale(node, 1, 180, EASE_OUT_BACK));
        node.addEventHandler(MouseEvent.MOUSE_EXITED, e -> animateScale(node, 1, 180, EASE_SMOOTH));
    }

    private void shake(Node node) {
        if (node == null) return;
        Timeline shakeAnim = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(node.translateXProperty(), 0)),
                new KeyFrame(Duration.millis(80), new KeyValue(node.translateXProperty(), -4)),
                new KeyFrame(Duration.millis(160), new KeyValue(node.translateXProperty(), 4)),
                new KeyFrame(Duration.millis(240), new KeyValue(node.translateXProperty(), -4)),
                new KeyFrame(Duration.millis(320), new KeyValue(node.translateXProperty(), 4)),
                new KeyFrame(Duration.millis(400), new KeyValue(node.translateXProperty(), 0)));
        shakeAnim.play();
    }

    private static void setVisibleManaged(Node node, boolean visible) {
        if (node == null) return;
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private static boolean isInteractive(Object target) {
        Node node = target instanceof Node n ? n : null;
        while (node != null) {
            if (node instanceof Button || node instanceof TextField || node instanceof ScrollBar) {
                return true;
            }
            node = node.getParent();
        }
        return false;
    }

    private static boolean isInside(Node ancestor, Object target) {
        Node node = target instanceof Node n ? n : null;
        while (node != null) {
            if (node == ancestor) return true;
            node = node.getParent();
        }
        return false;
    }

    // =====================================================================
    // 列表行数据
    // =====================================================================

    private static final class DirRow {
        GameDirManager.GameDirEntry entry;
        StackPane node;
        Label title;
        Label desc;
        StackPane radio;
        Region radioDot;
        HBox badge;
        HBox actions;
        HBox content;
        HBox editForm;
        TextField editInput;
        Region hoverBg;
        Region selectedBg;
        Region accent;
        Region newHighlight;
        HBox status;
        boolean selected;
        boolean editing;
        PauseTransition clickTimer;

        private DirRow(GameDirManager.GameDirEntry entry) {
            this.entry = entry;
        }
    }
}
