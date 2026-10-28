package com.example.starlight.download.pcl;

import com.example.starlight.download.DownloadSettings;
import com.example.starlight.newui.AppConfig;
import com.example.starlight.util.DebugLog;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * PCL 引擎的 HTTP 层（2026-10-21）。
 *
 * <h3>为什么不是简单搬一份旧引擎的连接代码</h3>
 * 旧引擎（{@code download.task.GameResourceCompleter} / {@code download.mod.ChunkedDownloader}）
 * 在每个请求的 {@code finally} 里无条件调用 {@code HttpURLConnection.disconnect()}，
 * 而 {@code disconnect()} 会关掉底层 socket、让连接<b>回不到 keep-alive 池</b>。
 * 于是一次补全要处理 3500+ 个小文件，就是 3500+ 次完整的 TCP + TLS 握手；
 * 单次握手成本（实测首包 490~517ms）远大于小文件本身的传输时间（57KB ≈ 60ms），
 * 有效开工率因此掉到约 10%。
 *
 * <p>PCL2 的做法相反：所有下载线程共享一个静态 {@code HttpClient}（{@code ModNet.vb:304}），
 * 每个请求结束只释放 request/response，客户端与连接池一直活着（{@code ModNet.vb:1058}）。
 * 本类在 {@code HttpURLConnection} 上复刻同一语义：
 * <ul>
 *   <li><b>成功路径不断开</b>：读干正文 → 关闭输入流，连接由 JDK 的 KeepAliveCache 回收复用；</li>
 *   <li>启动时把 {@code http.maxConnections} 提到并发上限（JDK 默认只有 5，
 *       不调大就等于大部分连接无法复用，这一点必须显式设置）；</li>
 *   <li>只有失败 / 重定向 / 主动放弃时才 {@code disconnect()}。</li>
 * </ul>
 *
 * <p>之所以仍然用 {@code HttpURLConnection} 而不是 {@code java.net.http.HttpClient}：
 * 后者没有 socket 读超时（项目已实测：跨线程 close 流 / interrupt / sendAsync+cancel
 * / ofFile+timeout 四种方式都无法终结卡死的正文读取），而 PCL 引擎的停滞判定需要
 * 「一次 read 超时」作为外层兜底。{@code setReadTimeout} 在本项目实测有效。
 */
public final class PclHttp {

    /** 手动跟随重定向的最大跳数（与 PCL 的 20 跳相比收紧，避免异常链路长时间占用线程） */
    static final int MAX_REDIRECT_HOPS = 5;

    /** 一次连接是否已初始化系统属性（幂等） */
    private static volatile boolean initialized = false;

    private PclHttp() {
    }

    /**
     * 初始化全局连接复用参数。<b>必须在任何 HttpURLConnection 被创建之前调用</b>：
     * {@code http.maxConnections} 由 JDK 的 KeepAliveCache 在类初始化时读取一次，之后设置无效。
     *
     * <p>启动时由 {@code newui.LauncherView} 主动调用一次，确保早于版本清单等首批请求；
     * 晚调用也不会出错，只是复用上限退回 JDK 默认值（每目标 5 条）。
     */
    public static synchronized void init() {
        if (initialized) return;
        try {
            System.setProperty("http.keepAlive", "true");
            // JDK 默认每目标只有 5 条复用连接；不调大的话 64 并发里绝大多数仍要重新握手
            System.setProperty("http.maxConnections",
                    String.valueOf(Math.max(16, DownloadSettings.getPclThreads())));
        } catch (Throwable ignored) {
            // 属性设置失败不阻塞下载：最坏情况退化成不复用，而不是不可用
        }
        initialized = true;
    }

    /** 并发数变化后同步复用上限（对已初始化的 KeepAliveCache 无效，仅用于下次启动前的最优值） */
    static void refreshConnectionLimit() {
        try {
            System.setProperty("http.maxConnections",
                    String.valueOf(Math.max(16, DownloadSettings.getPclThreads())));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 打开一个 GET 连接（关闭自动重定向，由调用方按 Range 语义手动跟随）。
     *
     * @param urlStr          请求地址
     * @param connectTimeoutMs 建连超时
     * @param readTimeoutMs    单次 read 超时（PCL 引擎把它作为停滞判定的外层兜底）
     * @param rangeHeader      Range 头（如 {@code bytes=100-}）；null 表示整文件
     */
    static HttpURLConnection openGet(String urlStr, int connectTimeoutMs, int readTimeoutMs,
                                     String rangeHeader) throws IOException {
        init();
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setRequestProperty("User-Agent", AppConfig.USER_AGENT);
        if (rangeHeader != null && !rangeHeader.isBlank()) {
            conn.setRequestProperty("Range", rangeHeader);
        }
        DebugLog.http(true, "GET", urlStr, 0, 0L, null, -1L);
        return conn;
    }

    /**
     * 成功收尾：读干剩余正文后关闭输入流，<b>不调用 disconnect()</b>，
     * 让连接回到 keep-alive 池供后续请求复用。
     *
     * @param in 已经读完的输入流（可为 null）
     */
    static void finishKeepAlive(InputStream in) {
        if (in == null) return;
        try {
            in.close();
        } catch (IOException ignored) {
            // 关闭失败无补救手段；即使连接失效，JDK 也会在下次取用时丢弃它
        }
    }

    /**
     * 失败 / 放弃收尾：主动断开。
     *
     * <p>与 {@link #finishKeepAlive(InputStream)} 的区别是语义而非形式：
     * 连接状态已经不可信（超时、半截正文、重定向），必须丢弃而不是归还连接池，
     * 否则下一次取到这条连接会立刻失败。
     */
    static void abort(HttpURLConnection conn) {
        if (conn == null) return;
        try {
            conn.disconnect();
        } catch (Exception ignored) {
            // disconnect 失败无补救手段
        }
    }

    /** 重定向 Location 里的非 ASCII 字符做百分号编码（与旧引擎同一逻辑，避免跨包依赖） */
    static String sanitizeRedirect(String location) {
        if (location == null || location.isBlank()) return location;
        boolean ascii = true;
        for (int i = 0; i < location.length(); i++) {
            if (location.charAt(i) > 127) {
                ascii = false;
                break;
            }
        }
        if (ascii) return location;
        StringBuilder sb = new StringBuilder(location.length() + 16);
        for (byte b : location.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int v = b & 0xFF;
            if (v < 0x80) {
                sb.append((char) v);
            } else {
                sb.append('%')
                  .append(Character.toUpperCase(Character.forDigit((v >> 4) & 0xF, 16)))
                  .append(Character.toUpperCase(Character.forDigit(v & 0xF, 16)));
            }
        }
        return sb.toString();
    }
}