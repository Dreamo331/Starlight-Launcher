/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.auth.offline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 本地披风 HTTP 服务器 + CapeUrlPatchAgent 注入：让 1.17.1 纯 OptiFine 客户端的
 * 披风下载指向本地服务器。
 *
 * <p>OptiFine 披风通道（{@code CapeUtils.downloadCape} → {@code HttpTexture}，
 * pipeline=true 走 {@code HttpPipeline}）实测不走 {@code -Dhttp.proxyHost} 系统属性；
 * {@code Minecraft.proxy} 来自 Main 的 {@code --proxyHost} 参数（SOCKS 类型），但
 * authlib-injector 的 {@code ProxyParameterWorkaround} 会把该参数从 Main 参数中剥掉。
 * hosts/80 端口劫持又需要管理员权限。因此采用字节码级方案：给游戏 JVM 追加
 * {@code -javaagent:lib/cape-url-patch-agent.jar=<端口>}，agent 在 CapeUtils 类加载时
 * 把常量池里的 {@code http://s.optifine.net} 前缀重写为 {@code http://127.0.0.1:<端口>}，
 * 下载直连本服务器的 {@code /capes/<玩家名>.png}。</p>
 *
 * <p><b>为何不用 JDK HttpServer</b>：com.sun.net.httpserver 的响应与 OptiFine
 * HttpPipelineReceiver 实测不兼容（status 2xx 但 getBody() 返回 null，
 * "Couldn't download http texture: NPE buf is null"）。改用 ServerSocket 手写
 * HTTP/1.1 响应（显式 Content-Length + Connection: close），与 HttpPipeline 的
 * 简单解析器完全兼容。</p>
 */
public final class LocalCapeProxy {

    private static final Logger LOG = LoggerFactory.getLogger(LocalCapeProxy.class);

    /** CapeUrlPatchAgent jar 相对路径（启动器工作目录下 lib/） */
    private static final String AGENT_JAR = "lib/cape-url-patch-agent.jar";

    private final ServerSocket server;
    private final ExecutorService executor;
    private final int capePort;
    private final String username;
    private final byte[] capePng;
    private volatile boolean stopped;

    private LocalCapeProxy(ServerSocket server, ExecutorService executor, String username, byte[] capePng) {
        this.server = server;
        this.executor = executor;
        this.capePort = server.getLocalPort();
        this.username = username;
        this.capePng = capePng;
    }

    /**
     * 启动本地披风 HTTP 服务器。
     *
     * @param capePng  披风 PNG 字节（非空）
     * @param username 玩家名（下载 URL 中的名字）
     * @return 代理实例；启动失败返回 null
     */
    public static LocalCapeProxy start(byte[] capePng, String username) {
        if (capePng == null) return null;
        try {
            ServerSocket ss = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
            ExecutorService ex = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "local-cape-http");
                t.setDaemon(true);
                return t;
            });
            LocalCapeProxy p = new LocalCapeProxy(ss, ex, username, capePng);
            ex.submit(p::acceptLoop);
            current = p; // 状态查询：同一时刻至多一个游戏会话在用，后启动覆盖前者
            LOG.info("本地披风服务器已启动：http://127.0.0.1:{}（拦截 /capes/{}，agent {}）",
                    p.capePort, username, Files.exists(resolveAgentJar()) ? "在位" : "缺失");
            return p;
        } catch (IOException e) {
            LOG.warn("本地披风服务器启动失败：{}", e.getMessage());
            return null;
        }
    }

    /** 披风 HTTP 端口 */
    public int getCapePort() {
        return capePort;
    }

    /** 最近一次启动的实例（开发者选项页「本地服务状态」查询用；未启动过为 null） */
    private static volatile LocalCapeProxy current;

    /** 当前运行的本地披风代理（已停止或未启动返回 null） */
    public static LocalCapeProxy current() {
        LocalCapeProxy p = current;
        return (p == null || p.stopped) ? null : p;
    }

    /**
     * 解析 CapeUrlPatchAgent jar 的绝对路径：优先 user.dir/lib（IDEA/常规运行），
     * 回退到启动器 classes/jar 所在位置向上逐级查找。不存在时返回 user.dir 版本
     * （由调用方判断）。
     */
    private static java.nio.file.Path resolveAgentJar() {
        java.nio.file.Path p = Paths.get(AGENT_JAR).toAbsolutePath().normalize();
        if (Files.exists(p)) return p;
        try {
            java.nio.file.Path cur = java.nio.file.Paths.get(
                    LocalCapeProxy.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            for (int i = 0; i < 8 && cur != null; i++) {
                java.nio.file.Path cand = cur.resolve("lib").resolve("cape-url-patch-agent.jar").normalize();
                if (Files.exists(cand)) return cand;
                java.nio.file.Path cand2 = cur.resolve("cape-url-patch-agent.jar").normalize();
                if (Files.exists(cand2)) return cand2;
                cur = cur.getParent();
            }
        } catch (Exception ignored) {
        }
        return p;
    }

    /**
     * 注入给游戏 JVM 的 CapeUrlPatchAgent 参数（绝对路径，游戏子进程工作目录可能与
     * 启动器不同）；agent jar 缺失时返回 null。
     * 返回示例：{@code -javaagent:C:\path\to\lib\cape-url-patch-agent.jar=9878}
     */
    public String agentJvmArg() {
        java.nio.file.Path agent = resolveAgentJar();
        if (!Files.exists(agent)) {
            LOG.warn("未找到 CapeUrlPatchAgent jar（{}），披风注入跳过", agent);
            return null;
        }
        return "-javaagent:" + agent + "=" + capePort;
    }

    public void stop() {
        stopped = true;
        try {
            server.close();
        } catch (IOException ignored) {
        }
        executor.shutdownNow();
        LOG.info("本地披风服务器已停止：http://127.0.0.1:{}", capePort);
    }

    private void acceptLoop() {
        while (!stopped) {
            try {
                Socket sock = server.accept();
                executor.submit(() -> handleSocket(sock));
            } catch (IOException e) {
                if (!stopped) {
                    LOG.debug("披风服务器 accept 中断：{}", e.getMessage());
                }
                return;
            }
        }
    }

    private void handleSocket(Socket sock) {
        try (Socket s = sock;
             InputStream in = s.getInputStream();
             OutputStream out = s.getOutputStream()) {
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;
            // 请求行：GET /capes/114514.png HTTP/1.1
            String[] parts = requestLine.split(" ");
            String path = parts.length >= 2 ? parts[1] : "/";
            // 读请求头直到空行（HttpPipeline 会带 User-Agent/Host/Accept/Connection）
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
            }
            serve(path, out);
        } catch (IOException e) {
            LOG.debug("披风连接处理异常：{}", e.getMessage());
        }
    }

    private void serve(String path, OutputStream out) throws IOException {
        if (path.startsWith("/capes/")) {
            String name = path.substring("/capes/".length());
            if (name.endsWith(".png")) name = name.substring(0, name.length() - 4);
            if (name.equals(username)) {
                LOG.info("收到 OptiFine 披风下载请求：/capes/{}.png（命中本地玩家 {}，返回 {} 字节）",
                        username, username, capePng.length);
                writeResponse(out, 200, "image/png", capePng);
                return;
            }
            LOG.info("收到披风下载请求 /capes/{}（非本地玩家 {}，404）", name, username);
        } else {
            LOG.debug("披风服务器收到非披风请求：{}（404）", path);
        }
        writeResponse(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
    }

    /** 手写 HTTP/1.1 响应：显式 Content-Length + Connection: close（兼容 OptiFine HttpPipeline 解析） */
    private static void writeResponse(OutputStream out, int status, String contentType, byte[] body)
            throws IOException {
        String reason = status == 200 ? "OK" : "Not Found";
        StringBuilder sb = new StringBuilder(128);
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
        sb.append("Content-Type: ").append(contentType).append("\r\n");
        sb.append("Content-Length: ").append(body.length).append("\r\n");
        sb.append("Connection: close\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    /** 读一行（CRLF 或 LF 结尾），返回去掉结尾的文本；EOF 返回 null */
    private static String readLine(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(64);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b == '\r') continue;
            buf.write(b);
        }
        return buf.size() == 0 ? null : new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
    }
}
