package com.example.starlight.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * 插件扫描器：扫描指定目录下的 {@code *.jar}，读取各 JAR 包根目录的
 * {@code launcher-plugin.json} 并解析为 {@link PluginMetadata}。
 *
 * <p>进程隔离前提（需求指定）：本扫描器<strong>只读 JSON、绝不加载任何类</strong>——
 * 使用 {@link JarFile} + Jackson 读取元数据文件，不触碰 JAR 内的 class 文件；
 * 插件的实际执行由后续的独立子进程（{@code java -jar plugin.jar}）完成，
 * 与启动器不共用 ClassLoader。
 *
 * <p>容错策略：非 JAR 文件、JAR 内缺少元数据、元数据非法的文件一律跳过并记录日志，
 * 单个坏插件不影响其余插件被发现。
 */
public final class PluginScanner {

    /** 默认插件目录（用户指定）：{@code <启动器根目录>/Starlight-Launcher/mod}。 */
    public static final String DEFAULT_PLUGINS_DIR = "Starlight-Launcher/mod";

    private static final Logger LOGGER = Logger.getLogger(PluginScanner.class.getName());

    /** Jackson 单例：仅用于解析 JSON，不涉及类加载。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path pluginsDir;

    /** 基于启动器根目录构造扫描器，插件目录固定为 {@code <launcherRoot>/Starlight-Launcher/mod}。 */
    public static PluginScanner forLauncherRoot(Path launcherRoot) {
        return new PluginScanner(launcherRoot.resolve(DEFAULT_PLUGINS_DIR));
    }

    /**
     * @param pluginsDir 插件 JAR 存放目录（可为不存在的目录，扫描结果为空并记警告）
     */
    public PluginScanner(Path pluginsDir) {
        this.pluginsDir = pluginsDir;
    }

    /** @return 插件目录路径 */
    public Path pluginsDir() {
        return pluginsDir;
    }

    /**
     * 扫描全部插件，返回合法元数据列表（保持文件名字典序，输出稳定）。
     * 只读 JSON，不加载类、不执行任何插件代码。
     *
     * @throws IOException 目录读取失败
     */
    public List<PluginMetadata> scan() throws IOException {
        List<PluginMetadata> result = new ArrayList<>();
        if (!Files.isDirectory(pluginsDir)) {
            LOGGER.warning("插件目录不存在，跳过扫描: " + pluginsDir.toAbsolutePath());
            return result;
        }
        try (Stream<Path> stream = Files.list(pluginsDir)) {
            List<Path> jars = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .toList();
            for (Path jar : jars) {
                PluginMetadata metadata = scanJar(jar);
                if (metadata != null) {
                    result.add(metadata);
                }
            }
        }
        LOGGER.info("插件扫描完成: " + pluginsDir.toAbsolutePath()
                + " 共发现 " + result.size() + " 个合法插件");
        return result;
    }

    /**
     * 扫描单个 JAR 的元数据。
     *
     * @return 合法插件元数据；缺少元数据或元数据非法时返回 {@code null}（已记录日志）
     */
    private PluginMetadata scanJar(Path jar) {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            JarEntry entry = jarFile.getJarEntry(PluginMetadata.METADATA_FILE);
            if (entry == null) {
                LOGGER.warning("跳过 " + jar.getFileName() + "：JAR 内缺少 "
                        + PluginMetadata.METADATA_FILE + "（非插件或元数据缺失）");
                return null;
            }
            PluginMetadata metadata;
            try (InputStream in = jarFile.getInputStream(entry)) {
                metadata = MAPPER.readValue(in, PluginMetadata.class);
            }
            metadata.setSourceJar(jar);
            String error = metadata.validate();
            if (error != null) {
                LOGGER.warning("跳过 " + jar.getFileName() + "：元数据非法 - " + error);
                return null;
            }
            LOGGER.info("发现插件: " + metadata.id() + " v" + metadata.version()
                    + " (" + metadata.name() + ") [" + metadata.typeLabel() + "] <- " + jar.getFileName());
            return metadata;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "扫描插件失败（已跳过）: " + jar, e);
            return null;
        }
    }
}
