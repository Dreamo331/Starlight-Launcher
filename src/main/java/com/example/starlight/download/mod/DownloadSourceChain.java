package com.example.starlight.download.mod;

import com.example.starlight.download.source.DownloadProvider;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 选源 / 首源轮转 / 禁用状态机（对标 PCL NetSource 链组织）。
 *
 * <ul>
 *   <li><b>选源规则</b>：从指定下标开始环形扫描，跳过已 disabled 的源，取第一个可用源；</li>
 *   <li><b>首源轮转（FirstThreadSource）</b>：每个文件的第一个线程不固定从源 0 开始，
 *       按 {@link #firstThreadCursor} 递增轮转——多个文件不会同时都打第一个源，分散压力；</li>
 *   <li><b>单线程源集合（SourcesOnce）</b>：不支持 Range 的源进入此集合，
 *       强制单线程（一个文件只允许一个线程挂在它上面，不分块、不做 Range）；</li>
 *   <li><b>整文件 Reset</b>：所有多线程源禁用时，把所有源标记为单线程源、清空已下进度，
 *       单线程整体重下一轮（由 DownloadFileTask.Retried 保证只整体重试一轮）。</li>
 * </ul>
 */
public final class DownloadSourceChain {

    /** 有序候选源（由 DownloadProvider 注入候选 URL 后构建） */
    private final List<DownloadSource> sources;

    /** 首源轮转游标：每次起首线程时 +1 取模，分散多文件压力 */
    private int firstThreadCursor = 0;

    /** SourcesOnce 单线程源集合（强制单线程不分块） */
    private final Set<DownloadSource> sourcesOnce = Collections.synchronizedSet(new LinkedHashSet<>());

    public DownloadSourceChain(List<String> candidateUrls) {
        List<DownloadSource> list = new ArrayList<>();
        if (candidateUrls != null) {
            for (String u : candidateUrls) {
                if (u != null && !u.isBlank()) {
                    list.add(new DownloadSource(u));
                }
            }
        }
        this.sources = Collections.unmodifiableList(list);
    }

    /**
     * 复用 {@link DownloadProvider#injectURLWithCandidates(String)} 注入候选 URL
     * （官方/BMCLAPI/MCBBS provider 不变，本包只消费候选列表）。
     */
    public static DownloadSourceChain fromProvider(String baseUrl, DownloadProvider provider) {
        List<String> urls = new ArrayList<>();
        if (provider != null) {
            List<URI> candidates = provider.injectURLWithCandidates(baseUrl);
            for (URI u : candidates) {
                urls.add(u.toString());
            }
        } else {
            urls.add(baseUrl);
        }
        return new DownloadSourceChain(urls);
    }

    public List<DownloadSource> getSources() {
        return sources;
    }

    /** SourcesOnce 集合（只读） */
    public Set<DownloadSource> getSourcesOnce() {
        return Collections.unmodifiableSet(sourcesOnce);
    }

    /** 该源是否已在单线程集合里 */
    public boolean isInSourcesOnce(DownloadSource source) {
        return sourcesOnce.contains(source);
    }

    /**
     * 环形扫描取下一个可用源，跳过 disabled。
     *
     * @param forFirstThread 是否给首线程用（首线程从轮转游标开始；分块线程从 0 开始）
     * @return 可用源；全部禁用返回 null
     */
    public synchronized DownloadSource nextSource(boolean forFirstThread) {
        int n = sources.size();
        if (n == 0) return null;
        int start = forFirstThread ? (firstThreadCursor % n) : 0;
        for (int i = 0; i < n; i++) {
            DownloadSource s = sources.get((start + i) % n);
            if (!s.isDisabled() && s.isAllowed()) {
                return s;
            }
        }
        return null;
    }

    /**
     * 标记某源禁用，并按 PCL §2.3 决定后续：
     * 若它是「不支持 Range」的原因则同时加入 SourcesOnce。
     */
    public synchronized void markDisabled(DownloadSource source, String reason) {
        if (source == null) return;
        source.markDisabled(reason);
        if (source.isSingleThread()) {
            sourcesOnce.add(source);
        }
    }

    /** Range 被忽略：该源降级为单线程源并加入 SourcesOnce */
    public synchronized void downgradeToSingleThread(DownloadSource source) {
        if (source == null) return;
        source.setSingleThread(true);
        sourcesOnce.add(source);
    }

    /** 首线程用完后轮转游标 +1（下次首线程落到下一个源 ID） */
    public synchronized void advanceFirstThreadCursor() {
        firstThreadCursor++;
    }

    /** 是否已没有任何多线程可用源（即所有可用源都在 SourcesOnce 或 disabled） */
    public synchronized boolean allMultiThreadSourcesGone() {
        for (DownloadSource s : sources) {
            if (!s.isDisabled() && !s.isSingleThread() && s.isAllowed()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 整文件 Reset 成单线程源：把所有尚未禁用的源都标记为单线程。
     * 配合 DownloadFileTask.Retried 标志，只整体重下一轮。
     */
    public synchronized void resetToSingleThread() {
        for (DownloadSource s : sources) {
            if (!s.isDisabled()) {
                s.setSingleThread(true);
                sourcesOnce.add(s);
            }
        }
    }
}
