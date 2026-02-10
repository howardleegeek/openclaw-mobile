# ClawPhones 快速上手

## 第一步：起后端（任意一台电脑）

```bash
cd proxy
pip install -r requirements.txt
MOCK_MODE=1 ADMIN_KEY=test LISTEN_PORT=8080 python server.py
```

记下这台电脑的 IP：
```bash
ifconfig | grep "inet 192"
# 例如 192.168.1.100
```

后端地址就是 `http://192.168.1.100:8080`

---

## 第二步：装 Android

需要：Android Studio + 手机开 USB 调试

```bash
cd android/clawphones-android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

打开 app → 输入后端地址 → 开聊

---

## 第三步：装 iOS

需要：Mac + Xcode 15+ + iPhone (iOS 17+)

1. 用 Xcode 打开 `ios/ClawPhones.xcodeproj`
2. 点 ClawPhones target → Signing & Capabilities → 选你的 Team
3. 连 iPhone → 点 Run (▶)
4. 手机上信任开发者：设置 → 通用 → VPN与设备管理 → 信任

打开 app → 输入后端地址 → 开聊
