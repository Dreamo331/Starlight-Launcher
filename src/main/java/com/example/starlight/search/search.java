/**
 * (C) Copyright 2026 Starlight. All rights reserved.
 * 搜索方法
 * 搜索引擎打算使用Bing
 * https://cn.bing.com/search?q= + 用户输入的数据UTF-8编码后的字符串
 */
package com.example.starlight.search;

import java.awt.Desktop;
import java.net.URI;
import java.net.URLEncoder;

/**
 * Utility class providing a simple search helper that opens the default
 * system browser to perform a Bing search for the given query.
 *
 * Note: uses the older URLEncoder.encode(String, String) overload for
 * maximum compatibility with older JDKs (no JDK upgrade required).
 */
public final class search {
    /**
     * Open the default browser to search the given question on Bing.
     * Safe for older JDKs (uses "UTF-8" string constant for encoding).
     */
    public static void search(String question) {
        if (question == null || question.trim().isEmpty()) return;

        if (!Desktop.isDesktopSupported()) {
            System.err.println("Desktop not supported; cannot open browser.");
            return;
        }

        Desktop desktop = Desktop.getDesktop();
        if (!desktop.isSupported(Desktop.Action.BROWSE)) {
            System.err.println("BROWSE action not supported on this platform.");
            return;
        }

        try {
            // Use the older String-based overload to avoid requiring newer JDK APIs
            String encoded = URLEncoder.encode(question.trim(), "UTF-8");
            String url = "https://cn.bing.com/search?q=" + encoded;
            desktop.browse(new URI(url));
        } catch (Exception e) {
            System.err.println("Search failed: " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }
}

