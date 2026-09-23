package com.example.starlight.pluginapi;

import java.util.Collections;
import java.util.Map;

/**
 * 一次 API 调用的请求信息（只读、不可变）。
 *
 * <p>由启动器 HTTP 服务层在收到 {@code /api/v1/data/{apiId}} 请求时构造，
 * 原样传递给中心调度类 {@code API}，再由 {@code API} 转发给对应的 {@link ApiProvider}。
 *
 * <p>设计为不可变对象，避免 Provider 之间、以及多线程并发下共享请求数据被意外修改。
 */
public final class ApiRequest {

    /** 目标 API 的唯一标识，例如 {@code "system/ping"}。 */
    private final String apiId;

    /** URL query 参数（如 {@code ?region=xxx&x=0&z=0}），不可变。 */
    private final Map<String, String> params;

    /** POST 请求体原始字节流（无请求体时为空数组），拷贝后持有，不可变。 */
    private final byte[] body;

    public ApiRequest(String apiId, Map<String, String> params, byte[] body) {
        this.apiId = apiId;
        this.params = params == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(params);
        this.body = body == null ? new byte[0] : body.clone();
    }

    public String apiId() {
        return apiId;
    }

    public Map<String, String> params() {
        return params;
    }

    public byte[] body() {
        return body.clone();
    }

    /** 便捷方法：取单个 query 参数，不存在时返回 {@code null}。 */
    public String param(String name) {
        return params.get(name);
    }
}
