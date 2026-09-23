/**
 * (c) 2026 Starlight Team. All rights reserved.
 * SL-MinecraftLAN 联机模块调用封装
 * 路径: *\Starlight-Launcher\slan\1.0\SL-MinecraftLAN.exe
 */
package com.example.starlight.slan;

import com.example.starlight.util.DebugLog;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SL-MinecraftLAN 联机模块管理器
 * 提供同步和异步方式调用联机功能
 */
public class SLanManager {

    // ==================== 常量配置 ====================

    /** 模块基础路径 */
    private static final String DEFAULT_BASE_PATH = "Starlight-Launcher\\slan\\1.0";

    /** 可执行文件名 */
    private static final String EXECUTABLE_NAME = "SL-MinecraftLAN.exe";

    // 注意：模块下载地址、中继服务器等远端服务器配置一律从 Starlight-Launcher/multiplayer.json
    // 读取（见 SLanConfig），不再在此硬编码。

    // ==================== 成员变量 ====================

    /** SL-MinecraftLAN.exe 的完整路径 */
    private final Path executablePath;

    /** 进程输出编码（Windows 默认 GBK，可按需调整） */
    private final String charset;

    /** 是否打印调试日志 */
    private boolean debugMode = false;

    /** 当前运行的联机进程（长驻进程引用，用于断开连接） */
    private volatile Process currentProcess;

    /** 进程退出监听器（进程退出时回调，参数为退出码；在后台线程触发） */
    private volatile Consumer<Integer> processExitListener;

    // ==================== 构造方法 ====================

    /**
     * 使用默认路径创建管理器
     * 默认路径: 当前工作目录\Starlight-Launcher\slan\1.0\SL-MinecraftLAN.exe
     */
    public SLanManager() {
        this(Paths.get(System.getProperty("user.dir"), DEFAULT_BASE_PATH, EXECUTABLE_NAME), null);
    }

    /**
     * 使用完整可执行文件路径创建管理器
     * @param executablePath SL-MinecraftLAN.exe 的完整路径
     */
    public SLanManager(Path executablePath) {
        this(executablePath, null);
    }

    /**
     * 私有主构造方法，所有构造方法最终都调用这里
     * @param executablePath 可执行文件路径
     * @param charset 编码，null 则自动检测
     */
    private SLanManager(Path executablePath, String charset) {
        this.executablePath = executablePath.toAbsolutePath().normalize();
        if (charset != null) {
            this.charset = charset;
        } else {
            this.charset = System.getProperty("os.name").toLowerCase().contains("windows") ? "GBK" : "UTF-8";
        }
        validateExecutable();
    }

    // ==================== 静态工厂方法 ====================

    /**
     * 使用自定义基础目录创建管理器
     * 从基础目录自动推导: {baseDirectory}\slan\1.0\SL-MinecraftLAN.exe
     *
     * @param baseDirectory Starlight-Launcher 所在的基础目录
     * @return SLanManager 实例
     */
    public static SLanManager fromBaseDirectory(Path baseDirectory) {
        Path exePath = baseDirectory.resolve("slan").resolve("1.0").resolve(EXECUTABLE_NAME);
        return new SLanManager(exePath);
    }

    /**
     * 使用自定义基础目录创建管理器（字符串版本）
     * @param baseDirectory 基础目录路径字符串
     * @return SLanManager 实例
     */
    public static SLanManager fromBaseDirectory(String baseDirectory) {
        return fromBaseDirectory(Paths.get(baseDirectory));
    }

    // ==================== 配置方法 ====================

    /**
     * 设置是否启用调试模式（打印详细日志）
     */
    public SLanManager setDebugMode(boolean enabled) {
        this.debugMode = enabled;
        return this;
    }

    /**
     * 设置进程退出监听器。
     * 联机进程退出时（主动断开或意外退出）都会回调，参数为退出码。
     * 注意：回调在后台线程执行，UI 更新需自行切回 UI 线程。
     */
    public SLanManager setOnProcessExit(Consumer<Integer> listener) {
        this.processExitListener = listener;
        return this;
    }

    /**
     * 启动子进程前统一注入环境变量：
     * <ul>
     *   <li>中继服务器地址（读 Starlight-Launcher/multiplayer.json，覆盖模块内置默认）；</li>
     *   <li>{@code PYTHONUNBUFFERED=1}：防止冻结 exe 的 stdout 块缓冲导致
     *       “邀请码等输出要等进程退出才一次性到达”的问题（双保险，主修复在客户端源码）。</li>
     * </ul>
     */
    private static void applyProcessEnvironment(ProcessBuilder pb) {
        SLanConfig.applyEnvironment(pb);
        pb.environment().put("PYTHONUNBUFFERED", "1");
    }

    // ==================== 核心功能：创建房间 ====================

    /**
     * 创建联机房间（同步阻塞）
     *
     * @param password 房间密码（空字符串表示无密码）
     * @return 执行结果，包含邀请码等信息
     * @throws SLanException 执行失败时抛出
     */
    public HostResult hostRoom(String password) throws SLanException {
        return hostRoom(password, null);
    }

    /**
     * 创建联机房间（同步，带输出监听）
     *
     * @param password 房间密码
     * @param outputListener 实时输出监听器（可为 null）
     * @return 执行结果
     * @throws SLanException 执行失败时抛出
     */
    public HostResult hostRoom(String password, Consumer<String> outputListener) throws SLanException {
        List<String> args = buildHostArgs(password);
        ProcessResult result = executeSync(args, outputListener);
        return parseHostResult(result);
    }

    /**
     * 创建联机房间（异步非阻塞）
     *
     * @param password 房间密码
     * @return CompletableFuture，完成后返回 HostResult
     */
    public CompletableFuture<HostResult> hostRoomAsync(String password) {
        return hostRoomAsync(password, null);
    }

    /**
     * 创建联机房间（异步长驻进程模式）
     * 注意：SL-MinecraftLAN.exe 创建房间后不会退出，而是持续运行等待玩家加入。
     * 返回的 Future 在输出中解析到「房间已创建」时立即完成（携带邀请码/端口/房间名等），
     * 进程输出会持续转发给 outputListener，直到调用 disconnect() 断开。
     *
     * @param password 房间密码
     * @param outputListener 实时输出监听器（可为 null，每行进程输出都会回调）
     * @return CompletableFuture，解析到房间创建成功时完成
     */
    public CompletableFuture<HostResult> hostRoomAsync(String password, Consumer<String> outputListener) {
        List<String> args = buildHostArgs(password);
        return startLongRunningProcess(args, outputListener, new HostResultParser());
    }

    // ==================== 核心功能：加入房间 ====================

    /**
     * 加入联机房间（同步阻塞）
     *
     * @param inviteCode 邀请码
     * @param password 房间密码（无密码传空字符串）
     * @return 执行结果
     * @throws SLanException 执行失败时抛出
     */
    public JoinResult joinRoom(String inviteCode, String password) throws SLanException {
        return joinRoom(inviteCode, password, null);
    }

    /**
     * 加入联机房间（同步，带输出监听）
     *
     * @param inviteCode 邀请码
     * @param password 房间密码
     * @param outputListener 实时输出监听器
     * @return 执行结果
     * @throws SLanException 执行失败时抛出
     */
    public JoinResult joinRoom(String inviteCode, String password, Consumer<String> outputListener) throws SLanException {
        List<String> args = buildJoinArgs(inviteCode, password);
        ProcessResult result = executeSync(args, outputListener);
        return parseJoinResult(result);
    }

    /**
     * 加入联机房间（异步非阻塞）
     *
     * @param inviteCode 邀请码
     * @param password 房间密码
     * @return CompletableFuture
     */
    public CompletableFuture<JoinResult> joinRoomAsync(String inviteCode, String password) {
        return joinRoomAsync(inviteCode, password, null);
    }

    /**
     * 加入联机房间（异步长驻进程模式）
     * 注意：SL-MinecraftLAN.exe 加入房间后不会退出，而是持续运行转发数据。
     * 返回的 Future 在输出中解析到「认证成功」时立即完成（携带端口等），
     * 进程输出会持续转发给 outputListener，直到调用 disconnect() 断开。
     *
     * @param inviteCode 邀请码
     * @param password 房间密码
     * @param outputListener 实时输出监听器（可为 null）
     * @return CompletableFuture，解析到加入成功时完成
     */
    public CompletableFuture<JoinResult> joinRoomAsync(String inviteCode, String password, Consumer<String> outputListener) {
        List<String> args = buildJoinArgs(inviteCode, password);
        return startLongRunningProcess(args, outputListener, new JoinResultParser());
    }

    // ==================== 原始命令执行（高级用法） ====================

    /**
     * 执行原始命令（同步）
     * @param command 完整命令参数列表
     * @return 原始输出结果
     */
    public ProcessResult executeRaw(List<String> command) throws SLanException {
        return executeSync(command, null);
    }

    // ==================== 参数构建 ====================

    private List<String> buildHostArgs(String password) {
        List<String> args = new ArrayList<>();
        args.add(executablePath.toString());
        args.add("host");
        if (password != null && !password.isEmpty()) {
            args.add("-p");
            args.add(password);
        }
        return args;
    }

    private List<String> buildJoinArgs(String inviteCode, String password) {
        List<String> args = new ArrayList<>();
        args.add(executablePath.toString());
        args.add("join");
        args.add("-c");
        args.add(inviteCode);
        if (password != null && !password.isEmpty()) {
            args.add("-p");
            args.add(password);
        }
        return args;
    }

    // ==================== 进程执行引擎 ====================

    private ProcessResult executeSync(List<String> command, Consumer<String> outputListener) throws SLanException {
        if (debugMode) {
            System.out.println("[SLanManager] Executing command: " + String.join(" ", command));
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true); // 合并 stderr 到 stdout
        applyProcessEnvironment(pb); // 注入中继服务器地址 + PYTHONUNBUFFERED=1

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new SLanException("Failed to start SL-MinecraftLAN.exe: " + e.getMessage(), e);
        }

        List<String> outputLines = new ArrayList<>();
        StringBuilder fullOutput = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), charset))) {

            String line;
            while ((line = reader.readLine()) != null) {
                outputLines.add(line);
                fullOutput.append(line).append("\n");

                if (outputListener != null) {
                    outputListener.accept(line);
                }

                if (debugMode) {
                    System.out.println("[SLanManager] Output: " + line);
                }
            }

        } catch (IOException e) {
            throw new SLanException("Failed to read process output: " + e.getMessage(), e);
        }

        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SLanException("Interrupted while waiting for process", e);
        }

        ProcessResult result = new ProcessResult(exitCode, outputLines, fullOutput.toString().trim());

        if (exitCode != 0) {
            throw new SLanException("SL-MinecraftLAN.exe returned non-zero exit code: " + exitCode
                    + "\nOutput: " + result.getOutput());
        }

        return result;
    }

    private CompletableFuture<ProcessResult> executeAsync(List<String> command, Consumer<String> outputListener) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeSync(command, outputListener);
            } catch (SLanException e) {
                throw new RuntimeException(e);
            }
        });
    }

    // ==================== 长驻进程执行引擎 ====================

    /**
     * 启动长驻进程并逐行解析输出。
     * 当解析器判定完成（如房间已创建/认证成功）时立即完成返回的 Future，
     * 进程本身保持运行，输出继续转发给 outputListener；
     * 进程意外退出且 Future 未完成时，以异常完成。
     */
    private <T> CompletableFuture<T> startLongRunningProcess(
            List<String> command, Consumer<String> outputListener, LineParser<T> parser) {

        CompletableFuture<T> future = new CompletableFuture<>();

        Thread worker = new Thread(() -> {
            Process process = null;
            StringBuilder fullOutput = new StringBuilder();
            try {
                notify(outputListener, "[System] Starting multiplayer process...");
                if (debugMode) {
                    System.out.println("[SLanManager] Executing command: " + String.join(" ", command));
                }

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true); // 合并 stderr 到 stdout
                applyProcessEnvironment(pb); // 注入中继服务器地址 + PYTHONUNBUFFERED=1
                process = pb.start();
                currentProcess = process;
                notify(outputListener, "[System] Process started (PID: " + process.pid() + ")");

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), charset))) {

                    String line;
                    while ((line = reader.readLine()) != null) {
                        fullOutput.append(line).append("\n");

                        if (outputListener != null) {
                            outputListener.accept(line);
                        }
                        if (debugMode) {
                            System.out.println("[SLanManager] Output: " + line);
                        }

                        parser.feed(line);
                        // 解析到成功标志且 Future 尚未完成 → 立即完成（进程继续运行）
                        if (parser.isDone() && !future.isDone()) {
                            notify(outputListener, "[System] Success flag detected, room info ready");
                            future.complete(parser.buildResult(fullOutput.toString()));
                        }
                    }
                }

                // 读取循环结束 = 进程已退出
                int exitCode = process.waitFor();
                if (!future.isDone()) {
                    String tail = lastLines(fullOutput.toString(), 12);
                    if (exitCode != 0) {
                        future.completeExceptionally(new SLanException(
                                "Multiplayer process exited abnormally (exit code: " + exitCode + ")\n" + tail));
                    } else {
                        future.completeExceptionally(new SLanException(
                                "Multiplayer process exited without success flag\n" + tail));
                    }
                } else {
                    notify(outputListener, "[System] Multiplayer process exited (exit code: " + exitCode + ")");
                }

                // 通知退出监听器（无论主动断开还是意外退出）
                Consumer<Integer> exitCb = processExitListener;
                if (exitCb != null) {
                    try {
                        exitCb.accept(exitCode);
                    } catch (Exception ignored) {}
                }

            } catch (IOException e) {
                future.completeExceptionally(new SLanException("Failed to start SL-MinecraftLAN.exe: " + e.getMessage(), e));
            } catch (Exception e) {
                future.completeExceptionally(new SLanException("Multiplayer process error: " + e.getMessage(), e));
            } finally {
                currentProcess = null;
            }
        }, "SLAN-Process");
        worker.setDaemon(true);
        worker.start();
        return future;
    }

    /**
     * 断开当前联机进程（关闭房间 / 退出房间）
     */
    public void disconnect() {
        Process p = currentProcess;
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                if (!p.waitFor(3, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        currentProcess = null;
    }

    /**
     * 当前是否有联机进程在运行
     */
    public boolean isRunning() {
        Process p = currentProcess;
        return p != null && p.isAlive();
    }

    /** 向监听器转发 SLanManager 内部通知（同时输出调试日志） */
    private void notify(Consumer<String> outputListener, String msg) {
        if (debugMode) {
            System.out.println("[SLanManager] " + msg);
        }
        if (outputListener != null) {
            outputListener.accept(msg);
        }
    }

    /** 取输出末尾 N 行用于错误提示 */
    private String lastLines(String output, int maxLines) {
        if (output == null || output.isEmpty()) {
            return "(no output)";
        }
        String[] lines = output.split("\n");
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, lines.length - maxLines);
        for (int i = start; i < lines.length; i++) {
            sb.append(lines[i]);
            if (i < lines.length - 1) sb.append("\n");
        }
        return sb.toString().trim();
    }

    // ==================== 输出行解析器 ====================

    /**
     * 输出行解析器接口：逐行喂入进程输出，满足条件后完成
     */
    private interface LineParser<T> {
        /** 喂入一行输出 */
        void feed(String line);

        /** 是否已满足完成条件（Future 应立即完成） */
        boolean isDone();

        /** 构建最终结果 */
        T buildResult(String fullOutput);
    }

    /** 房主模式解析器：解析房间名称/端口/邀请码/密码，检测「房间已创建」 */
    private class HostResultParser implements LineParser<HostResult> {
        private final HostResult result = new HostResult();
        private boolean done = false;

        @Override
        public void feed(String line) {
            result.setSuccess(true);

            // Minecraft 房间名称（来自 UDP 广播 MOTD）
            if (result.getRoomName() == null && (line.contains("房间名称") || line.contains("房间名"))) {
                result.setRoomName(extractAfter(line, "房间名称"));
                if (result.getRoomName() == null) result.setRoomName(extractAfter(line, "房间名"));
            }
            // Minecraft 端口
            if (result.getPort() == null && line.contains("端口")) {
                result.setPort(extractAfter(line, "端口"));
            }
            // 邀请码
            if (result.getInviteCode() == null && (line.contains("邀请码") || line.contains("Invite"))) {
                result.setInviteCode(extractAfter(line, "邀请码"));
            }
            // 连接密码
            if (result.getPassword() == null && line.contains("连接密码")) {
                result.setPassword(extractAfter(line, "连接密码"));
            }
            // 房间创建完成标志
            if (line.contains("房间已创建")) {
                done = true;
            }
        }

        @Override
        public boolean isDone() {
            return done;
        }

        @Override
        public HostResult buildResult(String fullOutput) {
            result.setRawOutput(fullOutput.trim());
            return result;
        }
    }

    /** 加入者模式解析器：解析虚拟端口/房间ID，检测「认证成功」 */
    private class JoinResultParser implements LineParser<JoinResult> {
        private final JoinResult result = new JoinResult();
        private boolean done = false;

        @Override
        public void feed(String line) {
            result.setSuccess(true);

            // 认证成功 = 已加入房间
            if (line.contains("认证成功")) {
                result.setConnected(true);
                done = true;
            }
            // 认证失败原因
            if (line.contains("认证失败")) {
                result.setConnected(false);
                result.setError(extractAfter(line, "认证失败"));
            }
            // 虚拟服务端口（本机 MC 客户端连接端口）
            if (result.getPort() == null && line.contains("虚拟服务端已启动")) {
                Matcher m = Pattern.compile(":(\\d{2,5})").matcher(line);
                if (m.find()) {
                    result.setPort(m.group(1));
                }
            }
            // 兜底：新版客户端输出「Minecraft 端口: xxx」
            if (result.getPort() == null && line.contains("Minecraft 端口")) {
                result.setPort(extractAfter(line, "Minecraft 端口"));
            }
            // 房间ID
            if (result.getHostAddress() == null && line.contains("房间ID")) {
                result.setHostAddress(extractAfter(line, "房间ID"));
            }
        }

        @Override
        public boolean isDone() {
            return done;
        }

        @Override
        public JoinResult buildResult(String fullOutput) {
            result.setRawOutput(fullOutput.trim());
            return result;
        }
    }

    // ==================== 结果解析 ====================

    private HostResult parseHostResult(ProcessResult result) {
        HostResult hostResult = new HostResult();
        hostResult.setRawOutput(result.getOutput());
        hostResult.setSuccess(result.getExitCode() == 0);

        // 从输出中解析房间信息（兼容多种输出格式）
        for (String line : result.getOutputLines()) {
            // 房间名称
            if (hostResult.getRoomName() == null && (line.contains("房间名称") || line.contains("房间名"))) {
                hostResult.setRoomName(extractAfter(line, "房间名称"));
                if (hostResult.getRoomName() == null) hostResult.setRoomName(extractAfter(line, "房间名"));
            }
            // 邀请码
            if (hostResult.getInviteCode() == null
                    && (line.contains("邀请码") || line.contains("Invite") || line.contains("Code"))) {
                hostResult.setInviteCode(extractAfter(line, "邀请码"));
                if (hostResult.getInviteCode() == null) hostResult.setInviteCode(extractValue(line));
            }
            // 端口
            if (hostResult.getPort() == null && (line.contains("端口") || line.contains("Port"))) {
                hostResult.setPort(extractAfter(line, "端口"));
                if (hostResult.getPort() == null) hostResult.setPort(extractValue(line));
            }
            // 连接密码
            if (hostResult.getPassword() == null && line.contains("连接密码")) {
                hostResult.setPassword(extractAfter(line, "连接密码"));
            }
        }

        return hostResult;
    }

    private JoinResult parseJoinResult(ProcessResult result) {
        JoinResult joinResult = new JoinResult();
        joinResult.setRawOutput(result.getOutput());
        joinResult.setSuccess(result.getExitCode() == 0);

        // 解析连接信息
        for (String line : result.getOutputLines()) {
            if (line.contains("成功") || line.contains("Success") || line.contains("Connected")) {
                joinResult.setConnected(true);
            }
            if (line.contains("IP") || line.contains("地址")) {
                joinResult.setHostAddress(extractValue(line));
            }
            if (line.contains("端口") || line.contains("Port")) {
                joinResult.setPort(extractAfter(line, "端口"));
                if (joinResult.getPort() == null) joinResult.setPort(extractValue(line));
            }
            if (line.contains("认证失败") || line.contains("邀请码无效")) {
                joinResult.setConnected(false);
                joinResult.setError(extractAfter(line, "认证失败"));
                if (joinResult.getError() == null) joinResult.setError(line.trim());
            }
        }

        return joinResult;
    }

    /**
     * 从行内指定关键字后提取值。
     * 支持「关键字: 值」「关键字：值」「关键字=值」格式，自动截断后置逗号分隔的内容。
     * 用于一行包含多个字段的输出（如「房间名称: xxx，端口: 12345」）。
     */
    private String extractAfter(String line, String keyword) {
        if (line == null || keyword == null) return null;
        int idx = line.indexOf(keyword);
        if (idx < 0) return null;
        String rest = line.substring(idx + keyword.length());
        // 跳过冒号/等号
        for (char c : rest.toCharArray()) {
            if (c == ':' || c == '=') continue;
            rest = rest.substring(rest.indexOf(c));
            break;
        }
        rest = rest.trim();
        // 截断逗号分隔的后续字段（支持中英文逗号）
        int comma = rest.indexOf('，');
        if (comma < 0) comma = rest.indexOf(',');
        if (comma > 0) rest = rest.substring(0, comma).trim();
        return rest.isEmpty() ? null : rest;
    }

    private String extractValue(String line) {
        // 尝试从 "Key: Value" 或 "Key=Value" 格式提取值
        String[] patterns = {":", "=", " "};
        for (String sep : patterns) {
            int idx = line.lastIndexOf(sep);
            if (idx > 0) {
                String value = line.substring(idx + 1).trim();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return line.trim();
    }

    // ==================== 验证与工具 ====================

    private void validateExecutable() {
        File exe = executablePath.toFile();
        if (!exe.exists()) {
            System.err.println("[SLanManager] WARN: executable not found: " + executablePath);
        } else if (!exe.canExecute()) {
            System.err.println("[SLanManager] WARN: executable is not executable: " + executablePath);
        }
    }

    /**
     * 检查模块是否可用
     */
    public boolean isAvailable() {
        File exe = executablePath.toFile();
        return exe.exists() && exe.canExecute();
    }

    /**
     * 下载联机模块到目标路径（异步），下载地址取配置
     * {@code Starlight-Launcher/multiplayer.json} 的 moduleDownloadUrl（见 {@link SLanConfig}）。
     * 下载期间先写入临时文件（.part），完成后校验大小并替换为目标文件；
     * 下载地址为 Gitee Release 时会 302 跳转到 CDN，自动跟随重定向。
     *
     * @param progress 进度回调（后台线程触发，0-100 整数百分比，可传 null）
     * @return 下载成功时正常完成；失败以 {@link SLanException} 异常完成
     */
    public CompletableFuture<Void> downloadModule(Consumer<Integer> progress) {
        return downloadModule(SLanConfig.moduleDownloadUrl(), progress);
    }

    /**
     * 下载联机模块到目标路径（异步，指定下载地址）。
     * 下载期间先写入临时文件（.part），完成后校验大小并替换为目标文件；
     * 下载地址为 Gitee Release 时会 302 跳转到 CDN，自动跟随重定向。
     *
     * @param downloadUrl 下载地址（不传时用 {@link #downloadModule(Consumer)}，从配置文件读取）
     * @param progress    进度回调（后台线程触发，0-100 整数百分比，可传 null）
     * @return 下载成功时正常完成；失败以 {@link SLanException} 异常完成
     */
    public CompletableFuture<Void> downloadModule(String downloadUrl, Consumer<Integer> progress) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Thread worker = new Thread(() -> {
            HttpURLConnection conn = null;
            File tmpFile = null;
            try {
                File dir = executablePath.getParent().toFile();
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new SLanException("无法创建模块目录: " + dir);
                }

                tmpFile = new File(dir, EXECUTABLE_NAME + ".part");
                URL url = new URL(downloadUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", "Starlight-Launcher");
                conn.setInstanceFollowRedirects(true);

                int code = conn.getResponseCode();
                DebugLog.http(true, "GET", downloadUrl, 0, 0, null, -1);
                if (code != HttpURLConnection.HTTP_OK) {
                    DebugLog.http(false, null, downloadUrl, code, 0, null, -1);
                    throw new SLanException("下载失败: HTTP " + code);
                }

                long total = conn.getContentLengthLong();
                try (InputStream in = conn.getInputStream();
                     OutputStream out = new FileOutputStream(tmpFile)) {
                    byte[] buffer = new byte[8192];
                    long downloaded = 0;
                    int lastPct = -1;
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        out.write(buffer, 0, n);
                        downloaded += n;
                        if (total > 0 && progress != null) {
                            int pct = (int) (downloaded * 100 / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                progress.accept(pct);
                            }
                        }
                    }
                    out.flush();
                    DebugLog.http(false, null, downloadUrl, code, downloaded, "[二进制文件]", -1);
                }

                if (total > 0 && tmpFile.length() != total) {
                    throw new SLanException("下载不完整: 期望 " + total + " 字节，实际 " + tmpFile.length() + " 字节");
                }

                // 替换目标文件（Windows 下先删除旧文件再重命名）
                File target = executablePath.toFile();
                if (target.exists() && !target.delete()) {
                    throw new SLanException("无法替换旧模块文件: " + target);
                }
                if (!tmpFile.renameTo(target)) {
                    throw new SLanException("模块文件保存失败: " + target);
                }
                tmpFile = null;

                if (progress != null) progress.accept(100);
                future.complete(null);
            } catch (SLanException e) {
                future.completeExceptionally(e);
            } catch (Exception e) {
                future.completeExceptionally(new SLanException("下载失败: " + e.getMessage(), e));
            } finally {
                if (conn != null) conn.disconnect();
                if (tmpFile != null && tmpFile.exists()) {
                    tmpFile.delete(); // 失败时清理残留的临时文件
                }
            }
        }, "SLAN-Download");
        worker.setDaemon(true);
        worker.start();
        return future;
    }

    /**
     * 获取当前配置的可执行文件路径
     */
    public Path getExecutablePath() {
        return executablePath;
    }

    // ==================== 结果数据类 ====================

    /**
     * 进程执行原始结果
     */
    public static class ProcessResult {
        private final int exitCode;
        private final List<String> outputLines;
        private final String output;

        public ProcessResult(int exitCode, List<String> outputLines, String output) {
            this.exitCode = exitCode;
            this.outputLines = outputLines;
            this.output = output;
        }

        public int getExitCode() { return exitCode; }
        public List<String> getOutputLines() { return outputLines; }
        public String getOutput() { return output; }
    }

    /**
     * 建房结果
     */
    public static class HostResult {
        private boolean success;
        private String inviteCode;
        private String port;
        private String roomName;   // Minecraft 房间名称（来自局域网广播 MOTD）
        private String password;   // 房间连接密码
        private String rawOutput;

        // Getters and Setters
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }

        public String getInviteCode() { return inviteCode; }
        public void setInviteCode(String inviteCode) { this.inviteCode = inviteCode; }

        public String getPort() { return port; }
        public void setPort(String port) { this.port = port; }

        public String getRoomName() { return roomName; }
        public void setRoomName(String roomName) { this.roomName = roomName; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }

        public String getRawOutput() { return rawOutput; }
        public void setRawOutput(String rawOutput) { this.rawOutput = rawOutput; }

        @Override
        public String toString() {
            return "HostResult{success=" + success + ", roomName='" + roomName
                    + "', inviteCode='" + inviteCode + "', port='" + port + "'}";
        }
    }

    /**
     * 加房结果
     */
    public static class JoinResult {
        private boolean success;
        private boolean connected;
        private String hostAddress;
        private String port;
        private String error;      // 失败原因
        private String rawOutput;

        // Getters and Setters
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }

        public boolean isConnected() { return connected; }
        public void setConnected(boolean connected) { this.connected = connected; }

        public String getHostAddress() { return hostAddress; }
        public void setHostAddress(String hostAddress) { this.hostAddress = hostAddress; }

        public String getPort() { return port; }
        public void setPort(String port) { this.port = port; }

        public String getError() { return error; }
        public void setError(String error) { this.error = error; }

        public String getRawOutput() { return rawOutput; }
        public void setRawOutput(String rawOutput) { this.rawOutput = rawOutput; }

        @Override
        public String toString() {
            return "JoinResult{success=" + success + ", connected=" + connected
                    + ", host='" + hostAddress + "', port='" + port + "'}";
        }
    }

    // ==================== 异常类 ====================

    /**
     * SLan 模块专用异常
     */
    public static class SLanException extends Exception {
        public SLanException(String message) {
            super(message);
        }

        public SLanException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
