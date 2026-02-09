# BotDrop vs NanaBot Installer — 对比与可复用点

本对比只针对“手机预装/一键装”的核心链路：**bootstrap → install → run → keep-alive → update/rollback**。

---

## 1) BotDrop（开源项目）的关键做法

来源：`/Users/howardli/Downloads/botdrop-android`

- **Termux bootstrap 内置到 APK**：首次启动解压到 `$PREFIX`，不依赖用户手工装包。
- **Installer 脚本是 Single Source of Truth**：
  - 由 `TermuxInstaller.createBotDropScripts()` 写入 `$PREFIX/share/botdrop/install.sh`
  - GUI 和终端都调用同一个脚本
  - 脚本输出结构化行：`BOTDROP_STEP / BOTDROP_ERROR / BOTDROP_COMPLETE`
- **OpenClaw 安装**：`npm install -g openclaw@latest --ignore-scripts --force`
- **Android 兼容性**：运行 OpenClaw 使用 `termux-chroot openclaw ...`（绕过 kernel 网络接口限制）
- **保活**：Android Foreground Service + 通知栏 + Monitor Service 自动拉起
- **事故教训（已文档化）**：slim bootstrap 误删 CA 配置，导致 Node `fetch` 全挂；结论是要设置 `SSL_CERT_FILE` 或避免“latest + slimming”的不确定性。

---

## 2) 你当前的 nanabot installer（脚本）的关键做法

你当前脚本（机器上存在多个同 hash 版本）：
- `/Users/howardli/Downloads/nanabot/install-nanabot.sh`
- `/Users/howardli/Downloads/oyster-automation/packages/nanabot/install-nanabot.sh`

优点：
- **Termux 检测** + 自动 `pkg install` 依赖
- **Python venv 隔离**
- **内嵌主程序**（离线可用，不依赖 git clone）
- **start/stop/status**（基本可用）
- **Termux boot 脚本**（需要 Termux:Boot 才能开机自启）

痛点（可改进）：
- 缺少 BotDrop 那种**结构化安装输出**（无法直接映射到 GUI 进度）
- start/stop 之前用 `pgrep -f` 容易误杀/误判（建议 pidfile）
- 依赖/主程序/README 存在版本分裂（`psutil` vs `/proc` 采集，依赖列表不一致）
- 没有“版本锁定/回滚”机制

---

## 3) 复用 BotDrop 的建议清单（落地优先级）

### P0：立刻复用
- **Structured output**：让脚本输出 `*_STEP:*` / `*_ERROR:*` / `*_COMPLETE`
- **Installer log**：把 stdout/stderr tee 到一个固定 log 文件，出问题好定位
- **Idempotent marker**：重复运行不会破坏用户配置

### P1：一周内复用
- **Foreground Service 守护**：Android 侧拉起/守护 nanabot（不要只靠 nohup）
- **统一环境变量**：尤其 `SSL_CERT_FILE=$PREFIX/etc/tls/cert.pem`（Termux 网络请求的基础）
- **安装脚本下沉到 `$PREFIX/share/<app>/install.sh`**：
  - App UI 直接调用（和 BotDrop 同款）
  - 不再依赖用户复制脚本到奇怪路径

### P2：中期
- **版本锁定/回滚**（避免 latest）
- **配置 UI 化**（token/开关不靠手改 JSON）
- **安全开关**：远程 `execute` 默认关闭，UI 明示开启

---

## 4) 我已经做的整合（本机）

已把 nanabot installer 按 BotDrop 风格重写为可 GUI 解析、可复跑的版本：
- `/Users/howardli/.openclaw/workspace/install-nanabot.sh`

特点：
- `NANABOT_STEP / NANABOT_ERROR / NANABOT_COMPLETE`
- pidfile 管理 + `nanabot start/stop/status/logs/config/uninstall`
- venv 只装 `websockets`（最小依赖）
- config 支持 env 注入（适合“预装脚本”非交互运行）

