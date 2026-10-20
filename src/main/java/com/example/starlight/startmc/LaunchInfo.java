/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.startmc;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 启动配置信息载体
 *
 * 包含所有启动所需的参数和路径解析逻辑。
 * 所有字段默认使用无参构造 + setter 方式赋值。
 */
public class LaunchInfo {

    // 加载器类型枚举 //

    /** 加载器类型枚举 */
    public enum LoaderType {
        VANILLA,
        FABRIC,
        QUILT,
        FORGE_LEGACY,
        FORGE_MODERN,
        NEOFORGE
    }

    // 目录 / 路径字段 //
    private Path gameDirPath;       // 游戏根目录（如 .minecraft）
    private String version;        // 版本名称（如 "1.20.1-Forge_47.4.16"）
    private String javaPath;       // Java 可执行文件路径

    // 用户 / 认证字段 //
    private String userName;       // 玩家名
    private String uuid;           // 玩家 UUID
    private String accessToken;    // 认证令牌
    private String userType;       // 账号类型（mojang / msa）

    // JVM / 游戏参数字段 //
    private int minMemoryMB = 512;       // 最小内存（MB）
    private int maxMemoryMB = 2048;      // 最大内存（MB）
    private List<String> jvmArgs = new ArrayList<>();   // 额外 JVM 参数列表
    private List<String> gameArgs = new ArrayList<>();  // 额外游戏参数列表

    // 窗口设置 //
    private int windowWidth = 854;     // 默认窗口宽度
    private int windowHeight = 480;    // 默认窗口高度
    private boolean fullscreen = false;

    // 启动器行为 //
    private LoaderType loaderType = LoaderType.VANILLA;  // 加载器类型
    private boolean versionIsolation = false;  // 是否启用版本隔离
    private String preLaunchCommand;   // 启动前命令
    private String postExitCommand;    // 退出后命令

    // 进程微调（设置 → 高级设置） //
    /** 自定义游戏窗口标题（空 = 保持游戏默认标题）；由 GameProcessTweaks 在进程启动后写入窗口 */
    private String windowTitle = "";
    /** 游戏进程优先级：low / below_normal / normal / above_normal / high / realtime */
    private String processPriority = "normal";

    // 运行时设置（由启动器内部设置） //
    private Path nativesDir;           // natives 目录路径

    // 路径解析方法 //

    /**
     * @return 版本目录：游戏根目录/versions/当前版本
     * @throws NullPointerException 当 gameDirPath 或 version 未设置
     */
    public Path resolveVersionDir() {
        Objects.requireNonNull(gameDirPath, "gameDirPath 未设置，请先调用 setGameDirPath()");
        Objects.requireNonNull(version, "version 未设置，请先调用 setVersion()");
        return gameDirPath.resolve("versions").resolve(version);
    }

    /**
     * @return assets 目录：游戏根目录/assets
     * @throws NullPointerException 当 gameDirPath 未设置
     */
    public Path resolveAssetsDir() {
        Objects.requireNonNull(gameDirPath, "gameDirPath 未设置");
        return gameDirPath.resolve("assets");
    }

    /**
     * @return libraries 根目录：游戏根目录/libraries
     * @throws NullPointerException 当 gameDirPath 未设置
     */
    public Path resolveLibrariesRoot() {
        Objects.requireNonNull(gameDirPath, "gameDirPath 未设置");
        return gameDirPath.resolve("libraries");
    }

    /**
     * @return 有效游戏数据目录（启用版本隔离时返回版本目录，否则返回游戏根目录）
     */
    public Path resolveEffectiveGameDir() {
        if (versionIsolation) {
            return resolveVersionDir();
        }
        return gameDirPath;
    }

    /**
     * @return 游戏根目录字符串（用于环境变量）；未设置时返回空字符串而非抛 NPE
     */
    public String getGameDir() {
        return gameDirPath != null ? gameDirPath.toString() : "";
    }

    // Getter / Setter //

    public Path getGameDirPath() {
        return gameDirPath;
    }

    public void setGameDirPath(Path gameDirPath) {
        this.gameDirPath = gameDirPath;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getJavaPath() {
        return javaPath;
    }

    public void setJavaPath(String javaPath) {
        this.javaPath = javaPath;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getUserType() {
        return userType;
    }

    public void setUserType(String userType) {
        this.userType = userType;
    }

    /** @return 最小内存（MB） */
    public int getMinMemoryMB() {
        return minMemoryMB;
    }

    /** @param minMemoryMB 最小内存（MB） */
    public void setMinMemoryMB(int minMemoryMB) {
        this.minMemoryMB = minMemoryMB;
    }

    /** @return 最大内存（MB） */
    public int getMaxMemoryMB() {
        return maxMemoryMB;
    }

    /** @param maxMemoryMB 最大内存（MB） */
    public void setMaxMemoryMB(int maxMemoryMB) {
        this.maxMemoryMB = maxMemoryMB;
    }

    /** @deprecated 请使用 {@link #getMinMemoryMB()} */
    @Deprecated
    public String getMinMemory() {
        return minMemoryMB + "M";
    }

    /** @deprecated 请使用 {@link #setMinMemoryMB(int)} */
    @Deprecated
    public void setMinMemory(String minMemory) {
        // 兼容旧版字符串格式，尝试解析
        if (minMemory != null && !minMemory.isEmpty()) {
            String upper = minMemory.toUpperCase();
            try {
                int val = Integer.parseInt(upper.replaceAll("[MG]", ""));
                if (upper.endsWith("G")) val *= 1024;
                this.minMemoryMB = val;
            } catch (NumberFormatException ignored) {
                this.minMemoryMB = 512;
            }
        }
    }

    /** @deprecated 请使用 {@link #getMaxMemoryMB()} */
    @Deprecated
    public String getMaxMemory() {
        return maxMemoryMB + "M";
    }

    /** @deprecated 请使用 {@link #setMaxMemoryMB(int)} */
    @Deprecated
    public void setMaxMemory(String maxMemory) {
        if (maxMemory != null && !maxMemory.isEmpty()) {
            String upper = maxMemory.toUpperCase();
            try {
                int val = Integer.parseInt(upper.replaceAll("[MG]", ""));
                if (upper.endsWith("G")) val *= 1024;
                this.maxMemoryMB = val;
            } catch (NumberFormatException ignored) {
                this.maxMemoryMB = 2048;
            }
        }
    }

    /** @return 额外 JVM 参数列表 */
    public List<String> getJvmArgs() {
        return jvmArgs;
    }

    /** @param jvmArgs 额外 JVM 参数列表 */
    public void setJvmArgs(List<String> jvmArgs) {
        this.jvmArgs = jvmArgs != null ? jvmArgs : new ArrayList<>();
    }

    /** @deprecated 请使用 {@link #setJvmArgs(List)} */
    @Deprecated
    public void setJvmArgs(String jvmArgs) {
        this.jvmArgs = new ArrayList<>();
        if (jvmArgs != null && !jvmArgs.isEmpty()) {
            for (String arg : jvmArgs.split("\\s+")) {
                if (!arg.isEmpty()) this.jvmArgs.add(arg);
            }
        }
    }

    /** @return 额外游戏参数列表 */
    public List<String> getGameArgs() {
        return gameArgs;
    }

    /** @param gameArgs 额外游戏参数列表 */
    public void setGameArgs(List<String> gameArgs) {
        this.gameArgs = gameArgs != null ? gameArgs : new ArrayList<>();
    }

    /** @deprecated 请使用 {@link #setGameArgs(List)} */
    @Deprecated
    public void setGameArgs(String gameArgs) {
        this.gameArgs = new ArrayList<>();
        if (gameArgs != null && !gameArgs.isEmpty()) {
            for (String arg : gameArgs.split("\\s+")) {
                if (!arg.isEmpty()) this.gameArgs.add(arg);
            }
        }
    }

    public int getWindowWidth() {
        return windowWidth;
    }

    public void setWindowWidth(int windowWidth) {
        this.windowWidth = windowWidth;
    }

    public int getWindowHeight() {
        return windowHeight;
    }

    public void setWindowHeight(int windowHeight) {
        this.windowHeight = windowHeight;
    }

    public boolean isFullscreen() {
        return fullscreen;
    }

    public void setFullscreen(boolean fullscreen) {
        this.fullscreen = fullscreen;
    }

    /** @deprecated 请使用 {@link #getLoaderTypeEnum()} */
    @Deprecated
    public String getLoaderType() {
        if (loaderType == null) return "Vanilla";
        // 映射到 LoaderDetector 的字符串常量格式
        switch (loaderType) {
            case VANILLA:      return "Vanilla";
            case FABRIC:       return "Fabric";
            case QUILT:        return "Quilt";
            case FORGE_LEGACY: return "Forge (Legacy)";
            case FORGE_MODERN: return "Forge (Modern)";
            case NEOFORGE:     return "NeoForge";
            default:           return "Vanilla";
        }
    }

    /** @deprecated 请使用 {@link #setLoaderTypeEnum(LoaderType)} */
    @Deprecated
    public void setLoaderType(String loaderType) {
        if (loaderType == null) {
            this.loaderType = LoaderType.VANILLA;
            return;
        }
        // 匹配 LoaderDetector 的字符串常量
        switch (loaderType) {
            case "Fabric":         this.loaderType = LoaderType.FABRIC; break;
            case "Quilt":          this.loaderType = LoaderType.QUILT; break;
            case "NeoForge":       this.loaderType = LoaderType.NEOFORGE; break;
            case "Forge (Modern)": this.loaderType = LoaderType.FORGE_MODERN; break;
            case "Forge (Legacy)": this.loaderType = LoaderType.FORGE_LEGACY; break;
            default:               this.loaderType = LoaderType.VANILLA; break;
        }
    }

    /** @return 加载器类型枚举 */
    public LoaderType getLoaderTypeEnum() {
        return loaderType;
    }

    /** @param loaderType 加载器类型枚举 */
    public void setLoaderTypeEnum(LoaderType loaderType) {
        this.loaderType = loaderType;
    }

    public boolean isVersionIsolation() {
        return versionIsolation;
    }

    public void setVersionIsolation(boolean versionIsolation) {
        this.versionIsolation = versionIsolation;
    }

    public String getPreLaunchCommand() {
        return preLaunchCommand;
    }

    public void setPreLaunchCommand(String preLaunchCommand) {
        this.preLaunchCommand = preLaunchCommand;
    }

    public String getPostExitCommand() {
        return postExitCommand;
    }

    public void setPostExitCommand(String postExitCommand) {
        this.postExitCommand = postExitCommand;
    }

    /** @return 自定义游戏窗口标题（空串 = 保持游戏默认标题） */
    public String getWindowTitle() {
        return windowTitle;
    }

    /** @param windowTitle 自定义游戏窗口标题（null / 空串 = 保持游戏默认标题） */
    public void setWindowTitle(String windowTitle) {
        this.windowTitle = windowTitle == null ? "" : windowTitle;
    }

    /** @return 游戏进程优先级值（默认 normal） */
    public String getProcessPriority() {
        return processPriority;
    }

    /** @param processPriority 游戏进程优先级值（low / below_normal / normal / above_normal / high / realtime） */
    public void setProcessPriority(String processPriority) {
        this.processPriority = (processPriority == null || processPriority.isBlank())
                ? "normal" : processPriority.trim();
    }

    public Path getNativesDir() {
        return nativesDir;
    }

    public void setNativesDir(Path nativesDir) {
        this.nativesDir = nativesDir;
    }
}
