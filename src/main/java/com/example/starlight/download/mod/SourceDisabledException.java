package com.example.starlight.download.mod;

import java.io.IOException;

/**
 * 异常族之二（对标 PCL NetSource.IsFailed 触发后「其余线程静默退出」的语义）。
 *
 * <p>当某个分块线程按 PCL §2.2 条件判定一条候选 URL 该被禁用时，它负责把该源标记 disabled，
 * 然后抛出本异常结束自己；此时正挂在同一条源上的其它分块线程检测到 disabled 后也应静默退出
 * （不重复计失败、不重复打日志），由 {@link DownloadCoordinator} 的 20ms 播种循环为剩余区间
 * 重新选源起线程。
 *
 * <p>文件生命周期短（一个文件下完即销毁），文件级源不做 half-open 恢复；
 * 与域名级 {@link com.example.starlight.util.MirrorCircuitBreaker} 的 half-open 恢复是两级并存、串联判定。
 */
public class SourceDisabledException extends IOException {

    public SourceDisabledException() {
        super();
    }

    public SourceDisabledException(String message) {
        super(message);
    }
}
