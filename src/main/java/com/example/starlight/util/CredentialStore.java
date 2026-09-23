package com.example.starlight.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 凭证安全存储工具类。
 * <p>
 * 将账号敏感信息（AccessToken、RefreshToken）与普通配置分离，
 * 存储到独立的凭证文件中进行简单混淆（Base64 + XOR），
 * 防止明文字段被直接扫描到。
 * </p>
 *
 * <p><b>注意：</b>此为轻量级混淆方案，非强加密。
 * 真正的安全存储应使用系统密钥链（Windows Credential Manager / macOS Keychain）。</p>
 */
public final class CredentialStore {

    private static final Logger log = LoggerFactory.getLogger(CredentialStore.class);

    private static final String CONFIG_DIR = "Starlight-Launcher";
    private static final String CREDENTIAL_FILE = CONFIG_DIR + "/.account.dat";

    // 简单 XOR 密钥（非强加密，仅防明文泄漏）
    private static final byte[] XOR_KEY = {(byte) 0x5A, (byte) 0x3C, (byte) 0x1F, (byte) 0x7B, (byte) 0x2D, (byte) 0x4E, (byte) 0x6A, (byte) 0x8C};

    private CredentialStore() { }

    /** 保存账号凭证到独立文件 */
    public static void saveCredentials(String accessToken, String refreshToken,
                                       String uuid, String userName) {
        try {
            Path configDir = Paths.get(CONFIG_DIR);
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }

            // 先读取已有的凭证
            Map<String, String> creds = loadCredentialMap();
            if (accessToken != null)  creds.put("AccessToken", accessToken);
            if (refreshToken != null) creds.put("RefreshToken", refreshToken);
            if (uuid != null)         creds.put("UUID", uuid);
            if (userName != null)     creds.put("UserName", userName);

            // 序列化为单行文本：key1=val1|key2=val2|...
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : creds.entrySet()) {
                if (sb.length() > 0) sb.append('|');
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            byte[] plainBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            byte[] obfuscated = obfuscate(plainBytes);
            String encoded = Base64.getEncoder().encodeToString(obfuscated);

            Path credPath = configDir.resolve(".account.dat");
            Files.writeString(credPath, encoded, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("Account credentials saved to separate storage file");
        } catch (IOException e) {
            log.error("Failed to save account credentials: {}", e.getMessage());
        }
    }

    /** 读取指定键的凭证值 */
    public static String getCredential(String key) {
        return loadCredentialMap().get(key);
    }

    /** 读取所有凭证 */
    public static Map<String, String> loadCredentialMap() {
        Map<String, String> result = new HashMap<>();
        try {
            Path credPath = Paths.get(CREDENTIAL_FILE);
            if (!Files.exists(credPath)) {
                credPath = Paths.get(CONFIG_DIR, ".account.dat");
            }
            if (!Files.exists(credPath)) return result;

            String encoded = Files.readString(credPath, StandardCharsets.UTF_8).trim();
            byte[] obfuscated = Base64.getDecoder().decode(encoded);
            byte[] plainBytes = deobfuscate(obfuscated);
            String plain = new String(plainBytes, StandardCharsets.UTF_8);

            // 解析 key=value 对
            String[] pairs = plain.split("\\|");
            for (String pair : pairs) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    result.put(pair.substring(0, eq), pair.substring(eq + 1));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read account credentials: {}", e.getMessage());
        }
        return result;
    }

    /** 清除凭证文件 */
    public static void clearCredentials() {
        try {
            Files.deleteIfExists(Paths.get(CREDENTIAL_FILE));
            log.info("Account credentials cleared");
        } catch (IOException e) {
            log.warn("Failed to clear account credentials: {}", e.getMessage());
        }
    }

    /** 简单 XOR 混淆 */
    private static byte[] obfuscate(byte[] data) {
        byte[] result = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            result[i] = (byte) (data[i] ^ XOR_KEY[i % XOR_KEY.length]);
        }
        return result;
    }

    /** XOR 逆混淆 */
    private static byte[] deobfuscate(byte[] data) {
        return obfuscate(data); // XOR 是对称操作
    }
}
