# Sprint 2-5 交叉验证验收报告

## 1) strings.xml 行数 + getString 调用数
**结论：PASS**

- `android/clawphones-android/app/src/main/res/values/strings.xml` 行数：**540**
- `android/clawphones-android/app/src/main/java/ai/clawphones/agent/**` 中 `getString(R.string...)` 调用数：**216**

文件与行号：
- `android/clawphones-android/app/src/main/res/values/strings.xml:1`
- `android/clawphones-android/app/src/main/res/values/strings.xml:416`
- `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ChatSettingsActivity.java:167`

代码片段：
```xml
<!-- android/.../strings.xml -->
<string name="onboarding_title_ai_assistant">AI Assistant</string>
<string name="onboarding_desc_ai_assistant">ClawPhones is your built-in AI assistant...</string>
```

```java
mLanguageOptions.add(new Option("auto", getString(R.string.settings_language_auto)));
mLanguageOptions.add(new Option("zh", getString(R.string.settings_language_zh)));
mLanguageOptions.add(new Option("en", getString(R.string.settings_language_en)));
```

## 2) Markdown 渲染文件
**结论：PASS**

文件与行号：
- `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ChatActivity.java:2446`
- `ios/ClawPhones/Views/MessageRow.swift:231`

代码片段：
```java
private static CharSequence renderMarkdown(String markdown) {
    ...
    Spanned spanned = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY);
    return applyQuoteBackground(spanned);
}
```

```swift
private func markdownTextView(_ markdown: String) -> some View {
    if let attributed = try? AttributedString(markdown: markdown, options: .init(interpretedSyntax: .full)) {
        Text(attributed)
    } else {
        Text(markdown)
    }
}
```

## 3) SSE streaming 双端
**结论：PASS**

文件与行号：
- `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ClawPhonesAPI.java:609`
- `ios/ClawPhones/Services/OpenClawAPI.swift:435`
- `proxy/server.py:2937`

代码片段：
```java
// POST /v1/conversations/{id}/chat/stream -> SSE streaming response.
conn.setRequestProperty("Accept", "text/event-stream");
```

```swift
func chatStream(...) -> AsyncThrowingStream<StreamChunk, Error> {
    request.addValue("text/event-stream", forHTTPHeaderField: "Accept")
}
```

```python
@app.post("/v1/conversations/{conversation_id}/chat/stream")
async def conversation_chat_stream(...):
    return StreamingResponse(stream_gen(), media_type="text/event-stream", headers=headers)
```

## 4) Token refresh 双端
**结论：PASS**

文件与行号：
- `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ClawPhonesAPI.java:296`
- `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ClawPhonesAPI.java:797`
- `ios/ClawPhones/Services/OpenClawAPI.swift:196`
- `ios/ClawPhones/Services/OpenClawAPI.swift:687`
- `proxy/server.py:1873`

代码片段：
```java
/** POST /v1/auth/refresh -> new token */
public static AuthToken refresh(Context context) ...
if (remaining < TOKEN_REFRESH_WINDOW_SECONDS) {
    AuthToken refreshed = refresh(context, token);
}
```

```swift
func refresh() async throws -> AuthRefreshResponse {
    let url = URL(string: "\(baseURLString)/v1/auth/refresh")!
}

if remaining < tokenRefreshWindowSeconds {
    let refreshed = try await refresh()
}
```

```python
@app.post("/v1/auth/refresh")
async def auth_refresh(request: Request) -> Any:
    ...
    return {"token": new_token, "tier": tier, "expires_at": expires_at}
```

## 5) Settings 集成
**结论：PASS**

文件与行号：
- `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ConversationListActivity.java:79`
- `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ChatSettingsActivity.java:88`
- `ios/ClawPhones/App/ContentView.swift:36`
- `ios/ClawPhones/Views/SettingsView.swift:9`
- `ios/ClawPhones/ViewModels/SettingsViewModel.swift:201`

代码片段：
```java
settings.setOnClickListener(v -> {
    Intent intent = new Intent(ConversationListActivity.this, ChatSettingsActivity.class);
    startActivity(intent);
});
```

```swift
NavigationStack {
    SettingsView()
}
.tag(Tab.settings)
```

```swift
let payload = try await OpenClawAPI.shared.getAIConfig()
aiConfig = AIConfig(...)
```

## 6) Onboarding 双端
**结论：PASS**

文件与行号：
- `android/clawphones-android/app/src/main/java/ai/clawphones/agent/ClawPhonesLauncherActivity.java:21`
- `android/clawphones-android/app/src/main/java/ai/clawphones/agent/OnboardingActivity.java:51`
- `ios/ClawPhones/App/ContentView.swift:55`
- `ios/ClawPhones/Views/OnboardingView.swift:3`

代码片段：
```java
boolean hasSeenOnboarding = prefs.getBoolean(OnboardingActivity.KEY_HAS_SEEN_ONBOARDING, false);
Class<?> nextActivity = hasSeenOnboarding ? LoginActivity.class : OnboardingActivity.class;
```

```swift
.fullScreenCover(isPresented: Binding(get: { !hasSeenOnboarding }, set: { _ in })) {
    OnboardingView { hasSeenOnboarding = true }
}
```

## 7) Push: FCM + APNs
**结论：FAIL**

已实现证据（服务端/Android 部分）：
- `proxy/server.py:3257`（APNs 发送）
- `proxy/server.py:3308`（FCM 发送）
- `proxy/server.py:3378`（按 `platform == "ios"|"android"` 分发）
- `android/clawphones-android/app/src/main/java/ai/clawphones/agent/ClawPhonesMessagingService.java:31`（FirebaseMessagingService）

缺失/阻断证据：
- iOS 端未发现 APNs 注册/上报代码（`registerForRemoteNotifications` / `didRegisterForRemoteNotifications` 无匹配）
- Android `ClawPhonesMessagingService` 调用 `ClawPhonesAPI.registerPushToken/cachePushToken`，但在 `ClawPhonesAPI.java` 未定位到实现
- 主 `AndroidManifest.xml` 未检索到 `ClawPhonesMessagingService` 声明

代码片段：
```python
# proxy/server.py
async def _send_apns_notification(...): ...
async def _send_fcm_notification(...): ...
if platform == "ios": ...
elif platform == "android": ...
```

```java
// ClawPhonesMessagingService.java
ClawPhonesAPI.cachePushToken(context, token);
ClawPhonesAPI.registerPushToken(context, "android", token);
```

```text
NO_IOS_PUSH_INTEGRATION_MATCH
```

## 8) ErrorHandler 双端
**结论：FAIL**

文件与行号：
- `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ErrorHandler.java:56`
- `ios/ClawPhones/Services/ErrorHandler.swift:38`

问题：
- Android 端已被调用（如 `ClawPhonesMessagingService.java:101`）
- iOS 端仅定义 `ErrorHandler`，未检索到在 View/ViewModel/API 中的调用集成

代码片段：
```java
public static boolean handle(Context context, Throwable error) {
    return handle(context, null, error, null, true);
}
```

```swift
func handle(_ error: Error, retryAction: (() -> Void)? = nil) {
    switch classify(error) { ... }
}
```

```text
# iOS usage search output: no matches
```

## 9) TTS 双端
**结论：FAIL**

结果：
- Android + iOS 主代码未命中 `TextToSpeech` / `AVSpeechSynthesizer` / `AVSpeechUtterance` / `.speak(`

代码片段（检索结果）：
```text
NO_TTS_MATCH
```

## 10) STT 双端
**结论：PASS**

文件与行号：
- `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/SpeechHelper.java:20`
- `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ChatActivity.java:1435`
- `ios/ClawPhones/Services/SpeechRecognizer.swift:11`
- `ios/ClawPhones/Views/ChatInputBar.swift:12`

代码片段：
```java
final class SpeechHelper implements RecognitionListener {
    void startListening(@Nullable Locale locale) { ... }
}
```

```swift
final class SpeechRecognizer: NSObject, ObservableObject {
    private let speechRecognizer = SFSpeechRecognizer(locale: Locale.current)
    func startListening() async { ... }
}
```

## 11) Voice UI 集成
**结论：PASS（基于 STT 语音输入 UI）**

文件与行号：
- `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ChatActivity.java:1480`
- `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ChatActivity.java:1495`
- `ios/ClawPhones/Views/ChatInputBar.swift:154`
- `ios/ClawPhones/Views/ChatInputBar.swift:188`

代码片段：
```java
mMic.setOnTouchListener((v, event) -> {
    if (action == MotionEvent.ACTION_DOWN) {
        mSpeechHelper.startListening(Locale.getDefault());
    }
    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
        mSpeechHelper.stopListening();
    }
});
```

```swift
private var micButton: some View { ... }
Task { await speechRecognizer.startListening() }
...
speechRecognizer.stopListening()
```

---

## 汇总
- PASS: 1, 2, 3, 4, 5, 6, 10, 11
- FAIL: 7, 8, 9
