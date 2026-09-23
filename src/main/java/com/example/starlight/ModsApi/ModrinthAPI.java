package com.example.starlight.ModsApi;

import com.example.starlight.util.DebugLog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Modrinth API Java 调用封装
 * 可通过项目ID、Slug或关键词搜索获取模组信息（封面、名称、描述等）
 * 支持按项目类型、游戏版本过滤，获取版本下载链接等
 */
public class ModrinthAPI {

    private static final String BASE_URL = "https://api.modrinth.com/v2/";
    private final HttpClient httpClient;
    private final Gson gson;

    public ModrinthAPI() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.gson = new GsonBuilder().create();
    }

    // ==================== 对外公开方法 ====================

    /**
     * 通过项目ID或Slug获取单个项目信息
     */
    public ModrinthProject getProject(String idOrSlug) throws IOException, InterruptedException {
        String url = BASE_URL + "project/" + encode(idOrSlug);
        String json = sendGetRequest(url);
        if (json == null || json.isEmpty()) {
            return null;
        }
        return gson.fromJson(json, ModrinthProject.class);
    }

    /**
     * 根据关键词搜索项目（支持项目类型和游戏版本过滤）
     * @param query       搜索关键词
     * @param limit       返回结果数量上限（最大100）
     * @param projectType 项目类型过滤（mod/modpack/datapack/resourcepack/shader），null不限
     * @param gameVersion 游戏版本过滤（如 "1.20.1"），null不限
     * @return 项目信息列表
     */
    public List<ModrinthProject> searchProjects(String query, int limit, String projectType, String gameVersion)
            throws IOException, InterruptedException {
        List<String> facets = new ArrayList<>();
        if (projectType != null && !projectType.isEmpty()) {
            facets.add("[\"project_type:" + projectType + "\"]");
        }
        if (gameVersion != null && !gameVersion.isEmpty()) {
            facets.add("[\"versions:" + gameVersion + "\"]");
        }

        StringBuilder url = new StringBuilder(BASE_URL + "search?limit=" + Math.min(limit, 100));
        if (!facets.isEmpty()) {
            url.append("&facets=").append(encode(String.valueOf(facets)));
        }
        if (query != null && !query.trim().isEmpty()) {
            url.append("&query=").append(encode(query.trim()));
        }

        String json = sendGetRequest(url.toString());
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }
        JsonObject root = gson.fromJson(json, JsonObject.class);
        JsonArray hits = root.getAsJsonArray("hits");
        if (hits == null || hits.isEmpty()) {
            return Collections.emptyList();
        }
        List<ModrinthProject> result = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            result.add(gson.fromJson(hits.get(i), ModrinthProject.class));
        }
        return result;
    }

    /**
     * 获取可用游戏版本列表
     */
    public List<GameVersion> getGameVersions() throws IOException, InterruptedException {
        String url = BASE_URL + "tag/game_version";
        String json = sendGetRequest(url);
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }
        JsonArray arr = gson.fromJson(json, JsonArray.class);
        List<GameVersion> list = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            list.add(gson.fromJson(arr.get(i), GameVersion.class));
        }
        return list;
    }

    /**
     * 获取项目的版本列表（含下载链接）
     * @param projectId 项目ID
     * @return 版本信息列表
     */
    public List<ModrinthVersion> getProjectVersions(String projectId) throws IOException, InterruptedException {
        String url = BASE_URL + "project/" + encode(projectId) + "/version";
        String json = sendGetRequest(url);
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }
        JsonArray arr = gson.fromJson(json, JsonArray.class);
        List<ModrinthVersion> list = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            list.add(gson.fromJson(arr.get(i), ModrinthVersion.class));
        }
        return list;
    }

    /**
     * 通过Slug获取项目图标URL（便捷方法）
     */
    public String getIconUrl(String slug) throws IOException, InterruptedException {
        ModrinthProject project = getProject(slug);
        return project != null ? project.getIconUrl() : null;
    }

    /**
     * 通过Slug获取项目名称（便捷方法）
     */
    public String getProjectTitle(String slug) throws IOException, InterruptedException {
        ModrinthProject project = getProject(slug);
        return project != null ? project.getTitle() : null;
    }

    // ==================== 内部辅助方法 ====================

    private String sendGetRequest(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "ModrinthAPI-Java/1.0")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request,
                // 显式 UTF-8：镜像响应无 charset 时 ofString() 回退 Latin-1，中文字段会乱码
                HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
        DebugLog.http(true, "GET", url, 0, 0, null, -1);
        DebugLog.http(false, null, url, response.statusCode(), response.body().length(), response.body(), -1);
        if (response.statusCode() == 200) {
            return response.body();
        } else if (response.statusCode() == 404) {
            return null;
        } else {
            throw new IOException("API request failed, status code: " + response.statusCode());
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // ==================== 数据模型类 ====================

    /**
     * 项目信息（搜索结果或详情）
     */
    public static class ModrinthProject {
        @SerializedName("project_id")
        private String projectId;

        @SerializedName("id")
        private String id;

        @SerializedName("slug")
        private String slug;

        @SerializedName("title")
        private String title;

        @SerializedName("description")
        private String description;

        @SerializedName("icon_url")
        private String iconUrl;

        @SerializedName("project_type")
        private String projectType;

        @SerializedName("downloads")
        private int downloads;

        @SerializedName("author")
        private String author;

        @SerializedName("client_side")
        private String clientSide;

        @SerializedName("server_side")
        private String serverSide;

        @SerializedName("categories")
        private List<String> categories;

        @SerializedName("loaders")
        private List<String> loaders;

        @SerializedName("versions")
        private List<String> versions;

        @SerializedName("date_modified")
        private String dateModified;

        @SerializedName("date_created")
        private String dateCreated;

        public String getId() { return id != null ? id : projectId; }
        public String getProjectId() { return projectId != null ? projectId : id; }
        public String getSlug() { return slug; }
        public String getTitle() { return title; }
        public String getDescription() { return description; }
        public String getIconUrl() { return iconUrl; }
        public String getProjectType() { return projectType; }
        public int getDownloads() { return downloads; }
        public String getAuthor() { return author; }
        public String getClientSide() { return clientSide; }
        public String getServerSide() { return serverSide; }
        public List<String> getCategories() { return categories != null ? categories : Collections.emptyList(); }
        public List<String> getLoaders() { return loaders != null ? loaders : Collections.emptyList(); }
        public List<String> getVersions() { return versions != null ? versions : Collections.emptyList(); }
        public String getDateModified() { return dateModified; }
        public String getDateCreated() { return dateCreated; }

        @Override
        public String toString() {
            return "ModrinthProject{" +
                    "title='" + title + '\'' +
                    ", slug='" + slug + '\'' +
                    ", author='" + author + '\'' +
                    ", downloads=" + downloads +
                    ", iconUrl='" + iconUrl + '\'' +
                    '}';
        }
    }

    /**
     * 游戏版本信息
     */
    public static class GameVersion {
        @SerializedName("version")
        private String version;

        @SerializedName("version_type")
        private String versionType;

        @SerializedName("released")
        private String released;

        public String getVersion() { return version; }
        public String getVersionType() { return versionType; }
        public String getReleased() { return released; }
    }

    /**
     * 项目版本信息（含下载文件）
     */
    public static class ModrinthVersion {
        @SerializedName("id")
        private String id;

        @SerializedName("project_id")
        private String projectId;

        @SerializedName("name")
        private String name;

        @SerializedName("version_number")
        private String versionNumber;

        @SerializedName("game_versions")
        private List<String> gameVersions;

        @SerializedName("loaders")
        private List<String> loaders;

        @SerializedName("files")
        private List<ModrinthFile> files;

        @SerializedName("date_published")
        private String datePublished;

        public String getId() { return id; }
        public String getProjectId() { return projectId; }
        public String getName() { return name; }
        public String getVersionNumber() { return versionNumber; }
        public List<String> getGameVersions() { return gameVersions != null ? gameVersions : Collections.emptyList(); }
        public List<String> getLoaders() { return loaders != null ? loaders : Collections.emptyList(); }
        public List<ModrinthFile> getFiles() { return files != null ? files : Collections.emptyList(); }
        public String getDatePublished() { return datePublished; }
    }

    /**
     * 版本文件信息
     */
    public static class ModrinthFile {
        @SerializedName("url")
        private String url;

        @SerializedName("filename")
        private String filename;

        @SerializedName("size")
        private long size;

        @SerializedName("primary")
        private boolean primary;

        public String getUrl() { return url; }
        public String getFilename() { return filename; }
        public long getSize() { return size; }
        public boolean isPrimary() { return primary; }
    }
}