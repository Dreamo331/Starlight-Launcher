/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.auth.offline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PCL2 风格离线皮肤 UUID 定制（移植自 PCL2 {@code ModLaunch.McLoginLegacyUuidWithCustomSkin}）。
 *
 * <p>原理：Minecraft 对离线玩家不提供皮肤数据，玩家默认皮肤（Steve/Alex 模型）由 UUID 决定；
 * 因此离线皮肤的第一层手段是<b>修改传给游戏的 {@code auth_uuid}</b>——保持玩家名不变，
 * 只把 UUID 微调到「游戏会把它判定为期望模型」为止。配合 {@link OfflineSkinPack} 的资源包
 * 覆盖默认贴图，即可在不注入任何会话数据的情况下实现离线皮肤。</p>
 *
 * <p>模型判定（对应 PCL2 {@code McSkinSex}）：取无连字符 UUID 第 7/15/23/31 位四个半字节
 * 做 XOR，奇数=Alex（纤细）、偶数=Steve（宽体）。每把末 5 位 +1，末位半字节奇偶必翻转，
 * 因此目标模型最多一次迭代即可命中。</p>
 */
public final class OfflineSkinUuid {

    private static final Logger LOG = LoggerFactory.getLogger(OfflineSkinUuid.class);

    /** 循环保护上限：正常 1 次即可命中，防意外死循环 */
    private static final int MAX_ITERATIONS = 100_000;

    private OfflineSkinUuid() {
    }

    /**
     * 按皮肤配置计算实际传给游戏的 UUID（无连字符 32 位小写十六进制）。
     *
     * @param baseUuid 离线账号的基础 UUID（账号创建时按玩家名生成），作为调整起点
     * @param skin     离线皮肤配置；null 按 DEFAULT 处理
     * @return 定制后的 UUID；异常时原样返回 baseUuid
     */
    public static String resolve(String baseUuid, Skin skin) {
        if (baseUuid == null || baseUuid.length() != 32) return baseUuid;
        String uuid = baseUuid.toLowerCase();
        Skin.Type type = skin == null ? Skin.Type.DEFAULT : skin.getType();
        try {
            switch (type) {
                case DEFAULT:
                    // 随机：不动，模型由玩家名哈希决定
                    return uuid;
                case STEVE:
                    return bumpUntil(uuid, "Steve");
                case ALEX:
                    return bumpUntil(uuid, "Alex");
                case ARI, EFE, KAI, MAKENA, NOOR, SUNNY, ZURI:
                    // 内置皮肤（非 Steve/Alex）：模型跟随该皮肤的官方模型，贴图由资源包注入
                    return bumpUntil(uuid, skin.isSlimBuiltin() ? "Alex" : "Steve");
                case LOCAL_FILE, LITTLE_SKIN, CUSTOM_SKIN_LOADER_API:
                    // 自定义皮肤：模型跟随用户选择的 slim/wide，贴图由资源包注入
                    return bumpUntil(uuid, skin.isSlim() ? "Alex" : "Steve");
                default:
                    return uuid;
            }
        } catch (Exception e) {
            LOG.warn("Failed to resolve offline skin UUID, fallback to base: {}", e.getMessage());
            return baseUuid;
        }
    }

    /** 将 UUID 末 5 位十六进制 +1 后返回（fffff 回绕为 00000），其余不变 */
    static String bump(String uuid) {
        if (uuid.endsWith("fffff")) {
            return uuid.substring(0, 27) + "00000";
        }
        long tail = Long.parseLong(uuid.substring(27), 16) + 1;
        String hex = Long.toHexString(tail);
        return uuid.substring(0, 27) + "0".repeat(5 - hex.length()) + hex;
    }

    /**
     * 循环递增 UUID 末 5 位，直到 {@link #skinSex} 命中目标模型。
     * 每次 +1 末位半字节奇偶必翻转，因此几乎立即命中；仍带迭代上限防异常。
     */
    private static String bumpUntil(String uuid, String targetSex) {
        String current = uuid;
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            if (skinSex(current).equals(targetSex)) return current;
            current = bump(current);
        }
        LOG.warn("Failed to find UUID with model {} after {} iterations, using last value", targetSex, MAX_ITERATIONS);
        return current;
    }

    /**
     * 判定 UUID 对应的默认皮肤模型（PCL2 {@code McSkinSex} 移植）：
     * 无连字符 32 位 UUID 的第 7/15/23/31 位半字节 XOR，奇数=Alex、偶数=Steve。
     */
    public static String skinSex(String uuid) {
        if (uuid == null || uuid.length() != 32) return "Steve";
        int a = Character.digit(uuid.charAt(7), 16);
        int b = Character.digit(uuid.charAt(15), 16);
        int c = Character.digit(uuid.charAt(23), 16);
        int d = Character.digit(uuid.charAt(31), 16);
        return ((a ^ b ^ c ^ d) % 2 == 1) ? "Alex" : "Steve";
    }
}
