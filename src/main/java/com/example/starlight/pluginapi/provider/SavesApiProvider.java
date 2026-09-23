package com.example.starlight.pluginapi.provider;

import com.example.starlight.pluginapi.ApiProvider;
import com.example.starlight.pluginapi.ApiRequest;
import com.example.starlight.service.SaveService;

/**
 * 功能类（包装）：{@code world/saves/list} —— 列出游戏存档。
 *
 * <p>包装既有 {@link SaveService}，不改动原有逻辑；只向外提供 JSON 数据，
 * UI 展示完全由插件负责。
 *
 * <p>Query 参数：{@code game_dir}（可选，默认 .minecraft）、{@code version}（可选，按版本过滤）。
 */
public final class SavesApiProvider implements ApiProvider {

    @Override
    public String apiId() {
        return "world/saves/list";
    }

    @Override
    public String apiVersion() {
        return "1.0.0";
    }

    @Override
    public byte[] handle(ApiRequest request) throws Exception {
        String gameDir = ProviderSupport.gameDir(request);
        // version 可选；SaveService 内部拼接 versions/<version> 路径，缺省传空串避免 "null"
        String version = request.param("version");
        if (version == null) {
            version = "";
        }
        var saves = SaveService.listSaves(gameDir, version);
        return ProviderSupport.json(ProviderSupport.map(
                "count", saves.size(),
                "game_dir", gameDir,
                "saves", saves));
    }
}
