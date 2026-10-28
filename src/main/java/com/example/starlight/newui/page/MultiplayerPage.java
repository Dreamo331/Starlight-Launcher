package com.example.starlight.newui.page;


import com.example.starlight.util.I18N;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.ui.InertiaScrollSupport;
import com.example.starlight.newui.ui.PageKit;
import com.example.starlight.slan.SLanConfig;
import com.example.starlight.slan.SLanManager;
import org.starlight.terracotta.*;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.function.Supplier;

/**
 * 联机页：PCL 式布局 + 陶瓦联机（Terracotta）/ FRP 联机（樱花 SakuraFrp）/ SLAN 三种联机方式。
 *
 * <p>FRP 面板实现在 {@link SakuraFrpPanel}，本类只负责挂载与切页；
 * 页面状态（slanManager / frpPanel / slanConnected / 陶瓦 UI 引用 / 状态监听器）随页面迁入本类，
 * 主壳保留 {@code multiplayerPage} 单例，保证切页后联机状态与日志不丢。
 */
public final class MultiplayerPage {

    private final LauncherContext ctx;

    private SLanManager slanManager;  // 管理器实例
    // FRP（SakuraFrp）面板：整页复用，切页后隧道状态与日志不丢
    private SakuraFrpPanel frpPanel;
    // SLAN 主动断开标志：true 表示用户点击「断开连接」，进程退出回调不再重复清理 UI
    private volatile boolean slanManualDisconnect = false;
    // SLAN 连接状态：true 表示已创建/加入房间（按钮原地切换为「断开连接」）
    private volatile boolean slanConnected = false;

    // 陶瓦联机 UI 引用
    private VBox tcInstallArea, tcActionArea, tcRoomCodeArea, tcJoinInputArea, tcPlayerListCard;
    private Label tcStatusDot, tcStatusText, tcStatusDesc, tcRoomCodeLabel;
    private HBox tcPlayerListContainer;

    // Terracotta 监听器引用（防止重复注册）
    private ChangeListener<TerracottaState> tcStateListener;

    public MultiplayerPage(LauncherContext ctx) {
        this.ctx = ctx;
    }

    // 联机页面 //

    public Node build() {
        HBox layout = new HBox(20);
        layout.setPadding(new Insets(0, 4, 0, 0));

        VBox nav = new VBox(4);
        nav.setPrefWidth(160);
        nav.setMinWidth(160);
        nav.setMaxWidth(160);
        nav.setMaxHeight(Double.MAX_VALUE); // 填满页面高度，底色与主侧边栏一致
        nav.getStyleClass().add("multiplayer-nav"); // 导航栏底色，提高文字可读性
        Label title = new Label(I18N.get("page.t0293"));
        title.getStyleClass().add("multiplayer-nav-title");
        nav.getChildren().add(title);

        StackPane contentArea = new StackPane();
        contentArea.setPadding(new Insets(0, 0, 0, 16));
        HBox.setHgrow(contentArea, Priority.ALWAYS);

        Node terracottaPanel = buildTerracottaPanel();
        // FRP 联机：只对接樱花 SakuraFrp（账号、隧道管理与 frpc 进程均在新面板内）
        if (frpPanel == null) frpPanel = new SakuraFrpPanel(ctx);
        Node frpPanelNode = frpPanel.build();
        Node easyTierPanel = buildEasyTierPanel();

        String[][] types = {{"terracotta", I18N.get("page.t0294"), "people-online"},
                {"frp", I18N.get("page.t0295"), "plug"}, {"easytier", I18N.get("page.t0296"), "wifi"}};
        Node[] panels = {terracottaPanel, frpPanelNode, easyTierPanel};

        for (int i = 0; i < types.length; i++) {
            Button btn = new Button(types[i][1]);
            btn.getStyleClass().add("sidebar-item");
            btn.setMaxWidth(Double.MAX_VALUE);
            AppIcons.apply(btn, types[i][2], 15, null);
            final int idx = i;
            btn.setOnAction(e -> {
                contentArea.getChildren().setAll(panels[idx]);
                nav.getChildren().stream()
                        .filter(n -> n instanceof Button)
                        .forEach(n -> n.getStyleClass().remove("selected"));
                btn.getStyleClass().add("selected");
            });
            nav.getChildren().add(btn);
        }

        contentArea.getChildren().add(terracottaPanel);
        // nav 子节点 0 为标题 Label，1 起才是按钮（当前第一个为陶瓦联机）
        ((Button) nav.getChildren().get(1)).getStyleClass().add("selected");

        ScrollPane contentScroll = new ScrollPane(contentArea);
        // 统一挂接惯性滚动（阻尼 + fling），与其它页面同一套手感；内部 ListView/TextArea 的滚轮由过滤器放行
        PageKit.configureScrollPane(ctx, contentScroll);
        HBox.setHgrow(contentScroll, Priority.ALWAYS);

        layout.getChildren().addAll(nav, contentScroll);
        // 让布局填满父容器可用高度，确保 contentScroll 有确定的高度约束
        VBox.setVgrow(layout, Priority.ALWAYS);
        return layout;
    }

    // 陶瓦联机 //

    private Node buildTerracottaPanel() {
        VBox root = new VBox(16);
        root.setPadding(new Insets(0, 4, 0, 0));

        Label title = new Label(I18N.get("page.t0297"));
        title.getStyleClass().add("content-title");
        root.getChildren().add(title);

        VBox card = new VBox(12);
        card.getStyleClass().add("settings-card");

        // 状态区
        HBox statusArea = new HBox(12);
        statusArea.setAlignment(Pos.CENTER_LEFT);
        statusArea.setStyle("-fx-background-color: rgba(0,0,0,0.03); -fx-background-radius: 10; -fx-padding: 12 16;");

        tcStatusDot = new Label();
        tcStatusDot.setStyle("-fx-min-width: 10; -fx-min-height: 10; -fx-pref-width: 10; -fx-pref-height: 10; -fx-background-radius: 999; -fx-background-color: #94a3b8;");
        tcStatusText = new Label(I18N.get("page.t0298"));
        tcStatusText.setStyle("-fx-font-size: 14px; -fx-font-weight: 600; -fx-text-fill: #6b7280;");
        tcStatusDesc = new Label(I18N.get("page.t0299"));
        tcStatusDesc.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-faint; -f-text-fill: #6b7280;");
        HBox.setHgrow(tcStatusDesc, Priority.ALWAYS);
        tcStatusDesc.setAlignment(Pos.CENTER_RIGHT);
        statusArea.getChildren().addAll(tcStatusDot, tcStatusText, tcStatusDesc);
        card.getChildren().add(statusArea);

        // 安装区域
        tcInstallArea = new VBox(10);
        tcInstallArea.setStyle("-fx-background-color: rgba(0,0,0,0.02); -fx-background-radius: 10; -fx-padding: 16;");
        Label installTitle = new Label(I18N.get("page.t0300"));
        installTitle.setStyle("-fx-font-size: 13px; -fx-font-weight: 500; -fx-text-fill: #000000;");
        Label installDesc = new Label(I18N.get("page.t0301"));
        installDesc.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-faint;");
        Button downloadBtn = AppIcons.button("download", I18N.get("page.t0302"));
        downloadBtn.getStyleClass().add("btn-primary");
        downloadBtn.setOnAction(e -> handleTcDownload());
        tcInstallArea.getChildren().addAll(installTitle, installDesc, downloadBtn);
        card.getChildren().add(tcInstallArea);

        // 操作区域
        tcActionArea = new VBox(10);
        tcActionArea.setStyle("-fx-background-color: rgba(0,0,0,0.02); -fx-background-radius: 10; -fx-padding: 16;");
        tcActionArea.setVisible(false); tcActionArea.setManaged(false);
        HBox btnRow = new HBox(10);
        btnRow.setAlignment(Pos.CENTER);
        Button hostBtn = AppIcons.button("home", I18N.get("page.t0303"));
        hostBtn.getStyleClass().add("btn-primary");
        hostBtn.setStyle("-fx-background-color: #22c55e; -fx-text-fill: white; -fx-min-width: 140;");
        hostBtn.setOnAction(e -> handleTcHost());
        Button joinBtn = AppIcons.button("door", I18N.get("page.t0304"));
        joinBtn.getStyleClass().add("btn-primary");
        joinBtn.setStyle("-fx-min-width: 140;");
        joinBtn.setOnAction(e -> handleTcJoin());
        btnRow.getChildren().addAll(hostBtn, joinBtn);
        tcActionArea.getChildren().add(btnRow);
        card.getChildren().add(tcActionArea);

        // 房间码区域
        tcRoomCodeArea = new VBox(10);
        tcRoomCodeArea.setStyle("-fx-background-color: rgba(139,92,246,0.08); -fx-background-radius: 12; -fx-padding: 14 18; -fx-border-color: rgba(139,92,246,0.2); -fx-border-radius: 12; -fx-border-width: 1;");
        tcRoomCodeArea.setVisible(false); tcRoomCodeArea.setManaged(false);
        HBox codeRow = new HBox(12);
        codeRow.setAlignment(Pos.CENTER_LEFT);
        Label codeLabel = new Label(I18N.get("page.t0305"));
        codeLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: 500; -fx-text-fill: #5b21b6;");
        tcRoomCodeLabel = new Label("------");
        tcRoomCodeLabel.setStyle("-fx-font-family: monospace; -fx-font-size: 22px; -fx-font-weight: 700; -fx-text-fill: #7c3aed; -fx-background-color: white; -fx-padding: 8 20; -fx-background-radius: 10; -fx-border-color: rgba(139,92,246,0.3); -fx-border-radius: 10; -fx-border-width: 1;");
        Button copyCodeBtn = AppIcons.button("copy", I18N.get("page.t0306"));
        copyCodeBtn.getStyleClass().add("btn-primary");
        copyCodeBtn.setOnAction(e -> handleTcCopyCode());
        Button backBtn = AppIcons.button("back", I18N.get("page.t0307"));
        backBtn.getStyleClass().add("btn-primary");
        backBtn.setOnAction(e -> handleTcBack());
        HBox.setHgrow(tcRoomCodeLabel, Priority.ALWAYS);
        codeRow.getChildren().addAll(codeLabel, tcRoomCodeLabel, copyCodeBtn, backBtn);
        tcRoomCodeArea.getChildren().add(codeRow);
        card.getChildren().add(tcRoomCodeArea);

        // 加入输入区域
        tcJoinInputArea = new VBox(10);
        tcJoinInputArea.setVisible(false); tcJoinInputArea.setManaged(false);
        HBox joinRow = new HBox(10);
        joinRow.setAlignment(Pos.CENTER_LEFT);
        VBox joinInfo = new VBox(2);
        Label joinTitle = new Label(I18N.get("page.t0308"));
        joinTitle.setStyle("-fx-font-size: 13px; -fx-font-weight: 500;");
        Label joinDesc = new Label(I18N.get("page.t0309"));
        joinDesc.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-faint;");
        joinInfo.getChildren().addAll(joinTitle, joinDesc);
        TextField codeInput = new TextField();
        codeInput.setPromptText(I18N.get("page.t0310"));
        codeInput.getStyleClass().add("input-field");
        codeInput.setPrefWidth(150);
        Button connectBtn = AppIcons.button("link", I18N.get("page.t0311"));
        connectBtn.getStyleClass().add("btn-primary");
        connectBtn.setStyle("-fx-background-color: #22c55e; -fx-text-fill: white;");
        connectBtn.setOnAction(e -> handleTcDoJoin(codeInput.getText()));
        HBox.setHgrow(joinInfo, Priority.ALWAYS);
        joinRow.getChildren().addAll(joinInfo, codeInput, connectBtn);
        tcJoinInputArea.getChildren().add(joinRow);
        card.getChildren().add(tcJoinInputArea);
        root.getChildren().add(card);

        // 玩家列表
        tcPlayerListCard = new VBox(10);
        tcPlayerListCard.getStyleClass().add("settings-card");
        tcPlayerListCard.setVisible(false); tcPlayerListCard.setManaged(false);
        Label playerTitle = new Label(I18N.get("page.t0312"));
        playerTitle.setStyle("-fx-font-size: 13px; -fx-font-weight: 600;");
        tcPlayerListCard.getChildren().add(playerTitle);
        tcPlayerListContainer = new HBox();
        tcPlayerListContainer.setSpacing(8);
        tcPlayerListContainer.setAlignment(Pos.CENTER_LEFT);
        tcPlayerListCard.getChildren().add(tcPlayerListContainer);
        root.getChildren().add(tcPlayerListCard);

        // 日志
        root.getChildren().add(buildLogArea("tc", I18N.get("page.t0313")));

        // 帮助
        VBox helpCard = new VBox(10);
        helpCard.getStyleClass().add("settings-card");
        Label helpTitle = new Label(I18N.get("page.t0314"));
        helpTitle.setStyle("-fx-font-size: 13px; -fx-font-weight: 600; -fx-text-fill: #000000;");
        helpCard.getChildren().add(helpTitle);
        String[] helps = {I18N.get("page.t0634"), I18N.get("page.t0635"), I18N.get("page.t0636"), I18N.get("page.t0637")};
        for (String h : helps) {
            Label hl = new Label("• " + h);
            hl.setStyle("-fx-font-size: 13px; -fx-text-fill: #475569; -fx-padding: 4 0 4 8;");
            // 长条目换行显示，不换行时窗口一窄就被截成「…」
            hl.setWrapText(true);
            helpCard.getChildren().add(hl);
        }
        root.getChildren().add(helpCard);

        // Terracotta 状态监听（单次注册 + 支持所有状态） //
        if (tcStateListener == null) {
            tcStateListener = (obs, old, state) -> Platform.runLater(() -> {
                if (state instanceof TerracottaState.Bootstrap) {
                    setTcStatus("waiting", I18N.get("page.t0638"), I18N.get("page.t0639"));
                } else if (state instanceof TerracottaState.Uninitialized) {
                    setTcStatus("idle", I18N.get("page.t0640"), I18N.get("page.t0641"));
                    tcInstallArea.setVisible(true); tcInstallArea.setManaged(true);
                    tcActionArea.setVisible(false); tcActionArea.setManaged(false);
                } else if (state instanceof TerracottaState.Preparing) {
                    setTcStatus("waiting", I18N.get("page.t0642"), I18N.get("page.t0643"));
                    addTcLog(I18N.get("page.t0315"));
                } else if (state instanceof TerracottaState.Launching) {
                    setTcStatus("waiting", I18N.get("page.t0644"), I18N.get("page.t0645"));
                    tcInstallArea.setVisible(false); tcInstallArea.setManaged(false);
                } else if (state instanceof TerracottaState.Waiting || state instanceof TerracottaState.Unknown) {
                    setTcStatus("active", I18N.get("page.t0646"), I18N.get("page.t0647"));
                    tcInstallArea.setVisible(false); tcInstallArea.setManaged(false);
                    tcActionArea.setVisible(true); tcActionArea.setManaged(true);
                } else if (state instanceof TerracottaState.HostOK h) {
                    setTcStatus("active", I18N.get("page.t0648"), I18N.get("page.t0649"));
                    tcRoomCodeLabel.setText(h.getCode());
                    tcRoomCodeArea.setVisible(true); tcRoomCodeArea.setManaged(true);
                    tcPlayerListCard.setVisible(true); tcPlayerListCard.setManaged(true);
                    addTcLog(I18N.get("page.p0833", h.getCode()));
                } else if (state instanceof TerracottaState.GuestOK) {
                    setTcStatus("active", I18N.get("page.t0650"), I18N.get("page.t0651"));
                    tcPlayerListCard.setVisible(true); tcPlayerListCard.setManaged(true);
                    addTcLog(I18N.get("page.t0316"));
                } else if (state instanceof TerracottaState.Fatal f) {
                    String errMsg = switch (f.getType()) {
                        case OS -> I18N.get("page.t0317");
                        case NETWORK -> I18N.get("page.t0318");
                        case DOWNLOAD -> I18N.get("page.t0319");
                        case INSTALL -> I18N.get("page.t0320");
                        case TERRACOTTA -> I18N.get("page.t0321");
                        default -> I18N.get("page.t0322");
                    };
                    setTcStatus("error", I18N.get("page.t0323"), errMsg);
                    addTcLog(I18N.get("page.p0834", errMsg));
                }
            });
            try {
                TerracottaManager.stateProperty().addListener(tcStateListener);
                // 立即检查当前状态
                TerracottaState current = TerracottaManager.stateProperty().get();
                if (current != null) tcStateListener.changed(null, null, current);
            } catch (Exception e) {
                addTcLog(I18N.get("page.p0835", e.getMessage()));
            }
        } else {
            // 监听器已存在，更新当前状态
            TerracottaState current = TerracottaManager.stateProperty().get();
            if (current != null) tcStateListener.changed(null, null, current);
        }

        return root;
    }

    private void setTcStatus(String dotClass, String status, String desc) {
        String color = switch (dotClass) {
            case "idle" -> "#94a3b8"; case "waiting" -> "#f59e0b"; case "active" -> "#10b981"; case "error" -> "#ef4444"; default -> "#94a3b8";
        };
        tcStatusDot.setStyle("-fx-min-width: 10; -fx-min-height: 10; -fx-pref-width: 10; -fx-pref-height: 10; -fx-background-radius: 999; -fx-background-color: " + color + ";");
        if ("waiting".equals(dotClass)) {
            Timeline pulse = new Timeline(
                    new KeyFrame(Duration.ZERO, new KeyValue(tcStatusDot.opacityProperty(), 1.0)),
                    new KeyFrame(Duration.seconds(0.75), new KeyValue(tcStatusDot.opacityProperty(), 0.4)),
                    new KeyFrame(Duration.seconds(1.5), new KeyValue(tcStatusDot.opacityProperty(), 1.0))
            );
            pulse.setCycleCount(Animation.INDEFINITE);
            pulse.play();
            tcStatusDot.setUserData(pulse);
        } else {
            Object anim = tcStatusDot.getUserData();
            if (anim instanceof Timeline t) t.stop();
            tcStatusDot.setOpacity(1.0);
        }
        tcStatusText.setText(I18N.get("page.p0836", status));
        tcStatusDesc.setText(desc);
    }

    private void handleTcDownload() {
        setTcStatus("waiting", I18N.get("page.t0652"), I18N.get("page.t0653"));
        addTcLog(I18N.get("page.t0324"));
        try { TerracottaManager.download(); } catch (Exception ex) { addTcLog(I18N.get("page.p0837", ex.getMessage())); }
    }

    private void handleTcHost() {
        setTcStatus("waiting", I18N.get("page.t0654"), I18N.get("page.t0655"));
        tcActionArea.setVisible(false); tcActionArea.setManaged(false);
        try {
            TerracottaManager.setScanning();
        } catch (Exception e) { addTcLog(I18N.get("page.p0838", e.getMessage())); }
    }

    private void handleTcJoin() {
        tcActionArea.setVisible(false); tcActionArea.setManaged(false);
        tcJoinInputArea.setVisible(true); tcJoinInputArea.setManaged(true);
    }

    private void handleTcDoJoin(String code) {
        tcJoinInputArea.setVisible(false); tcJoinInputArea.setManaged(false);
        setTcStatus("waiting", I18N.get("page.t0656"), I18N.get("page.t0657"));
        try { TerracottaManager.setGuesting(code); } catch (Exception ex) { addTcLog(I18N.get("page.p0839", ex.getMessage())); }
    }

    private void handleTcCopyCode() {
        String code = tcRoomCodeLabel.getText();
        javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(code);
        clipboard.setContent(content);
        addTcLog(I18N.get("page.p0840", code));
    }

    private void handleTcBack() {
        tcRoomCodeArea.setVisible(false); tcRoomCodeArea.setManaged(false);
        tcPlayerListCard.setVisible(false); tcPlayerListCard.setManaged(false);
        tcActionArea.setVisible(true); tcActionArea.setManaged(true);
        setTcStatus("active", I18N.get("page.t0658"), I18N.get("page.t0659"));
    }

    // SLAN 联机 //

    private Node buildEasyTierPanel() {
        // 初始化管理器（懒加载），用于模块可用性检查与下载
        if (slanManager == null) {
            slanManager = new SLanManager();
        }
        VBox root = new VBox(16);
        root.setPadding(new Insets(0, 4, 0, 0));
        Label title = new Label(I18N.get("page.t0325"));
        title.getStyleClass().add("content-title");
        root.getChildren().add(title);

        // 中继服务器改到「设置 → 联机设置」维护，这里只做引导（建房/加房日志会显示实际使用的服务器）
        Label relayTip = new Label(I18N.get("page.t0326"));
        relayTip.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-faint;");
        relayTip.setWrapText(true);
        root.getChildren().add(relayTip);

        VBox card = new VBox(12);
        card.getStyleClass().add("settings-card");

        // 状态区域 //
        HBox statusArea = new HBox(12);
        statusArea.setAlignment(Pos.CENTER_LEFT);
        statusArea.setStyle("-fx-background-color: rgba(0,0,0,0.03); -fx-background-radius: 10; -fx-padding: 12 16;");
        Label etDot = new Label();
        etDot.setStyle("-fx-min-width: 10; -fx-min-height: 10; -fx-pref-width: 10; -fx-pref-height: 10; -fx-background-radius: 999; -fx-background-color: #94a3b8;");
        Label etStatus = new Label(I18N.get("page.t0327"));
        etStatus.setStyle("-fx-font-size: 14px; -fx-font-weight: 600; -fx-text-fill: #6b7280;");
        Label etInfo = new Label(""); // 显示邀请码或连接信息
        etInfo.setStyle("-fx-font-size: 12px; -fx-text-fill: #22c55e;");
        HBox.setHgrow(etInfo, Priority.ALWAYS);
        etInfo.setAlignment(Pos.CENTER_RIGHT);
        statusArea.getChildren().addAll(etDot, etStatus, etInfo);
        card.getChildren().add(statusArea);

        // 安装模块区域（模块缺失时显示） //
        VBox slanInstallArea = new VBox(10);
        slanInstallArea.setStyle("-fx-background-color: rgba(0,0,0,0.02); -fx-background-radius: 10; -fx-padding: 16;");
        Label slanInstallTitle = new Label(I18N.get("page.t0328"));
        slanInstallTitle.setStyle("-fx-font-size: 13px; -fx-font-weight: 500; -fx-text-fill: #000000;");
        Label slanInstallDesc = new Label(I18N.get("page.t0329"));
        slanInstallDesc.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-faint;");
        Button slanDownloadBtn = AppIcons.button("download", I18N.get("page.t0330"));
        slanDownloadBtn.getStyleClass().add("btn-primary");
        ProgressBar slanDownloadProgress = new ProgressBar(0);
        slanDownloadProgress.setPrefWidth(260);
        slanDownloadProgress.setVisible(false);
        slanDownloadProgress.setManaged(false);
        Label slanDownloadPct = new Label("");
        slanDownloadPct.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-faint; -fx-min-width: 44;");
        HBox slanDownloadRow = new HBox(8, slanDownloadProgress, slanDownloadPct);
        slanDownloadRow.setAlignment(Pos.CENTER_LEFT);
        slanDownloadRow.setVisible(false);
        slanDownloadRow.setManaged(false);
        slanInstallArea.getChildren().addAll(slanInstallTitle, slanInstallDesc, slanDownloadBtn, slanDownloadRow);
        card.getChildren().add(slanInstallArea);

        // 联机操作区域（模式/密码/按钮，模块缺失时隐藏） //
        VBox slanActionArea = new VBox(12);

        // 模式选择（建房/加房） //
        HBox modeCard = PageKit.settingsCard(I18N.get("page.t0660"), I18N.get("page.t0661"), null);
        ToggleGroup modeGroup = new ToggleGroup();
        RadioButton hostMode = new RadioButton(I18N.get("page.t0331"));
        hostMode.setToggleGroup(modeGroup);
        hostMode.setSelected(true);
        hostMode.setStyle("-fx-font-size: 13px; -fx-text-fill: #6b7280;");
        RadioButton joinMode = new RadioButton(I18N.get("page.t0332"));
        joinMode.setToggleGroup(modeGroup);
        joinMode.setStyle("-fx-font-size: 13px; -fx-text-fill: #6b7280;");
        HBox modeBox = new HBox(20, hostMode, joinMode);
        modeCard.getChildren().add(modeBox);
        slanActionArea.getChildren().add(modeCard);

        // 邀请码输入区（仅加入模式显示，位于密码输入区上方） //
        HBox inviteCard = PageKit.settingsCard(I18N.get("page.t0662"), I18N.get("page.t0663"), null);
        TextField inviteField = new TextField();
        inviteField.setPromptText(I18N.get("page.t0333"));
        inviteField.getStyleClass().add("input-field");
        inviteCard.getChildren().add(inviteField);
        slanActionArea.getChildren().add(inviteCard);

        // 联机密码（创建房间用，留空自动生成），从配置加载上次使用的密码 //
        HBox hostPwdCard = PageKit.settingsCard(I18N.get("page.t0664"), I18N.get("page.t0665"), null);
        TextField hostPwdField = new TextField(ctx.config().getOrDefault("SlanPassword", ""));
        hostPwdField.setPromptText(I18N.get("page.t0334"));
        hostPwdField.getStyleClass().add("input-field");

        // 自动生成随机密码按钮
        Button genPwdBtn = AppIcons.button("dice", I18N.get("page.t0335"));
        genPwdBtn.setStyle("-fx-background-color: #6366f1; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 12; -fx-background-radius: 6;");
        genPwdBtn.setOnAction(e -> hostPwdField.setText(generateRandomPassword()));

        HBox hostPwdBox = new HBox(8, hostPwdField, genPwdBtn);
        hostPwdBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(hostPwdField, Priority.ALWAYS);
        hostPwdCard.getChildren().add(hostPwdBox);
        slanActionArea.getChildren().add(hostPwdCard);

        // 联机密码（加入房间用，必填） //
        HBox joinPwdCard = PageKit.settingsCard(I18N.get("page.t0666"), I18N.get("page.t0667"), null);
        TextField joinPwdField = new TextField();
        joinPwdField.setPromptText(I18N.get("page.t0336"));
        joinPwdField.getStyleClass().add("input-field");
        joinPwdCard.getChildren().add(joinPwdField);
        slanActionArea.getChildren().add(joinPwdCard);

        // 模式切换逻辑：建房显示创建密码区，加房显示邀请码+加入密码区
        hostMode.selectedProperty().addListener((obs, old, isHost) -> {
            inviteCard.setVisible(!isHost);
            inviteCard.setManaged(!isHost);
            hostPwdCard.setVisible(isHost);
            hostPwdCard.setManaged(isHost);
            joinPwdCard.setVisible(!isHost);
            joinPwdCard.setManaged(!isHost);
            if (isHost) {
                inviteField.clear();
                joinPwdField.clear();
            } else {
                hostPwdField.clear();
            }
        });
        inviteCard.setVisible(false); // 默认建房模式，隐藏邀请码输入区
        inviteCard.setManaged(false);
        joinPwdCard.setVisible(false); // 默认建房模式，隐藏加入密码输入区
        joinPwdCard.setManaged(false);

        // 操作按钮 //
        HBox btnRow = new HBox(10);
        btnRow.setAlignment(Pos.CENTER);
        btnRow.setStyle("-fx-padding: 10 0 0 0;");

        Button hostBtn = AppIcons.button("home", I18N.get("page.t0337"));
        hostBtn.getStyleClass().add("btn-primary");
        hostBtn.setStyle("-fx-background-color: #3b82f6; -fx-text-fill: white; -fx-min-width: 140;");

        Button joinBtn = AppIcons.button("door", I18N.get("page.t0338"));
        joinBtn.getStyleClass().add("btn-primary");
        joinBtn.setStyle("-fx-background-color: #22c55e; -fx-text-fill: white; -fx-min-width: 140;");
        joinBtn.setVisible(false); // 默认隐藏
        joinBtn.setManaged(false); // 关键：隐藏时同时退出布局，否则会占位导致按钮整体偏左

        // 模式切换时更新按钮（创建/加入成功后按钮原地变为「断开连接」，无独立断开按钮）
        Runnable updateModeButtons = () -> {
            boolean isHost = hostMode.isSelected();
            hostBtn.setVisible(isHost);
            hostBtn.setManaged(isHost);
            joinBtn.setVisible(!isHost);
            joinBtn.setManaged(!isHost);
        };
        modeGroup.selectedToggleProperty().addListener((obs, old, selected) -> updateModeButtons.run());
        updateModeButtons.run(); // 初始化时同步一次，确保隐藏按钮不占位、可见按钮居中

        btnRow.getChildren().addAll(hostBtn, joinBtn);
        slanActionArea.getChildren().add(btnRow);
        card.getChildren().add(slanActionArea);
        root.getChildren().add(card);

        // 房间信息卡片（创建/加入成功后显示） //
        VBox roomInfoCard = new VBox(8);
        roomInfoCard.setStyle("-fx-background-color: rgba(34,197,94,0.08); -fx-border-color: rgba(34,197,94,0.4); -fx-border-radius: 12; -fx-background-radius: 12; -fx-padding: 12 14;");
        roomInfoCard.setVisible(false);
        roomInfoCard.setManaged(false);

        Label infoTitle = new Label(I18N.get("page.t0339"));
        infoTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: 700; -fx-text-fill: #059669;");

        Label roomNameValue = new Label("-");
        Label mcPortValue = new Label("-");
        Label inviteValue = new Label("-");
        Label pwdValue = new Label("-");

        HBox roomNameRow = buildInfoRow("window", I18N.get("page.t0340"), roomNameValue, roomNameValue::getText);
        HBox mcPortRow = buildInfoRow("plug", I18N.get("page.t0341"), mcPortValue, mcPortValue::getText);
        HBox inviteRow = buildInfoRow("ticket", I18N.get("page.t0342"), inviteValue, inviteValue::getText);
        HBox pwdRow = buildInfoRow("key", I18N.get("page.t0343"), pwdValue, pwdValue::getText);

        roomInfoCard.getChildren().addAll(infoTitle, roomNameRow, mcPortRow, inviteRow, pwdRow);
        root.getChildren().add(roomInfoCard);

        // 日志区域（SLAN 日志加高，便于查看详细日志） //
        VBox logArea = buildLogArea("et", I18N.get("page.t0344"), 260);
        root.getChildren().add(logArea);

        // 单按钮模式：创建/加入成功后按钮原地变为「断开连接」，始终单个居中 //

        // 恢复按钮为初始状态（创建房间 / 加入房间）
        Runnable resetButtons = () -> {
            boolean isHost = hostMode.isSelected();
            hostBtn.setVisible(isHost);
            hostBtn.setManaged(isHost);
            joinBtn.setVisible(!isHost);
            joinBtn.setManaged(!isHost);
            hostBtn.setDisable(false);
            joinBtn.setDisable(false);
            AppIcons.setText(hostBtn, "home", I18N.get("page.t0345"));
            hostBtn.setStyle("-fx-background-color: #3b82f6; -fx-text-fill: white; -fx-min-width: 140;");
            AppIcons.setText(joinBtn, "door", I18N.get("page.t0346"));
            joinBtn.setStyle("-fx-background-color: #22c55e; -fx-text-fill: white; -fx-min-width: 140;");
            modeGroup.getToggles().forEach(t -> ((RadioButton) t).setDisable(false));
        };

        // 断开连接逻辑（主动断开时执行）
        Runnable doDisconnect = () -> {
            slanManualDisconnect = true; // 标记主动断开，进程退出回调不再重复清理
            addEtLog(I18N.get("page.t0347"));

            // 终止联机进程
            if (slanManager != null && slanManager.isRunning()) {
                slanManager.disconnect();
                addEtLog(I18N.get("page.t0348"));
            }

            // 隐藏房间信息卡片
            roomInfoCard.setVisible(false);
            roomInfoCard.setManaged(false);

            // 重置UI
            etDot.setStyle("-fx-min-width: 10; -fx-min-height: 10; -fx-pref-width: 10; -fx-pref-height: 10; -fx-background-radius: 999; -fx-background-color: #94a3b8;");
            etStatus.setText(I18N.get("page.t0349"));
            etStatus.setStyle("-fx-font-size: 14px; -fx-font-weight: 600; -fx-text-fill: #6b7280;");
            etInfo.setText("");
            addEtLog(I18N.get("page.t0350"));

            // 恢复按钮和模式选择
            slanConnected = false;
            resetButtons.run();
        };

        // 模块可用性检查：缺失时隐藏操作区，显示安装区 //
        boolean moduleAvailable = slanManager.isAvailable();
        slanInstallArea.setVisible(!moduleAvailable);
        slanInstallArea.setManaged(!moduleAvailable);
        slanActionArea.setVisible(moduleAvailable);
        slanActionArea.setManaged(moduleAvailable);
        if (!moduleAvailable) {
            etDot.setStyle("-fx-min-width: 10; -fx-min-height: 10; -fx-pref-width: 10; -fx-pref-height: 10; -fx-background-radius: 999; -fx-background-color: #94a3b8;");
            etStatus.setText(I18N.get("page.t0351"));
            etStatus.setStyle("-fx-font-size: 14px; -fx-font-weight: 600; -fx-text-fill: #6b7280;");
            etInfo.setText(I18N.get("page.t0352"));
            etInfo.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-faint;");
        }

        // 下载安装模块（点击后异步下载，完成后切换为联机操作区） //
        slanDownloadBtn.setOnAction(e -> {
            slanDownloadBtn.setDisable(true);
            slanDownloadBtn.setText(I18N.get("page.t0353"));
            slanDownloadRow.setVisible(true);
            slanDownloadRow.setManaged(true);
            slanDownloadProgress.setProgress(0);
            slanDownloadPct.setText("0%");
            etDot.setStyle("-fx-min-width: 10; -fx-min-height: 10; -fx-pref-width: 10; -fx-pref-height: 10; -fx-background-radius: 999; -fx-background-color: #f59e0b;");
            etStatus.setText(I18N.get("page.t0354"));
            etStatus.setStyle("-fx-font-size: 14px; -fx-font-weight: 600; -fx-text-fill: #f59e0b;");
            etInfo.setText(I18N.get("page.t0355"));
            etInfo.setStyle("-fx-font-size: 12px; -fx-text-fill: #f59e0b;");
            addEtLog(I18N.get("page.t0356"));
            // 下载地址从 Starlight-Launcher/multiplayer.json 读取（无硬编码）
            slanManager.downloadModule(pct -> Platform.runLater(() -> {
                slanDownloadProgress.setProgress(pct / 100.0);
                slanDownloadPct.setText(pct + "%");
            })).whenComplete((v, err) -> Platform.runLater(() -> {
                if (err == null) {
                    // 下载成功：隐藏安装区，显示联机操作区
                    slanDownloadProgress.setProgress(1.0);
                    slanDownloadPct.setText("100%");
                    addEtLog(I18N.get("page.t0357"));
                    addEtLog(I18N.get("page.t0358"));
                    slanInstallArea.setVisible(false);
                    slanInstallArea.setManaged(false);
                    slanActionArea.setVisible(true);
                    slanActionArea.setManaged(true);
                    etDot.setStyle("-fx-min-width: 10; -fx-min-height: 10; -fx-pref-width: 10; -fx-pref-height: 10; -fx-background-radius: 999; -fx-background-color: #94a3b8;");
                    etStatus.setText(I18N.get("page.t0359"));
                    etStatus.setStyle("-fx-font-size: 14px; -fx-font-weight: 600; -fx-text-fill: #6b7280;");
                    etInfo.setText("");
                    etInfo.setStyle("-fx-font-size: 12px; -fx-text-fill: #22c55e;");
                    ctx.ui().toast(I18N.get("page.t0360"));
                } else {
                    // 下载失败：恢复按钮与进度显示
                    Throwable cause = err.getCause() != null ? err.getCause() : err;
                    slanDownloadBtn.setDisable(false);
                    AppIcons.setText(slanDownloadBtn, "download", I18N.get("page.t0361"));
                    slanDownloadRow.setVisible(false);
                    slanDownloadRow.setManaged(false);
                    etDot.setStyle("-fx-min-width: 10; -fx-min-height: 10; -fx-pref-width: 10; -fx-pref-height: 10; -fx-background-radius: 999; -fx-background-color: #ef4444;");
                    etStatus.setText(I18N.get("page.t0362"));
                    etStatus.setStyle("-fx-font-size: 14px; -fx-font-weight: 600; -fx-text-fill: #ef4444;");
                    etInfo.setText(I18N.get("page.t0363"));
                    etInfo.setStyle("-fx-font-size: 12px; -fx-text-fill: #ef4444;");
                    addEtLog(I18N.get("page.p0841", cause.getMessage()));
                }
            }));
        });

        // 按钮事件处理 //

        // 初始化管理器并注册进程退出监听（进程意外退出时自动清理 UI）
        if (slanManager == null) {
            slanManager = new SLanManager();
        }
        slanManager.setOnProcessExit(exitCode -> Platform.runLater(() -> {
            // 主动断开时 UI 已由「断开连接」按钮处理，无需重复清理
            if (slanManualDisconnect) return;

            if (roomInfoCard.isVisible()) {
                // 连接成功后进程意外退出（对方断开/网络中断）→ 完整清理
                addEtLog(I18N.get("page.p0842", exitCode + "）"));
                addEtLog(I18N.get("page.t0364"));
                roomInfoCard.setVisible(false);
                roomInfoCard.setManaged(false);
                etDot.setStyle("-fx-min-width: 10; -fx-min-height: 10; -fx-pref-width: 10; -fx-pref-height: 10; -fx-background-radius: 999; -fx-background-color: #94a3b8;");
                etStatus.setText(I18N.get("page.t0365"));
                etStatus.setStyle("-fx-font-size: 14px; -fx-font-weight: 600; -fx-text-fill: #6b7280;");
                etInfo.setText("");
                slanConnected = false;
                resetButtons.run();
            } else {
                // 连接尚未成功进程就退出：失败分支已处理状态，仅恢复按钮可用
                hostBtn.setDisable(false);
                joinBtn.setDisable(false);
            }
        }));

        // 创建房间（已连接时同一按钮执行断开）
        hostBtn.setOnAction(e -> {
            if (slanConnected) {
                doDisconnect.run();
                return;
            }
            slanManualDisconnect = false; // 开始新一轮连接，重置主动断开标志
            String password = hostPwdField.getText().trim();
            // 密码留空则自动生成（联机模块要求必填密码）
            if (password.isEmpty()) {
                password = generateRandomPassword();
                hostPwdField.setText(password);
                addEtLog(I18N.get("page.p0843", password));
            }
            final String finalPassword = password;
            // 保存密码配置，下次建房自动填充
            ctx.config().put("SlanPassword", password);
            ctx.saveConfig();

            // 更新UI状态
            etDot.setStyle("-fx-min-width: 10; -fx-min-height: 10; -fx-pref-width: 10; -fx-pref-height: 10; -fx-background-radius: 999; -fx-background-color: #f59e0b;");
            etStatus.setText(I18N.get("page.t0366"));
            etStatus.setStyle("-fx-font-size: 14px; -fx-font-weight: 600; -fx-text-fill: #f59e0b;");
            addEtLog(I18N.get("page.t0367"));
            addEtLog(I18N.get("page.p0844", SLanConfig.relayServer()));
            addEtLog(I18N.get("page.t0368"));
            hostBtn.setDisable(true);
            joinBtn.setDisable(true);

            // 初始化管理器（懒加载）
            if (slanManager == null) {
                slanManager = new SLanManager();
            }

            // 调用联机模块（长驻进程模式：解析到「房间已创建」即完成，进程持续运行）
            slanManager.hostRoomAsync(finalPassword, line ->
                    Platform.runLater(() -> addEtLog(I18N.get("page.p0845", line)))
            ).whenComplete((result, err) -> Platform.runLater(() -> {
                if (err != null) {
                    // 创建失败
                    Throwable cause = err.getCause() != null ? err.getCause() : err;
                    etDot.setStyle("-fx-min-width: 10; -fx-min-height: 10; -fx-pref-width: 10; -fx-pref-height: 10; -fx-background-radius: 999; -fx-background-color: #ef4444;");
                    etStatus.setText(I18N.get("page.t0369"));
                    etStatus.setStyle("-fx-font-size: 14px; -fx-font-weight: 600; -fx-text-fill: #ef4444;");
                    addEtLog(I18N.get("page.p0846", cause.getMessage()));
                    if (cause.getMessage() != null && cause.getMessage().contains("\n")) {
                        for (String detail : cause.getMessage().split("\n")) {
                            if (!detail.trim().isEmpty()) addEtLog(I18N.get("page.p0847", detail.trim()));
                        }
                    }
                    // 恢复按钮
                    hostBtn.setDisable(false);
                    joinBtn.setDisable(false);
                    return;
                }

                if (result != null && result.isSuccess()) {
                    etDot.setStyle("-fx-min-width: 10; -fx-min-height: 10; -fx-pref-width: 10; -fx-pref-height: 10; -fx-background-radius: 999; -fx-background-color: #22c55e;");
                    etStatus.setText(I18N.get("page.t0370"));
                    etStatus.setStyle("-fx-font-size: 14px; -fx-font-weight: 600; -fx-text-fill: #22c55e;");

                    String roomName = safeOrUnknown(result.getRoomName());
                    String port = safeOrUnknown(result.getPort());
                    String inviteCode = safeOrUnknown(result.getInviteCode());
                    String roomPwd = result.getPassword() != null ? result.getPassword() : finalPassword;

                    addEtLog(I18N.get("page.t0371"));
                    addEtLog(I18N.get("page.p0848", roomName));
                    addEtLog(I18N.get("page.p0849", port));
                    addEtLog(I18N.get("page.p0850", inviteCode));
                    addEtLog(I18N.get("page.p0851", roomPwd));
                    addEtLog(I18N.get("page.t0372"));

                    // 填充房间信息卡片
                    roomNameValue.setText(roomName);
                    mcPortValue.setText(port);
                    inviteValue.setText(inviteCode);
                    pwdValue.setText(roomPwd);
                    roomNameRow.setVisible(true);
                    roomNameRow.setManaged(true);
                    roomInfoCard.setVisible(true);
                    roomInfoCard.setManaged(true);
                    etInfo.setText(I18N.get("page.p0852", inviteCode));

                    // 自动复制邀请码到剪贴板
                    if (result.getInviteCode() != null) {
                        Clipboard clipboard = Clipboard.getSystemClipboard();
                        ClipboardContent content = new ClipboardContent();
                        content.putString(result.getInviteCode());
                        clipboard.setContent(content);
                        addEtLog(I18N.get("page.t0373"));
                    }

                    // 同一按钮原地切换为「断开连接」，保持居中
                    slanConnected = true;
                    hostBtn.setDisable(false);
                    hostBtn.setText(I18N.get("page.t0374"));
                    hostBtn.setStyle("-fx-background-color: #ef4444; -fx-text-fill: white; -fx-min-width: 140;");
                    modeGroup.getToggles().forEach(t -> ((RadioButton)t).setDisable(true));
                }
            }));
        });

        // 加入房间（已连接时同一按钮执行断开）
        joinBtn.setOnAction(e -> {
            if (slanConnected) {
                doDisconnect.run();
                return;
            }
            slanManualDisconnect = false; // 开始新一轮连接，重置主动断开标志
            String inviteCode = inviteField.getText().trim();
            String password = joinPwdField.getText().trim();

            if (inviteCode.isEmpty()) {
                addEtLog(I18N.get("page.t0375"));
                inviteField.setStyle("-fx-border-color: #ef4444; -fx-border-width: 2;");
                return;
            }
            if (password.isEmpty()) {
                addEtLog(I18N.get("page.t0376"));
                joinPwdField.setStyle("-fx-border-color: #ef4444; -fx-border-width: 2;");
                return;
            }
            inviteField.setStyle(""); // 清除错误样式
            joinPwdField.setStyle("");

            // 更新UI状态
            etDot.setStyle("-fx-min-width: 10; -fx-min-height: 10; -fx-pref-width: 10; -fx-pref-height: 10; -fx-background-radius: 999; -fx-background-color: #f59e0b;");
            etStatus.setText(I18N.get("page.t0377"));
            etStatus.setStyle("-fx-font-size: 14px; -fx-font-weight: 600; -fx-text-fill: #f59e0b;");
            addEtLog(I18N.get("page.p0853", inviteCode));
            addEtLog(I18N.get("page.p0854", SLanConfig.relayServer()));
            hostBtn.setDisable(true);
            joinBtn.setDisable(true);

            // 初始化管理器（懒加载）
            if (slanManager == null) {
                slanManager = new SLanManager();
            }

            // 调用联机模块（长驻进程模式：解析到「认证成功」即完成，进程持续运行）
            slanManager.joinRoomAsync(inviteCode, password, line ->
                    Platform.runLater(() -> addEtLog(I18N.get("page.p0855", line)))
            ).whenComplete((result, err) -> Platform.runLater(() -> {
                if (err != null) {
                    // 加入失败
                    Throwable cause = err.getCause() != null ? err.getCause() : err;
                    etDot.setStyle("-fx-min-width: 10; -fx-min-height: 10; -fx-pref-width: 10; -fx-pref-height: 10; -fx-background-radius: 999; -fx-background-color: #ef4444;");
                    etStatus.setText(I18N.get("page.t0378"));
                    etStatus.setStyle("-fx-font-size: 14px; -fx-font-weight: 600; -fx-text-fill: #ef4444;");
                    addEtLog(I18N.get("page.p0856", cause.getMessage()));
                    addEtLog(I18N.get("page.t0379"));
                    if (cause.getMessage() != null && cause.getMessage().contains("\n")) {
                        for (String detail : cause.getMessage().split("\n")) {
                            if (!detail.trim().isEmpty()) addEtLog(I18N.get("page.p0857", detail.trim()));
                        }
                    }
                    // 恢复按钮
                    hostBtn.setDisable(false);
                    joinBtn.setDisable(false);
                    return;
                }

                if (result != null && result.isConnected()) {
                    etDot.setStyle("-fx-min-width: 10; -fx-min-height: 10; -fx-pref-width: 10; -fx-pref-height: 10; -fx-background-radius: 999; -fx-background-color: #22c55e;");
                    etStatus.setText(I18N.get("page.t0380"));
                    etStatus.setStyle("-fx-font-size: 14px; -fx-font-weight: 600; -fx-text-fill: #22c55e;");

                    String port = safeOrUnknown(result.getPort());

                    addEtLog(I18N.get("page.t0381"));
                    addEtLog(I18N.get("page.p0858", inviteCode));
                    addEtLog(I18N.get("page.p0859", port));
                    if (result.getHostAddress() != null) {
                        addEtLog(I18N.get("page.p0860", result.getHostAddress()));
                    }
                    addEtLog(I18N.get("page.t0382"));

                    // 填充房间信息卡片（加入模式无房间名称，隐藏该行）
                    roomNameValue.setText(I18N.get("page.t0383"));
                    roomNameRow.setVisible(false);
                    roomNameRow.setManaged(false);
                    mcPortValue.setText(port);
                    inviteValue.setText(inviteCode);
                    pwdValue.setText(password);
                    roomInfoCard.setVisible(true);
                    roomInfoCard.setManaged(true);
                    etInfo.setText(I18N.get("page.p0861", inviteCode));

                    // 同一按钮原地切换为「断开连接」，保持居中
                    slanConnected = true;
                    joinBtn.setDisable(false);
                    joinBtn.setText(I18N.get("page.t0384"));
                    joinBtn.setStyle("-fx-background-color: #ef4444; -fx-text-fill: white; -fx-min-width: 140;");
                    modeGroup.getToggles().forEach(t -> ((RadioButton)t).setDisable(true));
                }
            }));
        });

        return root;
    }

    // 通用日志组件 //

    private VBox buildLogArea(String prefix, String logTitle) {
        return buildLogArea(prefix, logTitle, 140);
    }

    private VBox buildLogArea(String prefix, String logTitle, double logHeight) {
        VBox logContainer = new VBox();
        logContainer.setStyle("-fx-background-color: rgba(0,0,0,0.85); -fx-border-color: rgba(255,255,255,0.08); -fx-border-radius: 12; -fx-background-radius: 12;");

        HBox header = new HBox();
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-padding: 10 14; -fx-background-color: rgba(255,255,255,0.05); -fx-border-color: transparent transparent rgba(255,255,255,0.06) transparent;");
        Label logTitleLabel = new Label("" + logTitle);
        logTitleLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: 600; -fx-text-fill: #94a3b8;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button clearBtn = AppIcons.button("trash", I18N.get("page.t0385"));
        clearBtn.setStyle("-fx-font-size: 11px; -fx-text-fill: #60a5fa; -fx-background-color: transparent; -fx-border-color: transparent;");
        clearBtn.setOnAction(e -> {
            ListView<String> lv = (ListView<String>) ctx.rootPane().lookup("#" + prefix + "-log-list");
            if (lv != null) lv.getItems().clear();
        });
        header.getChildren().addAll(logTitleLabel, spacer, clearBtn);
        logContainer.getChildren().add(header);

        ListView<String> logList = new ListView<>();
        logList.setId(prefix + "-log-list");
        logList.setPrefHeight(logHeight);
        logList.setStyle("-fx-background-color: transparent; -fx-control-inner-background: transparent;");
        // 虚拟化日志列表接入惯性滚动（与页面同一套阻尼手感）；SmoothScroll=false 时保持原生
        InertiaScrollSupport.installListView(ctx, logList);
        logList.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null); setStyle("-fx-background-color: transparent;");
                } else {
                    setText(item);
                    String style = "-fx-background-color: transparent; -fx-font-family: monospace; -fx-font-size: 12px; -fx-padding: 4 0;";
                    // i18n-lock: 不要翻译此行中文——展示自产日志并按标签着色，日志规范中文，翻译即功能失效
                    if (item.contains("成功") || item.contains("[成功]")) style += "-fx-text-fill: #4ade80;";
                    // i18n-lock: 不要翻译此行中文——展示自产日志并按标签着色，日志规范中文，翻译即功能失效
                    else if (item.contains("失败") || item.contains("[错误]")) style += "-fx-text-fill: #f87171;";
                    // i18n-lock: 不要翻译此行中文——展示自产日志并按标签着色，日志规范中文，翻译即功能失效
                    else if (item.contains("正在") || item.contains("[警告]")) style += "-fx-text-fill: #fbbf24;";
                    else style += "-fx-text-fill: #cbd5e1;";
                    setStyle(style);
                }
            }
        });

        logList.getItems().addListener((javafx.collections.ListChangeListener<String>) c -> {
            while (logList.getItems().size() > 100) logList.getItems().remove(0);
        });

        logContainer.getChildren().add(logList);
        return logContainer;
    }

    private void addTcLog(String msg) { addLog("tc", msg); }
    // 新增：SLAN 日志辅助方法
    private void addEtLog(String msg) { addLog("et", msg); }

    private void addLog(String prefix, String msg) {
        Platform.runLater(() -> {
            ListView<String> lv = (ListView<String>) ctx.rootPane().lookup("#" + prefix + "-log-list");
            if (lv != null) {
                // 添加时间戳，让日志更详细
                String ts = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
                lv.getItems().add("[" + ts + "] " + msg);
                lv.scrollTo(lv.getItems().size() - 1);
            }
        });
    }

    // SLAN 联机辅助方法 //

    /**
     * 构建房间信息行（图标 + 标签 + 值 + 复制按钮）
     */
    private HBox buildInfoRow(String icon, String title, Label valueLabel, Supplier<String> valueSupplier) {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        Label nameLabel = new Label(title);
        AppIcons.apply(nameLabel, icon, 14, null);
        nameLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: 600; -fx-text-fill: #059669; -fx-min-width: 160;");
        valueLabel.setStyle("-fx-font-size: 13px; -fx-font-family: monospace; -fx-text-fill: #065f46;");
        HBox.setHgrow(valueLabel, Priority.ALWAYS);
        Button copyBtn = AppIcons.button("copy", I18N.get("page.t0386"));
        copyBtn.setStyle("-fx-background-color: #10b981; -fx-text-fill: white; -fx-font-size: 11px; -fx-padding: 4 10; -fx-background-radius: 6;");
        copyBtn.setOnAction(e -> {
            String v = valueSupplier.get();
            if (v != null && !v.isEmpty() && !"-".equals(v)) {
                Clipboard clipboard = Clipboard.getSystemClipboard();
                ClipboardContent content = new ClipboardContent();
                content.putString(v);
                clipboard.setContent(content);
                ctx.ui().toast(I18N.get("mpp.r0001", title, v));
            } else {
                ctx.ui().toast(I18N.get("mpp.r0002", title));
            }
        });
        row.getChildren().addAll(nameLabel, valueLabel, copyBtn);
        return row;
    }

    /** 生成 8 位随机联机密码 */
    private String generateRandomPassword() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder();
        java.util.Random random = new java.util.Random();
        for (int i = 0; i < 8; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    /** 空值安全转换：null 或空串返回「未知」 */
    private String safeOrUnknown(String s) {
        return (s == null || s.trim().isEmpty()) ? I18N.get("page.t0387") : s.trim();
    }

}


