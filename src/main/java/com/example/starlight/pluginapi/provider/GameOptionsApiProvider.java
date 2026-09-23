package com.example.starlight.pluginapi.provider;

import com.example.starlight.pluginapi.ApiProvider;
import com.example.starlight.pluginapi.ApiRequest;
import com.example.starlight.service.FileService;

import java.util.Map;

/**
 * 功能类（包装）：{@code game/options} —— 读取游戏 options.txt 设置。
 *
 * <p>包装既有 {@link FileService}，不改动原有逻辑；只向外提供 JSON 数据，
 * UI 展示完全由插件负责。
 *
 * <p>Query 参数：{@code game_dir}（可选，默认 .minecraft）。
 */
public final class GameOptionsApiProvider implements ApiProvider {

    @Override
    public String apiId() {
        return "game/options";
    }

    @Override
    public String apiVersion() {
        return "1.0.0";
    }

    @Override
    public byte[] handle(ApiRequest request) throws Exception {
        String gameDir = ProviderSupport.gameDir(request);
        Map<String, String> options = FileService.readOptionsTxt(gameDir);
        return ProviderSupport.json(ProviderSupport.map(
                "count", options.size(),
                "game_dir", gameDir,
                "options", options));
    }
}
