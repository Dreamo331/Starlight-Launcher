package com.example.starlight.newui.page.settings;

import com.example.starlight.auth.offline.LocalCapeProxy;
import com.example.starlight.auth.offline.LocalYggdrasilServer;
import com.example.starlight.colorblind.ColorBlindTuneServer;
import com.example.starlight.download.DownloadSettings;
import com.example.starlight.download.mod.HostPolicy;
import com.example.starlight.download.mod.SourceHealth;
import com.example.starlight.log.sendlogs;
import com.example.starlight.main.ConfigManager;
import com.example.starlight.model.LogUploadRecord;
import com.example.starlight.newui.devfloat.DevFloatWindows;
import com.example.starlight.newui.framegen.GameProcessLocator;
import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.AppConfig;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.LogUploadResultWindow;
import com.example.starlight.newui.ui.PageKit;
import com.example.starlight.service.LogUploadStore;
import com.example.starlight.util.DebugLog;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 设置 → 开发者选项页。
 *
 * <p>从 LauncherView 抽离（方法体逐字搬运，逻辑、样式类与内联样式均未改动）：
 * 调试日志控件与监听器随本页迁入；设置页重建时由主壳调用 {@link #detachLogListener()} 摘除旧监听。
 *
 * <p>2026-10-11 扩展：日志导出/上传/过滤/缓冲、下载缓存清理、下载源健康面板、
 * 本地服务状态、下载策略参数（重试/退避/掐线/熔断）、单线程 Host 黑名单、
 * 合并配置查看器、上次启动命令导出、悬浮监控窗（性能/下载速度/游戏状态）。
 */
public final class DevPage {

    private final LauncherContext host;
    // 开发者选项：调试日志显示区与实时监听器（页面重建时先移除旧监听，防止重复注册）
    private TextArea devLogArea;
    private Consumer<String> devLogListener;
    // 日志过滤：历史行全量保留，显示时按关键字过滤（空 = 全显）
    private TextField filterField;
    private final List<String> history = new ArrayList<>();
    private static final int HISTORY_CAP = 5000;
    // 日志实时监听限流（重构 §4.7）：上次 UI 追加时间与合并挂起标记
    private long lastLogFlushMs = 0;
    private boolean pendingLogLines = false;

    // 下载源健康面板的自动刷新（页面重建时停止，见 detachLogListener）
    private Timeline sourceHealthTimer;
    private VBox sourceHealthRows;
    private Label sourceHealthSummary;

    // 本地服务状态 / Host 黑名单的动态行容器（按钮点击后重建）
    private VBox serviceRows;
    private VBox hostChips;

    // 游戏窗口帧率图表卡片（页面重建时停采样，见 detachLogListener）
    private DevFramegenCard framegenCard;

    // 最近一次 PID 解析结果（用于在卡片上如实说明「为什么没找到」）
    private GameProcessLocator.Target lastResolvedTarget;

    public DevPage(LauncherContext host) {
        this.host = host;
    }

    public Node build() {
        VBox root = PageKit.settingsPage(host, "开发者选项");

        // 启动画面开关（原有功能） //
        CheckBox splashToggle = PageKit.toggle("true".equals(host.config().getOrDefault("ShowSplash", "true")));
        splashToggle.setOnAction(e -> {
            host.config().put("ShowSplash", String.valueOf(splashToggle.isSelected()));
            host.saveConfig();
        });
        root.getChildren().add(PageKit.settingsCard("启动画面", "显示启动画面", splashToggle));

        // 调试模式开关 //
        boolean debugMode = "true".equalsIgnoreCase(host.config().getOrDefault("DebugMode", "false"));
        AppConfig.DEBUG_MODE = debugMode; // 与全局开关同步（loadConfig 已恢复，这里兜底）
        CheckBox debugToggle = PageKit.toggle(debugMode);
        debugToggle.setOnAction(e -> {
            AppConfig.DEBUG_MODE = debugToggle.isSelected();
            host.config().put("DebugMode", String.valueOf(debugToggle.isSelected()));
            host.saveConfig();
            DebugLog.applyMode(debugToggle.isSelected()); // 同步 logback root 级别（DEBUG/INFO）
            DebugLog.log("调试模式已" + (debugToggle.isSelected() ? "开启" : "关闭"));
        });
        root.getChildren().add(PageKit.settingsCard("调试模式",
                "记录底层页面刷新与网络数据包收发详情（大小、URL、请求/响应内容），实时显示在下方日志区", debugToggle));

        // 中文日志开关 //
        boolean chineseLog = !"false".equalsIgnoreCase(host.config().getOrDefault("ChineseLog", "true"));
        AppConfig.CHINESE_LOG = chineseLog; // 与全局开关同步
        CheckBox zhToggle = PageKit.toggle(chineseLog);
        zhToggle.setOnAction(e -> {
            AppConfig.CHINESE_LOG = zhToggle.isSelected();
            host.config().put("ChineseLog", String.valueOf(zhToggle.isSelected()));
            host.saveConfig();
            DebugLog.log("中文日志已" + (zhToggle.isSelected() ? "开启" : "关闭"));
        });
        root.getChildren().add(PageKit.settingsCard("中文日志",
                "调试日志以中文输出（如「发送 / 接收」）；关闭后以英文输出（SEND / RECV）", zhToggle));

        // 调试日志缓冲上限 //
        root.getChildren().add(buildBufferCard());

        // 调试日志显示区（重构 §6.15：默认 240px，可新窗口打开） //
        devLogArea = new TextArea();
        devLogArea.setEditable(false);
        devLogArea.setWrapText(false);
        devLogArea.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 12px;");
        devLogArea.setPrefHeight(240);
        devLogArea.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(devLogArea, Priority.ALWAYS);

        // 过滤关键字：改变后按历史全量重渲染
        filterField = new TextField();
        filterField.setPromptText("过滤日志（关键字，如 NET / 内部 / URL）...");
        filterField.textProperty().addListener((o, ov, nv) -> renderLog());

        // 页面重建时回显历史日志
        history.clear();
        history.addAll(DebugLog.snapshot());
        renderLog();

        // 注册实时日志监听（回调线程不保证为 FX 线程，统一 runLater 追加）。
        // 高频输出限流（重构 §4.7）：100ms 内的多条日志合并一次 UI 追加，避免阻塞界面
        devLogListener = line -> Platform.runLater(() -> {
            history.add(line);
            while (history.size() > HISTORY_CAP) history.remove(0);
            long now = System.currentTimeMillis();
            if (now - lastLogFlushMs < 100) {
                pendingLogLines = true;
                return;
            }
            lastLogFlushMs = now;
            pendingLogLines = false;
            if (matchesFilter(line) && devLogArea != null) {
                devLogArea.appendText(line + "\n");
                devLogArea.setScrollTop(Double.MAX_VALUE); // 自动滚到底部
            }
        });
        DebugLog.addListener(devLogListener);

        root.getChildren().add(buildLogButtons());
        root.getChildren().add(devLogArea);

        // 下载缓存清理 //
        root.getChildren().add(buildCacheCard());

        // 下载源健康面板 //
        root.getChildren().add(buildSourceHealthCard());

        // 本地服务状态 //
        root.getChildren().add(buildServiceCard());

        // 下载策略参数（重试 / 退避 / 掐线 / 熔断） //
        root.getChildren().add(buildDownloadParamsCard());

        // 单线程 Host 黑名单 //
        root.getChildren().add(buildHostPolicyCard());

        // 配置查看器 / 启动命令 //
        root.getChildren().add(buildConfigViewerCard());
        root.getChildren().add(buildLaunchCommandCard());

        // 游戏窗口帧率（渲染帧数，不是显示器显示帧数） //
        framegenCard = new DevFramegenCard(host, this::currentGamePid);
        root.getChildren().add(framegenCard.build());

        // 悬浮监控窗 //
        root.getChildren().add(buildFloatWindowsCard());

        Label warn = AppIcons.label("warning", "开发者选项可能会影响启动器稳定性，请谨慎操作");
        warn.setStyle("-fx-text-fill: #eab308; -fx-font-size: 12px; -fx-padding: 10 0;");
        root.getChildren().add(warn);
        return root;
    }

    /** 移除旧的调试日志监听器与健康面板定时器（设置页重建前调用，避免重复输出/空转） */
    public void detachLogListener() {
        if (devLogListener != null) {
            DebugLog.removeListener(devLogListener);
            devLogListener = null;
        }
        if (sourceHealthTimer != null) {
            sourceHealthTimer.stop();
            sourceHealthTimer = null;
        }
        if (framegenCard != null) {
            framegenCard.dispose();
            framegenCard = null;
        }
    }

    /**
     * 解析当前游戏的 PID。
     *
     * <p><b>修正记录</b>：早先这里读的是配置键 {@code GamePid / LastGamePid}，
     * 但启动器从不写这些键，于是恒返回 -1，开发者页的帧率图表永远拿不到数据。
     * 现在统一走 {@link GameProcessLocator}：先查
     * {@code GameInstanceRegistry}（启动器拉起实例的权威来源），
     * 再退到进程扫描（覆盖「先开游戏后开启动器」的情况）。
     */
    private int currentGamePid() {
        final GameProcessLocator.Target t = GameProcessLocator.locate();
        if (t.isValid()) {
            lastResolvedTarget = t;
            return t.pid();
        }
        lastResolvedTarget = t;
        return -1;
    }

    /** 最近一次 PID 解析结果，供 UI 展示「为什么没找到」 */
    GameProcessLocator.Target lastResolvedTarget() {
        return lastResolvedTarget;
    }

    // 日志缓冲上限 //

    /** 缓冲上限下拉（500~10000 条，写入 DebugLogMaxEntries；调小立即裁剪旧条目） */
    private Node buildBufferCard() {
        int current = DebugLog.getMaxEntries();
        ComboBox<String> combo = new ComboBox<>();
        for (int v : new int[]{500, 1000, 2000, 5000, 10000, 20000}) {
            combo.getItems().add(v + " 条");
        }
        selectNearest(combo, current);
        combo.setOnAction(e -> {
            int v = parseLeadingInt(combo.getValue(), 500);
            DebugLog.setMaxEntries(v);
            host.config().put("DebugLogMaxEntries", String.valueOf(v));
            host.saveConfig();
            host.toast("调试日志缓冲已调整为 " + v + " 条");
        });
        return PageKit.settingsCard("日志缓冲上限",
                "内存中保留的调试日志条数（超出丢弃最旧）；调大可回看更早的网络包", combo);
    }

    /** 下拉选中与 current 最接近的档位 */
    private void selectNearest(ComboBox<String> combo, int current) {
        int best = 500, bestDiff = Integer.MAX_VALUE;
        for (String item : combo.getItems()) {
            int v = parseLeadingInt(item, 500);
            int diff = Math.abs(v - current);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = v;
            }
        }
        combo.setValue(best + " 条");
    }

    private int parseLeadingInt(String s, int fallback) {
        if (s == null) return fallback;
        try {
            return Integer.parseInt(s.trim().split(" ")[0]);
        } catch (Exception e) {
            return fallback;
        }
    }

    // 日志区按钮与过滤 //

    /** 过滤行 + 操作按钮行（清空 / 复制 / 导出 / 上传 / 打开日志文件夹 / 新窗口打开） */
    private Node buildLogButtons() {
        filterField.setStyle("-fx-font-size: 12px;");
        HBox.setHgrow(filterField, Priority.ALWAYS);

        Button clearBtn = AppIcons.button("trash", "清空日志");
        clearBtn.getStyleClass().add("btn-primary");
        clearBtn.setOnAction(e -> {
            history.clear();
            renderLog();
        });
        Button copyBtn = AppIcons.button("copy", "复制全部");
        copyBtn.getStyleClass().add("btn-primary");
        copyBtn.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(filteredText());
            Clipboard.getSystemClipboard().setContent(content);
            host.ui().toast("已复制全部日志到剪贴板");
        });
        Button exportBtn = AppIcons.button("download", "导出");
        exportBtn.getStyleClass().add("btn-primary");
        exportBtn.setOnAction(e -> exportLog());
        Button uploadBtn = AppIcons.button("upload", "上传");
        uploadBtn.getStyleClass().add("btn-primary");
        uploadBtn.setOnAction(e -> uploadLog(uploadBtn));
        Button openDirBtn = AppIcons.button("folder", "日志文件夹");
        openDirBtn.getStyleClass().add("btn-primary");
        openDirBtn.setOnAction(e -> host.openFile(Path.of("logs").toAbsolutePath().toString()));
        // 新窗口打开（重构 §6.15：日志区缩小到 240px 后，完整回看走独立只读窗口）
        Button popoutBtn = AppIcons.button("external-link", "新窗口打开");
        popoutBtn.getStyleClass().add("btn-primary");
        popoutBtn.setOnAction(e -> openLogPopout());

        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getChildren().addAll(filterField, clearBtn, copyBtn, exportBtn, uploadBtn, openDirBtn, popoutBtn);
        return row;
    }

    /** 在只读弹窗里打开完整过滤日志（带复制按钮；日志区继续实时滚动，弹窗为打开时快照） */
    private void openLogPopout() {
        TextArea area = new TextArea(filteredText());
        area.setEditable(false);
        area.setWrapText(false);
        area.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 12px;");
        area.setPrefHeight(420);
        Button copy = PageKit.cardActionButton("复制全部");
        copy.setOnAction(ev -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(area.getText());
            Clipboard.getSystemClipboard().setContent(content);
            host.toast("已复制到剪贴板");
        });
        HBox top = new HBox(10);
        top.setAlignment(Pos.CENTER_RIGHT);
        top.getChildren().add(copy);
        VBox box = new VBox(8);
        box.getChildren().addAll(top, area);
        host.ui().modal("调试日志（只读快照）", box, 860, 560);
    }

    /** 过滤匹配：空 = 全部命中（忽略大小写的包含匹配） */
    private boolean matchesFilter(String line) {
        String kw = filterField == null ? null : filterField.getText();
        return kw == null || kw.isBlank() || line.toLowerCase().contains(kw.toLowerCase().trim());
    }

    private String filteredText() {
        StringBuilder sb = new StringBuilder();
        for (String line : history) {
            if (matchesFilter(line)) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** 按当前过滤条件全量重渲染日志区（过滤变更 / 清空后调用） */
    private void renderLog() {
        if (devLogArea == null) return;
        devLogArea.setText(filteredText());
        devLogArea.setScrollTop(Double.MAX_VALUE);
    }

    /** 导出日志为文件（FileChooser，默认名 starlight-debug.log） */
    private void exportLog() {
        FileChooser fc = new FileChooser();
        fc.setTitle("导出调试日志");
        fc.setInitialFileName("starlight-debug.log");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("日志文件", "*.log", "*.txt"),
                new FileChooser.ExtensionFilter("全部文件", "*.*"));
        java.io.File file = fc.showSaveDialog(host.stage());
        if (file == null) return;
        writeExport(file.toPath());
    }

    private void writeExport(Path target) {
        try {
            Files.writeString(target, filteredText(), StandardCharsets.UTF_8);
            host.toast("日志已导出: " + target);
        } catch (IOException ex) {
            host.toast("导出失败: " + ex.getMessage());
        }
    }

    /**
     * 上传调试日志：主内容 = 过滤后的日志区全文，附加 logs/starlight.log 尾部（≤1MB）。
     * 成功后登记到「上传的日志」列表并弹出分享/二维码窗口。
     */
    private void uploadLog(Button btn) {
        String main = filteredText();
        if (main.isBlank()) {
            host.toast("日志为空，无可上传内容");
            return;
        }
        btn.setDisable(true);
        btn.setText("上传中...");
        Thread worker = new Thread(() -> {
            try {
                List<sendlogs.LogFileEntry> extras = new ArrayList<>();
                Path mainLog = Path.of("logs", "starlight.log");
                if (Files.isRegularFile(mainLog)) {
                    extras.add(new sendlogs.LogFileEntry("starlight.log", readTail(mainLog, 1024L * 1024L)));
                }
                sendlogs.UploadResult ur = sendlogs.uploadLogBundleEx(
                        null, main, "starlight-debug.log", extras);
                LogUploadRecord record = new LogUploadRecord(ur.id, ur.url, ur.token,
                        "starlight-debug.log", System.currentTimeMillis(), 1 + extras.size(), main.length());
                LogUploadStore.add(record);
                Platform.runLater(() -> {
                    btn.setDisable(false);
                    btn.setText("上传");
                    LogUploadResultWindow.show(host.stage(), record, host::toast, () -> { });
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    btn.setDisable(false);
                    btn.setText("上传");
                    host.toast("上传失败: " + ex.getMessage());
                });
            }
        }, "dev-log-upload");
        worker.setDaemon(true);
        worker.start();
    }

    /** 读文件尾部（超长截取最后 limit 字节） */
    private String readTail(Path file, long limit) throws IOException {
        byte[] all = Files.readAllBytes(file);
        if (all.length > limit) {
            byte[] tail = new byte[(int) limit];
            System.arraycopy(all, all.length - (int) limit, tail, 0, (int) limit);
            return new String(tail, StandardCharsets.UTF_8);
        }
        return new String(all, StandardCharsets.UTF_8);
    }

    // 下载缓存清理 //

    /** 清除 <gameDir>/.cache：下载元数据缓存（meta/）+ 校验状态缓存（verify-state.json） */
    private Node buildCacheCard() {
        Button btn = PageKit.cardActionButton("清除下载缓存");
        btn.setOnAction(e -> host.ui().confirm("清除下载缓存",
                "将删除游戏目录 .cache 下的下载元数据缓存与校验状态缓存。\n"
                        + "不影响游戏文件本身，下次资源补全会重新拉取元数据并做完整校验（更慢但更严格）。",
                "清除", true, this::doClearCache));
        return PageKit.settingsCard("下载缓存", "清除下载元数据缓存与校验状态缓存（排查文件损坏 / 强制重新校验）", btn);
    }

    private void doClearCache() {
        Thread worker = new Thread(() -> {
            String gameDir = host.config().getOrDefault("GameDir", ".minecraft");
            long freed = 0;
            freed += deleteTree(Path.of(gameDir, ".cache", "meta"));
            freed += deleteFile(Path.of(gameDir, ".cache", "verify-state.json"));
            long f = freed;
            Platform.runLater(() -> host.toast(f > 0
                    ? "已清除下载缓存，释放 " + FormatBytes.human(f)
                    : "没有可清除的下载缓存"));
        }, "dev-clear-cache");
        worker.setDaemon(true);
        worker.start();
    }

    /** 递归删除目录，返回释放的字节数（目录不存在返回 0） */
    private long deleteTree(Path dir) {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> walk = Files.walk(dir)) {
            long[] freed = {0};
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    if (Files.isRegularFile(p)) freed[0] += Files.size(p);
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 单个文件删不掉（被占用）不影响其余清理
                }
            });
            return freed[0];
        } catch (IOException e) {
            return 0;
        }
    }

    /** 删除单个文件，返回释放的字节数 */
    private long deleteFile(Path file) {
        try {
            if (!Files.isRegularFile(file)) return 0;
            long size = Files.size(file);
            Files.deleteIfExists(file);
            return size;
        } catch (IOException e) {
            return 0;
        }
    }

    // 下载源健康面板 //

    /** 源健康面板：汇总行 + 自动刷新（5s）+ 地址段明细 + 重置按钮 */
    private Node buildSourceHealthCard() {
        sourceHealthSummary = new Label();
        sourceHealthSummary.setStyle("-fx-text-fill: #9ca3af; -fx-font-size: 12px;");
        sourceHealthRows = new VBox(4);

        Button refreshBtn = PageKit.cardActionButton("刷新");
        refreshBtn.setOnAction(e -> refreshSourceHealth());
        Button resetBtn = PageKit.cardActionButton("重置");
        resetBtn.setOnAction(e -> host.ui().confirm("重置源健康状态",
                "将清空全部下载源健康统计（拉黑 / 失败计数 / 成功经验），下一个文件起重新观察。",
                "重置", true, () -> {
                    SourceHealth.getInstance().reset();
                    refreshSourceHealth();
                    host.toast("源健康状态已重置");
                }));

        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        header.getChildren().addAll(sourceHealthSummary, spacer, refreshBtn, resetBtn);

        VBox card = PageKit.settingsCardStacked("下载源健康",
                "「主机 + 路径前缀」级熔断与成功经验（跳过的无效请求 / 各源失败计数 / 最近成功耗时）", header);
        card.getChildren().add(sourceHealthRows);
        refreshSourceHealth();

        // 停留期间 5s 自动刷新（解禁倒计时推进）；离开页面随 detach 停止；
        // 页面不可见（切到其他页签）时暂停，回来自动恢复（重构 §4.7 性能要求）
        sourceHealthTimer = new Timeline(new KeyFrame(Duration.seconds(5), e -> {
            if (devLogArea == null || devLogArea.getScene() == null) return;
            if (!devLogArea.isVisible()) return; // 页面不可见：暂停刷新
            refreshSourceHealth();
        }));
        sourceHealthTimer.setCycleCount(Timeline.INDEFINITE);
        sourceHealthTimer.play();
        return card;
    }

    private void refreshSourceHealth() {
        if (sourceHealthRows == null) return;
        sourceHealthRows.getChildren().clear();
        SourceHealth sh = SourceHealth.getInstance();
        long skipped = sh.getSkippedRequests();
        List<SourceHealth.HealthRow> rows = sh.snapshot();
        sourceHealthSummary.setText("累计跳过无效请求 " + skipped + " 次 · 跟踪 " + rows.size() + " 个地址段");
        if (rows.isEmpty()) {
            sourceHealthRows.getChildren().add(dimHint("暂无记录（尚未发生下载）"));
            return;
        }
        long now = System.currentTimeMillis();
        int shown = 0;
        for (SourceHealth.HealthRow row : rows) {
            if (shown >= 20) {
                sourceHealthRows.getChildren().add(dimHint("…其余 " + (rows.size() - shown) + " 条略"));
                break;
            }
            boolean blocked = row.blockedUntilMs > 0;
            boolean dirty = row.consecutive404 > 0 || row.consecutiveTimeout > 0
                    || row.consecutiveEmpty > 0 || row.slowCount > 0;
            String status = blocked
                    ? "已拉黑 " + Math.max(1, (row.blockedUntilMs - now) / 1000) + "s"
                    : "正常";
            String color = blocked ? "#ef4444" : dirty ? "#f59e0b" : "#9ca3af";
            StringBuilder line = new StringBuilder(row.key)
                    .append("  [").append(status).append("]");
            if (row.consecutive404 > 0) line.append(" 404×").append(row.consecutive404);
            if (row.consecutiveTimeout > 0) line.append(" 超时×").append(row.consecutiveTimeout);
            if (row.consecutiveEmpty > 0) line.append(" 空×").append(row.consecutiveEmpty);
            if (row.slowCount > 0) line.append(" 慢×").append(row.slowCount);
            if (row.lastSuccessCostMs >= 0) line.append(" · 上次成功 ").append(row.lastSuccessCostMs).append("ms");
            Label l = new Label(line.toString());
            l.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 11px;"
                    + " -fx-font-family: 'Consolas', monospace;");
            l.setWrapText(false);
            sourceHealthRows.getChildren().add(l);
            shown++;
        }
    }

    private Label dimHint(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #6b7280; -fx-font-size: 11px;");
        return l;
    }

    // 本地服务状态 //

    /** 本地服务状态：皮肤/披风服务 / 披风代理 / 色觉调节页（懒启动，随游戏会话或功能开关拉起） */
    private Node buildServiceCard() {
        serviceRows = new VBox(4);
        Button refreshBtn = PageKit.cardActionButton("刷新");
        refreshBtn.setOnAction(e -> refreshServices());
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label placeholder = new Label();
        placeholder.setVisible(false);
        header.getChildren().addAll(placeholder, spacer, refreshBtn);

        VBox card = PageKit.settingsCardStacked("本地服务状态",
                "启动器内嵌的回环 HTTP 服务（随机端口、懒启动）；排查皮肤/披风/色觉调节异常时先看这里", header);
        card.getChildren().add(serviceRows);
        refreshServices();
        return card;
    }

    private void refreshServices() {
        if (serviceRows == null) return;
        serviceRows.getChildren().clear();

        LocalYggdrasilServer ygg = LocalYggdrasilServer.current();
        serviceRow("本地皮肤/披风服务 (Yggdrasil)",
                ygg == null ? "未启动（启动游戏且本地有披风时拉起）" : "运行中 · 端口 " + ygg.getPort(),
                ygg != null);

        LocalCapeProxy cape = LocalCapeProxy.current();
        serviceRow("本地披风代理",
                cape == null ? "未启动（同上，配合 cape-url-patch-agent 注入）" : "运行中 · 端口 " + cape.getCapePort(),
                cape != null);

        boolean tuneUp = ColorBlindTuneServer.isRunning();
        String tuneUrl = ColorBlindTuneServer.url();
        serviceRow("色觉快速调节页",
                tuneUp ? "运行中 · " + tuneUrl : "未启动（打开色盲辅助调节时拉起）", tuneUp);
        if (tuneUp) {
            Button open = PageKit.cardActionButton("打开网页");
            open.setOnAction(e -> host.openWebUrl(tuneUrl));
            serviceRows.getChildren().add(open);
        }
    }

    /** 一行服务状态：名称 + 状态文案（运行中绿色 / 未启动灰色） */
    private void serviceRow(String name, String status, boolean running) {
        Label l = new Label(name + "  ·  " + status);
        l.setStyle("-fx-text-fill: " + (running ? "#22c55e" : "#6b7280") + "; -fx-font-size: 12px;");
        l.setWrapText(true);
        serviceRows.getChildren().add(l);
    }

    // 下载策略参数 //

    /** 重试 / 退避 / 掐线 / 熔断参数（写入 DownloadSettings + starlight-client.ini，立即生效） */
    private Node buildDownloadParamsCard() {
        TextField mergeRetry = numField(DownloadSettings.getMergeRetry());
        TextField mergeBackoff = numField(DownloadSettings.getMergeBackoffMs());
        TextField slowGap = numField(DownloadSettings.getSlowGapSec());
        TextField breakerThreshold = numField(DownloadSettings.getBreakerThreshold());
        TextField breakerCooldown = numField(DownloadSettings.getBreakerCooldownSec());

        VBox rows = new VBox(6);
        rows.getChildren().addAll(
                paramRow("合并重试次数（1~10）", mergeRetry),
                paramRow("合并退避基数 ms（100~5000，实际退避 = 基数×次数）", mergeBackoff),
                paramRow("慢速掐线阈值 秒（1~60，两包间隔超此值且不足 1KB 即断开）", slowGap),
                paramRow("镜像熔断阈值 次（1~10，连续失败多少次拉黑镜像）", breakerThreshold),
                paramRow("镜像熔断冷却 秒（10~600，冷却结束放行探测）", breakerCooldown));

        Button applyBtn = PageKit.cardActionButton("应用");
        applyBtn.setOnAction(e -> {
            int retry = PageKit.parseIntSafe(mergeRetry.getText(), DownloadSettings.DEFAULT_MERGE_RETRY);
            int backoff = PageKit.parseIntSafe(mergeBackoff.getText(), DownloadSettings.DEFAULT_MERGE_BACKOFF_MS);
            int gap = PageKit.parseIntSafe(slowGap.getText(), DownloadSettings.DEFAULT_SLOW_GAP_SEC);
            int threshold = PageKit.parseIntSafe(breakerThreshold.getText(), DownloadSettings.DEFAULT_BREAKER_THRESHOLD);
            int cooldown = PageKit.parseIntSafe(breakerCooldown.getText(), DownloadSettings.DEFAULT_BREAKER_COOLDOWN_SEC);
            DownloadSettings.setMergeRetry(retry);
            DownloadSettings.setMergeBackoffMs(backoff);
            DownloadSettings.setSlowGapSec(gap);
            DownloadSettings.setBreakerThreshold(threshold);
            DownloadSettings.setBreakerCooldownSec(cooldown);
            host.config().put(DownloadSettings.KEY_MERGE_RETRY, String.valueOf(DownloadSettings.getMergeRetry()));
            host.config().put(DownloadSettings.KEY_MERGE_BACKOFF_MS, String.valueOf(DownloadSettings.getMergeBackoffMs()));
            host.config().put(DownloadSettings.KEY_SLOW_GAP_SEC, String.valueOf(DownloadSettings.getSlowGapSec()));
            host.config().put(DownloadSettings.KEY_BREAKER_THRESHOLD, String.valueOf(DownloadSettings.getBreakerThreshold()));
            host.config().put(DownloadSettings.KEY_BREAKER_COOLDOWN_SEC, String.valueOf(DownloadSettings.getBreakerCooldownSec()));
            host.saveConfig();
            // 回显被钳制后的实际值
            mergeRetry.setText(String.valueOf(DownloadSettings.getMergeRetry()));
            mergeBackoff.setText(String.valueOf(DownloadSettings.getMergeBackoffMs()));
            slowGap.setText(String.valueOf(DownloadSettings.getSlowGapSec()));
            breakerThreshold.setText(String.valueOf(DownloadSettings.getBreakerThreshold()));
            breakerCooldown.setText(String.valueOf(DownloadSettings.getBreakerCooldownSec()));
            host.toast("下载策略参数已应用（立即生效）");
        });
        Button resetBtn = PageKit.cardActionButton("恢复默认");
        resetBtn.setOnAction(e -> {
            mergeRetry.setText(String.valueOf(DownloadSettings.DEFAULT_MERGE_RETRY));
            mergeBackoff.setText(String.valueOf(DownloadSettings.DEFAULT_MERGE_BACKOFF_MS));
            slowGap.setText(String.valueOf(DownloadSettings.DEFAULT_SLOW_GAP_SEC));
            breakerThreshold.setText(String.valueOf(DownloadSettings.DEFAULT_BREAKER_THRESHOLD));
            breakerCooldown.setText(String.valueOf(DownloadSettings.DEFAULT_BREAKER_COOLDOWN_SEC));
            applyBtn.fire();
        });

        HBox btns = new HBox(10);
        btns.setAlignment(Pos.CENTER_LEFT);
        btns.getChildren().addAll(applyBtn, resetBtn);

        VBox card = PageKit.settingsCardStacked("下载策略参数",
                "调错会直接伤下载成功率——仅在排查特定源/镜像问题时调整", rows);
        card.getChildren().add(btns);
        return card;
    }

    private TextField numField(int value) {
        TextField f = new TextField(String.valueOf(value));
        f.setPrefWidth(90);
        f.setStyle("-fx-font-size: 12px;");
        return f;
    }

    private HBox paramRow(String label, TextField field) {
        Label l = new Label(label);
        l.setStyle("-fx-font-size: 12px; -fx-text-fill: #9ca3af;");
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getChildren().addAll(field, l);
        return row;
    }

    // 单线程 Host 黑名单 //

    /** 用户自加的「只单线程」host 片段管理（内置片段固定展示，用户片段可删） */
    private Node buildHostPolicyCard() {
        // 启动时把持久化的用户片段注入 HostPolicy（仅在本页首次构建时执行一次）
        String saved = host.config().getOrDefault("SingleThreadHosts", "");
        if (!saved.isBlank()) {
            for (String frag : saved.split("\\|")) {
                if (!frag.isBlank()) HostPolicy.addSingleThreadHost(frag.trim());
            }
        }

        hostChips = new VBox(4);
        TextField input = new TextField();
        input.setPromptText("域名片段，如 my-mirror.example.com（可 | 分隔多个）");
        input.setStyle("-fx-font-size: 12px;");
        HBox.setHgrow(input, Priority.ALWAYS);
        Button addBtn = PageKit.cardActionButton("添加");
        addBtn.setOnAction(e -> {
            String raw = input.getText();
            if (raw == null || raw.isBlank()) return;
            int added = 0;
            for (String frag : raw.split("\\|")) {
                if (!frag.isBlank()) {
                    HostPolicy.addSingleThreadHost(frag.trim());
                    added++;
                }
            }
            if (added > 0) {
                persistHosts();
                input.clear();
                host.toast("已添加 " + added + " 个单线程 host 片段");
            }
            refreshHostChips();
        });

        VBox card = PageKit.settingsCardStacked("单线程 Host 黑名单",
                "命中片段的下载源只开一个线程（防风控/限流）；内置: bmclapi / github.com / githubusercontent"
                        + " / modrinth / mcbbs / optifine / meloong / momot / pcl2-server（不可移除）",
                hostInputRow(input, addBtn));
        card.getChildren().add(hostChips);
        refreshHostChips();
        return card;
    }

    /** 输入框 + 添加按钮（平铺一行） */
    private HBox hostInputRow(TextField input, Button addBtn) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getChildren().addAll(input, addBtn);
        return row;
    }

    private void refreshHostChips() {
        if (hostChips == null) return;
        hostChips.getChildren().clear();
        List<String> frags = HostPolicy.userFragments();
        if (frags.isEmpty()) {
            hostChips.getChildren().add(dimHint("暂无用户自加片段"));
            return;
        }
        for (String frag : frags) {
            Label name = new Label(frag);
            name.setStyle("-fx-text-fill: #d1d5db; -fx-font-size: 12px; -fx-font-family: 'Consolas', monospace;");
            Button remove = PageKit.cardActionButton("移除");
            remove.setOnAction(e -> {
                HostPolicy.removeSingleThreadHost(frag);
                persistHosts();
                refreshHostChips();
            });
            HBox row = new HBox(10);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getChildren().addAll(name, remove);
            hostChips.getChildren().add(row);
        }
    }

    /** 用户片段写回 starlight-client.ini（| 分隔） */
    private void persistHosts() {
        host.config().put("SingleThreadHosts", String.join("|", HostPolicy.userFragments()));
        host.saveConfig();
    }

    // 配置查看器 //

    /** 合并配置查看器：starlight.ini + starlight-client.ini + 版本级 overrides（敏感值打码） */
    private Node buildConfigViewerCard() {
        Button btn = PageKit.cardActionButton("查看合并配置");
        btn.setOnAction(e -> {
            TextArea area = new TextArea(buildConfigDump());
            area.setEditable(false);
            area.setWrapText(false);
            area.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 12px;");
            area.setPrefHeight(420);
            Button copy = PageKit.cardActionButton("复制全部");
            copy.setOnAction(ev -> {
                ClipboardContent content = new ClipboardContent();
                content.putString(area.getText());
                Clipboard.getSystemClipboard().setContent(content);
                host.toast("配置已复制到剪贴板");
            });
            HBox top = new HBox(10);
            top.setAlignment(Pos.CENTER_RIGHT);
            top.getChildren().add(copy);
            VBox box = new VBox(8);
            box.getChildren().addAll(top, area);
            host.ui().modal("合并配置查看（敏感值已打码）", box, 720, 560);
        });
        return PageKit.settingsCard("配置查看器",
                "只读展示两份 ini 与版本级 config.overrides.json 的实际内容，排查「改了没生效」", btn);
    }

    /** 拼装配置转储：两份 ini + 版本级覆盖文件；key 含 token/password/key/secret 的值打码 */
    private String buildConfigDump() {
        StringBuilder sb = new StringBuilder();
        sb.append("== starlight.ini ==\n");
        dumpMap(sb, ConfigManager.readConfig());
        sb.append("\n== starlight-client.ini ==\n");
        dumpMap(sb, ConfigManager.readClientConfig());
        sb.append("\n== 版本级覆盖 config.overrides.json ==\n");
        String gameDir = host.config().getOrDefault("GameDir", ".minecraft");
        boolean any = false;
        Path versions = Path.of(gameDir, "versions");
        if (Files.isDirectory(versions)) {
            try (Stream<Path> walk = Files.walk(versions, 2)) {
                List<Path> overrides = walk
                        .filter(p -> VersionConfigFileName.equals(p.getFileName().toString()))
                        .limit(10)
                        .toList();
                for (Path p : overrides) {
                    any = true;
                    sb.append("-- ").append(p).append(" --\n");
                    try {
                        String content = Files.readString(p);
                        if (content.length() > 2000) content = content.substring(0, 2000) + "\n…(已截断)";
                        sb.append(content).append('\n');
                    } catch (IOException ex) {
                        sb.append("（读取失败: ").append(ex.getMessage()).append("）\n");
                    }
                }
            } catch (IOException ignored) {
                // 目录遍历失败按无覆盖文件处理
            }
        }
        if (!any) sb.append("（当前游戏目录下没有版本级覆盖文件）\n");
        return sb.toString();
    }

    /** 版本级覆盖文件名（与 VersionConfigManager.OVERRIDES_FILENAME 一致，避免引入 UI 无关依赖） */
    private static final String VersionConfigFileName = "config.overrides.json";

    private void dumpMap(StringBuilder sb, Map<String, String> map) {
        if (map.isEmpty()) {
            sb.append("（空）\n");
            return;
        }
        new java.util.TreeMap<>(map).forEach((k, v) -> sb.append(k).append(" = ")
                .append(isSensitiveKey(k) ? "***" : v).append('\n'));
    }

    private boolean isSensitiveKey(String key) {
        String k = key.toLowerCase();
        return k.contains("password") || k.contains("token")
                || k.contains("apikey") || k.contains("api_key") || k.contains("secret");
    }

    // 启动命令 //

    /** 上次启动命令查看/导出（来源：logs/minecraft_launch_*.log 的「完整启动命令」行） */
    private Node buildLaunchCommandCard() {
        Button btn = PageKit.cardActionButton("查看上次启动命令");
        btn.setOnAction(e -> {
            String cmd = findLastLaunchCommand();
            if (cmd == null) {
                host.toast("还没有启动记录（未找到 logs/minecraft_launch_*.log）");
                return;
            }
            TextArea area = new TextArea(cmd);
            area.setEditable(false);
            area.setWrapText(true);
            area.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 12px;");
            area.setPrefHeight(200);

            Button copy = PageKit.cardActionButton("复制");
            copy.setOnAction(ev -> {
                ClipboardContent content = new ClipboardContent();
                content.putString(cmd);
                Clipboard.getSystemClipboard().setContent(content);
                host.toast("启动命令已复制到剪贴板");
            });
            Button export = PageKit.cardActionButton("导出 .bat");
            export.setOnAction(ev -> {
                FileChooser fc = new FileChooser();
                fc.setTitle("导出启动脚本");
                fc.setInitialFileName("launch-minecraft.bat");
                fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("批处理脚本", "*.bat"));
                java.io.File file = fc.showSaveDialog(host.stage());
                if (file == null) return;
                try {
                    String gameDir = host.config().getOrDefault("GameDir", ".minecraft");
                    String bat = "@echo off\r\ncd /d \"" + Path.of(gameDir).toAbsolutePath().normalize()
                            + "\"\r\n" + cmd + "\r\n";
                    Files.writeString(file.toPath(), bat, StandardCharsets.UTF_8);
                    host.toast("启动脚本已导出: " + file.getAbsolutePath());
                } catch (IOException ex) {
                    host.toast("导出失败: " + ex.getMessage());
                }
            });
            HBox btns = new HBox(10);
            btns.setAlignment(Pos.CENTER_RIGHT);
            btns.getChildren().addAll(copy, export);
            VBox box = new VBox(8);
            box.getChildren().addAll(btns, area);
            host.ui().modal("上次启动命令（完整 JVM + 游戏参数）", box, 860, 320);
        });
        return PageKit.settingsCard("启动命令预览",
                "提取最近一次游戏启动的完整命令行（含 JVM 参数 / classpath / 游戏参数），可复制或导出为 .bat 复现", btn);
    }

    /** 扫 logs/minecraft_launch_*.log 取最新一份中的「完整启动命令: 」行；没有返回 null */
    private String findLastLaunchCommand() {
        Path logs = Path.of("logs");
        if (!Files.isDirectory(logs)) return null;
        Path latest = null;
        try (Stream<Path> walk = Files.list(logs)) {
            latest = walk.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("minecraft_launch_") && n.endsWith(".log");
                    })
                    .max(java.util.Comparator.comparingLong(DevPage::mtimeOf))
                    .orElse(null);
        } catch (IOException ignored) {
            return null;
        }
        if (latest == null) return null;
        try {
            List<String> lines = Files.readAllLines(latest, StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0; i--) {
                String line = lines.get(i);
                if (line.contains("完整启动命令: ")) {
                    return line.substring(line.indexOf("完整启动命令: ") + "完整启动命令: ".length());
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static long mtimeOf(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    // 悬浮监控窗 //

    /** 三个悬浮监控窗的开关（性能 / 下载速度 / 游戏状态），窗口生命周期独立于本页 */
    private Node buildFloatWindowsCard() {
        Button perfBtn = floatButton("性能监控", DevFloatWindows::togglePerformance,
                () -> DevFloatWindows.isPerformanceOpen());
        Button dlBtn = floatButton("下载速度", DevFloatWindows::toggleDownloadSpeed,
                () -> DevFloatWindows.isDownloadSpeedOpen());
        Button gameBtn = floatButton("游戏状态", DevFloatWindows::toggleGameStatus,
                () -> DevFloatWindows.isGameStatusOpen());
        Button netBtn = floatButton("网络请求", DevFloatWindows::toggleNetworkStats,
                () -> DevFloatWindows.isNetworkStatsOpen());

        HBox btns = new HBox(10);
        btns.setAlignment(Pos.CENTER_LEFT);
        btns.getChildren().addAll(perfBtn, dlBtn, gameBtn, netBtn);
        return PageKit.settingsCardStacked("悬浮监控窗",
                "置顶小窗，游戏/下载时挂在角落观察；再次点击关闭。性能/下载速度 1s 采样，网络请求为累计计数", btns);
    }

    /** 悬浮窗开关按钮：点击切换，文案随状态刷新（页面重建时按当前状态恢复） */
    private Button floatButton(String name, Runnable toggle, java.util.function.BooleanSupplier isOpen) {
        Button b = PageKit.cardActionButton(name + "：" + (isOpen.getAsBoolean() ? "已开" : "关闭"));
        b.setOnAction(e -> {
            toggle.run();
            b.setText(name + "：" + (isOpen.getAsBoolean() ? "已开" : "关闭"));
        });
        return b;
    }

    // 小工具 //

    /** 字节数人性化（B / KB / MB），本页专用 */
    private static final class FormatBytes {
        static String human(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
            return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        }
    }
}
