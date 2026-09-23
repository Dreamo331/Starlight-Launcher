package com.example.starlight.loginmicrosoft;

import com.example.starlight.config.Endpoints;
import com.example.starlight.util.CredentialStore;
import com.example.starlight.util.DebugLog;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class MinecraftAuthLauncherDeviceCode {
    private static final Logger log = LoggerFactory.getLogger(MinecraftAuthLauncherDeviceCode.class);

    public static final String CLIENT_ID = Endpoints.microsoftClientId();

    private static final String DEVICE_CODE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    private static final String TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    private static final String XBOX_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_AUTH_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_AUTH_URL = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";
    private String accessToken = null;
    private String refreshToken = null;
    private String uuid = null;
    private String username = null;

    public static void main(String[] args) {
        try {
            MinecraftAuthLauncherDeviceCode launcher = new MinecraftAuthLauncherDeviceCode();
            // 通过命令行参数决定是全新登录还是刷新令牌
            if (args.length > 0 && "--refresh".equals(args[0])) {
                launcher.refreshAndSave();
            } else {
                launcher.startAuth();
            }
        } catch (Exception e) {
            log.error("Operation failed: {}", e.getMessage());
        }
    }

    /**
     * 启动设备代码授权流程
     */
    public void startAuth() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        // 1. 获取设备代码
        log.info("Requesting device code...");
        DeviceCodeInfo deviceCode = getDeviceCode(client);

        copyToClipboard(deviceCode.userCode);

        log.info("Please visit in browser: {}", deviceCode.verificationUri);
        log.info("And enter the device code: {}", deviceCode.userCode);

        // 自动打开浏览器
        openBrowser(deviceCode.verificationUri);

        // 2. 轮询获取 Microsoft 令牌
        log.info("Waiting for authorization (complete login in browser)...");
        MsTokenInfo msInfo = pollForToken(client, deviceCode.deviceCode, deviceCode.interval);

        if (msInfo == null || msInfo.accessToken == null) {
            log.error("Authorization timed out or failed");
            return;
        }

        String msToken = msInfo.accessToken;
        this.refreshToken = msInfo.refreshToken;

        log.info("Microsoft authorization succeeded!\n");

        // 3. 完成 Minecraft 认证并保存
        completeMinecraftAuth(client, msToken);
        saveCredentials(this.accessToken, this.uuid, this.username, this.refreshToken);
    }

    /**
     * 从本地保存的 refresh_token 刷新，并更新凭证
     */
    public void refreshAndSave() throws Exception {
        Path credPath = Paths.get(System.getProperty("user.home"), ".starlight-launcher", "login.json");
        if (!Files.exists(credPath)) {
            log.warn("No saved login info found, please run full login first (run without arguments).");
            return;
        }

        // 读取 refreshToken
        String jsonContent = Files.readString(credPath, StandardCharsets.UTF_8);
        JsonObject json = JsonParser.parseString(jsonContent).getAsJsonObject();
        String oldRefreshToken = json.has("refreshToken") && !json.get("refreshToken").isJsonNull()
                ? json.get("refreshToken").getAsString() : null;
        if (oldRefreshToken == null || oldRefreshToken.isEmpty()) {
            log.warn("Saved refresh_token is invalid, please log in again.");
            return;
        }

        log.info("Silently refreshing with refresh_token...");
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        MsTokenInfo msInfo = refreshMicrosoftToken(client, oldRefreshToken);
        log.info("Microsoft token refreshed!");

        completeMinecraftAuth(client, msInfo.accessToken);
        this.refreshToken = msInfo.refreshToken;

        saveCredentials(this.accessToken, this.uuid, this.username, this.refreshToken);
        log.info("Credentials updated.");
    }

    /**
     * 完成 Xbox -> XSTS -> Minecraft 认证链，并获取档案
     */
    private void completeMinecraftAuth(HttpClient client, String msToken) throws Exception {
        log.info("Authenticating with Xbox Live...");
        String xblToken = xboxAuth(client, msToken);

        log.info("Authenticating with XSTS...");
        String[] xstsData = xstsAuth(client, xblToken);
        String xstsToken = xstsData[0];
        String uhs = xstsData[1];

        log.info("Authenticating with Minecraft...");
        this.accessToken = minecraftAuth(client, xstsToken, uhs);

        log.info("Fetching user profile...");
        getMinecraftProfile(client, this.accessToken);

        log.info("=== Login successful === User: {}", this.username);
    }

    /**
     * 获取设备代码
     */
    private DeviceCodeInfo getDeviceCode(HttpClient client) throws Exception {
        String body = "client_id=" + CLIENT_ID +
                "&scope=XboxLive.signin%20offline_access";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DEVICE_CODE_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        DebugLog.http(true, "POST", DEVICE_CODE_URL, 0, body.length(), body, -1);
        DebugLog.http(false, null, DEVICE_CODE_URL, response.statusCode(), response.body().length(), response.body(), -1);

        if (response.statusCode() != 200) {
            throw new IOException("Failed to get device code: " + response.body());
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        DeviceCodeInfo info = new DeviceCodeInfo();
        info.deviceCode = getJsonStr(json, "device_code");
        info.userCode = getJsonStr(json, "user_code");
        info.verificationUri = getJsonStr(json, "verification_uri");
        String intervalStr = getJsonStr(json, "interval");
        info.interval = intervalStr != null && !intervalStr.isEmpty() ? Integer.parseInt(intervalStr) : 5;
        String expiresStr = getJsonStr(json, "expires_in");
        info.expiresIn = expiresStr != null && !expiresStr.isEmpty() ? Integer.parseInt(expiresStr) : 900;

        return info;
    }

    /**
     * 轮询获取 Microsoft 访问令牌和刷新令牌
     */
    private MsTokenInfo pollForToken(HttpClient client, String deviceCode, int interval) throws Exception {
        String body = "client_id=" + CLIENT_ID +
                "&device_code=" + deviceCode +
                "&grant_type=urn:ietf:params:oauth:grant-type:device_code";

        long startTime = System.currentTimeMillis();
        long maxWait = 900_000; // 15 分钟

        while (System.currentTimeMillis() - startTime < maxWait) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TOKEN_URL))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            DebugLog.http(true, "POST", TOKEN_URL, 0, body.length(), body, -1);
            DebugLog.http(false, null, TOKEN_URL, response.statusCode(), response.body().length(), response.body(), -1);

            if (response.statusCode() == 200) {
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                MsTokenInfo info = new MsTokenInfo();
                info.accessToken = getJsonStr(json, "access_token");
                info.refreshToken = getJsonStr(json, "refresh_token");
                long expiresIn = json.has("expires_in") && !json.get("expires_in").isJsonNull()
                        ? json.get("expires_in").getAsLong() : 3600;
                info.expiresAt = System.currentTimeMillis() / 1000 + expiresIn;
                return info;
            }

            String error = null;
            try {
                JsonObject errJson = JsonParser.parseString(response.body()).getAsJsonObject();
                error = getJsonStr(errJson, "error");
            } catch (Exception ignored) {}

            if (null == error) {
                log.warn("Polling error: {} - {}", error, response.body());
                return null;
            } else switch (error) {
                case "authorization_pending" -> {
                    log.debug(".");
                    sleepUninterruptibly(interval * 1000L);
                }
                case "slow_down" -> {
                    interval += 5;
                    sleepUninterruptibly(interval * 1000L);
                }
                default -> {
                    log.warn("Polling error: {} - {}", error, response.body());
                    return null;
                }
            }
        }

        return null;
    }

    /**
     * 使用刷新令牌获取新的 Microsoft 令牌
     */
    private MsTokenInfo refreshMicrosoftToken(HttpClient client, String oldRefreshToken) throws Exception {
        String body = "client_id=" + CLIENT_ID +
                "&grant_type=refresh_token" +
                "&refresh_token=" + URLEncoder.encode(oldRefreshToken, StandardCharsets.UTF_8) +
                "&scope=XboxLive.signin%20offline_access";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        DebugLog.http(true, "POST", TOKEN_URL, 0, body.length(), body, -1);
        DebugLog.http(false, null, TOKEN_URL, response.statusCode(), response.body().length(), response.body(), -1);

        if (response.statusCode() != 200) {
            throw new IOException("Failed to refresh Microsoft Token: " + response.body());
        }

        MsTokenInfo info = new MsTokenInfo();
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        info.accessToken = getJsonStr(json, "access_token");
        info.refreshToken = getJsonStr(json, "refresh_token");
        long expiresIn = json.has("expires_in") && !json.get("expires_in").isJsonNull()
                ? json.get("expires_in").getAsLong() : 3600;
        info.expiresAt = System.currentTimeMillis() / 1000 + expiresIn;

        // 如果响应中没有新的 refresh_token，沿用旧值
        if (info.refreshToken == null) {
            info.refreshToken = oldRefreshToken;
        }

        return info;
    }

    /**
     * 安全睡眠，捕获中断并恢复状态
     */
    private void sleepUninterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Wait interrupted");
        }
    }

    // ---------- 原有认证方法 (Xbox, XSTS, Minecraft, Profile) ----------
    private String xboxAuth(HttpClient client, String msToken) throws Exception {
        String json = "{" +
                "\"Properties\":{\"AuthMethod\":\"RPS\",\"SiteName\":\"user.auth.xboxlive.com\",\"RpsTicket\":\"d=" + msToken + "\"}," +
                "\"RelyingParty\":\"http://auth.xboxlive.com\"," +
                "\"TokenType\":\"JWT\"" +
                "}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(XBOX_AUTH_URL))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        DebugLog.http(true, "POST", XBOX_AUTH_URL, 0, json.length(), json, -1);
        DebugLog.http(false, null, XBOX_AUTH_URL, response.statusCode(), response.body().length(), response.body(), -1);

        if (response.statusCode() != 200) {
            throw new IOException("Xbox authentication failed: " + response.body());
        }

        return getJsonStr(JsonParser.parseString(response.body()).getAsJsonObject(), "Token");
    }

    private String[] xstsAuth(HttpClient client, String xblToken) throws Exception {
        String json = "{" +
                "\"Properties\":{\"SandboxId\":\"RETAIL\",\"UserTokens\":[\"" + xblToken + "\"]}," +
                "\"RelyingParty\":\"rp://api.minecraftservices.com/\"," +
                "\"TokenType\":\"JWT\"" +
                "}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(XSTS_AUTH_URL))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        DebugLog.http(true, "POST", XSTS_AUTH_URL, 0, json.length(), json, -1);
        DebugLog.http(false, null, XSTS_AUTH_URL, response.statusCode(), response.body().length(), response.body(), -1);

        if (response.statusCode() != 200) {
            throw new IOException("XSTS authentication failed: " + response.body());
        }

        JsonObject respJson = JsonParser.parseString(response.body()).getAsJsonObject();
        String token = getJsonStr(respJson, "Token");
        String uhs = getJsonStr(respJson.getAsJsonObject("DisplayClaims")
                .getAsJsonArray("xui").get(0).getAsJsonObject(), "uhs");
        return new String[]{token, uhs};
    }

    private String minecraftAuth(HttpClient client, String xstsToken, String uhs) throws Exception {
        String json = "{" +
                "\"identityToken\":\"XBL3.0 x=" + uhs + ";" + xstsToken + "\"" +
                "}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(MC_AUTH_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        DebugLog.http(true, "POST", MC_AUTH_URL, 0, json.length(), json, -1);
        DebugLog.http(false, null, MC_AUTH_URL, response.statusCode(), response.body().length(), response.body(), -1);

        if (response.statusCode() != 200) {
            throw new IOException("Minecraft authentication failed: " + response.body());
        }

        return getJsonStr(JsonParser.parseString(response.body()).getAsJsonObject(), "access_token");
    }

    private void getMinecraftProfile(HttpClient client, String mcToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(MC_PROFILE_URL))
                .header("Authorization", "Bearer " + mcToken)
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        DebugLog.http(true, "GET", MC_PROFILE_URL, 0, 0, null, -1);
        DebugLog.http(false, null, MC_PROFILE_URL, response.statusCode(), response.body().length(), response.body(), -1);

        if (response.statusCode() != 200) {
            throw new IOException("Failed to get Minecraft profile: " + response.body());
        }

        JsonObject profileJson = JsonParser.parseString(response.body()).getAsJsonObject();
        this.uuid = getJsonStr(profileJson, "id");
        this.username = getJsonStr(profileJson, "name");
    }

    // ---------- 浏览器 & JSON 解析 ----------

    /**
     * 打开系统默认浏览器访问登录页（多级降级，避免部分设备自动打开失败）：
     * Windows 优先 rundll32（不经过 cmd 命令行解析，兼容性最好）→
     * cmd start（URL 加引号防止 & 等特殊字符被二次解析）→ java.awt.Desktop 兜底。
     * @return true 表示已成功调起浏览器；false 表示全部方式失败，调用方应提示用户手动访问
     */
    public static boolean openBrowser(String url) {
        if (url == null || url.isEmpty()) return false;
        String os = System.getProperty("os.name").toLowerCase();
        boolean windows = os.contains("win");

        // 1) Windows: rundll32 url.dll,FileProtocolHandler —— 最底层、不依赖 cmd 命令行解析
        if (windows) {
            try {
                new ProcessBuilder("rundll32.exe", "url.dll,FileProtocolHandler", url).start();
                log.info("Opened login page in default browser (rundll32): {}", url);
                return true;
            } catch (Throwable e) {
                log.warn("rundll32 failed to open browser: {}", e.getMessage());
            }
        }

        // 2) 平台默认打开命令；Windows 下 URL 加引号，防止 & 等特殊字符被 cmd 二次解析
        try {
            ProcessBuilder pb;
            if (windows) {
                pb = new ProcessBuilder("cmd", "/c", "start", "", "\"" + url + "\"");
            } else if (os.contains("mac")) {
                pb = new ProcessBuilder("open", url);
            } else {
                pb = new ProcessBuilder("xdg-open", url);
            }
            pb.start();
            log.info("Opened login page in default browser (start command): {}", url);
            return true;
        } catch (Throwable e) {
            log.warn("start command failed to open browser: {}", e.getMessage());
        }

        // 3) java.awt.Desktop 兜底
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                log.info("Opened login page in default browser (java.awt.Desktop): {}", url);
                return true;
            }
        } catch (Throwable e) {
            log.warn("java.awt.Desktop failed to open browser: {}", e.getMessage());
        }

        log.warn("Cannot open browser automatically, please visit manually: {}", url);
        return false;
    }

    private static void copyToClipboard(String text) {
        try {
            StringSelection selection = new StringSelection(text);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
            log.info("Device code copied to clipboard automatically");
        } catch (HeadlessException e) {
            log.warn("Cannot copy to clipboard, please enter manually: {}", text);
        }
    }

    private void saveCredentials(String accessToken, String uuid, String userName, String refreshToken) {
        // 使用安全凭证存储
        CredentialStore.saveCredentials(accessToken, refreshToken, uuid, userName);

        // 同时保留旧的 login.json 用于兼容
        Path userConfigPath = Paths.get(System.getProperty("user.home"), ".starlight-launcher", "login.json");
        saveJson(userConfigPath, accessToken, uuid, userName, refreshToken);
    }

    private void saveJson(Path jsonPath, String accessToken, String uuid, String userName, String refreshToken) {
        try {
            Path parent = jsonPath.getParent();
            if (parent != null && !Files.exists(parent)) Files.createDirectories(parent);
            String json = String.format(
                    "{%n" +
                    "  \"accessToken\": \"%s\",%n" +
                    "  \"refreshToken\": \"%s\",%n" +
                    "  \"uuid\": \"%s\",%n" +
                    "  \"username\": \"%s\"%n" +
                    "}",
                    escapeJson(accessToken),
                    escapeJson(refreshToken),
                    escapeJson(uuid),
                    escapeJson(userName)
            );
            Files.writeString(jsonPath, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("Login info saved to {}", jsonPath.toAbsolutePath());
        } catch (IOException e) {
            log.warn("Failed to save JSON to {}: {}", jsonPath, e.getMessage());
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 使用 Gson 从 JsonObject 中安全提取字符串值 */
    private static String getJsonStr(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : null;
    }

    private String extractJsonValue(String json, String path) {
        // 已废弃，保留仅用于编译兼容
        return null;
    }

    // ================================================================
    //  静默刷新（纯内存操作，不依赖文件）
    // ================================================================

    /** 静默刷新结果 */
    public static class RefreshResult {
        public final String accessToken;
        public final String refreshToken;
        public final String uuid;
        public final String username;

        public RefreshResult(String accessToken, String refreshToken, String uuid, String username) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.uuid = uuid;
            this.username = username;
        }
    }

    /**
     * 静默刷新令牌 — 不依赖文件，纯内存操作。
     * 检测到 AccessToken 过期后调用此方法，用 refreshToken 换取新令牌。
     */
    public static RefreshResult silentRefresh(String currentRefreshToken) throws Exception {
        // 注意：本 JDK 的 HttpClient.Builder 无 readTimeout（被精简发行版移除），
        // 必须给每个 HttpRequest 设置 timeout，否则网络响应迟迟不来时 send() 会无限期阻塞
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        // 1. OAuth 刷新 — 获取新的 Microsoft access_token + refresh_token
        String tokenBody = "client_id=" + CLIENT_ID +
                "&grant_type=refresh_token" +
                "&refresh_token=" + URLEncoder.encode(currentRefreshToken, StandardCharsets.UTF_8) +
                "&scope=XboxLive.signin%20offline_access";

        HttpRequest tokenReq = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(tokenBody))
                .build();

        HttpResponse<String> tokenResp = client.send(tokenReq, HttpResponse.BodyHandlers.ofString());
        DebugLog.http(true, "POST", TOKEN_URL, 0, tokenBody.length(), tokenBody, -1);
        DebugLog.http(false, null, TOKEN_URL, tokenResp.statusCode(), tokenResp.body().length(), tokenResp.body(), -1);
        if (tokenResp.statusCode() != 200) {
            throw new IOException("Failed to refresh Microsoft Token: " + tokenResp.body());
        }

        JsonObject tokenJson = JsonParser.parseString(tokenResp.body()).getAsJsonObject();
        String msAccessToken = getJsonStr(tokenJson, "access_token");
        String newRefreshToken = getJsonStr(tokenJson, "refresh_token");
        if (newRefreshToken == null) newRefreshToken = currentRefreshToken;

        // 2. Xbox Live 认证
        String xblBody = "{" +
                "\"Properties\":{\"AuthMethod\":\"RPS\",\"SiteName\":\"user.auth.xboxlive.com\",\"RpsTicket\":\"d=" + msAccessToken + "\"}," +
                "\"RelyingParty\":\"http://auth.xboxlive.com\"," +
                "\"TokenType\":\"JWT\"" +
                "}";
        HttpRequest xblReq = HttpRequest.newBuilder()
                .uri(URI.create(XBOX_AUTH_URL))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(xblBody))
                .build();
        HttpResponse<String> xblResp = client.send(xblReq, HttpResponse.BodyHandlers.ofString());
        DebugLog.http(true, "POST", XBOX_AUTH_URL, 0, xblBody.length(), xblBody, -1);
        DebugLog.http(false, null, XBOX_AUTH_URL, xblResp.statusCode(), xblResp.body().length(), xblResp.body(), -1);
        if (xblResp.statusCode() != 200) throw new IOException("Xbox authentication failed: " + xblResp.body());
        String xblToken = getJsonStr(JsonParser.parseString(xblResp.body()).getAsJsonObject(), "Token");

        // 3. XSTS 认证
        String xstsBody = "{" +
                "\"Properties\":{\"SandboxId\":\"RETAIL\",\"UserTokens\":[\"" + xblToken + "\"]}," +
                "\"RelyingParty\":\"rp://api.minecraftservices.com/\"," +
                "\"TokenType\":\"JWT\"" +
                "}";
        HttpRequest xstsReq = HttpRequest.newBuilder()
                .uri(URI.create(XSTS_AUTH_URL))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(xstsBody))
                .build();
        HttpResponse<String> xstsResp = client.send(xstsReq, HttpResponse.BodyHandlers.ofString());
        DebugLog.http(true, "POST", XSTS_AUTH_URL, 0, xstsBody.length(), xstsBody, -1);
        DebugLog.http(false, null, XSTS_AUTH_URL, xstsResp.statusCode(), xstsResp.body().length(), xstsResp.body(), -1);
        if (xstsResp.statusCode() != 200) throw new IOException("XSTS authentication failed: " + xstsResp.body());

        JsonObject xstsJson = JsonParser.parseString(xstsResp.body()).getAsJsonObject();
        String xstsToken = getJsonStr(xstsJson, "Token");
        String uhs = getJsonStr(
                xstsJson.getAsJsonObject("DisplayClaims")
                        .getAsJsonArray("xui").get(0).getAsJsonObject(), "uhs");

        // 4. Minecraft 认证
        String mcBody = "{\"identityToken\":\"XBL3.0 x=" + uhs + ";" + xstsToken + "\"}";
        HttpRequest mcReq = HttpRequest.newBuilder()
                .uri(URI.create(MC_AUTH_URL))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(mcBody))
                .build();
        HttpResponse<String> mcResp = client.send(mcReq, HttpResponse.BodyHandlers.ofString());
        DebugLog.http(true, "POST", MC_AUTH_URL, 0, mcBody.length(), mcBody, -1);
        DebugLog.http(false, null, MC_AUTH_URL, mcResp.statusCode(), mcResp.body().length(), mcResp.body(), -1);
        if (mcResp.statusCode() != 200) throw new IOException("Minecraft authentication failed: " + mcResp.body());
        String mcAccessToken = getJsonStr(JsonParser.parseString(mcResp.body()).getAsJsonObject(), "access_token");

        // 5. 获取 Minecraft 档案
        String uuid = null, username = null;
        try {
            HttpRequest profileReq = HttpRequest.newBuilder()
                    .uri(URI.create(MC_PROFILE_URL))
                    .header("Authorization", "Bearer " + mcAccessToken)
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<String> profileResp = client.send(profileReq, HttpResponse.BodyHandlers.ofString());
            DebugLog.http(true, "GET", MC_PROFILE_URL, 0, 0, null, -1);
            DebugLog.http(false, null, MC_PROFILE_URL, profileResp.statusCode(), profileResp.body().length(), profileResp.body(), -1);
            if (profileResp.statusCode() == 200) {
                JsonObject profileJson = JsonParser.parseString(profileResp.body()).getAsJsonObject();
                uuid = getJsonStr(profileJson, "id");
                username = getJsonStr(profileJson, "name");
            }
        } catch (Exception ignored) { /* 非致命 */ }

        // 6. 保存到独立凭证存储
        CredentialStore.saveCredentials(mcAccessToken, newRefreshToken, uuid, username);

        return new RefreshResult(mcAccessToken, newRefreshToken, uuid, username);
    }

    /**
     * 启动设备码流程（仅获取设备码、复制到剪贴板、自动打开浏览器），不阻塞
     * @return DeviceCodeInfo 包含 deviceCode / userCode / verificationUri
     */
    public static DeviceCodeInfo startDeviceCodeFlow() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        DeviceCodeInfo info = getDeviceCodeStatic(client);
        copyToClipboard(info.userCode);
        info.browserOpened = openBrowser(info.verificationUri);
        log.info("Device code obtained: {}  Visit: {}  (browser opened: {})",
                info.userCode, info.verificationUri, info.browserOpened);
        return info;
    }

    /**
     * 从设备码继续——轮询 + 完成 Minecraft 认证，结果写 login.json
     */
    public void continueAuth(DeviceCodeInfo info) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        log.info("Waiting for authorization (complete login in browser)...");
        MsTokenInfo msInfo = pollForToken(client, info.deviceCode, info.interval);
        if (msInfo == null || msInfo.accessToken == null) {
            throw new IOException("Authorization timed out or failed");
        }
        this.refreshToken = msInfo.refreshToken;
        completeMinecraftAuth(client, msInfo.accessToken);
        saveCredentials(this.accessToken, this.uuid, this.username, this.refreshToken);
    }

    private static DeviceCodeInfo getDeviceCodeStatic(HttpClient client) throws Exception {
        String body = "client_id=" + CLIENT_ID +
                "&scope=XboxLive.signin%20offline_access";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DEVICE_CODE_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        DebugLog.http(true, "POST", DEVICE_CODE_URL, 0, body.length(), body, -1);
        DebugLog.http(false, null, DEVICE_CODE_URL, response.statusCode(), response.body().length(), response.body(), -1);
        if (response.statusCode() != 200) {
            throw new IOException("Failed to get device code: " + response.body());
        }
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        DeviceCodeInfo dci = new DeviceCodeInfo();
        dci.deviceCode = getJsonStr(json, "device_code");
        dci.userCode = getJsonStr(json, "user_code");
        dci.verificationUri = getJsonStr(json, "verification_uri");
        String intervalStr = getJsonStr(json, "interval");
        dci.interval = intervalStr != null && !intervalStr.isEmpty() ? Integer.parseInt(intervalStr) : 5;
        String expiresStr = getJsonStr(json, "expires_in");
        dci.expiresIn = expiresStr != null && !expiresStr.isEmpty() ? Integer.parseInt(expiresStr) : 900;
        return dci;
    }

    // ================================================================
    //  内部数据类
    // ================================================================

    /** 设备代码响应信息 */
    public static class DeviceCodeInfo {
        public String deviceCode;
        public String userCode;
        public String verificationUri;
        public int interval;
        public int expiresIn;
        /** 是否已成功调起系统默认浏览器（false 时 UI 应提示用户手动访问） */
        public boolean browserOpened;
    }

    /** Microsoft 令牌响应信息 */
    static class MsTokenInfo {
        String accessToken;
        String refreshToken;
        long expiresAt; // 过期时间戳（Unix秒），从 expires_in 计算
    }
}