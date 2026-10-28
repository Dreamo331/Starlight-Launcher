package com.example.starlight.newui.page;


import com.example.starlight.util.I18N;
import com.example.starlight.auth.AccountManager;
import com.example.starlight.auth.AccountManager.Account;
import com.example.starlight.config.GameDirManager;
import com.example.starlight.config.SelectedVersion;
import com.example.starlight.launch.LaunchRecord;
import com.example.starlight.gui.UIGeneralControlClass;
import com.example.starlight.gui.UIGeneralControlClass.LaunchConfig;
import com.example.starlight.launch.LaunchRecordStore;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.ui.LaunchPillButton;
import com.example.starlight.newui.ui.PageKit;
import com.example.starlight.newui.ui.VersionIconKit;
import com.example.starlight.util.AvatarGenerator;
import com.example.starlight.util.FormatUtils;
import com.example.starlight.util.MojangAvatarFetcher;
import com.example.starlight.util.NetworkStatusService;
import com.example.starlight.util.ResourceScanner;
import com.example.starlight.util.SystemInfoMonitor;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.shape.Rectangle;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.File;
import java.util.List;
import javafx.util.Duration;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 首页：快捷启动（最近游玩）、游戏信息卡、快捷工具、系统监控条。
 *
 * <p>从 LauncherView 抽离（方法体逐字搬运，逻辑、样式类与内联样式均未改动）。
 * 系统监控控件引用（cpuLabel / memLabel / diskLabel / cpuBar / memBar）随页面迁入本类；
 * 主壳保留 {@code homePage} 单例，{@code refreshHomePage()} 委派到本页。
 */
public final class HomePage {

    private final LauncherContext host;

    private Label cpuLabel, memLabel, diskLabel;
    private Region cpuBar, memBar;

    /** 首页当前的启动按钮（每次重建首页都会换新节点） */
    private LaunchPillButton launchButton;
    /** 启动状态监听的注销句柄（重建前必须先注销，避免旧按钮被继续驱动） */
    private Runnable launchStateUnregister;

    public HomePage(LauncherContext host) {
        this.host = host;
    }

    /** 启动状态 → 启动按钮外观（只动按钮，弹窗与启动流程都由 LauncherView 负责） */
    private void onLaunchStateChanged(LauncherContext.LaunchState state) {
        if (launchButton == null) return;
        switch (state) {
            case STARTED -> launchButton.showLoading();
            case SUCCEEDED -> launchButton.showSuccess();
            case FAILED -> launchButton.resetIdle();
        }
    }

    public Node build() {
        // 每次访问首页都重新检测节日（跨天时能自动切换）；开关关闭时保持默认
        com.example.starlight.newui.festival.Festival activeFestival = null;
        try {
            if (com.example.starlight.newui.festival.FestivalCalendar.isEnabled(host.config())) {
                activeFestival = com.example.starlight.newui.festival.FestivalCalendar.current();
            }
        } catch (Exception ignored) {
            // 配置读取异常时保持默认，不影响首页渲染
        }

        HBox layout = new HBox(20);
        layout.getStyleClass().add("home-layout");
        layout.setPadding(new Insets(0, 4, 4, 0));
        HBox.setHgrow(layout, Priority.ALWAYS);

        VBox left = new VBox(14);
        left.setPrefWidth(280);
        left.setMinWidth(250);
        left.setMaxWidth(280);

        // 用户卡片
        VBox userCard = new VBox(14);
        userCard.getStyleClass().add("home-card-left-item");
        HBox userHeader = new HBox(14);
        userHeader.setAlignment(Pos.CENTER_LEFT);
        // 头像：由账号皮肤生成（复刻 HMCL drawAvatar），无皮肤时按 UUID 哈希选内置默认皮肤
        StackPane avatarBox = new StackPane();
        avatarBox.getStyleClass().add("home-avatar");
        Label avatarFallback = new Label();
        // 颜色由 .home-avatar 样式控制（透明背景 + 灰色占位，无蓝色底）
        AppIcons.apply(avatarFallback, "avatar", 24, null);
        avatarBox.getChildren().add(avatarFallback);
        avatarBox.setCursor(javafx.scene.Cursor.HAND);
        avatarBox.setOnMouseClicked(e -> host.switchToSettings("sidebarAccount"));
        loadHomeAvatar(avatarBox, 52);
        VBox nameBox = new VBox(3);
        // 名字列可以被压窄（超长用户名允许省略），但整体不许把右侧按钮挤掉
        nameBox.setMinWidth(0);
        HBox.setHgrow(nameBox, Priority.ALWAYS);
        Label welcome = new Label(activeFestival != null ? activeFestival.greeting : I18N.get("page.t0220"));
        welcome.getStyleClass().add("home-welcome");
        if (activeFestival != null) {
            welcome.getStyleClass().add("festival-welcome");
            welcome.setStyle("-fx-text-fill: " + activeFestival.greetingColor + ";");
        }
        // 用户名单独一行，占满剩余宽度：账号类型徽标移到名字下方，
        // 不再和用户名抢横向空间（之前名字被挤成「Dreamo_...」）
        Label username = new Label(getCurrentUsername());
        username.getStyleClass().add("home-username");
        username.setMinWidth(60);
        username.setMaxWidth(Double.MAX_VALUE);
        username.setTextOverrun(OverrunStyle.ELLIPSIS);
        username.setTooltip(new Tooltip(getCurrentUsername()));
        nameBox.getChildren().addAll(welcome, username);
        Account homeAccount = AccountManager.getCurrentAccount();
        if (homeAccount != null) {
            Label typeBadge = new Label(homeAccount.getTypeLabel());
            typeBadge.getStyleClass().add("home-account-badge");
            typeBadge.setMinWidth(Region.USE_PREF_SIZE);
            typeBadge.setMaxWidth(Region.USE_PREF_SIZE);
            // 认证方式图标：微软用官方彩色 logo（PNG），离线 / 第三方用 SVG 图标（跟随徽标文字色）
            AppIcons.apply(typeBadge, switch (homeAccount.type) {
                case MICROSOFT -> "microsoft";
                case OFFLINE -> "user";
                case THIRD_PARTY -> "globe";
            }, 12, null);
            // 名字下方左对齐显示
            HBox badgeRow = new HBox(typeBadge);
            badgeRow.setAlignment(Pos.CENTER_LEFT);
            nameBox.getChildren().add(badgeRow);
        }
        userHeader.getChildren().addAll(avatarBox, nameBox);
        userCard.getChildren().add(userHeader);

        // 最近游玩（从 launcher.json 读取最近3条记录）
        VBox recentCard = new VBox(6);
        recentCard.getStyleClass().add("home-card-left-item");
        Label recentTitle = new Label(I18N.get("page.t0221"));
        recentTitle.getStyleClass().add("home-recent-title");
        recentCard.getChildren().add(recentTitle);
        // 记录行容器：不足3条时补透明占位行，卡片高度始终稳定为3条记录的样子
        VBox recentList = new VBox(6);
        java.util.List<LaunchRecord> records = LaunchRecordStore.load();
        // 自动清理版本已删除（目录不存在）的失效记录，避免残留无效条目
        if (records.removeIf(rec -> !LaunchRecordStore.versionExists(rec))) {
            LaunchRecordStore.persist(records);
        }
        if (records.isEmpty()) {
            HBox emptyRow = new HBox();
            emptyRow.getStyleClass().add("home-recent-item");
            emptyRow.setAlignment(Pos.CENTER_LEFT);
            Label empty = new Label(I18N.get("page.t0222"));
            empty.setStyle("-fx-text-fill: -sl-text-faint;");
            emptyRow.getChildren().add(empty);
            recentList.getChildren().add(emptyRow);
        } else {
            for (LaunchRecord rec : records) {
                HBox item = new HBox();
                item.getStyleClass().add("home-recent-item");
                item.setAlignment(Pos.CENTER_LEFT);
                VBox info = new VBox(2);
                Label name = new Label(rec.version);
                name.getStyleClass().add("home-recent-name");
                String sub = (rec.loaderType != null && !rec.loaderType.isEmpty() ? rec.loaderType + " | " : "") + rec.launchTime;
                Label time = new Label(sub);
                time.getStyleClass().add("home-recent-time");
                info.getChildren().addAll(name, time);
                HBox.setHgrow(info, Priority.ALWAYS);
                // 移除按钮（悬停显示）：点击确认后删除该条记录
                Label removeBtn = new Label();
                AppIcons.apply(removeBtn, "close", 12, null);
                removeBtn.getStyleClass().add("home-recent-remove");
                removeBtn.setCursor(javafx.scene.Cursor.HAND);
                LaunchRecord finalRec = rec;
                removeBtn.setOnMouseClicked(e -> host.confirmDelete(
                        I18N.get("hp.r0001", finalRec.version), () -> {
                    LaunchRecordStore.removeIf(r -> r.version.equals(finalRec.version)
                            && java.util.Objects.equals(r.gameDir, finalRec.gameDir));
                    host.ui().toast(I18N.get("page.p0818", finalRec.version));
                    host.refreshHomePage();
                }));
                Label playBtn = new Label();
                AppIcons.apply(playBtn, "play", 12, null);
                playBtn.getStyleClass().add("home-recent-play");
                playBtn.setCursor(javafx.scene.Cursor.HAND);
                playBtn.setOnMouseClicked(e -> host.launchGame(LaunchRecordStore.toLaunchConfig(finalRec)));
                item.getChildren().addAll(info, removeBtn, playBtn);
                recentList.getChildren().add(item);
            }
        }
        // 不足3行时补透明占位行（行高与真实行一致），保证卡片尺寸稳定
        for (int i = recentList.getChildren().size(); i < 3; i++) {
            HBox placeholder = new HBox();
            placeholder.getStyleClass().add("home-recent-item");
            placeholder.setOpacity(0.0);
            recentList.getChildren().add(placeholder);
        }
        recentCard.getChildren().add(recentList);

        // 启动按钮：线框胶囊；开始启动后收成圆形加载环，成功后短暂显示对勾再展开
        // （只改按钮自身外观；启动弹窗与启动流程都在 LauncherView，不受影响）
        VBox launchCard = new VBox();
        launchCard.getStyleClass().add("home-card-left-item");
        LaunchPillButton launchBtn = new LaunchPillButton(
                activeFestival != null ? activeFestival.icon : "play",
                activeFestival != null ? activeFestival.launchLabel() : I18N.get("page.t0223"));
        if (activeFestival != null) launchBtn.getStyleClass().add("festival-active");
        launchBtn.setOnAction(e -> host.launchGame());
        if (activeFestival != null) {
            // 节日按钮配色：从节日定义取渐变 + 文字色（内联，避免为每个节日写 CSS）
            // 常规态是线框胶囊；节日态填满渐变色，所以把线框边框设为透明
            launchBtn.setStyle("-fx-background-color: linear-gradient(to right, "
                    + activeFestival.gradientA + ", " + activeFestival.gradientB + ");"
                    + " -fx-text-fill: " + activeFestival.buttonText + ";"
                    + " -fx-border-color: transparent;");
        }
        // 首页每次重建都会换一个新按钮：先注销旧监听，再把监听挂到本节点上
        if (launchStateUnregister != null) {
            launchStateUnregister.run();
            launchStateUnregister = null;
        }
        launchButton = launchBtn;
        launchStateUnregister = host.addLaunchStateListener(this::onLaunchStateChanged);
        // 收成圆形时按钮仍居中：外层 StackPane 负责定位，宽度动画在按钮内部
        StackPane launchWrap = new StackPane(launchBtn);
        launchWrap.setAlignment(Pos.CENTER);
        launchCard.getChildren().add(launchWrap);
        left.getChildren().add(userCard);
        left.getChildren().addAll(recentCard, launchCard);

        // 右侧内容
        VBox right = new VBox(12);
        right.setPadding(new Insets(0, 4, 0, 0));
        HBox.setHgrow(right, Priority.ALWAYS);

        // 「详细信息」放进标题栏：正文区只保留 CPU/内存/磁盘三项，才能整体居中
        right.getChildren().add(createHomeCard("system-info", I18N.get("page.t0224"),
                buildSystemDetailButton(), this::buildSystemStats));

        // 快速进入存档（高级设置「主页快速进入存档」开关控制）：位于系统信息卡片下方
        if ("true".equalsIgnoreCase(host.config().getOrDefault("HomeQuickLaunchSave", "false"))) {
            right.getChildren().add(createHomeCard("gamepad", I18N.get("page.t0225"), this::buildQuickSaveContent));
        }

        right.getChildren().add(createHomeCard("version-select", I18N.get("page.t0226"), () -> {
            HBox ver = new HBox(14);
            ver.setAlignment(Pos.CENTER_LEFT);
            ver.getStyleClass().add("home-ver-card");
            // 图标跟随当前版本：整合包用包图标，其余按加载器（原版用草方块）
            // 版本选择真源在当前游戏目录内的 starlight.json
            String curVersion = SelectedVersion.of(host.config());
            Node versionIcon = curVersion.isBlank()
                    ? versionIconPlaceholder()
                    : VersionIconKit.icon(VersionIconKit.read(
                            GameDirManager.activePath(host.config()), curVersion), 32);
            VBox verInfo = new VBox(2);
            verInfo.getStyleClass().add("home-ver-info");
            Label verTitle = new Label(curVersion.isBlank() ? I18N.get("page.t0227") : curVersion);
            verTitle.getStyleClass().add("home-ver-title");
            Label verDesc = new Label(I18N.get("page.t0228"));
            verDesc.getStyleClass().add("home-ver-desc");
            verInfo.getChildren().addAll(verTitle, verDesc);
            Button switchBtn = AppIcons.button("refresh", I18N.get("page.t0229"));
            switchBtn.getStyleClass().add("btn-primary");
            // 锁固有宽度：不写内联 min-width（会覆盖宽度锁导致「切…」）
            switchBtn.setStyle("-fx-padding: 4 12;");
            switchBtn.setMinWidth(Region.USE_PREF_SIZE);
            switchBtn.setMaxWidth(Region.USE_PREF_SIZE);
            switchBtn.setOnAction(e -> host.switchToPage("versionSelect"));
            HBox.setHgrow(verInfo, Priority.ALWAYS);
            ver.getChildren().addAll(versionIcon, verInfo, switchBtn);
            return ver;
        }));

        right.getChildren().add(createHomeCard("gear", I18N.get("page.t0230"), () -> {
            HBox setting = new HBox(14);
            setting.setAlignment(Pos.CENTER_LEFT);
            setting.getStyleClass().add("home-ver-card");
            VBox info = new VBox(2);
            info.getStyleClass().add("home-ver-info");
            // 说明列可被压缩；JVM 参数可能很长，单行省略 + 悬停看全文（不再换行撑高卡片）
            info.setMinWidth(0);
            String maxMem = host.config().getOrDefault("MaxMemory", "4096");
            Label title = new Label(I18N.get("page.t0231"));
            title.getStyleClass().add("home-ver-title");
            // JVM 参数显示真实配置值（首页每次访问实时重建，始终反映最新保存的配置）
            String jvmShow = host.config().getOrDefault("JvmArgs", "");
            String jvmDesc = (jvmShow == null || jvmShow.trim().isEmpty()) ? I18N.get("page.t0232") : jvmShow;
            Label desc = new Label(I18N.get("page.p0819", maxMem + "MB | JVM: " + jvmDesc));
            desc.getStyleClass().add("home-ver-desc");
            PageKit.cardTooltip(desc);
            info.getChildren().addAll(title, desc);
            Button setBtn = AppIcons.button("gear", I18N.get("page.t0233"));
            setBtn.getStyleClass().add("btn-primary");
            setBtn.setMinWidth(Region.USE_PREF_SIZE);
            setBtn.setMaxWidth(Region.USE_PREF_SIZE);
            setBtn.setOnAction(e -> host.switchToSettings("sidebarJvm"));
            HBox.setHgrow(info, Priority.ALWAYS);
            setting.getChildren().addAll(info, setBtn);
            return setting;
        }));

        right.getChildren().add(createHomeCard("antenna", I18N.get("page.t0234"), () -> {
            HBox net = new HBox();
            net.setAlignment(Pos.CENTER);
            net.setSpacing(40);
            String source = NetworkStatusService.currentSourceName();
            Label statusLabel;
            // 「当前」一栏显示当前下载源的真实连通状态：有 30 秒内的测速缓存直接用，
            // 否则先占位「检测中...」并在后台补测一次（此前这里写死「空闲」，永远不刷新）
            NetworkStatusService.SourceResult cached = NetworkStatusService.isCacheFresh()
                    ? NetworkStatusService.currentSourceCached() : null;
            statusLabel = createInfoLabel(I18N.get("page.t0607"), cached != null ? cached.describe() : I18N.get("page.t0608"));
            net.getChildren().addAll(createInfoLabel(I18N.get("page.t0235"), source), statusLabel);
            if (cached == null) {
                NetworkStatusService.testCurrentSourceAsync().thenAccept(r -> javafx.application.Platform.runLater(() ->
                        statusLabel.setText(I18N.get("page.p0820", (r == null ? I18N.get("page.t0609") : r.describe())))));
            }
            return net;
        }));

        right.getChildren().add(createHomeCard("quick-tools", I18N.get("page.t0236"), () -> {
            HBox tools = new HBox(10);
            tools.setAlignment(Pos.CENTER);
            tools.getStyleClass().add("home-tools-grid");
            String[][] toolData = {{"memory", I18N.get("page.t0237")}, {"globe", I18N.get("page.t0238")}, {"clapper", I18N.get("page.t0239")}, {"wrench", I18N.get("page.t0240")}, {"bolt", I18N.get("page.t0241")}, {"view", I18N.get("page.t0242")}};
            for (String[] t : toolData) {
                VBox tool = new VBox(4);
                tool.getStyleClass().add("home-tool-item");
                tool.setAlignment(Pos.CENTER);
                // 六个入口挤在一行：单格宽度从 80 收到 70，避免最后一个被卡片边缘裁掉
                tool.setPrefWidth(70);
                Label iconLabel = new Label();
                AppIcons.apply(iconLabel, t[0], 20, null);
                iconLabel.getStyleClass().add("home-tool-icon");
                Label label = new Label(t[1]);
                label.getStyleClass().add("home-tool-label");
                tool.getChildren().addAll(iconLabel, label);
                // 调度用稳定 id（t[0] 的图标名），不用显示文案 t[1]：
                // 界面语言切换后文案会变，用文案当派发键会让所有入口同时失效。
                String toolId = t[0];
                tool.setOnMouseClicked(e -> host.handleQuickTool(toolId));
                tools.getChildren().add(tool);
            }
            return tools;
        }));

        ScrollPane rightScroll = new ScrollPane(right);
        PageKit.configureScrollPane(host, rightScroll);
        rightScroll.setPrefHeight(Region.USE_COMPUTED_SIZE);
        HBox.setHgrow(rightScroll, Priority.ALWAYS);
        layout.getChildren().addAll(left, rightScroll);
        return layout;
    }

    /** 主页右侧「快速进入存档」卡片内容：最近游玩的前 3 个存档（按最后修改时间倒序），点击直接启动游戏 */
    private Node buildQuickSaveContent() {
        List<UIGeneralControlClass.SaveInfo> saves;
        try {
            saves = UIGeneralControlClass.listRecentSaves(3);
        } catch (Exception e) {
            saves = java.util.Collections.emptyList();
        }
        VBox list = new VBox(6);
        if (saves.isEmpty()) {
            Label empty = new Label(I18N.get("page.t0243"));
            empty.setStyle("-fx-text-fill: -sl-text-dim;");
            list.getChildren().add(empty);
            return list;
        }
        // 标注版本限制：仅当存档属于当前版本且支持 quickPlay（1.20.2+ 且非 Forge/NeoForge）时可直接进入
        String curVer = SelectedVersion.of(host.config());
        boolean darkTheme = "dark".equals(host.currentTheme());
        for (UIGeneralControlClass.SaveInfo save : saves) {
            // 行结构对齐「最近游玩」卡片：名称在上、版本标注在下，右侧播放按钮
            HBox row = new HBox();
            row.getStyleClass().add("home-recent-item");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setCursor(javafx.scene.Cursor.HAND);
            VBox info = new VBox(2);
            Label name = new Label(save.worldName);
            name.getStyleClass().add("home-recent-name");
            boolean quick = save.version != null && !save.version.isBlank()
                    && baseVersionEquals(save.version, curVer)
                    && save.folderName != null && !save.folderName.isBlank()
                    && !save.folderName.contains(" ") && versionSupportsQuickPlay(save.version);
            Label ver = new Label(quick
                    ? I18N.get("hp.r0002", save.version)
                    : I18N.get("hp.r0003", save.version));
            ver.getStyleClass().add("home-recent-time");
            // 版本信息黑色提高对比度：浅色主题强制黑色，深色主题跟随 home-recent-time 主题色（白色系）
            if (!darkTheme) {
                ver.setStyle("-fx-text-fill: #000000;");
            }
            info.getChildren().addAll(name, ver);
            HBox.setHgrow(info, Priority.ALWAYS);
            Label playBtn = new Label();
            AppIcons.apply(playBtn, quick ? "play" : "play-outline", 12, null);
            playBtn.getStyleClass().add("home-recent-play");
            row.getChildren().addAll(info, playBtn);
            row.setOnMouseClicked(e -> quickLaunchSave(save));
            list.getChildren().add(row);
        }
        return list;
    }

    /** 快速启动进入指定存档：构造启动配置并携带 quickPlay 参数（支持版本）后启动 */
    private void quickLaunchSave(UIGeneralControlClass.SaveInfo save) {
        LaunchConfig config = UIGeneralControlClass.getLaunchConfig();
        if (config.version == null || config.version.isEmpty()) {
            host.ui().toast(I18N.get("page.t0244"));
            host.switchToSettings("sidebarGameDir");
            return;
        }
        if (UIGeneralControlClass.isLoggedIn()) {
            UIGeneralControlClass.applyCurrentAccount(config);
        }
        // 仅当存档属于当前版本且支持 quickPlay（1.20.2+ 且非 Forge/NeoForge）时直接进入存档；否则仅启动游戏
        boolean sameVersion = save.version != null && !save.version.isBlank()
                && baseVersionEquals(save.version, config.version);
        if (sameVersion && save.folderName != null && !save.folderName.isBlank()
                && !save.folderName.contains(" ") && versionSupportsQuickPlay(config.version)) {
            // quickPlay 参数名随版本变化：1.21.2+ 用 --quickPlayWorld，1.20.2-1.21.1 用 --quickPlaySingleplayer
            String argName = quickPlayArgName(config.version);
            String extra = argName + " " + save.folderName;
            config.gameArgs = (config.gameArgs == null || config.gameArgs.isBlank())
                    ? extra : config.gameArgs.trim() + " " + extra;
        }
        host.launchGame(config);
    }

    /** 判断版本是否支持 quickPlay 快速进入存档（1.20.2+；Forge/NeoForge 不注入，避免兼容问题） */
    private boolean versionSupportsQuickPlay(String version) {
        if (version == null) return false;
        String v = version.toLowerCase();
        if (v.contains("forge") || v.contains("neoforge")) return false;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?").matcher(version.trim());
        if (!m.find()) return false;
        try {
            int major = Integer.parseInt(m.group(1));
            int minor = Integer.parseInt(m.group(2));
            int patch = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
            if (major > 1) return true;
            if (major == 1 && minor > 20) return true;
            return major == 1 && minor == 20 && patch >= 2;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 提取版本号前缀（如 "1.21.1-Fabric_0.16.5" → "1.21.1"），无法解析时原样返回 */
    private String extractBaseVersion(String version) {
        if (version == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+\\.\\d+(?:\\.\\d+)?)").matcher(version.trim());
        return m.find() ? m.group(1) : version.trim();
    }

    /** 判断两个版本（可能带加载器后缀）是否为同一基础版本 */
    private boolean baseVersionEquals(String a, String b) {
        return extractBaseVersion(a).equals(extractBaseVersion(b));
    }

    /** quickPlay 参数名：1.21.2+ 用 --quickPlayWorld（接受世界名），1.20.2-1.21.1 用 --quickPlaySingleplayer */
    private String quickPlayArgName(String version) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?").matcher(extractBaseVersion(version));
        if (m.find()) {
            try {
                int major = Integer.parseInt(m.group(1));
                int minor = Integer.parseInt(m.group(2));
                int patch = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
                if (major > 1 || (major == 1 && minor > 21) || (major == 1 && minor == 21 && patch >= 2)) {
                    return "--quickPlayWorld";
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return "--quickPlaySingleplayer";
    }

    /** 未选择版本时的图标占位方块（32×32，与真实图标同尺寸，避免切换时卡片跳动） */
    private static Region versionIconPlaceholder() {
        Region icon = new Region();
        icon.setPrefSize(32, 32);
        icon.setMinSize(32, 32);
        icon.setMaxSize(32, 32);
        icon.setStyle("-fx-background-color: rgba(59,130,246,0.15); -fx-background-radius: 6;");
        return icon;
    }

    private VBox createHomeCard(String title, Supplier<Node> contentSupplier) {
        return createHomeCard(null, title, contentSupplier);
    }

    /** 带图标的首页卡片：icon 为 {@link AppIcons} 中的图标名，null 表示不显示图标 */
    private VBox createHomeCard(String icon, String title, Supplier<Node> contentSupplier) {
        return createHomeCard(icon, title, null, contentSupplier);
    }

    /**
     * 带标题栏操作的首页卡片：titleAction 非空时与标题同一行、靠右显示（如「系统信息」的详细信息按钮），
     * 正文区不再被按钮挤占，内容可以独立居中。
     */
    private VBox createHomeCard(String icon, String title, Node titleAction, Supplier<Node> contentSupplier) {
        VBox card = new VBox(8);
        card.getStyleClass().add("home-card");
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("home-card-title");
        if (icon != null) AppIcons.apply(titleLabel, icon, 15, null);
        Node content = contentSupplier.get();
        if (titleAction == null) {
            card.getChildren().addAll(titleLabel, content);
            return card;
        }
        HBox header = new HBox(8, titleLabel);
        header.getStyleClass().add("home-card-header");
        header.setAlignment(Pos.CENTER_LEFT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        header.getChildren().addAll(spacer, titleAction);
        card.getChildren().addAll(header, content);
        return card;
    }

    /** 系统信息卡片的「详细信息」按钮：位于标题栏右侧，跳转内存与性能页 */
    private Button buildSystemDetailButton() {
        Button detailBtn = AppIcons.button("info", I18N.get("page.t0245"));
        detailBtn.getStyleClass().add("btn-primary");
        detailBtn.setStyle("-fx-padding: 3 10; -fx-font-size: 11px;");
        detailBtn.setMinWidth(Region.USE_PREF_SIZE);
        detailBtn.setMinHeight(Region.USE_PREF_SIZE);
        detailBtn.setOnAction(e -> host.switchToPage("memory"));
        return detailBtn;
    }

    private VBox createStatItem(String label, Label valueLabel, Region barFill) {
        VBox item = new VBox(4);
        item.setAlignment(Pos.CENTER);
        item.setPrefWidth(100);
        item.getStyleClass().add("home-stat-item");
        Label lbl = new Label(label);
        lbl.getStyleClass().add("home-stat-label");
        valueLabel.getStyleClass().add("home-stat-value");
        // 轨道：80px 浅色底槽（宽度由 CSS 锁死，避免被 VBox 拉伸后填充条只占一部分）
        HBox track = new HBox(barFill);
        track.getStyleClass().add("home-stat-bar");
        item.getChildren().addAll(lbl, valueLabel, track);
        return item;
    }

    private Label createInfoLabel(String prefix, String value) {
        Label label = new Label(prefix + value);
        // 用主题文字色而不是硬编码黑色，否则深色主题下会变成黑字压深底
        label.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text;");
        return label;
    }


    private String getCurrentUsername() {
        Account acc = AccountManager.getCurrentAccount();
        return acc != null ? acc.name : I18N.get("page.t0246");
    }

    /**
     * 异步加载首页头像：后台线程加载账号皮肤，在 FX 线程生成头像（Canvas.snapshot 需 FX 线程）。
     * 微软账号通过 Mojang 官方 API 获取正版皮肤（本地缓存 24h）；其他账号加载本地皮肤配置。
     * 无账号 / 无皮肤 / 加载失败时按 UUID 哈希回退内置默认皮肤；仍失败则保留 emoji 占位。
     */
    private void loadHomeAvatar(StackPane avatarBox, double size) {
        Account acc = AccountManager.getCurrentAccount();
        UUID uuid = AvatarGenerator.parseUuid(acc != null ? acc.id : null);
        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            try {
                // 后台线程：皮肤加载含网络/文件 IO
                // 微软走 Mojang 官方、第三方走其认证服务器（均为 Yggdrasil session API）；离线走本地皮肤配置
                Image skinImage = (acc != null && (acc.type == AccountManager.AccountType.MICROSOFT
                        || acc.type == AccountManager.AccountType.THIRD_PARTY))
                        ? MojangAvatarFetcher.getAccountAvatar(acc)
                        : AvatarGenerator.loadAccountSkin(acc);
                Platform.runLater(() -> {
                    try {
                        Image avatarImg = skinImage != null
                                ? AvatarGenerator.generateAvatar(skinImage, 64)
                                : AvatarGenerator.generateAvatar(AvatarGenerator.getDefaultSkin(uuid), 64);
                        if (avatarImg == null) return; // 生成失败保留占位
                        ImageView view = new ImageView(avatarImg);
                        view.setFitWidth(size);
                        view.setFitHeight(size);
                        // 强制正方形显示区域：preserveRatio=true 时实际显示尺寸小于裁剪区域，头像显示不全
                        view.setPreserveRatio(false);
                        // 圆角矩形裁剪，与 .home-avatar 背景圆角(12)一致
                        Rectangle clip = new Rectangle(size, size);
                        clip.setArcWidth(12);
                        clip.setArcHeight(12);
                        view.setClip(clip);
                        avatarBox.getChildren().setAll(view);
                    } catch (Exception ignored) {
                        // 保留 emoji 占位
                    }
                });
            } catch (Exception ignored) {
                // 网络/IO 异常时保留默认头像
            }
        });
    }


    public Node buildSystemStats() {
        HBox stats = new HBox(12);
        // 横向居中 + 顶部对齐：磁盘项没有进度条，居中会把它的文字压到 CPU/内存标签下方
        stats.setAlignment(Pos.TOP_CENTER);
        stats.getStyleClass().add("home-stats-row");
        // 撑满卡片宽度，让三项指标整体落在卡片中间（不平铺则只会贴着左侧）
        stats.setMaxWidth(Double.MAX_VALUE);
        cpuLabel = new Label("0%");
        memLabel = new Label("0%");
        diskLabel = new Label("-- MB/s");
        cpuBar = createStatFill();
        memBar = createStatFill();
        stats.getChildren().addAll(
                createStatItem("CPU", cpuLabel, cpuBar),
                createStatItem(I18N.get("page.t0247"), memLabel, memBar),
                createDiskItem()
        );
        return stats;
    }

    /** 磁盘项：显示读写速率文本（与任务管理器性能页一致，无进度条） */
    private VBox createDiskItem() {
        VBox item = new VBox(4);
        item.setAlignment(Pos.CENTER);
        // 宽度走 CSS 的 .home-stat-item-disk（170px）：.home-stat-item 的 100px 会在
        // CSS 应用时覆盖代码 setPrefWidth，导致「读 x / 写 y MB/s」被截断成「…」
        item.getStyleClass().add("home-stat-item");
        item.getStyleClass().add("home-stat-item-disk");
        Label lbl = new Label(I18N.get("page.t0248"));
        lbl.getStyleClass().add("home-stat-label");
        diskLabel.getStyleClass().add("home-stat-value");
        diskLabel.setStyle("-fx-font-size: 12px;");
        item.getChildren().addAll(lbl, diskLabel);
        return item;
    }

    /** 创建统计进度条填充段（宽度随占用率动态变化） */
    private Region createStatFill() {
        Region fill = new Region();
        fill.getStyleClass().add("home-stat-bar-fill");
        fill.setPrefWidth(0);
        return fill;
    }

    public void startSystemMonitor() {
        Timeline timeline = new Timeline(new KeyFrame(Duration.seconds(3), e -> updateSystemStats()));
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.play();
    }

    private void updateSystemStats() {
        try {
            // 采样由 SystemInfoMonitor 统一负责（后台线程执行，闪屏期间已预热 CPU 采样窗口）
            SystemInfoMonitor.sample();
            double cpu = SystemInfoMonitor.getCpuPercent();
            double mem = SystemInfoMonitor.getMemPercent();
            double read = SystemInfoMonitor.getDiskReadMBps();
            double write = SystemInfoMonitor.getDiskWriteMBps();
            Platform.runLater(() -> {
                if (cpuLabel != null) cpuLabel.setText(cpu >= 0 ? String.format("%.1f%%", cpu) : I18N.get("page.t0249"));
                if (cpuBar != null) updateStatBar(cpuBar, cpu >= 0 ? cpu : 0);
                if (memLabel != null) memLabel.setText(mem >= 0 ? String.format("%.1f%%", mem) : "--%");
                if (memBar != null) updateStatBar(memBar, mem >= 0 ? mem : 0);
                if (diskLabel != null) {
                    diskLabel.setText(read >= 0 && write >= 0
                            ? String.format(I18N.get("page.t0250"), read, write)
                            : "-- MB/s");
                }
            });
        } catch (Exception ignored) {}
    }

    /** 更新进度条填充宽度与颜色（>85% 红、>60% 黄、其余绿） */
    private void updateStatBar(Region fill, double percent) {
        double pct = Math.max(0, Math.min(100, percent));
        // 按轨道实际宽度换算：轨道被父容器拉伸时，固定 80px 的填充最多只能占到一部分
        // （CSS 已锁定 80px；这里再兜底，避免以后改样式时进度条又填不满）
        double trackWidth = fill.getParent() instanceof Region track && track.getWidth() > 0
                ? track.getWidth() : 80;
        fill.setPrefWidth(pct / 100.0 * trackWidth);
        fill.getStyleClass().removeAll("warn", "danger");
        if (pct > 85) fill.getStyleClass().add("danger");
        else if (pct > 60) fill.getStyleClass().add("warn");
    }

}



