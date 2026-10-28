/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.service;


import com.example.starlight.util.I18N;
import com.example.starlight.model.CallbackInterfaces.ProgressCallback;
import com.example.starlight.model.CallbackInterfaces.ResultCallback;
import com.example.starlight.model.LaunchConfig;
import com.example.starlight.model.LaunchResult;
import com.example.starlight.auth.AccountManager;
import com.example.starlight.auth.AccountManager.Account;
import com.example.starlight.auth.AuthlibInjectorSupport;
import com.example.starlight.auth.offline.LocalYggdrasilServer;
import com.example.starlight.auth.offline.LocalCapeProxy;
import com.example.starlight.auth.offline.OfflineSkinManager;
import com.example.starlight.auth.offline.Skin;
import com.example.starlight.colorblind.ColorBlindOverlayManager;
import com.example.starlight.config.StarlightConfig;
import com.example.starlight.config.VersionConfigManager;
import com.example.starlight.download.DownloadEngineRouter;
import com.example.starlight.download.task.GameResourceCompleter;
import com.example.starlight.gamebat.MinecraftLauncherBuilder;
import com.example.starlight.lang.GameLanguage;
import com.example.starlight.lang.GameOptions;
import com.example.starlight.loginmicrosoft.MinecraftAuthLauncherDeviceCode;
import com.example.starlight.newui.ui.VersionIconKit;
import com.example.starlight.service.FileService;
import com.example.starlight.util.LaunchLog;
import com.example.starlight.util.LegacyLaunchArgs;
import com.example.starlight.util.VanillaVersion;
import com.example.starlight.util.VersionUtils;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.example.starlight.startmc.LaunchInfo;
import com.example.starlight.startmc.launcher.*;
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

    // 启动游戏 //

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
            // 本地披风服务器引用：同样放在 try 外面，退出与异常两条路径都要用它停止服务器
            final java.util.concurrent.atomic.AtomicReference<LocalYggdrasilServer> capeServerRef =
                    new java.util.concurrent.atomic.AtomicReference<>(null);
            // 本地披风代理引用（劫持 OptiFine s.optifine.net 披风下载）：与档案服务器同生命周期
            final java.util.concurrent.atomic.AtomicReference<LocalCapeProxy> capeProxyRef =
                    new java.util.concurrent.atomic.AtomicReference<>(null);
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
                // 离线皮肤（PCL2 风格）：离线账号按皮肤配置微调传给游戏的 UUID（Steve/Alex 模型
                // 由 UUID 决定）；始终以账号基础 UUID 为起点，幂等，不改动账号本身
                if (currentAccount != null && currentAccount.type == AccountManager.AccountType.OFFLINE) {
                    String resolvedUuid = OfflineSkinManager.resolveUuid(currentAccount.id, currentAccount.getSkin());
                    if (resolvedUuid != null && !resolvedUuid.isEmpty()) {
                        config.uuid = resolvedUuid;
                    }
                }
                // 披风：本地 Yggdrasil 档案服务器 + authlib-injector（仅当配置了披风；
                // 无披风保持纯 PCL2 机制）。必须在 buildLaunchInfo 之前追加 JVM 参数。
                if (currentAccount != null && currentAccount.type == AccountManager.AccountType.OFFLINE) {
                    try {
                        Skin.LoadedSkin loaded = OfflineSkinManager.loadForLaunch(
                                currentAccount.getSkin(), currentAccount.name);
                        LocalYggdrasilServer capeServer = LocalYggdrasilServer.start(
                                loaded, config.uuid, currentAccount.name);
                        if (capeServer != null) {
                            capeServerRef.set(capeServer);
                            java.util.List<String> injectArgs = new java.util.ArrayList<>(
                                    AuthlibInjectorSupport.buildLaunchJvmArgs(capeServer.getApiBase()));
                            // 本地披风代理：SOCKS5 劫持 OptiFine s.optifine.net 披风下载
                            //（1.17.1 纯 OptiFine 本地玩家披风唯一原生通道；--proxyHost 由
                            // Main 解析为 SOCKS 代理，HttpTexture 走 HttpPipeline SOCKS 隧道）
                            LocalCapeProxy capeProxy = LocalCapeProxy.start(
                                    OfflineSkinManager.capePngBytes(loaded), currentAccount.name);
                            if (capeProxy != null) {
                                capeProxyRef.set(capeProxy);
                                String agentArg = capeProxy.agentJvmArg();
                                if (agentArg != null) {
                                    injectArgs.add(agentArg);
                                }
                            }
                            String extra = String.join(" ", injectArgs);
                            config.jvmArgs = (config.jvmArgs == null || config.jvmArgs.isBlank())
                                    ? extra : config.jvmArgs.trim() + " " + extra;
                            LaunchLog.info(I18N.get("service.p0077", capeServer.getApiBase()));
                        }
                    } catch (Exception e) {
                        log.warn("Failed to start local cape server: {}", e.getMessage());
                    }
                }
                if (launchCancelled.get()) { onResult.onError(I18N.get("service.t0018")); return; }

                // 启动流程日志（PCL 标准）：预检测 / 登录方式 //
                if (config.userName != null && !config.userName.isEmpty()) {
                    boolean isMsa = "msa".equalsIgnoreCase(config.userType);
                    LaunchLog.info(isMsa ? "登录方式：正版（" + config.userName + "）"
                                        : "登录方式：离线（" + config.userName + "）");
                }

                // 自动选择 Java（与 UIGeneralControlClass 主链路保持一致，修复插件 API 入口
                // 走本服务时 AutoJava 静默失效的问题）。版本独立设置可仅对该版本关闭自动选择。
                boolean autoJavaOn = VersionConfigManager.effectiveAutoJava(
                        StarlightConfig.readConfig(), config.gameDir, config.version);
                if (autoJavaOn) {
                    onProgress.onProgress(5, I18N.get("service.t0019"));
                    String autoJava = com.example.starlight.gui.UIGeneralControlClass
                            .autoSelectJavaPath(config.version, config.gameDir);
                    if (autoJava != null) {
                        config.javaPath = autoJava;
                        LaunchLog.info(I18N.get("service.p0078", autoJava));
                    } else {
                        LaunchLog.warn(I18N.get("service.t0020"));
                    }
                }
                com.example.starlight.gui.UIGeneralControlClass
                        .recordLaunchJavaPath(config.version, config.javaPath);

                onProgress.onProgress(5, I18N.get("service.t0021"));
                LaunchLog.info(I18N.get("service.t0022"));
                LaunchInfo info = buildLaunchInfo(config);
                Path jsonPath = info.resolveVersionDir().resolve(config.version + ".json");
                if (!Files.exists(jsonPath)) {
                    onResult.onError(I18N.get("service.p0079", jsonPath));
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
                // 记录启动实例快照（真正启动的版本/目录/加载器/Java/隔离），供崩溃诊断回读
                com.example.starlight.gui.UIGeneralControlClass.recordLaunchSnapshot(
                        config.version, config.gameDir, loader, config.javaPath, config.versionIsolation);
                // 核心 jar 构建参数时会漏掉 --userProperties（1.7.x/1.12.x 的 Main 必填），补上
                var injectedArgs = LegacyLaunchArgs.apply(info, versionJson);
                if (!injectedArgs.isEmpty()) {
                    log.info("Injected legacy game args: {}", injectedArgs);
                }
                // 1.17+ 本地玩家皮肤/披风直注入：SkinManager 对本地玩家直接取 Session profile
                // 属性（--profileProperties），不走会话档案查询；无该参数时本地玩家永远没有
                // textures 属性，皮肤/披风无法显示（1.17.1 反编译实证）
                if (capeServerRef.get() != null) {
                    java.util.List<String> pp = OfflineSkinManager.profilePropertiesArgs(
                            capeServerRef.get(), config.version);
                    if (!pp.isEmpty()) {
                        java.util.List<String> ga = new java.util.ArrayList<>();
                        if (info.getGameArgs() != null) ga.addAll(info.getGameArgs());
                        ga.addAll(pp);
                        info.setGameArgs(ga);
                        log.info("Injected --profileProperties for local player textures");
                    }
                }
                onProgress.onProgress(20, I18N.get("service.p0080", loader));
                LaunchLog.info(I18N.get("service.p0081", loader));
                if (launchCancelled.get()) { onResult.onError(I18N.get("service.t0023")); return; }

                // 资源准备（并行加速开启时：原生库检查与资源补全同时进行，占 25~60 区间）
                // 关闭时保持原串行流程：先补全资源（资产+库），再检查原生库
                onProgress.onProgress(25, I18N.get("service.t0024"));
                LaunchLog.info(I18N.get("service.t0025"));
                if (ParallelLaunchManager.isEnabled()) {
                    boolean prepOk = ParallelLaunchManager.prepare(config.gameDir, config.version, jsonString,
                            (pct, msg) -> onProgress.onProgress(Math.min(25 + (int) (pct * 0.35), 60), msg),
                            launchCancelled);
                    if (!prepOk) {
                        if (launchCancelled.get()) { onResult.onError(I18N.get("service.t0026")); return; }
                        onResult.onError(I18N.get("service.t0027"));
                        return;
                    }
                } else {
                    onProgress.onProgress(25, I18N.get("service.t0028"));
                    DownloadEngineRouter.completeAll(config.gameDir, config.version,
                            (pct, msg) -> onProgress.onProgress(Math.min(25 + (int) (pct * 0.25), 50), msg),
                            launchCancelled);
                }
                if (launchCancelled.get()) { onResult.onError(I18N.get("service.t0029")); return; }

                BaseLauncher launcher = createLauncher(loader, info);
                if (launcher == null) {
                    onResult.onError(I18N.get("service.p0082", loader));
                    return;
                }
                // 取消判定器：把「取消启动」标志位交给基类，由它补杀窗口期起来的进程，
                // 并让退出日志不把强制结束误报成「游戏异常退出 (代码: 1)」
                launcher.setCancelChecker(launchCancelled::get);

                onProgress.onProgress(50, I18N.get("service.t0030"));
                LaunchLog.info(I18N.get("service.t0031"));
                // 方案一：在 initLogging() 之前采集已有日志文件 //
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

                onProgress.onProgress(60, I18N.get("service.t0032"));
                LaunchLog.info(I18N.get("service.t0033"));
                boolean nativesOk = FileService.checkAndCompleteNatives(config.gameDir, config.version, jsonString);
                if (!nativesOk) {
                    onResult.onError(I18N.get("service.t0034"));
                    return;
                }
                boolean processOk = launcher.processNatives();
                if (!processOk) {
                    onResult.onError(I18N.get("service.t0035"));
                    return;
                }

                // 应用游戏内设置（语言 / 全屏 → options.txt；取该版本合并后的有效值）
                onProgress.onProgress(70, I18N.get("service.t0036"));
                LaunchLog.info(I18N.get("service.t0037"));
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

                // 离线皮肤资源包（PCL2 风格：自定义皮肤以资源包覆盖默认贴图，
                // 无需本地皮肤服务器 / authlib-injector）
                if (currentAccount != null && currentAccount.type == AccountManager.AccountType.OFFLINE) {
                    onProgress.onProgress(75, I18N.get("service.t0038"));
                    try {
                        Skin skin = currentAccount.getSkin();
                        VanillaVersion vanilla = VanillaVersion.resolve(Path.of(config.gameDir), config.version);
                        OfflineSkinManager.applySkinPack(skin, currentAccount.name,
                                config.gameDir, config.version, config.versionIsolation, vanilla);
                    } catch (Exception e) {
                        log.warn("Failed to apply offline skin resource pack: {}", e.getMessage());
                    }
                }
                if (launchCancelled.get()) {
                    onResult.onError(I18N.get("service.t0039"));
                    return;
                }

                onProgress.onProgress(80, I18N.get("service.p0083", config.version + " ..."));

                // 启动参数区块（PCL 标准：参数获取开始 / 分隔线 / 每行一个参数） //
                LaunchLog.info(I18N.get("service.t0040"));
                if (versionJson != null && versionJson.has("mainClass")) {
                    LaunchLog.info(versionJson.get("mainClass").getAsString());
                }
                LaunchLog.info("Java：" + config.javaPath);
                LaunchLog.info(I18N.get("service.p0084", config.minMemory + " MB"));
                LaunchLog.info(I18N.get("service.p0085", config.maxMemory + " MB"));
                LaunchLog.info(I18N.get("service.p0086", config.version));
                LaunchLog.info(I18N.get("service.p0087", config.gameDir));
                LaunchLog.info(I18N.get("service.p0088", (config.versionIsolation ? I18N.get("service.t0061") : I18N.get("service.t0062"))));
                if (config.windowWidth > 0 && config.windowHeight > 0) {
                    LaunchLog.info(I18N.get("service.p0089", config.windowWidth + " x " + config.windowHeight));
                }
                // 进程微调（高级设置）：只有真的设置了才占日志行，默认值不打扰
                if (config.windowTitle != null && !config.windowTitle.isBlank()) {
                    LaunchLog.info(I18N.get("service.p0090", config.windowTitle));
                }
                if (config.processPriority != null && !"normal".equalsIgnoreCase(config.processPriority)) {
                    LaunchLog.info(I18N.get("service.p0091", com.example.starlight.util.GameProcessTweaks
                            .priorityLabel(config.processPriority)));
                }
                if (config.jvmArgs != null && !config.jvmArgs.isEmpty()) {
                    LaunchLog.info(I18N.get("service.p0092", config.jvmArgs));
                }
                if (config.gameArgs != null && !config.gameArgs.isEmpty()) {
                    LaunchLog.info(I18N.get("service.p0093", config.gameArgs));
                }
                LaunchLog.info(I18N.get("service.t0041"));

                // 最终检查：launcher.launch() 前再次确认未被取消
                if (launchCancelled.get()) {
                    onResult.onError(I18N.get("service.t0042"));
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
                    LaunchLog.info(I18N.get("service.p0094", config.javaPath + "（PID " + process.pid() + "）"));
                });

                // 游戏窗口检测（混合 PCL + HMCL 方案，两个阶段独立并行） //
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
                        // 已取消就不再上报「游戏已启动」：检测线程原来不响应取消，会在 25~60 秒后
                        // 补报一次，把一次取消误当成启动成功（伴生工具误拉起、界面补对勾等）
                        if (launchCancelled.get()) return;
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
                                // 用户已取消：不必再轮询到 25~60 秒超时，直接收工
                                if (launchCancelled.get()) return;
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
                                                // i18n-lock: 不要翻译此行中文——识别启动日志关键字，翻译即功能失效
                                                String reason = newContent.contains("Setting user:") ? "用户已设置"
                                                        // i18n-lock: 不要翻译此行中文——识别启动日志关键字，翻译即功能失效
                                                        : newContent.toLowerCase().contains("lwjgl version") ? "LWJGL 版本已确认"
                                                        // i18n-lock: 不要翻译此行中文——识别启动日志关键字，翻译即功能失效
                                                        : newContent.contains("OpenAL initialized") ? "OpenAL 已初始化"
                                                        // i18n-lock: 不要翻译此行中文——识别启动日志关键字，翻译即功能失效
                                                        : newContent.contains("Starting up SoundSystem") ? "SoundSystem 已启动"
                                                        : I18N.get("service.t0063");
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
                // 崩溃报告新鲜度过滤用「本次启动开始时间」；回填到快照
                com.example.starlight.gui.UIGeneralControlClass.markLaunchSnapshotStartTime(startTime);
                int exitCode = launcher.launch();
                LocalYggdrasilServer capeServerDone = capeServerRef.getAndSet(null);
                if (capeServerDone != null) capeServerDone.stop();
                LocalCapeProxy capeProxyDone = capeProxyRef.getAndSet(null);
                if (capeProxyDone != null) capeProxyDone.stop();
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                onProgress.onProgress(100, I18N.get("gls.r0001", elapsed));

                // 启动结束日志（PCL 标准：~ 基础参数 ~ / ~ 登录参数 ~ / 开始启动结束处理） //
                LaunchLog.info(I18N.get("service.t0043"));
                LaunchLog.info(I18N.get("service.p0095", config.version));
                LaunchLog.info(I18N.get("service.p0096", config.maxMemory + " MB"));
                LaunchLog.info(I18N.get("service.p0097", config.gameDir));
                LaunchLog.info(I18N.get("service.p0098", (config.versionIsolation ? I18N.get("service.t0064") : I18N.get("service.t0065"))));
                LaunchLog.info(I18N.get("service.p0099", config.javaPath));
                LaunchLog.info("");
                LaunchLog.info(I18N.get("service.t0044"));
                LaunchLog.info(I18N.get("service.p0100", (config.userName == null ? "" : config.userName)));
                LaunchLog.info("AccessToken：" + LaunchLog.mask(config.accessToken));
                LaunchLog.info("UUID：" + LaunchLog.mask(config.uuid));
                // i18n-lock: 不要翻译此行中文——识别启动日志关键字，翻译即功能失效
                LaunchLog.info("登录方式：" + ("msa".equalsIgnoreCase(config.userType) ? "正版" : "离线"));
                LaunchLog.info("");
                LaunchLog.info(I18N.get("service.t0045"));
                LaunchLog.info("游戏已退出，运行时长：" + elapsed + "秒");

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
                // 取消启动（用户主动结束）不算崩溃：不去收集错误日志，避免把无关的 stderr
                // 挂到这次结果上（与 UIGeneralControlClass 那条启动路径保持一致）
                if (exitCode != 0 && !launcher.isCancelledByUser()) {
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

            } catch (JsonSyntaxException | IOException | InterruptedException e) {
                LocalYggdrasilServer capeServerErr = capeServerRef.getAndSet(null);
                if (capeServerErr != null) capeServerErr.stop();
                LocalCapeProxy capeProxyErr = capeProxyRef.getAndSet(null);
                if (capeProxyErr != null) capeProxyErr.stop();
                // 启动失败也要注销：原先这里漏了注销，实例会一直挂在表里
                GameInstanceRegistry.unregister(gamePidRef[0]);
                LaunchLog.error(I18N.get("service.p0101", e.getMessage()));
                onResult.onError(I18N.get("service.p0102", e.getMessage()));
            }
        });
    }

    /**
     * 生成 .bat 启动脚本
     */
    public static String generateBatScript(LaunchConfig config) throws Exception {
        // 离线皮肤：与正常启动一致，按当前离线账号的皮肤配置解析定制 UUID
        Account current = AccountManager.getCurrentAccount();
        if (current != null && current.type == AccountManager.AccountType.OFFLINE) {
            String resolvedUuid = OfflineSkinManager.resolveUuid(current.id, current.getSkin());
            if (resolvedUuid != null && !resolvedUuid.isEmpty()) {
                config.uuid = resolvedUuid;
            }
        }
        String cmd = MinecraftLauncherBuilder.buildLaunchCommand(
                config.gameDir + "/versions/" + config.version + "/" + config.version + ".json",
                config.gameDir, config.version, config.javaPath,
                config.userName, config.uuid, config.accessToken,
                config.gameArgs, config.jvmArgs, "microsoft");

        String batchPath = "launch_" + config.version + ".bat";
        try (PrintWriter writer = new PrintWriter(batchPath, "UTF-8")) {
            writer.println("chcp 65001>nul");
            writer.println("@echo off");
            writer.println(I18N.get("service.p0103", config.version));
            writer.println(I18N.get("service.t0046"));
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
        LaunchLog.info(I18N.get("service.t0047"));
        if (runningGameProcess != null && runningGameProcess.isAlive()) {
            runningGameProcess.destroyForcibly();
        }
        GameInstanceRegistry.killAll();
    }

    // 内部 //

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
        // 按引号分组切分：-javaagent/-D 里可能含带空格的 Windows 用户目录路径
        if (!c.jvmArgs.isEmpty()) info.setJvmArgs(com.example.starlight.startmc.LaunchInfo.splitCommandLine(c.jvmArgs));
        if (!c.gameArgs.isEmpty()) info.setGameArgs(java.util.Arrays.asList(c.gameArgs.split("\\s+")));
        if (!c.preLaunchCommand.isEmpty()) info.setPreLaunchCommand(c.preLaunchCommand);
        if (!c.postExitCommand.isEmpty()) info.setPostExitCommand(c.postExitCommand);
        // 进程微调（高级设置）：自定义窗口标题 + 进程优先级，在 BaseLauncher 启动进程后应用
        info.setWindowTitle(c.windowTitle);
        info.setProcessPriority(c.processPriority);
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
