package com.example.starlight.pluginapi;

/**
 * 插件系统 API 调用异常：携带 HTTP 状态码。
 *
 * <p>由中心调度类 {@link API#dispatch} 统一抛出，HTTP 服务层（握手/数据端点）
 * 捕获后据此生成对应的错误 JSON 响应：
 * <ul>
 *   <li>404 —— 未知 API；</li>
 *   <li>400 —— 参数缺失或非法；</li>
 *   <li>500 —— Provider 内部错误。</li>
 * </ul>
 */
public class ApiException extends RuntimeException {

    private final int statusCode;

    public ApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public ApiException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /** @return 建议返回给插件的 HTTP 状态码 */
    public int statusCode() {
        return statusCode;
    }
}
