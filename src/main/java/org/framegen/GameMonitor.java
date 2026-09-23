/*
 * Decompiled with CFR 0.152.
 */
package org.framegen;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Minecraft 进程与 options.txt 监听。
 *
 * 检测说明：
 *  - JDK17 在 Windows 上通过 ProcessHandle 读不到其它进程的命令行（commandLine() 为空），
 *    因此改用系统 WMI（Get-CimInstance，兼容新版 Windows 移除 wmic.exe 的环境）读取 java/javaw
 *    进程的真实命令行做精确匹配。
 *  - 另外提供 notifyGameStarted/notifyGameStopped：本启动器自己拉起游戏时由启动器权威告知，
 *    与进程扫描互为补充（任一信号成立即视为游戏中）。
 */
public class GameMonitor {
    private volatile boolean running = false;
    private volatile boolean gameDetected = false;
    private volatile int gamePid = -1;
    private volatile String gameProcessName = "";
    private Thread monitorThread;
    private final List<GameStateListener> listeners = new ArrayList<GameStateListener>();
    private final AppConfig config;
    private final long ownPid = ProcessHandle.current().pid();
    /** 启动器权威告知的游戏状态（游戏由本启动器拉起时的精确信号） */
    private volatile boolean manualGameRunning = false;
    private volatile int manualGamePid = -1;
    private volatile String manualGameName = "";
    /** 当前识别出的游戏实际目录（从游戏命令行 --gameDir 解析 / 启动器目录候选），用于自动定位 options.txt */
    private volatile String detectedGameDir = "";
    /** 附加游戏目录候选提供者（启动器实时返回其游戏目录 / 版本隔离目录，不入配置文件） */
    private volatile Supplier<List<String>> auxiliaryGameDirProvider = null;

    public GameMonitor(AppConfig appConfig) {
        this.config = appConfig;
    }

    public void addListener(GameStateListener gameStateListener) {
        this.listeners.add(gameStateListener);
    }

    /**
     * 设置附加游戏目录候选提供者（每次搜索 options.txt 时实时调用，始终反映启动器当前配置）。
     */
    public synchronized void setAuxiliaryGameDirProvider(Supplier<List<String>> provider) {
        this.auxiliaryGameDirProvider = provider;
    }

    /**
     * 记录当前游戏的目录（例如从命令行 --gameDir 解析出的真实游戏目录），优先用于定位 options.txt。
     */
    public synchronized void setDetectedGameDir(String gameDir) {
        this.detectedGameDir = gameDir == null ? "" : gameDir.trim();
    }

    public String getDetectedGameDir() {
        return this.detectedGameDir;
    }

    public void start() {
        if (this.running) {
            return;
        }
        this.running = true;
        this.monitorThread = new Thread(this::monitorLoop, "GameMonitor");
        this.monitorThread.setDaemon(true);
        this.monitorThread.start();
        System.out.println("[GameMonitor] 已启动，正在监听游戏进程...");
    }

    public void stop() {
        this.running = false;
        if (this.monitorThread != null) {
            this.monitorThread.interrupt();
        }
    }

    public boolean isGameDetected() {
        return this.gameDetected;
    }

    public int getGamePid() {
        return this.gamePid;
    }

    public String getGameProcessName() {
        return this.gameProcessName;
    }

    /**
     * 启动器告知：游戏已启动（权威信号，即使进程扫描识别不到也保证能识别）。
     * 仅记录状态，事件仍由监控线程统一按状态变化触发，避免重复回调。
     */
    public void notifyGameStarted(String name, int pid) {
        this.manualGameRunning = true;
        this.manualGameName = (name == null || name.trim().isEmpty()) ? "Minecraft" : name.trim();
        this.manualGamePid = pid;
        // 立即让 UI/托盘感知运行中
        this.gameDetected = true;
        this.gameProcessName = this.manualGameName;
        if (pid > 0) {
            this.gamePid = pid;
        }
        System.out.println("[GameMonitor] 启动器告知游戏已启动: " + this.manualGameName + " (PID=" + pid + ")");
    }

    /** 启动器告知：游戏已退出（权威信号） */
    public void notifyGameStopped() {
        this.manualGameRunning = false;
        this.manualGamePid = -1;
        this.manualGameName = "";
    }

    private void fireGameStarted(String name, int pid) {
        this.gameDetected = true;
        if (pid > 0) {
            this.gamePid = pid;
        }
        this.gameProcessName = name;
        for (GameStateListener gameStateListener : this.listeners) {
            try {
                gameStateListener.onGameStarted(name, pid);
            }
            catch (Exception exception) {
                exception.printStackTrace();
            }
        }
        System.out.println("[GameMonitor] 检测到游戏: " + name + " (PID=" + pid + ")");
    }

    private void fireGameStopped() {
        this.gameDetected = false;
        this.gamePid = -1;
        this.gameProcessName = "";
        this.setDetectedGameDir("");
        for (GameStateListener gameStateListener : this.listeners) {
            try {
                gameStateListener.onGameStopped();
            }
            catch (Exception exception) {
                exception.printStackTrace();
            }
        }
        System.out.println("[GameMonitor] 游戏已退出");
    }

    private void monitorLoop() {
        boolean bl = false;
        while (this.running) {
            try {
                boolean bl2 = this.scanForMinecraft();
                boolean manual = this.manualGameRunning;
                boolean effective = bl2 || manual;
                if (effective && !bl) {
                    bl = true;
                    if (manual) {
                        this.fireGameStarted(this.manualGameName, this.manualGamePid);
                    } else {
                        this.fireGameStarted(this.gameProcessName, this.gamePid);
                    }
                } else if (!effective && bl) {
                    bl = false;
                    this.fireGameStopped();
                }
                Thread.sleep(2000L);
            }
            catch (InterruptedException interruptedException) {
                break;
            }
            catch (Exception exception) {
                System.err.println("[GameMonitor] 扫描异常: " + exception.getMessage());
                try {
                    Thread.sleep(3000L);
                }
                catch (InterruptedException interruptedException) {
                    break;
                }
            }
        }
    }

    // ==================== 进程扫描（基于 WMI 读取真实命令行） ====================

    private boolean scanForMinecraft() {
        List<String> lines = this.readJavaProcessCommandLines();
        if (lines == null) {
            return false;
        }
        for (String line : lines) {
            int sep = line.indexOf('\u001f');
            if (sep <= 0) {
                continue;
            }
            long pid;
            try {
                pid = Long.parseLong(line.substring(0, sep).trim());
            }
            catch (NumberFormatException numberFormatException) {
                continue;
            }
            if (pid == this.ownPid) {
                continue;
            }
            String commandLine = line.substring(sep + 1);
            if (!this.containsMinecraftMarker(commandLine)) {
                continue;
            }
            this.gamePid = (int) pid;
            this.gameProcessName = "Minecraft";
            this.setDetectedGameDir(this.parseGameDirFromCommand(commandLine));
            return true;
        }
        return false;
    }

    /**
     * 通过 PowerShell + Get-CimInstance(Win32_Process) 读取所有 java/javaw 进程的
     * “PID \u001f 命令行” 行列表；失败返回 null。
     */
    private List<String> readJavaProcessCommandLines() {
        String script =
                "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; "
                + "Get-CimInstance Win32_Process | "
                + "Where-Object { $_.Name -eq 'java.exe' -or $_.Name -eq 'javaw.exe' } | "
                + "ForEach-Object { if ($_.CommandLine) { "
                + "Write-Output ($_.ProcessId.ToString() + [char]31 + $_.CommandLine) } }";
        try {
            String powershell = System.getenv("windir");
            if (powershell != null && !powershell.isEmpty()) {
                powershell = powershell + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";
                if (!new File(powershell).exists()) {
                    powershell = "powershell.exe";
                }
            } else {
                powershell = "powershell.exe";
            }
            ProcessBuilder processBuilder = new ProcessBuilder(
                    powershell,
                    "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
                    "-Command", script);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            byte[] bytes = process.getInputStream().readAllBytes();
            if (!process.waitFor(10L, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            String output = new String(bytes, StandardCharsets.UTF_8);
            ArrayList<String> lines = new ArrayList<String>();
            for (String line : output.split("\r?\n")) {
                if (line != null && !line.trim().isEmpty()) {
                    lines.add(line);
                }
            }
            return lines;
        }
        catch (Throwable t) {
            System.err.println("[GameMonitor] 读取进程命令行失败(WMI): " + t.getMessage());
            return null;
        }
    }

    /** 命令行是否命中任一 Minecraft 加载器主类特征 */
    private boolean containsMinecraftMarker(String commandLine) {
        if (commandLine == null) {
            return false;
        }
        String lower = commandLine.toLowerCase();
        String[] markers = {
                "net.minecraft",
                "net.fabricmc",
                "org.quiltmc",
                "cpw.mods",
                "net.neoforged",
                "org.neoforged"
        };
        for (String marker : markers) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** 从游戏命令行解析 --gameDir / --game_directory 等参数对应的游戏目录（支持引号含空格路径） */
    private String parseGameDirFromCommand(String commandLine) {
        try {
            List<String> tokens = tokenizeCommandLine(commandLine);
            for (int i = 0; i < tokens.size(); i++) {
                String token = tokens.get(i);
                String lower = token.toLowerCase();
                if (!lower.startsWith("--gamedir") && !lower.startsWith("--game_dir")
                        && !lower.startsWith("--game-directory") && !lower.startsWith("gamedir")
                        && !lower.startsWith("game_dir")) {
                    continue;
                }
                int eq = token.indexOf('=');
                if (eq >= 0 && eq < token.length() - 1) {
                    return trimQuotes(token.substring(eq + 1));
                }
                if (i + 1 < tokens.size()) {
                    String value = trimQuotes(tokens.get(i + 1));
                    if (!value.isEmpty()) {
                        return value;
                    }
                }
            }
        }
        catch (Throwable ignored) {
            // 解析失败不影响检测本身
        }
        return "";
    }

    /** 简易命令行分词（支持双引号包裹含空格的路径） */
    private static List<String> tokenizeCommandLine(String commandLine) {
        ArrayList<String> tokens = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < commandLine.length(); i++) {
            char c = commandLine.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
            } else if (c == ' ' && !inQuote) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static String trimQuotes(String value) {
        if (value == null) {
            return "";
        }
        String v = value.trim();
        while (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1).trim();
        }
        return v;
    }

    // ==================== options.txt 全自动搜索 ====================

    /**
     * 全自动搜索 Minecraft 的 options.txt（傻瓜式，无需手动配置游戏目录）。
     * 依次尝试：已识别游戏的真实目录(含版本隔离) → 启动器当前游戏目录/版本隔离目录 →
     * 配置遗留目录 → 当前目录/.minecraft → %APPDATA%/.minecraft；返回第一个存在的文件。
     */
    public File findOptionsFile() {
        ArrayList<String> candidates = new ArrayList<String>();
        if (this.detectedGameDir != null && !this.detectedGameDir.isEmpty()) {
            candidates.add(this.detectedGameDir);
        }
        Supplier<List<String>> provider = this.auxiliaryGameDirProvider;
        if (provider != null) {
            try {
                List<String> dirs = provider.get();
                if (dirs != null) {
                    for (String dir : dirs) {
                        if (dir != null && !dir.trim().isEmpty()) {
                            candidates.add(dir.trim());
                        }
                    }
                }
            }
            catch (Throwable ignored) {
                // 提供者异常不影响其它候选
            }
        }
        String string = this.config != null ? this.config.getGameDir() : null;
        if (string != null && !string.trim().isEmpty()) {
            candidates.add(string.trim());
        }
        String userDir = System.getProperty("user.dir");
        if (userDir != null) {
            candidates.add(userDir);
            candidates.add(userDir + File.separator + ".minecraft");
        }
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.trim().isEmpty()) {
            candidates.add(appData.trim() + File.separator + ".minecraft");
        }
        for (String dir : candidates) {
            try {
                if (dir == null || dir.trim().isEmpty()) {
                    continue;
                }
                File file = new File(dir.trim(), "options.txt");
                if (file.exists() && file.isFile()) {
                    return file;
                }
            }
            catch (Throwable ignored) {
                // 跳过不可用候选
            }
        }
        return null;
    }

    public static interface GameStateListener {
        public void onGameStarted(String var1, int var2);

        public void onGameStopped();
    }
}
