package ai.clawphones.agent.analytics;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import ai.clawphones.agent.chat.ClawPhonesAPI;

/**
 * Lightweight analytics manager:
 * - in-memory queue
 * - periodic flush every 30s
 * - manual flush trigger for app background
 */
public final class AnalyticsManager {

    private static final String LOG_TAG = "AnalyticsManager";
    private static final long FLUSH_INTERVAL_MS = 30_000L;

    private static volatile AnalyticsManager sInstance;

    private final Context appContext;
    private final Object queueLock = new Object();
    private final ArrayList<JSONObject> queue = new ArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean flushInProgress = new AtomicBoolean(false);

    private AnalyticsManager(Context context) {
        this.appContext = context.getApplicationContext();
        scheduler.scheduleAtFixedRate(
            this::flushInternal,
            FLUSH_INTERVAL_MS,
            FLUSH_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );
    }

    public static AnalyticsManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (AnalyticsManager.class) {
                if (sInstance == null) {
                    sInstance = new AnalyticsManager(context);
                }
            }
        }
        return sInstance;
    }

    public void track(String eventName) {
        track(eventName, null);
    }

    public void track(String eventName, Map<String, ?> properties) {
        String normalizedEvent = eventName == null ? "" : eventName.trim();
        if (normalizedEvent.isEmpty()) return;

        JSONObject event = new JSONObject();
        try {
            event.put("event", normalizedEvent);
            event.put("timestamp", System.currentTimeMillis() / 1000L);
            event.put("properties", toJsonObject(properties));
        } catch (Exception e) {
            Log.w(LOG_TAG, "Skipping invalid analytics event", e);
            return;
        }

        synchronized (queueLock) {
            queue.add(event);
        }
    }

    public void onAppBackground() {
        flushInternal();
    }

    public void flushNow() {
        flushInternal();
    }

    private void flushInternal() {
        final List<JSONObject> batch;
        synchronized (queueLock) {
            if (queue.isEmpty()) return;
            if (!flushInProgress.compareAndSet(false, true)) return;
            batch = new ArrayList<>(queue);
            queue.clear();
        }

        networkExecutor.execute(() -> {
            boolean ok = sendBatch(batch);
            if (!ok) {
                synchronized (queueLock) {
                    queue.addAll(0, batch);
                }
            }
            flushInProgress.set(false);
        });
    }

    private boolean sendBatch(List<JSONObject> batch) {
        if (batch == null || batch.isEmpty()) return true;

        HttpURLConnection connection = null;
        try {
            URL url = new URL(ClawPhonesAPI.BASE_URL + "/v1/analytics/events");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json");

            String token = ClawPhonesAPI.getToken(appContext);
            if (!TextUtils.isEmpty(token)) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }

            JSONArray payload = new JSONArray();
            for (JSONObject item : batch) {
                payload.put(item);
            }

            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
                out.flush();
            }

            int code = connection.getResponseCode();
            if (code >= 200 && code < 300) {
                return true;
            }

            Log.w(LOG_TAG, "Analytics upload failed with HTTP " + code);
            return false;
        } catch (Exception e) {
            Log.w(LOG_TAG, "Analytics upload failed", e);
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private JSONObject toJsonObject(Map<String, ?> properties) {
        JSONObject obj = new JSONObject();
        if (properties == null || properties.isEmpty()) return obj;

        for (Map.Entry<String, ?> entry : properties.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.trim().isEmpty()) continue;

            Object value = entry.getValue();
            Object wrapped = JSONObject.wrap(value);
            if (wrapped == null && value != null) {
                wrapped = String.valueOf(value);
            }

            try {
                obj.put(key, wrapped == null ? JSONObject.NULL : wrapped);
            } catch (Exception ignored) {
            }
        }
        return obj;
    }
}
