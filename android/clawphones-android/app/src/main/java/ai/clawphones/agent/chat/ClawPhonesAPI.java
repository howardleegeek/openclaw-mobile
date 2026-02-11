package ai.clawphones.agent.chat;

import android.content.Context;
import android.content.SharedPreferences;

import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal HTTP client for ClawPhones backend API.
 *
 * BASE_URL: http://3.142.69.6:8080
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

    public static final String BASE_URL = "http://3.142.69.6:8080";

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    private static final String PREFS = "clawphones_api";
    private static final String PREF_TOKEN = "token";

    public static class ApiException extends Exception {
        public final int statusCode;
        public ApiException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
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

    // ── SharedPreferences helpers ───────────────────────────────────────────────

    public static void saveToken(Context context, String token) {
        if (context == null) return;
        if (token == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_TOKEN, token)
            .apply();
    }

    public static String getToken(Context context) {
        if (context == null) return null;
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String t = sp.getString(PREF_TOKEN, null);
        if (t == null) return null;
        t = t.trim();
        return t.isEmpty() ? null : t;
    }

    public static void clearToken(Context context) {
        if (context == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(PREF_TOKEN)
            .apply();
    }

    // ── Auth ──────────────────────────────────────────────────────────────────

    /** POST /v1/auth/register -> token */
    public static String register(String email, String password, String name)
        throws IOException, ApiException, JSONException {
        JSONObject body = new JSONObject();
        body.put("email", email);
        body.put("password", password);
        body.put("name", name);
        JSONObject resp = doPost(BASE_URL + "/v1/auth/register", body, null);
        return extractToken(resp);
    }

    /** POST /v1/auth/login -> token */
    public static String login(String email, String password)
        throws IOException, ApiException, JSONException {
        JSONObject body = new JSONObject();
        body.put("email", email);
        body.put("password", password);
        JSONObject resp = doPost(BASE_URL + "/v1/auth/login", body, null);
        return extractToken(resp);
    }

    // ── Conversations ─────────────────────────────────────────────────────────

    /** POST /v1/conversations -> conversationId */
    public static String createConversation(String token)
        throws IOException, ApiException, JSONException {
        JSONObject resp = doPost(BASE_URL + "/v1/conversations", new JSONObject(), token);
        String id = resp.optString("id", null);
        if (id == null || id.trim().isEmpty()) {
            throw new JSONException("Missing conversation id");
        }
        return id;
    }

    /** GET /v1/conversations -> list */
    public static List<ConversationSummary> listConversations(String token)
        throws IOException, ApiException, JSONException {
        JSONObject resp = doGet(BASE_URL + "/v1/conversations", token);
        JSONArray arr = resp.optJSONArray("conversations");
        List<ConversationSummary> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c == null) continue;
            out.add(new ConversationSummary(
                c.optString("id", ""),
                c.isNull("title") ? null : c.optString("title", null),
                c.optLong("created_at", 0),
                c.optLong("updated_at", 0),
                c.optInt("message_count", 0)
            ));
        }
        return out;
    }

    /** POST /v1/conversations/{id}/chat -> assistant content */
    public static String chat(String token, String conversationId, String message)
        throws IOException, ApiException, JSONException {
        JSONObject body = new JSONObject();
        body.put("message", message);
        String url = BASE_URL + "/v1/conversations/" + conversationId + "/chat";
        JSONObject resp = doPost(url, body, token);
        return extractAssistantContent(resp);
    }

    // ── HTTP internals ────────────────────────────────────────────────────────

    private static JSONObject doGet(String urlStr, String token) throws IOException, ApiException, JSONException {
        HttpURLConnection conn = openConnection(urlStr, "GET", token);
        return readResponse(conn);
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
        BufferedReader reader;
        if (code >= 200 && code < 300) {
            reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        } else {
            reader = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
        }

        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        conn.disconnect();

        String raw = sb.toString();
        if (code < 200 || code >= 300) {
            Logger.logError(LOG_TAG, "API error " + code + ": " + raw);
            throw new ApiException(code, raw);
        }
        return new JSONObject(raw);
    }

    // ── Response parsing ──────────────────────────────────────────────────────

    private static String extractToken(JSONObject resp) throws JSONException {
        if (resp == null) throw new JSONException("Empty response");
        String token = resp.optString("token", null);
        if (token == null || token.trim().isEmpty()) {
            token = resp.optString("access_token", null);
        }
        if (token == null || token.trim().isEmpty()) {
            throw new JSONException("Missing token");
        }
        return token.trim();
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
}

