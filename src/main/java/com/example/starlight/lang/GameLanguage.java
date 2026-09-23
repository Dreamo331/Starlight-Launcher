package com.example.starlight.lang;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 游戏语言管理器 —— 通过修改 {@code options.txt} 实现 Minecraft 游戏语言切换。
 *
 * <p>核心原理：Minecraft 启动时自动读取 {@code options.txt} 中的 {@code lang} 字段，
 * 启动器在游戏进程启动前写入该字段，让游戏"自然而然"加载目标语言。</p>
 *
 * <h3>文件定位规则</h3>
 * <ul>
 *   <li>版本隔离启用：{@code .minecraft/versions/&lt;version&gt;/options.txt}</li>
 *   <li>版本隔离禁用：{@code .minecraft/options.txt}</li>
 * </ul>
 */
public class GameLanguage {

    private static final Logger LOG = LoggerFactory.getLogger(GameLanguage.class);

    /** options.txt 中的语言键名 */
    public static final String OPTIONS_LANG_KEY = "lang";

    /** 语言显示名称 → Minecraft 语言代码 映射表 */
    public static final Map<String, String> LANGUAGE_MAP = new LinkedHashMap<>();

    static {
        LANGUAGE_MAP.put("简体中文", "zh_cn");
        LANGUAGE_MAP.put("繁体中文", "zh_tw");
        LANGUAGE_MAP.put("English (US)", "en_us");
        LANGUAGE_MAP.put("English (UK)", "en_gb");
        LANGUAGE_MAP.put("日本語", "ja_jp");
        LANGUAGE_MAP.put("한국어", "ko_kr");
        LANGUAGE_MAP.put("Français", "fr_fr");
        LANGUAGE_MAP.put("Deutsch", "de_de");
        LANGUAGE_MAP.put("Español", "es_es");
        LANGUAGE_MAP.put("Italiano", "it_it");
        LANGUAGE_MAP.put("Português (BR)", "pt_br");
        LANGUAGE_MAP.put("Русский", "ru_ru");
        LANGUAGE_MAP.put("العربية", "ar_sa");
        LANGUAGE_MAP.put("ไทย", "th_th");
        LANGUAGE_MAP.put("Tiếng Việt", "vi_vn");
        LANGUAGE_MAP.put("Polski", "pl_pl");
        LANGUAGE_MAP.put("Nederlands", "nl_nl");
        LANGUAGE_MAP.put("Čeština", "cs_cz");
        LANGUAGE_MAP.put("Svenska", "sv_se");
        LANGUAGE_MAP.put("Türkçe", "tr_tr");
    }

    // ================================================================
    //  路径解析
    // ================================================================

    /**
     * 解析 options.txt 的实际路径。
     *
     * @param gameDir          游戏根目录（如 {@code .minecraft}）
     * @param version          版本 ID（如 {@code 1.20.1}）
     * @param versionIsolation 是否启用版本隔离
     * @return options.txt 的完整路径
     */
    public static Path resolveOptionsPath(String gameDir, String version, boolean versionIsolation) {
        Path base = Path.of(gameDir);
        if (versionIsolation && version != null && !version.isEmpty()) {
            return base.resolve("versions").resolve(version).resolve("options.txt");
        }
        return base.resolve("options.txt");
    }

    // ================================================================
    //  读写操作
    // ================================================================

    /**
     * 读取 options.txt 全部键值对。
     *
     * @param optionsPath options.txt 路径
     * @return 键值对 Map（空 Map 表示文件不存在或读取失败）
     */
    public static Map<String, String> readOptions(Path optionsPath) {
        Map<String, String> map = new LinkedHashMap<>();
        if (!Files.exists(optionsPath)) {
            return map;
        }
        try {
            List<String> lines = Files.readAllLines(optionsPath, StandardCharsets.UTF_8);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String key = line.substring(0, colon).trim();
                    String value = line.substring(colon + 1).trim();
                    map.put(key, value);
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed to read options.txt: {} ({})", optionsPath, e.getMessage());
        }
        return map;
    }

    /**
     * 将键值对写回 options.txt（UTF-8 编码）。
     *
     * @param optionsPath options.txt 路径
     * @param options     要写入的全部键值对
     * @throws IOException 写入失败时抛出
     */
    public static void writeOptions(Path optionsPath, Map<String, String> options) throws IOException {
        // 确保父目录存在
        Path parent = optionsPath.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        List<String> lines = options.entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue())
                .toList();

        Files.write(optionsPath, lines, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        LOG.info("Written options.txt: {} ({} entries)", optionsPath, lines.size());
    }

    // ================================================================
    //  语言设置核心方法
    // ================================================================

    /**
     * 设置指定版本的游戏语言。
     *
     * <p>若 {@code options.txt} 已存在，则仅修改 {@code lang} 字段，保留其他配置不变；
     * 若不存在，则创建新文件仅写入语言设置。</p>
     *
     * @param gameDir          游戏根目录
     * @param version          版本 ID
     * @param versionIsolation 是否启用版本隔离
     * @param languageCode     Minecraft 语言代码（如 {@code zh_cn}）
     * @return true 表示操作成功，false 表示失败
     */
    public static boolean setLanguage(String gameDir, String version,
                                       boolean versionIsolation, String languageCode) {
        if (gameDir == null || gameDir.isEmpty()) {
            LOG.warn("Failed to set game language: gameDir is empty");
            return false;
        }
        if (languageCode == null || languageCode.isEmpty()) {
            LOG.warn("Failed to set game language: languageCode is empty");
            return false;
        }

        Path optionsPath = resolveOptionsPath(gameDir, version, versionIsolation);
        Map<String, String> options = readOptions(optionsPath);

        // 修改语言字段
        options.put(OPTIONS_LANG_KEY, languageCode);

        try {
            writeOptions(optionsPath, options);
            LOG.info("Game language set to '{}' (code: {}), path: {}",
                    languageCode, languageCode, optionsPath);
            return true;
        } catch (IOException e) {
            LOG.error("Failed to write options.txt: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 读取当前已设置的语言代码。
     *
     * @param gameDir          游戏根目录
     * @param version          版本 ID
     * @param versionIsolation 是否启用版本隔离
     * @return 语言代码，若未设置或读取失败返回 {@code null}
     */
    public static String getCurrentLanguage(String gameDir, String version, boolean versionIsolation) {
        Path optionsPath = resolveOptionsPath(gameDir, version, versionIsolation);
        Map<String, String> options = readOptions(optionsPath);
        return options.get(OPTIONS_LANG_KEY);
    }

    /**
     * 根据显示名称获取语言代码。
     *
     * @param displayName 语言显示名称（如 "简体中文"）
     * @return 对应的 Minecraft 语言代码，未找到时返回 {@code zh_cn}
     */
    public static String getCodeByDisplayName(String displayName) {
        return LANGUAGE_MAP.getOrDefault(displayName, "zh_cn");
    }

    /**
     * 根据语言代码获取显示名称。
     *
     * @param code Minecraft 语言代码（如 {@code zh_cn}）
     * @return 显示名称，未找到时返回代码本身
     */
    public static String getDisplayNameByCode(String code) {
        for (Map.Entry<String, String> entry : LANGUAGE_MAP.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(code)) {
                return entry.getKey();
            }
        }
        return code;
    }
}
