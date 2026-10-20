/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.auth.offline;

import com.example.starlight.util.VanillaVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 离线皮肤管理器（PCL2 风格）。
 *
 * <p>不再使用 authlib-injector + 本地会话服务器的注入方案，改为 PCL2 的两层机制：
 * <ol>
 *   <li><b>UUID 定制</b>：启动时按皮肤配置微调传给游戏的 {@code auth_uuid}，让游戏把玩家
 *       判定为期望的默认皮肤模型（Steve/Alex 由 UUID 决定）；</li>
 *   <li><b>资源包覆盖</b>：自定义皮肤（本地文件 / CSL API / 非 Steve-Alex 内置皮肤）通过
 *       构建 {@code Starlight Skin.zip} 覆盖游戏内置默认贴图。</li>
 * </ol>
 * 本类是启动流程的唯一入口：{@link #resolveUuid} 在构建启动参数前调用（幂等，始终以
 * 账号基础 UUID 为起点），{@link #applySkinPack} 在启动前构建/清理资源包。</p>
 */
public final class OfflineSkinManager {

    private static final Logger LOG = LoggerFactory.getLogger(OfflineSkinManager.class);

    private OfflineSkinManager() {
    }

    /**
     * 按皮肤配置解析实际传给游戏的 UUID（无连字符 32 位十六进制）。
     * 以账号基础离线 UUID 为起点，幂等：多次调用结果一致。
     *
     * @param baseUuid 账号离线 UUID（如 {@code Account.id}）
     * @param skin     皮肤配置；null 按 DEFAULT 处理（UUID 不变）
     * @return 定制后的 UUID；参数异常时返回 baseUuid
     */
    public static String resolveUuid(String baseUuid, Skin skin) {
        return OfflineSkinUuid.resolve(baseUuid, skin);
    }

    /**
     * 启动前应用离线皮肤：需要资源包的皮肤类型构建并启用资源包；
     * 其余情况（DEFAULT / STEVE / ALEX / 条件不满足）清理残留资源包。
     *
     * @return true=资源包已构建并启用
     */
    public static boolean applySkinPack(Skin skin, String username, String gameDir,
                                        String version, boolean versionIsolation, VanillaVersion vanilla) {
        if (skin == null || !OfflineSkinPack.needsResourcePack(skin)) {
            OfflineSkinPack.cleanup(gameDir, version, versionIsolation);
            return false;
        }
        return OfflineSkinPack.apply(skin, username, gameDir, version, versionIsolation, vanilla);
    }

    /** 皮肤类型是否需要资源包（DEFAULT / STEVE / ALEX 只需 UUID 模型调整） */
    public static boolean needsResourcePack(Skin skin) {
        return skin != null && OfflineSkinPack.needsResourcePack(skin);
    }

    /**
     * 启动前加载皮肤与披风（供本地档案服务器使用）。
     * 无自定义皮肤 / 加载失败时返回 null；调用方仅在 {@code loaded.getCape() != null}
     * 时启用披风服务器。
     */
    public static Skin.LoadedSkin loadForLaunch(Skin skin, String username) {
        if (skin == null) return null;
        try {
            return skin.load(username);
        } catch (Exception e) {
            LOG.warn("启动前加载皮肤失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 取已加载披风的 PNG 字节（供本地披风代理劫持 OptiFine 下载通道）。
     * 无披风 / 加载失败时返回 null。
     */
    public static byte[] capePngBytes(Skin.LoadedSkin loaded) {
        if (loaded == null || loaded.getCape() == null || loaded.getCape().getImage() == null) {
            return null;
        }
        try {
            return SkinTexture.toPngBytes(loaded.getCape().getImage());
        } catch (Exception e) {
            LOG.debug("披风转 PNG 失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 1.17+ 本地玩家皮肤/披风直注入：构建 {@code --profileProperties} 参数对。
     *
     * <p>1.17 起客户端 {@code SkinManager} 对本地玩家直接取登录 Session profile 的属性
     * （来自启动参数 --profileProperties），不再查询会话档案；没有该参数时本地玩家
     * profile 永远没有 textures 属性，皮肤/披风无法显示。仅当本地披风服务器已启动
     * 且版本 >= 1.17 时注入（1.16 及更早的 Main 没有该选项，传入会解析失败）。</p>
     *
     * @param server   已启动的本地披风服务器（null 则返回空）
     * @param versionId 版本 ID（如 {@code 1.17.1-OptiFine_H1}）
     * @return {@code ["--profileProperties", "<json>"]} 或空列表
     */
    public static java.util.List<String> profilePropertiesArgs(LocalYggdrasilServer server,
                                                               String versionId) {
        if (server == null) return java.util.List.of();
        VanillaVersion vanilla = VanillaVersion.parse(versionId);
        if (vanilla == null || vanilla.major() < 17) return java.util.List.of();
        String json = server.buildProfilePropertiesJson();
        if (json == null || json.isBlank()) return java.util.List.of();
        // Windows 命令行会把参数内的双引号当边界剥掉（JDK ProcessBuilder 不转义内部
        // 引号，CreateProcess 解析后 JSON 引号丢失 -> 游戏 Gson 解析崩溃，已用参数探针
        // temp/smoke/LaunchArgsProbe(2).java 实证）。把 " 转义为 \"，Windows 侧会还原为
        // 完整 JSON；非 Windows（如 Linux/macOS）不转义，否则游戏会收到字面 \" 而解析失败。
        if (isWindows()) {
            json = json.replace("\"", "\\\"");
        }
        return java.util.List.of("--profileProperties", json);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
