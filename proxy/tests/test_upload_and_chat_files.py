import asyncio
import base64
import importlib
import os
import sqlite3
import time
import uuid

import pytest
from fastapi.testclient import TestClient


@pytest.fixture()
def app_ctx(tmp_path, monkeypatch):
    db_path = tmp_path / "tokens.sqlite3"
    monkeypatch.setenv("TOKEN_DB_PATH", str(db_path))
    monkeypatch.setenv("MOCK_MODE", "1")

    import proxy.server as server

    server = importlib.reload(server)
    asyncio.run(server._init_db())

    token = "test-token"
    conversation_id = str(uuid.uuid4())
    now = int(time.time())

    with sqlite3.connect(server.TOKEN_DB_PATH) as conn:
        conn.execute(
            "INSERT INTO device_tokens(token,tier,status,created_at) VALUES (?,?,?,?)",
            (token, "max", "active", now),
        )
        conn.execute(
            "INSERT INTO conversations(id,device_token,title,created_at,updated_at) VALUES (?,?,?,?,?)",
            (conversation_id, token, None, now, now),
        )
        conn.commit()

    client = TestClient(server.app)
    headers = {"Authorization": f"Bearer {token}"}
    return client, server, conversation_id, headers


def test_upload_success_and_get_file(app_ctx):
    client, _server, conversation_id, headers = app_ctx

    upload = client.post(
        f"/v1/conversations/{conversation_id}/upload",
        files={"file": ("notes.txt", b"hello from attachment", "text/plain")},
        headers=headers,
    )
    assert upload.status_code == 200
    payload = upload.json()
    assert payload["file_id"]
    assert payload["mime_type"] == "text/plain"
    assert payload["size"] == len(b"hello from attachment")
    assert "hello from attachment" in (payload.get("extracted_text") or "")

    file_resp = client.get(f"/v1/files/{payload['file_id']}", headers=headers)
    assert file_resp.status_code == 200
    assert file_resp.content == b"hello from attachment"


def test_upload_rejects_oversize(app_ctx):
    client, _server, conversation_id, headers = app_ctx

    too_large = b"a" * ((20 * 1024 * 1024) + 1)
    resp = client.post(
        f"/v1/conversations/{conversation_id}/upload",
        files={"file": ("big.txt", too_large, "text/plain")},
        headers=headers,
    )
    assert resp.status_code == 413
    assert "file too large" in resp.text


def test_upload_rejects_unsupported_file_type(app_ctx):
    client, _server, conversation_id, headers = app_ctx

    resp = client.post(
        f"/v1/conversations/{conversation_id}/upload",
        files={"file": ("malware.exe", b"MZ\x90\x00\x03", "application/octet-stream")},
        headers=headers,
    )
    assert resp.status_code == 415


def test_upload_path_traversal_name_is_safely_hashed(app_ctx):
    client, server, conversation_id, headers = app_ctx

    resp = client.post(
        f"/v1/conversations/{conversation_id}/upload",
        files={"file": ("../../../../etc/passwd.txt", b"safe", "text/plain")},
        headers=headers,
    )
    assert resp.status_code == 200
    file_id = resp.json()["file_id"]

    with sqlite3.connect(server.TOKEN_DB_PATH) as conn:
        row = conn.execute(
            "SELECT stored_path,sha256_hash FROM conversation_files WHERE id=?",
            (file_id,),
        ).fetchone()

    assert row is not None
    stored_path, sha256_hash = row
    assert os.path.abspath(stored_path).startswith(os.path.abspath(server.UPLOAD_DIR))
    assert os.path.basename(stored_path).startswith(sha256_hash)
    assert ".." not in os.path.basename(stored_path)


def test_chat_with_file_ids_builds_text_and_vision_content(app_ctx, monkeypatch):
    client, server, conversation_id, headers = app_ctx

    text_upload = client.post(
        f"/v1/conversations/{conversation_id}/upload",
        files={"file": ("doc.txt", b"doc context body", "text/plain")},
        headers=headers,
    )
    assert text_upload.status_code == 200
    text_file_id = text_upload.json()["file_id"]

    png_1x1 = base64.b64decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO2P2b8AAAAASUVORK5CYII="
    )
    image_upload = client.post(
        f"/v1/conversations/{conversation_id}/upload",
        files={"file": ("pixel.png", png_1x1, "image/png")},
        headers=headers,
    )
    assert image_upload.status_code == 200
    image_file_id = image_upload.json()["file_id"]

    captured = {}

    async def fake_call_llm(*, token, tier, messages, forced_provider, wants_stream):
        captured["messages"] = messages
        return {
            "choices": [
                {
                    "message": {
                        "role": "assistant",
                        "content": "ok",
                    }
                }
            ]
        }

    monkeypatch.setattr(server, "_call_llm", fake_call_llm)

    chat_resp = client.post(
        f"/v1/conversations/{conversation_id}/chat",
        json={
            "message": "Analyze attached files",
            "file_ids": [text_file_id, image_file_id],
        },
        headers=headers,
    )
    assert chat_resp.status_code == 200

    user_content = captured["messages"][-1]["content"]
    assert isinstance(user_content, list)
    assert user_content[0]["type"] == "text"
    assert "[File: doc.txt]" in user_content[0]["text"]
    assert "doc context body" in user_content[0]["text"]
    assert "Analyze attached files" in user_content[0]["text"]
    assert user_content[1]["type"] == "image_url"
    assert user_content[1]["image_url"]["url"].startswith("data:image/png;base64,")
