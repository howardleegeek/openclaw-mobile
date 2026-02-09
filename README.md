# OpenClaw Mobile (Nana) Workspace

这份 workspace 是我们把 **BotDrop 开源项目** + **OpenClaw/NanaBot** + **三档订阅代理（DeepSeek/Kimi/Claude）** 整合成“老大妈可用”预装方案的落地资料库。

## 一句话入口
Route A（系统级预装 App）主链路已经在 Android fork 里实现：  
[howardleegeek/botdrop-android#1](https://github.com/howardleegeek/botdrop-android/pull/1)

## 核心文档
- 预装方案（面向 3 万台出厂流程）：`/Users/howardli/.openclaw/workspace/PREINSTALL_MOMS.md`
- 详细计划（域名/限额/订阅/风控/路线图）：`/Users/howardli/.openclaw/workspace/OPENCLAW_MOBILE_DETAILED_PLAN.md`
- BotDrop 对比与复用点：`/Users/howardli/.openclaw/workspace/BOTDROP_COMPARISON.md`

## 工具脚本
- Factory ADB provisioning：`/Users/howardli/.openclaw/workspace/factory_provision_adb.sh`
- Termux 兜底安装脚本（NanaBot）：`/Users/howardli/.openclaw/workspace/install-nanabot.sh`

## Backend（OpenAI-compatible Proxy）
代码在本机：`/Users/howardli/Downloads/openclaw-proxy/`
- 入口：`POST /v1/chat/completions`
- Bearer token：出厂写入的 `device_token`
- 三档路由：free→DeepSeek / pro→Kimi / max→Claude

本地验证（无上游 key 也能跑通链路）：
```bash
cd /Users/howardli/Downloads/openclaw-proxy
MOCK_MODE=1 ADMIN_KEY=devadmin LISTEN_PORT=8080 ./run_dev.sh
./smoke_test.sh http://127.0.0.1:8080
```

