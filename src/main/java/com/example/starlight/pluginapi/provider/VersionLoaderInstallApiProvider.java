package com.example.starlight.pluginapi.provider;

import com.example.starlight.pluginapi.ApiProvider;
import com.example.starlight.pluginapi.ApiRequest;
import com.example.starlight.version.LoaderInstallEngine;

/**
 * 功能类（包装）：{@code version/loader/install} —— 安装 Minecraft 加载器（Fabric/Forge/NeoForge）。
 *
 * <p>包装既有 {@link LoaderInstallEngine}，不改动原有逻辑；该方法为<strong>同步阻塞</strong>
 * 执行，可能耗时数分钟（需要下载依赖并运行安装器），HTTP 调用会一直等待其完成。
 *
 * <p>Query 参数：
 * <ul>
 *   <li>{@code mc_version}（必填）：原版 Minecraft 版本号</li>
 *   <li>{@code loader_type}（必填）：加载器类型（fabric/forge/neoforge 等）</li>
 *   <li>{@code loader_version}（可选）：加载器版本，缺省自动获取最新稳定版</li>
 *   <li>{@code game_dir}（可选，默认 .minecraft）：游戏根目录</li>
 *   <li>{@code java_path}（可选，默认 java）：Java 可执行文件路径</li>
 *   <li>{@code folder_name}（可选）：自定义版本文件夹名</li>
 * </ul>
 *
 * <p>响应：{@code {"success":bool, "mc_version":..., "loader_type":..., "message":最后进度消息}}。
 */
public final class VersionLoaderInstallApiProvider implements ApiProvider {

    @Override
    public String apiId() {
        return "version/loader/install";
    }

    @Override
    public String apiVersion() {
        return "1.0.0";
    }

    @Override
    public byte[] handle(ApiRequest request) throws Exception {
        String mcVersion = ProviderSupport.required(request, "mc_version");
        String loaderType = ProviderSupport.required(request, "loader_type");
        String loaderVersion = blankToNull(request.param("loader_version"));
        String gameDir = ProviderSupport.gameDir(request);
        String javaPath = request.param("java_path");
        if (javaPath == null || javaPath.isBlank()) {
            javaPath = "java";
        }
        String folderName = blankToNull(request.param("folder_name"));

        // installLoader 为同步阻塞调用，进度回调在调用线程内触发，记录最后一条消息
        final String[] lastMessage = {null};
        LoaderInstallEngine.ProgressCallback progress = (pct, msg) -> lastMessage[0] = msg;

        boolean success = LoaderInstallEngine.installLoader(
                mcVersion, loaderType, loaderVersion, gameDir, javaPath, progress, folderName);

        String message = lastMessage[0];
        if (message == null) {
            message = success ? "安装完成" : "安装失败";
        }
        return ProviderSupport.json(ProviderSupport.map(
                "success", success,
                "mc_version", mcVersion,
                "loader_type", loaderType,
                "message", message));
    }

    /** 空白字符串归一化为 null（可选参数缺省语义，null 让引擎自行取最新版/标准目录）。 */
    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
