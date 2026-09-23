package com.example.starlight.pluginapi.provider;

import com.example.starlight.pluginapi.ApiProvider;
import com.example.starlight.pluginapi.ApiRequest;

import java.nio.charset.StandardCharsets;

/**
 * 功能类（示例）：{@code system/ping} —— 最简单的探活 API。
 *
 * <p>固定返回 JSON 数据：{@code {"status":"ok"}}。
 * 它是示例插件演示"握手 → 调用 API → 拿到原始数据"完整链路的锚点 API，
 * 也是验证插件系统是否通畅的探针。
 *
 * <p>注意：本类只返回<strong>原始 JSON 字节</strong>，不包含任何 UI 展示逻辑；
 * 弹窗、按钮等界面表现全部由外部插件负责。
 */
public final class SystemPingApiProvider implements ApiProvider {

    /**
     * 固定响应体（UTF-8 字节）。
     * 探活响应内容恒定不变，直接预编译为字节常量，
     * 避免每次调用都走一遍 JSON 序列化。
     */
    private static final byte[] PAYLOAD =
            "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);

    @Override
    public String apiId() {
        return "system/ping";
    }

    @Override
    public String apiVersion() {
        return "1.0.0";
    }

    @Override
    public byte[] handle(ApiRequest request) {
        // 探活 API 不依赖任何参数，直接返回固定数据字节流。
        return PAYLOAD;
    }
}
