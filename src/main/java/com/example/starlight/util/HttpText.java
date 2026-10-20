/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * JSON / 文本类网络响应的压缩支持。
 *
 * <p><b>为什么必须有这个类</b>：JDK 的 {@code HttpClient} <b>不会</b>自动发送
 * {@code Accept-Encoding}，因此默认情况下服务端返回的是<b>未压缩</b>正文。
 * 实测 Modrinth 的 {@code /project/{id}/version} 对 JEI 返回 <b>6.9MB</b> 未压缩 JSON，
 * 首页点进详情要等 30 秒；显式请求 gzip 后同一响应降到 1.5MB、约 7.6 秒（4 倍）。
 * 这不是「小优化」——它是详情页/版本列表慢的主因之一。
 *
 * <p>用法：构造请求时调 {@link #acceptGzip(HttpRequest.Builder)}，
 * 接收时用 {@code BodyHandlers.ofByteArray()} 并交给 {@link #decode(HttpResponse)}。
 * <b>务必成对使用</b>：只发 {@code Accept-Encoding} 而用 {@code ofString()} 接收，
 * 会把 gzip 二进制当 UTF-8 解读，得到乱码（比慢更糟）。
 */
public final class HttpText {

    private HttpText() {
    }

    /** 让服务端返回压缩正文（服务端不支持时该头被忽略，无副作用） */
    public static void acceptGzip(HttpRequest.Builder builder) {
        builder.header("Accept-Encoding", "gzip");
    }

    /**
     * 按 {@code Content-Encoding} 解压响应正文并解码为 UTF-8 字符串。
     *
     * <p>兼容三种情况：{@code gzip} / {@code deflate} / 未压缩。
     * 另外对「响应头缺失但正文是 gzip 魔数」的服务器做兜底识别，避免个别镜像漏写头导致乱码。
     */
    public static String decode(HttpResponse<byte[]> response) throws IOException {
        byte[] body = response.body();
        if (body == null || body.length == 0) return "";
        String encoding = response.headers().firstValue("Content-Encoding")
                .orElse("").toLowerCase(Locale.ROOT);
        if (encoding.contains("gzip") || (encoding.isEmpty() && isGzip(body))) {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        if (encoding.contains("deflate")) {
            try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(body))) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    /** 是否为 gzip 魔数（0x1f 0x8b） */
    private static boolean isGzip(byte[] b) {
        return b.length >= 2 && (b[0] & 0xFF) == 0x1F && (b[1] & 0xFF) == 0x8B;
    }
}
