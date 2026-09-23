package com.example.starlight.newui.page;

import com.example.starlight.auth.AccountManager;
import com.example.starlight.auth.AccountManager.Account;
import com.example.starlight.config.GameDirManager;
import com.example.starlight.launch.LaunchRecord;
import com.example.starlight.gui.UIGeneralControlClass;
import com.example.starlight.gui.UIGeneralControlClass.LaunchConfig;
import com.example.starlight.launch.LaunchRecordStore;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.AppIcons;
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

    public HomePage(LauncherContext host) {
        this.host = host;
    }

    public Node build() {
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
        avatarBox.setOnMouseClicked(e -> host.switchToSettings("sidebarGameAccount"));
        loadHomeAvatar(avatarBox, 52);
        VBox nameBox = new VBox(3);
        // 名字列可以被压窄（超长用户名允许省略），但整体不许把右侧按钮挤掉
        nameBox.setMinWidth(0);
        HBox.setHgrow(nameBox, Priority.ALWAYS);
        Label welcome = new Label("欢迎回来");
        welcome.getStyleClass().add("home-welcome");
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
        Label recentTitle = new Label("最近游玩");
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
            Label empty = new Label("暂未启动过游戏");
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
                        "确定要移除最近游玩记录 \"" + finalRec.version + "\" 吗？", () -> {
                    LaunchRecordStore.removeIf(r -> r.version.equals(finalRec.version)
                            && java.util.Objects.equals(r.gameDir, finalRec.gameDir));
                    host.ui().toast("已移除记录: " + finalRec.version);
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

        // 启动按钮
        VBox launchCard = new VBox();
        launchCard.getStyleClass().add("home-card-left-item");
        Button launchBtn = AppIcons.button("play", "启动游戏");
        launchBtn.getStyleClass().add("home-launch-btn");
        launchBtn.setMaxWidth(Double.MAX_VALUE);
        launchBtn.setOnAction(e -> host.launchGame());
        launchCard.getChildren().add(launchBtn);
        left.getChildren().add(userCard);
        left.getChildren().addAll(recentCard, launchCard);

        // 右侧内容
        VBox right = new VBox(12);
        right.setPadding(new Insets(0, 4, 0, 0));
        HBox.setHgrow(right, Priority.ALWAYS);

        right.getChildren().add(createHomeCard("system-info", "系统信息", () -> {
            HBox box = new HBox(12);
            box.setAlignment(Pos.CENTER);
            box.getChildren().add(buildSystemStats());
            Button detailBtn = AppIcons.button("info", "详细信息");
            detailBtn.getStyleClass().add("btn-primary");
            detailBtn.setStyle("-fx-padding: 4 10; -fx-font-size: 11px;");
            detailBtn.setOnAction(e -> host.switchToPage("memory"));
            box.getChildren().add(detailBtn);
            return box;
        }));

        // 快速进入存档（高级设置「主页快速进入存档」开关控制）：位于系统信息卡片下方
        if ("true".equalsIgnoreCase(host.config().getOrDefault("HomeQuickLaunchSave", "false"))) {
            right.getChildren().add(createHomeCard("gamepad", "快速进入存档", this::buildQuickSaveContent));
        }

        right.getChildren().add(createHomeCard("version-select", "版本选择", () -> {
            HBox ver = new HBox(14);
            ver.setAlignment(Pos.CENTER_LEFT);
            ver.getStyleClass().add("home-ver-card");
            // 图标跟随当前版本：整合包用包图标，其余按加载器（原版用草方块）
            String curVersion = host.config().getOrDefault("Version", "");
            Node versionIcon = curVersion.isBlank()
                    ? versionIconPlaceholder()
                    : VersionIconKit.icon(VersionIconKit.read(
                            GameDirManager.activePath(host.config()), curVersion), 32);
            VBox verInfo = new VBox(2);
            verInfo.getStyleClass().add("home-ver-info");
            Label verTitle = new Label(host.config().getOrDefault("Version", "未选择"));
            verTitle.getStyleClass().add("home-ver-title");
            Label verDesc = new Label("点击切换版本");
            verDesc.getStyleClass().add("home-ver-desc");
            verInfo.getChildren().addAll(verTitle, verDesc);
            Button switchBtn = AppIcons.button("refresh", "切换 ");
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

        right.getChildren().add(createHomeCard("gear", "版本设置", () -> {
            HBox setting = new HBox(14);
            setting.setAlignment(Pos.CENTER_LEFT);
            setting.getStyleClass().add("home-ver-card");
            VBox info = new VBox(2);
            info.getStyleClass().add("home-ver-info");
            // 说明列可被压缩；长 JVM 参数换行显示，右侧按钮锁固有宽度不被挤成「…」
            info.setMinWidth(0);
            String maxMem = host.config().getOrDefault("MaxMemory", "4096");
            Label title = new Label("内存与JVM参数");
            title.getStyleClass().add("home-ver-title");
            // JVM 参数显示真实配置值（首页每次访问实时重建，始终反映最新保存的配置）
            String jvmShow = host.config().getOrDefault("JvmArgs", "");
            String jvmDesc = (jvmShow == null || jvmShow.trim().isEmpty()) ? "默认" : jvmShow;
            Label desc = new Label("当前最大内存 " + maxMem + "MB | JVM: " + jvmDesc);
            desc.getStyleClass().add("home-ver-desc");
            desc.setWrapText(true);
            info.getChildren().addAll(title, desc);
            Button setBtn = AppIcons.button("gear", "设置");
            setBtn.getStyleClass().add("btn-primary");
            setBtn.setMinWidth(Region.USE_PREF_SIZE);
            setBtn.setMaxWidth(Region.USE_PREF_SIZE);
            setBtn.setOnAction(e -> host.switchToSettings("sidebarJvm"));
            HBox.setHgrow(info, Priority.ALWAYS);
            setting.getChildren().addAll(info, setBtn);
            return setting;
        }));

        right.getChildren().add(createHomeCard("antenna", "网络连接", () -> {
            HBox net = new HBox();
            net.setAlignment(Pos.CENTER);
            net.setSpacing(40);
            String source = NetworkStatusService.currentSourceName();
            Label statusLabel;
            // 「当前」一栏显示当前下载源的真实连通状态：有 30 秒内的测速缓存直接用，
            // 否则先占位「检测中...」并在后台补测一次（此前这里写死「空闲」，永远不刷新）
            NetworkStatusService.SourceResult cached = NetworkStatusService.isCacheFresh()
                    ? NetworkStatusService.currentSourceCached() : null;
            statusLabel = createInfoLabel("当前: ", cached != null ? cached.describe() : "检测中...");
            net.getChildren().addAll(createInfoLabel("下载源: ", source), statusLabel);
            if (cached == null) {
                NetworkStatusService.testCurrentSourceAsync().thenAccept(r -> javafx.application.Platform.runLater(() ->
                        statusLabel.setText("当前: " + (r == null ? "未知" : r.describe()))));
            }
            return net;
        }));

        right.getChildren().add(createHomeCard("quick-tools", "快捷工具", () -> {
            HBox tools = new HBox(10);
            tools.setAlignment(Pos.CENTER);
            tools.getStyleClass().add("home-tools-grid");
            String[][] toolData = {{"memory", "内存"}, {"globe", "网络"}, {"clapper", "截图"}, {"wrench", "设置"}, {"bolt", "帧生成"}, {"view", "色盲辅助"}};
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
                String toolName = t[1];
                tool.setOnMouseClicked(e -> host.handleQuickTool(toolName));
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
            Label empty = new Label("暂无存档");
            empty.setStyle("-fx-text-fill: -sl-text-dim;");
            list.getChildren().add(empty);
            return list;
        }
        // 标注版本限制：仅当存档属于当前版本且支持 quickPlay（1.20.2+ 且非 Forge/NeoForge）时可直接进入
        String curVer = host.config().getOrDefault("Version", "");
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
                    ? "版本 " + save.version + "（可直接进入）"
                    : "版本 " + save.version + "（仅启动）");
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
            host.ui().toast("请先选择游戏版本");
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
        VBox card = new VBox(8);
        card.getStyleClass().add("home-card");
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("home-card-title");
        if (icon != null) AppIcons.apply(titleLabel, icon, 15, null);
        Node content = contentSupplier.get();
        card.getChildren().addAll(titleLabel, content);
        return card;
    }

    private VBox createStatItem(String label, Label valueLabel, Region barFill) {
        VBox item = new VBox(4);
        item.setAlignment(Pos.CENTER);
        item.setPrefWidth(100);
        item.getStyleClass().add("home-stat-item");
        Label lbl = new Label(label);
        lbl.getStyleClass().add("home-stat-label");
        valueLabel.getStyleClass().add("home-stat-value");
        // 轨道：固定 80px 浅色底槽。HBox 布局不会拉伸子节点，填充段宽度随 prefWidth 动态伸缩
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
        return acc != null ? acc.name : "未登录";
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
        stats.setAlignment(Pos.CENTER);
        stats.getStyleClass().add("home-stats-row");
        cpuLabel = new Label("0%");
        memLabel = new Label("0%");
        diskLabel = new Label("-- MB/s");
        cpuBar = createStatFill();
        memBar = createStatFill();
        stats.getChildren().addAll(
                createStatItem("CPU", cpuLabel, cpuBar),
                createStatItem("内存", memLabel, memBar),
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
        Label lbl = new Label("磁盘");
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
                if (cpuLabel != null) cpuLabel.setText(cpu >= 0 ? String.format("%.1f%%", cpu) : "计算中...");
                if (cpuBar != null) updateStatBar(cpuBar, cpu >= 0 ? cpu : 0);
                if (memLabel != null) memLabel.setText(mem >= 0 ? String.format("%.1f%%", mem) : "--%");
                if (memBar != null) updateStatBar(memBar, mem >= 0 ? mem : 0);
                if (diskLabel != null) {
                    diskLabel.setText(read >= 0 && write >= 0
                            ? String.format("读 %.1f / 写 %.1f MB/s", read, write)
                            : "-- MB/s");
                }
            });
        } catch (Exception ignored) {}
    }

    /** 更新进度条填充宽度与颜色（>85% 红、>60% 黄、其余绿） */
    private void updateStatBar(Region fill, double percent) {
        double pct = Math.max(0, Math.min(100, percent));
        fill.setPrefWidth(pct / 100.0 * 80);
        fill.getStyleClass().removeAll("warn", "danger");
        if (pct > 85) fill.getStyleClass().add("danger");
        else if (pct > 60) fill.getStyleClass().add("warn");
    }

}



