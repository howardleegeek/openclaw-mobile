# OpenClaw Proxy (OpenAI-compatible LLM Router)

Purpose: a single **OpenAI-compatible** endpoint for OpenClaw Mobile (Nana) that routes by `device_token` tier:
- Free → DeepSeek
- Pro → Kimi (Moonshot)
- Max → Claude (Anthropic)

## Endpoints
- `GET /health`
- `POST /v1/chat/completions` (OpenAI Chat Completions; streaming supported as 1-chunk SSE)
- Optional tier-forcing aliases:
  - `POST /deepseek/v1/chat/completions`
  - `POST /kimi/v1/chat/completions`
  - `POST /claude/v1/chat/completions`

## Auth
Send the factory-provisioned token:

```bash
Authorization: Bearer <device_token>
```

## Token DB (MVP)
SQLite at `TOKEN_DB_PATH` (default: `./data/tokens.sqlite3`).

If a token is not found in DB, it defaults to tier `free`.

## Env Vars
Required (depending on which tiers you enable):
- `DEEPSEEK_API_KEY`
- `KIMI_API_KEY` (Moonshot)
- `ANTHROPIC_API_KEY`

Optional:
- `DEEPSEEK_BASE_URL` (default: `https://api.deepseek.com/v1`)
- `DEEPSEEK_MODEL` (default: `deepseek-chat`)
- `KIMI_BASE_URL` (default: `https://api.moonshot.cn/v1`)
- `KIMI_MODEL` (default: `moonshot-v1-32k`)
- `CLAUDE_MODEL` (default: `claude-3-5-sonnet-latest`)
- `TOKEN_DB_PATH` (default: `./data/tokens.sqlite3`)
- `ADMIN_KEY` (enables admin endpoints)
- `MOCK_MODE=1` (dev-only: no upstream calls; returns deterministic mock replies)
- `APPLE_CLIENT_ID` (Sign in with Apple audience, usually iOS bundle id)
- `APPLE_CLIENT_IDS` (comma-separated audiences, overrides/supplements `APPLE_CLIENT_ID`)

## Run (dev)

```bash
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
# Auth endpoints (/v1/auth/*) use bcrypt for password hashing (included in requirements.txt).

export DEEPSEEK_API_KEY=...
export KIMI_API_KEY=...
export ANTHROPIC_API_KEY=...

python3 server.py
```

## Quick Test

```bash
curl -sS http://127.0.0.1:8080/health

curl -sS http://127.0.0.1:8080/v1/chat/completions \\
  -H 'Content-Type: application/json' \\
  -H 'Authorization: Bearer ocw1_example' \\
  -d '{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"你好\"}]}' | jq .
```

## Offline Smoke Test (No Upstream Keys)

```bash
MOCK_MODE=1 ADMIN_KEY=devadmin LISTEN_PORT=8080 ./run_dev.sh
./smoke_test.sh http://127.0.0.1:8080
```
