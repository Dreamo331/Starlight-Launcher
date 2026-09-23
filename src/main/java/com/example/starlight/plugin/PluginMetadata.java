package com.example.starlight.plugin;

import com.example.starlight.pluginapi.SemVer;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.file.Path;

/**
 * 插件元数据：对应插件 JAR 包<strong>根目录</strong>下的 {@code launcher-plugin.json}。
 *
 * <p>字段规范（需求指定）：
 * <ul>
 *   <li>{@code id}：唯一标识（官方插件使用 {@code official.*} 前缀保留）</li>
 *   <li>{@code name}：展示名称</li>
 *   <li>{@code version}：语义化版本</li>
 *   <li>{@code description}：描述（可选）</li>
 *   <li>{@code launcher_api_version}：依赖的启动器 API 版本（SemVer）</li>
 *   <li>{@code main_class}：入口主类（插件启动时执行的类）</li>
 *   <li>{@code plugin_type}（可选）：{@code official} 或 {@code community}，仅用于 UI 展示标识</li>
 * </ul>
 *
 * <p>解析原则（需求指定）：启动器加载插件元数据时<strong>只读 JSON，不加载任何类</strong>；
 * 执行插件时才以独立子进程方式启动（{@code java -jar plugin.jar}），
 * 与启动器进程隔离、不共用 ClassLoader。
 */
public final class PluginMetadata {

    /** 元数据文件名（固定位于 JAR 包根目录）。 */
    public static final String METADATA_FILE = "launcher-plugin.json";

    @JsonProperty("id")
    private String id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("version")
    private String version;

    @JsonProperty("description")
    private String description;

    @JsonProperty("launcher_api_version")
    private String launcherApiVersion;

    @JsonProperty("main_class")
    private String mainClass;

    @JsonProperty("plugin_type")
    private String pluginType;

    /** 来源 JAR 路径：由 {@link PluginScanner} 扫描时填充，不属于 JSON 字段。 */
    @JsonIgnore
    private Path sourceJar;

    public String id()                 { return id; }
    public String name()               { return name; }
    public String version()            { return version; }
    public String description()        { return description; }
    public String launcherApiVersion() { return launcherApiVersion; }
    public String mainClass()          { return mainClass; }
    public String pluginType()         { return pluginType; }
    public Path sourceJar()            { return sourceJar; }

    /** 由 PluginScanner 在解析完成后填充来源 JAR 路径（同包可见）。 */
    void setSourceJar(Path sourceJar) {
        this.sourceJar = sourceJar;
    }

    /** @return 是否为官方插件（plugin_type == "official"） */
    public boolean isOfficial() {
        return "official".equals(pluginType);
    }

    /** @return 是否为社区插件（plugin_type == "community"） */
    public boolean isCommunity() {
        return "community".equals(pluginType);
    }

    /** @return UI 展示用类型标签：官方 / 社区 / 未标注 */
    public String typeLabel() {
        if (isOfficial()) return "官方";
        if (isCommunity()) return "社区";
        return "未标注";
    }

    /**
     * 校验元数据合法性。
     *
     * @return 合法返回 {@code null}；非法返回错误描述（供扫描器记录日志并跳过）
     */
    public String validate() {
        if (isBlank(id)) {
            return "id 不能为空";
        }
        if (isBlank(name)) {
            return "name 不能为空";
        }
        if (!isValidSemVer(version)) {
            return "version 非法（需为 SemVer，如 1.0.0）: " + version;
        }
        if (!isValidSemVer(launcherApiVersion)) {
            return "launcher_api_version 非法（需为 SemVer）: " + launcherApiVersion;
        }
        if (isBlank(mainClass)) {
            return "main_class 不能为空";
        }
        if (pluginType != null && !"official".equals(pluginType) && !"community".equals(pluginType)) {
            return "plugin_type 只能为 official 或 community: " + pluginType;
        }
        // official.* 前缀为官方插件保留：社区插件使用视为非法（保留语义，仅做元数据层面校验）
        if (isCommunity() && id != null && id.startsWith("official.")) {
            return "official.* 前缀为官方插件保留，社区插件不得使用: " + id;
        }
        return null;
    }

    private static boolean isValidSemVer(String v) {
        if (v == null || v.isBlank()) {
            return false;
        }
        try {
            SemVer.parse(v);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @Override
    public String toString() {
        return "PluginMetadata{id='" + id + "', name='" + name + "', version='" + version
                + "', launcherApiVersion='" + launcherApiVersion + "', mainClass='" + mainClass
                + "', pluginType='" + pluginType + "'}";
    }
}
