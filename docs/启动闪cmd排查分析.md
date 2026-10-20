# 启动游戏闪现 cmd 窗口 — 排查分析与修复方案

> 日期：2026-09-30 · 状态：分析完成，待确认根因后实施修复
> 现象：在其他电脑上点击「启动游戏」会闪一个 cmd 窗口；开发机不闪。

## 1. 排查结论（摘要）

**当前源码里，GUI 主流程启动游戏本身不经过 cmd**：`LauncherView.launchGame()` → `UIGeneralControlClass.launchMinecraftAsync()` → `BaseLauncher.launch()` → `startProcessWithMonitoring()`，用 `ProcessBuilder` 直接拉起游戏 JVM，标准流走管道。

本机（Win10 19045 + JDK17）实测：**无论默认管道 / 重定向文件 / INHERIT / inheritIO，Java 拉起的 cmd 控制台窗口都不可见**（新建了不可见控制台，conhost 计数 29→30，但枚举不到可见窗口）。因此"本机不闪"是真实行为，不是没注意。

**"其他电脑闪"最可能的三个来源**（按可能性排序）：

1. **朋友机上的启动器是旧构建**：仓库里仍保留多条历史 cmd 启动路径（`MenuHandler.startGameProcess` 的 `cmd /c` + stdout INHERIT、`IndependentGameLauncher` 的 `cmd /c`、`generateBatScript` 生成的 .bat、更新脚本 `cmd /c start "" /min`）。旧版 EXE 如果走这些路径，就会在 GUI 无控制台的情况下新建可见控制台窗口 → 闪窗。
2. **PreLaunchCommand / PostExitCommand 在朋友机配置里有值**：这两条命令走 `cmd.exe /c <命令>`（`PlatformUtils.createShellProcessBuilder`）。当前仓库内配置为空，但分发出去的 `starlight.ini` 未必相同。
3. **游戏 Java 是控制台程序 `java.exe`（不是 `javaw.exe`）**：本机日志显示 `AutoJava=true` 且自动选中了 `...\bin\java.exe`。在部分环境/构建下，控制台子进程没有被隐藏窗口标志保护时，会带出一个黑色控制台；用户视角即"闪一个 cmd"（甚至常驻）。

次要来源（一般不会闪，但应一并加固）：更新脚本 `sl-update.bat`（`cmd /c start "" /min`，会闪最小化窗口/任务栏按钮）、CLI 模式遗留路径、帧生成/内存优化的 powershell（已带隐藏参数或走管道，实测不可见）。

## 2. 证据

| 项 | 证据 |
| --- | --- |
| GUI 主流程不走 cmd | `LauncherView.java:1301` → `UIGeneralControlClass.launchMinecraftAsync` → `VanillaLauncher.launch()` 字节码：`startProcessWithMonitoring(javaPath, args)`，无 cmd 包装 |
| 游戏用 java.exe | `logs/starlight.log`：`AutoJava selected: C:\Program Files\Microsoft\jdk-17.0.14.7-hotspot\bin\java.exe` |
| 打包 EXE 是 GUI 子系统 | `temp/log/build-20260929-213713/step6-link.log`：`/SUBSYSTEM:WINDOWS /ENTRY:mainCRTStartup` |
| 本机不闪（实测） | `temp/flash-test/`：javaw 父进程 + 4 种 spawn 方式 + 窗口枚举监视器，均无新可见控制台窗口；conhost 计数证实子进程获得的是不可见控制台 |
| 可疑遗留路径 | `MenuHandler.startGameProcess`（cmd /c + `redirectOutput(INHERIT)`）、`IndependentGameLauncher`（cmd /c，**无调用方=死代码**）、`generateBatScript`（生成 .bat）、`UpdateCoordinator:341`（cmd /c start "" /min） |
| 分发配置 | `Starlight-Launcher/starlight.ini`：`JavaPath=...\java.exe`、`AutoJava=true`、`PreLaunchCommand=`（空）、`PostExitCommand=`（空） |

## 3. 机理：为什么"本机不闪、其他电脑闪"

- Windows 规则：**控制台子进程在"父进程无控制台"时会被分配一个新控制台**；是否可见取决于创建标志（`CREATE_NO_WINDOW` 0x08000000 等）。Java/JDK 在 Windows 上对通过 `ProcessBuilder` 拉起的子进程默认隐藏控制台窗口（本机实测成立）。
- 因此闪不闪，取决于**走哪条拉起路径**（旧构建 vs 新构建）、**子进程是否被显式创建可见窗口**（`start`、ShellExecute 打开 .bat、INHERIT + 无隐藏标志）、以及**机器环境**（Windows 版本/终端宿主、杀软、Java 路径是否存在 → 走到不同分支）。
- 开发机与朋友机的差异，最可能是**构建版本不同**或**配置文件不同**（PreLaunchCommand / JavaPath / 更新触发），其次是 Win10/Win11 终端表现差异放大了同一个窗口。

## 4. 修复方案（按优先级）

### P0 — 取证（先确认根因，避免瞎修）
1. 确认朋友机 EXE 的构建日期/版本（文件属性→版本信息，或与我们打包日志对比）。
2. 让朋友录屏/描述：闪的窗口是黑框（cmd）、蓝框（powershell）还是最小化任务栏按钮？出现时机是点击后立刻、还是更新检查时？
3. 拷贝朋友机的 `starlight.ini` / `starlight-client.ini`，核对 `PreLaunchCommand`、`JavaPath`、`AutoJava`、更新相关项。
4. 可选：用 `temp/flash-test/watch-console.ps1` 这类监视器在朋友机上抓一次，确认窗口归属进程（cmd.exe / conhost / powershell / java.exe）。

### P0 — 代码加固（覆盖所有可疑路径）
1. **游戏一律用 javaw.exe**：`AutoJava` 选中与配置回退统一归一为 `javaw.exe`（同目录存在时优先；不存在才用 java.exe 并提示）。消除"游戏进程带控制台"。
2. **pre/post-launch 命令隐藏窗口**：`createShellProcessBuilder` 保持管道接管（当前实现即如此，勿改 INHERIT）；若未来需要 `start` 类命令，统一加隐藏。
3. **更新脚本改完全隐藏**：`UpdateCoordinator` 的 `cmd /c start "" /min sl-update.bat` 改为不弹窗方案（如 `cmd /c start "" /b` + 重定向，或 VBS 隐藏启动，或改用进程内更新）。
4. **清理遗留路径**：删除/停用无调用方的 `IndependentGameLauncher`；`MenuHandler.startGameProcess` 的 `redirectOutput(INHERIT)` 改为管道/文件，避免 CLI 模式或未来误用闪窗。

### P1 — 重新分发与回归
1. 重新打包 EXE（`build-single-exe.bat`），把新构建发给朋友机替换。
2. 回归验证：在本机用监视器脚本跑一次完整"启动游戏"，断言无新增可见控制台窗口；在朋友机复测。

### P2 — 文档与追踪
- 在 `docs/` 补一篇修复记录（本文件即为此用）；改动落地后按约定列出变更文件清单（长期文件存 `/tools`、临时文件存 `/temp`）。

## 5. 待确认项（影响最终根因判定）

- [ ] 朋友机 EXE 的构建版本/日期
- [ ] 朋友机 `starlight.ini` 的 `PreLaunchCommand` / `JavaPath` / `AutoJava`
- [ ] 闪窗进程归属（cmd / powershell / java）
- [ ] 朋友机 Windows 版本（Win10 / Win11）
