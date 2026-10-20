# 法律文档存证与时间戳指南（Legal Witness）

本目录存放**法律文档定稿的存证工具与存证记录**，用于在发生争议时证明：
**"这些规则自建仓/发布之日起即已存在，且此后未被悄悄改写。"**

> 存证不是"给文件加魔法"，而是三件事的组合：
> **固定内容（哈希）+ 固定时间（可信时间来源）+ 固定持有者（可验证的签名或第三方托管）**。
> 缺任何一环，证明力都会打折。

---

## 一、已完成的存证（2026-10-20）

| 项目 | 值 |
|---|---|
| 对象 | `LICENSE` + `docs/` 下 10 份法律文本（共 11 个文件） |
| 当前清单 | [MANIFEST-20261020-195322.sha256](MANIFEST-20261020-195322.sha256) |
| 当前时间戳令牌 | [MANIFEST-20261020-195322.tsr](MANIFEST-20261020-195322.tsr)（RFC 3161） |
| TSA 服务 | FreeTSA（<https://freetsa.org/tsr>） |
| 当前时间戳时刻 | **2026-10-20 11:53:35 UTC**（= 北京时间 19:53:35） |
| 当前 Manifest SHA-256 | `0198b542adda913d4934a14e5a246974f92271777c312bcc739c1cba4aae1661` |
| 当前 Bundle SHA-256 | `98cc989c24367508de4e0ac8ab00d776d2475b6c3a70d7e037d8cc7ad17b9350` |
| 台账 | [WITNESS-LOG.md](WITNESS-LOG.md) |
| 校验结果 | `-Mode verify`：11 个文件全部 OK；令牌 imprint 与清单摘要一致，TSA 状态 granted |

> 历史清单与令牌（`MANIFEST-20261020-194133.*`）保留作链式记录；台账前三条是该日工具联调与修订过程，**以最新一条为准**。

> **为什么把时间戳落在清单上而不是压缩包上：** 清单是纯文本、会随仓库长期可读，任何人事后都能用 `Get-FileHash` 重算它与 `.tsr` 里的 imprint 比对；压缩包体积大、不入库，只适合在公证时作为实物提交。

## 二、怎么自证（任何人可复现）

```powershell
# 1) 重新计算当前法律文档的哈希，与清单比对
powershell -File tools/legal-witness/witness.ps1 -Mode verify

# 2) 确认清单本身未被改动（应等于上表的 Manifest SHA-256）
Get-FileHash tools/legal-witness/MANIFEST-20261020-195322.sha256 -Algorithm SHA256

# 3) 把清单重新提交给 TSA，比对返回令牌中的 genTime 与 imprint 是否与 .tsr 一致
powershell -File tools/legal-witness/witness.ps1 -Mode tsa -Target tools/legal-witness/MANIFEST-20261020-195322.sha256
```

三步全过，即证明：**这份文本内容与清单一致 → 清单的摘要自 2026-10-20 11:53:35 UTC 起已存在 → 时间由第三方 TSA 背书。**

## 三、存证对象

被存证的内容即仓库根目录的下列文件：

```
LICENSE
docs/README.md
docs/LICENSE-EN.md
docs/LICENSE-ZH.md
docs/用户服务协议.md
docs/免责声明.md
docs/隐私政策.md
docs/安全与合规.md
docs/商标与品牌使用规范.md
docs/第三方声明-Third-Party-Notices.md
docs/第三方声明-Third-Party-Notices-中文.md
```

**不含** `docs/` 下的历史技术文档（`YYYY-MM-DD-*.md` 等），它们与法律主张无关且会频繁更新。

## 四、工具：`witness.ps1`

PowerShell 脚本，Windows 自带环境即可运行（无需安装 OpenSSL、GnuPG）。

| 模式 | 作用 |
|---|---|
| `-Mode manifest` | 生成 `MANIFEST-<时间>.sha256` 与 `BUNDLE-<时间>.zip`，打印两者的 SHA-256 |
| `-Mode verify` | 重新计算当前文件哈希，与最近一份清单逐项比对（OK / MISMATCH / MISSING） |
| `-Mode tsa` | 对最近一份清单（或 `-Target` 指定文件）构造 RFC 3161 请求并取时间戳；自动校验 imprint |
| `-Mode record` | 把存证结果追加到 `WITNESS-LOG.md` |

常用命令：

```powershell
# 生成新清单与压缩包
powershell -File tools/legal-witness/witness.ps1 -Mode manifest

# 取时间戳（默认 FreeTSA；换成付费 TSA 只需改 -TsaUrl）
powershell -File tools/legal-witness/witness.ps1 -Mode tsa -TsaUrl "https://freetsa.org/tsr"

# 登记台账
powershell -File tools/legal-witness/witness.ps1 -Mode record -Provider "FreeTSA" -Reference "genTime <贴入 .tsr 中的 14 位 UTC 时刻>"
```

> 若脚本报语法错误：Windows PowerShell 5.1 读取无 BOM 的 UTF-8 中文脚本会乱码。本脚本已保存为 **UTF-8 with BOM**，修改后请保持 BOM。

## 五、三条存证路径与成本

| 路径 | 证明力 | 成本 | 适用 |
|---|---|---|---|
| A. 仅提交清单到 git | 能证明提交时的内容，但提交时间可被本地伪造 | 0 元 | 日常留痕 |
| B. TSA 时间戳（RFC 3161，如 FreeTSA、联合信任 TSA、UniTrust） | 第三方 CA 证明某时刻该摘要已存在，民事诉讼中通常被采信 | FreeTSA 免费；商业 TSA 数元至数十元/次 | **推荐，本项目已采用** |
| C. 公证处保全证据公证 / 司法区块链 | 证明力最强，可被法院直接核验 | 数百元起 | 出现纠纷苗头、或大规模商业分发前 |

**建议：** 日常用 **A + B**（每次发布或每次修订法律文本后做一次），出现纠纷时补 **C**。

## 六、证明力的边界（必须诚实说明）

存证能证明"**内容与时间**"，**不能**证明：

- **不能**证明这些条款在法律上有效。免责条款是否有效，仍取决于是否符合《民法典》第 496–498 条（格式条款的提示说明义务）、第 506 条（不得免除的责任）以及《消费者权益保护法》的强制性规定。
- **不能**证明"用户已经同意"。同意的证据需要**产品侧留痕**（首启弹窗点击记录 + 协议版本号 + 时间戳），见[安全与合规](../../docs/安全与合规.md)第八节。
- **不能**让不存在的功能变成存在。文档中写的"内建黑名单阻断""无支付接口"必须与代码事实一致；若对方举证代码里没有，反而会削弱全部文档的可信度。

> **一句话：存证固定的是"你说了什么、什么时候说的"，不是"你说的算不算数"。**

## 七、维护约定

- 每次发布新版本，或每次修订法律文本后，重新执行 `manifest` → `tsa` → `record`。
- 不覆盖历史 `MANIFEST-*.sha256`、`*.tsr` 与 `WITNESS-LOG.md` 的既有条目；新条目追加在末尾。
- `verify` 报 MISMATCH 是**有意修订的预期结果**：先提交修订 → 生成新清单 → 取新时间戳 → 在台账写明"第 N 次修订及原因"。
- `BUNDLE-*.zip` 已被 `.gitignore` 忽略（体积大且可重建）；**清单、`.tsq`、`.tsr`、台账必须入库**。
- 下一轮 `manifest` 会包含本目录的台账与上一份清单，形成链式记录。

---

*本文档属于工具说明，不构成法律意见；正式使用前建议咨询执业律师。*
