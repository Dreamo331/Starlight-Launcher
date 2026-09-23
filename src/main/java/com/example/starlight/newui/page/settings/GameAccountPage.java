package com.example.starlight.newui.page.settings;

import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.PageKit;

import javafx.scene.Node;
import javafx.scene.layout.VBox;

import com.example.starlight.auth.AccountManager;
import com.example.starlight.auth.AccountManager.Account;
import com.example.starlight.newui.SkinSettingsPanel;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import java.util.List;

/** 
设置 → 游戏账户档案页（账号列表 / 登录入口 / 皮肤）
（从 LauncherView 抽离，逻辑与样式均未改动） */
public final class GameAccountPage {

    private final LauncherContext ctx;

    public GameAccountPage(LauncherContext ctx) {
        this.ctx = ctx;
    }

    public Node build() {
        VBox root = PageKit.settingsPage(ctx, "游戏账户档案");
        Account current = AccountManager.getCurrentAccount();
        List<Account> accounts = AccountManager.listAccounts();
        if (accounts.isEmpty()) {
            root.getChildren().add(new Label("暂无账号，请添加") {{
                setStyle("-fx-text-fill: -sl-text-faint; -fx-padding: 10 0;");
            }});
        } else {
            for (Account a : accounts) {
                HBox btnBox = new HBox(6);
                boolean isCurrent = current != null && current.id.equals(a.id);
                if (isCurrent) {
                    // 当前账号：绿色徽标标识使用中，无需切换按钮，切换结果一眼可见
                    Label currentBadge = AppIcons.label("bullet", "当前使用中");
                    currentBadge.getStyleClass().add("account-current-badge");
                    btnBox.getChildren().add(currentBadge);
                } else {
                    Button switchBtn = AppIcons.button("refresh", "切换");
                    switchBtn.getStyleClass().add("btn-primary");
                    switchBtn.setOnAction(e -> {
                        boolean ok = AccountManager.setCurrentAccount(a.id);
                        ctx.loadConfig();
                        if (ok) {
                            ctx.refreshAccountPages();
                            ctx.toast("已切换到: " + a.displayName());
                        } else {
                            ctx.toast("切换失败: " + a.displayName());
                        }
                    });
                    btnBox.getChildren().add(switchBtn);
                }
                Button removeBtn = AppIcons.button("trash", "删除");
                removeBtn.setStyle("-fx-background-color: #fee2e2; -fx-text-fill: #dc2626;");
                removeBtn.setOnAction(e -> {
                    boolean ok = AccountManager.removeAccount(a.id);
                    if (ok) {
                        ctx.refreshAccountPages();
                        ctx.toast("已删除 " + a.displayName());
                    } else {
                        ctx.toast("删除失败: " + a.displayName());
                    }
                });
                btnBox.getChildren().add(removeBtn);
                // 离线/第三方账号支持自定义皮肤：集成式皮肤设置面板（替代独立 SkinSettingsController 窗口）
                if (a.type != AccountManager.AccountType.MICROSOFT) {
                    Button skinBtn = AppIcons.button("palette", "皮肤");
                    skinBtn.getStyleClass().add("btn-primary");
                    skinBtn.setOnAction(e -> {
                        SkinSettingsPanel panel = new SkinSettingsPanel(a, () -> {
                            ctx.refreshAccountPages();
                            ctx.toast("皮肤已应用");
                        }, ctx.ui()::closeModal);
                        ctx.ui().modal("离线皮肤设置 - " + a.name, panel, 660, 440);
                    });
                    btnBox.getChildren().add(1, skinBtn);
                }
                String desc = a.getTypeLabel();
                if (a.type == AccountManager.AccountType.THIRD_PARTY
                        && a.authServer != null && !a.authServer.isBlank()) {
                    desc += " · " + a.authServer;
                }
                root.getChildren().add(PageKit.settingsCard(a.name, desc, btnBox));
            }
        }

        HBox addBtns = new HBox(10);
        addBtns.setPadding(new Insets(16, 0, 0, 0));
        Button microsoftBtn = AppIcons.button("user", "微软登录");
        microsoftBtn.getStyleClass().add("btn-primary");
        microsoftBtn.setOnAction(e -> ctx.microsoftLogin());
        Button offlineBtn = AppIcons.button("user", "离线登录");
        offlineBtn.getStyleClass().add("btn-primary");
        offlineBtn.setOnAction(e -> ctx.offlineLogin());
        Button thirdPartyBtn = AppIcons.button("user", "第三方登录");
        thirdPartyBtn.getStyleClass().add("btn-primary");
        thirdPartyBtn.setOnAction(e -> ctx.thirdPartyLogin());
        addBtns.getChildren().addAll(microsoftBtn, offlineBtn, thirdPartyBtn);
        root.getChildren().add(addBtns);
        return root;
    }
}
