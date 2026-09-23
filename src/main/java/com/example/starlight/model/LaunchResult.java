/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.model;

/**
 * 游戏启动结果
 */
public class LaunchResult {
    public final int exitCode;
    public final long elapsedSeconds;
    public final String errorLogPath;
    public final String loaderType;

    public LaunchResult(int exitCode, long elapsedSeconds, String errorLogPath) {
        this(exitCode, elapsedSeconds, errorLogPath, null);
    }

    public LaunchResult(int exitCode, long elapsedSeconds, String errorLogPath, String loaderType) {
        this.exitCode = exitCode;
        this.elapsedSeconds = elapsedSeconds;
        this.errorLogPath = errorLogPath;
        this.loaderType = loaderType;
    }

    public boolean isNormal() {
        return exitCode == 0;
    }
}
