package com.example.starlight.newui.page.settings;

import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.PageKit;
import com.example.starlight.util.I18N;

import javafx.scene.Node;
import javafx.scene.layout.VBox;

import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;

/**
 * 设置 → 语言页。
 *
 * <p>已并入「主界面」页，不再单独出现在侧边栏；保留本类是为了旧深链
 * {@code sidebarLanguage} 仍可达（NavRouter 会重定向，但直接调用也不能 404）。
 *
 * <p>与 MainUiPage 共用 {@link LanguageChoices}：下拉项存语言代码，显示名单独渲染。
 * 旧实现在这里用中文显示名反推语言代码，切语言后必然失配。
 */
public final class LanguagePage {

    private final LauncherContext ctx;

    public LanguagePage(LauncherContext ctx) {
        this.ctx = ctx;
    }

    public Node build() {
        VBox root = PageKit.settingsPage(ctx, I18N.get("lang.pageTitle"));
        ComboBox<String> combo = new ComboBox<>();
        combo.getItems().addAll(LanguageChoices.CODES);
        combo.setValue(LanguageChoices.normalize(ctx.config().getOrDefault("Language", "zh_CN")));
        combo.setCellFactory(cb -> languageCell());
        combo.setButtonCell(languageCell());
        combo.getStyleClass().add("select-field");
        combo.setOnAction(e -> LanguageChoices.apply(ctx, combo.getValue()));
        root.getChildren().add(PageKit.settingsCard(
                I18N.get("lang.displayLanguage"),
                I18N.get("lang.displayLanguageDesc"), combo));
        return root;
    }

    /** 语言下拉的显示单元：值仍是语言代码，只把代码渲染成名称 */
    private static ListCell<String> languageCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : LanguageChoices.displayName(item));
            }
        };
    }
}
