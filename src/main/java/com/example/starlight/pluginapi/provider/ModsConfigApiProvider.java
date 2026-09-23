package com.example.starlight.pluginapi.provider;

import com.example.starlight.pluginapi.ApiProvider;
import com.example.starlight.pluginapi.ApiRequest;
import com.example.starlight.model.ConfigFileInfo;
import com.example.starlight.service.ModService;

import java.util.List;

/**
 * 功能类（包装）：{@code mods/config} —— 列出模组配置文件。
 *
 * <p>包装既有 {@link ModService}，不改动原有逻辑；只向外提供 JSON 数据，
 * UI 展示完全由插件负责。
 *
 * <p>Query 参数：{@code game_dir}（可选，默认 .minecraft）。
 */
public final class ModsConfigApiProvider implements ApiProvider {

    @Override
    public String apiId() {
        return "mods/config";
    }

    @Override
    public String apiVersion() {
        return "1.0.0";
    }

    @Override
    public byte[] handle(ApiRequest request) throws Exception {
        String gameDir = ProviderSupport.gameDir(request);
        List<ConfigFileInfo> files = ModService.listConfigFiles(gameDir);
        return ProviderSupport.json(ProviderSupport.map(
                "count", files.size(),
                "config_files", files));
    }
}
