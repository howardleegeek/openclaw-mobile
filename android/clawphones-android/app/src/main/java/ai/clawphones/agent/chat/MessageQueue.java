package ai.clawphones.agent.chat;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Local queue for user messages that could not be sent immediately.
 */
final class MessageQueue {

    static final String STATUS_PENDING = "pending";
    static final String STATUS_SENDING = "sending";
    static final String STATUS_FAILED = "failed";

    static final class PendingMessage {
        final long id;
        final String message;
        final String conversationId;
        final long createdAt;
        final String status;
        final int retryCount;

        PendingMessage(long id, String message, String conversationId,
                       long createdAt, String status, int retryCount) {
            this.id = id;
            this.message = message;
            this.conversationId = conversationId;
            this.createdAt = createdAt;
            this.status = status;
            this.retryCount = retryCount;
        }
    }

    private static final String DB_NAME = "clawphones_chat.db";
    private static final int DB_VERSION = 1;

    private static final String TABLE_PENDING = "pending_messages";
    private static final String COL_ID = "id";
    private static final String COL_MESSAGE = "message";
    private static final String COL_CONVERSATION_ID = "conversation_id";
    private static final String COL_CREATED_AT = "created_at";
    private static final String COL_STATUS = "status";
    private static final String COL_RETRY_COUNT = "retry_count";

    private final SQLiteOpenHelper mHelper;

    MessageQueue(@NonNull Context context) {
        mHelper = new SQLiteOpenHelper(context.getApplicationContext(), DB_NAME, null, DB_VERSION) {
            @Override
            public void onCreate(SQLiteDatabase db) {
                db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_PENDING + " ("
                    + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + COL_MESSAGE + " TEXT NOT NULL, "
                    + COL_CONVERSATION_ID + " TEXT NOT NULL, "
                    + COL_CREATED_AT + " INTEGER NOT NULL, "
                    + COL_STATUS + " TEXT NOT NULL, "
                    + COL_RETRY_COUNT + " INTEGER NOT NULL DEFAULT 0"
                    + ")");
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_pending_status_created "
                    + "ON " + TABLE_PENDING + "(" + COL_STATUS + ", " + COL_CREATED_AT + ")");
            }

            @Override
            public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
                if (oldVersion < 1) {
                    onCreate(db);
                }
            }
        };
    }

    synchronized long enqueue(@NonNull String message, @Nullable String conversationId) {
        SQLiteDatabase db = mHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_MESSAGE, message);
        values.put(COL_CONVERSATION_ID, normalizeConversationId(conversationId));
        values.put(COL_CREATED_AT, System.currentTimeMillis());
        values.put(COL_STATUS, STATUS_PENDING);
        values.put(COL_RETRY_COUNT, 0);
        return db.insertOrThrow(TABLE_PENDING, null, values);
    }

    synchronized void assignConversationIdForEmpty(@NonNull String conversationId) {
        if (TextUtils.isEmpty(conversationId)) return;
        SQLiteDatabase db = mHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_CONVERSATION_ID, conversationId);
        db.update(
            TABLE_PENDING,
            values,
            COL_CONVERSATION_ID + " = ?",
            new String[]{""}
        );
    }

    synchronized void updateConversationId(long id, @NonNull String conversationId) {
        if (TextUtils.isEmpty(conversationId)) return;
        SQLiteDatabase db = mHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_CONVERSATION_ID, conversationId);
        db.update(TABLE_PENDING, values, COL_ID + " = ?", new String[]{String.valueOf(id)});
    }

    synchronized void markSending(long id) {
        updateStatus(id, STATUS_SENDING);
    }

    synchronized void markPending(long id) {
        updateStatus(id, STATUS_PENDING);
    }

    synchronized void markFailed(long id) {
        updateStatus(id, STATUS_FAILED);
    }

    synchronized void resetForManualRetry(long id) {
        SQLiteDatabase db = mHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_STATUS, STATUS_PENDING);
        values.put(COL_RETRY_COUNT, 0);
        db.update(TABLE_PENDING, values, COL_ID + " = ?", new String[]{String.valueOf(id)});
    }

    synchronized int incrementRetryCount(long id) {
        SQLiteDatabase db = mHelper.getWritableDatabase();
        db.execSQL("UPDATE " + TABLE_PENDING + " SET "
            + COL_RETRY_COUNT + " = " + COL_RETRY_COUNT + " + 1 WHERE " + COL_ID + " = ?",
            new Object[]{id});

        Cursor c = null;
        try {
            c = db.query(
                TABLE_PENDING,
                new String[]{COL_RETRY_COUNT},
                COL_ID + " = ?",
                new String[]{String.valueOf(id)},
                null,
                null,
                null
            );
            if (c.moveToFirst()) {
                return c.getInt(0);
            }
            return 0;
        } finally {
            if (c != null) c.close();
        }
    }

    synchronized void remove(long id) {
        SQLiteDatabase db = mHelper.getWritableDatabase();
        db.delete(TABLE_PENDING, COL_ID + " = ?", new String[]{String.valueOf(id)});
    }

    synchronized void clearAll() {
        SQLiteDatabase db = mHelper.getWritableDatabase();
        db.delete(TABLE_PENDING, null, null);
    }

    @Nullable
    synchronized PendingMessage getNextPendingToSend() {
        List<PendingMessage> list = queryPending(
            COL_STATUS + " IN (?, ?)",
            new String[]{STATUS_PENDING, STATUS_SENDING},
            COL_CREATED_AT + " ASC LIMIT 1"
        );
        return list.isEmpty() ? null : list.get(0);
    }

    @Nullable
    synchronized PendingMessage getNextPendingToSendForConversation(@Nullable String conversationId) {
        String normalized = normalizeConversationId(conversationId);
        if (TextUtils.isEmpty(normalized)) {
            List<PendingMessage> list = queryPending(
                COL_CONVERSATION_ID + " = ? AND " + COL_STATUS + " IN (?, ?)",
                new String[]{"", STATUS_PENDING, STATUS_SENDING},
                COL_CREATED_AT + " ASC LIMIT 1"
            );
            return list.isEmpty() ? null : list.get(0);
        }

        List<PendingMessage> list = queryPending(
            "(" + COL_CONVERSATION_ID + " = ? OR " + COL_CONVERSATION_ID + " = ?) AND "
                + COL_STATUS + " IN (?, ?)",
            new String[]{normalized, "", STATUS_PENDING, STATUS_SENDING},
            COL_CREATED_AT + " ASC LIMIT 1"
        );
        return list.isEmpty() ? null : list.get(0);
    }

    synchronized List<PendingMessage> listQueuedForConversation(@Nullable String conversationId) {
        String normalized = normalizeConversationId(conversationId);
        return queryPending(
            COL_CONVERSATION_ID + " = ? AND " + COL_STATUS + " IN (?, ?, ?)",
            new String[]{normalized, STATUS_PENDING, STATUS_SENDING, STATUS_FAILED},
            COL_CREATED_AT + " ASC"
        );
    }

    synchronized List<PendingMessage> listQueuedWithoutConversation() {
        return queryPending(
            COL_CONVERSATION_ID + " = ? AND " + COL_STATUS + " IN (?, ?, ?)",
            new String[]{"", STATUS_PENDING, STATUS_SENDING, STATUS_FAILED},
            COL_CREATED_AT + " ASC"
        );
    }

    private List<PendingMessage> queryPending(String selection, String[] args, String orderBy) {
        SQLiteDatabase db = mHelper.getReadableDatabase();
        Cursor c = null;
        List<PendingMessage> out = new ArrayList<>();
        try {
            c = db.query(
                TABLE_PENDING,
                new String[]{COL_ID, COL_MESSAGE, COL_CONVERSATION_ID, COL_CREATED_AT, COL_STATUS, COL_RETRY_COUNT},
                selection,
                args,
                null,
                null,
                orderBy
            );
            while (c.moveToNext()) {
                out.add(new PendingMessage(
                    c.getLong(0),
                    c.getString(1),
                    c.getString(2),
                    c.getLong(3),
                    c.getString(4),
                    c.getInt(5)
                ));
            }
            return out;
        } finally {
            if (c != null) c.close();
        }
    }

    private void updateStatus(long id, @NonNull String status) {
        SQLiteDatabase db = mHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_STATUS, status);
        db.update(TABLE_PENDING, values, COL_ID + " = ?", new String[]{String.valueOf(id)});
    }

    private static String normalizeConversationId(@Nullable String conversationId) {
        if (conversationId == null) return "";
        return conversationId.trim();
    }
}
