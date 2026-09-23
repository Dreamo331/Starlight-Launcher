/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.service;

import com.example.starlight.model.CallbackInterfaces.ProgressCallback;
import com.example.starlight.model.CallbackInterfaces.ResultCallback;
import com.example.starlight.model.LaunchConfig;
import com.example.starlight.model.LaunchResult;
import com.example.starlight.auth.AccountManager;
import com.example.starlight.auth.AccountManager.Account;
import com.example.starlight.auth.offline.OfflineSkinManager;
import com.example.starlight.auth.offline.OfflineSkinServer;
import com.example.starlight.auth.offline.Skin;
import com.example.starlight.colorblind.ColorBlindOverlayManager;
import com.example.starlight.config.StarlightConfig;
import com.example.starlight.config.VersionConfigManager;
import com.example.starlight.download.GameResourceCompleter;
import com.example.starlight.gamebat.MinecraftLauncherBuilder;
import com.example.starlight.lang.GameLanguage;
import com.example.starlight.lang.GameOptions;
import com.example.starlight.loginmicrosoft.MinecraftAuthLauncherDeviceCode;
import com.example.starlight.newui.ui.VersionIconKit;
import com.example.starlight.service.FileService;
import com.example.starlight.util.LegacyLaunchArgs;
import com.example.starlight.util.VersionUtils;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.startgame.LaunchInfo;
import com.startgame.launcher.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 游戏启动服务 — 负责 Minecraft 进程的启动、停止、脚本生成
 */
public class GameLauncherService {

    private static final Logger log = LoggerFactory.getLogger(GameLauncherService.class);

    private static final String LOG_DIR = "logs";

    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "launcher-worker");
        t.setDaemon(true);
        return t;
    });

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            POOL.shutdownNow();
            try { POOL.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException e) {
                POOL.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }, "launcher-pool-shutdown"));
    }

    private static volatile Process runningGameProcess = null;

    /**
     * 启动取消标志位：用户在准备阶段点击"取消启动"时置位。
     * <p>既是启动流程各阶段之间的检查点，也直接传给资源补全（
     * {@link GameResourceCompleter#completeAll(String, String, GameResourceCompleter.ProgressCallback, java.util.concurrent.atomic.AtomicBoolean)}），
     * 让下载中的文件也能随取消立即停止（此前取消只挡后续阶段，正在下载的资源会继续跑完）。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean launchCancelled =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // ================================================================
    //  启动游戏
    // ================================================================

    /**
     * 异步启动 Minecraft
     */
    public static void launchMinecraftAsync(LaunchConfig config,
                                             ProgressCallback onProgress,
                                             ResultCallback<LaunchResult> onResult) {
        launchMinecraftAsync(config, onProgress, onResult, null);
    }

    public static void launchMinecraftAsync(LaunchConfig config,
                                             ProgressCallback onProgress,
                                             ResultCallback<LaunchResult> onResult,
                                             Runnable onGameStarted) {
        POOL.submit(() -> {
            // 本次启动对应实例的 PID（-1 = 进程还没起来）：退出与异常两条路径都要用它注销实例
            final long[] gamePidRef = new long[]{-1L};
            try {
                // 每次启动重置取消标志
                launchCancelled.set(false);

                // Token 过期自动刷新（两种静默登录模式，与 UIGeneralControlClass 一致）
                // 模式一「启动游戏时静默登录」：每次启动游戏时检查并静默刷新过期令牌（默认）；
                // 模式二「开启启动器静默登录」：启动器启动时已在后台刷新，此处保留过期检查（幂等）
                Account currentAccount = AccountManager.getCurrentAccount();
                AccountManager.Account refreshedAccount = SilentLoginManager.refreshIfNeeded(
                        (pct, msg) -> onProgress.onProgress(pct, msg));
                if (refreshedAccount != null) {
                    config.accessToken = refreshedAccount.accessToken;
                    config.uuid = refreshedAccount.id;
                    config.userName = refreshedAccount.name;
                    config.userType = "msa";
                }
                if (launchCancelled.get()) { onResult.onError("启动已取消"); return; }

                onProgress.onProgress(5, "正在读取版本信息...");
                LaunchInfo info = buildLaunchInfo(config);
                Path jsonPath = info.resolveVersionDir().resolve(config.version + ".json");
                if (!Files.exists(jsonPath)) {
                    onResult.onError("版本 JSON 文件不存在: " + jsonPath);
                    return;
                }
                String jsonString = Files.readString(jsonPath);
                JsonObject versionJson = new Gson().fromJson(jsonString, JsonObject.class);
                // 整合包等版本是壳 JSON（只有 id/inheritsFrom/time/releaseTime/type）：
                // 先沿继承链合并为自包含视图，再去重/判加载器/补老参数，
                // 后续资源补全、natives 提取、启动库参数构建都拿到完整 libraries/assetIndex
                versionJson = VersionUtils.resolveInheritedJson(Path.of(config.gameDir), config.version, versionJson);
                jsonString = versionJson.toString();
                // 合并型 JSON 里同一 artifact 可能有多个版本并存（HNT 的 guava 15.0 与 17.0 都在），
                // 不去重时类路径先命中旧版，FML 启动即 NoSuchMethodError
                if (VersionUtils.deduplicateLibraries(versionJson) >= 0) {
                    jsonString = versionJson.toString();
                }
                log.info("Launch version {} with java: {}", config.version, config.javaPath);
                // 统一判定入口（含本工程补判）：核心 jar 的 LoaderDetector 认不出
                // mainClass=launchwrapper.Launch 的 Forge 版本，会误判 Vanilla 裸启
                String loader = VersionIconKit.detectLoader(versionJson, config.version);
                info.setLoaderType(loader);
                // 核心 jar 构建参数时会漏掉 --userProperties（1.7.x/1.12.x 的 Main 必填），补上
                var injectedArgs = LegacyLaunchArgs.apply(info, versionJson);
                if (!injectedArgs.isEmpty()) {
                    log.info("Injected legacy game args: {}", injectedArgs);
                }
                onProgress.onProgress(20, "检测到加载器: " + loader);
                if (launchCancelled.get()) { onResult.onError("启动已取消"); return; }

                // 资源准备（并行加速开启时：原生库检查与资源补全同时进行，占 25~60 区间）
                // 关闭时保持原串行流程：先补全资源（资产+库），再检查原生库
                onProgress.onProgress(25, "正在准备游戏资源...");
                if (ParallelLaunchManager.isEnabled()) {
                    boolean prepOk = ParallelLaunchManager.prepare(config.gameDir, config.version, jsonString,
                            (pct, msg) -> onProgress.onProgress(Math.min(25 + (int) (pct * 0.35), 60), msg),
                            launchCancelled);
                    if (!prepOk) {
                        if (launchCancelled.get()) { onResult.onError("启动已取消"); return; }
                        onResult.onError("原生库准备失败，请检查网络连接或重新安装该版本");
                        return;
                    }
                } else {
                    onProgress.onProgress(25, "正在补全资源文件...");
                    GameResourceCompleter.completeAll(config.gameDir, config.version,
                            (pct, msg) -> onProgress.onProgress(Math.min(25 + (int) (pct * 0.25), 50), msg),
                            launchCancelled);
                }
                if (launchCancelled.get()) { onResult.onError("启动已取消"); return; }

                BaseLauncher launcher = createLauncher(loader, info);
                if (launcher == null) {
                    onResult.onError("未知加载器类型: " + loader);
                    return;
                }

                onProgress.onProgress(50, "正在初始化路径...");
                // === 方案一：在 initLogging() 之前采集已有日志文件 ===
                // 确保 initLogging() 创建的新日志文件不会被过滤掉
                final java.nio.file.Path logDir = java.nio.file.Paths.get(LOG_DIR);
                final java.util.Set<String> existingLogs;
                if (java.nio.file.Files.isDirectory(logDir)) {
                    java.util.Set<String> logs = new java.util.HashSet<>();
                    try (var stream = java.nio.file.Files.list(logDir)) {
                        stream.filter(p -> p.getFileName().toString().startsWith("minecraft_launch_"))
                              .map(p -> p.getFileName().toString())
                              .forEach(logs::add);
                    } catch (java.io.IOException e) {
                        log.warn("Failed to scan log directory", e);
                    }
                    existingLogs = logs;
                } else {
                    existingLogs = java.util.Collections.emptySet();
                }
                launcher.initPaths();
                launcher.initLogging(Paths.get(LOG_DIR));
                launcher.setVersionJson(versionJson);

                onProgress.onProgress(60, "正在处理原生库...");
                boolean nativesOk = FileService.checkAndCompleteNatives(config.gameDir, config.version, jsonString);
                if (!nativesOk) {
                    onResult.onError("原生库准备失败，请检查网络连接或重新安装该版本");
                    return;
                }
                boolean processOk = launcher.processNatives();
                if (!processOk) {
                    onResult.onError("原生库处理失败，无法启动游戏");
                    return;
                }

                // 应用游戏内设置（语言 / 全屏 → options.txt；取该版本合并后的有效值）
                onProgress.onProgress(70, "正在应用版本设置（语言 / 全屏）...");
                Map<String, String> launchEffective = VersionConfigManager.buildEffectiveIni(
                        StarlightConfig.readConfig(), config.gameDir, config.version);
                String langCode = launchEffective.getOrDefault("GameLanguage", "");
                if (langCode != null && !langCode.isEmpty()) {
                    GameLanguage.setLanguage(config.gameDir, config.version,
                            config.versionIsolation, langCode);
                }
                // 全屏模式：改为写入 options.txt 的 fullscreen 字段（与游戏内设置一致）
                boolean launchFullscreen = "true".equalsIgnoreCase(
                        launchEffective.getOrDefault("Fullscreen", "false"));
                GameOptions.setFullscreen(config.gameDir, config.version,
                        config.versionIsolation, launchFullscreen);

                // 离线皮肤服务器（仅离线账号且配置了自定义皮肤时启动）
                OfflineSkinServer skinServer = null;
                if (currentAccount != null && currentAccount.type == AccountManager.AccountType.OFFLINE) {
                    Skin skin = currentAccount.getSkin();
                    if (OfflineSkinManager.needsSkinServer(skin)) {
                        onProgress.onProgress(75, "正在启动离线皮肤服务器...");
                        try {
                            String uuid = currentAccount.id;
                            // 确保 UUID 为标准格式
                            if (uuid.length() == 32) {
                                uuid = uuid.replaceFirst(
                                        "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5");
                            }
                            skinServer = OfflineSkinManager.startSkinServer(
                                    java.util.UUID.fromString(uuid),
                                    currentAccount.name,
                                    skin);
                            if (skinServer != null) {
                                info.getJvmArgs().addAll(
                                        java.util.Arrays.asList(OfflineSkinManager.getAuthlibInjectorArgs(skinServer)));
                                log.info("Offline skin server started, port: {}", skinServer.getPort());
                            }
                        } catch (Exception e) {
                            log.warn("Failed to start offline skin server, launching without skin mode: {}", e.getMessage());
                        }
                    }
                }
                if (launchCancelled.get()) {
                    if (skinServer != null) OfflineSkinManager.stopSkinServer();
                    onResult.onError("启动已取消");
                    return;
                }

                onProgress.onProgress(80, "正在启动 Minecraft " + config.version + " ...");

                // 最终检查：launcher.launch() 前再次确认未被取消
                if (launchCancelled.get()) {
                    if (skinServer != null) OfflineSkinManager.stopSkinServer();
                    onResult.onError("启动已取消");
                    return;
                }

                // 注册进程启动回调，供 stopGame() 取消使用；同时登记进实例表
                // （插件 API 拉起游戏后，「关闭游戏进程」弹窗也要能列出并关闭它）
                launcher.setOnProcessStarted(process -> {
                    runningGameProcess = process;
                    gamePidRef[0] = process.pid();
                    GameInstanceRegistry.register(new GameInstanceRegistry.Instance(
                            process.pid(), config.version, config.gameDir, config.userName,
                            loader, System.currentTimeMillis()));
                    log.info("Game process captured, PID: {}", process.pid());
                });

                // === 游戏窗口检测（混合 PCL + HMCL 方案，两个阶段独立并行） ===
                // Phase 1: HMCL 方式 — 检测新 Java 进程
                // Phase 2: PCL 方式 — 监控游戏 stdout 日志，等 "Setting user:" / "Created textures" 出现
                // 两个阶段独立运行，任一成功即关闭启动动画
                // （方案一：logDir/existingLogs 已在 initLogging() 之前采集，见上方）
                // （方案三：整个检测线程被 try-catch 保护，防止静默死亡）
                // （方案四：使用 content.length() 字符长度而非 Files.size() 字节长度，避免索引错位）
                if (onGameStarted != null) {
                    final long launcherPid = ProcessHandle.current().pid();
                    // 就绪信号统一入口：帧生成器/色盲辅助等伴生工具与界面回调共用同一时刻
                    final Runnable gameStartedSignal = () -> {
                        try {
                            ColorBlindOverlayManager.notifyGameStarted();
                        } catch (Throwable t) {
                            log.warn("Color blind overlay start failed", t);
                        }
                        onGameStarted.run();
                    };
                    // 记录已有 Java 进程（排除启动器自身）
                    final java.util.Set<Long> existingJavaPids = ProcessHandle.allProcesses()
                            .filter(p -> p.isAlive() && p.pid() != launcherPid)
                            .filter(p -> {
                                var cmd = p.info().command();
                                return cmd.isPresent() && isJavaExecutable(cmd.get());
                            })
                            .map(ProcessHandle::pid)
                            .collect(java.util.stream.Collectors.toSet());

                    POOL.submit(() -> {
                        try {
                            // 等 launcher.launch() 时间创建进程
                            try { Thread.sleep(1500); } catch (InterruptedException e) {
                                Thread.currentThread().interrupt(); return;
                            }

                            long deadline = System.currentTimeMillis() + 60_000;
                            boolean processFound = false;
                            long processFoundTime = 0;
                            java.nio.file.Path logFile = null;
                            int lastContentLen = 0;

                            while (System.currentTimeMillis() < deadline) {
                                // Phase 1: 等待新 Java 进程出现（HMCL 方式）
                                if (!processFound) {
                                    processFound = ProcessHandle.allProcesses().anyMatch(p -> {
                                        if (!p.isAlive() || p.pid() == launcherPid) return false;
                                        if (existingJavaPids.contains(p.pid())) return false;
                                        return p.info().command()
                                                .filter(GameLauncherService::isJavaExecutable)
                                                .isPresent();
                                    });
                                    if (processFound) {
                                        processFoundTime = System.currentTimeMillis();
                                        log.info("Game Java process detected as started");
                                    }
                                }

                                // Phase 2: 日志文件 tail（PCL 方式）— 独立运行，不依赖进程检测
                                if (logFile == null) {
                                    try (var stream = java.nio.file.Files.list(logDir)) {
                                        logFile = stream
                                                .filter(p -> p.getFileName().toString().startsWith("minecraft_launch_"))
                                                .filter(p -> !existingLogs.contains(p.getFileName().toString()))
                                                .max(java.util.Comparator.comparingLong(p -> {
                                                    try { return java.nio.file.Files.getLastModifiedTime(p).toMillis(); }
                                                    catch (java.io.IOException e) { return 0L; }
                                                }))
                                                .orElse(null);
                                        if (logFile != null) {
                                            log.info("Found game log file: {}", logFile.getFileName());
                                        }
                                    } catch (java.io.IOException e) {
                                        log.warn("Failed to scan log directory", e);
                                    }
                                }

                                if (logFile != null) {
                                    try {
                                        String content = java.nio.file.Files.readString(logFile);
                                        // 方案四：用字符长度而非字节长度，避免 UTF-8 中文字节/字符索引错位
                                        if (content.length() > lastContentLen) {
                                            String newContent = content.substring(lastContentLen);
                                            lastContentLen = content.length();

                                            // PCL 风格日志进度检测（参考 ModWatcher.vb LogProgress 2 中的方式）
                                            if (newContent.contains("Setting user:")
                                                    || newContent.toLowerCase().contains("lwjgl version")
                                                    || newContent.contains("OpenAL initialized")
                                                    || newContent.contains("Starting up SoundSystem")
                                                    || (newContent.contains("Created")
                                                            && newContent.contains("textures")
                                                            && newContent.contains("atlas"))) {
                                                String reason = newContent.contains("Setting user:") ? "用户已设置"
                                                        : newContent.toLowerCase().contains("lwjgl version") ? "LWJGL 版本已确认"
                                                        : newContent.contains("OpenAL initialized") ? "OpenAL 已初始化"
                                                        : newContent.contains("Starting up SoundSystem") ? "SoundSystem 已启动"
                                                        : "材质已加载";
                                                log.info("Game ready signal detected ({}), closing launch animation", reason);
                                                gameStartedSignal.run();
                                                return;
                                            }
                                        }
                                    } catch (java.io.IOException e) {
                                        log.warn("Failed to scan log directory", e);
                                    }
                                }

                                // 后备方案 A：进程检测成功后 25 秒强制关闭（PCL 启动流程通常在 10~20 秒内完成）
                                if (processFound && System.currentTimeMillis() > processFoundTime + 25_000) {
                                    log.info("Game process started 25s but no window ready signal, forcing launch animation close");
                                    gameStartedSignal.run();
                                    return;
                                }

                                // 后备方案 B：已检测到日志文件但无就绪标志，临近超时提前关闭
                                if (logFile != null && System.currentTimeMillis() > deadline - 20_000) {
                                    log.info("Launch log found but no window ready signal (20s before timeout), forcing animation close");
                                    gameStartedSignal.run();
                                    return;
                                }

                                try { Thread.sleep(200); } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt(); return;
                                }
                            }
                            log.warn("Game window detection timeout (60s), no game window detected, forcing animation close");
                        } catch (Exception e) {
                            // 方案三：检测线程异常保护，防止静默死亡导致动画永不关闭
                            log.error("Game window detection thread error", e);
                        }
                        gameStartedSignal.run();
                    });
                }

                long startTime = System.currentTimeMillis();
                int exitCode = launcher.launch();
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                onProgress.onProgress(100, "游戏已退出，运行时长: " + elapsed + "秒");

                // 清除进程引用，避免 stopGame() 误操作后续无关进程；同时从实例表注销
                runningGameProcess = null;
                GameInstanceRegistry.unregister(gamePidRef[0]);

                // 色盲辅助：游戏已退出，目标窗口消失 → 关闭矫正工具
                try {
                    ColorBlindOverlayManager.notifyGameStopped();
                } catch (Throwable t) {
                    log.warn("Color blind overlay stop failed", t);
                }

                String errorLog = null;
                if (exitCode != 0) {
                    // 类似 PCL2/HMCL：优先使用 JVM 实时 stderr 输出
                    String stderr = launcher.getStderrOutput();
                    if (stderr != null && !stderr.trim().isEmpty()) {
                        try {
                            Path jvmErrFile = Paths.get(LOG_DIR,
                                    "jvm_error_" + System.currentTimeMillis() + ".log");
                            Files.writeString(jvmErrFile, stderr);
                            errorLog = jvmErrFile.toAbsolutePath().toString();
                        } catch (IOException ignored) {}
                    }
                    if (errorLog == null) {
                        errorLog = findLatestErrorLog(config.gameDir, startTime);
                    }
                }
                onResult.onSuccess(new LaunchResult(exitCode, elapsed, errorLog));

                // 停止离线皮肤服务
                if (skinServer != null) {
                    OfflineSkinManager.stopSkinServer();
                    log.info("Offline skin server stopped");
                }

            } catch (JsonSyntaxException | IOException | InterruptedException e) {
                // 启动失败也要注销：原先这里漏了注销，实例会一直挂在表里
                GameInstanceRegistry.unregister(gamePidRef[0]);
                onResult.onError("启动失败: " + e.getMessage());
            }
        });
    }

    /**
     * 生成 .bat 启动脚本
     */
    public static String generateBatScript(LaunchConfig config) throws Exception {
        String cmd = MinecraftLauncherBuilder.buildLaunchCommand(
                config.gameDir + "/versions/" + config.version + "/" + config.version + ".json",
                config.gameDir, config.version, config.javaPath,
                config.userName, config.uuid, config.accessToken,
                config.gameArgs, config.jvmArgs, "microsoft");

        String batchPath = "launch_" + config.version + ".bat";
        try (PrintWriter writer = new PrintWriter(batchPath, "UTF-8")) {
            writer.println("chcp 65001>nul");
            writer.println("@echo off");
            writer.println("title 启动 - " + config.version);
            writer.println("echo 游戏正在启动，请稍候...");
            writer.println("cd /D \"" + config.gameDir + "\"");
            writer.println();
            writer.println(cmd);
            writer.println();
            writer.println("pause");
        }
        return new java.io.File(batchPath).getAbsolutePath();
    }

    /** 强制停止游戏（本启动器拉起的实例全部关闭） */
    public static void stopGame() {
        launchCancelled.set(true);
        if (runningGameProcess != null && runningGameProcess.isAlive()) {
            runningGameProcess.destroyForcibly();
        }
        GameInstanceRegistry.killAll();
    }

    // ================================================================
    //  内部
    // ================================================================

    private static LaunchInfo buildLaunchInfo(LaunchConfig c) {
        LaunchInfo info = new LaunchInfo();
        info.setGameDirPath(Path.of(c.gameDir));
        info.setVersion(c.version);
        info.setJavaPath(c.javaPath);
        info.setUserName(c.userName);
        info.setUuid(c.uuid);
        info.setAccessToken(c.accessToken);
        info.setUserType(c.userType);
        info.setMinMemoryMB(c.minMemory);
        info.setMaxMemoryMB(c.maxMemory);
        info.setWindowWidth(c.windowWidth);
        info.setWindowHeight(c.windowHeight);
        // 全屏模式改由 options.txt 控制（启动前由 GameOptions 写入 fullscreen 字段）：
        // 固定传 false，确保核心启动器不再向游戏命令追加 --fullscreen，保持纯 options.txt 行为
        info.setFullscreen(false);
        info.setVersionIsolation(c.versionIsolation);
        if (!c.jvmArgs.isEmpty()) info.setJvmArgs(java.util.Arrays.asList(c.jvmArgs.split("\\s+")));
        if (!c.gameArgs.isEmpty()) info.setGameArgs(java.util.Arrays.asList(c.gameArgs.split("\\s+")));
        if (!c.preLaunchCommand.isEmpty()) info.setPreLaunchCommand(c.preLaunchCommand);
        if (!c.postExitCommand.isEmpty()) info.setPostExitCommand(c.postExitCommand);
        return info;
    }

    private static BaseLauncher createLauncher(String loader, LaunchInfo info) {
        return switch (loader) {
            case LoaderDetector.VANILLA -> new VanillaLauncher(info);
            case LoaderDetector.FABRIC, LoaderDetector.QUILT -> new FabricLauncher(info);
            case LoaderDetector.FORGE_LEGACY -> new ForgeLegacyLauncher(info);
            case LoaderDetector.FORGE_MODERN, LoaderDetector.NEOFORGE -> new ForgeModernLauncher(info);
            default -> null;
        };
    }

    private static String findLatestErrorLog(String gameDir, long launchStartTime) {
        // 优先搜索启动器自身的 logs/ 目录下的 minecraft_error_*.log（包含 JVM stderr）
        Path launcherLogs = Paths.get(LOG_DIR);
        if (Files.isDirectory(launcherLogs)) {
            try (java.util.stream.Stream<Path> stream = Files.list(launcherLogs)) {
                String found = stream
                        .filter(p -> p.getFileName().toString().matches("(minecraft_error_|jvm_error_).*\\.log"))
                        .sorted(java.util.Comparator.<Path, Long>comparing(p -> {
                            try { return Files.getLastModifiedTime(p).toMillis(); }
                            catch (IOException e) { return 0L; }
                        }).reversed())
                        .findFirst()
                        .map(p -> p.toAbsolutePath().toString())
                        .orElse(null);
                if (found != null) return found;
            } catch (IOException ignored) {}
        }
    
        // 后备：搜索游戏目录（latest.log 仅当本次启动后有写入才采用，避免误读旧日志）
        Path logsDir = Paths.get(gameDir, "logs");
        if (!Files.isDirectory(logsDir)) logsDir = Paths.get(gameDir);
        if (!Files.isDirectory(logsDir)) logsDir = launcherLogs;
        if (!Files.isDirectory(logsDir)) return null;
    
        try (java.util.stream.Stream<Path> stream = Files.list(logsDir)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".log"))
                    .filter(p -> p.getFileName().toString().contains("error")
                            || p.getFileName().toString().contains("crash")
                            || (p.getFileName().toString().equals("latest.log")
                                && isLogFresh(p, launchStartTime)))
                    .sorted(java.util.Comparator.<Path, Long>comparing(p -> {
                        try { return Files.getLastModifiedTime(p).toMillis(); }
                        catch (IOException e) { return 0L; }
                    }).reversed())
                    .findFirst()
                    .map(p -> p.toAbsolutePath().toString())
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** 判断日志文件是否在本次启动开始之后被写入（避免误读上一次启动留下的旧日志） */
    private static boolean isLogFresh(Path p, long launchStartTime) {
        try { return Files.getLastModifiedTime(p).toMillis() >= launchStartTime; }
        catch (IOException e) { return false; }
    }
    
    /**
     * 判断是否是 Java 可执行文件（PCL/HMCL 方式：匹配进程名）
     */
    private static boolean isJavaExecutable(String command) {
        String lower = command.toLowerCase();
        return lower.endsWith("java.exe") || lower.endsWith("javaw.exe") || lower.endsWith("java");
    }
}
