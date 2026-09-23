package com.example.starlight.pluginapi.provider;

import com.example.starlight.pluginapi.ApiProvider;
import com.example.starlight.pluginapi.ApiRequest;
import com.example.starlight.model.ModInfo;
import com.example.starlight.service.ModService;

import java.util.List;

/**
 * 功能类（包装）：{@code mods/list} —— 列出已安装模组。
 *
 * <p>包装既有 {@link ModService}，不改动原有逻辑；只向外提供 JSON 数据，
 * UI 展示完全由插件负责。
 *
 * <p>Query 参数：{@code game_dir}（可选，默认 .minecraft）、{@code version}（可选，
 * 按版本过滤；源方法要求非 null，缺省按空串传入即不按版本过滤）。
 */
public final class ModsListApiProvider implements ApiProvider {

    @Override
    public String apiId() {
        return "mods/list";
    }

    @Override
    public String apiVersion() {
        return "1.0.0";
    }

    @Override
    public byte[] handle(ApiRequest request) throws Exception {
        String gameDir = ProviderSupport.gameDir(request);
        String version = request.param("version"); // 可选：缺省空串（不过滤版本）
        String normalized = version == null ? "" : version;
        List<ModInfo> mods = ModService.listMods(gameDir, normalized);
        return ProviderSupport.json(ProviderSupport.map(
                "count", mods.size(),
                "game_dir", gameDir,
                "version", normalized,
                "mods", mods));
    }
}
