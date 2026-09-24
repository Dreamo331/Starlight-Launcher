package com.example.starlight.main;

import com.example.starlight.auth.AccountManager;
import com.example.starlight.auth.AccountManager.Account;
import com.example.starlight.util.CredentialStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

/**
 * 配置文件管理 (starlight.ini 读写)
 * 负责读取/保存启动器配置，以及交互式配置输入
 */
public class ConfigManager {

    private static final Logger log = LoggerFactory.getLogger(ConfigManager.class);

    private static final String CONFIG_DIR = "Starlight-Launcher";
    private static final String CONFIG_FILE = CONFIG_DIR + "/starlight.ini";
    private static final String CLIENT_CONFIG_FILE = CONFIG_DIR + "/starlight-client.ini";

    // ================================================================
    //  读取配置
    // ================================================================

    /**
     * 读取 starlight.ini 配置文件
     */
    public static Map<String, String> readConfig() {
        Map<String, String> config = new HashMap<>();
        Path configPath = Paths.get(CONFIG_FILE);
        if (!Files.exists(configPath)) {
            configPath = Paths.get(CONFIG_DIR, "starlight.ini");
        }
        if (!Files.exists(configPath)) return config;

        try {
            List<String> lines = Files.readAllLines(configPath, StandardCharsets.UTF_8);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")
                    || line.startsWith("[")) continue;
                int eq = line.indexOf('=');
                if (eq > 0) {
                    String key = line.substring(0, eq).trim();
                    // 使用 lastIndexOf 获取最后一个 '=', 支持值中包含 '=' 的场景
                    String val = line.substring(eq + 1).trim();
                    config.put(key, val);
                }
            }

            // 从安全凭证存储中读取账号信息
            Map<String, String> creds = CredentialStore.loadCredentialMap();
            for (Map.Entry<String, String> e : creds.entrySet()) {
                config.put(e.getKey(), e.getValue());
            }

            log.info("Config file loaded: {}", configPath.toAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to load config file: {}", e.getMessage());
        }
        return config;
    }

    /** 便捷方法：读取单个配置项 */
    public static String get(String key) {
        return readConfig().get(key);
    }

    // ================================================================
    //  保存配置
    // ================================================================

    /**
     * 保存配置到 starlight.ini（保留 [Account] 段，仅覆盖 [Launcher] 段内容）
     */
    public static void saveConfig(Map<String, String> config) {
        try {
            Path configDir = Paths.get(CONFIG_DIR);
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }
            Path configPath = configDir.resolve("starlight.ini");

            // 读取现有文件
            List<String> oldLines = new ArrayList<>();
            if (Files.exists(configPath)) {
                oldLines = Files.readAllLines(configPath, StandardCharsets.UTF_8);
            }

            // 定义需要写入的 Launcher 字段及其默认值
            String[] launcherKeys = {
                "GameDir", "Version", "JavaPath",
                "MinMemory", "MaxMemory",
                "WindowWidth", "WindowHeight",
                "Fullscreen", "VersionIsolation",
                "JvmArgs", "GameArgs", "GameLanguage",
                "PreLaunchCommand", "PostExitCommand",
                "ServerUrl", "GameFolders", "GameDirNames", "ParallelLaunch",
                "ParallelVerify", "StreamingSha1", "FastVerify", "SilentLoginMode"
            };

            List<String> newLines = new ArrayList<>();
            boolean inLauncherSection = false;
            boolean hasLauncherSection = false;
            Set<String> seenLauncherKeys = new HashSet<>();

            // Pass 1: 复制现有行，替换 [Launcher] 段内的字段，并记录已存在的字段名
            // 同时跳过 [Account] 段中的敏感字段（凭证已迁移到独立存储）
            boolean inAccountSection = false;
            boolean shouldWriteLauncherSection = true;

            for (String line : oldLines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    // 离开 [Launcher] 段时，补全缺失的新字段
                    if (inLauncherSection) {
                        for (String key : launcherKeys) {
                            if (!seenLauncherKeys.contains(key)) {
                                newLines.add(key + "=" + config.getOrDefault(key, ""));
                            }
                        }
                        inLauncherSection = false;
                    }
                    inLauncherSection = trimmed.equalsIgnoreCase("[Launcher]");
                    if (inLauncherSection) {
                        hasLauncherSection = true;
                        seenLauncherKeys.clear();
                    }
                    inAccountSection = trimmed.equalsIgnoreCase("[Account]");
                    if (!inAccountSection) {
                        shouldWriteLauncherSection = true;
                    }
                    newLines.add(line);
                    continue;
                }
                if (inLauncherSection) {
                    // 替换 [Launcher] 段内已有的字段
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        String key = line.substring(0, eq).trim();
                        seenLauncherKeys.add(key);
                        if (config.containsKey(key)) {
                            newLines.add(key + "=" + config.get(key));
                        } else {
                            newLines.add(line);
                        }
                    } else {
                        newLines.add(line);
                    }
                } else if (!inAccountSection) {
                    // [Account] 段内容不再写入 starlight.ini
                    newLines.add(line);
                }
                // 如果在 [Account] 段，跳过该行（凭证已迁移）
            }

            // Pass 1.5: 如果文件末尾还在 [Launcher] 段内，也补全缺失字段
            if (inLauncherSection) {
                for (String key : launcherKeys) {
                    if (!seenLauncherKeys.contains(key)) {
                        newLines.add(key + "=" + config.getOrDefault(key, ""));
                    }
                }
            }

            // Pass 2: 如果没有 [Launcher] 段，追加一个并写入所有字段
            if (!hasLauncherSection) {
                if (!newLines.isEmpty() && !newLines.get(newLines.size() - 1).isEmpty()) {
                    newLines.add("");
                }
                newLines.add("[Launcher]");
                for (String key : launcherKeys) {
                    if (config.containsKey(key)) {
                        newLines.add(key + "=" + config.get(key));
                    }
                }
            }

            Files.write(configPath, newLines, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
            log.info("Config saved to: {}", configPath.toAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to save config: {}", e.getMessage());
        }

        // 同步保存凭证到独立存储
        String at = config.get("AccessToken");
        String rt = config.get("RefreshToken");
        String uuid = config.get("UUID");
        String userName = config.get("UserName");
        if (at != null || rt != null || uuid != null || userName != null) {
            CredentialStore.saveCredentials(at, rt, uuid, userName);
        }
    }

    // ================================================================
    //  客户端配置 (starlight-client.ini)
    // ================================================================

    private static final String[] CLIENT_KEYS = {
        "Theme", "Language", "ScreenshotDir",
        "DownloadSource", "DownloadThreads",
        "ShowSplash", "LastPlayed",
        "WindowX", "WindowY", "WindowMaximized", "KeepWindowPosition",
        // 标题栏窗口按钮样式：true=仿 macOS 三点式（默认），false=减号 + 叉号
        "MacStyleWindowButtons",
        "ScrollSpeed",
        // 个性化配置：侧边栏显隐 / 高对比度 / 背景图片 / 文件扫描 / 联机与反馈输入
        "ShowSidebar", "HighContrast", "BackgroundImage", "FileScan",
        "FrpPort", "FeedbackEmail", "SlanPassword",
        "HomeQuickLaunchSave", "HomeQuickManageMod",
        // 色盲辅助（外部矫正工具 ColorBlindOverlay.exe 的配置）
        "ColorBlindMode", "ColorBlindType", "ColorBlindStrength",
        "ColorBlindWindow", "ColorBlindAutoEnable", "ColorBlindRemember",
        // 高级设置页的下载/显示偏好与开发者页开关（漏登记会导致保存后重启即丢）
        "ModFileNameFormat", "ModDisplayStyle", "IgnoreQuiltLoader",
        "UseHighPerformanceGPU", "JvmPreheat",
        "DebugMode", "ChineseLog",
        // 高级设置 → AI 配置（崩溃日志诊断用的 API 地址 / Key / 模型 / 预设服务商，
        // 未登记的话点「保存」写不进 starlight-client.ini，重启就丢）
        "AiApi", "Aiapikey", "AiModel", "AiProvider"
    };

    public static Map<String, String> readClientConfig() {
        Map<String, String> result = new HashMap<>();
        Path p = Paths.get(CLIENT_CONFIG_FILE);
        if (!Files.exists(p)) return result;
        try {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(";") || line.startsWith("#") || line.startsWith("[")) continue;
                int eq = line.indexOf('=');
                if (eq > 0) result.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        } catch (IOException ignored) {}
        return result;
    }

    public static void saveClientConfig(Map<String, String> config) {
        // 读-改-写：先读取现有配置，未传入的键保留旧值，
        // 避免局部保存（如窗口防抖仅传窗口尺寸）清空主题/语言等其他配置
        Map<String, String> merged = readClientConfig();
        for (String key : CLIENT_KEYS) {
            if (config.containsKey(key)) merged.put(key, config.get(key));
        }
        try {
            Path dir = Paths.get(CONFIG_DIR);
            if (!Files.exists(dir)) Files.createDirectories(dir);
            Path p = Paths.get(CLIENT_CONFIG_FILE);
            List<String> lines = new ArrayList<>();
            lines.add("[Client]");
            for (String key : CLIENT_KEYS) {
                if (merged.containsKey(key)) {
                    lines.add(key + "=" + merged.get(key));
                }
            }
            Files.write(p, lines, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("Client config saved to: {}", p.toAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to save client config: {}", e.getMessage());
        }
    }

    // ================================================================
    //  交互式配置
    // ================================================================

    /**
     * 交互式配置输入
     */
    public static Map<String, String> interactiveConfig(Scanner scanner, Map<String, String> defaults) {
        // 优先从 AccountManager 的当前账号补充登录信息
        if (defaults.get("UserName") == null) {
            try {
                Account account = AccountManager.getCurrentAccount();
                if (account != null) {
                    String userName = account.name;
                    if (userName != null && !userName.isEmpty()) {
                        defaults.put("UserName", userName);
                        defaults.put("UUID", account.id != null ? account.id : "");
                        defaults.put("AccessToken", account.accessToken != null ? account.accessToken : "");
                    }
                }
            } catch (Exception ignored) {
                // 降级：从旧 login.json 读取
                Path loginJson = Paths.get(
                    System.getProperty("user.home"), ".starlight-launcher", "login.json");
                if (Files.exists(loginJson)) {
                    try {
                        String json = Files.readString(loginJson, StandardCharsets.UTF_8);
                        if (json.contains("username") && defaults.get("UserName") == null) {
                            defaults.put("UserName", extractJsonSimple(json, "username"));
                        }
                        if (json.contains("uuid") && defaults.get("UUID") == null) {
                            defaults.put("UUID", extractJsonSimple(json, "uuid"));
                        }
                    } catch (IOException ignored2) {}
                }
            }
        }

        log.info("--- Launch Configuration ---");
        defaults.put("GameDir",     promptInput(scanner, "Game directory (.minecraft path)",
                                    defaults.getOrDefault("GameDir", ".minecraft")));
        defaults.put("Version",     promptInput(scanner, "Version name",
                                    defaults.getOrDefault("Version", "")));
        defaults.put("JavaPath",    promptInput(scanner, "Java path (leave empty for default java)",
                                    defaults.getOrDefault("JavaPath", "java")));
        defaults.put("UserName",    promptInput(scanner, "Player name",
                                    defaults.getOrDefault("UserName", "Player")));
        defaults.putIfAbsent("MinMemory", "2048");
        defaults.putIfAbsent("MaxMemory", "4096");
        defaults.put("MinMemory",   promptInput(scanner, "Minimum memory (MB)",
                                    defaults.get("MinMemory")));
        defaults.put("MaxMemory",   promptInput(scanner, "Maximum memory (MB)",
                                    defaults.get("MaxMemory")));
        defaults.putIfAbsent("JvmArgs", "");
        defaults.putIfAbsent("GameArgs", "");
        defaults.put("JvmArgs",    promptInput(scanner, "JVM args (space separated, e.g. -Dfml.ignoreInvalidMinecraftCertificates=true)",
                                    defaults.get("JvmArgs")));
        defaults.put("GameArgs",   promptInput(scanner, "Game args (space separated, e.g. --server 127.0.0.1 --port 25565)",
                                    defaults.get("GameArgs")));
        defaults.putIfAbsent("PreLaunchCommand", "");
        defaults.putIfAbsent("PostExitCommand", "");
        defaults.put("PreLaunchCommand", promptInput(scanner, "Pre-launch command (system command executed before game launch)",
                                    defaults.get("PreLaunchCommand")));
        defaults.put("PostExitCommand",  promptInput(scanner, "Post-exit command (system command executed after game exits)",
                                    defaults.get("PostExitCommand")));
        defaults.putIfAbsent("Fullscreen", "false");
        defaults.putIfAbsent("VersionIsolation", "false");
        defaults.put("Fullscreen",       promptInput(scanner, "Fullscreen mode (true/false)",
                                    defaults.get("Fullscreen")));
        defaults.put("VersionIsolation", promptInput(scanner, "Version isolation (true/false, independent directory per version)",
                                    defaults.get("VersionIsolation")));

        // 将本次输入的配置保存回 starlight.ini
        saveConfig(defaults);
        log.info("");
        return defaults;
    }

    // ================================================================
    //  工具方法
    // ================================================================

    /**
     * 从简单 JSON 中提取字符串值
     */
    public static String extractJsonSimple(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + search.length());
        if (colon < 0) return null;
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length()) return null;
        if (json.charAt(start) == '"') {
            int end = start + 1;
            while (end < json.length() && json.charAt(end) != '"') end++;
            return json.substring(start + 1, end);
        }
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
        return json.substring(start, end).trim();
    }

    /**
     * 创建适配终端编码的 Scanner（自动检测控制台编码，避免中文乱码）
     */
    public static Scanner createScanner() {
        Charset charset = detectInputCharset();
        return new Scanner(System.in, charset);
    }

    /**
     * 通用输入读取（支持中文路径等复杂输入）
     * <p>
     * 优先级：
     * 1. System.console().readLine() — Windows 控制台原生支持，编码最准确
     * 2. BufferedReader + StandardCharsets.UTF_8 — 自动适配 chcp 65001
     * 3. Scanner 兜底
     */
    public static String readLine() {
        // 策略1: System.console() — 控制台原生支持，可正确处理 Unicode
        try {
            java.io.Console console = System.console();
            if (console != null) {
                return console.readLine();
            }
        } catch (Exception ignored) {}

        // 策略2: BufferedReader + UTF-8
        try {
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
            return reader.readLine();
        } catch (Exception ignored) {}

        // 策略3: Scanner 兜底
        return new Scanner(System.in).nextLine();
    }

    /**
     * 检测 System.in 的实际输入编码
     * <p>
     * Windows 常见的几种场景：
     * - chcp 936 + 中文系统 → GBK
     * - chcp 65001 → UTF-8
     * - 现代终端/PowerShell → UTF-8
     */
    public static Charset detectInputCharset() {
        // 策略1: sun.stdout.encoding（Windows JVM 自动记录的控制台编码，如 "UTF-8" / "Cp65001" / "GBK"）
        String encoding = System.getProperty("sun.stdout.encoding");
        if (encoding != null && !encoding.isEmpty()) {
            Charset c = parseEncodingName(encoding);
            if (c != null) return c;
        }

        // 策略2: native.encoding（JDK 19+）
        encoding = System.getProperty("native.encoding");
        if (encoding != null && !encoding.isEmpty()) {
            Charset c = parseEncodingName(encoding);
            if (c != null) return c;
        }

        // 策略3: System.console()
        try {
            java.io.Console console = System.console();
            if (console != null && console.charset() != null) {
                Charset c = parseEncodingName(console.charset().name());
                if (c != null) return c;
            }
        } catch (Exception ignored) {}

        // 策略4: file.encoding 系统属性
        encoding = System.getProperty("file.encoding");
        if (encoding != null && !encoding.isEmpty()) {
            Charset c = parseEncodingName(encoding);
            if (c != null) return c;
        }

        // 策略5: Charset.defaultCharset() 兜底
        return Charset.defaultCharset();
    }

    /**
     * 解析编码名称，统一处理 Windows 的变体名称
     * 例如 "Cp65001" / "cp65001" → UTF-8， "GBK" / "gb2312" → GBK
     */
    private static Charset parseEncodingName(String name) {
        if (name == null || name.isEmpty()) return null;
        String upper = name.toUpperCase(java.util.Locale.ROOT);

        // Cp65001 / 65001 → UTF-8
        if (upper.contains("65001") || upper.contains("UTF-8") || upper.contains("UTF8")) {
            return StandardCharsets.UTF_8;
        }
        // UTF-16
        if (upper.contains("UTF-16") || upper.contains("UNICODE")) {
            return null; // 不可能是控制台编码
        }

        try {
            return Charset.forName(name);
        } catch (Exception ignored) {
            // 名称不合法，尝试常见别名
            if (upper.contains("GBK") || upper.contains("GB2312") || upper.contains("CP936")) {
                try { return Charset.forName("GBK"); } catch (Exception ignored2) {}
            }
            if (upper.contains("ISO-8859-1") || upper.contains("LATIN")) {
                try { return Charset.forName("ISO-8859-1"); } catch (Exception ignored2) {}
            }
            return null;
        }
    }

    /**
     * 带默认值的输入提示
     */
    public static String promptInput(Scanner scanner, String prompt, String defaultValue) {
        String displayDefault = defaultValue.isEmpty() ? "(required)" : "(default: " + defaultValue + ")";
        System.out.print("  " + prompt + " " + displayDefault + ": ");
        String input = scanner.nextLine().trim();
        return input.isEmpty() ? defaultValue : input;
    }

    /**
     * 安全解析整数
     */
    public static int parseIntSafe(String s, int defaultValue) {
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    /**
     * 暂停等待回车
     */
    public static void pause(Scanner scanner) {
        if (scanner == null) return;
        log.info("");
        System.out.print("Press Enter to return to the main menu...");
        scanner.nextLine();
    }
}
