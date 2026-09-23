package com.example.starlight.pluginapi.provider;

import com.example.starlight.model.ActionResult;
import com.example.starlight.pluginapi.ApiProvider;
import com.example.starlight.pluginapi.ApiRequest;
import com.example.starlight.service.ModService;

/**
 * 功能类（包装）：{@code mods/toggle} —— 切换指定模组的启用/禁用状态。
 *
 * <p>包装既有 {@link ModService#toggleMod(String)}，不改动原有逻辑；
 * 返回 {@link ActionResult} 的 success/message 字段。
 *
 * <p>Query 参数：{@code mod_path}（必填，模组文件的绝对或相对路径）。
 */
public final class ModsToggleApiProvider implements ApiProvider {

    @Override
    public String apiId() {
        return "mods/toggle";
    }

    @Override
    public String apiVersion() {
        return "1.0.0";
    }

    @Override
    public byte[] handle(ApiRequest request) throws Exception {
        String modPath = ProviderSupport.required(request, "mod_path");

        ActionResult result = ModService.toggleMod(modPath);
        return ProviderSupport.json(ProviderSupport.map(
                "success", result.success,
                "message", result.message));
    }
}
