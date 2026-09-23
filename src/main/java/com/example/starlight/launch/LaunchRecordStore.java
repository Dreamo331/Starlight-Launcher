package com.example.starlight.launch;

import com.example.starlight.gui.UIGeneralControlClass;
import com.example.starlight.gui.UIGeneralControlClass.LaunchConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 「最近游玩」启动记录持久化（从 LauncherView 抽离，逻辑与原实现保持一致）。
 *
 * <p>记录文件固定为 {@code Starlight-Launcher/launcher.json}，最多保留 3 条，
 * 新的在最前；同「游戏目录 + 版本」重复时只更新启动时间与参数快照。
 *
 * <p>本类不依赖 JavaFX 与主界面状态，可在任意线程调用（写入失败静默忽略，与原行为一致）。
 */
public final class LaunchRecordStore {

    private LaunchRecordStore() {
    }

    private static final String RECORDS_FILE = "Starlight-Launcher/launcher.json";

    public static void save(LaunchConfig config, String loaderType) {
        try {
            java.util.List<LaunchRecord> records = load();
            String now = java.time.LocalDateTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

            // 查找是否已有同游戏目录+同版本的记录
            LaunchRecord existing = null;
            for (LaunchRecord r : records) {
                if (r.version.equals(config.version) && r.gameDir.equals(config.gameDir)) {
                    existing = r;
                    break;
                }
            }

            if (existing != null) {
                // 更新启动时间，移到顶部
                existing.launchTime = now;
                existing.loaderType = loaderType;
                existing.javaPath = config.javaPath;
                existing.maxMemory = config.maxMemory;
                existing.minMemory = config.minMemory;
                existing.windowWidth = config.windowWidth;
                existing.windowHeight = config.windowHeight;
                existing.fullscreen = config.fullscreen;
                existing.versionIsolation = config.versionIsolation;
                existing.jvmArgs = config.jvmArgs;
                existing.gameArgs = config.gameArgs;
                existing.preLaunchCommand = config.preLaunchCommand;
                existing.postExitCommand = config.postExitCommand;
                records.remove(existing);
                records.add(0, existing);
            } else {
                // 新建记录
                LaunchRecord rec = new LaunchRecord();
                rec.version = config.version;
                rec.gameDir = config.gameDir;
                rec.loaderType = loaderType;
                rec.launchTime = now;
                rec.javaPath = config.javaPath;
                rec.maxMemory = config.maxMemory;
                rec.minMemory = config.minMemory;
                rec.windowWidth = config.windowWidth;
                rec.windowHeight = config.windowHeight;
                rec.fullscreen = config.fullscreen;
                rec.versionIsolation = config.versionIsolation;
                rec.jvmArgs = config.jvmArgs;
                rec.gameArgs = config.gameArgs;
                rec.preLaunchCommand = config.preLaunchCommand;
                rec.postExitCommand = config.postExitCommand;
                records.add(0, rec);
            }

            while (records.size() > 3) records.remove(records.size() - 1);
            persist(records);
        } catch (Exception ignored) {}
    }

    public static java.util.List<LaunchRecord> load() {
        try {
            Path path = Paths.get(RECORDS_FILE);
            if (!Files.exists(path)) return new ArrayList<>();
            String json = Files.readString(path);
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            java.util.List<LaunchRecord> records = new ArrayList<>();
            for (var el : arr) {
                records.add(new Gson().fromJson(el, LaunchRecord.class));
            }
            return records;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** 将启动记录列表写回 launcher.json */
    public static void persist(java.util.List<LaunchRecord> records) {
        try {
            Path path = Paths.get(RECORDS_FILE);
            Files.createDirectories(path.getParent());
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(records);
            Files.writeString(path, json);
        } catch (Exception ignored) {}
    }

    /** 删除符合条件的启动记录（如删除版本时同步清理最近游玩）并写回 */
    public static void removeIf(java.util.function.Predicate<LaunchRecord> filter) {
        java.util.List<LaunchRecord> records = load();
        if (records.removeIf(filter)) {
            persist(records);
        }
    }

    /** 启动记录对应的版本目录是否仍存在（版本被删除后该记录即为失效） */
    public static boolean versionExists(LaunchRecord rec) {
        try {
            if (rec.gameDir == null || rec.gameDir.isEmpty()
                    || rec.version == null || rec.version.isEmpty()) return false;
            return Files.isDirectory(Paths.get(rec.gameDir, "versions", rec.version));
        } catch (Exception e) {
            return false;
        }
    }

    public static LaunchConfig toLaunchConfig(LaunchRecord rec) {
        // 按「记录自身的版本」合并独立配置（config.overrides.json）：
        // 修复——过去以当前选中版本为基准，从「最近游玩」启动其他版本时会误用选中版本的独立设置；
        // 内存/JVM参数/窗口/全屏/版本隔离/游戏参数等仍始终跟随最新配置，不读记录里的旧快照
        LaunchConfig config = UIGeneralControlClass.getLaunchConfigFor(rec.gameDir, rec.version);
        if (UIGeneralControlClass.isLoggedIn()) {
            UIGeneralControlClass.applyCurrentAccount(config);
        }
        return config;
    }
}
