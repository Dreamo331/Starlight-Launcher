package com.example.starlight.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 版本独立配置管理器（差异继承模型，参考 HMCL 的 overrideProperties / resolve(preset, instance) 设计）。
 *
 * <p>核心语义：<b>版本配置只保存被用户修改过的字段；未写入的字段动态继承全局配置。</b>
 * 有效配置 = 全局配置（starlight.ini [Launcher]）+ 版本覆盖（config.overrides.json）+ 启动期临时参数
 * （账号 / 自动选 Java 等，在启动链路注入）。「未覆盖」用「键不存在」表达，不能用 null 表达，
 * 否则无法区分「继承全局的值」和「覆盖为空」。
 *
 * <p>存储位置：{@code {游戏目录}/versions/{版本名}/config.overrides.json}，结构：
 * <pre>{@code
 * {
 *   "schemaVersion": 1,
 *   "overrides": {
 *     "java": { "maxMemory": 8192,
 *               "jvmArgs": { "$mode": "append", "value": ["-Dfile.encoding=UTF-8"] } },
 *     "game": { "width": 1920, "height": 1080, "versionIsolation": true }
 *   },
 *   "meta": { "overriddenPaths": ["java.maxMemory", "java.jvmArgs", "game.width", ...] }
 * }
 * }</pre>
 *
 * <p>合并规则：标量直接覆盖；{@code java.jvmArgs} 支持 {@code $mode}（replace / append / prepend / remove，
 * 按空白分词后对全局参数做数组运算）；删除某路径（恢复继承）后该字段重新跟随全局。
 */
public final class VersionConfigManager {

    private static final Logger log = LoggerFactory.getLogger(VersionConfigManager.class);

    /** 覆盖配置文件名（放在版本目录内，与 HMCL 的 instance-game-settings.json 同思路） */
    public static final String OVERRIDES_FILENAME = "config.overrides.json";
    public static final int SCHEMA_VERSION = 1;

    // ==================== 可覆盖字段路径（命名空间：java.* / game.*） ====================

    public static final String PATH_JAVA_PATH          = "java.path";
    public static final String PATH_MIN_MEMORY         = "java.minMemory";
    public static final String PATH_MAX_MEMORY         = "java.maxMemory";
    public static final String PATH_JVM_ARGS           = "java.jvmArgs";
    public static final String PATH_WIDTH              = "game.width";
    public static final String PATH_HEIGHT             = "game.height";
    public static final String PATH_FULLSCREEN         = "game.fullscreen";
    public static final String PATH_VERSION_ISOLATION  = "game.versionIsolation";
    public static final String PATH_GAME_ARGS          = "game.gameArgs";
    public static final String PATH_PRE_LAUNCH         = "game.preLaunchCommand";
    public static final String PATH_POST_EXIT          = "game.postExitCommand";
    public static final String PATH_GAME_LANGUAGE      = "game.language";

    /** 全部可覆盖路径（UI 据此渲染；账户/令牌等运行时字段不参与版本覆盖） */
    public static final List<String> OVERRIDABLE_PATHS = List.of(
            PATH_JAVA_PATH, PATH_MIN_MEMORY, PATH_MAX_MEMORY, PATH_JVM_ARGS,
            PATH_WIDTH, PATH_HEIGHT, PATH_FULLSCREEN, PATH_VERSION_ISOLATION,
            PATH_GAME_ARGS, PATH_PRE_LAUNCH, PATH_POST_EXIT, PATH_GAME_LANGUAGE);

    /** 覆盖路径 → starlight.ini [Launcher] 键名（有序，保证迁移与合并的稳定性） */
    private static final Map<String, String> PATH_TO_INI_KEY = new LinkedHashMap<>();
    static {
        PATH_TO_INI_KEY.put(PATH_JAVA_PATH,         "JavaPath");
        PATH_TO_INI_KEY.put(PATH_MIN_MEMORY,        "MinMemory");
        PATH_TO_INI_KEY.put(PATH_MAX_MEMORY,        "MaxMemory");
        PATH_TO_INI_KEY.put(PATH_JVM_ARGS,          "JvmArgs");
        PATH_TO_INI_KEY.put(PATH_WIDTH,             "WindowWidth");
        PATH_TO_INI_KEY.put(PATH_HEIGHT,            "WindowHeight");
        PATH_TO_INI_KEY.put(PATH_FULLSCREEN,        "Fullscreen");
        PATH_TO_INI_KEY.put(PATH_VERSION_ISOLATION, "VersionIsolation");
        PATH_TO_INI_KEY.put(PATH_GAME_ARGS,         "GameArgs");
        PATH_TO_INI_KEY.put(PATH_PRE_LAUNCH,        "PreLaunchCommand");
        PATH_TO_INI_KEY.put(PATH_POST_EXIT,         "PostExitCommand");
        PATH_TO_INI_KEY.put(PATH_GAME_LANGUAGE,     "GameLanguage");
    }

    // ==================== 旧配置迁移（version-settings.properties → config.overrides.json） ====================

    private static final String LEGACY_FILENAME = "version-settings.properties";
    private static final Map<String, String> LEGACY_KEY_TO_PATH = Map.ofEntries(
            Map.entry("javaPath",          PATH_JAVA_PATH),
            Map.entry("minMemory",         PATH_MIN_MEMORY),
            Map.entry("maxMemory",         PATH_MAX_MEMORY),
            Map.entry("overrideJvmArgs",   PATH_JVM_ARGS),
            Map.entry("overrideGameArgs",  PATH_GAME_ARGS),
            Map.entry("windowWidth",       PATH_WIDTH),
            Map.entry("windowHeight",      PATH_HEIGHT),
            Map.entry("fullscreen",        PATH_FULLSCREEN),
            Map.entry("preLaunchCommand",  PATH_PRE_LAUNCH),
            Map.entry("postExitCommand",   PATH_POST_EXIT));

    private static final Gson PRETTY_GSON = new GsonBuilder()
            .setPrettyPrinting().disableHtmlEscaping().create();

    private VersionConfigManager() {}

    // ================================================================
    //  路径 / 存储
    // ================================================================

    /** 版本覆盖文件的完整路径 */
    public static Path getOverridesPath(String gameDir, String version) {
        return Paths.get(gameDir == null ? "" : gameDir, "versions", version, OVERRIDES_FILENAME);
    }

    /** 该版本是否存在覆盖配置（文件存在且至少覆盖了一个字段） */
    public static boolean hasOverrides(String gameDir, String version) {
        return !overriddenPaths(gameDir, version).isEmpty();
    }

    /** 读取完整文档；不存在时返回空结构（不落盘） */
    public static JsonObject loadDoc(String gameDir, String version) {
        Path path = getOverridesPath(gameDir, version);
        if (!Files.exists(path)) return newDoc();
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            if (json.isBlank()) return newDoc();
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("overrides") || !root.get("overrides").isJsonObject())
                root.add("overrides", new JsonObject());
            if (!root.has("meta") || !root.get("meta").isJsonObject())
                root.add("meta", new JsonObject());
            JsonObject meta = root.getAsJsonObject("meta");
            if (!meta.has("overriddenPaths") || !meta.get("overriddenPaths").isJsonArray())
                meta.add("overriddenPaths", new JsonArray());
            return root;
        } catch (Exception e) {
            log.warn("Failed to read version overrides: {} / {}", version, path, e);
            return newDoc();
        }
    }

    /** 写回完整文档 */
    public static void saveDoc(String gameDir, String version, JsonObject doc) {
        Path path = getOverridesPath(gameDir, version);
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.write(path, PRETTY_GSON.toJson(doc).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.error("Failed to save version overrides: {} / {}", version, path, e);
        }
    }

    /** 读取 overrides 对象（可能为空，不会为 null） */
    public static JsonObject loadOverrides(String gameDir, String version) {
        return loadDoc(gameDir, version).getAsJsonObject("overrides");
    }

    private static JsonObject newDoc() {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.add("overrides", new JsonObject());
        JsonObject meta = new JsonObject();
        meta.add("overriddenPaths", new JsonArray());
        root.add("meta", meta);
        return root;
    }

    // ================================================================
    //  覆盖状态查询 / 修改（HMCL overrideProperties 的增删查）
    // ================================================================

    /** 该版本覆盖了哪些字段路径 */
    public static Set<String> overriddenPaths(String gameDir, String version) {
        Set<String> result = new LinkedHashSet<>();
        JsonObject doc = loadDoc(gameDir, version);
        JsonObject overrides = doc.getAsJsonObject("overrides");
        for (JsonElement el : doc.getAsJsonObject("meta").getAsJsonArray("overriddenPaths")) {
            String path = el.getAsString();
            if (getElementAtPath(overrides, path) != null) result.add(path);
        }
        return result;
    }

    /** 某字段是否被该版本覆盖 */
    public static boolean isOverridden(String gameDir, String version, String path) {
        JsonObject overrides = loadDoc(gameDir, version).getAsJsonObject("overrides");
        return getElementAtPath(overrides, path) != null;
    }

    /**
     * JVM 参数覆盖的运算模式。
     * @return replace / append / prepend / remove；未覆盖返回 null
     */
    public static String jvmArgsMode(String gameDir, String version) {
        JsonObject overrides = loadDoc(gameDir, version).getAsJsonObject("overrides");
        JsonElement el = getElementAtPath(overrides, PATH_JVM_ARGS);
        if (el == null) return null;
        if (el.isJsonObject() && el.getAsJsonObject().has("$mode")
                && el.getAsJsonObject().get("$mode").isJsonPrimitive()) {
            return el.getAsJsonObject().get("$mode").getAsString();
        }
        return "replace";
    }

    /**
     * JVM 参数覆盖里保存的增量 token（仅 append / prepend / remove 模式有意义）。
     *
     * <p>供设置页回显用：增量模式存的是「增量」而不是合并结果，
     * 若回显合并结果，用户再次保存就会把完整参数当成增量再加一遍。
     *
     * @return 增量 token 列表；未覆盖或 replace 模式返回空列表
     */
    public static List<String> jvmArgsOverrideTokens(String gameDir, String version) {
        JsonObject overrides = loadDoc(gameDir, version).getAsJsonObject("overrides");
        JsonElement el = getElementAtPath(overrides, PATH_JVM_ARGS);
        if (el == null || !el.isJsonObject()) return List.of();
        JsonObject spec = el.getAsJsonObject();
        if (!spec.has("value") || !spec.get("value").isJsonArray()) return List.of();
        List<String> tokens = new ArrayList<>();
        for (JsonElement token : spec.getAsJsonArray("value")) {
            if (token.isJsonPrimitive()) tokens.add(token.getAsString());
        }
        return tokens;
    }

    /**
     * 写入一个标量覆盖值；与全局值相同时自动改为「恢复继承」（保持差异最小化）。
     * @return 写入后该字段是否处于覆盖状态
     */
    public static boolean setOverrideIfChanged(String gameDir, String version, String path,
                                               String value, Map<String, String> globalIni) {
        String globalValue = globalIni.getOrDefault(PATH_TO_INI_KEY.get(path), "");
        if (value.equals(globalValue)) {
            clearOverride(gameDir, version, path);
            return false;
        }
        JsonObject doc = loadDoc(gameDir, version);
        setElementAtPath(doc.getAsJsonObject("overrides"), path,
                value.isEmpty() ? JsonNull.INSTANCE : new JsonPrimitive(value));
        markOverridden(doc, path, true);
        saveDoc(gameDir, version, doc);
        return true;
    }

    /** 写入布尔覆盖值；与全局相同时自动恢复继承。 @return 写入后是否处于覆盖状态 */
    public static boolean setBooleanOverrideIfChanged(String gameDir, String version, String path,
                                                      boolean value, Map<String, String> globalIni) {
        String globalValue = globalIni.getOrDefault(PATH_TO_INI_KEY.get(path), "false");
        if (String.valueOf(value).equalsIgnoreCase(globalValue.trim())) {
            clearOverride(gameDir, version, path);
            return false;
        }
        JsonObject doc = loadDoc(gameDir, version);
        setElementAtPath(doc.getAsJsonObject("overrides"), path, new JsonPrimitive(value));
        markOverridden(doc, path, true);
        saveDoc(gameDir, version, doc);
        return true;
    }

    /**
     * 写入 JVM 参数覆盖（数组运算模式）。
     * @param mode  replace / append / prepend / remove（replace 时 value 为空表示恢复继承）
     */
    public static void setJvmArgsOverride(String gameDir, String version, String mode, List<String> value) {
        JsonObject doc = loadDoc(gameDir, version);
        JsonObject overrides = doc.getAsJsonObject("overrides");
        if ("replace".equals(mode)) {
            if (value == null || value.isEmpty()) {
                clearOverride(gameDir, version, PATH_JVM_ARGS);
                return;
            }
            setElementAtPath(overrides, PATH_JVM_ARGS, new JsonPrimitive(String.join(" ", value)));
        } else {
            JsonObject spec = new JsonObject();
            spec.addProperty("$mode", mode);
            JsonArray arr = new JsonArray();
            if (value != null) value.forEach(arr::add);
            spec.add("value", arr);
            setElementAtPath(overrides, PATH_JVM_ARGS, spec);
        }
        markOverridden(doc, PATH_JVM_ARGS, true);
        saveDoc(gameDir, version, doc);
    }

    /** 恢复继承：删除该字段的覆盖（HMCL 的从 overrideProperties 移除语义） */
    public static void clearOverride(String gameDir, String version, String path) {
        JsonObject doc = loadDoc(gameDir, version);
        JsonObject overrides = doc.getAsJsonObject("overrides");
        if (removeElementAtPath(overrides, path)) {
            markOverridden(doc, path, false);
            saveDoc(gameDir, version, doc);
        }
    }

    /** 全部恢复继承：删除覆盖文件 */
    public static boolean clearAll(String gameDir, String version) {
        try {
            return Files.deleteIfExists(getOverridesPath(gameDir, version));
        } catch (IOException e) {
            log.warn("Failed to delete version overrides: {} / {}", version, gameDir, e);
            return false;
        }
    }

    /**
     * 把源版本的独立设置深拷贝到目标版本（「复制配置文件到其他版本」）。
     *
     * <p>目标版本原有的覆盖内容会被整体替换；{@code meta.overriddenPaths} 按拷贝结果重建，
     * 保证与 {@link #overriddenPaths} 的读取逻辑一致。
     *
     * @return 参数非法或源版本没有任何覆盖时返回 false（不写盘）
     */
    public static boolean copyOverrides(String gameDir, String fromVersion, String toVersion) {
        if (gameDir == null || gameDir.isEmpty()
                || fromVersion == null || fromVersion.isEmpty()
                || toVersion == null || toVersion.isEmpty()
                || fromVersion.equals(toVersion)) {
            return false;
        }
        JsonObject srcOverrides = loadOverrides(gameDir, fromVersion);
        if (srcOverrides.size() == 0) return false;

        JsonObject doc = newDoc();
        // 用 JSON 序列化做深拷贝，避免源与目标文档共享同一个节点实例
        JsonObject copied = JsonParser.parseString(srcOverrides.toString()).getAsJsonObject();
        doc.add("overrides", copied);
        JsonArray arr = new JsonArray();
        for (String path : OVERRIDABLE_PATHS) {
            if (getElementAtPath(copied, path) != null) arr.add(path);
        }
        doc.getAsJsonObject("meta").add("overriddenPaths", arr);
        saveDoc(gameDir, toVersion, doc);
        return true;
    }

    private static void markOverridden(JsonObject doc, String path, boolean overridden) {
        JsonArray arr = doc.getAsJsonObject("meta").getAsJsonArray("overriddenPaths");
        for (int i = 0; i < arr.size(); i++) {
            if (path.equals(arr.get(i).getAsString())) {
                if (!overridden) arr.remove(i);
                return;
            }
        }
        if (overridden) arr.add(path);
    }

    // ================================================================
    //  合并：全局 INI + 版本覆盖 → 有效配置（启动链路唯一入口）
    // ================================================================

    /**
     * 构建有效配置：全局 ini 拷贝后应用版本覆盖。版本为空或无覆盖时原样返回。
     * 顺带执行旧版 version-settings.properties 的一次性迁移。
     */
    public static Map<String, String> buildEffectiveIni(Map<String, String> globalIni,
                                                        String gameDir, String version) {
        Map<String, String> effective = new HashMap<>(globalIni);
        if (version == null || version.isEmpty() || gameDir == null || gameDir.isEmpty()) return effective;
        migrateLegacyIfNeeded(gameDir, version, effective);

        JsonObject overrides = loadOverrides(gameDir, version);
        if (overrides.size() == 0) return effective;

        for (Map.Entry<String, String> e : PATH_TO_INI_KEY.entrySet()) {
            JsonElement el = getElementAtPath(overrides, e.getKey());
            if (el == null) continue;
            if (PATH_JVM_ARGS.equals(e.getKey())) {
                effective.put(e.getValue(),
                        applyJvmArgsOverride(effective.getOrDefault(e.getValue(), ""), el));
            } else {
                effective.put(e.getValue(), scalarToString(el));
            }
        }
        return effective;
    }

    /** 计算某字段当前的有效值（全局 + 覆盖合并后） */
    public static String effectiveScalar(Map<String, String> globalIni,
                                         String gameDir, String version, String path) {
        String iniKey = PATH_TO_INI_KEY.get(path);
        if (iniKey == null) return "";
        return buildEffectiveIni(globalIni, gameDir, version).getOrDefault(iniKey, "");
    }

    /** 覆盖路径对应的 starlight.ini 键名 */
    public static String iniKeyFor(String path) {
        return PATH_TO_INI_KEY.get(path);
    }

    /** jvmArgs 的 $mode 数组运算：按空白分词，对全局参数做追加/前置/移除/替换 */
    private static String applyJvmArgsOverride(String globalArgs, JsonElement el) {
        if (el == null || el.isJsonNull()) return "";
        if (el.isJsonPrimitive()) return el.getAsString();
        if (!el.isJsonObject()) return globalArgs;

        JsonObject spec = el.getAsJsonObject();
        String mode = spec.has("$mode") && spec.get("$mode").isJsonPrimitive()
                ? spec.get("$mode").getAsString() : "replace";
        List<String> value = new ArrayList<>();
        if (spec.has("value")) {
            JsonElement v = spec.get("value");
            if (v.isJsonPrimitive()) value.add(v.getAsString());
            else if (v.isJsonArray()) v.getAsJsonArray().forEach(x -> value.add(x.getAsString()));
        }

        if ("replace".equals(mode)) return String.join(" ", value);

        List<String> base = tokenize(globalArgs);
        switch (mode) {
            case "append"  -> base.addAll(value);
            case "prepend" -> base.addAll(0, value);
            case "remove"  -> {
                Set<String> remove = new LinkedHashSet<>(value);
                base.removeIf(remove::contains);
            }
            default -> { return String.join(" ", value); }
        }
        return String.join(" ", base);
    }

    /** JVM 参数按空白分词（与启动库 LaunchInfo.setJvmArgs 的切分规则一致） */
    public static List<String> tokenize(String args) {
        List<String> tokens = new ArrayList<>();
        if (args != null && !args.isBlank()) {
            for (String t : args.trim().split("\\s+")) if (!t.isEmpty()) tokens.add(t);
        }
        return tokens;
    }

    private static String scalarToString(JsonElement el) {
        if (el == null || el.isJsonNull()) return "";
        if (el.isJsonPrimitive()) return el.getAsString();
        return el.toString();
    }

    // ================================================================
    //  JSON 点路径操作
    // ================================================================

    /** 按点路径取值；不存在返回 null（「未覆盖」与「覆盖为 null」由此区分） */
    private static JsonElement getElementAtPath(JsonObject overrides, String path) {
        String[] parts = path.split("\\.");
        JsonElement current = overrides;
        for (int i = 0; i < parts.length; i++) {
            if (current == null || !current.isJsonObject()) return null;
            JsonObject obj = current.getAsJsonObject();
            if (!obj.has(parts[i])) return null;
            current = obj.get(parts[i]);
        }
        return current;
    }

    private static void setElementAtPath(JsonObject overrides, String path, JsonElement value) {
        String[] parts = path.split("\\.");
        JsonObject current = overrides;
        for (int i = 0; i < parts.length - 1; i++) {
            if (!current.has(parts[i]) || !current.get(parts[i]).isJsonObject()) {
                JsonObject child = new JsonObject();
                current.add(parts[i], child);
            }
            current = current.getAsJsonObject(parts[i]);
        }
        current.add(parts[parts.length - 1], value);
    }

    /** 按点路径删除；父节点变空时逐级回收。 @return 是否确有删除 */
    private static boolean removeElementAtPath(JsonObject overrides, String path) {
        String[] parts = path.split("\\.");
        List<JsonObject> chain = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        JsonObject current = overrides;
        for (int i = 0; i < parts.length - 1; i++) {
            if (!current.has(parts[i]) || !current.get(parts[i]).isJsonObject()) return false;
            chain.add(current);
            keys.add(parts[i]);
            current = current.getAsJsonObject(parts[i]);
        }
        String leaf = parts[parts.length - 1];
        if (!current.has(leaf)) return false;
        current.remove(leaf);
        // 自底向上回收空对象，避免残留 "java": {} 之类的空壳
        for (int i = chain.size() - 1; i >= 0; i--) {
            JsonObject parent = chain.get(i);
            if (parent.getAsJsonObject(keys.get(i)).size() == 0) parent.remove(keys.get(i));
            else break;
        }
        return true;
    }

    // ================================================================
    //  旧配置迁移（设计文档 §8：与全局逐字段比较，相同字段不写入）
    // ================================================================

    /**
     * 一次性迁移：读取旧版 version-settings.properties，与全局比较后把不同的字段写入
     * config.overrides.json，随后把旧文件改名为 .migrated 备份。
     */
    private static void migrateLegacyIfNeeded(String gameDir, String version, Map<String, String> globalIni) {
        Path legacy = Paths.get(gameDir, "versions", version, LEGACY_FILENAME);
        if (!Files.exists(legacy)) return;
        if (Files.exists(getOverridesPath(gameDir, version))) return; // 已迁移过

        try {
            Map<String, String> legacyMap = new HashMap<>();
            for (String line : Files.readAllLines(legacy, StandardCharsets.UTF_8)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
                int eq = line.indexOf('=');
                if (eq > 0) legacyMap.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }

            JsonObject doc = newDoc();
            JsonObject overrides = doc.getAsJsonObject("overrides");
            int migrated = 0;
            for (Map.Entry<String, String> e : legacyMap.entrySet()) {
                String path = LEGACY_KEY_TO_PATH.get(e.getKey());
                if (path == null || e.getValue().isEmpty()) continue;
                String globalValue = globalIni.getOrDefault(PATH_TO_INI_KEY.get(path), "");
                if (e.getValue().equals(globalValue)) continue; // 与全局相同 → 不算覆盖
                setElementAtPath(overrides, path, new JsonPrimitive(e.getValue()));
                markOverridden(doc, path, true);
                migrated++;
            }
            saveDoc(gameDir, version, doc);
            Files.move(legacy, legacy.resolveSibling(LEGACY_FILENAME + ".migrated"),
                    StandardCopyOption.REPLACE_EXISTING);
            log.info("Migrated legacy version settings for {}: {} field(s) overridden", version, migrated);
        } catch (IOException e) {
            log.warn("Failed to migrate legacy version settings: {} / {}", version, legacy, e);
        }
    }
}
