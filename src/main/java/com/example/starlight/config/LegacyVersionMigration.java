/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.util.Map;

/**
 * 旧 {@code starlight.ini} 的 {@code Version} 键一次性迁移。
 *
 * <h3>背景</h3>
 * 版本选择已改为「每个游戏目录自己的 {@code starlight.json}」（见 {@link SelectedVersion}），
 * {@code starlight.ini} 不再保存 {@code Version}。老用户的 ini 里还留着旧值，
 * 需要把它<b>落到正确的目录</b>后再删掉，否则升级后等于「一个版本都没选」。
 *
 * <h3>迁移规则</h3>
 * 旧模型是「全局选择对所有目录生效」，因此忠实还原：
 * <ul>
 *   <li>凡 {@code GameFolders} 中<b>确实装有 {@code versions/X}</b> 的目录，写入 {@code selectedVersion = X}；</li>
 *   <li>已有 {@code starlight.json} 的目录<b>不覆盖</b>（用户在新模型下已经选过了）；</li>
 *   <li>{@code X} 在任何目录都不存在 → 不写任何文件，只删 ini 里那行；</li>
 *   <li>最后删掉 ini 的 {@code Version} 行（靠 {@link StarlightConfig#saveConfig} 丢弃旧键实现）。</li>
 * </ul>
 *
 * <p><b>幂等</b>：进程内只真正执行一次；ini 里没有该键时直接返回，不做任何写盘。
 * 调用点在启动链路：GUI 的 {@code LauncherView.loadConfig()} 与命令行的 {@code CliHandler}。
 */
public final class LegacyVersionMigration {

    private static final Logger log = LoggerFactory.getLogger(LegacyVersionMigration.class);

    /** 已执行标记（进程内一次性；ini 里没有旧键时同样置位，避免每次启动重复读盘判断） */
    private static volatile boolean done = false;

    private LegacyVersionMigration() {}

    /** 幂等执行迁移；任何异常都不阻断启动 */
    public static synchronized void runOnce() {
        if (done) return;
        try {
            migrate();
        } catch (Throwable e) {
            log.warn("Legacy Version migration failed: {}", e.getMessage());
        } finally {
            done = true;
        }
    }

    // 内部 //

    private static void migrate() {
        Map<String, String> cfg = StarlightConfig.readConfig();
        if (!cfg.containsKey("Version")) return; // 新装用户 / 已迁移过：无事发生

        String legacy = cfg.getOrDefault("Version", "").trim();
        int written = 0;

        if (!legacy.isEmpty()) {
            for (GameDirManager.GameDirEntry entry : GameDirManager.load(cfg)) {
                if (!entry.exists()) continue;
                try {
                    String dir = entry.getPath();
                    // 已在新模型下选过版本的目录不动它
                    if (Files.exists(SelectedVersion.fileOf(dir))) continue;
                    if (!SelectedVersion.isInstalled(dir, legacy)) continue;
                    if (SelectedVersion.set(dir, legacy)) written++;
                } catch (Exception e) {
                    log.warn("Failed to migrate version into {}: {}", entry.getPath(), e.getMessage());
                }
            }
        }

        cfg.remove("Version");
        StarlightConfig.saveConfig(cfg);
        log.info("Legacy Version '{}' migrated into {} game-dir config file(s), ini key removed",
                legacy, written);
    }
}