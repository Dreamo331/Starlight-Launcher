# 1.16 启动崩溃（GLFW 库加载失败 E5001）排查与修复记录

日期：2026-10-01
涉及版本：Minecraft 1.16（Vanilla）
崩溃时间：2026-10-01 13:45:13

---

## 一、问题现象

启动器（Starlight Launcher 1.0.0-RELEASE）启动 1.16 时报错：

- 错误码：`E5001`（被归类为"显卡驱动错误"）
- 崩溃摘要：`java.lang.RuntimeException: java.lang.NoClassDefFoundError: org/lwjgl/glfw/GLFW`
- 游戏目录：`D:\新建文件夹 (15)`，Java：1.8.0_482（Temurin）

## 二、完整崩溃链路（从启动器日志还原）

```
[13:45:13] Setting user: Dreamo_quq          ← 崩溃点前的最后正常日志
[13:45:13] ---- Minecraft Crash Report ----
java.lang.ExceptionInInitializerError
	at dmb.<init>(SourceFile:143)                 ← 窗口初始化
Caused by: java.lang.RuntimeException: java.lang.NoClassDefFoundError: org/lwjgl/glfw/GLFW
	at dgn.<clinit>(SourceFile:46)                ← GLFW 相关类静态初始化
Caused by: java.lang.ClassNotFoundException: org.lwjgl.glfw.GLFW
	at java.net.URLClassLoader.findClass(...)     ← ★ 类在 classpath 中根本不存在
```

**关键结论：这不是显卡驱动问题，也不是 glfw.dll 缺失，而是 `org.lwjgl.glfw.GLFW` 这个 Java 类没有出现在启动 classpath 中。**

## 三、根因分析（两层）

### 根因 1：启动库 getLibraries() 对老格式 version.json 的 natives 判定错误（代码 Bug）

`lib/StarlightLauncher-2.0.0.jar` 中 `BaseLauncher.getLibraries()` 的跳过逻辑：

```java
if (lib.has("natives")) continue;                    // ← 一刀切：带 natives 字段就整条跳过
if (lib.has("downloads") && ...has("classifiers") && hasNativeClassifier) continue;
```

Mojang 的 JSON 有两种格式：

| 格式 | 版本 | lwjgl 条目结构 | 正确行为 |
|---|---|---|---|
| 老格式 | 1.16 及更早（原版 1.17~1.20 同） | `natives` 字段 + `downloads.artifact`（主 jar）+ `downloads.classifiers`（natives jar） | **主 jar 进 classpath**，natives jar 只提取 DLL |
| 新格式 | 部分 1.20+ | natives 是独立 classifier 条目（无 artifact） | 整条跳过 |

老格式下原逻辑把**主 jar 也当成 native 跳过了**，导致 classpath 里没有任何 LWJGL Java jar（lwjgl、lwjgl-glfw 等），游戏启动时找不到 `org.lwjgl.glfw.GLFW` 类 → ClassNotFoundException。

> 注：`D:\新建文件夹 (15)` 里的 1.20.1.json 恰好是"无 natives 字段的精简格式"，所以 1.20.1 能正常启动，只有 1.16 暴露了这个 bug。

### 根因 2：1.16.json 被污染（LWJGL 条目重复两套）

`D:\新建文件夹 (15)\versions\1.16\1.16.json` 的 `libraries` 数组里，LWJGL 每个构件存在**两套条目**：

- 第 26~39 条：无 natives 字段的精简版（3.2.1 + 3.2.2 各一套）
- 第 40~53 条：带 natives 字段的原版格式（3.2.1 + 3.2.2 各一套）

启动器的 `deduplicateLibraries()` 按 `group:artifact`（去版本号）去重、后出现者覆盖先出现者，最终保留了**带 natives 字段的原版格式**（3.2.2），恰好触发根因 1 的跳过逻辑。

---

## 四、修复内容

### 1. 代码修复：`references\启动库jar源码（修复）\src\main\java\com\startgame\launcher\BaseLauncher.java`

`getLibraries()` 的 natives 跳过逻辑改为：**只要条目带 `downloads.artifact`（主 jar）就保留进 classpath；只有"纯 natives 条目"（无主 jar artifact）才跳过。**

```java
boolean hasMainArtifact = lib.has("downloads")
        && lib.getAsJsonObject("downloads").has("artifact");
if (!hasMainArtifact) {
    boolean nativeOnly = lib.has("natives");
    if (!nativeOnly && lib.has("downloads")
            && lib.getAsJsonObject("downloads").has("classifiers")) {
        JsonObject classifiers = lib.getAsJsonObject("downloads").getAsJsonObject("classifiers");
        for (String key : classifiers.keySet()) {
            if (key.startsWith("natives-")) { nativeOnly = true; break; }
        }
    }
    if (nativeOnly) continue;
}
```

重新编译后打包替换为 `lib/StarlightLauncher-2.0.0.jar`（旧 jar 已备份）。

### 2. 数据修复：`D:\新建文件夹 (15)\versions\1.16\1.16.json`

清理重复的 LWJGL 条目：57 条 → 36 条（删除 21 条重复/3.2.1 条目），每个 LWJGL 构件只保留一条 **3.2.2 原版格式**（带 natives 字段）：

```
org.lwjgl:lwjgl:3.2.2          org.lwjgl:lwjgl-jemalloc:3.2.2
org.lwjgl:lwjgl-openal:3.2.2   org.lwjgl:lwjgl-opengl:3.2.2
org.lwjgl:lwjgl-glfw:3.2.2     org.lwjgl:lwjgl-tinyfd:3.2.2
org.lwjgl:lwjgl-stb:3.2.2
```

原文件已备份。

---

## 五、验证结果

| 验证项 | 方法 | 结果 |
|---|---|---|
| classpath 构建 | 模拟 `deduplicateLibraries` + `getLibraries` | 34 条，**7 个 LWJGL 主 jar 全部进入 classpath**（修复前 26 条、LWJGL 全部缺失） |
| GLFW 类加载 | Java 8 + 1.16 classpath + natives 目录，实际加载 `org.lwjgl.glfw.GLFW` 并 `glfwInit()` | `GLFW class loaded from .../lwjgl-glfw-3.2.2.jar`，`glfwInit() = true`，DLL 版本 3.3.0（=LWJGL 3.2.2 内置） |
| natives 装配 | 修复后 `processNatives()` | 复用 `natives-extracted`（15 个 DLL），路径正确 |
| 端到端启动 | 真实启动参数离线启动 1.16，观察日志 | **越过崩溃点**：`Setting user` → `Backend library: LWJGL version 3.2.2 build 10` → `Reloading ResourceManager: Default`（游戏进入资源加载阶段后手动终止） |

---

## 六、变更文件清单

| 文件 | 操作 | 说明 |
|---|---|---|
| `references\启动库jar源码（修复）\src\main\java\com\startgame\launcher\BaseLauncher.java` | 修改 | getLibraries() natives 判定逻辑 |
| `lib\StarlightLauncher-2.0.0.jar` | 替换 | 重新编译打包（含修复） |
| `D:\新建文件夹 (15)\versions\1.16\1.16.json` | 修改 | 清理重复 LWJGL 条目（57→36） |
| `temp\StarlightLauncher-2.0.0.jar.bak-20261001` | 新增（备份） | 原启动库 jar |
| `temp\1.16.json.bak-20261001` | 新增（备份） | 原 1.16.json |
| `temp\launcherlib-rebuild\` | 新增 | 重新编译的 class 产物 |
| `temp\glfwcheck\` | 新增 | 验证程序（GlfwCheck / NativesCheck / e2e 启动脚本） |
| `docs\GLFW加载失败排查与修复.md` | 新增 | 本文档 |

回滚方式：将 `temp` 下的两个 `.bak` 文件按原名放回即可。

---

## 七、遗留说明

1. **建议在启动器中重新启动一次 1.16 做最终确认**（本次端到端验证为命令行直启，未走启动器 UI）。
2. 启动器把该崩溃归类为"显卡驱动错误（E5001）"是误分类（实际是 classpath 缺失），后续可考虑让 `CrashReportParser` 对 `ClassNotFoundException` / `NoClassDefFoundError` 单独分类。
3. 本修复同时惠及所有使用老格式 version.json 的版本（1.16 及更早、原版 1.17~1.20），不再依赖 JSON 是否被精简。

---

## 八、二次修复（2026-10-01 追加）：修复 jar 未装入本地 Maven 仓库，启动器仍加载旧核心库

### 1. 复现

用户通过启动器 UI（IntelliJ 运行）再次启动 1.16 仍报同一错误（E5001 / `NoClassDefFoundError: org/lwjgl/glfw/GLFW`）。

### 2. 根因（第三层）

上一轮只替换了 `lib\StarlightLauncher-2.0.0.jar`，但**没有把它安装进本地 Maven 仓库**（`.m2`）。实际运行中的启动器（PID 37992，由 IntelliJ 启动）classpath 里加载的 `com.startgame` 类来自：

```
C:\Users\Administrator\.m2\repository\com\starlight\starlight-launcher-core\2.0.0\starlight-launcher-core-2.0.0.jar
```

该 jar 仍是 **2026-08-17 的旧版（49,737 B）**，`BaseLauncher.getLibraries()` 的跳过 Bug 原样存在 → LWJGL 主 jar 依旧不进 classpath → GLFW 类找不到 → 同样的崩溃。

| 文件 | 大小 | 时间 | 是否含修复 |
|---|---|---|---|
| `lib\StarlightLauncher-2.0.0.jar` | 44,999 B | 10/01 14:21 | 是 |
| `.m2\...\starlight-launcher-core-2.0.0.jar`（修复前） | 49,737 B | 08/17 22:57 | 否 |

pom.xml 注释明确要求：改动 `lib` 下核心库后必须执行
`mvn install:install-file -Dfile=lib/StarlightLauncher-2.0.0.jar -DgroupId=com.starlight -DartifactId=starlight-launcher-core -Dversion=2.0.0 -Dpackaging=jar`。
上一轮漏掉了这一步。

### 3. 修复动作

1. 备份旧 m2 jar → `temp\m2-core-backup-20261001\starlight-launcher-core-2.0.0.jar.bak`；
2. 将修复版 `lib\StarlightLauncher-2.0.0.jar` 复制到 `.m2` 对应坐标（等价于 install-file，`mvn install:install-file` 因插件解析失败未采用）；
3. 校验哈希一致：`F342E80C5006F31FEE2035B0FBFC5F2B`；
4. 附带修复 `ErrorCode.detectFromText()` 误分类：`NoClassDefFoundError` / `ClassNotFoundException` 现在优先判为 `E6001 类路径错误`（原先含 "glfw" 文本被误判成 `E5001 显卡驱动错误`）。

### 4. 验证（走启动器生产路径，非命令行直启）

新增验证程序 `temp\verify-production\`（子类化 `VanillaLauncher`，调用链与 `GameLauncherService` / `UIGeneralControlClass` 一致）：

- **ClasspathCheck**：`initPaths → setVersionJson → processNatives → getLibraries(true)`
  - `processNatives = true`（natives-extracted，15 个 DLL）
  - `getLibraries size = 34`，**LWJGL 主 jar = 7**（lwjgl / glfw / openal / opengl / stb / tinyfd / jemalloc，全部 3.2.2），`1.16.jar` 入 classpath → `VERDICT=PASS`
- **LaunchE2E**：`VanillaLauncher.launch()` 实际拉起 1.16
  - 日志越过原崩溃点：`Setting user` → `OpenAL initialized` → `Sound engine started` → `Created: ...atlas`（已进入主菜单资源加载），验证后自动终止游戏进程
  - 修复前同路径崩溃于 `dgn.<clinit>`，latest.log 止于 `Setting user`
- **ErrorCodeCheck**：`NoClassDefFoundError: org/lwjgl/glfw/GLFW` → `E6001`（修复前 `E5001`）；真实 `GLFW error: 65542` / OpenGL 驱动错误仍 → `E5001`

### 5. 变更文件清单（本次）

| 文件 | 操作 | 说明 |
|---|---|---|
| `C:\Users\Administrator\.m2\repository\com\starlight\starlight-launcher-core\2.0.0\starlight-launcher-core-2.0.0.jar` | 替换 | 装入修复版（哈希 F342E8…，与 lib 一致） |
| `temp\m2-core-backup-20261001\starlight-launcher-core-2.0.0.jar.bak` | 新增（备份） | 原 m2 旧核心库（08/17 版） |
| `temp\verify-production\` | 新增 | ClasspathCheck / LaunchE2E / ErrorCodeCheck 验证程序 |
| `src\main\java\com\example\starlight\model\ErrorCode.java` | 修改 | detectFromText()：NoClassDefFoundError/ClassNotFoundException 优先归 E6001 |
| `docs\GLFW加载失败排查与修复.md` | 修改 | 追加本节 |

### 6. 用户操作

1. **重启启动器**（IntelliJ 停止并重新运行 `MainApp`）——当前运行的实例仍持有旧核心库，必须重启才能加载修复版；
2. 启动 1.16 应正常进入游戏；
3. 若对外分发单文件 EXE（GluonFX 原生镜像）：EXE 在构建时把核心库类内嵌进镜像，须在本次 `.m2` 安装完成后重新执行 `build-single-exe.bat` 打包，否则分发版仍是旧行为。

