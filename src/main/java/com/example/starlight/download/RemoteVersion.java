package com.example.starlight.download;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 远程版本描述符（参考 HMCL 架构设计）
 * 描述一个远端可下载的版本（原版、加载器等）
 */
public class RemoteVersion implements Comparable<RemoteVersion> {

    /** 版本类型标识：game/forge/fabric/neoforge/optifine 等 */
    private final String libraryId;
    /** 兼容的 Minecraft 版本 */
    private final String gameVersion;
    /** 自身版本号 */
    private final String selfVersion;
    /** 发布日期 */
    private final Instant releaseDate;
    /** 下载地址列表 */
    private final List<String> urls;
    /** 版本类型 */
    private final Type type;

    public RemoteVersion(String libraryId, String gameVersion, String selfVersion,
                         Instant releaseDate, List<String> urls) {
        this(libraryId, gameVersion, selfVersion, releaseDate, Type.UNCATEGORIZED, urls);
    }

    public RemoteVersion(String libraryId, String gameVersion, String selfVersion,
                         Instant releaseDate, Type type, List<String> urls) {
        this.libraryId = Objects.requireNonNull(libraryId);
        this.gameVersion = Objects.requireNonNull(gameVersion);
        this.selfVersion = Objects.requireNonNull(selfVersion);
        this.releaseDate = releaseDate;
        this.urls = Objects.requireNonNull(urls);
        this.type = Objects.requireNonNull(type);
    }

    public String getLibraryId() { return libraryId; }
    public String getGameVersion() { return gameVersion; }
    public String getSelfVersion() { return selfVersion; }
    public String getFullVersion() { return selfVersion; }
    public Instant getReleaseDate() { return releaseDate; }
    public List<String> getUrls() { return urls; }
    public Type getVersionType() { return type; }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof RemoteVersion
                && Objects.equals(selfVersion, ((RemoteVersion) obj).selfVersion)
                && Objects.equals(libraryId, ((RemoteVersion) obj).libraryId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(libraryId, selfVersion);
    }

    @Override
    public int compareTo(RemoteVersion o) {
        return o.selfVersion.compareTo(selfVersion);
    }

    public enum Type {
        UNCATEGORIZED,
        RELEASE,
        SNAPSHOT,
        OLD,
        PENDING,
        UNOBFUSCATED
    }
}
