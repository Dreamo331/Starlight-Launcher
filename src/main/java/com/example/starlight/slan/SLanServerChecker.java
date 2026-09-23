/**
 * (c) 2026 Starlight Team. All rights reserved.
 * SLAN 中继服务器可用性检测
 *
 * <p>用 JDK 自带 {@link java.net.http.HttpClient} 的 WebSocket 客户端连接中继服务器：
 * <ol>
 *   <li>握手成功 → 说明地址可达且确实是 WebSocket 服务；</li>
 *   <li>再发一个"非法模式"的认证帧（{@code mode=probe}），真正的 SL-MinecraftLAN
 *       中继服务端会用协议帧 0x02 回一个失败响应（"模式必须是 host 或 guest"）——
 *       收到该协议帧即证明<strong>对端就是本联机协议的中继服务器</strong>，
 *       且不会在服务端留下房间/连接计数副作用（不会被当作房主认证成功）。</li>
 * </ol>
 * 检测为异步执行，不阻塞 UI。</p>
 */
package com.example.starlight.slan;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/** 中继服务器可用性检测器（协议级探测，无副作用） */
public final class SLanServerChecker {

    /** 协议消息类型：认证（与服务端 protocol.go / 客户端 ws_client.py 保持一致） */
    private static final int MSG_AUTH = 0x01;
    /** 协议消息类型：认证响应 */
    private static final int MSG_AUTH_RESPONSE = 0x02;

    /** 单次检测总超时 */
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private SLanServerChecker() {
    }

    /**
     * 检测结果
     *
     * @param reachable        WebSocket 是否握手成功（服务器可达）
     * @param protocolMatched  是否收到 SL-MinecraftLAN 中继协议响应（确认是本联机服务器）
     * @param latencyMs        从握手完成到收到协议响应的耗时（毫秒，未匹配时为 -1）
     * @param message          面向用户的中文结论
     */
    public record Result(boolean reachable, boolean protocolMatched, long latencyMs, String message) {
    }

    /**
     * 异步检测指定中继服务器地址。
     *
     * @param wsUrl 服务器地址（可省略 ws:// 前缀，会自动补全）
     * @return 检测结果 Future（异常均已转换为失败结果，不会以异常完成）
     */
    public static CompletableFuture<Result> check(String wsUrl) {
        CompletableFuture<Result> future = new CompletableFuture<>();

        String url = SLanConfig.normalizeRelayServer(wsUrl);
        if (url.isBlank()) {
            future.complete(new Result(false, false, -1, "未填写中继服务器地址"));
            return future;
        }

        URI uri;
        try {
            uri = URI.create(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!scheme.equals("ws") && !scheme.equals("wss")) {
                future.complete(new Result(false, false, -1,
                        "地址协议不支持: " + scheme + "（应为 ws:// 或 wss://）"));
                return future;
            }
            if (uri.getHost() == null || uri.getHost().isEmpty()) {
                future.complete(new Result(false, false, -1, "地址无效，缺少主机名: " + url));
                return future;
            }
        } catch (RuntimeException e) {
            future.complete(new Result(false, false, -1, "地址格式错误: " + url + "（" + e.getMessage() + "）"));
            return future;
        }

        ProbeListener listener = new ProbeListener(future, url);
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(uri, listener)
                    .whenComplete((ws, err) -> {
                        if (err != null) {
                            // 握手阶段失败（拒绝连接 / DNS / 超时 / 非 WebSocket 服务）
                            listener.fail("无法连接服务器: " + describeFailure(err));
                        } else {
                            listener.attach(ws);
                        }
                    });
        } catch (RuntimeException e) {
            listener.fail("检测发起失败: " + describeFailure(e));
        }

        // 总超时保护：超时按失败返回（连接可能仍挂着，尽力关闭）
        return future.orTimeout(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(err -> new Result(false, false, -1,
                        "检测超时（" + TIMEOUT.toSeconds() + " 秒）: " + url))
                .thenApply(r -> {
                    listener.closeQuietly();
                    return r;
                });
    }

    /** WebSocket 监听器：收到协议帧 0x02 即判定为可用中继服务器 */
    private static final class ProbeListener implements WebSocket.Listener {
        private final CompletableFuture<Result> future;
        private final String url;
        private final StringBuilder textBuffer = new StringBuilder();
        private volatile WebSocket webSocket;
        private volatile long handshakeDoneAt = -1;
        private volatile boolean answered;

        ProbeListener(CompletableFuture<Result> future, String url) {
            this.future = future;
            this.url = url;
        }

        void attach(WebSocket ws) {
            this.webSocket = ws;
            if (future.isDone()) {
                closeQuietly();
                return;
            }
            handshakeDoneAt = System.currentTimeMillis();
            // 发送"非法模式"认证帧做协议探测（服务端仅回错误响应，不会创建房间/占房主名额）
            String payload = "{\"mode\":\"probe\",\"password\":\"starlight-probe\"}";
            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            ByteBuffer frame = ByteBuffer.allocate(3 + body.length);
            frame.put((byte) MSG_AUTH);
            frame.putShort((short) body.length);
            frame.put(body);
            frame.flip();
            ws.sendBinary(frame, true).whenComplete((v, err) -> {
                if (err != null) {
                    fail("握手成功但发送探测帧失败: " + rootMessage(err));
                } else {
                    ws.request(1);
                }
            });
        }

        @Override
        public void onOpen(WebSocket ws) {
            attach(ws);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            handleFrame(bytes);
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            // 服务端可能用文本 JSON 回包（兼容旧实现）：记录后按协议帧尝试解析
            textBuffer.append(data);
            if (last) {
                handleFrame(textBuffer.toString().getBytes(StandardCharsets.UTF_8));
                textBuffer.setLength(0);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            if (!answered) {
                fail("服务器关闭了连接（code=" + statusCode
                        + (reason == null || reason.isEmpty() ? "" : ", " + reason) + "）");
            }
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            if (!answered) {
                fail("连接出错: " + rootMessage(error));
            }
        }

        /** 解析协议帧：0x02 认证响应 = 确认是本联机中继服务器 */
        private void handleFrame(byte[] bytes) {
            if (bytes.length < 3 || answered) {
                return;
            }
            int type = bytes[0] & 0xFF;
            int len = ((bytes[1] & 0xFF) << 8) | (bytes[2] & 0xFF);
            if (type != MSG_AUTH_RESPONSE || bytes.length < 3 + len) {
                return; // 心跳等其它帧忽略
            }
            answered = true;
            String json = new String(bytes, 3, len, StandardCharsets.UTF_8);
            long latency = handshakeDoneAt > 0 ? System.currentTimeMillis() - handshakeDoneAt : -1;
            String detail = json.contains("error") ? json : "(响应内容: " + json + ")";
            future.complete(new Result(true, true, latency,
                    "服务器可用（已确认是本联机中继服务器），延迟 " + latency + " ms"));
            closeQuietly();
        }

        void fail(String message) {
            if (answered) {
                return;
            }
            answered = true;
            future.complete(new Result(false, false, -1, "" + message + "（" + url + "）"));
            closeQuietly();
        }

        void closeQuietly() {
            WebSocket ws = webSocket;
            if (ws != null) {
                try {
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "probe done");
                } catch (RuntimeException ignored) {
                    // 忽略关闭异常
                }
            }
        }
    }

    /** 取最内层异常信息，避免 UI 上出现一长串包装异常名 */
    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return (msg == null || msg.isBlank()) ? cur.getClass().getSimpleName() : msg;
    }

    /** 把底层异常翻译成用户能看懂的原因（连接被拒 / 超时 / DNS / TLS / 非中继服务） */
    private static String describeFailure(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String cls = cur.getClass().getSimpleName();
        String msg = cur.getMessage() == null ? "" : cur.getMessage();
        String lower = msg.toLowerCase(java.util.Locale.ROOT);

        if (t instanceof java.net.http.WebSocketHandshakeException || cls.contains("Handshake")) {
            return "WebSocket 握手失败（该地址可能不是中继服务器，或经过代理/需要 wss）";
        }
        if (cur instanceof java.net.UnknownHostException || cls.contains("UnknownHost")
                || cls.contains("UnresolvedAddress") || lower.contains("unknownhost")) {
            return "无法解析主机名（请检查域名是否正确、DNS 是否可用）";
        }
        // 注意：ConnectException 既可能是"拒绝连接"也可能是"连接超时"，需先判断超时关键字
        if (lower.contains("timed out") || lower.contains("timeout") || cls.contains("Timeout")) {
            return "连接超时（服务器不可达，或被防火墙/安全组拦截）";
        }
        if (cur instanceof java.net.ConnectException || cls.contains("ClosedChannel")
                || lower.contains("connection refused") || lower.contains("no further information")) {
            return "连接被拒绝（服务器未启动，或端口未开放/被防火墙拦截）";
        }
        if (cur instanceof javax.net.ssl.SSLException || cls.contains("SSL")) {
            return "TLS 握手失败（wss 地址的证书或端口不匹配）";
        }
        return msg.isBlank() ? cls : cls + ": " + msg;
    }
}
