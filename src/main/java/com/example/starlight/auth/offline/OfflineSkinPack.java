/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.auth.offline;


import com.example.starlight.util.I18N;
import com.example.starlight.lang.GameLanguage;
import com.example.starlight.util.VanillaVersion;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 离线皮肤资源包构建器（PCL2 风格）。
 *
 * <p>原理：把自定义皮肤 PNG 打包成 {@code Starlight Skin.zip} 放进游戏的
 * {@code resourcepacks/} 目录，并写入 {@code options.txt} 的 {@code resourcePacks} 字段，
 * 用资源包<b>覆盖游戏内置的默认玩家皮肤贴图</b>，实现离线玩家的自定义皮肤。
 * 多人游戏中仅自己可见，且会替换游戏中所有默认角色（与 PCL2 一致）。</p>
 *
 * <p>贴图覆盖路径分两代：1.19.3+ 替换
 * {@code assets/minecraft/textures/entity/player/{slim|wide}/} 下全部 9 个内置皮肤；
 * 1.6~1.19.2 替换 {@code textures/entity/{alex|steve}.png}；1.6/1.7 不支持双层皮肤，
 * 64×64 贴图会被裁剪为 64×32；1.5.x 及更早没有资源包机制，直接跳过。</p>
 */
public final class OfflineSkinPack {

    private static final Logger LOG = LoggerFactory.getLogger(OfflineSkinPack.class);

    /** 资源包文件名（同时用于 options.txt 中的条目识别） */
    public static final String ZIP_NAME = "Starlight Skin.zip";

    /** 需要一并清理的遗留离线皮肤资源包名（旧 PCL2 在同目录生成的同名包，若不清理会遮盖
     *  随机/Steve/Alex 等 UUID 模型调整的效果） */
    private static final String[] LEGACY_PACK_NAMES = {ZIP_NAME, "PCL2 Skin.zip"};

    /** options.txt 中启用资源包的键名 */
    private static final String OPTIONS_KEY = "resourcePacks";

    /** 失焦自动暂停键：游戏失焦即暂停（世界不渲染），1.17+ 皮肤/披风贴图下载不会触发，
     *  离线皮肤/披风场景必须强制关闭 */
    private static final String OPTIONS_PAUSE_ON_LOST_FOCUS = "pauseOnLostFocus";

    /** 1.19.3+ 内置玩家皮肤文件名（按 PCL2 相同的替换清单） */
    private static final String[] PLAYER_SKIN_NAMES =
            {"alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri"};

    private static final Gson GSON = new Gson();

    private OfflineSkinPack() {
    }

    /**
     * 构建并启用离线皮肤资源包；不满足条件（版本过老 / 贴图缺失）时回退为清理。
     *
     * @param skin              皮肤配置（仅 LOCAL_FILE / LITTLE_SKIN / CUSTOM_SKIN_LOADER_API
     *                          及非 Steve/Alex 的内置皮肤会构建资源包）
     * @param username          玩家名（CSL API 按名查询）
     * @param gameDir           游戏根目录（.minecraft）
     * @param version           版本目录名
     * @param versionIsolation  是否启用版本隔离（决定资源包与 options.txt 的实际位置）
     * @param vanilla           原版版本号（决定 pack_format 与贴图路径）
     * @return true=资源包已构建并启用；false=未启用（已做清理）
     */
    public static boolean apply(Skin skin, String username, String gameDir,
                                String version, boolean versionIsolation, VanillaVersion vanilla) {
        Path effectiveGameDir = effectiveGameDir(gameDir, version, versionIsolation);
        Path zipFile = effectiveGameDir.resolve("resourcepacks").resolve(ZIP_NAME);

        if (skin == null || !needsResourcePack(skin)) {
            LOG.info("Offline skin does not need resource pack, cleaning up");
            cleanup(gameDir, version, versionIsolation);
            return false;
        }

        if (vanilla == null || vanilla.major() < 6) {
            LOG.warn(I18N.get("offline.t0036"), vanilla);
            cleanup(gameDir, version, versionIsolation);
            return false;
        }

        try {
            boolean slim = skin.isBuiltin() ? skin.isSlimBuiltin() : skin.isSlim();
            byte[] png = loadTextureBytes(skin, username);
            if (png == null) {
                LOG.warn(I18N.get("offline.t0037"));
                cleanup(gameDir, version, versionIsolation);
                return false;
            }

            // 1.6/1.7 不支持双层皮肤：64×64 裁剪为 64×32（与 PCL2 一致）
            if ((vanilla.major() == 6 || vanilla.major() == 7) && isDoubleLayer(png)) {
                LOG.info(I18N.get("offline.t0038"));
                png = clipToLegacy(png);
            }

            int packFormat = packFormat(vanilla);
            LOG.info(I18N.get("offline.t0039"), ZIP_NAME, packFormat);

            Files.createDirectories(zipFile.getParent());
            writeZip(zipFile, png, slim, vanilla, packFormat);
            // 清理同目录下遗留的旧版离线皮肤资源包文件（如 PCL2 Skin.zip），避免资源包目录混淆
            for (String name : LEGACY_PACK_NAMES) {
                if (name.equals(ZIP_NAME)) continue;
                Path legacy = zipFile.getParent().resolve(name);
                if (Files.exists(legacy)) {
                    Files.delete(legacy);
                    LOG.info(I18N.get("offline.t0040"), legacy);
                }
            }
            enableInOptions(gameDir, version, versionIsolation, vanilla);
            return true;
        } catch (Exception e) {
            LOG.error(I18N.get("offline.t0041"), e.getMessage());
            cleanup(gameDir, version, versionIsolation);
            return false;
        }
    }

    /**
     * 清理离线皮肤资源包：删除 zip 并把它从 options.txt 的 resourcePacks 移除。
     * 每次启动都会按需调用（未启用自定义皮肤 / 版本过老 / 文件缺失时保持清理状态）。
     */
    public static void cleanup(String gameDir, String version, boolean versionIsolation) {
        try {
            Path effectiveGameDir = effectiveGameDir(gameDir, version, versionIsolation);
            for (String name : LEGACY_PACK_NAMES) {
                Path zipFile = effectiveGameDir.resolve("resourcepacks").resolve(name);
                if (Files.exists(zipFile)) {
                    Files.delete(zipFile);
                    LOG.info(I18N.get("offline.t0042"), zipFile);
                }
            }
            disableInOptions(gameDir, version, versionIsolation);
        } catch (IOException e) {
            LOG.warn(I18N.get("offline.t0043"), e.getMessage());
        }
    }

    /** 皮肤类型是否需要资源包（DEFAULT / STEVE / ALEX 走 UUID 模型调整，不需要） */
    static boolean needsResourcePack(Skin skin) {
        return switch (skin.getType()) {
            case LOCAL_FILE, LITTLE_SKIN, CUSTOM_SKIN_LOADER_API -> true;
            case ARI, EFE, KAI, MAKENA, NOOR, SUNNY, ZURI -> true;
            default -> false;
        };
    }

    // 贴图加载 //
    /** 加载皮肤 PNG 字节：本地文件直接读原文件，CSL API 走现有加载逻辑，内置皮肤读打包资源 */
    private static byte[] loadTextureBytes(Skin skin, String username) throws IOException {
        switch (skin.getType()) {
            case LOCAL_FILE: {
                String path = skin.getLocalSkinPath();
                if (path == null || path.isBlank() || !Files.exists(Paths.get(path))) return null;
                return Files.readAllBytes(Paths.get(path));
            }
            case LITTLE_SKIN, CUSTOM_SKIN_LOADER_API: {
                Skin.LoadedSkin loaded = skin.load(username);
                if (loaded == null || loaded.getSkin() == null || loaded.getSkin().getImage() == null) return null;
                return SkinTexture.toPngBytes(loaded.getSkin().getImage());
            }
            case ARI, EFE, KAI, MAKENA, NOOR, SUNNY, ZURI: {
                String resourcePath = "/Skins/" + skin.getType().name() + ".png";
                try (InputStream in = OfflineSkinPack.class.getResourceAsStream(resourcePath)) {
                    if (in == null) return null;
                    return in.readAllBytes();
                }
            }
            default:
                return null;
        }
    }

    private static boolean isDoubleLayer(byte[] png) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        return img != null && img.getWidth() == 64 && img.getHeight() == 64;
    }

    /** 64×64 双层皮肤裁剪为 64×32（1.6/1.7 旧版本不支持双层贴图） */
    private static byte[] clipToLegacy(byte[] png) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        if (img == null || img.getWidth() != 64 || img.getHeight() != 64) return png;
        BufferedImage clipped = img.getSubimage(0, 0, 64, 32);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(clipped, "png", out);
        return out.toByteArray();
    }

    // 资源包构建 //
    private static void writeZip(Path zipFile, byte[] png, boolean slim,
                                 VanillaVersion vanilla, int packFormat) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            // pack.mcmeta：按版本 pack_format，声明本包用途
            byte[] meta = ("{\"pack\":{\"pack_format\":" + packFormat
                    + ",\"description\":\"" + I18N.get("osp.r0001") + "\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            putEntry(zip, "pack.mcmeta", meta);

            if (vanilla.major() > 19 || (vanilla.major() == 19 && vanilla.minor() >= 3)) {
                // 1.19.3+：覆盖全部 9 个内置玩家皮肤贴图（slim/wide 按模型选一层）
                String modelDir = slim ? "slim" : "wide";
                for (String name : PLAYER_SKIN_NAMES) {
                    putEntry(zip, "assets/minecraft/textures/entity/player/" + modelDir + "/" + name + ".png", png);
                }
            } else {
                // 1.6~1.19.2：覆盖默认的 steve.png / alex.png
                String textureName = slim ? "alex.png" : "steve.png";
                putEntry(zip, "assets/minecraft/textures/entity/" + textureName, png);
            }
        }
    }

    private static void putEntry(ZipOutputStream zip, String path, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    /**
     * 按原版版本返回资源包格式号（pack_format）。
     * 1.21.9+ 与快照/未知版本统一用 64（旧式单整数格式，<65 仍被 1.21.9+ 接受）。
     */
    static int packFormat(VanillaVersion v) {
        int major = v.major(), minor = v.minor();
        return switch (major) {
            case 6, 7, 8 -> 1;
            case 9, 10 -> 2;
            case 11, 12 -> 3;
            case 13, 14 -> 4;
            case 15 -> 5;
            case 16 -> 6;
            case 17 -> 7;
            case 18 -> minor <= 1 ? 8 : 9;
            case 19 -> minor <= 2 ? 9 : minor == 3 ? 12 : 13;
            case 20 -> minor <= 1 ? 15 : minor == 2 ? 18 : minor <= 4 ? 22 : 32;
            case 21 -> minor <= 1 ? 34 : minor <= 3 ? 42 : minor == 4 ? 46 : minor == 5 ? 55 : minor == 6 ? 63 : 64;
            default -> 64;
        };
    }

    // options.txt 处理 //
    /** 版本隔离启用时游戏目录为 versions/<version>，否则为 gameDir（与 options.txt 定位一致） */
    private static Path effectiveGameDir(String gameDir, String version, boolean versionIsolation) {
        Path base = Path.of(gameDir);
        if (versionIsolation && version != null && !version.isBlank()) {
            return base.resolve("versions").resolve(version);
        }
        return base;
    }

    private static void enableInOptions(String gameDir, String version, boolean versionIsolation,
                                        VanillaVersion vanilla) {
        try {
            Map<String, String> options = GameLanguage.readOptions(
                    GameLanguage.resolveOptionsPath(gameDir, version, versionIsolation));
            List<String> entries = parseEntries(options.get(OPTIONS_KEY));
            // 去重：先移除旧的离线皮肤包条目（含遗留 PCL2 包）
            entries.removeIf(OfflineSkinPack::isOfflineSkinPack);
            if (entries.isEmpty() && vanilla.major() >= 13) {
                // 新式（1.13+）默认带 vanilla 条目
                entries.add("vanilla");
            }
            entries.add(entryName(vanilla));
            options.put(OPTIONS_KEY, GSON.toJson(entries));
            options.put(OPTIONS_PAUSE_ON_LOST_FOCUS, "false");
            GameLanguage.writeOptions(
                    GameLanguage.resolveOptionsPath(gameDir, version, versionIsolation), options);
            LOG.info(I18N.get("offline.t0044"), GSON.toJson(entries));
        } catch (IOException e) {
            LOG.warn(I18N.get("offline.t0045"), e.getMessage());
        }
    }

    private static void disableInOptions(String gameDir, String version, boolean versionIsolation) {
        try {
            Path optionsPath = GameLanguage.resolveOptionsPath(gameDir, version, versionIsolation);
            Map<String, String> options = GameLanguage.readOptions(optionsPath);
            if (!options.containsKey(OPTIONS_KEY)) return;
            List<String> entries = parseEntries(options.get(OPTIONS_KEY));
            int before = entries.size();
            entries.removeIf(OfflineSkinPack::isOfflineSkinPack);
            if (entries.size() == before) return;
            options.put(OPTIONS_KEY, GSON.toJson(entries));
            GameLanguage.writeOptions(optionsPath, options);
            LOG.info(I18N.get("offline.t0046"));
        } catch (IOException e) {
            LOG.warn(I18N.get("offline.t0047"), e.getMessage());
        }
    }

    /** 资源包条目名：1.13+ 新式带 file/ 前缀，旧式只有 zip 文件名（与 PCL2 一致）；
     *  引号由 Gson 序列化时统一添加，避免二次转义 */
    private static String entryName(VanillaVersion vanilla) {
        return vanilla.major() >= 13 ? "file/" + ZIP_NAME : ZIP_NAME;
    }

    /** 解析 options.txt 中的 resourcePacks 值为条目列表（含引号）；非法时按逗号拆分兜底 */
    private static List<String> parseEntries(String value) {
        List<String> entries = new ArrayList<>();
        if (value == null || value.isBlank()) return entries;
        String trimmed = value.trim();
        if (trimmed.startsWith("[")) {
            try {
                JsonArray arr = JsonParser.parseString(trimmed).getAsJsonArray();
                for (var el : arr) {
                    if (el.isJsonPrimitive()) entries.add(el.getAsString());
                }
                return entries;
            } catch (Exception ignored) {
                // 不是合法 JSON，走下方兜底
            }
        }
        // 兜底：去除括号后按逗号拆分并去引号
        for (String part : trimmed.replace("[", "").replace("]", "").split(",")) {
            String item = part.trim();
            if (item.length() >= 2 && item.startsWith("\"") && item.endsWith("\"")) {
                item = item.substring(1, item.length() - 1);
            }
            if (!item.isEmpty()) entries.add(item);
        }
        return entries;
    }

    /** 是否为本启动器或旧 PCL2 生成的离线皮肤资源包条目 */
    private static boolean isOfflineSkinPack(String entry) {
        String base = basename(entry);
        for (String name : LEGACY_PACK_NAMES) {
            if (base.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /** 取条目中的文件名部分（"file/xxx.zip" 与 "xxx.zip" 统一比较） */
    private static String basename(String entry) {
        String name = entry;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        return name.toLowerCase(Locale.ROOT);
    }
}
