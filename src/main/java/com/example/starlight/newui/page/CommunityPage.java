package com.example.starlight.newui.page;


import com.example.starlight.util.I18N;
import com.example.starlight.community.CommunityApi;
import com.example.starlight.community.CommunityApi.ApiResult;
import com.example.starlight.community.CommunityApi.CheckinResult;
import com.example.starlight.community.CommunityApi.CommunityComment;
import com.example.starlight.community.CommunityApi.CommunityPost;
import com.example.starlight.community.CommunityApi.CommunityUser;
import com.example.starlight.config.Endpoints;
import com.example.starlight.gui.UIGeneralControlClass;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.ui.PageKit;
import com.example.starlight.newui.ui.SkeletonFactory;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * 社区页：登录 / 签到 / 发帖 / 帖子列表 / 评论 / 点赞，全部对接真实后端接口。
 * 布局：左侧个人信息 + 签到固定，右侧发帖区固定，帖子列表区域独立滚动。
 * 帖子列表与登录态在页面构建后异步加载，不阻塞 UI 线程。
 *
 * <p>从 LauncherView 抽离（方法体逐字搬运，逻辑、样式类与内联样式均未改动）。
 * 页面组件引用（communityUserCard / communityPostList / …）随页面迁入本类，
 * 主壳只保留 {@code communityPage} 单例并委派三个页面入口。
 */
public final class CommunityPage {

    private final LauncherContext ctx;

    public CommunityPage(LauncherContext ctx) {
        this.ctx = ctx;
    }

    // 社区页组件引用（已接入星光MC社区后端） //
    private final CommunityApi communityApi = new CommunityApi(); // 社区 API 客户端
    private VBox communityUserCard;       // 登录卡片：登录成功后刷新为已登录状态
    private Label communityCheckinStatus; // 签到状态文案："今日未签到" / "已连续签到N天"
    private VBox communityPostList;       // 帖子列表容器：后端拉取后用 createCommunityPostCard 填充
    private Button communityCheckinBtn;   // 签到按钮：签到成功后禁用并变为"已签到"
    private TextField communityPostTitleField;     // 发帖标题输入框
    private TextArea communityPostContentArea;     // 发帖内容输入框
    private ComboBox<String> communityCategoryBox; // 发帖分类选择
    private CommunityPost currentDetailPost;       // 当前查看详情的帖子（点击帖子卡片时记录）

    // 社区页面（已接入星光MC社区后端，接口见 CommunityApi） //

    /**
     * 社区页骨架：登录 / 签到 / 发帖 / 帖子列表 / 评论 / 点赞，全部对接真实后端接口。
     * 布局：左侧个人信息 + 签到固定，右侧发帖区固定，帖子列表区域独立滚动。
     * 帖子列表与登录态在页面构建后异步加载，不阻塞 UI 线程。
     */
    public Node buildNews() {
        HBox layout = new HBox(20);
        layout.setPadding(new Insets(0, 4, 4, 0));
        HBox.setHgrow(layout, Priority.ALWAYS);
        VBox.setVgrow(layout, Priority.ALWAYS);

        // 左侧：登录 + 签到 + 官网提示（固定，不随帖子列表滚动） //
        VBox left = new VBox(14);
        left.setPrefWidth(280);
        left.setMinWidth(250);
        left.setMaxWidth(280);
        left.getChildren().add(buildCommunityLoginCard());
        left.getChildren().add(buildCommunityCheckinCard());
        left.getChildren().add(buildCommunityNoticeCard());

        // 右侧：帖子列表（含「发布帖子」按钮入口，发帖在独立页面完成） //
        VBox right = new VBox(12);
        right.setPadding(new Insets(0, 4, 0, 0));
        HBox.setHgrow(right, Priority.ALWAYS);

        VBox postCard = new VBox(10);
        postCard.getStyleClass().add("home-card");
        VBox.setVgrow(postCard, Priority.ALWAYS);

        // 标题行：帖子列表 + 发布帖子按钮（点击跳转到独立发帖页面）
        HBox postHeader = new HBox(10);
        postHeader.setAlignment(Pos.CENTER_LEFT);
        Label postTitle = new Label(I18N.get("page.t0001"));
        postTitle.getStyleClass().add("home-card-title");
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        Button communityPublishBtn = AppIcons.button("memo", I18N.get("page.t0002"));
        communityPublishBtn.getStyleClass().add("btn-primary");
        communityPublishBtn.setStyle("-fx-background-color: #3b82f6; -fx-text-fill: white; -fx-padding: 6 16; -fx-font-size: 12px;");
        communityPublishBtn.setOnAction(e -> ctx.switchToPage("postPublish"));
        postHeader.getChildren().addAll(postTitle, headerSpacer, communityPublishBtn);

        communityPostList = new VBox(10);
        communityPostList.setMaxWidth(Double.MAX_VALUE);

        // 帖子列表独立滚动区域（复用统一滚动配置，含滚轮速度调节）
        ScrollPane postScroll = new ScrollPane(communityPostList);
        PageKit.configureScrollPane(ctx, postScroll);

        postCard.getChildren().addAll(postHeader, postScroll);
        right.getChildren().add(postCard);

        layout.getChildren().addAll(left, right);

        // 异步加载帖子列表与恢复登录态
        loadCommunityPosts();
        restoreCommunitySession();
        return layout;
    }

    /** 官网提示卡片：红色提示框 + 打开官网按钮 */
    private VBox buildCommunityNoticeCard() {
        VBox card = new VBox(10);
        card.getStyleClass().add("community-notice-card");

        Label title = new Label(I18N.get("page.t0003"));
        title.getStyleClass().add("community-notice-title");

        Label desc = new Label(I18N.get("page.p0740", Endpoints.communitySiteUrl().replaceFirst("^https?://", "")));
        desc.getStyleClass().add("community-notice-desc");
        desc.setWrapText(true);

        Button websiteBtn = AppIcons.button("external-link", I18N.get("page.t0004"));
        websiteBtn.getStyleClass().add("community-notice-btn");
        websiteBtn.setMaxWidth(Double.MAX_VALUE);
        websiteBtn.setOnAction(e -> {
            try {
                java.awt.Desktop.getDesktop().browse(new java.net.URI(Endpoints.communitySiteUrl()));
            } catch (Exception ex) {
                ctx.ui().toast(I18N.get("page.p0741", ex.getMessage()));
            }
        });

        card.getChildren().addAll(title, desc, websiteBtn);
        return card;
    }

    /** 登录卡片：根据登录态显示「登录按钮」或「用户信息 + 退出按钮」 */
    private VBox buildCommunityLoginCard() {
        communityUserCard = new VBox(10);
        communityUserCard.getStyleClass().add("community-user-card");
        refreshCommunityUserCard();
        return communityUserCard;
    }

    /** 刷新登录卡片内容（登录 / 退出后调用） */
    private void refreshCommunityUserCard() {
        CommunityUser user = communityApi.getCurrentUser();
        communityUserCard.getChildren().clear();

        // 头像容器：默认 emoji 占位，加载到真实头像后替换为圆形图片
        StackPane avatarBox = new StackPane();
        avatarBox.getStyleClass().add("community-avatar-lg");
        Label avatarFallback = new Label();
        avatarFallback.setGraphic(AppIcons.icon("avatar", 28, javafx.scene.paint.Color.WHITE));
        avatarBox.getChildren().add(avatarFallback);

        if (user != null) {
            Label name = new Label(user.username);
            name.getStyleClass().add("community-login-name");

            Label desc = new Label(user.email == null || user.email.isEmpty() ? I18N.get("page.t0005") : user.email);
            desc.getStyleClass().add("community-login-desc");

            Button logoutBtn = AppIcons.button("log-out", I18N.get("page.t0006"));
            logoutBtn.getStyleClass().addAll("community-login-btn");
            logoutBtn.setStyle("-fx-background-color: #ef4444;");
            logoutBtn.setMaxWidth(Double.MAX_VALUE);
            logoutBtn.setOnAction(e -> {
                communityApi.logout();
                refreshCommunityUserCard();
                refreshCommunityCheckinCard();
                ctx.ui().toast(I18N.get("page.t0007"));
            });

            communityUserCard.getChildren().addAll(avatarBox, name, desc, logoutBtn);
            // 异步加载真实头像（未设置或失败时保留 emoji 占位）
            loadCommunityAvatar(avatarBox, avatarFallback, "community-avatar-lg", user.id, 64);
        } else {
            Label name = new Label(I18N.get("page.t0008"));
            name.getStyleClass().add("community-login-name");

            Label desc = new Label(I18N.get("page.t0009"));
            desc.getStyleClass().add("community-login-desc");

            Button loginBtn = AppIcons.button("user", I18N.get("page.t0010"));
            loginBtn.getStyleClass().addAll("btn-primary", "community-login-btn");
            loginBtn.setMaxWidth(Double.MAX_VALUE);
            loginBtn.setOnAction(e -> showCommunityLoginDialog());

            communityUserCard.getChildren().addAll(avatarBox, name, desc, loginBtn);
        }
    }

    /**
     * 异步加载真实头像：先调 avatar_get 获取 URL，再后台加载图片并替换为圆形 ImageView。
     * 未设置头像 / 图片加载失败时保留 emoji 占位（恢复头像背景样式）。
     */
    private void loadCommunityAvatar(StackPane avatarBox, Label avatarFallback,
                                     String avatarStyleClass, int uid, double size) {
        if (uid <= 0) return;
        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            try {
                ApiResult<String> result = communityApi.avatarGet(uid);
                Platform.runLater(() -> {
                    if (!result.ok || result.data == null || result.data.isEmpty()) return;
                    final Image image;
                    try {
                        // backgroundLoading=true：图片在后台线程加载，不阻塞 FX 线程
                        image = new Image(result.data, size, size, true, true, true);
                    } catch (Exception ex) {
                        return;
                    }
                    ImageView view = new ImageView(image);
                    view.setFitWidth(size);
                    view.setFitHeight(size);
                    // 强制正方形显示区域：preserveRatio=true 时非正方形图片实际显示尺寸
                    // 会小于裁剪圆直径，导致圆形头像被裁掉一部分（显示不全）
                    view.setPreserveRatio(false);
                    // 圆形裁剪
                    javafx.scene.shape.Circle clip = new javafx.scene.shape.Circle(size / 2, size / 2, size / 2);
                    view.setClip(clip);
                    avatarBox.getChildren().setAll(view);
                    avatarBox.getStyleClass().remove(avatarStyleClass);
                    // 图片加载失败（如 404）时回退到 emoji 占位
                    image.errorProperty().addListener((obs, o, n) -> {
                        if (Boolean.TRUE.equals(n)) {
                            Platform.runLater(() -> {
                                if (avatarBox.getChildren().size() > 0
                                        && avatarBox.getChildren().get(0) == view) {
                                    avatarBox.getChildren().setAll(avatarFallback);
                                    avatarBox.getStyleClass().add(avatarStyleClass);
                                }
                            });
                        }
                    });
                });
            } catch (Exception ignored) {
                // 网络异常时保留默认头像
            }
        });
    }

    /** 登录面板（集成在启动器内）：支持账号密码登录与令牌登录，异步调用接口，失败信息显示在面板内 */
    private void showCommunityLoginDialog() {
        VBox body = new VBox(10);
        body.setPadding(new Insets(10, 4, 0, 4));

        // 登录方式切换：账号登录 / 令牌登录 //
        ToggleGroup modeGroup = new ToggleGroup();
        ToggleButton accountModeBtn = AppIcons.toggle("user", I18N.get("page.t0011"));
        accountModeBtn.getStyleClass().add("login-mode-toggle");
        accountModeBtn.setToggleGroup(modeGroup);
        accountModeBtn.setSelected(true);
        ToggleButton tokenModeBtn = AppIcons.toggle("key", I18N.get("page.t0012"));
        tokenModeBtn.getStyleClass().add("login-mode-toggle");
        tokenModeBtn.setToggleGroup(modeGroup);
        HBox modeBox = new HBox(0, accountModeBtn, tokenModeBtn);
        modeBox.setAlignment(Pos.CENTER);

        // 账号密码输入区 //
        Label userLabel = new Label(I18N.get("page.t0013"));
        userLabel.getStyleClass().add("modal-text");
        TextField usernameField = new TextField();
        usernameField.setPromptText(I18N.get("page.t0014"));
        usernameField.getStyleClass().add("input-field");
        usernameField.setPrefWidth(Double.MAX_VALUE);
        Label passLabel = new Label(I18N.get("page.t0015"));
        passLabel.getStyleClass().add("modal-text");
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText(I18N.get("page.t0016"));
        passwordField.getStyleClass().add("input-field");
        passwordField.setPrefWidth(Double.MAX_VALUE);

        // 令牌输入区 //
        Label tokenLabel = new Label(I18N.get("page.t0017"));
        tokenLabel.getStyleClass().add("modal-text");
        PasswordField tokenField = new PasswordField();
        tokenField.setPromptText(I18N.get("page.t0018"));
        tokenField.getStyleClass().add("input-field");
        tokenField.setPrefWidth(Double.MAX_VALUE);
        Label tokenHint = new Label(I18N.get("page.t0019"));
        tokenHint.getStyleClass().add("modal-hint");
        tokenHint.setWrapText(true);

        // 登录状态提示（失败原因 / 登录中）
        Label statusLabel = new Label();
        statusLabel.setWrapText(true);
        statusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #dc2626;");

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        HBox btns = new HBox(10);
        btns.setAlignment(Pos.CENTER_RIGHT);
        Button cancelBtn = AppIcons.button("close", I18N.get("page.t0020"));
        cancelBtn.getStyleClass().add("modal-btn-cancel");
        cancelBtn.setOnAction(e -> ctx.ui().closeModal());
        Button loginBtn = AppIcons.button("user", I18N.get("page.t0021"));
        loginBtn.getStyleClass().add("modal-btn-ok");
        loginBtn.setDisable(true);

        // 登录按钮可用性：当前模式下必填项均非空
        Runnable updateLoginBtn = () -> {
            boolean accountMode = accountModeBtn.isSelected();
            boolean filled = accountMode
                    ? !usernameField.getText().trim().isEmpty() && !passwordField.getText().isEmpty()
                    : !tokenField.getText().trim().isEmpty();
            loginBtn.setDisable(!filled);
        };
        usernameField.textProperty().addListener((obs, o, n) -> updateLoginBtn.run());
        passwordField.textProperty().addListener((obs, o, n) -> updateLoginBtn.run());
        tokenField.textProperty().addListener((obs, o, n) -> updateLoginBtn.run());
        modeGroup.selectedToggleProperty().addListener((obs, o, n) -> updateLoginBtn.run());

        // 切换登录方式时显示对应输入区并清空状态提示
        Runnable updateModeFields = () -> {
            boolean accountMode = accountModeBtn.isSelected();
            userLabel.setVisible(accountMode);
            userLabel.setManaged(accountMode);
            usernameField.setVisible(accountMode);
            usernameField.setManaged(accountMode);
            passLabel.setVisible(accountMode);
            passLabel.setManaged(accountMode);
            passwordField.setVisible(accountMode);
            passwordField.setManaged(accountMode);
            tokenLabel.setVisible(!accountMode);
            tokenLabel.setManaged(!accountMode);
            tokenField.setVisible(!accountMode);
            tokenField.setManaged(!accountMode);
            tokenHint.setVisible(!accountMode);
            tokenHint.setManaged(!accountMode);
            statusLabel.setText("");
        };
        modeGroup.selectedToggleProperty().addListener((obs, o, n) -> updateModeFields.run());
        tokenLabel.setVisible(false); // 默认账号登录，隐藏令牌输入区
        tokenLabel.setManaged(false);
        tokenField.setVisible(false);
        tokenField.setManaged(false);
        tokenHint.setVisible(false);
        tokenHint.setManaged(false);

        loginBtn.setOnAction(e -> {
            loginBtn.setDisable(true);
            loginBtn.setText(I18N.get("page.t0022"));
            statusLabel.setText("");
            boolean accountMode = accountModeBtn.isSelected();
            String username = usernameField.getText().trim();
            String password = passwordField.getText();
            String token = tokenField.getText().trim();
            UIGeneralControlClass.ASYNC_POOL.submit(() -> {
                try {
                    ApiResult<CommunityUser> result = accountMode
                            ? communityApi.login(username, password)
                            : communityApi.loginWithToken(token);
                    Platform.runLater(() -> {
                        if (result.ok) {
                            ctx.ui().closeModal();
                            refreshCommunityUserCard();
                            refreshCommunityCheckinCard();
                            ctx.ui().toast(I18N.get("page.p0742", result.data.username));
                        } else {
                            loginBtn.setDisable(false);
                            AppIcons.setText(loginBtn, "user", I18N.get("page.t0023"));
                            statusLabel.setText(I18N.get("page.p0743", result.msg));
                        }
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        loginBtn.setDisable(false);
                        AppIcons.setText(loginBtn, "user", I18N.get("page.t0024"));
                        statusLabel.setText(I18N.get("page.p0744", ex.getMessage()));
                    });
                }
            });
        });
        btns.getChildren().addAll(cancelBtn, loginBtn);

        body.getChildren().addAll(modeBox, userLabel, usernameField, passLabel, passwordField,
                tokenLabel, tokenField, tokenHint, statusLabel, spacer, btns);
        ctx.ui().modal(I18N.get("page.t0025"), body, 360, 340);
    }

    /** 恢复登录态：本地存在 token 时异步调用 launcher_profile 验证 */
    private void restoreCommunitySession() {
        if (!communityApi.hasToken()) return;
        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            try {
                ApiResult<CommunityUser> result = communityApi.profile();
                Platform.runLater(() -> {
                    if (result.ok && communityApi.getCurrentUser() != null) {
                        refreshCommunityUserCard();
                        ctx.ui().toast(I18N.get("page.p0745", communityApi.getCurrentUser().username));
                    } else if ("it".equals(result.code) || "nl".equals(result.code)) {
                        // token 已失效（profile 已清理登录态）：同步 UI 并提示重新登录
                        refreshCommunityUserCard();
                        refreshCommunityCheckinCard();
                        ctx.ui().toast(I18N.get("page.t0026"));
                    }
                });
            } catch (Exception ignored) {
                // 网络异常时保留本地 token，下次启动再验证
            }
        });
    }

    /** 每日签到卡片：调用真实签到接口，成功后更新状态并禁用按钮 */
    private VBox buildCommunityCheckinCard() {
        VBox card = new VBox(10);
        card.getStyleClass().add("community-checkin-card");

        Label title = new Label(I18N.get("page.t0027"));
        title.getStyleClass().add("community-checkin-title");

        communityCheckinStatus = new Label(I18N.get("page.t0028"));
        communityCheckinStatus.getStyleClass().add("community-checkin-status");

        communityCheckinBtn = AppIcons.button("hand", I18N.get("page.t0029"));
        communityCheckinBtn.getStyleClass().add("community-checkin-btn");
        communityCheckinBtn.setMaxWidth(Double.MAX_VALUE);
        communityCheckinBtn.setOnAction(e -> doCommunityCheckin());

        card.getChildren().addAll(title, communityCheckinStatus, communityCheckinBtn);
        refreshCommunityCheckinCard();
        return card;
    }

    /** 刷新签到卡片状态（根据本地记录 / 登录态），登录退出后调用 */
    private void refreshCommunityCheckinCard() {
        if (communityCheckinStatus == null || communityCheckinBtn == null) return;
        if (communityApi.isCheckedInToday()) {
            communityCheckinStatus.setText(I18N.get("page.t0030"));
            communityCheckinBtn.setText(I18N.get("page.t0031"));
            communityCheckinBtn.setDisable(true);
            communityCheckinBtn.setStyle("-fx-background-color: #10b981;");
        } else {
            communityCheckinStatus.setText(communityApi.hasToken() ? I18N.get("page.t0558") : I18N.get("page.t0559"));
            AppIcons.setText(communityCheckinBtn, "hand", I18N.get("page.t0032"));
            communityCheckinBtn.setDisable(false);
            communityCheckinBtn.setStyle("");
        }
    }

    /** 每日签到：校验登录后异步调用签到接口，"今天已签到"同样更新为已完成 */
    private void doCommunityCheckin() {
        if (!communityApi.hasToken()) {
            ctx.ui().toast(I18N.get("page.t0033"));
            return;
        }
        communityCheckinBtn.setDisable(true);
        communityCheckinBtn.setText(I18N.get("page.t0034"));
        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            try {
                ApiResult<CheckinResult> result = communityApi.checkin();
                Platform.runLater(() -> {
                    if (result.ok) {
                        communityCheckinStatus.setText(I18N.get("page.t0035"));
                        communityCheckinBtn.setText(I18N.get("page.t0036"));
                        communityCheckinBtn.setStyle("-fx-background-color: #10b981;");
                        String tip = result.data != null && result.data.msg != null
                                ? result.data.msg : I18N.get("page.t0037");
                        // 服务端返回签到后余额时一并展示，便于核对到账
                        if (result.data != null && result.data.tear > 0) {
                            tip += I18N.get("cp.r0001", result.data.tear);
                        }
                        ctx.ui().toast(tip);
                    // i18n-lock: 不要翻译此行中文——与 CommunityApi 同一服务端响应，翻译即功能失效
                    } else if (result.msg != null && result.msg.contains("已签到")) {
                        // 服务端对"今天已签到"返回 ok=false + msg，同样标记为已完成
                        communityCheckinStatus.setText(I18N.get("page.t0038"));
                        communityCheckinBtn.setText(I18N.get("page.t0039"));
                        communityCheckinBtn.setStyle("-fx-background-color: #10b981;");
                        ctx.ui().toast(I18N.get("page.t0040"));
                    } else {
                        communityCheckinBtn.setDisable(false);
                        AppIcons.setText(communityCheckinBtn, "hand", I18N.get("page.t0041"));
                        ctx.ui().toast(I18N.get("page.p0746", result.msg));
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    communityCheckinBtn.setDisable(false);
                    AppIcons.setText(communityCheckinBtn, "hand", I18N.get("page.t0042"));
                    ctx.ui().toast(I18N.get("page.p0747", ex.getMessage()));
                });
            }
        });
    }

    /** 发帖页面：独立的标题 / 分类 / 内容编辑区，发布成功后自动返回社区页 */
    public VBox buildPostPublish() {
        VBox root = new VBox(16);
        // 本页有外层 ScrollPane（.main-content 的 18/22 内边距已生效），叠加 8 会比其它页多缩进一圈
        root.setPadding(new Insets(0, 4, 0, 0));
        VBox.setVgrow(root, Priority.ALWAYS);

        // 顶部：返回社区 + 页面标题
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        Button backBtn = AppIcons.button("back", I18N.get("page.t0043"));
        backBtn.getStyleClass().add("back-btn");
        backBtn.setOnAction(e -> ctx.switchToPage("news"));
        Label pageTitle = new Label(I18N.get("page.t0044"));
        pageTitle.getStyleClass().add("content-title");
        header.getChildren().addAll(backBtn, pageTitle);

        // 编辑卡片
        VBox card = new VBox(12);
        card.getStyleClass().add("home-card");

        communityPostTitleField = new TextField();
        communityPostTitleField.setPromptText(I18N.get("page.t0045"));
        communityPostTitleField.getStyleClass().add("input-field");
        communityPostTitleField.setPrefWidth(Double.MAX_VALUE);

        HBox catRow = new HBox(10);
        catRow.setAlignment(Pos.CENTER_LEFT);
        Label catLabel = new Label(I18N.get("page.t0046"));
        catLabel.getStyleClass().add("community-post-time");
        communityCategoryBox = new ComboBox<>();
        communityCategoryBox.getItems().addAll(I18N.get("page.t0560"), I18N.get("page.t0561"), I18N.get("page.t0562"), I18N.get("page.t0563"), I18N.get("page.t0564"), I18N.get("page.t0565"));
        communityCategoryBox.setValue(I18N.get("page.t0047"));
        communityCategoryBox.setEditable(true);
        communityCategoryBox.setPrefWidth(160);
        catRow.getChildren().addAll(catLabel, communityCategoryBox);

        communityPostContentArea = new TextArea();
        communityPostContentArea.setPromptText(I18N.get("page.t0048"));
        communityPostContentArea.getStyleClass().add("textarea-field");
        communityPostContentArea.setPrefRowCount(12);
        communityPostContentArea.setWrapText(true);
        VBox.setVgrow(communityPostContentArea, Priority.ALWAYS);

        Button publishBtn = AppIcons.button("memo", I18N.get("page.t0049"));
        publishBtn.getStyleClass().add("btn-primary");
        publishBtn.setStyle("-fx-background-color: #3b82f6; -fx-text-fill: white; -fx-min-width: 120; -fx-padding: 8 20;");
        publishBtn.setOnAction(e -> publishCommunityPost(publishBtn));

        HBox btnRow = new HBox(10);
        btnRow.setAlignment(Pos.CENTER_RIGHT);
        btnRow.getChildren().add(publishBtn);

        card.getChildren().addAll(communityPostTitleField, catRow, communityPostContentArea, btnRow);
        root.getChildren().addAll(header, card);
        return root;
    }

    /** 发布帖子：校验登录与输入，成功后返回社区页并刷新列表 */
    private void publishCommunityPost(Button publishBtn) {
        if (!communityApi.hasToken()) {
            ctx.ui().toast(I18N.get("page.t0050"));
            return;
        }
        String title = communityPostTitleField.getText().trim();
        String content = communityPostContentArea.getText().trim();
        String category = communityCategoryBox.getValue() == null ? I18N.get("page.t0051") : communityCategoryBox.getValue().trim();
        if (title.isEmpty()) {
            ctx.ui().toast(I18N.get("page.t0052"));
            return;
        }
        if (content.isEmpty()) {
            ctx.ui().toast(I18N.get("page.t0053"));
            return;
        }
        publishBtn.setDisable(true);
        publishBtn.setText(I18N.get("page.t0054"));
        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            try {
                ApiResult<Integer> result = communityApi.postPublish(title, content, category);
                Platform.runLater(() -> {
                    publishBtn.setDisable(false);
                    AppIcons.setText(publishBtn, "memo", I18N.get("page.t0055"));
                    if (result.ok) {
                        ctx.ui().toast(I18N.get("page.t0056"));
                        // 发布成功后跳转回社区页并刷新帖子列表
                        ctx.switchToPage("news");
                        loadCommunityPosts();
                    } else {
                        ctx.ui().toast(I18N.get("page.p0748", result.msg));
                        // token 失效时（failOnAuthError 已清理登录态）同步刷新社区卡片
                        if ("it".equals(result.code) || "nl".equals(result.code)) {
                            refreshCommunityUserCard();
                            refreshCommunityCheckinCard();
                        }
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    publishBtn.setDisable(false);
                    AppIcons.setText(publishBtn, "memo", I18N.get("page.t0057"));
                    ctx.ui().toast(I18N.get("page.p0749", ex.getMessage()));
                });
            }
        });
    }

    /** 异步加载帖子列表并渲染到 communityPostList */
    private void loadCommunityPosts() {
        communityPostList.getChildren().clear();
        // 加载期间用骨架屏微光占位
        communityPostList.getChildren().add(SkeletonFactory.buildListSkeleton(3));

        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            try {
                ApiResult<List<CommunityPost>> result = communityApi.postList(1, null, false);
                Platform.runLater(() -> renderCommunityPosts(result));
            } catch (Exception e) {
                Platform.runLater(() -> {
                    communityPostList.getChildren().clear();
                    communityPostList.getChildren().add(PageKit.buildErrorState(
                            I18N.get("page.t0058"), String.valueOf(e.getMessage()), this::loadCommunityPosts));
                });
            }
        });
    }

    /** 渲染帖子列表（FX 线程） */
    private void renderCommunityPosts(ApiResult<List<CommunityPost>> result) {
        communityPostList.getChildren().clear();
        if (!result.ok) {
            communityPostList.getChildren().add(PageKit.buildErrorState(
                    I18N.get("page.t0059"), result.msg, this::loadCommunityPosts));
            return;
        }
        if (result.data == null || result.data.isEmpty()) {
            Label empty = new Label(I18N.get("page.t0060"));
            empty.getStyleClass().add("community-empty");
            communityPostList.getChildren().add(empty);
            return;
        }
        for (CommunityPost post : result.data) {
            communityPostList.getChildren().add(createCommunityPostCard(post));
        }
    }

    /**
     * 帖子卡片：展示作者 / 时间 / 分类 / 浏览与点赞数。
     * 点击卡片打开详情（完整正文 + 评论区），点赞按钮直接切换点赞状态，
     * 评论按钮在卡片内展开 / 收起评论区。
     */
    private HBox createCommunityPostCard(CommunityPost post) {
        HBox card = new HBox(12);
        card.getStyleClass().add("community-post-item");
        card.setAlignment(Pos.TOP_LEFT);

        // 作者头像：默认 emoji 占位，加载到真实头像后替换为圆形图片
        StackPane avatarBox = new StackPane();
        avatarBox.getStyleClass().add("community-avatar-sm");
        Label avatarFallback = new Label();
        AppIcons.apply(avatarFallback, "avatar", 16, null);
        avatarBox.getChildren().add(avatarFallback);

        VBox body = new VBox(6);
        HBox.setHgrow(body, Priority.ALWAYS);

        HBox meta = new HBox(10);
        meta.setAlignment(Pos.CENTER_LEFT);
        Label authorLabel = new Label(post.username == null ? I18N.get("page.t0061") : post.username);
        authorLabel.getStyleClass().add("community-post-author");
        Label timeLabel = new Label(post.createdAt == null ? "" : post.createdAt);
        timeLabel.getStyleClass().add("community-post-time");
        meta.getChildren().addAll(authorLabel, timeLabel);
        if (post.category != null && !post.category.isEmpty()) {
            Label catLabel = new Label(post.category);
            catLabel.getStyleClass().add("community-tag");
            meta.getChildren().add(catLabel);
        }
        if (post.tagName != null && !post.tagName.isEmpty()) {
            for (String tag : post.tagName) {
                if (tag == null || tag.isEmpty()) continue;
                Label tagLabel = new Label(tag);
                tagLabel.getStyleClass().add("community-tag");
                meta.getChildren().add(tagLabel);
            }
        }
        Region metaSpacer = new Region();
        HBox.setHgrow(metaSpacer, Priority.ALWAYS);
        Label viewsLabel = new Label("" + post.views);
        viewsLabel.getStyleClass().add("community-post-time");
        meta.getChildren().addAll(metaSpacer, viewsLabel);

        Label titleLabel = new Label(post.title == null ? I18N.get("page.t0062") : post.title);
        titleLabel.getStyleClass().add("community-post-title");
        titleLabel.setCursor(javafx.scene.Cursor.HAND);
        titleLabel.setOnMouseClicked(e -> {
            e.consume();
            openCommunityPostDetail(post);
        });

        Label contentLabel = new Label(I18N.get("page.t0063"));
        contentLabel.getStyleClass().add("community-post-content");
        contentLabel.setWrapText(true);

        HBox actions = new HBox(8);
        Button likeBtn = new Button("" + post.likes);
        likeBtn.getStyleClass().add("community-action-btn");
        likeBtn.setOnAction(e -> {
            e.consume();
            toggleCommunityLike(post, likeBtn);
        });
        Button commentBtn = AppIcons.button("comment", I18N.get("page.t0064"));
        commentBtn.getStyleClass().add("community-action-btn");
        VBox commentsArea = new VBox(8);
        commentsArea.setVisible(false);
        commentsArea.setManaged(false);
        commentBtn.setOnAction(e -> {
            e.consume();
            boolean expanded = commentsArea.isVisible();
            commentsArea.setVisible(!expanded);
            commentsArea.setManaged(!expanded);
            if (!expanded) {
                loadCommunityComments(post.id, commentsArea);
            }
        });
        actions.getChildren().addAll(likeBtn, commentBtn);

        body.getChildren().addAll(meta, titleLabel, contentLabel, actions, commentsArea);
        card.getChildren().addAll(avatarBox, body);
        // 异步加载作者真实头像（未设置或失败时保留 emoji 占位）
        loadCommunityAvatar(avatarBox, avatarFallback, "community-avatar-sm", post.userId, 36);

        // 整卡点击跳转到详情页（按钮已 consume 事件，不会误触）
        card.setOnMouseClicked(e -> openCommunityPostDetail(post));
        // 已登录时异步查询当前用户是否已点赞，高亮按钮
        applyPostLikedState(post, likeBtn);
        return card;
    }

    /** 点赞 / 取消点赞（异步，成功后更新按钮文案与高亮状态） */
    private void toggleCommunityLike(CommunityPost post, Button likeBtn) {
        if (!communityApi.hasToken()) {
            ctx.ui().toast(I18N.get("page.t0065"));
            return;
        }
        boolean currentLiked = likeBtn.getStyleClass().contains("liked");
        likeBtn.setDisable(true);
        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            try {
                ApiResult<Integer> result = currentLiked
                        ? communityApi.postUnlike(post.id)
                        : communityApi.postLike(post.id);
                Platform.runLater(() -> {
                    likeBtn.setDisable(false);
                    if (!result.ok) {
                        ctx.ui().toast(result.msg);
                        return;
                    }
                    int likes = result.data != null && result.data >= 0 ? result.data
                            : (currentLiked ? Math.max(0, post.likes - 1) : post.likes + 1);
                    post.likes = likes;
                    likeBtn.setText("" + likes);
                    if (currentLiked) {
                        likeBtn.getStyleClass().remove("liked");
                    } else {
                        likeBtn.getStyleClass().add("liked");
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    likeBtn.setDisable(false);
                    ctx.ui().toast(I18N.get("page.p0750", ex.getMessage()));
                });
            }
        });
    }

    /** 异步查询帖子点赞状态，已赞则高亮按钮 */
    private void applyPostLikedState(CommunityPost post, Button likeBtn) {
        if (!communityApi.hasToken()) return;
        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            try {
                ApiResult<Boolean> result = communityApi.isPostLiked(post.id);
                Platform.runLater(() -> {
                    if (result.ok && Boolean.TRUE.equals(result.data)) {
                        likeBtn.getStyleClass().add("liked");
                    }
                });
            } catch (Exception ignored) {
                // 查询失败不影响使用
            }
        });
    }

    /** 打开帖子详情页：记录目标帖子并跳转 */
    private void openCommunityPostDetail(CommunityPost post) {
        currentDetailPost = post;
        ctx.switchToPage("postDetail");
    }

    /**
     * 帖子详情页：作者信息（真实头像）+ 完整正文 + 点赞 + 评论区。
     * 页面自管理滚动：顶部返回按钮，中部内容滚动，评论输入框固定底部。
     */
    public Node buildPostDetail() {
        // 兑底：未记录帖子时回到社区页
        CommunityPost post = currentDetailPost;
        if (post == null) {
            return buildNews();
        }

        VBox root = new VBox(16);
        // 本页有外层 ScrollPane（.main-content 的 18/22 内边距已生效），叠加 8 会比其它页多缩进一圈；
        // 间距同时与其它页统一为 16
        root.setPadding(new Insets(0, 4, 0, 0));
        VBox.setVgrow(root, Priority.ALWAYS);

        // 顶部：返回社区 + 页面标题 //
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        Button backBtn = AppIcons.button("back", I18N.get("page.t0066"));
        backBtn.getStyleClass().add("back-btn");
        backBtn.setOnAction(e -> ctx.switchToPage("news"));
        Label pageTitle = new Label(I18N.get("page.t0067"));
        pageTitle.getStyleClass().add("content-title");
        header.getChildren().addAll(backBtn, pageTitle);

        // 主体：滚动内容 + 底部固定评论输入 //
        BorderPane body = new BorderPane();
        VBox.setVgrow(body, Priority.ALWAYS);

        VBox content = new VBox(14);
        content.setPadding(new Insets(4, 10, 14, 10));
        // 加载期间用骨架屏微光占位
        content.getChildren().add(SkeletonFactory.buildListSkeleton(4));

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background-color: transparent;");
        PageKit.configureScrollPane(ctx, scroll);
        body.setCenter(scroll);

        // 评论区列表（滚动区内，详情加载后填充）
        VBox commentsArea = new VBox(10);
        // 评论输入行固定底部，不随内容滚动
        HBox inputRow = createCommentInputRow(post.id, commentsArea, false);
        inputRow.setPadding(new Insets(0, 10, 10, 10));
        body.setBottom(inputRow);

        root.getChildren().addAll(header, body);

        // 异步加载完整详情 //
        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            try {
                ApiResult<CommunityPost> result = communityApi.postView(post.id);
                Platform.runLater(() -> {
                    content.getChildren().clear();
                    if (!result.ok || result.data == null) {
                        content.getChildren().add(PageKit.buildErrorState(
                                result.ok ? I18N.get("page.t0566") : I18N.get("page.t0567"), result.msg,
                                result.ok ? null : () -> ctx.switchToPage("postDetail")));
                        return;
                    }
                    CommunityPost detail = result.data;

                    // 作者信息行：真实头像 + 作者 + 时间 + 分类 + 浏览量 //
                    HBox authorRow = new HBox(10);
                    authorRow.setAlignment(Pos.CENTER_LEFT);
                    StackPane avatarBox = new StackPane();
                    avatarBox.getStyleClass().add("community-avatar-sm");
                    Label avatarFallback = new Label();
                    AppIcons.apply(avatarFallback, "avatar", 16, null);
                    avatarBox.getChildren().add(avatarFallback);

                    VBox authorInfo = new VBox(2);
                    Label authorName = new Label(detail.username == null ? I18N.get("page.t0068") : detail.username);
                    authorName.getStyleClass().add("community-dialog-author");
                    Label timeLabel = new Label(detail.createdAt == null ? "" : detail.createdAt);
                    timeLabel.getStyleClass().add("community-post-time");
                    authorInfo.getChildren().addAll(authorName, timeLabel);

                    Region authorSpacer = new Region();
                    HBox.setHgrow(authorSpacer, Priority.ALWAYS);
                    Label viewsLabel = new Label("" + detail.views);
                    viewsLabel.getStyleClass().add("community-dialog-stat");
                    authorRow.getChildren().addAll(avatarBox, authorInfo, authorSpacer);
                    if (detail.category != null && !detail.category.isEmpty()) {
                        Label catLabel = new Label(detail.category);
                        catLabel.getStyleClass().add("community-tag");
                        authorRow.getChildren().add(catLabel);
                    }
                    authorRow.getChildren().add(viewsLabel);
                    // 真实头像（未设置或失败时保留 emoji 占位）
                    loadCommunityAvatar(avatarBox, avatarFallback, "community-avatar-sm", detail.userId, 36);

                    // 标题 //
                    Label titleLabel = new Label(detail.title == null ? I18N.get("page.t0069") : detail.title);
                    titleLabel.getStyleClass().add("community-dialog-title");
                    titleLabel.setWrapText(true);

                    // 正文卡片 //
                    Label bodyLabel = new Label(detail.content == null || detail.content.isEmpty()
                            ? I18N.get("page.t0070") : detail.content);
                    bodyLabel.getStyleClass().add("community-dialog-body");
                    bodyLabel.setWrapText(true);

                    // 操作行：点赞 + 统计 //
                    HBox actionRow = new HBox(10);
                    actionRow.setAlignment(Pos.CENTER_LEFT);
                    Button likeBtn = new Button("" + detail.likes);
                    likeBtn.getStyleClass().add("community-action-btn");
                    likeBtn.setOnAction(e -> toggleCommunityLike(detail, likeBtn));
                    Label statLabel = new Label(I18N.get("cp.r0002", detail.views));
                    statLabel.getStyleClass().add("community-dialog-stat");
                    actionRow.getChildren().addAll(likeBtn, statLabel);
                    applyPostLikedState(detail, likeBtn);

                    // 分隔线 + 评论区 //
                    Region divider = new Region();
                    divider.getStyleClass().add("community-dialog-divider");
                    Label commentTitle = new Label(I18N.get("page.t0071"));
                    commentTitle.getStyleClass().add("community-dialog-title");
                    commentTitle.setStyle("-fx-font-size: 13px; -fx-font-weight: 600;");

                    content.getChildren().addAll(authorRow, titleLabel, bodyLabel, actionRow,
                            divider, commentTitle, commentsArea);
                    // 页面底部输入框已固定，不再向列表末尾追加输入行
                    loadCommunityComments(post.id, commentsArea, false);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    content.getChildren().clear();
                    content.getChildren().add(PageKit.buildErrorState(
                            I18N.get("page.t0072"), String.valueOf(e.getMessage()),
                            () -> ctx.switchToPage("postDetail")));
                });
            }
        });
        return root;
    }

    /** 评论区：加载评论列表 + 输入框 + 发表评论，填充到指定容器（默认列表末尾附加输入行） */
    private void loadCommunityComments(int postId, VBox area) {
        loadCommunityComments(postId, area, true);
    }

    /**
     * 评论区：withInput=true 时在列表末尾附加评论输入行（帖子卡片内联评论区用），
     * false 时不附加（详情弹窗的输入框固定在底部）。
     */
    private void loadCommunityComments(int postId, VBox area, boolean withInput) {
        area.getChildren().clear();
        Label loading = new Label(I18N.get("page.t0073"));
        loading.getStyleClass().add("community-empty");
        area.getChildren().add(loading);

        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            try {
                ApiResult<List<CommunityComment>> result = communityApi.commentList(postId);
                Platform.runLater(() -> {
                    area.getChildren().clear();
                    if (!result.ok) {
                        area.getChildren().add(PageKit.buildErrorState(
                                I18N.get("page.t0074"), result.msg,
                                () -> loadCommunityComments(postId, area, withInput)));
                        return;
                    }
                    List<CommunityComment> comments = result.data;
                    if (comments == null || comments.isEmpty()) {
                        Label empty = new Label(I18N.get("page.t0075"));
                        empty.getStyleClass().add("community-empty");
                        area.getChildren().add(empty);
                    } else {
                        for (CommunityComment comment : comments) {
                            area.getChildren().add(createCommunityCommentItem(comment));
                        }
                    }
                    if (withInput) {
                        area.getChildren().add(createCommentInputRow(postId, area, true));
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    area.getChildren().clear();
                    area.getChildren().add(PageKit.buildErrorState(
                            I18N.get("page.t0076"), String.valueOf(e.getMessage()),
                            () -> loadCommunityComments(postId, area, withInput)));
                });
            }
        });
    }

    /** 单条评论项：24px 真实头像 + 作者 + 时间 + 内容 */
    private HBox createCommunityCommentItem(CommunityComment comment) {
        HBox item = new HBox(10);
        item.getStyleClass().add("community-comment-item");
        item.setAlignment(Pos.TOP_LEFT);

        StackPane avatarBox = new StackPane();
        avatarBox.getStyleClass().add("community-avatar-xs");
        Label avatarFallback = new Label();
        AppIcons.apply(avatarFallback, "avatar", 12, null);
        avatarBox.getChildren().add(avatarFallback);

        VBox body = new VBox(3);
        HBox.setHgrow(body, Priority.ALWAYS);

        HBox meta = new HBox(8);
        meta.setAlignment(Pos.CENTER_LEFT);
        Label author = new Label(comment.username == null ? I18N.get("page.t0077") : comment.username);
        author.getStyleClass().add("community-comment-author");
        Label time = new Label(comment.createdAt == null ? "" : comment.createdAt);
        time.getStyleClass().add("community-comment-time");
        meta.getChildren().addAll(author, time);

        Label content = new Label(comment.content == null ? "" : comment.content);
        content.getStyleClass().add("community-comment-text");
        content.setWrapText(true);

        body.getChildren().addAll(meta, content);
        item.getChildren().addAll(avatarBox, body);
        // 真实头像（服务端未返回 user_id 时跳过，显示默认占位）
        loadCommunityAvatar(avatarBox, avatarFallback, "community-avatar-xs", comment.userId, 24);
        return item;
    }

    /** 评论输入行：发表成功后刷新评论区 */
    private HBox createCommentInputRow(int postId, VBox area) {
        return createCommentInputRow(postId, area, true);
    }

    /** 评论输入行：withInput 透传给刷新调用，保证弹窗固定底部输入框刷新后不重复添加 */
    private HBox createCommentInputRow(int postId, VBox area, boolean withInput) {
        TextField input = new TextField();
        input.setPromptText(I18N.get("page.t0078"));
        input.getStyleClass().add("input-field");
        HBox.setHgrow(input, Priority.ALWAYS);

        Button submitBtn = AppIcons.button("comment", I18N.get("page.t0079"));
        submitBtn.getStyleClass().add("btn-primary");
        submitBtn.setOnAction(e -> {
            if (!communityApi.hasToken()) {
                ctx.ui().toast(I18N.get("page.t0080"));
                return;
            }
            String content = input.getText().trim();
            if (content.isEmpty()) {
                ctx.ui().toast(I18N.get("page.t0081"));
                return;
            }
            submitBtn.setDisable(true);
            UIGeneralControlClass.ASYNC_POOL.submit(() -> {
                try {
                    ApiResult<Void> result = communityApi.commentAdd(postId, content);
                    Platform.runLater(() -> {
                        submitBtn.setDisable(false);
                        if (result.ok) {
                            ctx.ui().toast(I18N.get("page.t0082"));
                            // 透传 withInput：弹窗固定底部输入框刷新后不重复添加输入行
                            loadCommunityComments(postId, area, withInput);
                        } else {
                            ctx.ui().toast(I18N.get("page.p0751", result.msg));
                        }
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        submitBtn.setDisable(false);
                        ctx.ui().toast(I18N.get("page.p0752", ex.getMessage()));
                    });
                }
            });
        });

        HBox row = new HBox(8);
        row.getChildren().addAll(input, submitBtn);
        return row;
    }

}



