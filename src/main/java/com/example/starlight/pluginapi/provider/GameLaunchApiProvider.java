package com.example.starlight.pluginapi.provider;

import com.example.starlight.model.CallbackInterfaces.ResultCallback;
import com.example.starlight.model.LaunchConfig;
import com.example.starlight.model.LaunchResult;
import com.example.starlight.pluginapi.ApiProvider;
import com.example.starlight.pluginapi.ApiRequest;
import com.example.starlight.service.GameLauncherService;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * 功能类（包装）：{@code game/launch} —— 异步启动 Minecraft 并阻塞等待结果。
 *
 * <p>包装既有 {@link GameLauncherService#launchMinecraftAsync}，不改动原有逻辑。
 * 异步回调通过 {@link CompletableFuture} 桥接：游戏窗口出现（onGameStarted）→
 * {@code "started"}；启动流程成功完成（onSuccess）→ {@code "launched"}；
 * 失败（onError）→ 以错误原因抛异常。
 *
 * <p>Query 参数：{@code version}（必填）、{@code game_dir}、{@code java_path}、
 * {@code max_memory}（默认 4096）、{@code min_memory}（默认 2048）、
 * {@code window_width}（默认 854）、{@code window_height}（默认 480）、
 * {@code fullscreen}（默认 false）、{@code version_isolation}（默认 false）、
 * {@code jvm_args}、{@code game_args}。
 *
 * <p>说明：账号相关字段（userName/uuid/accessToken/userType）保留 {@link LaunchConfig}
 * 默认值即可——启动器内部（SilentLoginManager）会按当前账号自动刷新覆盖。
 */
public final class GameLaunchApiProvider implements ApiProvider {

    @Override
    public String apiId() {
        return "game/launch";
    }

    @Override
    public String apiVersion() {
        return "1.0.0";
    }

    @Override
    public byte[] handle(ApiRequest request) throws Exception {
        // 构造启动配置（仅覆盖插件可指定的字段）
        LaunchConfig config = new LaunchConfig();
        config.gameDir = ProviderSupport.gameDir(request);
        config.version = ProviderSupport.required(request, "version");
        String javaPath = request.param("java_path");
        config.javaPath = javaPath == null || javaPath.isBlank() ? "java" : javaPath.trim();
        config.maxMemory = ProviderSupport.intParam(request, "max_memory", 4096);
        config.minMemory = ProviderSupport.intParam(request, "min_memory", 2048);
        config.windowWidth = ProviderSupport.intParam(request, "window_width", 854);
        config.windowHeight = ProviderSupport.intParam(request, "window_height", 480);
        config.fullscreen = ProviderSupport.boolParam(request, "fullscreen", false);
        config.versionIsolation = ProviderSupport.boolParam(request, "version_isolation", false);
        String jvmArgs = request.param("jvm_args");
        config.jvmArgs = jvmArgs == null ? "" : jvmArgs;
        String gameArgs = request.param("game_args");
        config.gameArgs = gameArgs == null ? "" : gameArgs;

        // 异步回调桥接：onGameStarted → "started"；onSuccess → "launched"；onError → 异常
        CompletableFuture<String> future = new CompletableFuture<>();
        String[] lastMessage = { "" }; // 记录最后一条进度消息

        GameLauncherService.launchMinecraftAsync(config,
                (pct, msg) -> lastMessage[0] = msg,
                new ResultCallback<LaunchResult>() {
                    @Override
                    public void onSuccess(LaunchResult result) {
                        future.complete("launched");
                    }

                    @Override
                    public void onError(String error) {
                        future.completeExceptionally(new IOException(error));
                    }
                },
                () -> future.complete("started")); // 游戏窗口已出现

        // 阻塞等待启动结果（最多 300 秒）
        String status = ProviderSupport.await(future, 300);
        return ProviderSupport.json(ProviderSupport.map(
                "status", status,
                "message", lastMessage[0]));
    }
}
