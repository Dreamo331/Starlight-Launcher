/*
 * Decompiled with CFR 0.152.
 */
package org.framegen;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

public class AppConfig {
    private static final String CONFIG_DIR = System.getenv("APPDATA") + "\\StarlightFrameGen";
    private static final String CONFIG_FILE = CONFIG_DIR + "\\config.properties";
    private final Properties props = new Properties();
    private int frameMultiplier = 0;
    private String losslessScalingPath = "";
    private String gameDir = "";
    private int baseFps = 60;
    private boolean autoStart = false;
    /** 是否在启动器内启用帧生成器系统托盘图标（默认关闭，由启动器页面开关控制） */
    private boolean trayEnabled = false;

    public AppConfig() {
        this.load();
    }

    public void load() {
        File file;
        File file2 = new File(CONFIG_DIR);
        if (!file2.exists()) {
            file2.mkdirs();
        }
        if ((file = new File(CONFIG_FILE)).exists()) {
            try (FileInputStream fileInputStream = new FileInputStream(file);){
                this.props.load(fileInputStream);
                this.frameMultiplier = Integer.parseInt(this.props.getProperty("frameMultiplier", "0"));
                this.losslessScalingPath = this.props.getProperty("losslessScalingPath", "");
                this.gameDir = this.props.getProperty("gameDir", "");
                this.baseFps = Integer.parseInt(this.props.getProperty("baseFps", "60"));
                this.autoStart = Boolean.parseBoolean(this.props.getProperty("autoStart", "false"));
                this.trayEnabled = Boolean.parseBoolean(this.props.getProperty("trayEnabled", "false"));
            }
            catch (Exception exception) {
                System.err.println("[Config] \u52a0\u8f7d\u5931\u8d25: " + exception.getMessage());
            }
        }
    }

    public void save() {
        File file = new File(CONFIG_DIR);
        if (!file.exists()) {
            file.mkdirs();
        }
        this.props.setProperty("frameMultiplier", String.valueOf(this.frameMultiplier));
        this.props.setProperty("losslessScalingPath", this.losslessScalingPath);
        this.props.setProperty("gameDir", this.gameDir);
        this.props.setProperty("baseFps", String.valueOf(this.baseFps));
        this.props.setProperty("autoStart", String.valueOf(this.autoStart));
        this.props.setProperty("trayEnabled", String.valueOf(this.trayEnabled));
        try (FileOutputStream fileOutputStream = new FileOutputStream(CONFIG_FILE);){
            this.props.store(fileOutputStream, "Starlight FrameGen Companion Config");
        }
        catch (Exception exception) {
            System.err.println("[Config] \u4fdd\u5b58\u5931\u8d25: " + exception.getMessage());
        }
    }

    public int getFrameMultiplier() {
        return this.frameMultiplier;
    }

    public void setFrameMultiplier(int n) {
        this.frameMultiplier = n;
        this.save();
    }

    public String getLosslessScalingPath() {
        return this.losslessScalingPath;
    }

    public void setLosslessScalingPath(String string) {
        this.losslessScalingPath = string;
        this.save();
    }

    public String getGameDir() {
        return this.gameDir;
    }

    public void setGameDir(String string) {
        this.gameDir = string;
        this.save();
    }

    public int getBaseFps() {
        return this.baseFps;
    }

    public void setBaseFps(int n) {
        this.baseFps = n;
        this.save();
    }

    public boolean isAutoStart() {
        return this.autoStart;
    }

    public void setAutoStart(boolean bl) {
        this.autoStart = bl;
        this.save();
    }

    public boolean isTrayEnabled() {
        return this.trayEnabled;
    }

    public void setTrayEnabled(boolean bl) {
        this.trayEnabled = bl;
        this.save();
    }

    public String getConfigDir() {
        return CONFIG_DIR;
    }
}
