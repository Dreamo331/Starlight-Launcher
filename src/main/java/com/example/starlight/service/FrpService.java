/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.service;

import com.example.starlight.config.Endpoints;
import com.example.starlight.model.ActionResult;
import com.example.starlight.frp.FrpClient;
import com.example.starlight.model.FrpStatus;
import com.example.starlight.model.CallbackInterfaces.ResultCallback;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * FRP 隧道服务
 */
public class FrpService {

    private static FrpClient frpClientInstance = null;

    /** 当前 FRP 隧道公网地址（主页联机卡片显示用） */
    private static String currentPublicAddress = null;

    /** 默认服务器 URL 前缀 */
    public static final String DEFAULT_SERVER_PREFIX = Endpoints.frpControlHost();

    /** 获取有效的服务器 URL */
    public static String getEffectiveServerUrl() {
        Map<String, String> cfg = com.example.starlight.config.StarlightConfig.readConfig();
        String url = cfg.getOrDefault("ServerUrl", "").trim();
        return url.isEmpty() ? Endpoints.logUploadUrl() : url;
    }

    /** 设置自定义服务器 URL */
    public static ActionResult setServerUrl(String url) {
        Map<String, String> cfg = com.example.starlight.config.StarlightConfig.readConfig();
        cfg.put("ServerUrl", url);
        com.example.starlight.config.StarlightConfig.saveConfig(cfg);
        return ActionResult.ok("服务器 URL 已更新");
    }

    /** 启动 FRP 隧道（异步） */
    public static void startFrpAsync(String localAddress, ResultCallback<FrpStatus> callback) {
        if (frpClientInstance != null) {
            callback.onError("FRP 隧道已在运行");
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                FrpClient client = new FrpClient(localAddress);
                frpClientInstance = client;
                final boolean[] connected = {false};
                client.setCallback(new FrpClient.ConnectionCallback() {
                    @Override public void onStateChange(FrpClient.State state) {}
                    @Override public void onError(String error) {
                        if (!connected[0]) {
                            frpClientInstance = null;
                            callback.onError("FRP 连接失败: " + error);
                        }
                    }
                    @Override public void onConnected(int tunnelPort) {
                        connected[0] = true;
                        currentPublicAddress = DEFAULT_SERVER_PREFIX + ":" + tunnelPort;
                        callback.onSuccess(new FrpStatus("已连接", tunnelPort, currentPublicAddress));
                    }
                    @Override public void onDisconnected() {
                        currentPublicAddress = null;
                        frpClientInstance = null;
                        if (connected[0]) callback.onError("FRP 隧道已断开");
                    }
                });
                client.connect();
            } catch (Exception e) {
                frpClientInstance = null;
                callback.onError("FRP 启动失败: " + e.getMessage());
            }
        });
    }

    /** 断开 FRP 隧道 */
    public static void stopFrp() {
        if (frpClientInstance != null) {
            frpClientInstance.disconnect();
            frpClientInstance = null;
        }
        currentPublicAddress = null;
    }

    /** 获取当前 FRP 隧道公网地址，未运行返回 null */
    public static String getCurrentPublicAddress() {
        return currentPublicAddress;
    }

    /** 检查 FRP 是否正在运行 */
    public static boolean isFrpRunning() {
        return frpClientInstance != null;
    }
}
