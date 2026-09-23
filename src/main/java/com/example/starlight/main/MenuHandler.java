package com.example.starlight.main;

import com.example.starlight.auth.AccountManager;
import com.example.starlight.auth.AccountManager.Account;
import com.example.starlight.config.GameDirManager;
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

    private static final String CONFIG_FILE = "starlight.ini";
    private static final String LOG_DIR = "logs";
    private static final Gson GSON = new Gson();

    // 当前登录的账户信息
    private static Account currentAccount;
    private static boolean loggedIn = false;

    // 游戏进程引用
    private static Process gameProcess = null;
    private static Thread gameWatcher = null;

    // ====================================================================
    //  quickLaunchMinecraft
    // ====================================================================

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
                String command = buildMinecraftCommand(config, versionJson);
                startGameProcess(config, command);
            } catch (Exception e) {
                System.err.println("Quick launch failed: " + e.getMessage());
                e.printStackTrace();
            }
        }, "quick-launch-thread").start();

        System.out.println("Quick launch command sent!");
    }

    // ====================================================================
    //  账号管理菜单
    // ====================================================================

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

    // ====================================================================
    //  FRP 隧道
    // ====================================================================

    public static void startFrpTunnel(String addr) {
        System.out.println("Starting FRP tunnel...");
        System.out.println("Target address: " + addr);

        new Thread(() -> {
            try {
                Path frpcPath = Paths.get("frpc");
                Path frpcExePath = Paths.get("frpc.exe");

                if (!Files.exists(frpcPath) && !Files.exists(frpcExePath)) {
                    System.err.println("Error: frpc/frpc.exe not found! Place frpc in the launcher directory.");
                    return;
                }

                String frpcCmd = Files.exists(frpcExePath) ? "frpc.exe" : "frpc";
                String[] parts = addr.split(":");
                String localIp = parts[0];
                String localPort = parts.length > 1 ? parts[1] : "25565";

                String configContent = String.format("""
                    [common]
                    server_addr = frp.your-server.com
                    server_port = 7000

                    [minecraft]
                    type = tcp
                    local_ip = %s
                    local_port = %s
                    remote_port = 25565
                    """, localIp, localPort);

                Files.writeString(Paths.get("frpc_temp.ini"), configContent);

                ProcessBuilder pb = new ProcessBuilder(frpcCmd, "-c", "frpc_temp.ini");
                pb.inheritIO();
                Process process = pb.start();
                System.out.println("FRP tunnel started (PID: " + process.pid() + ")");
                System.out.println("Local " + addr + " -> remote port 25565");
                System.out.println("Press Ctrl+C to stop the tunnel.");

                int exitCode = process.waitFor();
                System.out.println("FRP tunnel stopped, exit code: " + exitCode);

            } catch (Exception e) {
                System.err.println("FRP tunnel start failed: " + e.getMessage());
            }
        }, "frp-tunnel-thread").start();
    }

    // ====================================================================
    //  发送日志
    // ====================================================================

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

    // ====================================================================
    //  AI 分析日志
    // ====================================================================

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
                    suggestions.add("将最大内存 (-Xmx) 增加到 4096MB 或更高");
                }
                if (logContent.contains("EXCEPTION_ACCESS_VIOLATION")) {
                    suggestions.add("更新显卡驱动到最新版本");
                }
                if (suggestions.isEmpty()) {
                    suggestions.add("检查 Java 版本是否兼容");
                    suggestions.add("验证游戏文件完整性");
                    suggestions.add("更新所有模组到最新版本");
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

    // ====================================================================
    //  配置管理
    // ====================================================================

    public static Map<String, String> readConfig() {
        Map<String, String> config = new LinkedHashMap<>();
        Path configPath = Paths.get(CONFIG_FILE);

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
        config.put("Version", "1.20.1");
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
        Path configPath = Paths.get(CONFIG_FILE);
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

    // ====================================================================
    //  账户管理
    // ====================================================================

    public static boolean isLoggedIn() {
        return loggedIn && currentAccount != null;
    }

    public static String getLoggedInUserName() {
        return (currentAccount != null) ? currentAccount.name : "未知";
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
            config.accessToken = currentAccount.accessToken;
            config.userType = currentAccount.getUserType();
        }
    }

    // ====================================================================
    //  核心启动方法
    // ====================================================================

    public static boolean isConfigReady() {
        Map<String, String> cfg = readConfig();
        String version = cfg.get("Version");
        String gameDir = cfg.get("GameDir");
        if (version == null || version.isEmpty() || gameDir == null || gameDir.isEmpty()) return false;
        Path versionJson = Paths.get(gameDir, "versions", version, version + ".json");
        return Files.exists(versionJson);
    }

    public static UIGeneralControlClass.LaunchConfig getLaunchConfig() {
        Map<String, String> cfg = readConfig();
        // 版本独立配置：全局 ini + 当前版本覆盖（config.overrides.json）合并后再构造
        Map<String, String> effective = VersionConfigManager.buildEffectiveIni(
                cfg, GameDirManager.activePath(cfg), cfg.getOrDefault("Version", ""));

        UIGeneralControlClass.LaunchConfig config = new UIGeneralControlClass.LaunchConfig();
        config.gameDir = effective.getOrDefault("GameDir", ".minecraft");
        config.version = effective.getOrDefault("Version", "1.20.1");
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

    // ====================================================================
    //  构建 Minecraft 启动命令
    // ====================================================================

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
            String[] jvmArgsArray = config.jvmArgs.split("\\s+");
            for (String arg : jvmArgsArray) {
                if (!arg.trim().isEmpty()) parts.add(arg.trim());
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

    // ====================================================================
    //  构建 ClassPath
    // ====================================================================

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

    // ====================================================================
    //  启动游戏进程（独立进程，不阻塞启动器）
    // ====================================================================

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

    // ====================================================================
    //  launchMinecraft（交互式启动）
    // ====================================================================

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
                Map<String, String> cfg = new LinkedHashMap<>();
                cfg.put("Version", config.version);
                saveConfig(cfg);
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
}