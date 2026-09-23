package com.example.starlight.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 文本匹配工具集（Mod 中文搜索链路专用，实现思路对齐 HMCL 的 StringUtils）。
 *
 * <p>提供四类能力：
 * <ul>
 *   <li>{@link #containsChinese(String)} —— 判断查询串是否为中文查询；</li>
 *   <li>{@link #tokenize(String)} —— 把英文名拆成单词（去掉标点），用于拼装英文检索词；</li>
 *   <li>{@link #normalizeForMatch(String)} —— 归一化（小写 + 仅留字母数字），用于标题模糊匹配；</li>
 *   <li>{@link LevCalculator} / {@link LongestCommonSubsequence} —— 编辑距离与最长公共子序列，
 *       前者用于结果排序，后者用于中文关键词召回。</li>
 * </ul>
 *
 * <p>两个算法类都会复用内部 DP 缓冲区，适合在循环中反复调用（避免每次分配二维数组）。
 * 注意：它们<strong>不是线程安全</strong>的，请在单线程内复用同一个实例。
 */
public final class TextUtils {

    /** 拆词时视为分隔符的标点集合（不含 CJK，中文片段会原样保留为一个 token） */
    private static final String TOKEN_SEPARATORS = "-_/\\&()[]{}|,!?~•:;.'\"+*#@$%^=<>\t\r\n";

    private TextUtils() {
    }

    /** 字符串是否为 null 或全空白 */
    public static boolean isBlank(String str) {
        if (str == null) return true;
        for (int i = 0; i < str.length(); i++) {
            if (!Character.isWhitespace(str.charAt(i))) return false;
        }
        return true;
    }

    /** 字符串是否非空白 */
    public static boolean isNotBlank(String str) {
        return !isBlank(str);
    }

    /**
     * 是否包含中文（判定范围与 HMCL 一致：CJK 统一表意文字基本区 U+4E00–U+9FA5）。
     * <p>用于决定是否走「中文 → 英文」翻译搜索；扩展区汉字与日文假名不算中文，会按原样直搜。
     */
    public static boolean containsChinese(String str) {
        if (str == null) return false;
        for (int i = 0; i < str.length(); i++) {
            char ch = str.charAt(i);
            if (ch >= '\u4e00' && ch <= '\u9fa5') return true;
        }
        return false;
    }

    /**
     * 按空白与常见标点把字符串拆成单词列表（空片段被丢弃）。
     * <p>例：{@code "Just Enough Items (JEI)"} → {@code [Just, Enough, Items, JEI]}。
     */
    public static List<String> tokenize(String str) {
        List<String> result = new ArrayList<>();
        if (str == null || str.isEmpty()) return result;

        StringBuilder current = new StringBuilder();
        for (int i = 0; i < str.length(); ) {
            int cp = str.codePointAt(i);
            if (Character.isWhitespace(cp) || TOKEN_SEPARATORS.indexOf(cp) >= 0) {
                if (current.length() > 0) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        if (current.length() > 0) result.add(current.toString());
        return result;
    }

    /**
     * 归一化用于「标题 → 字典条目」匹配：转小写，并且只保留字母与数字。
     * <p>例：{@code "Just Enough Items (JEI)"} → {@code "justenoughitemsjei"}。
     * 中文汉字属于字母，会被保留。
     */
    public static String normalizeForMatch(String str) {
        if (str == null || str.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(str.length());
        for (int i = 0; i < str.length(); ) {
            int cp = str.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) {
                sb.appendCodePoint(Character.toLowerCase(cp));
            }
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    /**
     * 去掉标题的括号/冒号后缀，取主干部分。
     * <p>例：{@code "Just Enough Items (JEI)"} → {@code "Just Enough Items"}；
     * 无后缀时原样返回。
     */
    public static String stripBracketSuffix(String str) {
        if (str == null || str.isEmpty()) return str;
        int cut = str.length();
        // 半角/全角括号与冒号（\uFF08 \uFF1A 为全角括号与全角冒号）
        String marks = "([{\u3010\uFF08:\uFF1A";
        for (int i = 0; i < str.length(); i++) {
            if (marks.indexOf(str.charAt(i)) >= 0) {
                cut = i;
                break;
            }
        }
        String head = str.substring(0, cut).trim();
        return head.isEmpty() ? str : head;
    }

    /** 取字符串最后一段路径（用于从页面 URL 反推 slug） */
    public static String lastPathSegment(String url) {
        if (isBlank(url)) return "";
        String s = url.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        int idx = s.lastIndexOf('/');
        return idx >= 0 && idx + 1 < s.length() ? s.substring(idx + 1) : "";
    }

    /** 去掉 .jar / .jar.disabled 等后缀，得到文件名主干 */
    public static String stripJarExtension(String fileName) {
        if (fileName == null) return "";
        String name = fileName;
        if (name.toLowerCase(Locale.ROOT).endsWith(".disabled")) {
            name = name.substring(0, name.length() - ".disabled".length());
        }
        if (name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            name = name.substring(0, name.length() - ".jar".length());
        }
        return name;
    }

    /**
     * 编辑距离（Levenshtein 距离）计算器，内部复用滚动数组。
     *
     * <p>与 HMCL 的实现等价（同样的插入/删除/替换代价为 1 的递推），
     * 但每次计算都会重置起始行，因此可以安全地跨不同长度的字符串反复复用。
     * 复用同一实例可避免在结果排序循环中反复分配数组。
     */
    public static final class LevCalculator {
        private int[] prev = new int[0];
        private int[] cur = new int[0];

        /** 计算 a 与 b 的编辑距离 */
        public int calc(CharSequence a, CharSequence b) {
            int n = a.length();
            int m = b.length();
            if (n == 0) return m;
            if (m == 0) return n;

            if (prev.length < m + 1) {
                prev = new int[m + 1];
                cur = new int[m + 1];
            }

            for (int j = 0; j <= m; j++) prev[j] = j;
            for (int i = 1; i <= n; i++) {
                cur[0] = i;
                char ca = a.charAt(i - 1);
                for (int j = 1; j <= m; j++) {
                    int cost = ca == b.charAt(j - 1) ? 0 : 1;
                    int del = prev[j] + 1;
                    int ins = cur[j - 1] + 1;
                    int sub = prev[j - 1] + cost;
                    cur[j] = Math.min(del, Math.min(ins, sub));
                }
                int[] swap = prev;
                prev = cur;
                cur = swap;
            }
            return prev[m];
        }
    }

    /**
     * 最长公共子序列（LCS）计算器，复用二维 DP 表。
     *
     * <p>中文搜索用 LCS 而不是 {@code contains} 来做关键词召回：用户少打、多打、
     * 顺序颠倒或使用缩写时，只要共同字符足够多仍然能命中。
     * 构造时需给出两个字符串的最大长度，超长会抛 {@link IllegalArgumentException}。
     */
    public static final class LongestCommonSubsequence {
        private final int maxLengthA;
        private final int maxLengthB;
        private final int[][] f;

        public LongestCommonSubsequence(int maxLengthA, int maxLengthB) {
            this.maxLengthA = Math.max(0, maxLengthA);
            this.maxLengthB = Math.max(0, maxLengthB);
            this.f = new int[this.maxLengthA + 1][this.maxLengthB + 1];
        }

        /** 计算两个字符串的最长公共子序列长度 */
        public int calc(CharSequence a, CharSequence b) {
            int la = a.length();
            int lb = b.length();
            if (la > maxLengthA || lb > maxLengthB) {
                throw new IllegalArgumentException("Too large length: " + la + "/" + lb
                        + " (max " + maxLengthA + "/" + maxLengthB + ")");
            }
            for (int i = 1; i <= la; i++) {
                for (int j = 1; j <= lb; j++) {
                    if (a.charAt(i - 1) == b.charAt(j - 1)) {
                        f[i][j] = f[i - 1][j - 1] + 1;
                    } else {
                        f[i][j] = Math.max(f[i - 1][j], f[i][j - 1]);
                    }
                }
            }
            return f[la][lb];
        }
    }
}
