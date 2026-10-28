package com.example.starlight.pluginapi.provider;


import com.example.starlight.util.I18N;
import com.example.starlight.config.StarlightConfig;
import com.example.starlight.frp.SakuraFrpApi;
import com.example.starlight.frp.SakuraFrpService;
import com.example.starlight.frp.SakuraFrpTokenStore;
import com.example.starlight.main.ConfigManager;
import com.example.starlight.pluginapi.ApiProvider;
import com.example.starlight.pluginapi.ApiRequest;

import java.util.concurrent.CompletableFuture;

/**
 * 功能类（包装）：{@code net/frp/start} —— 启动一条 SakuraFrp 隧道（异地联机）。
 *
 * <p>调用前需要先在启动器「联机 → FRP 联机」里连接 SakuraFrp 并保留访问密钥，
 * 或者在本次请求里传 {@code token}（会写入本机凭据文件）。
 *
 * <p>Query 参数：
 * <ul>
 *   <li>{@code tunnel_id}（可选）：SakuraFrp 隧道 ID；缺省用联机页最后一次启动的隧道；</li>
 *   <li>{@code token}（可选）：SakuraFrp 访问密钥，仅在该账号尚未保存时提供。</li>
 * </ul>
 *
 * <p>响应：{@code {"success":true,"state":"running","tunnel_id":...,"tunnel_name":"...",
 * "remote_port":...,"public_address":"..."}}
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
        String token = request.param("token");
        if (token != null && !token.isBlank()) {
            // 先校验再落盘：避免插件传错密钥把本机已保存的有效密钥覆盖掉
            new SakuraFrpApi().userInfo(token.trim());
            SakuraFrpTokenStore.save(token.trim());
        }

        int tunnelId = ProviderSupport.intParam(request, "tunnel_id", 0);
        if (tunnelId <= 0) {
            // 联机页把默认隧道写在 starlight-client.ini（CLIENT_KEYS），
            // starlight.ini 里没有该键，必须读客户端配置
            tunnelId = parseId(ConfigManager.readClientConfig().get("SakuraFrpTunnelId"));
        }
        if (tunnelId <= 0) {
            tunnelId = parseId(StarlightConfig.get("SakuraFrpTunnelId"));
        }
        if (tunnelId <= 0) {
            throw new IllegalArgumentException(
                    I18N.get("provider.t0001"));
        }

        CompletableFuture<SakuraFrpService.StartResult> future =
                SakuraFrpService.get().startTunnelAsync(tunnelId, null);
        SakuraFrpService.StartResult result;
        try {
            result = ProviderSupport.await(future, 120);
        } catch (Exception e) {
            // 超时 / 客户端断开时不要留下一个还在后台启动的隧道
            SakuraFrpService.get().stop();
            throw e;
        }

        return ProviderSupport.json(ProviderSupport.map(
                "success", true,
                "state", "running",
                "tunnel_id", result.tunnelId(),
                "tunnel_name", result.tunnelName(),
                "remote_port", result.remotePort(),
                "public_address", result.publicAddress()));
    }

    private static int parseId(String text) {
        if (text == null || text.isBlank()) return 0;
        try {
            return Math.max(0, Integer.parseInt(text.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
