# 启动器 MOD 技术文档

> 版本：1.0.0（内测版）
> 版权：(C) Copyright 2026 Starlight. All rights reserved.
> 制作人员：deepseek
> 适用启动器：Starlight Launcher 2.0.0+

---

## 目录

1. [概述](#一概述)
2. [概念与边界](#二概念与边界)
3. [目录与文件约定](#三目录与文件约定)
4. [launcher-plugin.json 字段规范](#四launcher-pluginjson-字段规范)
5. [MOD 生命周期](#五mod-生命周期)
6. [API 交互协议](#六api-交互协议)
7. [可用 API 清单](#七可用-api-清单)
8. [开发流程](#八开发流程)
9. [内测注意事项](#九内测注意事项)

---

## 一、概述

启动器 MOD 是**启动器自身的扩展插件**，与 Minecraft 游戏的模组**没有任何关系**：

- MOD 存放在启动器专属目录 `Starlight-Launcher\mod`，游戏不会读取该目录；
- 启动器不解析 MC 版本、加载器等信息，不参与游戏的 mods 目录管理；
- 启动器 MOD 只能访问启动器通过本地 HTTP 服务暴露的 API（数据接口），自行负责 UI 展示。

当前该功能处于**内测阶段**，界面已标注「内测」，接口与行为可能调整。

## 二、概念与边界

| 概念 | 说明 |
|---|---|
| MOD 目录 | `<启动器根目录>\Starlight-Launcher\mod`，存放 MOD 的可执行 JAR |
| 元数据文件 | JAR 包**根目录**下的 `launcher-plugin.json`（启动器只读 JSON，不加载任何类） |
| 进程隔离 | MOD 以独立子进程 `java -jar <MOD.jar>` 运行，与启动器不共用 ClassLoader；崩溃不影响启动器 |
| 数据边界 | 启动器只提供**原始数据**（JSON 字节流），一切 UI 由 MOD 自己实现 |
| 交互方向 | 全部由 MOD **主动发起请求**；启动器从不向 MOD 推送 |

## 三、目录与文件约定

```
<启动器根目录>/
└── Starlight-Launcher/
    ├── mod/                    # MOD 目录（放入 *.jar 即可被扫描）
    │   └── starlight-mod.jar   # MOD 可执行 JAR
    └── plugins-state.json      # MOD 启用状态（{"enabled": ["plugin-id", ...]}）
```

- MOD JAR 内必须包含 `launcher-plugin.json`（位于 JAR 根目录），否则被跳过；
- 元数据非法的 JAR 会被跳过并记录日志，不影响其他 MOD；
- 新 MOD **默认禁用**，需在启动器「模组」页手动启用。

## 四、launcher-plugin.json 字段规范

```json
{
  "id": "community.example.my_mod",
  "name": "我的启动器MOD",
  "version": "1.0.0",
  "description": "在此描述功能",
  "launcher_api_version": "1.0.0",
  "main_class": "com.starlight.plugin.template.StarlightMod",
  "plugin_type": "community"
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `id` | ✅ | 全局唯一标识。`official.*` 前缀为官方插件保留，社区插件不得使用 |
| `name` | ✅ | 展示名称（显示在启动器「模组」页） |
| `version` | ✅ | 语义化版本，必须为合法 SemVer（如 `1.0.0`） |
| `description` | ❌ | 功能描述 |
| `launcher_api_version` | ✅ | 依赖的启动器 API 版本，必须为合法 SemVer |
| `main_class` | ✅ | 入口主类，必须与 JAR 清单的 `Main-Class` 一致 |
| `plugin_type` | ❌ | `official` 或 `community`，仅用于 UI 展示标识 |

## 五、MOD 生命周期

```
启动器启动
  └─ PluginScanner 扫描 Starlight-Launcher/mod 下的 *.jar
       └─ 读取 launcher-plugin.json（只读 JSON，不加载类）
            └─ 加载 plugins-state.json 启用状态
                 └─ 自动拉起所有已启用 MOD（子进程）
用户操作（「模组」页）
  ├─ 启用 → 立即启动子进程（失败则状态回滚为禁用）
  ├─ 禁用 → 优雅停止子进程
  └─ 删除 → 先停止并清除启用状态，再删除文件
启动器退出 → 停止所有 MOD 子进程（JVM 退出钩子）
```

启动命令：`<启动器自身JVM>/bin/java -Dstarlight.minecraft.dir=<游戏目录> -jar <MOD.jar>`
（工作目录 = MOD JAR 所在目录；子进程输出合并到启动器控制台）。

## 六、API 交互协议

启动器启动时在 `127.0.0.1` 上启动一个本地 HTTP 服务（端口由操作系统随机分配），
并把实际端口号写入 `<游戏目录>/starlight_api.port`（纯文本，仅含端口号）。

```
MOD 主动发起：

1. 定位端口文件 starlight_api.port（依次尝试：
   -Dstarlight.minecraft.dir 系统属性 → user.home/.minecraft → 当前目录 .minecraft）
   → 得到端口 → API 地址 http://127.0.0.1:<端口>

2. 握手：POST /api/v1/handshake
   请求体：
   {
     "plugin_id": "community.example.my_mod",
     "plugin_name": "我的启动器MOD",
     "plugin_version": "1.0.0",
     "launcher_api_version": "1.0.0",
     "required_apis": {"system/ping": "1.0.0"}
   }
   响应：{"session_token": "...", "available_apis": {...}}

3. 数据请求：GET /api/v1/data/{apiId}?param=value
   请求头：X-Session-Token: <session_token>
   响应：原始数据字节流（JSON 或二进制，由 MOD 自行解析）
```

- 错误响应统一为 JSON，含 HTTP 状态码：404=未知 API；400=参数错误；500=内部错误；
- 服务默认仅绑定回环地址 `127.0.0.1`（本机 MOD 才能访问）。

## 七、可用 API 清单

> 均为 v1.0.0。完整清单以启动器握手响应的 `available_apis` 为准。

| 分类 | apiId | 说明 |
|---|---|---|
| 账号 | `account/current` | 当前账号信息 |
| | `account/list` | 账号列表 |
| | `account/login/offline` | 离线登录 |
| 崩溃 | `crash/latest` | 最新崩溃报告 |
| 游戏 | `game/launch` | 启动游戏 |
| | `game/stop` | 停止游戏 |
| | `game/options` | 读取游戏设置 |
| | `game/options/update` | 更新游戏设置 |
| | `game/launcher_profiles` | 启动器档案 |
| Java | `java/list` | 已安装 Java 列表 |
| | `java/install` | 安装 Java |
| 模组 | `mods/list` | 游戏模组列表 |
| | `mods/toggle` | 切换模组启用/禁用 |
| | `mods/config` | 模组配置 |
| | `mods/resourcepacks` | 资源包 |
| | `mods/shaders` | 光影包 |
| 网络 | `net/frp/start` | 启动 FRP 内网穿透 |
| | `net/frp/stop` | 停止 FRP |
| | `net/lan/scan` | 局域网扫描 |
| | `net/slan/host` | SLAN 建房 |
| | `net/slan/join` | SLAN 加入房间 |
| 系统 | `system/ping` | 探活 |
| | `system/hardware` | 硬件信息 |
| 版本 | `version/manifest` | 版本清单 |
| | `version/installed` | 已安装版本 |
| | `version/install` | 安装版本 |
| | `version/loader/install` | 安装加载器 |
| 世界 | `world/saves/list` | 存档列表 |
| | `world/region/chunk` | 区块数据 |

## 八、开发流程

1. 复制 `启动器MOD模板` 目录，重命名为你的 MOD 工程；
2. 修改 `launcher-plugin.json`（id / name / version / description / main_class）；
3. 修改主类中的身份常量与业务逻辑（可参考模板中的完整交互流程）；
4. `mvn clean package` 构建出可执行 fat JAR；
5. 将 JAR 复制到 `Starlight-Launcher\mod`；
6. 在启动器「模组」页启用，观察启动器控制台日志验证。

详细步骤见《启动器MOD模板/使用说明.md》。

## 九、内测注意事项

- 本功能处于内测阶段，UI 已标注「内测」，API 协议与行为可能变更；
- MOD 以子进程运行，请勿在 MOD 内加载启动器类（进程隔离）；
- 社区插件不得使用 `official.*` 前缀；
- 启用的 MOD 会在启动器启动时自动拉起，请确保 MOD 退出逻辑正常（`EXIT_ON_CLOSE` 等）。

---

*版权 © 2026 Starlight · 制作人员：deepseek · 文档版本 1.0.0（内测）*
