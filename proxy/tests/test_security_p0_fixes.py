import asyncio
import importlib
import sqlite3
import sys
import time
import uuid
from pathlib import Path

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient
from starlette.requests import Request

sys.path.append(str(Path(__file__).resolve().parents[1]))


@pytest.fixture()
def app_ctx(tmp_path, monkeypatch):
    db_path = tmp_path / "tokens.sqlite3"
    export_dir = tmp_path / "exports"
    upload_dir = tmp_path / "uploads"

    monkeypatch.setenv("TOKEN_DB_PATH", str(db_path))
    monkeypatch.setenv("MOCK_MODE", "1")
    monkeypatch.setenv("ADMIN_KEY", "test-admin-key")

    import server

    server = importlib.reload(server)
    monkeypatch.setattr(server, "EXPORT_DIR", str(export_dir))
    monkeypatch.setattr(server, "UPLOAD_DIR", str(upload_dir))

    export_dir.mkdir(parents=True, exist_ok=True)
    upload_dir.mkdir(parents=True, exist_ok=True)

    server._RATE_LIMIT_HITS.clear()
    server._LOGIN_FAILURES.clear()
    asyncio.run(server._init_db())

    token = "test-security-token"
    conversation_id = str(uuid.uuid4())
    now = int(time.time())
    with sqlite3.connect(server.TOKEN_DB_PATH) as conn:
        conn.execute(
            "INSERT INTO device_tokens(token,tier,status,created_at) VALUES (?,?,?,?)",
            (token, "free", "active", now),
        )
        conn.execute(
            "INSERT INTO conversations(id,device_token,title,created_at,updated_at) VALUES (?,?,?,?,?)",
            (conversation_id, token, None, now, now),
        )
        conn.commit()

    client = TestClient(server.app)
    return {
        "client": client,
        "server": server,
        "token": token,
        "conversation_id": conversation_id,
        "upload_dir": upload_dir,
    }


def test_admin_auth_empty_wrong_correct(app_ctx):
    server = app_ctx["server"]

    with pytest.raises(HTTPException) as exc:
        server._admin_check(None)
    assert exc.value.status_code == 401

    with pytest.raises(HTTPException) as exc:
        server._admin_check("wrong-admin-key")
    assert exc.value.status_code == 401

    server._admin_check("test-admin-key")


def test_rate_limit_target_covers_critical_endpoints(app_ctx):
    server = app_ctx["server"]

    def target(method: str, path: str):
        scope = {
            "type": "http",
            "method": method,
            "path": path,
            "headers": [],
            "query_string": b"",
            "client": ("127.0.0.1", 12345),
            "scheme": "http",
            "server": ("testserver", 80),
            "root_path": "",
        }
        return server._rate_limit_target(Request(scope))

    assert target("POST", "/v1/auth/register") == ("auth", "/v1/auth/register")
    assert target("POST", "/v1/auth/apple") == ("auth", "/v1/auth/apple")
    assert target("POST", "/v1/auth/refresh") == ("auth", "/v1/auth/refresh")
    assert target("POST", "/v1/auth/login") is None

    assert target("POST", "/v1/chat/completions") == ("chat", "/v1/chat/completions")
    assert target("POST", "/deepseek/v1/chat/completions") == ("chat", "/deepseek/v1/chat/completions")
    assert target("POST", "/kimi/v1/chat/completions") == ("chat", "/kimi/v1/chat/completions")
    assert target("POST", "/claude/v1/chat/completions") == ("chat", "/claude/v1/chat/completions")
    assert target("POST", "/v1/conversations/abc/chat") == ("chat", "/v1/conversations/{id}/chat")
    assert target("POST", "/v1/conversations/abc/chat/stream") == ("chat", "/v1/conversations/{id}/chat/stream")
    assert target("POST", "/v1/conversations/abc/upload") == ("upload", "/v1/conversations/{id}/upload")

    assert target("POST", "/admin/tokens/generate") == ("admin", "/admin/*")
    assert target("POST", "/v1/user/export") == ("export", "/v1/user/export")
    assert target("DELETE", "/v1/user/account") == ("export", "/v1/user/account")
    assert target("POST", "/v1/crash-reports") == ("crash", "/v1/crash-reports")


def test_rate_limiter_returns_429_and_recovers_after_window(app_ctx, monkeypatch):
    client = app_ctx["client"]
    server = app_ctx["server"]

    monkeypatch.setitem(server.RATE_LIMITS, "auth", {"requests": 2, "window": 10})
    server._RATE_LIMIT_HITS.clear()

    clock = {"now": 1_700_000_000}
    monkeypatch.setattr(server.time, "time", lambda: clock["now"])

    for i in range(2):
        resp = client.post(
            "/v1/auth/register",
            json={
                "email": f"rate-test-{i}@example.com",
                "password": "password123",
                "name": "rate",
            },
        )
        assert resp.status_code == 200, resp.text

    limited = client.post(
        "/v1/auth/register",
        json={
            "email": "rate-test-3@example.com",
            "password": "password123",
            "name": "rate",
        },
    )
    assert limited.status_code == 429, limited.text

    clock["now"] += 11
    recovered = client.post(
        "/v1/auth/register",
        json={
            "email": "rate-test-4@example.com",
            "password": "password123",
            "name": "rate",
        },
    )
    assert recovered.status_code == 200, recovered.text


def test_path_traversal_blocked_but_normal_file_allowed(app_ctx):
    client = app_ctx["client"]
    server = app_ctx["server"]
    token = app_ctx["token"]
    conversation_id = app_ctx["conversation_id"]
    upload_dir: Path = app_ctx["upload_dir"]

    good_path = upload_dir / "good.txt"
    good_path.write_bytes(b"hello")

    normal_file_id = str(uuid.uuid4())
    traversal_file_id = str(uuid.uuid4())
    now = int(time.time())
    with sqlite3.connect(server.TOKEN_DB_PATH) as conn:
        conn.execute(
            """
            INSERT INTO conversation_files(
              id,conversation_id,original_name,stored_path,sha256_hash,mime_type,size_bytes,extracted_text,created_at
            ) VALUES (?,?,?,?,?,?,?,?,?)
            """,
            (
                normal_file_id,
                conversation_id,
                "good.txt",
                str(good_path),
                "hash-good",
                "text/plain",
                5,
                "hello",
                now,
            ),
        )
        conn.execute(
            """
            INSERT INTO conversation_files(
              id,conversation_id,original_name,stored_path,sha256_hash,mime_type,size_bytes,extracted_text,created_at
            ) VALUES (?,?,?,?,?,?,?,?,?)
            """,
            (
                traversal_file_id,
                conversation_id,
                "passwd",
                "../../etc/passwd",
                "hash-bad",
                "text/plain",
                0,
                "",
                now,
            ),
        )
        conn.commit()

    headers = {"Authorization": f"Bearer {token}"}
    ok = client.get(f"/v1/files/{normal_file_id}", headers=headers)
    assert ok.status_code == 200, ok.text
    assert ok.content == b"hello"

    blocked = client.get(f"/v1/files/{traversal_file_id}", headers=headers)
    assert blocked.status_code == 403, blocked.text
