package com.example.starlight.newui.page.settings.account;


import com.example.starlight.util.I18N;
import com.example.starlight.auth.AccountManager;
import com.example.starlight.auth.AccountManager.Account;
import com.example.starlight.auth.offline.Skin;
import com.example.starlight.newui.ui.AccountAvatars;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 账户详情面板：头像 + 名称/类型 + 明细表 + 「切换为此账户」。
 *
 * <p>对齐 {@code references/html/2.0.html} 的详情页。明细里的「上次使用」取自账号的
 * {@code lastUsed}（本项目账号数据没有「添加时间」字段），皮肤模型取自离线皮肤配置。
 */
public final class AccountDetailPane {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AccountPageHost host;

    private final VBox root = new VBox();
    private final Label avatarLetter = new Label();
    private final StackPane avatarBox = new StackPane(avatarLetter);
    private final Label nameLabel = new Label("—");
    private final Label typeLabel = new Label("—");
    private final Label idValue = new Label("—");
    private final Label methodValue = new Label("—");
    private final Label skinValue = new Label("—");
    private final Label lastUsedValue = new Label("—");
    private final Label statusValue = new Label("—");
    private final Button switchBtn = new Button(I18N.get("account.t0005"));

    private Account account;

    public AccountDetailPane(AccountPageHost host, Runnable onBack) {
        this.host = host;
        root.getStyleClass().add("account-detail-pane");
        root.getChildren().add(AccountPanesUi.header(I18N.get("account.t0006"), onBack));

        avatarLetter.getStyleClass().addAll("account-avatar", "account-avatar-lg");
        avatarBox.setMinSize(AccountPanesUi.AVATAR_SIZE_LARGE, AccountPanesUi.AVATAR_SIZE_LARGE);
        avatarBox.setPrefSize(AccountPanesUi.AVATAR_SIZE_LARGE, AccountPanesUi.AVATAR_SIZE_LARGE);
        avatarBox.setMaxSize(AccountPanesUi.AVATAR_SIZE_LARGE, AccountPanesUi.AVATAR_SIZE_LARGE);
        nameLabel.getStyleClass().add("detail-name");
        typeLabel.getStyleClass().add("detail-type");
        VBox heroInfo = new VBox(4, nameLabel, typeLabel);
        heroInfo.setMinWidth(0);
        heroInfo.setAlignment(Pos.CENTER_LEFT);
        HBox hero = new HBox(16, avatarBox, heroInfo);
        hero.setAlignment(Pos.CENTER_LEFT);
        hero.getStyleClass().add("detail-hero");

        VBox list = new VBox();
        list.getStyleClass().add("detail-list");
        idValue.getStyleClass().add("mono");
        list.getChildren().addAll(
                detailRow(I18N.get("account.t0062"), idValue),
                detailRow(I18N.get("account.t0063"), methodValue),
                detailRow(I18N.get("account.t0064"), skinValue),
                detailRow(I18N.get("account.t0065"), lastUsedValue),
                detailRow(I18N.get("account.t0066"), statusValue));

        switchBtn.getStyleClass().add("primary-btn");
        switchBtn.setMaxWidth(Double.MAX_VALUE);
        switchBtn.setOnAction(e -> switchToThis());

        VBox body = new VBox(18, hero, list, switchBtn);
        body.getStyleClass().add("detail-body");
        root.getChildren().add(body);
    }

    public Node node() {
        return root;
    }

    /** 用指定账号填充详情；isCurrent 决定状态行与按钮文案 */
    public void fill(Account account, boolean isCurrent) {
        this.account = account;
        if (account == null) return;

        avatarLetter.setText(AccountPanesUi.initialOf(account.name));
        avatarLetter.getStyleClass().removeIf(c -> c.startsWith("account-avatar-")
                && !c.equals("account-avatar-lg"));
        avatarLetter.getStyleClass().add(AccountPanesUi.avatarClass(account.type));
        // 真实皮肤头像（异步；加载中/失败时保留上面的字母）
        AccountAvatars.load(avatarBox, account, AccountPanesUi.AVATAR_SIZE_LARGE);

        nameLabel.setText(account.name == null ? "—" : account.name);
        typeLabel.setText(describe(account));
        idValue.setText(account.id == null || account.id.isBlank() ? "—" : account.id);
        methodValue.setText(methodLabel(account));
        skinValue.setText(skinModelText(account));
        lastUsedValue.setText(formatLastUsed(account.lastUsed));
        statusValue.setText(isCurrent ? I18N.get("account.t0067") : I18N.get("account.t0068"));
        statusValue.getStyleClass().removeAll("status-on", "status-off");
        statusValue.getStyleClass().add(isCurrent ? "status-on" : "status-off");

        switchBtn.setDisable(isCurrent);
        switchBtn.setText(isCurrent ? I18N.get("account.t0069") : I18N.get("account.t0070"));
    }

    private void switchToThis() {
        if (account == null || account.id.equals(AccountManager.getCurrentAccountId())) return;
        boolean ok = AccountManager.setCurrentAccount(account.id);
        if (ok) {
            fill(account, true);
            host.accountChanged(I18N.get("account.p0089", account.name));
        } else {
            host.toast(I18N.get("account.p0090", account.name));
        }
    }

    // 明细行 //

    private HBox detailRow(String key, Label value) {
        Label keyLabel = new Label(key);
        keyLabel.getStyleClass().add("k");
        value.getStyleClass().add("v");
        value.setWrapText(true);
        // 最小宽度：避免在 HBox 里被压缩成一列竖排文字
        value.setMinWidth(120);
        value.setMaxWidth(320);
        value.setAlignment(Pos.CENTER_RIGHT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(16, keyLabel, spacer, value);
        row.setAlignment(Pos.TOP_LEFT);
        row.getStyleClass().add("detail-row");
        return row;
    }

    private String describe(Account account) {
        return switch (account.type) {
            case MICROSOFT -> I18N.get("account.t0007");
            case OFFLINE -> I18N.get("account.t0008");
            case THIRD_PARTY -> account.authServer != null && !account.authServer.isBlank()
                    ? I18N.get("adp.r0001", account.authServer)
                    : I18N.get("account.t0071");
        };
    }

    private String methodLabel(Account account) {
        return switch (account.type) {
            case MICROSOFT -> I18N.get("account.t0009");
            case OFFLINE -> I18N.get("account.t0010");
            case THIRD_PARTY -> I18N.get("account.t0011");
        };
    }

    /** 皮肤模型：离线皮肤配置里的 slim/wide（无自定义皮肤时为默认的经典模型） */
    private String skinModelText(Account account) {
        try {
            Skin skin = account.getSkin();
            return skin != null && skin.getTextureModel() == Skin.TextureModel.SLIM
                    ? I18N.get("account.t0072")
                    : I18N.get("account.t0073");
        } catch (RuntimeException e) {
            return "—";
        }
    }

    private String formatLastUsed(long lastUsed) {
        if (lastUsed <= 0) return "—";
        return Instant.ofEpochMilli(lastUsed).atZone(ZoneId.systemDefault()).format(TIME_FORMAT);
    }
}