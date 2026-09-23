package com.example.starlight.newui.page.settings;

import com.example.starlight.config.GameDirManager;
import com.example.starlight.config.VersionConfigManager;
import com.example.starlight.gui.UIGeneralControlClass;
import com.example.starlight.lang.GameLanguage;
import com.example.starlight.listjava.FindAllJavaWindows;
import com.example.starlight.listjava.JavaCacheManager;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.ui.IconService;
import com.example.starlight.newui.ui.PageKit;
import com.example.starlight.newui.ui.VersionIconKit;
import com.example.starlight.util.VersionUtils;
import com.example.starlight.version.VersionDownloadService;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 设置 → 版本独立设置页（2026-09 参考 {@code 版本独立设置参考.html} 重做版）。
 *
 * <p>版式对齐参考稿：卡片 = 蓝色小图标 + 标题 + 右侧状态标签；页面底部固定操作栏
 * （恢复默认 / 保存设置）；顶部富样式「版本选择」下拉 + 「复制配置文件到其他版本」弹窗；
 * 内存 = 滑块 + 快捷档位；窗口全屏开关联动尺寸置灰；版本隔离带只读路径回显。
 *
 * <p>功能仍是原来的差异继承模型（参考 HMCL 的 overrideProperties 设计）：版本只保存被修改
 * 过的字段，未修改的字段动态继承全局配置（starlight.ini）。与旧版的差异是保存方式——
 * 由「逐行保存」改为「底部统一保存」：点「保存设置」时逐字段对比，与全局相同则自动恢复继承；
 * 每张卡片右上角的状态标签显示「继承全局 / 已覆盖」，已覆盖的卡片提供「恢复继承」链接。
 * 覆盖数据落盘在 {@code versions/{版本名}/config.overrides.json}。
 */
public final class VersionSettingsPage {

    private final LauncherContext host;

    /** 版本设置页在页面缓存中的 key（设置页与侧边栏 id 同名） */
    public static final String PAGE_KEY = "sidebarVersionSettings";

    /**
     * JVM 参数的四种数组运算模式：显示名 → $mode。
     * 用 LinkedHashMap 固定顺序，下拉列表每次打开顺序一致
     */
    private static final Map<String, String> JVM_ARG_MODES = new LinkedHashMap<>();

    static {
        JVM_ARG_MODES.put("替换全局参数", "replace");
        JVM_ARG_MODES.put("追加到全局参数（末尾）", "append");
        JVM_ARG_MODES.put("前置于全局参数", "prepend");
        JVM_ARG_MODES.put("从全局参数中移除", "remove");
    }

    /** 各模式的动词，用于提示文案（append → 追加） */
    private static final Map<String, String> MODE_VERBS = Map.of(
            "replace", "替换", "append", "追加", "prepend", "前置", "remove", "移除");

    /** 各模式下输入框的输入提示：增量模式只填增量，不是合并后的完整参数 */
    private static final Map<String, String> MODE_PROMPTS = Map.of(
            "replace", "直接替换全局 JVM 参数，例如 -Xmx4G -XX:+UseG1GC",
            "append", "只填要追加到全局参数末尾的项，例如 -XX:+UseZGC",
            "prepend", "只填要加到全局参数前面的项，例如 -Dfile.encoding=UTF-8",
            "remove", "只填要从全局参数里移除的项，例如 -XX:+UseG1GC");

    /** 游戏语言下拉的「不修改」选项（对应空覆盖值：跟随全局与游戏内设置） */
    private static final String LANGUAGE_FOLLOW = "不修改（跟随游戏内设置）";

    /** 内存快捷档位（MB），对齐参考稿的 chips */
    private static final int[] MEMORY_CHIPS = {2048, 4096, 6144, 8192, 12288};
    /** 滑块范围，与 JvmPage 的最内存滑块上限一致 */
    private static final double MEM_SLIDER_MIN = 1024;
    private static final double MEM_SLIDER_MAX = 16384;

    // ==================== 页面状态（页面实例常驻，每次 build 重置） ====================

    /** 全局配置快照：本页构建期间固定，保存动作基于它判断「与全局相同 → 恢复继承」 */
    private Map<String, String> globalIni = Map.of();
    private String gameDir = "";
    /** 当前编辑的版本（页内可切换；不影响启动器的「当前使用版本」） */
    private String version = "";
    /** 启动器当前使用的版本（用于下拉里的「使用中」标记） */
    private String activeVersion = "";

    /** 程序化更新控件时短路，避免误标「未保存修改」 */
    private boolean loading = true;
    /** 是否有未保存的修改（输入即置位；恢复继承 / 保存 / 切版本后重算） */
    private boolean dirty = false;

    private VBox contentBox;
    private ScrollPane contentScroll;
    private Text versionText;
    private Label footerHint;
    private Region footerDot;

    /** 卡片状态：key → 标签 + 恢复链接 + 重载方式 */
    private final Map<String, CardStatus> cardStatuses = new LinkedHashMap<>();

    /**
     * 版本列表的懒加载缓存：版本多时（几十个）避免在页面构建/打开下拉时一次性读取全部版本 JSON。
     * 信息与副标题按「首次被看到」的节奏逐个读取并缓存，图标图片按文件名全局去重。
     */
    private final Map<String, VersionIconKit.Info> versionInfoCache = new HashMap<>();
    private final Map<String, String> versionMetaCache = new HashMap<>();
    private final Map<String, Image> versionIconImageCache = new HashMap<>();

    // ---- 控件引用（每次重建卡片时更新） ----
    private ComboBox<VersionItem> versionCombo;
    private boolean comboGuard = false;
    private Label versionSummary;
    private Label versionPathNote;
    private Label versionTag;

    private TextField javaField;
    private Label javaNote;

    private Slider memSlider;
    private Label memValue;
    private TextField minMemField;

    private TextArea jvmArea;
    private ComboBox<String> jvmModeCombo;
    private boolean jvmModeReloading = false;

    private TextField widthField;
    private TextField heightField;
    private CheckBox fullscreenBox;
    private HBox sizeGroup;

    private CheckBox isolationBox;
    private HBox isolationReadout;
    private Label isolationText;
    private ComboBox<String> languageCombo;

    private TextField gameArgsField;
    private TextArea preArea;
    private TextArea postArea;

    public VersionSettingsPage(LauncherContext host) {
        this.host = host;
    }

    // ==================== 页面骨架 ====================

    public Node build() {
        cardStatuses.clear();
        versionInfoCache.clear();
        versionMetaCache.clear();
        versionIconImageCache.clear();
        contentBox = null;
        contentScroll = null;
        versionText = null;
        versionTag = null;
        footerHint = null;
        footerDot = null;
        loading = true;
        dirty = false;

        VBox root = PageKit.settingsPage(host, "版本独立设置");

        gameDir = GameDirManager.activePath(host.config());
        activeVersion = host.config().getOrDefault("Version", "");
        version = activeVersion;

        if (version.isEmpty()) {
            root.getChildren().add(buildNoVersionCard());
            return root;
        }

        globalIni = UIGeneralControlClass.readConfig();

        root.getChildren().add(buildSubtitle());

        contentBox = new VBox(14);
        contentBox.setMinWidth(0);
        contentBox.setPadding(new Insets(2, 6, 16, 0));
        rebuildContent();

        contentScroll = new ScrollPane(contentBox);
        PageKit.configureScrollPane(host, contentScroll);
        VBox.setVgrow(contentScroll, Priority.ALWAYS);

        VBox body = new VBox(0, contentScroll, buildFooter());
        body.setMinWidth(0);
        VBox.setVgrow(body, Priority.ALWAYS);
        root.getChildren().add(body);

        loading = false;
        refreshFooterHint();
        return root;
    }

    /** 页头副标题：当前编辑：<版本> —— 以下配置仅对该版本生效 */
    private TextFlow buildSubtitle() {
        Text prefix = new Text("当前编辑：");
        prefix.getStyleClass().add("vset-sub-text");
        versionText = new Text(version);
        versionText.getStyleClass().add("vset-sub-ver");
        Text suffix = new Text(" —— 以下配置仅对该版本生效");
        suffix.getStyleClass().add("vset-sub-text");
        TextFlow flow = new TextFlow(prefix, versionText, suffix);
        flow.getStyleClass().add("vset-subtitle");
        return flow;
    }

    /** 重建全部卡片（切版本 / 恢复默认后调用，不重建页面外壳） */
    private void rebuildContent() {
        if (contentBox == null) return;
        cardStatuses.clear();
        List<Node> cards = new ArrayList<>();
        cards.add(buildVersionCard());
        cards.add(buildJavaCard());
        cards.add(buildMemoryCard());
        cards.add(buildJvmCard());
        cards.add(buildWindowCard());
        cards.add(buildIsolationCard());
        cards.add(buildLanguageCard());
        cards.add(buildGameArgsCard());
        cards.add(buildPreCommandCard());
        cards.add(buildPostCommandCard());
        contentBox.getChildren().setAll(cards);
        if (contentScroll != null) contentScroll.setVvalue(0);
        refreshCardStatuses();
    }

    /** 尚未选择版本时的占位卡片（保持旧版行为） */
    private Node buildNoVersionCard() {
        Button goBtn = PageKit.actionButton("前往版本选择");
        goBtn.setOnAction(e -> host.switchToPage("versionSelect"));
        HBox row = new HBox(goBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox body = new VBox(10, PageKit.hintLabel(
                "还没有选择游戏版本。版本独立设置按版本保存，请先在「版本选择」页选好要配置的版本。"), row);
        return PageKit.settingsCardStacked("版本独立设置", "", body);
    }

    // ==================== 卡片脚手架 ====================

    /** 一张卡片构建完成后登记的状态（标签 + 恢复链接 + 单卡重载） */
    private static final class CardStatus {
        final List<String> paths;
        final Label tag;
        final Label restore;
        final Runnable reloader;

        CardStatus(List<String> paths, Label tag, Label restore, Runnable reloader) {
            this.paths = paths;
            this.tag = tag;
            this.restore = restore;
            this.reloader = reloader;
        }
    }

    /**
     * 卡片外壳：图标 + 标题 + 右侧「恢复继承」链接 + 状态标签。
     *
     * @param paths   该卡片对应的覆盖字段；为空表示不可继承（版本选择卡）——标签显示 staticTag
     * @param staticTag paths 为空时的静态标签文案
     */
    private VBox cardShell(String key, String iconName, String title,
                           List<String> paths, String staticTag, Node body, Runnable reloader) {
        Region icon = AppIcons.icon(iconName, 16, Color.web("#3b82f6"));
        icon.getStyleClass().add("vset-card-icon");

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("vset-card-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label restore = new Label("恢复继承");
        restore.getStyleClass().add("vset-mini-link");
        restore.setCursor(Cursor.HAND);
        restore.setVisible(false);
        restore.setManaged(false);
        restore.setOnMouseClicked(e -> {
            if (paths == null) return;
            for (String p : paths) VersionConfigManager.clearOverride(gameDir, version, p);
            host.ui().toast("已恢复继承全局设置", "info");
            if (reloader != null) reloader.run();
            recomputeDirty();
            refreshCardStatuses();
            refreshVersionSummary();
        });

        Label tag = new Label(staticTag == null ? "" : staticTag);
        tag.getStyleClass().add("vset-tag");
        if ("version".equals(key)) versionTag = tag;

        HBox head = new HBox(9, icon, titleLabel, spacer, restore, tag);
        head.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(12, head);
        card.getStyleClass().add("vset-card");
        card.setMinWidth(0);
        if (body != null) card.getChildren().add(body);

        if (paths != null) {
            cardStatuses.put(key, new CardStatus(paths, tag, restore, reloader));
        }
        return card;
    }

    /** 设置行：左侧标题 + 说明，右侧控件；divider 为 true 时顶部加分隔线 */
    private static HBox settingRow(String label, String desc, Node control, boolean divider) {
        Label title = new Label(label);
        title.getStyleClass().add("vset-row-label");
        VBox info = new VBox(3, title);
        if (desc != null && !desc.isEmpty()) {
            Label d = new Label(desc);
            d.getStyleClass().add("vset-row-desc");
            d.setWrapText(true);
            info.getChildren().add(d);
        }
        info.setMinWidth(0);
        HBox.setHgrow(info, Priority.ALWAYS);

        HBox row = new HBox(16, info, control);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("vset-row");
        if (divider) row.getStyleClass().add("divider-top");
        row.setMinWidth(0);
        return row;
    }

    /** 说明性小字行：彩色圆点 + 文案（对齐参考稿的 inline-note） */
    private static HBox noteRow(String dotKind, Node textNode) {
        Region dot = new Region();
        dot.getStyleClass().addAll("vset-note-dot", dotKind);
        HBox box = new HBox(7, dot, textNode);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("vset-note");
        return box;
    }

    private static Label mutedNoteLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("vset-note-text");
        l.setWrapText(true);
        return l;
    }

    // ==================== 1. 版本选择 ====================

    /** 下拉里的一个版本项（加载器信息与副标题按需懒加载，避免版本多时一次性读全部 JSON） */
    private record VersionItem(String dirName, boolean active) {
    }

    /** 版本列表数据（零读盘）：可见版本目录；activeVersion 不在列表时补进来 */
    private List<VersionItem> loadVersionItems() {
        List<VersionItem> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (!activeVersion.isEmpty()) {
            items.add(new VersionItem(activeVersion, true));
            seen.add(activeVersion);
        }
        for (String dir : VersionUtils.getVisibleVersions(Paths.get(gameDir))) {
            if (!seen.add(dir)) continue;
            items.add(new VersionItem(dir, dir.equals(activeVersion)));
        }
        return items;
    }

    /** 版本信息懒加载：每个版本只读一次盘，结果在页面实例内复用（构建/滚动不再重复读） */
    private VersionIconKit.Info infoOf(String dirName) {
        return versionInfoCache.computeIfAbsent(dirName, d -> VersionIconKit.read(gameDir, d));
    }

    /** 下拉副标题（懒计算 + 缓存） */
    private String metaOf(String dirName) {
        return versionMetaCache.computeIfAbsent(dirName,
                d -> versionMeta(infoOf(d), d.equals(activeVersion)));
    }

    /** 下拉图标图片（按文件名缓存；没有专属图标的加载器退回草方块）。整合包用草方块代替包图标，保持单元格构建轻量 */
    private Image iconImageOf(VersionIconKit.Info info) {
        String file = info.modpack() ? "grass.png" : IconService.loaderIconFile(info.loader());
        if (file == null) {
            file = IconService.versionIconFile(VersionDownloadService.VersionCategory.RELEASE);
        }
        return versionIconImageCache.computeIfAbsent(file, IconService::appImage);
    }

    /** 小图标视图（等尺寸、不平滑），供列表单元格与复制弹窗共用 */
    private static ImageView smallIcon(Image image, double size) {
        ImageView iv = new ImageView(image);
        iv.setFitWidth(size);
        iv.setFitHeight(size);
        iv.setPreserveRatio(true);
        iv.setSmooth(false);
        return iv;
    }

    /** 下拉项的副标题：「加载器 · 基于 父版本 · 当前启动版本」 */
    private String versionMeta(VersionIconKit.Info info, boolean active) {
        StringBuilder sb = new StringBuilder(loaderDisplay(info.loader()));
        if (info.base() != null && !info.base().isBlank() && !info.base().equals("null")) {
            sb.append(" · 基于 ").append(info.base());
        }
        if (active) sb.append(" · 当前启动版本");
        return sb.toString();
    }

    /** 加载器显示名：Vanilla/Forge Modern 收短，其余原样（整合包单独标注） */
    private static String loaderDisplay(String loader) {
        if (loader == null || loader.isBlank()) return "未识别";
        if ("Vanilla".equals(loader)) return "原版";
        if ("Forge (Modern)".equals(loader)) return "Forge";
        return loader;
    }

    private VBox buildVersionCard() {
        versionCombo = new ComboBox<>();
        versionCombo.getStyleClass().add("vset-version-combo");
        versionCombo.setMaxWidth(Double.MAX_VALUE);
        versionCombo.setMinWidth(0);
        versionCombo.setVisibleRowCount(6);
        versionCombo.setCellFactory(lv -> new VersionListCell());
        versionCombo.setButtonCell(new VersionButtonCell());

        List<VersionItem> items = loadVersionItems();
        versionCombo.getItems().setAll(items);
        VersionItem current = findItem(items, version);
        comboGuard = true;
        versionCombo.setValue(current);
        comboGuard = false;
        versionCombo.valueProperty().addListener((o, a, b) -> onComboChanged(a, b));
        // 弹出打开时收敛弹出列表的布局参数（固定行高/宽度），版本多时显著降低打开开销
        versionCombo.showingProperty().addListener((o, was, showing) -> {
            if (!showing) return;
            // 弹出面板可能比 showing 事件晚才进入窗口列表：下一帧 + 120ms 各兜底一次
            javafx.application.Platform.runLater(() -> tuneVersionPopup(versionCombo));
            javafx.animation.PauseTransition retry = new javafx.animation.PauseTransition(javafx.util.Duration.millis(120));
            retry.setOnFinished(e -> tuneVersionPopup(versionCombo));
            retry.play();
        });

        Button copyBtn = new Button("复制配置文件到其他版本");
        copyBtn.getStyleClass().add("vset-copy-btn");
        copyBtn.setMaxWidth(Double.MAX_VALUE);
        AppIcons.apply(copyBtn, "copy", 14, null);
        copyBtn.setOnAction(e -> openCopyModal());

        versionSummary = new Label();
        versionSummary.getStyleClass().add("vset-summary");
        versionSummary.setWrapText(true);

        versionPathNote = new Label("覆盖数据保存在 versions/" + version + "/config.overrides.json");
        versionPathNote.getStyleClass().add("vset-path-note");

        VBox body = new VBox(10, versionCombo, copyBtn, versionSummary, versionPathNote);
        body.setMinWidth(0);
        VBox card = cardShell("version", "version-select", "版本选择", null, "未覆盖", body, null);
        refreshVersionSummary();
        return card;
    }

    /**
     * 下拉打开时给弹出列表加“固定行高 + 宽度收敛”，大幅降低多版本下的打开开销。
     *
     * <p>弹出面板是独立窗口，命中方式与 ComboBoxAnimator 一致：
     * 在 Window 列表里找带 {@code .combo-box-popup} 样式类的桥接节点，取其下的 ListView。
     * 只需在首次弹出时应用一次（面板实例随皮肤复用）。
     */
    /**
     * 调优版本下拉的弹出列表（固定行高 48 + 宽度收敛到触发器宽度）。
     *
     * <p>弹出面板是独立窗口，命中方式与 ComboBoxAnimator 一致：在 Window 列表里找
     * 由本窗口持有、带 {@code .combo-box-popup} 桥接节点、且含 ListView 的弹窗。
     * 允许在弹窗隐藏后补调（状态随皮肤复用，下一次打开即生效）。
     */
    private void tuneVersionPopup(ComboBox<?> combo) {
        try {
            javafx.stage.Window owner = combo.getScene() == null ? null : combo.getScene().getWindow();
            for (javafx.stage.Window w : new ArrayList<>(javafx.stage.Window.getWindows())) {
                if (!(w instanceof javafx.stage.PopupWindow pw)) continue;
                if (owner != null && pw.getOwnerWindow() != owner) continue;
                if (pw.getScene() == null || pw.getScene().getRoot() == null) continue;
                Node bridge = pw.getScene().getRoot().lookup(".combo-box-popup");
                if (bridge == null) continue;
                if (bridge.lookup(".list-view") instanceof javafx.scene.control.ListView<?> listView) {
                    listView.setFixedCellSize(48);
                    double comboW = combo.getWidth() > 0 ? combo.getWidth() : 800;
                    listView.setPrefWidth(comboW);
                    listView.setMaxWidth(comboW);
                }
                return;
            }
        } catch (Throwable ignored) {
            // 调优失败不影响功能，保持默认弹出行为
        }
    }

    private static VersionItem findItem(List<VersionItem> items, String dir) {
        for (VersionItem item : items) {
            if (item.dirName().equals(dir)) return item;
        }
        return items.isEmpty() ? null : items.get(0);
    }

    private void onComboChanged(VersionItem oldItem, VersionItem newItem) {
        if (comboGuard || loading || newItem == null) return;
        if (newItem.dirName().equals(version)) return;
        if (dirty) {
            // 有未保存修改：先确认，取消则把选择拨回当前版本
            host.ui().confirm("未保存的修改",
                    "切换版本将丢弃当前未保存的修改（已保存的独立设置不受影响）。",
                    "丢弃并切换", false,
                    () -> applyVersionSwitch(newItem));
            comboGuard = true;
            versionCombo.setValue(oldItem);
            comboGuard = false;
        } else {
            applyVersionSwitch(newItem);
        }
    }

    private void applyVersionSwitch(VersionItem item) {
        version = item.dirName();
        dirty = false;
        loading = true;
        rebuildContent();
        if (versionText != null) versionText.setText(version);
        loading = false;
        refreshFooterHint();
        host.ui().toast("已切换到版本 " + version, "info");
    }

    /** 版本卡底部统计：已覆盖 N 项 + 字段名，或「全部继承全局设置」；标签同步变成状态（未覆盖 / 已覆盖 N 项） */
    private void refreshVersionSummary() {
        if (versionSummary == null) return;
        Set<String> overridden = VersionConfigManager.overriddenPaths(gameDir, version);
        versionSummary.getStyleClass().remove("warn");
        if (versionTag != null) versionTag.getStyleClass().remove("on");
        if (overridden.isEmpty()) {
            versionSummary.setText("当前未覆盖任何字段，全部继承全局设置");
            if (versionTag != null) versionTag.setText("未覆盖");
        } else {
            versionSummary.setText("已覆盖 " + overridden.size() + " 项："
                    + String.join("、", displayNames(overridden)));
            versionSummary.getStyleClass().add("warn");
            if (versionTag != null) {
                versionTag.setText("已覆盖 " + overridden.size() + " 项");
                versionTag.getStyleClass().add("on");
            }
        }
    }

    // ---- 版本下拉的单元格 ----

    /** 候选行：加载器图标 + 版本名 + 副标题 + 选中点（构建轻量：版本多时不拖慢打开） */
    private final class VersionListCell extends ListCell<VersionItem> {
        private final ImageView icon = new ImageView();
        private final Label name = new Label();
        private final Label meta = new Label();
        private final Region check = new Region();
        private final HBox box;

        VersionListCell() {
            icon.setFitWidth(20);
            icon.setFitHeight(20);
            icon.setPreserveRatio(true);
            icon.setSmooth(false);
            name.getStyleClass().add("vset-vitem-name");
            name.setTextOverrun(OverrunStyle.ELLIPSIS);
            meta.getStyleClass().add("vset-vitem-meta");
            meta.setTextOverrun(OverrunStyle.ELLIPSIS);
            VBox texts = new VBox(2, name, meta);
            texts.setMinWidth(0);
            HBox.setHgrow(texts, Priority.ALWAYS);
            check.getStyleClass().add("vset-vitem-check");
            box = new HBox(10, icon, texts, check);
            box.setAlignment(Pos.CENTER_LEFT);
            box.getStyleClass().add("vset-vitem");
            box.setMinWidth(0);
        }

        @Override
        protected void updateItem(VersionItem item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            icon.setImage(iconImageOf(infoOf(item.dirName())));
            name.setText(item.dirName());
            meta.setText(metaOf(item.dirName()));
            check.setVisible(item.dirName().equals(VersionSettingsPage.this.version));
            setGraphic(box);
        }

        /**
         * 固定测量尺寸：下拉打开时列表会对单元格做测量，版本多时逐项测量会拖慢打开。
         * 直接返回常量，跳过内容测量（实际布局仍由列表分配宽度，不受影响）。
         */
        @Override
        protected double computePrefWidth(double height) {
            return 640;
        }

        @Override
        protected double computePrefHeight(double width) {
            return 48;
        }
    }

    /** 触发区：图标 + 版本名 + 副标题（两行） */
    private final class VersionButtonCell extends ListCell<VersionItem> {
        private final ImageView icon = new ImageView();
        private final Label name = new Label();
        private final Label meta = new Label();
        private final HBox box;

        VersionButtonCell() {
            icon.setFitWidth(22);
            icon.setFitHeight(22);
            icon.setPreserveRatio(true);
            icon.setSmooth(false);
            name.getStyleClass().add("vset-vitem-name");
            name.setTextOverrun(OverrunStyle.ELLIPSIS);
            meta.getStyleClass().add("vset-vitem-meta");
            meta.setTextOverrun(OverrunStyle.ELLIPSIS);
            VBox texts = new VBox(2, name, meta);
            texts.setMinWidth(0);
            HBox.setHgrow(texts, Priority.ALWAYS);
            box = new HBox(10, icon, texts);
            box.setAlignment(Pos.CENTER_LEFT);
            box.getStyleClass().add("vset-vitem");
            box.setMinWidth(0);
        }

        @Override
        protected void updateItem(VersionItem item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            icon.setImage(iconImageOf(infoOf(item.dirName())));
            name.setText(item.dirName());
            meta.setText(metaOf(item.dirName()));
            setGraphic(box);
        }
    }

    // ==================== 2. Java 路径 ====================

    private VBox buildJavaCard() {
        String path = VersionConfigManager.PATH_JAVA_PATH;

        javaField = new TextField(effective(path));
        javaField.getStyleClass().addAll("input-field", "vset-mono");
        javaField.setMinWidth(0);
        javaField.setTooltip(new Tooltip(javaField.getText()));
        HBox.setHgrow(javaField, Priority.ALWAYS);

        Region folderIcon = AppIcons.icon("folder", 15, Color.web("#3b82f6"));
        folderIcon.getStyleClass().add("vset-path-icon");

        Button browse = new Button("浏览…");
        browse.getStyleClass().add("vset-browse-btn");
        browse.setOnAction(e -> browseJavaExe());

        HBox pathField = new HBox(10, folderIcon, javaField, browse);
        pathField.setAlignment(Pos.CENTER_LEFT);
        pathField.getStyleClass().add("vset-path-field");
        pathField.setMinWidth(0);

        javaNote = mutedNoteLabel("");
        HBox note = noteRow("ok", javaNote);
        refreshJavaNote();

        javaField.textProperty().addListener((o, a, b) -> {
            Tooltip tt = javaField.getTooltip();
            if (tt != null) tt.setText(b == null ? "" : b);
            refreshJavaNote();
            markDirty();
        });
        // 聚焦时容器描边蓝色（对齐参考稿的 focus 态；JavaFX 无 :focus-within，用类代劳）
        javaField.focusedProperty().addListener((o, a, b) -> {
            pathField.getStyleClass().remove("focused");
            if (b) pathField.getStyleClass().add("focused");
        });

        VBox body = new VBox(10, pathField, note);
        body.setMinWidth(0);
        return cardShell("java", "cpu", "Java 路径", List.of(path), null, body, () -> {
            javaField.setText(effective(path));
            refreshJavaNote();
        });
    }

    private void browseJavaExe() {
        FileChooser fc = new FileChooser();
        fc.setTitle("选择 Java 可执行文件");
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Java", "*.exe", "*.cmd"));
        }
        File f = fc.showOpenDialog(host.stage());
        if (f == null) return;
        loading = true;
        javaField.setText(f.getAbsolutePath());
        loading = false;
        refreshJavaNote();
        markDirty();
    }

    /** Java 路径状态提示：优先匹配本机扫描缓存里的 Java 版本；退回文件存在性判断 */
    private void refreshJavaNote() {
        if (javaNote == null) return;
        String path = javaField.getText() == null ? "" : javaField.getText().trim();
        HBox box = (HBox) javaNote.getParent();
        Region dot = (Region) box.getChildren().get(0);
        dot.getStyleClass().setAll("vset-note-dot", "ok");
        if (path.isEmpty()) {
            dot.getStyleClass().setAll("vset-note-dot", "warn");
            javaNote.setText("未填写路径，启动时将按全局策略选择 Java");
            return;
        }
        try {
            String normalized = path.replace('/', '\\').toLowerCase(Locale.ROOT);
            for (FindAllJavaWindows.JavaEntry entry : JavaCacheManager.getAvailable()) {
                String home = entry.homePath == null ? "" : entry.homePath.replace('/', '\\').toLowerCase(Locale.ROOT);
                if (home.isEmpty()) continue;
                if (normalized.equals(home) || normalized.startsWith(home + "\\")) {
                    javaNote.setText("已检测到 Java " + entry.version + "（本机扫描结果）");
                    return;
                }
            }
        } catch (Exception ignored) {
            // 扫描缓存不可用时退回文件存在性判断
        }
        if (Files.exists(Paths.get(path))) {
            javaNote.setText("已找到该文件；未匹配到本机扫描的 Java 列表");
        } else {
            dot.getStyleClass().setAll("vset-note-dot", "warn");
            javaNote.setText("该路径不存在，启动时可能失败");
        }
    }

    // ==================== 3. 内存分配 ====================

    private VBox buildMemoryCard() {
        String minPath = VersionConfigManager.PATH_MIN_MEMORY;
        String maxPath = VersionConfigManager.PATH_MAX_MEMORY;

        int maxMem = clampMem(PageKit.parseIntSafe(effective(maxPath), 4096));
        memValue = new Label(String.valueOf(maxMem));
        memValue.getStyleClass().add("vset-value");
        Label memUnit = new Label("MB");
        memUnit.getStyleClass().add("vset-value-unit");
        HBox valueBox = new HBox(5, memValue, memUnit);
        valueBox.setAlignment(Pos.BASELINE_LEFT);

        HBox headRow = settingRow("最大内存",
                "建议 4096 MB 以上；光影/整合包建议 6144 MB", valueBox, false);

        memSlider = new Slider(MEM_SLIDER_MIN, MEM_SLIDER_MAX, maxMem);
        memSlider.getStyleClass().add("vset-slider");
        memSlider.setBlockIncrement(512);
        memSlider.setMinWidth(240);
        memSlider.setMaxWidth(Double.MAX_VALUE);
        memSlider.valueProperty().addListener((o, a, b) -> {
            int v = (int) Math.round(b.doubleValue());
            memValue.setText(String.valueOf(v));
            refreshMemoryChips();
            if (!loading) markDirty();
        });

        memChips.clear();
        HBox chips = new HBox(7);
        chips.getStyleClass().add("vset-chips");
        for (int mem : MEMORY_CHIPS) {
            Button chip = new Button(chipLabel(mem));
            chip.getStyleClass().add("vset-chip");
            chip.setUserData(mem);
            chip.setOnAction(e -> {
                loading = true;
                memSlider.setValue(mem);
                loading = false;
                refreshMemoryChips();
                markDirty();
            });
            chips.getChildren().add(chip);
            memChips.add(chip);
        }
        refreshMemoryChips();

        minMemField = new TextField(effective(minPath));
        minMemField.getStyleClass().addAll("input-field", "input-field-sm", "vset-mono");
        minMemField.setMinWidth(Region.USE_PREF_SIZE);
        Label minUnit = new Label("MB");
        minUnit.getStyleClass().add("vset-unit-label");
        HBox minBox = new HBox(8, minMemField, minUnit);
        minBox.setAlignment(Pos.CENTER_LEFT);
        HBox minRow = settingRow("最小内存",
                "启动时预分配（-Xms）；一般无需修改", minBox, true);
        minMemField.textProperty().addListener((o, a, b) -> markDirty());

        VBox body = new VBox(10, headRow, memSlider, chips, minRow);
        body.setMinWidth(0);
        return cardShell("memory", "memory", "内存分配", List.of(minPath, maxPath), null, body, () -> {
            loading = true;
            memSlider.setValue(clampMem(PageKit.parseIntSafe(effective(maxPath), 4096)));
            memValue.setText(String.valueOf((int) Math.round(memSlider.getValue())));
            refreshMemoryChips();
            minMemField.setText(effective(minPath));
            loading = false;
        });
    }

    private static String chipLabel(int mem) {
        return (mem % 1024 == 0 ? String.valueOf(mem / 1024) : String.valueOf(mem / 1024.0)) + " GB";
    }

    /** 快捷档位激活态：滑块值等于档位值时高亮 */
    private void refreshMemoryChips() {
        if (memSlider == null) return;
        int current = (int) Math.round(memSlider.getValue());
        for (Button chip : memChips) {
            chip.getStyleClass().remove("active");
            if (Integer.valueOf(current).equals(chip.getUserData())) {
                chip.getStyleClass().add("active");
            }
        }
    }

    /** 内存快捷档位按钮（每次重建卡片时重填） */
    private final List<Button> memChips = new ArrayList<>();

    private static int clampMem(int value) {
        return Math.max((int) MEM_SLIDER_MIN, Math.min((int) MEM_SLIDER_MAX, value));
    }

    // ==================== 4. JVM 参数 ====================

    private VBox buildJvmCard() {
        String path = VersionConfigManager.PATH_JVM_ARGS;

        jvmArea = new TextArea(jvmArgsInputValue());
        jvmArea.getStyleClass().addAll("textarea-field", "textarea-mono");
        jvmArea.setWrapText(true);
        jvmArea.setPrefRowCount(3);
        jvmArea.setMaxWidth(Double.MAX_VALUE);
        jvmArea.setMinWidth(0);
        jvmArea.textProperty().addListener((o, a, b) -> markDirty());

        jvmModeCombo = new ComboBox<>();
        jvmModeCombo.getItems().addAll(JVM_ARG_MODES.keySet());
        jvmModeCombo.getStyleClass().add("select-field");
        jvmModeCombo.setPrefWidth(200);
        jvmModeCombo.setMinWidth(Region.USE_PREF_SIZE);
        jvmModeCombo.setValue(displayModeName(VersionConfigManager.jvmArgsMode(gameDir, version)));
        jvmModeCombo.setOnAction(e -> {
            if (jvmModeReloading) return;
            syncJvmPrompt();
            markDirty();
        });

        syncJvmPrompt();

        HBox controls = new HBox(10, jvmModeCombo);
        controls.setAlignment(Pos.CENTER_LEFT);

        VBox body = new VBox(10, jvmArea, controls);
        body.setMinWidth(0);
        return cardShell("jvm", "sliders", "JVM 参数", List.of(path), null, body, () -> {
            jvmModeReloading = true;
            try {
                jvmArea.setText(jvmArgsInputValue());
                jvmModeCombo.setValue(displayModeName(VersionConfigManager.jvmArgsMode(gameDir, version)));
                syncJvmPrompt();
            } finally {
                jvmModeReloading = false;
            }
        });
    }

    private void syncJvmPrompt() {
        if (jvmArea == null || jvmModeCombo == null) return;
        String mode = JVM_ARG_MODES.getOrDefault(jvmModeCombo.getValue(), "replace");
        jvmArea.setPromptText(MODE_PROMPTS.getOrDefault(mode, MODE_PROMPTS.get("replace")));
    }

    /**
     * JVM 参数输入框应显示的内容。
     *
     * <p>增量模式（追加 / 前置 / 移除）下框里显示「增量 token」而不是合并结果，
     * 否则再次保存会把合并后的完整参数当成增量再加一遍。
     */
    private String jvmArgsInputValue() {
        String mode = VersionConfigManager.jvmArgsMode(gameDir, version);
        if (isDeltaMode(mode)) {
            List<String> tokens = VersionConfigManager.jvmArgsOverrideTokens(gameDir, version);
            return String.join(" ", tokens);
        }
        return effective(VersionConfigManager.PATH_JVM_ARGS);
    }

    private static boolean isDeltaMode(String mode) {
        return mode != null && !"replace".equals(mode) && MODE_VERBS.containsKey(mode);
    }

    // ==================== 5. 窗口设置 ====================

    private VBox buildWindowCard() {
        String widthPath = VersionConfigManager.PATH_WIDTH;
        String heightPath = VersionConfigManager.PATH_HEIGHT;
        String fullscreenPath = VersionConfigManager.PATH_FULLSCREEN;

        widthField = new TextField(effective(widthPath));
        widthField.getStyleClass().addAll("input-field", "input-field-sm", "vset-mono", "vset-num");
        widthField.setMinWidth(Region.USE_PREF_SIZE);
        Label x = new Label("×");
        x.getStyleClass().add("vset-unit-label");
        heightField = new TextField(effective(heightPath));
        heightField.getStyleClass().addAll("input-field", "input-field-sm", "vset-mono", "vset-num");
        heightField.setMinWidth(Region.USE_PREF_SIZE);
        widthField.textProperty().addListener((o, a, b) -> markDirty());
        heightField.textProperty().addListener((o, a, b) -> markDirty());

        sizeGroup = new HBox(9, widthField, x, heightField);
        sizeGroup.setAlignment(Pos.CENTER_LEFT);
        HBox sizeRow = settingRow("窗口尺寸", "启动时游戏窗口的初始大小", sizeGroup, false);

        fullscreenBox = PageKit.toggle(effectiveBool(fullscreenPath));
        fullscreenBox.setOnAction(e -> {
            refreshSizeGroupDisabled();
            markDirty();
        });
        HBox fsRow = settingRow("全屏模式", "启动前写入游戏 options.txt 的 fullscreen 字段；全屏时忽略上方窗口尺寸",
                new HBox(fullscreenBox), true);
        refreshSizeGroupDisabled();

        VBox body = new VBox(10, sizeRow, fsRow);
        body.setMinWidth(0);
        return cardShell("window", "window", "窗口设置",
                List.of(widthPath, heightPath, fullscreenPath), null, body, () -> {
                    loading = true;
                    widthField.setText(effective(widthPath));
                    heightField.setText(effective(heightPath));
                    boolean fs = effectiveBool(fullscreenPath);
                    fullscreenBox.setSelected(fs);
                    refreshSizeGroupDisabled();
                    loading = false;
                });
    }

    /** 全屏开启时窗口尺寸控件置灰禁用（对齐参考稿 .size-group.disabled） */
    private void refreshSizeGroupDisabled() {
        if (sizeGroup == null || fullscreenBox == null) return;
        boolean on = fullscreenBox.isSelected();
        sizeGroup.setDisable(on);
        sizeGroup.getStyleClass().remove("disabled");
        if (on) sizeGroup.getStyleClass().add("disabled");
    }

    // ==================== 6. 版本隔离 ====================

    private VBox buildIsolationCard() {
        String path = VersionConfigManager.PATH_VERSION_ISOLATION;

        isolationBox = PageKit.toggle(effectiveBool(path));
        isolationBox.setOnAction(e -> {
            refreshIsolationReadout();
            markDirty();
        });
        HBox row = settingRow("启用版本隔离",
                "每个版本使用独立的存档、模组与配置文件，互不干扰", new HBox(isolationBox), false);

        Region folder = AppIcons.icon("folder", 14, Color.web("#3b82f6"));
        folder.getStyleClass().add("vset-ro-icon");
        isolationText = new Label();
        isolationText.getStyleClass().addAll("vset-ro-text", "vset-mono");
        isolationText.setMinWidth(0);
        HBox.setHgrow(isolationText, Priority.ALWAYS);
        isolationReadout = new HBox(9, folder, isolationText);
        isolationReadout.setAlignment(Pos.CENTER_LEFT);
        isolationReadout.getStyleClass().add("vset-readout");
        isolationReadout.setMinWidth(0);
        refreshIsolationReadout();

        VBox body = new VBox(10, row, isolationReadout);
        body.setMinWidth(0);
        return cardShell("isolation", "shield", "版本隔离", List.of(path), null, body, () -> {
            loading = true;
            isolationBox.setSelected(effectiveBool(path));
            refreshIsolationReadout();
            loading = false;
        });
    }

    /** 隔离路径回显：开启 → versions/<版本>；关闭 → .minecraft（全局共享目录） */
    private void refreshIsolationReadout() {
        if (isolationReadout == null) return;
        boolean on = isolationBox != null && isolationBox.isSelected();
        isolationReadout.getStyleClass().remove("off");
        if (on) {
            Path gd = Paths.get(gameDir);
            Path name = gd.getFileName();
            String base = name == null ? gameDir : name.toString();
            String sep = "\\";
            isolationText.setText(base + sep + "versions" + sep + version);
        } else {
            isolationReadout.getStyleClass().add("off");
            Path gd = Paths.get(gameDir);
            Path name = gd.getFileName();
            String base = name == null ? gameDir : name.toString();
            isolationText.setText(base + "（全局共享目录）");
        }
    }

    // ==================== 6.5 游戏语言 ====================

    private VBox buildLanguageCard() {
        String path = VersionConfigManager.PATH_GAME_LANGUAGE;

        languageCombo = new ComboBox<>();
        languageCombo.getStyleClass().add("select-field");
        languageCombo.setPrefWidth(240);
        languageCombo.setMinWidth(Region.USE_PREF_SIZE);
        languageCombo.getItems().add(LANGUAGE_FOLLOW);
        languageCombo.getItems().addAll(GameLanguage.LANGUAGE_MAP.keySet());
        String current = effective(path);
        languageCombo.setValue((current == null || current.isBlank())
                ? LANGUAGE_FOLLOW : GameLanguage.getDisplayNameByCode(current));
        languageCombo.setOnAction(e -> markDirty());

        HBox row = settingRow("游戏语言",
                "启动前写入游戏 options.txt 的 lang 字段；不改动其他游戏内设置",
                new HBox(languageCombo), false);

        VBox body = new VBox(10, row);
        body.setMinWidth(0);
        return cardShell("language", "languages", "游戏语言", List.of(path), null, body, () -> {
            String v = effective(path);
            languageCombo.setValue((v == null || v.isBlank())
                    ? LANGUAGE_FOLLOW : GameLanguage.getDisplayNameByCode(v));
        });
    }

    /** 下拉当前应写入的语言代码（「不修改」→ 空串：保存时与全局相同则自动恢复继承） */
    private String selectedLanguageCode() {
        if (languageCombo == null || LANGUAGE_FOLLOW.equals(languageCombo.getValue())) return "";
        return GameLanguage.getCodeByDisplayName(languageCombo.getValue());
    }

    // ==================== 7. 游戏参数 ====================

    private VBox buildGameArgsCard() {
        String path = VersionConfigManager.PATH_GAME_ARGS;

        gameArgsField = new TextField(effective(path));
        gameArgsField.getStyleClass().addAll("input-field", "vset-mono");
        gameArgsField.setMaxWidth(Double.MAX_VALUE);
        gameArgsField.setMinWidth(0);
        gameArgsField.textProperty().addListener((o, a, b) -> markDirty());

        HBox note = noteRow("ok", mutedNoteLabel(
                "额外传递给 Minecraft 的参数；多个参数用空格分隔"));

        VBox body = new VBox(10, gameArgsField, note);
        body.setMinWidth(0);
        return cardShell("gameArgs", "terminal", "游戏参数", List.of(path), null, body, () ->
                gameArgsField.setText(effective(path)));
    }

    // ==================== 8/9. 启动前 / 退出后命令 ====================

    private VBox buildPreCommandCard() {
        String path = VersionConfigManager.PATH_PRE_LAUNCH;

        preArea = new TextArea(effective(path));
        preArea.getStyleClass().addAll("textarea-field", "textarea-mono");
        preArea.setWrapText(true);
        preArea.setPrefRowCount(2);
        preArea.setMaxWidth(Double.MAX_VALUE);
        preArea.setMinWidth(0);
        preArea.setPromptText("在游戏启动前执行，例如备份存档…");
        preArea.textProperty().addListener((o, a, b) -> markDirty());

        VBox body = new VBox(10, preArea);
        body.setMinWidth(0);
        return cardShell("pre", "bolt", "启动前命令", List.of(path), null, body, () ->
                preArea.setText(effective(path)));
    }

    private VBox buildPostCommandCard() {
        String path = VersionConfigManager.PATH_POST_EXIT;

        postArea = new TextArea(effective(path));
        postArea.getStyleClass().addAll("textarea-field", "textarea-mono");
        postArea.setWrapText(true);
        postArea.setPrefRowCount(2);
        postArea.setMaxWidth(Double.MAX_VALUE);
        postArea.setMinWidth(0);
        postArea.setPromptText("在游戏退出后执行，例如同步存档…");
        postArea.textProperty().addListener((o, a, b) -> markDirty());

        VBox body = new VBox(10, postArea);
        body.setMinWidth(0);
        return cardShell("post", "power", "退出后命令", List.of(path), null, body, () ->
                postArea.setText(effective(path)));
    }

    // ==================== 底部操作栏 ====================

    private HBox buildFooter() {
        footerDot = new Region();
        footerDot.getStyleClass().add("vset-fh-dot");
        footerHint = new Label();
        footerHint.getStyleClass().add("vset-footer-hint");
        HBox hintBox = new HBox(7, footerDot, footerHint);
        hintBox.setAlignment(Pos.CENTER_LEFT);
        hintBox.setMinWidth(0);
        HBox.setHgrow(hintBox, Priority.ALWAYS);

        Button reset = new Button("恢复默认");
        reset.getStyleClass().add("vset-btn-ghost");
        reset.setMinWidth(Region.USE_PREF_SIZE);
        AppIcons.apply(reset, "refresh", 13, null);
        reset.setOnAction(e -> confirmReset());

        Button save = new Button("保存设置");
        save.getStyleClass().addAll("btn-primary", "vset-save-btn");
        save.setMinWidth(Region.USE_PREF_SIZE);
        AppIcons.apply(save, "save", 13, null);
        save.setOnAction(e -> saveAll());

        HBox bar = new HBox(10, hintBox, reset, save);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("vset-footer");
        return bar;
    }

    private void markDirty() {
        if (loading) return;
        if (!dirty) {
            dirty = true;
            refreshFooterHint();
        }
    }

    /** 根据 dirty 状态刷新底部提示（文案 + 圆点颜色） */
    private void refreshFooterHint() {
        if (footerHint == null) return;
        footerDot.getStyleClass().remove("dirty");
        if (dirty) {
            footerHint.setText("有未保存的修改，点右侧「保存设置」后生效");
            footerDot.getStyleClass().add("dirty");
        } else {
            footerHint.setText("修改将在下次启动该版本时生效");
        }
    }

    /**
     * 重算「是否有未保存修改」：把各控件当前值与已落盘的有效值逐项对比。
     * 用于「恢复继承 / 全部恢复 / 保存」之后的精确状态刷新（输入过程不调用，直接置 dirty）。
     */
    private void recomputeDirty() {
        if (loading) return;
        boolean any = false;
        any |= !javaField.getText().equals(effective(VersionConfigManager.PATH_JAVA_PATH));
        any |= !minMemField.getText().trim().equals(effective(VersionConfigManager.PATH_MIN_MEMORY));
        any |= (int) Math.round(memSlider.getValue())
                != PageKit.parseIntSafe(effective(VersionConfigManager.PATH_MAX_MEMORY), 4096);
        any |= !jvmArea.getText().equals(jvmArgsInputValue());
        any |= !displayModeName(VersionConfigManager.jvmArgsMode(gameDir, version))
                .equals(jvmModeCombo.getValue());
        any |= !widthField.getText().equals(effective(VersionConfigManager.PATH_WIDTH));
        any |= !heightField.getText().equals(effective(VersionConfigManager.PATH_HEIGHT));
        any |= fullscreenBox.isSelected() != effectiveBool(VersionConfigManager.PATH_FULLSCREEN);
        any |= isolationBox.isSelected() != effectiveBool(VersionConfigManager.PATH_VERSION_ISOLATION);
        any |= !gameArgsField.getText().equals(effective(VersionConfigManager.PATH_GAME_ARGS));
        any |= !preArea.getText().equals(effective(VersionConfigManager.PATH_PRE_LAUNCH));
        any |= !postArea.getText().equals(effective(VersionConfigManager.PATH_POST_EXIT));
        any |= !selectedLanguageCode().equals(effective(VersionConfigManager.PATH_GAME_LANGUAGE));
        dirty = any;
        refreshFooterHint();
    }

    /** 统一保存：逐字段对比全局值写覆盖（与全局相同的字段自动恢复继承） */
    private void saveAll() {
        String minS = minMemField.getText().trim();
        String maxS = String.valueOf((int) Math.round(memSlider.getValue()));
        String widthS = widthField.getText().trim();
        String heightS = heightField.getText().trim();

        if (!isNumeric(minS) || !isNumeric(maxS)) {
            host.ui().toast("内存请填写数字（单位 MB）", "warning");
            return;
        }
        if (PageKit.parseIntSafe(minS, 0) > PageKit.parseIntSafe(maxS, 0)) {
            host.ui().toast("最小内存不能大于最大内存", "warning");
            return;
        }
        if (!isNumeric(widthS) || !isNumeric(heightS)) {
            host.ui().toast("窗口宽高请填写数字（像素）", "warning");
            return;
        }
        if (PageKit.parseIntSafe(widthS, 0) <= 0 || PageKit.parseIntSafe(heightS, 0) <= 0) {
            host.ui().toast("窗口宽高必须大于 0", "warning");
            return;
        }
        String jvmMode = JVM_ARG_MODES.getOrDefault(jvmModeCombo.getValue(), "replace");
        List<String> jvmTokens = VersionConfigManager.tokenize(jvmArea.getText());
        if (!"replace".equals(jvmMode) && jvmTokens.isEmpty()) {
            host.ui().toast("请先输入要" + MODE_VERBS.getOrDefault(jvmMode, "调整") + "的 JVM 参数", "warning");
            return;
        }

        VersionConfigManager.setOverrideIfChanged(gameDir, version,
                VersionConfigManager.PATH_JAVA_PATH, javaField.getText().trim(), globalIni);
        VersionConfigManager.setOverrideIfChanged(gameDir, version,
                VersionConfigManager.PATH_MIN_MEMORY, minS, globalIni);
        VersionConfigManager.setOverrideIfChanged(gameDir, version,
                VersionConfigManager.PATH_MAX_MEMORY, maxS, globalIni);
        if ("replace".equals(jvmMode)) {
            VersionConfigManager.setOverrideIfChanged(gameDir, version,
                    VersionConfigManager.PATH_JVM_ARGS, jvmArea.getText().trim(), globalIni);
        } else {
            VersionConfigManager.setJvmArgsOverride(gameDir, version, jvmMode, jvmTokens);
        }
        VersionConfigManager.setOverrideIfChanged(gameDir, version,
                VersionConfigManager.PATH_WIDTH, widthS, globalIni);
        VersionConfigManager.setOverrideIfChanged(gameDir, version,
                VersionConfigManager.PATH_HEIGHT, heightS, globalIni);
        VersionConfigManager.setBooleanOverrideIfChanged(gameDir, version,
                VersionConfigManager.PATH_FULLSCREEN, fullscreenBox.isSelected(), globalIni);
        VersionConfigManager.setBooleanOverrideIfChanged(gameDir, version,
                VersionConfigManager.PATH_VERSION_ISOLATION, isolationBox.isSelected(), globalIni);
        VersionConfigManager.setOverrideIfChanged(gameDir, version,
                VersionConfigManager.PATH_GAME_ARGS, gameArgsField.getText().trim(), globalIni);
        VersionConfigManager.setOverrideIfChanged(gameDir, version,
                VersionConfigManager.PATH_PRE_LAUNCH, preArea.getText().trim(), globalIni);
        VersionConfigManager.setOverrideIfChanged(gameDir, version,
                VersionConfigManager.PATH_POST_EXIT, postArea.getText().trim(), globalIni);
        VersionConfigManager.setOverrideIfChanged(gameDir, version,
                VersionConfigManager.PATH_GAME_LANGUAGE, selectedLanguageCode(), globalIni);

        host.ui().toast("设置已保存 · 当前覆盖 "
                + VersionConfigManager.overriddenPaths(gameDir, version).size() + " 项", "success");
        refreshCardStatuses();
        refreshVersionSummary();
        recomputeDirty();
    }

    /** 恢复默认：清空该版本的全部独立设置（回继承全局），并原地重载各卡片 */
    private void confirmReset() {
        host.ui().confirm("恢复默认",
                "确定要清除版本 \"" + version + "\" 的全部独立设置吗？\n所有字段将恢复继承全局配置。",
                "恢复默认", true, () -> {
                    VersionConfigManager.clearAll(gameDir, version);
                    dirty = false;
                    loading = true;
                    rebuildContent();
                    loading = false;
                    refreshFooterHint();
                    host.ui().toast("已恢复默认设置（全部继承全局）", "warning");
                });
    }

    /** 刷新各卡片右上角标签与「恢复继承」链接的显示 */
    private void refreshCardStatuses() {
        for (CardStatus status : cardStatuses.values()) {
            boolean overridden = false;
            for (String p : status.paths) {
                overridden |= VersionConfigManager.isOverridden(gameDir, version, p);
            }
            status.tag.setText(overridden ? "已覆盖" : "继承全局");
            status.tag.getStyleClass().remove("on");
            if (overridden) status.tag.getStyleClass().add("on");
            status.restore.setVisible(overridden);
            status.restore.setManaged(overridden);
        }
    }

    // ==================== 复制配置弹窗 ====================

    /** 复制文件弹窗：单选目标版本（对齐参考稿的 modal 版式，复用 install-target 行样式） */
    private void openCopyModal() {
        if (!VersionConfigManager.hasOverrides(gameDir, version)) {
            host.ui().toast("当前版本没有任何独立设置，无需复制", "warning");
            return;
        }

        VBox body = new VBox(12);
        body.setAlignment(Pos.TOP_CENTER);
        body.setPadding(new Insets(20, 22, 16, 22));

        StackPane iconBox = new StackPane(AppIcons.icon("copy", 22, Color.web("#3b82f6")));
        iconBox.getStyleClass().addAll("modal-icon", "info");
        Label title = centered("复制配置文件", "modal-card-title");
        Label desc = centered("将「" + version + "」的独立设置复制到以下目标版本。\n目标版本原有的独立设置将被覆盖。",
                "modal-desc");
        body.getChildren().addAll(iconBox, title, desc);

        // ---- 目标版本列表 ----
        ToggleGroup group = new ToggleGroup();
        VBox list = new VBox(6);
        list.getStyleClass().add("install-target-list");

        // 源版本（禁用行，带「当前编辑」徽标）
        list.getChildren().add(copySourceRow());

        List<String> targets = new ArrayList<>();
        for (String dir : VersionUtils.getVisibleVersions(Paths.get(gameDir))) {
            if (!dir.equals(version)) targets.add(dir);
        }
        List<RadioButton> radios = new ArrayList<>();
        if (targets.isEmpty()) {
            Label empty = centered("没有可复制的其他版本", "install-target-empty");
            list.getChildren().add(empty);
        }
        for (String dir : targets) {
            VersionIconKit.Info info = infoOf(dir);
            StringBuilder sub = new StringBuilder(loaderDisplay(info.loader()));
            if (info.base() != null && !info.base().isBlank() && !info.base().equals("null")) {
                sub.append(" · 基于 ").append(info.base());
            }
            if (VersionConfigManager.hasOverrides(gameDir, dir)) {
                sub.append(" · 已有设置将被覆盖");
            }
            Label nameLabel = new Label(dir);
            nameLabel.getStyleClass().add("install-target-name");
            nameLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
            Label subLabel = new Label(sub.toString());
            subLabel.getStyleClass().add("install-target-sub");
            subLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
            VBox infoBox = new VBox(2, nameLabel, subLabel);
            infoBox.setMinWidth(0);
            HBox.setHgrow(infoBox, Priority.ALWAYS);

            StackPane icon = new StackPane(smallIcon(iconImageOf(info), 16));
            icon.getStyleClass().add("install-target-icon");

            RadioButton radio = new RadioButton();
            radio.setToggleGroup(group);
            radio.getStyleClass().add("install-target-radio");
            radio.setUserData(dir);
            radios.add(radio);

            HBox head = new HBox(10, radio, icon, infoBox);
            head.setAlignment(Pos.CENTER_LEFT);
            VBox row = new VBox(head);
            row.getStyleClass().add("install-target-row");
            row.setCursor(Cursor.HAND);
            row.setOnMouseClicked(e -> radio.setSelected(true));
            list.getChildren().add(row);
        }

        ScrollPane listScroll = new ScrollPane(list);
        listScroll.setFitToWidth(true);
        listScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        listScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        listScroll.getStyleClass().add("install-target-scroll");
        listScroll.setPrefViewportHeight(170);
        listScroll.setMinHeight(174);
        listScroll.setMaxHeight(174);
        PageKit.attachInertia(host, listScroll);
        body.getChildren().add(listScroll);

        // ---- 按钮 ----
        Button cancel = new Button("取消");
        cancel.getStyleClass().add("modal-btn-cancel");
        cancel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(cancel, Priority.ALWAYS);
        cancel.setOnAction(e -> host.ui().closeModal());

        Button confirm = new Button("确认复制");
        confirm.getStyleClass().add("modal-btn-ok");
        confirm.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(confirm, Priority.ALWAYS);
        confirm.setOnAction(e -> {
            RadioButton checked = null;
            for (RadioButton r : radios) {
                if (r.isSelected()) {
                    checked = r;
                    break;
                }
            }
            if (checked == null) {
                host.ui().toast("请先选择目标版本", "warning");
                return;
            }
            String target = String.valueOf(checked.getUserData());
            VersionConfigManager.copyOverrides(gameDir, version, target);
            host.ui().closeModal();
            host.ui().toast("已将独立设置复制到「" + target + "」", "success");
        });

        HBox actions = new HBox(10, cancel, confirm);
        actions.setMaxWidth(Double.MAX_VALUE);
        body.getChildren().add(actions);

        host.ui().modal("", body, 420, -1, false);
    }

    /** 弹窗里的源版本行：禁用 + 「当前编辑」徽标 */
    private Node copySourceRow() {
        VersionIconKit.Info info = infoOf(version);
        RadioButton radio = new RadioButton();
        radio.setDisable(true);
        radio.getStyleClass().add("install-target-radio");

        Label nameLabel = new Label(version);
        nameLabel.getStyleClass().add("install-target-name");
        nameLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        Label subLabel = new Label(loaderDisplay(info.loader()) + " · 当前编辑");
        subLabel.getStyleClass().add("install-target-sub");

        VBox infoBox = new VBox(2, nameLabel, subLabel);
        infoBox.setMinWidth(0);
        HBox.setHgrow(infoBox, Priority.ALWAYS);

        StackPane icon = new StackPane(smallIcon(iconImageOf(info), 16));
        icon.getStyleClass().add("install-target-icon");

        Label badge = new Label("当前");
        badge.getStyleClass().addAll("install-target-badge", "current");

        HBox head = new HBox(10, radio, icon, infoBox, badge);
        head.setAlignment(Pos.CENTER_LEFT);
        VBox row = new VBox(head);
        row.getStyleClass().addAll("install-target-row", "vset-row-disabled");
        return row;
    }

    private static Label centered(String text, String styleClass) {
        Label l = new Label(text);
        l.getStyleClass().add(styleClass);
        l.setMaxWidth(Double.MAX_VALUE);
        l.setAlignment(Pos.CENTER);
        l.setWrapText(true);
        l.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        return l;
    }

    // ==================== 构建辅助 ====================

    private String effective(String path) {
        return VersionConfigManager.effectiveScalar(globalIni, gameDir, version, path);
    }

    private boolean effectiveBool(String path) {
        return "true".equalsIgnoreCase(effective(path));
    }

    private static boolean isNumeric(String value) {
        return value != null && value.matches("\\d+");
    }

    private static final Map<String, String> PATH_DISPLAY_NAMES = Map.ofEntries(
            Map.entry(VersionConfigManager.PATH_JAVA_PATH, "Java路径"),
            Map.entry(VersionConfigManager.PATH_MIN_MEMORY, "最小内存"),
            Map.entry(VersionConfigManager.PATH_MAX_MEMORY, "最大内存"),
            Map.entry(VersionConfigManager.PATH_JVM_ARGS, "JVM参数"),
            Map.entry(VersionConfigManager.PATH_WIDTH, "窗口宽度"),
            Map.entry(VersionConfigManager.PATH_HEIGHT, "窗口高度"),
            Map.entry(VersionConfigManager.PATH_FULLSCREEN, "全屏模式"),
            Map.entry(VersionConfigManager.PATH_VERSION_ISOLATION, "版本隔离"),
            Map.entry(VersionConfigManager.PATH_GAME_ARGS, "游戏参数"),
            Map.entry(VersionConfigManager.PATH_PRE_LAUNCH, "启动前命令"),
            Map.entry(VersionConfigManager.PATH_POST_EXIT, "退出后命令"),
            Map.entry(VersionConfigManager.PATH_GAME_LANGUAGE, "游戏语言"));

    private static List<String> displayNames(Set<String> paths) {
        return paths.stream().map(p -> PATH_DISPLAY_NAMES.getOrDefault(p, p)).toList();
    }

    private static String displayModeName(String mode) {
        return JVM_ARG_MODES.entrySet().stream()
                .filter(e -> e.getValue().equals(mode))
                .map(Map.Entry::getKey)
                .findFirst().orElse("替换全局参数");
    }
}
