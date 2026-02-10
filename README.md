# ClawPhones Mobile

Multi-platform mobile AI agent powered by the OpenClaw LLM proxy.
Users see a single chat interface (voice + text); the backend transparently routes to the right model tier.

## Repository Structure

```
proxy/                      Python FastAPI backend (837 lines)
                            Conversation management, tiered LLM routing,
                            device-token auth, rate limiting & fallback
ios/                        SwiftUI iOS app (BundleID: ai.clawphones.app)
android/clawphones-android/ Android app (package: ai.clawphones.agent)
```

## API Overview

| Field | Value |
|-------|-------|
| Base URL | `https://api.openclaw.ai` |
| Auth | Bearer token (`ocw1_` prefix) |
| Protocol | OpenAI-compatible (`/v1/chat/completions`) |

### LLM Tiers

| Tier | Backend | Use Case |
|------|---------|----------|
| Free | DeepSeek | Cost-efficient daily chat |
| Pro | Kimi | Long-context reasoning |
| Max | Claude | Highest quality / stability |

### Conversation API

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/v1/conversations` | Create a conversation |
| GET | `/v1/conversations` | List conversations |
| GET | `/v1/conversations/{id}` | Get conversation detail |
| DELETE | `/v1/conversations/{id}` | Delete a conversation |
| POST | `/v1/conversations/{id}/chat` | Send message (streaming) |

## Quick Start

### Proxy (local dev)

```bash
cd proxy
pip install -r requirements.txt
MOCK_MODE=1 ADMIN_KEY=devadmin LISTEN_PORT=8080 python server.py
# Smoke test
curl http://127.0.0.1:8080/health
```

### iOS

Open `ios/ClawPhones.xcodeproj` in Xcode 15+. Requires iOS 17 SDK.

```bash
cd ios
xcodebuild -scheme ClawPhones -sdk iphonesimulator build
```

### Android

Requires JDK 17 and Android SDK 34.

```bash
cd android/clawphones-android
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

## Brand

**ClawPhones** -- [clawphones.com](https://clawphones.com)

## License

GPLv3. See `LICENSE` in the repository root.
