/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.service;

import com.example.starlight.auth.AccountManager;
import com.example.starlight.auth.AccountManager.Account;
import com.example.starlight.loginmicrosoft.MinecraftAuthLauncherDeviceCode;
import com.example.starlight.model.CallbackInterfaces.ResultCallback;
import com.example.starlight.model.MicrosoftAuthResult;

import java.util.concurrent.CompletableFuture;

/**
 * 微软认证服务，登录与令牌刷新
 */
public class MicrosoftAuthService {

    /** 首次微软登录（设备码流，异步） */
    public static void loginAsync(ResultCallback<MicrosoftAuthResult> callback) {
        CompletableFuture.runAsync(() -> {
            try {
                MinecraftAuthLauncherDeviceCode auth = new MinecraftAuthLauncherDeviceCode();
                auth.startAuth();
                MicrosoftAuthResult result = readMicrosoftAuthResult();
                if (result != null) {
                    callback.onSuccess(result);
                } else {
                    callback.onError("登录似乎已完成，但无法读取保存的登录信息");
                }
            } catch (Exception e) {
                callback.onError("微软登录失败: " + e.getMessage());
            }
        });
    }

    /** 刷新微软令牌（静默刷新，异步） */
    public static void refreshAsync(ResultCallback<MicrosoftAuthResult> callback) {
        CompletableFuture.runAsync(() -> {
            try {
                Account acc = AccountManager.getCurrentAccount();
                if (acc != null && acc.refreshToken != null && !acc.refreshToken.isEmpty()) {
                    Account updated = AccountManager.refreshMicrosoftSync(acc.refreshToken);
                    callback.onSuccess(new MicrosoftAuthResult(
                            updated.accessToken, updated.refreshToken, updated.id, updated.name));
                } else {
                    MinecraftAuthLauncherDeviceCode auth = new MinecraftAuthLauncherDeviceCode();
                    auth.refreshAndSave();
                    MicrosoftAuthResult result = readMicrosoftAuthResult();
                    callback.onSuccess(result != null ? result
                            : new MicrosoftAuthResult("", "", "", ""));
                }
            } catch (Exception e) {
                callback.onError("令牌刷新失败: " + e.getMessage());
            }
        });
    }

    /** 检查是否有可用账号（委托给 AccountManager） */
    public static boolean isLoggedIn() {
        return AccountManager.getCurrentAccount() != null;
    }

    /** 获取当前登录玩家名 */
    public static String getLoggedInUserName() {
        Account acc = AccountManager.getCurrentAccount();
        return acc != null ? acc.name : "";
    }

    /** 获取当前账号 UUID */
    public static String getLoggedInUUID() {
        Account acc = AccountManager.getCurrentAccount();
        return acc != null ? acc.id : "";
    }

    /** 获取当前账号类型 */
    public static String getCurrentUserType() {
        Account acc = AccountManager.getCurrentAccount();
        return acc != null ? acc.getUserType() : "mojang";
    }

    /** 获取当前账号 AccessToken */
    public static String getCurrentAccessToken() {
        Account acc = AccountManager.getCurrentAccount();
        return acc != null ? acc.accessToken : "";
    }

    // ================================================================
    //  内部
    // ================================================================

    private static MicrosoftAuthResult readMicrosoftAuthResult() {
        java.nio.file.Path loginJson = java.nio.file.Paths.get(
                System.getProperty("user.home"), ".starlight-launcher", "login.json");
        if (!java.nio.file.Files.exists(loginJson)) return null;
        try {
            String content = java.nio.file.Files.readString(loginJson, java.nio.charset.StandardCharsets.UTF_8);
            com.google.gson.JsonObject json = new com.google.gson.Gson().fromJson(content, com.google.gson.JsonObject.class);
            return new MicrosoftAuthResult(
                    getJsonStr(json, "accessToken"),
                    getJsonStr(json, "refreshToken"),
                    getJsonStr(json, "uuid"),
                    getJsonStr(json, "username"));
        } catch (Exception e) {
            return null;
        }
    }

    private static String getJsonStr(com.google.gson.JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
    }
}
