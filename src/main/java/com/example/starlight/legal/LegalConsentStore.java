package com.example.starlight.legal;

import com.example.starlight.config.AppDirs;
import com.example.starlight.newui.AppConfig;
import com.example.starlight.util.I18N;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * 用户对法律文档的同意记录（本机留存，作为「同意过哪一版」的可核查凭证）。
 *
 * <h3>存什么</h3>
 * <ul>
 *   <li>{@code consent.json}：最近一次有效同意 —— 同意时间（含时区偏移）、启动器版本、
 *       三份文档各自的标题 / SHA-256 / 字节数、界面语言、操作系统名称、随机记录号；</li>
 *   <li>{@code consent-history.jsonl}：追加式台账，每次同意追加一行，条款更新后重新同意时
 *       旧记录不会被覆盖（证明链连续）。</li>
 * </ul>
 *
 * <h3>不存什么</h3>
 * <p><b>不记录任何可直接识别个人身份的信息</b>：没有用户名、微软账号 UUID、邮箱、IP、
 * 机器码或主机名。记录只回答「这台电脑上的这个启动器在何时同意了哪一版文档」，
 * 且<b>只写入本机</b>（{@code ~/.starlight/legal/}，便携模式在工作目录），不上传。
 * 这与隐私政策「不收集个人信息」的红线一致，详见 docs/隐私政策.md 第三节。
 *
 * <h3>有效性判定</h3>
 * <p>记录的每份文档哈希都必须与当前打包进 classpath 的文档哈希一致；任一份被修订，
 * {@link #isAccepted()} 立即返回 false，用户下次启动需要重新阅读并确认。
 */
public final class LegalConsentStore {

    private static final Logger log = LoggerFactory.getLogger(LegalConsentStore.class);

    /** 记录格式版本：字段增减时 +1，旧记录自动失效（宁可让用户再确认一次） */
    public static final int SCHEMA_VERSION = 1;

    /** 最近一次同意记录 */
    public static final String FILE_NAME = "consent.json";
    /** 追加式同意台账（每行一条 JSON） */
    public static final String HISTORY_FILE_NAME = "consent-history.jsonl";

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson COMPACT = new GsonBuilder().create();

    /** 同意机制标识：写进记录，说明「不是点一下继续就算」，而是主动勾选后确认 */
    private static final String MECHANISM = "explicit-checkbox";

    private LegalConsentStore() {
    }

    /** 记录目录（与本机其它配置同根，随 --portable 一起搬家） */
    public static Path dir() {
        return AppDirs.root().resolve("legal");
    }

    public static Path consentFile() {
        return dir().resolve(FILE_NAME);
    }

    public static Path historyFile() {
        return dir().resolve(HISTORY_FILE_NAME);
    }

    /**
     * 当前用户是否已同意「现在这一版」的三份文档。
     *
     * <p>读不到 / 解析失败 / 格式版本不符 / 任一文档哈希不符，一律视为未同意。
     */
    public static boolean isAccepted() {
        JsonObject record = readRecord();
        if (record == null) return false;
        try {
            JsonElement schema = record.get("schema");
            if (schema == null || schema.getAsInt() != SCHEMA_VERSION) return false;
            JsonObject documents = record.getAsJsonObject("documents");
            if (documents == null) return false;
            for (LegalDocument doc : LegalDocument.values()) {
                JsonElement entry = documents.get(doc.id());
                if (entry == null || !entry.isJsonObject()) return false;
                JsonElement hash = entry.getAsJsonObject().get("sha256");
                if (hash == null || !doc.sha256().equals(hash.getAsString())) return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("同意记录解析失败，按未同意处理: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 记录一次同意（主动勾选 + 点击确认后调用）。
     *
     * @return true=已成功落盘；false=写入失败（调用方应提示，但不应因此阻断启动器本身，
     *         下次启动会再次弹出同意页）
     */
    public static boolean accept() {
        try {
            Files.createDirectories(dir());
            JsonObject record = buildRecord();
            String json = PRETTY.toJson(record);

            // 先写临时文件再原子替换：避免写一半断电留下半截 JSON（下次启动被误判为未同意）
            Path tmp = dir().resolve(FILE_NAME + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, consentFile(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicUnsupported) {
                Files.move(tmp, consentFile(), StandardCopyOption.REPLACE_EXISTING);
            }

            // 追加台账：失败不影响主记录（主记录已可证明本次同意）
            try {
                Files.writeString(historyFile(), COMPACT.toJson(record) + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception e) {
                log.warn("同意台账追加失败（不影响 consent.json）: {}", e.getMessage());
            }
            log.info("已记录法律文档同意: {} ({} 字节)", consentFile(), json.length());
            return true;
        } catch (Exception e) {
            log.error("写入法律文档同意记录失败: {}", e.getMessage());
            return false;
        }
    }

    /** 最近一次同意记录（供「设置 → 关于 → 同意记录」展示）；没有记录时返回空 */
    public static Optional<ConsentSummary> current() {
        JsonObject record = readRecord();
        if (record == null) return Optional.empty();
        try {
            String acceptedAt = optString(record, "acceptedAt");
            String version = optString(record, "launcherVersion");
            String recordId = optString(record, "recordId");
            if (acceptedAt.isEmpty()) return Optional.empty();
            return Optional.of(new ConsentSummary(acceptedAt, version, recordId));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * 同意记录摘要（只含展示需要的字段）。
     *
     * @param acceptedAt      同意时间（ISO-8601，带时区偏移）
     * @param launcherVersion 当时的启动器版本
     * @param recordId        随机记录号（与本机 consent-history.jsonl 对应）
     */
    public record ConsentSummary(String acceptedAt, String launcherVersion, String recordId) {
    }

    // 内部 //

    /** 组装要落盘的记录 */
    private static JsonObject buildRecord() {
        JsonObject record = new JsonObject();
        record.addProperty("schema", SCHEMA_VERSION);
        record.addProperty("recordId", UUID.randomUUID().toString());
        // 带偏移量：用户可能在任意时区，保留本地时间 + 偏移比只存 UTC 更能说明「当时」
        record.addProperty("acceptedAt",
                OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        record.addProperty("launcherVersion", AppConfig.APP_VERSION);
        record.addProperty("uiLanguage", uiLanguage());
        record.addProperty("os", operatingSystem());
        record.addProperty("mechanism", MECHANISM);

        JsonObject documents = new JsonObject();
        for (LegalDocument doc : LegalDocument.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("title", doc.title());
            entry.addProperty("sha256", doc.sha256());
            entry.addProperty("bytes", doc.byteSize());
            documents.add(doc.id(), entry);
        }
        record.add("documents", documents);
        return record;
    }

    /** 读 consent.json；文件不存在或损坏返回 null（损坏时不删除，保留现场便于排查） */
    private static JsonObject readRecord() {
        Path file = consentFile();
        if (!Files.isRegularFile(file)) return null;
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(json);
            return root.isJsonObject() ? root.getAsJsonObject() : null;
        } catch (Exception e) {
            log.warn("读取同意记录失败: {}", e.getMessage());
            return null;
        }
    }

    /** 界面语言（与配置键 Language 的取值一致） */
    private static String uiLanguage() {
        try {
            return I18N.getCurrentLocale().toString();
        } catch (Exception ignored) {
            return "zh_CN";
        }
    }

    /** 操作系统名称 / 版本 / 架构（不含主机名、用户名等可识别信息） */
    private static String operatingSystem() {
        String name = System.getProperty("os.name", "unknown");
        String version = System.getProperty("os.version", "");
        String arch = System.getProperty("os.arch", "");
        return String.format(Locale.ROOT, "%s %s %s", name, version, arch).trim();
    }

    private static String optString(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }
}
