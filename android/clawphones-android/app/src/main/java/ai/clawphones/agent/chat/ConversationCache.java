package ai.clawphones.agent.chat;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Local conversation cache for offline-first chat UX.
 */
public final class ConversationCache extends SQLiteOpenHelper {

    public static final int MAX_CONVERSATIONS = 50;
    public static final int MAX_MESSAGES_PER_CONVERSATION = 100;

    private static final String DB_NAME = "clawphones_conversations_cache.db";
    private static final int DB_VERSION = 1;

    private static final String TABLE_CONVERSATIONS = "conversations";
    private static final String TABLE_MESSAGES = "messages";

    public ConversationCache(@NonNull Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS " + TABLE_CONVERSATIONS + " (" +
                "id TEXT PRIMARY KEY," +
                "title TEXT," +
                "created_at INTEGER NOT NULL DEFAULT 0," +
                "updated_at INTEGER NOT NULL DEFAULT 0," +
                "message_count INTEGER NOT NULL DEFAULT 0," +
                "cached_at INTEGER NOT NULL DEFAULT 0" +
            ")"
        );
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_conversations_sort " +
                "ON " + TABLE_CONVERSATIONS + "(updated_at DESC, created_at DESC)"
        );

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS " + TABLE_MESSAGES + " (" +
                "local_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "conversation_id TEXT NOT NULL," +
                "message_id TEXT," +
                "role TEXT NOT NULL," +
                "content TEXT NOT NULL," +
                "created_at INTEGER NOT NULL DEFAULT 0," +
                "dedupe_key TEXT NOT NULL," +
                "UNIQUE(conversation_id, dedupe_key) ON CONFLICT REPLACE," +
                "FOREIGN KEY(conversation_id) REFERENCES " + TABLE_CONVERSATIONS + "(id) ON DELETE CASCADE" +
            ")"
        );
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_messages_conversation_created " +
                "ON " + TABLE_MESSAGES + "(conversation_id, created_at DESC, local_id DESC)"
        );
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_MESSAGES);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_CONVERSATIONS);
        onCreate(db);
    }

    public synchronized List<ClawPhonesAPI.ConversationSummary> getRecentConversations() {
        return getRecentConversations(MAX_CONVERSATIONS);
    }

    public synchronized List<ClawPhonesAPI.ConversationSummary> getRecentConversations(int limit) {
        int safeLimit = Math.max(1, limit);
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
            "SELECT id, title, created_at, updated_at, message_count " +
                "FROM " + TABLE_CONVERSATIONS + " " +
                "ORDER BY CASE WHEN updated_at > 0 THEN updated_at ELSE created_at END DESC " +
                "LIMIT ?",
            new String[]{String.valueOf(safeLimit)}
        );
        List<ClawPhonesAPI.ConversationSummary> out = new ArrayList<>();
        try {
            while (cursor.moveToNext()) {
                out.add(new ClawPhonesAPI.ConversationSummary(
                    cursor.getString(0),
                    cursor.isNull(1) ? null : cursor.getString(1),
                    cursor.getLong(2),
                    cursor.getLong(3),
                    cursor.getInt(4)
                ));
            }
        } finally {
            cursor.close();
        }
        return out;
    }

    public synchronized void upsertConversations(List<ClawPhonesAPI.ConversationSummary> conversations) {
        if (conversations == null) return;

        List<ClawPhonesAPI.ConversationSummary> sorted = new ArrayList<>(conversations);
        Collections.sort(sorted, new Comparator<ClawPhonesAPI.ConversationSummary>() {
            @Override
            public int compare(ClawPhonesAPI.ConversationSummary a, ClawPhonesAPI.ConversationSummary b) {
                long at = a.updatedAt > 0 ? a.updatedAt : a.createdAt;
                long bt = b.updatedAt > 0 ? b.updatedAt : b.createdAt;
                return Long.compare(bt, at);
            }
        });

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            int keep = Math.min(MAX_CONVERSATIONS, sorted.size());
            for (int i = 0; i < keep; i++) {
                ClawPhonesAPI.ConversationSummary item = sorted.get(i);
                if (item == null || TextUtils.isEmpty(item.id)) continue;
                upsertConversationInternal(db, item);
            }
            pruneConversationLimitInternal(db);
            pruneAllMessagesInternal(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized void upsertConversation(@NonNull ClawPhonesAPI.ConversationSummary summary) {
        if (TextUtils.isEmpty(summary.id)) return;

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            upsertConversationInternal(db, summary);
            pruneConversationLimitInternal(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized void removeConversation(String conversationId) {
        if (TextUtils.isEmpty(conversationId)) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(TABLE_MESSAGES, "conversation_id = ?", new String[]{conversationId});
            db.delete(TABLE_CONVERSATIONS, "id = ?", new String[]{conversationId});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized void clearAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(TABLE_MESSAGES, null, null);
            db.delete(TABLE_CONVERSATIONS, null, null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized List<Map<String, Object>> getRecentMessages(String conversationId) {
        return getRecentMessages(conversationId, MAX_MESSAGES_PER_CONVERSATION);
    }

    public synchronized List<Map<String, Object>> getRecentMessages(String conversationId, int limit) {
        if (TextUtils.isEmpty(conversationId)) return new ArrayList<>();

        int safeLimit = Math.max(1, limit);
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
            "SELECT message_id, role, content, created_at FROM (" +
                "SELECT message_id, role, content, created_at, local_id " +
                "FROM " + TABLE_MESSAGES + " " +
                "WHERE conversation_id = ? " +
                "ORDER BY created_at DESC, local_id DESC " +
                "LIMIT ?" +
            ") ORDER BY created_at ASC",
            new String[]{conversationId, String.valueOf(safeLimit)}
        );
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            while (cursor.moveToNext()) {
                Map<String, Object> row = new HashMap<>();
                String messageId = cursor.isNull(0) ? "" : cursor.getString(0);
                row.put("id", messageId);
                row.put("role", cursor.getString(1));
                row.put("content", cursor.getString(2));
                row.put("created_at", cursor.getLong(3));
                out.add(row);
            }
        } finally {
            cursor.close();
        }
        return out;
    }

    public synchronized void replaceMessages(String conversationId, List<Map<String, Object>> messages) {
        if (TextUtils.isEmpty(conversationId)) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ensureConversationRowInternal(db, conversationId);
            db.delete(TABLE_MESSAGES, "conversation_id = ?", new String[]{conversationId});
            insertMessagesInternal(db, conversationId, messages);
            pruneMessagesLimitInternal(db, conversationId);
            updateConversationStatsInternal(db, conversationId);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized void upsertMessages(String conversationId, List<Map<String, Object>> messages) {
        if (TextUtils.isEmpty(conversationId)) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ensureConversationRowInternal(db, conversationId);
            insertMessagesInternal(db, conversationId, messages);
            pruneMessagesLimitInternal(db, conversationId);
            updateConversationStatsInternal(db, conversationId);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private void upsertConversationInternal(SQLiteDatabase db, ClawPhonesAPI.ConversationSummary item) {
        ContentValues values = new ContentValues();
        values.put("id", safeTrim(item.id));
        values.put("title", nullableTrim(item.title));
        values.put("created_at", Math.max(0L, item.createdAt));
        values.put("updated_at", Math.max(0L, item.updatedAt));
        values.put("message_count", Math.max(0, item.messageCount));
        values.put("cached_at", nowSeconds());
        db.insertWithOnConflict(TABLE_CONVERSATIONS, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private void ensureConversationRowInternal(SQLiteDatabase db, String conversationId) {
        ContentValues values = new ContentValues();
        values.put("id", safeTrim(conversationId));
        values.put("cached_at", nowSeconds());
        db.insertWithOnConflict(TABLE_CONVERSATIONS, null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    private void insertMessagesInternal(SQLiteDatabase db, String conversationId, List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) return;

        for (Map<String, Object> row : messages) {
            if (row == null) continue;
            String role = safeTrim(asString(row.get("role")));
            String content = asString(row.get("content"));
            if (TextUtils.isEmpty(role) || TextUtils.isEmpty(content)) continue;

            String messageId = nullableTrim(asString(row.get("id")));
            long createdAt = asLong(row.get("created_at"));
            if (createdAt < 0) createdAt = 0L;

            ContentValues values = new ContentValues();
            values.put("conversation_id", conversationId);
            values.put("message_id", messageId);
            values.put("role", role);
            values.put("content", content);
            values.put("created_at", createdAt);
            values.put("dedupe_key", buildDedupeKey(messageId, role, content, createdAt));
            db.insertWithOnConflict(TABLE_MESSAGES, null, values, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    private void pruneConversationLimitInternal(SQLiteDatabase db) {
        db.execSQL(
            "DELETE FROM " + TABLE_CONVERSATIONS + " " +
                "WHERE id NOT IN (" +
                    "SELECT id FROM " + TABLE_CONVERSATIONS + " " +
                    "ORDER BY CASE WHEN updated_at > 0 THEN updated_at ELSE created_at END DESC " +
                    "LIMIT " + MAX_CONVERSATIONS +
                ")"
        );
    }

    private void pruneAllMessagesInternal(SQLiteDatabase db) {
        Cursor cursor = db.rawQuery("SELECT id FROM " + TABLE_CONVERSATIONS, null);
        List<String> conversationIds = new ArrayList<>();
        try {
            while (cursor.moveToNext()) {
                conversationIds.add(cursor.getString(0));
            }
        } finally {
            cursor.close();
        }
        for (String conversationId : conversationIds) {
            pruneMessagesLimitInternal(db, conversationId);
            updateConversationStatsInternal(db, conversationId);
        }
    }

    private void pruneMessagesLimitInternal(SQLiteDatabase db, String conversationId) {
        db.execSQL(
            "DELETE FROM " + TABLE_MESSAGES + " " +
                "WHERE local_id IN (" +
                    "SELECT local_id FROM " + TABLE_MESSAGES + " " +
                    "WHERE conversation_id = ? " +
                    "ORDER BY created_at DESC, local_id DESC " +
                    "LIMIT -1 OFFSET " + MAX_MESSAGES_PER_CONVERSATION +
                ")",
            new Object[]{conversationId}
        );
    }

    private void updateConversationStatsInternal(SQLiteDatabase db, String conversationId) {
        Cursor countCursor = db.rawQuery(
            "SELECT COUNT(*), COALESCE(MAX(created_at), 0) FROM " + TABLE_MESSAGES + " WHERE conversation_id = ?",
            new String[]{conversationId}
        );
        int messageCount = 0;
        long latestMessageTs = 0L;
        try {
            if (countCursor.moveToFirst()) {
                messageCount = countCursor.getInt(0);
                latestMessageTs = countCursor.getLong(1);
            }
        } finally {
            countCursor.close();
        }

        Cursor currentCursor = db.rawQuery(
            "SELECT created_at, updated_at FROM " + TABLE_CONVERSATIONS + " WHERE id = ? LIMIT 1",
            new String[]{conversationId}
        );
        long createdAt = 0L;
        long updatedAt = 0L;
        try {
            if (currentCursor.moveToFirst()) {
                createdAt = currentCursor.getLong(0);
                updatedAt = currentCursor.getLong(1);
            }
        } finally {
            currentCursor.close();
        }

        ContentValues values = new ContentValues();
        values.put("message_count", Math.max(0, messageCount));
        values.put("cached_at", nowSeconds());
        if (createdAt <= 0 && latestMessageTs > 0) {
            values.put("created_at", latestMessageTs);
        }
        long mergedUpdatedAt = Math.max(updatedAt, latestMessageTs);
        if (mergedUpdatedAt > 0) {
            values.put("updated_at", mergedUpdatedAt);
        }
        db.update(TABLE_CONVERSATIONS, values, "id = ?", new String[]{conversationId});
    }

    private static String buildDedupeKey(String messageId, String role, String content, long createdAt) {
        if (!TextUtils.isEmpty(messageId)) {
            return "id:" + messageId;
        }
        return "f:" + safeTrim(role) + ":" + createdAt + ":" + stableHash(content);
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    private static String safeTrim(String value) {
        if (value == null) return "";
        return value.trim();
    }

    private static String nullableTrim(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long asLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static String stableHash(String input) {
        long hash = 1469598103934665603L;
        final long prime = 1099511628211L;
        byte[] bytes = (input == null ? "" : input).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (byte b : bytes) {
            hash ^= (b & 0xff);
            hash *= prime;
        }
        return Long.toHexString(hash);
    }
}
