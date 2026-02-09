# “傻瓜都能用”的计划（基于 BotDrop + NanaBot/OpenClaw）

## 目标（用户视角）
把“装环境/写配置/跑命令”全部隐藏掉，用户只做 3 件事：
1. 安装 APK → 打开
2. 登录/粘贴一个 AI Key（或直接 OAuth 登录）
3. 选 Telegram/Discord → 点“连接” → 开始聊天

成功标准（MVP）：
- 从全新手机到可用 ≤ 3 分钟
- 全程 0 终端、0 手改 JSON
- 失败可重试，有明确报错和日志一键导出

---

## 用户旅程（推荐默认路径）
1. **首次启动**
   - 显示 2 个必须权限的解释页：通知权限 + 后台保活（电池优化白名单）
   - 一键跳系统设置页（不同厂商适配提示文案）

2. **自动安装（无感）**
   - App 内置 Termux bootstrap，自动解压到 `$PREFIX`
   - 运行统一 installer 脚本（结构化输出）显示进度条

3. **AI Provider / Auth**
   - 推荐第一屏只放 2 个：OpenAI（OAuth）/ Anthropic（粘贴 token）
   - “更多 provider”折叠
   - 保存到 OpenClaw auth profiles（文件权限 owner-only）

4. **连接聊天渠道**
   - 推荐 Telegram（最简单）
   - 引导：打开官方 Setup Bot → 自动拿 userId → 生成一次性“绑定码”
   - App 粘贴/扫码导入绑定码 → 自动写 `openclaw.json` channels 配置

5. **开始运行**
   - App 自动启动 OpenClaw Gateway（Foreground Service 保活）
   - NanaBot 默认安装但“远程执行”能力默认关闭（安全）
   - Dashboard 显示：Online/Offline、最近心跳、日志入口

---

## 技术实现（按 BotDrop 思路落地）

### 1) 单一安装脚本（Single Source of Truth）
原则：安装逻辑都在脚本里，App 只负责调用+解析输出。
- 路径建议：`$PREFIX/share/oyster/install.sh`
- 输出协议（GUI 解析）：
  - `OYSTER_STEP:N:START:msg`
  - `OYSTER_STEP:N:DONE`
  - `OYSTER_ERROR:msg`
  - `OYSTER_COMPLETE`

脚本做的事（顺序固定，便于 UI）：
1. 环境修复（chmod、TMPDIR、sshd key、目录创建）
2. Node/npm 校验（并设置 `SSL_CERT_FILE=$PREFIX/etc/tls/cert.pem`）
3. 安装 OpenClaw（版本可 pin，不用 latest）
4. 安装 NanaBot（python venv + 写入 nanabot.py + 写 manager）
5. 写默认 config（不覆盖用户已有配置）

### 2) Foreground Service 统一守护
不要用 “nohup + pidfile” 当主要保活方案（Android 会杀）。
- OpenClaw：继续用 `termux-chroot openclaw gateway run`（BotDrop 已验证）
- NanaBot：由同一个 Foreground Service 拉起守护（独立进程即可）
- Watchdog：崩溃重启 + backoff

### 3) Setup Bot（把最难的配置做成傻瓜式）
Telegram/Discord 的“创建 bot / token / userId”对新手极其痛。
最简单路径：官方 Setup Bot + 一次性绑定码。
- 用户只需要“点开 TG → 点 start → 复制一段码/扫二维码”
- App 导入后：
  - 写 `openclaw.json`（channels + gateway token）
  - 必要时重启 gateway

### 4) 诊断与支持（必须）
App 一键“导出诊断包”：
- 版本信息（bootstrap/OpenClaw/NanaBot）
- 关键配置（token 需脱敏）
- 最近 N 行日志
- 网络自检（health/probe）
输出 zip 到 downloads，便于用户发给客服。

---

## 安全与默认值（让新手不踩坑）
1. **默认 bind=loopback**，不暴露到 LAN；需要外网接入再显式开启 tailscale/funnel。
2. NanaBot 的 `execute` 默认关闭（需要用户在 App 里开关确认）。
3. 所有 token 文件权限：owner-only；UI 展示必须脱敏。
4. 版本策略：所有关键组件支持 pin + rollback（不要依赖 latest）。
5. SSL：统一注入 `SSL_CERT_FILE`，避免 Node fetch 全挂（BotDrop 已踩过坑）。

---

## 里程碑（建议）

### Phase 0（1 天）
- 完成可复跑 installer + structured output（已做）
- 写好目录约定 + manager 命令

### Phase 1（2-4 天，MVP APK）
- Fork `botdrop-android` 改品牌/包名
- 新增 `$PREFIX/share/oyster/install.sh`（OpenClaw + NanaBot）
- Dashboard 增加 NanaBot 状态/日志/启动停止

### Phase 2（3-7 天，傻瓜化关键）
- 上线 Setup Bot（Telegram 优先）
- App 支持“绑定码导入” + 自动写配置 + 自动重启 gateway
- 一键导出诊断包

### Phase 3（1-2 周，稳定性）
- 厂商电池优化适配文案（小米/OPPO/vivo/华为）
- 断网/重连/升级/回滚全链路测试
- GitHub Releases + 自动更新提示（可选）

