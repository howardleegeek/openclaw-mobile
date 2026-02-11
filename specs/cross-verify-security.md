# 后端安全独立审计（交叉验证节点）

目标文件：`proxy/server.py`

## 1) SQL 语句列表：参数化/非参数化

结论：**FAIL**

原因：绝大多数 SQL 使用 `?` 占位符参数化，但存在 4 处动态 SQL（模板拼接/变量 SQL），不属于严格静态参数化。

### 1.1 非严格参数化（动态 SQL）

1. `proxy/server.py:471`
```python
query = (
    "SELECT id,original_name,stored_path,mime_type,size_bytes,extracted_text "
    "FROM conversation_files "
    f"WHERE conversation_id=? AND id IN ({placeholders})"
)
async with db.execute(query, params) as cur:
```
判定：`IN (...)` 占位符数量动态拼接，值本身仍通过 `params` 绑定。

2. `proxy/server.py:1796`
```python
await db.execute(f"UPDATE users SET {', '.join(updates)} WHERE id=?", tuple(params))
```
判定：列名列表动态拼接，值参数化。

3. `proxy/server.py:1987`
```python
sql = f"UPDATE users SET {', '.join(updates)} WHERE id=?"
await db.execute(sql, tuple(params))
```
判定：列名列表动态拼接，值参数化。

4. `proxy/server.py:3399`
```python
await db.execute(f"DELETE FROM push_tokens WHERE id IN ({placeholders})", tuple(dedup_ids))
```
判定：`IN (...)` 占位符数量动态拼接，值参数化。

### 1.2 参数化 SQL（调用点清单）

以下调用均为参数绑定（`?` + tuple/list 参数）：

- `proxy/server.py:307`
- `proxy/server.py:732`
- `proxy/server.py:748`
- `proxy/server.py:759`
- `proxy/server.py:775`
- `proxy/server.py:801`
- `proxy/server.py:827`
- `proxy/server.py:866`
- `proxy/server.py:874`
- `proxy/server.py:908`
- `proxy/server.py:918`
- `proxy/server.py:934`
- `proxy/server.py:945`
- `proxy/server.py:956`
- `proxy/server.py:968`
- `proxy/server.py:1068`
- `proxy/server.py:1081`
- `proxy/server.py:1549` (`executemany`)
- `proxy/server.py:1594`
- `proxy/server.py:1747`
- `proxy/server.py:1759`
- `proxy/server.py:1767`
- `proxy/server.py:1803`
- `proxy/server.py:1816`
- `proxy/server.py:1881`
- `proxy/server.py:1905`
- `proxy/server.py:1923`
- `proxy/server.py:1924`
- `proxy/server.py:1925`
- `proxy/server.py:1926`
- `proxy/server.py:1930`
- `proxy/server.py:2036`
- `proxy/server.py:2051`
- `proxy/server.py:2144`
- `proxy/server.py:2177`
- `proxy/server.py:2188`
- `proxy/server.py:2225`
- `proxy/server.py:2259`
- `proxy/server.py:2274`
- `proxy/server.py:2281`
- `proxy/server.py:2310`
- `proxy/server.py:2316`
- `proxy/server.py:2328`
- `proxy/server.py:2337`
- `proxy/server.py:2346`
- `proxy/server.py:2355`
- `proxy/server.py:2356`
- `proxy/server.py:2357`
- `proxy/server.py:2358`
- `proxy/server.py:2593`
- `proxy/server.py:2599`
- `proxy/server.py:2620`
- `proxy/server.py:2651`
- `proxy/server.py:2659`
- `proxy/server.py:2719`
- `proxy/server.py:2726`
- `proxy/server.py:2764`
- `proxy/server.py:2819`
- `proxy/server.py:2832`
- `proxy/server.py:2837`
- `proxy/server.py:2850`
- `proxy/server.py:2918`
- `proxy/server.py:2922`
- `proxy/server.py:2970`
- `proxy/server.py:2983`
- `proxy/server.py:2988`
- `proxy/server.py:3001`
- `proxy/server.py:3201`
- `proxy/server.py:3205`
- `proxy/server.py:3242`
- `proxy/server.py:3250`
- `proxy/server.py:3251`
- `proxy/server.py:3356`
- `proxy/server.py:3439`
- `proxy/server.py:3470`
- `proxy/server.py:3552`
- `proxy/server.py:3571`
- `proxy/server.py:3577`
- `proxy/server.py:3603`

### 1.3 静态 SQL（DDL/索引/迁移，无外部参数）

以下是静态结构 SQL（不涉及用户输入拼接）：

- `proxy/server.py:557`
- `proxy/server.py:575`
- `proxy/server.py:588`
- `proxy/server.py:601`
- `proxy/server.py:607`
- `proxy/server.py:612`
- `proxy/server.py:617`
- `proxy/server.py:620`
- `proxy/server.py:632`
- `proxy/server.py:643`
- `proxy/server.py:654`
- `proxy/server.py:670`
- `proxy/server.py:671`
- `proxy/server.py:672`
- `proxy/server.py:673`
- `proxy/server.py:684`
- `proxy/server.py:685`
- `proxy/server.py:687`
- `proxy/server.py:704`
- `proxy/server.py:705`
- `proxy/server.py:706`
- `proxy/server.py:707`
- `proxy/server.py:708`
- `proxy/server.py:709`
- `proxy/server.py:710`
- `proxy/server.py:722`
- `proxy/server.py:723`

---

## 2) 认证检查：`compare_digest` vs `==`

结论：**FAIL**

原因：管理接口存在两套检查方式，部分接口使用 `==/!=` 直接比对密钥。

### 2.1 使用 `secrets.compare_digest`（PASS）

1. `proxy/server.py:3410`
```python
if not x_admin_key or not secrets.compare_digest(x_admin_key, ADMIN_KEY):
    raise HTTPException(status_code=401, detail="bad admin key")
```
覆盖接口：`/admin/tokens/generate`、`/admin/tokens/{token}/tier`、`/admin/push/announcement`（通过 `_admin_check`）

### 2.2 使用 `==/!=`（FAIL）

1. `proxy/server.py:3565`
```python
if admin != ADMIN_KEY:
    raise HTTPException(status_code=403, detail="admin key required")
```
接口：`GET /v1/crash-reports`

2. `proxy/server.py:3590`
```python
if admin != ADMIN_KEY:
    raise HTTPException(status_code=403, detail="admin key required")
```
接口：`PATCH /v1/crash-reports/{report_id}`

3. 相关提取逻辑（同一路径）`proxy/server.py:3564`, `proxy/server.py:3589`
```python
admin = request.headers.get("x-admin-key") or request.headers.get("authorization", "").replace("Bearer ", "")
```
判定：使用字符串替换提取 Bearer 值，且与上面的 `!=` 组合，缺少统一常量时间比较实现。

---

## 3) `os.getenv` 调用列表：是否 fallback 到硬编码值

结论：**FAIL**（存在大量硬编码 fallback）

### 3.1 调用清单

- `proxy/server.py:95` `TOKEN_DB_PATH = os.getenv("TOKEN_DB_PATH", "./data/tokens.sqlite3")`
- `proxy/server.py:96` `EXPORT_DIR = os.getenv("EXPORT_DIR", "./data/exports")`
- `proxy/server.py:104` `ADMIN_KEY = os.getenv("ADMIN_KEY")`
- `proxy/server.py:106` `DEEPSEEK_API_KEY = os.getenv("DEEPSEEK_API_KEY", "")`
- `proxy/server.py:107` `DEEPSEEK_BASE_URL = os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com/v1")...`
- `proxy/server.py:108` `DEEPSEEK_MODEL = os.getenv("DEEPSEEK_MODEL", "deepseek-chat")`
- `proxy/server.py:110` `KIMI_API_KEY = os.getenv("KIMI_API_KEY", "")`
- `proxy/server.py:111` `KIMI_BASE_URL = os.getenv("KIMI_BASE_URL", "https://api.moonshot.cn/v1")...`
- `proxy/server.py:112` `KIMI_MODEL = os.getenv("KIMI_MODEL", "moonshot-v1-32k")`
- `proxy/server.py:114` `ANTHROPIC_API_KEY = os.getenv("ANTHROPIC_API_KEY", "")`
- `proxy/server.py:115` `CLAUDE_BASE_URL = os.getenv("CLAUDE_BASE_URL", "")...`
- `proxy/server.py:116` `CLAUDE_MODEL = os.getenv("CLAUDE_MODEL", "claude-3-5-sonnet-latest")`
- `proxy/server.py:118` `LISTEN_HOST = os.getenv("LISTEN_HOST", "127.0.0.1")`
- `proxy/server.py:119` `LISTEN_PORT = int(os.getenv("LISTEN_PORT", "8080"))`
- `proxy/server.py:120` `MOCK_MODE = os.getenv("MOCK_MODE", "")...`
- `proxy/server.py:122` `FCM_PROJECT_ID = os.getenv("FCM_PROJECT_ID", "")...`
- `proxy/server.py:123` `FCM_ACCESS_TOKEN = os.getenv("FCM_ACCESS_TOKEN", "")...`
- `proxy/server.py:125` `APNS_AUTH_TOKEN = os.getenv("APNS_AUTH_TOKEN", "")...`
- `proxy/server.py:126` `APNS_TOPIC = os.getenv("APNS_TOPIC", "")...`
- `proxy/server.py:127` `APNS_USE_SANDBOX = os.getenv("APNS_USE_SANDBOX", "")...`
- `proxy/server.py:129` `APPLE_CLIENT_ID = os.getenv("APPLE_CLIENT_ID", "")...`
- `proxy/server.py:130` `APPLE_CLIENT_IDS = [v.strip() for v in os.getenv("APPLE_CLIENT_IDS", "").split(",") ...]`
- `proxy/server.py:133` `APPLE_JWKS_URL = os.getenv("APPLE_JWKS_URL", "https://appleid.apple.com/auth/keys")...`
- `proxy/server.py:134` `APPLE_JWKS_CACHE_TTL_SECONDS = max(60, int(os.getenv("APPLE_JWKS_CACHE_TTL_SECONDS", "3600")))`

### 3.2 明确无 fallback 的项

- `proxy/server.py:104` `ADMIN_KEY = os.getenv("ADMIN_KEY")`（可为空，表示 admin disabled）

---

## 4) 文件操作：路径穿越风险

结论：**FAIL（防御不足，存在信任数据库路径的风险面）**

### 4.1 相对安全点（PASS 证据）

1. 导出文件名做白名单清洗：`proxy/server.py:258-261`
```python
safe_user = re.sub(r"[^a-zA-Z0-9_-]", "", (user_id or ""))[:32] or "user"
safe_export = re.sub(r"[^a-zA-Z0-9_-]", "", (export_id or ""))[:12] or "export"
return f"user_export_{safe_user}_{now}_{safe_export}.json"
```

2. 上传存储路径基于 hash + 受限扩展名：`proxy/server.py:2703-2711`
```python
sha256_hash = hashlib.sha256(raw).hexdigest()
if not re.fullmatch(r"\.[a-z0-9]{1,10}", ext or ""):
    ext = ""
stored_path = os.path.join(UPLOAD_DIR, f"{sha256_hash}{ext}")
```

### 4.2 风险点（FAIL 证据）

1. 直接使用 DB 中 `file_path` 删除文件，未校验是否仍在 `EXPORT_DIR`：`proxy/server.py:921-923`
```python
for file_path in expired_files:
    with suppress(OSError):
        os.remove(file_path)
```

2. 下载导出文件直接信任 DB 路径并返回：`proxy/server.py:2270`, `proxy/server.py:2285-2286`
```python
file_path = str(row["file_path"])
return FileResponse(path=file_path, ...)
```

3. 下载会话文件直接信任 DB 中 `stored_path`：`proxy/server.py:2777`, `proxy/server.py:2780-2781`
```python
file_path = str(row["stored_path"])
return FileResponse(path=file_path, ...)
```

判定说明：当前写入路径主要由服务端生成，因此“直接用户输入路径穿越”概率低；但缺少 `realpath` + base-dir 前缀校验，DB 被污染时可越界访问/删除任意文件。

---

## 5) Rate limiting 覆盖率

结论：**FAIL（仅局部覆盖）**

### 5.1 已实现（PASS 证据）

1. 登录失败 IP 限流：`proxy/server.py:44-47`, `proxy/server.py:68-82`, `proxy/server.py:1637-1638`
```python
_LOGIN_FAILS_PER_MINUTE = 5
_LOGIN_LOCKOUT_SECS = 300
if _is_login_rate_limited(ip, now):
    raise HTTPException(status_code=429, detail="Too many login attempts. Try again in 5 minutes")
```

2. LLM 每日额度限制（token 维度）：`proxy/server.py:2415-2418`, `proxy/server.py:3071-3074`
```python
used_prompt, used_completion, _ = await _get_daily_usage(token)
if used_total + prompt_tokens > limits.daily_tokens:
    raise HTTPException(status_code=429, detail="daily quota exceeded")
```

### 5.2 缺口（FAIL 证据）

- 未见全局请求限流（按 IP/用户/端点）中间件或统一 throttle。
- 高风险接口（如 `/v1/auth/register`、`/v1/auth/apple`、上传、导出下载、管理接口）无独立速率限制。

---

## 6) 输入验证覆盖率

结论：**FAIL（整体较好，但存在关键缺口）**

### 6.1 覆盖较好（PASS 证据）

1. 账号注册校验完整：`proxy/server.py:1571-1582`
```python
if not isinstance(email, str) or not email.strip(): ...
if not _is_valid_email(email_norm): ...
if len(password) < 8 or len(password) > 72: ...
```

2. 文件上传校验：`proxy/server.py:2688-2701`, `proxy/server.py:2707-2708`
```python
if len(raw) > MAX_FILE_SIZE: ...
if detected_mime ... else: raise HTTPException(status_code=415, ...)
if not re.fullmatch(r"\.[a-z0-9]{1,10}", ext or ""):
    ext = ""
```

3. Push 参数校验：`proxy/server.py:2166-2171`
```python
if platform not in ("ios", "android"): ...
if not push_token: ...
if len(push_token) > 2048: ...
```

### 6.2 明确缺口（FAIL 证据）

1. `PATCH /v1/crash-reports/{report_id}` 未校验 `body` 为对象：`proxy/server.py:3593-3599`
```python
body = await request.json()
new_status = body.get("status")
```
如果 `body` 是 list/string，会触发 `AttributeError`（500），不是预期 400。

2. 管理密钥提取使用简单替换：`proxy/server.py:3564`, `proxy/server.py:3589`
```python
request.headers.get("authorization", "").replace("Bearer ", "")
```
格式验证弱于统一 `_parse_bearer`。

---

## 7) CORS 配置

结论：**FAIL**

证据：未发现 `CORSMiddleware` 或 `add_middleware` 调用。

1. 应用初始化：`proxy/server.py:1469`
```python
app = FastAPI(title="OpenClaw Proxy", version="0.1.0")
```

2. 全文件搜索未命中：`CORSMiddleware` / `allow_origins` / `add_middleware`。

影响：浏览器跨域策略完全依赖默认行为（通常等同未放行跨域），既不显式限制也不显式声明，容易在部署层被误配。

---

## 汇总结论

- 1) SQL 参数化：**FAIL**（4 处动态 SQL）
- 2) 认证比较：**FAIL**（`compare_digest` 与 `!=` 混用）
- 3) `os.getenv` fallback：**FAIL**（大量硬编码默认值）
- 4) 文件操作/路径穿越：**FAIL**（缺少 DB 路径的 base-dir 约束）
- 5) Rate limiting：**FAIL**（仅登录与配额，缺少全局/关键端点限流）
- 6) 输入验证：**FAIL**（局部缺口可导致 500）
- 7) CORS：**FAIL**（无显式配置）
