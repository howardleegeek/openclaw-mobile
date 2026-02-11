import asyncio
import base64
import calendar
import json
import os
import re
import secrets
import sqlite3
import time
import traceback
import uuid
from contextlib import suppress
from contextvars import ContextVar
from dataclasses import dataclass
from typing import Any, AsyncIterator, Dict, List, Optional, Tuple

import aiosqlite
import bcrypt
import httpx
from dotenv import load_dotenv
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse, StreamingResponse


load_dotenv()


# -----------------------------
# Security / Validation
# -----------------------------

# RFC 5322 (simplified) email regex. We also enforce max length (254) separately.
_EMAIL_RE = re.compile(
    r"^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@"
    r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
    r"(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$"
)

_LOGIN_FAILURES: Dict[str, List[int]] = {}
_LOGIN_FAILS_PER_MINUTE = 5
_LOGIN_FAIL_WINDOW_SECS = 60
_LOGIN_LOCKOUT_SECS = 300  # 5 minutes


def _client_ip(request: Request) -> str:
    # Prefer X-Forwarded-For when behind a proxy; otherwise fall back to peer address.
    xff = request.headers.get("x-forwarded-for") or request.headers.get("X-Forwarded-For")
    if isinstance(xff, str) and xff.strip():
        return xff.split(",")[0].strip() or "unknown"
    if request.client and getattr(request.client, "host", None):
        return str(request.client.host)
    return "unknown"


def _is_valid_email(email_norm: str) -> bool:
    if not isinstance(email_norm, str):
        return False
    if not email_norm or len(email_norm) > 254:
        return False
    return _EMAIL_RE.fullmatch(email_norm) is not None


def _is_login_rate_limited(ip: str, now: int) -> bool:
    ts = _LOGIN_FAILURES.get(ip) or []
    # Keep only what we need to evaluate "5 failures/minute" with a 5-minute lockout.
    cutoff = now - (_LOGIN_LOCKOUT_SECS + _LOGIN_FAIL_WINDOW_SECS)
    ts = [t for t in ts if isinstance(t, int) and t >= cutoff]
    _LOGIN_FAILURES[ip] = ts

    # Determine if the IP is currently within a lockout period:
    # Find any 5-failures-within-60s group and lock for 5 minutes from the 5th failure.
    lockout_until = 0
    if len(ts) >= _LOGIN_FAILS_PER_MINUTE:
        for i in range(_LOGIN_FAILS_PER_MINUTE - 1, len(ts)):
            if ts[i] - ts[i - (_LOGIN_FAILS_PER_MINUTE - 1)] <= _LOGIN_FAIL_WINDOW_SECS:
                lockout_until = max(lockout_until, ts[i] + _LOGIN_LOCKOUT_SECS)
    return now < lockout_until


def _record_login_failure(ip: str, now: int) -> None:
    ts = _LOGIN_FAILURES.get(ip) or []
    ts.append(int(now))
    _LOGIN_FAILURES[ip] = ts


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
CLAUDE_BASE_URL = os.getenv("CLAUDE_BASE_URL", "").rstrip("/")
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
    "free": TierLimits(max_context_tokens=8_000, max_output_tokens=2048, daily_tokens=60_000),
    "pro": TierLimits(max_context_tokens=32_000, max_output_tokens=1024, daily_tokens=600_000),
    "max": TierLimits(max_context_tokens=64_000, max_output_tokens=2048, daily_tokens=1_200_000),
}

_CALL_LLM_BODY: ContextVar[Optional[Dict[str, Any]]] = ContextVar("_CALL_LLM_BODY", default=None)

PERSONA_PROMPTS: Dict[str, str] = {
    "assistant": (
        "你是 ClawPhones AI 助手，由 Oyster Labs 开发。\n\n"
        "## 关于你\n"
        "- ClawPhones 是一款 AI 智能手机助手，运行在 Oyster Labs 的 Universal Phone 和 ClawGlasses 设备上\n"
        "- Oyster Labs 是一家 Web3 + AI 硬件公司，已售出 40,000+ 台设备，拥有 70,000+ 用户\n"
        "- 你的底层模型根据用户套餐自动选择：Free=DeepSeek, Pro=Kimi K2.5, Max=Claude Sonnet\n\n"
        "## 回复规则\n"
        "- **语言自适应**：用户用中文你就用中文回复，用英文就用英文，用日文就用日文，始终匹配用户语言\n"
        "- **简洁优先**：回复控制在 3-5 句话内，除非用户明确要求详细解释\n"
        "- **结构化输出**：善用 Markdown 格式（加粗、列表、代码块）让信息清晰易读\n"
        "- **有观点**：不要空泛，给出明确建议和具体答案。如果不确定就说不确定，不要编造\n"
        "- **代码场景**：给代码时附简短注释，默认用用户提到的语言，没提到用 Python\n"
        "- **不要过度道歉**：直接回答问题，不需要反复说'好的'、'当然可以'之类的客套话\n"
        "- **拒绝有害内容**：不生成违法、暴力、色情、歧视性内容\n\n"
        "## 你的能力\n"
        "- 日常问答、知识查询、翻译、写作、编程、数学、逻辑推理\n"
        "- 帮用户起草邮件、文案、社交媒体帖子\n"
        "- 解释复杂概念，提供学习建议\n"
        "- 头脑风暴、创意生成、问题分析\n\n"
        "## 你不能做的\n"
        "- 无法访问互联网或实时数据\n"
        "- 无法执行代码或操作用户设备\n"
        "- 无法访问用户的个人文件或应用数据\n"
    ),
    "coder": (
        "你是 ClawPhones 编程助手，由 Oyster Labs 开发。\n\n"
        "## 回复规则\n"
        "- 语言自适应：匹配用户语言\n"
        "- 代码优先：尽量用代码示例说明，附简短中文/英文注释\n"
        "- 给出完整可运行的代码片段，不要省略关键部分\n"
        "- 说明时间复杂度和边界情况\n"
        "- 如果有多种方案，先给最佳实践，再提替代方案\n"
        "- 使用 Markdown 代码块，标注语言类型\n\n"
        "## 擅长领域\n"
        "Python, JavaScript/TypeScript, Swift, Java/Kotlin, Rust, Go, SQL, Shell\n"
        "Web 开发, 移动开发, 后端架构, 数据库设计, DevOps, AI/ML\n"
    ),
    "writer": (
        "你是 ClawPhones 写作助手，由 Oyster Labs 开发。\n\n"
        "## 回复规则\n"
        "- 语言自适应：匹配用户语言\n"
        "- 文笔流畅自然，避免机械感和 AI 味\n"
        "- 根据场景调整风格：商务邮件正式严谨，社交媒体轻松活泼，文章有深度有观点\n"
        "- 给出多个版本供选择（正式版 / 口语版）\n"
        "- 善用修辞：比喻、排比、对比，让文字有感染力\n"
        "- 注意 SEO 关键词和阅读节奏\n\n"
        "## 擅长领域\n"
        "商务邮件, 社交媒体文案, 产品描述, 新闻稿, 博客文章, 学术写作, 创意文案\n"
    ),
    "translator": (
        "你是 ClawPhones 翻译助手，由 Oyster Labs 开发。\n\n"
        "## 回复规则\n"
        "- 自动检测源语言，翻译为目标语言\n"
        "- 如果用户没指定目标语言：中文内容翻译成英文，英文内容翻译成中文\n"
        "- 翻译准确自然，不是逐字翻译，而是意译 + 保留原文风格\n"
        "- 专业术语附英文原文：如 '去中心化金融 (DeFi)'\n"
        "- 长文本分段翻译，保持格式\n"
        "- 如果原文有歧义，给出多种翻译并解释区别\n\n"
        "## 支持语言\n"
        "中文 (简体/繁体), English, 日本語, 한국어, Français, Deutsch, Español, Português\n"
    ),
}


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


def _normalize_email(email: str) -> str:
    return (email or "").strip().lower()


def _gen_device_token() -> str:
    return "ocw1_" + base64.urlsafe_b64encode(secrets.token_bytes(24)).decode("utf-8").rstrip("=")


def _safe_json_loads_object(s: Any) -> Dict[str, Any]:
    if isinstance(s, dict):
        return s
    if not isinstance(s, str) or not s.strip():
        return {}
    try:
        obj = json.loads(s)
    except Exception:
        return {}
    return obj if isinstance(obj, dict) else {}


def _persona_system_prompt(ai_config: Dict[str, Any]) -> Optional[str]:
    persona = ai_config.get("persona")
    if not isinstance(persona, str) or not persona.strip():
        persona = "assistant"
    persona = persona.strip()

    if persona == "custom":
        custom_prompt = ai_config.get("custom_prompt")
        if isinstance(custom_prompt, str) and custom_prompt.strip():
            return custom_prompt.strip()
        # Fall back if custom persona is selected but no prompt provided.
        return PERSONA_PROMPTS["assistant"]

    return PERSONA_PROMPTS.get(persona) or PERSONA_PROMPTS["assistant"]


def _inject_persona_system_message(messages: Any, ai_config: Dict[str, Any]) -> Any:
    if not isinstance(messages, list):
        return messages
    system_prompt = _persona_system_prompt(ai_config)
    if not system_prompt:
        return messages
    return [{"role": "system", "content": system_prompt}] + list(messages)


def _utc_day_bounds(ts: Optional[int] = None) -> Tuple[int, int, str]:
    now = int(ts or time.time())
    g = time.gmtime(now)
    day = time.strftime("%Y-%m-%d", g)
    start = calendar.timegm((g.tm_year, g.tm_mon, g.tm_mday, 0, 0, 0, 0, 0, 0))
    end = start + 86400
    return (start, end, day)


async def _init_db() -> None:
    _ensure_dir(TOKEN_DB_PATH)
    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        await db.execute(
            """
            CREATE TABLE IF NOT EXISTS users (
              id TEXT PRIMARY KEY,
              email TEXT UNIQUE NOT NULL,
              password_hash TEXT,
              apple_id TEXT UNIQUE,
              name TEXT DEFAULT '',
              avatar_url TEXT,
              tier TEXT DEFAULT 'free',
              ai_config TEXT DEFAULT '{}',
              language TEXT DEFAULT 'auto',
              created_at INTEGER,
              updated_at INTEGER
            );
            """
        )
        await db.execute(
            """
            CREATE TABLE IF NOT EXISTS device_tokens (
              token TEXT PRIMARY KEY,
              tier TEXT NOT NULL CHECK (tier IN ('free','pro','max')),
              status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','disabled')),
              note TEXT,
              user_id TEXT REFERENCES users(id),
              created_at INTEGER NOT NULL,
              expires_at INTEGER
            )
            """
        )
        # Migration for existing DBs: add user_id to device_tokens.
        try:
            await db.execute("ALTER TABLE device_tokens ADD COLUMN user_id TEXT REFERENCES users(id)")
        except Exception:
            pass
        # Migration for existing DBs: add expires_at to device_tokens.
        # NULL means never expires (backwards compatible).
        try:
            await db.execute("ALTER TABLE device_tokens ADD COLUMN expires_at INTEGER")
        except Exception:
            pass
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
        await db.execute("CREATE INDEX IF NOT EXISTS idx_device_tokens_user_id ON device_tokens(user_id)")
        await db.execute("CREATE INDEX IF NOT EXISTS idx_users_email ON users(email)")
        await db.commit()


async def _get_token_row(token: str) -> Optional[Dict[str, Any]]:
    now = int(time.time())
    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        try:
            async with db.execute(
                "SELECT token,tier,status,note,created_at,user_id,expires_at FROM device_tokens WHERE token=?",
                (token,),
            ) as cur:
                row = await cur.fetchone()
                if not row:
                    return None
                d = dict(row)
                exp = d.get("expires_at")
                if isinstance(exp, int) and exp > 0 and now >= exp:
                    return None
                return d
        except sqlite3.OperationalError:
            # Older DB pre-migration.
            # Try the latest known subsets in order (some DBs may have user_id but not expires_at).
            try:
                async with db.execute(
                    "SELECT token,tier,status,note,created_at,user_id FROM device_tokens WHERE token=?",
                    (token,),
                ) as cur:
                    row = await cur.fetchone()
                    if not row:
                        return None
                    d = dict(row)
                    d["expires_at"] = None
                    return d
            except sqlite3.OperationalError:
                async with db.execute(
                    "SELECT token,tier,status,note,created_at FROM device_tokens WHERE token=?",
                    (token,),
                ) as cur:
                    row = await cur.fetchone()
                    if not row:
                        return None
                    d = dict(row)
                    d["user_id"] = None
                    d["expires_at"] = None
                    return d


async def _get_user_row_by_id(user_id: str) -> Optional[Dict[str, Any]]:
    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        async with db.execute(
            """
            SELECT
              id,
              email,
              password_hash,
              apple_id,
              name,
              avatar_url,
              tier,
              ai_config,
              language,
              created_at,
              updated_at
            FROM users
            WHERE id=?
            """,
            (user_id,),
        ) as cur:
            row = await cur.fetchone()
            return dict(row) if row else None


async def _get_user_row_by_email(email: str) -> Optional[Dict[str, Any]]:
    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        async with db.execute(
            """
            SELECT
              id,
              email,
              password_hash,
              apple_id,
              name,
              avatar_url,
              tier,
              ai_config,
              language,
              created_at,
              updated_at
            FROM users
            WHERE email=?
            """,
            (email,),
        ) as cur:
            row = await cur.fetchone()
            return dict(row) if row else None


async def _get_user_row_for_token_optional(token: str) -> Optional[Dict[str, Any]]:
    # For chat paths: optional enrichment (backward compatible for tokens without user_id).
    row = await _get_token_row(token)
    if not row:
        return None
    user_id = row.get("user_id")
    if not user_id:
        return None
    return await _get_user_row_by_id(str(user_id))


async def _require_user(request: Request) -> Tuple[str, Dict[str, Any]]:
    token = _require_device_token(request)
    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        try:
            async with db.execute(
                "SELECT token,tier,status,user_id,expires_at FROM device_tokens WHERE token=?",
                (token,),
            ) as cur:
                trow = await cur.fetchone()
        except sqlite3.OperationalError:
            # Older DB pre-migration.
            try:
                async with db.execute(
                    "SELECT token,tier,status,user_id FROM device_tokens WHERE token=?",
                    (token,),
                ) as cur:
                    trow = await cur.fetchone()
            except sqlite3.OperationalError:
                trow = None

    if not trow:
        raise HTTPException(status_code=401, detail="invalid token")
    exp: Any = None
    try:
        exp = trow["expires_at"]
    except Exception:
        exp = None
    if isinstance(exp, int) and exp > 0 and int(time.time()) >= exp:
        raise HTTPException(status_code=401, detail="token expired")
    if (trow["status"] or "") != "active":
        raise HTTPException(status_code=403, detail="token disabled")
    user_id = trow["user_id"]
    if not user_id:
        raise HTTPException(status_code=401, detail="token not associated with user")

    user = await _get_user_row_by_id(str(user_id))
    if not user:
        raise HTTPException(status_code=401, detail="user not found")
    return (token, user)


async def _get_tier_for_token(token: str) -> str:
    row = await _get_token_row(token)
    if not row:
        raise HTTPException(status_code=401, detail="invalid token")
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
    stream: bool = False,
) -> Any:
    url = f"{base_url}/chat/completions"
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }

    if not stream:
        async with httpx.AsyncClient(timeout=60) as client:
            resp = await client.post(url, headers=headers, json=body)
            if resp.status_code >= 400:
                raise HTTPException(status_code=resp.status_code, detail=resp.text)
            return resp.json()

    async def gen() -> AsyncIterator[str]:
        # Allow long-lived responses. We'll keep the client connection alive with SSE keepalives downstream.
        timeout = httpx.Timeout(60.0, connect=10.0, read=None)
        async with httpx.AsyncClient(timeout=timeout) as client:
            async with client.stream("POST", url, headers=headers, json=body) as resp:
                if resp.status_code >= 400:
                    raw = await resp.aread()
                    text = raw.decode("utf-8", errors="replace")
                    raise HTTPException(status_code=resp.status_code, detail=text)

                async for line in resp.aiter_lines():
                    if not line:
                        continue
                    # OpenAI-style SSE can include comments like ": ping".
                    if line.startswith(":"):
                        continue
                    if not line.startswith("data:"):
                        continue

                    data = line[len("data:") :].strip()
                    if not data:
                        continue
                    if data == "[DONE]":
                        break

                    try:
                        obj = json.loads(data)
                    except Exception:
                        continue

                    choices = obj.get("choices")
                    if not isinstance(choices, list) or not choices:
                        continue
                    c0 = choices[0]
                    if not isinstance(c0, dict):
                        continue
                    delta = c0.get("delta")
                    if not isinstance(delta, dict):
                        continue
                    content = delta.get("content")
                    if isinstance(content, str) and content:
                        yield content

    return gen()


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


def _sse_data(obj: Dict[str, Any]) -> bytes:
    return (f"data: {json.dumps(obj, ensure_ascii=False)}\n\n").encode("utf-8")


def _sse_comment(text: str) -> bytes:
    t = " ".join((text or "").split())
    return (f": {t}\n\n").encode("utf-8")


def _sse_error_once(message: str) -> AsyncIterator[bytes]:
    async def gen() -> AsyncIterator[bytes]:
        yield _sse_data({"error": str(message or "error"), "done": True})

    return gen()


app = FastAPI(title="OpenClaw Proxy", version="0.1.0")


@app.on_event("startup")
async def _startup() -> None:
    await _init_db()


@app.get("/health")
async def health() -> Dict[str, Any]:
    return {"ok": True, "ts": int(time.time())}


@app.post("/v1/auth/register")
async def auth_register(request: Request) -> Any:
    try:
        body = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="request body must be valid JSON")
    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="invalid json body")

    email = body.get("email")
    password = body.get("password")
    name = body.get("name") if body.get("name") is not None else ""

    if not isinstance(email, str) or not email.strip():
        raise HTTPException(status_code=400, detail="email required")
    if not isinstance(password, str) or not password:
        raise HTTPException(status_code=400, detail="password required")
    if not isinstance(name, str):
        raise HTTPException(status_code=400, detail="name must be a string")

    email_norm = _normalize_email(email)
    if not _is_valid_email(email_norm):
        raise HTTPException(status_code=400, detail="Invalid email format")
    if len(password) < 8 or len(password) > 72:
        raise HTTPException(status_code=400, detail="Password must be 8-72 characters")
    now = int(time.time())
    expires_at = now + 30 * 86400
    user_id = str(uuid.uuid4())

    pw_hash = bcrypt.hashpw(password.encode("utf-8"), bcrypt.gensalt()).decode("utf-8")

    # New users default to free tier; token tier is tied to user tier.
    tier = "free"

    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        try:
            await db.execute(
                """
                INSERT INTO users(id,email,password_hash,name,tier,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?)
                """,
                (user_id, email_norm, pw_hash, name.strip(), tier, now, now),
            )
        except sqlite3.IntegrityError:
            # Unique constraint on users.email.
            raise HTTPException(status_code=409, detail="email already registered")

        token: Optional[str] = None
        for _ in range(5):
            candidate = _gen_device_token()
            try:
                await db.execute(
                    "INSERT INTO device_tokens(token,tier,status,note,user_id,created_at,expires_at) VALUES (?,?,?,?,?,?,?)",
                    (candidate, tier, "active", None, user_id, now, expires_at),
                )
                token = candidate
                break
            except sqlite3.IntegrityError:
                continue

        if not token:
            raise HTTPException(status_code=500, detail="failed to allocate token")

        await db.commit()

    return {"user_id": user_id, "token": token, "tier": tier, "created_at": now}


@app.post("/v1/auth/login")
async def auth_login(request: Request) -> Any:
    try:
        body = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="request body must be valid JSON")
    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="invalid json body")

    email = body.get("email")
    password = body.get("password")
    if not isinstance(email, str) or not email.strip():
        raise HTTPException(status_code=400, detail="email required")
    if not isinstance(password, str) or not password:
        raise HTTPException(status_code=400, detail="password required")

    ip = _client_ip(request)
    now = int(time.time())
    expires_at = now + 30 * 86400
    if _is_login_rate_limited(ip, now):
        raise HTTPException(status_code=429, detail="Too many login attempts. Try again in 5 minutes")

    email_norm = _normalize_email(email)
    user = await _get_user_row_by_email(email_norm)
    if not user:
        _record_login_failure(ip, now)
        raise HTTPException(status_code=401, detail="Invalid email or password")

    pw_hash = user.get("password_hash") or ""
    if not isinstance(pw_hash, str) or not pw_hash:
        _record_login_failure(ip, now)
        raise HTTPException(status_code=401, detail="Invalid email or password")

    ok = False
    try:
        ok = bcrypt.checkpw(password.encode("utf-8"), pw_hash.encode("utf-8"))
    except Exception:
        ok = False
    if not ok:
        _record_login_failure(ip, now)
        raise HTTPException(status_code=401, detail="Invalid email or password")

    tier = str(user.get("tier") or "free")
    if tier not in LIMITS:
        tier = "free"

    user_id = str(user["id"])

    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        token: Optional[str] = None
        for _ in range(5):
            candidate = _gen_device_token()
            try:
                await db.execute(
                    "INSERT INTO device_tokens(token,tier,status,note,user_id,created_at,expires_at) VALUES (?,?,?,?,?,?,?)",
                    (candidate, tier, "active", None, user_id, now, expires_at),
                )
                token = candidate
                break
            except sqlite3.IntegrityError:
                continue

        if not token:
            raise HTTPException(status_code=500, detail="failed to allocate token")

        await db.commit()

    # Successful login clears failures for this IP.
    _LOGIN_FAILURES.pop(ip, None)

    ai_config = _safe_json_loads_object(user.get("ai_config"))
    return {"user_id": user_id, "token": token, "tier": tier, "name": user.get("name") or "", "ai_config": ai_config}


@app.get("/v1/user/profile")
async def user_get_profile(request: Request) -> Any:
    _, user = await _require_user(request)
    return {
        "user_id": user["id"],
        "email": user["email"],
        "name": user.get("name") or "",
        "avatar_url": user.get("avatar_url"),
        "tier": user.get("tier") or "free",
        "language": user.get("language") or "auto",
        "created_at": user.get("created_at"),
    }


@app.put("/v1/user/profile")
async def user_put_profile(request: Request) -> Any:
    _, user = await _require_user(request)
    try:
        body = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="request body must be valid JSON")
    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="invalid json body")

    name = body.get("name")
    language = body.get("language")
    if name is not None and not isinstance(name, str):
        raise HTTPException(status_code=400, detail="name must be a string")
    if language is not None and not isinstance(language, str):
        raise HTTPException(status_code=400, detail="language must be a string")

    updates: List[str] = []
    params: List[Any] = []
    if name is not None:
        name2 = name.strip()
        if len(name2) > 100:
            raise HTTPException(status_code=400, detail="name too long (max 100 chars)")
        updates.append("name=?")
        params.append(name2)
    if language is not None:
        updates.append("language=?")
        params.append(language.strip() or "auto")

    if updates:
        now = int(time.time())
        updates.append("updated_at=?")
        params.append(now)
        params.append(str(user["id"]))
        sql = f"UPDATE users SET {', '.join(updates)} WHERE id=?"
        async with aiosqlite.connect(TOKEN_DB_PATH) as db:
            await db.execute(sql, tuple(params))
            await db.commit()
        user = await _get_user_row_by_id(str(user["id"])) or user

    return {
        "user_id": user["id"],
        "email": user["email"],
        "name": user.get("name") or "",
        "avatar_url": user.get("avatar_url"),
        "tier": user.get("tier") or "free",
        "language": user.get("language") or "auto",
        "created_at": user.get("created_at"),
    }


@app.put("/v1/user/password")
async def user_put_password(request: Request) -> Any:
    _, user = await _require_user(request)
    try:
        body = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="request body must be valid JSON")
    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="invalid json body")

    old_password = body.get("old_password")
    new_password = body.get("new_password")
    if not isinstance(old_password, str) or not old_password:
        raise HTTPException(status_code=400, detail="old_password required")
    if not isinstance(new_password, str) or not new_password:
        raise HTTPException(status_code=400, detail="new_password required")

    pw_hash = user.get("password_hash") or ""
    if not isinstance(pw_hash, str) or not pw_hash:
        raise HTTPException(status_code=400, detail="password not set")

    ok = False
    try:
        ok = bcrypt.checkpw(old_password.encode("utf-8"), pw_hash.encode("utf-8"))
    except Exception:
        ok = False
    if not ok:
        raise HTTPException(status_code=401, detail="invalid credentials")

    new_hash = bcrypt.hashpw(new_password.encode("utf-8"), bcrypt.gensalt()).decode("utf-8")
    now = int(time.time())
    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        await db.execute(
            "UPDATE users SET password_hash=?, updated_at=? WHERE id=?",
            (new_hash, now, str(user["id"])),
        )
        await db.commit()
    return {"updated": True}


@app.get("/v1/user/plan")
async def user_get_plan(request: Request) -> Any:
    _, user = await _require_user(request)
    start_ts, end_ts, day = _utc_day_bounds()

    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        async with db.execute(
            """
            SELECT COUNT(1) AS cnt
            FROM messages m
            JOIN conversations c ON c.id = m.conversation_id
            JOIN device_tokens dt ON dt.token = c.device_token
            WHERE dt.user_id = ?
              AND m.role = 'user'
              AND m.created_at >= ?
              AND m.created_at < ?
            """,
            (str(user["id"]), int(start_ts), int(end_ts)),
        ) as cur:
            row = await cur.fetchone()
            usage_messages = int((row["cnt"] if row else 0) or 0)

    tier = str(user.get("tier") or "free")
    if tier not in LIMITS:
        tier = "free"

    plans = [
        {
            "tier": t,
            "limits": {
                "max_context_tokens": LIMITS[t].max_context_tokens,
                "max_output_tokens": LIMITS[t].max_output_tokens,
                "daily_tokens": LIMITS[t].daily_tokens,
            },
        }
        for t in ("free", "pro", "max")
    ]

    return {
        "tier": tier,
        "current_plan": tier,
        "usage": {"day": day, "messages": usage_messages},
        "available_plans": plans,
        "plans": plans,
    }


@app.get("/v1/user/ai-config")
async def user_get_ai_config(request: Request) -> Any:
    _, user = await _require_user(request)
    ai_config = _safe_json_loads_object(user.get("ai_config"))
    return {"ai_config": ai_config, "personas": list(PERSONA_PROMPTS.keys()) + ["custom"]}


@app.put("/v1/user/ai-config")
async def user_put_ai_config(request: Request) -> Any:
    _, user = await _require_user(request)
    try:
        body = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="request body must be valid JSON")
    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="invalid json body")

    persona = body.get("persona")
    custom_prompt = body.get("custom_prompt")
    temperature = body.get("temperature")

    allowed_personas = set(PERSONA_PROMPTS.keys()) | {"custom"}
    if persona is not None:
        if not isinstance(persona, str) or not persona.strip():
            raise HTTPException(status_code=400, detail="persona must be a string")
        persona = persona.strip()
        if persona not in allowed_personas:
            raise HTTPException(status_code=400, detail="invalid persona")

    if custom_prompt is not None and not isinstance(custom_prompt, str):
        raise HTTPException(status_code=400, detail="custom_prompt must be a string")
    if isinstance(custom_prompt, str) and len(custom_prompt) > 2000:
        raise HTTPException(status_code=400, detail="custom_prompt too long (max 2000 chars)")

    if temperature is not None and not isinstance(temperature, (int, float)):
        raise HTTPException(status_code=400, detail="temperature must be a number")

    ai_config = _safe_json_loads_object(user.get("ai_config"))
    if persona is not None:
        ai_config["persona"] = persona
    if custom_prompt is not None:
        ai_config["custom_prompt"] = custom_prompt
    if temperature is not None:
        t = float(temperature)
        if t < 0:
            t = 0.0
        elif t > 2:
            t = 2.0
        ai_config["temperature"] = t

    now = int(time.time())
    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        await db.execute(
            "UPDATE users SET ai_config=?, updated_at=? WHERE id=?",
            (json.dumps(ai_config, ensure_ascii=False), now, str(user["id"])),
        )
        await db.commit()

    return {"ai_config": ai_config, "personas": list(PERSONA_PROMPTS.keys()) + ["custom"]}


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
        "free": "kimi",
        "pro": "kimi",
        "max": "claude",
    }.get(tier, "kimi")

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
        if CLAUDE_BASE_URL:
            # OpenAI-compatible endpoint (e.g. OpenRouter)
            upstream_body = dict(body)
            upstream_body["model"] = CLAUDE_MODEL
            res = await _call_openai_compatible(base_url=CLAUDE_BASE_URL, api_key=ANTHROPIC_API_KEY, body=upstream_body)
        else:
            # Native Anthropic API
            anth = await _call_anthropic_messages(body=body, max_tokens=int(body["max_tokens"]))
            res = _anthropic_to_openai_completion(anth, public_model=public_model)
        res["model"] = public_model
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

    try:
        body = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="request body must be valid JSON")
    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="invalid json body")

    # Optional: apply user-level ai_config when this token is linked to a user.
    user = await _get_user_row_for_token_optional(token)
    if user:
        ai_config = _safe_json_loads_object(user.get("ai_config"))
        if isinstance(body.get("messages"), list):
            body["messages"] = _inject_persona_system_message(body.get("messages"), ai_config)
        if body.get("temperature") is None and isinstance(ai_config.get("temperature"), (int, float)):
            body["temperature"] = float(ai_config["temperature"])

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

    try:
        body = await request.json()
    except Exception:
        body = {}
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

    try:
        body = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="request body must be valid JSON")
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
    user = await _get_user_row_for_token_optional(device_token)
    ai_config: Dict[str, Any] = _safe_json_loads_object(user.get("ai_config")) if user else {}
    if user:
        oai_messages = _inject_persona_system_message(oai_messages, ai_config)

    overrides: Dict[str, Any] = {}
    if isinstance(ai_config.get("temperature"), (int, float)):
        overrides["temperature"] = float(ai_config["temperature"])

    if overrides:
        ctx = _CALL_LLM_BODY.set(overrides)
        try:
            completion = await _call_llm(
                token=device_token,
                tier=tier,
                messages=oai_messages,
                forced_provider=None,
                wants_stream=False,
            )
        finally:
            _CALL_LLM_BODY.reset(ctx)
    else:
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


@app.post("/v1/conversations/{conversation_id}/chat/stream")
async def conversation_chat_stream(conversation_id: str, request: Request) -> Any:
    device_token = _require_device_token(request)
    tier = await _get_tier_for_token(device_token)

    try:
        body = await request.json()
    except Exception:
        return StreamingResponse(_sse_error_once("request body must be valid JSON"), media_type="text/event-stream")
    if not isinstance(body, dict):
        return StreamingResponse(_sse_error_once("invalid json body"), media_type="text/event-stream")

    user_text = body.get("message")
    if not isinstance(user_text, str):
        return StreamingResponse(_sse_error_once("message must be a string"), media_type="text/event-stream")
    user_text = user_text.strip()
    if not user_text:
        return StreamingResponse(_sse_error_once("message must be non-empty"), media_type="text/event-stream")
    if len(user_text) > 50_000:
        return StreamingResponse(_sse_error_once("message too long (max 50000 chars)"), media_type="text/event-stream")

    now = int(time.time())
    user_message_id = str(uuid.uuid4())

    # Step 1: verify ownership + store user message first (required).
    try:
        async with aiosqlite.connect(TOKEN_DB_PATH) as db:
            db.row_factory = aiosqlite.Row
            async with db.execute(
                "SELECT id,title FROM conversations WHERE id=? AND device_token=?",
                (conversation_id, device_token),
            ) as cur:
                conv = await cur.fetchone()
            if not conv:
                return StreamingResponse(_sse_error_once("conversation not found"), media_type="text/event-stream")

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

            # Step 2: read full history -> OpenAI messages
            async with db.execute(
                "SELECT role,content FROM messages WHERE conversation_id=? ORDER BY created_at ASC, rowid ASC",
                (conversation_id,),
            ) as cur:
                rows = await cur.fetchall()
    except Exception as e:
        print(f"[chat/stream] internal error: {e!r}")
        traceback.print_exc()

        async def _gen_internal_error() -> AsyncIterator[bytes]:
            yield _sse_data({"error": "Internal error"})

        return StreamingResponse(_gen_internal_error(), media_type="text/event-stream")

    oai_messages = [{"role": r["role"], "content": r["content"]} for r in rows]

    # Keep behavior consistent with non-stream chat: optional user persona/system prompt + overrides.
    user = await _get_user_row_for_token_optional(device_token)
    ai_config: Dict[str, Any] = _safe_json_loads_object(user.get("ai_config")) if user else {}
    if user:
        oai_messages = _inject_persona_system_message(oai_messages, ai_config)

    overrides: Dict[str, Any] = {}
    if isinstance(ai_config.get("temperature"), (int, float)):
        overrides["temperature"] = float(ai_config["temperature"])

    async def stream_gen() -> AsyncIterator[bytes]:
        limits = LIMITS.get(tier) or LIMITS["free"]
        provider = {"free": "kimi", "pro": "kimi", "max": "claude"}.get(tier, "kimi")

        try:
            # Build OpenAI-compatible request body.
            if overrides:
                body2: Dict[str, Any] = dict(overrides)
                body2["messages"] = oai_messages
                body2.setdefault("model", "oyster-auto")
            else:
                body2 = {"messages": oai_messages, "model": "oyster-auto"}

            messages = body2.get("messages") or []
            if not isinstance(messages, list):
                yield _sse_data({"error": "messages must be an array", "done": True})
                return

            # Truncate oldest messages if needed (tier context limit).
            messages = _truncate_messages_to_fit(messages, limits.max_context_tokens)
            body2["messages"] = messages

            prompt_tokens = _messages_approx_tokens(messages)
            used_prompt, used_completion, _ = await _get_daily_usage(device_token)
            used_total = used_prompt + used_completion
            if used_total + prompt_tokens > limits.daily_tokens:
                yield _sse_data({"error": "daily quota exceeded", "done": True})
                return

            # Cap output tokens.
            req_max_tokens = body2.get("max_tokens")
            if isinstance(req_max_tokens, int) and req_max_tokens > 0:
                body2["max_tokens"] = min(req_max_tokens, limits.max_output_tokens)
            else:
                body2["max_tokens"] = limits.max_output_tokens

            # Upstream streaming.
            body2["stream"] = True

            if not MOCK_MODE:
                _require_upstream_key(provider)

            public_model = str(body2.get("model") or "oyster-auto")

            async def _mock_stream() -> AsyncIterator[str]:
                reply = f"[MOCK:{provider}:{tier}] " + (messages[-1].get("content") if messages else "")
                # Yield small chunks; we still send one SSE event per character downstream.
                for ch in reply:
                    await asyncio.sleep(0)
                    yield ch

            if MOCK_MODE:
                delta_iter: AsyncIterator[str] = _mock_stream()
            elif provider == "deepseek":
                upstream_body = dict(body2)
                upstream_body["model"] = DEEPSEEK_MODEL
                delta_iter = await _call_openai_compatible(
                    base_url=DEEPSEEK_BASE_URL,
                    api_key=DEEPSEEK_API_KEY,
                    body=upstream_body,
                    stream=True,
                )
            elif provider == "kimi":
                upstream_body = dict(body2)
                upstream_body["model"] = KIMI_MODEL
                delta_iter = await _call_openai_compatible(
                    base_url=KIMI_BASE_URL,
                    api_key=KIMI_API_KEY,
                    body=upstream_body,
                    stream=True,
                )
            elif provider == "claude" and CLAUDE_BASE_URL:
                upstream_body = dict(body2)
                upstream_body["model"] = CLAUDE_MODEL
                delta_iter = await _call_openai_compatible(
                    base_url=CLAUDE_BASE_URL,
                    api_key=ANTHROPIC_API_KEY,
                    body=upstream_body,
                    stream=True,
                )
            elif provider == "claude":
                # Native Anthropic API: fallback to non-stream and drip out locally.
                body2["stream"] = False
                anth = await _call_anthropic_messages(body=body2, max_tokens=int(body2["max_tokens"]))
                completion = _anthropic_to_openai_completion(anth, public_model=public_model)
                choice0 = (completion.get("choices") or [{}])[0] or {}
                assistant_msg = choice0.get("message") or {}
                assistant_content = assistant_msg.get("content") or ""
                if not isinstance(assistant_content, str):
                    assistant_content = str(assistant_content)

                async def _one_shot() -> AsyncIterator[str]:
                    yield assistant_content

                delta_iter = _one_shot()
            else:
                yield _sse_data({"error": "unknown provider", "done": True})
                return

            q: asyncio.Queue[Any] = asyncio.Queue()
            sentinel = object()
            producer_exc: Optional[BaseException] = None

            async def _producer() -> None:
                nonlocal producer_exc
                try:
                    async for d in delta_iter:
                        await q.put(d)
                except asyncio.CancelledError:
                    raise
                except Exception as e:
                    producer_exc = e
                finally:
                    await q.put(sentinel)

            task = asyncio.create_task(_producer())

            assistant_parts: List[str] = []
            assistant_message_id = str(uuid.uuid4())

            try:
                while True:
                    try:
                        item = await asyncio.wait_for(q.get(), timeout=15.0)
                    except asyncio.TimeoutError:
                        yield _sse_comment("keepalive")
                        continue

                    if item is sentinel:
                        break
                    if not isinstance(item, str) or not item:
                        continue

                    # One SSE event per "token" (approx. per character for true incremental rendering).
                    for ch in item:
                        assistant_parts.append(ch)
                        yield _sse_data({"delta": ch, "done": False})
            finally:
                task.cancel()
                with suppress(asyncio.CancelledError, Exception):
                    await task

            if producer_exc is not None:
                raise producer_exc

            full_content = "".join(assistant_parts)

            # Save assistant reply to DB before sending final done event.
            assistant_now = int(time.time())
            async with aiosqlite.connect(TOKEN_DB_PATH) as db:
                await db.execute(
                    "INSERT INTO messages(id,conversation_id,role,content,created_at) VALUES (?,?,?,?,?)",
                    (assistant_message_id, conversation_id, "assistant", full_content, assistant_now),
                )
                await db.execute(
                    "UPDATE conversations SET updated_at=? WHERE id=? AND device_token=?",
                    (assistant_now, conversation_id, device_token),
                )
                await db.commit()

            completion_tokens = _approx_tokens(full_content)
            await _bump_daily_usage(device_token, prompt_tokens, completion_tokens)

            yield _sse_data(
                {
                    "delta": "",
                    "done": True,
                    "message_id": assistant_message_id,
                    "content": full_content,
                }
            )
        except Exception as e:
            print(f"[chat/stream] internal error: {e!r}")
            traceback.print_exc()
            yield _sse_data({"error": "Internal error"})

    headers = {
        "Cache-Control": "no-cache",
        "Connection": "keep-alive",
        "X-Accel-Buffering": "no",
    }
    return StreamingResponse(stream_gen(), media_type="text/event-stream", headers=headers)


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
    try:
        body = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="request body must be valid JSON")
    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="invalid json body")
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
    try:
        body = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="request body must be valid JSON")
    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="invalid json body")
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
