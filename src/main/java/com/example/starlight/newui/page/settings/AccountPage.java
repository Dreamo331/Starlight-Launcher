package com.example.starlight.newui.page.settings;


import com.example.starlight.util.I18N;
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
        String status = loggedIn ? (account.name + " (" + account.getTypeLabel() + ")") : I18N.get("account.t0001");
        root.getChildren().add(PageKit.settingsCard(I18N.get("account.t0002"), status,
                PageKit.primaryButton(ctx, loggedIn ? I18N.get("account.t0003") : I18N.get("account.t0004"), "sidebarAccount")));
        return root;
    }
}
