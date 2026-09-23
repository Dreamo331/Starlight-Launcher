package com.example.starlight.version;

import com.example.starlight.ModsApi.RemoteMod;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.startgame.launcher.LoaderDetector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 游戏版本 / 加载器匹配工具（从 LauncherView 抽离，逻辑与原实现保持一致）。
 *
 * <p>包含版本号拆解与降序比较、游戏版本两级筛选、通配版本匹配、
 * 已安装加载器识别与匹配。全部为无状态静态方法，不依赖 JavaFX。
 */
public final class VersionMatcher {

    private VersionMatcher() {
    }

    /**
     * 取版本号的一级版本（「大版本」）：{@code 1.20.1} → {@code 1.20}、{@code 26.3-rc-2} → {@code 26.3}。
     *
     * <p>只认 {@code 数字.数字} 开头的一级版本号；周版本快照（如 {@code 25w14a}）没有一级版本概念，
     * 返回空串，从而不会在「大版本」下拉里各自变成一项（否则会出现上百个「大版本」）。
     */
    public static String majorVersionOf(String version) {
        if (version == null || version.isBlank()) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(\\d+\\.\\d+)")
                .matcher(version.trim());
        return m.find() ? m.group(1) : "";
    }

    /** 版本号从新到旧排序（按数字段比较，避免出现 1.9 > 1.20 这种字符串排序错误） */
    public static int compareVersionDesc(String a, String b) {
        String[] pa = a.split("[.\\-+]");
        String[] pb = b.split("[.\\-+]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            String sa = i < pa.length ? pa[i] : "";
            String sb = i < pb.length ? pb[i] : "";
            Integer na = parseIntOrNull(sa);
            Integer nb = parseIntOrNull(sb);
            int cmp;
            if (na != null && nb != null) {
                cmp = Integer.compare(nb, na);          // 数字段：大的更新
            } else if (na != null) {
                cmp = -1;                               // 有数字段的排在纯文字段之前（正式 > pre/rc）
            } else if (nb != null) {
                cmp = 1;
            } else {
                cmp = sb.compareToIgnoreCase(sa);
            }
            if (cmp != 0) return cmp;
        }
        return 0;
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isEmpty()) return null;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** 游戏版本两级筛选：子版本优先，其次大版本；都为空则不过滤 */
    public static boolean matchesGameVersionFilter(RemoteMod.Version v, String major, String minor) {
        if (v.getGameVersions() == null || v.getGameVersions().isEmpty()) {
            return major.isEmpty() && minor.isEmpty();
        }
        if (!minor.isEmpty()) return v.getGameVersions().contains(minor);
        if (!major.isEmpty()) {
            return v.getGameVersions().stream().anyMatch(gv -> major.equals(majorVersionOf(gv)));
        }
        return true;
    }

    /** 版本号匹配（精确或通配前缀） */
    public static boolean matchesVersion(String candidate, String raw) {
        if (candidate.equals(raw)) return true;
        if (raw.contains("x") || raw.contains("*") || raw.contains("X")) {
            String prefix = raw.replaceAll("[xX*].*$", "").replaceFirst("\\.$", "");
            return !prefix.isEmpty() && prefix.length() >= 2 && candidate.startsWith(prefix);
        }
        return false;
    }

    /** LoaderDetector 检测结果与安装类型匹配判断 */
    public static boolean matchesLoader(String detected, String loaderType) {
        switch (loaderType) {
            case "fabric":   return LoaderDetector.FABRIC.equals(detected);
            case "quilt":    return LoaderDetector.QUILT.equals(detected);
            case "neoforge": return LoaderDetector.NEOFORGE.equals(detected);
            case "forge":    return LoaderDetector.FORGE_MODERN.equals(detected) || LoaderDetector.FORGE_LEGACY.equals(detected);
            default:         return false;
        }
    }

    /** 已安装加载器名与界面加载器名是否对应（LoaderDetector 返回的是 "Forge (Modern)" 这类带后缀的名字） */
    public static boolean matchesInstalledLoader(String installed, String loaderName) {
        if (installed == null || installed.isBlank() || loaderName == null) return false;
        String a = installed.toLowerCase(java.util.Locale.ROOT);
        String b = loaderName.toLowerCase(java.util.Locale.ROOT);
        if (a.equals(LoaderDetector.VANILLA.toLowerCase(java.util.Locale.ROOT))) return false;
        return a.startsWith(b) || b.startsWith(a);
    }

    /** 找到已安装的加载器版本 JSON 的 id（继承自 mcVersion 且类型匹配），无则返回 null */
    public static String findLoaderVersionId(String gameDir, String mcVersion, String loaderType) {
        Path versionsDir = Paths.get(gameDir, "versions");
        if (!Files.isDirectory(versionsDir)) return null;
        try (var stream = Files.list(versionsDir)) {
            List<Path> dirs = stream.filter(Files::isDirectory).collect(Collectors.toList());
            for (Path dir : dirs) {
                String name = dir.getFileName().toString();
                if (name.equals(mcVersion)) continue;
                Path json = dir.resolve(name + ".json");
                if (!Files.exists(json)) continue;
                try {
                    JsonObject obj = new Gson().fromJson(
                            Files.readString(json, java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);
                    if (obj == null || !obj.has("id")) continue;
                    String parent = obj.has("inheritsFrom") ? obj.get("inheritsFrom").getAsString() : "";
                    if (!mcVersion.equals(parent)) continue;
                    // 统一判定入口（含补判）：裸 LoaderDetector 认不出 launchwrapper.Launch 主类的 Forge 版本
                    String detected = com.example.starlight.newui.ui.VersionIconKit.detectLoader(
                            obj, obj.get("id").getAsString());
                    if (matchesLoader(detected, loaderType)) return obj.get("id").getAsString();
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return null;
    }
}
