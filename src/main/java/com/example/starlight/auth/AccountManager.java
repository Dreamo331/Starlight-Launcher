package com.example.starlight.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.example.starlight.auth.offline.Skin;
import com.example.starlight.loginmicrosoft.MinecraftAuthLauncherDeviceCode;
import com.example.starlight.util.CredentialStore;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * 多账号管理类
 */
public class AccountManager {

    // 常量定义

    private static final String CONFIG_DIR = System.getProperty("user.home")
            + "/.starlight-launcher";
    private static final String ACCOUNTS_FILE = CONFIG_DIR + "/accounts.json";
    private static final String OLD_LOGIN_FILE = CONFIG_DIR + "/login.json";
    private static final String OLD_EXTERNAL_FILE = CONFIG_DIR + "/external_login.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    //  账号数据结构配置
    public enum AccountType {
        // 离线账号
        OFFLINE,
        // 微软正版账号
        MICROSOFT,
        // 第三方
        THIRD_PARTY
    }

    //账号信息
    public static class Account {
        public final String id;            // UUID
        public final String name;          // 玩家名
        public final AccountType type;     // 账号类型
        public final String accessToken;   // 访问令牌
        public final String refreshToken;  // 刷新令牌
        public final String authServer;    // 认证服务器地址
        public final long lastUsed;        // 上次使用时间戳
        public final long tokenExpiresAt;  // accessToken 过期时间戳（Unix秒，0=未知）
        private String skinJson;           // 皮肤配置JSON

        public Account(String id, String name, AccountType type,
                       String accessToken, String refreshToken,
                       String authServer, long lastUsed) {
            this(id, name, type, accessToken, refreshToken, authServer, lastUsed, 0);
        }

        public Account(String id, String name, AccountType type,
                       String accessToken, String refreshToken,
                       String authServer, long lastUsed, long tokenExpiresAt) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.authServer = authServer;
            this.lastUsed = lastUsed;
            this.tokenExpiresAt = tokenExpiresAt;
            this.skinJson = null;
        }

        public Skin getSkin() {
            if (skinJson != null && !skinJson.isEmpty()) {
                try {
                    JsonElement el = JsonParser.parseString(skinJson);
                    return Skin.fromJson(el);
                } catch (Exception e) {
                    return new Skin(Skin.Type.DEFAULT, null, null, null, null);
                }
            }
            return new Skin(Skin.Type.DEFAULT, null, null, null, null);
        }

        public void setSkinJson(String skinJson) {
            this.skinJson = skinJson;
        }

        public String getSkinJson() {
            return skinJson;
        }

        // 获取对应的 userType 值（Minecraft 启动协议值：msa=微软正版，mojang=Yggdrasil 认证）
        // 注意：这是传给游戏 --userType 参数的协议值，不是界面显示文本；界面显示请用 getTypeLabel()
        public String getUserType() {
            return switch (type) {
                case MICROSOFT -> "msa";
                case OFFLINE, THIRD_PARTY -> "mojang";
            };
        }

        // 账号类型中文显示标签（用于界面展示）
        public String getTypeLabel() {
            return switch (type) {
                case OFFLINE -> "离线";
                case MICROSOFT -> "微软";
                case THIRD_PARTY -> "第三方";
            };
        }

        @Override
        public String toString() {
            return "[" + getTypeLabel() + "] " + name + " (" + id + ")";
        }

        //简短显示名
        public String displayName() {
            return name + " (" + getTypeLabel() + ")";
        }
    }

    // 存储读取

    public static synchronized List<Account> loadAccounts() {
        Path file = Paths.get(ACCOUNTS_FILE);
        if (!Files.exists(file)) {
            migrateOldAccounts();
            if (!Files.exists(file)) return new ArrayList<>();
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return parseAccounts(content);
        } catch (IOException | JsonSyntaxException e) {
            return new ArrayList<>();
        }
    }


     //保存账号列表到磁盘
    public static synchronized void saveAccounts(List<Account> accounts) {
        try {
            Files.createDirectories(Paths.get(CONFIG_DIR));
            String json = serializeAccounts(accounts);
            Files.writeString(Paths.get(ACCOUNTS_FILE), json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[Account] Save failed: " + e.getMessage());
        }
    }

    // 选中账号

    // 获取当前选中的账号
    public static Account getCurrentAccount() {
        List<Account> accounts = loadAccounts();
        if (accounts.isEmpty()) return null;
        // 返回 lastUsed 最新的
        return Collections.max(accounts, (a, b) -> Long.compare(a.lastUsed, b.lastUsed));
    }

    // 获取当前选中的账号
    public static String getCurrentAccountId() {
        Account acc = getCurrentAccount();
        return acc != null ? acc.id : "";
    }

    // 设置指定 ID 为当前账号
    public static boolean setCurrentAccount(String id) {
        List<Account> accounts = loadAccounts();
        for (int i = 0; i < accounts.size(); i++) {
            if (accounts.get(i).id.equals(id)) {
                Account old = accounts.get(i);
                Account updated = new Account(old.id, old.name, old.type,
                        old.accessToken, old.refreshToken, old.authServer,
                        System.currentTimeMillis(), old.tokenExpiresAt);
                accounts.set(i, updated);
                saveAccounts(accounts);
                // 同步写入 INI 配置
                syncToIni(updated);
                return true;
            }
        }
        return false;
    }

    // CRUD 

    // 列出所有账号
    public static List<Account> listAccounts() {
        return loadAccounts();
    }

    // 添加账号
    public static void addAccount(Account account) {
        List<Account> accounts = loadAccounts();
        accounts.removeIf(a -> a.id.equals(account.id));
        accounts.add(account);
        saveAccounts(accounts);
        setCurrentAccount(account.id);
    }

    // 删除账号
    public static boolean removeAccount(String id) {
        List<Account> accounts = loadAccounts();
        boolean removed = accounts.removeIf(a -> a.id.equals(id));
        if (removed) saveAccounts(accounts);
        return removed;
    }

    // 登录方法

    // 创建离线账号
    public static Account loginOffline(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            playerName = "Player";
        }
        // 离线 UUID 基于玩家名生成
        String offlineUuid = UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8)).toString();
        offlineUuid = offlineUuid.replace("-", "");

        Account account = new Account(offlineUuid, playerName, AccountType.OFFLINE,
                "", "", "", System.currentTimeMillis());
        addAccount(account);
        return account;
    }

   
    public interface MicrosoftLoginCallback {
        void onSuccess(Account account);
        void onError(String error);
    }

    // 微软登录（设备码流，在当前线程执行）
    public static Account loginMicrosoftSync() throws Exception {
        MinecraftAuthLauncherDeviceCode auth = new MinecraftAuthLauncherDeviceCode();
        auth.startAuth();
        Path loginJson = Paths.get(OLD_LOGIN_FILE);
        if (!Files.exists(loginJson)) {
            throw new IOException("Credential file not found after login");
        }
        String content = Files.readString(loginJson, StandardCharsets.UTF_8);
        JsonObject json = JsonParser.parseString(content).getAsJsonObject();

        String accessToken = getJsonStr(json, "accessToken");
        String refreshToken = getJsonStr(json, "refreshToken");
        String uuid = getJsonStr(json, "uuid").replace("-", "");
        String username = getJsonStr(json, "username");

        if (accessToken.isEmpty() || uuid.isEmpty()) {
            throw new IOException("Incomplete credentials: accessToken=" + accessToken + " uuid=" + uuid);
        }

        Account account = new Account(uuid, username, AccountType.MICROSOFT,
                accessToken, refreshToken, "", System.currentTimeMillis());
        addAccount(account);
        return account;
    }

    /**
     * 完成设备码登录：轮询等待浏览器授权 + 完成 Minecraft 认证，
     * 成功后从 login.json 读取凭据并写入账号列表（供设备码弹窗流程使用）
     */
    public static Account completeMicrosoftLogin(MinecraftAuthLauncherDeviceCode.DeviceCodeInfo info) throws Exception {
        MinecraftAuthLauncherDeviceCode auth = new MinecraftAuthLauncherDeviceCode();
        auth.continueAuth(info);
        Path loginJson = Paths.get(OLD_LOGIN_FILE);
        if (!Files.exists(loginJson)) {
            throw new IOException("Credential file not found after login");
        }
        String content = Files.readString(loginJson, StandardCharsets.UTF_8);
        JsonObject json = JsonParser.parseString(content).getAsJsonObject();

        String accessToken = getJsonStr(json, "accessToken");
        String refreshToken = getJsonStr(json, "refreshToken");
        String uuid = getJsonStr(json, "uuid").replace("-", "");
        String username = getJsonStr(json, "username");

        if (accessToken.isEmpty() || uuid.isEmpty()) {
            throw new IOException("Incomplete credentials: accessToken=" + accessToken + " uuid=" + uuid);
        }

        Account account = new Account(uuid, username, AccountType.MICROSOFT,
                accessToken, refreshToken, "", System.currentTimeMillis());
        addAccount(account);
        return account;
    }

    // 刷新微软令牌
    public static Account refreshMicrosoftSync(String currentRefreshToken) throws Exception {
        MinecraftAuthLauncherDeviceCode.RefreshResult result =
                MinecraftAuthLauncherDeviceCode.silentRefresh(currentRefreshToken);
        if (result == null || result.accessToken == null) {
            throw new IOException("Silent refresh failed, no accessToken obtained");
        }

        String uuid = result.uuid != null ? result.uuid.replace("-", "") : "";
        String username = result.username != null ? result.username : "";

        List<Account> accounts = loadAccounts();
        for (int i = 0; i < accounts.size(); i++) {
            Account a = accounts.get(i);
            if (uuid.isEmpty() || !a.id.equals(uuid) || a.type != AccountType.MICROSOFT) continue;
            Account updated = new Account(uuid, username, AccountType.MICROSOFT,
                    result.accessToken, result.refreshToken, "",
                    System.currentTimeMillis());
            accounts.set(i, updated);
            saveAccounts(accounts);
            return updated;
        }
        // 未匹配到旧账号，创建为新账号
        Account account = new Account(uuid, username, AccountType.MICROSOFT,
                result.accessToken, result.refreshToken, "",
                System.currentTimeMillis());
        addAccount(account);
        return account;
    }

    public static Account loginThirdPartySync(String authServer, String email, String password) {
        ExternalLoginAuth.AuthResult result = ExternalLoginAuth.login(authServer, email, password);
        if (!result.success) {
            throw new RuntimeException(result.errorMessage != null ? result.errorMessage : "Third-party login failed");
        }
        String uuid = result.uuid.replace("-", "");
        Account account = new Account(uuid, result.userName, AccountType.THIRD_PARTY,
                result.accessToken, "", result.authServer, System.currentTimeMillis());
        addAccount(account);
        return account;
    }

    //  内部序列化 

    private static String serializeAccounts(List<Account> accounts) {
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        for (Account a : accounts) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", a.id);
            obj.addProperty("name", a.name);
            obj.addProperty("type", a.type.name());
            obj.addProperty("accessToken", a.accessToken != null ? a.accessToken : "");
            obj.addProperty("refreshToken", a.refreshToken != null ? a.refreshToken : "");
            obj.addProperty("authServer", a.authServer != null ? a.authServer : "");
            obj.addProperty("lastUsed", a.lastUsed);
            obj.addProperty("tokenExpiresAt", a.tokenExpiresAt);
            if (a.skinJson != null && !a.skinJson.isEmpty()) {
                obj.addProperty("skinJson", a.skinJson);
            }
            arr.add(obj);
        }
        root.add("accounts", arr);
        return GSON.toJson(root);
    }

    private static List<Account> parseAccounts(String json) {
        List<Account> list = new ArrayList<>();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray arr = root.getAsJsonArray("accounts");
        if (arr == null) return list;
        for (var elem : arr) {
            JsonObject obj = elem.getAsJsonObject();
            try {
                String id = getJsonStr(obj, "id");
                String name = getJsonStr(obj, "name");
                String typeStr = getJsonStr(obj, "type");
                String accessToken = getJsonStr(obj, "accessToken");
                String refreshToken = obj.has("refreshToken") && !obj.get("refreshToken").isJsonNull()
                        ? obj.get("refreshToken").getAsString() : "";
                String authServer = obj.has("authServer") && !obj.get("authServer").isJsonNull()
                        ? obj.get("authServer").getAsString() : "";
                long lastUsed = obj.has("lastUsed") ? obj.get("lastUsed").getAsLong() : 0L;
                long tokenExpiresAt = obj.has("tokenExpiresAt") ? obj.get("tokenExpiresAt").getAsLong() : 0L;

                AccountType type = AccountType.valueOf(typeStr);
                Account acc = new Account(id, name, type, accessToken, refreshToken, authServer, lastUsed, tokenExpiresAt);
                if (obj.has("skinJson")) {
                    acc.setSkinJson(obj.get("skinJson").getAsString());
                }
                list.add(acc);
            } catch (IllegalArgumentException ignored) {
                // 跳过无效条目
            }
        }
        return list;
    }

    //  旧账号迁移

    private static void migrateOldAccounts() {
        List<Account> accounts = new ArrayList<>();

        Path oldLogin = Paths.get(OLD_LOGIN_FILE);
        if (Files.exists(oldLogin)) {
            try {
                String content = Files.readString(oldLogin, StandardCharsets.UTF_8);
                JsonObject json = JsonParser.parseString(content).getAsJsonObject();
                String accessToken = getJsonStr(json, "accessToken");
                String refreshToken = getJsonStr(json, "refreshToken");
                String uuid = getJsonStr(json, "uuid").replace("-", "");
                String username = getJsonStr(json, "username");
                if (!uuid.isEmpty() && !username.isEmpty()) {
                    accounts.add(new Account(uuid, username, AccountType.MICROSOFT,
                            accessToken, refreshToken, "", System.currentTimeMillis()));
                }
            } catch (Exception ignored) {}
        }

        Path oldExternal = Paths.get(OLD_EXTERNAL_FILE);
        if (Files.exists(oldExternal)) {
            try {
                String content = Files.readString(oldExternal, StandardCharsets.UTF_8);
                JsonObject json = JsonParser.parseString(content).getAsJsonObject();
                boolean success = "true".equals(getJsonStr(json, "success"));
                if (success) {
                    String userName = getJsonStr(json, "userName");
                    String uuid = getJsonStr(json, "uuid").replace("-", "");
                    String accessToken = getJsonStr(json, "accessToken");
                    String authServer = getJsonStr(json, "authServer");
                    if (!uuid.isEmpty() && !userName.isEmpty()) {
                        accounts.add(new Account(uuid, userName, AccountType.THIRD_PARTY,
                                accessToken, "", authServer, System.currentTimeMillis()));
                    }
                }
            } catch (Exception ignored) {}
        }

        if (!accounts.isEmpty()) {
            saveAccounts(accounts);
        }
    }

    //  与 INI 配置同步 

    
    private static void syncToIni(Account account) {
        // 使用安全凭证存储替代明文 INI 写入
        CredentialStore.saveCredentials(
                account.accessToken,
                account.refreshToken,
                account.id,
                account.name
        );
    }


    private static String getJsonStr(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
    }

    /** 更新指定账号的皮肤配置 */
    public static void updateAccountSkin(String accountId, String skinJson) {
        List<Account> accounts = loadAccounts();
        for (Account a : accounts) {
            if (a.id.equals(accountId)) {
                a.setSkinJson(skinJson);
                saveAccounts(accounts);
                return;
            }
        }
    }
}
