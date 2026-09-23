package com.example.starlight.newui;

import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.PageKit;
import com.example.starlight.newui.ui.UiService;
import com.example.starlight.newui.ui.VersionIconKit;
import com.example.starlight.service.GameInstanceRegistry;
import com.example.starlight.service.GameInstanceRegistry.Instance;
import com.example.starlight.util.FormatUtils;
import com.example.starlight.util.ProcessMemory;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.RotateTransition;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 「关闭游戏进程」弹窗内容（对应设计稿 关闭游戏弹窗示例.html），三种状态：
 *
 * <ul>
 *   <li><b>多进程</b>：最近启动的实例高亮常显，其余收在「其他游戏进程」折叠框里；可勾选批量关闭，
 *       也可点行尾 ✕ 只关那一个；列表区固定 168px 高，展开折叠框不会把弹窗撑大。</li>
 *   <li><b>单进程</b>：只有 1 个实例（或从行尾 ✕ 进来）时展示进程名称 / PID / 内存 / 时长的信息表。</li>
 *   <li><b>空状态</b>：全部关完后显示提示，按钮变「完成」。</li>
 * </ul>
 *
 * <p>数据来自 {@link GameInstanceRegistry}（只含本启动器拉起的实例）。打开期间每秒刷新运行时长、
 * 每 2 秒复查存活并刷新内存，实例退出会自动从列表里消失；弹窗被别的弹窗顶掉时定时器自行停止。
 */
public final class GameProcessPanel extends VBox {

    /** 列表区固定高度：折叠框展开/收起时弹窗高度不变（示例里的 .process-area） */
    private static final double LIST_HEIGHT = 168;
    /** 列表区内容宽度（卡片 380 - 内边距 20×2）：折叠框测量高度用 */
    private static final double CONTENT_WIDTH = 340;

    private final UiService ui;
    /** 启动器上下文：进程日志滚动区挂接惯性滚动用（可为 null，null 时保持原生滚动） */
    private final LauncherContext ctx;
    private final Consumer<String> toast;
    private final Runnable closeHandler;

    /** 当前展示的实例（按启动时间倒序，最近启动在最前） */
    private final List<Instance> shown = new ArrayList<>();
    /** 勾选中的 PID */
    private final Set<Long> selected = new LinkedHashSet<>();

    // 行内控件引用（每次渲染重建），用于原地刷新而不重建整棵列表
    private final Map<Long, HBox> rowNodes = new HashMap<>();
    private final Map<Long, CheckBox> rowChecks = new HashMap<>();
    private final Map<Long, Label> rowMemories = new HashMap<>();
    private final Map<Long, Label> rowUptimes = new HashMap<>();

    // 底部与按钮区引用
    private CheckBox checkAllBox;
    private Label selectedCountValue;
    private Button closeSelectedBtn;
    private VBox collapseBox;
    private Label collapseHint;
    private Label collapseArrow;
    private VBox collapseBody;

    // 单进程视图的数字标签
    private Label singleMemoryLabel;
    private Label singleUptimeLabel;

    /** 单进程视图的目标（null = 不在单进程视图） */
    private Long singlePid;
    /** 是否从多进程列表的行尾 ✕ 进来的：是的话确认/取消后回到列表 */
    private boolean fromMulti;
    /** 折叠框是否展开（重渲染时保留） */
    private boolean collapseExpanded;

    private StackPane overlayRef;
    private Timeline refreshTimer;
    private int tickCount;

    public GameProcessPanel(LauncherContext ctx, UiService ui, Consumer<String> toast, Runnable closeHandler) {
        this.ui = ui;
        this.toast = toast;
        this.closeHandler = closeHandler;
        this.ctx = ctx;
        setAlignment(Pos.TOP_CENTER);
    }

    /** 打开弹窗：按当前实例数量决定进哪个视图 */
    public void show() {
        List<Instance> alive = GameInstanceRegistry.aliveInstances();
        if (alive.isEmpty()) {
            toast.accept("当前没有正在运行的游戏进程");
            return;
        }
        if (alive.size() == 1) {
            // 只有 1 个实例：直接进单进程视图（示例的「自动降级」）
            singlePid = alive.get(0).pid;
            fromMulti = false;
        } else {
            selected.clear();
            selected.add(alive.get(0).pid);   // 默认选中最近启动的
        }
        render();
        ui.modal(null, this, 380, -1, false);
        overlayRef = ui.currentModalOverlay();
        bindOverlayDismiss();
        startRefresh();
    }

    // ==================== 渲染 ====================

    private void render() {
        rowNodes.clear();
        rowChecks.clear();
        rowMemories.clear();
        rowUptimes.clear();
        checkAllBox = null;
        selectedCountValue = null;
        closeSelectedBtn = null;
        collapseBox = null;
        collapseBody = null;
        singleMemoryLabel = null;
        singleUptimeLabel = null;
        getChildren().clear();

        shown.clear();
        shown.addAll(GameInstanceRegistry.aliveInstances());

        if (singlePid != null) {
            Instance target = find(shown, singlePid);
            if (target != null) {
                buildSingle(target);
                return;
            }
            singlePid = null;   // 目标已经退出了，落到列表/空状态
        }
        if (shown.isEmpty()) {
            buildEmpty();
        } else {
            buildMulti();
        }
    }

    private static Instance find(List<Instance> list, long pid) {
        for (Instance i : list) {
            if (i.pid == pid) return i;
        }
        return null;
    }

    /** 多进程视图 */
    private void buildMulti() {
        getChildren().addAll(
                iconBlock("power", "danger"),
                titleLabel("关闭游戏进程"),
                countDesc(shown.size()),
                processArea());

        // 底部：全选 + 已选计数
        checkAllBox = new CheckBox("全选");
        checkAllBox.getStyleClass().addAll("process-check", "process-check-all");
        checkAllBox.setFocusTraversable(false);
        checkAllBox.setOnAction(e -> {
            if (checkAllBox.isSelected()) {
                shown.forEach(i -> selected.add(i.pid));
            } else {
                selected.clear();
            }
            applySelectionToRows();
        });
        Label selectedText = new Label("已选");
        selectedText.getStyleClass().add("process-selected-count");
        selectedCountValue = new Label("0");
        selectedCountValue.getStyleClass().add("process-selected-count-value");
        Label selectedUnit = new Label("个进程");
        selectedUnit.getStyleClass().add("process-selected-count");
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox footer = new HBox(6, checkAllBox, footerSpacer, selectedText, selectedCountValue, selectedUnit);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("process-footer");
        VBox.setMargin(footer, new Insets(12, 2, 0, 2));
        getChildren().add(footer);

        // 按钮区
        Button cancel = new Button("取消");
        cancel.getStyleClass().add("modal-btn-cancel");
        cancel.setOnAction(e -> doClose());
        closeSelectedBtn = new Button("关闭选中进程");
        closeSelectedBtn.getStyleClass().add("modal-btn-danger");
        closeSelectedBtn.setOnAction(e -> closeSelected());
        getChildren().add(actionRow(cancel, closeSelectedBtn));

        applySelectionToRows();
    }

    /** 单进程视图 */
    private void buildSingle(Instance inst) {
        getChildren().addAll(
                iconBlock("close", "danger"),
                titleLabel("关闭游戏进程"),
                singleDesc());

        singleMemoryLabel = valueLabel(memoryText(inst.pid));
        singleUptimeLabel = valueLabel(FormatUtils.formatDuration(inst.uptimeMillis()));
        VBox list = new VBox(7,
                infoRow("进程名称", valueLabel(inst.displayName())),
                infoRow("进程 PID", valueLabel(String.valueOf(inst.pid))),
                infoRow("内存占用", singleMemoryLabel),
                infoRow("运行时长", singleUptimeLabel));
        list.getStyleClass().add("modal-list");
        VBox.setMargin(list, new Insets(14, 0, 0, 0));
        getChildren().add(list);

        Button cancel = new Button("取消");
        cancel.getStyleClass().add("modal-btn-cancel");
        cancel.setOnAction(e -> onSingleCancel());
        Button confirm = new Button("关闭进程");
        confirm.getStyleClass().add("modal-btn-danger");
        confirm.setOnAction(e -> closeSingle(inst));
        getChildren().add(actionRow(cancel, confirm));
    }

    /** 空状态：全部关完 */
    private void buildEmpty() {
        getChildren().addAll(
                iconBlock("power", "danger"),
                titleLabel("关闭游戏进程"),
                descLabel("当前没有正在运行的游戏进程"));

        Region icon = AppIcons.icon("circle-check", 28, Color.web("#22c55e"));
        icon.getStyleClass().add("process-empty-icon");
        Label text = new Label("所有游戏进程已关闭");
        VBox empty = new VBox(10, icon, text);
        empty.setAlignment(Pos.CENTER);
        empty.setPrefHeight(LIST_HEIGHT);
        empty.setMinHeight(LIST_HEIGHT);
        empty.getStyleClass().add("process-empty");
        getChildren().add(empty);

        Button done = new Button("完成");
        done.getStyleClass().add("modal-btn-danger");
        done.setOnAction(e -> doClose());
        getChildren().add(actionRow(null, done));
    }

    /** 固定高度的进程列表区（折叠框在里面滚动，弹窗高度不变） */
    private ScrollPane processArea() {
        VBox content = new VBox(6);
        content.setFillWidth(true);
        content.getChildren().add(processRow(shown.get(0), true));

        List<Instance> others = shown.size() > 1 ? shown.subList(1, shown.size()) : List.of();
        if (!others.isEmpty()) {
            VBox inner = new VBox(6);
            inner.getStyleClass().add("collapse-inner");
            for (Instance inst : others) {
                inner.getChildren().add(processRow(inst, false));
            }

            collapseBody = new VBox(inner);
            collapseBody.getStyleClass().add("collapse-body");
            // 折叠动画靠改 prefHeight，超出部分必须裁掉，否则会盖到下面的全选栏
            Rectangle clip = new Rectangle();
            clip.widthProperty().bind(collapseBody.widthProperty());
            clip.heightProperty().bind(collapseBody.heightProperty());
            collapseBody.setClip(clip);
            collapseBody.setVisible(collapseExpanded);
            collapseBody.setManaged(collapseExpanded);

            collapseArrow = new Label("▶");
            collapseArrow.getStyleClass().add("collapse-arrow");
            Label title = new Label("其他游戏进程");
            title.getStyleClass().add("collapse-title");
            Label count = new Label(String.valueOf(others.size()));
            count.getStyleClass().add("collapse-count");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            collapseHint = new Label("点击展开");
            collapseHint.getStyleClass().add("collapse-hint");

            HBox header = new HBox(8, collapseArrow, title, count, spacer, collapseHint);
            header.setAlignment(Pos.CENTER_LEFT);
            header.getStyleClass().add("collapse-header");
            header.setOnMouseClicked(e -> toggleCollapse());

            collapseBox = new VBox(header, collapseBody);
            collapseBox.getStyleClass().add("collapse-box");
            if (collapseExpanded) collapseBox.getStyleClass().add("expanded");
            content.getChildren().add(collapseBox);
        }

        ScrollPane area = new ScrollPane(content);
        area.getStyleClass().add("process-area");
        area.setFitToWidth(true);
        area.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        area.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        area.setPrefHeight(LIST_HEIGHT);
        area.setMinHeight(LIST_HEIGHT);
        area.setMaxHeight(LIST_HEIGHT);
        // 日志滚动区补挂惯性滚动（阻尼 + fling）；固定尺寸由 attachInertia 保持不动
        if (ctx != null) PageKit.attachInertia(ctx, area);
        VBox.setMargin(area, new Insets(13, 0, 0, 0));
        return area;
    }

    /** 单个进程行 */
    private HBox processRow(Instance inst, boolean recent) {
        CheckBox check = new CheckBox();
        check.getStyleClass().add("process-check");
        check.setMouseTransparent(true);   // 勾选统一由整行点击驱动，避免与行事件双重切换
        check.setFocusTraversable(false);

        StackPane icon = new StackPane(
                VersionIconKit.icon(VersionIconKit.read(inst.gameDir, inst.version), 18));
        icon.getStyleClass().add("process-icon");

        Label name = new Label(inst.displayName());
        name.getStyleClass().add("process-name");
        String meta = "PID " + inst.pid + " · Java" + (inst.loader.isBlank() ? "" : " · " + inst.loader);
        Label metaLabel = new Label(meta);
        metaLabel.getStyleClass().add("process-meta");
        VBox info = new VBox(2, name, metaLabel);
        HBox.setHgrow(info, Priority.ALWAYS);
        // 被压缩时省略号截断的是「名称 / PID」这一列；右侧内存与时长必须完整显示，不能跟着被挤掉
        name.setMinWidth(0);
        metaLabel.setMinWidth(0);
        info.setMinWidth(0);

        Label memory = new Label(memoryText(inst.pid));
        memory.getStyleClass().add("process-memory");
        Label uptime = new Label(FormatUtils.formatDuration(inst.uptimeMillis()));
        uptime.getStyleClass().add("process-uptime");
        VBox stat = new VBox(2, memory, uptime);
        stat.setAlignment(Pos.CENTER_RIGHT);
        memory.setMinWidth(Region.USE_PREF_SIZE);
        uptime.setMinWidth(Region.USE_PREF_SIZE);
        stat.setMinWidth(Region.USE_PREF_SIZE);

        Button rowClose = new Button();
        rowClose.getStyleClass().add("row-close");
        AppIcons.apply(rowClose, "close", 11, null);
        rowClose.setTooltip(new Tooltip("仅关闭此进程"));
        rowClose.setFocusTraversable(false);
        rowClose.setOnAction(e -> openSingle(inst.pid));
        // 点 ✕ 时不要顺带触发行点击（否则会切换勾选）
        rowClose.addEventFilter(MouseEvent.MOUSE_CLICKED, MouseEvent::consume);

        HBox row = new HBox(9, check, icon, info, stat, rowClose);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("process-item");
        if (recent) row.getStyleClass().add("recent-item");
        row.setMinWidth(0);
        row.setOnMouseClicked(e -> toggleSelection(inst.pid, row));

        rowNodes.put(inst.pid, row);
        rowChecks.put(inst.pid, check);
        rowMemories.put(inst.pid, memory);
        rowUptimes.put(inst.pid, uptime);
        return row;
    }

    // ==================== 交互 ====================

    private void toggleSelection(long pid, HBox row) {
        if (selected.contains(pid)) {
            selected.remove(pid);
        } else {
            selected.add(pid);
        }
        applySelectionToRow(pid, row);
        updateFooterState();
    }

    private void applySelectionToRows() {
        rowNodes.forEach(this::applySelectionToRow);
        updateFooterState();
    }

    private void applySelectionToRow(long pid, HBox row) {
        boolean sel = selected.contains(pid);
        CheckBox check = rowChecks.get(pid);
        if (check != null) check.setSelected(sel);
        if (sel) {
            if (!row.getStyleClass().contains("selected")) row.getStyleClass().add("selected");
        } else {
            row.getStyleClass().remove("selected");
        }
    }

    private void updateFooterState() {
        selected.removeIf(pid -> find(shown, pid) == null);
        if (selectedCountValue != null) {
            selectedCountValue.setText(String.valueOf(selected.size()));
        }
        if (checkAllBox != null && !shown.isEmpty()) {
            checkAllBox.setIndeterminate(!selected.isEmpty() && selected.size() < shown.size());
            checkAllBox.setSelected(selected.size() == shown.size());
        }
        if (closeSelectedBtn != null) {
            boolean empty = selected.isEmpty();
            closeSelectedBtn.setDisable(empty);
            closeSelectedBtn.setText(empty
                    ? "关闭选中进程"
                    : "关闭选中进程 (" + selected.size() + ")");
        }
    }

    /** 展开/收起「其他游戏进程」：动画改 prefHeight（JavaFX 没有 max-height），箭头同步旋转 */
    private void toggleCollapse() {
        if (collapseBody == null) return;
        collapseExpanded = !collapseExpanded;
        if (collapseBox != null) {
            if (collapseExpanded) {
                if (!collapseBox.getStyleClass().contains("expanded")) {
                    collapseBox.getStyleClass().add("expanded");
                }
            } else {
                collapseBox.getStyleClass().remove("expanded");
            }
        }
        if (collapseHint != null) collapseHint.setOpacity(collapseExpanded ? 0 : 1);
        if (collapseArrow != null) {
            RotateTransition rt = new RotateTransition(Duration.millis(200), collapseArrow);
            rt.setToAngle(collapseExpanded ? 90 : 0);
            rt.play();
        }

        double to;
        if (collapseExpanded) {
            collapseBody.setVisible(true);
            collapseBody.setManaged(true);
            collapseBody.setPrefHeight(0);
            to = collapseBody.prefHeight(CONTENT_WIDTH);
        } else {
            to = 0;
        }
        Timeline anim = new Timeline(new KeyFrame(Duration.millis(220),
                new KeyValue(collapseBody.prefHeightProperty(), to)));
        if (!collapseExpanded) {
            anim.setOnFinished(e -> {
                collapseBody.setVisible(false);
                collapseBody.setManaged(false);
                collapseBody.setPrefHeight(Region.USE_COMPUTED_SIZE);
            });
        }
        anim.play();
    }

    /** 行尾 ✕：只看这一个进程 */
    private void openSingle(long pid) {
        singlePid = pid;
        fromMulti = true;
        render();
    }

    /** 单进程视图「取消」：从列表进来的回到列表，否则关掉弹窗 */
    private void onSingleCancel() {
        singlePid = null;
        if (fromMulti && !shown.isEmpty()) {
            render();
        } else {
            doClose();
        }
    }

    /** 单进程视图「关闭进程」 */
    private void closeSingle(Instance inst) {
        GameInstanceRegistry.kill(List.of(inst));
        toast.accept("已关闭：" + inst.displayName());
        singlePid = null;
        // 等一拍再刷新：刚 signal 完进程不会立刻消失，马上查还会算作存活
        PauseTransition wait = new PauseTransition(Duration.millis(500));
        wait.setOnFinished(e -> {
            if (fromMulti) {
                render();
            } else {
                doClose();
            }
        });
        wait.play();
    }

    /** 批量关闭勾选中的进程：关完即收起弹窗（不再留在列表上） */
    private void closeSelected() {
        List<Instance> targets = new ArrayList<>();
        for (Instance inst : shown) {
            if (selected.contains(inst.pid)) targets.add(inst);
        }
        if (targets.isEmpty()) return;

        int count = targets.size();
        boolean all = count == shown.size();
        GameInstanceRegistry.kill(targets);
        selected.clear();

        doClose();
        toast.accept(all ? "已关闭全部 " + count + " 个游戏进程" : "已关闭 " + count + " 个游戏进程");
    }

    private void doClose() {
        stopRefresh();
        closeHandler.run();
    }

    // ==================== 定时刷新 ====================

    private void startRefresh() {
        refreshTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> tick()));
        refreshTimer.setCycleCount(Animation.INDEFINITE);
        refreshTimer.play();
    }

    private void stopRefresh() {
        if (refreshTimer != null) {
            refreshTimer.stop();
            refreshTimer = null;
        }
    }

    private void tick() {
        // 弹窗被别的弹窗顶掉（或已关闭）时自行停表，避免定时器泄漏
        if (overlayRef != null && ui.currentModalOverlay() != overlayRef) {
            stopRefresh();
            return;
        }
        tickCount++;

        if (singlePid != null) {
            if (!GameInstanceRegistry.isAlive(singlePid)) {
                singlePid = null;
                render();
                return;
            }
            updateSingleStats();
            return;
        }

        refreshRowStats(tickCount % 2 == 0);
        if (tickCount % 2 == 0 && listChanged()) render();
    }

    private void updateSingleStats() {
        if (singlePid == null) return;
        Instance inst = find(shown, singlePid);
        if (singleMemoryLabel != null) {
            singleMemoryLabel.setText(memoryText(singlePid));
        }
        if (singleUptimeLabel != null && inst != null) {
            singleUptimeLabel.setText(FormatUtils.formatDuration(inst.uptimeMillis()));
        }
    }

    private void refreshRowStats(boolean withMemory) {
        for (Instance inst : shown) {
            Label uptime = rowUptimes.get(inst.pid);
            if (uptime != null) uptime.setText(FormatUtils.formatDuration(inst.uptimeMillis()));
            if (withMemory) {
                Label memory = rowMemories.get(inst.pid);
                if (memory != null) memory.setText(memoryText(inst.pid));
            }
        }
    }

    /** 列表里是否有实例退出/新增（退出靠存活复查，不需要等启动回调） */
    private boolean listChanged() {
        List<Instance> alive = GameInstanceRegistry.aliveInstances();
        if (alive.size() != shown.size()) return true;
        for (int i = 0; i < alive.size(); i++) {
            if (alive.get(i).pid != shown.get(i).pid) return true;
        }
        return false;
    }

    // ==================== 小工具 ====================

    /** 进程工作集；取不到（无 OSHI / native 构建）显示「—」 */
    private static String memoryText(long pid) {
        long bytes = ProcessMemory.workingSetBytes(pid);
        return bytes > 0 ? FormatUtils.formatFileSize(bytes) : "—";
    }

    private Node iconBlock(String iconName, String variant) {
        StackPane box = new StackPane(AppIcons.icon(iconName, 26, variantColor(variant)));
        box.getStyleClass().addAll("modal-icon", variant);
        VBox.setMargin(box, new Insets(0, 0, 16, 0));
        return box;
    }

    private static Color variantColor(String variant) {
        return "danger".equals(variant) ? Color.web("#ef4444") : Color.web("#3b82f6");
    }

    private static Label titleLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("modal-card-title");
        label.setWrapText(true);
        VBox.setMargin(label, new Insets(0, 0, 8, 0));
        return label;
    }

    private static Label descLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("modal-desc");
        return label;
    }

    /** 多进程弹窗的描述：「检测到 N 个正在运行的游戏进程 / 最近启动的进程已默认选中」（数字加粗） */
    private Node countDesc(int count) {
        HBox line = new HBox(4);
        line.setAlignment(Pos.CENTER);
        Label before = descLabel("检测到");
        Label number = new Label(String.valueOf(count));
        number.getStyleClass().add("modal-desc-strong");
        Label after = descLabel("个正在运行的游戏进程");
        line.getChildren().addAll(before, number, after);
        VBox box = new VBox(2, line, descLabel("最近启动的进程已默认选中"));
        box.setAlignment(Pos.CENTER);
        return box;
    }

    /** 单进程弹窗的描述：第二行红字警告（JavaFX 一个 Label 只能一个颜色，所以拆两行） */
    private Node singleDesc() {
        VBox box = new VBox(2, descLabel("确定要结束该游戏进程吗？"),
                descLabel("未保存的游戏进度将会丢失"));
        box.setAlignment(Pos.CENTER);
        ((Label) box.getChildren().get(1)).getStyleClass().add("danger-text");
        return box;
    }

    private static Label valueLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("modal-list-value");
        return label;
    }

    private static HBox infoRow(String key, Label value) {
        Label keyLabel = new Label(key);
        keyLabel.getStyleClass().add("modal-list-key");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(keyLabel, spacer, value);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** 等宽按钮行（示例的 .modal-actions：两个按钮各占一半） */
    private static HBox actionRow(Button left, Button right) {
        HBox box = new HBox(10);
        box.setMaxWidth(Double.MAX_VALUE);
        VBox.setMargin(box, new Insets(16, 0, 0, 0));
        if (left != null) {
            left.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(left, Priority.ALWAYS);
            box.getChildren().add(left);
        }
        if (right != null) {
            right.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(right, Priority.ALWAYS);
            box.getChildren().add(right);
        }
        return box;
    }

    /** Esc 与点遮罩关闭（UiService 的通用弹窗没有这两个能力，这里按设计稿补上） */
    private void bindOverlayDismiss() {
        StackPane overlay = overlayRef;
        if (overlay == null) return;
        overlay.setOnMouseClicked(e -> {
            if (e.getTarget() == overlay) doClose();
        });
        overlay.setFocusTraversable(true);
        overlay.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) doClose();
        });
        overlay.requestFocus();
    }
}
