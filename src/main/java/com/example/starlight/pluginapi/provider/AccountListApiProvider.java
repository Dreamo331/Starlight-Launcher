package com.example.starlight.pluginapi.provider;

import com.example.starlight.auth.AccountManager;
import com.example.starlight.auth.AccountManager.Account;
import com.example.starlight.pluginapi.ApiProvider;
import com.example.starlight.pluginapi.ApiRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 功能类（包装）：{@code account/list} —— 列出所有已登录账号。
 *
 * <p>包装既有 {@link AccountManager#listAccounts()}，不改动原有逻辑。
 *
 * <p><b>安全红线</b>：账号对象内含 accessToken / refreshToken / authServer 等敏感字段，
 * 绝不直接序列化 {@link Account}，只手动构造脱敏 Map（id/name/type/type_label/
 * display_name/last_used/token_expires_at）。
 *
 * <p>无 Query 参数。
 */
public final class AccountListApiProvider implements ApiProvider {

    @Override
    public String apiId() {
        return "account/list";
    }

    @Override
    public String apiVersion() {
        return "1.0.0";
    }

    @Override
    public byte[] handle(ApiRequest request) throws Exception {
        List<Account> accounts = AccountManager.listAccounts();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Account a : accounts) {
            list.add(accountMap(a));
        }
        return ProviderSupport.json(ProviderSupport.map(
                "count", accounts.size(),
                "accounts", list));
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
