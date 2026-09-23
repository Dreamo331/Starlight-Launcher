package com.example.starlight.pluginapi.provider;

import com.example.starlight.model.CallbackInterfaces;
import com.example.starlight.pluginapi.ApiProvider;
import com.example.starlight.pluginapi.ApiRequest;
import com.example.starlight.service.DownloadService;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * 功能类（包装）：{@code version/install} —— 异步下载安装指定 Minecraft 版本。
 *
 * <p>包装既有 {@link DownloadService#downloadVersionAsync}，不改动原有逻辑。
 * 异步回调通过 {@link CompletableFuture} 桥接：成功（onSuccess）→ 完成；
 * 失败（onError）→ 以错误原因抛异常；再以 {@link ProviderSupport#await} 阻塞等待
 * （超时 600 秒）。
 *
 * <p>Query 参数：{@code version_id}（必填）、{@code game_dir}（可选，默认 .minecraft）。
 */
public final class VersionInstallApiProvider implements ApiProvider {

    @Override
    public String apiId() {
        return "version/install";
    }

    @Override
    public String apiVersion() {
        return "1.0.0";
    }

    @Override
    public byte[] handle(ApiRequest request) throws Exception {
        String versionId = ProviderSupport.required(request, "version_id");
        String gameDir = ProviderSupport.gameDir(request);

        // 异步回调桥接：onSuccess → complete(true)；onError → 异常
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        DownloadService.downloadVersionAsync(versionId, gameDir,
                (pct, msg) -> { /* 进度消息：本 API 不返回进度，忽略 */ },
                new CallbackInterfaces.ResultCallback<Boolean>() {
                    @Override
                    public void onSuccess(Boolean result) {
                        future.complete(true);
                    }

                    @Override
                    public void onError(String error) {
                        future.completeExceptionally(new IOException(error));
                    }
                });

        // 阻塞等待下载完成（最多 600 秒）
        ProviderSupport.await(future, 600);
        return ProviderSupport.json(ProviderSupport.map(
                "success", true,
                "version_id", versionId));
    }
}
