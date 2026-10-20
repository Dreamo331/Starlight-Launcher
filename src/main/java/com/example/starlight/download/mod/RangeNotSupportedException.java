package com.example.starlight.download.mod;

import java.io.IOException;

/**
 * 异常族之一（对标 PCL ModDownloadLib 中「服务器忽略 Range」的判定）。
 *
 * <p>触发条件：分块线程带了 {@code Range: bytes=start-}（start&gt;0）请求，
 * 服务器却返回 200 全量响应（Content-Length 仍等于文件总大小），即它忽略了 Range 头。
 *
 * <p>处理动作：抛出后由 {@link DownloadSourceChain} 把该源加入 SourcesOnce 单线程集合，
 * 该源降级为「单线程源」——一个文件只允许一个线程挂在它上面，不再分块；
 * 若所有多线程源都落到这个结局，整文件 Reset 成单线程源整体重下一轮
 * （由 {@link DownloadFileTask} 的 Retried 标志保证只整体重试一轮）。
 */
public class RangeNotSupportedException extends IOException {

    public RangeNotSupportedException() {
        super();
    }

    public RangeNotSupportedException(String message) {
        super(message);
    }

    public RangeNotSupportedException(String message, Throwable cause) {
        super(message, cause);
    }
}
