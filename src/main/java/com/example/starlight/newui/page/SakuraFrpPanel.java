/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.newui.page;


import com.example.starlight.util.I18N;
import com.example.starlight.config.Endpoints;
import com.example.starlight.frp.SakuraFrpApi;
import com.example.starlight.frp.SakuraFrpService;
import com.example.starlight.frp.SakuraFrpTokenStore;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.util.FormatUtils;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Files;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 联机页「FRP 联机」面板 —— 只对接樱花 SakuraFrp。
 *
 * <p>对应技术文档 {@code temp/frp/verse-frp-manager 技术文档.md} 的樱花部分：
 * 访问密钥登录、账户信息、节点/隧道列表、建删改隧道、拉取 ini 配置、启动本地 frpc。
 * 与插件版不同，这里用 Java 直接托管 frpc 进程（不再生成 .bat），因此启动失败时
 * 能拿到 frpc 的真实输出。
 *
 * <p>安全边界：访问密钥只写入本机 {@code .sakura.dat}（混淆存储），
 * 不进入日志、不随社区/AI 请求上传。
 */
public final class SakuraFrpPanel {

    private final LauncherContext ctx;
    private final SakuraFrpApi api = new SakuraFrpApi();
    private final SakuraFrpService service = SakuraFrpService.get();

    private TextField tokenField;
    private Button connectBtn;
    private Button logoutBtn;
    private Button refreshBtn;
    private Button createBtn;

    private Region statusDot;
    private Label statusText;
    private Label accountLine;
    private Label accountDetail;

    private VBox tunnelList;
    private Label tunnelSummary;

    private Label frpcStatus;
    private Button downloadFrpcBtn;
    private ProgressBar frpcProgress;

    private ListView<String> logList;

    private final List<SakuraFrpApi.Tunnel> tunnels = new ArrayList<>();
    private Map<Integer, SakuraFrpApi.Node> nodes = new LinkedHashMap<>();
    private SakuraFrpApi.UserInfo user;
    private String token = "";
    private String latestFrpcVersion = "";
    private boolean connected;
    private boolean busy;

    public SakuraFrpPanel(LauncherContext ctx) {
        this.ctx = ctx;
    }

    // 页面 //

    public Node build() {
        VBox root = new VBox(16);
        root.setPadding(new Insets(0, 4, 0, 0));

        Label title = new Label(I18N.get("page.t0465"));
        title.getStyleClass().add("content-title");
        Label subtitle = new Label(I18N.get("page.t0466"));
        subtitle.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-faint;");
        subtitle.setWrapText(true);
        root.getChildren().addAll(title, subtitle);

        root.getChildren().add(buildAccountCard());
        root.getChildren().add(buildTunnelCard());
        root.getChildren().add(buildEngineCard());
        root.getChildren().add(buildHelpCard());
        root.getChildren().add(buildLogArea());

        updateFrpcStatus();
        service.setOnExit(() -> Platform.runLater(() -> {
            renderTunnels();
            setStatus("idle", I18N.get("page.t0690"), I18N.get("page.t0691"));
        }));

        // 已保存过密钥：只回填输入框，等用户点击「连接」再发起网络请求
        String saved = SakuraFrpTokenStore.load();
        if (!saved.isBlank()) {
            tokenField.setText(saved);
            setStatus("idle", I18N.get("page.t0692"), I18N.get("page.t0693"));
            addLog(I18N.get("page.t0467"));
        } else {
            setStatus("idle", I18N.get("page.t0694"), I18N.get("page.t0695"));
        }
        return root;
    }

    // 账户卡片 //

    private Node buildAccountCard() {
        VBox card = new VBox(12);
        card.getStyleClass().add("settings-card");

        HBox titleRow = new HBox(10);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        Label section = new Label(I18N.get("page.t0468"));
        section.setStyle("-fx-font-size: 13px; -fx-font-weight: 600;");
        Button tokenHelp = AppIcons.button("external-link", I18N.get("page.t0469"));
        tokenHelp.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; "
                + "-fx-text-fill: #3b82f6; -fx-font-size: 12px; -fx-padding: 2 6;");
        tokenHelp.setOnAction(e -> ctx.openWebUrl(Endpoints.sakuraFrpTokenUrl()));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        titleRow.getChildren().addAll(section, spacer, tokenHelp);

        HBox tokenRow = new HBox(8);
        tokenRow.setAlignment(Pos.CENTER_LEFT);
        Label tokenLabel = new Label(I18N.get("page.t0470"));
        tokenLabel.setMinWidth(70);
        tokenLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #6b7280;");
        tokenField = new TextField();
        tokenField.setPromptText(I18N.get("page.t0471"));
        tokenField.getStyleClass().add("input-field");
        HBox.setHgrow(tokenField, Priority.ALWAYS);
        tokenField.setOnAction(e -> {
            if (!busy) connect();
        });
        connectBtn = AppIcons.button("link", I18N.get("page.t0472"));
        connectBtn.getStyleClass().add("btn-primary");
        connectBtn.setOnAction(e -> connect());
        logoutBtn = AppIcons.button("log-out", I18N.get("page.t0473"));
        logoutBtn.setStyle("-fx-background-color: transparent; -fx-border-color: rgba(0,0,0,0.12); "
                + "-fx-text-fill: #6b7280; -fx-font-size: 12px;");
        logoutBtn.setOnAction(e -> logout());
        logoutBtn.setVisible(false);
        logoutBtn.setManaged(false);
        tokenRow.getChildren().addAll(tokenLabel, tokenField, connectBtn, logoutBtn);

        HBox statusRow = new HBox(12);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        statusRow.setStyle("-fx-background-color: rgba(0,0,0,0.03); -fx-background-radius: 10; "
                + "-fx-padding: 10 14;");
        statusDot = new Region();
        statusDot.setMinSize(10, 10);
        statusDot.setPrefSize(10, 10);
        statusDot.setMaxSize(10, 10);
        setDot("#94a3b8");

        VBox statusInfo = new VBox(2);
        statusText = new Label(I18N.get("page.t0474"));
        statusText.setStyle("-fx-font-size: 13px; -fx-font-weight: 600; -fx-text-fill: #6b7280;");
        accountLine = new Label(I18N.get("page.t0475"));
        accountLine.setStyle("-fx-font-size: 12px; -fx-text-fill: #6b7280;");
        accountLine.setWrapText(true);
        accountDetail = new Label("");
        accountDetail.setStyle("-fx-font-size: 11px; -fx-text-fill: -sl-text-faint;");
        accountDetail.setWrapText(true);
        statusInfo.getChildren().addAll(statusText, accountLine, accountDetail);
        HBox.setHgrow(statusInfo, Priority.ALWAYS);
        statusRow.getChildren().addAll(statusDot, statusInfo);

        card.getChildren().addAll(titleRow, tokenRow, statusRow);
        return card;
    }

    // 隧道卡片 //

    private Node buildTunnelCard() {
        VBox card = new VBox(12);
        card.getStyleClass().add("settings-card");

        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);
        Label section = new Label(I18N.get("page.t0476"));
        section.setStyle("-fx-font-size: 13px; -fx-font-weight: 600;");
        tunnelSummary = new Label(I18N.get("page.t0477"));
        tunnelSummary.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-faint;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        refreshBtn = AppIcons.button("refresh", I18N.get("page.t0478"));
        refreshBtn.setStyle("-fx-background-color: transparent; -fx-border-color: rgba(0,0,0,0.12); "
                + "-fx-font-size: 12px;");
        refreshBtn.setOnAction(e -> refreshTunnels());
        createBtn = AppIcons.button("plus", I18N.get("page.t0479"));
        createBtn.getStyleClass().add("btn-primary");
        createBtn.setOnAction(e -> openCreateDialog());
        header.getChildren().addAll(section, tunnelSummary, spacer, refreshBtn, createBtn);

        tunnelList = new VBox(8);
        Label empty = new Label(I18N.get("page.t0480"));
        empty.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-faint; -fx-padding: 8 0;");
        tunnelList.getChildren().add(empty);

        card.getChildren().addAll(header, tunnelList);
        setBusy(false);
        return card;
    }

    // frpc 卡片 //

    private Node buildEngineCard() {
        VBox card = new VBox(10);
        card.getStyleClass().add("settings-card");

        Label section = new Label(I18N.get("page.t0481"));
        section.setStyle("-fx-font-size: 13px; -fx-font-weight: 600;");
        Label desc = new Label(I18N.get("page.t0482"));
        desc.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-faint;");
        desc.setWrapText(true);

        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        frpcStatus = new Label(I18N.get("page.t0483"));
        frpcStatus.setStyle("-fx-font-size: 12px; -fx-font-family: monospace; -fx-text-fill: #6b7280;");
        frpcStatus.setMinWidth(0);
        HBox.setHgrow(frpcStatus, Priority.ALWAYS);
        downloadFrpcBtn = AppIcons.button("download", I18N.get("page.t0484"));
        downloadFrpcBtn.setStyle("-fx-background-color: transparent; -fx-border-color: rgba(0,0,0,0.12); "
                + "-fx-font-size: 12px;");
        downloadFrpcBtn.setOnAction(e -> downloadFrpc());
        Button openFolderBtn = AppIcons.button("folder-open", I18N.get("page.t0485"));
        openFolderBtn.setStyle("-fx-background-color: transparent; -fx-border-color: rgba(0,0,0,0.12); "
                + "-fx-font-size: 12px;");
        openFolderBtn.setOnAction(e -> openFrpcFolder());
        row.getChildren().addAll(frpcStatus, downloadFrpcBtn, openFolderBtn);

        frpcProgress = new ProgressBar(0);
        frpcProgress.setPrefWidth(260);
        frpcProgress.setVisible(false);
        frpcProgress.setManaged(false);

        card.getChildren().addAll(section, desc, row, frpcProgress);
        return card;
    }

    private Node buildHelpCard() {
        VBox card = new VBox(8);
        card.getStyleClass().add("settings-card");
        Label title = new Label(I18N.get("page.t0486"));
        title.setStyle("-fx-font-size: 13px; -fx-font-weight: 600;");
        card.getChildren().add(title);
        String[] tips = {
                I18N.get("page.t0696"),
                I18N.get("page.t0697"),
                I18N.get("page.t0698"),
                I18N.get("page.t0699"),
                I18N.get("page.t0700")
        };
        for (String tip : tips) {
            Label label = new Label("• " + tip);
            label.setWrapText(true);
            label.setStyle("-fx-font-size: 12px; -fx-text-fill: #475569; -fx-padding: 2 0 2 6;");
            card.getChildren().add(label);
        }
        return card;
    }

    // 日志 //

    private Node buildLogArea() {
        VBox container = new VBox();
        container.setStyle("-fx-background-color: rgba(0,0,0,0.85); -fx-border-color: rgba(255,255,255,0.08); "
                + "-fx-border-radius: 12; -fx-background-radius: 12;");

        HBox header = new HBox();
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-padding: 10 14; -fx-background-color: rgba(255,255,255,0.05);");
        Label title = new Label(I18N.get("page.t0487"));
        title.setStyle("-fx-font-size: 11px; -fx-font-weight: 600; -fx-text-fill: #94a3b8;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button clearBtn = AppIcons.button("trash", I18N.get("page.t0488"));
        clearBtn.setStyle("-fx-font-size: 11px; -fx-text-fill: #60a5fa; -fx-background-color: transparent; "
                + "-fx-border-color: transparent;");
        clearBtn.setOnAction(e -> {
            if (logList != null) logList.getItems().clear();
        });
        header.getChildren().addAll(title, spacer, clearBtn);

        logList = new ListView<>();
        logList.setId("frp-log-list");
        logList.setPrefHeight(160);
        logList.setStyle("-fx-background-color: transparent; -fx-control-inner-background: transparent;");
        logList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("-fx-background-color: transparent;");
                    return;
                }
                setText(item);
                String style = "-fx-background-color: transparent; -fx-font-family: monospace; "
                        + "-fx-font-size: 12px; -fx-padding: 3 0; ";
                // i18n-lock: 不要翻译此行中文——展示自产日志并按标签着色，日志规范中文，翻译即功能失效
                if (item.contains("[成功]") || item.contains("[下载]")) style += "-fx-text-fill: #4ade80;";
                // i18n-lock: 不要翻译此行中文——展示自产日志并按标签着色，日志规范中文，翻译即功能失效
                else if (item.contains("[错误]") || item.contains("失败")) style += "-fx-text-fill: #f87171;";
                // i18n-lock: 不要翻译此行中文——展示自产日志并按标签着色，日志规范中文，翻译即功能失效
                else if (item.contains("[系统]")) style += "-fx-text-fill: #fbbf24;";
                else style += "-fx-text-fill: #cbd5e1;";
                setStyle(style);
            }
        });
        logList.getItems().addListener((javafx.collections.ListChangeListener<String>) c -> {
            while (logList.getItems().size() > 200) logList.getItems().remove(0);
        });
        container.getChildren().addAll(header, logList);
        return container;
    }

    private void addLog(String message) {
        Platform.runLater(() -> {
            if (logList == null) return;
            String ts = java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            logList.getItems().add("[" + ts + "] " + message);
            logList.scrollTo(logList.getItems().size() - 1);
        });
    }

    // 账户操作 //

    private void connect() {
        String candidate = tokenField.getText() == null ? "" : tokenField.getText().trim();
        if (candidate.isEmpty()) {
            ctx.ui().toast(I18N.get("page.t0489"));
            tokenField.requestFocus();
            return;
        }
        if (busy) return;
        setBusy(true);
        setStatus("waiting", I18N.get("page.t0701"), I18N.get("page.t0702"));
        addLog(I18N.get("page.t0490"));
        CompletableFuture.supplyAsync(() -> {
            try {
                SakuraFrpApi.UserInfo userInfo = api.userInfo(candidate);
                Map<Integer, SakuraFrpApi.Node> nodeMap = api.nodes(candidate);
                List<SakuraFrpApi.Tunnel> tunnelListData = api.tunnels(candidate);
                String latest = "";
                try {
                    latest = api.latestFrpc().version();
                } catch (Exception ignored) {
                    // 拉不到最新版本不影响已安装 frpc 的使用
                }
                return new AccountSnapshot(userInfo, nodeMap, tunnelListData, latest);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }).whenComplete((snapshot, error) -> Platform.runLater(() -> {
            setBusy(false);
            if (error != null) {
                Throwable cause = unwrap(error);
                connected = false;
                user = null;
                tunnels.clear();
                nodes = new LinkedHashMap<>();
                setStatus("error", I18N.get("page.t0491"), safeMessage(cause));
                addLog(I18N.get("page.p0873", safeMessage(cause)));
                ctx.ui().toast(I18N.get("page.p0874", safeMessage(cause)));
                renderTunnels();
                renderAccount();
                return;
            }
            token = candidate;
            connected = true;
            user = snapshot.user;
            nodes = snapshot.nodes;
            tunnels.clear();
            tunnels.addAll(snapshot.tunnels);
            latestFrpcVersion = snapshot.latestFrpcVersion;
            SakuraFrpTokenStore.save(candidate);
            setStatus("active", I18N.get("page.t0703"), I18N.get("page.t0704"));
            addLog(I18N.get("page.p0875", user.name()));
            addLog(I18N.get("frp.r0001", tunnels.size(), nodes.size()));
            renderAccount();
            renderTunnels();
            updateFrpcStatus();
            ctx.ui().toast(I18N.get("page.t0492"));
        }));
    }

    private void logout() {
        service.stop();
        SakuraFrpTokenStore.clear();
        token = "";
        connected = false;
        user = null;
        tunnels.clear();
        nodes = new LinkedHashMap<>();
        tokenField.clear();
        setStatus("idle", I18N.get("page.t0705"), I18N.get("page.t0706"));
        renderAccount();
        renderTunnels();
        addLog(I18N.get("page.t0493"));
        ctx.ui().toast(I18N.get("page.t0494"));
    }

    private void refreshTunnels() {
        if (!connected || token.isBlank() || busy) return;
        setBusy(true);
        addLog(I18N.get("page.t0495"));
        CompletableFuture.supplyAsync(() -> {
            try {
                Map<Integer, SakuraFrpApi.Node> nodeMap = api.nodes(token);
                List<SakuraFrpApi.Tunnel> list = api.tunnels(token);
                return new TunnelSnapshot(nodeMap, list);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }).whenComplete((snapshot, error) -> Platform.runLater(() -> {
            setBusy(false);
            if (error != null) {
                Throwable cause = unwrap(error);
                addLog(I18N.get("page.p0876", safeMessage(cause)));
                ctx.ui().toast(I18N.get("page.p0877", safeMessage(cause)));
                return;
            }
            nodes = snapshot.nodes;
            tunnels.clear();
            tunnels.addAll(snapshot.tunnels);
            renderTunnels();
            addLog(I18N.get("page.t0496"));
        }));
    }

    private void renderAccount() {
        if (!connected || user == null) {
            accountLine.setText(I18N.get("page.t0497"));
            accountDetail.setText("");
            logoutBtn.setVisible(false);
            logoutBtn.setManaged(false);
            return;
        }
        String group = user.groupName() == null || user.groupName().isBlank()
                ? "" : " · " + user.groupName();
        accountLine.setText(I18N.get("frp.r0002", user.name(), group,
                user.speed().isBlank() ? "" : I18N.get("frp.r0003", user.speed())));
        accountDetail.setText(I18N.get("frp.r0004", FormatUtils.formatFileSize(user.usedToday()),
                FormatUtils.formatFileSize(user.trafficLeft()),
                user.tunnelLimit() > 0
                        ? I18N.get("frp.r0009", tunnels.size(), user.tunnelLimit()) : ""));
        logoutBtn.setVisible(true);
        logoutBtn.setManaged(true);
    }

    // 隧道渲染 //

    private void renderTunnels() {
        if (tunnelList == null) return;
        tunnelList.getChildren().clear();
        tunnelSummary.setText(connected ? I18N.get("frp.r0005", tunnels.size()) : I18N.get("page.t0707"));

        if (!connected) {
            Label empty = new Label(I18N.get("page.t0498"));
            empty.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-faint; -fx-padding: 8 0;");
            tunnelList.getChildren().add(empty);
        } else if (tunnels.isEmpty()) {
            Label empty = new Label(I18N.get("page.t0499"));
            empty.setWrapText(true);
            empty.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-faint; -fx-padding: 8 0;");
            tunnelList.getChildren().add(empty);
        } else {
            for (SakuraFrpApi.Tunnel tunnel : tunnels) {
                tunnelList.getChildren().add(buildTunnelRow(tunnel));
            }
        }

        boolean canOperate = connected && !busy;
        refreshBtn.setDisable(!connected || busy);
        createBtn.setDisable(!canOperate);
        tokenField.setDisable(busy);
        logoutBtn.setDisable(busy);
        connectBtn.setDisable(busy);
    }

    private Node buildTunnelRow(SakuraFrpApi.Tunnel tunnel) {
        SakuraFrpApi.Node node = nodes.get(tunnel.node());
        String address = tunnel.publicAddress(node == null ? "" : node.host());
        boolean running = service.isRunning() && service.runningTunnelId() == tunnel.id();
        if (running && !service.runningAddress().isBlank()) address = service.runningAddress();
        final String tunnelAddress = address;

        VBox card = new VBox(8);
        card.setStyle("-fx-background-color: rgba(0,0,0,0.02); -fx-background-radius: 10; "
                + "-fx-padding: 10 12;");

        HBox top = new HBox(10);
        top.setAlignment(Pos.CENTER_LEFT);
        Region dot = new Region();
        dot.setMinSize(9, 9);
        dot.setPrefSize(9, 9);
        dot.setMaxSize(9, 9);
        String dotColor = tunnel.banned() ? "#ef4444" : running ? "#10b981"
                : tunnel.online() ? "#3b82f6" : "#94a3b8";
        dot.setStyle("-fx-background-radius: 999; -fx-background-color: " + dotColor + ";");

        Label name = new Label(tunnel.name() == null || tunnel.name().isBlank()
                ? I18N.get("page.p0878", tunnel.id()) : tunnel.name());
        name.setStyle("-fx-font-size: 13px; -fx-font-weight: 600;");
        name.setMinWidth(0);
        Label type = new Label(tunnel.type().toUpperCase());
        type.setStyle("-fx-font-size: 10px; -fx-text-fill: #6b7280; -fx-background-color: rgba(0,0,0,0.06); "
                + "-fx-background-radius: 5; -fx-padding: 1 6;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label state = new Label(tunnel.banned() ? I18N.get("page.t0708") : running ? I18N.get("page.t0709")
                : tunnel.online() ? I18N.get("page.t0710") : I18N.get("page.t0711"));
        state.setStyle("-fx-font-size: 11px; -fx-font-weight: 600; -fx-text-fill: " + dotColor + ";");
        top.getChildren().addAll(dot, name, type, spacer, state);

        HBox bottom = new HBox(8);
        bottom.setAlignment(Pos.CENTER_LEFT);
        String nodeName = node == null ? I18N.get("frp.r0006", tunnel.node()) : node.name();
        Label meta = new Label(I18N.get("frp.r0007", nodeName, tunnel.localIp(), tunnel.localPort(),
                (tunnelAddress.isBlank() ? I18N.get("page.t0712") : tunnelAddress)));
        meta.setStyle("-fx-font-size: 11px; -fx-text-fill: #6b7280;");
        meta.setWrapText(true);
        meta.setMinWidth(0);
        HBox.setHgrow(meta, Priority.ALWAYS);

        Button copyBtn = smallButton("copy", I18N.get("page.t0500"), "#3b82f6");
        copyBtn.setDisable(tunnelAddress.isBlank());
        copyBtn.setOnAction(e -> copyAddress(tunnelAddress));
        Button editBtn = smallButton("edit", I18N.get("page.t0501"), "#6b7280");
        editBtn.setDisable(busy || tunnel.banned());
        editBtn.setOnAction(e -> openEditDialog(tunnel));
        Button deleteBtn = smallButton("trash", I18N.get("page.t0502"), "#ef4444");
        deleteBtn.setDisable(busy);
        deleteBtn.setOnAction(e -> confirmDelete(tunnel));

        Button toggleBtn;
        if (running) {
            toggleBtn = smallButton("stop", I18N.get("page.t0503"), "#ef4444");
            toggleBtn.setOnAction(e -> stopTunnel());
        } else {
            toggleBtn = smallButton("play", I18N.get("page.t0504"), "#10b981");
            toggleBtn.setDisable(busy || !connected || tunnel.banned());
            toggleBtn.setOnAction(e -> startTunnel(tunnel));
        }
        bottom.getChildren().addAll(meta, copyBtn, editBtn, deleteBtn, toggleBtn);

        card.getChildren().addAll(top, bottom);
        return card;
    }

    private Button smallButton(String icon, String text, String color) {
        Button button = AppIcons.button(icon, text);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setStyle("-fx-background-color: rgba(0,0,0,0.04); -fx-background-radius: 6; "
                + "-fx-text-fill: " + color + "; -fx-font-size: 11px; -fx-padding: 4 9;");
        return button;
    }

    private void copyAddress(String address) {
        if (address == null || address.isBlank()) {
            ctx.ui().toast(I18N.get("page.t0505"));
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(address);
        Clipboard.getSystemClipboard().setContent(content);
        ctx.ui().toast(I18N.get("page.p0879", address));
        addLog(I18N.get("page.p0880", address));
    }

    // 启动 / 停止 //

    private void startTunnel(SakuraFrpApi.Tunnel tunnel) {
        if (busy || !connected) return;
        setBusy(true);
        if (service.isRunning()) service.stop();
        setStatus("waiting", I18N.get("page.t0713"), I18N.get("page.t0714"));
        addLog(I18N.get("page.p0881", tunnel.name()));
        // 记住默认隧道，插件 API net/frp/start 与 CLI `frp` 未传 ID 时使用
        ctx.config().put("SakuraFrpTunnelId", String.valueOf(tunnel.id()));
        ctx.saveConfig();
        service.startTunnelAsync(tunnel.id(), this::addLog).whenComplete((result, error) -> Platform.runLater(() -> {
            setBusy(false);
            if (error != null) {
                Throwable cause = unwrap(error);
                setStatus("error", I18N.get("page.t0506"), safeMessage(cause));
                addLog(I18N.get("page.p0882", safeMessage(cause)));
                ctx.ui().toast(I18N.get("page.p0883", safeMessage(cause)));
                renderTunnels();
                return;
            }
            setStatus("active", I18N.get("page.t0715"), I18N.get("page.t0716"));
            addLog(I18N.get("page.p0884", result.tunnelName() + " → " + result.publicAddress()));
            if (!result.publicAddress().isBlank()) copyAddress(result.publicAddress());
            renderTunnels();
        }));
    }

    private void stopTunnel() {
        if (busy) return;
        setBusy(true);
        addLog(I18N.get("page.t0507"));
        service.stop();
        setStatus("idle", I18N.get("page.t0717"), I18N.get("page.t0718"));
        addLog(I18N.get("page.t0508"));
        setBusy(false);
        renderTunnels();
    }

    // 新建 / 编辑 / 删除 //

    private void openCreateDialog() {
        if (!connected) {
            ctx.ui().toast(I18N.get("page.t0509"));
            return;
        }
        List<SakuraFrpApi.Node> candidates = new ArrayList<>();
        for (SakuraFrpApi.Node node : nodes.values()) {
            if (node.canCreate()) candidates.add(node);
        }
        boolean fallbackAll = candidates.isEmpty();
        if (fallbackAll) candidates.addAll(nodes.values());
        if (candidates.isEmpty()) {
            ctx.ui().toast(I18N.get("page.t0510"));
            return;
        }

        TextField nameField = new TextField(I18N.get("page.t0511"));
        nameField.getStyleClass().add("input-field");
        ComboBox<String> typeBox = new ComboBox<>();
        typeBox.getItems().addAll("tcp", "udp", "http", "https");
        typeBox.setValue("tcp");
        typeBox.getStyleClass().add("select-field");
        typeBox.setMaxWidth(Double.MAX_VALUE);

        ComboBox<NodeChoice> nodeBox = new ComboBox<>();
        for (SakuraFrpApi.Node node : candidates) {
            nodeBox.getItems().add(new NodeChoice(node, fallbackAll));
        }
        nodeBox.setValue(nodeBox.getItems().get(0));
        nodeBox.getStyleClass().add("select-field");
        nodeBox.setMaxWidth(Double.MAX_VALUE);

        TextField localIpField = new TextField("127.0.0.1");
        localIpField.getStyleClass().add("input-field");
        TextField localPortField = new TextField(
                ctx.config().getOrDefault("SakuraFrpLocalPort", "25565"));
        localPortField.getStyleClass().add("input-field");
        TextField remoteField = new TextField();
        remoteField.setPromptText(I18N.get("page.t0512"));
        remoteField.getStyleClass().add("input-field");

        Label warning = new Label(I18N.get("page.t0556"));
        warning.setWrapText(true);
        warning.setStyle("-fx-font-size: 11px; -fx-text-fill: #d97706; -fx-background-color: rgba(245,158,11,0.10); "
                + "-fx-background-radius: 6; -fx-padding: 8 10;");

        VBox form = new VBox(10,
                labeledField(I18N.get("page.t0719"), nameField),
                labeledField(I18N.get("page.t0720"), typeBox),
                labeledField(I18N.get("page.t0721"), nodeBox),
                labeledField(I18N.get("page.t0722"), localIpField),
                labeledField(I18N.get("page.t0723"), localPortField),
                labeledField(I18N.get("page.t0724"), remoteField),
                warning);
        form.setPadding(new Insets(4, 2, 4, 2));

        HBox actions = new HBox(10);
        Button cancel = new Button(I18N.get("page.t0513"));
        cancel.getStyleClass().add("modal-btn-cancel");
        cancel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(cancel, Priority.ALWAYS);
        cancel.setOnAction(e -> ctx.ui().closeModal());
        Button ok = new Button(I18N.get("page.t0514"));
        ok.getStyleClass().add("modal-btn-ok");
        ok.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(ok, Priority.ALWAYS);
        ok.setOnAction(e -> {
            String name = nameField.getText() == null ? "" : nameField.getText().trim();
            String type = typeBox.getValue();
            NodeChoice choice = nodeBox.getValue();
            String localIp = localIpField.getText() == null ? "" : localIpField.getText().trim();
            int localPort = parsePort(localPortField.getText());
            String remote = remoteField.getText() == null ? "" : remoteField.getText().trim();
            if (name.isEmpty()) {
                ctx.ui().toast(I18N.get("page.t0515"));
                return;
            }
            if (choice == null) {
                ctx.ui().toast(I18N.get("page.t0516"));
                return;
            }
            if (localIp.isEmpty()) localIp = "127.0.0.1";
            if (localPort <= 0) {
                ctx.ui().toast(I18N.get("page.t0517"));
                return;
            }
            if (("http".equals(type) || "https".equals(type)) && remote.isEmpty()) {
                ctx.ui().toast(I18N.get("page.t0518"));
                return;
            }
            if (("tcp".equals(type) || "udp".equals(type)) && !remote.isEmpty() && parsePort(remote) <= 0) {
                ctx.ui().toast(I18N.get("page.t0519"));
                return;
            }
            ok.setDisable(true);
            ok.setText(I18N.get("page.t0520"));
            final String finalLocalIp = localIp;
            final int finalLocalPort = localPort;
            final String finalRemote = remote;
            ctx.config().put("SakuraFrpLocalPort", String.valueOf(localPort));
            ctx.saveConfig();
            CompletableFuture.supplyAsync(() -> {
                try {
                    return api.createTunnel(token, name, type, choice.node.id(),
                            finalLocalIp, finalLocalPort, finalRemote, "");
                } catch (Exception ex) {
                    throw new CompletionException(ex);
                }
            }).whenComplete((id, error) -> Platform.runLater(() -> {
                if (error != null) {
                    Throwable cause = unwrap(error);
                    ok.setDisable(false);
                    ok.setText(I18N.get("page.t0521"));
                    ctx.ui().toast(I18N.get("page.p0885", safeMessage(cause)));
                    addLog(I18N.get("page.p0886", safeMessage(cause)));
                    return;
                }
                ctx.ui().closeModal();
                addLog(I18N.get("page.p0887", name + "（ID " + id + "）"));
                ctx.ui().toast(I18N.get("page.t0522"));
                refreshTunnels();
            }));
        });
        actions.getChildren().addAll(cancel, ok);
        VBox body = new VBox(12, form, actions);
        ctx.ui().modal(I18N.get("page.t0523"), body, 520, -1);
    }

    private void openEditDialog(SakuraFrpApi.Tunnel tunnel) {
        TextField localIpField = new TextField(tunnel.localIp());
        localIpField.getStyleClass().add("input-field");
        TextField localPortField = new TextField(String.valueOf(tunnel.localPort()));
        localPortField.getStyleClass().add("input-field");
        TextField noteField = new TextField(tunnel.note() == null ? "" : tunnel.note());
        noteField.setPromptText(I18N.get("page.t0524"));
        noteField.getStyleClass().add("input-field");

        VBox form = new VBox(10,
                labeledField(I18N.get("page.t0725"), localIpField),
                labeledField(I18N.get("page.t0726"), localPortField),
                labeledField(I18N.get("page.t0727"), noteField));
        form.setPadding(new Insets(4, 2, 4, 2));
        Label hint = new Label(I18N.get("page.t0525"));
        hint.setWrapText(true);
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: -sl-text-faint;");
        form.getChildren().add(hint);

        HBox actions = new HBox(10);
        Button cancel = new Button(I18N.get("page.t0526"));
        cancel.getStyleClass().add("modal-btn-cancel");
        cancel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(cancel, Priority.ALWAYS);
        cancel.setOnAction(e -> ctx.ui().closeModal());
        Button ok = new Button(I18N.get("page.t0527"));
        ok.getStyleClass().add("modal-btn-ok");
        ok.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(ok, Priority.ALWAYS);
        ok.setOnAction(e -> {
            String localIp = localIpField.getText() == null ? "" : localIpField.getText().trim();
            int localPort = parsePort(localPortField.getText());
            if (localIp.isEmpty()) localIp = "127.0.0.1";
            if (localPort <= 0) {
                ctx.ui().toast(I18N.get("page.t0528"));
                return;
            }
            ok.setDisable(true);
            ok.setText(I18N.get("page.t0529"));
            final String finalIp = localIp;
            final int finalPort = localPort;
            final String note = noteField.getText() == null ? "" : noteField.getText().trim();
            CompletableFuture.runAsync(() -> {
                try {
                    api.editTunnel(token, tunnel.id(), note, finalIp, finalPort);
                } catch (Exception ex) {
                    throw new CompletionException(ex);
                }
            }).whenComplete((ignored, error) -> Platform.runLater(() -> {
                if (error != null) {
                    Throwable cause = unwrap(error);
                    ok.setDisable(false);
                    ok.setText(I18N.get("page.t0530"));
                    ctx.ui().toast(I18N.get("page.p0888", safeMessage(cause)));
                    return;
                }
                ctx.ui().closeModal();
                addLog(I18N.get("page.p0889", tunnel.name()));
                refreshTunnels();
            }));
        });
        actions.getChildren().addAll(cancel, ok);
        VBox body = new VBox(12, form, actions);
        ctx.ui().modal(I18N.get("page.p0890", tunnel.name()), body, 460, -1);
    }

    private void confirmDelete(SakuraFrpApi.Tunnel tunnel) {
        ctx.ui().confirm(I18N.get("page.t0728"), I18N.get("frp.r0008", tunnel.name()),
                I18N.get("page.t0729"), true, () -> {
                    if (service.isRunning() && service.runningTunnelId() == tunnel.id()) service.stop();
                    addLog(I18N.get("page.p0891", tunnel.name()));
                    CompletableFuture.runAsync(() -> {
                        try {
                            api.deleteTunnel(token, tunnel.id());
                        } catch (Exception ex) {
                            throw new CompletionException(ex);
                        }
                    }).whenComplete((ignored, error) -> Platform.runLater(() -> {
                        if (error != null) {
                            Throwable cause = unwrap(error);
                            addLog(I18N.get("page.p0892", safeMessage(cause)));
                            ctx.ui().toast(I18N.get("page.p0893", safeMessage(cause)));
                            return;
                        }
                        addLog(I18N.get("page.p0894", tunnel.name()));
                        ctx.ui().toast(I18N.get("page.t0531"));
                        refreshTunnels();
                    }));
                });
    }

    // frpc 操作 //

    private void downloadFrpc() {
        downloadFrpcBtn.setDisable(true);
        setBusy(true);
        renderTunnels();
        frpcProgress.setVisible(true);
        frpcProgress.setManaged(true);
        frpcProgress.setProgress(0);
        addLog(I18N.get("page.t0532"));
        service.downloadFrpc(pct -> Platform.runLater(() -> frpcProgress.setProgress(pct / 100.0)))
                .whenComplete((ignored, error) -> Platform.runLater(() -> {
                    setBusy(false);
                    downloadFrpcBtn.setDisable(false);
                    frpcProgress.setVisible(false);
                    frpcProgress.setManaged(false);
                    updateFrpcStatus();
                    if (error != null) {
                        Throwable cause = unwrap(error);
                        addLog(I18N.get("page.p0895", safeMessage(cause)));
                        ctx.ui().toast(I18N.get("page.p0896", safeMessage(cause)));
                        return;
                    }
                    addLog(I18N.get("page.t0533"));
                    ctx.ui().toast(I18N.get("page.t0534"));
                }));
    }

    private void updateFrpcStatus() {
        if (frpcStatus == null) return;
        boolean installed = service.isFrpcInstalled();
        String installedVersion = service.installedVersion();
        StringBuilder text = new StringBuilder(installed ? I18N.get("page.t0730") : I18N.get("page.t0731"));
        if (!installedVersion.isBlank()) text.append(" · ").append(installedVersion);
        if (!latestFrpcVersion.isBlank() && !latestFrpcVersion.equals(installedVersion)) {
            text.append(I18N.get("page.t0535")).append(latestFrpcVersion).append("）");
        }
        if (!installed) text.append(I18N.get("page.t0536"));
        frpcStatus.setText(text.toString());
    }

    private void openFrpcFolder() {
        try {
            Files.createDirectories(service.frpDir());
            ctx.openFile(service.frpDir().toString());
        } catch (IOException e) {
            ctx.ui().toast(I18N.get("page.p0897", e.getMessage()));
        }
    }

    // 小工具 //

    private VBox labeledField(String label, Node control) {
        Label title = new Label(label);
        title.setStyle("-fx-font-size: 12px; -fx-text-fill: #6b7280;");
        VBox box = new VBox(4, title, control);
        if (control instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }
        return box;
    }

    private void setBusy(boolean value) {
        this.busy = value;
        if (connectBtn != null) connectBtn.setDisable(value);
        if (refreshBtn != null) refreshBtn.setDisable(value || !connected);
        if (createBtn != null) createBtn.setDisable(value || !connected);
        if (downloadFrpcBtn != null) {
            downloadFrpcBtn.setDisable(value || (frpcProgress != null && frpcProgress.isVisible()));
        }
        if (payloadControlsReady()) {
            tokenField.setDisable(value);
            logoutBtn.setDisable(value);
        }
        if (!value) renderTunnels();
    }

    private boolean payloadControlsReady() {
        return tokenField != null && logoutBtn != null;
    }

    private void setStatus(String kind, String title, String detail) {
        String color = switch (kind) {
            case "waiting" -> "#f59e0b";
            case "active" -> "#10b981";
            case "error" -> "#ef4444";
            default -> "#94a3b8";
        };
        setDot(color);
        if (statusText != null) {
            statusText.setText(title);
            statusText.setStyle("-fx-font-size: 13px; -fx-font-weight: 600; -fx-text-fill: " + color + ";");
        }
        if (accountLine != null && detail != null && !detail.isBlank() && !connected) {
            accountLine.setText(detail);
        }
        if (accountDetail != null && detail != null && connected && user == null) {
            accountDetail.setText(detail);
        }
    }

    private void setDot(String color) {
        if (statusDot == null) return;
        statusDot.setStyle("-fx-background-radius: 999; -fx-background-color: " + color + ";");
    }

    private static int parsePort(String text) {
        try {
            int port = Integer.parseInt(text == null ? "" : text.trim());
            return port > 0 && port <= 65535 ? port : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message == null || message.isBlank()) return I18N.get("page.t0537");
        return message;
    }

    // 异步快照 //

    private record AccountSnapshot(SakuraFrpApi.UserInfo user,
                                   Map<Integer, SakuraFrpApi.Node> nodes,
                                   List<SakuraFrpApi.Tunnel> tunnels,
                                   String latestFrpcVersion) {
    }

    private record TunnelSnapshot(Map<Integer, SakuraFrpApi.Node> nodes,
                                  List<SakuraFrpApi.Tunnel> tunnels) {
    }

    /** 节点下拉项：默认只列可创建隧道的节点；满载兜底时在文案里提示 */
    private record NodeChoice(SakuraFrpApi.Node node, boolean fallback) {
        @Override
        public String toString() {
            String suffix = node.allowsPublicGame() ? I18N.get("page.t0538") : "";
            if (fallback) suffix += I18N.get("page.t0539");
            return node.name() + "（" + node.host() + "）" + suffix;
        }
    }
}
