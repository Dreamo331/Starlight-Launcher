package com.example.starlight.plugin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 插件管理器：负责插件子进程的<strong>启动 / 停止 / 重启</strong>、
 * 启用状态的<strong>持久化</strong>与启动器启动时的<strong>自动启动</strong>。
 *
 * <p>进程隔离（需求指定）：插件以独立子进程方式启动（{@code java -jar plugin.jar}），
 * 与启动器不共用 ClassLoader；本类只负责进程调度，不加载插件任何类。
 *
 * <p>启动命令：{@code <启动器自身JVM>/bin/java -Dstarlight.minecraft.dir=<游戏目录> -jar <插件jar>}。
 * 插件子进程通过 {@code -Dstarlight.minecraft.dir} 定位启动器写入的
 * {@code starlight_api.port} 端口文件，从而发现 API 地址并完成握手。
 *
 * <p>开关策略（用户已确认）：新插件<strong>默认禁用</strong>，需在启动器「模组」页手动开启；
 * 开启后立即启动子进程，状态持久化到
 * {@code <启动器根目录>/Starlight-Launcher/plugins-state.json}，
 * 下次启动器启动时自动拉起所有已启用插件。
 */
public final class PluginManager {

    /** 启用状态持久化文件（相对启动器根目录）。 */
    public static final String STATE_FILE = "Starlight-Launcher/plugins-state.json";

    private static final Logger LOGGER = Logger.getLogger(PluginManager.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单例：整个启动器进程内唯一的插件管理器（UI 与启动时序共用）。 */
    private static volatile PluginManager INSTANCE;

    /** 启动器根目录（插件目录与状态文件的基准）。 */
    private final Path launcherRoot;

    /** .minecraft 游戏目录（通过 -Dstarlight.minecraft.dir 传给插件子进程）。 */
    private final Path minecraftDir;

    /** 插件目录：<launcherRoot>/Starlight-Launcher/mod。 */
    private final Path pluginDir;

    /** 已扫描到的插件元数据：pluginId → metadata。 */
    private final Map<String, PluginMetadata> metadataById = new LinkedHashMap<>();

    /** 已启用插件 id 集合（默认全禁用，仅显式开启的才在集合内）。 */
    private final Set<String> enabledIds = Collections.synchronizedSet(new LinkedHashSet<>());

    /** 运行中的插件子进程：pluginId → Process。 */
    private final Map<String, Process> runningProcesses = new ConcurrentHashMap<>();

    /** 是否已扫描加载。 */
    private boolean initialized;

    private PluginManager(Path launcherRoot, Path minecraftDir) {
        this.launcherRoot = launcherRoot;
        this.minecraftDir = minecraftDir;
        this.pluginDir = launcherRoot.resolve(PluginScanner.DEFAULT_PLUGINS_DIR);
    }

    /**
     * 初始化单例（首次调用生效；重复调用返回已有实例）。
     * 同时注册 JVM 退出钩子：启动器退出时终止所有插件子进程。
     *
     * @param launcherRoot 启动器根目录（与 UI 中 Starlight-Launcher\\Mod 的基准一致，即启动器 CWD）
     * @param minecraftDir .minecraft 游戏目录（插件发现端口文件的基准）
     */
    public static synchronized PluginManager init(Path launcherRoot, Path minecraftDir) {
        if (INSTANCE == null) {
            INSTANCE = new PluginManager(launcherRoot, minecraftDir);
            Runtime.getRuntime().addShutdownHook(
                    new Thread(INSTANCE::shutdown, "starlight-plugin-shutdown"));
        }
        return INSTANCE;
    }

    /** @return 单例；尚未 init 时返回 {@code null}（UI 需判空）。 */
    public static PluginManager getInstance() {
        return INSTANCE;
    }

    /** @return 插件目录路径 */
    public Path pluginDir() {
        return pluginDir;
    }

    /**
     * 扫描插件 + 加载启用状态。启动器启动时调用一次。
     * 只读元数据（PluginScanner），不加载任何插件类。
     */
    public synchronized void load() {
        metadataById.clear();
        try {
            for (PluginMetadata metadata : new PluginScanner(pluginDir).scan()) {
                PluginMetadata previous = metadataById.put(metadata.id(), metadata);
                if (previous != null) {
                    LOGGER.warning("检测到重复插件 id: " + metadata.id()
                            + "（" + previous.sourceJar() + " 与 " + metadata.sourceJar()
                            + "，后者覆盖前者，请删除其中一个 jar）");
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "插件扫描失败: " + pluginDir, e);
        }
        loadState();
        initialized = true;
        LOGGER.info("插件系统就绪: 共 " + metadataById.size() + " 个插件，已启用 " + enabledIds.size() + " 个");
    }

    /**
     * 自动启动所有已启用插件。
     * <strong>必须先启动 PluginApiServer</strong>（端口文件写入完成）再调用本方法，
     * 否则插件子进程读不到 starlight_api.port。
     */
    public synchronized void startAll() {
        if (!initialized) {
            load();
        }
        for (PluginMetadata metadata : metadataById.values()) {
            if (isEnabled(metadata.id())) {
                startPlugin(metadata.id());
            }
        }
    }

    /**
     * 启动单个插件子进程（幂等：已在运行则跳过）。
     * 子进程命令：java -Dstarlight.minecraft.dir=<mcDir> -jar <plugin.jar>
     *
     * <p>启动前校验 JAR 的 Main-Class 主清单属性（java -jar 依赖它），
     * 缺少时给出明确报错——常见原因是误复制了 maven-shade 的
     * {@code original-*.jar} 原始瘦包而非可执行 fat JAR。
     *
     * @return true=启动成功或已在运行；false=启动失败（已记日志）
     */
    public synchronized boolean startPlugin(String pluginId) {
        PluginMetadata metadata = metadataById.get(pluginId);
        if (metadata == null) {
            LOGGER.warning("启动失败：未知插件 " + pluginId);
            return false;
        }
        if (metadata.sourceJar() == null || !Files.isRegularFile(metadata.sourceJar())) {
            LOGGER.warning("启动失败：插件 JAR 不存在: " + metadata.sourceJar());
            return false;
        }
        if (runningProcesses.containsKey(pluginId)) {
            return true; // 已在运行
        }
        // 校验 JAR 可执行性：Main-Class 必须存在（与 launcher-plugin.json 的 main_class 对应）
        try (JarFile jarFile = new JarFile(metadata.sourceJar().toFile())) {
            Manifest manifest = jarFile.getManifest();
            String mainClass = manifest == null ? null
                    : manifest.getMainAttributes().getValue("Main-Class");
            if (mainClass == null || mainClass.isBlank()) {
                LOGGER.severe("插件 JAR 缺少 Main-Class 主清单属性，无法以 java -jar 启动: "
                        + metadata.sourceJar() + "（metadata.main_class=" + metadata.mainClass()
                        + "；若复制的是 original- 开头的原始包，请改用 maven-shade 生成的正式产物，"
                        + "如 hello-starlight-plugin.jar）");
                return false;
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "读取插件 JAR 清单失败: " + metadata.sourceJar(), e);
            return false;
        }
        try {
            List<String> command = new ArrayList<>();
            command.add(javaExecutable().toString());
            // 关键：把 .minecraft 目录以绝对路径传给子进程，插件据此读取 starlight_api.port
            command.add("-Dstarlight.minecraft.dir=" + minecraftDir.toAbsolutePath());
            command.add("-jar");
            command.add(metadata.sourceJar().toAbsolutePath().toString());

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(metadata.sourceJar().toAbsolutePath().getParent().toFile()); // 工作目录 = JAR 所在目录
            builder.redirectErrorStream(true);                           // 子进程输出合并到启动器控制台
            builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);

            Process process = builder.start();
            runningProcesses.put(pluginId, process);
            LOGGER.info("插件子进程已启动: " + pluginId + " (" + metadata.name()
                    + ") pid=" + process.pid() + " jar=" + metadata.sourceJar());

            // 异步监听退出：记录退出码（不自动重启，保持行为可控）
            process.onExit().thenRun(() -> {
                runningProcesses.remove(pluginId);
                LOGGER.info("插件子进程已退出: " + pluginId + " exitCode=" + process.exitValue());
            });
            return true;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "插件子进程启动失败: " + pluginId, e);
            return false;
        }
    }

    /**
     * 停止单个插件子进程（优雅终止；退出由 onExit 异步记录，不阻塞调用线程）。
     */
    public synchronized void stopPlugin(String pluginId) {
        Process process = runningProcesses.remove(pluginId);
        if (process == null) {
            return;
        }
        process.destroy();
        LOGGER.info("插件子进程已停止: " + pluginId);
    }

    /**
     * 设置插件启用状态（用户开关）：开启立即启动子进程，关闭立即停止，状态持久化。
     *
     * <p>若启用时启动失败（如 JAR 不可执行），状态自动<strong>回滚为禁用</strong>
     * 并返回 false，避免坏 JAR 反复尝试拉起。
     *
     * @return true=操作成功；false=启用失败（状态已回滚为禁用）
     */
    public synchronized boolean setEnabled(String pluginId, boolean enabled) {
        if (enabled) {
            enabledIds.add(pluginId);
            saveState();
            if (startPlugin(pluginId)) {
                LOGGER.info("插件开关: " + pluginId + " -> 启用");
                return true;
            }
            // 启动失败：回滚状态，UI 开关随之回弹
            enabledIds.remove(pluginId);
            saveState();
            LOGGER.warning("插件 " + pluginId + " 启用失败，状态已回滚为禁用");
            return false;
        }
        enabledIds.remove(pluginId);
        saveState();
        stopPlugin(pluginId);
        LOGGER.info("插件开关: " + pluginId + " -> 禁用");
        return true;
    }

    /** @return 插件是否已启用（默认禁用，仅显式开启过才为 true） */
    public boolean isEnabled(String pluginId) {
        return enabledIds.contains(pluginId);
    }

    /** @return 插件子进程是否正在运行 */
    public boolean isRunning(String pluginId) {
        return runningProcesses.containsKey(pluginId);
    }

    /** @return 是否已完成扫描加载 */
    public boolean isInitialized() {
        return initialized;
    }

    /** @return 全部已扫描到的插件元数据（保持扫描顺序） */
    public List<PluginMetadata> allMetadata() {
        return new ArrayList<>(metadataById.values());
    }

    /** @return 指定插件元数据；未知插件返回 null */
    public PluginMetadata metadataOf(String pluginId) {
        return metadataById.get(pluginId);
    }

    /** 停止全部插件子进程（JVM 退出钩子 / 启动器关闭时调用）。 */
    public synchronized void shutdown() {
        for (String pluginId : new ArrayList<>(runningProcesses.keySet())) {
            stopPlugin(pluginId);
        }
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /**
     * 子进程 java 可执行文件。
     * 默认使用<strong>启动器自身 JVM</strong>（java.home/bin/java[.exe]，与启动器同为
     * Java 17，最可靠、无需额外配置）；可用系统属性 {@code starlight.plugin.java} 覆盖。
     */
    private static Path javaExecutable() {
        String override = System.getProperty("starlight.plugin.java");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        String exe = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", exe);
    }

    /** 从状态文件加载启用集合。文件不存在 → 全禁用（用户确认的默认策略）。 */
    private void loadState() {
        enabledIds.clear();
        Path file = launcherRoot.resolve(STATE_FILE);
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            State state = MAPPER.readValue(file.toFile(), State.class);
            if (state.enabled != null) {
                enabledIds.addAll(state.enabled);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "读取插件状态文件失败，按默认（全禁用）处理: " + file, e);
        }
    }

    /** 持久化启用集合到状态文件（覆盖写入）。 */
    private void saveState() {
        Path file = launcherRoot.resolve(STATE_FILE);
        try {
            Files.createDirectories(file.getParent());
            State state = new State();
            state.enabled = new ArrayList<>(enabledIds);
            MAPPER.writeValue(file.toFile(), state);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "保存插件状态失败: " + file, e);
        }
    }

    /** 状态文件结构：{@code {"enabled": ["plugin-id", ...]}}（未列出的插件视为禁用）。 */
    public static final class State {

        @JsonProperty("enabled")
        public List<String> enabled = new ArrayList<>();
    }
}
