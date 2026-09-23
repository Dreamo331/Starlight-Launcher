package com.example.starlight.newui.download;

import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.ui.FabMenu;
import com.example.starlight.newui.ui.PageKit;
import com.example.starlight.newui.ui.UiService;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * 下载任务管理：任务登记、下载管理页、右下角悬浮按钮。
 *
 * <p>从 LauncherView 抽离（方法体逐字搬运）：任务列表（最新在前）、来源页记录、
 * 悬浮按钮显隐都集中在这里；页面导航与「详情页上下文是否仍在」通过 {@link Host} 回调，
 * 避免与 LauncherView 形成循环依赖。样式类与内联样式与原实现完全一致。
 */
public final class DownloadTaskManager {

    /** 宿主回调：页面状态与导航（由 LauncherView 实现） */
    public interface Host {
        /** 当前页面 key（尚未切页时为 null） */
        String currentPageKey();

        /** 切换页面（downloadManager / download / downloadDetail ...） */
        void switchToPage(String key);

        /** 资源详情页上下文是否仍在（已释放时「返回」回退到下载列表） */
        boolean hasDetailContext();

        /** 选版本页上下文是否仍在（已释放时「返回」回退到下载列表） */
        boolean hasVersionPickContext();
    }

    private final StackPane rootPane;
    private final UiService ui;
    private final Host host;
    /** 启动器上下文：下载管理页滚动区挂接惯性滚动用（可为 null，null 时保持原生滚动） */
    private final LauncherContext ctx;
    /** 任务列表（最新的排在最前） */
    private final List<DownloadTaskCard> tasks = new ArrayList<>();
    /** 右下角统一 FAB（FabMenu）里的「下载管理」子按钮位置（上扇形 0=左上 1=正上 2=右上 中的左上位） */
    public static final int FAB_SLOT = 0;
    /** 统一 FAB（buildFab 时由 LauncherView 注入，可为 null——未注入时 updateFab 只做计算） */
    private FabMenu fabMenu;
    /** 下载管理页「返回」按钮的目标页面 */
    private String backKey = "download";

    public DownloadTaskManager(StackPane rootPane, UiService ui, Host host) {
        this(rootPane, ui, host, null);
    }

    public DownloadTaskManager(StackPane rootPane, UiService ui, Host host, LauncherContext ctx) {
        this.rootPane = rootPane;
        this.ui = ui;
        this.host = host;
        this.ctx = ctx;
    }

    /** 启动器上下文（DownloadTaskCard 内嵌列表挂惯性用；测试构造时可能为 null） */
    public LauncherContext context() {
        return ctx;
    }

    /**
     * 新建一个下载任务并跳到下载管理页。
     *
     * <p>任务会登记进 {@link #tasks}（最新在前），右下角「下载管理」悬浮按钮随之出现；
     * 任务结束后按钮在离开下载管理页时自动隐藏。
     */
    public DownloadTaskCard open(String title) {
        // 下载管理是主窗口整页：先把可能开着的弹窗关掉，避免遮罩浮在新页面上
        ui.closeModal();
        DownloadTaskCard task = new DownloadTaskCard(title, this);
        tasks.add(0, task);
        // 记下来源页：点「返回」能回到原来的位置，而不是固定回列表
        backKey = (host.currentPageKey() == null || "downloadManager".equals(host.currentPageKey()))
                ? "download" : host.currentPageKey();
        host.switchToPage("downloadManager");
        updateFab();
        return task;
    }

    /** 下载管理页「返回」：只切换页面，后台任务继续跑 */
    public void goBack() {
        String target = backKey;
        if ("downloadDetail".equals(target) && !host.hasDetailContext()) target = "download";
        if ("downloadVersionPick".equals(target) && !host.hasVersionPickContext()) target = "download";
        host.switchToPage(target == null ? "download" : target);
        updateFab();
    }

    /**
     * 移除一个下载任务（成功后自动清理、或用户点「删除任务」），并刷新下载管理页。
     * <p>正在下载的任务不会被移除，避免把还在跑的任务从列表里弄丢。
     */
    public void remove(DownloadTaskCard task) {
        if (task == null) return;
        if (task.status() == DownloadTaskStatus.RUNNING) return;
        // 从页面上摘掉卡片，避免它还被某个父容器持有
        if (task.card.getParent() instanceof Pane p) {
            p.getChildren().remove(task.card);
        }
        tasks.remove(task);
        // 当前正停在下载管理页时立刻重绘，否则只更新悬浮按钮
        if ("downloadManager".equals(host.currentPageKey())) {
            host.switchToPage("downloadManager");
        }
        updateFab();
    }

    /**
     * 下载管理页（对应 PCL2 / VersePc2 的下载任务列表）。
     * <p>列出全部下载任务卡片，支持逐个取消，以及一键清空已完成任务。
     */
    public Node buildManagerPage() {
        VBox root = new VBox(12);
        root.setPadding(new Insets(8, 8, 8, 0));
        VBox.setVgrow(root, Priority.ALWAYS);

        Button backBtn = AppIcons.button("back", "返回");
        backBtn.getStyleClass().add("back-btn");
        backBtn.setOnAction(e -> goBack());
        Label titleLabel = new Label("下载管理");
        titleLabel.getStyleClass().add("home-card-title");
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        long running = tasks.stream().filter(t -> t.status() == DownloadTaskStatus.RUNNING).count();
        Label summary = new Label(tasks.isEmpty() ? "暂无下载任务"
                : ("共 " + tasks.size() + " 个任务" + (running > 0 ? "，进行中 " + running + " 个" : "")));
        summary.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-dim;");
        Button clearBtn = AppIcons.button("trash", "清空已完成");
        clearBtn.getStyleClass().add("back-btn");
        clearBtn.setDisable(tasks.stream().noneMatch(t -> t.status() != DownloadTaskStatus.RUNNING));
        clearBtn.setOnAction(e -> {
            tasks.removeIf(t -> t.status() != DownloadTaskStatus.RUNNING);
            host.switchToPage("downloadManager");
            updateFab();
        });
        HBox header = new HBox(10, backBtn, titleLabel, headerSpacer, summary, clearBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(header);

        VBox list = new VBox(10);
        if (tasks.isEmpty()) {
            Label empty = new Label("还没有下载任务。在下载中心安装模组或游戏版本后，任务会出现在这里。");
            empty.setWrapText(true);
            empty.setStyle("-fx-text-fill: -sl-text-dim; -fx-padding: 20;");
            list.getChildren().add(empty);
        } else {
            for (DownloadTaskCard task : tasks) {
                // 任务卡片节点在多次进入本页时复用；先从其旧父节点摘掉，避免重复添加
                if (task.card.getParent() instanceof Pane oldParent) {
                    oldParent.getChildren().remove(task.card);
                }
                list.getChildren().add(task.card);
            }
        }
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setStyle("-fx-background-color: transparent;");
        // 任务列表补挂惯性滚动（阻尼 + fling）；固定尺寸与布局保持不动
        if (ctx != null) PageKit.attachInertia(ctx, scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        root.getChildren().add(scroll);
        return root;
    }

    // ==================== 右下角「下载管理」悬浮入口（统一 FAB 的子按钮） ====================

    /**
     * 把「下载管理」挂到统一 FAB（{@link FabMenu}）的左子按钮位。
     * <p>FAB 本体由 LauncherView 建好传入；显隐与徽标由 {@link #updateFab()} 驱动，
     * 没有「关闭游戏进程」按钮挤占角落的让位问题（两者同住一个 FAB）。
     */
    public void buildFab(FabMenu fabMenu) {
        this.fabMenu = fabMenu;
        fabMenu.setItem(FAB_SLOT, "download", "下载管理", () -> {
            backKey = (host.currentPageKey() == null || "downloadManager".equals(host.currentPageKey()))
                    ? "download" : host.currentPageKey();
            host.switchToPage("downloadManager");
            updateFab();
        });
        updateFab();
    }

    /**
     * 刷新「下载管理」子按钮的可用性与徽标。
     * <p>可用条件：有进行中的任务；或当前就停在下载管理页（这样任务刚结束时还能回到列表看结果）。
     */
    public void updateFab() {
        long running = tasks.stream().filter(t -> t.status() == DownloadTaskStatus.RUNNING).count();
        boolean onManager = "downloadManager".equals(host.currentPageKey());
        boolean show = running > 0 || (onManager && !tasks.isEmpty());
        if (fabMenu != null) {
            fabMenu.setItemEnabled(FAB_SLOT, show);
            fabMenu.setBadge(running > 0 ? String.valueOf(running) : null);
        }
    }
}


