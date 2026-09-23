package com.example.starlight.ModsApi;

/**
 * 远程资源搜索条件。
 *
 * <p>对应 VersePc2 下载页工具条上的「搜索 + 加载器 + 游戏版本 + 分类 + 排序 + 来源」六项输入，
 * 作为 {@link RemoteModRepository#search(com.example.starlight.download.DownloadProvider, ModSearchQuery)}
 * 的参数载体，避免为每个仓库单独扩展一长串方法签名。
 *
 * <p>本类是<b>可变</b>的：界面上的下拉框每次触发搜索时复用同一个实例并就地改写字段，
 * 需要跨线程传递时请用 {@link #copy()} 复制一份，避免异步任务读到被改写后的值。
 */
public final class ModSearchQuery {

    /** 搜索关键词（支持中文，由 LocalizedRemoteModRepository 负责翻译） */
    private String query = "";
    /** 游戏版本筛选，如 {@code 1.20.1}；空串表示不限 */
    private String gameVersion = "";
    /** 加载器筛选键：{@code fabric/forge/neoforge/quilt}；空串表示不限 */
    private String loader = "";
    /** Modrinth 分类名（如 {@code technology}）；空串表示不限 */
    private String category = "";
    /** CurseForge 分类 ID；{@code -1} 表示不限 */
    private int categoryId = -1;
    /** 起始页下标（0 基） */
    private int pageOffset = 0;
    /** 每页数量 */
    private int pageSize = 20;
    /** 排序方式；{@code null} 表示按相关度（Modrinth 不传 index，CurseForge 用 Featured） */
    private RemoteModRepository.SortType sortType = null;
    private RemoteModRepository.SortOrder sortOrder = RemoteModRepository.SortOrder.DESC;

    public String getQuery() { return query; }

    public ModSearchQuery setQuery(String query) {
        this.query = query == null ? "" : query.trim();
        return this;
    }

    public String getGameVersion() { return gameVersion; }

    public ModSearchQuery setGameVersion(String gameVersion) {
        this.gameVersion = gameVersion == null ? "" : gameVersion.trim();
        return this;
    }

    public String getLoader() { return loader; }

    public ModSearchQuery setLoader(String loader) {
        this.loader = loader == null ? "" : loader.trim().toLowerCase(java.util.Locale.ROOT);
        return this;
    }

    public String getCategory() { return category; }

    public ModSearchQuery setCategory(String category) {
        this.category = category == null ? "" : category.trim();
        return this;
    }

    public int getCategoryId() { return categoryId; }

    public ModSearchQuery setCategoryId(int categoryId) {
        this.categoryId = categoryId;
        return this;
    }

    public int getPageOffset() { return pageOffset; }

    public ModSearchQuery setPageOffset(int pageOffset) {
        this.pageOffset = Math.max(0, pageOffset);
        return this;
    }

    public int getPageSize() { return pageSize; }

    public ModSearchQuery setPageSize(int pageSize) {
        this.pageSize = Math.max(1, pageSize);
        return this;
    }

    public RemoteModRepository.SortType getSortType() { return sortType; }

    public ModSearchQuery setSortType(RemoteModRepository.SortType sortType) {
        this.sortType = sortType;
        return this;
    }

    public RemoteModRepository.SortOrder getSortOrder() { return sortOrder; }

    public ModSearchQuery setSortOrder(RemoteModRepository.SortOrder sortOrder) {
        this.sortOrder = sortOrder == null ? RemoteModRepository.SortOrder.DESC : sortOrder;
        return this;
    }

    /** 深拷贝：异步搜索任务应持有副本，防止界面继续改动下拉框时读到半新半旧的条件 */
    public ModSearchQuery copy() {
        ModSearchQuery c = new ModSearchQuery();
        c.query = query;
        c.gameVersion = gameVersion;
        c.loader = loader;
        c.category = category;
        c.categoryId = categoryId;
        c.pageOffset = pageOffset;
        c.pageSize = pageSize;
        c.sortType = sortType;
        c.sortOrder = sortOrder;
        return c;
    }
}
