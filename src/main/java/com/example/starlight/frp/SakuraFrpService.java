/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.frp;


import com.example.starlight.util.I18N;
import com.example.starlight.config.AppDirs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * SakuraFrp 隧道引擎：把「访问密钥 → 隧道配置 → frpc 进程」这一段串起来。
 *
 * <p>职责边界：
 * <ul>
 *   <li>{@link SakuraFrpApi} 负责 HTTP 与数据解析；</li>
 *   <li>本类负责下载 / 校验 frpc、写入 {@code frpc.ini}、启动与停止进程、汇总公网地址；</li>
 *   <li>UI 只订阅 {@code log} 与 {@link #setOnExit(Runnable)}，不直接碰 {@link Process}。</li>
 * </ul>
 *
 * <p>同一时刻只运行一条隧道：SakuraFrp 的配置接口可以一次取多条隧道，但联机页以
 * 「一条隧道一个启动按钮」的方式呈现，避免端口与日志互相干扰。
 */
public final class SakuraFrpService {

    private static final SakuraFrpService INSTANCE = new SakuraFrpService();

    /** frpc 启动后等待出现「start proxy success」的最长时间 */
    private static final long START_TIMEOUT_SECONDS = 30;

    private final SakuraFrpApi api = new SakuraFrpApi();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private volatile Process frpcProcess;
    private volatile int runningTunnelId = -1;
    private volatile String runningTunnelName = "";
    private volatile String runningAddress = "";
    private volatile Runnable onExit;
    private volatile boolean starting;
    private volatile boolean startCancelled;
    private final AtomicBoolean downloading = new AtomicBoolean(false);

    private SakuraFrpService() {
        // 启动器退出时不要留下孤立的 frpc 进程
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                Process p = frpcProcess;
                if (p != null) {
                    p.destroy();
                }
            }, "SakuraFrp-Shutdown"));
        } catch (IllegalStateException ignored) {
            // JVM 已在关闭流程中
        }
    }

    public static SakuraFrpService get() {
        return INSTANCE;
    }

    // 路径 //

    public Path frpDir() {
        return AppDirs.frpDir();
    }

    public Path frpcPath() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return frpDir().resolve(windows ? "frpc.exe" : "frpc");
    }

    public Path configPath() {
        return frpDir().resolve("frpc.ini");
    }

    /** 记录本机 frpc 版本的小文件（下载成功时写入） */
    public Path versionPath() {
        return frpDir().resolve("version.txt");
    }

    public boolean isFrpcInstalled() {
        return Files.isRegularFile(frpcPath());
    }

    /** 本机 frpc 版本；未下载或读不到时返回空串 */
    public String installedVersion() {
        try {
            if (!Files.isRegularFile(versionPath())) return "";
            return Files.readString(versionPath(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    // 运行状态 //

    public boolean isRunning() {
        Process p = frpcProcess;
        return p != null && p.isAlive();
    }

    public int runningTunnelId() {
        return isRunning() ? runningTunnelId : -1;
    }

    public String runningTunnelName() {
        return isRunning() ? runningTunnelName : "";
    }

    public String runningAddress() {
        return isRunning() ? runningAddress : "";
    }

    /** 进程退出（主动停止或意外断开）回调；在后台线程触发，UI 需自行切回 JavaFX 线程 */
    public void setOnExit(Runnable listener) {
        this.onExit = listener;
    }

    // 下载 frpc //

    /**
     * 下载官方最新 frpc 到 {@code ~/.starlight/frp/}。
     *
     * @param progress 进度回调（0-100，后台线程触发，可为 null）
     */
    public CompletableFuture<Void> downloadFrpc(Consumer<Integer> progress) {
        return CompletableFuture.runAsync(() -> {
            if (!downloading.compareAndSet(false, true)) {
                throw new RuntimeException(I18N.get("frp.t0015"));
            }
            try {
                if (isRunning() || starting) {
                    throw new IOException(I18N.get("frp.t0016"));
                }
                SakuraFrpApi.ClientFile file = api.latestFrpc();
                downloadToFile(file, progress);
            } catch (IOException e) {
                throw new RuntimeException(e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(I18N.get("frp.t0017"), e);
            } finally {
                downloading.set(false);
            }
        });
    }

    /**
     * 下载并校验 frpc；MD5 不符或大小不符都会失败，避免把损坏文件写进磁盘。
     */
    private void downloadToFile(SakuraFrpApi.ClientFile file, Consumer<Integer> progress) throws IOException,
            InterruptedException {
        Files.createDirectories(frpDir());
        Path target = frpcPath();
        Path part = frpDir().resolve(target.getFileName() + ".part");

        HttpRequest request = HttpRequest.newBuilder(URI.create(file.url()))
                .timeout(Duration.ofSeconds(90))
                .header("User-Agent", "Starlight-Launcher (SakuraFrp)")
                .GET()
                .build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            try (InputStream ignored = response.body()) {
                // 关闭连接即可
            }
            throw new IOException(I18N.get("frp.p0033", response.statusCode()));
        }

        long total = file.size() > 0 ? file.size() : response.headers()
                .firstValueAsLong("Content-Length").orElse(-1L);
        long downloaded = 0;
        MessageDigest md5;
        try {
            md5 = MessageDigest.getInstance("MD5");
        } catch (Exception e) {
            md5 = null;
        }
        try (InputStream in = response.body();
             OutputStream out = Files.newOutputStream(part)) {
            byte[] buffer = new byte[16 * 1024];
            int lastPct = -1;
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                if (md5 != null) md5.update(buffer, 0, n);
                downloaded += n;
                if (progress != null && total > 0) {
                    int pct = (int) Math.min(100, downloaded * 100 / total);
                    if (pct != lastPct) {
                        lastPct = pct;
                        progress.accept(pct);
                    }
                }
            }
        } catch (IOException e) {
            Files.deleteIfExists(part);
            throw e;
        }

        if (total > 0 && downloaded != total) {
            Files.deleteIfExists(part);
            throw new IOException(I18N.get("sfs.r0001", total, downloaded));
        }
        if (md5 != null && file.hash() != null && file.hash().length() == 32) {
            String actual = HexFormat.of().formatHex(md5.digest());
            if (!actual.equalsIgnoreCase(file.hash())) {
                Files.deleteIfExists(part);
                throw new IOException(I18N.get("frp.t0018"));
            }
        }

        try {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(part);
            throw new IOException(I18N.get("frp.p0034", e.getMessage()), e);
        }
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            target.toFile().setExecutable(true);
        }
        Files.writeString(versionPath(), file.version(), StandardCharsets.UTF_8);
        if (progress != null) progress.accept(100);
    }

    // 启动 / 停止 //

    /** 启动结果 */
    public record StartResult(int tunnelId, String tunnelName, String publicAddress, int remotePort) {
    }

    /**
     * 启动一条隧道（异步）。返回的 Future 在 frpc 输出「start proxy success」时完成；
     * 鉴权失败、隧道被封禁、frpc 启动错误或 30 秒超时会以异常完成。
     *
     * @param tunnelId 隧道 ID（来自 /tunnels）
     * @param log      逐行日志回调（后台线程触发，可为 null）
     */
    public CompletableFuture<StartResult> startTunnelAsync(int tunnelId, Consumer<String> log) {
        CompletableFuture<StartResult> future = new CompletableFuture<>();
        if (tunnelId <= 0) {
            future.completeExceptionally(new IOException(I18N.get("frp.t0019")));
            return future;
        }
        synchronized (this) {
            if (starting) {
                future.completeExceptionally(new IOException(I18N.get("frp.t0020")));
                return future;
            }
            starting = true;
            startCancelled = false;
        }
        Thread worker = new Thread(() -> {
            try {
                StartResult result = doStart(tunnelId, log);
                future.complete(result);
            } catch (Throwable t) {
                destroyProcess();
                future.completeExceptionally(t);
            } finally {
                starting = false;
            }
        }, "SakuraFrp-Start");
        worker.setDaemon(true);
        worker.start();
        return future;
    }

    /** 同步启动（CLI 用）；失败时抛 IOException */
    public StartResult startTunnel(int tunnelId, Consumer<String> log) throws IOException {
        try {
            return startTunnelAsync(tunnelId, log).get(START_TIMEOUT_SECONDS + 90, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw cause instanceof IOException io ? io : new IOException(cause.getMessage(), cause);
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** 停止当前隧道（安全空操作） */
    public void stop() {
        String name = runningTunnelName;
        startCancelled = true;
        destroyProcess();
        if (!name.isEmpty()) {
            System.out.println("[SakuraFrp] 隧道已停止: " + name);
        }
    }

    /** 销毁当前 frpc 进程并清空运行状态（不触发 onExit，UI 自行处理主动停止） */
    private void destroyProcess() {
        Process p;
        synchronized (this) {
            p = frpcProcess;
            frpcProcess = null;
            runningTunnelId = -1;
            runningTunnelName = "";
            runningAddress = "";
        }
        if (p == null) return;
        // 不在 UI 线程上等待进程退出：先发终止信号，后台线程兜底强杀
        p.destroy();
        Thread killer = new Thread(() -> {
            try {
                if (!p.waitFor(3, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }, "SakuraFrp-Kill");
        killer.setDaemon(true);
        killer.start();
    }

    /**
     * 准备配置并启动 frpc。成功返回后，读取进程输出的守护线程仍在运行，
     * 直到进程退出才清理运行状态并触发 {@link #setOnExit(Runnable)}。
     */
    private StartResult doStart(int tunnelId, Consumer<String> log) throws IOException, InterruptedException {
        if (isRunning()) {
            throw new IOException(I18N.get("frp.t0021"));
        }
        if (downloading.get()) {
            throw new IOException(I18N.get("frp.t0022"));
        }
        String token = SakuraFrpTokenStore.load();
        if (token.isBlank()) {
            throw new IOException(I18N.get("frp.t0023"));
        }

        emit(log, I18N.get("frp.t0024"));
        Map<Integer, SakuraFrpApi.Node> nodes = api.nodes(token);
        SakuraFrpApi.Tunnel tunnel = api.tunnels(token).stream()
                .filter(t -> t.id() == tunnelId)
                .findFirst()
                .orElseThrow(() -> new IOException(I18N.get("sfs.r0003", tunnelId)));
        if (tunnel.banned()) {
            String reason = tunnel.statusReason() == null || tunnel.statusReason().isBlank()
                    ? I18N.get("frp.t0029") : I18N.get("frp.p0035", tunnel.statusReason());
            throw new IOException(reason);
        }
        SakuraFrpApi.Node node = nodes.get(tunnel.node());
        String nodeHost = node == null ? "" : node.host();

        if (!isFrpcInstalled() || installedVersion().isBlank()) {
            emit(log, I18N.get("frp.t0025"));
            SakuraFrpApi.ClientFile file = api.latestFrpc();
            downloadToFile(file, pct -> {
                if (pct % 10 == 0) emit(log, I18N.get("frp.p0036", pct + "%"));
            });
            emit(log, I18N.get("frp.p0037", installedVersion()));
        }
        if (startCancelled) {
            throw new IOException(I18N.get("frp.t0026"));
        }

        String frpcVersion = installedVersion();
        if (frpcVersion.isBlank()) frpcVersion = SakuraFrpApi.DEFAULT_FRPC_VERSION;
        emit(log, I18N.get("frp.p0038", frpcVersion + "）..."));
        String config = api.tunnelConfig(token, List.of(tunnelId), frpcVersion);
        if (startCancelled) {
            throw new IOException(I18N.get("frp.t0027"));
        }
        Files.createDirectories(frpDir());
        Files.writeString(configPath(), config, StandardCharsets.UTF_8);

        String address = tunnel.publicAddress(nodeHost);
        int remotePort = tunnel.remotePort();
        emit(log, I18N.get("frp.p0039", tunnel.localIp() + ":" + tunnel.localPort()
                + " → " + (address.isEmpty() ? I18N.get("frp.t0030") : address)));

        ProcessBuilder pb = new ProcessBuilder(frpcPath().toString(), "-c", configPath().toString());
        pb.directory(frpDir().toFile());
        pb.redirectErrorStream(true);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IOException(I18N.get("frp.p0040", e.getMessage()), e);
        }
        frpcProcess = process;
        runningTunnelId = tunnel.id();
        runningTunnelName = tunnel.name();
        runningAddress = address;

        CompletableFuture<StartResult> started = new CompletableFuture<>();
        Thread reader = new Thread(
                () -> readProcessOutput(process, tunnel, address, remotePort, started, log),
                "SakuraFrp-frpc");
        reader.setDaemon(true);
        reader.start();

        try {
            return started.get(START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            destroyProcess();
            throw new IOException(I18N.get("sfs.r0002", START_TIMEOUT_SECONDS));
        } catch (java.util.concurrent.ExecutionException e) {
            destroyProcess();
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw cause instanceof IOException io ? io : new IOException(cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroyProcess();
            throw new IOException(I18N.get("frp.t0028"), e);
        }
    }

    /** 读取 frpc 输出：完成启动信号、转发日志，并在进程退出时清理状态 */
    private void readProcessOutput(Process process, SakuraFrpApi.Tunnel tunnel, String address,
                                   int remotePort, CompletableFuture<StartResult> started,
                                   Consumer<String> log) {
        StringBuilder tail = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String text = stripAnsi(line).trim();
                if (text.isEmpty()) continue;
                emit(log, "[frpc] " + text);
                tail.append(text).append('\n');
                if (tail.length() > 4000) tail.delete(0, tail.length() - 4000);

                String lower = text.toLowerCase(Locale.ROOT);
                if (!started.isDone()) {
                    // i18n-lock: 不要翻译此行中文——解析 frpc 进程输出，翻译即功能失效
                    if (lower.contains("start proxy success") || text.contains("启动成功")) {
                        started.complete(new StartResult(tunnel.id(), tunnel.name(), address, remotePort));
                    } else if (lower.contains("start error")
                            || lower.contains("login to server failed")
                            || lower.contains("authentication failed")
                            || lower.contains("invalid token")
                            || lower.contains("token not found")) {
                        started.completeExceptionally(new IOException(I18N.get("frp.p0041", text)));
                    }
                }
            }
        } catch (IOException e) {
            if (!started.isDone()) {
                started.completeExceptionally(new IOException(I18N.get("frp.p0042", e.getMessage()), e));
            }
        } finally {
            int exitCode = -1;
            try {
                exitCode = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            boolean current = frpcProcess == process;
            if (current) {
                clearRunningStateIf(process);
                emit(log, I18N.get("frp.p0043", exitCode + "）"));
                Runnable listener = onExit;
                if (listener != null) {
                    try {
                        listener.run();
                    } catch (Exception ignored) {
                        // UI 回调异常不能反过来影响进程清理
                    }
                }
            }
            if (!started.isDone()) {
                String detail = tail.isEmpty() ? "" : "\n" + tail.toString().trim();
                started.completeExceptionally(new IOException(
                        I18N.get("frp.p0044", exitCode + "）" + detail)));
            }
        }
    }

    private void clearRunningStateIf(Process process) {
        synchronized (this) {
            if (frpcProcess == process) {
                frpcProcess = null;
                runningTunnelId = -1;
                runningTunnelName = "";
                runningAddress = "";
            }
        }
    }

    private static void emit(Consumer<String> log, String message) {
        if (log == null) return;
        try {
            log.accept(message);
        } catch (Exception ignored) {
            // 日志回调不应影响隧道流程
        }
    }

    /** 去掉 frpc 可能输出的 ANSI 颜色控制符 */
    private static String stripAnsi(String text) {
        return text.replaceAll("\u001B\\[[;\\d]*m", "");
    }
}
