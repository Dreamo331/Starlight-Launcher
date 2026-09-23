package com.example.starlight.ModsApi;

import com.example.starlight.download.DownloadProvider;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 远程 MOD 仓库接口（参考 HMCL 架构设计）
 * 统一 Modrinth / CurseForge 的查询接口
 */
public interface RemoteModRepository {

    enum Type {
        MOD, MODPACK, RESOURCE_PACK, SHADER_PACK, WORLD, CUSTOMIZATION
    }

    Type getType();

    enum SortType {
        POPULARITY, NAME, DATE_CREATED, LAST_UPDATED, AUTHOR, TOTAL_DOWNLOADS
    }

    enum SortOrder {
        ASC, DESC
    }

    class SearchResult {
        private final Stream<RemoteMod> sortedResults;
        private final Stream<RemoteMod> unsortedResults;
        private final int totalPages;

        public SearchResult(Stream<RemoteMod> sortedResults, Stream<RemoteMod> unsortedResults, int totalPages) {
            this.sortedResults = sortedResults;
            this.unsortedResults = unsortedResults;
            this.totalPages = totalPages;
        }

        public SearchResult(Stream<RemoteMod> sortedResults, int pages) {
            this.sortedResults = sortedResults;
            this.unsortedResults = sortedResults;
            this.totalPages = pages;
        }

        public Stream<RemoteMod> getResults() { return sortedResults; }
        public Stream<RemoteMod> getUnsortedResults() { return unsortedResults; }
        public int getTotalPages() { return totalPages; }
    }

    SearchResult search(DownloadProvider dp, String gameVersion, Category category,
                        int pageOffset, int pageSize, String searchFilter,
                        SortType sortType, SortOrder sortOrder) throws IOException;

    Optional<RemoteMod.Version> getRemoteVersionByLocalFile(LocalModFile localModFile, Path file) throws IOException;

    RemoteMod getModById(DownloadProvider dp, String id) throws IOException;

    default RemoteMod resolveDependency(DownloadProvider dp, String id) throws IOException {
        return getModById(dp, id);
    }

    RemoteMod.File getModFile(String modId, String fileId) throws IOException;

    Stream<RemoteMod.Version> getRemoteVersionsById(DownloadProvider dp, String id) throws IOException;

    Stream<Category> getCategories() throws IOException;

    // ==================== 下载中心扩展（加载器/分类筛选、详情） ====================

    /**
     * 带完整筛选条件的搜索（加载器 + 分类 + 游戏版本 + 排序 + 来源）。
     *
     * <p>默认实现忽略加载器与分类，退化为原有的 {@link #search}，保证既有实现零改动可用；
     * Modrinth 与 CurseForge 各自覆写以接入后端的 facet / categoryId 参数。
     */
    default SearchResult search(DownloadProvider dp, ModSearchQuery query) throws IOException {
        if (query == null) query = new ModSearchQuery();
        return search(dp, query.getGameVersion(), null, query.getPageOffset(), query.getPageSize(),
                query.getQuery(), query.getSortType(), query.getSortOrder());
    }

    /**
     * 项目详情（描述正文 / 图库 / 许可 / 外链），供模组详情页使用。
     *
     * <p>默认实现用 {@link #getModById} 的结果拼一份最小详情，保证不会返回 null；
     * 支持详情接口的仓库应覆写以填充描述正文与图库。
     */
    default RemoteModDetail getProjectDetail(DownloadProvider dp, String id) throws IOException {
        return RemoteModDetail.fallback(getModById(dp, id));
    }

    /**
     * 与当前资源类型匹配的分类列表（已剔除其它类目的分类）。
     * <p>用于下载页「分类」下拉：Modrinth 按 project_type 过滤，CurseForge 按 classId 过滤。
     */
    default List<Category> getCategoriesForType() throws IOException {
        return getCategories().collect(java.util.stream.Collectors.toList());
    }

    class Category {
        private final Object self;
        private final String id;
        private final List<Category> subcategories;

        public Category(Object self, String id, List<Category> subcategories) {
            this.self = self;
            this.id = id;
            this.subcategories = subcategories;
        }

        public Object getSelf() { return self; }
        public String getId() { return id; }
        public List<Category> getSubcategories() { return subcategories; }
    }
}
