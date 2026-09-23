package com.example.starlight.util;

import com.example.starlight.gui.UIGeneralControlClass;
import com.example.starlight.listsaves.listsaves;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地资源目录扫描（从 LauncherView 抽离，逻辑与原实现保持一致）。
 *
 * <p>职责：
 * <ul>
 *   <li>收集 {@code <gameDir>/<subDir>} 与各版本隔离目录下的同名子目录；</li>
 *   <li>目录归属标签（全局 / 版本名 / 自定义目录名）；</li>
 *   <li>存档（NBT 解析复用 {@link listsaves}）、崩溃报告、截图清单；</li>
 *   <li>判断指定目录是否已安装某版本；</li>
 *   <li>推导截图目录。</li>
 * </ul>
 *
 * <p>配置项（自定义截图目录、游戏目录、版本隔离、当前版本）由调用方读出后以参数传入，
 * 本类不直接读配置，也不依赖 JavaFX，可在任意后台线程调用。
 */
public final class ResourceScanner {

    private ResourceScanner() {
    }

    /**
     * 推导截图目录：优先配置项，否则按版本隔离规则取
     * {@code <gameDir>/versions/<version>/screenshots} 或 {@code <gameDir>/screenshots}。
     */
    public static String resolveScreenshotDir(String screenshotDirConfig, String gameDir,
                                              boolean versionIsolation, String version) {
        if (screenshotDirConfig != null && !screenshotDirConfig.isEmpty()) return screenshotDirConfig;
        if (versionIsolation && !version.isEmpty()) {
            return gameDir + "/versions/" + version + "/screenshots";
        }
        return gameDir + "/screenshots";
    }

    /** 指定目录的 versions 下是否已安装该版本 */
    public static boolean versionInstalledIn(String gameDir, String version) {
        if (gameDir == null || gameDir.isEmpty() || version == null || version.isEmpty()) return false;
        try {
            return Files.isDirectory(Paths.get(gameDir, "versions", version));
        } catch (InvalidPathException e) {
            return false;
        }
    }


    /** 收集 gameDir 下所有包含指定子目录的目录（全局 + 各版本隔离目录），全局在前、版本按名称排序 */
    public static List<Path> collectResourceDirs(String gameDir, String subDir) {
        List<Path> dirs = new ArrayList<>();
        Path global = Paths.get(gameDir, subDir);
        if (Files.isDirectory(global)) dirs.add(global);
        Path versionsDir = Paths.get(gameDir, "versions");
        if (Files.isDirectory(versionsDir)) {
            try (var stream = Files.list(versionsDir)) {
                stream.filter(Files::isDirectory)
                        .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                        .forEach(v -> {
                            Path p = v.resolve(subDir);
                            if (Files.isDirectory(p)) dirs.add(p);
                        });
            } catch (Exception ignored) {}
        }
        return dirs;
    }

    /** 资源目录归属标签：全局目录返回“全局”，版本隔离目录返回版本名，其他自定义目录返回目录名 */
    public static String dirOriginLabel(Path dir, String gameDir) {
        if (dir == null) return "全局";
        Path versionsDir = Paths.get(gameDir, "versions");
        Path parent = dir.getParent();
        if (parent != null && parent.getParent() != null && parent.getParent().equals(versionsDir)) {
            return parent.getFileName().toString();
        }
        if (parent != null && parent.equals(Paths.get(gameDir))) {
            return "全局";
        }
        return dir.getFileName() != null ? dir.getFileName().toString() : "全局";
    }


    /** 扫描全局 saves + 所有版本隔离目录下的 saves，合并去重后返回全部存档（filterOrigin 为 null 时不筛选） */
    public static List<UIGeneralControlClass.SaveInfo> listAllSaves(String gameDir, String filterOrigin) {
        List<UIGeneralControlClass.SaveInfo> saves = new ArrayList<>();
        for (Path savesDir : collectResourceDirs(gameDir, "saves")) {
            if (filterOrigin != null && !dirOriginLabel(savesDir, gameDir).equals(filterOrigin)) continue;
            saves.addAll(listSavesInDir(savesDir, gameDir));
        }
        // 按路径去重（同名存档存在于不同目录时保留各自条目）
        Map<String, UIGeneralControlClass.SaveInfo> byPath = new LinkedHashMap<>();
        for (UIGeneralControlClass.SaveInfo s : saves) byPath.putIfAbsent(s.path, s);
        return new ArrayList<>(byPath.values());
    }

    /** 解析单个 saves 目录中的存档（复用 listsaves 的 NBT 解析，拦截控制台输出），version 字段记录来源标签 */
    public static List<UIGeneralControlClass.SaveInfo> listSavesInDir(Path savesDir, String gameDir) {
        String origin = dirOriginLabel(savesDir, gameDir);
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        List<String> capturedLines = new ArrayList<>();
        try {
            PrintStream capturingPs = new PrintStream(new OutputStream() {
                // 按字节累积后整体按 UTF-8 解码，避免中文（多字节字符）被逐字节拆成乱码
                final ByteArrayOutputStream buf = new ByteArrayOutputStream();
                @Override
                public void write(int b) {
                    buf.write(b);
                    if (b == '\n') {
                        capturedLines.add(buf.toString(StandardCharsets.UTF_8).stripTrailing());
                        buf.reset();
                    }
                }
                @Override
                public void write(byte[] b, int off, int len) {
                    for (int i = off; i < off + len; i++) write(b[i]);
                }
            }, true, StandardCharsets.UTF_8);
            System.setOut(capturingPs);
            System.setErr(capturingPs);
            listsaves.main(new String[]{savesDir.toString(), savesDir.toString()});
            System.out.flush();
            System.err.flush();
        } catch (Exception e) {
            return Collections.emptyList();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        // 解析输出（格式：Save name: / Save path:），版本字段替换为来源标签
        List<UIGeneralControlClass.SaveInfo> saves = new ArrayList<>();
        String currentName = null;
        String currentPath = null;
        for (String line : capturedLines) {
            if (line.startsWith("Save name:")) {
                currentName = line.substring(line.indexOf(':') + 1).trim();
            } else if (line.startsWith("Save path:")) {
                currentPath = line.substring(line.indexOf(':') + 1).trim();
                if (currentName != null && currentPath != null) {
                    saves.add(new UIGeneralControlClass.SaveInfo(currentName,
                            new File(currentPath).getName(), currentPath, origin));
                    currentName = null;
                }
            }
        }
        return saves;
    }

    /** 列出全部崩溃报告（全局 + 各版本隔离目录，crash-*.txt），按修改时间倒序；filterOrigin 为 null 时不筛选 */
    public static List<Path> listCrashReports(String gameDir, String filterOrigin) {
        List<Path> reports = new ArrayList<>();
        for (Path dir : collectResourceDirs(gameDir, "crash-reports")) {
            if (filterOrigin != null && !dirOriginLabel(dir, gameDir).equals(filterOrigin)) continue;
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.getFileName().toString().matches("crash-.*\\.txt"))
                        .forEach(reports::add);
            } catch (Exception ignored) {}
        }
        reports.sort(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed());
        return reports;
    }

    /** 列出全部截图（全局 + 各版本隔离目录 + 自定义截图目录），按修改时间倒序；filterOrigin 为 null 时不筛选 */
    public static List<Path> listScreenshots(String gameDir, String filterOrigin, String customScreenshotDir) {
        List<Path> shots = new ArrayList<>();
        for (Path dir : collectResourceDirs(gameDir, "screenshots")) {
            if (filterOrigin != null && !dirOriginLabel(dir, gameDir).equals(filterOrigin)) continue;
            collectImages(dir, shots);
        }
        // 自定义截图目录（配置 ScreenshotDir）也纳入扫描
        String custom = customScreenshotDir;
        if (custom != null && !custom.isEmpty()) {
            Path customDir = Paths.get(custom);
            if (filterOrigin == null || dirOriginLabel(customDir, gameDir).equals(filterOrigin)) {
                collectImages(customDir, shots);
            }
        }
        // 按路径去重 + 按修改时间倒序
        Map<String, Path> byPath = new LinkedHashMap<>();
        for (Path p : shots) byPath.putIfAbsent(p.toString(), p);
        List<Path> result = new ArrayList<>(byPath.values());
        result.sort(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed());
        return result;
    }

    /** 收集目录中的图片文件到目标列表 */
    private static void collectImages(Path dir, List<Path> out) {
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            stream.filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg");
                    }).forEach(out::add);
        } catch (Exception ignored) {}
    }

}
