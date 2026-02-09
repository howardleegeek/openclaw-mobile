# Oyster Mobile Agent (BotDrop 复刻 + NanaBot 整合) — 架构草案

目标：基于 `botdrop-android` 的 Termux bootstrap + GUI 安装思路，做一款“手机预装的一键 Agent App”，同时管理：
- **OpenClaw**（本地 AI agent + Telegram/Discord 等 channel）
- **NanaBot/nanabot**（边缘设备 telemetry + 远程指令，WS 连 Oyster Gateway）

本文件是“能落地”的架构：每个模块对应到 BotDrop repo 的现有实现点，并给出我们要改/加的东西。

---

## 1) 系统分层

### A. Android App 层（UI + 前台保活）
- **Setup Wizard**（4 步）：
  1. Auth（Provider / Token）
  2. Agent（选择要装 OpenClaw / NanaBot / Both）
  3. Install（后台安装，进度条，失败可重试）
  4. Channel（Telegram/Discord 连接 + 生成配置）
- **Dashboard**：
  - OpenClaw Gateway 状态（running/stopped/uptime）
  - NanaBot 状态（running/stopped/last_seen/log tail）
  - Buttons：Start / Stop / Restart / Logs
- **Foreground Service**（核心）：
  - 持续运行 OpenClaw gateway（和/或 NanaBot）
  - Watchdog：崩溃自动拉起
  - 通知栏常驻（Android 体系下最稳的保活方式）

对应 BotDrop 现有点：
- `app/src/main/java/app/botdrop/SetupActivity.java`（向导）
- `app/src/main/java/app/botdrop/InstallFragment.java`（安装进度 UI）
- `app/src/main/java/app/botdrop/DashboardActivity.java`（运行状态 + 控制）
- `app/src/main/java/app/botdrop/BotDropService.java`（后台命令执行 + gateway 生命周期）

### B. Termux Runtime 层（Linux 环境）
- 由 Termux bootstrap 提供：
  - Node/npm（OpenClaw）
  - bash/sshd 等基础工具
  - Python（给 NanaBot 用）
- **关键点**：Android kernel 对 Node 的网络接口有坑，BotDrop 用 `termux-chroot`（proot）运行 OpenClaw 规避。

对应 BotDrop 现有点：
- `app/src/main/java/com/termux/app/TermuxInstaller.java`（bootstrap 解压 + 写入脚本）
- `app/src/main/java/com/termux/app/TermuxInstaller.java` 的 `createBotDropScripts()`（写 `$PREFIX/share/.../install.sh`）

### C. 安装脚本层（Single Source of Truth）
原则：**安装逻辑放在脚本里**，App 只负责：
- 调用脚本
- 解析结构化输出（step start/done/error）
- 结果展示和重试

BotDrop 当前脚本：`$PREFIX/share/botdrop/install.sh`

我们要做的：扩展为 `$PREFIX/share/oyster/install.sh`（或拆成两个脚本），支持安装：
- OpenClaw（npm global install）
- NanaBot（python venv + nanabot.py + manager）

**结构化输出标准**（建议沿用 BotDrop 风格）：
- `OYSTER_STEP:N:START:msg`
- `OYSTER_STEP:N:DONE`
- `OYSTER_ERROR:msg`
- `OYSTER_COMPLETE`

---

## 2) 关键配置与目录（Android/Termux 侧）

### OpenClaw
- config：`$HOME/.openclaw/openclaw.json`
- auth profiles：`$HOME/.openclaw/agents/main/agent/auth-profiles.json`
- gateway：
  - 默认 loopback + token auth
  - App 负责 start/stop/restart + log tail

### NanaBot
- install dir：`$HOME/nanabot`
- config：`$HOME/nanabot/config/nanabot.json`
- logs：`$HOME/nanabot/logs/nanabot.log`
- pid：`$HOME/nanabot/run/nanabot.pid`

建议：App 层额外维护一个“聚合状态文件”：
- `$HOME/.oyster/state.json`
  - openclaw: { installed, version, gatewayRunning }
  - nanabot: { installed, running, lastHeartbeatAt }

这样 Dashboard 刷新不需要每次都 exec 多个命令。

---

## 3) “做得更好”的点（从 BotDrop 学到的 + 我们补齐）

### A. 安装可观测性（必须）
BotDrop 做得好的：
- installer 写 log 文件：`$HOME/botdrop-install.log`
- structured output：GUI 可精准显示哪一步挂了

我们要补齐：
- nanabot 安装同样写 log（`$HOME/nanabot-install.log`）
- nanabot 运行日志分 stdout 和业务日志（`nanabot.out` / `nanabot.log`）
- App UI：失败时展示“最近 20 行 tail”

### B. 版本锁定与回滚（必须）
BotDrop 遇到 slim bootstrap SSL 崩溃的经验说明：
- **不能依赖 latest**（尤其是 bootstrap）
- 需要“固定版本 + 一键回滚”

我们要做：
- installer 里对 OpenClaw / NanaBot 都支持固定版本（env 或 config）
- App 提供 “Rollback to last known good” 按钮（本质是切换版本 + 重启）

### C. SSL / CA 证书（Android 上很容易踩坑）
BotDrop 的事故：`SSL_CERT_FILE` 没设置导致 Node fetch 全挂。

我们要做：
- installer 或 service 启动时统一设置：
  - `SSL_CERT_FILE=$PREFIX/etc/tls/cert.pem`（Termux）
  - 以及必要时的 `NODE_EXTRA_CA_CERTS`（可选）

### D. 运行时保活（Android 的正确姿势）
仅靠 `nohup` + pidfile 在 Android 上不够稳（会被系统杀）。

我们要做：
- OpenClaw gateway：**Foreground Service** + notification（BotDrop 已有）
- NanaBot：同样由 Foreground Service 拉起/守护（而不是仅 Termux:Boot）
- Termux:Boot 只当“兜底自启”，不是主方案

### E. 安全边界（别把远程执行默认全开）
你现在的 nanabot 支持 `execute` 远程跑 shell，这很强但也很危险。

我们要做（最小可行）：
- config 里 `capabilities.execute` 默认 false
- UI 里显式开关（用户手动开启）
- token 存储：写文件权限 owner-only（Android/Termux 下也要尽量限制）

---

## 4) 落地路线（建议 2 周内能出 MVP）

### Phase 0（今天就能做）
- 统一脚本：把 nanabot installer 做成 structured output + idempotent（已落到 `~/.openclaw/workspace/install-nanabot.sh`）
- 写清楚目录/配置/启动命令

### Phase 1（1-2 天）
- Fork `botdrop-android` → 改包名/品牌/文案
- 在 `TermuxInstaller.createBotDropScripts()` 里新增我们的 `install.sh`
- 在 `BotDropService` 增加 `installNanabot()` + `startNanabot()/stopNanabot()`（通过脚本/manager）

### Phase 2（3-5 天）
- Dashboard 同时显示 OpenClaw + NanaBot 状态
- 增加“日志查看 + 错误 tail”
- 增加“版本锁定/回滚”入口

### Phase 3（后续）
- 服务化：NanaBot 用 Foreground Service 守护
- Auth/Channel 的 UI 深度集成（减少手工编辑 JSON）

---

## 5) 关键接口（App ↔ Termux）

App 调用 Termux 的方式（BotDrop 已验证可用）：
- 通过 `ProcessBuilder` 执行 `$PREFIX/bin/bash <script>`
- 环境变量统一注入：
  - `HOME`, `PREFIX`, `PATH`, `TMPDIR`
  - `SSL_CERT_FILE`（Termux 必要）

建议约定：
- App 安装走 `$PREFIX/share/oyster/install.sh`
- App 管理走 `$HOME/nanabot/bin/nanabot <cmd>`

