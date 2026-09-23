package com.example.starlight.pluginapi.provider;

import com.example.starlight.pluginapi.ApiProvider;
import com.example.starlight.pluginapi.ApiRequest;
import com.example.starlight.model.LauncherProfileInfo;
import com.example.starlight.service.FileService;

/**
 * 功能类（包装）：{@code game/launcher_profiles} —— 读取启动器档案（launcher_profiles.json）。
 *
 * <p>包装既有 {@link FileService}，不改动原有逻辑；只向外提供 JSON 数据，
 * UI 展示完全由插件负责。文件缺失或解析失败时返回的 {@code profiles} 为 {@code null}。
 *
 * <p>Query 参数：{@code game_dir}（可选，默认 .minecraft）。
 */
public final class GameLauncherProfilesApiProvider implements ApiProvider {

    @Override
    public String apiId() {
        return "game/launcher_profiles";
    }

    @Override
    public String apiVersion() {
        return "1.0.0";
    }

    @Override
    public byte[] handle(ApiRequest request) throws Exception {
        String gameDir = ProviderSupport.gameDir(request);
        LauncherProfileInfo info = FileService.readLauncherProfiles(gameDir);
        return ProviderSupport.json(ProviderSupport.map(
                "game_dir", gameDir,
                "profiles", info));
    }
}
