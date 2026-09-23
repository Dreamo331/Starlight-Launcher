package com.example.starlight.newui.page.settings;

import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.PageKit;

import javafx.scene.Node;
import javafx.scene.layout.VBox;

import com.example.starlight.auth.AccountManager;
import com.example.starlight.auth.AccountManager.Account;

/** 设置 → Account 页（从 LauncherView 抽离，逻辑与布局样式均未改动） */
public final class AccountPage {

    private final LauncherContext ctx;

    public AccountPage(LauncherContext ctx) {
        this.ctx = ctx;
    }

    public Node build() {
        VBox root = PageKit.settingsPage(ctx, "Account");
        Account account = AccountManager.getCurrentAccount();
        boolean loggedIn = account != null;
        String status = loggedIn ? (account.name + " (" + account.getTypeLabel() + ")") : "当前未登录";
        root.getChildren().add(PageKit.settingsCard("登录状态", status,
                PageKit.primaryButton(ctx, loggedIn ? "切换账号" : "登录", "sidebarGameAccount")));
        return root;
    }
}
