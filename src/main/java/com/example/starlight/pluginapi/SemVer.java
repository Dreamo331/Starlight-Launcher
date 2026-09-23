package com.example.starlight.pluginapi;

import java.util.Objects;

/**
 * 语义化版本（SemVer）工具类，用于插件握手时的版本兼容性校验。
 *
 * <p>校验规则（需求指定）：<strong>主版本号必须完全一致</strong>，
 * 提供方的次版本号和修订号必须 <strong>&gt;=</strong> 要求方。
 * <ul>
 *   <li>提供 1.5.2、要求 1.3.0 → 兼容；</li>
 *   <li>提供 1.5.2、要求 1.6.0 → 不兼容（次版本不足）；</li>
 *   <li>提供 2.0.0、要求 1.0.0 → 不兼容（主版本不一致）。</li>
 * </ul>
 */
public final class SemVer implements Comparable<SemVer> {

    private final int major;
    private final int minor;
    private final int patch;

    private SemVer(int major, int minor, int patch) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
    }

    /**
     * 解析版本字符串，如 {@code "1.2.3"}、{@code "1.2"}、{@code "1.2.3-beta.1"}。
     * <ul>
     *   <li>少于 3 段时，缺失段按 0 处理（{@code "1.2"} → 1.2.0）；</li>
     *   <li>{@code -} 预发布 / {@code +} 构建元数据部分被忽略（{@code "1.2.3-beta.1"} → 1.2.3）；</li>
     *   <li>含非数字段或超过 3 段视为非法，抛出 {@link IllegalArgumentException}。</li>
     * </ul>
     */
    public static SemVer parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("版本号不能为空");
        }
        // 去掉预发布 / 构建元数据部分（如 -beta.1、+build5）
        String core = text.split("[-+]", 2)[0];
        String[] parts = core.split("\\.");
        if (parts.length == 0 || parts.length > 3) {
            throw new IllegalArgumentException("非法版本号: " + text);
        }
        int major = parseSegment(parts[0], text);
        int minor = parts.length > 1 ? parseSegment(parts[1], text) : 0;
        int patch = parts.length > 2 ? parseSegment(parts[2], text) : 0;
        return new SemVer(major, minor, patch);
    }

    private static int parseSegment(String segment, String original) {
        if (!segment.matches("\\d+")) {
            throw new IllegalArgumentException("非法版本号（含非数字段）: " + original);
        }
        return Integer.parseInt(segment);
    }

    /**
     * 兼容性判定：本版本为<strong>提供方</strong>，{@code required} 为<strong>要求方</strong>。
     * 主版本必须完全一致，且本版本次/修订 &gt;= 要求方。
     */
    public boolean isCompatibleWith(SemVer required) {
        if (major != required.major) {
            return false;
        }
        if (minor > required.minor) {
            return true;
        }
        return minor == required.minor && patch >= required.patch;
    }

    public int major() {
        return major;
    }

    public int minor() {
        return minor;
    }

    public int patch() {
        return patch;
    }

    @Override
    public int compareTo(SemVer o) {
        int c = Integer.compare(major, o.major);
        if (c != 0) return c;
        c = Integer.compare(minor, o.minor);
        if (c != 0) return c;
        return Integer.compare(patch, o.patch);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SemVer that)) return false;
        return major == that.major && minor == that.minor && patch == that.patch;
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
