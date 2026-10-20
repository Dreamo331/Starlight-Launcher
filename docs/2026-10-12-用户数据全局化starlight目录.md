# 用户数据全局化到 `~/.starlight/`（实施记录）

日期：2026-10-12
相关盘点：[配置文件与可下载工具盘点及全局目录建议.md](配置文件与可下载工具盘点及全局目录建议.md)

## 一、目标与决策

把散落在三处的用户级数据统一到 **`~/.starlight/`**，废弃 `~/.starlight-launcher/`。
经确认的决策：

| 议题 | 决定 |
|---|---|
| 迁移方式 | 自动**移动**到新位置，旧文件删除 |
| 旧目录 | 迁移成功后自动删除（非空则保留并提示） |
| 便携能力 | 保留：默认全局，`--portable` 回落工作目录 |
| `multiplayer.json` | 内置运营默认 + 全局仅存用户改动，逐键回落 |
| 迁移范围 | 配置文件 + 运行时组件（JDK/Terracotta/SLan/ColorBlind）+ `mod/` + 壁纸/皮肤 |
| 日志 `logs/` | **留在工作目录**（便于就地排查） |
| 孤儿文件 | 收进 `~/.starlight/legacy/`，不静默删除 |
| 目录结构 | 扁平：原文件名平铺到 `.starlight/` 根 |

## 二、新增代码

### `config/AppDirs.java` — 路径唯一真源

所有用户级路径的唯一解析入口。约定：**任何类都不再写 `"Starlight-Launcher/..."` 字面量**。

- `initAndStrip(args)`：剥离 `--portable` 并初始化，返回剩余参数；
- 全局模式 → `~/.starlight`；便携模式 → `<工作目录>/Starlight-Launcher`；
- 提供 `starlightIni()` / `accountsJson()` / `javaDir()` / `pluginsDir()` / `colorBlindExe()` 等取址方法；
- 导出系统属性 `starlight.data.dir` 供插件子进程与诊断使用。

**兜底探测**：部分类把路径放在 `static final` 字段里，若比 `init()` 更早被类加载就会被冻结。
因此 `root()` 在未初始化时会额外探测 `-Dstarlight.portable`、`sun.java.command`
与 `ProcessHandle` 命令行，避免「类加载顺序」隐式决定数据目录。

### `config/LegacyMigration.java` — 一次性迁移

三个来源：`~/.starlight-launcher/`、`<工作目录>/Starlight-Launcher/`、`%APPDATA%\StarlightFrameGen/`，
外加 `<工作目录>/tools/java` → `~/.starlight/java`。

关键设计：

1. **先复制、校验通过后再删源** —— 不用 `Files.move`，因为工作目录可能在另一个盘（跨卷 move 目录会失败）；
   中断最多留下重复文件，不丢数据。
2. **目标已存在按修改时间取新**，避免覆盖更新的配置。
3. **绝对路径改写**：`starlight.ini` 的 `JavaPath`/`JavaPathHistory`、`starlight-client.ini` 的
   `BackgroundImage`、账号 `skinJson` 的 `localSkinPath`/`localCapePath` 都是绝对路径，文件搬走后会失效。
   改写时**长前缀优先**（否则 `<工作目录>` 的通用规则会先命中，把 `work\tools\java` 错改成 `.starlight\tools\java`），
   并分别覆盖 **1 / 2 / 4 层反斜杠转义** —— `accounts.json` 的 `skinJson` 是「装着 JSON 的 JSON 字符串」，
   里层路径被转义两次，落盘即 `G:\\\\a\\\\b`。只处理前两层会漏改皮肤路径。
4. **空目录自底向上清理**，避免 `.minecraft/versions` 这类自动生成的空骨架把父目录撑成「非空」而删不掉。
5. 失败只记日志、不阻断启动；跨进程幂等（源不存在即跳过）。

### 入口接入

- `MainApp.main()` 第一行调用 `AppDirs.initAndStrip(args)`；
- `JavaFXLauncher.main()` 同样接入（IDEA 运行配置常直接指向它，否则会跳过迁移）；
- `Endpoints` 新增读取 `~/.starlight/endpoints.local.json`，**真实密钥不必再重新打包**。

### `SLanConfig` 语义调整

原实现「首次从 jar 模板复制一份，之后磁盘整文件说了算」，导致运营换服后老用户不跟随。
现改为：模板是运营默认值，全局文件只承载用户改动，`getString()` 逐键回落模板；
首次生成的是**空覆盖骨架**而非模板副本。

## 三、迁移映射

| 旧位置 | 新位置 |
|---|---|
| `~/.starlight-launcher/accounts.json` | `~/.starlight/accounts.json` |
| `~/.starlight-launcher/login.json`、`external_login.json` | 同名平铺 |
| `~/.starlight-launcher/authlib-injector.jar` | `~/.starlight/authlib-injector.jar` |
| `~/.starlight-launcher/avatars/` | `~/.starlight/avatars/` |
| `~/.starlight-launcher/java-cache.json` | `~/.starlight/java-cache.json` |
| `~/.starlight-launcher/{auth_servers,endpoints.real}.json` | `~/.starlight/legacy/`（代码已不引用） |
| `<工作目录>/Starlight-Launcher/starlight*.ini` | `~/.starlight/` |
| `<工作目录>/Starlight-Launcher/.account.dat`、`.community.dat` | 同名平铺 |
| `<工作目录>/Starlight-Launcher/mod/` | `~/.starlight/mod/` |
| `<工作目录>/Starlight-Launcher/{skin,wallpaper,mod-dictionary}/` | 同名目录 |
| `<工作目录>/Starlight-Launcher/{terracotta,slan,ColorBlindOverlay}/` | 同名目录 |
| `<工作目录>/tools/java/` | `~/.starlight/java/` |
| `%APPDATA%\StarlightFrameGen\config.properties` | `~/.starlight/framegen/config.properties` |
| `~/.starlight/{cache,terracotta-cache}/` | 原地保留（本就在 `.starlight` 下） |

**不迁移**：游戏目录内的 `.cache/verify-state.json`（判据是文件自身 mtime+size，全局化会让多个游戏目录互相污染）、
`versions/*/config.overrides.json`（版本隔离语义）、`logs/`（按决策留在工作目录）。

## 四、验证

在**完全隔离的沙箱**中用真实数据副本跑通（沙箱自带 `user.home`/`user.dir`/`APPDATA`，不触碰真实系统）：

- **最终回归 24 项全部通过**：目录/文件到位、两个旧目录已删除、5 个账号完好、
  皮肤与披风路径已改写且目标文件真实存在、ini 中无旧路径残留；
- **幂等**：连续 `init` 三次，文件数稳定 35，无数据丢失；
- **便携模式**：`--portable` 正确剥离，数据回落到工作目录，源目录原样保留；
- **兜底探测**：不调 `init` 直接读 `static final` 字段时，`-Dstarlight.portable=true`
  与命令行 `--portable` 均能被识别；
- **`mvn clean compile` 通过**。

## 五、遗留与注意

1. **便携性契约已改写**：README 原写「数据都在工作目录、删掉 `Starlight-Launcher/` 即可重装」，
   全局模式下需删 `~/.starlight/`（会连账号一起删），已在 README 更新。
2. **首次运行才会真正迁移**：本次只改代码并编译，未运行启动器，因此
   `G:\hello-starlight\starlight-launcher\Starlight-Launcher\` 与 `~/.starlight-launcher/` 仍在原地，
   首次以新代码启动时才会搬动。建议先用 `--portable` 或备份验证一次。
3. `UIGeneralControlClass.CONFIG_DIR/CONFIG_FILE`、`MenuHandler.CONFIG_FILE`、
   `PluginScanner.DEFAULT_PLUGINS_DIR` 等常量已标注 `@Deprecated` 且不再参与寻址，
   后续可清理。
