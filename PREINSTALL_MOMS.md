# 3 万台手机“直接预装”方案（目标用户：老大妈）

一句话：不要让用户接触“API key / Telegram Bot / 配置文件 / 终端”。预装时把运行环境和默认配置都准备好，用户只需要“点一下说话”。

---

## 0) 产品原则（专为非技术用户）

1. **默认能用**：开机第一次点开 App 就能说话，不要求注册、不要求复制粘贴 Key。
2. **只有一个主入口**：桌面只有一个大按钮“语音助手”，所有高级设置藏在“更多”里。
3. **失败能自救**：断网/后台被杀/权限没开，都给“按这个按钮修复”的单步动作，不给长篇解释。
4. **客服可诊断**：一键生成“支持码 + 日志包”，客服拿到就能定位（用户只会发给你一张截图）。

---

## 1) 预装的两条路线（你要选其一作为主线）

### 路线 A（推荐）：BotDrop Fork 作为“系统级预装 App”
适用：你能控制固件/出厂流程，允许把 App 作为系统 app 或能跑出厂脚本。

特点：
- 不出现 Termux UI
- OpenClaw + NanaBot 全由 App 后台管理
- Foreground Service 保活，稳定性最高

### 路线 B（兜底）：预装 Termux + Termux:Boot + 脚本
适用：无法改系统，只能预装普通 app。

特点：
- 依赖 Termux 生态，厂商电池策略影响更大
- 可快速上线，但体验不够“傻瓜”

结论：**路线 A 做主线**，路线 B 当兼容/紧急兜底。

---

## 2) “老大妈 3 步走”开箱体验（OOBE）

### Step 1：打开 App（自动修复权限）
- 页面 1：一句话解释“需要后台运行”，一个大按钮“去开启”
- 页面 2：一句话解释“需要通知提醒”，一个大按钮“去开启”
- 页面 3：一句话解释“省电模式会杀后台”，一个大按钮“加入白名单”

注意：不同品牌（小米/OPPO/vivo/华为）要做分支提示，但 UI 仍保持“一个按钮”。

### Step 2：激活（不让用户碰 Key）
不要让用户输入 API key。两种可行方式：

1) **出厂预置 token（最傻瓜）**
- 每台手机在出厂时写入一个 `device_token`
- App 首次启动自动用 `device_token` 换取云端短期会话（可限额）

2) **扫码激活（次优）**
- 盒子里一张卡片二维码（或手机背面贴纸）
- 用户只需要“扫一扫”

### Step 2.5：订阅与模式（DeepSeek / Kimi / Claude）
目标：**用户永远不接触任何 key**，所有计费/限额/切模型都在云端做。

- **设备 token = 订阅身份**：每台机器一个 `device_token`，请求时带上即可，服务端按 token 做：
  - 免费额度（每日/每月上限）
  - 付费订阅（Pro）
  - 机型/SKU 特供（比如 Kimi 模式）
- **模式切换不要做成“让用户选模型”**（老大妈会被吓到）：只给“普通 / 更聪明 / 更省钱”这种按钮，甚至默认 Auto。
- **实现建议（最省事）**：OpenClaw 端统一走 OpenAI-compatible proxy：
  - App 写入 `OPENAI_BASE_URL` 指向你的 proxy（例如 `https://api.openclaw.ai/v1`），不直连任何模型厂商
  - App 写入 `OPENAI_API_KEY=<device_token>`（token 不是 OpenAI key，只要你的 proxy 认得即可）
  - Proxy 内部按 `device_token` 的订阅档位路由（并强制限额）：
    - **Free → DeepSeek**（推荐 `deepseek-chat`）：省成本，适合大多数问答
      - 建议限额：上下文 8K，输出 512 tokens，20-50 轮/天（按你成本微调）
    - **Pro → Kimi**（建议 32K 上下文档）：长文理解/总结更稳
      - 建议限额：上下文 32K，输出 1024 tokens，200 轮/天
    - **Max → Claude**（推荐 Sonnet 档）：复杂对话/更强稳定性
      - 建议限额：上下文 64K，输出 2048 tokens，200 轮/天

备注：如需 OEM/QA 强制某个模式，可让 App 把 `OPENAI_BASE_URL` 写成
`https://api.openclaw.ai/deepseek/v1` / `.../kimi/v1` / `.../claude/v1`（服务端仍需校验 token 档位，避免被改配置越权）。

### Step 3：开始说话（内置聊天 UI）
老大妈不一定有 Telegram/Discord。
- App 内置 chat UI（文字 + 语音）
- 默认语音输入（按钮按住说话）+ 语音播报（TTS）
- Telegram/Discord 作为“家人远程陪护”选项，而不是必须项

---

## 3) 预装时要做什么（工程落地）

### A. 出厂阶段（Factory Provisioning）
目标：把所有“耗时/依赖网络”的安装提前完成。

1. 预装 APK（系统镜像或出厂 ADB）
2. 首次运行 App（自动完成 Termux bootstrap 解压）
3. 执行统一 installer（OpenClaw + NanaBot）
4. 验证：
- OpenClaw gateway health OK
- NanaBot 能起进程并写入日志
5. 写入设备身份（device_id / device_token），并锁定权限

### B. App 内安装器（Single Source of Truth）
原则沿用 BotDrop：
- 脚本在 `$PREFIX/share/oyster/install.sh`
- 输出 `OYSTER_STEP / OYSTER_ERROR / OYSTER_COMPLETE`
- App 只解析输出并展示进度

必须包含：
- `SSL_CERT_FILE=$PREFIX/etc/tls/cert.pem`（防止 Node fetch 崩）
- 版本 pin + rollback（不要 latest）

### C. 运行守护（Foreground Service）
App 开机自启（BootReceiver）+ 前台服务：
- OpenClaw gateway：`termux-chroot openclaw gateway run`
- NanaBot：`$HOME/nanabot/venv/bin/python $HOME/nanabot/bin/nanabot.py`
- 崩溃 backoff 重启（5s, 10s, 20s, ... 到 5min）

---

## 4) 让“不会用的人”也不出事：默认值

1. **默认不暴露局域网**：gateway bind=loopback；远程用 tailscale/隧道由你控制开关。
2. **默认禁用危险能力**：NanaBot 的远程 `execute` 默认 false，必须在 App “高级设置”手动打开。
3. **一键修复按钮**（必须有）：
- 重新安装运行环境
- 重启服务
- 导出诊断包
4. **一键客服码**：
- “复制支持码”按钮，用户只需发给客服即可

---

## 5) 更新策略（3 万台规模必须考虑）

1. APK 更新：自建更新源或系统 OTA；不要依赖用户去商店点更新。
2. Runtime 更新：
- OpenClaw：固定版本，灰度升级（1% -> 10% -> 50% -> 100%）
- NanaBot：同理（脚本支持 pin + rollback）
3. 事故回滚：配置里记录 last-known-good，App 一键回滚并重启服务。

---

## 6) 下一步要做的工程任务（最短路径）

1. Fork `botdrop-android` 改包名/品牌，并把 installer 从 `botdrop` 扩展为 `oyster`（装 OpenClaw + NanaBot）
2. App 增加“内置聊天 UI”（直接连本地 gateway，不需要 Telegram）
3. 做 Factory Provisioning 脚本/流程（跑一遍安装 + health check）
4. 加“诊断包导出 + 支持码”
