package com.example.starlight.pluginapi.server;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话管理：{@code session_token} → 会话信息。
 *
 * <p>策略（用户已确认）：<strong>UUID 随机串 + 内存 Map 存储 + 无过期时间</strong>；
 * 启动器进程退出即全部失效，适合本地单机场景。
 * 使用 {@link ConcurrentHashMap} 保证 HTTP 多线程并发安全。
 */
final class SessionManager {

    /** 一次握手成功后建立的插件会话。 */
    static final class Session {

        final String token;
        final String pluginId;
        final String pluginName;
        final String pluginVersion;
        final long createdAtMillis;

        Session(String token, String pluginId, String pluginName, String pluginVersion, long createdAtMillis) {
            this.token = token;
            this.pluginId = pluginId;
            this.pluginName = pluginName;
            this.pluginVersion = pluginVersion;
            this.createdAtMillis = createdAtMillis;
        }

        @Override
        public String toString() {
            return "Session{pluginId='" + pluginId + "', pluginVersion='" + pluginVersion + "'}";
        }
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** 生成 UUID 形式的 token 并建立会话。 */
    String createSession(String pluginId, String pluginName, String pluginVersion) {
        String token = UUID.randomUUID().toString();
        sessions.put(token, new Session(token, pluginId, pluginName, pluginVersion, System.currentTimeMillis()));
        return token;
    }

    /** token 是否有效（存在即有效，无过期时间）。 */
    boolean isValid(String token) {
        return token != null && sessions.containsKey(token);
    }

    Session get(String token) {
        return token == null ? null : sessions.get(token);
    }

    /** 吊销会话（预留：插件注销时使用）。 */
    void revoke(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    /** 当前活跃会话数（调试用）。 */
    int sessionCount() {
        return sessions.size();
    }
}
