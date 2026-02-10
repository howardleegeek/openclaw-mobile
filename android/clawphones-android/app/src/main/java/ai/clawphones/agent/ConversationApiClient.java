package ai.clawphones.agent;

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
 * HTTP client for ClawPhones Conversation API.
 * Mirrors the iOS OpenClawAPI.swift implementation.
 *
 * Endpoints:
 *   POST   /v1/conversations              — create conversation
 *   GET    /v1/conversations               — list conversations
 *   GET    /v1/conversations/{id}          — get conversation detail
 *   POST   /v1/conversations/{id}/chat     — send message
 *   DELETE /v1/conversations/{id}          — delete conversation
 *
 * All methods run on the calling thread — wrap in AsyncTask or Executor.
 */
public class ConversationApiClient {

    private static final String LOG_TAG = "ConversationApiClient";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    private final String baseUrl;
    private final String deviceToken;

    public ConversationApiClient(String baseUrl, String deviceToken) {
        // Strip trailing slash
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.deviceToken = deviceToken;
    }

    // ── Data classes ────────────────────────────────────────────

    public static class Conversation {
        public final String id;
        public final String title; // may be null
        public final long createdAt;

        public Conversation(String id, String title, long createdAt) {
            this.id = id;
            this.title = title;
            this.createdAt = createdAt;
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

    public static class Message {
        public final String id;
        public final String role;
        public final String content;
        public final long createdAt;

        public Message(String id, String role, String content, long createdAt) {
            this.id = id;
            this.role = role;
            this.content = content;
            this.createdAt = createdAt;
        }
    }

    public static class ConversationDetail {
        public final String id;
        public final String title; // may be null
        public final long createdAt;
        public final List<Message> messages;

        public ConversationDetail(String id, String title, long createdAt, List<Message> messages) {
            this.id = id;
            this.title = title;
            this.createdAt = createdAt;
            this.messages = messages;
        }
    }

    public static class ChatResponse {
        public final String messageId;
        public final String role;
        public final String content;
        public final String conversationId;
        public final long createdAt;

        public ChatResponse(String messageId, String role, String content, String conversationId, long createdAt) {
            this.messageId = messageId;
            this.role = role;
            this.content = content;
            this.conversationId = conversationId;
            this.createdAt = createdAt;
        }
    }

    public static class ApiException extends Exception {
        public final int statusCode;
        public ApiException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }

    // ── Endpoints ───────────────────────────────────────────────

    /** POST /v1/conversations */
    public Conversation createConversation(String systemPrompt) throws IOException, ApiException, JSONException {
        JSONObject body = new JSONObject();
        if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
            body.put("system_prompt", systemPrompt.trim());
        }

        JSONObject resp = doPost(baseUrl + "/v1/conversations", body);
        return new Conversation(
            resp.getString("id"),
            resp.optString("title", null),
            resp.getLong("created_at")
        );
    }

    /** GET /v1/conversations?limit=&offset= */
    public List<ConversationSummary> listConversations(int limit, int offset) throws IOException, ApiException, JSONException {
        String url = baseUrl + "/v1/conversations?limit=" + limit + "&offset=" + offset;
        JSONObject resp = doGet(url);
        JSONArray arr = resp.getJSONArray("conversations");
        List<ConversationSummary> result = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.getJSONObject(i);
            result.add(new ConversationSummary(
                c.getString("id"),
                c.optString("title", null),
                c.getLong("created_at"),
                c.optLong("updated_at", 0),
                c.optInt("message_count", 0)
            ));
        }
        return result;
    }

    /** GET /v1/conversations/{id} */
    public ConversationDetail getConversation(String conversationId) throws IOException, ApiException, JSONException {
        JSONObject resp = doGet(baseUrl + "/v1/conversations/" + conversationId);
        JSONArray msgArr = resp.getJSONArray("messages");
        List<Message> messages = new ArrayList<>(msgArr.length());
        for (int i = 0; i < msgArr.length(); i++) {
            JSONObject m = msgArr.getJSONObject(i);
            messages.add(new Message(
                m.getString("id"),
                m.getString("role"),
                m.getString("content"),
                m.getLong("created_at")
            ));
        }
        return new ConversationDetail(
            resp.getString("id"),
            resp.optString("title", null),
            resp.getLong("created_at"),
            messages
        );
    }

    /** POST /v1/conversations/{id}/chat */
    public ChatResponse chat(String conversationId, String message) throws IOException, ApiException, JSONException {
        JSONObject body = new JSONObject();
        body.put("message", message);

        JSONObject resp = doPost(baseUrl + "/v1/conversations/" + conversationId + "/chat", body);
        return new ChatResponse(
            resp.getString("message_id"),
            resp.getString("role"),
            resp.getString("content"),
            resp.getString("conversation_id"),
            resp.getLong("created_at")
        );
    }

    /** DELETE /v1/conversations/{id} */
    public boolean deleteConversation(String conversationId) throws IOException, ApiException, JSONException {
        JSONObject resp = doDelete(baseUrl + "/v1/conversations/" + conversationId);
        return resp.optBoolean("deleted", false);
    }

    // ── HTTP internals ──────────────────────────────────────────

    private JSONObject doGet(String urlStr) throws IOException, ApiException, JSONException {
        HttpURLConnection conn = openConnection(urlStr, "GET");
        return readResponse(conn);
    }

    private JSONObject doPost(String urlStr, JSONObject body) throws IOException, ApiException, JSONException {
        HttpURLConnection conn = openConnection(urlStr, "POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        return readResponse(conn);
    }

    private JSONObject doDelete(String urlStr) throws IOException, ApiException, JSONException {
        HttpURLConnection conn = openConnection(urlStr, "DELETE");
        return readResponse(conn);
    }

    private HttpURLConnection openConnection(String urlStr, String method) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod(method);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + deviceToken);
        return conn;
    }

    private JSONObject readResponse(HttpURLConnection conn) throws IOException, ApiException, JSONException {
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

        if (code < 200 || code >= 300) {
            Logger.logError(LOG_TAG, "API error " + code + ": " + sb);
            throw new ApiException(code, sb.toString());
        }

        return new JSONObject(sb.toString());
    }
}
