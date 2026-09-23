package com.example.starlight.newui.page.settings;

import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.ui.PageKit;

import javafx.scene.Node;
import javafx.scene.layout.VBox;

import com.example.starlight.util.ProxyConfig;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

import java.util.Map;

/**
 * 设置 → 代理页
 *
 * <p>三种代理方式（关闭代理／系统代理／HTTP 代理）只在「保存并应用」时落盘，
 * 保存后立即应用到启动器自身网络，并写入配置供游戏启动时注入 JVM 参数。
 *
 * <p>HTTP 主机／端口／认证输入框始终显示：未选择 HTTP 代理时整组置灰不可编辑，
 * 而不是整张卡片消失（卡片只剩标题会被当成界面出错）。
 */
public final class ProxySettingsPage {

    private final LauncherContext ctx;

    public ProxySettingsPage(LauncherContext ctx) {
        this.ctx = ctx;
    }

    public Node build() {
        VBox root = PageKit.settingsPage(ctx, "代理");
        Map<String, String> cfg = ctx.config();
        ProxyConfig.ProxyType curMode = ProxyConfig.type(cfg);

        // ===== 模式选择（单选）=====
        RadioButton directRadio = new RadioButton("关闭代理（直连）");
        RadioButton systemRadio = new RadioButton("系统代理");
        RadioButton httpRadio = new RadioButton("HTTP 代理");
        ToggleGroup modeGroup = new ToggleGroup();
        for (RadioButton rb : new RadioButton[]{directRadio, systemRadio, httpRadio}) {
            rb.setToggleGroup(modeGroup);
            rb.setStyle("-fx-font-size: 13px; -fx-font-weight: 600;");
        }
        directRadio.setSelected(curMode == ProxyConfig.ProxyType.DIRECT);
        systemRadio.setSelected(curMode == ProxyConfig.ProxyType.SYSTEM);
        httpRadio.setSelected(curMode == ProxyConfig.ProxyType.HTTP);

        HBox modeRow = new HBox(32,
                modeOption(directRadio, "不使用任何代理"),
                modeOption(systemRadio, "跟随操作系统代理设置"),
                modeOption(httpRadio, "手动填写主机与端口"));
        modeRow.setAlignment(Pos.TOP_LEFT);

        // ===== HTTP 参数区（网格对齐）=====
        TextField hostField = textInput(cfg.getOrDefault(ProxyConfig.KEY_HOST, ""), "如 127.0.0.1");
        TextField portField = textInput(cfg.getOrDefault(ProxyConfig.KEY_PORT, ""), "如 7890");
        portField.getStyleClass().add("input-field-sm");
        TextField userField = textInput(cfg.getOrDefault(ProxyConfig.KEY_USER, ""), "可选");
        PasswordField passField = new PasswordField();
        passField.setText(cfg.getOrDefault(ProxyConfig.KEY_PASS, ""));
        passField.getStyleClass().add("input-field");
        passField.setPromptText("可选");

        GridPane httpGrid = new GridPane();
        httpGrid.setHgap(14);
        httpGrid.setVgap(10);
        httpGrid.add(fieldLabel("主机"), 0, 0);
        httpGrid.add(hostField, 1, 0);
        httpGrid.add(fieldLabel("端口"), 2, 0);
        httpGrid.add(portField, 3, 0);
        httpGrid.add(fieldLabel("用户名"), 0, 1);
        httpGrid.add(userField, 1, 1);
        httpGrid.add(fieldLabel("密码"), 2, 1);
        httpGrid.add(passField, 3, 1);
        // 未选择 HTTP 代理时整组置灰（值保留，切回来还能看到上次填的地址）
        httpGrid.setDisable(!httpRadio.isSelected());

        // ===== 当前状态徽标（三态）+ 未保存提示 =====
        Label statusBadge = new Label();
        // 徽标是固定文案，不许被压缩（否则会变成「当前: 已...」）
        statusBadge.setMinWidth(Region.USE_PREF_SIZE);
        Label statusDesc = new Label();
        statusDesc.getStyleClass().add("settings-card-desc");
        statusDesc.setWrapText(true);
        Label dirtyChip = new Label("未保存");
        dirtyChip.setStyle("-fx-background-color: #fef3c7; -fx-text-fill: #b45309;"
                + " -fx-padding: 4 12; -fx-background-radius: 999; -fx-font-size: 12px; -fx-font-weight: 600;");
        dirtyChip.setMinWidth(Region.USE_PREF_SIZE);
        dirtyChip.setVisible(false);
        dirtyChip.setManaged(false);

        // 徽标始终描述「已保存」的配置；界面上改过还没保存时另挂「未保存」提示，
        // 避免用户以为改一下单选就立刻生效了
        Runnable syncStatus = () -> {
            Map<String, String> saved = ctx.config();
            ProxyConfig.ProxyType mode = ProxyConfig.type(saved);
            String bg, fg, text;
            if (mode == ProxyConfig.ProxyType.HTTP) {
                bg = "#dbeafe"; fg = "#1d4ed8"; text = "HTTP 代理";
            } else if (mode == ProxyConfig.ProxyType.DIRECT) {
                bg = "#e5e7eb"; fg = "#374151"; text = "已关闭";
            } else {
                bg = "#e0e7ff"; fg = "#4338ca"; text = "系统代理";
            }
            statusBadge.setText("当前: " + text);
            statusBadge.setStyle("-fx-background-color: " + bg + ";"
                    + " -fx-text-fill: " + fg + ";"
                    + " -fx-padding: 4 12; -fx-background-radius: 999; -fx-font-size: 12px; -fx-font-weight: 600;");
            statusDesc.setText(describe(mode, saved));
        };
        Runnable syncDirty = () -> {
            Map<String, String> saved = ctx.config();
            boolean dirty = selectedMode(directRadio, systemRadio, httpRadio) != ProxyConfig.type(saved)
                    || !trim(hostField.getText()).equals(trim(saved.get(ProxyConfig.KEY_HOST)))
                    || !trim(portField.getText()).equals(trim(saved.get(ProxyConfig.KEY_PORT)))
                    || !trim(userField.getText()).equals(trim(saved.get(ProxyConfig.KEY_USER)))
                    || !nullToEmpty(passField.getText()).equals(nullToEmpty(saved.get(ProxyConfig.KEY_PASS)));
            dirtyChip.setVisible(dirty);
            dirtyChip.setManaged(dirty);
        };
        syncStatus.run();
        syncDirty.run();

        // 切换代理模式：HTTP 输入框联动可用性 + 刷新「未保存」提示
        for (RadioButton rb : new RadioButton[]{directRadio, systemRadio, httpRadio}) {
            rb.selectedProperty().addListener((o, a, b) -> {
                httpGrid.setDisable(!httpRadio.isSelected());
                syncDirty.run();
            });
        }
        for (TextField f : new TextField[]{hostField, portField, userField, passField}) {
            f.textProperty().addListener((o, a, b) -> syncDirty.run());
        }

        // ===== 保存按钮 =====
        Button saveBtn = AppIcons.button("save", "保存并应用");
        saveBtn.getStyleClass().add("btn-primary");
        saveBtn.setOnAction(e -> {
            if (httpRadio.isSelected()) {
                String host = trim(hostField.getText());
                String port = trim(portField.getText());
                if (host.isEmpty()) {
                    ctx.toast("请填写 HTTP 代理的主机地址");
                    return;
                }
                if (port.isEmpty()) {
                    ctx.toast("请填写 HTTP 代理的端口");
                    return;
                }
                int p = PageKit.parseIntSafe(port, -1);
                if (p < 1 || p > 65535) {
                    ctx.toast("端口需为 1-65535 之间的数字");
                    return;
                }
            }
            ProxyConfig.ProxyType mode = selectedMode(directRadio, systemRadio, httpRadio);
            ctx.config().put(ProxyConfig.KEY_TYPE, mode.name());
            ctx.config().put(ProxyConfig.KEY_HOST, trim(hostField.getText()));
            ctx.config().put(ProxyConfig.KEY_PORT, trim(portField.getText()));
            ctx.config().put(ProxyConfig.KEY_USER, trim(userField.getText()));
            ctx.config().put(ProxyConfig.KEY_PASS, nullToEmpty(passField.getText()));
            ctx.saveConfig();
            ProxyConfig.applyToSystem(ctx.config());
            syncStatus.run();
            syncDirty.run();
            ctx.toast("代理设置已保存并应用: " + modeTitle(mode));
        });

        // ===== 组装：模式选择 / HTTP 配置 / 应用 各自独立卡片 =====
        root.getChildren().add(PageKit.settingsCardStacked(
                "代理模式", "选择代理方式：关闭代理（直连）/ 系统代理 / HTTP 代理", modeRow));

        VBox httpBody = new VBox(8, httpGrid,
                PageKit.hintLabel("用户名与密码留空表示该代理不需要认证。"));
        root.getChildren().add(PageKit.settingsCardStacked(
                "HTTP 代理配置", "填写代理服务器地址与端口；未选择 HTTP 代理时不可编辑", httpBody));

        HBox actionRow = new HBox(14, saveBtn, statusBadge, dirtyChip, statusDesc);
        actionRow.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(PageKit.settingsCardStacked(
                "应用", "保存后立即生效；游戏启动时自动携带对应的 JVM 代理参数", actionRow));

        root.getChildren().add(PageKit.hintLabel(
                "提示：关闭代理为直连网络；系统代理使用操作系统代理设置；HTTP 代理需填写主机与端口，认证信息可选。"));

        return root;
    }

    /**
     * 单个代理方式选项：圆点 + 标题，下面跟一行灰色说明（点击说明文字等同于点圆点）。
     */
    private static VBox modeOption(RadioButton radio, String desc) {
        Label descLabel = new Label(desc);
        descLabel.getStyleClass().add("settings-card-desc");
        VBox box = new VBox(2, radio, descLabel);
        // 说明文字与标题左对齐（让出圆点宽度）
        VBox.setMargin(descLabel, new Insets(0, 0, 0, 26));
        box.setAlignment(Pos.TOP_LEFT);
        box.setCursor(Cursor.HAND);
        box.setOnMouseClicked(e -> radio.setSelected(true));
        return box;
    }

    private static TextField textInput(String value, String prompt) {
        TextField field = new TextField(value == null ? "" : value);
        field.getStyleClass().add("input-field");
        field.setPromptText(prompt);
        return field;
    }

    /** 表单标签：深色主题用浅灰，避免与背景混淆 */
    private Label fieldLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 13px; -fx-text-fill: "
                + ("dark".equals(ctx.currentTheme()) ? "#cbd5e1" : "#4b5563") + ";");
        return label;
    }

    private static ProxyConfig.ProxyType selectedMode(RadioButton direct, RadioButton system, RadioButton http) {
        if (direct.isSelected()) return ProxyConfig.ProxyType.DIRECT;
        if (http.isSelected()) return ProxyConfig.ProxyType.HTTP;
        return ProxyConfig.ProxyType.SYSTEM;
    }

    private static String modeTitle(ProxyConfig.ProxyType mode) {
        return switch (mode) {
            case DIRECT -> "关闭代理（直连）";
            case HTTP -> "HTTP 代理";
            default -> "系统代理";
        };
    }

    /** 已保存配置的一句话状态说明（HTTP 模式下显示实际的主机:端口） */
    private static String describe(ProxyConfig.ProxyType mode, Map<String, String> cfg) {
        if (mode == ProxyConfig.ProxyType.HTTP) {
            return ProxyConfig.isHttpValid(cfg)
                    ? ProxyConfig.host(cfg) + ":" + ProxyConfig.port(cfg)
                    : "尚未填写有效的主机与端口";
        }
        if (mode == ProxyConfig.ProxyType.DIRECT) return "直连网络，不使用任何代理";
        return "使用操作系统代理设置";
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
