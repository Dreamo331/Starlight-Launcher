package com.example.starlight.pluginapi.provider;

import com.example.starlight.auth.AccountManager;
import com.example.starlight.auth.AccountManager.Account;
import com.example.starlight.pluginapi.ApiProvider;
import com.example.starlight.pluginapi.ApiRequest;

import java.util.Map;

/**
 * 功能类（包装）：{@code account/login/offline} —— 以离线账号方式登录（同步）。
 *
 * <p>包装既有 {@link AccountManager#loginOffline(String)}，不改动原有逻辑；
 * 登录成功后会写入账号列表并设为当前账号。
 *
 * <p><b>安全红线</b>：返回的账号对象内含 accessToken / refreshToken / authServer 等
 * 敏感字段，绝不直接序列化 {@link Account}，只手动构造脱敏 Map。
 *
 * <p>Query 参数：{@code player_name}（必填，玩家名）。
 */
public final class AccountLoginOfflineApiProvider implements ApiProvider {

    @Override
    public String apiId() {
        return "account/login/offline";
    }

    @Override
    public String apiVersion() {
        return "1.0.0";
    }

    @Override
    public byte[] handle(ApiRequest request) throws Exception {
        String playerName = ProviderSupport.required(request, "player_name");
        Account acc = AccountManager.loginOffline(playerName);
        return ProviderSupport.json(ProviderSupport.map(
                "success", true,
                "account", accountMap(acc)));
    }

    /** 账号脱敏结构：只暴露展示字段，绝不包含令牌/认证服务器等敏感信息。 */
    private static Map<String, Object> accountMap(Account a) {
        return ProviderSupport.map(
                "id", a.id,
                "name", a.name,
                "type", a.type.name(),
                "type_label", a.getTypeLabel(),
                "display_name", a.displayName(),
                "last_used", a.lastUsed,
                "token_expires_at", a.tokenExpiresAt);
    }
}
