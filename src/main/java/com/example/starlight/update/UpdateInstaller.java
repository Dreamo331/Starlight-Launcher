package com.example.starlight.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 自更新安装辅助（从 LauncherView 抽离，逻辑与原实现保持一致）。
 *
 * <p>负责：定位当前运行中的启动器 exe、校验更新包 MD5、生成自替换更新脚本。
 * 全部为无状态静态方法，不依赖 JavaFX，可在后台线程调用。
 */
public final class UpdateInstaller {

    private UpdateInstaller() {
    }

    /** MD5 读取缓冲区（64KB，与原实现一致） */
    private static final int BUFFER_SIZE = 64 * 1024;

    /** 定位当前运行中的启动器可执行文件（正式打包的 exe），失败返回 null */
    public static Path getCurrentExePath() {
        try {
            java.util.Optional<String> cmd = ProcessHandle.current().info().command();
            if (cmd.isPresent()) {
                Path p = Paths.get(cmd.get());
                if (Files.isRegularFile(p)) return p;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 计算文件 MD5（小写十六进制） */
    public static String md5Of(Path file) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        try (java.io.InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /**
     * 生成自替换更新脚本（sl-update.bat，纯 ASCII 避免编码问题）：
     * 轮询尝试用 .new 文件覆盖当前 exe —— 启动器进程退出后文件锁释放即成功；
     * 覆盖成功后启动新版本并删除脚本自身，最长等待 10 分钟。
     */
    public static String buildUpdateBatContent(String exeName) {
        String nl = "\r\n";
        StringBuilder sb = new StringBuilder();
        sb.append("@echo off").append(nl);
        sb.append("setlocal").append(nl);
        sb.append("cd /d \"%~dp0\"").append(nl);
        sb.append("if not exist \"").append(exeName).append(".new\" exit /b 1").append(nl);
        sb.append("set /a n=0").append(nl);
        sb.append(":try").append(nl);
        sb.append("move /y \"").append(exeName).append(".new\" \"").append(exeName).append("\" >nul 2>&1").append(nl);
        sb.append("if not errorlevel 1 goto done").append(nl);
        sb.append("set /a n+=1").append(nl);
        sb.append("if %n% GEQ 600 goto fail").append(nl);
        sb.append("timeout /t 1 /nobreak >nul").append(nl);
        sb.append("goto try").append(nl);
        sb.append(":done").append(nl);
        sb.append("start \"\" \"").append(exeName).append("\"").append(nl);
        sb.append("exit /b 0").append(nl);
        sb.append(":fail").append(nl);
        sb.append("exit /b 1").append(nl);
        return sb.toString();
    }

    /** 生成随机 length 位字母数字字符串（背景图片重命名用，字母+数字避免混淆字符） */
    public static String randomAlphanumeric(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(length);
        java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(rnd.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
