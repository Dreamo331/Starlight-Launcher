/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.frp;

import com.example.starlight.config.AppDirs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

/**
 * SakuraFrp 访问密钥的本地存储。
 *
 * <p>访问密钥等同于账号的隧道管理权限，因此不写进 {@code starlight.ini} 等普通配置，
 * 而是单独放在 {@code ~/.starlight/.sakura.dat}（便携模式为
 * {@code Starlight-Launcher/.sakura.dat}），做 Base64 + XOR 混淆，至少避免被明文
 * 扫描 / 误传进日志。它<b>不是强加密</b>，与项目既有的账号凭据存储保持同一强度，
 * 任何情况下都不会随日志或社区请求上传。
 */
public final class SakuraFrpTokenStore {

    /** 与账号凭据不同的独立 XOR 密钥，避免同一份密钥被复用 */
    private static final byte[] XOR_KEY = {
            (byte) 0x37, (byte) 0xB1, (byte) 0x6D, (byte) 0x92,
            (byte) 0x0E, (byte) 0xCA, (byte) 0x58, (byte) 0xF3
    };

    private SakuraFrpTokenStore() {
    }

    /** 读取已保存的访问密钥；不存在或解析失败返回空串 */
    public static String load() {
        Path path = AppDirs.sakuraFrpTokenFile();
        try {
            if (!Files.isRegularFile(path)) return "";
            String encoded = Files.readString(path, StandardCharsets.UTF_8).trim();
            if (encoded.isEmpty()) return "";
            byte[] obfuscated = Base64.getDecoder().decode(encoded);
            return new String(obfuscate(obfuscated), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** 保存访问密钥（空串等于清除） */
    public static void save(String token) {
        String value = token == null ? "" : token.trim();
        Path path = AppDirs.sakuraFrpTokenFile();
        if (value.isEmpty()) {
            clear();
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            String encoded = Base64.getEncoder()
                    .encodeToString(obfuscate(value.getBytes(StandardCharsets.UTF_8)));
            Files.writeString(path, encoded, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            restrictPermissions(path);
        } catch (IOException e) {
            // 存不下来不应阻断本次连接：密钥仍保存在当前会话内存里
            System.err.println("[SakuraFrp] 保存访问密钥失败: " + e.getMessage());
        }
    }

    /** 清除本地访问密钥 */
    public static void clear() {
        try {
            Files.deleteIfExists(AppDirs.sakuraFrpTokenFile());
        } catch (IOException e) {
            System.err.println("[SakuraFrp] 清除访问密钥失败: " + e.getMessage());
        }
    }

    /** 非 Windows 时把文件权限收紧到仅本人可读写 */
    private static void restrictPermissions(Path path) {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, perms);
        } catch (Exception ignored) {
            // 文件系统不支持 POSIX 权限时忽略
        }
    }

    private static byte[] obfuscate(byte[] data) {
        byte[] result = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            result[i] = (byte) (data[i] ^ XOR_KEY[i % XOR_KEY.length]);
        }
        return result;
    }
}
