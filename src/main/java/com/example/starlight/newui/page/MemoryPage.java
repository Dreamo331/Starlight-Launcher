package com.example.starlight.newui.page;

import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.ModsApi.RemoteMod;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.PageKit;
import com.example.starlight.util.FormatUtils;
import com.example.starlight.util.ResourceScanner;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.File;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.example.starlight.gui.UIGeneralControlClass;
import com.example.starlight.util.MemoryOptimizer;
import com.example.starlight.util.SystemInfoMonitor;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;
import javafx.scene.control.ProgressBar;

/** 
内存管理页与「内存优化」卡片
（从 LauncherView 抽离，方法体逐字搬运，样式未改动） */
public final class MemoryPage {

    private final LauncherContext host;


    public MemoryPage(LauncherContext host) {
        this.host = host;
    }

    // ===== 内存管理页面 =====

    public Node build() {
        VBox root = PageKit.settingsPage(host, "内存管理", "home");

        // ===== 卡片1：JVM 堆内存（实时刷新） =====
        VBox heapCard = new VBox(10);
        heapCard.getStyleClass().add("settings-card");
        Label heapTitle = AppIcons.label("memory", "JVM 堆内存");
        heapTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: 600; -fx-text-fill: -sl-text;");
        Label heapPctLbl = new Label("--%");
        heapPctLbl.setStyle("-fx-font-size: 26px; -fx-font-weight: 700; -fx-text-fill: #22c55e;");
        Region heapFill = new Region();
        heapFill.setStyle("-fx-background-color: #22c55e; -fx-background-radius: 5;" +
                " -fx-min-height: 10; -fx-max-height: 10;");
        heapFill.setPrefWidth(0);
        HBox heapTrack = new HBox(heapFill);
        heapTrack.setStyle("-fx-background-color: -sl-track; -fx-background-radius: 5;" +
                " -fx-min-height: 10; -fx-max-height: 10;");
        heapTrack.setMinWidth(280);
        heapTrack.setPrefWidth(280);
        heapTrack.setMaxWidth(280);
        HBox heapUsageRow = new HBox(16);
        heapUsageRow.setAlignment(Pos.CENTER_LEFT);
        heapUsageRow.getChildren().addAll(heapPctLbl, heapTrack);
        Label maxLbl = new Label("--");
        Label committedLbl = new Label("--");
        Label usedLbl = new Label("--");
        Label freeLbl = new Label("--");
        HBox heapStats = new HBox(20);
        heapStats.setAlignment(Pos.CENTER);
        heapStats.getChildren().addAll(
                createMemStatBox("最大", maxLbl),
                createMemStatBox("已分配", committedLbl),
                createMemStatBox("已使用", usedLbl),
                createMemStatBox("空闲", freeLbl)
        );
        Label heapHint = new Label("启动器自身 JVM 堆内存（游戏启动后占用会明显上升）");
        heapHint.setStyle("-fx-font-size: 11px; -fx-text-fill: -sl-text-faint;");
        heapCard.getChildren().addAll(heapTitle, heapUsageRow, heapStats, heapHint);
        root.getChildren().add(heapCard);

        // ===== 卡片2：系统物理内存 =====
        VBox sysCard = new VBox(10);
        sysCard.getStyleClass().add("settings-card");
        Label sysTitle = new Label("系统物理内存");
        sysTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: 600; -fx-text-fill: -sl-text;");
        Label sysPctLbl = new Label("--%");
        sysPctLbl.setStyle("-fx-font-size: 26px; -fx-font-weight: 700; -fx-text-fill: #22c55e;");
        Region sysFill = new Region();
        sysFill.setStyle("-fx-background-color: #22c55e; -fx-background-radius: 5;" +
                " -fx-min-height: 10; -fx-max-height: 10;");
        sysFill.setPrefWidth(0);
        HBox sysTrack = new HBox(sysFill);
        sysTrack.setStyle("-fx-background-color: -sl-track; -fx-background-radius: 5;" +
                " -fx-min-height: 10; -fx-max-height: 10;");
        sysTrack.setMinWidth(280);
        sysTrack.setPrefWidth(280);
        sysTrack.setMaxWidth(280);
        HBox sysUsageRow = new HBox(16);
        sysUsageRow.setAlignment(Pos.CENTER_LEFT);
        sysUsageRow.getChildren().addAll(sysPctLbl, sysTrack);
        Label sysHint = new Label("系统物理内存总占用（含其他程序），与任务管理器口径一致");
        sysHint.setStyle("-fx-font-size: 11px; -fx-text-fill: -sl-text-faint;");
        sysCard.getChildren().addAll(sysTitle, sysUsageRow, sysHint);
        root.getChildren().add(sysCard);

        // ===== 卡片3：非堆内存 =====
        VBox nonHeapCard = new VBox(10);
        nonHeapCard.getStyleClass().add("settings-card");
        Label nonHeapTitle = AppIcons.label("package", "非堆内存");
        nonHeapTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: 600; -fx-text-fill: -sl-text;");
        Label nonHeapUsedLbl = new Label("--");
        Label nonHeapCommittedLbl = new Label("--");
        HBox nonHeapStats = new HBox(20);
        nonHeapStats.setAlignment(Pos.CENTER);
        nonHeapStats.getChildren().addAll(
                createMemStatBox("已使用", nonHeapUsedLbl),
                createMemStatBox("已提交", nonHeapCommittedLbl)
        );
        Label nonHeapHint = new Label("Metaspace、代码缓存等非堆区域，一般保持稳定");
        nonHeapHint.setStyle("-fx-font-size: 11px; -fx-text-fill: -sl-text-faint;");
        nonHeapCard.getChildren().addAll(nonHeapTitle, nonHeapStats, nonHeapHint);
        root.getChildren().add(nonHeapCard);

        // ===== 卡片4：GC 与线程 =====
        VBox gcCard = new VBox(10);
        gcCard.getStyleClass().add("settings-card");
        Label gcTitle = AppIcons.label("refresh", "GC 与线程");
        gcTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: 600; -fx-text-fill: -sl-text;");
        Label gcCountLbl = new Label("--");
        Label gcTimeLbl = new Label("--");
        Label threadLbl = new Label("--");
        HBox gcStats = new HBox(20);
        gcStats.setAlignment(Pos.CENTER);
        gcStats.getChildren().addAll(
                createMemStatBox("GC 次数", gcCountLbl),
                createMemStatBox("GC 累计耗时", gcTimeLbl),
                createMemStatBox("线程数(当前/峰值)", threadLbl)
        );
        gcCard.getChildren().addAll(gcTitle, gcStats);
        root.getChildren().add(gcCard);

        // ===== 操作按钮 =====
        HBox btnRow = new HBox(10);
        btnRow.setAlignment(Pos.CENTER);
        Button jvmBtn = AppIcons.button("cpu", "JVM设置");
        jvmBtn.getStyleClass().add("btn-primary");
        jvmBtn.setStyle("-fx-min-width: 140;");
        jvmBtn.setOnAction(e -> host.switchToSettings("sidebarJvm"));
        btnRow.getChildren().add(jvmBtn);
        root.getChildren().add(btnRow);

        // 内存优化：调用 Windows EmptyWorkingSet 把各进程工作集交还系统。
        // 与「高级设置 → 内存优化」是同一个实现（MemoryOptimizer），两处入口行为一致。
        Button memOptBtn = AppIcons.button("bolt", "内存优化");
        memOptBtn.getStyleClass().add("btn-primary");
        memOptBtn.setStyle("-fx-min-width: 140;");
        memOptBtn.setOnAction(e -> runMemoryOptimize(memOptBtn, () -> {
            // 优化会改变系统物理内存占用，立即重采样并刷新本页显示
            SystemInfoMonitor.sample();
        }));
        btnRow.getChildren().add(memOptBtn);
        Label lastUpdateLbl = new Label("最后刷新: --");
        lastUpdateLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: -sl-text-faint; -fx-alignment: center;");
        lastUpdateLbl.setMaxWidth(Double.MAX_VALUE);
        root.getChildren().add(lastUpdateLbl);

        // ===== 定时刷新：进入页面时启动，离开页面时停止（避免后台空转） =====
        final Runnable refresh = () -> {
            try {
                // JVM 堆内存
                MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
                long max = heap.getMax() > 0 ? heap.getMax() : 0;
                long committed = heap.getCommitted();
                long used = heap.getUsed();
                double pct = max > 0 ? (double) used / max * 100 : 0;
                heapPctLbl.setText(String.format("%.1f%%", pct));
                heapPctLbl.setStyle("-fx-font-size: 26px; -fx-font-weight: 700; -fx-text-fill: " + FormatUtils.usageColor(pct) + ";");
                heapFill.setPrefWidth(PageKit.clamp(pct, 0, 100) / 100.0 * 280);
                heapFill.setStyle("-fx-background-color: " + FormatUtils.usageColor(pct) + "; -fx-background-radius: 5;" +
                        " -fx-min-height: 10; -fx-max-height: 10;");
                maxLbl.setText(max > 0 ? max / 1024 / 1024 + " MB" : "--");
                committedLbl.setText(committed / 1024 / 1024 + " MB");
                usedLbl.setText(used / 1024 / 1024 + " MB");
                freeLbl.setText((committed - used) / 1024 / 1024 + " MB");

                // 系统物理内存（采样由 SystemInfoMonitor 负责，毫秒级同步、磁盘异步）
                SystemInfoMonitor.sample();
                double sysMem = SystemInfoMonitor.getMemPercent();
                double sysPct = sysMem >= 0 ? sysMem : 0;
                sysPctLbl.setText(sysMem >= 0 ? String.format("%.1f%%", sysMem) : "--%");
                sysPctLbl.setStyle("-fx-font-size: 26px; -fx-font-weight: 700; -fx-text-fill: " + FormatUtils.usageColor(sysPct) + ";");
                sysFill.setPrefWidth(PageKit.clamp(sysPct, 0, 100) / 100.0 * 280);
                sysFill.setStyle("-fx-background-color: " + FormatUtils.usageColor(sysPct) + "; -fx-background-radius: 5;" +
                        " -fx-min-height: 10; -fx-max-height: 10;");

                // 非堆内存
                MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
                nonHeapUsedLbl.setText(nonHeap.getUsed() / 1024 / 1024 + " MB");
                nonHeapCommittedLbl.setText(nonHeap.getCommitted() / 1024 / 1024 + " MB");

                // GC 统计（部分收集器可能返回 -1，需过滤）
                long gcCount = 0, gcTime = 0;
                for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                    long c = gc.getCollectionCount();
                    long t = gc.getCollectionTime();
                    if (c >= 0) gcCount += c;
                    if (t >= 0) gcTime += t;
                }
                gcCountLbl.setText(gcCount + " 次");
                gcTimeLbl.setText(String.format("%.1f s", gcTime / 1000.0));

                // 线程统计
                ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
                threadLbl.setText(threadBean.getThreadCount() + " / " + threadBean.getPeakThreadCount());

                lastUpdateLbl.setText("最后刷新: " + java.time.LocalTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
            } catch (Exception ignored) {}
        };
        Timeline timer = new Timeline(new KeyFrame(Duration.seconds(2), e -> refresh.run()));
        timer.setCycleCount(Animation.INDEFINITE);
        root.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                refresh.run();
                if (timer.getStatus() != Animation.Status.RUNNING) timer.play();
            } else {
                timer.stop();
            }
        });
        return root;
    }

    /**
     * 执行一次内存优化（「内存管理」与「高级设置 → 内存优化」两处共用）。
     *
     * <p>优化期间按钮置灰并显示进度文案；结束后回调 onDone 刷新页面显示，
     * 并以 Toast 汇报释放量与处理的进程数。非 Windows / 权限不足时如实提示，不假装成功。
     */
    private void runMemoryOptimize(Button trigger, Runnable onDone) {
        String originalText = trigger.getText();
        trigger.setDisable(true);
        trigger.setText("优化中...");
        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            long before = com.example.starlight.util.MemoryOptimizer.readStatus().usedBytes();
            int cleaned = com.example.starlight.util.MemoryOptimizer.optimize();
            long after = com.example.starlight.util.MemoryOptimizer.readStatus().usedBytes();
            Platform.runLater(() -> {
                trigger.setDisable(false);
                trigger.setText(originalText);
                if (onDone != null) onDone.run();
                long freed = Math.max(0, before - after);
                host.ui().toast(cleaned > 0
                        ? "已优化 " + cleaned + " 个进程，释放约 " + FormatUtils.formatFileSize(freed)
                        : "没有可优化的进程（仅 Windows 支持，且部分进程需要管理员权限）");
            });
        });
    }

    /** 内存统计项：标签 + 可实时更新的数值 Label */
    private VBox createMemStatBox(String label, Label valueLabel) {
        VBox item = new VBox(4);
        item.setAlignment(Pos.CENTER);
        item.setPrefWidth(110);
        Label lbl = new Label(label);
        lbl.setStyle("-fx-font-size: 11px; -fx-text-fill: -sl-text-faint;");
        valueLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: 700; -fx-text-fill: -sl-text;");
        item.getChildren().addAll(lbl, valueLabel);
        return item;
    }


    public Node buildOptimizeCard() {
        // 纵向排布：标题 / 说明在最上，控件整块放到卡片下方（横跨卡片宽度）
        VBox card = new VBox(10);
        card.getStyleClass().add("settings-card");
        card.setStyle("-fx-alignment: top-left; -fx-translate-y: 0; -fx-padding: 16 18;");

        Label title = new Label("内存优化");
        title.getStyleClass().add("settings-card-title");
        Label desc = new Label("将物理内存占用降低约 1/3，不仅限于 Minecraft。"
                + "如果使用机械硬盘，可能会导致一小段时间的卡顿。");
        desc.getStyleClass().add("settings-card-desc");
        desc.setWrapText(true);
        desc.setMaxWidth(Double.MAX_VALUE);
        card.getChildren().addAll(title, desc);

        // ---- 内存使用率 ----
        Label usageCaption = new Label("内存使用率");
        usageCaption.setStyle("-fx-font-size: 13px; -fx-text-fill: -sl-text;");

        ProgressBar bar = new ProgressBar(0);
        bar.getStyleClass().add("memory-bar");
        bar.setPrefWidth(560);
        bar.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(bar, Priority.ALWAYS);

        Label percentLabel = new Label("--%");
        percentLabel.setMinWidth(Region.USE_PREF_SIZE);
        percentLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: 600;");

        Button optimizeBtn = AppIcons.button("bolt", "内存优化");
        optimizeBtn.getStyleClass().add("btn-primary");
        optimizeBtn.setMinWidth(Region.USE_PREF_SIZE);

        HBox barRow = new HBox(12, bar, percentLabel, optimizeBtn);
        barRow.setAlignment(Pos.CENTER_LEFT);

        Label detailLabel = new Label("读取中...");
        detailLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-dim;");

        Runnable refresh = () -> {
            com.example.starlight.util.MemoryOptimizer.MemoryStatus st =
                    com.example.starlight.util.MemoryOptimizer.readStatus();
            if (st.totalBytes() <= 0) {
                percentLabel.setText("未知");
                detailLabel.setText("无法读取内存信息");
                return;
            }
            bar.setProgress(st.percent() / 100.0);
            percentLabel.setText(st.percent() + "%");
            detailLabel.setText(st.usedText() + " / " + st.totalText());
        };
        refresh.run();

        optimizeBtn.setOnAction(e -> runMemoryOptimize(optimizeBtn, refresh));

        card.getChildren().addAll(usageCaption, barRow, detailLabel);

        // ---- 启动前自动优化（开关 + 明确标注，点标注等同于点开关）----
        CheckBox autoToggle = PageKit.toggle(PageKit.boolConfig(host, "OptimizeMemoryBeforeLaunch", false));
        Label autoLabel = new Label("启动游戏前优化一次");
        autoLabel.getStyleClass().add("toggle-switch-label");
        autoLabel.setOnMouseClicked(e -> autoToggle.setSelected(!autoToggle.isSelected()));
        autoToggle.selectedProperty().addListener((o, ov, nv) -> {
            host.config().put("OptimizeMemoryBeforeLaunch", String.valueOf(nv));
            host.saveConfig();
            host.ui().toast("启动前优化: " + (nv ? "开启" : "关闭"));
        });
        HBox autoRow = new HBox(10, autoToggle, autoLabel);
        autoRow.setAlignment(Pos.CENTER_LEFT);

        VBox autoBox = new VBox(4, autoRow,
                PageKit.hintLabel("每次启动游戏前自动清理系统待机内存和所有进程的工作集，释放约 1/3 物理内存"));
        autoBox.setPadding(new Insets(10, 0, 0, 0));
        card.getChildren().addAll(new Separator(), autoBox);

        // 停留期间定期刷新占用条；离开页面（节点脱离场景）即停止，避免动画空转
        javafx.animation.Timeline timer = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(Duration.seconds(3), ev -> refresh.run()));
        timer.setCycleCount(javafx.animation.Animation.INDEFINITE);
        timer.play();
        card.sceneProperty().addListener((o, ov, nv) -> {
            if (nv == null) timer.stop(); else timer.play();
        });

        return card;
    }


    /** 后台预热 JVM：跑一次极轻量的 java 调用，把运行时与类库读进系统缓存 */
    public void preheatJvmAsync() {
        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            try {
                String java = host.config().getOrDefault("JavaPath", "java");
                ProcessBuilder pb = new ProcessBuilder(java, "-XX:+UseSerialGC", "-version");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                try (var in = p.getInputStream()) {
                    while (in.read() != -1) {
                        // 读空输出，避免管道填满导致子进程阻塞
                    }
                }
                p.waitFor();
            } catch (Exception ignored) {
                // 预热失败不影响任何功能
            }
        });
    }

}


