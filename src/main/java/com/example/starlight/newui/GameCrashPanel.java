/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.newui;


import com.example.starlight.util.I18N;
import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.ui.VersionIconKit;
import com.example.starlight.crash.CrashDiagnosticData;
import com.example.starlight.crash.CrashReportParser;
import com.example.starlight.gui.UIGeneralControlClass;
import com.example.starlight.gui.UIGeneralControlClass.LaunchConfig;
import com.example.starlight.model.ActionResult;
import com.example.starlight.model.ErrorCode;
import com.example.starlight.log.sendlogs;
import com.example.starlight.model.LogUploadRecord;
import com.example.starlight.newui.ui.LogUploadResultWindow;
import com.example.starlight.service.LogUploadStore;
import com.example.starlight.util.LogExporter;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.example.starlight.startmc.launcher.LoaderDetector;
import com.example.starlight.startmc.launcher.PlatformUtils;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 游戏崩溃诊断面板 —— 集成在启动器窗口内（替代独立 Stage 的 GameCrashWindow）。
 * 展示错误码、崩溃原因、环境信息与关键日志，提供重启游戏 / 复制错误 / 上传日志 /
 * 导出日志 / AI 诊断操作。
 */
public class GameCrashPanel extends VBox {

    private final CrashDiagnosticData crashData;
    private final Runnable restartHandler;   // 重启游戏回调
    private final Consumer<String> toast;    // 提示回调（LauncherView.showToast）
    private final Runnable openAIDiagnosis;  // 打开 AI 诊断面板回调
    private final Runnable closeHandler;     // 关闭面板回调（LauncherView.closeModalPanel）

    private Button sendBtn;
    private Button exportBtn;
    private Label statusLabel;
    private final Window ownerWindow;   // 上传结果弹窗的 owner（LauncherView 主窗口）

    public GameCrashPanel(CrashDiagnosticData crashData, Runnable restartHandler,
                          Consumer<String> toast, Runnable openAIDiagnosis, Runnable closeHandler,
                          Window ownerWindow) {
        super(10);
        this.crashData = crashData;
        this.restartHandler = restartHandler;
        this.toast = toast;
        this.openAIDiagnosis = openAIDiagnosis;
        this.closeHandler = closeHandler;
        this.ownerWindow = ownerWindow;
        setPadding(new Insets(10, 4, 0, 4));

        ErrorCode ec = crashData.getErrorCode();
        if (ec == null) ec = ErrorCode.E0001;

        // 顶部：标题 + 退出码 + 错误码徽标
        HBox titleRow = new HBox(10);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        Label titleLabel = new Label(I18N.get("newui.t0028"));
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: 700; -fx-text-fill: #dc2626;");
        Label exitLabel = new Label(I18N.get("newui.p0141", crashData.getExitCode()));
        exitLabel.getStyleClass().add("modal-text");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label codeLabel = new Label(ec.getCode());
        codeLabel.setStyle("-fx-background-color: #fee2e2; -fx-text-fill: #dc2626;" +
                " -fx-padding: 2 10; -fx-background-radius: 10; -fx-font-size: 12px; -fx-font-weight: 600;");
        Label typeLabel = new Label(ec.getDisplayName());
        typeLabel.getStyleClass().add("modal-text");
        titleRow.getChildren().addAll(titleLabel, exitLabel, spacer, codeLabel, typeLabel);

        // 环境信息行
        HBox envRow = new HBox(16);
        envRow.setAlignment(Pos.CENTER_LEFT);
        envRow.getStyleClass().add("modal-info-row");
        envRow.setPadding(new Insets(8, 12, 8, 12));
        envRow.getChildren().addAll(
                envInfo(I18N.get("newui.t0115"), crashData.getGameVersion()),
                envInfo(I18N.get("newui.t0116"), crashData.getLoaderType()),
                envInfo("Java", crashData.getJavaVersion()),
                envInfo(I18N.get("newui.t0117"), crashData.getMaxMemory() > 0 ? crashData.getMaxMemory() + " MB" : null),
                envInfo(I18N.get("newui.t0118"), crashData.getOsInfo()));

        // 崩溃原因
        Label descTitle = new Label(I18N.get("newui.t0029"));
        descTitle.getStyleClass().add("modal-text-title");
        TextArea descArea = new TextArea(buildDescription());
        descArea.setEditable(false);
        descArea.setWrapText(true);
        descArea.setPrefHeight(80);
        descArea.setStyle("-fx-font-family: 'Consolas', 'Microsoft YaHei', monospace; -fx-font-size: 12px;");

        // 关键日志
        Label logTitle = new Label(I18N.get("newui.t0030"));
        logTitle.getStyleClass().add("modal-text-title");
        TextArea logArea = new TextArea();
        List<String> logLines = crashData.getLatestLogTail();
        logArea.setText(logLines != null && !logLines.isEmpty()
                ? String.join("\n", logLines) : I18N.get("newui.t0031"));
        logArea.setEditable(false);
        logArea.setWrapText(false);
        logArea.setPrefHeight(130);
        logArea.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 11px;");
        VBox.setVgrow(logArea, Priority.ALWAYS);

        // 操作状态提示（上传/导出结果展示在面板内，替代系统 Alert）
        statusLabel = new Label();
        statusLabel.setWrapText(true);
        statusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #2563eb;");
        statusLabel.setMinHeight(16);

        // 按钮行
        HBox btnRow = new HBox(8);
        btnRow.setAlignment(Pos.CENTER_LEFT);
        Button restartBtn = AppIcons.button("arrow-right", I18N.get("newui.t0032"));
        restartBtn.getStyleClass().add("btn-primary");
        restartBtn.setStyle("-fx-padding: 6 16; -fx-font-size: 12px; -fx-cursor: hand;");
        restartBtn.setOnAction(e -> {
            closeHandler.run();
            if (restartHandler != null) restartHandler.run();
        });
        Button copyBtn = AppIcons.button("copy", I18N.get("newui.t0033"));
        copyBtn.getStyleClass().add("btn-primary");
        copyBtn.setStyle("-fx-padding: 6 16; -fx-font-size: 12px; -fx-cursor: hand;");
        copyBtn.setOnAction(e -> handleCopyError());
        sendBtn = AppIcons.button("upload", I18N.get("newui.t0034"));
        sendBtn.getStyleClass().add("btn-primary");
        sendBtn.setStyle("-fx-padding: 6 16; -fx-font-size: 12px; -fx-cursor: hand;");
        sendBtn.setOnAction(e -> handleSendLog());
        exportBtn = AppIcons.button("download", I18N.get("newui.t0035"));
        exportBtn.getStyleClass().add("btn-primary");
        exportBtn.setStyle("-fx-padding: 6 16; -fx-font-size: 12px; -fx-cursor: hand;");
        exportBtn.setOnAction(e -> handleExportLog());
        Button chatBtn = AppIcons.button("sparkles", I18N.get("newui.t0036"));
        chatBtn.getStyleClass().add("btn-primary");
        chatBtn.setStyle("-fx-padding: 6 16; -fx-font-size: 12px; -fx-background-color: #8b5cf6;" +
                " -fx-text-fill: white; -fx-cursor: hand;");
        chatBtn.setOnAction(e -> openAIDiagnosis.run());
        Region btnSpacer = new Region();
        HBox.setHgrow(btnSpacer, Priority.ALWAYS);
        Button closeBtn = AppIcons.button("close", I18N.get("newui.t0037"));
        closeBtn.getStyleClass().add("modal-btn-cancel");
        // 与同排按钮（6 16 / 12px）保持一致：.modal-btn-cancel 是弹窗底栏尺寸，直接用会比同排高一截
        closeBtn.setStyle("-fx-padding: 6 16; -fx-font-size: 12px;");
        closeBtn.setOnAction(e -> closeHandler.run());
        btnRow.getChildren().addAll(restartBtn, copyBtn, sendBtn, exportBtn, chatBtn, btnSpacer, closeBtn);

        getChildren().addAll(titleRow, envRow, descTitle, descArea, logTitle, logArea, statusLabel, btnRow);
    }

    /** 环境信息项：名称 + 值（空值显示 "-"） */
    private Label envInfo(String name, String value) {
        Label lbl = new Label(name + ": " + (value != null && !value.isBlank() ? value : "-"));
        lbl.getStyleClass().add("modal-text");
        return lbl;
    }

    /** 构建错误描述：优先 errorDescription，其次 crash-report 摘要 */
    private String buildDescription() {
        String desc = crashData.getErrorDescription();
        if (desc == null || desc.isBlank()) {
            if (crashData.getCrashInfo() != null) {
                CrashReportParser.CrashInfo ci = crashData.getCrashInfo();
                desc = ci.cause != null && !ci.cause.isEmpty() ? ci.cause
                        : (ci.stackTrace != null && !ci.stackTrace.isEmpty() ? ci.stackTrace : ci.title);
            }
            if (desc == null || desc.isBlank()) {
                desc = I18N.get("newui.p0142", crashData.getExitCode());
            }
        }
        return desc;
    }

    /** 复制错误摘要到剪贴板 */
    private void handleCopyError() {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        ClipboardContent content = new ClipboardContent();
        content.putString(crashData.buildSummaryText());
        clipboard.setContent(content);
        toast.accept(I18N.get("newui.t0038"));
    }

    /** 上传日志：优先 crash-report，其次 latest.log，最后 starlight.log；成功后存记录并弹出分享窗口 */
    private void handleSendLog() {
        final String logFileToUpload = findBestLogFile();
        if (logFileToUpload == null) {
            statusLabel.setText(I18N.get("newui.t0039"));
            return;
        }
        sendBtn.setDisable(true);
        sendBtn.setText(I18N.get("newui.t0040"));
        statusLabel.setText(I18N.get("newui.t0041"));
        new Thread(() -> {
            try {
                sendlogs.UploadResult ur = sendlogs.uploadLogFileEx(logFileToUpload, null);
                File f = new File(logFileToUpload);
                LogUploadRecord record = new LogUploadRecord(ur.id, ur.url, ur.token,
                        f.getName(), System.currentTimeMillis(), 1, f.length());
                LogUploadStore.add(record);
                Platform.runLater(() -> {
                    sendBtn.setDisable(false);
                    AppIcons.setText(sendBtn, "upload", I18N.get("newui.t0042"));
                    statusLabel.setText(I18N.get("newui.t0043"));
                    if (ownerWindow != null) {
                        LogUploadResultWindow.show(ownerWindow, record,
                                toast,
                                () -> statusLabel.setText(I18N.get("newui.t0044")));
                    } else {
                        toast.accept(I18N.get("newui.p0143", record.url));
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    sendBtn.setDisable(false);
                    AppIcons.setText(sendBtn, "upload", I18N.get("newui.t0045"));
                    statusLabel.setText(I18N.get("newui.p0144", e.getMessage()));
                });
            }
        }, "crash-log-upload").start();
    }

    /** 导出日志为 zip 压缩包 */
    private void handleExportLog() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18N.get("newui.t0046"));
        chooser.setInitialFileName("crash-report-" + System.currentTimeMillis() + ".zip");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(I18N.get("newui.t0047"), "*.zip"));
        File selected = chooser.showSaveDialog(getScene().getWindow());
        if (selected == null) return;

        exportBtn.setDisable(true);
        exportBtn.setText(I18N.get("newui.t0048"));
        statusLabel.setText(I18N.get("newui.t0049"));
        final String exportDir = selected.getParent();
        new Thread(() -> {
            String resultPath = LogExporter.exportCrashLogs(crashData, exportDir);
            Platform.runLater(() -> {
                exportBtn.setDisable(false);
                AppIcons.setText(exportBtn, "download", I18N.get("newui.t0050"));
                if (resultPath != null) {
                    statusLabel.setText(I18N.get("newui.p0145", resultPath));
                    LogExporter.openInExplorer(resultPath);
                } else {
                    statusLabel.setText(I18N.get("newui.t0051"));
                }
            });
        }, "crash-log-export").start();
    }

    /** 查找最适合上传的日志文件 */
    private String findBestLogFile() {
        if (crashData.getCrashReportPath() != null) {
            File cr = new File(crashData.getCrashReportPath());
            if (cr.exists()) return cr.getAbsolutePath();
        }
        if (crashData.getLatestLogPath() != null) {
            File ll = new File(crashData.getLatestLogPath());
            if (ll.exists()) return ll.getAbsolutePath();
        }
        Path sl = Paths.get("logs/starlight.log");
        if (Files.exists(sl)) return sl.toAbsolutePath().toString();
        return null;
    }

    /** 读取系统物理内存总量（MB），用于崩溃诊断的「物理内存」字段 */
    private static String readTotalPhysicalMemory() {
        try {
            java.lang.management.OperatingSystemMXBean osBean =
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean) {
                long bytes = sunOsBean.getTotalMemorySize();
                if (bytes > 0) return (bytes / (1024 * 1024)) + " MB";
            }
        } catch (Throwable ignored) {
            // 反射/兼容性失败时退回估算值
        }
        return (Runtime.getRuntime().maxMemory() / (1024 * 1024)) + " MB";
    }

    /**
     * 由启动用的 java.exe 路径反查其版本号（读 Java 扫描缓存，不触发全盘扫描）。
     * 命中不了时（自定义路径 / 缓存缺失）回退到启动器自身 JVM 版本。
     */
    private static String resolveGameJavaVersion(String javaPath) {
        String fallback = System.getProperty("java.version");
        if (javaPath == null || javaPath.isBlank()) return fallback;
        try {
            String target = javaPath.replace('/', '\\').toLowerCase(java.util.Locale.ROOT);
            String targetJavaw = target.replace("javaw.exe", "java.exe");
            for (com.example.starlight.listjava.FindAllJavaWindows.JavaEntry entry
                    : com.example.starlight.listjava.JavaCacheManager.getAvailable()) {
                String home = entry.homePath.replace('/', '\\').toLowerCase(java.util.Locale.ROOT);
                if (target.startsWith(home) || targetJavaw.equals(entry.getJavaExePath()
                        .replace('/', '\\').toLowerCase(java.util.Locale.ROOT))) {
                    return entry.version;
                }
            }
        } catch (Exception ignored) {
            // 缓存不可用时退回启动器自身版本
        }
        return fallback;
    }

    /**
     * 收集崩溃诊断数据（复用 GameCrashWindow.collectCrashData 逻辑，
     * 入参为主 UI 使用的 UIGeneralControlClass.LaunchConfig）
     * @param launchStartTime 本次启动开始时间戳(ms)，latest.log 早于该值视为旧日志，不采用
     */
    public static CrashDiagnosticData collectCrashData(LaunchConfig config, int exitCode, String errorLogPath, long launchStartTime) {
        // 优先取「最近一次启动实例快照」：崩溃面板此前只拿当前选中版本的配置，
        // 从「最近游玩」/插件启动非当前版本时会把当前版本元数据与旧崩溃报告拼到一起（串档）。
        // 快照记录的是真正启动的版本/目录/加载器/Java/隔离，命中则以此为准。
        UIGeneralControlClass.LaunchSnapshot snap = UIGeneralControlClass.getLastLaunchSnapshot();
        String effectiveVersion = snap != null ? snap.version : config.version;
        String effectiveGameDir = snap != null ? snap.gameDir : config.gameDir;
        String effectiveLoader = snap != null ? snap.loader : null;
        boolean effectiveIsolation = snap != null ? snap.versionIsolation : config.versionIsolation;
        // 快照里的 startTime 与本次 launchStartTime 一致时优先用它（进程真正启动前已回填）
        long snapshotStartTime = snap != null ? snap.launchStartTime : 0L;

        // AutoJava 在启动线程里覆盖的 javaPath 不在重新读出的 config 里，
        // 优先取本次启动实际使用的 Java（配置里那条只在自动选择失效时才是真的用过）
        String launchedJava = UIGeneralControlClass.getLastLaunchJavaPath(effectiveVersion);
        String effectiveJava = launchedJava != null ? launchedJava : config.javaPath;

        CrashDiagnosticData data = new CrashDiagnosticData();
        data.setExitCode(exitCode);
        data.setGameVersion(effectiveVersion);
        data.setGameDir(effectiveGameDir);
        data.setJavaPath(effectiveJava);
        data.setMaxMemory(config.maxMemory);
        data.setOsInfo(System.getProperty("os.name"));
        data.setOsArch(System.getProperty("os.arch"));
        // 显示「游戏实际使用的 Java」：按启动用的 java.exe 路径反查扫描缓存。
        // 之前直接用 System.getProperty 会把启动器自身的 JVM 版本当成游戏的显示出去，
        // 1.7.10 用 Java 8 启动却显示 17，看起来像自动选 Java 失效
        data.setJavaVersion(resolveGameJavaVersion(effectiveJava));
        data.setLauncherVersion(AppConfig.APP_VERSION);

        // 检测加载器类型：快照命中直接用（含 AutoJava 等启动期覆盖），否则按版本 JSON 重判
        String loaderType = effectiveLoader;
        if (loaderType == null || loaderType.isBlank()) {
            loaderType = I18N.get("newui.t0052");
            Path verJsonPath = Paths.get(effectiveGameDir, "versions", effectiveVersion, effectiveVersion + ".json");
            if (Files.exists(verJsonPath)) {
                try {
                    String verStr = Files.readString(verJsonPath);
                    JsonObject verJson = new Gson().fromJson(verStr, JsonObject.class);
                    // 统一判定入口（含补判），与启动路径、版本列表保持一致
                    loaderType = VersionIconKit.detectLoader(verJson, effectiveVersion);
                } catch (Exception ignored) {}
            }
        }
        data.setLoaderType(loaderType);

        // 计算总物理内存（用 OS 提供的物理内存总量，而非启动器自身 JVM 堆上限）
        data.setTotalMemory(readTotalPhysicalMemory());

        // 构建启动命令摘要（用真实启动的 Java / 版本 / 隔离目录）
        String effectiveGameDirArg = effectiveIsolation
                ? effectiveGameDir + "/versions/" + effectiveVersion : effectiveGameDir;
        StringBuilder cmdBuilder = new StringBuilder();
        cmdBuilder.append(effectiveJava).append(" ");
        cmdBuilder.append("-Xms").append(config.minMemory).append("M ");
        cmdBuilder.append("-Xmx").append(config.maxMemory).append("M ");
        if (!config.jvmArgs.isEmpty()) cmdBuilder.append(config.jvmArgs).append(" ");
        cmdBuilder.append("net.minecraft.client.main.Main ");
        cmdBuilder.append("--username ").append(config.userName).append(" ");
        cmdBuilder.append("--version ").append(effectiveVersion).append(" ");
        cmdBuilder.append("--gameDir ").append(effectiveGameDirArg).append(" ");
        cmdBuilder.append("--assetsDir ").append(effectiveGameDir).append("/assets ");
        cmdBuilder.append("--uuid ").append(config.uuid).append(" ");
        cmdBuilder.append("--accessToken ").append("{HIDDEN}").append(" ");
        cmdBuilder.append("--userType ").append(config.userType);
        if (!config.gameArgs.isEmpty()) cmdBuilder.append(" ").append(config.gameArgs);
        // 启动命令：优先用本次启动日志里记录的真实命令行。
        // 之前这里按配置重新拼一份「摘要」，审计参数时会被误导：缺少启动器注入的编码 / natives /
        // 调优项，而用户 JvmArgs 反而被排到主类前面，看着像是启动器在传 Forge 参数。
        String realCommand = maskSecrets(findRealLaunchCommand(launchStartTime));
        data.setLaunchCommand(realCommand != null && !realCommand.isBlank()
                ? realCommand
                : cmdBuilder.toString() + I18N.get("gcp.r0005"));

        // 1. 查找 crash-report（版本感知 + 时间过滤，避免串档 / 抓到历史残留）
        String crashReportPath = CrashReportParser.findLatestCrashReport(
                effectiveGameDir, effectiveVersion, snapshotStartTime);
        if (crashReportPath != null) {
            data.setCrashReportPath(crashReportPath);
            CrashReportParser.CrashInfo ci = CrashReportParser.parse(crashReportPath);
            data.setCrashInfo(ci);
            if (ci != null) {
                data.setCrashReportContent(ci.fullContent);
            }
        }

        // 2. 读取 crash-report 或 errorLogPath 中的错误描述
        String errorDesc = null;
        if (errorLogPath != null) {
            try {
                Path path = Paths.get(errorLogPath);
                if (Files.exists(path)) {
                    // JVM stderr 里常混着与崩溃无关的弃用告警（LWJGL 在新 JDK 上调 sun.misc.Unsafe
                    // 触发的那几行最典型）：先剔掉噪音，剩下的才有资格当「错误描述」；
                    // 只剩噪音就当作没读到，交给崩溃报告 / 启动日志兜底，
                    // 而不是把一条 WARNING 当成崩溃原因（报出 E0001「未知错误」）
                    errorDesc = stripBenignJvmWarnings(Files.readString(path));
                    if (errorDesc.isBlank()) {
                        errorDesc = null;
                    } else if (errorDesc.length() > 500) {
                        errorDesc = errorDesc.substring(0, 500) + I18N.get("gcp.r0001");
                    }
                }
            } catch (IOException ignored) {}
        }

        // 如果 errorLogPath 没读到，尝试从 crash-report 获取
        if (errorDesc == null && data.getCrashInfo() != null) {
            CrashReportParser.CrashInfo ci = data.getCrashInfo();
            if (ci.cause != null && !ci.cause.isEmpty()) {
                errorDesc = ci.cause;
            } else if (ci.stackTrace != null && !ci.stackTrace.isEmpty()) {
                errorDesc = ci.stackTrace;
            } else {
                errorDesc = ci.title;
            }
        }

        // 2.5 原生崩溃（EXCEPTION_ACCESS_VIOLATION 之类）既没有 Java 异常、也没有 crash-report，
        // 现场只写在 JVM 的 hs_err_pid*.log 里：主动找出来当错误描述，面板才有可读的原因
        if (errorDesc == null || isUninformativeErrorText(errorDesc)) {
            String hsErr = CrashReportParser.findLatestHsErr(
                    effectiveGameDir, effectiveVersion, snapshotStartTime);
            if (hsErr != null) {
                String summary = CrashReportParser.summarizeHsErr(hsErr);
                if (summary != null && !summary.isBlank()) {
                    if (data.getCrashReportPath() == null) {
                        data.setCrashReportPath(hsErr);
                        data.setCrashReportContent(readHead(hsErr));
                    }
                    errorDesc = summary;
                }
            }
        }

        // stderr 常常只剩一句「Exception in thread "main"」（核心 jar 的 Unable to launch / Caused by
        // 只写在 minecraft_launch_*.log 里），补出真实原因，否则诊断框等于没说；
        // 只有告警、启动日志里也捞不到异常时，落到下面「未捕获到具体错误信息」的兜底文案
        if (isUninformativeErrorText(errorDesc)) {
            String cause = extractCauseFromLaunchLog(launchStartTime);
            if (cause != null && !cause.isBlank()) {
                errorDesc = (errorDesc == null || errorDesc.isBlank())
                        ? cause
                        : errorDesc.trim() + "\n" + cause;
            } else if (isOnlyWarnings(errorDesc)) {
                errorDesc = null;
            }
        }

        if (errorDesc == null) {
            // 翻译退出码：原生崩溃在 Java 层没有任何异常，退出码是唯一线索，
            // 直接展示 -1073741819 这种数字用户看不懂，也匹配不到错误码
            String reason = PlatformUtils.describeExitCode(exitCode);
            errorDesc = I18N.get("gcp.r0002", exitCode,
                    reason == null ? "" : I18N.get("gcp.r0004", reason));
        }
        data.setErrorDescription(errorDesc);

        // 3. 智能匹配错误码
        data.setErrorCode(ErrorCode.detectFromText(errorDesc));

        // 4. 读取 latest.log 末尾关键日志（仅当本次启动后有写入，避免误读上一次启动的旧日志）
        // 版本隔离开启时 latest.log 落在 versions/<版本>/logs，否则在游戏目录 logs
        Path latestLog = Paths.get(effectiveGameDir, "versions", effectiveVersion, "logs", "latest.log");
        if (!Files.exists(latestLog)) {
            latestLog = Paths.get(effectiveGameDir, "logs", "latest.log");
        }
        if (!Files.exists(latestLog)) {
            latestLog = Paths.get("logs", "latest.log");
        }
        if (Files.exists(latestLog) && isLogFresh(latestLog, launchStartTime)) {
            data.setLatestLogPath(latestLog.toAbsolutePath().toString());
            data.setLatestLogTail(readLogTail(latestLog, 150));
        }

        // 6. 环境提示：把这次崩溃最该做的一件事直接写进报告，别让用户自己推断
        data.setRuntimeAdvice(buildRuntimeAdvice(effectiveJava, exitCode, data.getCrashReportPath()));

        return data;
    }

    /**
     * 生成「环境提示与下一步」：从这次崩溃现有的证据里挑出最该做的一步。
     *
     * <ul>
     *   <li><b>GraalVM</b>：JVMCI 与激进 -XX 参数叠加是已知的「无堆栈原生崩溃」诱因，
     *       引导做一次标准 OpenJDK 对照（而不是让用户自己猜该换什么）；</li>
     *   <li><b>负的退出码</b>：原生层被系统终止，Java 层不会有堆栈，引导去事件查看器看故障模块；</li>
     *   <li><b>既无 crash-report 也无 hs_err</b>：连 JVM 的日志系统都没来得及写，把这一事实讲清楚。</li>
     * </ul>
     */
    private static String buildRuntimeAdvice(String javaPath, int exitCode, String crashReportPath) {
        StringBuilder sb = new StringBuilder();
        if (PlatformUtils.isGraalVmRuntime(javaPath)) {
            sb.append(I18N.get("newui.t0119"))
              .append(I18N.get("newui.t0120"))
              .append(I18N.get("newui.t0121"));
        }
        String reason = PlatformUtils.describeExitCode(exitCode);
        if (reason != null) {
            sb.append(I18N.get("newui.t0122")).append(exitCode).append(I18N.get("newui.t0123")).append(reason)
              .append(I18N.get("newui.t0124"))
              .append(I18N.get("newui.t0125"))
              .append(I18N.get("newui.t0126"));
        }
        if (crashReportPath == null) {
            sb.append(I18N.get("newui.t0127"))
              .append(I18N.get("newui.t0128"));
        }
        return sb.toString().trim();
    }

    /**
     * 错误文本是否没有信息量：只有一行 {@code Exception in thread "main"} 之类，
     * 既没有 Caused by 也没有异常类名；或者整段只有告警 / 提示行（一行异常都没有）——
     * 后者看这条等于没说，还容易把一条 JVM WARNING 误当成崩溃原因。
     */
    private static boolean isUninformativeErrorText(String text) {
        if (text == null || text.isBlank()) return true;
        String trimmed = text.trim();
        if (isOnlyWarnings(trimmed)) return true;
        return trimmed.length() < 80 && !trimmed.contains("Caused by:")
                && !trimmed.contains("Error: ") && !trimmed.contains("Exception: ");
    }

    /** 与崩溃无关的 JVM 弃用告警（LWJGL 在新 JDK 上通过 sun.misc.Unsafe 触发的那一组） */
        // i18n-lock: 正则是协议锚点，不是界面文案；static final 还会冻结语言，绝不能走 I18N
private static final Pattern BENIGN_JVM_WARNING = Pattern.compile(
            I18N.get("newui.p0146", "|terminally deprecated method"
                    + "|Please consider reporting this to the maintainers"
                    + "|will be removed in a future release).*$"),
            Pattern.CASE_INSENSITIVE);

    /**
     * 剔除 JVM 弃用告警噪音。典型输出（LWJGL + 新 JDK）：
     * <pre>
     * WARNING: A terminally deprecated method in sun.misc.Unsafe has been called
     * WARNING: sun.misc.Unsafe::objectFieldOffset has been called by org.lwjgl.system...
     * WARNING: Please consider reporting this to the maintainers of class ...
     * WARNING: sun.misc.Unsafe::objectFieldOffset will be removed in a future release
     * </pre>
     * 这些只是「某个类用了将被移除的 API」的提示，游戏照样能跑，不能当成崩溃原因。
     */
    private static String stripBenignJvmWarnings(String text) {
        if (text == null || text.isBlank()) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (String line : text.split("\\R")) {
            if (BENIGN_JVM_WARNING.matcher(line.trim()).matches()) continue;
            sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    /** 文本是否只有告警 / 提示行（一行异常或错误都没有） */
    private static boolean isOnlyWarnings(String text) {
        if (text == null || text.isBlank()) return true;
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            boolean notice = trimmed.startsWith("WARNING") || trimmed.startsWith("警告")
                    || trimmed.startsWith("Note:") || trimmed.startsWith("注意")
                    || trimmed.startsWith("提示");
            if (!notice) return false;
        }
        return true;
    }

    /** 读文件开头若干行（报告里只嵌一段：hs_err 动辄上千行） */
    private static String readHead(String filePath) {
        try {
            List<String> lines = Files.readAllLines(Paths.get(filePath));
            int count = Math.min(lines.size(), 60);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < count; i++) sb.append(lines.get(i)).append('\n');
            if (lines.size() > count) {
                sb.append(I18N.get("newui.t0053")).append(filePath).append(")\n");
            }
            return sb.toString();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 从本次启动的 {@code logs/minecraft_launch_*.log} 里取真实完整启动命令。
     * {@code BaseLauncher} 每次启动都会写一行 {@code 完整启动命令: <整条命令>}（时间戳与级别前缀由
     * {@code writeLog} 加），这里就按这个标记取。
     */
    private static String findRealLaunchCommand(long launchStartTime) {
        Path logDir = Paths.get("logs");
        if (!Files.isDirectory(logDir)) return null;
        try (Stream<Path> stream = Files.list(logDir)) {
            Path newest = stream
                    .filter(p -> p.getFileName().toString().startsWith("minecraft_launch_"))
                    .filter(p -> isLogFresh(p, launchStartTime))
                    .max(Comparator.comparingLong(p -> {
                        try { return Files.getLastModifiedTime(p).toMillis(); }
                        catch (IOException e) { return 0L; }
                    }))
                    .orElse(null);
            if (newest == null) return null;
            String marker = I18N.get("newui.t0054");
            for (String line : Files.readAllLines(newest)) {
                int idx = line.indexOf(marker);
                if (idx >= 0) return line.substring(idx + marker.length()).trim();
            }
        } catch (Exception ignored) {
            // 日志不可读就退回摘要
        }
        return null;
    }

    /** 报告可能被贴到公开场合：访问令牌一律打码 */
    private static String maskSecrets(String command) {
        if (command == null || command.isBlank()) return command;
        return command.replaceAll("(--accessToken\\s+)\\S+", "$1{HIDDEN}");
    }

    /**
     * 从本次启动的 {@code logs/minecraft_launch_*.log} 里摘出真实崩溃原因。
     * <p>核心 jar 的 {@code [LaunchWrapper]: Unable to launch} 与 {@code Caused by:} 只写在这份日志里，
     * stderr 那份往往只剩一句没有信息量的 {@code Exception in thread "main"}。
     */
    private static String extractCauseFromLaunchLog(long launchStartTime) {
        Path logDir = Paths.get("logs");
        if (!Files.isDirectory(logDir)) return null;
        try (Stream<Path> stream = Files.list(logDir)) {
            Path newest = stream
                    .filter(p -> p.getFileName().toString().startsWith("minecraft_launch_"))
                    .filter(p -> isLogFresh(p, launchStartTime))
                    .max(Comparator.comparingLong(p -> {
                        try { return Files.getLastModifiedTime(p).toMillis(); }
                        catch (IOException e) { return 0L; }
                    }))
                    .orElse(null);
            if (newest == null) return null;

            List<String> lines = Files.readAllLines(newest);
            int start = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains("Caused by:")) { start = i; break; }
            }
            if (start < 0) {
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).contains("Unable to launch")) { start = i; break; }
                }
            }
            if (start < 0) return null;

            StringBuilder sb = new StringBuilder();
            for (int i = start; i < lines.size() && i < start + 14; i++) {
                String body = stripLogPrefix(lines.get(i));
                sb.append(body).append('\n');
                // 异常行之后只收堆栈行，遇到别的内容就停
                if (i > start && !body.startsWith("at ") && !body.startsWith("\tat ")) break;
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return null;
        }
    }

    /** 去掉日志行前缀（{@code [时间] [STDOUT] ...}），只留正文 */
    private static String stripLogPrefix(String line) {
        int idx = line.lastIndexOf("] ");
        return idx >= 0 && idx + 2 < line.length() ? line.substring(idx + 2) : line;
    }

    /** 判断日志文件是否在本次启动开始之后被写入（避免误读上一次启动留下的旧日志） */
    private static boolean isLogFresh(Path p, long launchStartTime) {
        try { return Files.getLastModifiedTime(p).toMillis() >= launchStartTime; }
        catch (IOException e) { return false; }
    }

    /** 读取日志文件末尾 N 行 */
    private static List<String> readLogTail(Path filePath, int lineCount) {
        try (Stream<String> lines = Files.lines(filePath)) {
            List<String> allLines = lines.collect(Collectors.toList());
            int size = allLines.size();
            if (size <= lineCount) {
                return allLines;
            }
            return allLines.subList(size - lineCount, size);
        } catch (IOException e) {
            return List.of(I18N.get("newui.p0147", e.getMessage() + ")"));
        }
    }
}
