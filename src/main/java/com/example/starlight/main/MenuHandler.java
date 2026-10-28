package com.example.starlight.main;


import com.example.starlight.util.I18N;
import com.example.starlight.auth.AccountManager;
import com.example.starlight.auth.AccountManager.Account;
import com.example.starlight.auth.AuthlibInjectorSupport;
import com.example.starlight.auth.offline.LocalYggdrasilServer;
import com.example.starlight.auth.offline.LocalCapeProxy;
import com.example.starlight.auth.offline.OfflineSkinManager;
import com.example.starlight.auth.offline.Skin;
import com.example.starlight.config.GameDirManager;
import com.example.starlight.config.SelectedVersion;
import com.example.starlight.config.StarlightConfig;
import com.example.starlight.frp.SakuraFrpService;
import com.example.starlight.util.VanillaVersion;
import com.example.starlight.config.VersionConfigManager;
import com.example.starlight.gui.UIGeneralControlClass;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 菜单处理器 - 处理启动器主菜单的各项操作
 */
public class MenuHandler {

    /** 配置路径统一由 AppDirs 解析（默认 ~/.starlight，--portable 回落工作目录） */
    @Deprecated
    private static final String CONFIG_FILE = "starlight.ini";
    private static final String LOG_DIR = "logs";
    private static final Gson GSON = new Gson();

    // 当前登录的账户信息
    private static Account currentAccount;
    private static boolean loggedIn = false;

    // 游戏进程引用
    private static Process gameProcess = null;
    private static Thread gameWatcher = null;
    private static volatile LocalYggdrasilServer gameCapeServer = null;
    private static volatile LocalCapeProxy gameCapeProxy = null;

    // quickLaunchMinecraft //

    public static void quickLaunchMinecraft() {
        System.out.println("Quick launching Minecraft...");

        UIGeneralControlClass.LaunchConfig config = UIGeneralControlClass.getLaunchConfig();

        if (config.version == null || config.version.isEmpty()) {
            System.err.println("Error: No game version set! Run 'configure' first to set the version.");
            return;
        }

        Path versionJsonPath = Paths.get(config.gameDir, "versions", config.version, config.version + ".json");
        if (!Files.exists(versionJsonPath)) {
            System.err.println("Error: JSON file for version " + config.version + " does not exist!");
            return;
        }

        if (isLoggedIn()) {
            applyCurrentAccount(config);
            System.out.println("Login account applied: " + config.userName);
        }

        System.out.println("Launching Minecraft " + config.version + " in background...");

        new Thread(() -> {
            try {
                String jsonString = Files.readString(versionJsonPath);
                JsonObject versionJson = GSON.fromJson(jsonString, JsonObject.class);
                applyOfflineSkinPack(config);
                String command = buildMinecraftCommand(config, versionJson);
                startGameProcess(config, command);
            } catch (Exception e) {
                System.err.println("Quick launch failed: " + e.getMessage());
                e.printStackTrace();
            }
        }, "quick-launch-thread").start();

        System.out.println("Quick launch command sent!");
    }

    // 账号管理菜单 //

    public static void accountManagement() {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.println("\n======= Account Management =======");
            System.out.println("1. View current account");
            System.out.println("2. Offline login");
            System.out.println("3. Microsoft account login");
            System.out.println("4. Third-party login");
            System.out.println("5. List all accounts");
            System.out.println("6. Switch account");
            System.out.println("7. Delete account");
            System.out.println("8. Back");
            System.out.print("\nPlease choose: ");

            String choice = scanner.nextLine().trim();

            switch (choice) {
                case "1" -> {
                    if (isLoggedIn() && currentAccount != null) {
                        System.out.println("Current account: " + currentAccount.name);
                        System.out.println("UUID: " + currentAccount.id);
                        System.out.println("Type: " + currentAccount.getTypeLabel());
                    } else {
                        System.out.println("Not logged in.");
                    }
                }
                case "2" -> {
                    System.out.print("Enter player name: ");
                    String name = scanner.nextLine().trim();
                    if (name.isEmpty()) name = "Player";
                    AccountManager.loginOffline(name);
                    currentAccount = AccountManager.getCurrentAccount();
                    loggedIn = currentAccount != null;
                    System.out.println("Logged in offline as: " + name);
                }
                case "3" -> {
                    System.out.println("Starting Microsoft login...");
                    System.out.print("Enter authorization code (or 'test'): ");
                    String code = scanner.nextLine().trim();
                    if (code.equalsIgnoreCase("test")) {
                        AccountManager.loginOffline("MicrosoftPlayer");
                        currentAccount = AccountManager.getCurrentAccount();
                        loggedIn = currentAccount != null;
                        System.out.println("Microsoft login successful!");
                    } else {
                        System.out.println("Microsoft login requires Azure AD configuration.");
                    }
                }
                case "4" -> {
                    System.out.print("Auth server address: ");
                    String server = scanner.nextLine().trim();
                    System.out.print("Email: ");
                    String email = scanner.nextLine().trim();
                    System.out.print("Password: ");
                    String password = scanner.nextLine().trim();
                    try {
                        AccountManager.loginThirdPartySync(server, email, password);
                        currentAccount = AccountManager.getCurrentAccount();
                        loggedIn = true;
                        System.out.println("Third-party login successful!");
                    } catch (Exception ex) {
                        System.err.println("Third-party login failed: " + ex.getMessage());
                    }
                }
                case "5" -> {
                    var accounts = AccountManager.listAccounts();
                    if (accounts.isEmpty()) {
                        System.out.println("No saved accounts.");
                    } else {
                        System.out.println("Saved accounts (" + accounts.size() + "):");
                        for (Account a : accounts) {
                            String marker = (currentAccount != null && currentAccount.id.equals(a.id)) ? " <- current" : "";
                            System.out.println("  " + a.displayName() + marker);
                        }
                    }
                }
                case "6" -> {
                    System.out.print("Enter target account ID (UUID): ");
                    String id = scanner.nextLine().trim();
                    if (AccountManager.setCurrentAccount(id)) {
                        currentAccount = AccountManager.getCurrentAccount();
                        System.out.println("Switched to account: " + (currentAccount != null ? currentAccount.name : id));
                    } else {
                        System.out.println("No account found with ID '" + id + "'.");
                    }
                }
                case "7" -> {
                    System.out.print("Enter account ID to delete (UUID): ");
                    String id = scanner.nextLine().trim();
                    if (AccountManager.removeAccount(id)) {
                        if (currentAccount != null && currentAccount.id.equals(id)) {
                            currentAccount = null;
                            loggedIn = false;
                        }
                        System.out.println("Removed account: " + id);
                    } else {
                        System.out.println("No account found with ID '" + id + "'.");
                    }
                }
                case "8" -> {
                    System.out.println("Back to main menu.");
                    return;
                }
                default -> System.out.println("Invalid choice.");
            }
        }
    }

    // FRP 隧道（SakuraFrp）//

    /**
     * 启动一条已存在的 SakuraFrp 隧道（CLI 用）。
     *
     * <p>访问密钥由联机页连接后保存到本机凭据文件；这里不接收明文密钥。
     * 未传隧道 ID 时回退到配置项 {@code SakuraFrpTunnelId}（联机页最后一次启动的隧道）。
     */
    public static void startFrpTunnel(String tunnelIdArg) {
        int tunnelId = parseTunnelId(tunnelIdArg);
        if (tunnelId <= 0) {
            // 联机页保存在 starlight-client.ini；历史值可能仍在 starlight.ini，一并兜底
            tunnelId = parseTunnelId(ConfigManager.readClientConfig().get("SakuraFrpTunnelId"));
        }
        if (tunnelId <= 0) {
            tunnelId = parseTunnelId(StarlightConfig.get("SakuraFrpTunnelId"));
        }
        if (tunnelId <= 0) {
            System.err.println("Error: no tunnel ID. Usage: frp <tunnel-id>"
                    + " (or start a tunnel once in the launcher's FRP page first).");
            return;
        }
        final int id = tunnelId;
        System.out.println("Starting SakuraFrp tunnel " + id + "...");
        new Thread(() -> {
            try {
                SakuraFrpService.StartResult result = SakuraFrpService.get().startTunnel(id, System.out::println);
                System.out.println("Tunnel established: " + result.publicAddress());
                System.out.println("Press Ctrl+C to stop the tunnel.");
                while (SakuraFrpService.get().isRunning()) {
                    Thread.sleep(1000);
                }
                System.out.println("SakuraFrp tunnel stopped.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.err.println("SakuraFrp tunnel failed: " + e.getMessage());
            }
        }, "sakura-frp-thread").start();
    }

    private static int parseTunnelId(String text) {
        if (text == null || text.isBlank()) return -1;
        try {
            int id = Integer.parseInt(text.trim());
            return id > 0 ? id : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // 发送日志 //

    public static void sendLogToServer(String logPath, String serverUrl) {
        System.out.println("Sending logs...");
        System.out.println("Log file: " + logPath);
        System.out.println("Server: " + serverUrl);

        new Thread(() -> {
            try {
                Path logFilePath = Paths.get(logPath);
                if (!Files.exists(logFilePath)) {
                    System.err.println("Error: log file does not exist - " + logPath);
                    return;
                }

                String logContent = Files.readString(logFilePath);
                String jsonBody = GSON.toJson(Map.of(
                        "log", logContent,
                        "timestamp", System.currentTimeMillis(),
                        "source", "starlight-launcher"
                ));

                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(serverUrl))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .timeout(Duration.ofSeconds(30))
                        .build();

                System.out.println("Uploading logs to server...");
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    System.out.println("Log upload successful!");
                    System.out.println("Server response: " + response.body());
                } else {
                    System.err.println("Log upload failed, HTTP status: " + response.statusCode());
                }

            } catch (Exception e) {
                System.err.println("Failed to send logs: " + e.getMessage());
            }
        }, "send-logs-thread").start();
    }

    // AI 分析日志 //

    public static void aiAnalyzeLogFile(String logPath) {
        System.out.println("Analyzing log: " + logPath);

        new Thread(() -> {
            try {
                Path logFilePath = Paths.get(logPath);
                if (!Files.exists(logFilePath)) {
                    System.err.println("Error: log file does not exist - " + logPath);
                    return;
                }

                String logContent = Files.readString(logFilePath);
                System.out.println("Log size: " + logContent.length() + " characters");

                System.out.println("\n======= AI Log Analysis Result =======");

                // 崩溃分析
                System.out.println("\n[Crash Analysis]");
                if (logContent.contains("OutOfMemoryError") || logContent.contains("OutOfMemory")) {
                    System.out.println("  OutOfMemoryError detected");
                    System.out.println("    Suggestion: increase maximum memory allocation (-Xmx)");
                }
                if (logContent.contains("StackOverflowError")) {
                    System.out.println("  StackOverflowError detected");
                    System.out.println("    Suggestion: possible mod conflict or recursive call issue");
                }
                if (logContent.contains("Could not create the Java Virtual Machine")) {
                    System.out.println("  JVM creation failed");
                    System.out.println("    Suggestion: check Java path and memory settings");
                }
                if (logContent.contains("EXCEPTION_ACCESS_VIOLATION")) {
                    System.out.println("  Memory access violation detected (EXCEPTION_ACCESS_VIOLATION)");
                    System.out.println("    Suggestion: possible GPU driver issue or native library conflict");
                }
                if (logContent.contains("GLFW error") || logContent.contains("glfw")) {
                    System.out.println("  GLFW error detected (window/graphics system)");
                    System.out.println("    Suggestion: update GPU driver or check display settings");
                }

                // 常见错误分析
                System.out.println("\n[Common Error Detection]");
                if (logContent.contains("java.lang.NoClassDefFoundError")) {
                    System.out.println("  Class not found error (NoClassDefFoundError)");
                    System.out.println("    Suggestion: check mod compatibility or reinstall");
                }
                if (logContent.contains("java.lang.NoSuchMethodError")) {
                    System.out.println("  Method not found error (NoSuchMethodError)");
                    System.out.println("    Suggestion: mod version mismatch, please update mods");
                }
                if (logContent.contains("java.lang.ClassNotFoundException")) {
                    System.out.println("  Class not found exception (ClassNotFoundException)");
                    System.out.println("    Suggestion: check classpath or reinstall");
                }
                if (logContent.contains("java.lang.NullPointerException")) {
                    System.out.println("  Null pointer exception (NullPointerException)");
                }
                if (logContent.contains("java.io.IOException")) {
                    System.out.println("  IO exception (IOException)");
                    System.out.println("    Suggestion: check file permissions and disk space");
                }
                if (logContent.contains("java.net.ConnectException")) {
                    System.out.println("  Connection exception (ConnectException)");
                    System.out.println("    Suggestion: check network connection or server address");
                }

                // 性能问题
                System.out.println("\n[Performance Issue Detection]");
                if (logContent.contains("Can't keep up!")) {
                    System.out.println("  Server/client lag (Can't keep up!)");
                    System.out.println("    Suggestion: lower render distance or view distance settings");
                }
                if (logContent.contains("Ticking")) {
                    System.out.println("  Game tick timeout (Ticking)");
                    System.out.println("    Suggestion: check mod performance or lower settings");
                }

                // 模组冲突
                System.out.println("\n[Mod Conflict Detection]");
                boolean hasConflict = false;
                if (logContent.contains("conflict") || logContent.contains("Conflict")) {
                    hasConflict = true;
                    System.out.println("  Mod conflict detected");
                }
                if (logContent.contains("Duplicate")) {
                    hasConflict = true;
                    System.out.println("  Duplicate mods detected");
                }
                if (logContent.contains("incompatible")) {
                    hasConflict = true;
                    System.out.println("  Incompatible mods detected");
                }
                if (!hasConflict) {
                    System.out.println("  No obvious mod conflicts detected");
                }

                // 建议
                System.out.println("\n[Fix Suggestions]");
                List<String> suggestions = new ArrayList<>();
                if (logContent.contains("OutOfMemoryError")) {
                    suggestions.add(I18N.get("main.t0002"));
                }
                if (logContent.contains("EXCEPTION_ACCESS_VIOLATION")) {
                    suggestions.add(I18N.get("main.t0003"));
                }
                if (suggestions.isEmpty()) {
                    suggestions.add(I18N.get("main.t0004"));
                    suggestions.add(I18N.get("main.t0005"));
                    suggestions.add(I18N.get("main.t0006"));
                }
                for (int i = 0; i < suggestions.size(); i++) {
                    System.out.println("  " + (i + 1) + ". " + suggestions.get(i));
                }

                System.out.println("======= Analysis Complete =======");

            } catch (Exception e) {
                System.err.println("Failed to analyze log: " + e.getMessage());
            }
        }, "ai-log-analysis-thread").start();
    }

    // 配置管理 //

    public static Map<String, String> readConfig() {
        Map<String, String> config = new LinkedHashMap<>();
        Path configPath = com.example.starlight.config.AppDirs.starlightIni();

        if (!Files.exists(configPath)) {
            System.out.println("Config file not found, using default configuration");
            return getDefaultConfig();
        }

        try (BufferedReader reader = Files.newBufferedReader(configPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";") || line.startsWith("[")) continue;
                int eqIdx = line.indexOf('=');
                if (eqIdx > 0) {
                    config.put(line.substring(0, eqIdx).trim(), line.substring(eqIdx + 1).trim());
                }
            }
            System.out.println("Config file loaded: " + configPath.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("Failed to read config file: " + e.getMessage());
            return getDefaultConfig();
        }
        return config;
    }

    public static Map<String, String> getDefaultConfig() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("GameDir", ".minecraft");
        // 版本选择不在这里给默认值：它属于「某个游戏目录」，写在各目录的 starlight.json 里
        config.put("JavaPath", "java");
        config.put("MinMemory", "2048");
        config.put("MaxMemory", "4096");
        config.put("WindowWidth", "854");
        config.put("WindowHeight", "480");
        config.put("Fullscreen", "false");
        config.put("VersionIsolation", "false");
        config.put("JvmArgs", "");
        config.put("GameArgs", "");
        config.put("PreLaunchCommand", "");
        config.put("PostExitCommand", "");
        config.put("UserName", "Player");
        config.put("UUID", "00000000-0000-0000-0000-000000000000");
        config.put("AccessToken", "dummy_token");
        config.put("UserType", "msa");
        return config;
    }

    public static void saveConfig(Map<String, String> config) {
        Path configPath = com.example.starlight.config.AppDirs.starlightIni();
        try (BufferedWriter writer = Files.newBufferedWriter(configPath)) {
            writer.write("[Launcher]\n");
            for (Map.Entry<String, String> entry : config.entrySet()) {
                writer.write(entry.getKey() + "=" + entry.getValue() + "\n");
            }
            System.out.println("Config saved to: " + configPath.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("Failed to save config file: " + e.getMessage());
        }
    }

    // 账户管理 //

    public static boolean isLoggedIn() {
        return loggedIn && currentAccount != null;
    }

    public static String getLoggedInUserName() {
        return (currentAccount != null) ? currentAccount.name : I18N.get("main.t0007");
    }

    public static void loginOffline(String username) {
        AccountManager.loginOffline(username);
        currentAccount = AccountManager.getCurrentAccount();
        loggedIn = currentAccount != null;
        System.out.println("Logged in offline as: " + username);
    }

    public static void applyCurrentAccount(UIGeneralControlClass.LaunchConfig config) {
        if (currentAccount != null) {
            config.userName = currentAccount.name;
            config.uuid = currentAccount.id;
            // 离线皮肤（PCL2 风格）：离线账号按皮肤配置微调 UUID，让游戏按期望模型渲染默认皮肤
            if (currentAccount.type == AccountManager.AccountType.OFFLINE) {
                String resolvedUuid = OfflineSkinManager.resolveUuid(currentAccount.id, currentAccount.getSkin());
                if (resolvedUuid != null && !resolvedUuid.isEmpty()) {
                    config.uuid = resolvedUuid;
                }
            }
            config.accessToken = currentAccount.accessToken;
            config.userType = currentAccount.getUserType();
        }
    }

    // 核心启动方法 //

    public static boolean isConfigReady() {
        Map<String, String> cfg = readConfig();
        // 版本选择真源：当前游戏目录内的 starlight.json（ini 不再保存 Version）
        String gameDir = GameDirManager.activePath(cfg);
        String version = SelectedVersion.of(cfg);
        if (version.isEmpty() || gameDir.isEmpty()) return false;
        Path versionJson = Paths.get(gameDir, "versions", version, version + ".json");
        return Files.exists(versionJson);
    }

    public static UIGeneralControlClass.LaunchConfig getLaunchConfig() {
        Map<String, String> cfg = readConfig();
        // 版本选择真源：当前游戏目录内的 starlight.json（ini 不再保存 Version）
        String gameDir = GameDirManager.activePath(cfg);
        String version = SelectedVersion.of(cfg);
        // 版本独立配置：全局 ini + 当前版本覆盖（config.overrides.json）合并后再构造
        Map<String, String> effective = VersionConfigManager.buildEffectiveIni(cfg, gameDir, version);
        // 合并结果里已没有 Version / GameDir 键，这里显式带上，保证与选定目录一致
        effective.put("Version", version);
        effective.put("GameDir", gameDir);

        UIGeneralControlClass.LaunchConfig config = new UIGeneralControlClass.LaunchConfig();
        config.gameDir = effective.getOrDefault("GameDir", ".minecraft");
        config.version = version.isEmpty() ? "1.20.1" : version;
        config.javaPath = effective.getOrDefault("JavaPath", "java");
        config.userName = effective.getOrDefault("UserName", "Player");
        config.uuid = effective.getOrDefault("UUID", UUID.randomUUID().toString());
        config.accessToken = effective.getOrDefault("AccessToken", "dummy_token");
        config.userType = effective.getOrDefault("UserType", "mojang");
        config.minMemory = parseIntOrDefault(effective.get("MinMemory"), 2048);
        config.maxMemory = parseIntOrDefault(effective.get("MaxMemory"), 4096);
        config.windowWidth = parseIntOrDefault(effective.get("WindowWidth"), 854);
        config.windowHeight = parseIntOrDefault(effective.get("WindowHeight"), 480);
        config.fullscreen = Boolean.parseBoolean(effective.getOrDefault("Fullscreen", "false"));
        config.versionIsolation = Boolean.parseBoolean(effective.getOrDefault("VersionIsolation", "false"));
        config.jvmArgs = effective.getOrDefault("JvmArgs", "");
        config.gameArgs = effective.getOrDefault("GameArgs", "");
        config.preLaunchCommand = effective.getOrDefault("PreLaunchCommand", "");
        config.postExitCommand = effective.getOrDefault("PostExitCommand", "");
        // 高级设置：自定义游戏窗口标题 / 进程优先级（与 UIGeneralControlClass.LaunchConfig 一致）
        config.windowTitle = effective.getOrDefault("GameWindowTitle", "");
        config.processPriority = effective.getOrDefault("GameProcessPriority", "normal");

        return config;
    }

    private static int parseIntOrDefault(String value, int defaultValue) {
        if (value == null || value.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // 构建 Minecraft 启动命令 //

    private static String buildMinecraftCommand(UIGeneralControlClass.LaunchConfig config, JsonObject versionJson) {
        String mainClass = "net.minecraft.client.main.Main";
        try {
            mainClass = versionJson.get("mainClass").getAsString();
        } catch (Exception ignored) {}

        List<String> parts = new ArrayList<>();
        parts.add("\"" + config.javaPath + "\"");
        parts.add("-Xms" + config.minMemory + "M");
        parts.add("-Xmx" + config.maxMemory + "M");

        if (config.jvmArgs != null && !config.jvmArgs.isEmpty()) {
            // 必须按「带引号路径」切分：-javaagent 指向 ~/.starlight 下的绝对路径，
            // 用户名含空格时朴素 split("\\s+") 会把 jar 路径拆成两半，JVM 直接拒绝启动
            for (String arg : com.example.starlight.startmc.LaunchInfo.splitCommandLine(config.jvmArgs)) {
                if (!arg.isBlank()) parts.add(arg);
            }
        }

        // JNA native access (Java 16+, 消除 restricted method 警告)
        int javaVer = getJavaMajorVersion(config.javaPath);
        if (javaVer >= 16) {
            parts.add("--enable-native-access=ALL-UNNAMED");
        }

        String nativesDir = Paths.get(config.gameDir, "versions", config.version,
                config.version + "-natives").toString();
        parts.add("-Djava.library.path=" + nativesDir);

        parts.add("-cp");
        parts.add("\"" + buildClassPath(config) + "\"");

        parts.add(mainClass);

        // 游戏参数
        parts.add("--username"); parts.add(config.userName);
        parts.add("--uuid"); parts.add(config.uuid);
        parts.add("--accessToken"); parts.add(config.accessToken);
        parts.add("--version"); parts.add(config.version);
        parts.add("--gameDir"); parts.add("\"" + config.gameDir + "\"");
        parts.add("--assetsDir"); parts.add("\"" + config.gameDir + "/assets\"");
        parts.add("--assetIndex"); parts.add(config.version);
        parts.add("--userType"); parts.add(config.userType);
        parts.add("--width"); parts.add(String.valueOf(config.windowWidth));
        parts.add("--height"); parts.add(String.valueOf(config.windowHeight));

        // 1.17+ 本地玩家皮肤/披风直注入：SkinManager 对本地玩家直接取 Session profile
        // 属性（--profileProperties），不走会话档案查询；无该参数时本地玩家永远没有
        // textures 属性，皮肤/披风无法显示（1.17.1 反编译实证）
        if (gameCapeServer != null) {
            java.util.List<String> pp = OfflineSkinManager.profilePropertiesArgs(
                    gameCapeServer, config.version);
            for (String a : pp) parts.add(a);
        }

        if (config.fullscreen) {
            parts.add("--fullscreen");
        }

        if (config.gameArgs != null && !config.gameArgs.isEmpty()) {
            String[] gameArgsArray = config.gameArgs.split("\\s+");
            for (String arg : gameArgsArray) {
                if (!arg.trim().isEmpty()) parts.add(arg.trim());
            }
        }

        return String.join(" ", parts);
    }

    /**
     * 检测指定 Java 可执行文件的版本
     * @param javaPath Java 可执行文件路径
     * @return Java 主版本号（如 8, 11, 17, 21），检测失败返回 8
     */
    private static int getJavaMajorVersion(String javaPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(javaPath, "-version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                if (line != null) {
                    Pattern pattern = Pattern.compile("version\\s+\"([^\"]+)\"");
                    Matcher m = pattern.matcher(line);
                    if (m.find()) {
                        String ver = m.group(1);
                        if (ver.startsWith("1.")) {
                            return Integer.parseInt(ver.substring(2, 3));
                        }
                        int dot = ver.indexOf('.');
                        if (dot > 0) {
                            return Integer.parseInt(ver.substring(0, dot));
                        }
                        return Integer.parseInt(ver);
                    }
                }
            }
            p.waitFor();
        } catch (IOException | InterruptedException | NumberFormatException e) {
            // 检测失败时默认返回 8
        }
        return 8;
    }

    // 构建 ClassPath //

    private static String buildClassPath(UIGeneralControlClass.LaunchConfig config) {
        StringBuilder cp = new StringBuilder();
        Path libsDir = Paths.get(config.gameDir, "libraries");
        if (Files.exists(libsDir)) {
            try {
                Files.walk(libsDir)
                        .filter(p -> p.toString().endsWith(".jar"))
                        .forEach(p -> cp.append(p.toAbsolutePath()).append(";"));
            } catch (IOException ignored) {}
        }
        Path verJar = Paths.get(config.gameDir, "versions", config.version, config.version + ".jar");
        if (Files.exists(verJar)) {
            cp.append(verJar.toAbsolutePath());
        }
        return cp.toString();
    }

    // 启动游戏进程（独立进程，不阻塞启动器） //

    private static void startGameProcess(UIGeneralControlClass.LaunchConfig config, String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", command);
            pb.directory(new File(config.gameDir));
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);

            gameProcess = pb.start();
            System.out.println("Game launched, PID: " + gameProcess.pid());

            gameWatcher = new Thread(() -> {
                try {
                    int exitCode = gameProcess.waitFor();
                    System.out.println("\n[Game Monitor] Game exited, exit code: " + exitCode);
                    gameProcess = null;
                    stopCapeServer();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "game-process-watcher");
            gameWatcher.setDaemon(true);
            gameWatcher.start();

        } catch (IOException e) {
            System.err.println("Failed to start game process: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // launchMinecraft（交互式启动） //

    public static void launchMinecraft(Scanner scanner) {
        System.out.println("========================================");
        System.out.println("        Minecraft Game Launcher");
        System.out.println("========================================");

        UIGeneralControlClass.LaunchConfig config = getLaunchConfig();

        System.out.println("\nCurrent configuration:");
        System.out.println("  Game directory: " + config.gameDir);
        System.out.println("  Version: " + config.version);
        System.out.println("  Java path: " + config.javaPath);
        System.out.println("  Player name: " + config.userName);
        System.out.println("  Memory: " + config.minMemory + "MB ~ " + config.maxMemory + "MB");

        if (config.version == null || config.version.isEmpty()) {
            System.out.println("\nError: No game version set!");
            System.out.print("Enter Minecraft version to launch (e.g. 1.20.1): ");
            if (scanner.hasNextLine()) {
                config.version = scanner.nextLine().trim();
                // 版本选择真源：写进该游戏目录自己的 starlight.json，不再写 ini
                SelectedVersion.set(config.gameDir, config.version);
            }
        }

        Path versionJsonPath = Paths.get(config.gameDir, "versions", config.version, config.version + ".json");
        if (!Files.exists(versionJsonPath)) {
            System.out.println("\nError: JSON file for version " + config.version + " does not exist!");
            System.out.println("  Path: " + versionJsonPath.toAbsolutePath());
            return;
        }

        if (isLoggedIn()) {
            applyCurrentAccount(config);
            System.out.println("\nLogin account applied: " + config.userName);
        } else {
            if (config.userName == null || config.userName.isEmpty()) {
                config.userName = "Player_" + System.getProperty("user.name").replaceAll("[^a-zA-Z0-9_]", "");
            }
            System.out.println("\nUsing offline mode: " + config.userName);
        }

        System.out.print("\nLaunch the game? (y/n): ");
        if (scanner.hasNextLine()) {
            String confirm = scanner.nextLine().trim().toLowerCase();
            if (!confirm.equals("y") && !confirm.equals("yes")) {
                System.out.println("Launch cancelled.");
                return;
            }
        }

        System.out.println("\nPreparing to launch Minecraft " + config.version + " ...");
        System.out.println("The launcher remains usable after the game starts.\n");

        new Thread(() -> {
            try {
                String jsonString = Files.readString(versionJsonPath);
                JsonObject versionJson = GSON.fromJson(jsonString, JsonObject.class);

                applyOfflineSkinPack(config);
                String command = buildMinecraftCommand(config, versionJson);
                startGameProcess(config, command);

            } catch (Exception e) {
                System.err.println("\n[Launch Thread] Launch failed: " + e.getMessage());
                e.printStackTrace();
            }
        }, "game-starter-thread").start();

        System.out.println("\nLaunch command sent, the game will start in background.");
        System.out.println("Enter 'stop' to force stop the game.");
        System.out.println("========================================\n");
    }

    /**
     * CLI 启动前应用离线皮肤资源包（PCL2 风格：自定义皮肤以资源包覆盖默认贴图）。
     * UUID 定制已在 applyCurrentAccount 完成（幂等，以账号基础 UUID 为起点）。
     */
    private static void applyOfflineSkinPack(UIGeneralControlClass.LaunchConfig config) {
        try {
            if (currentAccount == null || currentAccount.type != AccountManager.AccountType.OFFLINE) return;
            VanillaVersion vanilla = VanillaVersion.resolve(
                    java.nio.file.Path.of(config.gameDir), config.version);
            OfflineSkinManager.applySkinPack(currentAccount.getSkin(), currentAccount.name,
                    config.gameDir, config.version, config.versionIsolation, vanilla);
            // 披风：本地 Yggdrasil 档案服务器（仅当配置了披风；无披风保持纯 PCL2 机制）
            stopCapeServer();
            if (vanilla == null || vanilla.major() >= 8) {
                Skin.LoadedSkin loaded = OfflineSkinManager.loadForLaunch(
                        currentAccount.getSkin(), currentAccount.name);
                LocalYggdrasilServer capeServer = LocalYggdrasilServer.start(
                        loaded, config.uuid, currentAccount.name);
                if (capeServer != null) {
                    gameCapeServer = capeServer;
                    java.util.List<String> injectArgs = new java.util.ArrayList<>(
                            AuthlibInjectorSupport.buildLaunchJvmArgs(capeServer.getApiBase()));
                    // 本地披风代理：SOCKS5 劫持 OptiFine s.optifine.net 披风下载
                    //（1.17.1 纯 OptiFine 本地玩家披风唯一原生通道；--proxyHost 由
                    // Main 解析为 SOCKS 代理，HttpTexture 走 HttpPipeline SOCKS 隧道）
                    LocalCapeProxy capeProxy = LocalCapeProxy.start(
                            OfflineSkinManager.capePngBytes(loaded), currentAccount.name);
                    if (capeProxy != null) {
                        gameCapeProxy = capeProxy;
                        String agentArg = capeProxy.agentJvmArg();
                        if (agentArg != null) {
                            injectArgs.add(agentArg);
                        }
                    }
                    String extra = String.join(" ", injectArgs);
                    config.jvmArgs = (config.jvmArgs == null || config.jvmArgs.isBlank())
                            ? extra : config.jvmArgs.trim() + " " + extra;
                    System.out.println("Local cape server enabled: " + capeServer.getApiBase());
                }
            }
        } catch (Exception e) {
            System.err.println("Offline skin pack apply failed: " + e.getMessage());
        }
    }

    private static void stopCapeServer() {
        LocalYggdrasilServer s = gameCapeServer;
        if (s != null) {
            gameCapeServer = null;
            s.stop();
        }
        LocalCapeProxy p = gameCapeProxy;
        if (p != null) {
            gameCapeProxy = null;
            p.stop();
        }
    }
}