package com.example.starlight.newui.page.settings.account;


import com.example.starlight.util.I18N;
import com.example.starlight.auth.AccountManager;
import com.example.starlight.auth.AccountManager.Account;
import com.example.starlight.config.Endpoints;
import com.example.starlight.gui.UIGeneralControlClass;
import com.example.starlight.newui.ui.HoldButton;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.net.URI;
import java.util.List;

/**
 * 第三方（外置登录）面板：预设下拉 + API 地址 + 账号 + 密码，行内校验后调真实认证接口。
 *
 * <p>对齐 {@code references/html/2.0.html} 的第三方页：预设只负责填地址（选中即不发请求），
 * 校验规则与原页面一致（地址需 http(s) 且可解析、账号不能与已有账号重名、密码非空），
 * 「前往官网注册」按预设或当前地址推导注册页并用系统浏览器打开。
 *
 * <p>登录本身是真实网络请求（{@code UIGeneralControlClass.loginThirdPartyAsync}），
 * 期间显示卡片内「正在与服务器通信…」与全屏遮罩；结果回来再收起。
 */
public final class ThirdPartyLoginPane {

    /** 长按取消所需时长（对齐 HTML 的 1500ms） */
    private static final long HOLD_MS = 1500;

    private final AccountPageHost host;
    private final VBox root = new VBox();

    /** 预设来自 assets/endpoints.json 的 services.thirdPartyPresets（第三方地址唯一真源） */
    private final List<String[]> presets = Endpoints.thirdPartyPresets();

    private final ComboBox<String> presetBox = new ComboBox<>();
    private final TextField apiField = new TextField();
    private final TextField userField = new TextField();
    private final PasswordField passField = new PasswordField();
    private final Label apiError = new Label();
    private final Label userError = new Label();
    private final Label passError = new Label();
    private final Button loginBtn = new Button(I18N.get("account.t0042"));

    private boolean submitting;

    public ThirdPartyLoginPane(AccountPageHost host, Runnable onBack, Runnable onBackToMethods) {
        this.host = host;
        root.getStyleClass().add("account-third-pane");
        root.getChildren().add(AccountPanesUi.header(I18N.get("account.t0043"), onBack));

        presetBox.getStyleClass().add("select-field");
        presetBox.setMaxWidth(Double.MAX_VALUE);
        presetBox.setPromptText(I18N.get("account.t0044"));
        for (String[] preset : presets) presetBox.getItems().add(preset[0] + " — " + preset[1]);
        presetBox.getItems().add(I18N.get("account.t0045"));
        presetBox.setOnAction(e -> applyPreset());

        apiField.setPromptText("https://example.com/api/yggdrasil");
        apiField.getStyleClass().add("input-field");
        userField.setPromptText(I18N.get("account.t0046"));
        userField.getStyleClass().add("input-field");
        passField.setPromptText(I18N.get("account.t0047"));
        passField.getStyleClass().add("input-field");
        passField.setOnAction(e -> submit());

        // 已出错的字段边打字边复校；非空字段失焦时也校验一次（对齐 HTML 的 input / blur 行为）
        apiField.textProperty().addListener((o, a, b) -> {
            if (apiError.isVisible()) checkApi();
        });
        userField.textProperty().addListener((o, a, b) -> {
            if (userError.isVisible()) checkUser();
        });
        passField.textProperty().addListener((o, a, b) -> {
            if (passError.isVisible()) checkPass();
        });
        apiField.focusedProperty().addListener((o, a, focused) -> {
            if (!focused && !apiField.getText().isBlank()) checkApi();
        });
        userField.focusedProperty().addListener((o, a, focused) -> {
            if (!focused && !userField.getText().isBlank()) checkUser();
        });

        HoldButton cancel = new HoldButton(I18N.get("account.t0048"), HOLD_MS, onBackToMethods);
        cancel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(cancel, Priority.ALWAYS);

        Button register = new Button(I18N.get("account.t0049"));
        register.getStyleClass().add("link-btn");
        register.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        register.setOnAction(e -> openRegister());

        loginBtn.getStyleClass().addAll("primary-btn", "btn-sm");
        loginBtn.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        loginBtn.setOnAction(e -> submit());

        HBox actions = new HBox(8, cancel, register, loginBtn);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.getStyleClass().add("form-actions");

        VBox form = new VBox(14,
                AccountPanesUi.field(I18N.get("account.t0085"), presetBox, new Label()),
                AccountPanesUi.field(I18N.get("account.t0086"), apiField, apiError),
                AccountPanesUi.field(I18N.get("account.t0087"), userField, userError),
                AccountPanesUi.field(I18N.get("account.t0088"), passField, passError),
                actions);
        form.getStyleClass().add("form-body");
        root.getChildren().add(form);
    }

    public Node node() {
        return root;
    }

    // 预设与注册 //

    private void applyPreset() {
        int index = presetBox.getSelectionModel().getSelectedIndex();
        if (index < 0) return;
        if (index >= presets.size()) {
            // 「自定义…」：清空地址让用户自己填，不保留上一个预设的地址
            apiField.clear();
            AccountPanesUi.clearError(apiField, apiError);
            apiField.requestFocus();
            return;
        }
        apiField.setText(presets.get(index)[1]);
        if (apiError.isVisible()) checkApi();
    }

    /** 注册页：优先用预设自带的注册地址，否则按当前 API 地址推导 origin + /auth/register */
    private void openRegister() {
        int index = presetBox.getSelectionModel().getSelectedIndex();
        String url = "";
        if (index >= 0 && index < presets.size()) {
            url = presets.get(index)[2];
        } else {
            String api = apiField.getText() == null ? "" : apiField.getText().trim();
            if (!api.isEmpty()) {
                try {
                    url = URI.create(api).resolve("/auth/register").toString();
                } catch (RuntimeException e) {
                    url = "";
                }
            }
        }
        if (url.isEmpty()) {
            AccountPanesUi.error(apiField, apiError, I18N.get("account.t0050"));
            apiField.requestFocus();
            host.toast(I18N.get("account.t0051"));
            return;
        }
        host.openWeb(url);
    }

    // 校验 //

    private boolean checkApi() {
        String value = apiField.getText() == null ? "" : apiField.getText().trim();
        if (value.isEmpty()) {
            AccountPanesUi.error(apiField, apiError, I18N.get("account.t0052"));
            return false;
        }
        if (!value.matches("(?i)^https?://.+")) {
            AccountPanesUi.error(apiField, apiError, I18N.get("account.t0053"));
            return false;
        }
        try {
            URI.create(value);
        } catch (RuntimeException e) {
            AccountPanesUi.error(apiField, apiError, I18N.get("account.t0054"));
            return false;
        }
        AccountPanesUi.clearError(apiField, apiError);
        return true;
    }

    private boolean checkUser() {
        String value = userField.getText() == null ? "" : userField.getText().trim();
        if (value.isEmpty()) {
            AccountPanesUi.error(userField, userError, I18N.get("account.t0055"));
            return false;
        }
        for (Account account : AccountManager.listAccounts()) {
            if (account.name != null && account.name.equals(value)) {
                AccountPanesUi.error(userField, userError, I18N.get("account.t0056"));
                return false;
            }
        }
        AccountPanesUi.clearError(userField, userError);
        return true;
    }

    private boolean checkPass() {
        if (passField.getText() == null || passField.getText().isEmpty()) {
            AccountPanesUi.error(passField, passError, I18N.get("account.t0057"));
            return false;
        }
        AccountPanesUi.clearError(passField, passError);
        return true;
    }

    // 提交 //

    private void submit() {
        if (submitting) return;
        if (!checkApi()) {
            apiField.requestFocus();
            return;
        }
        if (!checkUser()) {
            userField.requestFocus();
            return;
        }
        if (!checkPass()) {
            passField.requestFocus();
            return;
        }

        submitting = true;
        loginBtn.setDisable(true);
        loginBtn.setText(I18N.get("account.t0058"));
        String server = apiField.getText().trim();
        String user = userField.getText().trim();
        String password = passField.getText();
        host.showBusy(I18N.get("account.t0059"));

        UIGeneralControlClass.loginThirdPartyAsync(server, user, password,
                new UIGeneralControlClass.ResultCallback<Account>() {
                    @Override
                    public void onSuccess(Account account) {
                        Platform.runLater(() -> {
                            resetSubmitting();
                            host.hideBusy();
                            clearForm();
                            host.accountLoggedIn(I18N.get("account.p0098", (account == null ? user : account.name)));
                        });
                    }

                    @Override
                    public void onError(String message) {
                        Platform.runLater(() -> {
                            resetSubmitting();
                            host.hideBusy();
                            AccountPanesUi.error(apiField, apiError, message == null ? I18N.get("account.t0060") : message);
                            host.toast(I18N.get("account.p0099", message));
                        });
                    }
                });
    }

    private void resetSubmitting() {
        submitting = false;
        loginBtn.setDisable(false);
        loginBtn.setText(I18N.get("account.t0061"));
    }

    private void clearForm() {
        presetBox.getSelectionModel().clearSelection();
        presetBox.setValue(null);
        apiField.clear();
        userField.clear();
        passField.clear();
        AccountPanesUi.clearError(apiField, apiError);
        AccountPanesUi.clearError(userField, userError);
        AccountPanesUi.clearError(passField, passError);
    }
}