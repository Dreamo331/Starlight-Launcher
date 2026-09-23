package com.example.starlight.service;

import com.example.starlight.auth.AccountManager;
import com.example.starlight.config.StarlightConfig;
import com.example.starlight.loginmicrosoft.MinecraftAuthLauncherDeviceCode;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 微软账号静默登录（令牌刷新）管理器
 * <p>
 * 两种模式（设置 → 高级设置 → 静默登录时机，默认「启动游戏时」）：
 * <ul>
 *   <li>{@link #MODE_LAUNCH}  启动游戏时静默登录：每次点击启动时检查并刷新过期令牌</li>
 *   <li>{@link #MODE_STARTUP} 开启启动器静默登录：启动器启动时（闪屏后台）提前刷新，
 *       游戏启动时几乎总是命中有效令牌，不再等待网络刷新</li>
 * </ul>
 * 刷新逻辑幂等：仅微软账号 + 令牌已过期 + 距上次刷新超过 5 分钟冷却才执行。
 */
public final class SilentLoginManager {

    /** 配置键（starlight.ini [Launcher] 段） */
    public static final String CONFIG_KEY = "SilentLoginMode";
    /** 模式：启动游戏时静默登录（默认） */
    public static final String MODE_LAUNCH = "launch";
    /** 模式：开启启动器静默登录 */
    public static final String MODE_STARTUP = "startup";

    private static final Logger log = LoggerFactory.getLogger(SilentLoginManager.class);

    /** 上次刷新时间戳（防抖用，跨调用共享） */
    private static volatile long lastRefreshTime = 0;
    private static final long REFRESH_COOLDOWN_MS = 5 * 60 * 1000L; // 5 分钟冷却

    /** 静默登录进度回调（可为 null，表示完全静默） */
    public interface Progress {
        void onProgress(int percent, String message);
    }

    private SilentLoginManager() {}

    /** 是否「开启启动器静默登录」模式（默认 false，即启动游戏时静默登录） */
    public static boolean isStartupMode() {
        return MODE_STARTUP.equalsIgnoreCase(StarlightConfig.get(CONFIG_KEY));
    }

    /** 是否「启动游戏时静默登录」模式（默认） */
    public static boolean isLaunchMode() {
        return !isStartupMode();
    }

    /**
     * 静默刷新当前微软账号令牌（幂等）。
     * <p>
     * 刷新成功后写回 AccountManager；调用方可根据返回值更新启动配置中的令牌。
     *
     * @param progress 进度回调（可为 null）
     * @return 刷新后的账号（未刷新/无账号/冷却中返回 null）
     */
    public static synchronized AccountManager.Account refreshIfNeeded(Progress progress) {
        AccountManager.Account currentAccount = AccountManager.getCurrentAccount();
        if (currentAccount == null || currentAccount.type != AccountManager.AccountType.MICROSOFT
                || currentAccount.refreshToken == null || currentAccount.refreshToken.isEmpty()
                || !isTokenExpired(currentAccount.accessToken, currentAccount.tokenExpiresAt)) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (now - lastRefreshTime < REFRESH_COOLDOWN_MS) {
            report(progress, 4, "距上次刷新不足 5 分钟，跳过刷新");
            return null;
        }
        report(progress, 2, "检测到令牌已过期，正在静默刷新...");
        try {
            MinecraftAuthLauncherDeviceCode.RefreshResult refreshed =
                    MinecraftAuthLauncherDeviceCode.silentRefresh(currentAccount.refreshToken);
            if (refreshed != null && refreshed.accessToken != null) {
                AccountManager.Account updated = new AccountManager.Account(
                        refreshed.uuid != null ? refreshed.uuid : currentAccount.id,
                        refreshed.username != null ? refreshed.username : currentAccount.name,
                        AccountManager.AccountType.MICROSOFT,
                        refreshed.accessToken,
                        refreshed.refreshToken,
                        "",
                        System.currentTimeMillis()
                );
                AccountManager.addAccount(updated);
                lastRefreshTime = now;
                report(progress, 4, "令牌刷新成功");
                return updated;
            }
        } catch (Exception e) {
            log.warn("Silent token refresh failed: {}", e.getMessage());
            report(progress, 4, "令牌刷新失败，使用现有凭证继续: " + e.getMessage());
        }
        return null;
    }

    private static void report(Progress progress, int percent, String message) {
        if (progress != null) {
            try {
                progress.onProgress(percent, message);
            } catch (Exception ignored) {
            }
        }
    }

    // ======================== 令牌过期判断（与 UIGeneralControlClass 原实现一致） ========================

    private static boolean isTokenExpired(String accessToken, long tokenExpiresAt) {
        if (accessToken == null || accessToken.isEmpty()) return true;
        long now = System.currentTimeMillis() / 1000;
        // 优先使用记录的时间戳
        if (tokenExpiresAt > 0) {
            return tokenExpiresAt - now < 300; // 5 分钟缓冲
        }
        // 降级：尝试 JWT 解析
        return isTokenExpiredJwt(accessToken);
    }

    private static boolean isTokenExpiredJwt(String accessToken) {
        if (accessToken == null || accessToken.isEmpty()) return true;
        try {
            String[] parts = accessToken.split("\\.");
            if (parts.length < 2) return false; // 无法解析时假定未过期
            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            String json = new String(decoded, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (!obj.has("exp")) return false;   // 无 exp 字段时假定未过期
            long exp = obj.get("exp").getAsLong();
            long now = System.currentTimeMillis() / 1000;
            return exp - now < 300;                // 5 分钟缓冲
        } catch (Exception e) {
            return false; // 解析失败时假定未过期，由服务端校验
        }
    }
}
