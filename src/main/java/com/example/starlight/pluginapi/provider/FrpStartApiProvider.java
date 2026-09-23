package com.example.starlight.pluginapi.provider;

import com.example.starlight.model.CallbackInterfaces.ResultCallback;
import com.example.starlight.model.FrpStatus;
import com.example.starlight.pluginapi.ApiProvider;
import com.example.starlight.pluginapi.ApiRequest;
import com.example.starlight.service.FrpService;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * 功能类（包装）：{@code net/frp/start} —— 启动 FRP 隧道（异地联机）。
 *
 * <p>包装既有 {@link FrpService}，不改动原有逻辑。{@link FrpService#startFrpAsync}
 * 是异步回调式的，本类通过 {@link CompletableFuture} 桥接为同步等待
 * （最长 60 秒），隧道建立成功后返回公网信息。
 *
 * <p>Query 参数：{@code local_address}（必填，如 127.0.0.1:25565）。
 *
 * <p>响应：{@code {"success":true, "state":..., "tunnel_port":..., "public_address":...}}
 * （{@link FrpStatus} 为公共字段类，手动构造响应 Map）。
 */
public final class FrpStartApiProvider implements ApiProvider {

    @Override
    public String apiId() {
        return "net/frp/start";
    }

    @Override
    public String apiVersion() {
        return "1.0.0";
    }

    @Override
    public byte[] handle(ApiRequest request) throws Exception {
        String localAddress = ProviderSupport.required(request, "local_address");

        // 异步回调 → CompletableFuture 桥接：onSuccess 完成，onError 以 IOException 异常完成
        CompletableFuture<FrpStatus> future = new CompletableFuture<>();
        ResultCallback<FrpStatus> callback = new ResultCallback<>() {
            @Override
            public void onSuccess(FrpStatus status) {
                future.complete(status);
            }

            @Override
            public void onError(String error) {
                future.completeExceptionally(new IOException(error));
            }
        };
        FrpService.startFrpAsync(localAddress, callback);

        FrpStatus status = ProviderSupport.await(future, 60);
        return ProviderSupport.json(ProviderSupport.map(
                "success", true,
                "state", status.state,
                "tunnel_port", status.tunnelPort,
                "public_address", status.publicAddress));
    }
}
