package com.example.starlight.pluginapi.provider;

import com.example.starlight.pluginapi.ApiProvider;
import com.example.starlight.pluginapi.ApiRequest;
import com.example.starlight.service.FrpService;

/**
 * 功能类（包装）：{@code net/frp/stop} —— 断开当前 FRP 隧道。
 *
 * <p>包装既有 {@link FrpService#stopFrp()}，不改动原有逻辑；无参数，
 * 未运行隧道时也是安全的空操作。
 *
 * <p>响应：{@code {"success":true}}。
 */
public final class FrpStopApiProvider implements ApiProvider {

    @Override
    public String apiId() {
        return "net/frp/stop";
    }

    @Override
    public String apiVersion() {
        return "1.0.0";
    }

    @Override
    public byte[] handle(ApiRequest request) throws Exception {
        FrpService.stopFrp();
        return ProviderSupport.json(ProviderSupport.map("success", true));
    }
}
