/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.util;

import java.text.MessageFormat;
import java.util.HashSet;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;

/**
 * i18n utility based on Java ResourceBundle.
 *
 * <p>默认语言 zh_CN（界面文案的正本），可通过配置键 {@code Language} 切换为 en_US。
 * 与旧实现相比补了两点，否则界面无法安全地整体改成键引用：
 *
 * <ul>
 *   <li><b>缺失键兜底</b>：不再返回 {@code !key!}，而是回退中文包，再回退键名本身。
 *       少译一条不会在界面上爆出感叹号。</li>
 *   <li><b>语言变更监听</b>：保留原有 addListener，供重启前需要即时重建的少量控件使用。</li>
 * </ul>
 *
 * <p>注意：语言切换是「重启后生效」。{@link #setLocale} 只在启动早期调用一次，
 * 界面文本在页面构建时取值，因此不要在运行期指望旧页面自动改写。
 */
public class I18N {

    private static final String BASE_NAME = "i18n.messages";

    /** 中文正本包：任何缺失键的兜底来源 */
    private static ResourceBundle fallback;
    private static ResourceBundle bundle;
    private static final Set<Runnable> listeners = new HashSet<>();

    static {
        // 静态初始化绝不能抛异常：这里一抛，I18N 类初始化即失败，
        // 之后每次 I18N.get 都会连锁 NoClassDefFoundError，整个启动器起不来。
        // 资源缺失（打包漏了 i18n/、工作目录不对）时退化为「显示键名」——难看但可用。
        fallback = load(Locale.CHINA);
        bundle = fallback;
    }

    /**
     * 按语言取资源包。
     *
     * <p><b>为什么不能用 ResourceBundle.getBundle(base, Locale.ENGLISH) 直接了事</b>：
     * 候选链是 en → en_US → <b>默认 Locale</b> → 基础包。中文系统上默认 Locale 是 zh_CN，
     * 于是请求英文会先命中 messages_zh_CN.properties，永远拿不到英文；
     * 反过来在英文系统上请求中文也会被默认 Locale 抢先。
     *
     * <p>所以这里把「默认 Locale」从候选链里摘出去：临时把默认切成目标语言，
     * 加载完立刻还原。英文走基础包 messages.properties（无后缀），中文走 messages_zh_CN。
     */
    private static ResourceBundle load(Locale locale) {
        if (locale == null) locale = Locale.CHINA;
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(locale);
            return ResourceBundle.getBundle(BASE_NAME, locale);
        } catch (MissingResourceException e) {
            // 目标语言包缺失时退回中文正本（若连它都没有，则全部退化为基础包）
            try {
                Locale.setDefault(Locale.CHINA);
                return ResourceBundle.getBundle(BASE_NAME, Locale.CHINA);
            } catch (MissingResourceException ignored) {
                return null;
            }
        } finally {
            Locale.setDefault(saved);
        }
    }

    private I18N() {
    }

    /** Get localized text by key */
    public static String get(String key) {
        if (key == null || key.isEmpty()) return "";
        if (bundle == null) return key;
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            // 当前语言缺这条 → 回退中文正本 → 再不行回退键名（便于定位漏配）
            if (fallback != null) {
                try {
                    return fallback.getString(key);
                } catch (MissingResourceException ignored) {
                    // 落到下面返回键名
                }
            }
            return key;
        }
    }

    /** Get localized text with MessageFormat arguments */
    public static String get(String key, Object... args) {
        String pattern = get(key);
        if (args == null || args.length == 0 || pattern.equals(key)) return pattern;
        try {
            // MessageFormat 里的单引号是语法字符，文案中若出现普通撇号需转义为 ''
            return MessageFormat.format(pattern, args);
        } catch (IllegalArgumentException e) {
            return pattern;
        }
    }

    /** Switch locale, update bundle, notify listeners */
    public static void setLocale(Locale locale) {
        if (locale == null) locale = Locale.CHINA;
        ResourceBundle loaded = load(locale);
        // 加载失败时保留原资源包（而不是把 bundle 置空导致整界面变成键名）
        if (loaded != null) bundle = loaded;
        for (Runnable r : new HashSet<>(listeners)) {
            try {
                r.run();
            } catch (Exception ignored) {
                // 单个监听器失败不影响整体切换
            }
        }
    }

    /** Switch to Chinese */
    public static void useChinese() {
        setLocale(Locale.CHINA);
    }

    /** Switch to English */
    public static void useEnglish() {
        setLocale(Locale.ENGLISH);
    }

    /** Get current ResourceBundle for FXMLLoader */
    public static ResourceBundle getBundle() {
        return bundle;
    }

    /** Get current Locale */
    public static Locale getCurrentLocale() {
        return bundle.getLocale();
    }

    /**
     * 配置键 {@code Language} 的取值 → Locale。
     *
     * <p>唯一映射处：启动早期（LauncherView）与「主界面 → 显示语言」页共用，
     * 避免两处各写一份三元表达式后走岔。只支持 zh_CN / en_US；
     * 历史配置里的其它值（如已下线的 ja_JP）一律回落简体中文。
     */
    public static Locale localeOf(String languageCode) {
        if ("en_US".equalsIgnoreCase(languageCode) || "en".equalsIgnoreCase(languageCode)) {
            return Locale.ENGLISH;
        }
        return Locale.CHINA;
    }

    /** 按配置值切换语言（启动早期调用一次） */
    public static void applyConfig(String languageCode) {
        setLocale(localeOf(languageCode));
    }

    /** Register a listener for locale changes */
    public static void addListener(Runnable r) {
        if (r != null) listeners.add(r);
    }

    /** Remove a locale change listener */
    public static void removeListener(Runnable r) {
        listeners.remove(r);
    }
}
