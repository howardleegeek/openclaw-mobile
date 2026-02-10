import asyncio
import base64
import json
import os
import secrets
import time
import uuid
from contextvars import ContextVar
from dataclasses import dataclass
from typing import Any, AsyncIterator, Dict, List, Optional, Tuple

import aiosqlite
import httpx
from dotenv import load_dotenv
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse, StreamingResponse


load_dotenv()


# -----------------------------
# Config
# -----------------------------

TOKEN_DB_PATH = os.getenv("TOKEN_DB_PATH", "./data/tokens.sqlite3")
ADMIN_KEY = os.getenv("ADMIN_KEY")  # optional

DEEPSEEK_API_KEY = os.getenv("DEEPSEEK_API_KEY", "")
DEEPSEEK_BASE_URL = os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com/v1").rstrip("/")
DEEPSEEK_MODEL = os.getenv("DEEPSEEK_MODEL", "deepseek-chat")

KIMI_API_KEY = os.getenv("KIMI_API_KEY", "")
KIMI_BASE_URL = os.getenv("KIMI_BASE_URL", "https://api.moonshot.cn/v1").rstrip("/")
KIMI_MODEL = os.getenv("KIMI_MODEL", "moonshot-v1-32k")

ANTHROPIC_API_KEY = os.getenv("ANTHROPIC_API_KEY", "")
CLAUDE_MODEL = os.getenv("CLAUDE_MODEL", "claude-3-5-sonnet-latest")

LISTEN_HOST = os.getenv("LISTEN_HOST", "127.0.0.1")
LISTEN_PORT = int(os.getenv("LISTEN_PORT", "8080"))
MOCK_MODE = os.getenv("MOCK_MODE", "").strip() in ("1", "true", "yes", "on")


TIER_LEVEL = {"free": 0, "pro": 1, "max": 2}
LEVEL_TIER = {0: "free", 1: "pro", 2: "max"}


@dataclass(frozen=True)
class TierLimits:
    max_context_tokens: int
    max_output_tokens: int
    daily_tokens: int


LIMITS: Dict[str, TierLimits] = {
    "free": TierLimits(max_context_tokens=8_000, max_output_tokens=512, daily_tokens=60_000),
    "pro": TierLimits(max_context_tokens=32_000, max_output_tokens=1024, daily_tokens=600_000),
    "max": TierLimits(max_context_tokens=64_000, max_output_tokens=2048, daily_tokens=1_200_000),
}

_CALL_LLM_BODY: ContextVar[Optional[Dict[str, Any]]] = ContextVar("_CALL_LLM_BODY", default=None)


def _approx_tokens(text: str) -> int:
    # Cheap approximation: ~4 chars per token for mixed CJK/ASCII.
    if not text:
        return 0
    return max(1, (len(text) + 3) // 4)


def _messages_approx_tokens(messages: List[Dict[str, Any]]) -> int:
    total = 0
    for m in messages:
        c = m.get("content", "")
        if isinstance(c, str):
            total += _approx_tokens(c)
        elif isinstance(c, list):
            # multimodal: count text parts only
            for p in c:
                if isinstance(p, dict) and p.get("type") == "text":
                    total += _approx_tokens(p.get("text", ""))
    return total


def _ensure_dir(path: str) -> None:
    d = os.path.dirname(os.path.abspath(path))
    if d:
        os.makedirs(d, exist_ok=True)


async def _init_db() -> None:
    _ensure_dir(TOKEN_DB_PATH)
    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        await db.execute(
            """
            CREATE TABLE IF NOT EXISTS device_tokens (
              token TEXT PRIMARY KEY,
              tier TEXT NOT NULL CHECK (tier IN ('free','pro','max')),
              status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','disabled')),
              note TEXT,
              created_at INTEGER NOT NULL
            )
            """
        )
        await db.execute(
            """
            CREATE TABLE IF NOT EXISTS usage_daily (
              token TEXT NOT NULL,
              day TEXT NOT NULL,
              prompt_tokens INTEGER NOT NULL DEFAULT 0,
              completion_tokens INTEGER NOT NULL DEFAULT 0,
              requests INTEGER NOT NULL DEFAULT 0,
              PRIMARY KEY (token, day)
            )
            """
        )
        await db.execute(
            """
            CREATE TABLE IF NOT EXISTS conversations (
              id TEXT PRIMARY KEY,
              device_token TEXT NOT NULL,
              title TEXT,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL
            )
            """
        )
        await db.execute(
            """
            CREATE TABLE IF NOT EXISTS messages (
              id TEXT PRIMARY KEY,
              conversation_id TEXT NOT NULL,
              role TEXT NOT NULL CHECK (role IN ('user','assistant','system')),
              content TEXT NOT NULL,
              created_at INTEGER NOT NULL
            )
            """
        )
        await db.execute("CREATE INDEX IF NOT EXISTS idx_conversations_token_updated ON conversations(device_token, updated_at)")
        await db.execute("CREATE INDEX IF NOT EXISTS idx_messages_conv_created ON messages(conversation_id, created_at)")
        await db.commit()


async def _get_token_row(token: str) -> Optional[Dict[str, Any]]:
    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        async with db.execute(
            "SELECT token,tier,status,note,created_at FROM device_tokens WHERE token=?",
            (token,),
        ) as cur:
            row = await cur.fetchone()
            return dict(row) if row else None


async def _get_tier_for_token(token: str) -> str:
    row = await _get_token_row(token)
    if not row:
        return "free"
    if row.get("status") != "active":
        raise HTTPException(status_code=403, detail="token disabled")
    return row.get("tier") or "free"


def _today_utc() -> str:
    return time.strftime("%Y-%m-%d", time.gmtime())


async def _get_daily_usage(token: str) -> Tuple[int, int, int]:
    day = _today_utc()
    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        async with db.execute(
            "SELECT prompt_tokens,completion_tokens,requests FROM usage_daily WHERE token=? AND day=?",
            (token, day),
        ) as cur:
            row = await cur.fetchone()
            if not row:
                return (0, 0, 0)
            return (row["prompt_tokens"], row["completion_tokens"], row["requests"])


async def _bump_daily_usage(token: str, prompt_tokens: int, completion_tokens: int) -> None:
    day = _today_utc()
    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        await db.execute(
            """
            INSERT INTO usage_daily(token, day, prompt_tokens, completion_tokens, requests)
            VALUES (?, ?, ?, ?, 1)
            ON CONFLICT(token, day) DO UPDATE SET
              prompt_tokens = prompt_tokens + excluded.prompt_tokens,
              completion_tokens = completion_tokens + excluded.completion_tokens,
              requests = requests + 1
            """,
            (token, day, int(prompt_tokens), int(completion_tokens)),
        )
        await db.commit()


def _truncate_messages_to_fit(messages: List[Dict[str, Any]], max_context_tokens: int) -> List[Dict[str, Any]]:
    # Keep all system messages; drop oldest non-system messages until under limit.
    system_msgs = [m for m in messages if m.get("role") == "system"]
    non_system = [m for m in messages if m.get("role") != "system"]

    kept = list(non_system)
    while kept and _messages_approx_tokens(system_msgs + kept) > max_context_tokens:
        kept.pop(0)
    return system_msgs + kept


def _parse_bearer(auth_header: Optional[str]) -> Optional[str]:
    if not auth_header:
        return None
    v = auth_header.strip()
    if not v.lower().startswith("bearer "):
        return None
    token = v[7:].strip()
    return token or None


def _require_upstream_key(provider: str) -> None:
    if provider == "deepseek" and not DEEPSEEK_API_KEY:
        raise HTTPException(status_code=500, detail="missing DEEPSEEK_API_KEY")
    if provider == "kimi" and not KIMI_API_KEY:
        raise HTTPException(status_code=500, detail="missing KIMI_API_KEY")
    if provider == "claude" and not ANTHROPIC_API_KEY:
        raise HTTPException(status_code=500, detail="missing ANTHROPIC_API_KEY")


async def _call_openai_compatible(
    *,
    base_url: str,
    api_key: str,
    body: Dict[str, Any],
) -> Dict[str, Any]:
    url = f"{base_url}/chat/completions"
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }
    async with httpx.AsyncClient(timeout=60) as client:
        resp = await client.post(url, headers=headers, json=body)
        if resp.status_code >= 400:
            raise HTTPException(status_code=resp.status_code, detail=resp.text)
        return resp.json()


async def _call_anthropic_messages(
    *,
    body: Dict[str, Any],
    max_tokens: int,
) -> Dict[str, Any]:
    url = "https://api.anthropic.com/v1/messages"
    headers = {
        "x-api-key": ANTHROPIC_API_KEY,
        "anthropic-version": "2023-06-01",
        "content-type": "application/json",
    }

    # Convert OpenAI messages -> Anthropic messages (minimal text-only mapping).
    messages = body.get("messages") or []
    if not isinstance(messages, list):
        raise HTTPException(status_code=400, detail="messages must be an array")

    system_parts: List[str] = []
    out_msgs: List[Dict[str, Any]] = []
    for m in messages:
        if not isinstance(m, dict):
            continue
        role = (m.get("role") or "").strip()
        content = m.get("content", "")
        if role == "system":
            if isinstance(content, str) and content.strip():
                system_parts.append(content.strip())
            continue

        # Anthropic roles: user/assistant only. Tool messages become user text (MVP).
        a_role = "assistant" if role == "assistant" else "user"
        if isinstance(content, str):
            a_content = content
        else:
            # Multimodal/tool content: stringify for MVP.
            a_content = json.dumps(content, ensure_ascii=False)

        out_msgs.append({"role": a_role, "content": a_content})

    payload: Dict[str, Any] = {
        "model": CLAUDE_MODEL,
        "max_tokens": max_tokens,
        "messages": out_msgs,
    }
    if system_parts:
        payload["system"] = "\n\n".join(system_parts)

    # Optional knobs
    if isinstance(body.get("temperature"), (int, float)):
        payload["temperature"] = body["temperature"]

    async with httpx.AsyncClient(timeout=60) as client:
        resp = await client.post(url, headers=headers, json=payload)
        if resp.status_code >= 400:
            raise HTTPException(status_code=resp.status_code, detail=resp.text)
        return resp.json()


def _anthropic_to_openai_completion(anth: Dict[str, Any], *, public_model: str) -> Dict[str, Any]:
    # Anthropic response contains content blocks; we join text.
    parts: List[str] = []
    for b in (anth.get("content") or []):
        if isinstance(b, dict) and b.get("type") == "text":
            t = b.get("text", "")
            if isinstance(t, str) and t:
                parts.append(t)
    text = "\n".join(parts).strip()

    prompt_tokens = int((anth.get("usage") or {}).get("input_tokens") or 0)
    completion_tokens = int((anth.get("usage") or {}).get("output_tokens") or 0)
    total_tokens = prompt_tokens + completion_tokens

    created = int(time.time())
    return {
        "id": f"chatcmpl_{secrets.token_hex(12)}",
        "object": "chat.completion",
        "created": created,
        "model": public_model,
        "choices": [
            {
                "index": 0,
                "message": {"role": "assistant", "content": text},
                "finish_reason": "stop",
            }
        ],
        "usage": {
            "prompt_tokens": prompt_tokens,
            "completion_tokens": completion_tokens,
            "total_tokens": total_tokens,
        },
    }


def _openai_sse_one_chunk(payload: Dict[str, Any]) -> AsyncIterator[bytes]:
    async def gen() -> AsyncIterator[bytes]:
        # Convert a normal chat.completion response into a single chunk stream.
        created = int(payload.get("created") or time.time())
        model = payload.get("model", "unknown")
        choice0 = (payload.get("choices") or [{}])[0] or {}
        msg = choice0.get("message") or {}
        content = msg.get("content") or ""

        chunk = {
            "id": payload.get("id") or f"chatcmpl_{secrets.token_hex(12)}",
            "object": "chat.completion.chunk",
            "created": created,
            "model": model,
            "choices": [
                {
                    "index": 0,
                    "delta": {"content": content},
                    "finish_reason": "stop",
                }
            ],
        }
        yield (f"data: {json.dumps(chunk, ensure_ascii=False)}\n\n").encode("utf-8")
        yield b"data: [DONE]\n\n"

    return gen()


app = FastAPI(title="OpenClaw Proxy", version="0.1.0")


@app.on_event("startup")
async def _startup() -> None:
    await _init_db()


@app.get("/health")
async def health() -> Dict[str, Any]:
    return {"ok": True, "ts": int(time.time())}


async def _call_llm(
    *,
    token: str,
    tier: str,
    messages: list,
    forced_provider: str = None,
    wants_stream: bool = False,
) -> Dict[str, Any]:
    # Returns an OpenAI-compatible chat.completion dict. Streaming is handled by the caller.
    limits = LIMITS.get(tier) or LIMITS["free"]

    # Optional provider forcing by URL prefix (deepseek/kimi/claude).
    provider = forced_provider or {
        "free": "deepseek",
        "pro": "kimi",
        "max": "claude",
    }.get(tier, "deepseek")

    # Enforce: forced provider cannot exceed token tier.
    if forced_provider:
        forced_tier = {"deepseek": "free", "kimi": "pro", "claude": "max"}.get(forced_provider)
        if forced_tier is None:
            raise HTTPException(status_code=400, detail="invalid forced provider")
        if TIER_LEVEL[forced_tier] > TIER_LEVEL.get(tier, 0):
            raise HTTPException(status_code=403, detail="tier not allowed")

    if not MOCK_MODE:
        _require_upstream_key(provider)

    # Keep original request knobs when provided (temperature, top_p, etc).
    ctx_body = _CALL_LLM_BODY.get()
    if ctx_body is None:
        body: Dict[str, Any] = {"messages": messages, "model": "oyster-auto"}
    else:
        body = dict(ctx_body)
        body["messages"] = messages

    # Enforce daily usage (approx tokens).
    messages = body.get("messages") or []
    if not isinstance(messages, list):
        raise HTTPException(status_code=400, detail="messages must be an array")

    # Truncate oldest messages if needed.
    messages = _truncate_messages_to_fit(messages, limits.max_context_tokens)
    body["messages"] = messages

    prompt_tokens = _messages_approx_tokens(messages)
    used_prompt, used_completion, _ = await _get_daily_usage(token)
    used_total = used_prompt + used_completion
    if used_total + prompt_tokens > limits.daily_tokens:
        raise HTTPException(status_code=429, detail="daily quota exceeded")

    # Cap output tokens.
    req_max_tokens = body.get("max_tokens")
    if isinstance(req_max_tokens, int) and req_max_tokens > 0:
        body["max_tokens"] = min(req_max_tokens, limits.max_output_tokens)
    else:
        body["max_tokens"] = limits.max_output_tokens

    # Proxy handles streaming itself (1-chunk SSE); upstream always gets stream=false.
    body["stream"] = False

    # Keep the caller-provided model as a "public model" hint, but override upstream model per tier.
    public_model = str(body.get("model") or "oyster-auto")

    if MOCK_MODE:
        created = int(time.time())
        reply = f"[MOCK:{provider}:{tier}] " + (messages[-1].get("content") if messages else "")
        completion_tokens = min(limits.max_output_tokens, _approx_tokens(reply))
        await _bump_daily_usage(token, prompt_tokens, completion_tokens)
        return {
            "id": f"chatcmpl_{secrets.token_hex(12)}",
            "object": "chat.completion",
            "created": created,
            "model": public_model,
            "choices": [{"index": 0, "message": {"role": "assistant", "content": reply}, "finish_reason": "stop"}],
            "usage": {
                "prompt_tokens": prompt_tokens,
                "completion_tokens": completion_tokens,
                "total_tokens": prompt_tokens + completion_tokens,
            },
        }

    if provider == "deepseek":
        upstream_body = dict(body)
        upstream_body["model"] = DEEPSEEK_MODEL
        res = await _call_openai_compatible(base_url=DEEPSEEK_BASE_URL, api_key=DEEPSEEK_API_KEY, body=upstream_body)
        # Rewrite model to keep clients stable (optional).
        res["model"] = public_model
        usage = res.get("usage") or {}
        completion_tokens = int(usage.get("completion_tokens") or 0)
        await _bump_daily_usage(token, prompt_tokens, completion_tokens)
        return res

    if provider == "kimi":
        upstream_body = dict(body)
        upstream_body["model"] = KIMI_MODEL
        res = await _call_openai_compatible(base_url=KIMI_BASE_URL, api_key=KIMI_API_KEY, body=upstream_body)
        res["model"] = public_model
        usage = res.get("usage") or {}
        completion_tokens = int(usage.get("completion_tokens") or 0)
        await _bump_daily_usage(token, prompt_tokens, completion_tokens)
        return res

    if provider == "claude":
        anth = await _call_anthropic_messages(body=body, max_tokens=int(body["max_tokens"]))
        res = _anthropic_to_openai_completion(anth, public_model=public_model)
        usage = res.get("usage") or {}
        completion_tokens = int(usage.get("completion_tokens") or 0)
        await _bump_daily_usage(token, prompt_tokens, completion_tokens)
        return res

    raise HTTPException(status_code=500, detail="unknown provider")


async def _handle_chat_completions(request: Request, forced_provider: Optional[str]) -> Any:
    auth = request.headers.get("authorization")
    token = _parse_bearer(auth)
    if not token:
        raise HTTPException(status_code=401, detail="missing bearer token")

    body = await request.json()
    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="invalid json body")

    # Figure out tier from token DB.
    tier = await _get_tier_for_token(token)
    wants_stream = bool(body.get("stream"))
    ctx = _CALL_LLM_BODY.set(body)
    try:
        res = await _call_llm(
            token=token,
            tier=tier,
            messages=body.get("messages"),
            forced_provider=forced_provider,
            wants_stream=wants_stream,
        )
    finally:
        _CALL_LLM_BODY.reset(ctx)

    if wants_stream:
        return StreamingResponse(_openai_sse_one_chunk(res), media_type="text/event-stream")
    return JSONResponse(res)


@app.post("/v1/chat/completions")
async def chat_completions(request: Request) -> Any:
    return await _handle_chat_completions(request, forced_provider=None)


@app.post("/deepseek/v1/chat/completions")
async def chat_completions_deepseek(request: Request) -> Any:
    return await _handle_chat_completions(request, forced_provider="deepseek")


@app.post("/kimi/v1/chat/completions")
async def chat_completions_kimi(request: Request) -> Any:
    return await _handle_chat_completions(request, forced_provider="kimi")


@app.post("/claude/v1/chat/completions")
async def chat_completions_claude(request: Request) -> Any:
    return await _handle_chat_completions(request, forced_provider="claude")


def _require_device_token(request: Request) -> str:
    auth = request.headers.get("authorization")
    token = _parse_bearer(auth)
    if not token:
        raise HTTPException(status_code=401, detail="missing bearer token")
    return token


def _title_from_user_message(text: str) -> Optional[str]:
    t = " ".join((text or "").strip().split())
    if not t:
        return None
    return t[:50]


@app.post("/v1/conversations")
async def create_conversation(request: Request) -> Any:
    device_token = _require_device_token(request)
    # Ensure disabled tokens can't use conversation endpoints.
    await _get_tier_for_token(device_token)

    body = await request.json()
    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="invalid json body")

    system_prompt = body.get("system_prompt")
    if system_prompt is not None and not isinstance(system_prompt, str):
        raise HTTPException(status_code=400, detail="system_prompt must be a string")
    system_prompt = (system_prompt or "").strip()

    now = int(time.time())
    conversation_id = str(uuid.uuid4())

    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        await db.execute(
            "INSERT INTO conversations(id,device_token,title,created_at,updated_at) VALUES (?,?,?,?,?)",
            (conversation_id, device_token, None, now, now),
        )
        if system_prompt:
            message_id = str(uuid.uuid4())
            await db.execute(
                "INSERT INTO messages(id,conversation_id,role,content,created_at) VALUES (?,?,?,?,?)",
                (message_id, conversation_id, "system", system_prompt, now),
            )
        await db.commit()

    return {"id": conversation_id, "title": None, "created_at": now}


@app.get("/v1/conversations")
async def list_conversations(request: Request, limit: int = 20, offset: int = 0) -> Any:
    device_token = _require_device_token(request)
    await _get_tier_for_token(device_token)

    if limit < 1:
        raise HTTPException(status_code=400, detail="limit must be >= 1")
    if offset < 0:
        raise HTTPException(status_code=400, detail="offset must be >= 0")

    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        async with db.execute(
            """
            SELECT
              c.id,
              c.title,
              c.created_at,
              c.updated_at,
              (
                SELECT COUNT(1)
                FROM messages m
                WHERE m.conversation_id = c.id
              ) AS message_count
            FROM conversations c
            WHERE c.device_token = ?
            ORDER BY c.updated_at DESC
            LIMIT ? OFFSET ?
            """,
            (device_token, int(limit), int(offset)),
        ) as cur:
            rows = await cur.fetchall()

    return {"conversations": [dict(r) for r in rows]}


@app.get("/v1/conversations/{conversation_id}")
async def get_conversation(conversation_id: str, request: Request) -> Any:
    device_token = _require_device_token(request)
    await _get_tier_for_token(device_token)

    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        async with db.execute(
            "SELECT id,title,created_at FROM conversations WHERE id=? AND device_token=?",
            (conversation_id, device_token),
        ) as cur:
            conv = await cur.fetchone()
        if not conv:
            raise HTTPException(status_code=404, detail="conversation not found")

        async with db.execute(
            "SELECT id,role,content,created_at FROM messages WHERE conversation_id=? ORDER BY created_at ASC, rowid ASC",
            (conversation_id,),
        ) as cur:
            msgs = await cur.fetchall()

    return {
        "id": conv["id"],
        "title": conv["title"],
        "created_at": conv["created_at"],
        "messages": [dict(m) for m in msgs],
    }


@app.post("/v1/conversations/{conversation_id}/chat")
async def conversation_chat(conversation_id: str, request: Request) -> Any:
    device_token = _require_device_token(request)
    tier = await _get_tier_for_token(device_token)

    body = await request.json()
    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="invalid json body")

    user_text = body.get("message")
    if not isinstance(user_text, str):
        raise HTTPException(status_code=400, detail="message must be a string")
    user_text = user_text.strip()
    if not user_text:
        raise HTTPException(status_code=400, detail="message must be non-empty")
    if len(user_text) > 50_000:
        raise HTTPException(status_code=400, detail="message too long (max 50000 chars)")

    now = int(time.time())
    user_message_id = str(uuid.uuid4())

    # Step 2/3: verify ownership + store user message.
    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        async with db.execute(
            "SELECT id,title FROM conversations WHERE id=? AND device_token=?",
            (conversation_id, device_token),
        ) as cur:
            conv = await cur.fetchone()
        if not conv:
            raise HTTPException(status_code=404, detail="conversation not found")

        await db.execute(
            "INSERT INTO messages(id,conversation_id,role,content,created_at) VALUES (?,?,?,?,?)",
            (user_message_id, conversation_id, "user", user_text, now),
        )
        title_candidate = _title_from_user_message(user_text) or None
        await db.execute(
            """
            UPDATE conversations
            SET
              updated_at = ?,
              title = CASE WHEN title IS NULL THEN ? ELSE title END
            WHERE id=? AND device_token=?
            """,
            (now, title_candidate, conversation_id, device_token),
        )
        await db.commit()

        # Step 4/5: read full history -> OpenAI messages
        async with db.execute(
            "SELECT role,content FROM messages WHERE conversation_id=? ORDER BY created_at ASC, rowid ASC",
            (conversation_id,),
        ) as cur:
            rows = await cur.fetchall()

    oai_messages = [{"role": r["role"], "content": r["content"]} for r in rows]

    # Step 6: reuse existing LLM routing/limits/quota logic.
    completion = await _call_llm(token=device_token, tier=tier, messages=oai_messages, forced_provider=None, wants_stream=False)

    # Step 7: extract assistant content
    choice0 = (completion.get("choices") or [{}])[0] or {}
    assistant_msg = choice0.get("message") or {}
    assistant_content = assistant_msg.get("content")
    if assistant_content is None:
        assistant_content = ""
    if not isinstance(assistant_content, str):
        assistant_content = str(assistant_content)

    # Step 8/9: store assistant message + bump updated_at.
    assistant_now = int(time.time())
    assistant_message_id = str(uuid.uuid4())

    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        await db.execute(
            "INSERT INTO messages(id,conversation_id,role,content,created_at) VALUES (?,?,?,?,?)",
            (assistant_message_id, conversation_id, "assistant", assistant_content, assistant_now),
        )
        await db.execute(
            "UPDATE conversations SET updated_at=? WHERE id=? AND device_token=?",
            (assistant_now, conversation_id, device_token),
        )
        await db.commit()

    return {
        "message_id": assistant_message_id,
        "role": "assistant",
        "content": assistant_content,
        "conversation_id": conversation_id,
        "created_at": assistant_now,
    }


@app.delete("/v1/conversations/{conversation_id}")
async def delete_conversation(conversation_id: str, request: Request) -> Any:
    device_token = _require_device_token(request)
    await _get_tier_for_token(device_token)

    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        async with db.execute(
            "SELECT id FROM conversations WHERE id=? AND device_token=?",
            (conversation_id, device_token),
        ) as cur:
            row = await cur.fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="conversation not found")

        await db.execute("DELETE FROM messages WHERE conversation_id=?", (conversation_id,))
        await db.execute("DELETE FROM conversations WHERE id=? AND device_token=?", (conversation_id, device_token))
        await db.commit()

    return {"deleted": True}


def _admin_check(x_admin_key: Optional[str]) -> None:
    if not ADMIN_KEY:
        raise HTTPException(status_code=404, detail="admin disabled")
    if not x_admin_key or not secrets.compare_digest(x_admin_key, ADMIN_KEY):
        raise HTTPException(status_code=401, detail="bad admin key")


@app.post("/admin/tokens/generate")
async def admin_generate_tokens(
    request: Request,
    x_admin_key: Optional[str] = Header(default=None),
) -> Any:
    _admin_check(x_admin_key)
    body = await request.json()
    tier = str(body.get("tier") or "free")
    count = int(body.get("count") or 1)
    if tier not in LIMITS:
        raise HTTPException(status_code=400, detail="invalid tier")
    if count < 1 or count > 1000:
        raise HTTPException(status_code=400, detail="count must be 1..1000")

    now = int(time.time())
    tokens: List[str] = []
    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        for _ in range(count):
            token = "ocw1_" + base64.urlsafe_b64encode(secrets.token_bytes(24)).decode("utf-8").rstrip("=")
            tokens.append(token)
            await db.execute(
                "INSERT OR REPLACE INTO device_tokens(token,tier,status,note,created_at) VALUES (?,?,?,?,?)",
                (token, tier, "active", None, now),
            )
        await db.commit()

    return {"tier": tier, "tokens": tokens}


@app.post("/admin/tokens/{token}/tier")
async def admin_set_tier(
    token: str,
    request: Request,
    x_admin_key: Optional[str] = Header(default=None),
) -> Any:
    _admin_check(x_admin_key)
    body = await request.json()
    tier = str(body.get("tier") or "")
    if tier not in LIMITS:
        raise HTTPException(status_code=400, detail="invalid tier")

    row = await _get_token_row(token)
    if not row:
        raise HTTPException(status_code=404, detail="token not found")

    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        await db.execute("UPDATE device_tokens SET tier=? WHERE token=?", (tier, token))
        await db.commit()

    return {"token": token, "tier": tier}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host=LISTEN_HOST, port=LISTEN_PORT)
