package com.example.starlight.newui.festival;


import com.example.starlight.util.I18N;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 节日注册表 + 检测：给定日期，返回当时生效的节日（含前后延展区间）。
 *
 * <p>壁纸约定：{@code src/main/resources/wallpaper/<id>.png|jpg|jpeg|webp}，
 * 未命中时回退默认背景。
 */
public final class FestivalCalendar {

    /** 配置键：节日主题开关（{@code auto}=自动生效，{@code off}=关闭） */
    public static final String CONFIG_KEY = "FestivalTheme";

    /** 是否启用节日主题：读取配置，缺省或非 off 一律视为启用 */
    public static boolean isEnabled(java.util.Map<String, String> config) {
        return config == null || !"off".equalsIgnoreCase(config.getOrDefault(CONFIG_KEY, "auto"));
    }

    /** 全部节日（按优先级：同一天命中多个时，靠前者优先） */
    private static final List<Festival> ALL = new ArrayList<>();

    static {
        // 春节（农历正月初一）
        ALL.add(new Festival("chunjie", I18N.get("festival.t0002"), I18N.get("festival.t0003"), true, 1, 1, 5, 9,
                "sparkles", "#dc2626", "#f59e0b", "#fff7ed", "#dc2626", "#e35d3a", "#d0482f"));
        // 元宵（农历正月十五）
        ALL.add(new Festival("yuanxiao", I18N.get("festival.t0004"), I18N.get("festival.t0005"), true, 1, 15, 2, 3,
                "sparkles", "#ea580c", "#f59e0b", "#fff7ed", "#ea580c", "#e35d3a", "#f59e0b"));
        // 清明（公历 4 月 5 日，节气近似）
        ALL.add(new Festival("qingming", I18N.get("festival.t0006"), I18N.get("festival.t0007"), false, 4, 5, 2, 3,
                "leaf", "#16a34a", "#4ade80", "#f0fdf4", "#16a34a", "#4ade80", "#16a34a"));
        // 端午（农历五月初五）
        ALL.add(new Festival("duanwu", I18N.get("festival.t0008"), I18N.get("festival.t0009"), true, 5, 5, 3, 3,
                "leaf", "#16a34a", "#0d9488", "#f0fdfa", "#16a34a", "#16a34a", "#0d9488"));
        // 七夕（农历七月初七）
        ALL.add(new Festival("qixi", I18N.get("festival.t0010"), I18N.get("festival.t0011"), true, 7, 7, 1, 2,
                "heart", "#e11d48", "#f472b6", "#fdf2f8", "#e11d48", "#f472b6", "#e11d48"));
        // 中秋（农历八月十五）
        ALL.add(new Festival("zhongqiu", I18N.get("festival.t0012"), I18N.get("festival.t0013"), true, 8, 15, 4, 4,
                "moon", "#0f2847", "#1e3a5f", "#fcd34d", "#d97706", "#e35d3a", "#d0482f"));
        // 重阳（农历九月初九）
        ALL.add(new Festival("chongyang", I18N.get("festival.t0014"), I18N.get("festival.t0015"), true, 9, 9, 1, 2,
                "sun", "#b45309", "#f59e0b", "#fffbeb", "#b45309", "#f59e0b", "#b45309"));
        // 国庆（公历 10 月 1 日）
        ALL.add(new Festival("guoqing", I18N.get("festival.t0016"), I18N.get("festival.t0017"), false, 10, 1, 3, 6,
                "star", "#dc2626", "#f59e0b", "#fff7ed", "#dc2626", "#dc2626", "#f59e0b"));
        // 万圣（公历 10 月 31 日）
        ALL.add(new Festival("wansheng", I18N.get("festival.t0018"), I18N.get("festival.t0019"), false, 10, 31, 4, 2,
                "ghost", "#7c3aed", "#ea580c", "#f5f3ff", "#ea580c", "#ea580c", "#7c3aed"));
        // 圣诞（公历 12 月 25 日）
        ALL.add(new Festival("shengdan", I18N.get("festival.t0020"), I18N.get("festival.t0021"), false, 12, 25, 6, 3,
                "gift", "#b91c1c", "#166534", "#fef2f2", "#b91c1c", "#b91c1c", "#166534"));
        // 元旦（公历 1 月 1 日）
        ALL.add(new Festival("yuandan", I18N.get("festival.t0022"), I18N.get("festival.t0023"), false, 1, 1, 3, 3,
                "sparkles", "#2563eb", "#7c3aed", "#eff6ff", "#2563eb", "#2563eb", "#7c3aed"));
    }

    private FestivalCalendar() {
    }

    /** 当前日期命中的节日（无则返回 null） */
    public static Festival current() {
        return at(LocalDate.now());
    }

    /** 指定日期命中的节日（含前后延展区间；同一天多个时按注册顺序取第一个） */
    public static Festival at(LocalDate date) {
        if (date == null) return null;
        for (Festival f : ALL) {
            if (activeOn(f, date)) return f;
        }
        return null;
    }

    /** 该节日在给定日期是否生效（含前后延展） */
    private static boolean activeOn(Festival f, LocalDate date) {
        LocalDate anchor = f.lunar ? lunarDate(f, date.getYear()) : LocalDate.of(date.getYear(), f.month, f.day);
        if (anchor == null) return false;
        LocalDate start = anchor.minusDays(f.beforeDays);
        LocalDate end = anchor.plusDays(f.afterDays);
        return !date.isBefore(start) && !date.isAfter(end);
    }

    /** 农历节日在公历某年的锚点日期（跨年处理：11、12 月节日按上一年农历算） */
    private static LocalDate lunarDate(Festival f, int solarYear) {
        // 农历年可能前一年 11 月/12 月的节日仍在公历当年，简化处理：尝试当年与上一年农历
        for (int y = solarYear; y >= solarYear - 1; y--) {
            LunarCalendar.LunarDate ld = LunarCalendar.solarToLunar(LocalDate.of(y, 1, 1));
            if (ld == null) continue;
            // 从当年 1 月 1 日起，逐日推进直到农历月日匹配（区间最坏跨到次年 2 月）
            LocalDate probe = LocalDate.of(y, 1, 1);
            int guard = 0;
            while (guard++ < 420) {
                LunarCalendar.LunarDate cur = LunarCalendar.solarToLunar(probe);
                if (cur != null && !cur.leap && cur.month == f.month && cur.day == f.day) {
                    return probe;
                }
                probe = probe.plusDays(1);
            }
        }
        return null;
    }

    /** 全部节日列表（供测试/展示） */
    public static List<Festival> all() {
        return new ArrayList<>(ALL);
    }
}
