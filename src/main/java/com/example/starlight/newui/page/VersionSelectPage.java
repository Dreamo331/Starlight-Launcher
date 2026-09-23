package com.example.starlight.newui.page;

import com.example.starlight.config.GameDirManager;
import com.example.starlight.launch.LaunchRecordStore;
import com.example.starlight.util.ArchiveUtils;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.ui.PageKit;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.stage.DirectoryChooser;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.starlight.newui.ui.VersionIconKit;
import com.startgame.launcher.LoaderDetector;
import java.util.stream.Collectors;
import javafx.scene.control.Tooltip;

/**
 * 版本选择页（左侧版本文件夹导航 + 右侧按「整合包 / 加载器」分组的版本列表）
 * （从 LauncherView 抽离，方法体逐字搬运，样式未改动；
 * 右侧版本列表后改为分组折叠展示，详见 {@link #buildVersionGroup}）
 */
public final class VersionSelectPage {

    private final LauncherContext host;

    /** 已折叠的加载器分组名（默认空 = 全部展开；供展开/收起与页面重建后保持状态） */
    private final Set<String> collapsedGroups = new HashSet<>();

    public VersionSelectPage(LauncherContext host) {
        this.host = host;
    }

    // ===== 版本选择页面 =====

    public Node build() {
        VBox root = PageKit.settingsPage(host, "版本选择", "home");

        // 左侧「版本文件夹」栏 + 右侧版本列表：两栏各自独立滚动（本页不再被外层 ScrollPane 包裹）
        HBox layout = new HBox(16);
        layout.setPadding(new Insets(0, 4, 0, 0));
        layout.setAlignment(Pos.TOP_LEFT);
        VBox.setVgrow(layout, Priority.ALWAYS);

        VBox content = new VBox(16);
        content.setMinWidth(0);
        content.setPadding(new Insets(0, 6, 0, 0));

        ScrollPane contentScroll = new ScrollPane(content);
        PageKit.configureScrollPane(host, contentScroll);
        HBox.setHgrow(contentScroll, Priority.ALWAYS);

        layout.getChildren().addAll(buildVersionFolderNav(), contentScroll);
        root.getChildren().add(layout);

        String gameDir = GameDirManager.activePath(host.config());
        Path versionsDir = Paths.get(gameDir, "versions");
        List<String> versionList = new ArrayList<>();

        if (Files.isDirectory(versionsDir)) {
            try (var stream = Files.list(versionsDir)) {
                versionList = stream
                        .filter(Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .sorted((a, b) -> b.compareToIgnoreCase(a))
                        .collect(Collectors.toList());
            } catch (Exception ignored) {}
        }

        // 标题行：已安装版本数量 + 刷新；当前文件夹完整路径另起一行（可换行，不省略）
        HBox titleRow = new HBox(10);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        Label listTitle = new Label("已安装版本 (" + versionList.size() + ")");
        // 显式指定文字颜色（浅色背景上默认文字色偏白看不清），深色主题由 CSS 覆盖
        listTitle.getStyleClass().add("section-title");
        listTitle.setMinWidth(Region.USE_PREF_SIZE);
        HBox.setHgrow(listTitle, Priority.ALWAYS);
        Button refreshBtn = AppIcons.button("refresh", "刷新");
        refreshBtn.getStyleClass().add("btn-primary");
        refreshBtn.setStyle("-fx-padding: 4 12; -fx-font-size: 11px;");
        refreshBtn.setMinWidth(Region.USE_PREF_SIZE);
        refreshBtn.setOnAction(e -> {
            host.cachePage("versionSelect", build());
            host.switchToPage("versionSelect");
        });
        titleRow.getChildren().addAll(listTitle, refreshBtn);

        Label dirLabel = new Label("当前文件夹: " + GameDirManager.resolve(gameDir));
        dirLabel.getStyleClass().add("settings-card-desc");
        dirLabel.setWrapText(true);
        content.getChildren().add(new VBox(4, titleRow, dirLabel));

        if (versionList.isEmpty()) {
            Label empty = new Label("该文件夹暂无已安装的版本\n请先在启动器中下载游戏版本，或在左侧切换到其他文件夹"
                    + (Files.isDirectory(Paths.get(gameDir, "versions"))
                        ? ""
                        : "\n（添加文件夹时会自动初始化标准目录结构）"));
            empty.setStyle("-fx-text-fill: -sl-text-faint; -fx-padding: 20; -fx-text-alignment: center;");
            content.getChildren().add(empty);
        } else {
            // 分组：整合包单独一组，其余按加载器分组（一组一行，点行展开/收起组内已安装的版本，
            // 与「版本下载配置页」的组件列表同款观感）
            Map<String, List<VersionIconKit.Info>> grouped = new LinkedHashMap<>();
            for (String ver : versionList) {
                VersionIconKit.Info info = VersionIconKit.read(gameDir, ver);
                grouped.computeIfAbsent(groupKeyOf(info), k -> new ArrayList<>()).add(info);
            }

            String currentVer = host.config().getOrDefault("Version", "");
            VBox groupList = new VBox(6);
            for (String groupKey : orderedGroups(grouped.keySet())) {
                groupList.getChildren().add(buildVersionGroup(gameDir, grouped.get(groupKey), currentVer));
            }
            content.getChildren().add(groupList);
        }

        Button addVerBtn = AppIcons.button("download", "下载新版本");
        addVerBtn.getStyleClass().add("btn-primary");
        addVerBtn.setMaxWidth(Double.MAX_VALUE);
        // 复用下载中心已有的版本下载流程（游戏版本分类），不再只是提示占位
        addVerBtn.setOnAction(e -> host.openDownloadCategory(0));
        content.getChildren().add(addVerBtn);
        return root;
    }

    /** 左侧文件夹栏宽度（含自身滚动条占位） */
    private static final double VERSION_FOLDER_NAV_WIDTH = 176;

    /**
     * 版本选择页左侧「版本文件夹」切换栏：列出多目录管理中的全部游戏目录，
     * 点击即把该目录切换为当前游戏目录（GameDir），右侧版本列表随之重建刷新。
     * 当前目录用「选中」高亮 + 标记，一眼可见正在浏览哪个文件夹。
     *
     * <p>文件夹较多时本栏自己滚动，与右侧版本列表互不影响。
     */
    private ScrollPane buildVersionFolderNav() {
        VBox nav = new VBox(4);
        nav.setAlignment(Pos.TOP_LEFT);
        nav.setFillWidth(true);
        nav.getStyleClass().add("download-nav");

        Label navTitle = new Label("版本文件夹");
        navTitle.getStyleClass().add("download-nav-title");
        nav.getChildren().add(navTitle);

        List<GameDirManager.GameDirEntry> dirs = GameDirManager.load(host.config());
        String active = GameDirManager.activePath(host.config());

        for (GameDirManager.GameDirEntry entry : dirs) {
            boolean isActive = GameDirManager.samePath(entry.getPath(), active);
            int versions = entry.exists() ? entry.countVersions() : 0;

            Button item = navItem("folder", entry.getName()
                    + "\n" + (entry.exists() ? versions + " 个版本" : "目录不存在"));
            item.setTooltip(new Tooltip(entry.getDisplayPath()
                    + (entry.exists() ? "\n已安装 " + versions + " 个版本" : "\n目录不存在")));
            if (isActive) {
                item.getStyleClass().add("selected");
                AppIcons.apply(item, "bullet", 8, null);
            }

            item.setOnAction(e -> {
                if (GameDirManager.samePath(entry.getPath(), GameDirManager.activePath(host.config()))) {
                    host.ui().toast("当前已是该版本文件夹: " + entry.getName());
                    return;
                }
                // 切换目录后重新挂载本页：右侧列表刷新为该文件夹的版本
                host.ui().toast(host.switchGameDir(entry.getPath(), "versionSelect"));
            });
            nav.getChildren().add(item);
        }

        // 分隔线 + 快捷入口
        Region sep = new Region();
        sep.setMinHeight(1);
        sep.setStyle("-fx-background-color: rgba(0,0,0,0.08);");
        VBox.setMargin(sep, new Insets(8, 4, 8, 4));
        nav.getChildren().add(sep);

        Button addItem = navItem("plus", "添加文件夹");
        addItem.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("选择要切换的 .minecraft 目录");
            File f = dc.showDialog(host.stage());
            if (f != null) host.ui().toast(host.switchGameDir(f.getAbsolutePath(), "versionSelect"));
        });
        nav.getChildren().add(addItem);

        Button manageItem = navItem("list", "管理目录");
        manageItem.setOnAction(e -> host.switchToSettings("sidebarGameDir"));
        nav.getChildren().add(manageItem);

        // 本栏独立滚动：与右侧版本列表互不影响（本页已不在外层 ScrollPane 内）
        ScrollPane navScroll = new ScrollPane(nav);
        navScroll.setFitToWidth(true);
        navScroll.setFitToHeight(false);
        navScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        navScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        navScroll.setStyle("-fx-background-color: transparent;");
        navScroll.setPrefWidth(VERSION_FOLDER_NAV_WIDTH);
        navScroll.setMinWidth(VERSION_FOLDER_NAV_WIDTH);
        navScroll.setMaxWidth(VERSION_FOLDER_NAV_WIDTH);
        // 定宽侧栏保持自管尺寸，仅补挂惯性滚动（阻尼手感与右侧版本列表一致）
        PageKit.attachInertia(host, navScroll);
        return navScroll;
    }

    /**
     * 左侧文件夹栏条目：统一「填满栏宽 + 长文本换行」。
     * .sidebar-item 的 CSS 首选宽度固定为 184px（为 200px 主侧边栏设计），
     * 在更窄的文件夹栏里会被压成省略号，这里改为按内容计算宽度。
     */
    private Button navItem(String text) {
        return navItem(null, text);
    }

    /** 带图标的文件夹栏条目；icon 为 {@link AppIcons} 中的图标名，null 表示不显示图标 */
    private Button navItem(String icon, String text) {
        Button btn = new Button(text);
        btn.getStyleClass().add("sidebar-item");
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setAlignment(Pos.CENTER_LEFT);
        btn.setWrapText(true);
        btn.setPrefWidth(Region.USE_COMPUTED_SIZE);
        AppIcons.apply(btn, icon, 15, null);
        return btn;
    }

    // ==================== 已安装版本：按「整合包 / 加载器」分组展示 ====================

    /**
     * 「整合包」分组的键（不是加载器名，用一个不会与加载器冲突的哨兵值）。
     * <p>判定标准集中在 {@link VersionIconKit#isModpack}，本页只负责拿它的结果分组。
     */
    private static final String MODPACK_GROUP = "__modpack__";

    /** 分组键：整合包单独成组，其余按加载器分组 */
    private static String groupKeyOf(VersionIconKit.Info info) {
        return info.modpack() ? MODPACK_GROUP : info.loader();
    }

    /**
     * 分组顺序（整合包在最前，随后原版，加载器按 Forge → NeoForge → Fabric → Quilt 的习惯顺序），
     * 「未识别」（缺少 version.json）放最后，名单外的加载器再按名称追加在后面。
     */
    private static final List<String> LOADER_ORDER = List.of(
            MODPACK_GROUP,
            LoaderDetector.VANILLA,
            LoaderDetector.FORGE_MODERN,
            LoaderDetector.FORGE_LEGACY,
            LoaderDetector.NEOFORGE,
            LoaderDetector.FABRIC,
            LoaderDetector.QUILT,
            "");

    /** 把出现过的分组按固定顺序排列（名单外的加载器按名称追加在后面） */
    private static List<String> orderedGroups(Collection<String> present) {
        List<String> ordered = new ArrayList<>();
        for (String want : LOADER_ORDER) {
            if (present.contains(want)) ordered.add(want);
        }
        List<String> rest = new ArrayList<>(present);
        rest.removeAll(LOADER_ORDER);
        rest.sort(String::compareTo);
        ordered.addAll(rest);
        return ordered;
    }

    /** 分组显示名：整合包 / 原版 / 「缺少 version.json」用更明确的说法，其余直接用加载器名 */
    private static String groupDisplayName(String groupKey) {
        if (MODPACK_GROUP.equals(groupKey)) return "整合包";
        if (LoaderDetector.VANILLA.equals(groupKey)) return "原版（Vanilla）";
        if (groupKey == null || groupKey.isBlank()) return "未识别（缺少版本 JSON）";
        return groupKey;
    }

    /** 分组标题右侧的数量单位：整合包数的是包，其余数的是版本 */
    private static String groupUnit(String groupKey) {
        return MODPACK_GROUP.equals(groupKey) ? "个整合包" : "个版本";
    }

    /**
     * 分组图标：整合包用「包」图标（与下载中心「整合包」分类同一个 SVG 图标），
     * 原版与未识别用版本图标（草方块），加载器用各自的图标（Forge 用 forge.png、Fabric 用 Fabric.png…）。
     */
    private static Node groupIcon(String groupKey, double size) {
        if (MODPACK_GROUP.equals(groupKey)) return VersionIconKit.modpackIcon(size);
        return VersionIconKit.loaderIcon(groupKey, size);
    }

    /**
     * 一个分组（整合包 / 原版 / 各加载器）：可点击展开/收起的标题行 + 组内已安装版本列表。
     * <p>展开/收起只切换展开区的显隐，不重建整页（滚动位置不会被重置）；
     * 折叠状态记录在 {@link #collapsedGroups}，页面因选择 / 删除重建后依然保持。
     */
    private VBox buildVersionGroup(String gameDir, List<VersionIconKit.Info> versions, String currentVer) {
        String groupKey = groupKeyOf(versions.get(0));
        boolean holdsCurrent = versions.stream().anyMatch(v -> v.dirName().equals(currentVer));
        boolean expanded = !collapsedGroups.contains(groupKey);

        VBox panel = buildInstalledVersionsPanel(gameDir, versions, currentVer);
        panel.setVisible(expanded);
        panel.setManaged(expanded);

        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().addAll("settings-card", "component-row", "usable");
        if (holdsCurrent) row.getStyleClass().add("chosen");

        StackPane iconBox = new StackPane(groupIcon(groupKey, 26));
        iconBox.setMinSize(34, 34);
        iconBox.setPrefSize(34, 34);
        iconBox.setMaxSize(34, 34);

        Label nameLabel = new Label(groupDisplayName(groupKey));
        nameLabel.getStyleClass().add("component-name");
        HBox.setHgrow(nameLabel, Priority.ALWAYS);

        Label statusLabel = new Label(holdsCurrent
                ? "当前使用中"
                : versions.size() + " " + groupUnit(groupKey));
        statusLabel.getStyleClass().add("component-status");
        if (holdsCurrent) statusLabel.getStyleClass().add("chosen");

        Label chevron = new Label(expanded ? "︿" : "﹀");
        chevron.getStyleClass().add("component-chevron");
        row.getChildren().addAll(iconBox, nameLabel, statusLabel, chevron);

        row.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            boolean collapse = panel.isVisible();
            if (collapse) collapsedGroups.add(groupKey); else collapsedGroups.remove(groupKey);
            panel.setVisible(!collapse);
            panel.setManaged(!collapse);
            chevron.setText(collapse ? "﹀" : "︿");
        });

        // 「整合包」分组悬停说明判定标准：免得看不出为什么某些版本没被归进来
        if (MODPACK_GROUP.equals(groupKey)) {
            Tooltip.install(row, new Tooltip("整合包判定：由本启动器安装的整合包"
                    + "（版本 JSON 有 inheritsFrom、没有 mainClass）。\n"
                    + "用 PCL2 等外部启动器安装的整合包无法自动识别（目录里没有标记），"
                    + "仍按底层加载器归类。"));
        }

        return new VBox(6, row, panel);
    }

    /** 分组展开区：标题 + 组内已安装版本列表（样式与下载配置页展开的版本列表一致） */
    private VBox buildInstalledVersionsPanel(String gameDir, List<VersionIconKit.Info> versions, String currentVer) {
        VBox panel = new VBox(4);
        panel.getStyleClass().add("settings-card");
        panel.setStyle("-fx-padding: 12 16; -fx-translate-y: 0; -fx-background-color: rgba(127,127,127,0.07);");

        Label allTitle = new Label("已安装 " + versions.size() + " 个版本");
        allTitle.getStyleClass().add("config-panel-title");
        panel.getChildren().add(allTitle);

        for (VersionIconKit.Info info : versions) {
            panel.getChildren().add(buildInstalledVersionItem(gameDir, info, currentVer));
        }
        return panel;
    }

    /**
     * 分组内的一条已安装版本：版本图标 + 版本文件夹名 + 说明 + 选择 / 版本设置 / 删除。
     * <p>整行不可点（操作走右侧按钮），所以用 {@code installed-version-item}，
     * 而不是下载配置页那个「整行点击即选中」的 {@code loader-version-item}。
     */
    private HBox buildInstalledVersionItem(String gameDir, VersionIconKit.Info entry, String currentVer) {
        String ver = entry.dirName();
        boolean isCurrent = ver.equals(currentVer);

        HBox item = new HBox(10);
        item.setAlignment(Pos.CENTER_LEFT);
        item.getStyleClass().add("installed-version-item");
        if (isCurrent) item.getStyleClass().add("chosen");

        StackPane iconBox = new StackPane(VersionIconKit.icon(entry, 22));
        iconBox.setMinSize(26, 26);
        iconBox.setPrefSize(26, 26);
        iconBox.setMaxSize(26, 26);

        VBox info = new VBox(1);
        info.setMinWidth(0);
        Label nameLabel = new Label(ver);
        nameLabel.getStyleClass().add("loader-version-name");
        info.getChildren().add(nameLabel);
        String caption = isCurrent ? "当前使用中" : (entry.base().isBlank() ? "" : "基于 " + entry.base());
        if (!caption.isEmpty()) {
            Label capLabel = new Label(caption);
            capLabel.getStyleClass().add("loader-version-caption");
            info.getChildren().add(capLabel);
        }
        HBox.setHgrow(info, Priority.ALWAYS);
        item.getChildren().addAll(iconBox, info);

        Button selectBtn = AppIcons.button("check", isCurrent ? "当前" : "选择");
        selectBtn.getStyleClass().add("btn-primary");
        selectBtn.setStyle("-fx-padding: 4 12; -fx-font-size: 11px;");
        selectBtn.setMinWidth(Region.USE_PREF_SIZE);
        if (isCurrent) {
            selectBtn.setDisable(true);
        } else {
            selectBtn.setOnAction(e -> {
                host.config().put("Version", ver);
                host.saveConfig();
                host.ui().toast("已选择版本: " + ver);
                host.refreshHomeCache();
                host.cachePage("versionSelect", build());
                host.switchToPage("versionSelect");
            });
        }

        // 版本设置：进入该版本的独立设置页（差异继承全局，未覆盖字段跟随全局配置）
        Button verSettingsBtn = AppIcons.button("sliders", "版本设置");
        verSettingsBtn.getStyleClass().add("btn-primary");
        verSettingsBtn.setStyle("-fx-padding: 4 12; -fx-font-size: 11px;");
        verSettingsBtn.setMinWidth(Region.USE_PREF_SIZE);
        verSettingsBtn.setOnAction(e -> {
            if (!ver.equals(currentVer)) {
                host.config().put("Version", ver);
                host.saveConfig();
            }
            host.switchToSettings("sidebarVersionSettings");
        });

        // 删除版本：确认后删除版本目录，并同步清理首页「最近游玩」中该版本的记录
        Button deleteBtn = AppIcons.button("trash", "删除");
        deleteBtn.setStyle("-fx-padding: 4 12; -fx-font-size: 11px;"
                + " -fx-background-color: #fee2e2; -fx-text-fill: #dc2626;");
        deleteBtn.setMinWidth(Region.USE_PREF_SIZE);
        deleteBtn.setOnAction(e -> {
            Path versionDir = Paths.get(gameDir, "versions", ver);
            host.confirmDelete("确定要删除版本 \"" + ver + "\" 吗？\n将删除该版本全部文件（含此版本的隔离存档、模组等数据），此操作不可恢复！", () -> {
                try {
                    ArchiveUtils.deleteRecursively(versionDir);
                } catch (Exception ex) {
                    host.ui().toast("删除版本失败: " + ex.getMessage());
                    return;
                }
                // 同步清理首页「最近游玩」中该版本的启动记录
                LaunchRecordStore.removeIf(r -> ver.equals(r.version)
                        && java.util.Objects.equals(gameDir, r.gameDir));
                // 删除的是当前选中版本时，重置版本配置
                if (ver.equals(host.config().get("Version"))) {
                    host.config().put("Version", "");
                    host.saveConfig();
                }
                host.ui().toast("已删除版本: " + ver);
                host.refreshHomeCache();
                host.cachePage("versionSelect", build());
                host.switchToPage("versionSelect");
            });
        });

        HBox actions = new HBox(6, selectBtn, verSettingsBtn, deleteBtn);
        actions.setAlignment(Pos.CENTER_RIGHT);
        item.getChildren().add(actions);
        return item;
    }

    /** 探测指定版本已安装的加载器（判定逻辑见 {@link VersionIconKit}；json 缺失时返回空串） */
    public String detectLoaderForVersion(String gameDir, String version) {
        return VersionIconKit.read(gameDir, version).loader();
    }

}


