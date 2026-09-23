package com.example.starlight.util;

import com.example.starlight.main.ConfigManager;
import com.example.starlight.newui.AppConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 下载源网络状态服务：对下载源做<b>真实 HTTP 往返延迟测量</b>并缓存结果。
 *
 * <p>此前「网络检测」页和主页网络卡片展示的是假数据（随机数/写死的「正常/空闲」，
 * 页面缓存后也不再更新）。这里统一提供真实测量：
 * <ul>
 *   <li>按源发一次轻量请求（HEAD，返回任何 HTTP 响应即视为网络可达），取往返毫秒数；</li>
 *   <li>结果带时间戳缓存，同一源 30 秒内不重复测；</li>
 *   <li>后台异步执行，UI 侧只负责把结果写进控件。</li>
 * </ul>
 *
 * <p>全部方法线程安全，可在任意线程调用；UI 更新请自行切回 FX 线程。
 */
public final class NetworkStatusService {

    /** 单个源的测速结果 */
    public static final class SourceResult {
        public final String name;
        public final String url;
        /** 是否可达（拿到 HTTP 响应即 true；5xx 视为服务异常，false） */
        public final boolean ok;
        /** 往返延迟毫秒；失败时无意义 */
        public final int latencyMs;

        SourceResult(String name, String url, boolean ok, int latencyMs) {
            this.name = name;
            this.url = url;
            this.ok = ok;
            this.latencyMs = latencyMs;
        }

        /** 供状态文案使用的简短描述 */
        public String describe() {
            if (!ok) return "不可达";
            return "在线 · " + latencyMs + "ms";
        }
    }

    /** 测速目标：与设置页「下载源」选项一致（源名、探针 URL） */
    private static final String[][] SOURCES = {
            {"BMCLAPI", "https://bmclapi2.bangbang93.com/mc/game/version_manifest.json"},
            {"Mojang", "https://launchermeta.mojang.com/mc/game/version_manifest.json"},
            {"MCBBS", "https://download.mcbbs.net/mc/game/version_manifest.json"},
    };

    /** 结果缓存有效期：30 秒内重复请求直接返回缓存 */
    public static final long CACHE_TTL_MS = 30_000L;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "net-status");
        t.setDaemon(true);
        return t;
    });

    private static volatile List<SourceResult> lastResults = Collections.emptyList();
    private static volatile long lastTestAt = 0L;

    private NetworkStatusService() {
    }

    /** 测速候选源名（顺序与设置页一致） */
    public static List<String> sourceNames() {
        List<String> names = new ArrayList<>(SOURCES.length);
        for (String[] s : SOURCES) names.add(s[0]);
        return names;
    }

    /** 当前配置的下载源名（starlight-client.ini 的 DownloadSource，默认 BMCLAPI） */
    public static String currentSourceName() {
        try {
            return ConfigManager.readClientConfig().getOrDefault("DownloadSource", "BMCLAPI");
        } catch (Exception e) {
            return "BMCLAPI";
        }
    }

    /** 当前下载源的最近一次测速结果；从未测过返回 null */
    public static SourceResult currentSourceCached() {
        return cachedFor(currentSourceName());
    }

    /** 指定源名的最近一次测速结果；从未测过返回 null */
    public static SourceResult cachedFor(String name) {
        if (name == null) return null;
        for (SourceResult r : lastResults) {
            if (r.name.equalsIgnoreCase(name)) return r;
        }
        return null;
    }

    /** 缓存是否仍在有效期内 */
    public static boolean isCacheFresh() {
        return System.currentTimeMillis() - lastTestAt < CACHE_TTL_MS;
    }

    /**
     * 测速单个源（阻塞，请勿在 FX 线程调用）。
     *
     * @return 结果；{@code ok=false} 表示不可达或服务异常
     */
    public static SourceResult ping(String name, String url) {
        long t0 = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(6))
                    .header("User-Agent", AppConfig.USER_AGENT)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> response = CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
            int ms = (int) ((System.nanoTime() - t0) / 1_000_000);
            int code = response.statusCode();
            // 拿到任何 HTTP 响应都说明网络可达；仅 5xx 视为服务端异常
            return new SourceResult(name, url, code < 500, ms);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new SourceResult(name, url, false, -1);
        }
    }

    /** 测速全部候选源（阻塞，内部并发）。结果写入缓存并按源名顺序返回。 */
    public static List<SourceResult> testAll() {
        List<Future<SourceResult>> futures = new ArrayList<>(SOURCES.length);
        for (String[] s : SOURCES) {
            futures.add(POOL.submit(() -> ping(s[0], s[1])));
        }
        List<SourceResult> results = new ArrayList<>(SOURCES.length);
        for (Future<SourceResult> f : futures) {
            try {
                results.add(f.get());
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }
        lastResults = Collections.unmodifiableList(results);
        lastTestAt = System.currentTimeMillis();
        return lastResults;
    }

    /** 异步测速全部源；Future 完成于后台线程 */
    public static CompletableFuture<List<SourceResult>> testAllAsync() {
        return CompletableFuture.supplyAsync(NetworkStatusService::testAll, POOL);
    }

    /**
     * 异步测速当前下载源；缓存新鲜时直接返回缓存（不重复打网络）。
     * Future 完成于后台线程；失败时结果为 null（理论上不会发生，ping 内部已兜底）。
     */
    public static CompletableFuture<SourceResult> testCurrentSourceAsync() {
        String current = currentSourceName();
        SourceResult cached = cachedFor(current);
        if (cached != null && isCacheFresh()) {
            return CompletableFuture.completedFuture(cached);
        }
        String[] target = null;
        for (String[] s : SOURCES) {
            if (s[0].equalsIgnoreCase(current)) {
                target = s;
                break;
            }
        }
        if (target == null) {
            // 未知源名（配置被改过）：用 BMCLAPI 探针避免空白
            target = SOURCES[0];
        }
        final String[] t = target;
        return CompletableFuture.supplyAsync(() -> {
            SourceResult r = ping(t[0], t[1]);
            // 合并进缓存（保留旧结果，替换同名项）
            List<SourceResult> merged = new ArrayList<>();
            boolean replaced = false;
            for (SourceResult old : lastResults) {
                if (old.name.equalsIgnoreCase(r.name)) {
                    merged.add(r);
                    replaced = true;
                } else {
                    merged.add(old);
                }
            }
            if (!replaced) merged.add(r);
            lastResults = Collections.unmodifiableList(merged);
            lastTestAt = System.currentTimeMillis();
            return r;
        }, POOL);
    }
}
