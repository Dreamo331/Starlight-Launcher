package com.example.starlight.pluginapi.provider;

import com.example.starlight.pluginapi.ApiRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Provider 公共支撑：统一 JSON 序列化、参数解析与异步回调桥接。
 * 保证各功能类的行为一致（同样的 JSON 输出、同样的参数错误处理 → API.dispatch 统一映射 400/500）。
 */
public final class ProviderSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProviderSupport() {
        // 工具类：禁止实例化
    }

    /** 任意对象 → UTF-8 JSON 字节（模型按 getter/公共字段序列化）。 */
    public static byte[] json(Object value) throws IOException {
        return MAPPER.writeValueAsBytes(value);
    }

    /** 构建有序 JSON Map（键值交替传入）。 */
    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    /** 必填参数；缺失/空白抛 IllegalArgumentException（API.dispatch 映射为 400）。 */
    public static String required(ApiRequest request, String name) {
        String v = request.param(name);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("缺少必要参数: " + name);
        }
        return v.trim();
    }

    /** 整数参数（带默认值）；非法时抛 400。 */
    public static int intParam(ApiRequest request, String name, int def) {
        String v = request.param(name);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参数 " + name + " 非法（需整数）: " + v);
        }
    }

    /** 布尔参数（带默认值）。 */
    public static boolean boolParam(ApiRequest request, String name, boolean def) {
        String v = request.param(name);
        return v == null || v.isBlank() ? def : Boolean.parseBoolean(v);
    }

    /** 游戏目录：game_dir 参数优先，缺省 ".minecraft"（启动器默认 GameDir，相对启动器 CWD）。 */
    public static String gameDir(ApiRequest request) {
        String v = request.param("game_dir");
        return v == null || v.isBlank() ? ".minecraft" : v.trim();
    }

    /** 阻塞等待异步回调结果（超时抛 IOException；回调错误以原因为异常抛出）。 */
    public static <T> T await(CompletableFuture<T> future, int timeoutSeconds) throws Exception {
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IOException("操作超时（" + timeoutSeconds + "s）");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new IOException("操作失败: " + cause);
        }
    }
}
