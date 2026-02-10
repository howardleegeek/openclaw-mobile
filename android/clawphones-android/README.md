# BotDrop

Run AI agents on your Android phone — no terminal, no CLI, just a guided setup.

BotDrop wraps [OpenClaw](https://github.com/nicepkg/openclaw) into a user-friendly Android app. Install, configure, and manage your AI agent in 4 simple steps.

## Features

- **Guided 4-step setup** — Auth → Agent → Install → Channel
- **Multi-provider support** — Anthropic, OpenAI, Google Gemini, OpenRouter, and more
- **Telegram & Discord integration** — Chat with your agent through your favorite messenger
- **Background gateway** — Keeps your agent running with auto-restart
- **No terminal required** — Everything happens through the GUI

## Installation

### Download APK

Download the latest APK from [Releases](../../releases).

### Build from Source

Prerequisites:
- Android SDK (API level 34+)
- NDK r29+
- JDK 17+

```bash
git clone https://github.com/louzhixian/botdrop.git
cd botdrop
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/`.

## Architecture

BotDrop is built on [Termux](https://github.com/termux/termux-app), providing a Linux environment for running Node.js-based AI agents on Android.

```
┌──────────────────────────────────┐
│     BotDrop UI (app.botdrop)     │
├──────────────────────────────────┤
│     Termux Core (com.termux)     │
├──────────────────────────────────┤
│  Linux Environment (proot/apt)   │
├──────────────────────────────────┤
│  OpenClaw + Node.js + npm        │
└──────────────────────────────────┘
```

See [docs/design.md](docs/design.md) for detailed architecture.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE).

Built on [Termux](https://github.com/termux/termux-app) (GPLv3).
