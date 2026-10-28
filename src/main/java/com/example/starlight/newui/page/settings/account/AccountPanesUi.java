package com.example.starlight.newui.page.settings.account;


import com.example.starlight.util.I18N;
import com.example.starlight.newui.ui.AppIcons;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * 账户页各面板共用的小控件工厂（对齐 references/html/2.0.html 的 .pane-header / .btn-icon / .avatar）。
 *
 * <p>都是纯样式拼装，不含业务逻辑：面板只关心「哪里需要一个返回按钮 / 图标按钮 / 圆形头像」。
 */
final class AccountPanesUi {

    private AccountPanesUi() {
    }

    /**
     * 带返回按钮的面板头（详情 / 微软 / 离线 / 第三方面板用）。
     *
     * @param title  面板标题
     * @param onBack 返回动作
     */
    static HBox header(String title, Runnable onBack) {
        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("pane-header");
        header.getChildren().addAll(backButton(onBack), titleLabel(title));
        return header;
    }

    /**
     * 整行可点的面板头（登录方式面板用，对齐 HTML 的 .pane-header--back）：
     * 点哪儿都能返回，而不是只有按钮可点。
     */
    static HBox clickableHeader(String title, Runnable onBack) {
        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().addAll("pane-header", "pane-header-clickable");
        header.setCursor(javafx.scene.Cursor.HAND);
        Region arrow = AppIcons.icon("back", 16, null);
        arrow.getStyleClass().add("back-arrow");
        header.getChildren().addAll(arrow, titleLabel(title));
        header.setOnMouseClicked(e -> onBack.run());
        return header;
    }

    static Label titleLabel(String title) {
        Label label = new Label(title);
        label.getStyleClass().add("pane-title");
        return label;
    }

    private static Button backButton(Runnable onBack) {
        Button back = new Button();
        back.getStyleClass().addAll("account-icon-btn", "back-btn");
        AppIcons.apply(back, "back", 15, null);
        back.setTooltip(new Tooltip(I18N.get("account.t0018")));
        back.setOnAction(e -> onBack.run());
        return back;
    }

    /**
     * 图标按钮（行内操作 / 面板按钮）：图标颜色跟随按钮文字色，深浅主题自动切换。
     *
     * @param extraStyleClass 追加样式类（如 "danger" / "switch"），可为 null
     */
    static Button iconButton(String icon, String tip, String extraStyleClass) {
        Button btn = new Button();
        btn.getStyleClass().add("account-icon-btn");
        if (extraStyleClass != null) btn.getStyleClass().add(extraStyleClass);
        AppIcons.apply(btn, icon, 15, null);
        if (tip != null) btn.setTooltip(new Tooltip(tip));
        return btn;
    }

    /**
     * 圆形头像：账号名首字母，底色按账号类型区分。
     *
     * @param typeStyleClass "account-avatar-ms" / "-offline" / "-third"；null 表示默认灰
     */
    static Label avatar(String text, String typeStyleClass) {
        Label avatar = new Label(text);
        avatar.getStyleClass().add("account-avatar");
        if (typeStyleClass != null) avatar.getStyleClass().add(typeStyleClass);
        return avatar;
    }

    /** 账号类型 → 头像/筛选用的短标识（与 2.0.html 的 data-type 一致：ms / offline / third） */
    static String typeKey(com.example.starlight.auth.AccountManager.AccountType type) {
        return switch (type) {
            case MICROSOFT -> "ms";
            case OFFLINE -> "offline";
            case THIRD_PARTY -> "third";
        };
    }

    /** 账号类型 → 头像样式类 */
    static String avatarClass(com.example.starlight.auth.AccountManager.AccountType type) {
        return "account-avatar-" + switch (type) {
            case MICROSOFT -> "ms";
            case OFFLINE -> "offline";
            case THIRD_PARTY -> "third";
        };
    }

    /** 账号名首字母（空名退化为 ?） */
    static String initialOf(String name) {
        if (name == null || name.isBlank()) return "?";
        return name.substring(0, 1);
    }

    /** 列表 / 添加行头像边长（与 CSS 的 .account-avatar 一致） */
    static final double AVATAR_SIZE = 38;

    /** 详情页大头像边长（与 CSS 的 .account-avatar-lg 一致） */
    static final double AVATAR_SIZE_LARGE = 60;

    /**
     * 头像容器：字母占位 + 异步加载的真实皮肤头像（加载失败 / 加载中时显示字母，不会空白）。
     *
     * @param account        账号（决定头像来源：微软/第三方走官方、离线走本地皮肤）
     * @param typeStyleClass 字母占位的配色类（"account-avatar-ms" 等）
     * @param size           显示边长
     */
    static StackPane avatarBox(com.example.starlight.auth.AccountManager.Account account,
                              String typeStyleClass, double size) {
        Label letter = avatar(initialOf(account == null ? null : account.name), typeStyleClass);
        StackPane holder = new StackPane(letter);
        holder.getStyleClass().add("account-avatar-box");
        holder.setMinSize(size, size);
        holder.setPrefSize(size, size);
        holder.setMaxSize(size, size);
        com.example.starlight.newui.ui.AccountAvatars.load(holder, account, size);
        return holder;
    }

    /**
     * 表单字段：标签 + 输入控件 + 行内错误行（对齐 HTML 的 .field / .field-error）。
     *
     * @param error 错误行 Label（初始不可见，由 {@link #error} / {@link #clearError} 控制）
     */
    static VBox field(String labelText, javafx.scene.Node input, Label error) {
        VBox field = new VBox(6);
        field.getStyleClass().add("account-field");
        Label label = new Label(labelText);
        label.getStyleClass().add("account-field-label");
        error.getStyleClass().add("account-field-error");
        error.setVisible(false);
        error.setManaged(false);
        error.setWrapText(true);
        field.getChildren().addAll(label, input, error);
        return field;
    }

    /** 标记字段为错误并显示错误文案（错误行上浮淡入，对齐 HTML 的 errIn 200ms） */
    static void error(javafx.scene.control.TextInputControl input, Label error, String message) {
        input.getStyleClass().add("invalid");
        error.setText(message);
        boolean wasHidden = !error.isVisible();
        error.setVisible(true);
        error.setManaged(true);
        if (wasHidden) {
            error.setOpacity(0);
            error.setTranslateY(-2);
            javafx.animation.FadeTransition fade =
                    new javafx.animation.FadeTransition(javafx.util.Duration.millis(200), error);
            fade.setToValue(1);
            javafx.animation.TranslateTransition move =
                    new javafx.animation.TranslateTransition(javafx.util.Duration.millis(200), error);
            move.setToY(0);
            new javafx.animation.ParallelTransition(fade, move).play();
        }
    }

    /** 清除字段错误态 */
    static void clearError(javafx.scene.control.TextInputControl input, Label error) {
        input.getStyleClass().remove("invalid");
        error.setVisible(false);
        error.setManaged(false);
    }
}