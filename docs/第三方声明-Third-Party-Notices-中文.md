# 星光启动器 Starlight Launcher · 第三方声明

**版本 1.0 · 更新日期：2026 年 10 月 20 日**

本文件列出星光启动器使用的第三方软件、数据与服务，及其各自适用的许可或条款。其目的是满足这些许可的署名要求，并让用户清楚"哪一部分由哪一个第三方负责"。

> **权威清单。** 第三方组件及其许可全文，以启动器内 **设置 → 版权 → 开源许可** 页面，以及发布包内的 `THIRD-PARTY-NOTICES` 文件为准。本文件与该页面不一致时，以该页面为准。
>
> **范围。** 本文件不修改、不限制、不补充任何第三方许可。第三方条款与本文件冲突时，就该第三方材料而言，以第三方条款为准。
>
> **English version:** see [Third-Party Notices](第三方声明-Third-Party-Notices.md).

---

## 一、运行时与界面框架

| 组件 | 用途 | 许可 | 说明 |
|---|---|---|---|
| JavaFX 17.0.6 | 全部用户界面 | GPL v2 + Classpath Exception | Classpath 例外使 MIT 协议项目可在其上构建；本项目按其条款分发。 |
| OpenJDK / Eclipse Temurin | 运行启动器与 Minecraft | GPL v2 + Classpath Exception | **不随包分发**。由用户按需从清华 TUNA / 华为云镜像或 Adoptium 下载，保存于 `~/.starlight/java/`。 |

## 二、依赖库

| 组件 | 用途 | 许可 |
|---|---|---|
| Gson | JSON 解析 | Apache-2.0 |
| Jackson（databind / core / annotations） | 插件 API 的 JSON 解析 | Apache-2.0 |
| Logback / SLF4J | 日志 | EPL-2.0 / LGPL-2.1（SLF4J 为 MIT） |
| OSHI | 本地读取内存与 CPU 信息 | Apache-2.0 |
| ice4j | 联机与中继所需的 STUN/TURN | Apache-2.0 / LGPL-2.1 |
| ZXing | 日志分享链接二维码 | Apache-2.0 |
| JNA | 必要的本地调用 | LGPL-2.1 / Apache-2.0 |
| Maven（仅构建期） | 构建与打包 | Apache-2.0 |

## 三、美术、字体与图标

| 组件 | 用途 | 许可 |
|---|---|---|
| Lucide 图标集 | 界面图标 | ISC |
| Bing 标识（`images/bing.png`） | 顶部搜索胶囊上的搜索引擎指示 | Microsoft 的商标与图形资源，仅作「此搜索会打开 Bing」的指示性使用；本项目与 Microsoft 无隶属关系，不主张任何权利 |
| 星光启动器图标与美术素材 | 项目标识 | © 2026 Starlight Launcher Contributors。**未授权在分支中复用**，见[许可协议](LICENSE-ZH.md)第 4 条。 |

## 四、网络服务与数据源

启动器在**用户操作时**代用户访问下列服务。这些服务均由第三方运营，适用其自身条款与隐私政策；星光启动器既不控制也不背书。

| 服务 | 用途 | 运营方 / 条款 |
|---|---|---|
| `piston-meta.mojang.com`、`launchermeta.mojang.com`、`libraries.minecraft.net`、`resources.download.minecraft.net` | 游戏版本元数据、依赖库、资源文件 | Mojang Studios / Microsoft |
| `login.microsoftonline.com`、`user.auth.xboxlive.com`、`xsts.auth.xboxlive.com`、`api.minecraftservices.com` | 微软账号登录与游戏档案 | Microsoft |
| `bmclapi2.bangbang93.com`（BMCLAPI）、`download.mcbbs.net`（MCBBS 镜像，已停服） | 中国大陆下载镜像 | 镜像运营方，与本项目无隶属关系 |
| `meta.fabricmc.net`、`maven.fabricmc.net` | Fabric 装载器 | FabricMC |
| `maven.minecraftforge.net`、`files.minecraftforge.net` | Forge 装载器 | MinecraftForge |
| `maven.neoforged.net` | NeoForge 装载器 | NeoForged |
| `meta.quiltmc.org` | Quilt 装载器 | QuiltMC |
| `optifine.net` | OptiFine | OptiFine |
| `api.modrinth.com`、`cdn.modrinth.com`（或 `mod.mcimirror.top` 镜像） | 模组与整合包搜索、下载 | Modrinth |
| `api.curseforge.com`、`edge.forgecdn.net`（或镜像） | 模组与整合包搜索、下载 | Overwolf / CurseForge |
| `raw.githubusercontent.com`（HMCL 模组词典）、`www.mcmod.cn` | 中文模组名称与链接 | HMCL 贡献者；mcmod.cn |
| `littleskin.cn`、`skin.prinzeugen.net`（Blessing Skin 官方演示）、`skin.mualliance.ltd`（MUA 皮肤站），以及你自行填写的皮肤站 | 外置登录（Yggdrasil / authlib）。`services.thirdPartyPresets` 里的预设只为「第三方登录」下拉框预填地址，选中不发起请求；请求一律由你点击登录触发 | 各皮肤站运营方，与本项目无隶属关系 |
| `authlib-injector.yushi.moe`、GitHub Releases | authlib-injector，按需下载 | yushijinhun，AGPL-3.0 |
| `mirrors.tuna.tsinghua.edu.cn`、`repo.huaweicloud.com`、`adoptium.net` | JDK 下载 | 各镜像运营方；Eclipse Adoptium |
| `api.logshare.cn` | **仅在你主动点击"上传日志"时**使用的日志分享 | Logshare |
| `api.natfrp.com`、`www.natfrp.com`、`nya.globalslb.net`（frpc 下载 CDN）及你所用隧道的节点域名 | FRP 联机（樱花 SakuraFrp）：访问密钥校验、隧道与节点管理、读取隧道配置、下载官方 frpc。访问密钥只保存在本机 | 樱花 SakuraFrp（natfrp），与本项目无隶属关系；使用时须遵守其服务条款 |
| 你自行配置的 AI 服务商（OpenAI、DeepSeek、Kimi、智谱、通义千问、豆包、硅基流动、OpenRouter、Groq、MiniMax、混元、文心一言、讯飞星火、阶跃星辰、百川、Gemini、Claude） | 你主动调用的文本类功能 | 你所选服务商 |
| 第三方社区站点 API（仅在配置了社区模块时） | 社区帖子与签到 | 该站点 |
| GitHub / Gitee | 更新检查与发布包下载 | GitHub, Inc. / Gitee |
| `cn.bing.com` | 顶部搜索胶囊：启动器只在本地把关键词拼成结果页地址，**交给系统默认浏览器打开**，不代替用户发起请求，也不读取或解析结果页 | Microsoft（Bing）；由你的浏览器直接访问 |
| 星光启动器官方发布渠道（`services.capeAgentUrl` 指向的地址，可配置） | 单 EXE 内置的披风补丁 agent（`cape-url-patch-agent.jar`）**仅在内置副本缺失时**的补下载；该组件由本项目自行编写并随源码以 MIT 提供 | 本项目 |

## 五、游戏内容与商标

- **Minecraft** 是 Mojang Studios 的商标。星光启动器**不是** Minecraft 官方产品，与 Mojang Studios、Microsoft **无隶属、背书、赞助或其他关联关系**。
- 游戏文件、资源与元数据来自 Mojang 服务或其镜像，仍适用 Mojang 条款。本软件不授予、也无法授予任何 Mojang 游戏内容的权利。
- 用户下载的模组、整合包、资源包、光影、皮肤与披风，仍适用其各自作者的条款。

## 六、明确不包含的内容

为避免疑义，星光启动器：

- **不含**任何 Mojang 游戏代码，也**不含** Minecraft 客户端文件；
- **不捆绑** JDK、authlib-injector 与任何模组——这些仅在用户主动请求时下载；
  （例外说明：披风补丁 agent `cape-url-patch-agent.jar` 是本项目自有的若干 KB 代码，
  作为内置资源随 EXE 分发，用于让离线账号的 OptiFine 披风指向本机回环地址；它不包含任何第三方代码。）
- **不含**广告 SDK、统计 SDK、崩溃自动上报 SDK，也**不追踪**用户行为；
- **不含**内建充值、支付、打赏功能，**不处理**任何支付；
- **不附带**任何可变现的服务器列表，**不从**任何第三方服务器取得分成。

## 七、再分发者的署名义务

若你分发星光启动器的构建版本，你必须：

1. 保留完整 MIT 声明（见[许可协议](LICENSE-ZH.md)第 3.3 条）；
2. 保留你的构建所含每个组件在本文件中的声明；
3. 以接收方可读的形式提供每个捆绑组件的许可文本——实践中即保持"设置 → 版权 → 开源许可"页面完整可用；
4. 按[许可协议](LICENSE-ZH.md)第 5.1 条标注你的构建为非官方版本。

删除开源许可页面，即违反 JavaFX、Logback、OSHI 等组件的许可，这与本项目的自有条款无关、单独构成违约。

---

*文档版本 1.0 · 2026。如发现清单有误，请通过官方仓库 Issues 反馈。*
