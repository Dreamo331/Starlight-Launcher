package com.example.starlight.newui.page.settings;

import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.ui.PageKit;

import javafx.scene.Node;
import javafx.scene.layout.VBox;

import com.example.starlight.slan.SLanConfig;
import com.example.starlight.slan.SLanManager;
import com.example.starlight.slan.SLanServerChecker;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/** 
设置 → 联机设置页（中继服务器 + 模块状态）
（从 LauncherView 抽离，逻辑与样式均未改动） */
public final class MultiplayerSettingsPage {

    private final LauncherContext ctx;

    public MultiplayerSettingsPage(LauncherContext ctx) {
        this.ctx = ctx;
    }

    public Node build() {
        VBox root = PageKit.settingsPage(ctx, "联机设置");

        // ===== 中继服务器地址（读写 Starlight-Launcher/multiplayer.json）=====
        TextField relayServerField = new TextField(SLanConfig.relayServer());
        relayServerField.getStyleClass().add("input-field");
        relayServerField.setPromptText("如 ws://your.server:8080（可省略 ws://）");
        relayServerField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(relayServerField, Priority.ALWAYS);

        Label relayEffectiveLabel = new Label("当前生效: " + SLanConfig.relayServer());
        relayEffectiveLabel.setWrapText(true);
        relayEffectiveLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-faint;");

        // ===== 检测结果状态：与按钮同一行，宽度自适应换行 =====
        Label checkStatusLabel = new Label("尚未检测。");
        checkStatusLabel.setWrapText(true);
        checkStatusLabel.setMaxWidth(Double.MAX_VALUE);
        checkStatusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-faint;");
        HBox.setHgrow(checkStatusLabel, Priority.ALWAYS);

        Button saveRelayBtn = PageKit.cardActionButton("保存");
        Button checkRelayBtn = PageKit.cardActionButton("检测服务器");
        AppIcons.apply(checkRelayBtn, "search", 15, null);

        saveRelayBtn.setOnAction(e -> {
            String val = relayServerField.getText() == null ? "" : relayServerField.getText().trim();
            if (val.isEmpty()) {
                ctx.toast("请填写中继服务器地址，如 ws://your.server:8080");
                return;
            }
            try {
                SLanConfig.update(val, null);
                String effective = SLanConfig.relayServer(); // 未带协议头时自动补 ws://
                relayServerField.setText(effective);
                relayEffectiveLabel.setText("当前生效: " + effective);
                checkStatusLabel.setText("配置已更新，请重新检测");
                checkStatusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-faint;");
                ctx.toast("联机中继服务器已保存: " + effective);
            } catch (Exception ex) {
                ctx.toast("保存失败: " + ex.getMessage());
            }
        });

        checkRelayBtn.setOnAction(e -> {
            // 优先检测输入框里的值（未保存也能先测），为空则用已保存配置
            String raw = relayServerField.getText() == null ? "" : relayServerField.getText().trim();
            String target = raw.isEmpty() ? SLanConfig.relayServer() : SLanConfig.normalizeRelayServer(raw);
            checkRelayBtn.setDisable(true);
            checkStatusLabel.setText("检测中... " + target);
            checkStatusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #f59e0b;");
            SLanServerChecker.check(target).whenComplete((result, err) -> Platform.runLater(() -> {
                checkRelayBtn.setDisable(false);
                if (err != null || result == null) {
                    String msg = err == null ? "未知错误" : (err.getCause() != null ? err.getCause().getMessage() : err.getMessage());
                    checkStatusLabel.setText("检测失败: " + msg);
                    checkStatusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #ef4444;");
                    return;
                }
                checkStatusLabel.setText(result.message());
                checkStatusLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: 600; -fx-text-fill: "
                        + (result.protocolMatched() ? "#10b981" : (result.reachable() ? "#f59e0b" : "#ef4444")) + ";");
                if (result.protocolMatched()) {
                    ctx.toast("联机服务器可用: " + target);
                } else if (result.reachable()) {
                    ctx.toast("端口可达，但未收到联机协议响应");
                } else {
                    ctx.toast("联机服务器不可用");
                }
            }));
        });

        HBox addressRow = new HBox(10, relayServerField, saveRelayBtn);
        addressRow.setAlignment(Pos.CENTER_LEFT);
        HBox actionRow = new HBox(10, checkRelayBtn, checkStatusLabel);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        Label relayPathLabel = new Label("配置文件: " + SLanConfig.configPath().toAbsolutePath());
        relayPathLabel.setWrapText(true);
        relayPathLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #94a3b8;");

        VBox relayServerBody = new VBox(10, addressRow, relayEffectiveLabel, actionRow, relayPathLabel);
        relayServerBody.setPrefWidth(PageKit.SETTINGS_CONTROL_WIDTH);
        relayServerBody.setMaxWidth(PageKit.SETTINGS_CONTROL_WIDTH);
        relayServerBody.setMinWidth(0);
        root.getChildren().add(PageKit.settingsCard("联机中继服务器",
                "SLAN 联机使用的房间中继服务器（自建服务器填自己的地址，可省略 ws:// 前缀）；保存后重新建房/加房生效",
                relayServerBody));

        // ===== 联机模块状态（SL-MinecraftLAN.exe 是否已安装）=====
        boolean moduleInstalled;
        String modulePath;
        try {
            SLanManager probeManager = new SLanManager();
            moduleInstalled = probeManager.isAvailable();
            modulePath = probeManager.getExecutablePath().toString();
        } catch (Exception ex) {
            moduleInstalled = false;
            modulePath = "无法定位模块路径: " + ex.getMessage();
        }
        Label moduleStatusLabel = new Label(moduleInstalled ? "已安装" : "未安装");
        moduleStatusLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: 600; -fx-text-fill: "
                + (moduleInstalled ? "#10b981" : "#f59e0b") + ";");
        Label modulePathLabel = new Label(moduleInstalled
                ? modulePath
                : "首次使用请到「联机 → SLAN 联机」页点击「下载安装」");
        modulePathLabel.setWrapText(true);
        modulePathLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #94a3b8;");
        VBox moduleBody = new VBox(6, moduleStatusLabel, modulePathLabel);
        moduleBody.setPrefWidth(PageKit.SETTINGS_CONTROL_WIDTH);
        moduleBody.setMaxWidth(PageKit.SETTINGS_CONTROL_WIDTH);
        moduleBody.setMinWidth(0);
        root.getChildren().add(PageKit.settingsCard("联机模块",
                "SL-MinecraftLAN 联机模块（负责与中继服务器通信、转发 Minecraft 局域网数据）", moduleBody));

        Label hint = new Label("提示：检测会与中继服务器建立一次 WebSocket 连接并发送协议探测帧，"
                + "成功即说明服务器可用且协议匹配；检测不会创建房间、不影响正在进行的联机。");
        hint.setWrapText(true);
        hint.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-dim;");
        root.getChildren().add(hint);

        return root;
    }
}
