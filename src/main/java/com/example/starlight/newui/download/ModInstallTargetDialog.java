package com.example.starlight.newui.download;

import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.ui.PageKit;
import com.example.starlight.newui.ui.VersionIconKit;
import com.example.starlight.util.VersionUtils;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 「配置下载路径」弹窗：下载模组前先确定文件落到哪个 mods 文件夹。
 *
 * <p>版式对齐 {@code 下载.html} 设计稿：顶部图标块、标题、说明，中间是定高滚动的候选列表
 * （分组：全局文件夹 / 版本文件夹 / 自定义路径 · 临时），下面是通栏虚线的
 * 「打开资源管理器自定义路径」按钮、当前选中路径预览、取消 / 确认下载。宽度与列表高度
 * 都取设计稿的值（380 / 168）。
 *
 * <p>为什么必须问：开启版本隔离时游戏只读 {@code versions/<版本>/mods}，关闭时只读全局
 * {@code mods}，放错地方的表现就是「装完了但游戏里没有」。列表里带绿色「会加载」徽章的那一项
 * 才是当前配置下真正生效的文件夹。
 *
 * <p>比设计稿多的两点：候选列表上方的搜索框（版本多时按名字/路径过滤）；
 * 自定义路径选到游戏目录本身时自动补 {@code mods} 一级（见 {@link #resolvePickedDir}）。
 */
public final class ModInstallTargetDialog {

    /** 弹窗宽度，与设计稿 {@code .modal} 一致 */
    private static final double MODAL_WIDTH = 380;
    /** 候选列表固定高度，与设计稿 {@code .path-list} 一致（正好 3 行候选） */
    private static final double LIST_HEIGHT = 168;

    private final LauncherContext host;
    private final Path root;
    private final boolean isolation;
    private final String currentVersion;
    private final String what;
    private final Consumer<Path> onConfirm;

    /** 一个候选位置：单选按钮、整行节点、展示名、副标题（相对路径，自定义为绝对路径）、落地目录 */
    private record Row(RadioButton radio, VBox node, String name, String sub, Path dir) {
    }

    private final ToggleGroup group = new ToggleGroup();
    private final List<Row> rows = new ArrayList<>();
    /** 版本候选（搜索只过滤候选行，全局项始终显示） */
    private final List<Row> filterableRows = new ArrayList<>();
    /** 自定义候选容器与分组标签：加入第一项时才显示 */
    private VBox customBox;
    private Label customGroupLabel;
    private Label emptyNote;

    private final Label previewValue = new Label();
    private Button openBtn;
    private Button confirmBtn;

    private ModInstallTargetDialog(LauncherContext host, String gameDir, String currentVersion,
                                   String what, Consumer<Path> onConfirm) {
        this.host = host;
        String dir = (gameDir == null || gameDir.isBlank()) ? ".minecraft" : gameDir;
        this.root = Paths.get(dir).toAbsolutePath();
        Map<String, String> cfg = host.config();
        this.isolation = cfg != null && "true".equalsIgnoreCase(cfg.getOrDefault("VersionIsolation", "false"));
        this.currentVersion = currentVersion == null ? "" : currentVersion.trim();
        this.what = what == null || what.isBlank() ? "模组" : what;
        this.onConfirm = onConfirm;
    }

    /**
     * 弹出「配置下载路径」弹窗。
     *
     * @param gameDir        游戏根目录（可相对）
     * @param currentVersion 当前选中的游戏版本，作为默认选中项；为空则默认全局文件夹
     * @param what           下载内容描述（如「模组 Iris」），只用于文案
     * @param onConfirm      确认后的回调，参数为选定的 mods 目录绝对路径；用户取消时不回调
     */
    public static void show(LauncherContext host, String gameDir, String currentVersion,
                            String what, Consumer<Path> onConfirm) {
        new ModInstallTargetDialog(host, gameDir, currentVersion, what, onConfirm).open();
    }

    // ==================== 构建 ====================

    private void open() {
        List<String> versions = new ArrayList<>();
        if (!currentVersion.isEmpty()) versions.add(currentVersion);
        for (String v : VersionUtils.getVisibleVersions(root)) {
            if (!versions.contains(v)) versions.add(v);
        }
        Path loadDir = loadedDir();

        VBox body = new VBox(10);
        body.setAlignment(Pos.TOP_CENTER);
        body.getChildren().add(dialogIcon());
        body.getChildren().add(centered("配置下载路径", "modal-card-title"));
        body.getChildren().add(centered("选择" + what + "的下载位置", "modal-desc"));
        body.getChildren().add(centered(isolationHintText(), "modal-hint"));

        // ===== 候选列表：全局 / 版本 / 自定义（临时） =====
        VBox list = new VBox(6);
        list.getStyleClass().add("install-target-list");

        list.getChildren().add(groupLabel("全局文件夹"));
        Path globalDir = root.resolve("mods");
        list.getChildren().add(createRow("全局文件夹", relative(globalDir), globalDir,
                AppIcons.icon("globe", 16, Color.web("#8b5cf6")), "global",
                List.of(badge(samePath(globalDir, loadDir) ? "会加载" : "所有版本共用",
                        samePath(globalDir, loadDir) ? "accent" : "shared")), false).node());

        list.getChildren().add(groupLabel("版本文件夹"));
        emptyNote = centered("没有已安装的游戏版本", "install-target-empty");
        emptyNote.setVisible(versions.isEmpty());
        emptyNote.setManaged(versions.isEmpty());
        list.getChildren().add(emptyNote);
        for (String v : versions) {
            Path dir = root.resolve("versions").resolve(v).resolve("mods");
            List<Label> badges = new ArrayList<>();
            if (samePath(dir, loadDir)) badges.add(badge("会加载", "accent"));
            if (v.equals(currentVersion)) badges.add(badge("当前", "current"));
            list.getChildren().add(createRow(v, relative(dir), dir,
                    VersionIconKit.icon(VersionIconKit.read(root.toString(), v), 16), "version",
                    badges, true).node());
        }

        customGroupLabel = groupLabel("自定义路径 · 临时");
        customGroupLabel.setVisible(false);
        customGroupLabel.setManaged(false);
        customBox = new VBox(6);
        customBox.setVisible(false);
        customBox.setManaged(false);
        list.getChildren().addAll(customGroupLabel, customBox);

        ScrollPane listScroll = new ScrollPane(list);
        listScroll.setFitToWidth(true);
        listScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        listScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        listScroll.getStyleClass().add("install-target-scroll");
        listScroll.setPrefViewportHeight(LIST_HEIGHT);
        listScroll.setMinHeight(LIST_HEIGHT + 4);
        listScroll.setMaxHeight(LIST_HEIGHT + 4);
        // 版本选择列表补挂惯性滚动（阻尼 + fling）；固定尺寸保持不动
        PageKit.attachInertia(host, listScroll);

        if (!versions.isEmpty()) body.getChildren().add(searchRow());
        body.getChildren().add(listScroll);
        body.getChildren().add(browseButton());
        body.getChildren().add(previewBox());
        body.getChildren().add(actions());

        // 默认选中当前游戏版本；没有当前版本时退回全局文件夹
        Row preferred = null;
        for (Row r : rows) {
            if (r.name().equals(currentVersion)) preferred = r;
        }
        (preferred != null ? preferred : rows.get(0)).radio().setSelected(true);
        group.selectedToggleProperty().addListener((o, a, b) -> refresh());

        // 设计稿里没有关闭按钮，退出靠「取消」，所以 showCloseBtn 传 false
        host.ui().modal("", body, MODAL_WIDTH, -1, false);
        refresh();
    }

    /** 顶部图标块（对应设计稿的 .modal-icon.info） */
    private Node dialogIcon() {
        StackPane box = new StackPane(AppIcons.icon("download", 24, Color.web("#3b82f6")));
        box.getStyleClass().addAll("modal-icon", "info");
        VBox.setMargin(box, new Insets(0, 0, 10, 0));
        return box;
    }

    private static Label centered(String text, String styleClass) {
        Label l = new Label(text);
        l.getStyleClass().add(styleClass);
        l.setMaxWidth(Double.MAX_VALUE);
        l.setAlignment(Pos.CENTER);
        l.setWrapText(true);
        return l;
    }

    /** 搜索框：放大镜 + 透明输入框 + 清除按钮 */
    private Node searchRow() {
        TextField field = new TextField();
        field.setPromptText("搜索版本");
        field.getStyleClass().add("install-target-search-field");
        HBox.setHgrow(field, Priority.ALWAYS);
        field.textProperty().addListener((o, a, b) -> filter(b));

        Button clear = new Button();
        clear.getStyleClass().add("install-target-clear");
        AppIcons.apply(clear, "close", 11, null);
        clear.setVisible(false);
        clear.setManaged(false);
        clear.setOnAction(e -> {
            field.clear();
            field.requestFocus();
        });
        field.textProperty().addListener((o, a, b) -> {
            boolean on = b != null && !b.isEmpty();
            clear.setVisible(on);
            clear.setManaged(on);
        });

        HBox box = new HBox(7, AppIcons.icon("search", 13, Color.web("#9ca3af")), field, clear);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("install-target-search");
        return box;
    }

    /** 新建候选行：圆点 + 分类图标 + 名称/路径 + 徽章 */
    private Row createRow(String name, String sub, Path dir, Node icon, String iconKind,
                          List<Label> badges, boolean filterable) {
        RadioButton radio = new RadioButton();
        radio.setToggleGroup(group);
        radio.getStyleClass().add("install-target-radio");

        StackPane iconBox = new StackPane(icon);
        iconBox.getStyleClass().addAll("install-target-icon", iconKind);

        Label nameLabel = new Label(name);
        nameLabel.getStyleClass().add("install-target-name");
        nameLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        Label subLabel = new Label(sub);
        subLabel.getStyleClass().add("install-target-sub");
        subLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        VBox info = new VBox(2, nameLabel, subLabel);
        info.setMinWidth(0);
        HBox.setHgrow(info, Priority.ALWAYS);

        HBox head = new HBox(10, radio, iconBox, info);
        head.setAlignment(Pos.CENTER_LEFT);
        head.getChildren().addAll(badges);

        VBox row = new VBox(head);
        row.getStyleClass().add("install-target-row");
        row.setCursor(Cursor.HAND);
        row.setOnMouseClicked(e -> radio.setSelected(true));
        // 列表里显示相对路径，完整绝对路径挂在 tooltip 上（VBox 不是 Control，只能 install）
        Tooltip.install(row, new Tooltip(dir.toString()));

        Row entry = new Row(radio, row, name, sub, dir);
        rows.add(entry);
        if (filterable) filterableRows.add(entry);
        return entry;
    }

    /** 通栏虚线按钮：打开资源管理器自选路径（加入临时候选，不写配置） */
    private Button browseButton() {
        Button browse = new Button("打开资源管理器自定义路径");
        browse.getStyleClass().add("install-target-browse");
        AppIcons.apply(browse, "folder-open", 14, null);
        browse.setMaxWidth(Double.MAX_VALUE);
        browse.setOnAction(e -> browseCustomPath());
        return browse;
    }

    /** 当前选中路径预览：整块可点，直接打开该文件夹 */
    private HBox previewBox() {
        Label label = new Label("下载至");
        label.getStyleClass().add("install-target-preview-label");
        previewValue.getStyleClass().add("install-target-preview-value");
        previewValue.setTextOverrun(OverrunStyle.ELLIPSIS);
        VBox text = new VBox(1, label, previewValue);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);

        openBtn = new Button();
        openBtn.getStyleClass().add("install-target-open");
        AppIcons.apply(openBtn, "folder-open", 13, null);
        openBtn.setMinWidth(Region.USE_PREF_SIZE);
        openBtn.setOnAction(e -> openSelectedDir());

        HBox box = new HBox(10, AppIcons.icon("folder", 13, Color.web("#3b82f6")), text, openBtn);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("install-target-preview");
        box.setCursor(Cursor.HAND);
        box.setOnMouseClicked(e -> openSelectedDir());
        return box;
    }

    private HBox actions() {
        Button cancelBtn = new Button("取消");
        cancelBtn.getStyleClass().add("modal-btn-cancel");
        cancelBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(cancelBtn, Priority.ALWAYS);
        cancelBtn.setOnAction(e -> host.ui().closeModal());

        confirmBtn = new Button("确认下载");
        confirmBtn.getStyleClass().add("modal-btn-ok");
        confirmBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(confirmBtn, Priority.ALWAYS);
        confirmBtn.setOnAction(e -> {
            Path dir = selectedDir();
            if (dir == null) return;
            host.ui().closeModal();
            onConfirm.accept(dir);
        });

        HBox box = new HBox(10, cancelBtn, confirmBtn);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    // ==================== 交互 ====================

    private Label groupLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("install-target-group");
        return l;
    }

    private static Label badge(String text, String kind) {
        Label l = new Label(text);
        l.getStyleClass().add("install-target-badge");
        if (kind != null) l.getStyleClass().add(kind);
        l.setMinWidth(Region.USE_PREF_SIZE);
        return l;
    }

    /** 搜索过滤：名字与路径都参与匹配；全部被过滤掉时列表里给个提示 */
    private void filter(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (Row r : filterableRows) {
            boolean hit = q.isEmpty()
                    || r.name().toLowerCase(Locale.ROOT).contains(q)
                    || r.sub().toLowerCase(Locale.ROOT).contains(q);
            r.node().setVisible(hit);
            r.node().setManaged(hit);
            if (hit) shown++;
        }
        if (emptyNote != null && !filterableRows.isEmpty()) {
            boolean none = shown == 0;
            emptyNote.setText("没有匹配的版本");
            emptyNote.setVisible(none);
            emptyNote.setManaged(none);
        }
    }

    /** 打开资源管理器选文件夹；已有同目录候选时直接选中它，不重复添加 */
    private void browseCustomPath() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择下载到哪个文件夹");
        Path current = selectedDir();
        Path start = bestExistingDir(current == null ? root : current);
        if (start != null) chooser.setInitialDirectory(start.toFile());
        File picked = chooser.showDialog(host.stage());
        if (picked == null) return;

        Path dir = resolvePickedDir(picked.toPath());
        for (Row r : rows) {
            if (samePath(r.dir(), dir)) {
                r.radio().setSelected(true);
                return;
            }
        }
        // 自定义候选不参与搜索过滤：刚刚手选的位置必须一直看得见
        Row added = createRow(folderName(dir), dir.toString(), dir,
                AppIcons.icon("folder", 16, Color.web("#16a34a")), "custom",
                List.of(badge("不保存", "temp")), false);
        customBox.getChildren().add(added.node());
        customGroupLabel.setVisible(true);
        customGroupLabel.setManaged(true);
        customBox.setVisible(true);
        customBox.setManaged(true);
        added.radio().setSelected(true);
        refresh();
    }

    private Row selectedRow() {
        for (Row r : rows) {
            if (r.radio().isSelected()) return r;
        }
        return null;
    }

    private Path selectedDir() {
        Row r = selectedRow();
        return r == null ? null : r.dir();
    }

    /** 刷新选中高亮、路径预览与按钮可用性 */
    private void refresh() {
        Row sel = selectedRow();
        for (Row r : rows) {
            boolean on = r.radio().isSelected();
            if (on) {
                if (!r.node().getStyleClass().contains("selected")) r.node().getStyleClass().add("selected");
            } else {
                r.node().getStyleClass().remove("selected");
            }
        }
        if (sel == null) {
            previewValue.setText("请选择下载位置");
            if (!previewValue.getStyleClass().contains("empty")) previewValue.getStyleClass().add("empty");
            if (confirmBtn != null) confirmBtn.setDisable(true);
            if (openBtn != null) openBtn.setDisable(true);
            return;
        }
        previewValue.getStyleClass().remove("empty");
        previewValue.setText(sel.sub());
        previewValue.setTooltip(new Tooltip(sel.dir().toString()));
        if (confirmBtn != null) confirmBtn.setDisable(false);
        if (openBtn != null) openBtn.setDisable(!Files.isDirectory(sel.dir()));
    }

    private void openSelectedDir() {
        Path dir = selectedDir();
        if (dir != null && Files.isDirectory(dir)) host.openFile(dir.toString());
    }

    // ==================== 路径规则 ====================

    /**
     * 用户在资源管理器里选中的文件夹 → 实际使用的 mods 目录。
     *
     * <p>选到的若是游戏目录本身（含 {@code versions} 子目录，或就叫 {@code .minecraft}），
     * 自动补上 mods 一级，免得把模组 jar 直接倒进 .minecraft；其它情况原样使用选中的文件夹。
     */
    private static Path resolvePickedDir(Path picked) {
        Path resolved = picked.toAbsolutePath();
        if (Files.isDirectory(resolved.resolve("versions"))
                || ".minecraft".equalsIgnoreCase(String.valueOf(resolved.getFileName()))) {
            return resolved.resolve("mods");
        }
        return resolved;
    }

    /** 当前配置下游戏真正会加载的 mods 目录；无法判定（开隔离但没选版本）时返回 null */
    private Path loadedDir() {
        if (!isolation) return root.resolve("mods");
        if (currentVersion.isEmpty()) return null;
        return root.resolve("versions").resolve(currentVersion).resolve("mods");
    }

    private String isolationHintText() {
        if (!isolation) {
            return "未开启版本隔离：游戏只加载全局 mods 文件夹";
        }
        if (currentVersion.isEmpty()) {
            return "已开启版本隔离，但还没选中游戏版本";
        }
        return "已开启版本隔离：只加载当前版本的 mods 文件夹";
    }

    /** 相对游戏目录显示；不在其下时退回绝对路径 */
    private String relative(Path dir) {
        try {
            Path rel = root.relativize(dir);
            if (!rel.toString().startsWith("..")) {
                String s = rel.toString().replace('\\', '/');
                return s.isEmpty() ? "." : s;
            }
        } catch (Exception ignored) {
        }
        return dir.toString();
    }

    private static String folderName(Path dir) {
        Path name = dir.getFileName();
        return name == null ? dir.toString() : name.toString();
    }

    private static boolean samePath(Path a, Path b) {
        return a != null && b != null && a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }

    /** 目录选择器的起始目录：选中目录存在就用它，否则用最近的已存在父目录 */
    private static Path bestExistingDir(Path dir) {
        Path p = dir;
        while (p != null && !Files.isDirectory(p)) p = p.getParent();
        return p;
    }
}
