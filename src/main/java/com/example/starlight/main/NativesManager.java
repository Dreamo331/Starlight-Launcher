package com.example.starlight.main;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.JsonObject;
import com.startgame.LaunchInfo;
import com.startgame.launcher.VanillaLauncher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 原生库 (natives) 管理
 * 负责下载、解压、整理 Minecraft 原生库文件 (DLL/SO/dylib)
 */
public class NativesManager {

    // ================================================================
    //  补全原生库（含启动前预检）
    // ================================================================

    /** Native 文件最低有效数量（低于此值认为不完整，参考 BaseLauncher.MIN_REQUIRED_DLLS） */
    private static final int MIN_REQUIRED_NATIVES = 5;

    /**
     * 生成与启动库 BaseLauncher 一致的 natives 候选目录名（按优先级排序）
     * 对应 PlatformUtils.getNativesDirCandidates() + BaseLauncher 附加项
     */
    private static List<String> getNativesDirCandidates() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (arch.contains("aarch64") || arch.contains("arm64")) arch = "arm64";
        else if (arch.contains("amd64") || arch.contains("x86_64")) arch = "x86_64";
        else if (arch.contains("x86")) arch = "x86";

        List<String> list = new ArrayList<>();
        if (os.contains("win")) {
            list.add("natives-windows-" + arch);
            list.add("natives-windows");
        } else if (os.contains("mac")) {
            list.add("natives-macos-" + arch);
            list.add("natives-macos");
            list.add("natives-osx");
        } else {
            list.add("natives-linux-" + arch);
            list.add("natives-linux");
        }
        list.add("natives");
        list.add("natives-extracted");
        return list;
    }

    /**
     * 严格判定原生库是否就绪
     * 只认 BaseLauncher 的候选目录名，且有效 native 文件 ≥5 个且全部 >0 字节
     * （避免 1.20.1-natives 等历史残留目录被误判为就绪）
     */
    public static boolean isNativesReady(String gameDir, String version) {
        Path verDir = Paths.get(gameDir, "versions", version);
        for (String dirName : getNativesDirCandidates()) {
            Path dir = verDir.resolve(dirName);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.list(dir)) {
                long count = files.filter(f -> {
                    String n = f.getFileName().toString().toLowerCase();
                    if (!(n.endsWith(".dll") || n.endsWith(".so") || n.endsWith(".dylib"))) return false;
                    try {
                        return Files.size(f) > 0;
                    } catch (IOException e) {
                        return false;
                    }
                }).count();
                if (count >= MIN_REQUIRED_NATIVES) return true;
            } catch (IOException ignored) {}
        }
        return false;
    }

    /**
     * 启动前的原生库预检
     * 严格检查候选 natives 目录是否就绪（≥5 个有效 native 文件），
     * 如果缺失则调用 processNatives() 补全
     */
    public static boolean checkAndCompleteNatives(String gameDir, String version,
            String versionJson, boolean verbose) {
        if (isNativesReady(gameDir, version)) {
            if (verbose) System.out.println("[natives] Native libraries ready");
            return true;
        }

        // 原生库缺失，调用 processNatives() 补全
        if (verbose) System.out.println("[natives] Native libraries missing, provisioning...");
        return runNativeProvision(gameDir, version, verbose);
    }

    /**
     * 通过临时启动器执行原生库的下载和解压
     * 复用 StarlightLauncher 自身的 processNatives() 逻辑，
     * 确保处理方式和正式启动完全一致
     */
    public static boolean runNativeProvision(String gameDir, String version, boolean verbose) {
        Path versionJsonPath = Paths.get(gameDir, "versions", version, version + ".json");

        try {
            // 1. 读取 version.json
            String jsonStr = Files.readString(versionJsonPath, StandardCharsets.UTF_8);
            JsonObject versionJson = new Gson().fromJson(jsonStr, JsonObject.class);

            // 2. 构建最小 LaunchInfo（只需要启动器需要的信息）
            LaunchInfo info = new LaunchInfo();
            info.setGameDirPath(Path.of(gameDir));
            info.setVersion(version);
            info.setJavaPath("java");
            info.setVersionIsolation(false);

            // 3. 使用 VanillaLauncher 调用原生库处理
            VanillaLauncher launcher = new VanillaLauncher(info);
            launcher.initPaths();
            launcher.setVersionJson(versionJson);
            boolean ok = launcher.processNatives();
            if (!ok) {
                if (verbose) System.err.println("[natives] processNatives() failed, natives not ready");
                return false;
            }

            // 4. 清理多余的原生库文件，只保留实际需要的 DLL
            Path verDirNatives = Paths.get(gameDir, "versions", version);
            try (Stream<Path> stream = Files.list(verDirNatives)) {
                stream
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().contains("natives"))
                    .forEach(dir -> cleanupNativesDirectory(dir, verbose));
            } catch (IOException ignored) {}

            // 5. 严格复核：即使 processNatives 返回成功，也确认产物可用
            if (!isNativesReady(gameDir, version)) {
                if (verbose) System.err.println("[natives] Natives still not ready after provisioning");
                return false;
            }
            return true;

        } catch (JsonSyntaxException | IOException e) {
            if (verbose) System.err.println("[natives] Processing failed: " + e.getMessage());
            return false;
        }
    }

    // ================================================================
    //  清理原生库
    // ================================================================

    /**
     * 清理多余的原生库文件
     * 1. 将子目录中的 DLL 平铺到根目录（去重，保留体积大的）
     * 2. 删除不需要的文件（twitch、jtracy 等）
     * 3. 删除空子目录
     */
    public static void cleanupNativesDirectory(Path nativesDir, boolean verbose) {
        if (!Files.isDirectory(nativesDir)) return;

        try {
            // 1. 递归查找所有 DLL 文件
            List<Path> allDlls;
            try (Stream<Path> walk = Files.walk(nativesDir)) {
                allDlls = walk
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".dll"))
                    .sorted((a, b) -> b.toString().length() - a.toString().length())
                    .toList();
            }

            if (allDlls.isEmpty()) return;

            // 2. 按文件名分组，每个文件只保留体积最大的
            Map<String, Path> keepMap = new HashMap<>();
            for (Path dll : allDlls) {
                String name = dll.getFileName().toString();
                try {
                    long size = Files.size(dll);
                    Path existing = keepMap.get(name);
                    if (existing == null || Files.size(existing) < size) {
                        keepMap.put(name, dll);
                    }
                } catch (IOException e) {
                    keepMap.putIfAbsent(name, dll);
                }
            }

            // 3. 删除不需要的文件（twitch、jtracy、SAPIWrapper 等）
            String[] removePatterns = {"twitch", "avutil", "libmp3lame",
                "libmfxsw64", "swresample", "jtracy", "SAPIWrapper"};
            Set<String> toRemove = new HashSet<>();
            for (Path dll : allDlls) {
                String name = dll.getFileName().toString().toLowerCase();
                for (String pattern : removePatterns) {
                    if (name.contains(pattern)) {
                        toRemove.add(name);
                        break;
                    }
                }
            }
            for (String name : toRemove) {
                keepMap.remove(name);
                if (verbose) System.out.println("[natives] Removed: " + name);
            }

            // 4. 删除所有不在 keepMap 中的 DLL
            for (Path dll : allDlls) {
                String name = dll.getFileName().toString();
                Path kept = keepMap.get(name);
                if (kept != null && !dll.equals(kept)) {
                    try { Files.delete(dll); } catch (IOException ignored) {}
                }
            }

            // 5. 将 keepMap 中的 DLL 移到根目录
            for (Map.Entry<String, Path> entry : keepMap.entrySet()) {
                Path dll = entry.getValue();
                Path parent = dll.getParent();
                if (parent != null && !parent.equals(nativesDir)) {
                    Path target = nativesDir.resolve(entry.getKey());
                    try {
                        Files.move(dll, target, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        if (verbose) System.err.println("[natives] Move failed: " + entry.getKey());
                    }
                }
            }

            // 6. 删除空子目录
            try (Stream<Path> walk = Files.walk(nativesDir)) {
                walk.sorted((a, b) -> b.toString().length() - a.toString().length())
                    .filter(Files::isDirectory)
                    .filter(p -> !p.equals(nativesDir))
                    .forEach(p -> {
                        try { Files.delete(p); } catch (IOException ignored) {}
                    });
            }

            // 7. 统计最终结果
            if (verbose) {
                try (Stream<Path> list = Files.list(nativesDir)) {
                    List<Path> finalDlls = list
                        .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".dll"))
                        .sorted()
                        .toList();
                    System.out.println("[natives] After cleanup: " + finalDlls.size() + " files");
                    for (Path p : finalDlls) {
                        System.out.println("    " + p.getFileName());
                    }
                } catch (IOException ignored) {}
            }

        } catch (IOException e) {
            if (verbose) System.err.println("[natives] Cleanup failed: " + e.getMessage());
        }
    }
}
