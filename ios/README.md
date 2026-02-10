# ClawPhones iOS

**Your personal AI assistant, pre-configured and ready to use.**

Built on OpenClaw, powered by Claude AI. Designed for Oyster devices with zero-config setup.

## Architecture

```
ClawPhones iOS App (< 20 MB)
  ├─ SwiftUI Chat Interface
  ├─ Device Token Auth (MDM/Keychain)
  └─ HTTP Client → api.openclaw.ai
```

### Why This Architecture?

Following QuickClaw's proven App Store compliance strategy:
- ✅ All AI processing on cloud (no code download)
- ✅ No background services required
- ✅ Privacy-first: isolated user workspaces
- ✅ Guideline 2.5.2 compliant

## Features

- 📱 Native SwiftUI interface
- 🔑 Pre-configured device token (Oyster phones)
- 🎤 Siri Shortcuts integration
- 💬 Multi-turn conversations with context
- 📁 File management capabilities
- 🌐 Web browsing & research

## Project Structure

```
ClawPhones/
├── App/
│   ├── ClawPhonesApp.swift          # App entry point
│   └── ContentView.swift          # Main view
├── ViewModels/
│   └── ChatViewModel.swift        # Chat logic
├── Services/
│   ├── OpenClawAPI.swift          # API client
│   ├── DeviceConfig.swift         # Token management
│   └── KeychainHelper.swift       # Secure storage
├── Views/
│   ├── MessageRow.swift           # Message bubble
│   └── ChatInputBar.swift         # Input field
├── Models/
│   └── Message.swift              # Data models
└── Config/
    ├── Info.plist
    └── PrivacyInfo.xcprivacy      # Privacy manifest
```

## Development

### Requirements
- Xcode 15.0+
- iOS 17.0+ deployment target
- Swift 5.9+

### Build

```bash
cd ClawPhones
open ClawPhones.xcodeproj
# Build and run (⌘R)
```

### Configuration

Device token sources (priority order):
1. MDM Managed App Config: `ai.openclaw.device_token`
2. Keychain (factory pre-installed)
3. User authentication fallback

## App Store Compliance

### Guideline 2.5.2 - No Code Downloading
All code execution happens on `api.openclaw.ai`. The app is purely a UI client.

### Guideline 5.1.2(i) - Third-Party AI Disclosure
Privacy policy clearly states usage of Claude API via OpenClaw backend.

### Privacy Manifest
Declared API usage:
- UserDefaults: Reading MDM configuration
- Keychain: Secure token storage

## Comparison

| Feature | QuickClaw | ClawPhones |
|---------|-----------|---------|
| App Size | 16.8 MB | ~15 MB (target) |
| Runtime | Cloud | Cloud |
| Auth | Account required | **Pre-configured token** |
| Platform | iOS only | **iOS + Android** |
| Business Model | Credit-based | **Device bundled** |

## Roadmap

- [x] Architecture design
- [ ] Core API client
- [ ] Chat interface
- [ ] MDM integration
- [ ] Siri Shortcuts
- [ ] App Store submission

## License

Proprietary - Oyster Labs

---

**Reference:** Built following proven patterns from [QuickClaw](https://apps.apple.com/us/app/quickclaw/id6758868107)
