package com.example.starlight.newui.framegen;

import com.example.starlight.config.AppDirs;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * framegen-host.exe 的进程生命周期与 IPC 管理。
 *
 * <p>职责：
 * <ul>
 *   <li>按需启动 / 停止 host 进程</li>
 *   <li>从 host stdout 第一行 {@code PORT <n>} 解析端口并建立 TCP 连接</li>
 *   <li>解析 host 推送的 JSON Lines 事件，转成 {@link HostStatus}</li>
 *   <li>发送 start / stop / config / ping / quit 命令</li>
 * </ul>
 *
 * <p><b>线程模型</b>：所有回调都在 {@code framegen-io} 单线程池上执行，
 * 调用方若需操作 JavaFX 控件必须自行 {@code Platform.runLater}。
 */
public final class FrameGenHostManager {

    private static final long CONNECT_TIMEOUT_MS = 5000;
    private static final long PING_INTERVAL_MS = 3000;

    /** 一次状态快照 */
    public static final class HostStatus {
        public boolean handshaked;
        public boolean running;
        /** 是否正在呈现（竞技模式下 running=false 但 presenting=true） */
        public boolean presenting;
        public boolean closed;
        public String captureBackend = "";
        public String version = "";
        public int width;
        public int height;
        public long captureFrames;
        public long presentFrames;
        public long dropFrames;
        public double interpMs;
        public int quality = -1;
        /** 显示器实际刷新率（Hz）——用于判断「倍率」是否物理可行 */
        public double displayHz;
        /** host 实际采用的输出节奏（已按刷新率钳制） */
        public double targetHz;
        /** 上一秒真正呈现到屏幕的次数 */
        public long shownFrames;
        /** 纹理/交换链重建次数，异常增长说明尺寸在抖动 */
        public long rebuildCount;
        public String lastError = "";

        @Override
        public String toString() {
            if (!handshaked) {
                return "未连接";
            }
            if (lastError != null && !lastError.isEmpty()) {
                return "错误：" + lastError;
            }
            if (closed) {
                return "已结束";
            }
            if (!presenting && !running) {
                return "未启动";
            }
            if (!running) {
                return "直通中（不插值）";
            }
            return String.format("%dx%d · %s · 插值 %.2f ms",
                    width, height, captureBackend, interpMs);
        }
    }

    private final FrameGenSettings settings;
    private final Consumer<HostStatus> onStatus;
    private final Consumer<String> onError;

    private volatile Process process;
    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile BufferedReader reader;

    private final ScheduledExecutorService io =
            Executors.newSingleThreadScheduledExecutor(daemonFactory("framegen-io"));
    private final ScheduledExecutorService ping =
            Executors.newSingleThreadScheduledExecutor(daemonFactory("framegen-ping"));

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicLong seq = new AtomicLong();
    private volatile HostStatus latest = new HostStatus();

    private static ThreadFactory daemonFactory(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    public FrameGenHostManager(FrameGenSettings settings,
                               Consumer<HostStatus> onStatus,
                               Consumer<String> onError) {
        this.settings = settings;
        this.onStatus = onStatus;
        this.onError = onError;
    }

    // -----------------------------------------------------------------
    // 可执行文件定位
    // -----------------------------------------------------------------

    /** host exe 的预期位置（随启动器数据目录） */
    public static Path hostExePath() {
        return AppDirs.framegenDir().resolve("framegen-host.exe");
    }

    public static boolean isHostAvailable() {
        return Files.isRegularFile(hostExePath());
    }

    public HostStatus status() {
        return latest;
    }

    public boolean isRunning() {
        Process p = process;
        return p != null && p.isAlive();
    }

    // -----------------------------------------------------------------
    // 启动 / 停止
    // -----------------------------------------------------------------

    /**
     * 启动 host 并附着到指定游戏进程。
     *
     * @param gamePid Minecraft 的进程 PID
     * @return 是否成功启动并连接
     */
    public boolean start(int gamePid) {
        if (isRunning()) {
            stop();
        }
        Path exe = hostExePath();
        if (!Files.isRegularFile(exe)) {
            fail("未找到 framegen-host.exe（预期位置：" + exe + "）");
            return false;
        }
        if (gamePid <= 0) {
            fail("无效的游戏进程 ID：" + gamePid);
            return false;
        }

        try {
            Path logFile = AppDirs.framegenDir().resolve("host.log");
            Files.createDirectories(AppDirs.framegenDir());
            ProcessBuilder pb = new ProcessBuilder(
                    exe.toString(),
                    "--pid", String.valueOf(gamePid),
                    "--quality", String.valueOf(settings.getQuality()),
                    "--target", String.valueOf(settings.getTargetFps()),
                    "--log", logFile.toString());
            pb.redirectErrorStream(true);
            process = pb.start();
        } catch (IOException e) {
            fail("启动 host 失败：" + e.getMessage());
            return false;
        }

        int port = readPortLine(process);
        if (port <= 0) {
            fail("host 未报告端口，启动异常");
            destroyProcess();
            return false;
        }

        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", port), (int) CONNECT_TIMEOUT_MS);
            s.setTcpNoDelay(true);
            socket = s;
            out = s.getOutputStream();
            reader = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            fail("连接 host 失败：" + e.getMessage());
            destroyProcess();
            return false;
        }

        connected.set(true);
        latest = new HostStatus();
        io.execute(this::readLoop);
        ping.scheduleAtFixedRate(this::sendPing,
                PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS);

        sendConfig();
        if (settings.isCompetitiveMode()) {
            sendStop();
        } else {
            sendStart();
        }
        return true;
    }

    private int readPortLine(Process p) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            long deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                String line = r.readLine();
                if (line == null) {
                    break;
                }
                if (line.startsWith("PORT ")) {
                    // 之后 stdout 仍需持续消费，否则管道写满会阻塞 host
                    drainAsync(r);
                    return Integer.parseInt(line.substring(5).trim());
                }
            }
        } catch (Exception e) {
            System.err.println("[FrameGen] 解析端口失败: " + e.getMessage());
        }
        return -1;
    }

    /** 持续消费 host stdout，防止管道写满阻塞 */
    private void drainAsync(BufferedReader r) {
        Thread t = new Thread(() -> {
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    System.err.println("[framegen-host] " + line);
                }
            } catch (IOException ignored) {
                // host 已退出
            }
        }, "framegen-host-stdout");
        t.setDaemon(true);
        t.start();
    }

    // -----------------------------------------------------------------
    // 命令发送
    // -----------------------------------------------------------------

    private void sendJson(JsonObject o) {
        OutputStream s = out;
        if (s == null) {
            return;
        }
        try {
            s.write((o.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            s.flush();
        } catch (IOException e) {
            System.err.println("[FrameGen] 发送命令失败: " + e.getMessage());
        }
    }

    public void sendStart() {
        JsonObject o = new JsonObject();
        o.addProperty("cmd", "start");
        sendJson(o);
    }

    public void sendStop() {
        JsonObject o = new JsonObject();
        o.addProperty("cmd", "stop");
        sendJson(o);
    }

    public void sendConfig() {
        JsonObject o = new JsonObject();
        o.addProperty("cmd", "config");
        o.addProperty("quality", settings.getQuality());
        o.addProperty("target", settings.getTargetFps());
        sendJson(o);
    }

    private void sendPing() {
        if (!connected.get()) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("cmd", "ping");
        o.addProperty("seq", seq.incrementAndGet());
        sendJson(o);
    }

    // -----------------------------------------------------------------
    // 接收
    // -----------------------------------------------------------------

    private void readLoop() {
        BufferedReader r = reader;
        try {
            String line;
            while (r != null && (line = r.readLine()) != null) {
                handleEvent(line);
            }
        } catch (IOException e) {
            System.err.println("[FrameGen] 读取事件失败: " + e.getMessage());
        } finally {
            connected.set(false);
            if (latest.handshaked && !latest.closed) {
                HostStatus s = copy(latest);
                s.running = false;
                s.lastError = "连接已断开";
                publish(s);
            }
        }
    }

    private void handleEvent(String line) {
        try {
            JsonObject o = JsonParser.parseString(line).getAsJsonObject();
            String evt = o.has("evt") ? o.get("evt").getAsString() : "";
            HostStatus s = copy(latest);

            switch (evt) {
                case "hello": {
                    s.handshaked = true;
                    s.version = str(o, "version");
                    s.captureBackend = str(o, "capture");
                    s.width = num(o, "width");
                    s.height = num(o, "height");
                    s.running = true;
                    break;
                }
                case "status": {
                    s.handshaked = true;
                    s.running = bool(o, "running", true);
                    s.presenting = bool(o, "presenting", true);
                    s.closed = bool(o, "closed", false);
                    s.captureFrames = num(o, "captureFrames");
                    s.presentFrames = num(o, "presentFrames");
                    s.dropFrames = num(o, "dropFrames");
                    s.interpMs = dbl(o, "interpMs");
                    s.quality = num(o, "quality");
                    s.displayHz = dbl(o, "displayHz");
                    s.targetHz = dbl(o, "targetHz");
                    s.shownFrames = num(o, "shownFrames");
                    s.rebuildCount = num(o, "rebuildCount");
                    break;
                }
                case "error": {
                    String code = str(o, "code");
                    s.lastError = code + ": " + str(o, "message");
                    if (onError != null) {
                        onError.accept(s.lastError);
                    }
                    // 只有「目标已消失」类错误才视为彻底结束；
                    // DEVICE_LOST / CAPTURE_LOST 是可恢复的，交给上层决定是否重启。
                    if ("WINDOW_GONE".equals(code)) {
                        s.closed = true;
                        s.running = false;
                        s.presenting = false;
                    }
                    break;
                }
                case "bye": {
                    s.closed = true;
                    s.running = false;
                    s.presenting = false;
                    break;
                }
                case "pong":
                default:
                    break;
            }
            publish(s);
        } catch (Exception e) {
            System.err.println("[FrameGen] 解析事件失败: " + line + " · " + e.getMessage());
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static int num(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : 0;
    }

    private static double dbl(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : 0.0;
    }

    private static boolean bool(JsonObject o, String k, boolean def) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsBoolean() : def;
    }

    private static HostStatus copy(HostStatus s) {
        HostStatus c = new HostStatus();
        c.handshaked = s.handshaked;
        c.running = s.running;
        c.presenting = s.presenting;
        c.closed = s.closed;
        c.captureBackend = s.captureBackend;
        c.version = s.version;
        c.width = s.width;
        c.height = s.height;
        c.captureFrames = s.captureFrames;
        c.presentFrames = s.presentFrames;
        c.dropFrames = s.dropFrames;
        c.interpMs = s.interpMs;
        c.quality = s.quality;
        c.lastError = s.lastError;
        c.displayHz = s.displayHz;
        c.targetHz = s.targetHz;
        c.shownFrames = s.shownFrames;
        c.rebuildCount = s.rebuildCount;
        return c;
    }

    private void publish(HostStatus s) {
        latest = s;
        if (onStatus == null) {
            return;
        }
        try {
            onStatus.accept(s);
        } catch (Throwable t) {
            System.err.println("[FrameGen] 状态回调异常: " + t);
        }
    }

    // -----------------------------------------------------------------
    // 停止 / 清理
    // -----------------------------------------------------------------

    public void stop() {
        if (process == null && socket == null) {
            return;
        }

        JsonObject q = new JsonObject();
        q.addProperty("cmd", "quit");
        sendJson(q);

        connected.set(false);
        closeQuietly(socket);
        socket = null;
        out = null;
        reader = null;

        destroyProcess();

        HostStatus s = copy(latest);
        s.running = false;
        s.presenting = false;
        publish(s);
    }

    private static void closeQuietly(Socket s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (IOException ignored) {
            // 忽略
        }
    }

    private void destroyProcess() {
        Process p = process;
        if (p == null) {
            return;
        }
        try {
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                p.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            process = null;
        }
    }

    /** 完全释放（启动器退出时调用） */
    public void shutdown() {
        stop();
        ping.shutdownNow();
        io.shutdownNow();
    }

    private void fail(String message) {
        HostStatus s = copy(latest);
        s.handshaked = true;
        s.running = false;
        s.lastError = message;
        publish(s);
        if (onError != null) {
            onError.accept(message);
        }
    }

    /** 供 UI 判断 host 是否已就绪 */
    public boolean isConnected() {
        return connected.get();
    }

    /** 供测试 / 诊断：当前 IPC 端口（未连接时返回 -1） */
    public int port() {
        Socket s = socket;
        return s == null ? -1 : s.getPort();
    }

    /** 启动器退出前的兜底：进程是否已经消失 */
    public String describeProcessState() {
        Process p = process;
        if (p == null) {
            return "no-process";
        }
        return p.isAlive() ? "alive" : "dead";
    }
}
