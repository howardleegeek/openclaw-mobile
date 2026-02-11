package ai.clawphones.agent.chat;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.text.TextUtils;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Relay transport for ClawVision node ingestion on Android.
 */
public final class RelayClient {

    private static final String LOG_TAG = "RelayClient";
    private static final String PREFS = "clawphones_relay";
    private static final String SECURE_PREFS = "clawphones_relay_secure";
    private static final String PREF_SECURE_MIGRATED = "secure_migrated_v1";
    private static final String PREF_RELAY_URL = "relay_url";
    private static final String PREF_NODE_ID = "relay_node_id";
    private static final String PREF_TOKEN = "relay_token";
    private static final String PREF_LAST_LAT = "relay_last_lat";
    private static final String PREF_LAST_LON = "relay_last_lon";
    private static final String PREF_LAST_HEADING = "relay_last_heading";
    private static final String PREF_HAS_HEADING = "relay_has_heading";
    private static final String PREF_LAST_TS = "relay_last_ts";
    private static final String DEFAULT_RELAY_URL = "http://localhost:8787";
    private static final long HEARTBEAT_INTERVAL_MINUTES = 5L;
    private static final int MAX_QUEUE_RETRY = 3;
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final String HEARTBEAT_JPEG_BASE64 = "/9j/4AAQSkZJRgABAQAAAQABAAD/2wCEAAkGBxISEhUTEhIVFhUVFRUVFRUVFRUWFxUVFRUXFhUVFRUYHSggGBolHRUVITEhJSkrLi4uFx8zODMtNygtLisBCgoKDg0OGhAQGi0lHyUtLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLf/AABEIABQAFAMBIgACEQEDEQH/xAAXAAEBAQEAAAAAAAAAAAAAAAAAAQID/8QAFxEBAQEBAAAAAAAAAAAAAAAAAQACEf/aAAwDAQACEAMQAAAB6AA//8QAFhEBAQEAAAAAAAAAAAAAAAAAABEh/9oACAEBAAEFAn//xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oACAEDAQE/AR//xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oACAECAQE/AR//xAAVEAEBAAAAAAAAAAAAAAAAAAABEP/aAAgBAQAGPwJf/8QAFhABAQEAAAAAAAAAAAAAAAAAARAR/9oACAEBAAE/Idf/xAAWEQEBAQAAAAAAAAAAAAAAAAABABH/2gAIAQMBAT8hP//EABYRAQEBAAAAAAAAAAAAAAAAAAEAIf/aAAgBAgEBPyGf/8QAFhABAQEAAAAAAAAAAAAAAAAAARAR/9oACAEBAAE/IZf/2Q==";

    private final Context appContext;
    private final OkHttpClient httpClient;
    private final RelayFrameQueue frameQueue;
    private final ExecutorService flushExecutor;
    private final ScheduledExecutorService heartbeatExecutor;
    private final Object flushLock = new Object();
    private boolean isFlushing = false;
    @Nullable private ConnectivityManager connectivityManager;
    @Nullable private ConnectivityManager.NetworkCallback networkCallback;

    public static final class Registration {
        @NonNull public final String nodeId;
        @NonNull public final String token;
        @Nullable public final String ingestUrl;

        Registration(@NonNull String nodeId, @NonNull String token, @Nullable String ingestUrl) {
            this.nodeId = nodeId;
            this.token = token;
            this.ingestUrl = ingestUrl;
        }
    }

    public static final class FrameUploadResult {
        @Nullable public final String id;
        @Nullable public final String cell;
        @Nullable public final String previewUrl;

        FrameUploadResult(@Nullable String id, @Nullable String cell, @Nullable String previewUrl) {
            this.id = id;
            this.cell = cell;
            this.previewUrl = previewUrl;
        }
    }

    public static final class RelayException extends Exception {
        public final int statusCode;

        RelayException(int statusCode, @NonNull String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }

    public RelayClient(@NonNull Context context) {
        this(context.getApplicationContext(), ClawPhonesAPI.getOkHttpClient());
    }

    RelayClient(@NonNull Context context, @NonNull OkHttpClient okHttpClient) {
        this.appContext = context.getApplicationContext();
        this.httpClient = okHttpClient;
        this.frameQueue = new RelayFrameQueue(this.appContext);
        this.flushExecutor = Executors.newSingleThreadExecutor();
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        registerNetworkCallback();
        heartbeatExecutor.scheduleAtFixedRate(
            this::heartbeatSafe,
            HEARTBEAT_INTERVAL_MINUTES,
            HEARTBEAT_INTERVAL_MINUTES,
            TimeUnit.MINUTES
        );
    }

    public void shutdown() {
        unregisterNetworkCallback();
        flushExecutor.shutdownNow();
        heartbeatExecutor.shutdownNow();
    }

    @NonNull
    public String getRelayURL() {
        String stored = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREF_RELAY_URL, DEFAULT_RELAY_URL);
        String normalized = normalizeRelayURL(stored);
        return normalized == null ? DEFAULT_RELAY_URL : normalized;
    }

    public void setRelayURL(@NonNull String relayURL) {
        String normalized = normalizeRelayURL(relayURL);
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_RELAY_URL, normalized == null ? DEFAULT_RELAY_URL : normalized)
            .apply();
    }

    @Nullable
    public String getNodeId() {
        SharedPreferences prefs = getSecurePrefs();
        return prefs == null ? null : trimToNull(prefs.getString(PREF_NODE_ID, null));
    }

    public Registration register() throws IOException, RelayException, JSONException {
        return register("clawvision-android", Arrays.asList("frame", "gps"));
    }

    public Registration register(@NonNull String name, @NonNull List<String> capabilities)
        throws IOException, RelayException, JSONException {
        JSONObject body = new JSONObject();
        body.put("name", name);
        body.put("capabilities", new JSONArray(capabilities));

        JSONObject response = executeJson(postRequest("/v1/nodes/register", body, null));
        String nodeId = trimToNull(response.optString("node_id", null));
        String token = trimToNull(response.optString("token", null));
        if (nodeId == null || token == null) {
            throw new RelayException(500, "register missing node_id/token");
        }

        saveCredentials(nodeId, token);
        flushPendingFramesAsync();
        return new Registration(nodeId, token, trimToNull(response.optString("ingest_url", null)));
    }

    public FrameUploadResult uploadFrame(@NonNull byte[] jpeg, double lat, double lon, @Nullable Double heading)
        throws IOException, RelayException, JSONException {
        if (jpeg.length == 0) {
            throw new IllegalArgumentException("jpeg must not be empty");
        }

        Credentials credentials = requireCredentials();
        long ts = nowEpochSeconds();
        JSONObject payload = buildFramePayload(
            credentials.nodeId,
            ts,
            lat,
            lon,
            heading,
            Base64.encodeToString(jpeg, Base64.NO_WRAP)
        );
        saveLastLocation(lat, lon, heading, ts);

        try {
            FrameUploadResult result = postFrame(payload, credentials.token);
            flushPendingFramesAsync();
            return result;
        } catch (IOException | RelayException | JSONException e) {
            frameQueue.enqueue(payload);
            throw e;
        }
    }

    public void heartbeat() throws IOException, RelayException, JSONException {
        Credentials credentials = requireCredentials();
        JSONObject body = new JSONObject();
        body.put("node_id", credentials.nodeId);
        body.put("ts", nowEpochSeconds());

        try {
            executeJson(postRequest("/v1/nodes/heartbeat", body, credentials.token));
            return;
        } catch (RelayException e) {
            if (e.statusCode != 404 && e.statusCode != 405) {
                throw e;
            }
        }

        sendFallbackFrameHeartbeat(credentials);
    }

    public void flushPendingFrames() {
        synchronized (flushLock) {
            if (isFlushing) return;
            isFlushing = true;
        }

        try {
            Credentials credentials;
            try {
                credentials = requireCredentials();
            } catch (RelayException e) {
                return;
            }

            while (true) {
                RelayFrameQueue.PendingFrame pending = frameQueue.nextPending();
                if (pending == null) {
                    return;
                }

                frameQueue.markSending(pending.id);
                try {
                    postFrame(pending.payload, credentials.token);
                    frameQueue.remove(pending.id);
                } catch (IOException | RelayException | JSONException e) {
                    int retries = frameQueue.incrementRetryCount(pending.id);
                    if (retries >= MAX_QUEUE_RETRY) {
                        frameQueue.markFailed(pending.id);
                    } else {
                        frameQueue.markPending(pending.id);
                    }
                    return;
                }
            }
        } finally {
            synchronized (flushLock) {
                isFlushing = false;
            }
        }
    }

    public void flushPendingFramesAsync() {
        flushExecutor.execute(this::flushPendingFrames);
    }

    private void heartbeatSafe() {
        try {
            heartbeat();
        } catch (Exception e) {
            Logger.logDebug(LOG_TAG, "heartbeat skipped: " + e.getMessage());
        }
    }

    private void sendFallbackFrameHeartbeat(@NonNull Credentials credentials) throws IOException, RelayException, JSONException {
        LastLocation location = readLastLocation();
        if (location == null) return;

        JSONObject payload = buildFramePayload(
            credentials.nodeId,
            nowEpochSeconds(),
            location.lat,
            location.lon,
            location.heading,
            HEARTBEAT_JPEG_BASE64
        );

        try {
            postFrame(payload, credentials.token);
        } catch (IOException | RelayException | JSONException e) {
            frameQueue.enqueue(payload);
        }
    }

    private FrameUploadResult postFrame(@NonNull JSONObject payload, @NonNull String token)
        throws IOException, RelayException, JSONException {
        JSONObject response = executeJson(postRequest("/v1/events/frame", payload, token));
        return new FrameUploadResult(
            trimToNull(response.optString("id", null)),
            trimToNull(response.optString("cell", null)),
            trimToNull(response.optString("preview_url", null))
        );
    }

    @NonNull
    private Request postRequest(@NonNull String path, @NonNull JSONObject body, @Nullable String token)
        throws RelayException {
        HttpUrl url = endpoint(path);
        Request.Builder builder = new Request.Builder()
            .url(url)
            .post(RequestBody.create(JSON_MEDIA_TYPE, body.toString()))
            .addHeader("Accept", "application/json")
            .addHeader("Content-Type", "application/json");
        if (!TextUtils.isEmpty(token)) {
            builder.addHeader("Authorization", "Bearer " + token);
        }
        return builder.build();
    }

    @NonNull
    private JSONObject executeJson(@NonNull Request request) throws IOException, RelayException, JSONException {
        Response response = null;
        try {
            response = httpClient.newCall(request).execute();
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new RelayException(response.code(), body.isEmpty() ? response.message() : body);
            }
            if (body.trim().isEmpty()) {
                return new JSONObject();
            }
            return new JSONObject(body);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    @NonNull
    private Credentials requireCredentials() throws RelayException {
        SharedPreferences prefs = getSecurePrefs();
        if (prefs == null) {
            throw new RelayException(500, "secure storage unavailable");
        }

        String nodeId = trimToNull(prefs.getString(PREF_NODE_ID, null));
        String token = trimToNull(prefs.getString(PREF_TOKEN, null));
        if (nodeId == null || token == null) {
            throw new RelayException(401, "missing relay credentials");
        }
        return new Credentials(nodeId, token);
    }

    @NonNull
    private HttpUrl endpoint(@NonNull String path) throws RelayException {
        String base = normalizeRelayURL(getRelayURL());
        if (base == null) {
            throw new RelayException(400, "invalid relay URL");
        }
        HttpUrl url = HttpUrl.parse(base + path);
        if (url == null) {
            throw new RelayException(400, "invalid endpoint URL");
        }
        return url;
    }

    @Nullable
    private String normalizeRelayURL(@Nullable String raw) {
        String value = trimToNull(raw);
        if (value == null) return null;

        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }

        HttpUrl url = HttpUrl.parse(value);
        if (url == null) return null;
        String scheme = url.scheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return null;
        if (TextUtils.isEmpty(url.host())) return null;
        return value;
    }

    private void saveCredentials(@NonNull String nodeId, @NonNull String token) {
        SharedPreferences prefs = getSecurePrefs();
        if (prefs == null) return;
        prefs.edit()
            .putString(PREF_NODE_ID, nodeId)
            .putString(PREF_TOKEN, token)
            .apply();
    }

    private void saveLastLocation(double lat, double lon, @Nullable Double heading, long ts) {
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit()
            .putLong(PREF_LAST_LAT, Double.doubleToRawLongBits(lat))
            .putLong(PREF_LAST_LON, Double.doubleToRawLongBits(lon))
            .putLong(PREF_LAST_TS, ts);
        if (heading == null) {
            editor.putBoolean(PREF_HAS_HEADING, false);
            editor.remove(PREF_LAST_HEADING);
        } else {
            editor.putBoolean(PREF_HAS_HEADING, true);
            editor.putLong(PREF_LAST_HEADING, Double.doubleToRawLongBits(heading));
        }
        editor.apply();
    }

    @Nullable
    private LastLocation readLastLocation() {
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.contains(PREF_LAST_LAT) || !prefs.contains(PREF_LAST_LON)) return null;
        double lat = Double.longBitsToDouble(prefs.getLong(PREF_LAST_LAT, 0L));
        double lon = Double.longBitsToDouble(prefs.getLong(PREF_LAST_LON, 0L));
        Double heading = null;
        if (prefs.getBoolean(PREF_HAS_HEADING, false) && prefs.contains(PREF_LAST_HEADING)) {
            heading = Double.longBitsToDouble(prefs.getLong(PREF_LAST_HEADING, 0L));
        }
        long ts = prefs.getLong(PREF_LAST_TS, 0L);
        return new LastLocation(lat, lon, heading, ts);
    }

    private long nowEpochSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    @Nullable
    private SharedPreferences getSecurePrefs() {
        try {
            SharedPreferences secure = EncryptedSharedPreferences.create(
                SECURE_PREFS,
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                appContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            migrateLegacySecurePrefs(secure);
            return secure;
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "EncryptedSharedPreferences unavailable, fallback to plain prefs: " + e.getMessage());
            return appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
    }

    private void migrateLegacySecurePrefs(@NonNull SharedPreferences securePrefs) {
        if (securePrefs.getBoolean(PREF_SECURE_MIGRATED, false)) return;

        SharedPreferences legacy = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String nodeId = trimToNull(legacy.getString(PREF_NODE_ID, null));
        String token = trimToNull(legacy.getString(PREF_TOKEN, null));

        SharedPreferences.Editor secureEditor = securePrefs.edit();
        if (nodeId != null) secureEditor.putString(PREF_NODE_ID, nodeId);
        if (token != null) secureEditor.putString(PREF_TOKEN, token);
        secureEditor.putBoolean(PREF_SECURE_MIGRATED, true).apply();

        legacy.edit()
            .remove(PREF_NODE_ID)
            .remove(PREF_TOKEN)
            .apply();
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void registerNetworkCallback() {
        connectivityManager = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    flushPendingFramesAsync();
                }
            };

            try {
                connectivityManager.registerDefaultNetworkCallback(networkCallback);
            } catch (Exception e) {
                networkCallback = null;
                Logger.logWarn(LOG_TAG, "Failed to register network callback: " + e.getMessage());
            }
        }
    }

    private void unregisterNetworkCallback() {
        if (connectivityManager == null || networkCallback == null) return;
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        } catch (Exception ignored) {
        }
        networkCallback = null;
    }

    @NonNull
    private JSONObject buildFramePayload(
        @NonNull String nodeId,
        long ts,
        double lat,
        double lon,
        @Nullable Double heading,
        @NonNull String jpegBase64
    ) throws JSONException {
        JSONObject body = new JSONObject();
        body.put("node_id", nodeId);
        body.put("ts", ts);
        body.put("lat", lat);
        body.put("lon", lon);
        if (heading != null) {
            body.put("heading", heading);
        } else {
            body.put("heading", JSONObject.NULL);
        }
        body.put("jpeg_base64", jpegBase64);
        return body;
    }

    private static final class Credentials {
        @NonNull final String nodeId;
        @NonNull final String token;

        Credentials(@NonNull String nodeId, @NonNull String token) {
            this.nodeId = nodeId;
            this.token = token;
        }
    }

    private static final class LastLocation {
        final double lat;
        final double lon;
        @Nullable final Double heading;
        final long ts;

        LastLocation(double lat, double lon, @Nullable Double heading, long ts) {
            this.lat = lat;
            this.lon = lon;
            this.heading = heading;
            this.ts = ts;
        }
    }

    private static final class RelayFrameQueue {
        static final String STATUS_PENDING = "pending";
        static final String STATUS_SENDING = "sending";
        static final String STATUS_FAILED = "failed";

        private static final String DB_NAME = "clawphones_relay.db";
        private static final int DB_VERSION = 1;
        private static final String TABLE_PENDING = "pending_relay_frames";
        private static final String COL_ID = "id";
        private static final String COL_PAYLOAD = "payload_json";
        private static final String COL_CREATED_AT = "created_at";
        private static final String COL_STATUS = "status";
        private static final String COL_RETRY_COUNT = "retry_count";

        private final SQLiteOpenHelper helper;

        RelayFrameQueue(@NonNull Context context) {
            helper = new SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
                @Override
                public void onCreate(SQLiteDatabase db) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_PENDING + " ("
                        + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + COL_PAYLOAD + " TEXT NOT NULL, "
                        + COL_CREATED_AT + " INTEGER NOT NULL, "
                        + COL_STATUS + " TEXT NOT NULL, "
                        + COL_RETRY_COUNT + " INTEGER NOT NULL DEFAULT 0"
                        + ")");
                    db.execSQL("CREATE INDEX IF NOT EXISTS idx_relay_pending_status_created "
                        + "ON " + TABLE_PENDING + "(" + COL_STATUS + ", " + COL_CREATED_AT + ")");
                }

                @Override
                public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
                    if (oldVersion < 1) onCreate(db);
                }
            };
        }

        synchronized void enqueue(@NonNull JSONObject payload) {
            SQLiteDatabase db = helper.getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put(COL_PAYLOAD, payload.toString());
            values.put(COL_CREATED_AT, System.currentTimeMillis());
            values.put(COL_STATUS, STATUS_PENDING);
            values.put(COL_RETRY_COUNT, 0);
            db.insertOrThrow(TABLE_PENDING, null, values);
        }

        @Nullable
        synchronized PendingFrame nextPending() {
            SQLiteDatabase db = helper.getReadableDatabase();
            Cursor c = null;
            try {
                c = db.query(
                    TABLE_PENDING,
                    new String[]{COL_ID, COL_PAYLOAD, COL_CREATED_AT, COL_STATUS, COL_RETRY_COUNT},
                    COL_STATUS + " IN (?, ?)",
                    new String[]{STATUS_PENDING, STATUS_SENDING},
                    null,
                    null,
                    COL_CREATED_AT + " ASC LIMIT 1"
                );
                if (!c.moveToFirst()) return null;
                return toPendingFrame(c);
            } finally {
                if (c != null) c.close();
            }
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

        synchronized int incrementRetryCount(long id) {
            SQLiteDatabase db = helper.getWritableDatabase();
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
                if (c.moveToFirst()) return c.getInt(0);
                return 0;
            } finally {
                if (c != null) c.close();
            }
        }

        synchronized void remove(long id) {
            SQLiteDatabase db = helper.getWritableDatabase();
            db.delete(TABLE_PENDING, COL_ID + " = ?", new String[]{String.valueOf(id)});
        }

        private void updateStatus(long id, @NonNull String status) {
            SQLiteDatabase db = helper.getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put(COL_STATUS, status);
            db.update(TABLE_PENDING, values, COL_ID + " = ?", new String[]{String.valueOf(id)});
        }

        @Nullable
        private PendingFrame toPendingFrame(@NonNull Cursor c) {
            long id = c.getLong(0);
            String payloadRaw = c.getString(1);
            long createdAt = c.getLong(2);
            String status = c.getString(3);
            int retryCount = c.getInt(4);
            try {
                JSONObject payload = new JSONObject(payloadRaw);
                return new PendingFrame(id, payload, createdAt, status, retryCount);
            } catch (JSONException e) {
                remove(id);
                return null;
            }
        }

        static final class PendingFrame {
            final long id;
            @NonNull final JSONObject payload;
            final long createdAt;
            @NonNull final String status;
            final int retryCount;

            PendingFrame(long id, @NonNull JSONObject payload, long createdAt, @NonNull String status, int retryCount) {
                this.id = id;
                this.payload = payload;
                this.createdAt = createdAt;
                this.status = status;
                this.retryCount = retryCount;
            }
        }
    }
}
