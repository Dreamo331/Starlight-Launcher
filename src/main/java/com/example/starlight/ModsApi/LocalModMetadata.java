package com.example.starlight.ModsApi;

import com.example.starlight.util.TextUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 从本地模组 jar 中读取元数据（modId / 展示名 / 版本）。
 *
 * <p>中文名反查需要 modId 才能精确命中字典（文件名往往带版本号，如
 * {@code jei-1.20.1-forge-15.2.0.27.jar}，直接拿文件名匹配容易误判），
 * 因此这里按主流加载器的元数据文件顺序探测：
 * <ol>
 *   <li>{@code fabric.mod.json}（Fabric）</li>
 *   <li>{@code quilt.mod.json}（Quilt）</li>
 *   <li>{@code META-INF/neoforge.mods.toml} / {@code META-INF/mods.toml}（NeoForge / Forge）</li>
 *   <li>{@code mcmod.info}（旧版 Forge）</li>
 * </ol>
 *
 * <p>结果按「文件路径 + 最后修改时间 + 文件大小」缓存，列表刷新时不会重复解压读取。
 * 这是纯文本规则解析（不是完整的 JSON/TOML 解析器），只取首个匹配项，足以拿到 modId。
 */
public final class LocalModMetadata {

    /** 读取元数据时单个条目最多读取的字节数（防御异常大文件） */
    private static final int MAX_ENTRY_BYTES = 256 * 1024;

    private static final Pattern FABRIC_ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern FABRIC_NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern FABRIC_VERSION = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MCMOD_ID = Pattern.compile("\"modid\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TOML_MOD_ID = Pattern.compile("(?m)^\\s*modId\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern TOML_NAME = Pattern.compile("(?m)^\\s*displayName\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern TOML_VERSION = Pattern.compile("(?m)^\\s*version\\s*=\\s*[\"']([^\"']+)[\"']");

    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

    private LocalModMetadata() {
    }

    /**
     * 模组元数据。
     *
     * @param modId   模组 ID（未识别时为空串）
     * @param name    展示名（未识别时为空串）
     * @param version 版本号（未识别时为空串）
     * @param source  元数据来源文件名（未识别时为空串）
     */
    public record Info(String modId, String name, String version, String source) {
        /** 空结果（不支持的加载器 / 非模组 jar / 读取失败） */
        public static final Info EMPTY = new Info("", "", "", "");

        /** 是否成功识别到 modId */
        public boolean hasModId() {
            return TextUtils.isNotBlank(modId);
        }
    }

    /**
     * 读取模组 jar 的元数据；结果带缓存，读取失败或格式不支持时返回 {@link Info#EMPTY}。
     *
     * @param jar 模组文件（可以是 {@code .jar} 或 {@code .jar.disabled}）
     */
    public static Info read(Path jar) {
        if (jar == null || !Files.isRegularFile(jar)) return Info.EMPTY;

        String key = jar.toAbsolutePath().toString();
        long modified;
        long size;
        try {
            modified = Files.getLastModifiedTime(jar).toMillis();
            size = Files.size(jar);
        } catch (Exception e) {
            return Info.EMPTY;
        }

        CacheEntry cached = CACHE.get(key);
        if (cached != null && cached.modified() == modified && cached.size() == size) {
            return cached.info();
        }

        Info info = scan(jar);
        CACHE.put(key, new CacheEntry(modified, size, info));
        return info;
    }

    /** 清空缓存（模组增删后可调用；正常情况下靠修改时间自动失效） */
    public static void clearCache() {
        CACHE.clear();
    }

    private static Info scan(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Info fabric = readJsonLike(zip, "fabric.mod.json", FABRIC_ID);
            if (fabric.hasModId()) return fabric;

            Info quilt = readJsonLike(zip, "quilt.mod.json", FABRIC_ID);
            if (quilt.hasModId()) return quilt;

            Info neoForge = readToml(zip, "META-INF/neoforge.mods.toml");
            if (neoForge.hasModId()) return neoForge;

            Info forge = readToml(zip, "META-INF/mods.toml");
            if (forge.hasModId()) return forge;

            Info legacy = readJsonLike(zip, "mcmod.info", MCMOD_ID);
            if (legacy.hasModId()) return legacy;
        } catch (Exception e) {
            // 非 zip / 损坏文件 / 编码异常：按无法识别处理
        }
        return Info.EMPTY;
    }

    /** 解析 fabric.mod.json / quilt.mod.json / mcmod.info 这类 JSON 文本 */
    private static Info readJsonLike(ZipFile zip, String entryName, Pattern idPattern) {
        String text = readEntry(zip, entryName);
        if (text == null) return Info.EMPTY;

        String id = firstGroup(idPattern, text);
        if (TextUtils.isBlank(id)) return Info.EMPTY;
        return new Info(id.trim(),
                orEmpty(firstGroup(FABRIC_NAME, text)),
                orEmpty(firstGroup(FABRIC_VERSION, text)),
                entryName);
    }

    /** 解析 NeoForge / Forge 的 mods.toml */
    private static Info readToml(ZipFile zip, String entryName) {
        String text = readEntry(zip, entryName);
        if (text == null) return Info.EMPTY;

        String id = firstGroup(TOML_MOD_ID, text);
        if (TextUtils.isBlank(id)) return Info.EMPTY;

        String name = firstGroup(TOML_NAME, text);
        if (TextUtils.isBlank(name)) name = id;
        return new Info(id.trim(), name.trim(), orEmpty(firstGroup(TOML_VERSION, text)), entryName);
    }

    /** 读取 zip 中某个文本条目（限制大小，UTF-8 解码） */
    private static String readEntry(ZipFile zip, String entryName) {
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) {
            // 部分 jar 的路径大小写不规范，退化做一次不敏感匹配
            String lower = entryName.toLowerCase(Locale.ROOT);
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry candidate = entries.nextElement();
                if (candidate.getName().toLowerCase(Locale.ROOT).equals(lower)) {
                    entry = candidate;
                    break;
                }
            }
            if (entry == null) return null;
        }

        try (InputStream in = zip.getInputStream(entry)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int total = 0;
            int read;
            while (total < MAX_ENTRY_BYTES && (read = in.read(chunk)) != -1) {
                int usable = Math.min(read, MAX_ENTRY_BYTES - total);
                buffer.write(chunk, 0, usable);
                total += usable;
            }
            return buffer.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /** 缓存条目：文件指纹 + 解析结果 */
    private record CacheEntry(long modified, long size, Info info) {
    }
}
