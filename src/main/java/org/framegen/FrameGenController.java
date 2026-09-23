/*
 * Decompiled with CFR 0.152.
 */
package org.framegen;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class FrameGenController {
    private final AppConfig config;
    private final GameMonitor monitor;
    private volatile int currentMultiplier = 0;
    private Process losslessScalingProcess = null;
    private final List<FrameGenListener> listeners = new ArrayList<FrameGenListener>();

    public FrameGenController(AppConfig appConfig, GameMonitor gameMonitor) {
        this.config = appConfig;
        this.monitor = gameMonitor;
        this.currentMultiplier = appConfig.getFrameMultiplier();
    }

    public void addListener(FrameGenListener frameGenListener) {
        this.listeners.add(frameGenListener);
    }

    public int getCurrentMultiplier() {
        return this.currentMultiplier;
    }

    public synchronized void applyFrameGen(int n) {
        Object object;
        if (n != 0 && n != 2 && n != 4 && n != 6 && n != 8) {
            System.err.println("[FrameGen] 无效倍率: " + n);
            return;
        }
        this.currentMultiplier = n;
        this.config.setFrameMultiplier(n);
        if (n == 0) {
            object = "\u5e27\u751f\u6210\u5df2\u5173\u95ed";
            this.stopLosslessScaling();
            this.restoreOptionsFps();
        } else {
            int n2 = this.config.getBaseFps() * n;
            object = n + "x \u5e27\u751f\u6210\u5df2\u542f\u7528 (\u76ee\u6807 " + n2 + " FPS)";
            if (!this.startLosslessScaling(n)) {
                this.applyOptionsFps(n2);
                object = (String)object + " [FPS\u9650\u5236\u6a21\u5f0f]";
            } else {
                object = (String)object + " [Lossless Scaling]";
            }
        }
        System.out.println("[FrameGen] " + (String)object);
        for (FrameGenListener frameGenListener : this.listeners) {
            try {
                frameGenListener.onFrameGenChanged(n, (String)object);
            }
            catch (Exception exception) {
                exception.printStackTrace();
            }
        }
    }

    private void applyOptionsFps(int n) {
        File file = this.monitor.findOptionsFile();
        if (file == null) {
            System.err.println("[FrameGen] 未找到 options.txt，无法设置 FPS");
            return;
        }
        try {
            ArrayList<String> arrayList = new ArrayList<String>();
            boolean bl = false;
            try (BufferedReader bufferedReader = new BufferedReader(new FileReader(file));) {
                String object;
                while ((object = bufferedReader.readLine()) != null) {
                    if (object.startsWith("maxFps:")) {
                        arrayList.add("maxFps:" + n);
                        bl = true;
                        continue;
                    }
                    arrayList.add(object);
                }
            }
            if (!bl) {
                arrayList.add("maxFps:" + n);
            }
            try (PrintWriter printWriter = new PrintWriter(new FileWriter(file));) {
                for (String string : arrayList) {
                    printWriter.println(string);
                }
            }
            System.out.println("[FrameGen] 已设置 options.txt maxFps=" + n);
        }
        catch (IOException iOException) {
            System.err.println("[FrameGen] 修改 options.txt 失败: " + iOException.getMessage());
        }
    }

    private void restoreOptionsFps() {
        this.applyOptionsFps(this.config.getBaseFps());
    }

    private boolean startLosslessScaling(int n) {
        String string = this.config.getLosslessScalingPath();
        if (string == null || string.isEmpty() || !new File(string).exists()) {
            return false;
        }
        try {
            this.stopLosslessScaling();
            ProcessBuilder processBuilder = new ProcessBuilder(string, "--mode", "lsfg", "--scale", String.valueOf(n));
            processBuilder.redirectErrorStream(true);
            this.losslessScalingProcess = processBuilder.start();
            System.out.println("[FrameGen] Lossless Scaling 已启动, " + n + "x");
            return true;
        }
        catch (IOException iOException) {
            System.err.println("[FrameGen] 启动 Lossless Scaling 失败: " + iOException.getMessage());
            return false;
        }
    }

    private void stopLosslessScaling() {
        if (this.losslessScalingProcess != null && this.losslessScalingProcess.isAlive()) {
            this.losslessScalingProcess.destroy();
            try {
                this.losslessScalingProcess.waitFor(3L, TimeUnit.SECONDS);
            }
            catch (InterruptedException interruptedException) {
                // empty catch block
            }
            this.losslessScalingProcess = null;
            System.out.println("[FrameGen] Lossless Scaling 已停止");
        }
    }

    public void shutdown() {
        this.stopLosslessScaling();
        if (this.currentMultiplier > 0) {
            this.restoreOptionsFps();
        }
    }

    public static interface FrameGenListener {
        public void onFrameGenChanged(int var1, String var2);
    }
}
