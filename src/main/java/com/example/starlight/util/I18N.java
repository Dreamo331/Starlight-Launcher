/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.util;

import java.text.MessageFormat;
import java.util.HashSet;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;

/**
 * i18n utility based on Java ResourceBundle.
 * Default locale: zh_CN. Dynamic switching via setLocale().
 */
public class I18N {

    private static final String BASE_NAME = "i18n.messages";
    private static ResourceBundle bundle;
    private static final Set<Runnable> listeners = new HashSet<>();

    static {
        bundle = ResourceBundle.getBundle(BASE_NAME, Locale.CHINA);
    }

    private I18N() {}

    /** Get localized text by key */
    public static String get(String key) {
        try {
            return bundle.getString(key);
        } catch (Exception e) {
            return "!" + key + "!";
        }
    }

    /** Get localized text with MessageFormat arguments */
    public static String get(String key, Object... args) {
        try {
            return MessageFormat.format(bundle.getString(key), args);
        } catch (Exception e) {
            return "!" + key + "!";
        }
    }

    /** Switch locale, update bundle, notify listeners */
    public static void setLocale(Locale locale) {
        bundle = ResourceBundle.getBundle(BASE_NAME, locale);
        for (Runnable r : listeners) {
            try { r.run(); } catch (Exception ignored) {}
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

    /** Register a listener for locale changes */
    public static void addListener(Runnable r) {
        listeners.add(r);
    }

    /** Remove a locale change listener */
    public static void removeListener(Runnable r) {
        listeners.remove(r);
    }
}
