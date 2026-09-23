package com.example.starlight.pluginapi.provider;

import com.example.starlight.pluginapi.ApiProvider;
import com.example.starlight.pluginapi.ApiRequest;
import com.example.starlight.slan.SLanManager;

/**
 * 功能类（包装）：{@code net/slan/join} —— 通过邀请码加入 SL-MinecraftLAN 联机房间。
 *
 * <p>包装既有 {@link SLanManager}，不改动原有逻辑；{@link SLanManager#joinRoom}
 * 为<strong>同步阻塞</strong>调用，失败时抛出 {@code SLanManager.SLanException}，本类直接上抛。
 *
 * <p>Query 参数：{@code invite_code}（必填）、{@code password}（可选）、
 * {@code base_dir}（可选，默认 "."）。
 *
 * <p>响应：{@code {"success":bool, "connected":..., "host_address":..., "port":...,
 * "error":..., "raw_output":...}}（{@code JoinResult} 含 getter，手动构造响应 Map）。
 */
public final class SlanJoinApiProvider implements ApiProvider {

    @Override
    public String apiId() {
        return "net/slan/join";
    }

    @Override
    public String apiVersion() {
        return "1.0.0";
    }

    @Override
    public byte[] handle(ApiRequest request) throws Exception {
        String inviteCode = ProviderSupport.required(request, "invite_code");
        String password = blankToNull(request.param("password"));
        String baseDir = request.param("base_dir");
        if (baseDir == null || baseDir.isBlank()) {
            baseDir = ".";
        }

        SLanManager manager = SLanManager.fromBaseDirectory(baseDir);
        SLanManager.JoinResult result = manager.joinRoom(inviteCode, password);

        return ProviderSupport.json(ProviderSupport.map(
                "success", result.isSuccess(),
                "connected", result.isConnected(),
                "host_address", result.getHostAddress(),
                "port", result.getPort(),
                "error", result.getError(),
                "raw_output", result.getRawOutput()));
    }

    /** 空白字符串归一化为 null（无密码房间语义）。 */
    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
