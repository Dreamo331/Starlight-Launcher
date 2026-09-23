package com.example.starlight.pluginapi.provider;

import com.example.starlight.pluginapi.ApiProvider;
import com.example.starlight.pluginapi.ApiRequest;
import com.example.starlight.model.PackInfo;
import com.example.starlight.service.ModService;

import java.util.List;

/**
 * 功能类（包装）：{@code mods/shaders} —— 列出光影包。
 *
 * <p>包装既有 {@link ModService}，不改动原有逻辑；只向外提供 JSON 数据，
 * UI 展示完全由插件负责。
 *
 * <p>Query 参数：{@code game_dir}（可选，默认 .minecraft）。
 */
public final class ModsShaderPacksApiProvider implements ApiProvider {

    @Override
    public String apiId() {
        return "mods/shaders";
    }

    @Override
    public String apiVersion() {
        return "1.0.0";
    }

    @Override
    public byte[] handle(ApiRequest request) throws Exception {
        String gameDir = ProviderSupport.gameDir(request);
        List<PackInfo> packs = ModService.listShaderPacks(gameDir);
        return ProviderSupport.json(ProviderSupport.map(
                "count", packs.size(),
                "shader_packs", packs));
    }
}
