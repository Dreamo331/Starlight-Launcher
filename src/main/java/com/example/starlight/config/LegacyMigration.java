/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 一次性历史数据迁移：把散落在旧位置的数据并入 {@code ~/.starlight/}。
 *
 * <h3>三个来源</h3>
 * <ol>
 *   <li>{@code ~/.starlight-launcher/}（已废弃的全局目录）：账号、注入器、头像等；</li>
 *   <li>{@code <工作目录>/Starlight-Launcher/}：全部旧配置与运行时组件；</li>
 *   <li>{@code %APPDATA%\StarlightFrameGen/}：帧生成器配置。</li>
 * </ol>
 *
 * <h3>安全设计</h3>
 * <ul>
 *   <li><b>先复制、校验通过后再删源</b>：不使用 {@code Files.move}，因为工作目录可能在
 *       另一个盘（跨卷 move 目录会直接失败）；复制成功才删源，中断最多留下重复文件，不会丢数据。</li>
 *   <li><b>目标已存在时按修改时间取新</b>：不盲目覆盖，避免把更新的配置冲掉。</li>
 *   <li><b>绝对路径改写</b>：{@code starlight.ini} 的 {@code JavaPath/JavaPathHistory}、
 *       {@code starlight-client.ini} 的 {@code BackgroundImage}、账号里的皮肤路径都是绝对路径，
 *       文件搬走后会指向空位置，这里做一次前缀替换。</li>
 *   <li><b>失败不阻断启动</b>：任何异常只记日志；下次启动会重试（天然幂等）。</li>
 *   <li>代码已不再引用的「孤儿文件」不直接删，收进 {@code ~/.starlight/legacy/} 由用户自行处置。</li>
 * </ul>
 */
final class LegacyMigration {

    /** 从旧全局目录 ~/.starlight-launcher 并入根目录的条目 */
    private static final String[] FROM_LEGACY_GLOBAL = {
            "accounts.json",
            "login.json",
            "external_login.json",
            "authlib-injector.jar",
            "avatars",
            "java-cache.json",
    };

    /**
     * 旧全局目录里「代码已不再引用」的孤儿文件。
     * 含真实密钥，不能随目录一起静默删除，收进 legacy/ 留待用户确认。
     */
    private static final String[] LEGACY_GLOBAL_ORPHANS = {
            "auth_servers.json",
            "endpoints.real.json",
    };

    /** 从 <工作目录>/Starlight-Launcher 并入根目录的条目 */
    private static final String[] FROM_WORKDIR = {
            "starlight.ini",
            "starlight-client.ini",
            "multiplayer.json",
            ".account.dat",
            ".community.dat",
            "java-cache.json",
            "plugins-state.json",
            "launcher.json",
            "uploaded_logs.json",
            "source-health.json",
            "version-manifest-cache.json",
            "mod-dictionary",
            "wallpaper",
            "skin",
            // 插件目录：磁盘上实际为 Mod/，Windows 大小写不敏感，两个名字都试
            "mod",
            "Mod",
            // 运行时组件
            "ColorBlindOverlay",
            "slan",
            "terracotta",
    };

    /** 迁移后需要改写「绝对路径前缀」的文本配置 */
    private static final String[] REWRITE_TARGETS = {
            "starlight.ini",
            "starlight-client.ini",
            "accounts.json",
            "login.json",
            "external_login.json",
    };

    private static volatile boolean done;

    private LegacyMigration() {
    }

    /** 执行一次（进程内幂等；跨进程幂等由「源不存在即跳过」保证） */
    static synchronized void runOnce() {
        if (done) {
            return;
        }
        done = true;
        try {
            Path target = AppDirs.root();
            Files.createDirectories(target);

            Path legacyGlobal = AppDirs.legacyGlobalRoot();
            Path workDirOld = AppDirs.portableRoot();   // <user.dir>/Starlight-Launcher

            boolean any = false;
            any |= migrateLegacyGlobal(legacyGlobal, target);
            any |= migrateWorkDir(workDirOld, target);
            any |= migrateToolsJava(workDirOld.getParent(), target);
            any |= migrateFramegen(target);

            if (any) {
                rewriteAbsolutePaths(target);
                cleanupEmptyDirs(legacyGlobal, workDirOld);
            }
        } catch (Throwable t) {
            // 迁移失败绝不能阻断启动：配置读不到时各处已有兜底默认值
            System.err.println("[AppDirs] 历史数据迁移失败（不影响启动）: " + t);
        }
    }

    // 各来源 //

    private static boolean migrateLegacyGlobal(Path src, Path target) throws IOException {
        if (!Files.isDirectory(src)) {
            return false;
        }
        System.out.println("[AppDirs] 检测到已废弃的 " + src + "，正在并入 " + target);
        boolean moved = false;
        for (String name : FROM_LEGACY_GLOBAL) {
            moved |= moveEntry(src.resolve(name), target.resolve(name));
        }
        // 孤儿文件收纳到 legacy/
        for (String name : LEGACY_GLOBAL_ORPHANS) {
            Path s = src.resolve(name);
            if (Files.exists(s)) {
                moved |= moveEntry(s, AppDirs.legacyDir().resolve(name));
            }
        }
        return moved;
    }

    private static boolean migrateWorkDir(Path src, Path target) throws IOException {
        if (!Files.isDirectory(src)) {
            return false;
        }
        boolean moved = false;
        for (String name : FROM_WORKDIR) {
            moved |= moveEntry(src.resolve(name), target.resolve(name));
        }
        return moved;
    }

    /** {@code <工作目录>/tools/java} → {@code ~/.starlight/java}（不再与构建脚本 tools/ 混放） */
    private static boolean migrateToolsJava(Path workDir, Path target) throws IOException {
        if (workDir == null) {
            return false;
        }
        Path toolsJava = workDir.resolve("tools").resolve("java");
        return moveEntry(toolsJava, AppDirs.javaDir());
    }

    /** {@code %APPDATA%\StarlightFrameGen\config.properties} → {@code ~/.starlight/framegen/config.properties} */
    private static boolean migrateFramegen(Path target) throws IOException {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) {
            return false;
        }
        Path oldDir = Path.of(appData, "StarlightFrameGen");
        if (!Files.isDirectory(oldDir)) {
            return false;
        }
        boolean moved = moveEntry(oldDir.resolve("config.properties"), AppDirs.framegenConfig());
        if (moved) {
            cleanupEmptyDir(oldDir);
        }
        return moved;
    }

    // 搬运原语 //

    /**
     * 把 {@code src} 搬到 {@code dest}：目标不存在则复制后删源；目标已存在则按修改时间取新。
     *
     * @return 是否真的搬动了内容
     */
    private static boolean moveEntry(Path src, Path dest) throws IOException {
        if (src == null || dest == null || !Files.exists(src)) {
            return false;
        }
        // 源与目标归一化后相同（例如便携模式解析出的路径就是它自己）→ 不动
        if (src.toAbsolutePath().normalize().equals(dest.toAbsolutePath().normalize())) {
            return false;
        }
        if (Files.isDirectory(src)) {
            // 目录：合并式复制（目标已存在时逐文件覆盖），再删源
            if (Files.exists(dest) && !Files.isDirectory(dest)) {
                return false; // 目标被同名文件占位，放弃以免破坏
            }
            copyRecursively(src, dest);
            deleteRecursively(src);
            return true;
        }
        // 文件
        if (Files.exists(dest)) {
            long srcTime = Files.getLastModifiedTime(src).toMillis();
            long destTime = Files.getLastModifiedTime(dest).toMillis();
            if (destTime >= srcTime) {
                Files.delete(src);      // 目标更新，保留目标，丢弃旧源
                return true;
            }
        }
        if (dest.getParent() != null) {
            Files.createDirectories(dest.getParent());
        }
        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        Files.delete(src);
        return true;
    }

    private static void copyRecursively(Path src, Path dest) throws IOException {
        if (Files.isDirectory(src)) {
            Files.createDirectories(dest);
            try (var stream = Files.list(src)) {
                for (Path child : stream.toList()) {
                    copyRecursively(child, dest.resolve(child.getFileName().toString()));
                }
            }
            return;
        }
        if (dest.getParent() != null) {
            Files.createDirectories(dest.getParent());
        }
        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.deleteIfExists(f);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                Files.deleteIfExists(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // 绝对路径改写 //

    /**
     * 把配置文件里指向旧位置的绝对路径前缀改写为新根目录。
     *
     * <p>覆盖三类已知的绝对路径：{@code starlight.ini} 的 {@code JavaPath} /
     * {@code JavaPathHistory}（指向旧的 {@code tools/java}）、{@code starlight-client.ini} 的
     * {@code BackgroundImage}（旧的 {@code wallpaper/}）、账号 {@code skinJson} 里的
     * {@code localSkinPath} / {@code localCapePath}（旧的 {@code skin/}）。
     */
    private static void rewriteAbsolutePaths(Path root) {
        List<String[]> pairs = new ArrayList<>();
        Path workDir = AppDirs.portableRoot();
        Path legacyGlobal = AppDirs.legacyGlobalRoot();
        String newRoot = root.toString();

        addPrefixPairs(pairs, workDir.toString(), newRoot);
        addPrefixPairs(pairs, legacyGlobal.toString(), newRoot);
        // JavaPath / JavaPathHistory 指向的旧 JDK 目录不在 Starlight-Launcher/ 下，
        // 而是 <工作目录>/tools/java（未走 AppDirs 的历史遗留），需单独映射到 <root>/java
        Path parent = workDir.getParent();
        if (parent != null) {
            addPrefixPairs(pairs, parent.resolve("tools").resolve("java").toString(),
                    AppDirs.javaDir().toString());
        }

        // 长前缀优先：否则 <工作目录> 的通用规则会先命中，
        // 把 ...\work\tools\java\... 错改成 ...\.starlight\tools\java\...（应为 ...\.starlight\java\...）
        pairs.sort((a, b) -> Integer.compare(b[0].length(), a[0].length()));

        for (String name : REWRITE_TARGETS) {
            Path f = root.resolve(name);
            if (!Files.isRegularFile(f)) {
                continue;
            }
            try {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                String updated = text;
                for (String[] pair : pairs) {
                    if (updated.contains(pair[0])) {
                        updated = updated.replace(pair[0], pair[1]);
                    }
                }
                if (!updated.equals(text)) {
                    Files.writeString(f, updated, StandardCharsets.UTF_8);
                    System.out.println("[AppDirs] 已改写配置内的绝对路径: " + name);
                }
            } catch (IOException e) {
                System.err.println("[AppDirs] 改写 " + name + " 失败: " + e.getMessage());
            }
        }
    }

    /**
     * 同一个前缀补多种写法。
     *
     * <p>反斜杠的「转义层数」必须分别覆盖，否则会漏改：
     * <ul>
     *   <li>深度 1：{@code starlight.ini} / {@code starlight-client.ini} 等纯文本，路径就是一个反斜杠；</li>
     *   <li>深度 2：普通 JSON 字段里的路径（{@code "BackgroundImage":"G:\\a\\b"}）；</li>
     *   <li>深度 4：{@code accounts.json} 的 {@code skinJson} —— 它本身是一个「装着 JSON 的 JSON 字符串」，
     *       里层路径被转义了两次，落盘即 {@code G:\\\\a\\\\b}。这是真实数据里最常见的形态。</li>
     * </ul>
     * 只补前两层的话，皮肤 / 披风路径迁移后会继续指向旧目录（实测踩到过）。
     */
    private static void addPrefixPairs(List<String[]> pairs, String from, String to) {
        String fromBack = from.replace('/', '\\');
        String toBack = to.replace('/', '\\');
        for (int depth : new int[]{1, 2, 4}) {
            String bs = "\\".repeat(depth);
            pairs.add(new String[]{
                    fromBack.replace("\\", bs) + bs,
                    toBack.replace("\\", bs) + bs});
        }
        // 正斜杠写法（部分配置会写成 G:/a/b）
        pairs.add(new String[]{from + "/", to + "/"});
        pairs.add(new String[]{from.replace('\\', '/') + "/", to.replace('\\', '/') + "/"});
    }

    // 收尾 //

    /** 源目录搬空后删除；非空则保留（说明还有本迁移不认识的用户文件） */
    private static void cleanupEmptyDirs(Path legacyGlobal, Path workDirOld) {
        cleanupEmptyDir(legacyGlobal);
        // 旧目录里常有自动生成的空骨架（如 <工作目录>/Starlight-Launcher/.minecraft/versions），
        // 它们本身没有数据但会让父目录"非空"，导致旧目录删不掉。先自底向上清掉空目录树。
        pruneEmptyTree(workDirOld);
        cleanupEmptyDir(workDirOld);
        Path toolsJavaParent = workDirOld.getParent() == null
                ? null : workDirOld.getParent().resolve("tools");
        if (toolsJavaParent != null) {
            pruneEmptyTree(toolsJavaParent.resolve("java"));
            cleanupEmptyDir(toolsJavaParent);
        }
    }

    /**
     * 自底向上删除空目录（只删空目录，任何含文件的分支原样保留）。
     *
     * <p>安全边界：本方法<b>只</b>删除「不含任何文件、且所有子目录也都为空」的目录，
     * 因此不可能删掉用户数据；像 {@code .minecraft} 这种若真有内容则整体保留。
     */
    private static void pruneEmptyTree(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            // 深度倒序 = 自底向上，先处理子目录
            List<Path> all = stream.sorted(java.util.Comparator.reverseOrder()).toList();
            for (Path p : all) {
                if (p.equals(dir) || !Files.isDirectory(p)) {
                    continue;
                }
                try (var children = Files.list(p)) {
                    if (children.findAny().isEmpty()) {
                        Files.deleteIfExists(p);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[AppDirs] 清理空目录失败 " + dir + ": " + e.getMessage());
        }
    }

    /**
     * 仅当目录为空时删除。
     *
     * <p>刻意不递归强删：{@code ~/.starlight-launcher/} 里可能还留着本迁移清单未覆盖的
     * 用户文件，宁可留一个空壳目录，也不能误删。
     */
    private static void cleanupEmptyDir(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            List<Path> children = stream.toList();
            if (children.isEmpty()) {
                Files.deleteIfExists(dir);
                System.out.println("[AppDirs] 已删除空的旧目录: " + dir);
            } else {
                System.out.println("[AppDirs] 旧目录非空，保留待人工确认: " + dir
                        + "（剩余 " + children.size() + " 项，例如 " + children.get(0).getFileName() + "）");
            }
        } catch (IOException e) {
            System.err.println("[AppDirs] 清理旧目录失败 " + dir + ": " + e.getMessage());
        }
    }

    /** 供诊断 / 日志使用：当前是否仍有旧目录残留 */
    static boolean hasLegacyLeftovers() {
        return Files.isDirectory(AppDirs.legacyGlobalRoot())
                || Files.isDirectory(AppDirs.portableRoot());
    }

    @SuppressWarnings("unused")
    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
