package com.example.starlight.newui.page.settings;


import com.example.starlight.util.I18N;
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
        VBox root = PageKit.settingsPage(ctx, I18N.get("mpsettings.t0001"));

        // 中继服务器地址（读写 Starlight-Launcher/multiplayer.json） //
        TextField relayServerField = new TextField(SLanConfig.relayServer());
        relayServerField.getStyleClass().add("input-field");
        relayServerField.setPromptText(I18N.get("mpsettings.t0002"));
        relayServerField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(relayServerField, Priority.ALWAYS);

        Label relayEffectiveLabel = new Label(I18N.get("mpsettings.k0001", SLanConfig.relayServer()));
        relayEffectiveLabel.setWrapText(true);
        // 换行标签必须有最小宽度：默认最小宽=整段文本宽，放在 HBox 右侧被挤压后
        // 会变成一列几个字符（英文文案变长后尤其明显，表现为一条竖排文字）
        relayEffectiveLabel.setMinWidth(PageKit.SETTINGS_CONTROL_WIDTH * 0.5);
        relayEffectiveLabel.setMaxWidth(PageKit.SETTINGS_CONTROL_WIDTH);
        relayEffectiveLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-faint;");

        // 检测结果状态：与按钮同一行，宽度自适应换行 //
        Label checkStatusLabel = new Label(I18N.get("mpsettings.t0003"));
        checkStatusLabel.setWrapText(true);
        // 最小宽度：避免在 HBox 里被压缩成一列竖排文字
        checkStatusLabel.setMinWidth(140);
        checkStatusLabel.setMaxWidth(Double.MAX_VALUE);
        checkStatusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-faint;");
        HBox.setHgrow(checkStatusLabel, Priority.ALWAYS);

        Button saveRelayBtn = PageKit.cardActionButton(I18N.get("mpsettings.t0004"));
        Button checkRelayBtn = PageKit.cardActionButton(I18N.get("mpsettings.t0005"));
        AppIcons.apply(checkRelayBtn, "search", 15, null);

        saveRelayBtn.setOnAction(e -> {
            String val = relayServerField.getText() == null ? "" : relayServerField.getText().trim();
            if (val.isEmpty()) {
                ctx.toast(I18N.get("mpsettings.t0006"));
                return;
            }
            try {
                SLanConfig.update(val, null);
                String effective = SLanConfig.relayServer(); // 未带协议头时自动补 ws://
                relayServerField.setText(effective);
                relayEffectiveLabel.setText(I18N.get("mpsettings.k0002", effective));
                checkStatusLabel.setText(I18N.get("mpsettings.t0007"));
                checkStatusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-faint;");
                ctx.toast(I18N.get("mpsettings.k0003", effective));
            } catch (Exception ex) {
                ctx.toast(I18N.get("mpsettings.k0004", ex.getMessage()));
            }
        });

        checkRelayBtn.setOnAction(e -> {
            // 优先检测输入框里的值（未保存也能先测），为空则用已保存配置
            String raw = relayServerField.getText() == null ? "" : relayServerField.getText().trim();
            String target = raw.isEmpty() ? SLanConfig.relayServer() : SLanConfig.normalizeRelayServer(raw);
            checkRelayBtn.setDisable(true);
            checkStatusLabel.setText(I18N.get("mpsettings.k0005", target));
            checkStatusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #f59e0b;");
            SLanServerChecker.check(target).whenComplete((result, err) -> Platform.runLater(() -> {
                checkRelayBtn.setDisable(false);
                if (err != null || result == null) {
                    String msg = err == null ? I18N.get("mpsettings.t0008") : (err.getCause() != null ? err.getCause().getMessage() : err.getMessage());
                    checkStatusLabel.setText(I18N.get("mpsettings.k0006", msg));
                    checkStatusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #ef4444;");
                    return;
                }
                checkStatusLabel.setText(result.message());
                checkStatusLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: 600; -fx-text-fill: "
                        + (result.protocolMatched() ? "#10b981" : (result.reachable() ? "#f59e0b" : "#ef4444")) + ";");
                if (result.protocolMatched()) {
                    ctx.toast(I18N.get("mpsettings.k0007", target));
                } else if (result.reachable()) {
                    ctx.toast(I18N.get("mpsettings.t0009"));
                } else {
                    ctx.toast(I18N.get("mpsettings.t0010"));
                }
            }));
        });

        HBox addressRow = new HBox(10, relayServerField, saveRelayBtn);
        addressRow.setAlignment(Pos.CENTER_LEFT);
        HBox actionRow = new HBox(10, checkRelayBtn, checkStatusLabel);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        Label relayPathLabel = new Label(I18N.get("mpsettings.k0008", SLanConfig.configPath().toAbsolutePath()));
        relayPathLabel.setWrapText(true);
        relayPathLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #94a3b8;");

        VBox relayServerBody = new VBox(10, addressRow, relayEffectiveLabel, actionRow, relayPathLabel);
        relayServerBody.setPrefWidth(PageKit.SETTINGS_CONTROL_WIDTH);
        relayServerBody.setMaxWidth(PageKit.SETTINGS_CONTROL_WIDTH);
        relayServerBody.setMinWidth(0);
        root.getChildren().add(PageKit.settingsCard(I18N.get("mpsettings.t0011"),
                I18N.get("mpsettings.t0012"),
                relayServerBody));

        // 联机模块状态（SL-MinecraftLAN.exe 是否已安装） //
        boolean moduleInstalled;
        String modulePath;
        try {
            SLanManager probeManager = new SLanManager();
            moduleInstalled = probeManager.isAvailable();
            modulePath = probeManager.getExecutablePath().toString();
        } catch (Exception ex) {
            moduleInstalled = false;
            modulePath = I18N.get("mpsettings.k0009", ex.getMessage());
        }
        Label moduleStatusLabel = new Label(moduleInstalled ? I18N.get("mpsettings.t0013") : I18N.get("mpsettings.t0014"));
        moduleStatusLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: 600; -fx-text-fill: "
                + (moduleInstalled ? "#10b981" : "#f59e0b") + ";");
        Label modulePathLabel = new Label(moduleInstalled
                ? modulePath
                : I18N.get("mpsettings.t0015"));
        modulePathLabel.setWrapText(true);
        modulePathLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #94a3b8;");
        VBox moduleBody = new VBox(6, moduleStatusLabel, modulePathLabel);
        if (!moduleInstalled) {
            // 未安装：跳转到「联机 → SLAN 联机」页完成下载安装（安装流程由该页负责，设置页只做导航）
            Button goInstallBtn = PageKit.cardActionButton(I18N.get("mpsettings.t0016"));
            goInstallBtn.setOnAction(e -> ctx.switchToPage("multiplayer"));
            moduleBody.getChildren().add(goInstallBtn);
        }
        moduleBody.setPrefWidth(PageKit.SETTINGS_CONTROL_WIDTH);
        moduleBody.setMaxWidth(PageKit.SETTINGS_CONTROL_WIDTH);
        moduleBody.setMinWidth(0);
        root.getChildren().add(PageKit.settingsCard(I18N.get("mpsettings.t0017"),
                I18N.get("mpsettings.r0001"), moduleBody));

        Label hint = new Label(I18N.get("mpsettings.r0002"));
        hint.setWrapText(true);
        hint.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-dim;");
        root.getChildren().add(hint);

        return root;
    }
}
