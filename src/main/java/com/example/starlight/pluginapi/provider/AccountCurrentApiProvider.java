package com.example.starlight.pluginapi.provider;

import com.example.starlight.auth.AccountManager;
import com.example.starlight.auth.AccountManager.Account;
import com.example.starlight.pluginapi.ApiProvider;
import com.example.starlight.pluginapi.ApiRequest;

import java.util.Map;

/**
 * 功能类（包装）：{@code account/current} —— 查询当前选中的账号。
 *
 * <p>包装既有 {@link AccountManager#getCurrentAccount()}（可能返回 {@code null}），
 * 不改动原有逻辑。
 *
 * <p><b>安全红线</b>：账号对象内含 accessToken / refreshToken / authServer 等敏感字段，
 * 绝不直接序列化 {@link Account}，只手动构造脱敏 Map。
 *
 * <p>无 Query 参数。未登录时返回 {@code {"logged_in":false}}。
 */
public final class AccountCurrentApiProvider implements ApiProvider {

    @Override
    public String apiId() {
        return "account/current";
    }

    @Override
    public String apiVersion() {
        return "1.0.0";
    }

    @Override
    public byte[] handle(ApiRequest request) throws Exception {
        Account acc = AccountManager.getCurrentAccount();
        if (acc == null) {
            // 未登录：仅返回登录状态
            return ProviderSupport.json(ProviderSupport.map("logged_in", false));
        }
        return ProviderSupport.json(ProviderSupport.map(
                "logged_in", true,
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
