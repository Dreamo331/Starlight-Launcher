package com.example.starlight.newui.page;

import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.PageKit;
import com.example.starlight.util.NetworkStatusService;
import com.example.starlight.util.NetworkStatusService.SourceResult;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * 网络检测页（真实测速）。
 *
 * <p>此前本页的「测速」是随机数、状态卡写死「--ms / 正常」，页面有缓存且建好后不再更新。
 * 现在全部改为对下载源发真实 HTTP 请求测往返延迟（{@link NetworkStatusService}）：
 * <ul>
 *   <li>进入页面时自动测一次当前下载源，状态卡实时反映结果；</li>
 *   <li>每个源的「测速」按钮单独测该源；</li>
 *   <li>「一键网络测速」并发测全部源，结果同时回填状态卡（若被测源是当前下载源）。</li>
 * </ul>
 */
public final class NetworkCheckPage {

    private final LauncherContext host;

    /** 内置测速目标：与下载源选项一致（名称、探针根地址） */
    private static final String[][] SOURCES = {
            {"BMCLAPI", "https://bmclapi2.bangbang93.com", "推荐"},
            {"Mojang", "https://launchermeta.mojang.com", "官方"},
            {"MCBBS", "https://download.mcbbs.net", "已停服"},
    };

    public NetworkCheckPage(LauncherContext host) {
        this.host = host;
    }

    // ===== 网络检测页面 =====

    public Node build() {
        VBox root = PageKit.settingsPage(host, "网络检测", "home");

        // ===== 状态卡：下载源 / 延迟 / 连接状态（真实数据，进页面即刷新） =====
        VBox statusCard = new VBox(10);
        statusCard.getStyleClass().add("settings-card");
        Label statusTitle = AppIcons.label("globe", "网络状态");
        statusTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: 600; -fx-text-fill: -sl-text;");

        String currentSource = NetworkStatusService.currentSourceName();
        Label sourceValue = statValue(currentSource);
        Label latencyValue = statValue("--ms");
        Label connValue = statValue("检测中...");

        HBox statusRow = new HBox(20);
        statusRow.setAlignment(Pos.CENTER);
        statusRow.getChildren().addAll(
                statBlock("下载源", sourceValue),
                statBlock("延迟", latencyValue),
                statBlock("连接状态", connValue)
        );
        statusCard.getChildren().addAll(statusTitle, statusRow);
        root.getChildren().add(statusCard);

        // ===== 下载源测速卡 =====
        VBox sourceCard = new VBox(10);
        sourceCard.getStyleClass().add("settings-card");
        Label sourceTitle = AppIcons.label("antenna", "下载源测速");
        sourceTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: 600; -fx-text-fill: -sl-text;");
        sourceCard.getChildren().add(sourceTitle);

        // 行内延迟标签登记表（一键测速完成后统一回填）
        java.util.List<String[]> rowNames = new java.util.ArrayList<>();
        java.util.List<Label> rowLatencies = new java.util.ArrayList<>();

        for (String[] s : SOURCES) {
            HBox item = new HBox(12);
            item.setAlignment(Pos.CENTER_LEFT);
            Label name = new Label(s[0]);
            name.setStyle("-fx-font-size: 13px; -fx-font-weight: 500; -fx-min-width: 80;");
            String tag = (s.length > 2 && !s[2].isBlank()) ? "　（" + s[2] + "）" : "";
            Label url = new Label(s[1] + tag);
            url.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-faint;");
            HBox.setHgrow(url, Priority.ALWAYS);
            Label latency = new Label("--ms");
            latency.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-faint;");
            Button testBtn = AppIcons.button("gauge", "测速");
            testBtn.getStyleClass().add("btn-primary");
            testBtn.setStyle("-fx-padding: 4 12; -fx-font-size: 11px;");
            testBtn.setOnAction(e -> {
                testBtn.setDisable(true);
                latency.setText("测试中...");
                latency.setStyle("-fx-font-size: 12px; -fx-text-fill: #f59e0b;");
                NetworkStatusService.testAllAsync().thenAccept(list -> {
                    // 单源按钮复用整体测速（30 秒缓存期内不会重复打网络），再挑出本行的结果
                    SourceResult r = pick(list, s[0]);
                    Platform.runLater(() -> {
                        applyLatency(latency, r);
                        testBtn.setDisable(false);
                        if (s[0].equalsIgnoreCase(NetworkStatusService.currentSourceName())) {
                            applyStatusCard(r, latencyValue, connValue);
                        }
                    });
                });
            });
            item.getChildren().addAll(name, url, latency, testBtn);
            sourceCard.getChildren().add(item);
            rowNames.add(s);
            rowLatencies.add(latency);
        }
        root.getChildren().add(sourceCard);

        // ===== 一键测速 =====
        Button speedTestBtn = AppIcons.button("rocket", "一键网络测速");
        speedTestBtn.getStyleClass().add("home-launch-btn");
        speedTestBtn.setMaxWidth(Double.MAX_VALUE);
        speedTestBtn.setOnAction(e -> {
            speedTestBtn.setDisable(true);
            host.ui().toast("开始网络测速...");
            NetworkStatusService.testAllAsync().thenAccept(list -> Platform.runLater(() -> {
                speedTestBtn.setDisable(false);
                SourceResult current = pick(list, NetworkStatusService.currentSourceName());
                if (current != null) {
                    applyStatusCard(current, latencyValue, connValue);
                }
                for (int i = 0; i < rowLatencies.size() && i < rowNames.size(); i++) {
                    applyLatency(rowLatencies.get(i), pick(list, rowNames.get(i)[0]));
                }
                host.ui().toast("测速完成");
            }));
        });
        root.getChildren().add(speedTestBtn);

        // 进入页面即测一次当前下载源（缓存 30 秒内直接复用，不重复打网络）
        latencyValue.setText("--ms");
        connValue.setText("检测中...");
        connValue.setStyle("-fx-font-size: 15px; -fx-font-weight: 700; -fx-text-fill: #f59e0b;");
        NetworkStatusService.testCurrentSourceAsync().thenAccept(r -> Platform.runLater(() ->
                applyStatusCard(r, latencyValue, connValue)));

        return root;
    }

    /** 状态卡应用一次测速结果 */
    private void applyStatusCard(SourceResult r, Label latencyValue, Label connValue) {
        if (r == null) {
            latencyValue.setText("--ms");
            connValue.setText("未知");
            connValue.setStyle("-fx-font-size: 15px; -fx-font-weight: 700; -fx-text-fill: #6b7280;");
            return;
        }
        if (r.ok) {
            latencyValue.setText(r.latencyMs + "ms");
            latencyValue.setStyle("-fx-font-size: 15px; -fx-font-weight: 700; -fx-text-fill: "
                    + latencyColor(r.latencyMs) + ";");
            connValue.setText("正常");
            connValue.setStyle("-fx-font-size: 15px; -fx-font-weight: 700; -fx-text-fill: #22c55e;");
        } else {
            latencyValue.setText("--ms");
            latencyValue.setStyle("-fx-font-size: 15px; -fx-font-weight: 700; -fx-text-fill: #ef4444;");
            connValue.setText("不可达");
            connValue.setStyle("-fx-font-size: 15px; -fx-font-weight: 700; -fx-text-fill: #ef4444;");
        }
    }

    /** 测速行应用一次结果 */
    private void applyLatency(Label latency, SourceResult r) {
        if (r == null || !r.ok) {
            latency.setText("不可达");
            latency.setStyle("-fx-font-size: 12px; -fx-text-fill: #ef4444;");
            return;
        }
        latency.setText(r.latencyMs + "ms");
        latency.setStyle("-fx-font-size: 12px; -fx-text-fill: " + latencyColor(r.latencyMs) + ";");
    }

    private static String latencyColor(int ms) {
        if (ms < 100) return "#22c55e";
        if (ms < 200) return "#f59e0b";
        return "#ef4444";
    }

    /** 从结果列表里挑出指定源名的结果 */
    private static SourceResult pick(java.util.List<SourceResult> list, String name) {
        if (list == null || name == null) return null;
        for (SourceResult r : list) {
            if (name.equalsIgnoreCase(r.name)) return r;
        }
        return null;
    }

    private Label statValue(String value) {
        Label val = new Label(value);
        val.setStyle("-fx-font-size: 15px; -fx-font-weight: 700; -fx-text-fill: -sl-text;");
        return val;
    }

    private VBox statBlock(String label, Label valueLabel) {
        VBox item = new VBox(4);
        item.setAlignment(Pos.CENTER);
        item.setPrefWidth(100);
        Label lbl = new Label(label);
        lbl.setStyle("-fx-font-size: 11px; -fx-text-fill: -sl-text-faint;");
        item.getChildren().addAll(lbl, valueLabel);
        return item;
    }
}
