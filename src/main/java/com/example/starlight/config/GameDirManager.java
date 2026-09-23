/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.config;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 游戏目录（多目录）管理器：把「启动器使用的 .minecraft 目录」从单一配置项扩展为可管理的目录列表。
 *
 * <p>持久化格式（starlight.ini 的 [Launcher] 段，均为 {@code |} 分隔）：
 * <pre>
 * GameDir     = D:\mc\one\.minecraft                      ; 当前使用的目录（始终是列表中的一项）
 * GameFolders = D:\mc\one\.minecraft|E:\mc\two\.minecraft  ; 目录列表（沿用旧版键，兼容旧配置）
 * GameDirNames= 主目录|测试目录                             ; 显示名称，与 GameFolders 顺序一一对应
 * </pre>
 *
 * <p>兼容性：旧配置只有 {@code GameDir}（或 {@code GameFolders} 中不含当前目录）时，
 * {@link #load(Map)} 会自动把当前目录补进列表，缺失的名称按目录名生成。
 *
 * <p>本类只负责「读取 / 规整 / 写回配置 Map」，不做任何文件系统写入：
 * 调用方修改完 {@code config} 后按启动器既有流程保存（{@code saveConfig()}），
 * 目录的增删不会删除磁盘上的任何文件。
 */
public final class GameDirManager {

    /** 当前使用的游戏目录（沿用旧键，所有既有启动/安装逻辑都读它） */
    public static final String KEY_ACTIVE = "GameDir";
    /** 多目录列表（{@code |} 分隔的路径） */
    public static final String KEY_FOLDERS = "GameFolders";
    /** 多目录显示名称（{@code |} 分隔，顺序与 {@link #KEY_FOLDERS} 对应） */
    public static final String KEY_NAMES = "GameDirNames";

    /** 未配置任何目录时使用的默认目录 */
    public static final String DEFAULT_DIR = ".minecraft";

    private static final String SEP = "|";

    private GameDirManager() {}

    // ================================================================
    //  目录项
    // ================================================================

    /** 一个受管理的游戏目录：显示名称 + 路径 */
    public static final class GameDirEntry {

        private final String name;
        private final String path;

        public GameDirEntry(String name, String path) {
            this.name = name == null ? "" : name.trim();
            this.path = path == null ? "" : path.trim();
        }

        public String getName() {
            return name;
        }

        public String getPath() {
            return path;
        }

        /** 展示用路径：相对路径按启动器工作目录解析为绝对路径 */
        public String getDisplayPath() {
            return GameDirManager.resolve(path);
        }

        /** 目录是否真实存在（相对路径按启动器工作目录解析） */
        public boolean exists() {
            try {
                return Files.isDirectory(Paths.get(path));
            } catch (InvalidPathException e) {
                return false;
            }
        }

        /** versions 目录下已安装的版本数量（目录不存在时为 0） */
        public int countVersions() {
            Path versionsDir;
            try {
                versionsDir = Paths.get(path, "versions");
            } catch (InvalidPathException e) {
                return 0;
            }
            if (!Files.isDirectory(versionsDir)) return 0;
            try (Stream<Path> stream = Files.list(versionsDir)) {
                return (int) stream.filter(Files::isDirectory).count();
            } catch (Exception e) {
                return 0;
            }
        }

        @Override
        public String toString() {
            return name.isEmpty() ? path : name + " (" + path + ")";
        }
    }

    // ================================================================
    //  读取
    // ================================================================

    /**
     * 读取目录列表：{@code GameFolders} + {@code GameDirNames} 按序配对，
     * 并保证当前使用的目录（{@code GameDir}）一定在列表中。
     *
     * @param config 配置 Map（configCache / ConfigManager.readConfig() 均可）
     * @return 至少包含一项的目录列表；配置为空时返回默认目录 .minecraft
     */
    public static List<GameDirEntry> load(Map<String, String> config) {
        List<GameDirEntry> entries = new ArrayList<>();
        List<String> paths = split(config == null ? null : config.get(KEY_FOLDERS));
        List<String> names = split(config == null ? null : config.get(KEY_NAMES));

        for (int i = 0; i < paths.size(); i++) {
            String path = paths.get(i);
            if (path.isEmpty() || containsPath(entries, path)) continue;
            String name = i < names.size() ? sanitizeName(names.get(i)) : "";
            if (name.isEmpty()) name = suggestName(path, entries);
            entries.add(new GameDirEntry(name, path));
        }

        // 当前目录必须可管理：旧配置只有 GameDir 时在这里自动补一条
        String active = activePath(config);
        if (!containsPath(entries, active)) {
            entries.add(0, new GameDirEntry(suggestName(active, entries), active));
        }
        return entries;
    }

    /** 当前使用的游戏目录：{@code GameDir} 为空时回退到列表首项 / 默认目录 */
    public static String activePath(Map<String, String> config) {
        String active = config == null ? "" : trim(config.get(KEY_ACTIVE));
        if (!active.isEmpty()) return active;
        List<GameDirEntry> entries = listFromConfig(config);
        return entries.isEmpty() ? DEFAULT_DIR : entries.get(0).getPath();
    }

    /** 仅解析 {@code GameFolders} / {@code GameDirNames}（不含 GameDir 兜底，避免递归） */
    private static List<GameDirEntry> listFromConfig(Map<String, String> config) {
        List<GameDirEntry> entries = new ArrayList<>();
        List<String> paths = split(config == null ? null : config.get(KEY_FOLDERS));
        List<String> names = split(config == null ? null : config.get(KEY_NAMES));
        for (int i = 0; i < paths.size(); i++) {
            String path = paths.get(i);
            if (path.isEmpty() || containsPath(entries, path)) continue;
            String name = i < names.size() ? sanitizeName(names.get(i)) : "";
            entries.add(new GameDirEntry(name.isEmpty() ? suggestName(path, entries) : name, path));
        }
        return entries;
    }

    // ================================================================
    //  写入
    // ================================================================

    /**
     * 把目录列表写回配置 Map（只改内存，不落盘）：
     * 同步写入 {@code GameFolders} / {@code GameDirNames}，并把 {@code GameDir} 校正为列表内的有效项。
     *
     * @param config     配置 Map
     * @param entries    目录列表（按展示顺序）
     * @param activePath 期望的当前目录；为空或不在列表中时回退为列表首项
     */
    public static void store(Map<String, String> config, List<GameDirEntry> entries, String activePath) {
        if (config == null) return;
        List<GameDirEntry> list = entries == null ? new ArrayList<>() : entries;

        List<String> paths = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (GameDirEntry entry : list) {
            String path = trim(entry.getPath());
            if (path.isEmpty() || listContains(paths, path)) continue;
            paths.add(path);
            String name = sanitizeName(entry.getName());
            names.add(name.isEmpty() ? uniqueName(path, paths) : name);
        }

        String active = trim(activePath);
        if (active.isEmpty() || !listContains(paths, active)) {
            active = paths.isEmpty() ? DEFAULT_DIR : paths.get(0);
        }
        if (paths.isEmpty()) {
            paths.add(active);
            names.add(uniqueName(active, new ArrayList<>()));
        }
        dedupeNames(names);

        config.put(KEY_FOLDERS, String.join(SEP, paths));
        config.put(KEY_NAMES, String.join(SEP, names));
        config.put(KEY_ACTIVE, active);
    }

    // ================================================================
    //  工具方法
    // ================================================================

    /** 列表中是否已包含该路径（Windows 下大小写不敏感，忽略末尾分隔符与 . / ..） */
    public static boolean containsPath(List<GameDirEntry> entries, String path) {
        if (entries == null) return false;
        for (GameDirEntry entry : entries) {
            if (samePath(entry.getPath(), path)) return true;
        }
        return false;
    }

    /** 路径列表中是否已包含该路径（非 GameDirEntry 形式，存储/去重内部使用） */
    private static boolean listContains(List<String> paths, String path) {
        for (String p : paths) {
            if (samePath(p, path)) return true;
        }
        return false;
    }

    /** 在两个路径间查找匹配项下标，找不到返回 -1 */
    public static int indexOf(List<GameDirEntry> entries, String path) {
        if (entries == null) return -1;
        for (int i = 0; i < entries.size(); i++) {
            if (samePath(entries.get(i).getPath(), path)) return i;
        }
        return -1;
    }

    /** 判断两个路径是否指向同一目录（规范化后比较，Windows 下忽略大小写） */
    public static boolean samePath(String a, String b) {
        String pa = trim(a);
        String pb = trim(b);
        if (pa.isEmpty() || pb.isEmpty()) return false;
        String na = normalize(pa);
        String nb = normalize(pb);
        return isWindows() ? na.equalsIgnoreCase(nb) : na.equals(nb);
    }

    /** 解析为绝对规范化路径字符串；路径非法时原样返回 */
    public static String resolve(String path) {
        String p = trim(path);
        if (p.isEmpty()) return "";
        try {
            return Paths.get(p).toAbsolutePath().normalize().toString();
        } catch (InvalidPathException e) {
            return p;
        }
    }

    /**
     * 依据已有目录生成一个不重复的显示名称（取路径最后一段，重名时追加序号）。
     * 例如 {@code D:\mc\.minecraft} → {@code .minecraft}，再次添加同名目录 → {@code .minecraft (2)}。
     */
    public static String suggestName(String path, List<GameDirEntry> existing) {
        List<String> used = new ArrayList<>();
        if (existing != null) {
            for (GameDirEntry entry : existing) used.add(entry.getName());
        }
        return uniqueName(path, used);
    }

    /** 名称去重内部实现：与已用名称列表比较（大小写不敏感），重名时追加序号 */
    private static String uniqueName(String path, List<String> usedNames) {
        String base = autoName(path);

        Set<String> used = new LinkedHashSet<>();
        for (String n : usedNames) used.add(n.toLowerCase(java.util.Locale.ROOT));
        if (!used.contains(base.toLowerCase(java.util.Locale.ROOT))) return base;
        for (int i = 2; i < 1000; i++) {
            String candidate = base + " (" + i + ")";
            if (!used.contains(candidate.toLowerCase(java.util.Locale.ROOT))) return candidate;
        }
        return base + " (" + System.currentTimeMillis() + ")";
    }

    /**
     * 依据路径自动生成显示名称：默认取最后一段目录名；
     * 名称为 {@code .minecraft} 这类通用名时（绝对路径）改用上一级目录名，
     * 这样「E:\PCL\.minecraft」「D:\HMCL\.minecraft」在列表里是「PCL」「HMCL」而不是两个 .minecraft。
     */
    private static String autoName(String path) {
        String p = trim(path);
        if (p.isEmpty()) return DEFAULT_DIR;
        try {
            Path full = Paths.get(p);
            Path name = full.getFileName();
            String last = name == null ? "" : name.toString();
            if (isGenericMcDir(last) && full.isAbsolute()) {
                Path parent = full.normalize().getParent();
                String parentName = (parent == null || parent.getFileName() == null)
                        ? "" : parent.getFileName().toString();
                if (!parentName.isEmpty() && !isGenericMcDir(parentName)) last = parentName;
            }
            if (!last.isEmpty()) return sanitizeName(last);
        } catch (InvalidPathException ignored) {
            // 路径非法：退回整串路径作为名称
        }
        return sanitizeName(p);
    }

    /** 是否为无区分度的通用游戏目录名 */
    private static boolean isGenericMcDir(String name) {
        String n = trim(name).toLowerCase(java.util.Locale.ROOT);
        return n.equals(".minecraft") || n.equals("minecraft");
    }

    /** 名称合法化：去掉分隔符与换行，避免破坏 ini 的 {@code |} 分隔格式 */
    public static String sanitizeName(String name) {
        String n = trim(name);
        if (n.isEmpty()) return "";
        return n.replace("|", "/").replace("\r", " ").replace("\n", " ").trim();
    }

    /** 名称去重：重名时追加序号，保证设置页列表中一眼可区分 */
    private static void dedupeNames(List<String> names) {
        Set<String> used = new LinkedHashSet<>();
        for (int i = 0; i < names.size(); i++) {
            String base = sanitizeName(names.get(i));
            if (base.isEmpty()) base = DEFAULT_DIR;
            String candidate = base;
            for (int k = 2; !used.add(candidate.toLowerCase(java.util.Locale.ROOT)); k++) {
                candidate = base + " (" + k + ")";
            }
            names.set(i, candidate);
        }
    }

    /** 当前系统是否为 Windows（路径大小写不敏感） */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static String normalize(String path) {
        try {
            return Paths.get(path).toAbsolutePath().normalize().toString();
        } catch (InvalidPathException e) {
            return path;
        }
    }

    private static List<String> split(String raw) {
        List<String> parts = new ArrayList<>();
        if (raw == null) return parts;
        for (String p : raw.split("\\" + SEP, -1)) {
            String v = trim(p);
            if (!v.isEmpty()) parts.add(v);
        }
        return parts;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
