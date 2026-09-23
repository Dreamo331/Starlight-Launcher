package com.example.starlight.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 游戏目录 / 版本隔离目录的初始化工具（参考 PCL2 的目录初始化与版本隔离策略）。
 *
 * <p>职责只有一件事：按需创建缺失的标准子目录与 {@code launcher_profiles.json}，
 * 已有内容一律不动（幂等）。所有 IO 异常都被吞掉并汇总到返回值里，
 * 调用方（添加目录、安装版本）不应因为初始化失败而中断主流程。
 */
public final class GameDirInitializer {

    /** 游戏根目录的标准子目录骨架 */
    private static final String[] GAME_DIR_SUBDIRS = {
            "versions", "mods", "saves", "config", "resourcepacks", "screenshots", "logs"
    };

    /** 开启版本隔离时，每个版本目录内的隔离子目录 */
    private static final String[] VERSION_ISOLATED_SUBDIRS = {
            "mods", "saves", "config", "resourcepacks", "screenshots", "logs"
    };

    private GameDirInitializer() {}

    /** launcher_profiles.json 的处理结果 */
    public enum ProfilesStatus {
        /** 文件存在且结构合法，不动它 */
        VALID,
        /** 文件缺失或不合法，已重新生成 */
        REGENERATED,
        /** 生成失败（如无写权限） */
        FAILED
    }

    /** 初始化结果：created = 本次实际创建的目录数；filesCreated = 本次创建的文件数；errors = 创建失败的条目描述 */
    public record InitResult(int created, int filesCreated, List<String> errors) {
        public boolean ok() { return errors.isEmpty(); }
        /** 供 toast / 日志使用的一句话摘要 */
        public String summary() {
            if (ok()) {
                if (created == 0 && filesCreated == 0) return "目录已完整，无需初始化";
                return "已创建 " + created + " 个目录" + (filesCreated > 0 ? "、" + filesCreated + " 个配置文件" : "");
            }
            return "部分目录初始化失败：" + String.join("、", errors);
        }
    }

    /**
     * 初始化 .minecraft 目录骨架（versions/mods/saves/config/resourcepacks/screenshots/logs），
     * 并在缺失时生成 {@code launcher_profiles.json}。目录本身不存在时也会被创建。
     */
    public static InitResult initializeGameDir(Path gameDir) {
        if (gameDir == null) return new InitResult(0, 0, List.of("路径为空"));
        InitResult dirs = createMissingDirs(gameDir, GAME_DIR_SUBDIRS);
        int files = ensureLauncherProfiles(gameDir) == ProfilesStatus.REGENERATED ? 1 : 0;
        return new InitResult(dirs.created(), files, dirs.errors());
    }

    /**
     * 为某个版本预建隔离目录树（versions/&lt;dirName&gt;/{mods,saves,config,...}）。
     * 仅当版本目录名合法时执行；调用方需自行确认版本隔离开关已开启。
     */
    public static InitResult initializeVersionIsolation(Path gameDir, String versionDirName) {
        if (gameDir == null || versionDirName == null || versionDirName.isBlank()) {
            return new InitResult(0, 0, List.of("版本目录名为空"));
        }
        // 与下载页 folderName 校验保持一致：含非法字符的目录名不应落盘
        if (versionDirName.matches(".*[\\\\/:*?\"<>|].*")) {
            return new InitResult(0, 0, List.of("版本目录名含非法字符: " + versionDirName));
        }
        return createMissingDirs(gameDir.resolve("versions").resolve(versionDirName),
                VERSION_ISOLATED_SUBDIRS);
    }

    /** 当前游戏目录是否已有完整骨架（versions 存在即视为初始化过，避免重复扫盘） */
    public static boolean isInitialized(Path gameDir) {
        return gameDir != null && Files.isDirectory(gameDir.resolve("versions"));
    }

    /**
     * 确保游戏根目录存在<b>合法的</b> {@code launcher_profiles.json}：
     * 存在且结构合法时原样保留（官方启动器等工具写入的内容不被覆盖）；
     * 缺失或内容损坏/字段不全时重新生成。
     */
    public static ProfilesStatus ensureLauncherProfiles(Path gameDir) {
        if (gameDir == null) return ProfilesStatus.FAILED;
        if (isLauncherProfilesValid(gameDir)) return ProfilesStatus.VALID;
        try {
            Files.createDirectories(gameDir);
            Files.writeString(gameDir.resolve("launcher_profiles.json"), buildLauncherProfilesJson());
            return ProfilesStatus.REGENERATED;
        } catch (IOException e) {
            return ProfilesStatus.FAILED;
        }
    }

    /**
     * 切换目录 / 启动器启动后的健康检测：目录未初始化则补齐骨架（含 profiles 文件），
     * 已初始化则只校验并修复 {@code launcher_profiles.json}。
     */
    public static InitResult detectAndFix(Path gameDir) {
        if (gameDir == null) return new InitResult(0, 0, List.of("路径为空"));
        if (!isInitialized(gameDir)) return initializeGameDir(gameDir);
        int files = 0;
        List<String> errors = new ArrayList<>();
        switch (ensureLauncherProfiles(gameDir)) {
            case REGENERATED -> files = 1;
            case FAILED -> errors.add("launcher_profiles.json");
            default -> { }
        }
        return new InitResult(0, files, List.copyOf(errors));
    }

    /**
     * {@code launcher_profiles.json} 是否存在且结构合法：
     * v3 骨架的必备字段（authenticationDatabase / clientToken / launcherVersion /
     * profiles / selectedProfile / settings / version）齐全即视为合法，字段值不做强校验。
     */
    public static boolean isLauncherProfilesValid(Path gameDir) {
        if (gameDir == null) return false;
        Path file = gameDir.resolve("launcher_profiles.json");
        if (!Files.isRegularFile(file)) return false;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (!hasObject(root, "authenticationDatabase")) return false;
            if (!hasObject(root, "profiles") || !hasObject(root, "settings")) return false;
            if (!root.has("clientToken") || !root.get("clientToken").isJsonPrimitive()) return false;
            if (root.get("clientToken").getAsString().isBlank()) return false;
            if (!root.has("selectedProfile") || !root.get("selectedProfile").isJsonPrimitive()) return false;
            if (!root.has("launcherVersion")) return false;
            JsonObject launcherVersion = root.getAsJsonObject("launcherVersion");
            if (launcherVersion == null || !launcherVersion.has("name") || !launcherVersion.has("format")) return false;
            return root.has("version") && root.get("version").getAsInt() == 3;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean hasObject(JsonObject root, String key) {
        return root.has(key) && root.get(key).isJsonObject();
    }

    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 按官方 launcher_profiles.json v3 结构生成默认内容（对齐 PCL2 的初始化产物） */
    private static String buildLauncherProfilesJson() {
        JsonObject settings = new JsonObject();
        settings.addProperty("enableSnapshots", false);
        settings.addProperty("enableAdvanced", false);
        settings.addProperty("keepLauncherOpen", false);
        settings.addProperty("showGameLog", false);
        settings.addProperty("showMenu", false);
        settings.addProperty("soundOn", false);
        settings.addProperty("locale", "zh_CN");
        settings.addProperty("profileSorting", "ByLastPlayed");
        settings.addProperty("crashAssistance", true);

        JsonObject profile = new JsonObject();
        profile.addProperty("icon", "Star");
        profile.addProperty("name", "Starlight");
        profile.addProperty("lastVersionId", "latest-release");
        profile.addProperty("type", "latest-release");
        profile.addProperty("lastUsed", java.time.OffsetDateTime
                .now(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.0000'Z'")));

        JsonObject profiles = new JsonObject();
        profiles.add("Starlight", profile);

        JsonObject root = new JsonObject();
        root.add("authenticationDatabase", new JsonObject());
        // 32 位十六进制 clientToken，格式与官方启动器一致
        root.addProperty("clientToken", UUID.randomUUID().toString().replace("-", ""));
        JsonObject launcherVersion = new JsonObject();
        launcherVersion.addProperty("name", "StarlightLauncher");
        launcherVersion.addProperty("format", 21);
        root.add("launcherVersion", launcherVersion);
        root.add("profiles", profiles);
        root.addProperty("selectedProfile", "Starlight");
        root.add("settings", settings);
        root.addProperty("version", 3);
        return PRETTY_GSON.toJson(root);
    }

    /** 尽力而为地创建 root + 各子目录，失败的条目记入 errors */
    private static InitResult createMissingDirs(Path root, String[] subdirs) {
        List<String> errors = new ArrayList<>();
        int created = 0;
        if (!Files.isDirectory(root) && mkdir(root, errors)) created++;
        for (String sub : subdirs) {
            Path dir = root.resolve(sub);
            if (Files.isDirectory(dir)) continue;
            if (mkdir(dir, errors)) created++;
        }
        return new InitResult(created, 0, List.copyOf(errors));
    }

    private static boolean mkdir(Path dir, List<String> errors) {
        try {
            Files.createDirectories(dir);
            return true;
        } catch (IOException e) {
            errors.add(dir.getFileName() + " (" + e.getMessage() + ")");
            return false;
        }
    }
}
