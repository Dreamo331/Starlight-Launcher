package com.example.starlight.pluginapi.provider;

import com.example.starlight.model.ActionResult;
import com.example.starlight.pluginapi.ApiProvider;
import com.example.starlight.pluginapi.ApiRequest;
import com.example.starlight.service.FileService;

/**
 * 功能类（包装）：{@code game/options/update} —— 修改游戏 options.txt 中的单个配置项。
 *
 * <p>包装既有 {@link FileService#updateOptionsTxt(String, String, String)}，
 * 不改动原有逻辑；返回 {@link ActionResult} 的 success/message 字段。
 *
 * <p>Query 参数：{@code game_dir}（可选，默认 .minecraft）、{@code key}（必填）、
 * {@code value}（必填）。
 */
public final class GameOptionsUpdateApiProvider implements ApiProvider {

    @Override
    public String apiId() {
        return "game/options/update";
    }

    @Override
    public String apiVersion() {
        return "1.0.0";
    }

    @Override
    public byte[] handle(ApiRequest request) throws Exception {
        String gameDir = ProviderSupport.gameDir(request);
        String key = ProviderSupport.required(request, "key");
        String value = ProviderSupport.required(request, "value");

        ActionResult result = FileService.updateOptionsTxt(gameDir, key, value);
        return ProviderSupport.json(ProviderSupport.map(
                "success", result.success,
                "message", result.message));
    }
}
