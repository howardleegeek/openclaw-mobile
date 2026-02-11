# HEARTBEAT — Oyster Labs 24/7 自动任务

每 30 分钟轮询一次，按顺序检查：

## 1. 系统健康
- curl localhost:8080/health (OpenClaw Proxy)
- curl localhost:8787 (Oysterworld Relay)
- curl localhost:3456 (Dashboard)
- 有任何服务挂了 → 立即通知 Howard

## 2. 蜂群派发
- 检查 researcher agent 是否有新发现需要汇报
- 检查 content agent 今天是否产出了推文草稿
- 检查 bd agent 是否有新联系人跟进
- 检查 monitor agent 是否报告异常

## 3. 内容日历
- 4 个 Twitter 账号今天是否都有安排发帖？
- 如果没有 → 让 content agent 补充

## 4. 记忆维护
- 如果距上次整理 >3 天 → 扫描 memory/*.md，更新 MEMORY.md

## 5. 项目进度
- 检查 x402 hackathon 准备情况 (Feb 11-14)
- 检查 Solana AI hackathon 进度 (Feb 12)

如果一切正常，回复 HEARTBEAT_OK
