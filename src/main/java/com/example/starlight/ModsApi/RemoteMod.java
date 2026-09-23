package com.example.starlight.ModsApi;

import com.example.starlight.download.DownloadProvider;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 远程 MOD 数据模型（参考 HMCL 架构设计）
 * 统一 Modrinth / CurseForge 的 MOD 描述
 */
public final class RemoteMod {

    public static final RemoteMod BROKEN = new RemoteMod("", "", "RemoteMod.BROKEN", "",
            List.of(), "", "", new IMod() {
        @Override
        public List<RemoteMod> loadDependencies(RemoteModRepository repo, DownloadProvider dp) throws IOException {
            throw new IOException("Broken mod");
        }

        @Override
        public Stream<Version> loadVersions(RemoteModRepository repo, DownloadProvider dp) throws IOException {
            throw new IOException("Broken mod");
        }
    });

    private final String slug;
    private final String author;
    private final String title;
    private final String description;
    private final List<String> categories;
    private final String pageUrl;
    private final String iconUrl;
    private final IMod data;
    private final int downloads;

    public RemoteMod(String slug, String author, String title, String description,
                     List<String> categories, String pageUrl, String iconUrl, IMod data) {
        this(slug, author, title, description, categories, pageUrl, iconUrl, data, 0);
    }

    public RemoteMod(String slug, String author, String title, String description,
                     List<String> categories, String pageUrl, String iconUrl, IMod data,
                     int downloads) {
        this.slug = slug;
        this.author = author;
        this.title = title;
        this.description = description;
        this.categories = categories;
        this.pageUrl = pageUrl;
        this.iconUrl = iconUrl;
        this.data = data;
        this.downloads = downloads;
    }

    public String getSlug() { return slug; }
    public String getAuthor() { return author; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public List<String> getCategories() { return categories; }
    public String getPageUrl() { return pageUrl; }
    public String getIconUrl() { return iconUrl; }
    public IMod getData() { return data; }
    public int getDownloads() { return downloads; }

    // ==================== 版本类型 ====================

    public enum VersionType { Release, Beta, Alpha }

    // ==================== 依赖类型 ====================

    public enum DependencyType { REQUIRED, OPTIONAL, TOOL, INCLUDE, EMBEDDED, INCOMPATIBLE, BROKEN }

    // ==================== 依赖 ====================

    public static final class Dependency {
        private final DependencyType type;
        private final RemoteModRepository source;
        private final String id;
        private transient RemoteMod mod;

        private Dependency(DependencyType type, RemoteModRepository source, String id) {
            this.type = type;
            this.source = source;
            this.id = id;
        }

        public static Dependency of(DependencyType type, RemoteModRepository source, String modid) {
            return new Dependency(type, source, modid);
        }

        public DependencyType getType() { return type; }
        public RemoteModRepository getSource() { return source; }
        public String getId() { return id; }

        public RemoteMod load(DownloadProvider dp) throws IOException {
            if (mod == null) mod = source.getModById(dp, id);
            return mod;
        }
    }

    // ==================== MOD 类型 ====================

    public enum Type {
        MODRINTH(com.example.starlight.ModsApi.ModrinthRemoteModRepository.MODS),
        CURSEFORGE(com.example.starlight.ModsApi.CurseForgeRemoteModRepository.MODS);

        private final RemoteModRepository repo;
        Type(RemoteModRepository repo) { this.repo = repo; }
        public RemoteModRepository getRepo() { return repo; }
    }

    // ==================== IMod 接口 ====================

    public interface IMod {
        List<RemoteMod> loadDependencies(RemoteModRepository repo, DownloadProvider dp) throws IOException;
        Stream<Version> loadVersions(RemoteModRepository repo, DownloadProvider dp) throws IOException;
    }

    // ==================== IVersion 接口 ====================

    public interface IVersion {
        Type getType();
    }

    // ==================== 版本 ====================

    public static class Version {
        private final IVersion self;
        private final String modid;
        private final String name;
        private final String version;
        private final String changelog;
        private final Instant datePublished;
        private final VersionType versionType;
        private final File file;
        private final List<Dependency> dependencies;
        private final List<String> gameVersions;
        private final List<ModLoaderType> loaders;

        public Version(IVersion self, String modid, String name, String version, String changelog,
                       Instant datePublished, VersionType versionType, File file,
                       List<Dependency> dependencies, List<String> gameVersions, List<ModLoaderType> loaders) {
            this.self = self;
            this.modid = modid;
            this.name = name;
            this.version = version;
            this.changelog = changelog;
            this.datePublished = datePublished;
            this.versionType = versionType;
            this.file = file;
            this.dependencies = dependencies;
            this.gameVersions = gameVersions;
            this.loaders = loaders;
        }

        public IVersion getSelf() { return self; }
        public String getModid() { return modid; }
        public String getName() { return name; }
        public String getVersion() { return version; }
        public String getChangelog() { return changelog; }
        public Instant getDatePublished() { return datePublished; }
        public VersionType getVersionType() { return versionType; }
        public File getFile() { return file; }
        public List<Dependency> getDependencies() { return dependencies; }
        public List<String> getGameVersions() { return gameVersions; }
        public List<ModLoaderType> getLoaders() { return loaders; }
    }

    // ==================== 文件 ====================

    public static class File {
        private final Map<String, String> hashes;
        private final String url;
        private final String filename;

        public File(Map<String, String> hashes, String url, String filename) {
            this.hashes = hashes;
            this.url = url;
            this.filename = filename;
        }

        public Map<String, String> getHashes() { return hashes; }
        public String getUrl() { return url; }
        public String getFilename() { return filename; }
    }
}
