package com.example.starlight.pluginapi.provider;

import com.example.starlight.pluginapi.ApiProvider;
import com.example.starlight.pluginapi.ApiRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 功能类（自定义实现）：{@code version/installed} —— 扫描已安装的游戏版本。
 *
 * <p>无现成服务类：直接扫描游戏目录下 {@code versions} 子目录，凡目录名与
 * 同名 {@code .json} 文件同时存在即视为已安装版本，按名称字典序排序后返回；
 * 只向外提供 JSON 数据，UI 展示完全由插件负责。
 *
 * <p>Query 参数：{@code game_dir}（可选，默认 .minecraft）。
 */
public final class VersionInstalledApiProvider implements ApiProvider {

    @Override
    public String apiId() {
        return "version/installed";
    }

    @Override
    public String apiVersion() {
        return "1.0.0";
    }

    @Override
    public byte[] handle(ApiRequest request) throws Exception {
        String gameDir = ProviderSupport.gameDir(request);
        Path versionsDir = Paths.get(gameDir, "versions");
        List<String> ids = new ArrayList<>();
        if (Files.isDirectory(versionsDir)) {
            try (Stream<Path> stream = Files.list(versionsDir)) {
                stream.filter(Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .filter(name -> Files.isRegularFile(versionsDir.resolve(name).resolve(name + ".json")))
                        .sorted()
                        .forEach(ids::add);
            }
        }
        List<Map<String, Object>> versions = new ArrayList<>();
        for (String id : ids) {
            versions.add(ProviderSupport.map("id", id));
        }
        return ProviderSupport.json(ProviderSupport.map(
                "count", versions.size(),
                "game_dir", gameDir,
                "versions", versions));
    }
}
