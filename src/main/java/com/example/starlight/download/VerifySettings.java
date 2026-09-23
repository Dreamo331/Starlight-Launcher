package com.example.starlight.download;

import com.example.starlight.config.StarlightConfig;

/**
 * 资源文件校验加速设置（运行时实时读取 starlight.ini，保存后下次校验立即生效）
 * <p>
 * 三个独立开关（设置 → 高级设置）：
 * <ul>
 *   <li>ParallelVerify  并行文件校验：多线程校验已存在的资源文件，加快启动校验速度（默认开启）</li>
 *   <li>StreamingSha1   流式哈希校验：分块读取计算 SHA-1，降低大文件内存占用（默认开启）</li>
 *   <li>FastVerify      快速校验：文件大小一致即视为完整，不计算哈希；下载后的完整性校验不受影响（默认关闭）</li>
 * </ul>
 */
public final class VerifySettings {

    /** 配置键：并行文件校验 */
    public static final String KEY_PARALLEL = "ParallelVerify";
    /** 配置键：流式哈希校验 */
    public static final String KEY_STREAMING = "StreamingSha1";
    /** 配置键：快速校验（跳过哈希） */
    public static final String KEY_FAST = "FastVerify";

    private VerifySettings() {}

    /** 并行文件校验是否开启（默认开启，仅显式配置为 false 时关闭） */
    public static boolean isParallelVerifyEnabled() {
        return !"false".equalsIgnoreCase(StarlightConfig.get(KEY_PARALLEL));
    }

    /** 流式哈希校验是否开启（默认开启，仅显式配置为 false 时关闭） */
    public static boolean isStreamingSha1Enabled() {
        return !"false".equalsIgnoreCase(StarlightConfig.get(KEY_STREAMING));
    }

    /** 快速校验（跳过哈希）是否开启（默认关闭，仅显式配置为 true 时开启） */
    public static boolean isFastVerifyEnabled() {
        return "true".equalsIgnoreCase(StarlightConfig.get(KEY_FAST));
    }
}
