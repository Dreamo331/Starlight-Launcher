package com.example.starlight.pluginapi.provider;

import com.example.starlight.pluginapi.ApiProvider;
import com.example.starlight.pluginapi.ApiRequest;
import com.example.starlight.util.SystemInfoMonitor;

/**
 * 功能类（包装）：{@code system/hardware} —— 查询系统硬件负载（CPU / 内存 / 磁盘读写速率）。
 *
 * <p>包装既有 {@link SystemInfoMonitor}，不改动原有逻辑；先触发一次采样刷新数据，
 * 再读取各指标，只向外提供 JSON 数据，UI 展示完全由插件负责。
 *
 * <p>无 Query 参数。
 */
public final class SystemHardwareApiProvider implements ApiProvider {

    @Override
    public String apiId() {
        return "system/hardware";
    }

    @Override
    public String apiVersion() {
        return "1.0.0";
    }

    @Override
    public byte[] handle(ApiRequest request) throws Exception {
        SystemInfoMonitor.sample(); // 触发采样刷新（磁盘采样在后台异步完成）
        return ProviderSupport.json(ProviderSupport.map(
                "cpu_percent", SystemInfoMonitor.getCpuPercent(),
                "mem_percent", SystemInfoMonitor.getMemPercent(),
                "disk_read_mbps", SystemInfoMonitor.getDiskReadMBps(),
                "disk_write_mbps", SystemInfoMonitor.getDiskWriteMBps()));
    }
}
