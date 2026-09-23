package com.example.starlight.newui.download;

import com.example.starlight.ModsApi.ModSearchQuery;
import com.example.starlight.ModsApi.RemoteMod;
import com.example.starlight.ModsApi.RemoteModDetail;
import com.example.starlight.ModsApi.RemoteModRepository;
import com.example.starlight.version.VersionDownloadService;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 下载中心远程数据内存缓存（从 LauncherView 抽离，方法体逐字搬运）。
 *
 * <p>下载中心的数据都来自网络，翻页 / 切分类 / 进退详情页时重复请求既慢又浪费配额，
 * 这里把「搜索结果」「项目版本列表」「项目详情」各缓存一份到进程内存，
 * 再次访问同样的查询条件时直接命中；点「刷新」按钮（force=true）可强制绕过缓存。
 *
 * <p>缓存条数上限 {@value #REMOTE_CACHE_MAX}，超出后整体清空（查询组合有限，无需精细 LRU）。
 * 不依赖 JavaFX，可在后台线程调用。
 */
public final class RemoteCacheService {

    /** 缓存条数上限：超出后整体清空（下载中心的查询组合是有限的，没必要做精细 LRU） */
    private static final int REMOTE_CACHE_MAX = 300;

    /** 搜索结果缓存：key = 资源类型 + 来源 + 全部筛选条件 + 页码 */
    private final Map<String, SearchCacheEntry> remoteSearchCache = new ConcurrentHashMap<>();
    /** 项目版本列表缓存：key = 项目页面地址 */
    private final Map<String, List<RemoteMod.Version>> remoteVersionsCache = new ConcurrentHashMap<>();
    /** 项目详情缓存：key = 项目页面地址 */
    private final Map<String, RemoteModDetail> remoteDetailCache = new ConcurrentHashMap<>();

    /** 读取搜索结果缓存（未命中返回 null） */
    public SearchCacheEntry getSearch(String key) {
        return remoteSearchCache.get(key);
    }

    /** 写入搜索结果缓存（写入前做容量控制，与原 guardCacheSize + put 等价） */
    public void putSearch(String key, List<ModHit> hits, int totalPages) {
        guardCacheSize(remoteSearchCache);
        remoteSearchCache.put(key, new SearchCacheEntry(hits, totalPages));
    }

    /** 清空下载中心的全部远程数据缓存（「刷新」按钮与设置变更后使用） */
    public void clear() {
        remoteSearchCache.clear();
        remoteVersionsCache.clear();
        remoteDetailCache.clear();
    }

    /** 项目级缓存的 key：页面地址天然按站点区分（Modrinth / CurseForge 各一份），比 slug 更稳 */
    private static String projectCacheKey(RemoteMod mod) {
        String url = mod.getPageUrl();
        return (url == null || url.isBlank()) ? String.valueOf(mod.getSlug()) : url;
    }

    /** 搜索结果缓存的 key：把参与查询的所有维度都拼进去，任一维度不同即视为不同查询 */
    public static String searchCacheKey(String panelTitle, ModSearchQuery q, List<String> repoLabels) {
        StringBuilder sb = new StringBuilder(panelTitle).append('|');
        for (String label : repoLabels) sb.append(label).append(',');
        return sb.append('|').append(q.getQuery())
                .append('|').append(q.getGameVersion())
                .append('|').append(q.getLoader())
                .append('|').append(q.getCategory())
                .append('|').append(q.getCategoryId())
                .append('|').append(q.getSortType())
                .append('|').append(q.getPageOffset())
                .append('|').append(q.getPageSize())
                .toString();
    }

    /** 缓存写入前的容量控制：超过上限就整体清空，避免长时间使用后无限增长 */
    private static void guardCacheSize(Map<?, ?> cache) {
        if (cache.size() >= REMOTE_CACHE_MAX) cache.clear();
    }

    /**
     * 读取项目版本列表（带内存缓存）。
     *
     * @param force true 时忽略缓存强制重新拉取（点「刷新」用）
     */
    public List<RemoteMod.Version> loadVersions(RemoteModRepository repo, RemoteMod mod, boolean force)
            throws IOException {
        String key = projectCacheKey(mod);
        if (!force) {
            List<RemoteMod.Version> cached = remoteVersionsCache.get(key);
            if (cached != null) return cached;
        }
        List<RemoteMod.Version> versions = repo
                .getRemoteVersionsById(VersionDownloadService.getDownloadProvider(), mod.getSlug())
                .collect(Collectors.toList());
        if (!versions.isEmpty()) {
            guardCacheSize(remoteVersionsCache);
            remoteVersionsCache.put(key, versions);
        }
        return versions;
    }

    /**
     * 读取项目详情（带内存缓存）。
     *
     * @param force true 时忽略缓存强制重新拉取
     */
    public RemoteModDetail loadDetail(RemoteModRepository repo, RemoteMod mod, boolean force) {
        String key = projectCacheKey(mod);
        if (!force) {
            RemoteModDetail cached = remoteDetailCache.get(key);
            if (cached != null) return cached;
        }
        RemoteModDetail detail = null;
        try {
            detail = repo.getProjectDetail(VersionDownloadService.getDownloadProvider(), mod.getSlug());
        } catch (Exception e) {
            System.err.println("[DL] 详情加载失败: " + e.getMessage());
        }
        if (detail == null || detail.getTitle().isBlank()) {
            // 失败时降级为列表页已有的信息；不写缓存，方便下次重试
            return RemoteModDetail.fallback(mod);
        }
        guardCacheSize(remoteDetailCache);
        remoteDetailCache.put(key, detail);
        return detail;
    }
}
