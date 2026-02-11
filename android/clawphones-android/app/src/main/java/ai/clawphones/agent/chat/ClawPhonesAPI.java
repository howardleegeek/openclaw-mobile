package ai.clawphones.agent.chat;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Minimal HTTP client for ClawPhones backend API.
 *
 * BASE_URL: https://3.142.69.6:8080
 *
 * Endpoints:
 *   POST /v1/auth/register
 *   POST /v1/auth/login
 *   POST /v1/conversations
 *   POST /v1/conversations/{id}/chat
 *   GET  /v1/conversations
 */
public class ClawPhonesAPI {

    private static final String LOG_TAG = "ClawPhonesAPI";

    public static final String BASE_URL = "https://3.142.69.6:8080";

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int STREAM_READ_TIMEOUT_MS = 120_000;

    private static final String PREFS = "clawphones_api";
    private static final String SECURE_PREFS = "clawphones_secure_prefs";
    private static final String PREF_TOKEN = "token";
    private static final String PREF_TOKEN_EXPIRES_AT = "token_expires_at";
    private static final String PREF_SECURE_MIGRATED = "secure_migrated_v1";
    private static final long TOKEN_TTL_SECONDS = 30L * 24L * 60L * 60L;
    private static final long TOKEN_REFRESH_WINDOW_SECONDS = 7L * 24L * 60L * 60L;
    private static final List<String> DEFAULT_PERSONAS = Arrays.asList(
        "assistant", "coder", "writer", "translator", "custom");

    public static class ApiException extends Exception {
        public final int statusCode;
        public ApiException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }

    public static class AuthToken {
        public final String token;
        public final long expiresAt;

        public AuthToken(String token, long expiresAt) {
            this.token = token;
            this.expiresAt = expiresAt;
        }
    }

    public static class ConversationSummary {
        public final String id;
        public final String title; // may be null
        public final long createdAt;
        public final long updatedAt;
        public final int messageCount;

        public ConversationSummary(String id, String title, long createdAt, long updatedAt, int messageCount) {
            this.id = id;
            this.title = title;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.messageCount = messageCount;
        }
    }

    /** Plan feature limits for a single tier. */
    public static class PlanTier {
        public final String tier;
        public final long contextLength;
        public final long outputLimit;
        public final long dailyCap;

        public PlanTier(String tier, long contextLength, long outputLimit, long dailyCap) {
            this.tier = normalizeTierName(tier);
            this.contextLength = contextLength;
            this.outputLimit = outputLimit;
            this.dailyCap = dailyCap;
        }
    }

    /** Current user plan with usage and all tier limits. */
    public static class UserPlan {
        public final String currentTier;
        public final long todayUsedTokens;
        public final long dailyTokenLimit;
        public final List<PlanTier> tiers;

        public UserPlan(String currentTier, long todayUsedTokens, long dailyTokenLimit, List<PlanTier> tiers) {
            this.currentTier = normalizeTierName(currentTier);
            this.todayUsedTokens = Math.max(0L, todayUsedTokens);
            this.dailyTokenLimit = Math.max(0L, dailyTokenLimit);
            this.tiers = tiers == null ? new ArrayList<>() : tiers;
        }
    }

    /** /v1/user/profile */
    public static class UserProfile {
        public final String userId;
        public final String email;
        public final String name;
        public final String tier;
        public final String language;

        public UserProfile(String userId, String email, String name, String tier, String language) {
            this.userId = userId;
            this.email = email;
            this.name = name;
            this.tier = normalizeTierName(tier);
            this.language = normalizeLanguage(language);
        }
    }

    /** /v1/user/ai-config */
    public static class AIConfig {
        public final String persona;
        public final String customPrompt; // nullable
        public final Double temperature; // nullable
        public final List<String> personas;

        public AIConfig(String persona, String customPrompt, Double temperature, List<String> personas) {
            this.persona = normalizePersona(persona);
            this.customPrompt = customPrompt;
            this.temperature = temperature;
            this.personas = personas == null ? new ArrayList<>() : personas;
        }
    }

    /** /v1/user/export */
    public static class UserDataExport {
        public final String exportId;
        public final String downloadUrl;
        public final long expiresAt;

        public UserDataExport(String exportId, String downloadUrl, long expiresAt) {
            this.exportId = exportId == null ? "" : exportId;
            this.downloadUrl = downloadUrl == null ? "" : downloadUrl;
            this.expiresAt = expiresAt;
        }
    }

    /**
     * Callback interface for SSE streaming chat responses.
     */
    public interface StreamCallback {
        /** Called on each text delta (may be called many times). */
        void onDelta(String delta);
        /** Called once when streaming completes with the full content. */
        void onComplete(String fullContent, String messageId);
        /** Called on error (network, API, or stream parse error). */
        void onError(Exception error);
    }

    public interface UploadCallback {
        void onSuccess(UploadedFile uploadedFile);
        void onError(Exception error);
    }

    public static class UploadedFile {
        public final String fileId;
        public final String filename;
        public final String mimeType;
        public final long size;
        public final String extractedText;

        public UploadedFile(String fileId, String filename, String mimeType, long size, String extractedText) {
            this.fileId = fileId == null ? "" : fileId;
            this.filename = filename == null ? "" : filename;
            this.mimeType = mimeType == null ? "" : mimeType;
            this.size = Math.max(0L, size);
            this.extractedText = extractedText;
        }
    }

    // ── SharedPreferences helpers ───────────────────────────────────────────────

    private static SharedPreferences getSecurePrefs(Context context) {
        if (context == null) {
            return null;
        }
        try {
            SharedPreferences secure = EncryptedSharedPreferences.create(
                SECURE_PREFS,
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            migrateLegacyTokenIfNeeded(context, secure);
            return secure;
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "EncryptedSharedPreferences unavailable, fallback to legacy prefs: " + e.getMessage());
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
    }

    private static void migrateLegacyTokenIfNeeded(Context context, SharedPreferences securePrefs) {
        if (context == null || securePrefs == null) return;
        if (securePrefs.getBoolean(PREF_SECURE_MIGRATED, false)) return;

        SharedPreferences legacyPrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String legacyToken = legacyPrefs.getString(PREF_TOKEN, null);
        long legacyExpiresAt = legacyPrefs.getLong(PREF_TOKEN_EXPIRES_AT, 0L);

        SharedPreferences.Editor secureEditor = securePrefs.edit();
        if (legacyToken != null && !legacyToken.trim().isEmpty()) {
            secureEditor.putString(PREF_TOKEN, legacyToken.trim());
        }
        if (legacyExpiresAt > 0L) {
            secureEditor.putLong(PREF_TOKEN_EXPIRES_AT, legacyExpiresAt);
        }
        secureEditor.putBoolean(PREF_SECURE_MIGRATED, true);
        secureEditor.apply();

        legacyPrefs.edit()
            .remove(PREF_TOKEN)
            .remove(PREF_TOKEN_EXPIRES_AT)
            .apply();
    }

    public static void saveToken(Context context, String token) {
        saveToken(context, token, nowEpochSeconds() + TOKEN_TTL_SECONDS);
    }

    public static void saveToken(Context context, String token, long expiresAt) {
        if (context == null) return;
        if (token == null) return;
        long normalizedExpiry = normalizeExpiry(expiresAt);
        SharedPreferences prefs = getSecurePrefs(context);
        if (prefs == null) return;
        prefs
            .edit()
            .putString(PREF_TOKEN, token)
            .putLong(PREF_TOKEN_EXPIRES_AT, normalizedExpiry)
            .apply();
    }

    public static String getToken(Context context) {
        if (context == null) return null;
        SharedPreferences sp = getSecurePrefs(context);
        if (sp == null) return null;
        String t = sp.getString(PREF_TOKEN, null);
        if (t == null) return null;
        t = t.trim();
        if (t.isEmpty()) return null;
        long expiresAt = sp.getLong(PREF_TOKEN_EXPIRES_AT, 0L);
        if (expiresAt > 0L && nowEpochSeconds() >= expiresAt) {
            clearToken(context);
            return null;
        }
        return t;
    }

    public static long getTokenExpiresAt(Context context) {
        if (context == null) return 0L;
        SharedPreferences sp = getSecurePrefs(context);
        if (sp == null) return 0L;
        return sp.getLong(PREF_TOKEN_EXPIRES_AT, 0L);
    }

    public static boolean isTokenExpired(Context context) {
        long expiresAt = getTokenExpiresAt(context);
        return expiresAt > 0L && nowEpochSeconds() >= expiresAt;
    }

    public static void clearToken(Context context) {
        if (context == null) return;
        SharedPreferences securePrefs = getSecurePrefs(context);
        if (securePrefs != null) {
            securePrefs
                .edit()
                .remove(PREF_TOKEN)
                .remove(PREF_TOKEN_EXPIRES_AT)
                .apply();
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(PREF_TOKEN)
            .remove(PREF_TOKEN_EXPIRES_AT)
            .apply();
    }

    // ── Auth ──────────────────────────────────────────────────────────────────

    /** POST /v1/auth/register -> token */
    public static AuthToken register(String email, String password, String name)
        throws IOException, ApiException, JSONException {
        JSONObject body = new JSONObject();
        body.put("email", email);
        body.put("password", password);
        body.put("name", name);
        JSONObject resp = doPost(BASE_URL + "/v1/auth/register", body, null);
        return extractAuthToken(resp);
    }

    /** POST /v1/auth/login -> token */
    public static AuthToken login(String email, String password)
        throws IOException, ApiException, JSONException {
        JSONObject body = new JSONObject();
        body.put("email", email);
        body.put("password", password);
        JSONObject resp = doPost(BASE_URL + "/v1/auth/login", body, null);
        return extractAuthToken(resp);
    }

    /** POST /v1/auth/refresh -> new token */
    public static AuthToken refresh(Context context)
        throws IOException, ApiException, JSONException {
        String currentToken = getToken(context);
        if (currentToken == null || currentToken.trim().isEmpty()) {
            throw new ApiException(401, "missing bearer token");
        }
        return refresh(context, currentToken.trim());
    }

    private static AuthToken refresh(Context context, String currentToken)
        throws IOException, ApiException, JSONException {
        JSONObject resp = doPost(BASE_URL + "/v1/auth/refresh", new JSONObject(), currentToken);
        AuthToken refreshed = extractAuthToken(resp);
        if (context != null) {
            saveToken(context, refreshed.token, refreshed.expiresAt);
        }
        return refreshed;
    }

    // ── Conversations ─────────────────────────────────────────────────────────

    /** POST /v1/conversations -> conversationId */
    public static String createConversation(Context context)
        throws IOException, ApiException, JSONException {
        String token = resolveAuthTokenForRequest(context);
        JSONObject resp = doPost(BASE_URL + "/v1/conversations", new JSONObject(), token);
        String id = resp.optString("id", null);
        if (id == null || id.trim().isEmpty()) {
            throw new JSONException("Missing conversation id");
        }
        return id;
    }

    /** GET /v1/conversations -> list */
    public static List<ConversationSummary> listConversations(Context context)
        throws IOException, ApiException, JSONException {
        List<Map<String, Object>> maps = getConversations(context);
        List<ConversationSummary> out = new ArrayList<>();
        for (Map<String, Object> map : maps) {
            out.add(new ConversationSummary(
                asString(map.get("id")),
                asStringOrNull(map.get("title")),
                asLong(map.get("created_at")),
                asLong(map.get("updated_at")),
                (int) asLong(map.get("message_count"))
            ));
        }
        return out;
    }

    /** GET /v1/conversations?limit=&offset= -> list */
    public static List<ConversationSummary> listConversations(Context context, int limit, int offset)
        throws IOException, ApiException, JSONException {
        List<Map<String, Object>> maps = getConversations(context, limit, offset);
        List<ConversationSummary> out = new ArrayList<>();
        for (Map<String, Object> map : maps) {
            out.add(new ConversationSummary(
                asString(map.get("id")),
                asStringOrNull(map.get("title")),
                asLong(map.get("created_at")),
                asLong(map.get("updated_at")),
                (int) asLong(map.get("message_count"))
            ));
        }
        return out;
    }

    /** GET /v1/conversations -> [{id,title,created_at,updated_at,message_count}, ...] */
    public static List<Map<String, Object>> getConversations(Context context)
        throws IOException, ApiException, JSONException {
        return getConversations(context, 20, 0);
    }

    /** GET /v1/conversations?limit=&offset= -> [{id,title,created_at,updated_at,message_count}, ...] */
    public static List<Map<String, Object>> getConversations(Context context, int limit, int offset)
        throws IOException, ApiException, JSONException {
        String token = resolveAuthTokenForRequest(context);
        int safeLimit = Math.max(1, limit);
        int safeOffset = Math.max(0, offset);
        String url = BASE_URL + "/v1/conversations?limit=" + safeLimit + "&offset=" + safeOffset;
        Object resp = doGetAny(url, token);
        JSONArray arr = extractArray(resp, "conversations");
        List<Map<String, Object>> out = new ArrayList<>();
        if (arr == null) return out;

        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c == null) continue;
            HashMap<String, Object> row = new HashMap<>();
            row.put("id", c.optString("id", ""));
            row.put("title", c.isNull("title") ? null : c.optString("title", null));
            row.put("created_at", c.optLong("created_at", 0));
            row.put("updated_at", c.optLong("updated_at", 0));
            row.put("message_count", c.optInt("message_count", 0));
            out.add(row);
        }
        return out;
    }

    /** DELETE /v1/conversations/{id} -> 204 */
    public static void deleteConversation(Context context, String conversationId)
        throws IOException, ApiException {
        String token = resolveAuthTokenForRequest(context);
        doDeleteNoContent(BASE_URL + "/v1/conversations/" + conversationId, token);
    }

    /** GET /v1/conversations/{id} -> {id, title, messages:[{id,role,content,created_at}, ...]} */
    public static List<Map<String, Object>> getMessages(Context context, String conversationId)
        throws IOException, ApiException, JSONException {
        String token = resolveAuthTokenForRequest(context);
        Object resp = doGetAny(BASE_URL + "/v1/conversations/" + conversationId, token);
        JSONArray arr = extractArray(resp, "messages");
        List<Map<String, Object>> out = new ArrayList<>();
        if (arr == null) return out;

        for (int i = 0; i < arr.length(); i++) {
            JSONObject msg = arr.optJSONObject(i);
            if (msg == null) continue;
            HashMap<String, Object> row = new HashMap<>();
            row.put("id", msg.optString("id", ""));
            row.put("role", msg.optString("role", ""));
            row.put("content", msg.optString("content", ""));
            row.put("created_at", msg.optLong("created_at", 0));
            out.add(row);
        }
        return out;
    }

    /** POST /v1/conversations/{id}/chat -> assistant content */
    public static String chat(Context context, String conversationId, String message)
        throws IOException, ApiException, JSONException {
        String token = resolveAuthTokenForRequest(context);
        JSONObject body = new JSONObject();
        body.put("message", message);
        String url = BASE_URL + "/v1/conversations/" + conversationId + "/chat";
        JSONObject resp = doPost(url, body, token);
        return extractAssistantContent(resp);
    }

    /** GET /v1/user/plan -> current tier + daily usage + tier comparison */
    public static UserPlan getUserPlan(Context context)
        throws IOException, ApiException, JSONException {
        String token = resolveAuthTokenForRequest(context);
        Object resp = doGetAny(BASE_URL + "/v1/user/plan", token);
        return extractUserPlan(resp);
    }

    /** GET /v1/user/profile */
    public static UserProfile getUserProfile(Context context)
        throws IOException, ApiException, JSONException {
        String token = resolveAuthTokenForRequest(context);
        JSONObject resp = doGet(BASE_URL + "/v1/user/profile", token);
        return extractUserProfile(resp);
    }

    /** PUT /v1/user/profile */
    public static UserProfile updateUserProfile(Context context, String name, String language)
        throws IOException, ApiException, JSONException {
        String token = resolveAuthTokenForRequest(context);
        JSONObject body = new JSONObject();
        if (name != null) body.put("name", name);
        if (language != null) body.put("language", language);
        JSONObject resp = doPut(BASE_URL + "/v1/user/profile", body, token);
        return extractUserProfile(resp);
    }

    /** GET /v1/user/ai-config */
    public static AIConfig getAIConfig(Context context)
        throws IOException, ApiException, JSONException {
        String token = resolveAuthTokenForRequest(context);
        JSONObject resp = doGet(BASE_URL + "/v1/user/ai-config", token);
        return extractAIConfig(resp);
    }

    /** PUT /v1/user/ai-config */
    public static AIConfig updateAIConfig(Context context, String persona, String customPrompt, Double temperature)
        throws IOException, ApiException, JSONException {
        String token = resolveAuthTokenForRequest(context);
        JSONObject body = new JSONObject();
        if (persona != null) body.put("persona", normalizePersona(persona));
        if (customPrompt != null) body.put("custom_prompt", customPrompt);
        if (temperature != null) body.put("temperature", temperature.doubleValue());
        JSONObject resp = doPut(BASE_URL + "/v1/user/ai-config", body, token);
        return extractAIConfig(resp);
    }

    /** POST /v1/user/export -> temporary download link */
    public static UserDataExport createUserDataExport(Context context)
        throws IOException, ApiException, JSONException {
        String token = resolveAuthTokenForRequest(context);
        JSONObject resp = doPost(BASE_URL + "/v1/user/export", new JSONObject(), token);
        return extractUserDataExport(resp);
    }

    /** Download export JSON to app-local file. */
    public static File downloadUserDataExport(Context context, UserDataExport exportInfo)
        throws IOException, ApiException {
        if (context == null) throw new IOException("context is required");
        if (exportInfo == null || exportInfo.downloadUrl == null || exportInfo.downloadUrl.trim().isEmpty()) {
            throw new ApiException(400, "missing export download url");
        }

        String downloadUrl = exportInfo.downloadUrl.trim();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(downloadUrl).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String raw = readBody(conn, code);
                throw new ApiException(code, raw.isEmpty() ? "HTTP " + code : raw);
            }

            File exportDir = new File(context.getFilesDir(), "exports");
            if (!exportDir.exists() && !exportDir.mkdirs()) {
                throw new IOException("failed to create export directory");
            }

            String idPart = sanitizeFileNamePart(exportInfo.exportId);
            if (idPart.isEmpty()) {
                idPart = String.valueOf(nowEpochSeconds());
            }
            File output = new File(
                exportDir,
                "clawphones_export_" + idPart + "_" + nowEpochSeconds() + ".json"
            );

            try (
                InputStream in = conn.getInputStream();
                FileOutputStream out = new FileOutputStream(output, false)
            ) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            }

            return output;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** DELETE /v1/user/account with body {"confirm": true} -> 204 */
    public static void deleteAccount(Context context)
        throws IOException, ApiException, JSONException {
        String token = resolveAuthTokenForRequest(context);
        JSONObject body = new JSONObject();
        body.put("confirm", true);
        doDeleteNoContent(BASE_URL + "/v1/user/account", body, token);
    }

    /** POST /v1/crash-reports -> 2xx */
    public static void postCrashReport(Context context, String jsonBody)
        throws IOException, ApiException {
        String token = resolveAuthTokenForRequest(context);
        String body = jsonBody;
        if (body == null || body.trim().isEmpty()) {
            body = "{}";
        }
        doPostRaw(BASE_URL + "/v1/crash-reports", body, token);
    }

    public static void uploadFile(
        Context context,
        String conversationId,
        byte[] fileData,
        String filename,
        String mimeType,
        UploadCallback callback
    ) {
        if (callback == null) {
            throw new IllegalArgumentException("callback is required");
        }
        try {
            callback.onSuccess(uploadFileBlocking(context, conversationId, fileData, filename, mimeType));
        } catch (Exception e) {
            callback.onError(e);
        }
    }

    public static UploadedFile uploadFileBlocking(
        Context context,
        String conversationId,
        byte[] fileData,
        String filename,
        String mimeType
    ) throws IOException, ApiException, JSONException {
        String token = resolveAuthTokenForRequest(context);
        if (conversationId == null || conversationId.trim().isEmpty()) {
            throw new ApiException(400, "conversation_id is required");
        }
        if (fileData == null || fileData.length == 0) {
            throw new ApiException(400, "file data is empty");
        }

        String safeName = filename == null ? "upload.bin" : filename.trim();
        if (safeName.isEmpty()) safeName = "upload.bin";
        String safeMime = mimeType == null ? "" : mimeType.trim();
        if (safeMime.isEmpty()) safeMime = "application/octet-stream";

        String boundary = "----clawphones-" + UUID.randomUUID().toString();
        String urlStr = BASE_URL + "/v1/upload?conversation_id=" + conversationId;
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (token != null && !token.trim().isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + token.trim());
        }
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            head.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            head.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + escapeQuoted(safeName) + "\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
            head.write(("Content-Type: " + safeMime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            os.write(head.toByteArray());
            os.write(fileData);
            os.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        JSONObject resp = readResponse(conn);
        return new UploadedFile(
            resp.optString("file_id", ""),
            resp.optString("filename", safeName),
            resp.optString("mime_type", safeMime),
            resp.optLong("size", fileData.length),
            resp.optString("extracted_text", null)
        );
    }

    /**
     * POST /v1/conversations/{id}/chat/stream -> SSE streaming response.
     * Must be called from a background thread. Callbacks fire on the calling thread.
     */
    public static void chatStream(
        Context context,
        String conversationId,
        String message,
        List<String> fileIds,
        StreamCallback callback
    ) {
        if (callback == null) {
            throw new IllegalArgumentException("callback is required");
        }

        HttpURLConnection conn = null;
        try {
            String token = resolveAuthTokenForRequest(context);
            JSONObject body = new JSONObject();
            body.put("message", message);
            if (fileIds != null && !fileIds.isEmpty()) {
                JSONArray array = new JSONArray();
                for (String fileId : fileIds) {
                    if (fileId == null) continue;
                    String normalized = fileId.trim();
                    if (!normalized.isEmpty()) {
                        array.put(normalized);
                    }
                }
                if (array.length() > 0) {
                    body.put("file_ids", array);
                }
            }

            String urlStr = BASE_URL + "/v1/conversations/" + conversationId + "/chat/stream";
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(STREAM_READ_TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("Content-Type", "application/json");
            if (token != null && !token.trim().isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token.trim());
            }
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String rawError = readRawBody(conn.getErrorStream());
                Logger.logError(LOG_TAG, "Stream API error " + code + ": " + rawError);
                callback.onError(new ApiException(code, rawError.isEmpty() ? "HTTP " + code : rawError));
                return;
            }

            StringBuilder accumulated = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || line.startsWith(":")) continue;
                    if (!line.startsWith("data:")) continue;

                    String dataJson = line.substring("data:".length()).trim();
                    if (dataJson.isEmpty()) continue;

                    JSONObject event = new JSONObject(dataJson);
                    if (event.has("error")) {
                        String msg = event.optString("error", "stream error");
                        callback.onError(new ApiException(code, msg));
                        return;
                    }

                    boolean done = event.optBoolean("done", false);
                    if (!done) {
                        String delta = event.optString("delta", "");
                        if (!delta.isEmpty()) {
                            accumulated.append(delta);
                            callback.onDelta(delta);
                        }
                        continue;
                    }

                    String fullContent = event.optString("content", null);
                    if (fullContent == null || fullContent.isEmpty()) {
                        fullContent = accumulated.toString();
                    }
                    String messageId = event.optString("message_id", null);
                    callback.onComplete(fullContent, messageId);
                    return;
                }
            }

            callback.onError(new IOException("stream closed before done event"));
        } catch (IOException | JSONException | ApiException e) {
            callback.onError(e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ── Auth internals ────────────────────────────────────────────────────────

    private static String resolveAuthTokenForRequest(Context context) throws ApiException {
        String token = getToken(context);
        if (token == null || token.trim().isEmpty()) {
            throw new ApiException(401, "missing bearer token");
        }

        long now = nowEpochSeconds();
        long expiresAt = getTokenExpiresAt(context);
        if (expiresAt <= 0L) {
            expiresAt = now + TOKEN_TTL_SECONDS;
            saveToken(context, token, expiresAt);
        }

        if (now >= expiresAt) {
            clearToken(context);
            throw new ApiException(401, "token expired");
        }

        long remaining = expiresAt - now;
        if (remaining < TOKEN_REFRESH_WINDOW_SECONDS) {
            try {
                AuthToken refreshed = refresh(context, token);
                if (refreshed != null && refreshed.token != null && !refreshed.token.trim().isEmpty()) {
                    token = refreshed.token.trim();
                }
            } catch (ApiException e) {
                if (e.statusCode == 401) {
                    clearToken(context);
                    throw e;
                }
                if (e.statusCode != 400) {
                    Logger.logWarn(LOG_TAG, "Token refresh failed (will continue with old token): " + e.getMessage());
                }
            } catch (IOException | JSONException e) {
                Logger.logWarn(LOG_TAG, "Token refresh network/parse failure (will continue with old token): " + e.getMessage());
            }
        }

        return token;
    }

    private static long nowEpochSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    private static long normalizeExpiry(long expiresAt) {
        if (expiresAt <= 0L) {
            return nowEpochSeconds() + TOKEN_TTL_SECONDS;
        }
        return expiresAt;
    }

    // ── HTTP internals ────────────────────────────────────────────────────────

    private static JSONObject doGet(String urlStr, String token) throws IOException, ApiException, JSONException {
        HttpURLConnection conn = openConnection(urlStr, "GET", token);
        return readResponse(conn);
    }

    private static Object doGetAny(String urlStr, String token) throws IOException, ApiException, JSONException {
        HttpURLConnection conn = openConnection(urlStr, "GET", token);
        return readResponseAny(conn);
    }

    private static JSONObject doPost(String urlStr, JSONObject body, String token) throws IOException, ApiException, JSONException {
        HttpURLConnection conn = openConnection(urlStr, "POST", token);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        return readResponse(conn);
    }

    private static JSONObject doPut(String urlStr, JSONObject body, String token) throws IOException, ApiException, JSONException {
        HttpURLConnection conn = openConnection(urlStr, "PUT", token);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        return readResponse(conn);
    }

    private static void doPostRaw(String urlStr, String rawBody, String token) throws IOException, ApiException {
        HttpURLConnection conn = openConnection(urlStr, "POST", token);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(rawBody.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        String raw;
        try {
            raw = readBody(conn, code);
        } finally {
            conn.disconnect();
        }
        if (code < 200 || code >= 300) {
            Logger.logError(LOG_TAG, "API error " + code + ": " + raw);
            throw new ApiException(code, raw.isEmpty() ? "HTTP " + code : raw);
        }
    }

    private static void doDeleteNoContent(String urlStr, String token) throws IOException, ApiException {
        doDeleteNoContent(urlStr, null, token);
    }

    private static void doDeleteNoContent(String urlStr, JSONObject body, String token) throws IOException, ApiException {
        HttpURLConnection conn = openConnection(urlStr, "DELETE", token);
        if (body != null) {
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = conn.getResponseCode();
        String raw = "";
        try {
            raw = readBody(conn, code);
        } finally {
            conn.disconnect();
        }
        if (code < 200 || code >= 300) {
            Logger.logError(LOG_TAG, "API error " + code + ": " + raw);
            throw new ApiException(code, raw.isEmpty() ? "HTTP " + code : raw);
        }
    }

    private static HttpURLConnection openConnection(String urlStr, String method, String token) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod(method);
        conn.setRequestProperty("Accept", "application/json");
        if (token != null && !token.trim().isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + token.trim());
        }
        return conn;
    }

    private static JSONObject readResponse(HttpURLConnection conn) throws IOException, ApiException, JSONException {
        int code = conn.getResponseCode();
        String raw;
        try {
            raw = readBody(conn, code);
        } finally {
            conn.disconnect();
        }
        if (code < 200 || code >= 300) {
            Logger.logError(LOG_TAG, "API error " + code + ": " + raw);
            throw new ApiException(code, raw.isEmpty() ? "HTTP " + code : raw);
        }
        if (raw.isEmpty()) {
            throw new JSONException("Empty response body");
        }
        return new JSONObject(raw);
    }

    private static Object readResponseAny(HttpURLConnection conn) throws IOException, ApiException, JSONException {
        int code = conn.getResponseCode();
        String raw;
        try {
            raw = readBody(conn, code);
        } finally {
            conn.disconnect();
        }
        if (code < 200 || code >= 300) {
            Logger.logError(LOG_TAG, "API error " + code + ": " + raw);
            throw new ApiException(code, raw.isEmpty() ? "HTTP " + code : raw);
        }
        if (raw.isEmpty()) {
            return new JSONArray();
        }
        Object parsed = new JSONTokener(raw).nextValue();
        if (parsed instanceof JSONObject || parsed instanceof JSONArray) {
            return parsed;
        }
        throw new JSONException("Unexpected response type");
    }

    private static String readBody(HttpURLConnection conn, int code) throws IOException {
        StringBuilder sb = new StringBuilder();
        try {
            InputStream stream;
            if (code >= 200 && code < 300) {
                stream = conn.getInputStream();
            } else {
                stream = conn.getErrorStream();
                if (stream == null) {
                    try {
                        stream = conn.getInputStream();
                    } catch (IOException ignored) {
                        stream = null;
                    }
                }
            }
            if (stream != null) {
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                }
            }
        } catch (IOException e) {
            if (code >= 200 && code < 300) throw e;
        }
        return sb.toString();
    }

    private static String readRawBody(InputStream stream) throws IOException {
        if (stream == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static JSONArray extractArray(Object resp, String key) {
        if (resp instanceof JSONArray) {
            return (JSONArray) resp;
        }
        if (resp instanceof JSONObject) {
            return ((JSONObject) resp).optJSONArray(key);
        }
        return null;
    }

    private static String asString(Object v) {
        if (v == null) return "";
        return String.valueOf(v);
    }

    private static String asStringOrNull(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static long asLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static int asInt(Object v, int fallback) {
        if (v == null) return fallback;
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static Double asDoubleOrNull(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static UserPlan extractUserPlan(Object resp) {
        JSONObject root = toObject(resp);
        JSONObject data = optObject(root, "data", "result", "payload");
        JSONObject scoped = data != null ? data : root;

        String currentTier = coalesce(
            optString(scoped, "current_tier", "currentTier", "tier", "plan"),
            optString(root, "current_tier", "currentTier", "tier", "plan")
        );

        JSONObject currentPlanObj = optObject(scoped, "plan", "subscription", "current_plan", "currentPlan");
        if (isBlank(currentTier) && currentPlanObj != null) {
            currentTier = optString(currentPlanObj, "tier", "name", "id", "plan");
        }

        JSONObject usage = optObject(scoped, "usage", "token_usage", "today_usage", "daily_usage");
        if (usage == null) usage = optObject(root, "usage", "token_usage", "today_usage", "daily_usage");

        long used = firstPositive(
            optLong(usage, "today_used", "todayUsed", "used", "used_tokens", "usedTokens",
                "tokens_today", "tokensToday", "daily_used", "dailyUsed",
                "messages", "messages_today", "used_today"),
            optLong(scoped, "today_used", "todayUsed", "tokens_today", "tokensToday",
                "daily_used", "dailyUsed", "messages", "messages_today", "used_today"),
            optLong(root, "today_used", "todayUsed", "tokens_today", "tokensToday",
                "daily_used", "dailyUsed", "messages", "messages_today", "used_today")
        );

        long limit = firstPositive(
            optLong(usage, "daily_limit", "dailyLimit", "daily_cap", "dailyCap", "limit", "token_limit", "tokenLimit",
                "daily_tokens", "messages_per_day", "daily_messages"),
            optLong(scoped, "daily_limit", "dailyLimit", "daily_cap", "dailyCap", "token_limit", "tokenLimit",
                "daily_tokens", "messages_per_day", "daily_messages"),
            optLong(root, "daily_limit", "dailyLimit", "daily_cap", "dailyCap", "token_limit", "tokenLimit",
                "daily_tokens", "messages_per_day", "daily_messages")
        );

        List<PlanTier> tiers = parsePlanTiers(scoped);
        if (tiers.isEmpty() && scoped != root) {
            tiers = parsePlanTiers(root);
        }

        if (limit <= 0L && !isBlank(currentTier)) {
            String normalizedCurrent = normalizeTierName(currentTier);
            for (PlanTier tier : tiers) {
                if (normalizedCurrent.equals(normalizeTierName(tier.tier)) && tier.dailyCap > 0L) {
                    limit = tier.dailyCap;
                    break;
                }
            }
        }

        return new UserPlan(currentTier, used, limit, tiers);
    }

    private static List<PlanTier> parsePlanTiers(JSONObject scoped) {
        ArrayList<PlanTier> parsed = new ArrayList<>();
        if (scoped == null) return parsed;

        JSONArray arr = optArray(scoped, "tiers", "plans", "plan_tiers", "comparison", "tier_comparison", "available_plans");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                PlanTier tier = parsePlanTier(item, null);
                if (tier != null) parsed.add(tier);
            }
        }

        JSONObject obj = optObject(scoped, "tiers", "plans", "plan_tiers", "comparison", "tier_comparison");
        if (obj != null) {
            java.util.Iterator<String> it = obj.keys();
            while (it.hasNext()) {
                String key = it.next();
                JSONObject item = obj.optJSONObject(key);
                if (item == null) continue;
                PlanTier tier = parsePlanTier(item, key);
                if (tier != null) parsed.add(tier);
            }
        }

        LinkedHashMap<String, PlanTier> dedup = new LinkedHashMap<>();
        for (PlanTier tier : parsed) {
            if (tier == null || isBlank(tier.tier)) continue;
            dedup.put(normalizeTierName(tier.tier), tier);
        }
        return new ArrayList<>(dedup.values());
    }

    private static PlanTier parsePlanTier(JSONObject item, String fallbackTier) {
        if (item == null) return null;

        String tierName = coalesce(
            optString(item, "tier", "name", "id", "plan"),
            fallbackTier
        );
        if (isBlank(tierName)) return null;

        JSONObject limits = optObject(item, "limits", "features", "quota");

        long contextLength = firstPositive(
            optLong(item, "context_length", "contextLength", "context_limit", "contextLimit", "max_context_tokens"),
            optLong(limits, "context_length", "contextLength", "context_limit", "contextLimit", "max_context_tokens")
        );

        long outputLimit = firstPositive(
            optLong(item, "output_limit", "outputLimit", "max_output_tokens", "max_tokens"),
            optLong(limits, "output_limit", "outputLimit", "max_output_tokens", "max_tokens")
        );

        long dailyCap = firstPositive(
            optLong(item, "daily_cap", "dailyCap", "daily_limit", "dailyLimit", "token_limit", "tokenLimit",
                "daily_tokens", "messages_per_day", "daily_messages"),
            optLong(limits, "daily_cap", "dailyCap", "daily_limit", "dailyLimit", "token_limit", "tokenLimit",
                "daily_tokens", "messages_per_day", "daily_messages")
        );

        return new PlanTier(tierName, contextLength, outputLimit, dailyCap);
    }

    private static JSONObject toObject(Object value) {
        if (value instanceof JSONObject) {
            return (JSONObject) value;
        }
        if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            if (arr.length() > 0) {
                JSONObject first = arr.optJSONObject(0);
                if (first != null) return first;
            }
        }
        return new JSONObject();
    }

    private static JSONObject optObject(JSONObject obj, String... keys) {
        if (obj == null || keys == null) return null;
        for (String key : keys) {
            if (key == null) continue;
            JSONObject child = obj.optJSONObject(key);
            if (child != null) return child;
        }
        return null;
    }

    private static JSONArray optArray(JSONObject obj, String... keys) {
        if (obj == null || keys == null) return null;
        for (String key : keys) {
            if (key == null) continue;
            JSONArray arr = obj.optJSONArray(key);
            if (arr != null) return arr;
        }
        return null;
    }

    private static String optString(JSONObject obj, String... keys) {
        if (obj == null || keys == null) return null;
        for (String key : keys) {
            if (key == null || !obj.has(key) || obj.isNull(key)) continue;
            Object raw = obj.opt(key);
            if (raw instanceof JSONObject) continue;
            String value = String.valueOf(raw).trim();
            if (!value.isEmpty()) return value;
        }
        return null;
    }

    private static long optLong(JSONObject obj, String... keys) {
        if (obj == null || keys == null) return -1L;
        for (String key : keys) {
            if (key == null || !obj.has(key) || obj.isNull(key)) continue;
            Object raw = obj.opt(key);
            long value = asLong(raw);
            if (value >= 0L) return value;
        }
        return -1L;
    }

    private static long firstPositive(long... values) {
        if (values == null) return 0L;
        for (long value : values) {
            if (value > 0L) return value;
        }
        return 0L;
    }

    private static String coalesce(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (!isBlank(value)) return value.trim();
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String normalizeTierName(String tier) {
        if (tier == null) return "free";
        String t = tier.trim().toLowerCase();
        if (t.isEmpty()) return "free";
        if ("basic".equals(t)) return "free";
        if ("premium".equals(t)) return "pro";
        if ("plus".equals(t)) return "pro";
        if ("enterprise".equals(t)) return "max";
        return t;
    }

    // ── Response parsing ──────────────────────────────────────────────────────

    private static AuthToken extractAuthToken(JSONObject resp) throws JSONException {
        if (resp == null) throw new JSONException("Empty response");
        String token = resp.optString("token", null);
        if (token == null || token.trim().isEmpty()) {
            token = resp.optString("access_token", null);
        }
        if (token == null || token.trim().isEmpty()) {
            throw new JSONException("Missing token");
        }
        long expiresAt = resp.optLong("expires_at", 0L);
        expiresAt = normalizeExpiry(expiresAt);
        return new AuthToken(token.trim(), expiresAt);
    }

    private static String extractAssistantContent(JSONObject resp) {
        if (resp == null) return "";

        // Primary format: { content: "..." }
        String content = resp.optString("content", null);
        if (content != null && !content.trim().isEmpty()) return content;

        // Possible nested formats
        JSONObject assistant = resp.optJSONObject("assistant");
        if (assistant != null) {
            content = assistant.optString("content", null);
            if (content != null && !content.trim().isEmpty()) return content;
            content = assistant.optString("message", null);
            if (content != null && !content.trim().isEmpty()) return content;
        }

        Object messageObj = resp.opt("message");
        if (messageObj instanceof JSONObject) {
            JSONObject m = (JSONObject) messageObj;
            content = m.optString("content", null);
            if (content != null && !content.trim().isEmpty()) return content;
            content = m.optString("text", null);
            if (content != null && !content.trim().isEmpty()) return content;
        } else if (messageObj instanceof String) {
            content = (String) messageObj;
            if (!content.trim().isEmpty()) return content;
        }

        // OpenAI-style: { choices: [ { message: { content: "..." } } ] }
        JSONArray choices = resp.optJSONArray("choices");
        if (choices != null && choices.length() > 0) {
            JSONObject c0 = choices.optJSONObject(0);
            if (c0 != null) {
                JSONObject msg = c0.optJSONObject("message");
                if (msg != null) {
                    content = msg.optString("content", null);
                    if (content != null && !content.trim().isEmpty()) return content;
                }
                content = c0.optString("text", null);
                if (content != null && !content.trim().isEmpty()) return content;
            }
        }

        // Fallback: show the raw JSON.
        return resp.toString();
    }

    private static UserProfile extractUserProfile(JSONObject resp) {
        if (resp == null) {
            return new UserProfile("", "", "", "free", "auto");
        }
        return new UserProfile(
            resp.optString("user_id", ""),
            resp.optString("email", ""),
            resp.optString("name", ""),
            resp.optString("tier", "free"),
            resp.optString("language", "auto")
        );
    }

    private static UserDataExport extractUserDataExport(JSONObject resp) throws JSONException {
        if (resp == null) {
            throw new JSONException("Empty export response");
        }
        String exportId = resp.optString("export_id", "").trim();
        String downloadUrl = resp.optString("download_url", "").trim();
        long expiresAt = resp.optLong("expires_at", 0L);

        if (downloadUrl.isEmpty()) {
            throw new JSONException("Missing export download_url");
        }
        if (downloadUrl.startsWith("/")) {
            downloadUrl = BASE_URL + downloadUrl;
        }
        return new UserDataExport(exportId, downloadUrl, expiresAt);
    }

    private static AIConfig extractAIConfig(JSONObject resp) {
        JSONObject payload = resp == null ? null : resp.optJSONObject("ai_config");
        if (payload == null) payload = resp;

        String persona = "assistant";
        String customPrompt = null;
        Double temperature = null;
        if (payload != null) {
            persona = normalizePersona(payload.optString("persona", "assistant"));
            customPrompt = asStringOrNull(payload.opt("custom_prompt"));
            temperature = asDoubleOrNull(payload.opt("temperature"));
        }

        List<String> personas = new ArrayList<>();
        if (resp != null) {
            JSONArray arr = resp.optJSONArray("personas");
            if (arr == null) arr = resp.optJSONArray("available_personas");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String p = normalizePersona(arr.optString(i, ""));
                    if (!p.isEmpty() && !personas.contains(p)) {
                        personas.add(p);
                    }
                }
            }
        }
        for (String fallback : DEFAULT_PERSONAS) {
            if (!personas.contains(fallback)) {
                personas.add(fallback);
            }
        }
        return new AIConfig(persona, customPrompt, temperature, personas);
    }

    private static String normalizeLanguage(String language) {
        String normalized = language == null ? "" : language.trim().toLowerCase();
        if (normalized.isEmpty()) return "auto";
        if ("zh-cn".equals(normalized) || "zh-hans".equals(normalized)) return "zh";
        if ("en-us".equals(normalized) || "en-gb".equals(normalized)) return "en";
        return normalized;
    }

    private static String normalizePersona(String persona) {
        String normalized = persona == null ? "" : persona.trim().toLowerCase();
        if (normalized.isEmpty()) return "assistant";
        if ("general".equals(normalized)) return "assistant";
        if ("coding".equals(normalized)) return "coder";
        if ("writing".equals(normalized)) return "writer";
        if ("translation".equals(normalized)) return "translator";
        return normalized;
    }

    private static String escapeQuoted(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String sanitizeFileNamePart(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("[^a-zA-Z0-9_-]", "");
    }
}
