package com.example.starlight.pluginapi.provider;

import com.example.starlight.pluginapi.ApiProvider;
import com.example.starlight.pluginapi.ApiRequest;
import com.example.starlight.service.GameLauncherService;

/**
 * 功能类（包装）：{@code game/stop} —— 强制停止当前运行中的游戏。
 *
 * <p>包装既有 {@link GameLauncherService#stopGame()}（void），不改动原有逻辑。
 *
 * <p>无 Query 参数。
 */
public final class GameStopApiProvider implements ApiProvider {

    @Override
    public String apiId() {
        return "game/stop";
    }

    @Override
    public String apiVersion() {
        return "1.0.0";
    }

    @Override
    public byte[] handle(ApiRequest request) throws Exception {
        GameLauncherService.stopGame();
        return ProviderSupport.json(ProviderSupport.map("success", true));
    }
}
