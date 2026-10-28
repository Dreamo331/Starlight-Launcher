package com.example.starlight.newui.page.settings;


import com.example.starlight.util.I18N;
import com.example.starlight.auth.AccountManager;
import com.example.starlight.auth.AccountManager.Account;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.SkinSettingsPanel;
import com.example.starlight.newui.page.settings.account.AccountDetailPane;
import com.example.starlight.newui.page.settings.account.AccountListPane;
import com.example.starlight.newui.page.settings.account.AccountPageHost;
import com.example.starlight.newui.page.settings.account.LoginMethodsPane;
import com.example.starlight.newui.page.settings.account.MicrosoftLoginPane;
import com.example.starlight.newui.page.settings.account.OfflineLoginPane;
import com.example.starlight.newui.page.settings.account.ThirdPartyLoginPane;
import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.ui.CollapsibleBox;
import com.example.starlight.newui.ui.PageKit;
import com.example.starlight.newui.ui.PaneSwitcher;

import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.List;

/**
 * 设置 → 账户页：按 {@code references/html/2.0.html} 重做的账号管理中心。
 *
 * <p>版式：居中窄列（卡片正上方是问候标题）→ 一张卡片壳，壳内七个面板互斥显示
 * （列表 / 详情 / 登录方式 / 微软设备码 / 离线 / 第三方 / 加载）→ 下方「添加新的账户」按钮 → 条款。
 * 卡片壳的切换动画（高度过渡 + 面板淡入）由 {@link PaneSwitcher} 负责；
 * 进入详情或「添加账户」视图时，上方的问候/搜索/筛选与下方的添加按钮一起折叠收起
 * （{@link CollapsibleBox}），与 HTML 的 .collapsible 行为一致。
 *
 * <p>标题：没有账号时显示「选择或注册一个账号」，有账号时显示「你好，<当前账号名>」；
 * 设置页外壳自带的「账户」标题已移除，避免与问候重复。
 */
public final class GameAccountPage implements AccountPageHost {

    /** 内容列宽度（2.0.html 是 560px；这里收窄一点配合整页尺寸） */
    private static final double COLUMN_MAX_WIDTH = 520;

    private final LauncherContext ctx;

    private VBox root;
    private Label greeting;
    private CollapsibleBox topControls;
    private CollapsibleBox bottomControls;
    private PaneSwitcher switcher;
    private AccountListPane listPane;
    private AccountDetailPane detailPane;
    private TextField searchField;

    /** 当前类型筛选：all / ms / offline / third */
    private String filter = "all";

    /** 全屏「正在通信」遮罩（挂在场景根上，覆盖整个窗口）；null 表示未显示 */
    private StackPane busyOverlay;
    /** 忙碌前停留的面板，收起遮罩后回到这里 */
    private String paneBeforeBusy;

    public GameAccountPage(LauncherContext ctx) {
        this.ctx = ctx;
    }

    public Node build() {
        root = PageKit.settingsPage(ctx, I18N.get("gameaccount.t0001"));
        // 「账户」标题由卡片正上方的问候语取代（title 仍传给外壳，侧边栏隐藏时的下拉导航要靠它回显）
        PageKit.removePageTitle(root);

        greeting = new Label();
        greeting.getStyleClass().add("account-greeting");
        greeting.setWrapText(true);

        searchField = PageKit.searchField(I18N.get("gameaccount.t0002"), "",
                keyword -> listPane.applyFilter(keyword, filter));
        searchField.getStyleClass().add("account-search-field");
        Region searchIcon = AppIcons.icon("search", 15, null);
        searchIcon.setMouseTransparent(true);
        StackPane searchBox = new StackPane(searchField, searchIcon);
        searchBox.getStyleClass().add("account-search-box");
        StackPane.setAlignment(searchIcon, Pos.CENTER_LEFT);
        StackPane.setMargin(searchIcon, new Insets(0, 0, 0, 14));

        topControls = new CollapsibleBox(new VBox(10, greeting, searchBox, buildFilters()));

        // 卡片壳与七个面板
        switcher = new PaneSwitcher();
        switcher.getStyleClass().add("account-card-shell");

        listPane = new AccountListPane(ctx, this, this::showDetail);
        detailPane = new AccountDetailPane(this, this::backToList);
        LoginMethodsPane methodsPane = new LoginMethodsPane(this::backToList, this::pickMethod);
        OfflineLoginPane offlinePane = new OfflineLoginPane(this, () -> switcher.show("methods"));
        ThirdPartyLoginPane thirdPane = new ThirdPartyLoginPane(this,
                () -> switcher.show("methods"), () -> switcher.show("methods"));
        MicrosoftLoginPane microsoftPane = new MicrosoftLoginPane(this,
                () -> switcher.show("methods"), () -> switcher.show("methods"));

        switcher.register("list", listPane.node(), this::expandControls, null);
        switcher.register("detail", detailPane.node(), null, null);
        switcher.register("methods", methodsPane.node(), null, null);
        switcher.register("offline", offlinePane.node(), offlinePane::focusField, null);
        switcher.register("third", thirdPane.node(), null, null);
        switcher.register("microsoft", microsoftPane.node(), microsoftPane::onShow, microsoftPane::onHide);
        switcher.register("loading", buildLoadingPane(), null, null);

        bottomControls = new CollapsibleBox(buildAddRow());

        // 三层叠放：顶部控件贴顶、卡片在页面正中、底部（添加账户 + 条款）贴底。
        // 卡片单独一层才能做到「居中不变的始终是卡片本身」——上下控件折叠/展开与面板高度过渡
        // 都是逐帧重排，卡片因此表现为原地变形、位置停在中心不动。
        VBox topLayer = layer(topControls);
        VBox cardLayer = layer(switcher);
        VBox bottomLayer = layer(bottomControls, buildFooter());

        StackPane pageCenter = new StackPane(topLayer, cardLayer, bottomLayer);
        pageCenter.getStyleClass().add("account-page-center");
        StackPane.setAlignment(topLayer, Pos.TOP_CENTER);
        StackPane.setAlignment(cardLayer, Pos.CENTER);
        StackPane.setAlignment(bottomLayer, Pos.BOTTOM_CENTER);
        pageCenter.setMinHeight(0);
        VBox.setVgrow(pageCenter, Priority.ALWAYS);
        root.getChildren().add(pageCenter);

        switcher.showImmediate("list");
        refreshAccountViews();
        return root;
    }

    // 列表与问候语 //

    /**
     * 页面的一层：宽度锁在内容列宽并居中，高度锁在自身首选高（避免被 StackPane 拉伸）。
     * 三层（顶部控件 / 卡片 / 底部控件+条款）共用同一宽度基准，视觉上仍是一条居中的窄列。
     */
    private VBox layer(Node... children) {
        VBox box = new VBox(8, children);
        box.setPrefWidth(COLUMN_MAX_WIDTH);
        box.setMaxWidth(COLUMN_MAX_WIDTH);
        box.setMaxHeight(Region.USE_PREF_SIZE);
        box.setFillWidth(true);
        return box;
    }

    /**
     * 卡下条款：两个文档名是可点的，点开用系统浏览器访问官方仓库里对应的 .md。
     *
     * <p>条款正文的唯一真源是 {@code docs/} 下那两份文件，界面只做引用与跳转、不在代码里复述；
     * 也不把它们打进 jar（改条款无需重新打包，且避免正文出现第二份副本）。
     */
    private HBox buildFooter() {
        Label terms = footerLink(I18N.get("gameaccount.t0003"), I18N.get("gameaccount.t0004"));
        Label privacy = footerLink(I18N.get("gameaccount.t0005"), I18N.get("gameaccount.t0006"));
        HBox row = new HBox(4,
                footerPlain(I18N.get("gameaccount.t0007")), terms, footerPlain(I18N.get("gameaccount.t0008")), privacy, footerPlain("。"));
        row.setAlignment(Pos.CENTER);
        row.getStyleClass().add("account-page-footer");
        return row;
    }

    private Label footerPlain(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("account-footer-plain");
        return label;
    }

    private Label footerLink(String text, String docFile) {
        Label link = new Label(text);
        link.getStyleClass().add("account-footer-link");
        link.setCursor(Cursor.HAND);
        link.setOnMouseClicked(e -> openWeb(docUrl(docFile)));
        return link;
    }

    /** 文档地址：官方仓库 docs/ 下的对应文件（中文文件名按 URL 编码） */
    private String docUrl(String docFile) {
        String encoded = java.net.URLEncoder.encode(docFile, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        return "https://github.com/" + com.example.starlight.config.Endpoints.githubRepo()
                + "/blob/main/docs/" + encoded;
    }

    /** 重建列表行并同步问候标题（账号集合、当前账号、皮肤等变化后调用） */
    private void refreshAccountViews() {
        Account current = AccountManager.getCurrentAccount();
        greeting.setText(current == null ? I18N.get("gameaccount.t0009") : I18N.get("gameaccount.k0001", current.name));
        List<Account> accounts = AccountManager.listAccounts();
        listPane.rebuild(accounts, current == null ? null : current.id);
    }

    private HBox buildFilters() {
        HBox row = new HBox(8);
        row.getStyleClass().add("account-filters");
        // 筛选标签整行居中（HBox 撑满列宽后由对齐方式决定位置）
        row.setAlignment(Pos.CENTER);
        row.setMaxWidth(Double.MAX_VALUE);
        ToggleGroup group = new ToggleGroup();
        String[][] defs = {{I18N.get("gameaccount.t0010"), "all"}, {I18N.get("gameaccount.t0011"), "ms"}, {I18N.get("gameaccount.t0012"), "offline"}, {I18N.get("gameaccount.t0013"), "third"}};
        for (String[] def : defs) {
            ToggleButton chip = new ToggleButton(def[0]);
            chip.getStyleClass().add("filter-chip");
            chip.setToggleGroup(group);
            chip.setSelected("all".equals(def[1]));
            chip.setMinWidth(Region.USE_PREF_SIZE);
            chip.setOnAction(e -> {
                // 组内允许取消选中会导致「一个都没选」，再点一次补回
                if (!chip.isSelected()) {
                    chip.setSelected(true);
                    return;
                }
                filter = def[1];
                listPane.applyFilter(searchField.getText(), filter);
            });
            row.getChildren().add(chip);
        }
        return row;
    }

    private HBox buildAddRow() {
        HBox row = new HBox(14);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("add-account");
        row.setCursor(Cursor.HAND);

        Label avatar = new Label();
        avatar.getStyleClass().addAll("account-avatar", "account-avatar-add");
        AppIcons.apply(avatar, "user-plus", 18, null);

        Label title = new Label(I18N.get("gameaccount.t0014"));
        title.getStyleClass().add("add-account-text");
        HBox.setHgrow(title, Priority.ALWAYS);

        Region chevron = AppIcons.icon("arrow-right", 16, javafx.scene.paint.Color.web("#d1d5db"));
        chevron.getStyleClass().add("account-row-arrow");

        row.getChildren().addAll(avatar, title, chevron);
        row.setOnMouseClicked(e -> enterAddView());
        return row;
    }

    private VBox buildLoadingPane() {
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(24, 24);
        spinner.setMaxSize(24, 24);
        Label text = new Label(I18N.get("gameaccount.t0015"));
        text.getStyleClass().add("loading-text");
        VBox body = new VBox(12, spinner, text);
        body.setAlignment(Pos.CENTER);
        body.getStyleClass().add("loading-body");
        return body;
    }

    // 面板流转 //

    private void showDetail(Account account) {
        if (account == null) return;
        Account current = AccountManager.getCurrentAccount();
        detailPane.fill(account, current != null && account.id.equals(current.id));
        topControls.setCollapsed(true);
        bottomControls.setCollapsed(true);
        switcher.show("detail");
    }

    private void enterAddView() {
        topControls.setCollapsed(true);
        bottomControls.setCollapsed(true);
        switcher.show("methods");
    }

    private void backToList() {
        switcher.show("list");
    }

    private void expandControls() {
        topControls.setCollapsed(false);
        bottomControls.setCollapsed(false);
    }

    private void pickMethod(String method) {
        switch (method) {
            case "microsoft" -> switcher.show("microsoft");
            case "offline" -> switcher.show("offline");
            case "third" -> switcher.show("third");
            default -> switcher.show("methods");
        }
    }

    // AccountPageHost //

    @Override
    public void toast(String message) {
        ctx.toast(message);
    }

    @Override
    public void openWeb(String url) {
        ctx.openWebUrl(url);
    }

    @Override
    public void confirmDelete(String message, Runnable onConfirm) {
        ctx.confirmDelete(message, onConfirm);
    }

    @Override
    public void openSkinSettings(Account account) {
        SkinSettingsPanel panel = new SkinSettingsPanel(account, () -> {
            refreshAccountViews();
            ctx.toast(I18N.get("gameaccount.t0016"));
        }, ctx.ui()::closeModal);
        ctx.ui().modal(I18N.get("gameaccount.k0002", account.name), panel, 660, 440);
    }

    @Override
    public void accountChanged(String message) {
        // 原地刷新列表与问候语：不重建页面，切换动画与滚动位置都保留
        refreshAccountViews();
        if (message != null && !message.isBlank()) ctx.toast(message);
    }

    @Override
    public void accountLoggedIn(String message) {
        refreshAccountViews();
        expandControls();
        switcher.show("list");
        if (message != null && !message.isBlank()) ctx.toast(message);
    }

    @Override
    public void showPane(String paneKey) {
        switcher.show(paneKey);
    }

    @Override
    public void showBusy(String message) {
        paneBeforeBusy = switcher.current();
        switcher.show("loading");
        showBusyOverlay(message);
    }

    @Override
    public void hideBusy() {
        hideBusyOverlay();
        if (paneBeforeBusy != null) {
            switcher.show(paneBeforeBusy);
            paneBeforeBusy = null;
        }
    }

    // 全屏「正在通信」遮罩（对齐 HTML 的 .page-loader：淡入 + 半透明底 + 转圈 + 两行说明） //

    private void showBusyOverlay(String message) {
        if (busyOverlay != null) return;
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(36, 36);
        spinner.setMaxSize(36, 36);
        Label title = new Label(message == null || message.isBlank() ? I18N.get("gameaccount.t0017") : message);
        title.getStyleClass().add("page-loader-title");
        Label desc = new Label(I18N.get("gameaccount.t0018"));
        desc.getStyleClass().add("page-loader-desc");
        VBox inner = new VBox(14, spinner, title, desc);
        inner.setAlignment(Pos.CENTER);
        inner.getStyleClass().add("page-loader-inner");

        StackPane overlay = new StackPane(inner);
        overlay.getStyleClass().add("account-page-loader");
        // 遮罩期间吃掉鼠标事件：避免用户在请求进行中重复提交
        overlay.setOnMouseClicked(javafx.event.Event::consume);
        overlay.setOnMousePressed(javafx.event.Event::consume);
        overlay.setOpacity(0);
        busyOverlay = overlay;
        ctx.rootPane().getChildren().add(overlay);
        FadeTransition fade = new FadeTransition(Duration.millis(350), overlay);
        fade.setToValue(1);
        fade.play();
    }

    private void hideBusyOverlay() {
        StackPane overlay = busyOverlay;
        if (overlay == null) return;
        busyOverlay = null;
        FadeTransition fade = new FadeTransition(Duration.millis(350), overlay);
        fade.setToValue(0);
        fade.setOnFinished(e -> ctx.rootPane().getChildren().remove(overlay));
        fade.play();
    }
}