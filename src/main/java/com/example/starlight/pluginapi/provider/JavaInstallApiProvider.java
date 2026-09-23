package com.example.starlight.pluginapi.provider;

import com.example.starlight.model.CallbackInterfaces;
import com.example.starlight.pluginapi.ApiProvider;
import com.example.starlight.pluginapi.ApiRequest;
import com.example.starlight.service.JavaService;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * 功能类（包装）：{@code java/install} —— 异步安装 JDK 运行时。
 *
 * <p>包装既有 {@link JavaService}，不改动原有逻辑。{@link JavaService#installJavaAsync}
 * 本身是异步回调式的，本类通过 {@link CompletableFuture} 桥接为同步等待
 * （最长 600 秒），供 HTTP 调用方拿到最终安装结果。
 *
 * <p>Query 参数：{@code version}（必填，如 jdk8/jdk17/jdk21）、
 * {@code download_dir}（可选，默认 "."）。
 *
 * <p>响应：{@code {"success":true, "version":..., "message":...}}。
 */
public final class JavaInstallApiProvider implements ApiProvider {

    @Override
    public String apiId() {
        return "java/install";
    }

    @Override
    public String apiVersion() {
        return "1.0.0";
    }

    @Override
    public byte[] handle(ApiRequest request) throws Exception {
        String version = ProviderSupport.required(request, "version");
        String downloadDir = request.param("download_dir");
        if (downloadDir == null || downloadDir.isBlank()) {
            downloadDir = ".";
        }

        // 异步回调 → CompletableFuture 桥接：onSuccess 完成，onError 以 IOException 异常完成
        CompletableFuture<String> future = new CompletableFuture<>();
        CallbackInterfaces.ProgressCallback progress = (pct, msg) -> {
            // 进度仅供安装过程使用，响应中不体现
        };
        CallbackInterfaces.ResultCallback<String> result = new CallbackInterfaces.ResultCallback<>() {
            @Override
            public void onSuccess(String value) {
                future.complete(value);
            }

            @Override
            public void onError(String error) {
                future.completeExceptionally(new IOException(error));
            }
        };
        JavaService.installJavaAsync(version, downloadDir, progress, result);

        String message = ProviderSupport.await(future, 600);
        return ProviderSupport.json(ProviderSupport.map(
                "success", true,
                "version", version,
                "message", message));
    }
}
