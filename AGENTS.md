# AGENTS.md — Starlight Launcher 项目全局目录

> **本文件是项目的"总目录 + 修改规则"，写给每一次进入本项目的 AI 模型或人类协作者。**
> 开始任何改动前，先读完本文件；改完之后，按第五节的检查表逐项确认。

---

## 一、项目一句话

**星光启动器（Starlight Launcher）**：Java 17 + JavaFX 17 从零编写的 Minecraft 启动器，Maven 构建，MIT 开源。
代码约 7.9 万行（357 个 `.java`），包名根为 `com.example.starlight`。

| 事实 | 值 |
|---|---|
| 构建 | `mvn clean compile`（Java 17，无 wrapper，`mvn` 需自备） |
| 版本号唯一权威 | `src/main/java/com/example/starlight/newui/AppConfig.java` → `APP_VERSION` |
| 打包 | `build-single-exe.bat`（GraalVM native-image） |
| 许可 | MIT，[LICENSE](LICENSE)；完整法律文本在 [docs/](docs/README.md) |
| 官方仓库 | GitHub `Dreamo331/Starlight-Launcher` · Gitee `Horses-always-love-to-run/starlight-launcher-cn` |

---

## 二、根目录总览（改哪里）

```
starlight-launcher/
├── AGENTS.md              ← 本文件：总目录与修改规则
├── README.md              项目说明（含版本号规则、架构图、下载体系）
├── LICENSE                MIT 正本 + 标识权补充说明
├── pom.xml                Maven 构建；依赖、native-image 配置、JNI 注册
├── build-single-exe.bat   一键打包单 EXE
├── .gitignore             忽略规则（构建产物、本地数据、密钥）
│
├── docs/                  文档与法律文本（重点）
│   ├── README.md          法律文档索引（效力层级、条款落点、再分发清单）
│   ├── LICENSE-EN.md      开源协议正本（英文）
│   ├── LICENSE-ZH.md      开源协议中文译本
│   ├── 用户服务协议.md      EULA
│   ├── 免责声明.md         免责声明（首启弹窗可用）
│   ├── 隐私政策.md         隐私政策
│   ├── 安全与合规.md       面向平台审核的技术事实
│   ├── 商标与品牌使用规范.md
│   ├── 第三方声明-*.md     第三方组件与服务清单（中/英）
│   └── YYYY-MM-DD-*.md    历史技术文档（按日期命名，与法律文本无关）
│
├── tools/                 构建与辅助脚本
│   ├── legal-witness/     法律文档时间戳存证（重点）（清单 / .tsr / 台账 / witness.ps1）
│   ├── agent/             CapeUrlPatchAgent.java（披风 URL 补丁 agent 源码）
│   ├── rcedit-x64.exe     写入 EXE 元数据的工具
│   └── *.ps1 / *.bat / *.cmd  构建流水线脚本
│
├── src/main/java/com/example/starlight/
│   ├── newui/             新 UI（重点）（页面、设置、AI、联机页、皮肤面板）
│   │   ├── page/          各功能页（首页、下载中心、资源管理、设置…）
│   │   ├── page/settings/ 设置子页（关于页含 GitHub/Gitee 链接）
│   │   ├── ai/            AI 助手：服务商表、模型目录、选择器
│   │   └── AppConfig.java 版本号唯一权威
│   ├── launch/ startmc/ gamebat/   启动引擎（Vanilla/Forge/Fabric/NeoForge/Quilt/OptiFine）
│   ├── download/          下载调度（engine / source / task / mod 四层）
│   ├── ModsApi/           Modrinth / CurseForge / mcmod 中文词典
│   ├── auth/ loginmicrosoft/   微软登录、外置登录、authlib-injector、离线皮肤
│   ├── slan/ frp/         SLan 联机模块、FRP 中继
│   ├── crash/ log/        崩溃诊断、日志上传（Logshare）
│   ├── plugin/ pluginapi/ 插件系统与本机 HTTP API（仅绑定 127.0.0.1）
│   ├── config/            AppDirs（数据目录唯一真源）、Endpoints、迁移
│   ├── community/         第三方社区站点 API 客户端
│   └── util/              更新检查、网络、图片、WebP 解码等
│
├── src/main/resources/    图片、i18n、css、字体、游戏资源模板、logback.xml
├── lib/                   cape-url-patch-agent.jar（被 LocalCapeProxy 引用，勿删）
├── native/                management_stub.c（被 pom 的 JNI 注册引用，勿删）
│
├── references/            只读参考：PCL / HMCL / XMCL 上游源码（不入库，可重建）
└── .minecraft/            本机测试游戏目录（不入库）
```

**六个"唯一真源"（改之前先确认你改的是不是它）：**

| 事项 | 唯一真源 |
|---|---|
| 版本号 | `newui/AppConfig.java` → `APP_VERSION`（改完同步 `pom.xml`、`latest_version.json`、tag） |
| 用户数据路径 | `config/AppDirs.java`（禁止再写 `Starlight-Launcher/...` 字面量） |
| 第三方地址 | `config/Endpoints.java` + `src/main/resources/assets/endpoints.json` |
| 站点真实密钥 | `src/main/resources/assets/endpoints.local.json`（**已 gitignore，绝不提交**） |
| 法律条款 | `docs/` 下那份 `.md` 本身，其他地方只做引用不复述 |
| 存证哈希 | `tools/legal-witness/MANIFEST-*.sha256` 与 `WITNESS-LOG.md` |

---

## 三、法律文档清单（每次改动都要过一遍）

| 文件 | 管什么 | 什么时候必须改 |
|---|---|---|
| [LICENSE](LICENSE) | MIT 正本 + 标识权补充 | 极少改；改了必须同步两份 LICENSE 文档 |
| [docs/LICENSE-EN.md](docs/LICENSE-EN.md) | 开源协议正本（英文） | 修改代码许可、标识条款、第三方清单时 |
| [docs/LICENSE-ZH.md](docs/LICENSE-ZH.md) | 开源协议中文译本 | 每次改 LICENSE-EN 必须同步 |
| [docs/用户服务协议.md](docs/用户服务协议.md) | EULA：使用限制、免责、未成年人 | 新增功能引入新责任/新收费/新数据时 |
| [docs/免责声明.md](docs/免责声明.md) | 对外免责口径 | 与上面同步 |
| [docs/隐私政策.md](docs/隐私政策.md) | 数据处理 | **新增任何网络请求、数据收集、上传时** |
| [docs/安全与合规.md](docs/安全与合规.md) | 可核查技术事实与自查表 | 引入/移除安全边界时 |
| [docs/商标与品牌使用规范.md](docs/商标与品牌使用规范.md) | 名称、图标、署名 | 标识策略变化时 |
| [docs/第三方声明-*.md](docs/第三方声明-Third-Party-Notices-中文.md) | 第三方组件与服务 | **新增依赖、新增外部服务域名时** |

---

## 四、改代码时必须对照的协议红线

修改任何功能前，扫一眼这张表。**代码事实必须与文档一致**——文档写了而代码没有，或代码做了而文档没写，都会在争议时反噬。

| 红线 | 代码约束 | 对应文档 |
|---|---|---|
| **不内置任何充值/支付/打赏/代收** | 不引入支付 SDK，不加收款账户，不加"赞助解锁" | EULA 3.2、3.4；免责声明 2.3 |
| **不与第三方服务器建立商业绑定** | 不加付费服务器推广位、不加分成、不加"官方合作"服务器列表 | EULA 5.1–5.3；LICENSE §8.2 |
| **不收集个人信息** | 不加统计/埋点/崩溃自动上报 SDK；调试埋点只能落**本地日志** | 隐私政策 3.3、第四节 |
| **日志上传必须由用户主动触发** | 保留 `GameCrashPanel.handleSendLog()` 的手动触发；不得改为后台自动上传 | 隐私政策 3.3；免责声明 6.1 |
| **插件 API 只绑回环地址** | `PluginApiServer` 必须保持 `new InetSocketAddress("127.0.0.1", 0)` 与会话令牌 | 安全与合规 第五节 |
| **不提供开服/服务端能力** | 不加开服向导、不生成 `server.properties`/`eula.txt`、不做端口映射 | docs/README 第四条 |
| **不冒用项目标识** | 不改官方下载链指向、不删关于页署名 | LICENSE §4.2；商标规范 |
| **违法内容处置是"有权"不是"义务"** | 若实现黑名单，只做"可远程更新的屏蔽列表 + 提示"，不要写成自动审核 | EULA 5.8 |
| **与 Mojang 无关联** | 任何界面文案不得暗示官方或"授权" | 免责声明 1.2；LICENSE §7.1 |

**改完代码自检三问：**
1. 我新增了网络请求吗？→ 有则更新[隐私政策](docs/隐私政策.md) 3.3 表格与[第三方声明](docs/第三方声明-Third-Party-Notices-中文.md)第四节。
2. 我新增了依赖吗？→ 有则更新[第三方声明](docs/第三方声明-Third-Party-Notices-中文.md)第二节与启动器内"设置 → 版权 → 开源许可"。
3. 我的改动让某段文档描述失真了吗？→ 有则**改文档**，不要留着过期表述。

---

## 五、改了法律文档之后：必须重新存证

法律文本一旦改动，旧的哈希清单就失效了。**必须重新走一遍存证流程并推送到仓库**，否则"这些规则最初就是这样"的证明链会断。

```powershell
# 1. 生成新的哈希清单与压缩包
powershell -File tools/legal-witness/witness.ps1 -Mode manifest

# 2. 对清单取 RFC 3161 时间戳（自动校验 imprint）
powershell -File tools/legal-witness/witness.ps1 -Mode tsa

# 3. 把结果登记进台账
powershell -File tools/legal-witness/witness.ps1 -Mode record `
    -Provider "FreeTSA" -Reference "<令牌编号或 genTime>"

# 4. 校验当前文本与清单一致
powershell -File tools/legal-witness/witness.ps1 -Mode verify

# 5. 提交并推送（这一步才是"上传到仓库"）
git add -A
git commit -m "docs: 修订法律文档并重新存证"
# 本地分支名（github-clean）与远程分支名（main）不同，必须显式写 HEAD:main
# 冒号后的 main 表示"推到远程的 main 分支"，缺了它 git 会推成同名的 github-clean 分支
git push github HEAD:main
git push gitee HEAD:main
```

**注意：**
- Windows PowerShell 5.1 读取无 BOM 的 UTF-8 中文脚本会乱码，`witness.ps1` 必须保持 **UTF-8 with BOM**。
- `BUNDLE-*.zip` 不入库（见 `.gitignore`）；**`MANIFEST-*.sha256`、`*.tsr`、`*.tsq`、`WITNESS-LOG.md` 必须入库**。
- 历史清单与令牌**不要删**，它们是链式记录的一部分。
- 存证之后**不要再改**这批文件；若必须改，重新走一次流程并在台账写明原因。

---

## 六、改动前的标准流程

1. **读**：本文件 → 涉及的法律文档 → 相关 `docs/YYYY-MM-DD-*.md` 技术文档。
2. **查唯一真源**：版本号、路径、地址、条款分别在哪个文件里（见第二节表格）。
3. **确认红线**：对照第四节，确认改动不越过任何一条。
4. **改代码**：只做必要改动，不自作主张重构无关模块。
5. **改文档**：代码与文档必须同时更新，不允许只改一边。
6. **验证**：`mvn clean compile` 通过；如改了法律文本，跑完第五节的存证流程。
7. **提交**：提交信息说明"改了什么 / 为什么改 / 是否改动了法律文本"。

---

## 七、环境与常见坑

| 事项 | 说明 |
|---|---|
| 构建 | `mvn clean compile`；`mvn` 未在 PATH 时用 IDE 内置 Maven |
| Java | 17（本机 `openjdk 17.0.18`），不要用 21+ 编译 |
| 编码 | 源码与文档统一 **UTF-8**；PowerShell 脚本（含中文）用 **UTF-8 with BOM** |
| 数据目录 | 默认 `~/.starlight/`，`--portable` 回落工作目录；见 `config/AppDirs.java` |
| 密钥 | `endpoints.local.json` 与 `%USERPROFILE%\.starlight\endpoints.local.json` 已忽略，**任何情况下不得提交** |
| 删除前确认 | 删除目录前先跑 `git ls-files -- <路径>`，确认未被跟踪；`lib/`、`native/` 被 pom 引用，**不可删** |
| 未跟踪目录 | `references/`（上游源码参考）、`.minecraft/`（测试游戏）、日志与构建产物 |

---

## 八、一句话总结

**改代码 → 对照第四节红线 → 同步文档 → 若动了法律文本就重新存证并推送 → 提交时写明理由。**
文档过期比文档缺失更危险：缺失只是没写，过期等于写错。
