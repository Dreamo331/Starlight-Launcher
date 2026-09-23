package com.example.starlight.pluginapi.provider;

import com.example.starlight.pluginapi.ApiProvider;
import com.example.starlight.pluginapi.ApiRequest;
import com.example.starlight.slan.SLanManager;

/**
 * 功能类（包装）：{@code net/slan/host} —— 创建 SL-MinecraftLAN 联机房间。
 *
 * <p>包装既有 {@link SLanManager}，不改动原有逻辑；{@link SLanManager#hostRoom}
 * 为<strong>同步阻塞</strong>调用，失败时抛出 {@code SLanManager.SLanException}，本类直接上抛。
 *
 * <p>Query 参数：{@code base_dir}（可选，默认 "."，即 Starlight-Launcher 基础目录）、
 * {@code password}（可选，可为 null，表示无密码房间）。
 *
 * <p>响应：{@code {"success":bool, "invite_code":..., "port":..., "room_name":..., "raw_output":...}}
 * （{@code HostResult} 含 getter，手动构造响应 Map）。
 */
public final class SlanHostApiProvider implements ApiProvider {

    @Override
    public String apiId() {
        return "net/slan/host";
    }

    @Override
    public String apiVersion() {
        return "1.0.0";
    }

    @Override
    public byte[] handle(ApiRequest request) throws Exception {
        String baseDir = request.param("base_dir");
        if (baseDir == null || baseDir.isBlank()) {
            baseDir = ".";
        }
        String password = blankToNull(request.param("password"));

        SLanManager manager = SLanManager.fromBaseDirectory(baseDir);
        SLanManager.HostResult result = manager.hostRoom(password);

        return ProviderSupport.json(ProviderSupport.map(
                "success", result.isSuccess(),
                "invite_code", result.getInviteCode(),
                "port", result.getPort(),
                "room_name", result.getRoomName(),
                "raw_output", result.getRawOutput()));
    }

    /** 空白字符串归一化为 null（无密码房间语义）。 */
    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
