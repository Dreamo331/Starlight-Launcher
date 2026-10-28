package com.example.starlight.newui.page.settings.account;


import com.example.starlight.util.I18N;
import com.example.starlight.auth.AccountManager;
import com.example.starlight.auth.AccountManager.Account;
import com.example.starlight.gui.UIGeneralControlClass;
import com.example.starlight.loginmicrosoft.MinecraftAuthLauncherDeviceCode;
import com.example.starlight.loginmicrosoft.MinecraftAuthLauncherDeviceCode.DeviceCodeInfo;
import com.example.starlight.newui.ui.HoldButton;

import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.util.Duration;

/**
 * 微软登录面板：真实设备码 + 真实剩余时间圆环 + 复制 / 打开浏览器 / 长按取消。
 *
 * <p>对齐 {@code references/html/2.0.html} 的微软页，但把演示数据换成了真实流程：
 * 设备码来自 {@link MinecraftAuthLauncherDeviceCode#startDeviceCodeFlow()}，倒计时按响应里的
 * {@code expires_in} 走（默认 900 秒 = 15:00，≤5 分钟变黄、≤1 分钟变红），
 * 请求到设备码后立刻在后台轮询授权（{@link AccountManager#completeMicrosoftLogin}），
 * 用户在浏览器里一授权，页面这里就会收到结果并回到列表——所以不需要额外的「已完成登录」按钮。
 *
 * <p>「长按取消验证」只负责结束本面板：后台轮询线程无法安全中断，若它稍后才成功，
 * 账号仍会写入（用户在浏览器里确实授权过），此时只保证界面不再跳转。
 */
public final class MicrosoftLoginPane {

    /** 长按取消所需时长（对齐 HTML 的 1500ms） */
    private static final long HOLD_MS = 1500;

    /** 圆环半径 / 描边宽度（96×96 视区内画得下） */
    private static final double RING_RADIUS = 44;
    private static final double RING_STROKE = 7;

    private static final Color RING_NORMAL = Color.web("#3b82f6");
    private static final Color RING_WARN = Color.web("#f59e0b");
    private static final Color RING_DANGER = Color.web("#ef4444");

    private final AccountPageHost host;
    private final VBox root = new VBox();

    private final Label linkLabel = new Label(I18N.get("account.t0020"));
    private final Label codeLabel = new Label("--------");
    private final Label remainLabel = new Label("15:00");
    private final Label statusLabel = new Label();
    private final Button copyBtn = new Button(I18N.get("account.t0021"));
    private final Button openBtn = new Button(I18N.get("account.t0022"));
    private final Arc ringBg = new Arc(48, 48, RING_RADIUS, RING_RADIUS, 90, -360);
    private final Arc ringFill = new Arc(48, 48, RING_RADIUS, RING_RADIUS, 90, 0);

    private DeviceCodeInfo deviceCode;
    private int remainSeconds;
    private int totalSeconds = 900;
    private Timeline countdown;
    private boolean fetching;
    private boolean cancelled;

    public MicrosoftLoginPane(AccountPageHost host, Runnable onBack, Runnable onCancelToMethods) {
        this.host = host;
        root.getStyleClass().add("account-microsoft-pane");
        root.getChildren().add(AccountPanesUi.header(I18N.get("account.t0023"), onBack));

        // 圆环：底层整圆 + 上层按剩余比例收缩的弧，颜色随剩余时间变档
        ringBg.setType(ArcType.OPEN);
        ringBg.setFill(null);
        ringBg.setStroke(Color.web("#eef1f5"));
        ringBg.setStrokeWidth(RING_STROKE);
        ringBg.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        ringFill.setType(ArcType.OPEN);
        ringFill.setFill(null);
        ringFill.setStroke(RING_NORMAL);
        ringFill.setStrokeWidth(RING_STROKE);
        ringFill.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);

        remainLabel.getStyleClass().add("ring-time");
        Label remainHint = new Label(I18N.get("account.t0024"));
        remainHint.getStyleClass().add("ring-hint");
        VBox ringText = new VBox(1, remainLabel, remainHint);
        ringText.setAlignment(Pos.CENTER);
        ringText.setMouseTransparent(true);

        StackPane ring = new StackPane(ringBg, ringFill, ringText);
        ring.getStyleClass().add("ring-wrap");
        ring.setMinSize(96, 96);
        ring.setPrefSize(96, 96);
        ring.setMaxSize(96, 96);

        Label linkTitle = new Label(I18N.get("account.t0025"));
        linkTitle.getStyleClass().add("link-label");
        linkLabel.getStyleClass().add("login-link");
        linkLabel.setWrapText(true);
        VBox linkArea = new VBox(4, linkTitle, linkLabel);
        linkArea.setMinWidth(0);
        HBox.setHgrow(linkArea, Priority.ALWAYS);

        HBox top = new HBox(16, linkArea, ring);
        top.setAlignment(Pos.CENTER);
        top.getStyleClass().add("ms-top");

        codeLabel.getStyleClass().add("device-code");
        copyBtn.getStyleClass().add("copy-btn");
        copyBtn.setOnAction(e -> copyCode());
        HBox codeBox = new HBox(12, codeLabel, copyBtn);
        codeBox.setAlignment(Pos.CENTER_LEFT);
        codeBox.getStyleClass().add("device-code-box");
        HBox.setHgrow(codeLabel, Priority.ALWAYS);

        openBtn.getStyleClass().add("primary-btn");
        openBtn.setOnAction(e -> openBrowser());
        HoldButton cancel = new HoldButton(I18N.get("account.t0026"), HOLD_MS, () -> {
            cancelVerification();
            onCancelToMethods.run();
        });
        cancel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(cancel, Priority.ALWAYS);
        HBox actions = new HBox(10, cancel, openBtn);
        actions.setAlignment(Pos.CENTER);
        actions.getStyleClass().add("ms-actions");

        statusLabel.getStyleClass().add("ms-status");
        statusLabel.setWrapText(true);
        statusLabel.setVisible(false);
        statusLabel.setManaged(false);

        VBox body = new VBox(14, top, codeBox, statusLabel, actions);
        body.getStyleClass().add("ms-body");
        root.getChildren().add(body);

        setEnabled(false);
    }

    public Node node() {
        return root;
    }

    /** 切入本面板：没有进行中的设备码就去取一个新的，已有则继续显示（避免重复发起授权流程） */
    public void onShow() {
        if (deviceCode != null && remainSeconds > 0) {
            startCountdown();
            return;
        }
        if (fetching) return;
        cancelled = false;
        fetchDeviceCode();
    }

    /** 切出本面板：停掉倒计时（后台轮询继续，用户回来时还能接着显示） */
    public void onHide() {
        stopCountdown();
    }

    // 设备码流程 //

    private void fetchDeviceCode() {
        fetching = true;
        setStatus(I18N.get("account.t0027"), false);
        setEnabled(false);
        linkLabel.setText(I18N.get("account.t0028"));
        codeLabel.setText("--------");

        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            try {
                DeviceCodeInfo info = MinecraftAuthLauncherDeviceCode.startDeviceCodeFlow();
                Platform.runLater(() -> {
                    fetching = false;
                    deviceCode = info;
                    totalSeconds = Math.max(1, info.expiresIn);
                    remainSeconds = totalSeconds;
                    linkLabel.setText(info.verificationUri);
                    codeLabel.setText(info.userCode);
                    setEnabled(true);
                    updateRing();
                    startCountdown();
                    setStatus(info.browserOpened ? I18N.get("account.t0083") : I18N.get("account.t0084"), false);
                    startCompletionPoll(info);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    fetching = false;
                    setEnabled(false);
                    setStatus(I18N.get("account.p0094", ex.getMessage()), true);
                });
            }
        });
    }

    /**
     * 后台轮询授权结果：成功即写入账号并回到列表。
     *
     * <p>这就是 HTML 里「不需要点确认按钮」的等价实现——那边是演示，这里是真轮询。
     */
    private void startCompletionPoll(DeviceCodeInfo info) {
        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            try {
                Account account = AccountManager.completeMicrosoftLogin(info);
                Platform.runLater(() -> {
                    stopCountdown();
                    deviceCode = null;
                    if (cancelled) return;   // 用户已长按取消：界面不再跳转（账号已在总库里）
                    host.accountLoggedIn(I18N.get("account.p0095", account.name));
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    if (cancelled) return;
                    setStatus(I18N.get("mlp.r0001", ex.getMessage()), true);
                    host.toast(I18N.get("account.p0096", ex.getMessage()));
                });
            }
        });
    }

    private void startCountdown() {
        stopCountdown();
        remainSeconds = Math.min(remainSeconds, totalSeconds);
        countdown = new Timeline(new KeyFrame(Duration.seconds(1), e -> tick()));
        countdown.setCycleCount(Timeline.INDEFINITE);
        countdown.play();
    }

    private void stopCountdown() {
        if (countdown != null) {
            countdown.stop();
            countdown = null;
        }
    }

    private void tick() {
        remainSeconds--;
        if (remainSeconds <= 0) {
            remainSeconds = 0;
            stopCountdown();
            updateRing();
            setStatus(I18N.get("account.t0029"), true);
            host.toast(I18N.get("account.t0030"));
            return;
        }
        updateRing();
    }

    /** 圆环与剩余时间：按剩余比例收缩弧长，剩余 ≤5 分钟变黄、≤1 分钟变红 */
    private void updateRing() {
        double pct = totalSeconds <= 0 ? 0 : (double) remainSeconds / totalSeconds;
        ringFill.setLength(-360 * pct);
        Color color = remainSeconds <= 60 ? RING_DANGER
                : remainSeconds <= 300 ? RING_WARN : RING_NORMAL;
        ringFill.setStroke(color);
        remainLabel.setText(String.format("%d:%02d", remainSeconds / 60, remainSeconds % 60));
    }

    private void cancelVerification() {
        cancelled = true;
        stopCountdown();
        deviceCode = null;
        remainSeconds = 0;
    }

    // 小动作 //

    private void copyCode() {
        if (deviceCode == null) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(deviceCode.userCode);
        Clipboard.getSystemClipboard().setContent(content);
        copyBtn.setText(I18N.get("account.t0031"));
        copyBtn.getStyleClass().add("copied");
        PauseTransition restore = new PauseTransition(Duration.millis(1500));
        restore.setOnFinished(e -> {
            copyBtn.setText(I18N.get("account.t0032"));
            copyBtn.getStyleClass().remove("copied");
        });
        restore.play();
        host.toast(I18N.get("account.t0033"));
    }

    private void openBrowser() {
        if (deviceCode == null) return;
        if (!MinecraftAuthLauncherDeviceCode.openBrowser(deviceCode.verificationUri)) {
            host.toast(I18N.get("account.t0034"));
        }
    }

    private void setEnabled(boolean enabled) {
        copyBtn.setDisable(!enabled);
        openBtn.setDisable(!enabled);
    }

    private void setStatus(String message, boolean error) {
        statusLabel.setText(message == null ? "" : message);
        boolean show = message != null && !message.isBlank();
        statusLabel.setVisible(show);
        statusLabel.setManaged(show);
        statusLabel.getStyleClass().remove("error");
        if (error) statusLabel.getStyleClass().add("error");
    }
}