import asyncio
import base64
import calendar
import hashlib
import io
import json
import mimetypes
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
from pathlib import Path
from threading import Lock
from typing import Any, AsyncIterator, Dict, List, Optional, Tuple

import aiosqlite
import bcrypt
import httpx
import jwt
from dotenv import load_dotenv
from fastapi import FastAPI, Header, HTTPException, Request, Response
from fastapi.responses import FileResponse, JSONResponse, StreamingResponse
from jwt import InvalidTokenError


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
RATE_LIMITS: Dict[str, Dict[str, int]] = {
    "auth": {"requests": 10, "window": 300},
    "chat": {"requests": 60, "window": 60},
    "upload": {"requests": 10, "window": 60},
    "admin": {"requests": 5, "window": 60},
    "export": {"requests": 3, "window": 300},
    "crash": {"requests": 20, "window": 60},
    "default": {"requests": 120, "window": 60},
}
_RATE_LIMIT_HITS: Dict[str, List[int]] = {}
_RATE_LIMIT_LOCK = Lock()


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


def _rate_limit_target(request: Request) -> Optional[Tuple[str, str]]:
    method = request.method.upper()
    path = request.url.path

    # Keep existing dedicated /v1/auth/login behavior unchanged.
    if method == "POST" and path == "/v1/auth/login":
        return None

    if method not in {"POST", "PUT", "PATCH", "DELETE"}:
        return None

    if method == "POST" and path in {"/v1/auth/register", "/v1/auth/apple", "/v1/auth/refresh"}:
        return ("auth", path)

    if method == "POST" and path in {
        "/v1/chat/completions",
        "/deepseek/v1/chat/completions",
        "/kimi/v1/chat/completions",
        "/claude/v1/chat/completions",
    }:
        return ("chat", path)

    if method == "POST" and re.fullmatch(r"/v1/conversations/[^/]+/chat/stream", path):
        return ("chat", "/v1/conversations/{id}/chat/stream")
    if method == "POST" and re.fullmatch(r"/v1/conversations/[^/]+/chat", path):
        return ("chat", "/v1/conversations/{id}/chat")

    if method == "POST" and re.fullmatch(r"/v1/conversations/[^/]+/upload", path):
        return ("upload", "/v1/conversations/{id}/upload")

    if method == "POST" and path.startswith("/admin/"):
        return ("admin", "/admin/*")

    if method == "POST" and path == "/v1/user/export":
        return ("export", path)
    if method == "DELETE" and path == "/v1/user/account":
        return ("export", path)

    if method == "POST" and path == "/v1/crash-reports":
        return ("crash", path)

    return ("default", path)


async def _enforce_rate_limit(request: Request) -> None:
    target = _rate_limit_target(request)
    if not target:
        return

    bucket, endpoint = target
    conf = RATE_LIMITS.get(bucket) or RATE_LIMITS["default"]
    max_requests = int(conf.get("requests") or 1)
    window = int(conf.get("window") or 1)
    now = int(time.time())
    cutoff = now - window
    ip = _client_ip(request)
    key = f"{bucket}:{ip}:{endpoint}"

    with _RATE_LIMIT_LOCK:
        hits = [t for t in (_RATE_LIMIT_HITS.get(key) or []) if isinstance(t, int) and t > cutoff]
        if len(hits) >= max_requests:
            _RATE_LIMIT_HITS[key] = hits
            raise HTTPException(status_code=429, detail="too many requests")
        hits.append(now)
        _RATE_LIMIT_HITS[key] = hits


# -----------------------------
# Config
# -----------------------------

TOKEN_DB_PATH = os.getenv("TOKEN_DB_PATH", "./data/tokens.sqlite3")
EXPORT_DIR = os.getenv("EXPORT_DIR", "./data/exports")
UPLOAD_DIR = os.path.join(os.path.dirname(__file__), "data", "uploads")
os.makedirs(UPLOAD_DIR, exist_ok=True)
MAX_IMAGE_SIZE = 10 * 1024 * 1024
MAX_FILE_SIZE = 20 * 1024 * 1024
ALLOWED_IMAGE_TYPES = {"image/jpeg", "image/png", "image/gif", "image/webp"}
ALLOWED_FILE_TYPES = {"application/pdf", "text/plain", "text/csv", "application/json", "text/markdown"}
EXPORT_URL_TTL_SECONDS = 24 * 60 * 60
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

FCM_PROJECT_ID = os.getenv("FCM_PROJECT_ID", "").strip()
FCM_ACCESS_TOKEN = os.getenv("FCM_ACCESS_TOKEN", "").strip()

APNS_AUTH_TOKEN = os.getenv("APNS_AUTH_TOKEN", "").strip()
APNS_TOPIC = os.getenv("APNS_TOPIC", "").strip()
APNS_USE_SANDBOX = os.getenv("APNS_USE_SANDBOX", "").strip().lower() in ("1", "true", "yes", "on")

APPLE_CLIENT_ID = os.getenv("APPLE_CLIENT_ID", "").strip()
APPLE_CLIENT_IDS = [v.strip() for v in os.getenv("APPLE_CLIENT_IDS", "").split(",") if v.strip()]
if APPLE_CLIENT_ID and APPLE_CLIENT_ID not in APPLE_CLIENT_IDS:
    APPLE_CLIENT_IDS.append(APPLE_CLIENT_ID)
APPLE_JWKS_URL = os.getenv("APPLE_JWKS_URL", "https://appleid.apple.com/auth/keys").strip() or "https://appleid.apple.com/auth/keys"
APPLE_JWKS_CACHE_TTL_SECONDS = max(60, int(os.getenv("APPLE_JWKS_CACHE_TTL_SECONDS", "3600")))


TIER_LEVEL = {"free": 0, "pro": 1, "max": 2}
LEVEL_TIER = {0: "free", 1: "pro", 2: "max"}
TIER_ALIASES = {
    "basic": "free",
    "plus": "pro",
    "premium": "pro",
    "enterprise": "max",
}

PERSONA_ALIASES = {
    "general": "assistant",
    "coding": "coder",
    "writing": "writer",
    "translation": "translator",
}


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

TOKEN_TTL_SECONDS = 30 * 86400
TOKEN_REFRESH_WINDOW_SECONDS = 7 * 86400

_CALL_LLM_BODY: ContextVar[Optional[Dict[str, Any]]] = ContextVar("_CALL_LLM_BODY", default=None)
_APPLE_JWKS_CACHE: Dict[str, Any] = {"fetched_at": 0, "keys": []}

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


SUPPORTED_PERSONAS: Tuple[str, ...] = tuple(PERSONA_PROMPTS.keys()) + ("custom",)


def _normalize_tier_name(tier: Any, default: str = "free") -> str:
    raw = str(tier or "").strip().lower()
    if not raw:
        return default
    normalized = TIER_ALIASES.get(raw, raw)
    if normalized in LIMITS:
        return normalized
    return default


def _normalize_persona_name(persona: Any, *, default: str = "assistant", allow_custom: bool = True) -> str:
    raw = str(persona or "").strip().lower()
    if not raw:
        return default
    normalized = PERSONA_ALIASES.get(raw, raw)
    if normalized in PERSONA_PROMPTS:
        return normalized
    if allow_custom and normalized == "custom":
        return "custom"
    return default


def _normalize_ai_config(ai_config: Dict[str, Any]) -> Dict[str, Any]:
    normalized: Dict[str, Any] = dict(ai_config or {})
    normalized["persona"] = _normalize_persona_name(
        normalized.get("persona"),
        default="assistant",
        allow_custom=True,
    )
    custom_prompt = normalized.get("custom_prompt")
    if custom_prompt is not None and not isinstance(custom_prompt, str):
        normalized.pop("custom_prompt", None)
    return normalized


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


def _ensure_export_dir() -> None:
    os.makedirs(EXPORT_DIR, exist_ok=True)


def _ensure_upload_dir() -> None:
    os.makedirs(UPLOAD_DIR, exist_ok=True)


def _safe_export_filename(*, user_id: str, export_id: str, now: int) -> str:
    safe_user = re.sub(r"[^a-zA-Z0-9_-]", "", (user_id or ""))[:32] or "user"
    safe_export = re.sub(r"[^a-zA-Z0-9_-]", "", (export_id or ""))[:12] or "export"
    return f"user_export_{safe_user}_{now}_{safe_export}.json"


def _normalize_email(email: str) -> str:
    return (email or "").strip().lower()


def _normalize_apple_name(value: Any) -> str:
    if not isinstance(value, str):
        return ""
    return " ".join(value.strip().split())


def _normalize_apple_sub(value: Any) -> str:
    if not isinstance(value, str):
        return ""
    return value.strip()


def _is_valid_apple_name(name: str) -> bool:
    return bool(name) and len(name) <= 100


def _apple_placeholder_email(sub: str, attempt: int) -> str:
    safe_sub = re.sub(r"[^a-z0-9]", "", (sub or "").lower())[:32]
    if not safe_sub:
        safe_sub = secrets.token_hex(8)
    suffix = f"_{attempt}" if attempt > 0 else ""
    return f"apple_{safe_sub}{suffix}@appleid.local"


def _gen_device_token() -> str:
    return "ocw1_" + base64.urlsafe_b64encode(secrets.token_bytes(24)).decode("utf-8").rstrip("=")


async def _mint_device_token_for_user(
    db: Any,
    *,
    user_id: str,
    tier: str,
    now: int,
    expires_at: Optional[int],
) -> str:
    tier = _normalize_tier_name(tier)
    for _ in range(5):
        candidate = _gen_device_token()
        try:
            await db.execute(
                "INSERT INTO device_tokens(token,tier,status,note,user_id,created_at,expires_at) VALUES (?,?,?,?,?,?,?)",
                (candidate, tier, "active", None, user_id, now, expires_at),
            )
            return candidate
        except sqlite3.IntegrityError:
            continue
    raise HTTPException(status_code=500, detail="failed to allocate token")


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


_MESSAGE_META_PREFIX = "[[MESSAGE_META]]"
_MESSAGE_META_SUFFIX = "[[/MESSAGE_META]]"
_MAX_EXTRACTED_TEXT = 50_000
_MAX_FILE_ATTACHMENTS_PER_MESSAGE = 10


def _normalize_file_ids(value: Any) -> List[str]:
    if value is None:
        return []
    if not isinstance(value, list):
        raise HTTPException(status_code=400, detail="file_ids must be an array of strings")

    out: List[str] = []
    seen: set[str] = set()
    for item in value:
        if not isinstance(item, str):
            raise HTTPException(status_code=400, detail="file_ids must be an array of strings")
        fid = item.strip()
        if not fid:
            raise HTTPException(status_code=400, detail="file_ids must not contain empty values")
        if fid in seen:
            continue
        seen.add(fid)
        out.append(fid)

    if len(out) > _MAX_FILE_ATTACHMENTS_PER_MESSAGE:
        raise HTTPException(
            status_code=400,
            detail=f"too many files attached (max {_MAX_FILE_ATTACHMENTS_PER_MESSAGE})",
        )
    return out


def _parse_message_content_with_meta(raw_content: Any) -> Tuple[str, Dict[str, Any]]:
    if raw_content is None:
        return "", {}
    if not isinstance(raw_content, str):
        raw_content = str(raw_content)

    if raw_content.startswith(_MESSAGE_META_PREFIX):
        start = len(_MESSAGE_META_PREFIX)
        end = raw_content.find(_MESSAGE_META_SUFFIX, start)
        if end > start:
            meta_json = raw_content[start:end]
            meta = _safe_json_loads_object(meta_json)
            body = raw_content[end + len(_MESSAGE_META_SUFFIX) :]
            return body, meta
    return raw_content, {}


def _encode_message_content_with_meta(text: str, *, file_ids: List[str], files: List[Dict[str, Any]]) -> str:
    clean_ids = _normalize_file_ids(file_ids)
    if not clean_ids:
        return text

    file_cards: List[Dict[str, Any]] = []
    for f in files:
        file_cards.append(
            {
                "id": str(f.get("id") or ""),
                "name": str(f.get("original_name") or ""),
                "size": int(f.get("size_bytes") or 0),
                "type": str(f.get("mime_type") or ""),
                "url": f"/v1/files/{f.get('id')}",
            }
        )

    meta = {"file_ids": clean_ids, "files": file_cards}
    return f"{_MESSAGE_META_PREFIX}{json.dumps(meta, ensure_ascii=False)}{_MESSAGE_META_SUFFIX}{text}"


def _is_likely_utf8_text(file_bytes: bytes) -> bool:
    if not file_bytes:
        return True
    sample = file_bytes[:4096]
    if b"\x00" in sample:
        return False
    try:
        sample.decode("utf-8")
        return True
    except UnicodeDecodeError:
        return False


def _detect_mime_type(file_bytes: bytes, original_name: str) -> str:
    if file_bytes.startswith(b"\xFF\xD8\xFF"):
        return "image/jpeg"
    if file_bytes.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if file_bytes.startswith(b"GIF87a") or file_bytes.startswith(b"GIF89a"):
        return "image/gif"
    if len(file_bytes) >= 12 and file_bytes[:4] == b"RIFF" and file_bytes[8:12] == b"WEBP":
        return "image/webp"
    if file_bytes.startswith(b"%PDF-"):
        return "application/pdf"

    suffix = Path(original_name or "").suffix.lower()
    if suffix in (".md", ".markdown"):
        return "text/markdown"
    if suffix == ".csv":
        return "text/csv"
    if suffix == ".json":
        return "application/json"
    if suffix == ".txt":
        return "text/plain"

    if _is_likely_utf8_text(file_bytes):
        stripped = file_bytes[:2048].lstrip()
        if stripped.startswith((b"{", b"[")):
            return "application/json"
        guessed_type, _ = mimetypes.guess_type(original_name or "")
        if guessed_type in ALLOWED_FILE_TYPES:
            return guessed_type
        return "text/plain"

    # For opaque binary payloads, do not trust filename extension/content-type hints.
    return "application/octet-stream"


def _guess_extension(mime_type: str, original_name: str) -> str:
    suffix = Path(original_name or "").suffix.lower()
    if suffix:
        return suffix

    preferred = {
        "image/jpeg": ".jpg",
        "image/png": ".png",
        "image/gif": ".gif",
        "image/webp": ".webp",
        "application/pdf": ".pdf",
        "text/plain": ".txt",
        "text/csv": ".csv",
        "application/json": ".json",
        "text/markdown": ".md",
    }
    if mime_type in preferred:
        return preferred[mime_type]
    guessed = mimetypes.guess_extension(mime_type or "")
    if guessed == ".jpe":
        return ".jpg"
    return guessed or ".bin"


def _extract_text_from_pdf(file_bytes: bytes) -> str:
    try:
        import PyPDF2  # type: ignore
    except Exception:
        return ""

    try:
        reader = PyPDF2.PdfReader(io.BytesIO(file_bytes))
    except Exception:
        return ""

    parts: List[str] = []
    total = 0
    for page in getattr(reader, "pages", []):
        if total >= _MAX_EXTRACTED_TEXT:
            break
        try:
            page_text = page.extract_text() or ""
        except Exception:
            page_text = ""
        if not page_text:
            continue
        page_text = page_text.replace("\x00", "").strip()
        if not page_text:
            continue
        remaining = _MAX_EXTRACTED_TEXT - total
        chunk = page_text[:remaining]
        parts.append(chunk)
        total += len(chunk)

    return "\n\n".join(parts)[:_MAX_EXTRACTED_TEXT]


def _extract_text_from_file(file_bytes: bytes, mime_type: str) -> Optional[str]:
    if mime_type in ALLOWED_IMAGE_TYPES:
        return None
    if mime_type == "application/pdf":
        text = _extract_text_from_pdf(file_bytes)
        return text or ""
    if mime_type in {"text/plain", "text/csv", "application/json", "text/markdown"}:
        return file_bytes.decode("utf-8", errors="replace")[:_MAX_EXTRACTED_TEXT]
    return None


def _parse_content_disposition_params(header_value: str) -> Dict[str, str]:
    params: Dict[str, str] = {}
    if not isinstance(header_value, str):
        return params
    for token in header_value.split(";")[1:]:
        token = token.strip()
        if "=" not in token:
            continue
        key, value = token.split("=", 1)
        key = key.strip().lower()
        value = value.strip()
        if value.startswith('"') and value.endswith('"') and len(value) >= 2:
            value = value[1:-1]
        params[key] = value
    return params


def _extract_file_field_from_multipart(content_type: str, body: bytes) -> Tuple[str, bytes]:
    if not isinstance(content_type, str) or "multipart/form-data" not in content_type.lower():
        raise HTTPException(status_code=400, detail="content-type must be multipart/form-data")

    boundary_match = re.search(r'boundary=(?:"([^"]+)"|([^;]+))', content_type, flags=re.IGNORECASE)
    if not boundary_match:
        raise HTTPException(status_code=400, detail="missing multipart boundary")
    boundary = boundary_match.group(1) or boundary_match.group(2) or ""
    boundary = boundary.strip()
    if not boundary:
        raise HTTPException(status_code=400, detail="invalid multipart boundary")

    delimiter = b"--" + boundary.encode("utf-8", errors="ignore")
    for chunk in body.split(delimiter):
        part = chunk.strip()
        if not part or part == b"--":
            continue
        if part.endswith(b"--"):
            part = part[:-2]
        part = part.strip(b"\r\n")
        if not part:
            continue

        header_block, sep, payload = part.partition(b"\r\n\r\n")
        if not sep:
            continue

        headers: Dict[str, str] = {}
        for raw_line in header_block.split(b"\r\n"):
            line = raw_line.decode("latin-1", errors="ignore")
            if ":" not in line:
                continue
            k, v = line.split(":", 1)
            headers[k.strip().lower()] = v.strip()

        disposition = headers.get("content-disposition", "")
        if "form-data" not in disposition.lower():
            continue
        params = _parse_content_disposition_params(disposition)
        if params.get("name") != "file":
            continue

        filename = os.path.basename(str(params.get("filename") or "upload.bin").strip() or "upload.bin")
        if payload.endswith(b"\r\n"):
            file_bytes = payload[:-2]
        else:
            file_bytes = payload
        if not file_bytes:
            raise HTTPException(status_code=400, detail="empty file not allowed")
        return filename, file_bytes

    raise HTTPException(status_code=400, detail="multipart field 'file' is required")


def _compose_user_text_with_file_context(user_text: str, files: List[Dict[str, Any]]) -> str:
    text_blocks: List[str] = []
    for f in files:
        extracted = f.get("extracted_text")
        if not isinstance(extracted, str):
            continue
        extracted = extracted.strip()
        if not extracted:
            continue
        name = str(f.get("original_name") or "file")
        text_blocks.append(f"[File: {name}]\n{extracted}")

    if text_blocks:
        context_text = "\n\n".join(text_blocks)
        if user_text:
            return f"{context_text}\n\n{user_text}"
        return context_text
    return user_text


def _build_user_content_for_model(user_text: str, files: List[Dict[str, Any]]) -> Any:
    text_with_context = _compose_user_text_with_file_context(user_text, files)
    image_parts: List[Dict[str, Any]] = []
    for f in files:
        mime_type = str(f.get("mime_type") or "")
        if mime_type not in ALLOWED_IMAGE_TYPES:
            continue
        stored_path = str(f.get("stored_path") or "")
        if not stored_path:
            continue
        try:
            with open(stored_path, "rb") as fh:
                raw = fh.read()
        except Exception:
            continue
        if not raw:
            continue
        image_parts.append(
            {
                "type": "image_url",
                "image_url": {"url": f"data:{mime_type};base64,{base64.b64encode(raw).decode('ascii')}"},
            }
        )

    if not image_parts:
        return text_with_context

    text_part = text_with_context.strip() if isinstance(text_with_context, str) else ""
    if not text_part:
        text_part = "Please analyze the attached image(s)."
    return [{"type": "text", "text": text_part}] + image_parts


async def _fetch_conversation_files_by_ids(db: Any, conversation_id: str, file_ids: List[str]) -> List[Dict[str, Any]]:
    if not file_ids:
        return []

    placeholders = ",".join("?" for _ in file_ids)
    query = (
        "SELECT id,conversation_id,original_name,stored_path,sha256_hash,mime_type,size_bytes,extracted_text,created_at "
        f"FROM conversation_files WHERE conversation_id=? AND id IN ({placeholders})"
    )
    async with db.execute(query, (conversation_id, *file_ids)) as cur:
        rows = await cur.fetchall()
    row_map: Dict[str, Dict[str, Any]] = {str(r["id"]): dict(r) for r in rows}

    missing = [fid for fid in file_ids if fid not in row_map]
    if missing:
        raise HTTPException(status_code=400, detail=f"unknown file_id(s): {', '.join(missing)}")

    return [row_map[fid] for fid in file_ids]


async def _load_file_map_for_messages(db: Any, conversation_id: str, rows: List[Any]) -> Dict[str, Dict[str, Any]]:
    all_file_ids: List[str] = []
    seen: set[str] = set()

    for row in rows:
        _, meta = _parse_message_content_with_meta(row["content"])
        for fid in _normalize_file_ids(meta.get("file_ids")):
            if fid in seen:
                continue
            seen.add(fid)
            all_file_ids.append(fid)

    if not all_file_ids:
        return {}

    placeholders = ",".join("?" for _ in all_file_ids)
    query = (
        "SELECT id,conversation_id,original_name,stored_path,sha256_hash,mime_type,size_bytes,extracted_text,created_at "
        f"FROM conversation_files WHERE conversation_id=? AND id IN ({placeholders})"
    )
    async with db.execute(query, (conversation_id, *all_file_ids)) as cur:
        fetched = await cur.fetchall()
    return {str(r["id"]): dict(r) for r in fetched}


def _build_oai_messages_from_rows(rows: List[Any], file_map: Dict[str, Dict[str, Any]]) -> List[Dict[str, Any]]:
    oai_messages: List[Dict[str, Any]] = []
    for row in rows:
        role = str(row["role"])
        text, meta = _parse_message_content_with_meta(row["content"])
        file_ids = _normalize_file_ids(meta.get("file_ids"))
        if role == "user" and file_ids:
            files = [file_map[fid] for fid in file_ids if fid in file_map]
            content = _build_user_content_for_model(text, files)
        else:
            content = text
        oai_messages.append({"role": role, "content": content})
    return oai_messages


def _persona_system_prompt(ai_config: Dict[str, Any]) -> Optional[str]:
    persona = _normalize_persona_name(ai_config.get("persona"), default="assistant", allow_custom=True)

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
    _ensure_export_dir()
    _ensure_upload_dir()
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
              updated_at INTEGER,
              last_refresh_at INTEGER
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
        await db.execute(
            """
            CREATE TABLE IF NOT EXISTS push_tokens (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              user_id TEXT NOT NULL REFERENCES users(id),
              platform TEXT NOT NULL CHECK (platform IN ('ios','android')),
              push_token TEXT NOT NULL,
              created_at INTEGER NOT NULL
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
        # Migration for existing DBs: add last_refresh_at to users.
        try:
            await db.execute("ALTER TABLE users ADD COLUMN last_refresh_at INTEGER")
        except Exception:
            pass
        # Migration for existing DBs: add apple_id to users.
        try:
            await db.execute("ALTER TABLE users ADD COLUMN apple_id TEXT")
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
        await db.execute(
            """
            CREATE TABLE IF NOT EXISTS conversation_files (
              id TEXT PRIMARY KEY,
              conversation_id TEXT NOT NULL,
              original_name TEXT NOT NULL,
              stored_path TEXT NOT NULL,
              sha256_hash TEXT NOT NULL,
              mime_type TEXT NOT NULL,
              size_bytes INTEGER NOT NULL,
              extracted_text TEXT,
              created_at INTEGER NOT NULL,
              FOREIGN KEY (conversation_id) REFERENCES conversations(id)
            )
            """
        )
        await db.execute("CREATE INDEX IF NOT EXISTS idx_conversations_token_updated ON conversations(device_token, updated_at)")
        await db.execute("CREATE INDEX IF NOT EXISTS idx_messages_conv_created ON messages(conversation_id, created_at)")
        await db.execute("CREATE INDEX IF NOT EXISTS idx_conversation_files_conv_created ON conversation_files(conversation_id, created_at DESC)")
        await db.execute(
            """
            CREATE TABLE IF NOT EXISTS analytics_events (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              event_name TEXT NOT NULL,
              properties TEXT NOT NULL DEFAULT '{}',
              user_id TEXT,
              timestamp INTEGER NOT NULL
            )
            """
        )
        await db.execute("CREATE INDEX IF NOT EXISTS idx_analytics_events_ts ON analytics_events(timestamp)")
        await db.execute("CREATE INDEX IF NOT EXISTS idx_analytics_events_event_ts ON analytics_events(event_name, timestamp)")

        await db.execute(
            """
            CREATE TABLE IF NOT EXISTS crash_reports (
              id TEXT PRIMARY KEY,
              device_token TEXT,
              platform TEXT,
              app_version TEXT,
              device_model TEXT,
              os_version TEXT,
              stacktrace TEXT,
              user_action TEXT,
              fatal INTEGER DEFAULT 1,
              status TEXT DEFAULT 'new',
              created_at INTEGER NOT NULL
            )
            """
        )
        await db.execute("CREATE INDEX IF NOT EXISTS idx_crash_reports_status ON crash_reports(status, created_at)")
        await db.execute("CREATE INDEX IF NOT EXISTS idx_device_tokens_user_id ON device_tokens(user_id)")
        await db.execute("CREATE INDEX IF NOT EXISTS idx_push_tokens_user_id ON push_tokens(user_id)")
        await db.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_push_tokens_platform_token ON push_tokens(platform, push_token)")
        await db.execute("CREATE INDEX IF NOT EXISTS idx_users_email ON users(email)")
        await db.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_users_apple_id ON users(apple_id)")
        await db.execute(
            """
            CREATE TABLE IF NOT EXISTS user_exports (
              id TEXT PRIMARY KEY,
              user_id TEXT NOT NULL REFERENCES users(id),
              download_token TEXT NOT NULL UNIQUE,
              file_path TEXT NOT NULL,
              created_at INTEGER NOT NULL,
              expires_at INTEGER NOT NULL
            )
            """
        )
        await db.execute("CREATE INDEX IF NOT EXISTS idx_user_exports_user_created ON user_exports(user_id, created_at DESC)")
        await db.execute("CREATE INDEX IF NOT EXISTS idx_user_exports_expires ON user_exports(expires_at)")
        # Normalize legacy tier aliases/casing to canonical free/pro/max in-place.
        for legacy, canonical in (
            ("free", "free"),
            ("pro", "pro"),
            ("max", "max"),
            ("basic", "free"),
            ("plus", "pro"),
            ("premium", "pro"),
            ("enterprise", "max"),
        ):
            await db.execute(
                "UPDATE users SET tier=? WHERE lower(trim(coalesce(tier,'')))=?",
                (canonical, legacy),
            )
            await db.execute(
                "UPDATE device_tokens SET tier=? WHERE lower(trim(coalesce(tier,'')))=?",
                (canonical, legacy),
            )
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
                d["tier"] = _normalize_tier_name(d.get("tier"))
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
                    d["tier"] = _normalize_tier_name(d.get("tier"))
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
                    d["tier"] = _normalize_tier_name(d.get("tier"))
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
            if not row:
                return None
            data = dict(row)
            data["tier"] = _normalize_tier_name(data.get("tier"))
            return data


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
            if not row:
                return None
            data = dict(row)
            data["tier"] = _normalize_tier_name(data.get("tier"))
            return data


async def _get_user_row_by_apple_id(apple_id: str) -> Optional[Dict[str, Any]]:
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
            WHERE apple_id=?
            """,
            (apple_id,),
        ) as cur:
            row = await cur.fetchone()
            if not row:
                return None
            data = dict(row)
            data["tier"] = _normalize_tier_name(data.get("tier"))
            return data


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


async def _cleanup_expired_exports(now: int) -> None:
    expired_files: List[str] = []
    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        try:
            async with db.execute(
                "SELECT file_path FROM user_exports WHERE expires_at <= ?",
                (int(now),),
            ) as cur:
                rows = await cur.fetchall()
        except sqlite3.OperationalError:
            # Table may not exist in older DB before migration/startup.
            return
        expired_files = [str(r["file_path"]) for r in rows if r and r["file_path"]]
        if expired_files:
            await db.execute("DELETE FROM user_exports WHERE expires_at <= ?", (int(now),))
            await db.commit()

    for file_path in expired_files:
        with suppress(OSError):
            os.remove(file_path)


async def _build_user_export_payload(user: Dict[str, Any]) -> Dict[str, Any]:
    user_id = str(user["id"])
    ai_config = _normalize_ai_config(_safe_json_loads_object(user.get("ai_config")))
    now = int(time.time())

    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        db.row_factory = aiosqlite.Row

        async with db.execute(
            """
            SELECT token,tier,status,created_at,expires_at
            FROM device_tokens
            WHERE user_id=?
            ORDER BY created_at ASC, rowid ASC
            """,
            (user_id,),
        ) as cur:
            token_rows = await cur.fetchall()

        async with db.execute(
            """
            SELECT platform,push_token,created_at
            FROM push_tokens
            WHERE user_id=?
            ORDER BY created_at DESC, id DESC
            """,
            (user_id,),
        ) as cur:
            push_rows = await cur.fetchall()

        async with db.execute(
            """
            SELECT c.id,c.device_token,c.title,c.created_at,c.updated_at
            FROM conversations c
            JOIN device_tokens dt ON dt.token = c.device_token
            WHERE dt.user_id = ?
            ORDER BY c.created_at ASC, c.rowid ASC
            """,
            (user_id,),
        ) as cur:
            convo_rows = await cur.fetchall()

        async with db.execute(
            """
            SELECT m.id,m.conversation_id,m.role,m.content,m.created_at
            FROM messages m
            JOIN conversations c ON c.id = m.conversation_id
            JOIN device_tokens dt ON dt.token = c.device_token
            WHERE dt.user_id = ?
            ORDER BY m.created_at ASC, m.rowid ASC
            """,
            (user_id,),
        ) as cur:
            msg_rows = await cur.fetchall()

    messages_by_conversation: Dict[str, List[Dict[str, Any]]] = {}
    for row in msg_rows:
        cid = str(row["conversation_id"])
        messages_by_conversation.setdefault(cid, []).append(
            {
                "id": str(row["id"]),
                "role": str(row["role"]),
                "content": str(row["content"]),
                "created_at": int(row["created_at"] or 0),
            }
        )

    conversations: List[Dict[str, Any]] = []
    for row in convo_rows:
        cid = str(row["id"])
        conversations.append(
            {
                "id": cid,
                "title": row["title"],
                "device_token": str(row["device_token"]),
                "created_at": int(row["created_at"] or 0),
                "updated_at": int(row["updated_at"] or 0),
                "messages": messages_by_conversation.get(cid, []),
            }
        )

    return {
        "export_version": 1,
        "generated_at": now,
        "account": {
            "user_id": user_id,
            "email": user.get("email") or "",
            "name": user.get("name") or "",
            "avatar_url": user.get("avatar_url"),
            "tier": _normalize_tier_name(user.get("tier")),
            "language": user.get("language") or "auto",
            "apple_id": user.get("apple_id"),
            "created_at": user.get("created_at"),
            "updated_at": user.get("updated_at"),
        },
        "settings": {
            "language": user.get("language") or "auto",
            "ai_config": ai_config,
            "push_tokens": [
                {
                    "platform": str(r["platform"]),
                    "push_token": str(r["push_token"]),
                    "created_at": int(r["created_at"] or 0),
                }
                for r in push_rows
            ],
        },
        "device_tokens": [
            {
                "token": str(r["token"]),
                "tier": _normalize_tier_name(r["tier"]),
                "status": str(r["status"]),
                "created_at": int(r["created_at"] or 0),
                "expires_at": int(r["expires_at"]) if isinstance(r["expires_at"], (int, float)) else None,
            }
            for r in token_rows
        ],
        "conversations": conversations,
        "summary": {
            "conversation_count": len(conversations),
            "message_count": len(msg_rows),
        },
    }


async def _get_tier_for_token(token: str) -> str:
    row = await _get_token_row(token)
    if not row:
        raise HTTPException(status_code=401, detail="invalid token")
    if row.get("status") != "active":
        raise HTTPException(status_code=403, detail="token disabled")
    return _normalize_tier_name(row.get("tier"))


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


def _coerce_event_timestamp(value: Any, fallback: int) -> int:
    ts = int(fallback)
    if isinstance(value, (int, float)):
        ts = int(value)
    elif isinstance(value, str):
        v = value.strip()
        if v:
            try:
                ts = int(float(v))
            except Exception:
                pass

    # Accept milliseconds from clients and normalize to epoch seconds.
    if ts > 10_000_000_000:
        ts = ts // 1000
    if ts <= 0:
        ts = int(fallback)
    return ts


def _require_upstream_key(provider: str) -> None:
    if provider == "deepseek" and not DEEPSEEK_API_KEY:
        raise HTTPException(status_code=500, detail="missing DEEPSEEK_API_KEY")
    if provider == "kimi" and not KIMI_API_KEY:
        raise HTTPException(status_code=500, detail="missing KIMI_API_KEY")
    if provider == "claude" and not ANTHROPIC_API_KEY:
        raise HTTPException(status_code=500, detail="missing ANTHROPIC_API_KEY")


def _apple_token_audiences(aud: Any) -> List[str]:
    if isinstance(aud, str):
        v = aud.strip()
        return [v] if v else []
    if isinstance(aud, list):
        out: List[str] = []
        for item in aud:
            if isinstance(item, str):
                v = item.strip()
                if v:
                    out.append(v)
        return out
    return []


def _is_expected_apple_audience(aud: Any) -> bool:
    if not APPLE_CLIENT_IDS:
        return False
    audiences = _apple_token_audiences(aud)
    if not audiences:
        return False
    allowed = set(APPLE_CLIENT_IDS)
    return any(a in allowed for a in audiences)


async def _fetch_apple_jwks(*, force_refresh: bool = False) -> List[Dict[str, Any]]:
    now = int(time.time())
    cached_at = int(_APPLE_JWKS_CACHE.get("fetched_at") or 0)
    cached_keys = _APPLE_JWKS_CACHE.get("keys") or []
    if not force_refresh and isinstance(cached_keys, list) and cached_keys and (now - cached_at) < APPLE_JWKS_CACHE_TTL_SECONDS:
        return [k for k in cached_keys if isinstance(k, dict)]

    timeout = httpx.Timeout(10.0, connect=5.0)
    try:
        async with httpx.AsyncClient(timeout=timeout) as client:
            resp = await client.get(APPLE_JWKS_URL, headers={"Accept": "application/json"})
    except Exception:
        raise HTTPException(status_code=502, detail="failed to fetch Apple public keys")

    if resp.status_code >= 400:
        raise HTTPException(status_code=502, detail="failed to fetch Apple public keys")

    try:
        payload = resp.json()
    except Exception:
        raise HTTPException(status_code=502, detail="invalid Apple keys response")

    keys = payload.get("keys") if isinstance(payload, dict) else None
    if not isinstance(keys, list):
        raise HTTPException(status_code=502, detail="invalid Apple keys response")
    normalized = [k for k in keys if isinstance(k, dict)]
    _APPLE_JWKS_CACHE["fetched_at"] = now
    _APPLE_JWKS_CACHE["keys"] = normalized
    return normalized


def _find_apple_jwk(keys: List[Dict[str, Any]], kid: str) -> Optional[Dict[str, Any]]:
    for key in keys:
        if not isinstance(key, dict):
            continue
        if key.get("kid") == kid and key.get("kty") == "RSA":
            return key
    return None


async def _verify_apple_identity_token(identity_token: str) -> Dict[str, Any]:
    if not APPLE_CLIENT_IDS:
        raise HTTPException(status_code=500, detail="Apple auth is not configured on server")

    token = (identity_token or "").strip()
    if not token:
        raise HTTPException(status_code=400, detail="identity_token required")

    try:
        header = jwt.get_unverified_header(token)
    except Exception:
        raise HTTPException(status_code=400, detail="invalid Apple identity token")

    kid = header.get("kid")
    alg = header.get("alg")
    if not isinstance(kid, str) or not kid.strip():
        raise HTTPException(status_code=400, detail="invalid Apple identity token header")
    if alg != "RS256":
        raise HTTPException(status_code=400, detail="unsupported Apple identity token algorithm")

    keys = await _fetch_apple_jwks(force_refresh=False)
    jwk = _find_apple_jwk(keys, kid.strip())
    if not jwk:
        keys = await _fetch_apple_jwks(force_refresh=True)
        jwk = _find_apple_jwk(keys, kid.strip())
    if not jwk:
        raise HTTPException(status_code=401, detail="Apple public key not found for token")

    try:
        public_key = jwt.algorithms.RSAAlgorithm.from_jwk(json.dumps(jwk))
    except Exception:
        raise HTTPException(status_code=500, detail="invalid Apple public key")

    try:
        payload = jwt.decode(
            token,
            key=public_key,
            algorithms=["RS256"],
            issuer="https://appleid.apple.com",
            options={
                "verify_aud": False,
                "require": ["iss", "aud", "sub", "exp", "iat"],
            },
        )
    except InvalidTokenError:
        raise HTTPException(status_code=401, detail="invalid Apple identity token")

    if not _is_expected_apple_audience(payload.get("aud")):
        raise HTTPException(status_code=401, detail="Apple token audience mismatch")
    sub = _normalize_apple_sub(payload.get("sub"))
    if not sub:
        raise HTTPException(status_code=401, detail="Apple token missing subject")

    return payload


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


@app.middleware("http")
async def _global_rate_limit_middleware(request: Request, call_next):
    try:
        await _enforce_rate_limit(request)
    except HTTPException as exc:
        return JSONResponse(status_code=exc.status_code, content={"detail": exc.detail})
    return await call_next(request)


@app.on_event("startup")
async def _startup() -> None:
    await _init_db()


@app.get("/health")
async def health() -> Dict[str, Any]:
    return {"ok": True, "ts": int(time.time())}


@app.post("/v1/analytics/events")
async def post_analytics_events(request: Request) -> Any:
    try:
        body = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="request body must be valid JSON")

    events: List[Any] = []
    if isinstance(body, list):
        events = body
    elif isinstance(body, dict):
        if isinstance(body.get("events"), list):
            events = body.get("events") or []
        elif ("event" in body) or ("event_name" in body):
            events = [body]
        else:
            raise HTTPException(status_code=400, detail="body must be an event array or object with events")
    else:
        raise HTTPException(status_code=400, detail="body must be an event array or object with events")

    if not events:
        return {"ok": True, "stored": 0, "dropped": 0}

    token = _parse_bearer(request.headers.get("authorization"))
    resolved_user_id: Optional[str] = None
    if token:
        user = await _get_user_row_for_token_optional(token)
        if user and user.get("id"):
            resolved_user_id = str(user["id"])

    now = int(time.time())
    rows: List[Tuple[str, str, Optional[str], int]] = []
    dropped = 0
    for raw in events:
        if not isinstance(raw, dict):
            dropped += 1
            continue

        event_name = raw.get("event")
        if not isinstance(event_name, str) or not event_name.strip():
            event_name = raw.get("event_name")
        if not isinstance(event_name, str) or not event_name.strip():
            dropped += 1
            continue
        event_name = event_name.strip()[:128]

        properties = raw.get("properties")
        if not isinstance(properties, dict):
            properties = {}
        try:
            properties_json = json.dumps(properties, ensure_ascii=False)
        except Exception:
            properties_json = "{}"

        user_id = resolved_user_id
        if not user_id:
            raw_user_id = raw.get("user_id")
            if isinstance(raw_user_id, str) and raw_user_id.strip():
                user_id = raw_user_id.strip()

        ts = _coerce_event_timestamp(raw.get("timestamp"), fallback=now)
        rows.append((event_name, properties_json, user_id, ts))

    if not rows:
        raise HTTPException(status_code=400, detail="no valid analytics events")

    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        await db.executemany(
            "INSERT INTO analytics_events(event_name,properties,user_id,timestamp) VALUES (?,?,?,?)",
            rows,
        )
        await db.commit()

    return {"ok": True, "stored": len(rows), "dropped": dropped}


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
    expires_at = now + TOKEN_TTL_SECONDS
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

        token = await _mint_device_token_for_user(
            db,
            user_id=user_id,
            tier=tier,
            now=now,
            expires_at=expires_at,
        )

        await db.commit()

    return {"user_id": user_id, "token": token, "tier": tier, "created_at": now, "expires_at": expires_at}


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
    expires_at = now + TOKEN_TTL_SECONDS
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

    tier = _normalize_tier_name(user.get("tier"))

    user_id = str(user["id"])

    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        token = await _mint_device_token_for_user(
            db,
            user_id=user_id,
            tier=tier,
            now=now,
            expires_at=expires_at,
        )

        await db.commit()

    # Successful login clears failures for this IP.
    _LOGIN_FAILURES.pop(ip, None)

    ai_config = _normalize_ai_config(_safe_json_loads_object(user.get("ai_config")))
    return {
        "user_id": user_id,
        "token": token,
        "tier": tier,
        "name": user.get("name") or "",
        "ai_config": ai_config,
        "expires_at": expires_at,
    }


@app.post("/v1/auth/apple")
async def auth_apple(request: Request) -> Any:
    try:
        body = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="request body must be valid JSON")
    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="invalid json body")

    identity_token = body.get("identity_token")
    user_identifier_raw = body.get("user_identifier")
    email_raw = body.get("email")
    full_name_raw = body.get("full_name")

    if not isinstance(identity_token, str) or not identity_token.strip():
        raise HTTPException(status_code=400, detail="identity_token required")
    if user_identifier_raw is not None and not isinstance(user_identifier_raw, str):
        raise HTTPException(status_code=400, detail="user_identifier must be a string")
    if email_raw is not None and not isinstance(email_raw, str):
        raise HTTPException(status_code=400, detail="email must be a string")
    if full_name_raw is not None and not isinstance(full_name_raw, str):
        raise HTTPException(status_code=400, detail="full_name must be a string")

    payload = await _verify_apple_identity_token(identity_token)
    apple_id = _normalize_apple_sub(payload.get("sub"))
    if not apple_id:
        raise HTTPException(status_code=401, detail="Apple token missing subject")

    user_identifier = _normalize_apple_sub(user_identifier_raw)
    if user_identifier and user_identifier != apple_id:
        raise HTTPException(status_code=400, detail="user_identifier does not match Apple token subject")

    email_norm = ""
    for candidate in (email_raw, payload.get("email")):
        if isinstance(candidate, str) and candidate.strip():
            candidate_norm = _normalize_email(candidate)
            if _is_valid_email(candidate_norm):
                email_norm = candidate_norm
                break

    full_name = _normalize_apple_name(full_name_raw)
    if not full_name:
        given_name = _normalize_apple_name(body.get("given_name"))
        family_name = _normalize_apple_name(body.get("family_name"))
        full_name = _normalize_apple_name(f"{given_name} {family_name}".strip())
    if full_name and not _is_valid_apple_name(full_name):
        raise HTTPException(status_code=400, detail="full_name too long")

    now = int(time.time())
    expires_at = now + TOKEN_TTL_SECONDS
    user: Optional[Dict[str, Any]] = None
    created = False

    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        db.row_factory = aiosqlite.Row

        async with db.execute(
            """
            SELECT id,email,password_hash,apple_id,name,avatar_url,tier,ai_config,language,created_at,updated_at
            FROM users
            WHERE apple_id=?
            """,
            (apple_id,),
        ) as cur:
            row = await cur.fetchone()
            user = dict(row) if row else None

        if user and full_name and not _normalize_apple_name(user.get("name")):
            await db.execute(
                "UPDATE users SET name=?, updated_at=? WHERE id=?",
                (full_name, now, str(user["id"])),
            )
            user["name"] = full_name
            user["updated_at"] = now

        if not user and email_norm:
            async with db.execute(
                """
                SELECT id,email,password_hash,apple_id,name,avatar_url,tier,ai_config,language,created_at,updated_at
                FROM users
                WHERE email=?
                """,
                (email_norm,),
            ) as cur:
                row = await cur.fetchone()
                if row:
                    user = dict(row)
                    linked_apple_id = _normalize_apple_sub(user.get("apple_id"))
                    if linked_apple_id and linked_apple_id != apple_id:
                        raise HTTPException(status_code=409, detail="email already linked to another Apple account")

                    updates: List[str] = []
                    params: List[Any] = []
                    if not linked_apple_id:
                        updates.append("apple_id=?")
                        params.append(apple_id)
                        user["apple_id"] = apple_id
                    if full_name and not _normalize_apple_name(user.get("name")):
                        updates.append("name=?")
                        params.append(full_name)
                        user["name"] = full_name
                    if updates:
                        updates.append("updated_at=?")
                        params.append(now)
                        params.append(str(user["id"]))
                        await db.execute(f"UPDATE users SET {', '.join(updates)} WHERE id=?", tuple(params))
                        user["updated_at"] = now

        if not user:
            if not email_norm:
                for attempt in range(20):
                    candidate_email = _apple_placeholder_email(apple_id, attempt)
                    async with db.execute("SELECT 1 FROM users WHERE email=?", (candidate_email,)) as cur:
                        exists = await cur.fetchone()
                    if not exists:
                        email_norm = candidate_email
                        break
            if not email_norm:
                raise HTTPException(status_code=500, detail="failed to generate email for Apple account")
            if not _is_valid_email(email_norm):
                raise HTTPException(status_code=400, detail="invalid email for Apple account")

            user_id = str(uuid.uuid4())
            tier = "free"
            try:
                await db.execute(
                    """
                    INSERT INTO users(id,email,password_hash,apple_id,name,tier,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,?,?)
                    """,
                    (user_id, email_norm, None, apple_id, full_name, tier, now, now),
                )
                user = {
                    "id": user_id,
                    "email": email_norm,
                    "password_hash": None,
                    "apple_id": apple_id,
                    "name": full_name,
                    "avatar_url": None,
                    "tier": tier,
                    "ai_config": "{}",
                    "language": "auto",
                    "created_at": now,
                    "updated_at": now,
                }
                created = True
            except sqlite3.IntegrityError:
                # Race condition fallback: lookup newly linked/created account.
                user = await _get_user_row_by_apple_id(apple_id)
                if not user and email_norm:
                    user = await _get_user_row_by_email(email_norm)
                if not user:
                    raise HTTPException(status_code=409, detail="Apple account already registered")

        if not user:
            raise HTTPException(status_code=500, detail="failed to resolve Apple user")

        tier = _normalize_tier_name(user.get("tier"))

        token = await _mint_device_token_for_user(
            db,
            user_id=str(user["id"]),
            tier=tier,
            now=now,
            expires_at=expires_at,
        )
        await db.commit()

    ai_config = _normalize_ai_config(_safe_json_loads_object(user.get("ai_config")))
    return {
        "user_id": str(user["id"]),
        "token": token,
        "tier": tier,
        "name": user.get("name") or "",
        "ai_config": ai_config,
        "created": created,
        "expires_at": expires_at,
    }


@app.post("/v1/auth/refresh")
async def auth_refresh(request: Request) -> Any:
    old_token = _require_device_token(request)
    now = int(time.time())

    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        try:
            async with db.execute(
                "SELECT token,tier,status,user_id,expires_at FROM device_tokens WHERE token=?",
                (old_token,),
            ) as cur:
                token_row = await cur.fetchone()
        except sqlite3.OperationalError:
            token_row = None

        if not token_row:
            raise HTTPException(status_code=401, detail="invalid token")
        if (token_row["status"] or "") != "active":
            raise HTTPException(status_code=403, detail="token disabled")
        user_id = token_row["user_id"]
        if not user_id:
            raise HTTPException(status_code=401, detail="token not associated with user")

        exp: Any = token_row["expires_at"]
        if not isinstance(exp, int) or exp <= 0:
            raise HTTPException(status_code=400, detail="token is not refreshable")
        if now >= exp:
            raise HTTPException(status_code=401, detail="token expired")
        if (exp - now) >= TOKEN_REFRESH_WINDOW_SECONDS:
            raise HTTPException(status_code=400, detail="refresh only allowed in the last 7 days")

        async with db.execute("SELECT tier FROM users WHERE id=?", (str(user_id),)) as cur:
            user_row = await cur.fetchone()
        if not user_row:
            raise HTTPException(status_code=401, detail="user not found")
        tier = _normalize_tier_name(user_row["tier"] or token_row["tier"] or "free")

        expires_at = now + TOKEN_TTL_SECONDS
        new_token = await _mint_device_token_for_user(
            db,
            user_id=str(user_id),
            tier=tier,
            now=now,
            expires_at=expires_at,
        )

        # Preserve user data continuity and rotate away from the old token.
        await db.execute("UPDATE conversations SET device_token=? WHERE device_token=?", (new_token, old_token))
        await db.execute("UPDATE usage_daily SET token=? WHERE token=?", (new_token, old_token))
        await db.execute("UPDATE crash_reports SET device_token=? WHERE device_token=?", (new_token, old_token))
        await db.execute(
            "UPDATE device_tokens SET status='disabled', note=?, expires_at=? WHERE token=?",
            ("rotated_by_refresh", now, old_token),
        )
        await db.execute(
            "UPDATE users SET last_refresh_at=?, updated_at=? WHERE id=?",
            (now, now, str(user_id)),
        )
        await db.commit()

    return {"token": new_token, "tier": tier, "expires_at": expires_at}


@app.get("/v1/user/profile")
async def user_get_profile(request: Request) -> Any:
    _, user = await _require_user(request)
    return {
        "user_id": user["id"],
        "email": user["email"],
        "name": user.get("name") or "",
        "avatar_url": user.get("avatar_url"),
        "tier": _normalize_tier_name(user.get("tier")),
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

    tier = _normalize_tier_name(user.get("tier"))

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
    ai_config = _normalize_ai_config(_safe_json_loads_object(user.get("ai_config")))
    return {"ai_config": ai_config, "personas": list(SUPPORTED_PERSONAS)}


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

    allowed_personas = set(SUPPORTED_PERSONAS)
    if persona is not None:
        if not isinstance(persona, str) or not persona.strip():
            raise HTTPException(status_code=400, detail="persona must be a string")
        persona = _normalize_persona_name(persona, default="", allow_custom=True)
        if persona not in allowed_personas:
            raise HTTPException(status_code=400, detail="invalid persona")

    if custom_prompt is not None and not isinstance(custom_prompt, str):
        raise HTTPException(status_code=400, detail="custom_prompt must be a string")
    if isinstance(custom_prompt, str) and len(custom_prompt) > 2000:
        raise HTTPException(status_code=400, detail="custom_prompt too long (max 2000 chars)")

    if temperature is not None and not isinstance(temperature, (int, float)):
        raise HTTPException(status_code=400, detail="temperature must be a number")

    ai_config = _normalize_ai_config(_safe_json_loads_object(user.get("ai_config")))
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

    return {"ai_config": ai_config, "personas": list(SUPPORTED_PERSONAS)}


@app.post("/v1/user/push-token")
async def user_register_push_token(request: Request) -> Any:
    _, user = await _require_user(request)
    try:
        body = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="request body must be valid JSON")
    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="invalid json body")

    platform = str(body.get("platform") or "").strip().lower()
    push_token = str(body.get("push_token") or "").strip()

    if platform not in ("ios", "android"):
        raise HTTPException(status_code=400, detail="platform must be 'ios' or 'android'")
    if not push_token:
        raise HTTPException(status_code=400, detail="push_token required")
    if len(push_token) > 2048:
        raise HTTPException(status_code=400, detail="push_token too long")

    now = int(time.time())
    user_id = str(user["id"])

    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        await db.execute(
            """
            INSERT INTO push_tokens(user_id, platform, push_token, created_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(platform, push_token)
            DO UPDATE SET user_id=excluded.user_id, created_at=excluded.created_at
            """,
            (user_id, platform, push_token, now),
        )
        await db.commit()
        db.row_factory = aiosqlite.Row
        async with db.execute(
            "SELECT id, created_at FROM push_tokens WHERE platform=? AND push_token=?",
            (platform, push_token),
        ) as cur:
            row = await cur.fetchone()

    return {
        "registered": True,
        "id": int(row["id"]) if row else None,
        "user_id": user_id,
        "platform": platform,
        "created_at": int(row["created_at"]) if row else now,
    }


@app.post("/v1/user/export")
async def user_export_data(request: Request) -> Any:
    _, user = await _require_user(request)
    now = int(time.time())
    await _cleanup_expired_exports(now)

    payload = await _build_user_export_payload(user)
    export_id = str(uuid.uuid4())
    download_token = secrets.token_urlsafe(32)
    expires_at = now + EXPORT_URL_TTL_SECONDS
    filename = _safe_export_filename(user_id=str(user["id"]), export_id=export_id, now=now)
    file_path = os.path.join(EXPORT_DIR, filename)

    _ensure_export_dir()
    try:
        with open(file_path, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, indent=2)
    except Exception:
        raise HTTPException(status_code=500, detail="failed to build export file")

    try:
        async with aiosqlite.connect(TOKEN_DB_PATH) as db:
            await db.execute(
                """
                INSERT INTO user_exports(id,user_id,download_token,file_path,created_at,expires_at)
                VALUES (?,?,?,?,?,?)
                """,
                (export_id, str(user["id"]), download_token, file_path, now, expires_at),
            )
            await db.commit()
    except Exception:
        with suppress(OSError):
            os.remove(file_path)
        raise HTTPException(status_code=500, detail="failed to save export record")

    base_download_url = str(request.url_for("user_download_export", export_id=export_id))
    download_url = f"{base_download_url}?token={download_token}"
    return {
        "export_id": export_id,
        "download_url": download_url,
        "expires_at": expires_at,
    }


@app.get("/v1/user/export/{export_id}", name="user_download_export")
async def user_download_export(export_id: str, token: str = "") -> Any:
    token_norm = (token or "").strip()
    if not token_norm:
        raise HTTPException(status_code=401, detail="download token required")

    now = int(time.time())
    await _cleanup_expired_exports(now)

    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        try:
            async with db.execute(
                "SELECT id,file_path,expires_at FROM user_exports WHERE id=? AND download_token=?",
                (export_id, token_norm),
            ) as cur:
                row = await cur.fetchone()
        except sqlite3.OperationalError:
            row = None

        if not row:
            raise HTTPException(status_code=404, detail="export not found")

        file_path = str(row["file_path"])
        expires_at = int(row["expires_at"] or 0)

        if expires_at > 0 and now >= expires_at:
            await db.execute("DELETE FROM user_exports WHERE id=?", (export_id,))
            await db.commit()
            with suppress(OSError):
                os.remove(file_path)
            raise HTTPException(status_code=410, detail="export link expired")

        if not os.path.isfile(file_path):
            await db.execute("DELETE FROM user_exports WHERE id=?", (export_id,))
            await db.commit()
            raise HTTPException(status_code=404, detail="export file missing")

    return FileResponse(
        path=file_path,
        media_type="application/json",
        filename=f"clawphones_export_{export_id}.json",
        headers={"Cache-Control": "private, no-store"},
    )


@app.delete("/v1/user/account", status_code=204)
async def user_delete_account(request: Request) -> Response:
    _, user = await _require_user(request)
    try:
        body = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="request body must be valid JSON")
    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="invalid json body")
    if body.get("confirm") is not True:
        raise HTTPException(status_code=400, detail="confirm must be true")

    user_id = str(user["id"])
    export_files: List[str] = []
    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        try:
            async with db.execute("SELECT file_path FROM user_exports WHERE user_id=?", (user_id,)) as cur:
                export_files = [str(r["file_path"]) for r in await cur.fetchall() if r and r["file_path"]]
        except sqlite3.OperationalError:
            export_files = []

        # Purge messages first, then parent entities and token-linked records.
        await db.execute(
            """
            DELETE FROM messages
            WHERE conversation_id IN (
              SELECT id FROM conversations
              WHERE device_token IN (
                SELECT token FROM device_tokens WHERE user_id=?
              )
            )
            """,
            (user_id,),
        )
        await db.execute(
            """
            DELETE FROM conversations
            WHERE device_token IN (
              SELECT token FROM device_tokens WHERE user_id=?
            )
            """,
            (user_id,),
        )
        await db.execute(
            """
            DELETE FROM usage_daily
            WHERE token IN (
              SELECT token FROM device_tokens WHERE user_id=?
            )
            """,
            (user_id,),
        )
        await db.execute(
            """
            DELETE FROM crash_reports
            WHERE device_token IN (
              SELECT token FROM device_tokens WHERE user_id=?
            )
            """,
            (user_id,),
        )
        await db.execute("DELETE FROM push_tokens WHERE user_id=?", (user_id,))
        await db.execute("DELETE FROM user_exports WHERE user_id=?", (user_id,))
        await db.execute("DELETE FROM device_tokens WHERE user_id=?", (user_id,))
        await db.execute("DELETE FROM users WHERE id=?", (user_id,))
        await db.commit()

    for file_path in export_files:
        with suppress(OSError):
            os.remove(file_path)

    return Response(status_code=204)


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

    normalized_msgs: List[Dict[str, Any]] = []
    for m in msgs:
        content_text, meta = _parse_message_content_with_meta(m["content"])
        row: Dict[str, Any] = {
            "id": m["id"],
            "role": m["role"],
            "content": content_text,
            "created_at": m["created_at"],
        }
        try:
            file_ids = _normalize_file_ids(meta.get("file_ids"))
        except Exception:
            file_ids = []
        if file_ids:
            row["file_ids"] = file_ids
        files = meta.get("files")
        if isinstance(files, list) and files:
            row["files"] = files
        normalized_msgs.append(row)

    return {
        "id": conv["id"],
        "title": conv["title"],
        "created_at": conv["created_at"],
        "messages": normalized_msgs,
    }


async def _handle_file_upload_request(
    request: Request,
    *,
    device_token: str,
    conversation_id: Optional[str],
) -> Dict[str, Any]:
    normalized_conversation_id = (conversation_id or "").strip()
    if not normalized_conversation_id:
        raise HTTPException(status_code=400, detail="conversation_id is required")

    content_type = request.headers.get("content-type", "")
    raw_body = await request.body()
    if not isinstance(raw_body, (bytes, bytearray)):
        raise HTTPException(status_code=400, detail="invalid request body")
    if len(raw_body) > (MAX_FILE_SIZE + 2 * 1024 * 1024):
        raise HTTPException(status_code=413, detail=f"file too large (max {MAX_FILE_SIZE} bytes)")

    original_name, file_bytes = _extract_file_field_from_multipart(content_type, bytes(raw_body))
    if not isinstance(file_bytes, (bytes, bytearray)):
        raise HTTPException(status_code=400, detail="invalid file payload")
    file_bytes = bytes(file_bytes)
    if not file_bytes:
        raise HTTPException(status_code=400, detail="empty file not allowed")

    mime_type = _detect_mime_type(file_bytes, original_name)
    if mime_type not in ALLOWED_IMAGE_TYPES and mime_type not in ALLOWED_FILE_TYPES:
        raise HTTPException(status_code=415, detail=f"unsupported file type: {mime_type}")

    max_size = MAX_IMAGE_SIZE if mime_type in ALLOWED_IMAGE_TYPES else MAX_FILE_SIZE
    if len(file_bytes) > max_size:
        raise HTTPException(status_code=413, detail=f"file too large (max {max_size} bytes)")

    sha256_hash = hashlib.sha256(file_bytes).hexdigest()
    extension = _guess_extension(mime_type, original_name)
    if not re.fullmatch(r"\.[A-Za-z0-9]{1,10}", extension or ""):
        extension = ""
    stored_path = os.path.abspath(os.path.join(UPLOAD_DIR, f"{sha256_hash}{extension}"))
    if not os.path.exists(stored_path):
        try:
            with open(stored_path, "wb") as fh:
                fh.write(file_bytes)
        except Exception:
            raise HTTPException(status_code=500, detail="failed to store uploaded file")

    extracted_text = _extract_text_from_file(file_bytes, mime_type)
    file_id = str(uuid.uuid4())
    created_at = int(time.time())

    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        async with db.execute(
            "SELECT id FROM conversations WHERE id=? AND device_token=?",
            (normalized_conversation_id, device_token),
        ) as cur:
            conv = await cur.fetchone()
        if not conv:
            raise HTTPException(status_code=404, detail="conversation not found")

        await db.execute(
            """
            INSERT INTO conversation_files(
              id,conversation_id,original_name,stored_path,sha256_hash,mime_type,size_bytes,extracted_text,created_at
            ) VALUES (?,?,?,?,?,?,?,?,?)
            """,
            (
                file_id,
                normalized_conversation_id,
                original_name,
                stored_path,
                sha256_hash,
                mime_type,
                len(file_bytes),
                extracted_text,
                created_at,
            ),
        )
        await db.commit()

    return {
        "file_id": file_id,
        "url": f"/v1/files/{file_id}",
        "mime_type": mime_type,
        "size": len(file_bytes),
    }


@app.post("/v1/upload")
async def upload_file(
    request: Request,
    conversation_id: Optional[str] = None,
) -> Any:
    device_token = _require_device_token(request)
    await _get_tier_for_token(device_token)
    return await _handle_file_upload_request(
        request,
        device_token=device_token,
        conversation_id=conversation_id,
    )


@app.post("/v1/conversations/{conversation_id}/upload")
async def upload_conversation_file(
    conversation_id: str,
    request: Request,
) -> Any:
    device_token = _require_device_token(request)
    await _get_tier_for_token(device_token)
    return await _handle_file_upload_request(
        request,
        device_token=device_token,
        conversation_id=conversation_id,
    )


@app.get("/v1/files/{file_id}")
async def get_uploaded_file(file_id: str, request: Request) -> Any:
    device_token = _require_device_token(request)
    await _get_tier_for_token(device_token)

    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        async with db.execute(
            """
            SELECT
              cf.id,
              cf.original_name,
              cf.stored_path,
              cf.mime_type
            FROM conversation_files cf
            JOIN conversations c ON c.id = cf.conversation_id
            WHERE cf.id=? AND c.device_token=?
            """,
            (file_id, device_token),
        ) as cur:
            row = await cur.fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="file not found")

    path = str(row["stored_path"])
    real_path = os.path.realpath(path)
    upload_dir = os.path.realpath(UPLOAD_DIR)
    if not real_path.startswith(upload_dir + os.sep):
        raise HTTPException(status_code=403, detail="access denied")
    if not os.path.isfile(real_path):
        raise HTTPException(status_code=404, detail="file content missing")
    return FileResponse(
        path=real_path,
        media_type=str(row["mime_type"]),
        filename=str(row["original_name"]),
    )


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
    file_ids = _normalize_file_ids(body.get("file_ids"))
    if not isinstance(user_text, str):
        raise HTTPException(status_code=400, detail="message must be a string")
    user_text = user_text.strip()
    if not user_text and not file_ids:
        raise HTTPException(status_code=400, detail="message must be non-empty when file_ids is empty")
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

        attached_files = await _fetch_conversation_files_by_ids(db, conversation_id, file_ids)
        stored_user_content = _encode_message_content_with_meta(user_text, file_ids=file_ids, files=attached_files)
        await db.execute(
            "INSERT INTO messages(id,conversation_id,role,content,created_at) VALUES (?,?,?,?,?)",
            (user_message_id, conversation_id, "user", stored_user_content, now),
        )
        title_seed = user_text or (str(attached_files[0].get("original_name")) if attached_files else "")
        title_candidate = _title_from_user_message(title_seed) or None
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
        file_map = await _load_file_map_for_messages(db, conversation_id, rows)

    oai_messages = _build_oai_messages_from_rows(rows, file_map)

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
    try:
        file_ids = _normalize_file_ids(body.get("file_ids"))
    except HTTPException as e:
        return StreamingResponse(_sse_error_once(str(e.detail)), media_type="text/event-stream")
    if not isinstance(user_text, str):
        return StreamingResponse(_sse_error_once("message must be a string"), media_type="text/event-stream")
    user_text = user_text.strip()
    if not user_text and not file_ids:
        return StreamingResponse(
            _sse_error_once("message must be non-empty when file_ids is empty"),
            media_type="text/event-stream",
        )
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

            attached_files = await _fetch_conversation_files_by_ids(db, conversation_id, file_ids)
            stored_user_content = _encode_message_content_with_meta(user_text, file_ids=file_ids, files=attached_files)
            await db.execute(
                "INSERT INTO messages(id,conversation_id,role,content,created_at) VALUES (?,?,?,?,?)",
                (user_message_id, conversation_id, "user", stored_user_content, now),
            )
            title_seed = user_text or (str(attached_files[0].get("original_name")) if attached_files else "")
            title_candidate = _title_from_user_message(title_seed) or None
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
            file_map = await _load_file_map_for_messages(db, conversation_id, rows)
    except Exception as e:
        print(f"[chat/stream] internal error: {e!r}")
        traceback.print_exc()

        async def _gen_internal_error() -> AsyncIterator[bytes]:
            yield _sse_data({"error": "Internal error"})

        return StreamingResponse(_gen_internal_error(), media_type="text/event-stream")

    oai_messages = _build_oai_messages_from_rows(rows, file_map)

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
        await db.execute("DELETE FROM conversation_files WHERE conversation_id=?", (conversation_id,))
        await db.execute("DELETE FROM conversations WHERE id=? AND device_token=?", (conversation_id, device_token))
        await db.commit()

    return {"deleted": True}


async def _send_apns_notification(push_token: str, title: str, body: str) -> Dict[str, Any]:
    if not APNS_AUTH_TOKEN:
        return {"ok": False, "error": "APNS_AUTH_TOKEN not configured"}
    if not APNS_TOPIC:
        return {"ok": False, "error": "APNS_TOPIC not configured"}

    host = "api.sandbox.push.apple.com" if APNS_USE_SANDBOX else "api.push.apple.com"
    url = f"https://{host}/3/device/{push_token}"
    headers = {
        "authorization": f"bearer {APNS_AUTH_TOKEN}",
        "apns-topic": APNS_TOPIC,
        "apns-push-type": "alert",
        "apns-priority": "10",
        "content-type": "application/json",
    }
    payload = {
        "aps": {
            "alert": {"title": title, "body": body},
            "sound": "default",
        }
    }

    try:
        async with httpx.AsyncClient(http2=True, timeout=10.0) as client:
            resp = await client.post(url, headers=headers, json=payload)
    except Exception as e:
        return {"ok": False, "error": f"apns request failed: {e}"}

    if 200 <= resp.status_code < 300:
        return {"ok": True, "status_code": resp.status_code}

    reason = None
    details: Any = None
    try:
        details = resp.json()
        if isinstance(details, dict):
            reason = details.get("reason")
    except Exception:
        details = None
    if details is None:
        details = (resp.text or "")[:500]

    return {
        "ok": False,
        "status_code": resp.status_code,
        "reason": reason,
        "error": "apns rejected push",
        "details": details,
    }


async def _send_fcm_notification(push_token: str, title: str, body: str) -> Dict[str, Any]:
    if not FCM_PROJECT_ID:
        return {"ok": False, "error": "FCM_PROJECT_ID not configured"}
    if not FCM_ACCESS_TOKEN:
        return {"ok": False, "error": "FCM_ACCESS_TOKEN not configured"}

    url = f"https://fcm.googleapis.com/v1/projects/{FCM_PROJECT_ID}/messages:send"
    headers = {
        "authorization": f"Bearer {FCM_ACCESS_TOKEN}",
        "content-type": "application/json; charset=utf-8",
    }
    payload = {
        "message": {
            "token": push_token,
            "notification": {"title": title, "body": body},
        }
    }

    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post(url, headers=headers, json=payload)
    except Exception as e:
        return {"ok": False, "error": f"fcm request failed: {e}"}

    if 200 <= resp.status_code < 300:
        response_body: Any = None
        try:
            response_body = resp.json()
        except Exception:
            response_body = (resp.text or "")[:500]
        return {"ok": True, "status_code": resp.status_code, "details": response_body}

    details: Any = None
    try:
        details = resp.json()
    except Exception:
        details = (resp.text or "")[:500]
    return {
        "ok": False,
        "status_code": resp.status_code,
        "error": "fcm rejected push",
        "details": details,
    }


async def send_push(user_id: str, title: str, body: str) -> Dict[str, Any]:
    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        async with db.execute(
            "SELECT id, platform, push_token FROM push_tokens WHERE user_id=? ORDER BY id DESC",
            (str(user_id),),
        ) as cur:
            token_rows = await cur.fetchall()

    if not token_rows:
        return {"total": 0, "sent": 0, "failed": 0, "results": []}

    results: List[Dict[str, Any]] = []
    invalid_row_ids: List[int] = []
    apns_invalid_reasons = {
        "BadDeviceToken",
        "DeviceTokenNotForTopic",
        "Unregistered",
    }

    for row in token_rows:
        row_id = int(row["id"])
        platform = str(row["platform"])
        push_token = str(row["push_token"])

        if platform == "ios":
            send_result = await _send_apns_notification(push_token, title, body)
            reason = send_result.get("reason")
            if isinstance(reason, str) and reason in apns_invalid_reasons:
                invalid_row_ids.append(row_id)
            if int(send_result.get("status_code") or 0) == 410:
                invalid_row_ids.append(row_id)
        elif platform == "android":
            send_result = await _send_fcm_notification(push_token, title, body)
            details_text = json.dumps(send_result.get("details"), ensure_ascii=False)
            if ("UNREGISTERED" in details_text) or ("registration-token-not-registered" in details_text):
                invalid_row_ids.append(row_id)
        else:
            send_result = {"ok": False, "error": f"unsupported platform: {platform}"}

        results.append({"id": row_id, "platform": platform, **send_result})

    if invalid_row_ids:
        dedup_ids = sorted(set(invalid_row_ids))
        placeholders = ",".join(["?"] * len(dedup_ids))
        async with aiosqlite.connect(TOKEN_DB_PATH) as db:
            await db.execute(f"DELETE FROM push_tokens WHERE id IN ({placeholders})", tuple(dedup_ids))
            await db.commit()

    sent = sum(1 for r in results if bool(r.get("ok")))
    failed = len(results) - sent
    return {"total": len(results), "sent": sent, "failed": failed, "results": results}


def _admin_key_matches(x_admin_key: Optional[str]) -> bool:
    if not ADMIN_KEY:
        return False
    provided = x_admin_key or ""
    return secrets.compare_digest(provided, ADMIN_KEY)


def _admin_check(x_admin_key: Optional[str]) -> None:
    if not ADMIN_KEY:
        raise HTTPException(status_code=404, detail="admin disabled")
    if not _admin_key_matches(x_admin_key):
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
    tier = _normalize_tier_name(body.get("tier"))
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
    tier = _normalize_tier_name(body.get("tier"), default="")
    if tier not in LIMITS:
        raise HTTPException(status_code=400, detail="invalid tier")

    row = await _get_token_row(token)
    if not row:
        raise HTTPException(status_code=404, detail="token not found")

    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        await db.execute("UPDATE device_tokens SET tier=? WHERE token=?", (tier, token))
        await db.commit()

    return {"token": token, "tier": tier}


@app.post("/admin/push/announcement")
async def admin_push_announcement(
    request: Request,
    x_admin_key: Optional[str] = Header(default=None),
) -> Any:
    _admin_check(x_admin_key)
    try:
        payload = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="request body must be valid JSON")
    if not isinstance(payload, dict):
        raise HTTPException(status_code=400, detail="invalid json body")

    user_id = str(payload.get("user_id") or "").strip()
    title = str(payload.get("title") or "").strip()
    message_body = str(payload.get("body") or "").strip()

    if not user_id:
        raise HTTPException(status_code=400, detail="user_id required")
    if not title:
        raise HTTPException(status_code=400, detail="title required")
    if not message_body:
        raise HTTPException(status_code=400, detail="body required")
    if len(title) > 200:
        raise HTTPException(status_code=400, detail="title too long")
    if len(message_body) > 2000:
        raise HTTPException(status_code=400, detail="body too long")

    user = await _get_user_row_by_id(user_id)
    if not user:
        raise HTTPException(status_code=404, detail="user not found")

    result = await send_push(user_id=user_id, title=title, body=message_body)
    return {
        "ok": result.get("sent", 0) > 0,
        "type": "system_announcement",
        "user_id": user_id,
        "title": title,
        "body": message_body,
        "delivery": result,
    }




# ── Crash Reports ─────────────────────────────────────────────────────────────

@app.post("/v1/crash-reports")
async def post_crash_report(request: Request) -> Any:
    """App submits crash report (requires auth token)."""
    auth = request.headers.get("authorization")
    token = _parse_bearer(auth)
    if not token:
        raise HTTPException(status_code=401, detail="missing bearer token")
    # verify token exists
    await _get_tier_for_token(token)

    try:
        body = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="invalid json")
    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="invalid json body")

    now = int(time.time())
    report_id = str(uuid.uuid4())

    stacktrace = str(body.get("stacktrace", ""))[:5000]  # cap at 5KB
    platform = str(body.get("platform", "unknown"))[:20]
    app_version = str(body.get("app_version", ""))[:50]
    device_model = str(body.get("device_model", ""))[:100]
    os_version = str(body.get("os_version", ""))[:50]
    user_action = str(body.get("user_action", ""))[:200]
    fatal = 1 if body.get("fatal", True) else 0

    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        await db.execute(
            "INSERT INTO crash_reports(id,device_token,platform,app_version,device_model,os_version,stacktrace,user_action,fatal,status,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            (report_id, token, platform, app_version, device_model, os_version, stacktrace, user_action, fatal, "new", now),
        )
        await db.commit()

    return {"id": report_id, "status": "received"}


@app.get("/v1/crash-reports")
async def get_crash_reports(request: Request, status: str = None, limit: int = 50) -> Any:
    """Admin: list crash reports. Requires ADMIN_KEY header."""
    admin = request.headers.get("x-admin-key") or request.headers.get("authorization", "").replace("Bearer ", "")
    if not _admin_key_matches((admin or "").strip()):
        raise HTTPException(status_code=403, detail="admin key required")

    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        if status:
            async with db.execute(
                "SELECT * FROM crash_reports WHERE status=? ORDER BY created_at DESC LIMIT ?",
                (status, min(limit, 200)),
            ) as cur:
                rows = await cur.fetchall()
        else:
            async with db.execute(
                "SELECT * FROM crash_reports ORDER BY created_at DESC LIMIT ?",
                (min(limit, 200),),
            ) as cur:
                rows = await cur.fetchall()

    return {"crash_reports": [dict(r) for r in rows], "count": len(rows)}


@app.patch("/v1/crash-reports/{report_id}")
async def patch_crash_report(report_id: str, request: Request) -> Any:
    """Admin: update crash report status."""
    admin = request.headers.get("x-admin-key") or request.headers.get("authorization", "").replace("Bearer ", "")
    if not _admin_key_matches((admin or "").strip()):
        raise HTTPException(status_code=403, detail="admin key required")

    try:
        body = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="invalid json")

    new_status = body.get("status")
    if new_status not in ("new", "spec", "fixing", "fixed", "wontfix"):
        raise HTTPException(status_code=400, detail="invalid status")

    async with aiosqlite.connect(TOKEN_DB_PATH) as db:
        result = await db.execute(
            "UPDATE crash_reports SET status=? WHERE id=?",
            (new_status, report_id),
        )
        await db.commit()
        if result.rowcount == 0:
            raise HTTPException(status_code=404, detail="report not found")

    return {"id": report_id, "status": new_status}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host=LISTEN_HOST, port=LISTEN_PORT)
