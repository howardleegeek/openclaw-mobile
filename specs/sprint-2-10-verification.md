# ClawPhones Sprint 2-10 验收报告

- 验收时间: 2026-02-11
- 验收范围: `ios/`, `android/clawphones-android/`, `proxy/server.py`
- Sprint 基线定义: 以 Sprint 2-10 提交（`9ba0517`, `433060c`, `6d36271`, `60f845e`, `69467b2`, `4c2a8ef`）变更文件作为功能文件清单

## 轮1 基础检查

**结果: FAIL**

### 1) Sprint 2-10 功能文件存在性与非空
- 基线文件总数: 90
- 缺失: 2
- 空文件: 0
- 缺失文件:
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/ChatActivity.java`
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/PlaceholderFragment.java`

### 2) iOS 编译/语法检查
- `cd ios && swift build --skip-update` 失败（该工程非 SwiftPM 工程，缺少 `Package.swift`）
- `xcodebuild` 在当前沙箱环境受限（Simulator runtime/系统目录权限）无法完成完整构建
- 回退语法检查:
  - `xcrun swiftc -typecheck` 对 `ClawPhones` target 通过（仅告警，无 error）
  - `xcrun swiftc -typecheck` 对 `ClawPhonesShare` target 通过

### 3) Android Java 语法检查
- 先修复 JDK 环境后执行 `javac` 检查
- 受当前环境（Android classpath/Gradle daemon socket 沙箱限制）影响，无法完成完整 Gradle Java 编译链
- 使用 `javac` 基础语法检查时未发现典型语法错误（如 `';' expected`、`illegal start` 等），主要报错为 Android/Firebase 符号缺失

### 4) Python 语法检查
- `cd proxy && python -c ...` 不可用（系统无 `python` 命令）
- 使用 `python3` 验证通过:
  - `cd proxy && python3 -c 'import ast; ast.parse(open("server.py").read())'`

---

## 轮2 质量检查

**结果: FAIL**

### 1) 代码规范与命名一致性
发现命名不一致（会导致跨端/后端语义不一致）:
- iOS `PlanTier` 使用 `"Free"/"Pro"/"Max"`，后端 tier 为小写 `free/pro/max`
  - `ios/ClawPhones/ViewModels/SettingsViewModel.swift:15`
  - `ios/ClawPhones/ViewModels/SettingsViewModel.swift:111`
  - `ios/ClawPhones/ViewModels/SettingsViewModel.swift:199`
- iOS persona 使用 `general/coding/writing/translation/custom`，Android 与后端使用 `assistant/coder/writer/translator/custom`
  - `ios/ClawPhones/ViewModels/SettingsViewModel.swift:39`
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ChatSettingsActivity.java:44`
  - `proxy/server.py:2227`

### 2) TODO/FIXME/HACK 遗留
- 共发现 9 处（未清零）
- 代表位置:
  - `ios/ClawPhones/ViewModels/SettingsViewModel.swift:115`
  - `ios/ClawPhones/ViewModels/SettingsViewModel.swift:142`
  - `ios/ClawPhones/ViewModels/SettingsViewModel.swift:167`
  - `ios/ClawPhones/ViewModels/SettingsViewModel.swift:187`
  - `ios/ClawPhones/ViewModels/SettingsViewModel.swift:207`
  - `ios/ClawPhones/ViewModels/SettingsViewModel.swift:224`
  - `ios/ClawPhones/ViewModels/SettingsViewModel.swift:259`
  - `ios/ClawPhones/ViewModels/SettingsViewModel.swift:297`
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/AuthFragment.java:371`

### 3) 重复代码 / 明显 copy-paste 错误
- 未发现跨 iOS/Android 的直接复制残留（例如错误平台 API 或明显平台代码粘贴错位）
- 但存在“跨端命名未对齐”的语义错配（见上）

### 4) 依赖完整性（import 对应）
发现依赖未完整声明:
- Android 代码引入 Firebase Messaging，但依赖未在 `app/build.gradle` 声明
  - 引入位置:
    - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/ClawPhonesMessagingService.java`
  - 依赖文件仅见 material，无 firebase messaging:
    - `android/clawphones-android/app/build.gradle:33`
- Python `proxy/server.py` 使用 `PyPDF2`，但 `proxy/requirements.txt` 未包含 `pypdf2`

---

## 轮3 安全检查

**结果: FAIL**

### 1) `server.py` 硬编码 key 检查
- 未发现明显硬编码密钥（`sk-`/`AKIA`/`ghp_` 等模式）
- `ADMIN_KEY`、上游 API key 均由环境变量读取（符合预期）

### 2) SQL 注入检查
- 主体查询使用参数化 `?` 占位，未见直接拼接用户输入进 SQL 值位
- 动态 SQL 位置经检查为受控字段或受控占位符拼接（风险低）

### 3) admin auth 一致性检查
发现鉴权实现不一致:
- 标准实现使用 `_admin_check` + `secrets.compare_digest`
  - `proxy/server.py:3488`
- crash report admin 接口未复用 `_admin_check`，直接 `admin != ADMIN_KEY`
  - `proxy/server.py:3645`
  - `proxy/server.py:3670`

### 4) 移动端明文密码/不安全连接检查
发现高风险项:
- Android 仪表盘读取并展示明文 SSH 密码（来自 `~/.ssh_password`）
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/DashboardActivity.java:391`
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/DashboardActivity.java:402`
- iOS 默认 API 地址为 HTTP 明文
  - `ios/ClawPhones/Services/DeviceConfig.swift:58`
- Android 默认 API 地址为 HTTP 明文
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ClawPhonesAPI.java:48`
- Android 允许 cleartext traffic
  - `android/clawphones-android/app/src/main/AndroidManifest.xml:56`
  - `android/clawphones-android/app/src/main/res/xml/network_security_config.xml:16`
- iOS ATS 对 `3.142.69.6` 放开了不安全 HTTP 例外
  - `ios/ClawPhones/Config/Info.plist:38`

补充风险:
- token 持久化含明文存储路径（Android `SharedPreferences`、iOS `UserDefaults`）
  - `android/clawphones-android/app/src/main/java/ai/clawphones/agent/chat/ClawPhonesAPI.java:214`
  - `ios/ClawPhones/Services/DeviceConfig.swift:92`

---

## 总结

- 轮1: **FAIL**（Sprint 基线文件缺失 2 个；编译链在当前沙箱有环境限制，但 iOS/Python 语法已通过）
- 轮2: **FAIL**（命名不一致 + TODO 遗留 + 依赖声明不完整）
- 轮3: **FAIL**（admin 鉴权不一致 + 明文密码展示 + HTTP 明文链路）

**总体验收结论: FAIL（当前不建议作为 Sprint 2-10 完成态签收）**

## 建议修复列表（按优先级）

1. 统一安全基线: 将移动端默认 `BASE_URL` 切到 HTTPS；移除 cleartext 例外（Android `usesCleartextTraffic`、iOS ATS 例外）
2. 移除明文 SSH 密码展示逻辑，禁止 UI 读取 `~/.ssh_password`
3. 统一 admin 鉴权入口：`/v1/crash-reports*` 复用 `_admin_check` 并使用 constant-time compare
4. 修复跨端枚举命名不一致：iOS `PlanTier` 与 `persona` 对齐后端/Android 枚举值
5. 补齐依赖声明：补 `firebase-messaging`（或删除未启用 FCM 代码），补 `pypdf2`（或移除 PDF 解析分支）
6. 清理 9 处 `TODO`，将“后端未就绪”的临时分支改为明确特性开关或错误处理策略
7. 处理 Sprint 基线缺失文件：恢复/替代映射并在文档中注明迁移关系（`agent/ChatActivity.java`、`agent/PlaceholderFragment.java`）
