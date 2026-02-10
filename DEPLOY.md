# ClawPhones Device Deployment Guide

How to build, sign, and install ClawPhones on a real phone.

---

## 1. Backend (Proxy) — Start Here

The phone apps need a running proxy. Start one locally or use production.

```bash
cd proxy
pip install -r requirements.txt

# Option A: Mock mode (no real LLM, good for UI testing)
MOCK_MODE=1 ADMIN_KEY=devadmin LISTEN_PORT=8080 python server.py

# Option B: Real LLMs
ANTHROPIC_API_KEY=sk-... \
DEEPSEEK_API_KEY=sk-... \
KIMI_API_KEY=sk-... \
ADMIN_KEY=your_admin_key \
LISTEN_PORT=8080 \
python server.py
```

Generate a test token:

```bash
curl -X POST http://127.0.0.1:8080/admin/generate-token \
  -H "X-Admin-Key: devadmin" \
  -H "Content-Type: application/json" \
  -d '{"tier": "free", "note": "test device"}'
# Returns: {"token": "ocw1_..."}
```

> **Production URL:** `https://api.openclaw.ai` — use this if proxy is already deployed.

---

## 2. Android

### Prerequisites

- Android Studio (or JDK 17 + Android SDK 34 command-line tools)
- USB cable + Android phone (7.0+ / API 24+)
- Phone: Settings → Developer Options → USB Debugging ON

### Build & Install

```bash
cd android/clawphones-android

# Build debug APK
./gradlew assembleDebug

# APK location:
# app/build/outputs/apk/debug/app-debug.apk

# Install via USB (phone must be connected)
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or from Android Studio:
1. Open `android/clawphones-android/` as project
2. Wait for Gradle sync
3. Connect phone via USB, select it as target
4. Click Run (▶)

### First Launch

1. App opens → ClawPhones setup screen
2. Enter API endpoint: `http://<your-ip>:8080` (or `https://api.openclaw.ai`)
3. Enter token: `ocw1_...` (from step 1)
4. Start chatting

### Troubleshooting

| Issue | Fix |
|-------|-----|
| `INSTALL_FAILED_NO_MATCHING_ABIS` | Phone is 32-bit; build only supports arm64. Change `abiFilters` in `app/build.gradle` |
| Gradle sync fails | Check JDK version: `java -version` must be 17+ |
| Can't find device | Run `adb devices`, check USB debugging is enabled |
| Bootstrap download fails | Check internet; bootstrap downloads from GitHub on first build |

---

## 3. iOS

### Prerequisites

- Mac with Xcode 15+ installed
- Apple Developer account (paid $99/year, or free with 7-day limit)
- USB cable + iPhone (iOS 17+)
- Your **Team ID** (10-char alphanumeric, from developer.apple.com → Membership)

### Step 1: Set Team ID

Replace `REPLACE_ME` with your actual Team ID:

**Option A — Xcode UI (recommended):**
1. Open `ios/ClawPhones.xcodeproj` in Xcode
2. Select ClawPhones target → Signing & Capabilities
3. Check "Automatically manage signing"
4. Select your team from dropdown
5. Xcode auto-generates provisioning profile

**Option B — Command line:**
```bash
cd ios
# Replace REPLACE_ME with your actual Team ID
sed -i '' 's/DEVELOPMENT_TEAM = REPLACE_ME/DEVELOPMENT_TEAM = YOUR_TEAM_ID/' \
  ClawPhones.xcodeproj/project.pbxproj
```

### Step 2: Build & Run

**From Xcode:**
1. Connect iPhone via USB
2. Trust the computer on your iPhone
3. Select your iPhone as build destination (top bar)
4. Click Run (▶) or ⌘R
5. First time: iPhone will ask to trust the developer
   → Settings → General → VPN & Device Management → trust your certificate

**From CLI:**
```bash
cd ios
xcodebuild -scheme ClawPhones \
  -destination 'platform=iOS,name=YourPhoneName' \
  -allowProvisioningUpdates \
  build
```

### Step 3: Configure App

1. App opens → Setup screen
2. Enter API URL: `https://api.openclaw.ai`
3. Enter token: `ocw1_...`
4. Save → Start chatting

### Troubleshooting

| Issue | Fix |
|-------|-----|
| "No signing certificate" | Xcode → Settings → Accounts → Download manual profiles |
| "Untrusted Developer" | iPhone → Settings → General → VPN & Device Management → Trust |
| "Failed to register bundle identifier" | Bundle ID `ai.clawphones.app` may need to be registered at developer.apple.com → Identifiers |
| Free account: "Maximum apps reached" | Free accounts allow 3 apps; delete old ones from device |
| Free account: App expires in 7 days | Re-deploy from Xcode every 7 days (paid account = no limit) |

---

## 4. Testing Checklist

After installing on device, verify these work:

- [ ] App launches without crash
- [ ] Setup screen accepts API URL + token
- [ ] New conversation creates successfully
- [ ] Send text message → receive response
- [ ] Conversation appears in list
- [ ] Tap existing conversation → loads messages
- [ ] Delete conversation works
- [ ] App handles offline gracefully (no crash)
- [ ] Voice input works (Android: STT, iOS: microphone permission)

---

## 5. Common Network Issues

**Phone can't reach local proxy:**
- Phone and laptop must be on same WiFi
- Use laptop's local IP (not `localhost`): `ifconfig | grep 192`
- Mac firewall: System Settings → Network → Firewall → allow Python
- Alternative: use `ngrok http 8080` to get a public URL

**Using production API:**
- Set endpoint to `https://api.openclaw.ai`
- Token must be a real provisioned `ocw1_*` token

---

## 6. Release Build (when ready for production)

### Android
```bash
# Generate release keystore (one-time)
keytool -genkey -v -keystore clawphones-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias clawphones

# Add to app/build.gradle signingConfigs.release
# Build signed APK
./gradlew assembleRelease
```

### iOS
```bash
# Archive for App Store / TestFlight
xcodebuild -scheme ClawPhones \
  -destination 'generic/platform=iOS' \
  -archivePath build/ClawPhones.xcarchive \
  archive

# Export IPA
xcodebuild -exportArchive \
  -archivePath build/ClawPhones.xcarchive \
  -exportPath build/ipa \
  -exportOptionsPlist ExportOptions.plist
```
