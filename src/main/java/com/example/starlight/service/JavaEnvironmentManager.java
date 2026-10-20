/*
 * Decompiled with CFR 0.152.
 */
package com.example.starlight.service;

import com.example.starlight.gui.UIGeneralControlClass;
import com.example.starlight.download.task.JavaInstall;
import com.example.starlight.model.CallbackInterfaces;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileAttribute;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class JavaEnvironmentManager {
    /** 启动器自动下载的 JDK 安装根目录（统一走 AppDirs；不再与构建脚本 tools/ 混放） */
    public static final String JAVA_TOOLS_DIR =
            com.example.starlight.config.AppDirs.javaDir().toString();
    public static final String JDK_DOWNLOAD_PAGE = "https://adoptium.net/temurin/releases/";

    private JavaEnvironmentManager() {
    }

    public static boolean isJavaUsable(String javaPath) {
        String cmd = javaPath == null || javaPath.trim().isEmpty() || "java".equalsIgnoreCase(javaPath.trim()) ? "java" : javaPath.trim();
        File exe = new File(cmd);
        if (!cmd.equalsIgnoreCase("java") && !exe.isFile()) {
            return false;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd, "-version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean ok = p.waitFor(15L, TimeUnit.SECONDS);
            try {
                p.destroyForcibly();
            }
            catch (Throwable throwable) {
                // empty catch block
            }
            return ok;
        }
        catch (Throwable t) {
            return false;
        }
    }

    public static String findInstalledJavaExe(Path root) {
        if (root == null || !Files.isDirectory(root, new LinkOption[0])) {
            return null;
        }
        String[] best = new String[]{null};
        try {
            Files.walk(root, new FileVisitOption[0]).filter(p -> Files.isRegularFile(p, new LinkOption[0])).filter(p -> p.getFileName().toString().equalsIgnoreCase("java.exe") || p.getFileName().toString().equalsIgnoreCase("java")).filter(p -> p.getParent() != null && p.getParent().getFileName() != null && p.getParent().getFileName().toString().equalsIgnoreCase("bin")).forEach(p -> {
                String s = p.toString();
                if (best[0] == null || s.length() < best[0].length()) {
                    best[0] = s;
                }
            });
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        return best[0];
    }

    public static String ensureJavaForLaunch(String javaPath, String versionId, String gameDir, CallbackInterfaces.ProgressCallback onProgress) {
        try {
            String current;
            String string = current = javaPath == null || javaPath.trim().isEmpty() ? "java" : javaPath.trim();
            if (JavaEnvironmentManager.isJavaUsable(current)) {
                return current;
            }
            int requiredMajor = UIGeneralControlClass.getRequiredJavaMajorFromJson(gameDir, versionId);
            String jdk = JavaEnvironmentManager.jdkNameForMajor(requiredMajor);
            JavaEnvironmentManager.report(onProgress, "\u672a\u68c0\u6d4b\u5230\u53ef\u7528\u7684 Java \u73af\u5883\uff0c\u6b63\u5728\u81ea\u52a8\u4e0b\u8f7d\u5b89\u88c5 " + jdk.toUpperCase(Locale.ROOT) + "\uff08\u7ea6 200MB\uff0c\u9996\u6b21\u5b89\u88c5\u9700\u8981\u51e0\u5206\u949f\uff09");
            Path target = Paths.get(JAVA_TOOLS_DIR, new String[0]);
            try {
                Files.createDirectories(target, new FileAttribute[0]);
            }
            catch (IOException iOException) {
                // empty catch block
            }
            String existing = JavaEnvironmentManager.findInstalledJavaExe(target);
            if (existing != null && JavaEnvironmentManager.isJavaUsable(existing)) {
                JavaEnvironmentManager.report(onProgress, "\u68c0\u6d4b\u5230\u5df2\u5b89\u88c5\u7684 Java: " + existing);
                return JavaEnvironmentManager.configureAndPersist(existing, onProgress);
            }
            String installed = JavaEnvironmentManager.installBlocking(jdk, target, onProgress);
            if (installed == null) {
                return null;
            }
            return JavaEnvironmentManager.configureAndPersist(installed, onProgress);
        }
        catch (Throwable t) {
            JavaEnvironmentManager.report(onProgress, "Java \u73af\u5883\u68c0\u67e5\u5931\u8d25: " + t.getMessage());
            return null;
        }
    }

    private static String configureAndPersist(String javaExe, CallbackInterfaces.ProgressCallback onProgress) {
        try {
            Map<String, String> cfg = UIGeneralControlClass.readConfig();
            cfg.put("JavaPath", javaExe);
            UIGeneralControlClass.saveConfig(cfg);
            JavaEnvironmentManager.configureUserEnvironment(javaExe);
            JavaEnvironmentManager.report(onProgress, "Java \u73af\u5883\u5c31\u7eea: " + javaExe);
            return javaExe;
        }
        catch (Throwable t) {
            JavaEnvironmentManager.report(onProgress, "Java \u5df2\u5b89\u88c5\u4f46\u914d\u7f6e\u5931\u8d25: " + t.getMessage());
            return javaExe;
        }
    }

    private static void configureUserEnvironment(String javaExe) {
        File bin = new File(javaExe).getParentFile();
        if (bin == null) {
            return;
        }
        File home = bin.getParentFile();
        if (home == null) {
            return;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (!windows) {
            return;
        }
        try {
            new ProcessBuilder("setx", "JAVA_HOME", home.getAbsolutePath()).redirectErrorStream(true).start();
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        try {
            new ProcessBuilder("setx", "PATH", bin.getAbsolutePath() + ";%PATH%").redirectErrorStream(true).start();
        }
        catch (Throwable throwable) {
            // empty catch block
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static String installBlocking(String jdkVersion, Path targetDir, final CallbackInterfaces.ProgressCallback onProgress) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(){
            private int lastPercent = -1;

            @Override
            public synchronized void write(byte[] b, int off, int len) {
                super.write(b, off, len);
                String chunk = new String(b, off, len, StandardCharsets.UTF_8);
                int pIdx = chunk.indexOf(37);
                if (pIdx > 0) {
                    for (int i = pIdx - 1; i >= 0; --i) {
                        if (Character.isDigit(chunk.charAt(i))) continue;
                        try {
                            int pct = Integer.parseInt(chunk.substring(i + 1, pIdx));
                            if (pct == this.lastPercent) break;
                            this.lastPercent = pct;
                            JavaEnvironmentManager.report(onProgress, chunk.trim());
                        }
                        catch (NumberFormatException numberFormatException) {}
                        break;
                    }
                }
                if (chunk.contains("ERROR") || chunk.contains("\u5931\u8d25") || chunk.contains("\u5b8c\u6210") || chunk.contains("SUCCESS")) {
                    JavaEnvironmentManager.report(onProgress, chunk.trim());
                }
            }
        };
        PrintStream captured = new PrintStream((OutputStream)baos, true, StandardCharsets.UTF_8);
        try {
            System.setProperty("starlight.embed", "true");
            JavaInstall.setOutput(captured);
            JavaInstall.main(new String[]{jdkVersion, "--json", targetDir == null ? JAVA_TOOLS_DIR : targetDir.toString()});
            captured.flush();
        }
        catch (Throwable t) {
            JavaEnvironmentManager.report(onProgress, "Java \u81ea\u52a8\u5b89\u88c5\u5931\u8d25: " + t.getMessage());
            String string = null;
            return string;
        }
        finally {
            JavaInstall.setOutput(System.out);
        }
        String javaExe = JavaEnvironmentManager.findInstalledJavaExe(targetDir == null ? Paths.get(JAVA_TOOLS_DIR, new String[0]) : targetDir);
        if (javaExe == null) {
            JavaEnvironmentManager.report(onProgress, "Java \u4e0b\u8f7d\u5b8c\u6210\uff0c\u4f46\u672a\u627e\u5230 java.exe\uff08\u5b89\u88c5\u76ee\u5f55\u7ed3\u6784\u5f02\u5e38\uff09");
            return null;
        }
        return javaExe;
    }

    public static void installJavaInteractive(String jdkVersion, CallbackInterfaces.ProgressCallback onProgress, CallbackInterfaces.ResultCallback<String> onResult) {
        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            String javaExe = JavaEnvironmentManager.installBlocking(jdkVersion, Paths.get(JAVA_TOOLS_DIR, new String[0]), onProgress);
            if (javaExe == null) {
                onResult.onError("JDK \u5b89\u88c5\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u7f51\u7edc\u540e\u91cd\u8bd5\uff0c\u6216\u5230\u5b98\u7f51\u624b\u52a8\u4e0b\u8f7d");
                return;
            }
            String finalPath = JavaEnvironmentManager.configureAndPersist(javaExe, onProgress);
            onResult.onSuccess(finalPath);
        });
    }

    public static String jdkNameForMajor(int requiredMajor) {
        if (requiredMajor <= 8) {
            return "jdk8";
        }
        if (requiredMajor <= 11) {
            return "jdk11";
        }
        if (requiredMajor <= 17) {
            return "jdk17";
        }
        if (requiredMajor <= 21) {
            return "jdk21";
        }
        return "jdk25";
    }

    private static void report(CallbackInterfaces.ProgressCallback onProgress, String msg) {
        if (onProgress != null) {
            try {
                onProgress.onProgress(0, msg);
            }
            catch (Throwable throwable) {
                // empty catch block
            }
        }
    }

    public static String readJavaVersion(String javaPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(javaPath == null || javaPath.trim().isEmpty() ? "java" : javaPath, "-version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                return r.readLine();
            }
        } catch (IOException e) {
            return null;
        }
    }
}
