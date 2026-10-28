package com.example.starlight.newui.page.settings.account;


import com.example.starlight.util.I18N;
import com.example.starlight.auth.AccountManager;
import com.example.starlight.auth.AccountManager.Account;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.ui.PageKit;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 账户列表面板：账号行、悬停操作、单击切换 / 双击详情、搜索与类型筛选、滚动渐隐、空态。
 *
 * <p>对齐 {@code references/html/2.0.html} 的列表区：
 * 行 = 类型着色圆形头像 + 名称（当前账号带「当前使用中」绿标，出现时缩放淡入）+ 类型说明
 * + 行尾（默认箭头、悬停换成操作按钮）；列表内部滚动，首尾各有一道渐隐遮罩提示还有内容。
 *
 * <p>点击语义与 HTML 一致：单击 = 切到该账号，双击 = 打开详情；两者靠 280ms 窗口区分，
 * 窗口内点到另一行则上一行立即生效（避免快速连点时前一次点击被吞掉）。
 */
public final class AccountListPane {

    /** 列表视口一次显示的账号行数（滚动区域高度 = 行高 × 本值） */
    private static final int VISIBLE_ROWS = 4;

    /** 列表视口最大高度：正好放下 VISIBLE_ROWS 行，多出来的内容内部滚动（HTML 是 min(60dvh,520px)） */
    private static final double LIST_MAX_HEIGHT = 61 * VISIBLE_ROWS;

    /** 单击 / 双击判定窗口（与 HTML 相同的 280ms） */
    private static final Duration CLICK_WINDOW = Duration.millis(280);

    /** 单个账号行的高度（上下内边距 11×2 + 头像 38 + 行分隔线 1）：算列表视口高度用 */
    private static final double ROW_HEIGHT = 61;

    /** 空态行的高度（.account-empty-state 的内边距 + 一行文字） */
    private static final double EMPTY_HEIGHT = 76;

    /** 行尾箭头色（对齐 HTML 的 #d1d5db） */
    private static final Color ARROW_COLOR = Color.web("#d1d5db");

    private final LauncherContext ctx;
    private final AccountPageHost host;
    private final Consumer<Account> onShowDetail;

    private final StackPane root = new StackPane();
    private final VBox rows = new VBox();
    private final ScrollPane scroll;
    private final Region fadeTop = new Region();
    private final Region fadeBottom = new Region();
    private final Label emptyState = new Label();

    private final List<Account> accounts = new ArrayList<>();
    private String currentId;
    private String keyword = "";
    private String filter = "all";

    /** 待判定的单击目标：换行点击时前一个立即生效（与 HTML 的 pendingRow 行为一致） */
    private Account pendingAccount;
    private final PauseTransition clickTimer = new PauseTransition(CLICK_WINDOW);

    public AccountListPane(LauncherContext ctx, AccountPageHost host, Consumer<Account> onShowDetail) {
        this.ctx = ctx;
        this.host = host;
        this.onShowDetail = onShowDetail;

        rows.getStyleClass().add("account-rows");
        rows.setFillWidth(true);

        scroll = new ScrollPane(rows);
        scroll.getStyleClass().add("account-list-scroll");
        scroll.setFitToWidth(true);
        // 视口高度在 applyFilter 里按「可见行数」显式算好：只设 maxHeight 的话，
        // ScrollPane 的首选高仍等于内容高、会把整页撑高（页面就装不下了）。
        scroll.setMinHeight(0);
        scroll.setMaxHeight(LIST_MAX_HEIGHT);
        // 与全局滚动一致：惯性引擎 + 隐藏滚动条；滚轮落在列表上时由项目内的嵌套判定交给本列表
        PageKit.configureScrollPane(ctx, scroll);
        scroll.vvalueProperty().addListener((o, a, b) -> updateFades());

        fadeTop.getStyleClass().add("account-fade-top");
        fadeBottom.getStyleClass().add("account-fade-bottom");
        fadeTop.setMouseTransparent(true);
        fadeBottom.setMouseTransparent(true);
        StackPane.setAlignment(fadeTop, Pos.TOP_CENTER);
        StackPane.setAlignment(fadeBottom, Pos.BOTTOM_CENTER);

        emptyState.getStyleClass().add("account-empty-state");
        emptyState.setWrapText(true);
        emptyState.setMouseTransparent(true);

        root.getStyleClass().add("account-list-pane");
        root.getChildren().addAll(scroll, fadeTop, fadeBottom);

        clickTimer.setOnFinished(e -> flushPending());
    }

    public Node node() {
        return root;
    }

    /** 用最新账号重建行（页码、当前账号、类型说明都会跟着变） */
    public void rebuild(List<Account> accounts, String currentId) {
        this.accounts.clear();
        if (accounts != null) this.accounts.addAll(accounts);
        this.currentId = currentId;
        cancelPendingClick();

        rows.getChildren().clear();
        for (Account account : this.accounts) {
            rows.getChildren().add(buildRow(account));
        }
        rows.getChildren().add(emptyState);
        applyFilter(this.keyword, this.filter);
    }

    /**
     * 应用搜索词与类型筛选。
     *
     * @param keyword 搜索词（匹配账号名 / 类型 / 认证服务器，忽略大小写；空串 = 不过滤）
     * @param type    all / ms / offline / third
     */
    public void applyFilter(String keyword, String type) {
        this.keyword = keyword == null ? "" : keyword.trim().toLowerCase();
        this.filter = type == null || type.isBlank() ? "all" : type;

        int visible = 0;
        for (Node node : rows.getChildren()) {
            if (!(node instanceof HBox row) || !(row.getUserData() instanceof Account account)) continue;
            boolean typeOk = "all".equals(filter) || AccountPanesUi.typeKey(account.type).equals(filter);
            boolean searchOk = this.keyword.isEmpty() || searchText(account).contains(this.keyword);
            boolean show = typeOk && searchOk;
            row.setVisible(show);
            row.setManaged(show);
            if (show) visible++;
        }

        emptyState.setText(this.accounts.isEmpty()
                ? I18N.get("account.t0074")
                : I18N.get("account.t0075"));
        emptyState.setVisible(visible == 0);
        emptyState.setManaged(visible == 0);

        // 列表视口高度：按可见行数取（行高固定：内边距 11×2 + 头像 38 + 分隔线 1），封顶 LIST_MAX_HEIGHT。
        // 账号少时列表自然收窄，整页才能一屏放下。
        double contentHeight = visible > 0 ? visible * ROW_HEIGHT : EMPTY_HEIGHT;
        scroll.setPrefHeight(Math.min(LIST_MAX_HEIGHT, contentHeight + 2));

        cancelPendingClick();
        // 行数变化要等这次布局结束才能算准可滚区间
        Platform.runLater(this::updateFades);
    }

    // 行构建 //

    private HBox buildRow(Account account) {
        boolean isCurrent = account.id.equals(currentId);

        HBox row = new HBox(14);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().addAll("account-row", "account-row-with-actions");
        row.setUserData(account);
        row.setCursor(javafx.scene.Cursor.HAND);

        VBox info = new VBox(3);
        info.setMinWidth(0);
        HBox.setHgrow(info, Priority.ALWAYS);

        HBox nameLine = new HBox(8);
        nameLine.setAlignment(Pos.CENTER_LEFT);
        Label name = new Label(account.name);
        name.getStyleClass().add("account-row-name");
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        name.setMinWidth(0);
        nameLine.getChildren().add(name);
        if (isCurrent) {
            Label tag = AppIcons.label("bullet", I18N.get("account.t0012"));
            tag.getStyleClass().add("account-status-tag");
            nameLine.getChildren().add(tag);
            animateTag(tag);
        }
        Label desc = new Label(describe(account));
        desc.getStyleClass().add("account-row-desc");
        desc.setTextOverrun(OverrunStyle.ELLIPSIS);
        info.getChildren().addAll(nameLine, desc);

        HBox actions = new HBox(8);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.getStyleClass().add("account-row-actions");
        actions.setMinWidth(Region.USE_PREF_SIZE);
        if (!isCurrent) {
            Button switchBtn = AccountPanesUi.iconButton("refresh", I18N.get("account.t0013"), "switch");
            switchBtn.setOnAction(e -> {
                cancelPendingClick();
                switchTo(account);
            });
            actions.getChildren().add(switchBtn);
        }
        // 微软账号的皮肤由官方账号系统管理，只有离线 / 第三方账号能自定义
        if (account.type != AccountManager.AccountType.MICROSOFT) {
            Button skinBtn = AccountPanesUi.iconButton("palette", I18N.get("account.t0014"), null);
            skinBtn.setOnAction(e -> {
                cancelPendingClick();
                host.openSkinSettings(account);
            });
            actions.getChildren().add(skinBtn);
        }
        Button deleteBtn = AccountPanesUi.iconButton("trash", I18N.get("account.t0015"), "danger");
        deleteBtn.setOnAction(e -> {
            cancelPendingClick();
            host.confirmDelete(I18N.get("alp.r0002", account.displayName()),
                    () -> {
                        AccountManager.removeAccount(account.id);
                        host.accountChanged(I18N.get("account.p0091", account.name));
                    });
        });
        actions.getChildren().add(deleteBtn);

        Region chevron = AppIcons.icon("arrow-right", 16, ARROW_COLOR);
        chevron.getStyleClass().add("account-row-arrow");
        // 悬停时箭头右移并淡出（淡出由 CSS 的 :hover 规则完成，这里只负责位移）
        TranslateTransition chevronSlide = new TranslateTransition(Duration.millis(180), chevron);
        row.setOnMouseEntered(e -> {
            chevronSlide.stop();
            chevronSlide.setToX(6);
            chevronSlide.playFromStart();
        });
        row.setOnMouseExited(e -> {
            chevronSlide.stop();
            chevronSlide.setToX(0);
            chevronSlide.playFromStart();
        });

        StackPane tail = new StackPane(chevron, actions);
        tail.setAlignment(Pos.CENTER_RIGHT);
        tail.setMinWidth(Region.USE_PREF_SIZE);

        row.getChildren().addAll(
                AccountPanesUi.avatarBox(account, AccountPanesUi.avatarClass(account.type),
                        AccountPanesUi.AVATAR_SIZE),
                info, tail);

        row.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            if (e.getClickCount() >= 2 || pendingAccount == account) {
                cancelPendingClick();
                onShowDetail.accept(account);
                return;
            }
            // 换行连点：上一行立即生效，与新一次点一起构成「单击」语义
            if (pendingAccount != null) flushPending();
            pendingAccount = account;
            clickTimer.playFromStart();
        });
        return row;
    }

    /** 「当前使用中」绿标出现动画：缩放 .85→1 + 淡入（对齐 HTML 的 tagIn 300ms） */
    private void animateTag(Label tag) {
        tag.setOpacity(0);
        tag.setScaleX(0.85);
        tag.setScaleY(0.85);
        ScaleTransition scale = new ScaleTransition(Duration.millis(300), tag);
        scale.setToX(1);
        scale.setToY(1);
        FadeTransition fade = new FadeTransition(Duration.millis(300), tag);
        fade.setToValue(1);
        new ParallelTransition(scale, fade).play();
    }

    // 交互 //

    private void flushPending() {
        Account account = pendingAccount;
        pendingAccount = null;
        if (account == null || account.id.equals(currentId)) return;
        switchTo(account);
    }

    private void cancelPendingClick() {
        clickTimer.stop();
        pendingAccount = null;
    }

    private void switchTo(Account account) {
        if (account == null || account.id.equals(currentId)) return;
        boolean ok = AccountManager.setCurrentAccount(account.id);
        ctx.loadConfig();
        if (ok) {
            host.accountChanged(I18N.get("account.p0092", account.name));
        } else {
            host.toast(I18N.get("account.p0093", account.name));
        }
    }

    // 杂项 //

    private void updateFades() {
        double range = rows.getHeight() - scroll.getViewportBounds().getHeight();
        if (range <= 0) {
            fadeTop.setOpacity(0);
            fadeBottom.setOpacity(0);
            return;
        }
        double value = scroll.getVvalue();
        fadeTop.setOpacity(value > 0.01 ? 1 : 0);
        fadeBottom.setOpacity(value < 0.99 ? 1 : 0);
    }

    /** 搜索用的原文：账号名 + 类型说明 + 认证服务器地址，全部转小写 */
    private String searchText(Account account) {
        StringBuilder sb = new StringBuilder();
        sb.append(account.name == null ? "" : account.name).append(' ')
                .append(account.getTypeLabel()).append(' ');
        if (account.authServer != null) sb.append(account.authServer);
        return sb.toString().toLowerCase();
    }

    private String describe(Account account) {
        return switch (account.type) {
            case MICROSOFT -> I18N.get("account.t0016");
            case OFFLINE -> I18N.get("account.t0017");
            case THIRD_PARTY -> account.authServer != null && !account.authServer.isBlank()
                    ? I18N.get("adp.r0001", account.authServer)
                    : I18N.get("account.t0076");
        };
    }
}