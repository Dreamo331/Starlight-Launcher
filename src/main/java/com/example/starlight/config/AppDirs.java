/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动器「用户级数据目录」的唯一解析入口（单一真源）。
 *
 * <h3>背景</h3>
 * 历史上用户级数据散落在三处：工作目录下的 {@code Starlight-Launcher/}、
 * 用户主目录的 {@code ~/.starlight-launcher/}（账号与注入器）以及
 * {@code ~/.starlight/}（缓存）。现统一收敛到 <b>{@code ~/.starlight/}</b>：
 * <ul>
 *   <li>{@code ~/.starlight-launcher/} 已废弃，内容由 {@link LegacyMigration} 一次性并入；</li>
 *   <li>启动参数带 {@link #ARG_PORTABLE} 时整体回落到工作目录的 {@code Starlight-Launcher/}，
 *       保留绿色版 / 便携能力。</li>
 * </ul>
 *
 * <h3>目录结构（扁平，尽量与原文件名 1:1）</h3>
 * <pre>
 * ~/.starlight/
 *   starlight.ini  starlight-client.ini  multiplayer.json
 *   accounts.json  login.json  external_login.json
 *   .account.dat   .community.dat   authlib-injector.jar
 *   java-cache.json  plugins-state.json  launcher.json
 *   uploaded_logs.json  source-health.json  version-manifest-cache.json
 *   avatars/  cache/  terracotta-cache/  mod-dictionary/  wallpaper/  skin/  mod/
 *   java/  terracotta/  slan/1.0/  ColorBlindOverlay/  framegen/  legacy/
 * </pre>
 *
 * <h3>硬约定</h3>
 * 任何类都不要再写 {@code "Starlight-Launcher/..."} 字面量，一律经本类取值；
 * 否则便携模式与全局模式会出现两套互相矛盾的路径。
 *
 * <p>本类不依赖任何其他业务类（避免初始化环），可被任意包安全调用。
 */
public final class AppDirs {

    /** 命令行开关：把用户级数据放回工作目录下的 {@code Starlight-Launcher/}（便携 / 绿色版模式） */
    public static final String ARG_PORTABLE = "--portable";

    /** 全局模式目录名（位于用户主目录下） */
    private static final String GLOBAL_DIR_NAME = ".starlight";

    /** 便携模式目录名（位于工作目录下，与旧版一致） */
    private static final String PORTABLE_DIR_NAME = "Starlight-Launcher";

    /** 系统属性：向外暴露当前生效的数据根目录（插件子进程 / 诊断用） */
    public static final String PROP_DATA_DIR = "starlight.data.dir";

    private static volatile Path root;
    private static volatile boolean portable;

    private AppDirs() {
    }

    // 初始化 //

    /**
     * 解析并剥离 {@link #ARG_PORTABLE}，然后完成初始化。
     *
     * <p><b>必须在 {@code main} 的最前面调用</b>：任何路径取值都依赖这里的结果，
     * 晚于首次配置读取就会写错目录。
     *
     * @param args 原始命令行参数，可为 null
     * @return 剥离 {@code --portable} 之后的参数数组
     */
    public static String[] initAndStrip(String[] args) {
        boolean wantPortable = false;
        List<String> rest = new ArrayList<>();
        if (args != null) {
            for (String a : args) {
                if (ARG_PORTABLE.equalsIgnoreCase(a)) {
                    wantPortable = true;
                } else {
                    rest.add(a);
                }
            }
        }
        init(wantPortable);
        return rest.toArray(new String[0]);
    }

    /**
     * 完成目录解析。非便携模式下顺带触发一次历史数据迁移（幂等、失败不阻断启动）。
     *
     * @param portableMode true=便携模式（工作目录），false=全局模式（用户主目录）
     */
    public static synchronized void init(boolean portableMode) {
        AppDirs.portable = portableMode;
        AppDirs.root = portableMode ? portableRoot() : globalRoot();
        System.setProperty(PROP_DATA_DIR, AppDirs.root.toString());
        if (!portableMode) {
            LegacyMigration.runOnce();
        }
    }

    /** 是否处于便携模式 */
    public static boolean isPortable() {
        return portable;
    }

    /** 当前生效的数据根目录 */
    public static Path root() {
        Path r = root;
        if (r == null) {
            synchronized (AppDirs.class) {
                if (root == null) {
                    // 未经 init 就被调用：可能是「类加载早于 init」。
                    // 此时仍要尽量尊重 --portable，否则 static final 字段会冻结成错误的根目录。
                    if (!portable && detectPortableFromJvm()) {
                        portable = true;
                    }
                    root = portable ? portableRoot() : globalRoot();
                }
                r = root;
            }
        }
        return r;
    }

    /**
     * 从 JVM 自身的启动参数里探测 {@code --portable}。
     *
     * <p>存在的意义：若干类把路径放在 {@code static final} 字段里（如
     * {@code JavaEnvironmentManager.JAVA_TOOLS_DIR}），一旦它们比 {@link #init} 更早被类加载，
     * 字段值就会被永久冻结。正常入口（{@code MainApp} / {@code JavaFXLauncher}）都已在第一行
     * 调用 init，但为了不让「加载顺序」这种隐式约定决定数据目录，这里再做一层兜底探测。
     */
    private static boolean detectPortableFromJvm() {
        // 1) 显式系统属性优先：-Dstarlight.portable=true
        if (Boolean.parseBoolean(System.getProperty("starlight.portable", "false"))) {
            return true;
        }
        // 2) sun.java.command = "主类 参数1 参数2 ..."
        String cmd = System.getProperty("sun.java.command", "");
        if (containsPortable(cmd)) {
            return true;
        }
        // 3) 进程真实命令行（覆盖 java -jar 等形态）
        try {
            var info = ProcessHandle.current().info();
            if (info.arguments().isPresent() && containsPortable(String.join(" ", info.arguments().get()))) {
                return true;
            }
            if (info.commandLine().isPresent() && containsPortable(info.commandLine().get())) {
                return true;
            }
        } catch (Throwable ignored) {
            // 取不到进程信息就退化为「非便携」
        }
        return false;
    }

    /** 按空白切词精确匹配 {@code --portable}，避免误命中包含该子串的其它参数 */
    private static boolean containsPortable(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (String token : text.split("\\s+")) {
            if (ARG_PORTABLE.equalsIgnoreCase(token)) {
                return true;
            }
        }
        return false;
    }

    /** 全局模式根目录：{@code ~/.starlight} */
    public static Path globalRoot() {
        return Paths.get(System.getProperty("user.home", "."), GLOBAL_DIR_NAME)
                .toAbsolutePath().normalize();
    }

    /** 便携模式根目录：{@code <工作目录>/Starlight-Launcher} */
    public static Path portableRoot() {
        return Paths.get(System.getProperty("user.dir", "."), PORTABLE_DIR_NAME)
                .toAbsolutePath().normalize();
    }

    /** 已被废弃的旧全局目录：{@code ~/.starlight-launcher}（仅供迁移与兼容回退使用） */
    public static Path legacyGlobalRoot() {
        return Paths.get(System.getProperty("user.home", "."), ".starlight-launcher")
                .toAbsolutePath().normalize();
    }

    /** 数据根目录下的一个子项 */
    public static Path file(String name) {
        return root().resolve(name);
    }

    // 配置文件 //

    public static Path starlightIni() {
        return file("starlight.ini");
    }

    public static Path starlightClientIni() {
        return file("starlight-client.ini");
    }

    public static Path multiplayerJson() {
        return file("multiplayer.json");
    }

    /** 真实密钥的全局覆盖文件（不入库；缺失时回落 jar 内 {@code /assets/endpoints.local.json}） */
    public static Path endpointsLocalJson() {
        return file("endpoints.local.json");
    }

    // 账号与凭据 //

    public static Path accountsJson() {
        return file("accounts.json");
    }

    public static Path loginJson() {
        return file("login.json");
    }

    public static Path externalLoginJson() {
        return file("external_login.json");
    }

    /** 账号令牌（Base64 + XOR 混淆） */
    public static Path credentialFile() {
        return file(".account.dat");
    }

    /** 星光社区令牌（独立 XOR 密钥） */
    public static Path communityTokenFile() {
        return file(".community.dat");
    }

    public static Path authlibInjectorJar() {
        return file("authlib-injector.jar");
    }

    public static Path avatarsDir() {
        return file("avatars");
    }

    // 缓存与状态 //

    public static Path javaCacheJson() {
        return file("java-cache.json");
    }

    public static Path pluginsStateJson() {
        return file("plugins-state.json");
    }

    public static Path launcherJson() {
        return file("launcher.json");
    }

    public static Path uploadedLogsJson() {
        return file("uploaded_logs.json");
    }

    public static Path sourceHealthJson() {
        return file("source-health.json");
    }

    public static Path versionManifestCacheJson() {
        return file("version-manifest-cache.json");
    }

    /** 版本 / 加载器元数据缓存（{@code CacheManager}） */
    public static Path cacheDir() {
        return file("cache");
    }

    /** Terracotta 安装包缓存（按 hash 命名） */
    public static Path terracottaCacheDir() {
        return file("terracotta-cache");
    }

    public static Path modDictionaryDir() {
        return file("mod-dictionary");
    }

    // 用户资产 //

    public static Path wallpaperDir() {
        return file("wallpaper");
    }

    public static Path skinDir() {
        return file("skin");
    }

    /** 启动器 MOD（插件）JAR 目录 */
    public static Path pluginsDir() {
        return file("mod");
    }

    // 运行时组件（可下载的 exe / JDK） //

    /** 启动器自动下载的 JDK 安装根目录（原 {@code <工作目录>/tools/java}） */
    public static Path javaDir() {
        return file("java");
    }

    /** Terracotta 组件安装根目录（其下按版本号分子目录） */
    public static Path terracottaDir() {
        return file("terracotta");
    }

    /** SL-MinecraftLAN 联机模块目录（其下为 {@code 1.0/}） */
    public static Path slanDir() {
        return file("slan");
    }

    /** 色盲矫正工具所在目录 */
    public static Path colorBlindDir() {
        return file("ColorBlindOverlay");
    }

    /** 色盲矫正工具完整路径 */
    public static Path colorBlindExe() {
        return colorBlindDir().resolve("ColorBlindOverlay.exe");
    }

    // 帧生成器 //

    /** 帧生成器配置目录（原 {@code %APPDATA%\StarlightFrameGen}） */
    public static Path framegenDir() {
        return file("framegen");
    }

    public static Path framegenConfig() {
        return framegenDir().resolve("config.properties");
    }

    // 其它 //

    /** 迁移时收纳「代码已不再引用但可能仍有用」的历史文件 */
    public static Path legacyDir() {
        return file("legacy");
    }
}
