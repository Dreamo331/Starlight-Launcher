package com.example.starlight.ModsApi;

import java.util.ArrayList;
import java.util.List;

/**
 * 远程项目详情（模组详情页所需，{@link RemoteMod} 只承载列表页用得到的轻量字段）。
 *
 * <p>对应 VersePc2 {@code page-mod-detail} 的「头部 + 描述 + 图库 + 侧栏」四块内容：
 * <ul>
 *   <li><b>头部</b>：标题、简介、图标、下载量、关注数、分类标签；</li>
 *   <li><b>描述</b>：{@link #getBody()}，Modrinth 返回 Markdown、CurseForge 返回 HTML；</li>
 *   <li><b>图库</b>：{@link #getGallery()}，图片直链列表；</li>
 *   <li><b>侧栏</b>：许可协议、客户端/服务端支持、更新时间，以及源码/问题追踪/Wiki 等外链。</li>
 * </ul>
 */
public final class RemoteModDetail {

    /** 描述正文的格式，决定详情页用哪种方式渲染 */
    public enum BodyFormat {
        /** Modrinth 风格 Markdown 文本 */
        MARKDOWN,
        /** CurseForge 风格 HTML 片段 */
        HTML,
        /** 无正文，只显示简介 */
        PLAIN
    }

    private final String id;
    private final String title;
    private final String slug;
    private final String description;
    private final BodyFormat bodyFormat;
    private final String body;
    private final String iconUrl;
    private final String author;
    private final String license;
    private final String licenseUrl;
    private final String clientSide;
    private final String serverSide;
    private final String published;
    private final String updated;
    private final long downloads;
    private final long followers;
    private final List<String> gallery;
    private final List<String> categories;
    private final String pageUrl;
    private final String sourceUrl;
    private final String issuesUrl;
    private final String wikiUrl;
    private final String discordUrl;

    private RemoteModDetail(Builder b) {
        this.id = b.id;
        this.title = b.title;
        this.slug = b.slug;
        this.description = b.description;
        this.bodyFormat = b.bodyFormat;
        this.body = b.body;
        this.iconUrl = b.iconUrl;
        this.author = b.author;
        this.license = b.license;
        this.licenseUrl = b.licenseUrl;
        this.clientSide = b.clientSide;
        this.serverSide = b.serverSide;
        this.published = b.published;
        this.updated = b.updated;
        this.downloads = b.downloads;
        this.followers = b.followers;
        this.gallery = List.copyOf(b.gallery);
        this.categories = List.copyOf(b.categories);
        this.pageUrl = b.pageUrl;
        this.sourceUrl = b.sourceUrl;
        this.issuesUrl = b.issuesUrl;
        this.wikiUrl = b.wikiUrl;
        this.discordUrl = b.discordUrl;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getSlug() { return slug; }
    public String getDescription() { return description; }
    public BodyFormat getBodyFormat() { return bodyFormat; }
    public String getBody() { return body; }
    public String getIconUrl() { return iconUrl; }
    public String getAuthor() { return author; }
    public String getLicense() { return license; }
    public String getLicenseUrl() { return licenseUrl; }
    public String getClientSide() { return clientSide; }
    public String getServerSide() { return serverSide; }
    public String getPublished() { return published; }
    public String getUpdated() { return updated; }
    public long getDownloads() { return downloads; }
    public long getFollowers() { return followers; }
    public List<String> getGallery() { return gallery; }
    public List<String> getCategories() { return categories; }
    public String getPageUrl() { return pageUrl; }
    public String getSourceUrl() { return sourceUrl; }
    public String getIssuesUrl() { return issuesUrl; }
    public String getWikiUrl() { return wikiUrl; }
    public String getDiscordUrl() { return discordUrl; }

    /** 是否有可渲染的正文（无正文时详情页回退到显示一行简介） */
    public boolean hasBody() {
        return body != null && !body.isBlank();
    }

    /**
     * 用列表页已有的 {@link RemoteMod} 兜底构造一份详情。
     * <p>详情接口请求失败时使用，保证详情页至少能显示标题、简介与图标，而不是一片空白。
     */
    public static RemoteModDetail fallback(RemoteMod mod) {
        if (mod == null) return new Builder().build();
        return new Builder()
                .id(mod.getSlug())
                .slug(mod.getSlug())
                .title(mod.getTitle())
                .description(mod.getDescription())
                .body("")
                .bodyFormat(BodyFormat.PLAIN)
                .iconUrl(mod.getIconUrl())
                .author(mod.getAuthor())
                .downloads(mod.getDownloads())
                .categories(mod.getCategories())
                .pageUrl(mod.getPageUrl())
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 详情对象构造器：字段多且大多可选，用链式赋值可读性优于超长构造方法 */
    public static final class Builder {
        private String id = "";
        private String title = "";
        private String slug = "";
        private String description = "";
        private BodyFormat bodyFormat = BodyFormat.PLAIN;
        private String body = "";
        private String iconUrl = "";
        private String author = "";
        private String license = "";
        private String licenseUrl = "";
        private String clientSide = "";
        private String serverSide = "";
        private String published = "";
        private String updated = "";
        private long downloads = 0;
        private long followers = 0;
        private List<String> gallery = new ArrayList<>();
        private List<String> categories = new ArrayList<>();
        private String pageUrl = "";
        private String sourceUrl = "";
        private String issuesUrl = "";
        private String wikiUrl = "";
        private String discordUrl = "";

        public Builder id(String v) { this.id = nz(v); return this; }
        public Builder title(String v) { this.title = nz(v); return this; }
        public Builder slug(String v) { this.slug = nz(v); return this; }
        public Builder description(String v) { this.description = nz(v); return this; }
        public Builder bodyFormat(BodyFormat v) { if (v != null) this.bodyFormat = v; return this; }
        public Builder body(String v) { this.body = nz(v); return this; }
        public Builder iconUrl(String v) { this.iconUrl = nz(v); return this; }
        public Builder author(String v) { this.author = nz(v); return this; }
        public Builder license(String v) { this.license = nz(v); return this; }
        public Builder licenseUrl(String v) { this.licenseUrl = nz(v); return this; }
        public Builder clientSide(String v) { this.clientSide = nz(v); return this; }
        public Builder serverSide(String v) { this.serverSide = nz(v); return this; }
        public Builder published(String v) { this.published = nz(v); return this; }
        public Builder updated(String v) { this.updated = nz(v); return this; }
        public Builder downloads(long v) { this.downloads = v; return this; }
        public Builder followers(long v) { this.followers = v; return this; }
        public Builder categories(List<String> v) { if (v != null) this.categories = v; return this; }
        public Builder pageUrl(String v) { this.pageUrl = nz(v); return this; }
        public Builder sourceUrl(String v) { this.sourceUrl = nz(v); return this; }
        public Builder issuesUrl(String v) { this.issuesUrl = nz(v); return this; }
        public Builder wikiUrl(String v) { this.wikiUrl = nz(v); return this; }
        public Builder discordUrl(String v) { this.discordUrl = nz(v); return this; }

        public Builder gallery(List<String> v) {
            if (v != null) this.gallery = v;
            return this;
        }

        private static String nz(String v) { return v == null ? "" : v; }

        public RemoteModDetail build() { return new RemoteModDetail(this); }
    }
}
