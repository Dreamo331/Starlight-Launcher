package com.example.starlight.newui.framegen;


import com.example.starlight.util.I18N;
import com.example.starlight.config.AppDirs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 帧插值设置模型。
 *
 * <p>与现有 {@code FrameGenCoordinator} 的 "倍率 / maxFps" 语义完全独立：
 * 本类管理新的插值语义（质量档位、目标帧率、竞技模式）。
 * 两者共用 {@link AppDirs#framegenDir()} 目录，但文件名不同，互不覆盖。
 *
 * <p><b>目录副作用约定</b>：{@link #load()} 不创建任何目录，
 * 只有 {@link #save()} 才落盘。
 */
public final class FrameGenSettings {

    private static final Path CONFIG_PATH =
            AppDirs.framegenDir().resolve("interp.properties");

    /** 是否启用插值 */
    private boolean enabled = false;
    /** 质量档位 0=blend 1=blockmatch 2=neural(当前降级为 1) */
    private int quality = 1;
    /** 目标输出帧率 */
    private int targetFps = 120;
    /** 竞技模式：保留捕获链路但直通呈现，不做插值 */
    private boolean competitiveMode = false;
    /** 是否在游戏启动时自动拉起 host */
    private boolean autoStart = true;

    private FrameGenSettings() {
    }

    public static FrameGenSettings load() {
        FrameGenSettings s = new FrameGenSettings();
        if (!Files.isRegularFile(CONFIG_PATH)) {
            return s;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(CONFIG_PATH)) {
            p.load(in);
        } catch (IOException e) {
            System.err.println("[FrameGen] 读取设置失败: " + e.getMessage());
            return s;
        }
        s.enabled = Boolean.parseBoolean(p.getProperty("enabled", "false"));
        s.quality = clampInt(p.getProperty("quality"), 0, 2, 1);
        s.targetFps = clampInt(p.getProperty("targetFps"), 30, 240, 120);
        s.competitiveMode = Boolean.parseBoolean(p.getProperty("competitiveMode", "false"));
        s.autoStart = Boolean.parseBoolean(p.getProperty("autoStart", "true"));
        return s;
    }

    private static int clampInt(String raw, int min, int max, int def) {
        if (raw == null) {
            return def;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 仅在真正写盘时创建目录 */
    public void save() {
        try {
            Path parent = CONFIG_PATH.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            System.err.println("[FrameGen] 创建设置目录失败: " + e.getMessage());
            return;
        }

        Properties p = new Properties();
        p.setProperty("enabled", String.valueOf(enabled));
        p.setProperty("quality", String.valueOf(quality));
        p.setProperty("targetFps", String.valueOf(targetFps));
        p.setProperty("competitiveMode", String.valueOf(competitiveMode));
        p.setProperty("autoStart", String.valueOf(autoStart));

        try (OutputStream out = Files.newOutputStream(CONFIG_PATH)) {
            p.store(out, "Starlight Frame Interpolation Settings");
        } catch (IOException e) {
            System.err.println("[FrameGen] 保存设置失败: " + e.getMessage());
        }
    }

    public static Path configPath() {
        return CONFIG_PATH;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        this.enabled = v;
        save();
    }

    public int getQuality() {
        return quality;
    }

    public void setQuality(int v) {
        this.quality = Math.max(0, Math.min(2, v));
        save();
    }

    public int getTargetFps() {
        return targetFps;
    }

    public void setTargetFps(int v) {
        this.targetFps = Math.max(30, Math.min(240, v));
        save();
    }

    public boolean isCompetitiveMode() {
        return competitiveMode;
    }

    public void setCompetitiveMode(boolean v) {
        this.competitiveMode = v;
        save();
    }

    public boolean isAutoStart() {
        return autoStart;
    }

    public void setAutoStart(boolean v) {
        this.autoStart = v;
        save();
    }

    public String qualityName() {
        switch (quality) {
            case 0:
                return I18N.get("framegen.t0065");
            case 2:
                return I18N.get("framegen.t0066");
            case 1:
            default:
                return I18N.get("framegen.t0067");
        }
    }
}
