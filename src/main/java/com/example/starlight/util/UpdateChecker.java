package com.example.starlight.util;

import com.example.starlight.config.Endpoints;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Platform;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 更新检查服务：三级来源获取最新版本并与当前版本比对。
 *
 * <p><b>来源优先级</b>（见 {@link Source}）：
 * <ol>
 *   <li><b>官方服务器</b>：星光MC社区 {@code auth.php?action=launcher_update}（优先，唯一能给出
 *       {@code md5} 与 {@code force_update} 的来源，接口见《星光MC社区_启动器更新API文档.md》）；</li>
 *   <li><b>降级来源</b>：官方不可用时，GitHub 与 Gitee 两个仓库的版本检查 API <b>并发竞速</b>，
 *       谁先返回有效结果就用谁——等价于「用用户所在位置网速最快的那家」，
 *       总耗时约等于较快一方的耗时而非两者之和。</li>
 * </ol>
 *
 * <p><b>官方「失败」的判定</b>（任一命中即降级）：网络异常/超时、HTTP 非 200、
 * 服务端返回 {@code ok=false}、响应缺少 {@code version} 字段。
 *
 * <p><b>降级来源的解析</b>：两家各用各的接口 ——
 * GitHub 走标准 REST（{@code releases/latest} → {@code releases} 列表 → {@code tags} 列表 三级降级）；
 * Gitee 走<b>网页版 JSON</b>（官方 API v5 对匿名请求一律 403 限流，详见 {@link #fetchGitee}）。
 * 两家最终都归一成 {@link Candidate}。仓库存放发行版的方式三种都能覆盖；只有标签（无发行版）时拿不到
 * 更新说明，也没有安装包直链，此时 {@link UpdateResult#releasePageUrl} 给出发行版页面供用户手动下载。
 * 网络层失败（主机不可达）会立即放弃该来源，不会对同一主机的其它接口重复重试。
 *
 * <p>纯逻辑类，不依赖任何 UI；调用方负责展示检查结果（弹窗等）。
 */
public final class UpdateChecker {

    /**
     * 检测更新 API（公开接口，无需 API Key）——官方服务器，优先使用。
     * 返回结构：
     *   {"ok":true,"version":"1.0.1","download_url":"...","md5":"...",
     *    "release_notes":"更新说明","force_update":false,"check_time":"..."}
     */
    public static final String RELEASES_API_URL =
            Endpoints.communityApiUrl() + "?action=launcher_update";

    /** 更新检查专用线程池（守护线程，不阻止 JVM 退出） */
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "update-checker");
        t.setDaemon(true);
        return t;
    });

    /** 单次 HTTP 请求超时；降级来源有三级，故不宜再放大 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    /**
     * Gitee 请求必须伪装成浏览器：实测 {@code /api/v5/} 匿名一律 403
     * （{@code 403 Forbidden (Rate Limit Exceeded)}，连仓库元信息也拦），
     * 只剩网页版 JSON 接口可用，而它按 User-Agent 判断给 HTML 还是 JSON。
     */
    private static final String GITEE_BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    /**
     * 版本号形式的标签名：{@code v1.0.0}、{@code 1.0.0-SNAPSHOT}、{@code v2.0.0-RC} …
     * 用于在 {@code tags}/{@code releases} 列表里滤掉 {@code release-2026-06} 这类非版本标签。
     */
    private static final Pattern VERSION_LIKE = Pattern.compile("^[vV]?\\d+\\.\\d+.*$");

    /** 拆出「数字段」与「发布阶段后缀」：{@code v1.0.0-rc2-2} → 数字 {@code 1.0.0} + 后缀 {@code rc2-2} */
    private static final Pattern VERSION_PARTS =
            Pattern.compile("^\\s*[vV]?(\\d+(?:\\.\\d+)*)\\s*(?:[-_.+]?\\s*(.*?))?\\s*$");

    /** 后缀里的数字（{@code rc2} 的 2），用于同后缀之间比大小 */
    private static final Pattern QUALIFIER_NUMBER = Pattern.compile("(\\d+)");

    // 发布阶段（后缀）的稳定度：数字越大越正式，顺序与 AppConfig 里声明的规则一致
    private static final int STAGE_SNAPSHOT = 0;
    private static final int STAGE_ALPHA = 1;
    private static final int STAGE_BETA = 2;
    private static final int STAGE_RC = 3;
    private static final int STAGE_FINAL = 4;

    /** 版本检查来源 */
    public enum Source {
        /** 星光MC社区官方服务器（优先） */
        OFFICIAL("星光MC社区官方服务器"),
        /** GitHub 仓库发行版 API（官方不可用时的降级来源） */
        GITHUB("GitHub"),
        /** Gitee 仓库发行版 API（官方不可用时的降级来源） */
        GITEE("Gitee");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        /** 展示用来源名（日志、界面提示） */
        public String label() {
            return label;
        }

        /** 网页版地址（发行版页面 / 网页版 JSON 接口），GitHub 与 Gitee 的路径格式一致 */
        String webBase(String repo) {
            return (this == GITHUB ? "https://github.com/" : "https://gitee.com/") + repo;
        }

        /** 发行版页面地址（无安装包直链时给用户在浏览器里手动下载） */
        String releasePageUrl(String repo, String tag) {
            return webBase(repo) + "/releases/tag/" + tag;
        }
    }

    /**
     * 检查结果：
     * - latestTag        显示用版本标签（含 v 前缀，如 v1.0.1）
     * - latestVersion    服务端 version 字段（如 1.0.1，不含 v 前缀）
     * - releaseBody      更新说明（release_notes / 发行版正文）
     * - downloadUrl      安装包下载地址（仅指向 .exe 直链；降级来源无 exe 附件时为空）
     * - md5              安装包 MD5（校验完整性；仅官方服务器提供，降级来源为空）
     * - forceUpdate      是否强制更新（true=必须更新；仅官方服务器能给出）
     * - error            失败原因（非空表示检查失败）
     * - source           本次结果来自哪个来源（失败时为 null）
     * - releasePageUrl   发行版页面地址（浏览器打开手动下载用，可能为空）
     */
    public static final class UpdateResult {
        public final String latestTag;
        public final String latestVersion;
        public final String releaseBody;
        public final String downloadUrl;
        public final String md5;
        public final boolean forceUpdate;
        public final String error;
        public final Source source;
        public final String releasePageUrl;

        /** 兼容旧签名（无来源信息）的结果；来源与发行版页面按缺省值填充 */
        public UpdateResult(String latestTag, String latestVersion, String releaseBody,
                            String downloadUrl, String md5, boolean forceUpdate, String error) {
            this(latestTag, latestVersion, releaseBody, downloadUrl, md5, forceUpdate, error, null, "");
        }

        public UpdateResult(String latestTag, String latestVersion, String releaseBody,
                            String downloadUrl, String md5, boolean forceUpdate, String error,
                            Source source, String releasePageUrl) {
            this.latestTag = latestTag;
            this.latestVersion = latestVersion;
            this.releaseBody = releaseBody;
            this.downloadUrl = downloadUrl;
            this.md5 = md5;
            this.forceUpdate = forceUpdate;
            this.error = error;
            this.source = source;
            this.releasePageUrl = releasePageUrl == null ? "" : releasePageUrl;
        }
    }

    /** 单个来源解析成功后的中间结果（字段含义同 {@link UpdateResult}） */
    private static final class Candidate {
        final String version;
        final String notes;
        final String downloadUrl;
        final String md5;
        final boolean forceUpdate;
        final String pageUrl;
        final Source source;

        Candidate(String version, String notes, String downloadUrl, String md5,
                  boolean forceUpdate, String pageUrl, Source source) {
            this.version = version;
            this.notes = notes == null ? "" : notes;
            this.downloadUrl = downloadUrl == null ? "" : downloadUrl;
            this.md5 = md5 == null ? "" : md5;
            this.forceUpdate = forceUpdate;
            this.pageUrl = pageUrl == null ? "" : pageUrl;
            this.source = source;
        }
    }

    /** 取一个来源的版本信息（可抛异常，用于竞速任务） */
    private interface Fetcher {
        Candidate fetch() throws Exception;
    }

    private UpdateChecker() {}

    /**
     * 异步检查更新：优先官方服务器，失败则 GitHub / Gitee 并发竞速。
     * 回调在 JavaFX 应用线程执行，可直接操作 UI。
     *
     * @param currentVersion 当前版本号（不含 v 前缀），用于 User-Agent 与版本比对
     * @param callback       检查完成后的回调，结果中 error 非空表示所有来源都失败
     */
    public static void checkUpdate(String currentVersion, Consumer<UpdateResult> callback) {
        POOL.submit(() -> {
            // 兜底：无论内部出什么意外都产生一个结果回调出去，
            // 否则调用方的「正在检查更新...」提示会一直挂着
            UpdateResult result;
            try {
                result = checkBlocking(currentVersion);
            } catch (Throwable t) {
                result = failure("检查更新失败：" + describe(t));
            }
            final UpdateResult r = result;
            Platform.runLater(() -> callback.accept(r));
        });
    }

    /** 阻塞式检查（在 {@link #POOL} 线程内执行）：官方优先，失败后 GitHub / Gitee 竞速 */
    private static UpdateResult checkBlocking(String currentVersion) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        // ① 官方服务器优先（连通即可用，不必再问降级来源）
        String officialError;
        try {
            return toResult(fetchOfficial(client, currentVersion));
        } catch (Exception ex) {
            officialError = describe(ex);
        }

        // ② 官方不可用：GitHub / Gitee 并发竞速，先返回有效结果的一方胜出。
        //    失败的一方不阻塞——只要有一家先给出结果就立即采用。
        List<String> failures = new ArrayList<>();
        BlockingQueue<Object> sink = new LinkedBlockingQueue<>();
        ExecutorService race = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "update-checker-race");
            t.setDaemon(true);
            return t;
        });
        try {
            race.execute(() -> fetchInto(sink, () -> fetchGitHub(client, currentVersion)));
            race.execute(() -> fetchInto(sink, () -> fetchGitee(client, currentVersion)));
            for (int i = 0; i < 2; i++) {
                Object first = sink.take();
                if (first instanceof Candidate) {
                    return toResult((Candidate) first);   // 先到者胜，不再等另一家
                }
                failures.add(describe((Throwable) first));
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            failures.add("检查更新被中断");
        } catch (Exception ex) {
            // 竞速线程池本身出问题（理论上不会）：照样给出失败结果，不把异常抛给调用方
            failures.add("降级检查异常：" + describe(ex));
        } finally {
            race.shutdownNow();   // 中断落败的一方（其对结果已无影响）
        }
        return failure("官方服务器不可用（" + officialError + "）；"
                + String.join("；", failures));
    }

    /** 竞速任务体：把结果或失败原因投进同一个队列，供主线程「先到先取」 */
    private static void fetchInto(BlockingQueue<Object> sink, Fetcher fetcher) {
        try {
            sink.offer(fetcher.fetch());
        } catch (Throwable t) {
            sink.offer(t);
        }
    }

    // ==================== 各来源的版本获取 ====================

    /** 官方服务器：slmc.cc.cd/auth.php?action=launcher_update（唯一提供 md5 / force_update 的来源） */
    private static Candidate fetchOfficial(HttpClient client, String currentVersion) throws Exception {
        String url = RELEASES_API_URL;
        HttpResponse<String> resp = send(client, url, currentVersion);
        if (resp.statusCode() != 200) {
            throw new IOException("官方服务器响应异常 (HTTP " + resp.statusCode() + ")"
                    + statusDetail(resp));
        }
        JsonObject obj = asObject(resp.body(), "官方服务器");
        // ok=false 表示服务端认为更新服务不可用，按失败处理并降级
        if (obj.has("ok") && !obj.get("ok").isJsonNull() && !obj.get("ok").getAsBoolean()) {
            String detail = string(obj, "error");
            throw new IOException("官方服务器返回 ok=false（更新服务暂不可用）"
                    + (isBlank(detail) ? "" : "：" + detail));
        }
        String version = string(obj, "version");
        if (isBlank(version)) {
            throw new IOException("官方服务器响应缺少 version 字段");
        }
        return new Candidate(
                stripV(version),
                string(obj, "release_notes"),
                string(obj, "download_url"),
                string(obj, "md5"),
                bool(obj, "force_update"),
                "",                       // 官方总是给直链，不需要发行版页面
                Source.OFFICIAL);
    }

    /**
     * GitHub 版本获取，三级降级：
     * <ol>
     *   <li>{@code releases/latest} —— 最新正式发行版（首选，含更新说明与附件）；</li>
     *   <li>{@code releases} 列表 —— latest 返回 404 但仓库确有发行版时（例如全部标记为预发布/draft），
     *       在列表里取版本号最高者；</li>
     *   <li>{@code tags} 列表 —— 仓库完全没有发行版时，从标签名解析版本号（无更新说明与安装包直链）。</li>
     * </ol>
     * 网络层失败（主机不可达/超时）直接抛出，不再对同一主机的其它接口重试。
     */
    private static Candidate fetchGitHub(HttpClient client, String currentVersion) throws Exception {
        String repo = Endpoints.githubRepo();
        String base = "https://api.github.com/repos/" + repo;
        String appUa = "StarlightLauncher/" + currentVersion;

        // ① 最新发行版
        HttpResponse<String> latest = send(client, base + "/releases/latest", appUa);
        if (latest.statusCode() == 200) {
            Candidate c = parseGitHubRelease(Source.GITHUB, repo, asObject(latest.body(), Source.GITHUB.label()));
            if (c != null) return c;
        } else if (latest.statusCode() != 404) {
            // 404 = 仓库还没有发行版，属于正常情况，继续往下试；其它状态码视为该来源失败
            throw new IOException(Source.GITHUB.label() + " 响应异常 (HTTP " + latest.statusCode() + ")"
                    + statusDetail(latest));
        }

        // ② 发行版列表（latest 为 404 时的兜底）
        HttpResponse<String> releases = send(client, base + "/releases", appUa);
        if (releases.statusCode() == 200) {
            Candidate c = bestGitHubRelease(Source.GITHUB, repo,
                    asArray(releases.body(), Source.GITHUB.label() + " 发行版列表"));
            if (c != null) return c;
        } else if (releases.statusCode() != 404) {
            throw new IOException(Source.GITHUB.label() + " 发行版列表响应异常 (HTTP "
                    + releases.statusCode() + ")" + statusDetail(releases));
        }

        // ③ 标签列表（仓库只有 tag、没有 Release 时的兜底）
        HttpResponse<String> tags = send(client, base + "/tags", appUa);
        if (tags.statusCode() != 200) {
            throw new IOException(Source.GITHUB.label() + " 标签列表响应异常 (HTTP "
                    + tags.statusCode() + ")" + statusDetail(tags));
        }
        Candidate c = bestTag(Source.GITHUB, repo,
                asArray(tags.body(), Source.GITHUB.label() + " 标签列表"));
        if (c == null) {
            throw new IOException(Source.GITHUB.label() + " 仓库没有任何版本号形式的发行版或标签");
        }
        return c;
    }

    /**
     * Gitee 版本获取。
     *
     * <p><b>为什么不用官方 API v5</b>：{@code gitee.com/api/v5/repos/...} 对匿名请求
     * <b>一律 403</b>（{@code 403 Forbidden (Rate Limit Exceeded)}）——实测连仓库元信息本身都被拦，
     * 换浏览器 UA / 加 Referer / 改 Accept 都无效，属于 Gitee 对匿名 API 的出口 IP 限流，
     * 只带有效 {@code access_token} 才能穿过。因此改用网页版 JSON 接口。
     *
     * <p><b>网页版接口的两个实测要点</b>（缺一不可，否则静默返回 HTML 页面）：
     * <ol>
     *   <li>{@code Accept} 必须<b>精确等于</b> {@code application/json}。
     *       写成 {@code application/json, text/plain, &ast;/&ast;} 或 {@code &ast;/&ast;}
     *       都会返回整页 HTML，然后被当成「不是 JSON」而失败；</li>
     *   <li>User-Agent 必须是常规浏览器 UA。</li>
     * </ol>
     *
     * <p><b>返回结构与 GitHub 完全不同</b>（Gitee 私有格式，非 REST）：
     * {@code {"releases":[{"tag":{"name":"1.0.0-RELEASE","message":"更新说明"},
     * "release":{"title":"...","path":"/owner/repo/releases/tag/1.0.0-RELEASE",
     * "is_prerelease":false},"attach_files":[]}]}}
     * —— 版本号在 {@code tag.name}，更新说明在 {@code tag.message}，
     * {@code attach_files} 为空表示没传附件（Gitee 也不提供 {@code zip_download_url} 之外的安装包）。
     * 网页版没有 {@code tags} 接口（{@code /tags} 对非浏览器请求返回 405），故只有这一级。
     */
    private static Candidate fetchGitee(HttpClient client, String currentVersion) throws Exception {
        String repo = Endpoints.giteeRepo();
        String url = Source.GITEE.webBase(repo) + "/releases?per_page=100";
        HttpResponse<String> resp = send(client, url, GITEE_BROWSER_UA, "application/json");
        if (resp.statusCode() != 200) {
            throw new IOException(Source.GITEE.label() + " 响应异常 (HTTP " + resp.statusCode() + ")"
                    + statusDetail(resp));
        }
        JsonObject root = asObject(resp.body(), Source.GITEE.label());
        if (!root.has("releases") || !root.get("releases").isJsonArray()) {
            // 常见的失败形态：Accept 没写对，拿回整页 HTML（asObject 会先报「不是 JSON 对象」）
            throw new IOException(Source.GITEE.label() + " 响应缺少 releases 数组");
        }
        Candidate c = bestGiteeRelease(repo, root.getAsJsonArray("releases"));
        if (c == null) {
            throw new IOException(Source.GITEE.label() + " 仓库没有任何版本号形式的发行版");
        }
        return c;
    }

    /** 解析 GitHub 单个发行版对象；标签名不是版本号形式时返回 null（交给下一级降级处理） */
    private static Candidate parseGitHubRelease(Source source, String repo, JsonObject rel) {
        String tag = string(rel, "tag_name");
        if (!isVersionLike(tag)) return null;
        String page = string(rel, "html_url");
        if (isBlank(page)) page = source.releasePageUrl(repo, tag.trim());
        return new Candidate(
                stripV(tag),
                string(rel, "body"),
                findInstallerAsset(rel),   // 无 .exe 附件时为空：不可原地替换，只给发行版页面
                "",                        // 降级来源不提供 md5，跳过完整性校验
                false,                     // 降级来源无法表达强制更新
                page,
                source);
    }

    /** 在 GitHub 发行版列表里取版本号最高者（不依赖服务端排序） */
    private static Candidate bestGitHubRelease(Source source, String repo, JsonArray arr) {
        Candidate best = null;
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            Candidate c = parseGitHubRelease(source, repo, el.getAsJsonObject());
            if (c == null) continue;
            if (best == null || compareVersions(c.version, best.version) > 0) best = c;
        }
        return best;
    }

    /**
     * 解析 Gitee 网页版发行版；标签名不是版本号形式时返回 null。
     * 版本号取 {@code tag.name}（实测就是 {@code 1.0.0-RELEASE} 的裸版本号，不带宽高前缀），
     * 更新说明取 {@code tag.message}（Gitee 把标签说明当发行版说明），
     * 安装包直链取 {@code attach_files} 里第一个 {@code .exe}（没传附件就为空）。
     */
    private static Candidate parseGiteeRelease(String repo, JsonObject rel) {
        if (!rel.has("tag") || !rel.get("tag").isJsonObject()) return null;
        JsonObject tag = rel.getAsJsonObject("tag");
        String name = string(tag, "name");
        if (!isVersionLike(name)) return null;

        String title = "";
        String page = "";
        if (rel.has("release") && rel.get("release").isJsonObject()) {
            JsonObject release = rel.getAsJsonObject("release");
            title = nullToEmpty(string(release, "title"));
            String path = string(release, "path");
            if (!isBlank(path)) {
                page = path.startsWith("http") ? path : "https://gitee.com" + path;
            }
        }
        if (isBlank(page)) page = Source.GITEE.releasePageUrl(repo, name.trim());

        // 说明优先用标签说明（内容更全），没有才退回发行版标题
        String notes = string(tag, "message");
        if (isBlank(notes)) notes = title;

        return new Candidate(
                stripV(name),
                notes,
                findGiteeInstallerAsset(rel),
                "",     // 降级来源不提供 md5
                false,  // 降级来源无法表达强制更新
                page,
                Source.GITEE);
    }

    /** 在 Gitee 发行版列表里取版本号最高者 */
    private static Candidate bestGiteeRelease(String repo, JsonArray arr) {
        Candidate best = null;
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            Candidate c = parseGiteeRelease(repo, el.getAsJsonObject());
            if (c == null) continue;
            if (best == null || compareVersions(c.version, best.version) > 0) best = c;
        }
        return best;
    }

    /**
     * 从 Gitee 的 {@code attach_files} 里挑 {@code .exe} 直链。
     * Gitee 附件字段名与 GitHub 不同（{@code browser_download_url} → {@code download_url}），
     * 故单独解析；字段名可能随 Gitee 改版变化，故用「取第一个形似 URL 的字符串值」兜底。
     */
    private static String findGiteeInstallerAsset(JsonObject rel) {
        if (!rel.has("attach_files") || !rel.get("attach_files").isJsonArray()) return null;
        String fallback = null;
        for (JsonElement el : rel.getAsJsonArray("attach_files")) {
            if (!el.isJsonObject()) continue;
            JsonObject file = el.getAsJsonObject();
            String name = string(file, "name");
            String url = string(file, "download_url");
            if (isBlank(url)) url = string(file, "browser_download_url");
            if (isBlank(url)) url = string(file, "url");
            if (isBlank(name) || isBlank(url)) continue;
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".exe")) continue;
            if (lower.contains("starlight")) return url.trim();
            if (fallback == null) fallback = url.trim();
        }
        return fallback;
    }

    /** 在标签列表里取版本号最高者；都没有版本号形式的标签时返回 null */
    private static Candidate bestTag(Source source, String repo, JsonArray arr) {
        String best = null;
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            String name = string(el.getAsJsonObject(), "name");
            if (!isVersionLike(name)) continue;
            if (best == null || compareVersions(name, best) > 0) best = name;
        }
        if (best == null) return null;
        return new Candidate(stripV(best), "", "", "", false,
                source.releasePageUrl(repo, best.trim()), source);
    }

    /**
     * 从发行版附件里挑可直接替换启动器的安装包直链。
     * 只认 {@code .exe}（启动器自更新靠原地替换 exe，zip/源码包会破坏替换逻辑）；
     * 多个 exe 时优先文件名含 starlight 的那个，否则取第一个。
     */
    private static String findInstallerAsset(JsonObject rel) {
        if (!rel.has("assets") || !rel.get("assets").isJsonArray()) return null;
        String fallback = null;
        for (JsonElement el : rel.getAsJsonArray("assets")) {
            if (!el.isJsonObject()) continue;
            JsonObject asset = el.getAsJsonObject();
            String name = string(asset, "name");
            String url = string(asset, "browser_download_url");
            if (isBlank(name) || isBlank(url)) continue;
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".exe")) continue;
            if (lower.contains("starlight")) return url.trim();
            if (fallback == null) fallback = url.trim();
        }
        return fallback;
    }

    // ==================== HTTP 与 JSON 工具 ====================

    /** 发一次 GET（带 User-Agent 与耗时日志），网络层异常原样抛出 */
    private static HttpResponse<String> send(HttpClient client, String url, String userAgent)
            throws IOException, InterruptedException {
        return send(client, url, userAgent, "application/json");
    }

    /**
     * 发一次 GET（可指定 User-Agent 与 Accept），网络层异常原样抛出。
     *
     * @param userAgent 请求方标识：GitHub 用 {@code StarlightLauncher/<版本>}，
     *                  Gitee 必须用常规浏览器 UA（见 {@link #GITEE_BROWSER_UA}）
     * @param accept    Accept 头：Gitee 网页版接口必须精确为 {@code application/json}，
     *                  写成 {@code application/json, text/plain, &ast;/&ast;} 之类会返回整页 HTML
     */
    private static HttpResponse<String> send(HttpClient client, String url, String userAgent, String accept)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", userAgent)
                .header("Accept", accept)
                .GET()
                .build();
        long start = System.currentTimeMillis();
        DebugLog.http(true, "GET", url, 0, 0, null, -1);
        try {
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            DebugLog.http(false, null, url, resp.statusCode(), resp.body().length(), resp.body(),
                    System.currentTimeMillis() - start);
            return resp;
        } catch (IOException | InterruptedException ex) {
            DebugLog.http(false, null, url, 0, 0, "请求失败: " + describe(ex),
                    System.currentTimeMillis() - start);
            throw ex;
        }
    }

    /** 非 200 时尽量带上服务端说明（官方/Gitee 用 error 字段，GitHub 用 message 字段） */
    private static String statusDetail(HttpResponse<String> resp) {
        String body = resp.body();
        if (isBlank(body)) return "";
        try {
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            String msg = string(obj, "error");
            if (isBlank(msg)) msg = string(obj, "message");
            return isBlank(msg) ? "" : "：" + msg;
        } catch (Exception ignored) {
            return "";   // 非 JSON 响应（如服务器默认错误页）
        }
    }

    private static JsonObject asObject(String body, String who) throws IOException {
        JsonElement el = parse(body, who);
        if (!el.isJsonObject()) throw new IOException(who + " 返回的不是 JSON 对象");
        return el.getAsJsonObject();
    }

    private static JsonArray asArray(String body, String who) throws IOException {
        JsonElement el = parse(body, who);
        if (!el.isJsonArray()) throw new IOException(who + " 返回的不是 JSON 数组");
        return el.getAsJsonArray();
    }

    private static JsonElement parse(String body, String who) throws IOException {
        if (isBlank(body)) throw new IOException(who + " 返回内容为空");
        // 站点防护（WAF / JS 跳转挑战）拦截时会以 200 返回一个 HTML 页面，
        // 明确点出这种情况，免得排查时只看到「无法解析为 JSON」而不知道是被拦了
        if (body.stripLeading().startsWith("<")) {
            throw new IOException(who + " 返回的是网页（HTML）而不是 JSON，疑似被站点防护 / WAF 拦截");
        }
        try {
            return JsonParser.parseString(body);
        } catch (Exception ex) {
            throw new IOException(who + " 返回的数据无法解析为 JSON", ex);
        }
    }

    /** 取字符串字段；缺失/null/非原始类型一律返回 null */
    private static String string(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) return null;
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) return null;
        return el.getAsString();
    }

    /** 取布尔字段；缺失/null/不可解析一律 false */
    private static boolean bool(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) return false;
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) return false;
        try {
            return el.getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 标签名是否形如版本号（滤掉 release-2026-06 之类的非版本标签） */
    private static boolean isVersionLike(String name) {
        return name != null && VERSION_LIKE.matcher(name.trim()).matches();
    }

    /** 去掉版本号前的 v/V 前缀（界面展示时再统一补回） */
    private static String stripV(String version) {
        String v = version == null ? "" : version.trim();
        if (v.length() > 1 && (v.charAt(0) == 'v' || v.charAt(0) == 'V')
                && Character.isDigit(v.charAt(1))) {
            return v.substring(1);
        }
        return v;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** null → 空串（拼接展示文案时用，避免出现字面量 "null"） */
    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String describe(Throwable t) {
        String msg = t == null ? null : t.getMessage();
        return isBlank(msg) ? (t == null ? "未知错误" : t.getClass().getSimpleName()) : msg;
    }

    private static UpdateResult toResult(Candidate c) {
        // latestTag 仅用于界面显示（补 v 前缀）
        String tag = c.version != null && !c.version.startsWith("v") ? "v" + c.version : c.version;
        return new UpdateResult(tag, c.version, c.notes, c.downloadUrl, c.md5,
                c.forceUpdate, null, c.source, c.pageUrl);
    }

    private static UpdateResult failure(String message) {
        return new UpdateResult(null, null, "", "", "", false, message, null, "");
    }

    /**
     * 版本号比较：先比数字段（MAJOR.MINOR.PATCH，短的一方缺位补 0），
     * 数字段相同再比发布阶段后缀，后缀相同再比后缀里的数字。
     *
     * <p>后缀顺序（与 {@code AppConfig} 里声明的版本号规则一致）：
     * {@code SNAPSHOT < ALPHA < BETA < RC < RELEASE}。
     * 不带后缀与 {@code RELEASE}/{@code FINAL}/{@code GA} 都视为正式发布版（最高档）；
     * 未登记的后缀也按正式版处理（保持与旧行为一致，不影响既有版本号）。
     *
     * <p>两个要点：
     * <ul>
     *   <li>后缀里的数字会被排除在版本数字段之外——{@code 2.0.0-rc1} 的 {@code 1} 是「第 1 个候选版」，
     *       不是第 4 位版本号（旧实现会把它算进去，导致 {@code 2.0.0-beta} 被判成比 {@code 2.0.0-rc1} 新）；</li>
     *   <li>所以 {@code 2.0.0-RC → 2.0.0-RELEASE} 这类「同号转正」能正确识别为需要更新。</li>
     * </ul>
     *
     * <p>无法识别为「数字版本号」的字符串按最低档处理，不会误判成比正式版更新。
     */
    public static int compareVersions(String v1, String v2) {
        Version a = Version.parse(v1);
        Version b = Version.parse(v2);
        int cmp = Version.compareNumbers(a, b);
        if (cmp != 0) return cmp;
        cmp = Integer.compare(a.stage, b.stage);
        if (cmp != 0) return cmp;
        return Integer.compare(a.stageNumber, b.stageNumber);
    }

    /** 拆解后的版本号：数字段 + 发布阶段 + 阶段序号 */
    private static final class Version {
        final int[] numbers;
        final int stage;
        final int stageNumber;

        Version(int[] numbers, int stage, int stageNumber) {
            this.numbers = numbers;
            this.stage = stage;
            this.stageNumber = stageNumber;
        }

        static Version parse(String raw) {
            Matcher m = VERSION_PARTS.matcher(raw == null ? "" : raw.trim());
            if (!m.matches()) {
                // 不是「数字版本号」（如 nightly）：按最低档处理，避免误判为比正式版更新
                return new Version(new int[0], STAGE_SNAPSHOT, 0);
            }
            String[] parts = m.group(1).split("\\.");
            int[] nums = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                nums[i] = toInt(parts[i]);
            }
            String qualifier = m.group(2);
            return new Version(nums, stageOf(qualifier), stageNumberOf(qualifier));
        }

        static int compareNumbers(Version a, Version b) {
            int len = Math.max(a.numbers.length, b.numbers.length);
            for (int i = 0; i < len; i++) {
                int x = i < a.numbers.length ? a.numbers[i] : 0;
                int y = i < b.numbers.length ? b.numbers[i] : 0;
                if (x != y) return Integer.compare(x, y);
            }
            return 0;
        }

        /** 后缀 → 发布阶段；空后缀与未登记后缀都算正式版 */
        private static int stageOf(String qualifier) {
            String name = qualifierName(qualifier);
            if (name.isEmpty()) return STAGE_FINAL;
            if (name.startsWith("SNAPSHOT")) return STAGE_SNAPSHOT;
            if (name.startsWith("ALPHA")) return STAGE_ALPHA;
            if (name.startsWith("BETA")) return STAGE_BETA;
            if (name.startsWith("RC") || name.startsWith("CR")) return STAGE_RC;
            return STAGE_FINAL;   // RELEASE / FINAL / GA / 其它未登记后缀
        }

        /** 只取后缀开头的字母做阶段识别：{@code rc2-2} → {@code RC} */
        private static String qualifierName(String qualifier) {
            if (qualifier == null) return "";
            StringBuilder sb = new StringBuilder();
            for (char c : qualifier.toCharArray()) {
                if (Character.isLetter(c)) sb.append(Character.toUpperCase(c));
                else if (sb.length() > 0) break;
            }
            return sb.toString();
        }

        /** 后缀里第一个数字：{@code rc2} → 2，{@code beta} → 0 */
        private static int stageNumberOf(String qualifier) {
            if (qualifier == null) return 0;
            Matcher m = QUALIFIER_NUMBER.matcher(qualifier);
            return m.find() ? toInt(m.group(1)) : 0;
        }

        /**
         * 数字段解析：超过 int 上限的一律钳到 {@link Integer#MAX_VALUE}（保持「数字大就是新」的单调性），
         * 非数字内容按 0。无论输入多离谱都不抛异常。
         */
        private static int toInt(String s) {
            String t = s == null ? "" : s.trim();
            if (t.isEmpty()) return 0;
            try {
                long v = Long.parseLong(t);
                if (v > Integer.MAX_VALUE) return Integer.MAX_VALUE;
                return v < 0 ? 0 : (int) v;
            } catch (NumberFormatException overflow) {
                // 位数多到连 long 都装不下：按极大值处理，而不是 0
                // （否则 99999999999999999999.0.0 会被判成比 1.0.0 还旧，把顺序搞反）
                boolean allDigits = true;
                for (int i = 0; i < t.length(); i++) {
                    if (!Character.isDigit(t.charAt(i))) { allDigits = false; break; }
                }
                return allDigits ? Integer.MAX_VALUE : 0;
            }
        }
    }
}
