package com.example.starlight.pluginapi.provider;

import com.example.starlight.crash.CrashReportParser;
import com.example.starlight.pluginapi.ApiProvider;
import com.example.starlight.pluginapi.ApiRequest;

/**
 * 功能类（包装）：{@code crash/latest} —— 查询最近的崩溃报告及其摘要。
 *
 * <p>包装既有 {@link CrashReportParser}，不改动原有逻辑；只向外提供 JSON 数据，
 * UI 展示完全由插件负责。
 *
 * <p>Query 参数：{@code game_dir}（可选，默认 .minecraft）。
 * 无崩溃报告时返回 {@code {"found": false}}，否则返回路径与摘要。
 */
public final class CrashLatestApiProvider implements ApiProvider {

    @Override
    public String apiId() {
        return "crash/latest";
    }

    @Override
    public String apiVersion() {
        return "1.0.0";
    }

    @Override
    public byte[] handle(ApiRequest request) throws Exception {
        String gameDir = ProviderSupport.gameDir(request);
        String path = CrashReportParser.findLatestCrashReport(gameDir);
        if (path == null) {
            return ProviderSupport.json(ProviderSupport.map(
                    "found", false,
                    "game_dir", gameDir));
        }
        String summary = CrashReportParser.summarize(path);
        return ProviderSupport.json(ProviderSupport.map(
                "found", true,
                "game_dir", gameDir,
                "path", path,
                "summary", summary));
    }
}
