package com.example.starlight.launch;

/**
 * 启动记录（序列化到 {@code Starlight-Launcher/launcher.json}）。
 *
 * <p>从 LauncherView 抽离而出（原为内部类 {@code LauncherView.LaunchRecord}），
 * 字段名即 JSON 键名，<b>不可重命名</b>，否则旧记录文件无法反序列化。
 *
 * <p>注意：Gson 在原生镜像（GluonFX / GraalVM）下依赖反射，
 * 本类已登记在 {@code pom.xml} 的 {@code reflectionList} 中；
 * 若再次移动或改名，必须同步更新该处注册，否则打包后运行期会解析失败。
 */
public class LaunchRecord {
    public String version;
    public String gameDir;
    public String loaderType;
    public String launchTime;
    public String javaPath;
    public int maxMemory;
    public int minMemory;
    public int windowWidth;
    public int windowHeight;
    public boolean fullscreen;
    public boolean versionIsolation;
    public String jvmArgs;
    public String gameArgs;
    public String preLaunchCommand;
    public String postExitCommand;
}
