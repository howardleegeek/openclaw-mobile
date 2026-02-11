import asyncio
import base64
import hashlib
import sqlite3
import sys
import time
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

sys.path.append(str(Path(__file__).resolve().parents[1]))
import server


TEST_TOKEN = "tok_test_files"
TEST_CONVERSATION_ID = "conv_test_files"


@pytest.fixture
def api_ctx(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    db_path = tmp_path / "tokens.sqlite3"
    export_dir = tmp_path / "exports"
    upload_dir = tmp_path / "uploads"

    monkeypatch.setattr(server, "TOKEN_DB_PATH", str(db_path))
    monkeypatch.setattr(server, "EXPORT_DIR", str(export_dir))
    monkeypatch.setattr(server, "UPLOAD_DIR", str(upload_dir))

    export_dir.mkdir(parents=True, exist_ok=True)
    upload_dir.mkdir(parents=True, exist_ok=True)

    asyncio.run(server._init_db())

    now = int(time.time())
    with sqlite3.connect(db_path) as conn:
        conn.execute(
            "INSERT INTO device_tokens(token,tier,status,created_at) VALUES (?,?,?,?)",
            (TEST_TOKEN, "free", "active", now),
        )
        conn.execute(
            "INSERT INTO conversations(id,device_token,title,created_at,updated_at) VALUES (?,?,?,?,?)",
            (TEST_CONVERSATION_ID, TEST_TOKEN, None, now, now),
        )
        conn.commit()

    with TestClient(server.app) as client:
        yield {
            "client": client,
            "db_path": db_path,
            "upload_dir": upload_dir,
            "headers": {"Authorization": f"Bearer {TEST_TOKEN}"},
            "conversation_id": TEST_CONVERSATION_ID,
        }


def test_upload_success_text_file(api_ctx):
    client = api_ctx["client"]
    resp = client.post(
        f"/v1/conversations/{api_ctx['conversation_id']}/upload",
        headers=api_ctx["headers"],
        files={"file": ("notes.txt", b"hello upload", "text/plain")},
    )
    assert resp.status_code == 200, resp.text
    payload = resp.json()
    assert payload["filename"] == "notes.txt"
    assert payload["mime_type"] == "text/plain"
    assert payload["size"] == len(b"hello upload")
    assert "hello upload" in payload["extracted_text"]

    with sqlite3.connect(api_ctx["db_path"]) as conn:
        row = conn.execute(
            "SELECT original_name,mime_type,size_bytes,stored_path FROM conversation_files WHERE id=?",
            (payload["file_id"],),
        ).fetchone()
    assert row is not None
    assert row[0] == "notes.txt"
    assert row[1] == "text/plain"
    assert row[2] == len(b"hello upload")
    assert Path(row[3]).is_file()


def test_upload_rejects_oversized_file(api_ctx):
    client = api_ctx["client"]
    too_big = b"a" * (server.MAX_FILE_SIZE + 1)
    resp = client.post(
        f"/v1/conversations/{api_ctx['conversation_id']}/upload",
        headers=api_ctx["headers"],
        files={"file": ("too-big.txt", too_big, "text/plain")},
    )
    assert resp.status_code == 413
    assert "file too large" in resp.text


def test_upload_rejects_unsupported_mime(api_ctx):
    client = api_ctx["client"]
    exe_bytes = b"MZ\x90\x00\x03\x00\x00\x00fake"
    resp = client.post(
        f"/v1/conversations/{api_ctx['conversation_id']}/upload",
        headers=api_ctx["headers"],
        files={"file": ("payload.exe", exe_bytes, "application/octet-stream")},
    )
    assert resp.status_code == 415
    assert "unsupported file type" in resp.text


def test_upload_rejects_spoofed_image_header(api_ctx):
    client = api_ctx["client"]
    # Payload is not a real PNG/JPEG by magic bytes, even if filename/content-type claim image.
    fake_png = b"\x00\xFF\x11\x22NOT_A_REAL_PNG_BINARY"
    resp = client.post(
        f"/v1/conversations/{api_ctx['conversation_id']}/upload",
        headers=api_ctx["headers"],
        files={"file": ("avatar.png", fake_png, "image/png")},
    )
    assert resp.status_code == 415
    assert "unsupported file type" in resp.text


def test_upload_path_traversal_filename_is_sanitized(api_ctx):
    client = api_ctx["client"]
    content = b"safe data"
    digest = hashlib.sha256(content).hexdigest()
    resp = client.post(
        f"/v1/conversations/{api_ctx['conversation_id']}/upload",
        headers=api_ctx["headers"],
        files={"file": ("../../etc/passwd.txt", content, "text/plain")},
    )
    assert resp.status_code == 200, resp.text
    payload = resp.json()
    assert payload["filename"] == "passwd.txt"

    with sqlite3.connect(api_ctx["db_path"]) as conn:
        row = conn.execute(
            "SELECT original_name,stored_path FROM conversation_files WHERE id=?",
            (payload["file_id"],),
        ).fetchone()
    assert row is not None
    stored_name = Path(row[1]).name
    assert row[0] == "passwd.txt"
    assert ".." not in row[1]
    assert stored_name.startswith(digest)


def test_get_uploaded_file_returns_binary_content(api_ctx):
    client = api_ctx["client"]
    payload = b"fetch me back"
    upload = client.post(
        f"/v1/conversations/{api_ctx['conversation_id']}/upload",
        headers=api_ctx["headers"],
        files={"file": ("document.txt", payload, "text/plain")},
    )
    assert upload.status_code == 200, upload.text
    file_id = upload.json()["file_id"]

    download = client.get(f"/v1/files/{file_id}", headers=api_ctx["headers"])
    assert download.status_code == 200, download.text
    assert download.content == payload
    assert download.headers.get("content-type", "").startswith("text/plain")


def test_chat_with_file_ids_builds_multimodal_messages(api_ctx, monkeypatch: pytest.MonkeyPatch):
    client = api_ctx["client"]

    txt = client.post(
        f"/v1/conversations/{api_ctx['conversation_id']}/upload",
        headers=api_ctx["headers"],
        files={"file": ("note.txt", b"hello from file", "text/plain")},
    )
    assert txt.status_code == 200, txt.text
    txt_file_id = txt.json()["file_id"]

    png_bytes = base64.b64decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO7Y3mQAAAAASUVORK5CYII="
    )
    img = client.post(
        f"/v1/conversations/{api_ctx['conversation_id']}/upload",
        headers=api_ctx["headers"],
        files={"file": ("pixel.png", png_bytes, "image/png")},
    )
    assert img.status_code == 200, img.text
    img_file_id = img.json()["file_id"]

    captured = {}

    async def fake_call_llm(*, token, tier, messages, forced_provider=None, wants_stream=False):
        captured["messages"] = messages
        return {"choices": [{"message": {"content": "ok"}}]}

    monkeypatch.setattr(server, "_call_llm", fake_call_llm)

    chat = client.post(
        f"/v1/conversations/{api_ctx['conversation_id']}/chat",
        headers=api_ctx["headers"],
        json={"message": "Please inspect", "file_ids": [txt_file_id, img_file_id]},
    )
    assert chat.status_code == 200, chat.text
    assert "messages" in captured

    user_message = [m for m in captured["messages"] if m.get("role") == "user"][-1]
    assert isinstance(user_message["content"], list)
    assert user_message["content"][0]["type"] == "text"
    text_part = user_message["content"][0]["text"]
    assert "[File: note.txt]" in text_part
    assert "hello from file" in text_part
    assert "Please inspect" in text_part

    image_parts = [p for p in user_message["content"] if p.get("type") == "image_url"]
    assert len(image_parts) == 1
    assert image_parts[0]["image_url"]["url"].startswith("data:image/png;base64,")

    with sqlite3.connect(api_ctx["db_path"]) as conn:
        row = conn.execute(
            "SELECT content FROM messages WHERE conversation_id=? AND role='user' ORDER BY created_at DESC LIMIT 1",
            (api_ctx["conversation_id"],),
        ).fetchone()
    assert row is not None
    assert "[[MESSAGE_META]]" in row[0]
