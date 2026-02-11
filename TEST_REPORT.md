# ClawPhones Test Report

**Date:** 2026-02-10 14:00 PST
**Server:** http://3.142.69.6:8080
**Simulator:** iPhone 17 Pro (iOS 26.2)
**Tester:** Claude Code (Opus 4.6) — Automated

---

## Summary

| Category | Pass | Fail | Skip | Total |
|----------|------|------|------|-------|
| API Core (T01-T09) | 9 | 0 | 0 | 9 |
| Input Validation (T13-T19) | 7 | 0 | 0 | 7 |
| Token/Admin (T20) | 0 | 0 | 1 | 1 |
| Multi-Tier LLM (T21-T24) | 4 | 0 | 0 | 4 |
| iOS Simulator (T10) | 1 | 0 | 0 | 1 |
| **TOTAL** | **21** | **0** | **1** | **22** |

**Result: ✅ 21/22 PASS, 0 FAIL, 1 SKIP (95.5% pass rate)**

---

## Detailed Results

### P0: Core API Tests

| # | Test | Result | Detail |
|---|------|--------|--------|
| T01 | Health Check | ✅ PASS | `{"ok":true}` |
| T02a | Token Gen (free) | ✅ PASS | `ocw1_lVftpa3I...` |
| T02b | Token Gen (pro) | ✅ PASS | `ocw1_KrEQfL3r...` |
| T02c | Token Gen (max) | ✅ PASS | `ocw1_ham-46Gl...` |
| T03 | Create Conv (empty body) | ✅ PASS | UUID returned, HTTP 200 |
| T04 | Create Conv (system_prompt) | ✅ PASS | UUID returned, HTTP 200 |
| T05 | Chat + AI Reply | ✅ PASS | DeepSeek replied "4" to "2+2" |
| T06 | List Conversations | ✅ PASS | count=2 |
| T07 | Get Conversation Detail | ✅ PASS | messages=2 (user + assistant) |
| T08 | Delete Conversation | ✅ PASS | `deleted=true` |
| T09 | No Token → 401 | ✅ PASS | HTTP 401 |

### P1: Input Validation & Error Handling

| # | Test | Result | Detail |
|---|------|--------|--------|
| T13 | Chinese Message | ✅ PASS | AI replied "1+1等于2" in Chinese |
| T14 | Long Message (1000 chars) | ✅ PASS | HTTP 200 |
| T15 | Too-Long Message (51K chars) | ✅ PASS | HTTP 400 (rejected) |
| T16 | Empty Message | ✅ PASS | HTTP 400 (rejected) |
| T17 | Invalid JSON Body | ✅ PASS | HTTP 400 (rejected) |
| T18 | Delete Non-Existent Conv | ✅ PASS | HTTP 404 |
| T19 | Cross-User Access | ✅ PASS | HTTP 404 (token A can't see token B's data) |

### P1: Token Admin

| # | Test | Result | Detail |
|---|------|--------|--------|
| T20 | Disabled Token → 403 | ⏭️ SKIP | No `/admin/tokens/{token}/status` endpoint exists |

### P2: Multi-Tier LLM Routing

| # | Test | Result | Detail |
|---|------|--------|--------|
| T21 | Free Tier → DeepSeek | ✅ PASS | HTTP 200, got AI reply |
| T22 | Pro Tier → Kimi | ✅ PASS | HTTP 200, got AI reply |
| T23 | Max Tier → Claude | ✅ PASS | HTTP 200, got AI reply |
| T24 | Free Cannot Force Claude | ✅ PASS | HTTP 403 (blocked) |

### P3: iOS Simulator

| # | Test | Result | Detail |
|---|------|--------|--------|
| T10 | App Build + Launch | ✅ PASS | BUILD SUCCEEDED, PID 84941 |
| T10a | Conversation List Loaded | ✅ PASS | 3 conversations visible, no crash |
| T10b | Network Connectivity | ✅ PASS | HTTP 200, 67ms RTT to server |
| T10c | No Crash in Logs | ✅ PASS | Clean log, no exceptions |

---

## Bugs Fixed During Testing

### BUG-001: Server crash on empty POST body (FIXED)
- **Symptom:** Tapping + in iOS app → "Internal Server Error"
- **Root Cause:** `server.py` line 577: `await request.json()` crashes with `JSONDecodeError` when iOS sends POST without body
- **Fix:** Wrapped all 5 `request.json()` calls in `try/except`, defaulting to `{}` for create_conversation and raising 400 for endpoints that require a body
- **Files:** `proxy/server.py` (5 locations)
- **Verified:** T03 now passes — empty body creates conversation successfully

---

## Screenshots

| Screenshot | Path | Description |
|-----------|------|-------------|
| T10 Launch | `/tmp/clawphones_T10_launch.png` | App launched, conversation list visible |
| T10 Loaded | `/tmp/clawphones_T10_loaded.png` | Same view 2s later, stable |
| Earlier test | `/tmp/clawphones_screen1.png` | Initial working state |

---

## Known Limitations (Not Bugs)

1. **No streaming** — Chat responses appear all at once (not word-by-word)
2. **No offline mode** — App requires network connectivity
3. **No pagination** — Only loads first 20 conversations
4. **No retry on failure** — User must manually resend failed messages
5. **No token disable endpoint** — Admin can't disable tokens via API (T20 skipped)
6. **HTTP only** — Server uses HTTP, not HTTPS (ATS override required)

---

## Recommendations for Next Steps

1. **Add `/admin/tokens/{token}/status` endpoint** — Enable token disable/enable
2. **Add HTTPS** — Use Let's Encrypt or Cloudflare for TLS
3. **Add streaming** — SSE for real-time chat responses
4. **Add XCUITest suite** — Automated UI tests for iOS
5. **Add Android build verification** — Team needs to build Android APK

---

## Test Environment

- **macOS:** Sequoia (Apple Silicon)
- **Xcode:** Latest (iOS 26.2 SDK)
- **Simulator:** iPhone 17 Pro (0E90379B-E6C7-4913-96DE-23F02BF2724F)
- **Server:** AWS EC2 t3.micro, Python 3.11, FastAPI + uvicorn
- **LLM Providers:** DeepSeek (free), Kimi K2 (pro), Claude Sonnet 4 (max) via OpenRouter
- **Admin Key:** <REDACTED_ADMIN_KEY>
