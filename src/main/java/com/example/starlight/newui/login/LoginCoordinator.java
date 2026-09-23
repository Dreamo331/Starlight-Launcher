package com.example.starlight.newui.login;

import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.auth.AccountManager;
import com.example.starlight.auth.AccountManager.Account;
import com.example.starlight.loginmicrosoft.MinecraftAuthLauncherDeviceCode;
import com.example.starlight.loginmicrosoft.MinecraftAuthLauncherDeviceCode.DeviceCodeInfo;
import com.example.starlight.newui.LauncherContext;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import com.example.starlight.gui.UIGeneralControlClass;
import javafx.scene.layout.VBox;

/**
 * 登录流程协调器：微软登录（设备码流 + 集成式弹窗）/ 第三方外置登录 / 离线登录。
 *
 * <p>从 LauncherView 抽离（方法体逐字搬运，逻辑、样式类与内联样式均未改动）：
 * 设备码状态（currentDeviceCode / deviceCodeLoginActive）随流程迁入本类，
 * 主壳只保留三个 {@code login} 委派入口。
 */
public final class LoginCoordinator {

    private final LauncherContext host;

    /** 微软登录（设备码流）：当前待授权的设备码，null 表示未获取或已结束 */
    private DeviceCodeInfo currentDeviceCode;
    /** 微软登录弹窗打开标志：防止重复打开弹窗 */
    private boolean deviceCodeLoginActive = false;

    public LoginCoordinator(LauncherContext host) {
        this.host = host;
    }

    /**
     * 微软登录（设备码流 + 集成式弹窗）：
     * 弹窗内展示授权链接与验证码并自动打开浏览器；自动打开失败时（默认浏览器关联缺失/
     * 被安全软件拦截等）用户可点击「打开浏览器」或手动复制链接完成授权，
     * 授权完成后点击「已完成登录」轮询并写入账号。
     */
    public void microsoftLogin() {
        if (deviceCodeLoginActive) return; // 弹窗已打开时忽略重复点击
        deviceCodeLoginActive = true;
        currentDeviceCode = null;

        Label statusLabel = new Label("正在获取设备码...");
        statusLabel.getStyleClass().add("modal-text");
        statusLabel.setWrapText(true);

        Label urlLabel = new Label();
        urlLabel.getStyleClass().add("modal-text");
        urlLabel.setWrapText(true);

        // 「复制链接」：浏览器未自动打开时，用户可一键复制下方地址手动访问
        Button copyUrlBtn = AppIcons.button("copy", "复制链接");
        copyUrlBtn.getStyleClass().add("btn-primary");
        // 不写内联 min-width:0（会覆盖按钮宽度锁，挤压时变成「复…」）
        copyUrlBtn.setStyle("-fx-padding: 4 10; -fx-font-size: 11px;");
        copyUrlBtn.setMinWidth(Region.USE_PREF_SIZE);
        copyUrlBtn.setDisable(true);
        copyUrlBtn.setOnAction(e -> {
            DeviceCodeInfo info = currentDeviceCode;
            if (info == null) return;
            ClipboardContent cc = new ClipboardContent();
            cc.putString(info.verificationUri);
            Clipboard.getSystemClipboard().setContent(cc);
            host.ui().toast("链接已复制到剪贴板");
        });

        // 授权地址显示在提示文案下方（“复制下方地址”），与复制按钮同行
        HBox urlRow = new HBox(8);
        urlRow.setAlignment(Pos.CENTER_LEFT);
        urlRow.getChildren().addAll(urlLabel, copyUrlBtn);
        urlRow.setVisible(false);
        urlRow.setManaged(false);

        Label codeLabel = new Label();
        codeLabel.setStyle("-fx-font-size: 26px; -fx-font-weight: bold; -fx-text-fill: #2563eb;");
        codeLabel.setVisible(false);
        codeLabel.setManaged(false);

        Label hintLabel = new Label();
        hintLabel.getStyleClass().add("modal-text");
        hintLabel.setWrapText(true);
        hintLabel.setVisible(false);
        hintLabel.setManaged(false);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        HBox btns = new HBox(10);
        btns.setAlignment(Pos.CENTER_RIGHT);
        Button openBtn = AppIcons.button("external-link", "打开浏览器");
        openBtn.getStyleClass().add("btn-primary");
        openBtn.setDisable(true);
        openBtn.setOnAction(e -> {
            DeviceCodeInfo info = currentDeviceCode;
            if (info != null && !MinecraftAuthLauncherDeviceCode.openBrowser(info.verificationUri)) {
                host.ui().toast("自动打开失败，请复制链接手动访问");
            }
        });
        Button completeBtn = AppIcons.button("check", "已完成登录");
        completeBtn.getStyleClass().add("modal-btn-ok");
        // 与同排「打开浏览器」(.btn-primary：6 16 / 12px) 对齐，否则这两个弹窗底栏按钮会比它高一截
        completeBtn.setStyle("-fx-padding: 6 16; -fx-font-size: 12px;");
        completeBtn.setDisable(true);
        completeBtn.setOnAction(e -> completeDeviceCodeLogin(completeBtn, openBtn, statusLabel));
        Button cancelBtn = AppIcons.button("close", "取消");
        cancelBtn.getStyleClass().add("modal-btn-cancel");
        cancelBtn.setStyle("-fx-padding: 6 16; -fx-font-size: 12px;");
        cancelBtn.setOnAction(e -> {
            deviceCodeLoginActive = false;
            currentDeviceCode = null;
            host.ui().closeModal();
        });
        btns.getChildren().addAll(openBtn, completeBtn, cancelBtn);

        VBox body = new VBox(12);
        body.setPadding(new Insets(10, 4, 0, 4));
        // 顺序：状态 -> 提示（未自动打开时提示复制下方地址）-> 授权地址 -> 验证码
        body.getChildren().addAll(statusLabel, hintLabel, urlRow, codeLabel, spacer, btns);
        // 不显示 关闭按钮：统一由「取消」关闭并重置登录标志，防止关闭后无法再次打开弹窗
        host.ui().modal("微软登录", body, 480, 340, false);

        // 后台获取设备码（不阻塞 UI；获取成功前「已完成登录」不可点）
        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            try {
                DeviceCodeInfo info = MinecraftAuthLauncherDeviceCode.startDeviceCodeFlow();
                currentDeviceCode = info;
                Platform.runLater(() -> {
                    statusLabel.setText("请在浏览器中打开以下链接，输入验证码完成授权：");
                    urlLabel.setText(info.verificationUri);
                    urlRow.setVisible(true);
                    urlRow.setManaged(true);
                    copyUrlBtn.setDisable(false);
                    codeLabel.setText(info.userCode);
                    codeLabel.setVisible(true);
                    codeLabel.setManaged(true);
                    hintLabel.setText("验证码已复制到剪贴板。\n如果自动打开浏览器失败，请复制下方地址前往浏览器中访问，完成授权后点击「已完成登录」");
                    hintLabel.setVisible(true);
                    hintLabel.setManaged(true);
                    openBtn.setDisable(false);
                    completeBtn.setDisable(false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    deviceCodeLoginActive = false;
                    currentDeviceCode = null;
                    statusLabel.setText("获取设备码失败: " + ex.getMessage());
                    AppIcons.setText(cancelBtn, "close", "关闭");
                });
            }
        });
    }

    /** 用户确认已在浏览器完成授权：后台轮询并完成认证，成功后写入账号并刷新界面 */
    private void completeDeviceCodeLogin(Button completeBtn, Button openBtn, Label statusLabel) {
        DeviceCodeInfo info = currentDeviceCode;
        if (info == null) return;
        completeBtn.setDisable(true);
        completeBtn.setText("登录中...");
        openBtn.setDisable(true);
        statusLabel.setText("正在等待微软授权并完成登录（通常几秒）...");
        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            try {
                Account account = AccountManager.completeMicrosoftLogin(info);
                Platform.runLater(() -> {
                    deviceCodeLoginActive = false;
                    currentDeviceCode = null;
                    host.ui().closeModal();
                    host.ui().toast("微软登录成功: " + account.name);
                    host.loadConfig();
                    host.refreshAccountPages();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    completeBtn.setDisable(false);
                    AppIcons.setText(completeBtn, "check", "已完成登录");
                    openBtn.setDisable(false);
                    statusLabel.setText("登录失败: " + e.getMessage());
                });
            }
        });
    }

    /** 第三方登录（外置登录）：输入认证服务器地址 + 账号 + 密码，异步认证（参考 HMCL AuthlibInjector 外置登录） */
    public void thirdPartyLogin() {
        VBox body = new VBox(12);
        body.setPadding(new Insets(10, 4, 0, 4));

        Label tip = new Label("输入第三方认证服务器（皮肤站）地址与账号信息，支持 LittleSkin、Blessing Skin 等 Authlib-Injector 皮肤站");
        tip.getStyleClass().add("modal-text");
        tip.setWrapText(true);

        TextField serverField = new TextField();
        serverField.setPromptText("认证服务器地址，如 https://littleskin.cn 或 https://littleskin.cn/api/yggdrasil");
        serverField.getStyleClass().add("input-field");

        TextField userField = new TextField();
        userField.setPromptText("邮箱 / 用户名");
        userField.getStyleClass().add("input-field");

        PasswordField passField = new PasswordField();
        passField.setPromptText("密码");
        passField.getStyleClass().add("input-field");

        Label statusLabel = new Label();
        statusLabel.getStyleClass().add("modal-text");
        statusLabel.setWrapText(true);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        HBox btns = new HBox(10);
        btns.setAlignment(Pos.CENTER_RIGHT);
        Button cancelBtn = AppIcons.button("close", "取消");
        cancelBtn.getStyleClass().add("modal-btn-cancel");
        cancelBtn.setOnAction(e -> host.ui().closeModal());
        Button okBtn = AppIcons.button("user", "登录");
        okBtn.getStyleClass().add("modal-btn-ok");
        okBtn.setDisable(true);
        Runnable validate = () -> okBtn.setDisable(
                serverField.getText() == null || serverField.getText().trim().isEmpty()
                        || userField.getText() == null || userField.getText().trim().isEmpty()
                        || passField.getText() == null || passField.getText().isEmpty());
        serverField.textProperty().addListener((o, a, b) -> validate.run());
        userField.textProperty().addListener((o, a, b) -> validate.run());
        passField.textProperty().addListener((o, a, b) -> validate.run());
        okBtn.setOnAction(e -> {
            okBtn.setDisable(true);
            okBtn.setText("登录中...");
            cancelBtn.setDisable(true);
            statusLabel.setText("正在向认证服务器请求登录...");
            String server = serverField.getText().trim();
            String user = userField.getText().trim();
            String pass = passField.getText();
            UIGeneralControlClass.loginThirdPartyAsync(server, user, pass, new UIGeneralControlClass.ResultCallback<Account>() {
                @Override
                public void onSuccess(Account account) {
                    Platform.runLater(() -> {
                        host.ui().closeModal();
                        host.ui().toast("第三方登录成功: " + account.name);
                        host.loadConfig();
                        host.refreshAccountPages();
                    });
                }

                @Override
                public void onError(String error) {
                    Platform.runLater(() -> {
                        okBtn.setDisable(false);
                        AppIcons.setText(okBtn, "user", "登录");
                        cancelBtn.setDisable(false);
                        statusLabel.setText(error);
                    });
                }
            });
        });
        btns.getChildren().addAll(cancelBtn, okBtn);

        body.getChildren().addAll(tip, serverField, userField, passField, statusLabel, spacer, btns);
        host.ui().modal("第三方登录", body, 480, 340);
    }

    public void offlineLogin() {
        host.ui().input("离线登录", "输入玩家名", "玩家名", name -> {
            AccountManager.loginOffline(name);
            host.ui().toast("离线登录成功: " + name);
            host.loadConfig();
            host.refreshAccountPages();
        });
    }
}


