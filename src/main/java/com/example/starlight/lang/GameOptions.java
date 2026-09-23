package com.example.starlight.lang;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * 游戏 options.txt 的通用设置写入（目前用于「全屏模式」）。
 *
 * <p>与 {@link GameLanguage} 相同的文件定位规则：版本隔离启用时写入
 * {@code versions/&lt;version&gt;/options.txt}，否则写入 {@code .minecraft/options.txt}。
 * 仅当值发生变化（或文件 / 字段缺失）时写入，避免无谓改动文件时间戳。</p>
 */
public final class GameOptions {

    private static final Logger LOG = LoggerFactory.getLogger(GameOptions.class);

    /** options.txt 中的全屏键名 */
    public static final String OPTIONS_FULLSCREEN_KEY = "fullscreen";

    private GameOptions() {
    }

    /**
     * 同步全屏模式到 options.txt（替代原先依赖启动参数的实现）。
     *
     * <p>开关开启时写入 {@code fullscreen:true}，关闭时写入 {@code fullscreen:false}；
     * 与当前值一致时跳过，不做多余写入。</p>
     *
     * @return true=已写入或已一致；false=写入失败
     */
    public static boolean setFullscreen(String gameDir, String version,
                                        boolean versionIsolation, boolean fullscreen) {
        if (gameDir == null || gameDir.isEmpty()) {
            LOG.warn("Failed to set fullscreen: gameDir is empty");
            return false;
        }
        Path optionsPath = GameLanguage.resolveOptionsPath(gameDir, version, versionIsolation);
        Map<String, String> options = GameLanguage.readOptions(optionsPath);

        String desired = String.valueOf(fullscreen);
        String current = options.get(OPTIONS_FULLSCREEN_KEY);
        if (desired.equalsIgnoreCase(current)) {
            return true;
        }

        options.put(OPTIONS_FULLSCREEN_KEY, desired);
        try {
            GameLanguage.writeOptions(optionsPath, options);
            LOG.info("Fullscreen set to '{}', path: {}", desired, optionsPath);
            return true;
        } catch (IOException e) {
            LOG.error("Failed to write options.txt (fullscreen): {}", e.getMessage());
            return false;
        }
    }

    /**
     * 读取当前 options.txt 里的全屏值。
     *
     * @return {@code "true"} / {@code "false"}，未设置或读取失败返回 {@code null}
     */
    public static String getFullscreen(String gameDir, String version, boolean versionIsolation) {
        Path optionsPath = GameLanguage.resolveOptionsPath(gameDir, version, versionIsolation);
        return GameLanguage.readOptions(optionsPath).get(OPTIONS_FULLSCREEN_KEY);
    }
}
