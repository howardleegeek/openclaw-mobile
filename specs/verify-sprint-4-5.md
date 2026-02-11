# Sprint 4-5 核心功能验收报告

日期: 2026-02-11
工作目录: `/Users/howardli/.openclaw/workspace`

## 1) Push Notifications
**结果: FAIL**

- Android FCM 代码存在:
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/ClawPhonesMessagingService.java:31` (`FirebaseMessagingService` 子类)
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/ClawPhonesMessagingService.java:39` (`onNewToken`)
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/ClawPhonesMessagingService.java:45` (`onMessageReceived`)
- Android 集成缺口:
  - `android/clawphones-android/app/src/main/AndroidManifest.xml:157`（service 区域未声明 `ClawPhonesMessagingService`）
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/ClawPhonesMessagingService.java:75`（`syncToken` 仅定义，未在 app 内检索到调用）
- iOS APNs 缺失:
  - `ios/ClawPhones/App/ClawPhonesApp.swift:9`（仅 `@main App`，无 `UNUserNotificationCenter`/远程通知注册逻辑）
  - `ios/ClawPhones/Config/ClawPhones.entitlements:5`（仅 app group，无 `aps-environment`）

## 2) Error Handling
**结果: FAIL**

- 文件存在:
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ErrorHandler.java:37`
  - `ios/ClawPhones/Services/ErrorHandler.swift:10`
- Android 分类逻辑完整（401/429/5xx/网络/超时）:
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ErrorHandler.java:74`
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ErrorHandler.java:85`
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ErrorHandler.java:90`
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ErrorHandler.java:102`
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ErrorHandler.java:108`
- iOS 分类缺口:
  - `ios/ClawPhones/Services/ErrorHandler.swift:67`（定义了 `rateLimited`/`serverUnavailable`）
  - `ios/ClawPhones/Services/ErrorHandler.swift:76`（`classify` 实际未返回上述两类）
  - `ios/ClawPhones/Services/ErrorHandler.swift:203`（`extractRetryAfter` 存在但未被分类路径使用）
  - `ios/ClawPhones/Services/OpenClawAPI.swift:675`（非 401 HTTP 错误统一抛 `apiError(message)`，未传结构化状态给 ErrorHandler）

## 3) TTS
**结果: FAIL**

- 未检索到 Android `TextToSpeech` 实现（`app/src/main/java` 范围）。
- 未检索到 iOS `AVSpeechSynthesizer` / `AVSpeechUtterance` 实现（`ios/ClawPhones` 范围）。
- 当前语音相关实现为 STT 输入链路（非 TTS）:
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ChatActivity.java:91`
  - `ios/ClawPhones/Views/ChatInputBar.swift:97`

## 4) STT
**结果: PASS**

- Android `SpeechHelper.java` 完整:
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/SpeechHelper.java:20`（类定义）
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/SpeechHelper.java:43`（`startListening`）
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/SpeechHelper.java:150`（`onResults`）
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/SpeechHelper.java:161`（`onPartialResults`）
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/SpeechHelper.java:182`（错误码映射）
- iOS `SpeechRecognizer.swift` 完整:
  - `ios/ClawPhones/Services/SpeechRecognizer.swift:11`（类定义）
  - `ios/ClawPhones/Services/SpeechRecognizer.swift:50`（权限请求）
  - `ios/ClawPhones/Services/SpeechRecognizer.swift:62`（`startListening`）
  - `ios/ClawPhones/Services/SpeechRecognizer.swift:135`（`finishListening`）
  - `ios/ClawPhones/Services/SpeechRecognizer.swift:216`（Speech 权限）

## 5) Voice UI
**结果: PASS**

- Android 录音按钮已接入 ChatActivity + 布局:
  - `android/clawphones-android/app/src/main/res/layout/activity_chat.xml:84`（`message_mic`）
  - `android/clawphones-android/app/src/main/res/layout/activity_chat.xml:77`（`message_mic_pulse`）
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ChatActivity.java:168`（绑定 mic）
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ChatActivity.java:982`（`initSpeechInput`）
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ChatActivity.java:1027`（按住说话触控逻辑）
- iOS 录音按钮已接入 ChatInputBar:
  - `ios/ClawPhones/Views/ChatInputBar.swift:97`（`micButton`）
  - `ios/ClawPhones/Views/ChatInputBar.swift:126`（按住录音手势）
  - `ios/ClawPhones/Views/ChatView.swift:84`（`ChatInputBar` 集成到聊天页）

## 6) Onboarding
**结果: PASS**

- Android:
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/OnboardingActivity.java:28`（Onboarding Activity）
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/OnboardingActivity.java:51`（已看过则跳过）
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/ClawPhonesLauncherActivity.java:24`（首启路由到 Onboarding）
- iOS:
  - `ios/ClawPhones/Views/OnboardingView.swift:3`（Onboarding View）
  - `ios/ClawPhones/App/ContentView.swift:54`（未看过时 `fullScreenCover` 展示 Onboarding）

## 总结
- PASS: 3/6（STT、Voice UI、Onboarding）
- FAIL: 3/6（Push Notifications、Error Handling、TTS）
