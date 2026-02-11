# Mac-2 交叉验收报告：Sprint 7-10 + 安全审计

- 验收时间: 2026-02-11
- 验收方式: 静态代码审计 + 本机 release 构建实测
- 验收范围: `proxy/`、`ios/ClawPhones`、`android/clawphones-android/app`

## Sprint 7

### 7.1 Markdown 增强（代码高亮/表格）
**结论: PASS**

- Android 代码高亮/表格预处理:
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ChatActivity.java:1848`
    - `String normalized = preprocessTables(source);`
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ChatActivity.java:1854`
    - `html = html.replaceAll("```([^`]{0,20000})```", "<pre>$1</pre>");`
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ChatActivity.java:1991`
    - `private static String preprocessTables(String markdown) {`
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ChatActivity.java:2008`
    - `output.append("```table\\n").append(table).append("\\n```");`
- iOS 代码块解析/高亮:
  - `ios/ClawPhones/Views/MessageRow.swift:236`
    - `private func codeBlockView(language: String?, code: String) -> some View {`
  - `ios/ClawPhones/Views/MessageRow.swift:245`
    - `highlightedCodeText(code: code, language: language)`
  - `ios/ClawPhones/Views/MessageRow.swift:257`
    - `let pattern = "```([A-Za-z0-9_+-]*)[ \\t]*\\n([\\s\\S]*?)```"`

### 7.2 图片上传（要求 `POST /v1/upload`）
**结论: FAIL**

- 实际后端路由为会话内上传，不是验收要求的 `/v1/upload`:
  - `proxy/server.py:2783`
    - `@app.post("/v1/conversations/{conversation_id}/upload")`
- 全仓库未找到 `@app.post("/v1/upload")`。
- 客户端未接入 upload 调用链（聊天体仅 `message`）:
  - `ios/ClawPhones/Services/OpenClawAPI.swift:508`
    - `private struct ChatRequestBody: Encodable { let message: String }`
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ClawPhonesAPI.java:390`
    - `body.put("message", message);`

### 7.3 文件分享
**结论: PASS**

- iOS Share Extension + Deep Link + 主 App 桥接已实现:
  - `ios/ClawPhonesShare/ShareViewController.swift:82`
    - `try openMainApp(payloadID: payload.id)`
  - `ios/ClawPhonesShare/ShareViewController.swift:214`
    - `URL(string: "clawphones://share?payload=...")`
  - `ios/ClawPhones/Services/SharePayloadBridge.swift:33`
    - `guard url.scheme == "clawphones" else { return nil }`
- 文件读取接口存在:
  - `proxy/server.py:2870`
    - `@app.get("/v1/files/{file_id}")`

## Sprint 8

### 8.1 Biometric Auth 双端（`LAContext` / `BiometricPrompt`）
**结论: PASS**

- iOS:
  - `ios/ClawPhones/Services/BiometricAuthService.swift:33`
    - `let context = LAContext()`
  - `ios/ClawPhones/Services/BiometricAuthService.swift:67`
    - `return try await context.evaluatePolicy(...)`
- Android:
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/BiometricLockController.java:14`
    - `import androidx.biometric.BiometricPrompt;`
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/BiometricLockController.java:197`
    - `promptHolder[0] = new BiometricPrompt(activity, executor, callback);`

### 8.2 Account Deletion（级联删除）
**结论: PASS**

- 后端级联删除覆盖 users/device_tokens/push_tokens/conversations/messages/usage/crash_reports/user_exports:
  - `proxy/server.py:2406`
    - `@app.delete("/v1/user/account", status_code=204)`
  - `proxy/server.py:2431`
    - `DELETE FROM messages WHERE conversation_id IN (...)`
  - `proxy/server.py:2443`
    - `DELETE FROM conversations WHERE device_token IN (...)`
  - `proxy/server.py:2452`
    - `DELETE FROM usage_daily WHERE token IN (...)`
  - `proxy/server.py:2461`
    - `DELETE FROM crash_reports WHERE device_token IN (...)`
  - `proxy/server.py:2468`
    - `DELETE FROM push_tokens WHERE user_id=?`
  - `proxy/server.py:2471`
    - `DELETE FROM users WHERE id=?`
- 双端调用存在:
  - `ios/ClawPhones/Services/OpenClawAPI.swift:254`
    - `func deleteAccount(confirm: Bool = true) async throws`
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ClawPhonesAPI.java:507`
    - `public static void deleteAccount(Context context)`

### 8.3 Data Export
**结论: PASS**

- 后端:
  - `proxy/server.py:2316`
    - `@app.post("/v1/user/export")`
  - `proxy/server.py:2360`
    - `@app.get("/v1/user/export/{export_id}", name="user_download_export")`
- iOS:
  - `ios/ClawPhones/Services/OpenClawAPI.swift:304`
    - `func createUserDataExport() async throws -> UserDataExportResponse`
  - `ios/ClawPhones/Services/OpenClawAPI.swift:345`
    - `func exportUserDataToTemporaryFile() async throws -> URL`
  - `ios/ClawPhones/Views/SettingsView.swift:119`
    - `if let fileURL = await viewModel.exportMyData() { ... }`
- Android:
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ClawPhonesAPI.java:444`
    - `createUserDataExport(...)`
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ClawPhonesAPI.java:452`
    - `downloadUserDataExport(...)`
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ChatSettingsActivity.java:306`
    - `private void exportUserData()`

## Sprint 9

### 9.1 Launch 优化（lazy loading）
**结论: PASS**

- iOS 延迟初始化:
  - `ios/ClawPhones/App/ClawPhonesApp.swift:17`
    - `startupCoordinator.startDeferredInitialization()`
  - `ios/ClawPhones/App/ClawPhonesApp.swift:43`
    - `lazy var crashReporter: CrashReporter = CrashReporter.shared`
- Android 延迟非关键初始化:
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/ClawPhonesApp.java:18`
    - `keeps cold-start work minimal and defers non-critical setup`
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/ClawPhonesApp.java:41`
    - `mainHandler.post(() -> DEFERRED_INIT_EXECUTOR.execute(() -> { ... }))`

### 9.2 Memory 优化（`onTrimMemory` / 分页）
**结论: PASS**

- Android:
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ChatActivity.java:243`
    - `public void onTrimMemory(int level)`
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ChatActivity.java:255`
    - `if (... mAllHistoryMessages.size() > MESSAGE_PAGE_SIZE * 2) { ... prune ... }`
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ChatActivity.java:111`
    - `private static final int MESSAGE_PAGE_SIZE = 80;`
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ChatActivity.java:416`
    - `if (mLoadingOlderHistory || mNextHistoryLoadStart <= 0 ...) return;`
- iOS:
  - `ios/ClawPhones/ViewModels/ChatViewModel.swift:558`
    - `private func applyPagedMessages(_ source: [Message])`
  - `ios/ClawPhones/ViewModels/ChatViewModel.swift:574`
    - `private func observeMemoryWarnings()`
  - `ios/ClawPhones/ViewModels/ChatViewModel.swift:588`
    - `private func compactHiddenHistoryForMemoryPressure()`

## Sprint 10

### 10.1 Analytics 双端（批量上报）
**结论: PASS**

- 后端批量接收:
  - `proxy/server.py:1595`
    - `@app.post("/v1/analytics/events")`
  - `proxy/server.py:1603`
    - `if isinstance(body, list): events = body`
  - `proxy/server.py:1662`
    - `await db.executemany("INSERT INTO analytics_events ...", rows)`
- iOS 批量 flush:
  - `ios/ClawPhones/Services/AnalyticsService.swift:92`
    - `let batch = pendingEvents`
  - `ios/ClawPhones/Services/AnalyticsService.swift:107`
    - `private func send(batch: [[String: Any]], ...)`
- Android 批量 flush:
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/analytics/AnalyticsManager.java:98`
    - `final List<JSONObject> batch;`
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/analytics/AnalyticsManager.java:117`
    - `private boolean sendBatch(List<JSONObject> batch)`

### 10.2 App Store Prep（`Info.plist` 隐私 / `build.gradle` release）
**结论: PASS**

- iOS 隐私键:
  - `ios/ClawPhones/Config/Info.plist:57` `NSCameraUsageDescription`
  - `ios/ClawPhones/Config/Info.plist:59` `NSPhotoLibraryUsageDescription`
  - `ios/ClawPhones/Config/Info.plist:61` `NSMicrophoneUsageDescription`
  - `ios/ClawPhones/Config/Info.plist:63` `NSSpeechRecognitionUsageDescription`
  - `ios/ClawPhones/Config/Info.plist:65` `NSFaceIDUsageDescription`
- Android release 配置:
  - `android/clawphones-android/app/build.gradle:106`
    - `release { signingConfig signingConfigs.release ... }`
  - `android/clawphones-android/app/build.gradle:108`
    - `minifyEnabled true`
  - `android/clawphones-android/app/build.gradle:110`
    - `proguardFiles ...`

### 10.3 Release Build
**结论: FAIL（环境阻塞）**

- iOS 命令:
  - `xcodebuild -project ios/ClawPhones/ClawPhones.xcodeproj -scheme ClawPhones -configuration Release -sdk iphonesimulator -derivedDataPath ios/build-sim build`
  - 报错: `xcodebuild requires Xcode, active developer directory '/Library/Developer/CommandLineTools' is a command line tools instance`
- Android 命令:
  - `./gradlew assembleRelease`
  - 报错: `Unable to locate a Java Runtime.`

## 安全审计

### 1) SQL 注入：非参数化查询清单
**结论: PASS（未发现“用户输入直接拼接 SQL”）**

- `proxy/server.py:579`
  - `f"FROM conversation_files WHERE conversation_id=? AND id IN ({placeholders})"`
- `proxy/server.py:610`
  - `f"FROM conversation_files WHERE conversation_id=? AND id IN ({placeholders})"`
- `proxy/server.py:1909`
  - `await db.execute(f"UPDATE users SET {', '.join(updates)} WHERE id=?", tuple(params))`
- `proxy/server.py:2100`
  - `sql = f"UPDATE users SET {', '.join(updates)} WHERE id=?"`
- `proxy/server.py:3452`
  - `await db.execute(f"DELETE FROM push_tokens WHERE id IN ({placeholders})", tuple(dedup_ids))`
- `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/MessageQueue.java:61`
  - `db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_PENDING + " (...")`
- `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/MessageQueue.java:69`
  - `db.execSQL("CREATE INDEX IF NOT EXISTS ... ON " + TABLE_PENDING + "(...)")`
- `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/MessageQueue.java:136`
  - `db.execSQL("UPDATE " + TABLE_PENDING + " SET ... WHERE ... = ?", new Object[]{id})`
- `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ConversationCache.java:46`
  - `"CREATE TABLE IF NOT EXISTS " + TABLE_CONVERSATIONS + " (..."`
- `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ConversationCache.java:61`
  - `"CREATE TABLE IF NOT EXISTS " + TABLE_MESSAGES + " (..."`
- `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ConversationCache.java:296`
  - `"DELETE FROM " + TABLE_CONVERSATIONS + " ..."`
- `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ConversationCache.java:323`
  - `"DELETE FROM " + TABLE_MESSAGES + " ..."`

备注: 以上为非参数化/动态 SQL 位置；当前可变部分主要来自白名单字段、常量表名或 `?` 占位符集合。

### 2) Admin Auth：列出所有 admin 认证，并标记 `==/!=` vs `compare_digest`
**结论: FAIL（实现不一致）**

- 使用 `compare_digest`:
  - `proxy/server.py:3463`
    - `if not x_admin_key or not secrets.compare_digest(x_admin_key, ADMIN_KEY):`
  - 覆盖端点:
    - `proxy/server.py:3467` `/admin/tokens/generate`
    - `proxy/server.py:3501` `/admin/tokens/{token}/tier`
    - `proxy/server.py:3529` `/admin/push/announcement`
- 使用普通比较（不是 `compare_digest`）:
  - `proxy/server.py:3618`
    - `if admin != ADMIN_KEY:` (`GET /v1/crash-reports`)
  - `proxy/server.py:3643`
    - `if admin != ADMIN_KEY:` (`PATCH /v1/crash-reports/{report_id}`)

备注: 未发现 `== ADMIN_KEY`，但存在 `!= ADMIN_KEY`。

### 3) 硬编码 Key / Secret / Password
**结论: FAIL**

- `proxy/scripts/auto_fix_dispatcher.py:35`
  - `ADMIN_KEY = "<REDACTED_ADMIN_KEY>"`
- `proxy/scripts/crash_analyzer.py:33`
  - `ADMIN_KEY = "<REDACTED_ADMIN_KEY>"`
- `proxy/test_api.sh:6`
  - `ADMIN_KEY="<REDACTED_ADMIN_KEY>"`
- `android/clawphones-android/app/build.gradle:91`
  - `storePassword 'xrj45yWGLbsO7W0v'`
- `android/clawphones-android/app/build.gradle:92`
  - `keyPassword 'xrj45yWGLbsO7W0v'`

### 4) Rate Limiting：未限速端点
**结论: FAIL（仅登录限速）**

- 登录限速证据:
  - `proxy/server.py:69` `_is_login_rate_limited(...)`
  - `proxy/server.py:1750` `if _is_login_rate_limited(ip, now):`
- 非通用限速（与请求速率不同）:
  - `proxy/server.py:2531` `raise HTTPException(status_code=429, detail="daily quota exceeded")`
- 下列端点未见独立限速或全局限速中间件:
  - `proxy/server.py:1595` `/v1/analytics/events`
  - `proxy/server.py:1671` `/v1/auth/register`
  - `proxy/server.py:1804` `/v1/auth/apple`
  - `proxy/server.py:1986` `/v1/auth/refresh`
  - `proxy/server.py:2052` `/v1/user/profile`
  - `proxy/server.py:2266` `/v1/user/push-token`
  - `proxy/server.py:2316` `/v1/user/export`
  - `proxy/server.py:2646` `/v1/chat/completions`
  - `proxy/server.py:2681` `/v1/conversations`
  - `proxy/server.py:2783` `/v1/conversations/{conversation_id}/upload`
  - `proxy/server.py:2900` `/v1/conversations/{conversation_id}/chat`
  - `proxy/server.py:3024` `/v1/conversations/{conversation_id}/chat/stream`
  - `proxy/server.py:3467` `/admin/tokens/generate`
  - `proxy/server.py:3576` `/v1/crash-reports`

### 5) 路径穿越：文件 serve 路径校验
**结论: FAIL（缺少显式路径边界校验）**

- 导出下载:
  - `proxy/server.py:2383` `file_path = str(row["file_path"])`
  - `proxy/server.py:2393` `if not os.path.isfile(file_path): ...`
  - `proxy/server.py:2398` `return FileResponse(path=file_path, ...)`
- 上传文件读取:
  - `proxy/server.py:2894` `path = str(row["stored_path"])`
  - `proxy/server.py:2895` `if not os.path.isfile(path): ...`
  - `proxy/server.py:2897` `return FileResponse(path=path, ...)`
- 未见 `realpath/normpath + 目录前缀校验`（例如必须在 `EXPORT_DIR` / `UPLOAD_DIR` 下）。

## 总结

- Sprint 7: 部分通过（Markdown/文件分享通过，`POST /v1/upload` 不符合要求）
- Sprint 8: 通过
- Sprint 9: 通过
- Sprint 10: 部分通过（功能与配置通过，release build 受环境阻塞）
- 安全审计: 存在整改项（Admin 认证一致性、硬编码凭据、全局限速、文件路径边界校验）
