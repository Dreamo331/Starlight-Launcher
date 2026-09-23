package com.example.starlight.auth;

import com.example.starlight.util.DebugLog;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;

/**
 * Authlib-injector based external login for third-party Yggdrasil auth servers
 * such as LittleSkin, Blessing Skin, etc.
 * <p>
 * Uses Java's built-in {@link HttpClient} to POST to the auth server's
 * {@code /authserver/authenticate} endpoint and persists the result to
 * a JSON file at {@code $HOME/.starlight-launcher/external_login.json}.
 */
public class ExternalLoginAuth {

    private static final String CONFIG_DIR = System.getProperty("user.home")
            + "/.starlight-launcher";
    private static final String AUTH_FILE = CONFIG_DIR + "/external_login.json";

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Authenticate against the given external Yggdrasil auth server.
     *
     * @param authServer base URL of the auth server
     *                   (e.g. {@code https://littleskin.cn/api/yggdrasil})
     *                   The method appends {@code /authserver/authenticate}.
     * @param email      email or username for the account
     * @param password   password for the account
     * @return an {@link AuthResult} with profile data on success, or an error
     *         description on failure
     */
    public static AuthResult login(String authServer, String email, String password) {
        if (authServer == null || authServer.isBlank()) {
            return AuthResult.failure("Auth server URL must not be empty.");
        }
        if (email == null || email.isBlank()) {
            return AuthResult.failure("Email/username must not be empty.");
        }
        if (password == null || password.isBlank()) {
            return AuthResult.failure("Password must not be empty.");
        }

        // Normalise the auth server URL
        // 优先通过 Authlib-Injector 元数据发现规范化地址（参考 HMCL AuthlibInjectorServer.locateServer），
        // 支持用户直接填皮肤站根地址（如 https://littleskin.cn）；定位失败时回退到用户输入的原地址
        String server = null;
        AuthlibInjectorServer located = AuthlibInjectorServer.locateServerOrNull(authServer);
        if (located != null) {
            server = located.getUrl();
        }
        if (server == null) {
            server = authServer.endsWith("/")
                    ? authServer.substring(0, authServer.length() - 1)
                    : authServer;
        } else if (server.endsWith("/")) {
            server = server.substring(0, server.length() - 1);
        }

        String authenticateUrl = server + "/authserver/authenticate";

        // Build the JSON request body as specified by the Yggdrasil protocol
        String jsonBody = String.format(
                "{\"agent\":{\"name\":\"Minecraft\",\"version\":1},\"username\":\"%s\",\"password\":\"%s\"}",
                escapeJson(email),
                escapeJson(password)
        );

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(authenticateUrl))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            DebugLog.http(true, "POST", authenticateUrl, 0, jsonBody.length(), jsonBody, -1);
            DebugLog.http(false, null, authenticateUrl, response.statusCode(), response.body().length(), response.body(), -1);

            int status = response.statusCode();
            String responseBody = response.body();

            if (status != 200) {
                // 使用 Gson 解析错误信息
                try {
                    JsonObject errObj = JsonParser.parseString(responseBody).getAsJsonObject();
                    String errorMsg = errObj.has("errorMessage") ? errObj.get("errorMessage").getAsString() : null;
                    if (errorMsg == null) errorMsg = errObj.has("error") ? errObj.get("error").getAsString() : null;
                    if (errorMsg != null) {
                        return AuthResult.failure("Server returned " + status + ": " + errorMsg);
                    }
                } catch (JsonSyntaxException ignored) {}
                return AuthResult.failure("Authentication failed (HTTP " + status + ").");
            }

            // 使用 Gson 解析响应
            JsonObject root;
            try {
                root = JsonParser.parseString(responseBody).getAsJsonObject();
            } catch (JsonSyntaxException e) {
                return AuthResult.failure("Invalid JSON response: " + e.getMessage()
                        + " | body(200chars): " + (responseBody.length() > 200 ? responseBody.substring(0, 200) : responseBody));
            }

            if (!root.has("accessToken") || root.get("accessToken").isJsonNull()) {
                // 记录部分响应以便调试
                String snippet = responseBody.length() > 300 ? responseBody.substring(0, 300) + "..." : responseBody;
                return AuthResult.failure("Response did not contain an access token. Response: " + snippet);
            }
            String accessToken = root.get("accessToken").getAsString();

            // 提取 profile 信息
            String userName = null;
            String uuid = null;

            if (root.has("selectedProfile") && root.get("selectedProfile").isJsonObject()) {
                JsonObject profile = root.getAsJsonObject("selectedProfile");
                userName = profile.has("name") ? profile.get("name").getAsString() : null;
                uuid = profile.has("id") ? profile.get("id").getAsString() : null;
            }

            // 如果 selectedProfile 缺失，尝试 availableProfiles 数组
            if (userName == null && root.has("availableProfiles") && root.get("availableProfiles").isJsonArray()) {
                JsonArray profiles = root.getAsJsonArray("availableProfiles");
                if (profiles.size() > 0) {
                    JsonObject first = profiles.get(0).getAsJsonObject();
                    userName = first.has("name") ? first.get("name").getAsString() : null;
                    uuid = first.has("id") ? first.get("id").getAsString() : null;
                }
            }

            if (userName == null) userName = "Unknown";
            if (uuid == null) uuid = "";

            return new AuthResult(true, userName, uuid, accessToken, server, null);
        } catch (IOException e) {
            return AuthResult.failure("Network error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AuthResult.failure("Request was interrupted.");
        }
    }

    /**
     * Persist an {@link AuthResult} to disk so it can be reused across
     * launcher sessions.
     *
     * @param result the auth result to save (must be a successful result)
     */
    public static void saveAuthResult(AuthResult result) {
        if (result == null) return;

        // Use Gson for serialization
        try {
            Files.createDirectories(Paths.get(CONFIG_DIR));
            com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
            obj.addProperty("success", result.success);
            obj.addProperty("userName", result.userName != null ? result.userName : "");
            obj.addProperty("uuid", result.uuid != null ? result.uuid : "");
            obj.addProperty("accessToken", result.accessToken != null ? result.accessToken : "");
            obj.addProperty("authServer", result.authServer != null ? result.authServer : "");
            if (result.errorMessage != null) {
                obj.addProperty("errorMessage", result.errorMessage);
            }
            com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
            Files.writeString(Paths.get(AUTH_FILE), gson.toJson(obj), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Silently fail
        }
    }

    /**
     * Read a previously saved external auth result from disk.
     *
     * @return the stored {@link AuthResult}, or an {@link AuthResult} with
     *         {@code success=false} if no saved result is found
     */
    public static AuthResult readAuthResult() {
        Path file = Paths.get(AUTH_FILE);
        if (!Files.isRegularFile(file)) {
            return AuthResult.failure("No saved authentication found.");
        }

        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            com.google.gson.JsonObject root = JsonParser.parseString(content).getAsJsonObject();

            boolean success = root.has("success") && root.get("success").getAsBoolean();
            if (!success) {
                String error = root.has("errorMessage") ? root.get("errorMessage").getAsString() : "Saved session is invalid.";
                return AuthResult.failure(error);
            }

            String userName = root.has("userName") ? root.get("userName").getAsString() : "";
            String uuid = root.has("uuid") ? root.get("uuid").getAsString() : "";
            String accessToken = root.has("accessToken") ? root.get("accessToken").getAsString() : "";
            String authServer = root.has("authServer") ? root.get("authServer").getAsString() : "";

            return new AuthResult(true, userName, uuid, accessToken, authServer, null);
        } catch (IOException | JsonSyntaxException e) {
            return AuthResult.failure("Could not read saved authentication: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Minimal JSON helpers (no external JSON library)
    // -----------------------------------------------------------------------

    /**
     * Escape a string for inclusion in a JSON value (only the bare minimum:
     * backslash, double-quote, and control characters).
     */
    private static String escapeJson(String value) {
        if (value == null) return "";
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"'  -> sb.append("\\\"");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Extract the value of a top-level string field from a JSON snippet.
     * <p>
     * Handles {@code "field": "value"} patterns.  Does NOT handle nested
     * objects — for those use {@link #extractJsonObject(String, String)}.
     *
     * @param json  the JSON text
     * @param field the field name to look for
     * @return the string value, or {@code null} if not found
     */
    private static String extractJsonField(String json, String field) {
        if (json == null || field == null) return null;

        // Build a pattern that matches:  "field": "value"
        String search = "\"" + field + "\"\\s*:\\s*\"";
        int start = indexOfPattern(json, search);
        if (start < 0) {
            // Possibly a JSON null value:  "field": null
            String nullSearch = "\"" + field + "\"\\s*:\\s*null";
            if (indexOfPattern(json, nullSearch) >= 0) {
                return null;
            }
            return null;
        }

        start += search.length() - 1; // position at the opening quote (minus 1 because we already consumed the opening quote via search string)

        // Actually, let's recompute properly
        start = indexOfPattern(json, search);
        if (start < 0) return null;
        start += search.length(); // position after the opening quote

        StringBuilder value = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                value.append(switch (next) {
                    case '"' -> '"';
                    case '\\' -> '\\';
                    case '/' -> '/';
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case 'u' -> {
                        if (i + 5 < json.length()) {
                            String hex = json.substring(i + 2, i + 6);
                            i += 4;
                            yield (char) Integer.parseInt(hex, 16);
                        }
                        yield 'u';
                    }
                    default -> next;
                });
                i++;
            } else if (c == '"') {
                break;
            } else {
                value.append(c);
            }
        }
        return value.toString();
    }

    /**
     * Extract a JSON object value for a given field name.
     * Returns the substring from the opening {@code \{} to the matching {@code \}}.
     */
    private static String extractJsonObject(String json, String field) {
        if (json == null || field == null) return null;

        String search = "\"" + field + "\"\\s*:\\s*\\{";
        int start = indexOfPattern(json, search);
        if (start < 0) return null;

        start += search.length() - 1; // position at '{'

        int depth = 0;
        int objStart = -1;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                if (depth == 0) objStart = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objStart >= 0) {
                    return json.substring(objStart, i + 1);
                }
            }
        }
        return null;
    }

    /**
     * Extract the first element of a top-level JSON array for a field.
     * Returns the substring of the first object in the array.
     */
    private static String extractFirstArrayElement(String json, String field) {
        if (json == null || field == null) return null;

        String search = "\"" + field + "\"\\s*:\\s*\\[";
        int start = indexOfPattern(json, search);
        if (start < 0) return null;

        start += search.length() - 1; // position at '['

        // Skip whitespace
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;

        if (start >= json.length() || json.charAt(start) != '{') return null;

        int depth = 0;
        int objStart = start;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return json.substring(objStart, i + 1);
            }
        }
        return null;
    }

    /**
     * Simple case-insensitive indexOf for a regex-like literal string pattern
     * (whitespace-insensitive matching after the colon).  This is good enough
     * for simple hand-crafted Json.
     */
    private static int indexOfPattern(String json, String pattern) {
        // Normalise: collapse whitespace around colons in both json and pattern
        String normalizedJson = json.replaceAll("\\s*:\\s*", ":");
        String normalizedPattern = pattern.replaceAll("\\s*:\\s*", ":");

        return normalizedJson.indexOf(normalizedPattern);
    }

    // -----------------------------------------------------------------------
    // Data class
    // -----------------------------------------------------------------------

    /**
     * Holds the result of an external Yggdrasil authentication attempt.
     */
    public static class AuthResult {
        public final boolean success;
        public final String userName;
        public final String uuid;
        public final String accessToken;
        public final String authServer;
        public final String errorMessage;

        public AuthResult(boolean success, String userName, String uuid,
                          String accessToken, String authServer, String errorMessage) {
            this.success = success;
            this.userName = userName;
            this.uuid = uuid;
            this.accessToken = accessToken;
            this.authServer = authServer;
            this.errorMessage = errorMessage;
        }

        public static AuthResult failure(String errorMessage) {
            return new AuthResult(false, null, null, null, null, errorMessage);
        }

        @Override
        public String toString() {
            if (success) {
                return "AuthResult{success=true, userName='" + userName + "', authServer='" + authServer + "'}";
            }
            return "AuthResult{success=false, errorMessage='" + errorMessage + "'}";
        }
    }
}
