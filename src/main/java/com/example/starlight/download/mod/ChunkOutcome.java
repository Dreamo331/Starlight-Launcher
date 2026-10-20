package com.example.starlight.download.mod;

/**
 * 分块并发下载结果（2026-09-30 A/B 双池方案，B 池大文件专用）。
 *
 * <ul>
 *   <li>{@link #OK}：分片全部下载并合并完成（SHA-1 由调用方校验）；</li>
 *   <li>{@link #RETRY}：某分片停滞 / 超时 / 长度不符 / 连接失败，可重试（换源优先）；</li>
 *   <li>{@link #FAIL}：404/410 等确定不存在；</li>
 *   <li>{@link #CANCELLED}：用户取消；</li>
 *   <li>{@link #NO_RANGE}：源不支持 Range（对 Range 请求回 200 全量），回退单连接下载。</li>
 * </ul>
 */
public enum ChunkOutcome {
    OK,
    RETRY,
    FAIL,
    CANCELLED,
    NO_RANGE
}
