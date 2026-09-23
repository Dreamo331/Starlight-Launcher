package com.example.starlight.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 压缩包与文件系统工具（从 LauncherView 抽离，逻辑与原实现保持一致）。
 *
 * <p>全部为无状态静态方法，不依赖 JavaFX，可在任意后台线程调用。
 */
public final class ArchiveUtils {

    private ArchiveUtils() {
    }

    /** 解压 zip 到目标目录（含路径穿越防护） */
    public static void unzipTo(Path zipPath, Path destDir) throws IOException {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path target = destDir.resolve(entry.getName()).normalize();
                if (!target.startsWith(destDir)) continue; // 路径穿越防护
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (var in = zip.getInputStream(entry)) {
                        Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    /** 递归复制目录 */
    public static void copyDirectory(Path src, Path dest) throws IOException {
        if (!Files.isDirectory(src)) return;
        Files.walkFileTree(src, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dest.resolve(src.relativize(dir)));
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Files.copy(file, dest.resolve(src.relativize(file)), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    /** 递归删除目录 */
    public static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
    }

    /** 在 parent 下取一个不冲突的目录名：name、name (2)、name (3)… */
    public static Path uniqueDir(Path parent, String name) {
        Path candidate = parent.resolve(name);
        int i = 2;
        while (Files.exists(candidate)) {
            candidate = parent.resolve(name + " (" + i + ")");
            i++;
        }
        return candidate;
    }

    /** 下载文件名净化：非法字符替换为下划线，含路径穿越时返回 null */
    public static String sanitizeDownloadFileName(String filename) {
        if (filename == null || filename.isBlank()) return null;
        String f = filename.replace('\\', '_').replace('/', '_')
                .replaceAll("[\\x00-\\x1f<>:\"|?*]", "_");
        if (f.equals(".") || f.equals("..") || f.contains("..")) return null;
        return f;
    }

    /** 世界名净化：剔除路径分隔与非法字符，保留大小写（存档目录名对用户可见） */
    public static String sanitizeWorldName(String name) {
        String s = name == null ? "" : name.trim();
        s = s.replace('\\', '_').replace('/', '_')
                .replaceAll("[\\x00-\\x1f<>:\"|?*]", "_")
                .trim();
        // CurseForge 的世界资源常常没有独立显示名，getName() 直接就是文件名（如 "Alphadei.zip"），
        // 这里去掉归档后缀，避免生成 "Alphadei.zip" 这样的存档目录
        s = s.replaceAll("(?i)\\.(zip|rar|7z|tar|gz|mcworld)$", "").trim();
        s = s.replaceAll("\\.+$", "").trim();
        if (s.isEmpty()) s = "world";
        if (s.length() > 60) s = s.substring(0, 60).trim();
        return s;
    }
}
