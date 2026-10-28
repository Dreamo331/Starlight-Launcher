package com.example.starlight.newui.page.settings.account;


import com.example.starlight.util.I18N;
import com.example.starlight.newui.ui.AppIcons;

import javafx.animation.ScaleTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.function.Consumer;

/**
 * 登录方式面板：微软 / 第三方 / 离线三张卡片。
 *
 * <p>对齐 {@code references/html/2.0.html} 的方法列表：图标块悬停放大 1.06（250ms），
 * 整卡可点，点哪个就把对应面板切进来（由页面传入的回调决定）。
 */
public final class LoginMethodsPane {

    private final VBox root = new VBox();

    /**
     * @param onBack 返回列表
     * @param onPick 选中登录方式（"microsoft" / "third" / "offline"）
     */
    public LoginMethodsPane(Runnable onBack, Consumer<String> onPick) {
        root.getStyleClass().add("account-methods-pane");
        root.getChildren().add(AccountPanesUi.clickableHeader(I18N.get("account.t0019"), onBack));

        VBox list = new VBox();
        list.getStyleClass().add("method-list");
        list.getChildren().addAll(
                methodCard("microsoft", "microsoft", "ms", I18N.get("account.t0077"), I18N.get("account.t0078"), onPick),
                methodCard("third", "globe", "third", I18N.get("account.t0079"), I18N.get("account.t0080"), onPick),
                methodCard("offline", "user", "offline", I18N.get("account.t0081"), I18N.get("account.t0082"), onPick));
        root.getChildren().add(list);
    }

    public Node node() {
        return root;
    }

    private HBox methodCard(String key, String icon, String typeClass, String title, String desc,
                            Consumer<String> onPick) {
        // 图标块用 Label：图标颜色由 AppIcons 绑定到文字色，于是 .method-icon 的配色直接管到图标
        Label tile = new Label();
        tile.getStyleClass().addAll("method-icon", typeClass);
        AppIcons.apply(tile, icon, 22, null);

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("method-title");
        Label descLabel = new Label(desc);
        descLabel.getStyleClass().add("method-desc");
        descLabel.setWrapText(true);
        VBox info = new VBox(2, titleLabel, descLabel);
        info.setMinWidth(0);
        info.getStyleClass().add("method-info");
        HBox.setHgrow(info, Priority.ALWAYS);

        Label chevron = new Label();
        AppIcons.apply(chevron, "arrow-right", 16, null);
        chevron.getStyleClass().add("method-chevron");

        HBox card = new HBox(14, tile, info, chevron);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("method-card");
        card.setCursor(javafx.scene.Cursor.HAND);

        ScaleTransition zoom = new ScaleTransition(Duration.millis(250), tile);
        zoom.setToX(1.06);
        zoom.setToY(1.06);
        ScaleTransition shrink = new ScaleTransition(Duration.millis(250), tile);
        shrink.setToX(1);
        shrink.setToY(1);
        card.setOnMouseEntered(e -> zoom.playFromStart());
        card.setOnMouseExited(e -> shrink.playFromStart());

        card.setOnMouseClicked(e -> onPick.accept(key));
        return card;
    }
}