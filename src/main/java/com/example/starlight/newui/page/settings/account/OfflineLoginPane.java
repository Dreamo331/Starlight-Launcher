package com.example.starlight.newui.page.settings.account;


import com.example.starlight.util.I18N;
import com.example.starlight.auth.AccountManager;
import com.example.starlight.auth.AccountManager.Account;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.VBox;

/**
 * 离线登录面板：填账号名 → 行内校验 → 直接写入账号。
 *
 * <p>对齐 {@code references/html/2.0.html} 的离线页：同一个表单里做校验（非空 / ≤16 字符 /
 * 不能与已有账号重名），错误就地显示在字段下方并给输入框套红环。
 * 离线账号是纯本地写入、无需联网，所以这里不触发「正在处理」遮罩（HTML 那段等待是演示效果）。
 */
public final class OfflineLoginPane {

    /** 账号名长度上限：与 Minecraft 用户名上限一致（HTML 也是 16） */
    private static final int NAME_MAX_LENGTH = 16;

    private final AccountPageHost host;
    private final VBox root = new VBox();
    private final TextField nameField = new TextField();
    private final Label nameError = new Label();

    public OfflineLoginPane(AccountPageHost host, Runnable onBack) {
        this.host = host;
        root.getStyleClass().add("account-offline-pane");
        root.getChildren().add(AccountPanesUi.header(I18N.get("account.t0035"), onBack));

        nameField.setPromptText(I18N.get("account.t0036"));
        nameField.getStyleClass().add("input-field");
        nameField.setTextFormatter(new TextFormatter<>(change ->
                change.getControlNewText().length() <= NAME_MAX_LENGTH ? change : null));
        nameField.setOnAction(e -> submit());
        // 出错后边打字边复校，错误消失即可提交
        nameField.textProperty().addListener((o, a, b) -> {
            if (nameError.isVisible()) validate();
        });

        Button confirm = new Button(I18N.get("account.t0037"));
        confirm.getStyleClass().add("primary-btn");
        confirm.setMaxWidth(Double.MAX_VALUE);
        confirm.setOnAction(e -> submit());

        VBox form = new VBox(14, AccountPanesUi.field(I18N.get("account.t0038"), nameField, nameError), confirm);
        form.getStyleClass().add("form-body");
        root.getChildren().add(form);
    }

    public Node node() {
        return root;
    }

    /** 切进本面板时聚焦输入框（HTML 在切面板后 320ms 聚焦，这里等布局完成再聚焦） */
    public void focusField() {
        javafx.application.Platform.runLater(nameField::requestFocus);
    }

    private void submit() {
        if (!validate()) {
            nameField.requestFocus();
            return;
        }
        String name = nameField.getText().trim();
        Account account = AccountManager.loginOffline(name);
        nameField.clear();
        AccountPanesUi.clearError(nameField, nameError);
        if (account == null) {
            AccountPanesUi.error(nameField, nameError, I18N.get("account.t0039"));
            return;
        }
        host.accountLoggedIn(I18N.get("account.p0097", name));
    }

    /** 校验账号名；通过返回 true */
    private boolean validate() {
        String value = nameField.getText() == null ? "" : nameField.getText().trim();
        if (value.isEmpty()) {
            AccountPanesUi.error(nameField, nameError, I18N.get("account.t0040"));
            return false;
        }
        if (value.length() > NAME_MAX_LENGTH) {
            AccountPanesUi.error(nameField, nameError, I18N.get("olp.r0001", NAME_MAX_LENGTH));
            return false;
        }
        if (exists(value)) {
            AccountPanesUi.error(nameField, nameError, I18N.get("account.t0041"));
            return false;
        }
        AccountPanesUi.clearError(nameField, nameError);
        return true;
    }

    private boolean exists(String name) {
        for (Account account : AccountManager.listAccounts()) {
            if (account.name != null && account.name.equals(name)) return true;
        }
        return false;
    }
}