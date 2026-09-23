package com.example.starlight.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Authlib-Injector 第三方认证服务器（皮肤站）元数据发现。
 * <p>
 * 参考 HMCL {@code org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorServer}：
 * <ol>
 *   <li>自动补充协议（缺省 {@code https://}）；</li>
 *   <li>GET 服务器地址，读取 {@code x-authlib-injector-api-location} 响应头定位真实 API 地址；</li>
 *   <li>拉取并解析 metadata（{@code meta.serverName} / {@code meta.links} / {@code meta.feature.non_email_login}）。</li>
 * </ol>
 * 这样用户既可以填皮肤站根地址（如 {@code https://littleskin.cn}），也可以直接填 API 地址。
 */
public class AuthlibInjectorServer {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final String url;              // 规范化后的 API 地址（以 / 结尾）
    private final String name;             // meta.serverName，可空
    private final Map<String, String> links;       // meta.links（注册/主页等）
    private final boolean nonEmailLogin;   // meta.feature.non_email_login

    private AuthlibInjectorServer(String url, JsonObject metadata) {
        this.url = url;
        JsonObject meta = (metadata != null && metadata.has("meta") && metadata.get("meta").isJsonObject())
                ? metadata.getAsJsonObject("meta") : null;

        this.name = (meta != null && meta.has("serverName") && meta.get("serverName").isJsonPrimitive())
                ? meta.get("serverName").getAsString() : null;

        Map<String, String> linksMap = new LinkedHashMap<>();
        if (meta != null && meta.has("links") && meta.get("links").isJsonObject()) {
            JsonObject linksObj = meta.getAsJsonObject("links");
            linksObj.entrySet().forEach(e -> {
                if (e.getValue() != null && e.getValue().isJsonPrimitive()) {
                    linksMap.put(e.getKey(), e.getValue().getAsString());
                }
            });
        }
        this.links = Collections.unmodifiableMap(linksMap);

        this.nonEmailLogin = meta != null && meta.has("feature.non_email_login")
                && meta.get("feature.non_email_login").isJsonPrimitive()
                && meta.get("feature.non_email_login").getAsBoolean();
    }

    /**
     * 定位并规范化认证服务器地址。
     *
     * @param url 用户输入的服务器地址（皮肤站根地址或 API 地址均可）
     * @return 定位结果
     * @throws IOException 网络错误、响应非 Authlib-Injector metadata 时抛出
     */
    public static AuthlibInjectorServer locateServer(String url) throws IOException {
        try {
            if (url == null || url.isBlank()) {
                throw new IOException("认证服务器地址不能为空");
            }
            String input = url.trim();
            if (!input.startsWith("http://") && !input.startsWith("https://")) {
                input = "https://" + input;
            }

            HttpResponse<String> resp = get(input);
            String apiLocation = resp.headers().firstValue("x-authlib-injector-api-location").orElse(null);
            String current = resp.uri().toString();

            // 皮肤站根地址通常返回该响应头指向真实 API 地址（参考 HMCL locateServer）
            if (apiLocation != null && !urlEqualsIgnoreSlash(current, apiLocation)) {
                String absolute = resolve(apiLocation, current);
                resp = get(absolute);
                current = resp.uri().toString();
            }
            if (!current.endsWith("/")) current += "/";

            JsonObject metadata;
            try {
                metadata = JsonParser.parseString(resp.body()).getAsJsonObject();
            } catch (JsonSyntaxException | IllegalStateException e) {
                String body = resp.body();
                String snippet = body == null ? "" : (body.length() > 200 ? body.substring(0, 200) + "..." : body);
                throw new IOException("服务器响应不是有效的 Authlib-Injector metadata JSON: " + snippet);
            }
            return new AuthlibInjectorServer(current, metadata);
        } catch (IOException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("定位认证服务器被中断", e);
        } catch (IllegalArgumentException e) {
            throw new IOException("无效的服务器地址: " + url, e);
        }
    }

    /** 容错版本：定位失败返回 null，调用方可回退到用户输入的原地址直接认证 */
    public static AuthlibInjectorServer locateServerOrNull(String url) {
        try {
            return locateServer(url);
        } catch (Exception e) {
            return null;
        }
    }

    /** 规范化后的 API 地址（以 / 结尾），用于 /authserver/authenticate 与 javaagent 注入 */
    public String getUrl() {
        return url;
    }

    /** 皮肤站显示名（meta.serverName），未知时返回 null */
    public String getName() {
        return name;
    }

    public Map<String, String> getLinks() {
        return links;
    }

    public boolean isNonEmailLogin() {
        return nonEmailLogin;
    }

    @Override
    public String toString() {
        return (name != null ? name + " (" + url + ")" : url);
    }

    private static HttpResponse<String> get(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json, text/plain, */*")
                .header("User-Agent", "StarlightLauncher/2.0.0")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() >= 400) {
            throw new IOException("服务器返回 HTTP " + resp.statusCode() + " (GET " + url + ")");
        }
        return resp;
    }

    private static String resolve(String location, String base) {
        try {
            return URI.create(base).resolve(location).toString();
        } catch (IllegalArgumentException e) {
            return location;
        }
    }

    private static boolean urlEqualsIgnoreSlash(String a, String b) {
        if (!a.endsWith("/")) a += "/";
        if (!b.endsWith("/")) b += "/";
        return a.equals(b);
    }
}
