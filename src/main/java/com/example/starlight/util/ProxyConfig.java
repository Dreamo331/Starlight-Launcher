package com.example.starlight.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 代理配置（参照 HMCL {@code ProxyOption} 与 {@code DefaultLauncher.buildJVMArgs}）：
 * <ul>
 *   <li>系统代理（SYSTEM）：启动器与游戏均使用操作系统代理设置（{@code -Djava.net.useSystemProxies=true}）；</li>
 *   <li>HTTP 代理（HTTP）：通过指定主机/端口（可选用户名密码）走 HTTP 代理
 *       （{@code -Dhttp.proxyHost/-Dhttp.proxyPort/-Dhttps.proxyHost/-Dhttps.proxyPort}）。</li>
 * </ul>
 * 配置键：ProxyType / ProxyHost / ProxyPort / ProxyUser / ProxyPass。
 */
public final class ProxyConfig {

    public static final String KEY_TYPE = "ProxyType";
    public static final String KEY_HOST = "ProxyHost";
    public static final String KEY_PORT = "ProxyPort";
    public static final String KEY_USER = "ProxyUser";
    public static final String KEY_PASS = "ProxyPass";

    public enum ProxyType {
        /** 不使用代理（直连） */
        DIRECT,
        /** 使用操作系统代理设置 */
        SYSTEM,
        /** 指定 HTTP 代理 */
        HTTP
    }

    private ProxyConfig() {
    }

    public static ProxyType type(Map<String, String> cfg) {
        String t = cfg != null ? cfg.get(KEY_TYPE) : null;
        if ("HTTP".equalsIgnoreCase(t)) return ProxyType.HTTP;
        if ("DIRECT".equalsIgnoreCase(t)) return ProxyType.DIRECT;
        return ProxyType.SYSTEM;
    }

    public static String host(Map<String, String> cfg) {
        return cfg != null ? cfg.getOrDefault(KEY_HOST, "") : "";
    }

    public static int port(Map<String, String> cfg) {
        if (cfg == null) return 0;
        try {
            return Integer.parseInt(cfg.getOrDefault(KEY_PORT, "").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String user(Map<String, String> cfg) {
        return cfg != null ? cfg.getOrDefault(KEY_USER, "") : "";
    }

    public static String pass(Map<String, String> cfg) {
        return cfg != null ? cfg.getOrDefault(KEY_PASS, "") : "";
    }

    /** HTTP 代理配置是否有效（主机非空且端口在有效范围） */
    public static boolean isHttpValid(Map<String, String> cfg) {
        return !host(cfg).isBlank() && port(cfg) > 0 && port(cfg) <= 65535;
    }

    /**
     * 应用到启动器自身网络（修改 JVM 系统属性；java.net.http.HttpClient 与
     * URLConnection 的默认代理选择器会读取这些属性）。
     * HTTP 代理时清除系统代理标志并设置显式代理；
     * 系统代理/未配置时清除显式代理并启用系统代理（与 BaseLauncher 默认行为一致）。
     */
    public static void applyToSystem(Map<String, String> cfg) {
        if (type(cfg) == ProxyType.HTTP && isHttpValid(cfg)) {
            System.clearProperty("java.net.useSystemProxies");
            System.setProperty("http.proxyHost", host(cfg));
            System.setProperty("http.proxyPort", String.valueOf(port(cfg)));
            System.setProperty("https.proxyHost", host(cfg));
            System.setProperty("https.proxyPort", String.valueOf(port(cfg)));
            if (!user(cfg).isBlank()) {
                System.setProperty("http.proxyUser", user(cfg));
                System.setProperty("http.proxyPassword", pass(cfg));
            } else {
                System.clearProperty("http.proxyUser");
                System.clearProperty("http.proxyPassword");
            }
        } else if (type(cfg) == ProxyType.DIRECT) {
            // 直连：清除所有代理属性并关闭系统代理
            System.clearProperty("http.proxyHost");
            System.clearProperty("http.proxyPort");
            System.clearProperty("https.proxyHost");
            System.clearProperty("https.proxyPort");
            System.clearProperty("http.proxyUser");
            System.clearProperty("http.proxyPassword");
            System.clearProperty("java.net.useSystemProxies");
        } else {
            System.clearProperty("http.proxyHost");
            System.clearProperty("http.proxyPort");
            System.clearProperty("https.proxyHost");
            System.clearProperty("https.proxyPort");
            System.clearProperty("http.proxyUser");
            System.clearProperty("http.proxyPassword");
            System.setProperty("java.net.useSystemProxies", "true");
        }
    }

    /**
     * 生成游戏启动 JVM 注入参数（参照 HMCL DefaultLauncher.buildJVMArgs）：
     * <ul>
     *   <li>SYSTEM：{@code -Djava.net.useSystemProxies=true}；</li>
     *   <li>HTTP：{@code -Djava.net.useSystemProxies=false}（覆盖 BaseLauncher 默认值，
     *       命令行重复 -D 参数后者生效）+ {@code -Dhttp.proxyHost/-Dhttp.proxyPort/-Dhttps.proxyHost/-Dhttps.proxyPort}
     *       及可选认证参数。</li>
     * </ul>
     */
    public static List<String> buildJvmArgs(Map<String, String> cfg) {
        List<String> args = new ArrayList<>();
        if (type(cfg) == ProxyType.HTTP && isHttpValid(cfg)) {
            args.add("-Djava.net.useSystemProxies=false");
            args.add("-Dhttp.proxyHost=" + host(cfg));
            args.add("-Dhttp.proxyPort=" + port(cfg));
            args.add("-Dhttps.proxyHost=" + host(cfg));
            args.add("-Dhttps.proxyPort=" + port(cfg));
            if (!user(cfg).isBlank()) {
                args.add("-Dhttp.proxyUser=" + user(cfg));
                args.add("-Dhttp.proxyPassword=" + pass(cfg));
            }
        } else if (type(cfg) == ProxyType.DIRECT) {
            // 直连：覆盖 BaseLauncher 默认的系统代理标志（命令行重复 -D 后者生效）
            args.add("-Djava.net.useSystemProxies=false");
        } else {
            args.add("-Djava.net.useSystemProxies=true");
        }
        return args;
    }
}
