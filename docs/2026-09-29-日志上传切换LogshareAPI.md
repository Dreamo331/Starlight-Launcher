# 日志上传功能切换为 Logshare API

> 日期：2026-09-29 ｜ 范围：本仓库当前工作区
> 背景：旧日志上传服务器（`http://121.196.173.18:19000/api/logs`）已不可达（连接超时），
> 按 `temp/api.txt` 提供的 Logshare 平台文档切换到其 API。

## 一、接入方案

**默认端点**：`https://api.logshare.cn/v1/log`（LogShare.CN v1，公共 API，无需鉴权）
- 亦兼容旧版端点 `https://logshare.cn/api/upload` 与自定义服务器——`sendlogs` 的响应解析
  同时支持 `{success,url}`、`{success,url,shortUrl,id}`、`{success,id}`（按 id 拼 `https://logshare.cn/{id}`）、
  以及旧自定义服务器返回的纯文本 URL。
- 端点可配置：`assets/endpoints.json` 的 `services.logUploadUrl`（默认值已改）；
  本地真值见 `assets/endpoints.local.json`（gitignore，不入库），其中 `logUploadUrl` 已同步改为 Logshare。

**请求体（JSON，UTF-8）**：
```json
{
  "source": "starlight-launcher/1.0.0",
  "content": "主日志全文",
  "name": "latest.log",
  "files": [
    { "name": "crash-reports/crash-2026-09-14.txt", "content": "..." },
    { "name": "launcher.log", "content": "..." }
  ]
}
```
- `source` 固定为 `starlight-launcher/1.0.0`（帮助平台 AI 诊断引擎匹配启动器案例库）；
- 单条日志上限按文档 10MB，客户端上传前只取文件末尾最多 4MB（`readFileTail`，UTF-8 安全截断）；
- 只声明 `Accept-Encoding: gzip`（JDK 透明解压 gzip），不声明 br，避免收到无法解压的 Brotli 响应。

**响应解析**：优先取 `url` → `shortUrl` → `id` 拼接 → 纯文本 URL；
`success:false` 或非 2xx 直接抛带服务端错误信息的异常。

## 二、变更点

| 文件 | 变更 |
| --- | --- |
| `src/main/java/com/example/starlight/log/sendlogs.java` | 重写上传：multipart/form-data → Logshare JSON API；新增 `uploadLogBundle`（多文件打包）、`LogFileEntry`、`readFileTail` 公开方法；响应解析兼容 v1 / 旧版 / 自定义服务器 |
| `src/main/java/com/example/starlight/service/LogService.java` | 新增 `uploadBundleAndGenerateQR`（打包上传 + 二维码） |
| `src/main/java/com/example/starlight/newui/GameCrashPanel.java` | 「上传日志」改为打包上传：崩溃报告 + latest.log 末尾 + 启动器运行日志；无崩溃报告时退化为单文件上传 |
| `src/main/java/com/example/starlight/main/MenuHandler.java` | CLI `sendlogs` 统一走 Logshare 上传（删除原有自实现 JSON 上传） |
| `src/main/java/com/example/starlight/main/CliHandler.java` | `sendlogs` 的 server-url 参数改为可选 |
| `src/main/resources/assets/endpoints.json` | `services.logUploadUrl` 默认值 `""` → `https://api.logshare.cn/v1/log` |
| `src/main/resources/assets/endpoints.local.json` | 本地覆盖的 `logUploadUrl` 由旧服务器 → Logshare 端点（其余密钥未动） |

## 三、验证情况

- `mvn -o compile`：BUILD SUCCESS（284 源文件，无新增警告）；
- 本地 mock 服务器测试 13 项全过：标准 v1 响应 / 仅 id 拼接 / 纯文本 URL 兼容 /
  `success:false` 报错 / gzip 响应解压 / 请求体含 source、content、name / 多文件 files 数组结构；
- 真实端点冒烟：`sendlogs.uploadLogFile` 上传 `https://api.logshare.cn/v1/log` 成功，
  返回 `https://logshare.cn/RHIRrWK`；随后以 Bearer token 调用 `DELETE /v1/log/{id}` 返回 200（清理验证）。
  注：java 冒烟测试那次上传的日志 token 未被保留，无法删除，Logshare 上可能残留一条两行假日志（内容为上传功能验证，无害）；
- 测试中发现并修复 `readFileTail` 的截断 bug（原逻辑会把内容裁成只剩最后一个字符），
  已改为按 U+FFFD 剔除开头不完整字符。

## 四、说明

- 启动器运行期自动安装的 JDK、本机 Java 缓存等仍按既有约定放 `tools/`、`temp/`；
- 若你后续想切回旧版 Logshare 端点，只需改 `endpoints.local.json` 里的 `logUploadUrl` 为
  `https://logshare.cn/api/upload`（响应解析已兼容）；
- 上传 30s 连接 / 60s 读超时；限流为每 IP 每分钟 10 条（旧版文档）/ v1 按 IP+方法+路径计数。
