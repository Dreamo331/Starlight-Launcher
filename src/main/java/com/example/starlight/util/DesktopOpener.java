/*
 * Decompiled with CFR 0.152.
 */
package com.example.starlight.util;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.util.Locale;

public final class DesktopOpener {
    private DesktopOpener() {
    }

    public static boolean openUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        String trimmed = url.trim();
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith("http://") && !trimmed.toLowerCase(Locale.ROOT).startsWith("https://")) {
            return false;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean windows = os.contains("win");
        if (windows) {
            try {
                new ProcessBuilder("rundll32.exe", "url.dll,FileProtocolHandler", trimmed).start();
                return true;
            }
            catch (Throwable throwable) {
                // empty catch block
            }
        }
        try {
            ProcessBuilder pb = windows ? new ProcessBuilder("cmd", "/c", "start", "", "\"" + trimmed + "\"") : (os.contains("mac") ? new ProcessBuilder("open", trimmed) : new ProcessBuilder("xdg-open", trimmed));
            pb.start();
            return true;
        }
        catch (Throwable throwable) {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(new URI(trimmed));
                    return true;
                }
            }
            catch (Throwable throwable2) {
                // empty catch block
            }
            return false;
        }
    }

    public static boolean openPath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean windows = os.contains("win");
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(new File(path));
                return true;
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        if (windows) {
            try {
                new ProcessBuilder("explorer.exe", path).start();
                return true;
            }
            catch (Throwable throwable) {
                // empty catch block
            }
        }
        try {
            ProcessBuilder pb = os.contains("mac") ? new ProcessBuilder("open", path) : (windows ? new ProcessBuilder("cmd", "/c", "start", "", "\"" + path + "\"") : new ProcessBuilder("xdg-open", path));
            pb.start();
            return true;
        }
        catch (Throwable throwable) {
            return false;
        }
    }
}
