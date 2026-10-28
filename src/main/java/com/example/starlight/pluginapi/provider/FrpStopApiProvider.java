package com.example.starlight.pluginapi.provider;

import com.example.starlight.frp.SakuraFrpService;
import com.example.starlight.pluginapi.ApiProvider;
import com.example.starlight.pluginapi.ApiRequest;

/**
 * 功能类（包装）：{@code net/frp/stop} —— 停止当前 SakuraFrp 隧道。
 *
 * <p>无参数；未运行隧道时也是安全的空操作。
 * 根据 {@code net/frp/start} 的语义，这里只停止由启动器托管的 frpc 进程，
 * 不会撤销或删除 SakuraFrp 面板里的隧道。
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
        SakuraFrpService.get().stop();
        return ProviderSupport.json(ProviderSupport.map("success", true));
    }
}
