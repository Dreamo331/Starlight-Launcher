package com.example.starlight.ModsApi;

import com.example.starlight.newui.AppConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Mod 中文名字典的数据管理：加载、校验、运行时更新。
 *
 * <p>字典是「中文名 ↔ 英文名 ↔ 平台 ID」的映射表，格式与 HMCL 的
 * {@code assets/mod_data.txt} 完全一致（每行 6 个分号分隔字段）：
 * <pre>
 * curseforgeSlug ; mcmodId ; modIds(逗号分隔) ; 中文名 ; 英文名 ; 缩写
 * buildcraft;4;BuildCraft|Core,buildcraftlib;建筑;BuildCraft;BC
 * </pre>
 *
 * <p><b>数据来源优先级</b>（保证「离线可用 + 数据可更新」）：
 * <ol>
 *   <li>{@code Starlight-Launcher/mod-dictionary/<文件名>} —— 运行时更新的缓存，优先使用；</li>
 *   <li>jar 内 classpath 资源 {@code assets/<文件名>} —— 随包分发的兜底数据。</li>
 * </ol>
 *
 * <p><b>关于更新地址</b>：默认地址指向 HMCL 仓库中的同名数据文件，属于「运行兜底」；
 * 由于该数据文件的版权标注为 mcmod.cn 所有，正式发布前应在设置页把地址替换为
 * 自有数据源（{@code ModDictionaryUrl} / {@code ModPackDictionaryUrl} 两个配置项），
 * 或直接改用自建字典。<b>本类不做任何静默下载，更新只由用户在设置页手动触发。</b>
 */
public final class ModDictionaryManager {

    /** 模组字典的 classpath 资源路径 */
    public static final String MOD_RESOURCE = "assets/mod_data.txt";
    /** 整合包字典的 classpath 资源路径 */
    public static final String MODPACK_RESOURCE = "assets/modpack_data.txt";
    /** 模组字典的缓存文件名 */
    public static final String MOD_CACHE_FILE = "mod_data.txt";
    /** 整合包字典的缓存文件名 */
    public static final String MODPACK_CACHE_FILE = "modpack_data.txt";

    /** 运行时更新缓存的存放目录（相对启动器工作目录） */
    public static final Path DICTIONARY_DIR = Path.of("Starlight-Launcher", "mod-dictionary");

    /** 配置项：模组字典更新地址 */
    public static final String CONFIG_URL_MOD = "ModDictionaryUrl";
    /** 配置项：整合包字典更新地址 */
    public static final String CONFIG_URL_MODPACK = "ModPackDictionaryUrl";

    /** 默认模组字典地址（HMCL 仓库内的同名数据文件，运行时兜底用） */
    public static final String DEFAULT_MOD_URL =
            "https://raw.githubusercontent.com/HMCL-dev/HMCL/main/HMCL/src/main/resources/assets/mod_data.txt";
    /** 默认整合包字典地址 */
    public static final String DEFAULT_MODPACK_URL =
            "https://raw.githubusercontent.com/HMCL-dev/HMCL/main/HMCL/src/main/resources/assets/modpack_data.txt";

    /** 字典最少有效行数，低于该值视为下载内容异常（防止把错误页面写进缓存） */
    private static final int MIN_VALID_ENTRIES = 100;
    /** 字段数（分号分隔） */
    private static final int FIELD_COUNT = 6;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(90);

    private ModDictionaryManager() {
    }

    // ==================== 加载 ====================

    /**
     * 读取字典原始行（含注释行，由调用方决定是否过滤）。
     * 优先读运行时更新缓存，缺失或异常时回退到 jar 内置资源。
     *
     * @param resourceName  classpath 资源路径，如 {@link #MOD_RESOURCE}
     * @param cacheFileName 缓存文件名，如 {@link #MOD_CACHE_FILE}
     * @return 文本行列表；两个来源都不可用时返回空列表（调用方应按「字典为空」降级处理）
     */
    public static List<String> readLines(String resourceName, String cacheFileName) {
        Path cache = cacheFile(cacheFileName);
        if (Files.isRegularFile(cache)) {
            try {
                List<String> lines = Files.readAllLines(cache, StandardCharsets.UTF_8);
                if (countEntries(lines) >= MIN_VALID_ENTRIES) {
                    return lines;
                }
                System.err.println("[ModDict] 缓存字典条目过少，回退内置数据: " + cache.toAbsolutePath());
            } catch (Exception e) {
                System.err.println("[ModDict] 读取缓存字典失败，回退内置数据: " + e.getMessage());
            }
        }
        return readBundledLines(resourceName);
    }

    /** 读取 jar 内置字典行；资源不存在或读取失败返回空列表 */
    public static List<String> readBundledLines(String resourceName) {
        if (resourceName == null || resourceName.isEmpty()) return List.of();
        try (InputStream in = ModDictionaryManager.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (in == null) {
                System.err.println("[ModDict] 未找到内置字典资源: " + resourceName);
                return List.of();
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                List<String> lines = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) lines.add(line);
                return lines;
            }
        } catch (Exception e) {
            System.err.println("[ModDict] 读取内置字典失败 " + resourceName + ": " + e.getMessage());
            return List.of();
        }
    }

    /** 统计字典有效条目数（跳过注释与空行，且字段数必须为 6） */
    public static int countEntries(List<String> lines) {
        if (lines == null) return 0;
        int count = 0;
        for (String line : lines) {
            if (isValidEntryLine(line)) count++;
        }
        return count;
    }

    /** 该行是否为一条合法的字典条目（非注释、非空、恰好 6 个字段） */
    public static boolean isValidEntryLine(String line) {
        if (line == null) return false;
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return false;
        int fields = 1;
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) == ';') fields++;
        }
        return fields == FIELD_COUNT;
    }

    // ==================== 状态 ====================

    /** 字典来源与规模，用于设置页展示 */
    public record DictionaryStatus(boolean fromCache, int entryCount, Instant updatedAt, Path path) {
        /** 人类可读的来源描述 */
        public String describeSource() {
            if (fromCache) return "已更新（本地缓存）";
            return entryCount > 0 ? "随包内置" : "不可用";
        }
    }

    /**
     * 查询某个字典当前的实际来源与条目数（会真实读一遍数据，仅供设置页手动刷新时调用）。
     */
    public static DictionaryStatus status(String resourceName, String cacheFileName) {
        Path cache = cacheFile(cacheFileName);
        if (Files.isRegularFile(cache)) {
            try {
                List<String> lines = Files.readAllLines(cache, StandardCharsets.UTF_8);
                int entries = countEntries(lines);
                if (entries >= MIN_VALID_ENTRIES) {
                    Instant time = null;
                    try {
                        time = Files.getLastModifiedTime(cache).toInstant();
                    } catch (IOException ignored) {
                    }
                    return new DictionaryStatus(true, entries, time, cache);
                }
            } catch (Exception ignored) {
            }
        }
        int bundled = countEntries(readBundledLines(resourceName));
        return new DictionaryStatus(false, bundled, null, cache);
    }

    /** 运行时更新缓存的完整路径 */
    public static Path cacheFile(String cacheFileName) {
        return DICTIONARY_DIR.resolve(cacheFileName);
    }

    // ==================== 更新 ====================

    /** 更新结果 */
    public record UpdateResult(boolean success, int entryCount, String message) {
    }

    /**
     * 从指定 URL 下载字典并写入本地缓存（成功后调用方需要让 {@link ModTranslations} 重新加载）。
     *
     * <p>安全措施：下载内容必须先通过校验（有效条目数 ≥ {@value #MIN_VALID_ENTRIES}）才会落盘，
     * 并采用「先写临时文件再原子替换」的方式，避免半截文件污染缓存。
     * 本方法会阻塞网络请求，请放到后台线程调用。
     *
     * @param url           字典地址（非空）
     * @param cacheFileName 缓存文件名，如 {@link #MOD_CACHE_FILE}
     */
    public static UpdateResult updateFromRemote(String url, String cacheFileName) {
        if (url == null || url.trim().isEmpty()) {
            return new UpdateResult(false, 0, "更新地址为空");
        }
        String target = url.trim();
        Path cache = cacheFile(cacheFileName);
        Path temp = cache.resolveSibling(cacheFileName + ".tmp");
        try {
            Files.createDirectories(DICTIONARY_DIR);
            String text = download(target);

            List<String> lines = new ArrayList<>();
            for (String line : text.split("\r\n|\r|\n", -1)) lines.add(line);
            int entries = countEntries(lines);
            if (entries < MIN_VALID_ENTRIES) {
                return new UpdateResult(false, entries,
                        "下载内容校验失败：有效条目仅 " + entries + " 条（可能是镜像返回了错误页面或被拦截）");
            }

            Files.writeString(temp, text, StandardCharsets.UTF_8);
            try {
                Files.move(temp, cache, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicFailed) {
                Files.move(temp, cache, StandardCopyOption.REPLACE_EXISTING);
            }

            reloadAll();
            return new UpdateResult(true, entries, "更新成功：" + entries + " 条");
        } catch (Exception e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
            }
            return new UpdateResult(false, 0, "更新失败：" + e.getMessage());
        } finally {
            removeStaleTempFiles();
        }
    }

    /** 让全部字典丢弃已建索引，下次访问时重新读取文件 */
    public static void reloadAll() {
        for (ModTranslations t : ModTranslations.values()) {
            t.reload();
        }
    }

    /** 清空本地缓存字典，回退到随包内置数据 */
    public static boolean clearCache(String cacheFileName) {
        try {
            boolean deleted = Files.deleteIfExists(cacheFile(cacheFileName));
            if (deleted) reloadAll();
            return deleted;
        } catch (IOException e) {
            System.err.println("[ModDict] 清理缓存失败: " + e.getMessage());
            return false;
        }
    }

    /** 读取配置里的字典地址，缺省时用默认地址 */
    public static String resolveUrl(Map<String, String> config, String key, String defaultUrl) {
        if (config == null) return defaultUrl;
        String value = config.get(key);
        return value == null || value.trim().isEmpty() ? defaultUrl : value.trim();
    }

    // ==================== 内部：网络 ====================

    private static String download(String url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", AppConfig.USER_AGENT + " (ModDictionaryUpdater)")
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        int code = response.statusCode();
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code + "（" + url + "）");
        }
        byte[] body = response.body();
        if (body == null || body.length == 0) {
            throw new IOException("响应内容为空");
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    private static void removeStaleTempFiles() {
        if (!Files.isDirectory(DICTIONARY_DIR)) return;
        try (Stream<Path> files = Files.list(DICTIONARY_DIR)) {
            files.filter(p -> p.getFileName().toString().endsWith(".tmp")).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
