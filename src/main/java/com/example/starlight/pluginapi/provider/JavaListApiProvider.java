package com.example.starlight.pluginapi.provider;

import com.example.starlight.pluginapi.ApiProvider;
import com.example.starlight.pluginapi.ApiRequest;
import com.example.starlight.model.JavaInfo;
import com.example.starlight.service.JavaService;

import java.util.List;

/**
 * 功能类（包装）：{@code java/list} —— 列出本机所有 Java 安装。
 *
 * <p>包装既有 {@link JavaService}，不改动原有逻辑；只向外提供 JSON 数据，
 * UI 展示完全由插件负责。
 *
 * <p>无 Query 参数。
 */
public final class JavaListApiProvider implements ApiProvider {

    @Override
    public String apiId() {
        return "java/list";
    }

    @Override
    public String apiVersion() {
        return "1.0.0";
    }

    @Override
    public byte[] handle(ApiRequest request) throws Exception {
        List<JavaInfo> java = JavaService.findAllJava();
        return ProviderSupport.json(ProviderSupport.map(
                "count", java.size(),
                "java", java));
    }
}
