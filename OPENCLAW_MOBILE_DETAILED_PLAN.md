# OpenClaw Mobile（代号 Nana）“傻瓜可用”详细计划

目标用户：**长辈用户 / 非技术用户**（不懂 Key/不懂配置/不会排错）。  
目标规模：**3 万台手机预装**。  
主路线：**Route A**（BotDrop fork 做系统级预装 App）+ **内置语音聊天 UI** + **出厂预置 device_token** + **云端 OpenAI-compatible Proxy** + **订阅三档：DeepSeek / Kimi / Claude**。

---

## 0) 成功标准（必须量化）

用户侧：
- 开机首次点开 App 到“能说话有回复”：≤ 30 秒（已预装/已出厂跑过安装的前提下）
- 全程 0 复制粘贴、0 终端、0 配置文件
- 出错时用户只需要点“修复”或把“支持码”发给客服

工程侧：
- 端侧不存任何上游模型厂商 key（只存 `device_token`）
- 模型切换/限额/订阅升级全部在服务端完成（端侧不发版也能换）

---

## 1) 终局体验（用户视角）

桌面只有一个图标：**语音助手（Nana）**。

1. 点开 App
2. 一个大麦克风按钮
3. 说话
4. Nana 回答（文字 + 语音播报）

用户永远看不到：
- API key / token 输入框
- Telegram/Discord bot 配置
- Termux/终端
- “选择模型”这种吓人的选项

---

## 2) 系统架构（可落地）

### 2.1 端侧（Android 预装 App）
基于 `botdrop-android`（Termux bootstrap + OpenClaw 安装器 + Foreground Service 保活）做 fork。

端侧职责只做 4 件事：
1. 自动安装运行时：首次启动解压 bootstrap，安装 OpenClaw（后续可加 NanaBot）
2. 自动写配置：读取 `device_token`，写入 OpenClaw 配置（env 注入）
3. 稳定保活：Foreground Service 守护 OpenClaw gateway（必要时也守护 NanaBot）
4. 傻瓜 UI：内置聊天 UI（语音输入 + TTS 播报）

### 2.2 云端（OpenAI-compatible Proxy）
暴露一个 OpenAI-compatible 的入口，端侧 OpenClaw 当它是 “OpenAI API” 来用：
- `POST /v1/chat/completions`
- `GET /health`
-（可选）`GET /v1/models`

Proxy 做的事情：
- `device_token` 鉴权（Authorization: Bearer）
- token → 订阅档位（free/pro/max）
- 强制限额（上下文、输出 token、日/月额度、并发、速率）
- 路由到上游（DeepSeek / Kimi / Claude）
- 降级与 fallback（上游挂了 → 退回 DeepSeek）

### 2.3 出厂（Factory Provisioning）
把“用户不会做的事”做掉：
- 预装 APK（最好作为 system app）
- 每台机写入 `device_token`
- 首次启动跑一遍安装 + health check
-（可选）预热：跑 1 次对话，确保链路 OK

---

## 3) 关键决策（你已确认）

### 3.1 三档订阅（free/pro/max）
你定的三档：
1. **DeepSeek（free）**
2. **Kimi（pro）**
3. **Claude（max）**

### 3.2 Proxy 对外域名（建议）
建议统一：`https://api.openclaw.ai`  
端侧默认：`OPENAI_BASE_URL=https://api.openclaw.ai/v1`

为 OEM/QA 强制某档，保留可选路径（服务端仍必须做越权校验，防止被改配置越权）：
- `https://api.openclaw.ai/deepseek/v1`
- `https://api.openclaw.ai/kimi/v1`
- `https://api.openclaw.ai/claude/v1`

---

## 4) Proxy 路由与限额（默认推荐值）

### 4.1 默认模型 ID（服务端内部配置）
端侧不需要知道真实上游模型 ID；**真实模型 ID 全在服务端配置**（可随时替换，端侧 0 改动）。

建议初始映射（可替换）：
- Free: `deepseek-chat`
- Pro: Kimi 32K 档（例如 `moonshot-v1-32k` 或你们选定的 Kimi 32K/长上下文型号）
- Max: Claude Sonnet 档（例如 `claude-3-5-sonnet-latest`）

> 免费档推荐 DeepSeek：省成本、覆盖大多数日常问答；把昂贵能力锁在 Pro/Max。

### 4.2 建议限额（成本优先，适配“长辈日常问答”）
把“长文/重度用户成本”牢牢锁在服务端。

- Free（DeepSeek）：
  - 上下文：8K（服务端截断）
  - 输出：512 tokens
  - 日额度：20-50 轮对话/天（或按 token 额度）

- Pro（Kimi）：
  - 上下文：32K
  - 输出：1024 tokens
  - 日额度：200 轮/天

- Max（Claude）：
  - 上下文：64K
  - 输出：2048 tokens
  - 日额度：200 轮/天（重点是并发/速率限制，避免被滥用）

### 4.3 降级策略（稳定性）
按“能回答”优先：
1. Claude/Kimi 调用失败（429/5xx/超时）→ 自动降级到 DeepSeek
2. DeepSeek 也失败 → 返回端侧短句：“网络/服务异常，请稍后再试”

---

## 5) device_token（出厂身份）设计

### 5.1 token 的来源与存放（端侧）
端侧读取优先级（已实现）：
1. 系统属性：`persist.oyster.device_token`（最推荐，适合 system app / ROM）
2. App 私有文件：`/data/data/app.botdrop/files/device_token.txt`（调试/QA 友好）

### 5.2 token 格式（推荐）
建议生成不可猜的随机 token，并带版本前缀便于轮换：
- `ocw1_<random_base64url_32bytes>`

档位绑定方式两种：
1. **DB 绑定（推荐）**：token → tier（可升级/可封禁/可限额）
2. token 前缀编码 tier（省一次 DB 查，但不利于升级与风控）

建议：DB 绑定 + Redis/内存缓存（扛 3 万台量级）。

---

## 6) 端侧 UX（让傻瓜都能用）

### 6.1 首次启动的唯一阻力：权限
真正傻瓜用，尽量把权限从用户手里拿走：
- system app：
  - 用 `privapp-permissions` 白名单预授予：通知、录音、忽略电池优化等
  - 用户无需点“允许”
- 非 system app（兜底）：
  - 只留 2 个大按钮：通知 + 电池白名单
  - 文案极短，失败给“一键修复”

### 6.2 聊天界面（长辈用户适配）
原则：一屏，一按钮。
- 大麦克风（按住说话，松开发送）
- 默认语音播报
- 大字体，高对比
- 网络差只提示一行，不弹窗

### 6.3 一键客服支持（决定你客服成本）
- 一键生成：支持码 + 诊断包（版本/日志 tail/配置脱敏）
- 客服后台按支持码查：失败率、延迟、近期请求错误类型、剩余额度

---

## 7) 工程路线图（按周落地）

### Week 1：MVP（能预装、能说话、能计费路由）
1. Android（Route A）：
   - 自动 bootstrap
   - 自动安装 OpenClaw
   - 自动写入 `OPENAI_BASE_URL` + `OPENAI_API_KEY=device_token`
   - 内置 chat UI（语音 + TTS）
2. Proxy（最小可用）：
   - `/v1/chat/completions`
   - token 鉴权（先硬编码/SQLite）
   - DeepSeek 路由跑通
3. Factory（最小流程）：
   - ADB 写 token
   - 启动一次 App 自动装好

### Week 2：三档订阅 + 限额 + 运维
1. Proxy：
   - Kimi/Claude 路由
   - 限额与降级
   - admin 工具（生成 token、升级 tier、查看 usage）
2. Android：
   - 一键修复（重启服务/重新安装）
   - 支持码/诊断包导出

### Week 3：规模化预装与质量
1. 系统预授予权限（system app/privapp-permissions）
2. 灰度升级（1%→10%→50%→100%）
3. 典型厂商电池策略适配（小米/OPPO/vivo/华为）

### Week 4：商业化（适配长辈用户）
1. 订阅入口：
   - 线下二维码卡片（家人扫码付费 → 自动升级该 device_token）
   -（后续）微信/支付宝
2. 可选“家人模式”：
   - 家人远程查看摘要、帮她设置提醒（不强依赖）

---

## 8) 如何做得更好（真正能占领市场的点）

1. **系统级权限预装**：把“允许通知/电池白名单/录音权限”从用户手里拿走
2. **语音识别兜底**：很多国内机没有 Google 语音
   - 兜底 A：云端 STT（Whisper/自建）按量计费
   - 兜底 B：接 OEM/讯飞（更稳）
3. **服务端智能升档**：不需要每次都上 Claude
   - Auto：默认 DeepSeek；长文/复杂再升 Kimi/Claude（服务端做，端侧不发版）
4. **防滥用**：预装 3 万一定会被抓包薅
   - token 绑定设备指纹（Android ID/硬件 hash）+ 异常检测
   - 并发/速率限制、地域/ASN 风控、封禁/轮换
5. **可观测性**：问题定位速度决定客服成本
   - 端到端 trace id
   - token 维度失败率/延迟/成本报表

---

## 9) 当前已落地（代码状态）

Android 端（fork BotDrop）已做出 Route A 主链路：
- 自动预装 flow（bootstrap → install → provision token → start gateway → 打开 chat）
- 内置聊天 UI（文字 + 语音输入 + TTS）
- token 读取（系统属性优先，私有文件兜底）
- Proxy base URL 默认写入 `https://api.openclaw.ai/.../v1`（可由出厂系统属性覆盖）

PR：
- `howardleegeek/botdrop-android` PR #1
