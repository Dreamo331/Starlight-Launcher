package com.example.starlight.newui.download;

import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.ui.PageKit;
import com.example.starlight.util.FormatUtils;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * 单个下载任务的卡片（下载管理页里的一项）。
 *
 * <p>对应 VersePc2 安装弹窗里的内容：阶段文案 + 百分比 + 进度条 + 明细消息 + 速度 +
 * 下载文件列表 + 取消。版本安装、模组下载、整合包安装、依赖批量下载各自生成一张卡片，
 * 统一登记到 下载管理页，由下载管理页集中展示；任务在后台跑，切走再切回不丢进度。
 *
 * <p>取消标志是<b>每任务一份</b>的：多个任务并行时点某一个的「取消下载」只中断它自己。
 *
 * <p>线程约定：所有更新方法内部都用 {@link Platform#runLater} 派发，后台线程可直接调用。
 */
public final class DownloadTaskCard {

    /** 任务卡片根节点（下载管理页里的一项） */
    final VBox card;
    private final String title;
    private final Label stageLabel = new Label("准备中...");
    private final Label percentLabel = new Label("0%");
    private final ProgressBar bar = new ProgressBar(0);
    private final Label messageLabel = new Label();
    private final Label speedLabel = new Label();
    private final VBox fileList = new VBox(2);
    private final Button cancelBtn = AppIcons.button("close", "取消下载");
    private final HBox footer = new HBox(10);
    private final long startedAt = System.nanoTime();

    /** 该任务的取消标志，供下载循环轮询 */
    private final java.util.concurrent.atomic.AtomicBoolean cancelledFlag =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 任务状态：RUNNING 时右下角悬浮按钮才显示 */
    private volatile DownloadTaskStatus status = DownloadTaskStatus.RUNNING;

    /** 已下载总量（字节）；为 -1 表示未知，此时不显示速度只显示已用时 */
    private long totalBytes = -1;
    private long lastSampleTime;
    private int lastSamplePct = -1;
    private Label currentFileRow;
    private volatile boolean finished;
    /** 失败后的「重新下载」动作；由发起安装的调用方通过 {@link #setRetryAction} 提供 */
    private Runnable retryAction;

    private final DownloadTaskManager manager;
    /** 启动器上下文：内嵌文件列表挂接惯性滚动用（可为 null，null 时保持原生滚动） */
    private final LauncherContext ctx;

        public DownloadTaskCard(String title, DownloadTaskManager manager) {
            this.manager = manager;
        this.ctx = manager != null ? manager.context() : null;
        this.title = title;

        card = new VBox(8);
        card.getStyleClass().add("settings-card");
        // settings-card 默认垂直居中且带 hover 位移，任务卡片需要覆盖成顶部对齐、无位移
        card.setStyle("-fx-alignment: top-left; -fx-padding: 14 16; -fx-translate-y: 0;");

        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: 600;");
        titleLabel.setWrapText(true);
        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        Label statusChip = new Label("进行中");
        statusChip.setStyle("-fx-font-size: 10px; -fx-padding: 1 7; -fx-background-radius: 4;"
                + " -fx-background-color: rgba(59,130,246,0.16); -fx-text-fill: #3b82f6;");
        statusLabel = statusChip;
        HBox titleRow = new HBox(8, titleLabel, titleSpacer, statusChip);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        stageLabel.setStyle("-fx-font-weight: 600; -fx-font-size: 12px;");
        percentLabel.setStyle("-fx-font-weight: 600; -fx-font-size: 12px; -fx-text-fill: #3b82f6;");
        Region infoSpacer = new Region();
        HBox.setHgrow(infoSpacer, Priority.ALWAYS);
        HBox infoRow = new HBox(8, stageLabel, infoSpacer, percentLabel);
        infoRow.setAlignment(Pos.CENTER_LEFT);

        bar.setPrefWidth(Double.MAX_VALUE);
        bar.setPrefHeight(8);
        bar.getStyleClass().add("memory-bar");   // 标准渐变款进度条（扫光动画见 ProgressBars）
        bar.setProgress(0);

        messageLabel.setWrapText(true);
        messageLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-dim;");
        speedLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -sl-text-dim;");

        fileList.setStyle("-fx-padding: 2;");
        ScrollPane fileScroll = new ScrollPane(fileList);
        fileScroll.setFitToWidth(true);
        fileScroll.setPrefHeight(90);
        fileScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        fileScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        fileScroll.setStyle("-fx-background-color: transparent;"
                + " -fx-border-color: rgba(127,127,127,0.25); -fx-border-radius: 6;");
        // 内嵌文件列表补挂惯性滚动（阻尼 + fling）；固定尺寸保持不动
        if (ctx != null) PageKit.attachInertia(ctx, fileScroll);

        cancelBtn.getStyleClass().add("modal-btn-cancel");
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.getChildren().add(cancelBtn);
        cancelBtn.setOnAction(e -> {
            cancelledFlag.set(true);
            stage("正在取消...");
            message("等待当前下载片段结束");
        });

        card.getChildren().addAll(titleRow, infoRow, bar, messageLabel, speedLabel,
                fileScroll, footer);
    }

    /** 顶部状态徽标 */
    private final Label statusLabel;

    /** 该任务的取消标志（下载循环轮询用） */
    public java.util.concurrent.atomic.AtomicBoolean cancelFlag() {
        return cancelledFlag;
    }

    /** 登记失败后「重新下载」要执行的动作（不设置则失败时只显示「删除任务」） */
    public void setRetryAction(Runnable action) {
        this.retryAction = action;
    }

    DownloadTaskStatus status() {
        return status;
    }

    private void setStatus(DownloadTaskStatus next, String chipText, String chipStyle) {
        status = next;
        Label chip = statusLabel;
        chip.setText(chipText);
        chip.setStyle("-fx-font-size: 10px; -fx-padding: 1 7; -fx-background-radius: 4;" + chipStyle);
        manager.updateFab();
    }

    /** 设置阶段文案（对应 VersePc2 弹窗里的 {@code install-stage}） */
    public void stage(String text) {
        Platform.runLater(() -> stageLabel.setText(text == null ? "" : text));
    }

    /** 设置明细消息（对应 {@code install-message}） */
    public void message(String text) {
        Platform.runLater(() -> messageLabel.setText(text == null ? "" : text));
    }

    /**
     * 指定本次下载的总字节数，用于把百分比换算成速度。
     * <p>未知时可传 -1，此时只显示已用时。
     */
    public void totalBytes(long bytes) {
        this.totalBytes = bytes;
        this.lastSampleTime = System.nanoTime();
    }

    /** 更新百分比进度（0-100） */
    public void progress(int pct) {
        int clamped = Math.max(0, Math.min(100, pct));
        long now = System.nanoTime();
        String speed = null;
        if (totalBytes > 0 && lastSamplePct >= 0 && now > lastSampleTime) {
            long deltaBytes = (long) ((clamped - lastSamplePct) / 100.0 * totalBytes);
            double seconds = (now - lastSampleTime) / 1_000_000_000.0;
            if (deltaBytes >= 0 && seconds > 0.2) {
                speed = FormatUtils.formatSpeed(deltaBytes / seconds);
            }
        }
        if (speed != null || lastSamplePct < 0) {
            lastSamplePct = clamped;
            lastSampleTime = now;
        }
        final String speedText = speed;
        Platform.runLater(() -> {
            bar.setProgress(clamped / 100.0);
            percentLabel.setText(clamped + "%");
            if (speedText != null) {
                speedLabel.setText("速度 " + speedText);
            } else if (totalBytes <= 0) {
                speedLabel.setText("已用时 " + elapsedText());
            }
        });
    }

    /** 追加一个「正在下载」的文件行，返回后需用 {@link #finishFile} 收尾 */
    public void beginFile(String fileName) {
        Platform.runLater(() -> {
            currentFileRow = new Label(fileName);
            AppIcons.apply(currentFileRow, "hourglass", 11, null);
            currentFileRow.setStyle("-fx-font-size: 11px; -fx-text-fill: -sl-text-dim;");
            fileList.getChildren().add(currentFileRow);
            // 列表过长时裁掉最早的行，避免长时间安装后占满内存
            if (fileList.getChildren().size() > 200) {
                fileList.getChildren().remove(0, 50);
            }
        });
    }

    /** 结束当前文件行，{@code ok=false} 时标红 */
    public void finishFile(boolean ok) {
        Platform.runLater(() -> {
            if (currentFileRow == null) return;
            AppIcons.apply(currentFileRow, ok ? "check" : "close", 11, null);
            if (!ok) currentFileRow.setStyle("-fx-font-size: 11px; -fx-text-fill: #ef4444;");
            currentFileRow = null;
        });
    }

    /** 直接追加一条带状态的日志行（不需要配对结束的场合） */
    public void logLine(String text, boolean ok) {
        Platform.runLater(() -> {
            Label row = new Label((ok ? "" : "• ") + text);
            row.setStyle("-fx-font-size: 11px; -fx-text-fill: -sl-text-dim;");
            fileList.getChildren().add(row);
        });
    }

    /** 用户是否点击了取消 */
    public boolean isCancelled() {
        return cancelledFlag.get();
    }

    /** 点击「取消」后的确认文案，由调用方在收尾时使用 */
    public boolean wasCancelled() {
        return cancelledFlag.get();
    }

    /**
     * 收尾：把进度推到终态，状态徽标改为结果，并给出后续操作。
     *
     * <p>成功后卡片会自动消失（不需要用户手动清理）；失败 / 取消时提供
     * 「重新下载」（若调用方提供了重试动作）与「删除任务」。
     *
     * @param success 是否成功
     * @param summary 结果文案
     * @param onClose 点击「返回」后的回调（可为 null）
     */
    public void finish(boolean success, String summary, Runnable onClose) {
        if (finished) return;
        finished = true;
        boolean userCancelled = cancelledFlag.get();
        Platform.runLater(() -> {
            bar.setProgress(success ? 1.0 : bar.getProgress());
            percentLabel.setText(success ? "100%" : "已停止");
            stageLabel.setText(success ? "完成" : (userCancelled ? "已取消" : "已停止"));
            messageLabel.setText(summary == null ? "" : summary);
            speedLabel.setText("总用时 " + elapsedText());
            cancelBtn.setVisible(false);
            cancelBtn.setManaged(false);
            if (success) {
                setStatus(DownloadTaskStatus.SUCCESS, "已完成",
                        " -fx-background-color: rgba(76,175,80,0.16); -fx-text-fill: #4caf50;");
            } else if (userCancelled) {
                setStatus(DownloadTaskStatus.CANCELLED, "已取消",
                        " -fx-background-color: rgba(148,163,184,0.2); -fx-text-fill: #94a3b8;");
            } else {
                setStatus(DownloadTaskStatus.FAILED, "失败",
                        " -fx-background-color: rgba(239,83,80,0.16); -fx-text-fill: #ef5350;");
            }

            Button doneBtn = AppIcons.button("back", "返回");
            doneBtn.getStyleClass().add("modal-btn-ok");
            doneBtn.setMinWidth(Region.USE_PREF_SIZE);
            doneBtn.setOnAction(e -> {
                manager.goBack();
                if (onClose != null) onClose.run();
            });
            if (!footer.getChildren().contains(doneBtn)) {
                footer.getChildren().add(doneBtn);
            }

            if (success) {
                // 成功的任务不需要用户清理：留 2.5 秒让用户看到「完成」，然后自动移除
                javafx.animation.PauseTransition delay =
                        new javafx.animation.PauseTransition(Duration.millis(2500));
                delay.setOnFinished(e -> manager.remove(this));
                delay.play();
            } else {
                // 失败 / 取消：给「重新下载」和「删除任务」
                if (retryAction != null) {
                    Button retryBtn = AppIcons.button("refresh", "重新下载");
                    retryBtn.getStyleClass().add("modal-btn-ok");
                    retryBtn.setMinWidth(Region.USE_PREF_SIZE);
                    retryBtn.setOnAction(e -> {
                        manager.remove(this);
                        retryAction.run();
                    });
                    footer.getChildren().add(0, retryBtn);
                }
                Button deleteBtn = AppIcons.button("trash", "删除任务");
                deleteBtn.getStyleClass().add("modal-btn-cancel");
                deleteBtn.setMinWidth(Region.USE_PREF_SIZE);
                deleteBtn.setOnAction(e -> manager.remove(this));
                footer.getChildren().add(0, deleteBtn);
            }
        });
    }

    private String elapsedText() {
        long seconds = (System.nanoTime() - startedAt) / 1_000_000_000L;
        return seconds >= 60 ? (seconds / 60) + " 分 " + (seconds % 60) + " 秒" : seconds + " 秒";
    }
}
// ==== BODY ====

