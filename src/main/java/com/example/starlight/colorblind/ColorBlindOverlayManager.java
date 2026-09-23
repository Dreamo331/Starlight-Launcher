package com.example.starlight.colorblind;

import com.example.starlight.config.Endpoints;
import com.example.starlight.main.ConfigManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 色盲矫正工具（{@code Starlight-Launcher/ColorBlindOverlay/ColorBlindOverlay.exe}）的进程与配置管理。
 *
 * <p>矫正工具只对指定窗口生效，因此只在游戏启动时拉起：启动器检测到游戏窗口就绪后调用
 * {@link #notifyGameStarted()}，游戏退出（正常退出 / 崩溃 / 被取消）后调用 {@link #notifyGameStopped()}。
 * 窗口标题为子串匹配：留空时按 {@link #DEFAULT_WINDOW} 抓取 Minecraft 窗口，用户自定义后按自定义值匹配。
 *
 * <p>配置项存放于 starlight-client.ini（与主题、高对比度等个性化项同一份配置，见
 * {@code ConfigManager.CLIENT_KEYS}）：{@code ColorBlindMode}（总开关）、{@code ColorBlindType}、
 * {@code ColorBlindStrength}、{@code ColorBlindWindow}、{@code ColorBlindAutoEnable}、
 * {@code ColorBlindRemember}。
 */
public final class ColorBlindOverlayManager {

    // ==================== 配置键与默认值 ====================

    public static final String KEY_MODE = "ColorBlindMode";
    public static final String KEY_TYPE = "ColorBlindType";
    public static final String KEY_STRENGTH = "ColorBlindStrength";
    public static final String KEY_WINDOW = "ColorBlindWindow";
    public static final String KEY_AUTO_ENABLE = "ColorBlindAutoEnable";
    public static final String KEY_REMEMBER = "ColorBlindRemember";

    /** 全部配置键（启动初始化时用于同步界面配置缓存） */
    public static final String[] ALL_KEYS = {
            KEY_MODE, KEY_TYPE, KEY_STRENGTH, KEY_WINDOW, KEY_AUTO_ENABLE, KEY_REMEMBER
    };

    /** 未自定义窗口标题时抓取的窗口（ColorBlindOverlay 为子串匹配，可命中「Minecraft 1.20.1」） */
    public static final String DEFAULT_WINDOW = "Minecraft";
    public static final String DEFAULT_TYPE = "deuteranopia";
    public static final double DEFAULT_STRENGTH = 1.0;

    /** 可执行文件相对路径（相对启动器运行目录） */
    private static final String EXE_RELATIVE_PATH = "Starlight-Launcher" + File.separator
            + "ColorBlindOverlay" + File.separator + "ColorBlindOverlay.exe";

    /** 矫正工具下载地址（Gitee Release）：本机找不到可执行文件时提示用户从这里手动下载 */
    public static final String DOWNLOAD_URL = Endpoints.colorBlindOverlayUrl();

    /** 色障类型：CLI 参数值 → 中文名（顺序即下拉框顺序，默认类型排最前） */
    private static final Map<String, String> TYPES;

    static {
        Map<String, String> types = new LinkedHashMap<>();
        types.put("deuteranopia", "绿色盲");
        types.put("deuteranomaly", "绿色弱");
        types.put("protanopia", "红色盲");
        types.put("protanomaly", "红色弱");
        types.put("tritanopia", "蓝色盲");
        types.put("tritanomaly", "蓝色弱");
        types.put("achromatopsia", "全色盲");
        types.put("achromatomaly", "色弱");
        TYPES = Collections.unmodifiableMap(types);
    }

    // ==================== 运行状态 ====================

    /** 已拉起的矫正工具进程（null = 未启动或已退出） */
    private static volatile Process overlayProcess;

    /** 游戏是否由启动器拉起并在运行中（矫正工具的应用条件） */
    private static volatile boolean gameRunning;

    private static boolean shutdownHookAdded;

    private ColorBlindOverlayManager() {
    }

    // ==================== 配置读写 ====================

    /** 读取生效配置：starlight.ini 打底，starlight-client.ini（个性化项）覆盖 */
    public static Map<String, String> readMergedConfig() {
        Map<String, String> merged = new LinkedHashMap<>(ConfigManager.readConfig());
        merged.putAll(ConfigManager.readClientConfig());
        return merged;
    }

    /** 总开关是否开启 */
    public static boolean isEnabled() {
        return "true".equalsIgnoreCase(readMergedConfig().getOrDefault(KEY_MODE, "false"));
    }

    /** 全部色障类型的 CLI 参数值 */
    public static List<String> typeCodes() {
        return new ArrayList<>(TYPES.keySet());
    }

    /** 全部色障类型的中文名（下拉框选项） */
    public static List<String> typeLabels() {
        return new ArrayList<>(TYPES.values());
    }

    /** 中文名 → CLI 参数值（未知返回默认类型） */
    public static String codeOfLabel(String label) {
        for (Map.Entry<String, String> e : TYPES.entrySet()) {
            if (e.getValue().equals(label)) {
                return e.getKey();
            }
        }
        return DEFAULT_TYPE;
    }

    /** CLI 参数值 → 中文名（未知返回原值） */
    public static String typeLabel(String code) {
        if (code == null) {
            return TYPES.getOrDefault(DEFAULT_TYPE, DEFAULT_TYPE);
        }
        return TYPES.getOrDefault(code, code);
    }

    /** 类型值归一化：不认识的类型回退为默认类型 */
    public static String normalizeType(String code) {
        return (code != null && TYPES.containsKey(code)) ? code : DEFAULT_TYPE;
    }

    /** 强度归一化到 0.0~3.0 */
    public static double clampStrength(double value) {
        return Math.max(0.0, Math.min(3.0, value));
    }

    /** 解析强度配置（非法值回退默认强度） */
    public static double parseStrength(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return DEFAULT_STRENGTH;
        }
        try {
            return clampStrength(Double.parseDouble(raw.trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_STRENGTH;
        }
    }

    /** 强度的命令行文本（保留一位小数，避免出现 1.0000000000000002 之类） */
    public static String formatStrength(double value) {
        return String.format(Locale.ROOT, "%.1f", clampStrength(value));
    }

    /** 生效的窗口标题：留空 → 默认 Minecraft 窗口 */
    public static String resolveWindow(Map<String, String> config) {
        String window = config == null ? "" : config.getOrDefault(KEY_WINDOW, "");
        return (window == null || window.trim().isEmpty()) ? DEFAULT_WINDOW : window.trim();
    }

    /** 生效的色障类型 */
    public static String resolveType(Map<String, String> config) {
        return normalizeType(config == null ? null : config.get(KEY_TYPE));
    }

    /** 生效的矫正强度 */
    public static double resolveStrength(Map<String, String> config) {
        return parseStrength(config == null ? null : config.get(KEY_STRENGTH));
    }

    /**
     * 启动器启动时的初始化：按「记住配置」决定是否重置数值项，按「自动开启」就绪总开关。
     *
     * <p>不直接拉起进程 —— 矫正工具只在游戏启动时生效，这里只把配置调整到「下次游戏启动即生效」的状态。
     *
     * @return 六个配置键的最终生效值（供界面配置缓存同步，避免界面显示与磁盘不一致）
     */
    public static synchronized Map<String, String> bootstrapAtLauncherStartup() {
        Map<String, String> client = ConfigManager.readClientConfig();
        Map<String, String> updates = new LinkedHashMap<>();

        // 「记住当前配置」关闭：色障类型 / 强度 / 窗口标题每次启动恢复默认值
        if (!"true".equalsIgnoreCase(client.getOrDefault(KEY_REMEMBER, "true"))) {
            updates.put(KEY_TYPE, DEFAULT_TYPE);
            updates.put(KEY_STRENGTH, formatStrength(DEFAULT_STRENGTH));
            updates.put(KEY_WINDOW, "");
        }
        // 「自动开启」只决定「还没有总开关记录」时的初始状态（首次运行）；
        // 总开关一旦落过盘就以用户那次选择为准。早先这里是无条件写 true，
        // 于是手动关掉色盲辅助后一重启它又自己开了（AutoEnable 默认为 true）。
        if (!client.containsKey(KEY_MODE)
                && "true".equalsIgnoreCase(client.getOrDefault(KEY_AUTO_ENABLE, "true"))) {
            updates.put(KEY_MODE, "true");
        }
        if (!updates.isEmpty()) {
            ConfigManager.saveClientConfig(updates);
        }

        Map<String, String> effective = readMergedConfig();
        Map<String, String> result = new LinkedHashMap<>();
        // 缺失的键按默认值回填：界面配置缓存拿到的是明确值，而不是可能与「未设置」混淆的空串
        result.put(KEY_MODE, effective.getOrDefault(KEY_MODE, "false"));
        result.put(KEY_TYPE, normalizeType(effective.get(KEY_TYPE)));
        result.put(KEY_STRENGTH, formatStrength(parseStrength(effective.get(KEY_STRENGTH))));
        result.put(KEY_WINDOW, effective.getOrDefault(KEY_WINDOW, ""));
        result.put(KEY_AUTO_ENABLE,
                String.valueOf(!"false".equalsIgnoreCase(effective.getOrDefault(KEY_AUTO_ENABLE, "true"))));
        result.put(KEY_REMEMBER,
                String.valueOf(!"false".equalsIgnoreCase(effective.getOrDefault(KEY_REMEMBER, "true"))));
        return result;
    }

    // ==================== 游戏生命周期 ====================

    /** 游戏窗口就绪（启动器权威信号）：总开关开启时按当前配置拉起矫正工具 */
    public static synchronized void notifyGameStarted() {
        gameRunning = true;
        if (!isEnabled()) {
            return;
        }
        String message = ensureStarted();
        System.out.println("[ColorBlind] 游戏已启动: " + message);
    }

    /** 游戏退出：关闭矫正工具（目标窗口都没了，继续运行没有意义） */
    public static synchronized void notifyGameStopped() {
        gameRunning = false;
        stopOverlayInternal();
    }

    /** 总开关切换（设置页）：开启且游戏在运行则立即拉起，否则只置为开启状态等游戏启动 */
    public static synchronized String applyMasterSwitch(boolean on) {
        if (!on) {
            // 关闭总开关是用户的最终决定：连「启动后自动开启」一起关掉。
            // 两个开关本该互不矛盾，留着 AutoEnable=true 会让下次启动器启动时又把它顶回开启。
            // （设置页负责同步界面配置缓存，见 LauncherView.applyColorBlindMode）
            ConfigManager.saveClientConfig(Map.of(KEY_AUTO_ENABLE, "false"));
            boolean wasRunning = isRunning();
            stopOverlayInternal();
            return wasRunning ? "色盲辅助已关闭，矫正工具已停止" : "色盲辅助已关闭";
        }
        if (!gameRunning) {
            return "色盲辅助已开启，将在下次启动游戏时自动生效";
        }
        return "色盲辅助已开启：" + ensureStarted();
    }

    /** 按当前配置重启矫正工具（设置页 / 快速调节网页的「重启」按钮） */
    public static synchronized String restartOverlay() {
        if (!isEnabled()) {
            return "色盲辅助总开关未开启，未启动矫正工具";
        }
        stopOverlayInternal();
        return ensureStarted();
    }

    /** 停止矫正工具 */
    public static synchronized String stopOverlay() {
        if (!isRunning()) {
            return "矫正工具未在运行";
        }
        stopOverlayInternal();
        return "矫正工具已停止";
    }

    /** 矫正工具进程是否在运行 */
    public static boolean isRunning() {
        Process process = overlayProcess;
        return process != null && process.isAlive();
    }

    /** 矫正工具可执行文件是否已就位（缺失时提示用户从 {@link #DOWNLOAD_URL} 手动下载） */
    public static boolean isExeAvailable() {
        return resolveExe() != null;
    }

    /**
     * 计算矫正工具的下载落点并确保父目录存在：按 {@link #resolveExe} 同款顺序选位置
     * （运行目录优先，user.dir 兜底），保证下载完成后 {@link #resolveExe} 能立刻找到它。
     *
     * @throws IOException 两个候选位置的父目录都创建失败（只读盘等）
     */
    public static File prepareDownloadTarget() throws IOException {
        File primary = new File(EXE_RELATIVE_PATH);
        File dir = primary.getParentFile();
        if (dir != null && (dir.isDirectory() || dir.mkdirs())) {
            return primary;
        }
        String userDir = System.getProperty("user.dir");
        if (userDir != null) {
            File alt = new File(userDir, EXE_RELATIVE_PATH);
            File altDir = alt.getParentFile();
            if (altDir != null && (altDir.isDirectory() || altDir.mkdirs())) {
                return alt;
            }
        }
        throw new IOException("无法创建矫正工具目录: " + EXE_RELATIVE_PATH);
    }

    /** 游戏是否正在运行（由启动器检测信号维护） */
    public static boolean isGameRunning() {
        return gameRunning;
    }

    /** 状态描述（设置页 / 调节网页展示） */
    public static String statusText() {
        return "总开关 " + (isEnabled() ? "开启" : "关闭")
                + " · 游戏 " + (gameRunning ? "运行中" : "未运行")
                + " · 矫正工具 " + (isRunning() ? "运行中" : "未运行");
    }

    // ==================== 内部实现 ====================

    /** 按当前配置启动矫正工具（已在运行则原样返回） */
    private static String ensureStarted() {
        if (isRunning()) {
            return "矫正工具已在运行";
        }
        File exe = resolveExe();
        if (exe == null) {
            return "未找到矫正工具: " + EXE_RELATIVE_PATH;
        }
        Map<String, String> config = readMergedConfig();
        String window = resolveWindow(config);
        String type = resolveType(config);
        String strength = formatStrength(resolveStrength(config));
        try {
            ProcessBuilder builder = new ProcessBuilder(exe.getAbsolutePath(),
                    "-w", window, "-t", type, "-s", strength);
            builder.directory(exe.getParentFile());
            builder.redirectErrorStream(true);
            overlayProcess = builder.start();
            addShutdownHook();
            System.out.println("[ColorBlind] 矫正工具已启动: " + exe.getAbsolutePath()
                    + " -w " + window + " -t " + type + " -s " + strength);
            return "矫正工具已启动（窗口 " + window + " · " + typeLabel(type) + " · 强度 " + strength + "）";
        } catch (IOException e) {
            System.err.println("[ColorBlind] 启动矫正工具失败: " + e.getMessage());
            return "启动矫正工具失败: " + e.getMessage();
        }
    }

    /** 关闭矫正工具进程（幂等） */
    private static void stopOverlayInternal() {
        Process process = overlayProcess;
        overlayProcess = null;
        if (process == null) {
            return;
        }
        try {
            if (process.isAlive()) {
                process.destroy();
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
                System.out.println("[ColorBlind] 矫正工具已停止");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 定位可执行文件：先按运行目录，再按 user.dir 兜底（jpackage 启动时运行目录可能不同） */
    private static File resolveExe() {
        File file = new File(EXE_RELATIVE_PATH);
        if (file.isFile()) {
            return file;
        }
        String userDir = System.getProperty("user.dir");
        if (userDir != null) {
            File alt = new File(userDir, EXE_RELATIVE_PATH);
            if (alt.isFile()) {
                return alt;
            }
        }
        return null;
    }

    /** 退出清理：启动器关闭时一并结束矫正工具，避免留下无主进程 */
    private static void addShutdownHook() {
        if (shutdownHookAdded) {
            return;
        }
        shutdownHookAdded = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                stopOverlayInternal();
            } catch (Throwable ignored) {
            }
        }, "ColorBlindShutdownHook"));
    }

    // ==================== 快速调节网页 ====================

    /** 启动本地调节服务并返回访问地址（失败返回 null） */
    public static String tuneServerUrl() {
        return ColorBlindTuneServer.ensureStarted();
    }

    /**
     * 启动器退出清理：关闭矫正工具与本地调节服务。
     *
     * <p>由 JavaFX 的 {@code Application.stop()} 调用（启动器关窗时 Platform.exit() 会走到这里）。
     * HttpServer 的调度线程不是守护线程，必须在退出前显式停止，否则窗口关了进程还在。
     */
    public static void shutdown() {
        try {
            stopOverlayInternal();
        } catch (Throwable ignored) {
        }
        try {
            ColorBlindTuneServer.stop();
        } catch (Throwable ignored) {
        }
    }
}
