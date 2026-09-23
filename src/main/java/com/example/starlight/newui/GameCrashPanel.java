/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.newui;

import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.ui.VersionIconKit;
import com.example.starlight.crash.CrashDiagnosticData;
import com.example.starlight.crash.CrashReportParser;
import com.example.starlight.gui.UIGeneralControlClass;
import com.example.starlight.gui.UIGeneralControlClass.LaunchConfig;
import com.example.starlight.model.ActionResult;
import com.example.starlight.model.ErrorCode;
import com.example.starlight.service.LogService;
import com.example.starlight.util.LogExporter;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.startgame.launcher.LoaderDetector;
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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
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

    public GameCrashPanel(CrashDiagnosticData crashData, Runnable restartHandler,
                          Consumer<String> toast, Runnable openAIDiagnosis, Runnable closeHandler) {
        super(10);
        this.crashData = crashData;
        this.restartHandler = restartHandler;
        this.toast = toast;
        this.openAIDiagnosis = openAIDiagnosis;
        this.closeHandler = closeHandler;
        setPadding(new Insets(10, 4, 0, 4));

        ErrorCode ec = crashData.getErrorCode();
        if (ec == null) ec = ErrorCode.E0001;

        // 顶部：标题 + 退出码 + 错误码徽标
        HBox titleRow = new HBox(10);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        Label titleLabel = new Label("游戏异常退出");
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: 700; -fx-text-fill: #dc2626;");
        Label exitLabel = new Label("退出码: " + crashData.getExitCode());
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
                envInfo("版本", crashData.getGameVersion()),
                envInfo("加载器", crashData.getLoaderType()),
                envInfo("Java", crashData.getJavaVersion()),
                envInfo("内存", crashData.getMaxMemory() > 0 ? crashData.getMaxMemory() + " MB" : null),
                envInfo("系统", crashData.getOsInfo()));

        // 崩溃原因
        Label descTitle = new Label("崩溃原因");
        descTitle.getStyleClass().add("modal-text-title");
        TextArea descArea = new TextArea(buildDescription());
        descArea.setEditable(false);
        descArea.setWrapText(true);
        descArea.setPrefHeight(80);
        descArea.setStyle("-fx-font-family: 'Consolas', 'Microsoft YaHei', monospace; -fx-font-size: 12px;");

        // 关键日志
        Label logTitle = new Label("关键日志（latest.log 末尾）");
        logTitle.getStyleClass().add("modal-text-title");
        TextArea logArea = new TextArea();
        List<String> logLines = crashData.getLatestLogTail();
        logArea.setText(logLines != null && !logLines.isEmpty()
                ? String.join("\n", logLines) : "（无可用日志）");
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
        Button restartBtn = AppIcons.button("arrow-right", "重启游戏");
        restartBtn.getStyleClass().add("btn-primary");
        restartBtn.setStyle("-fx-padding: 6 16; -fx-font-size: 12px; -fx-cursor: hand;");
        restartBtn.setOnAction(e -> {
            closeHandler.run();
            if (restartHandler != null) restartHandler.run();
        });
        Button copyBtn = AppIcons.button("copy", "复制错误");
        copyBtn.getStyleClass().add("btn-primary");
        copyBtn.setStyle("-fx-padding: 6 16; -fx-font-size: 12px; -fx-cursor: hand;");
        copyBtn.setOnAction(e -> handleCopyError());
        sendBtn = AppIcons.button("upload", "上传日志");
        sendBtn.getStyleClass().add("btn-primary");
        sendBtn.setStyle("-fx-padding: 6 16; -fx-font-size: 12px; -fx-cursor: hand;");
        sendBtn.setOnAction(e -> handleSendLog());
        exportBtn = AppIcons.button("download", "导出日志");
        exportBtn.getStyleClass().add("btn-primary");
        exportBtn.setStyle("-fx-padding: 6 16; -fx-font-size: 12px; -fx-cursor: hand;");
        exportBtn.setOnAction(e -> handleExportLog());
        Button chatBtn = AppIcons.button("sparkles", "AI 诊断");
        chatBtn.getStyleClass().add("btn-primary");
        chatBtn.setStyle("-fx-padding: 6 16; -fx-font-size: 12px; -fx-background-color: #8b5cf6;" +
                " -fx-text-fill: white; -fx-cursor: hand;");
        chatBtn.setOnAction(e -> openAIDiagnosis.run());
        Region btnSpacer = new Region();
        HBox.setHgrow(btnSpacer, Priority.ALWAYS);
        Button closeBtn = AppIcons.button("close", "关闭");
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
                desc = "未知错误，退出码: " + crashData.getExitCode();
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
        toast.accept("错误信息已复制到剪贴板");
    }

    /** 上传日志：优先 crash-report，其次 latest.log，最后 starlight.log */
    private void handleSendLog() {
        final String logFileToUpload = findBestLogFile();
        if (logFileToUpload == null) {
            statusLabel.setText("未找到可上传的日志文件");
            return;
        }
        sendBtn.setDisable(true);
        sendBtn.setText("正在发送...");
        statusLabel.setText("正在上传日志，请稍候...");
        new Thread(() -> {
            try {
                ActionResult result = LogService.uploadLogAndGenerateQR(logFileToUpload, null);
                Platform.runLater(() -> onSendLogResult(result));
            } catch (Exception e) {
                Platform.runLater(() -> {
                    sendBtn.setDisable(false);
                    AppIcons.setText(sendBtn, "upload", "上传日志");
                    statusLabel.setText("上传失败: " + e.getMessage());
                });
            }
        }, "crash-log-upload").start();
    }

    private void onSendLogResult(ActionResult result) {
        sendBtn.setDisable(false);
        AppIcons.setText(sendBtn, "upload", "上传日志");
        if (result.success && result.data instanceof LogService.LogUploadResult) {
            LogService.LogUploadResult ur = (LogService.LogUploadResult) result.data;
            if (ur.qrFilePath != null) {
                statusLabel.setText("日志已上传: " + ur.url + "（二维码已保存至 " + ur.qrFilePath + "）");
                LogExporter.openInExplorer(ur.qrFilePath);
            } else {
                statusLabel.setText("日志已上传: " + ur.url);
            }
        } else {
            statusLabel.setText("上传失败: " + result.message);
        }
    }

    /** 导出日志为 zip 压缩包 */
    private void handleExportLog() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择导出目录");
        chooser.setInitialFileName("crash-report-" + System.currentTimeMillis() + ".zip");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP 压缩包", "*.zip"));
        File selected = chooser.showSaveDialog(getScene().getWindow());
        if (selected == null) return;

        exportBtn.setDisable(true);
        exportBtn.setText("正在导出...");
        statusLabel.setText("正在导出日志...");
        final String exportDir = selected.getParent();
        new Thread(() -> {
            String resultPath = LogExporter.exportCrashLogs(crashData, exportDir);
            Platform.runLater(() -> {
                exportBtn.setDisable(false);
                AppIcons.setText(exportBtn, "download", "导出日志");
                if (resultPath != null) {
                    statusLabel.setText("日志已导出至: " + resultPath);
                    LogExporter.openInExplorer(resultPath);
                } else {
                    statusLabel.setText("导出失败，请重试");
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
        // 入参 config 是重新从配置读出来的，AutoJava 在启动线程里覆盖的 javaPath 不在里面，
        // 所以优先取本次启动实际使用的 Java（配置里那条只在自动选择失效时才是真的用过）
        String launchedJava = UIGeneralControlClass.getLastLaunchJavaPath(config.version);
        String effectiveJava = launchedJava != null ? launchedJava : config.javaPath;

        CrashDiagnosticData data = new CrashDiagnosticData();
        data.setExitCode(exitCode);
        data.setGameVersion(config.version);
        data.setGameDir(config.gameDir);
        data.setJavaPath(effectiveJava);
        data.setMaxMemory(config.maxMemory);
        data.setOsInfo(System.getProperty("os.name"));
        data.setOsArch(System.getProperty("os.arch"));
        // 显示「游戏实际使用的 Java」：按启动用的 java.exe 路径反查扫描缓存。
        // 之前直接用 System.getProperty 会把启动器自身的 JVM 版本当成游戏的显示出去，
        // 1.7.10 用 Java 8 启动却显示 17，看起来像自动选 Java 失效
        data.setJavaVersion(resolveGameJavaVersion(effectiveJava));
        data.setLauncherVersion(AppConfig.APP_VERSION);

        // 检测加载器类型
        String loaderType = "未知";
        Path verJsonPath = Paths.get(config.gameDir, "versions", config.version, config.version + ".json");
        if (Files.exists(verJsonPath)) {
            try {
                String verStr = Files.readString(verJsonPath);
                JsonObject verJson = new Gson().fromJson(verStr, JsonObject.class);
                // 统一判定入口（含补判），与启动路径、版本列表保持一致
                loaderType = VersionIconKit.detectLoader(verJson, config.version);
            } catch (Exception ignored) {}
        }
        data.setLoaderType(loaderType);

        // 计算总物理内存
        long totalMem = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        data.setTotalMemory(totalMem + " MB");

        // 构建启动命令摘要
        StringBuilder cmdBuilder = new StringBuilder();
        cmdBuilder.append(effectiveJava).append(" ");
        cmdBuilder.append("-Xms").append(config.minMemory).append("M ");
        cmdBuilder.append("-Xmx").append(config.maxMemory).append("M ");
        if (!config.jvmArgs.isEmpty()) cmdBuilder.append(config.jvmArgs).append(" ");
        cmdBuilder.append("net.minecraft.client.main.Main ");
        cmdBuilder.append("--username ").append(config.userName).append(" ");
        cmdBuilder.append("--version ").append(config.version).append(" ");
        cmdBuilder.append("--gameDir ").append(config.gameDir).append(" ");
        cmdBuilder.append("--assetsDir ").append(config.gameDir).append("/assets ");
        cmdBuilder.append("--uuid ").append(config.uuid).append(" ");
        cmdBuilder.append("--accessToken ").append("{HIDDEN}").append(" ");
        cmdBuilder.append("--userType ").append(config.userType);
        if (!config.gameArgs.isEmpty()) cmdBuilder.append(" ").append(config.gameArgs);
        data.setLaunchCommand(cmdBuilder.toString());

        // 1. 查找 crash-report
        String crashReportPath = CrashReportParser.findLatestCrashReport(config.gameDir);
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
                    errorDesc = Files.readString(path);
                    if (errorDesc.length() > 500) {
                        errorDesc = errorDesc.substring(0, 500) + "...\n(截断，完整内容见日志)";
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

        if (errorDesc == null) {
            errorDesc = "游戏进程异常退出(退出码: " + exitCode + ")";
        }

        // stderr 常常只剩一句「Exception in thread "main"」（核心 jar 的 Unable to launch / Caused by
        // 只写在 minecraft_launch_*.log 里），补出真实原因，否则诊断框等于没说
        if (isUninformativeErrorText(errorDesc)) {
            String cause = extractCauseFromLaunchLog(launchStartTime);
            if (cause != null && !cause.isBlank()) {
                errorDesc = errorDesc.isBlank() ? cause : errorDesc.trim() + "\n" + cause;
            }
        }
        data.setErrorDescription(errorDesc);

        // 3. 智能匹配错误码
        data.setErrorCode(ErrorCode.detectFromText(errorDesc));

        // 4. 读取 latest.log 末尾关键日志（仅当本次启动后有写入，避免误读上一次启动的旧日志）
        Path latestLog = Paths.get(config.gameDir, "logs", "latest.log");
        if (!Files.exists(latestLog)) {
            latestLog = Paths.get("logs", "latest.log");
        }
        if (Files.exists(latestLog) && isLogFresh(latestLog, launchStartTime)) {
            data.setLatestLogPath(latestLog.toAbsolutePath().toString());
            data.setLatestLogTail(readLogTail(latestLog, 150));
        }

        return data;
    }

    /**
     * 错误文本是否没有信息量：只有一行 {@code Exception in thread "main"} 之类，
     * 既没有 Caused by 也没有异常类名，看这条等于没说。
     */
    private static boolean isUninformativeErrorText(String text) {
        if (text == null || text.isBlank()) return true;
        String trimmed = text.trim();
        return trimmed.length() < 80 && !trimmed.contains("Caused by:")
                && !trimmed.contains("Error: ") && !trimmed.contains("Exception: ");
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
            return List.of("(读取日志文件失败: " + e.getMessage() + ")");
        }
    }
}
