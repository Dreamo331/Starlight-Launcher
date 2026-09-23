package com.example.starlight.pluginapi.provider;

import com.example.starlight.MinecraftLanListenerV2.MinecraftLanListenerV2;
import com.example.starlight.pluginapi.ApiProvider;
import com.example.starlight.pluginapi.ApiRequest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 功能类（包装）：{@code net/lan/scan} —— 扫描局域网内的 Minecraft 房间。
 *
 * <p>包装既有 {@link MinecraftLanListenerV2}，不改动原有逻辑。监听期间发现的新房间
 * （已按 IP+端口去重）通过回调收集，扫描结束后停止监听并返回结果列表。
 * 回调在监听线程内触发，故使用线程安全的 {@link CopyOnWriteArrayList} 收集。
 *
 * <p>Query 参数：{@code duration_seconds}（可选，默认 3，超过 30 按 30 计）。
 *
 * <p>响应：{@code {"count":N, "duration_seconds":..., "games":[{host, port, motd}...]}}。
 */
public final class LanScanApiProvider implements ApiProvider {

    /** 扫描时长上限（秒）。 */
    private static final int MAX_DURATION_SECONDS = 30;

    @Override
    public String apiId() {
        return "net/lan/scan";
    }

    @Override
    public String apiVersion() {
        return "1.0.0";
    }

    @Override
    public byte[] handle(ApiRequest request) throws Exception {
        int duration = ProviderSupport.intParam(request, "duration_seconds", 3);
        if (duration < 1) {
            duration = 1;
        }
        if (duration > MAX_DURATION_SECONDS) {
            duration = MAX_DURATION_SECONDS;
        }

        List<Map<String, Object>> games = new CopyOnWriteArrayList<>();
        MinecraftLanListenerV2 listener = new MinecraftLanListenerV2();
        listener.setCallback((host, port, motd) -> games.add(ProviderSupport.map(
                "host", host,
                "port", port,
                "motd", motd)));

        listener.start();
        try {
            Thread.sleep(duration * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            listener.stop();
        }

        return ProviderSupport.json(ProviderSupport.map(
                "count", games.size(),
                "duration_seconds", duration,
                "games", games));
    }
}
