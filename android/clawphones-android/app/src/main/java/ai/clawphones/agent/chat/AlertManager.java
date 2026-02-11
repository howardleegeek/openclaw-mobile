package ai.clawphones.agent.chat;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Smart local alerts for ClawVision.
 *
 * Rules:
 * - Debounce same type within 30 seconds
 * - Quiet hours support
 * - Importance filter: person > vehicle > animal > package
 * - Keep latest 100 history records in SharedPreferences (JSON array)
 */
public final class AlertManager {

    private static final String LOG_TAG = "ClawVisionAlertManager";

    public static final String CHANNEL_ID = "clawvision_alerts";
    private static final String CHANNEL_NAME = "ClawVision Alerts";

    private static final String PREFS_NAME = "clawvision_alert_manager";
    private static final String PREF_HISTORY_JSON = "alert_history_json";
    private static final String PREF_QUIET_START_MIN = "quiet_start_min";
    private static final String PREF_QUIET_END_MIN = "quiet_end_min";
    private static final String PREF_MIN_IMPORTANCE_RANK = "min_importance_rank";
    private static final String PREF_MIN_CONFIDENCE = "min_confidence";
    private static final String PREF_LAST_SENT_PREFIX = "last_sent_";

    private static final int MAX_HISTORY = 100;
    private static final long DEBOUNCE_MS = 30_000L;

    private static final String TYPE_PERSON = "person";
    private static final String TYPE_VEHICLE = "vehicle";
    private static final String TYPE_ANIMAL = "animal";
    private static final String TYPE_PACKAGE = "package";

    private final Context appContext;
    private final SharedPreferences prefs;
    private final NotificationManager notificationManager;
    private final Map<String, Long> lastSentCache = new HashMap<>();

    public static final class AlertEvent {
        public final String type;
        public final float confidence;
        public final long timestamp;
        @Nullable public final byte[] thumbnail;

        public AlertEvent(@NonNull String type, float confidence, long timestamp, @Nullable byte[] thumbnail) {
            this.type = normalizeType(type);
            this.confidence = confidence;
            this.timestamp = timestamp;
            this.thumbnail = thumbnail;
        }
    }

    public AlertManager(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.notificationManager = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannelIfNeeded();
    }

    // 防抖: 同类事件 30 秒内只推送一次
    // 静默时段: 用户可设置免打扰时间段
    // 重要度过滤: person > vehicle > animal > package
    public void processDetection(@NonNull VisionDetector.Detection detection, @Nullable Bitmap frame) {
        if (detection == null) return;

        String type = normalizeType(detection.type);
        if (TextUtils.isEmpty(type)) return;
        if (!isSupportedType(type)) return;
        if (!passesImportanceFilter(type)) return;
        if (detection.confidence < getMinimumConfidence()) return;

        long now = System.currentTimeMillis();
        if (isInQuietHours(now)) return;
        if (isDebounced(type, now)) return;

        byte[] thumbnail = buildThumbnailJpeg(frame);
        AlertEvent event = new AlertEvent(type, detection.confidence, now, thumbnail);

        appendHistory(event);
        markLastSent(type, now);
        sendLocalNotification(event);
    }

    public void sendLocalNotification(@NonNull AlertEvent event) {
        if (!canPostNotifications()) return;
        createNotificationChannelIfNeeded();

        Intent intent = new Intent(appContext, NodeModeActivity.class);
        intent.putExtra("open_target", "node_mode");
        intent.putExtra("from_alert", true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(
            appContext,
            Math.abs((int) (event.timestamp % Integer.MAX_VALUE)),
            intent,
            pendingIntentFlags
        );

        String contentText = "检测到 " + displayType(event.type) + " " + formatTime(event.timestamp);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle("ClawVision")
            .setContentText(contentText)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_EVENT);

        Bitmap bigPicture = decodeBitmap(event.thumbnail);
        if (bigPicture != null) {
            builder.setStyle(
                new NotificationCompat.BigPictureStyle()
                    .bigPicture(bigPicture)
                    .setSummaryText(contentText)
            );
        } else {
            builder.setStyle(new NotificationCompat.BigTextStyle().bigText(contentText));
        }

        NotificationManagerCompat.from(appContext).notify(buildNotificationId(event), builder.build());
    }

    public void setQuietHours(int startHour, int startMinute, int endHour, int endMinute) {
        int start = clamp(startHour, 0, 23) * 60 + clamp(startMinute, 0, 59);
        int end = clamp(endHour, 0, 23) * 60 + clamp(endMinute, 0, 59);
        prefs.edit()
            .putInt(PREF_QUIET_START_MIN, start)
            .putInt(PREF_QUIET_END_MIN, end)
            .apply();
    }

    public void clearQuietHours() {
        prefs.edit()
            .remove(PREF_QUIET_START_MIN)
            .remove(PREF_QUIET_END_MIN)
            .apply();
    }

    public void setMinimumAlertType(@NonNull String type) {
        String normalized = normalizeType(type);
        int rank = importanceRank(normalized);
        if (rank <= 0) return;
        prefs.edit().putInt(PREF_MIN_IMPORTANCE_RANK, rank).apply();
    }

    public void setMinimumConfidence(float confidence) {
        prefs.edit().putFloat(PREF_MIN_CONFIDENCE, clamp(confidence, 0f, 1f)).apply();
    }

    @NonNull
    public List<AlertEvent> getHistory() {
        JSONArray array = loadHistoryArray();
        List<AlertEvent> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String type = normalizeType(item.optString("type", ""));
            if (TextUtils.isEmpty(type)) continue;

            float confidence = (float) item.optDouble("confidence", 0f);
            long timestamp = item.optLong("timestamp", 0L);
            byte[] thumbnail = decodeBase64(item.optString("thumbnail_base64", ""));
            out.add(new AlertEvent(type, confidence, timestamp, thumbnail));
        }
        return out;
    }

    private void createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        if (notificationManager == null) return;

        NotificationChannel existing = notificationManager.getNotificationChannel(CHANNEL_ID);
        if (existing != null) return;

        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("Meaningful motion and object alerts from ClawVision");
        notificationManager.createNotificationChannel(channel);
    }

    private boolean canPostNotifications() {
        if (Build.VERSION.SDK_INT < 33) return true;
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED;
    }

    private boolean passesImportanceFilter(@NonNull String type) {
        int rank = importanceRank(type);
        if (rank <= 0) return false;
        int minRank = prefs.getInt(PREF_MIN_IMPORTANCE_RANK, importanceRank(TYPE_PACKAGE));
        return rank >= minRank;
    }

    private float getMinimumConfidence() {
        return prefs.getFloat(PREF_MIN_CONFIDENCE, 0.60f);
    }

    private boolean isDebounced(@NonNull String type, long now) {
        Long last = lastSentCache.get(type);
        if (last == null) {
            long persisted = prefs.getLong(PREF_LAST_SENT_PREFIX + type, 0L);
            last = persisted <= 0L ? null : persisted;
            if (last != null) lastSentCache.put(type, last);
        }

        return last != null && now - last < DEBOUNCE_MS;
    }

    private void markLastSent(@NonNull String type, long now) {
        lastSentCache.put(type, now);
        prefs.edit().putLong(PREF_LAST_SENT_PREFIX + type, now).apply();
    }

    private boolean isInQuietHours(long timestamp) {
        if (!prefs.contains(PREF_QUIET_START_MIN) || !prefs.contains(PREF_QUIET_END_MIN)) {
            return false;
        }

        int start = prefs.getInt(PREF_QUIET_START_MIN, -1);
        int end = prefs.getInt(PREF_QUIET_END_MIN, -1);
        if (start < 0 || end < 0) return false;

        Date now = new Date(timestamp);
        @SuppressWarnings("SimpleDateFormat")
        SimpleDateFormat formatter = new SimpleDateFormat("H:mm", Locale.US);
        String[] parts = formatter.format(now).split(":");
        if (parts.length != 2) return false;

        int current = safeInt(parts[0], 0) * 60 + safeInt(parts[1], 0);
        if (start == end) return true;
        if (start < end) return current >= start && current < end;
        return current >= start || current < end;
    }

    private int buildNotificationId(@NonNull AlertEvent event) {
        int seed = (event.type + "_" + event.timestamp).hashCode();
        return seed == Integer.MIN_VALUE ? 1 : Math.abs(seed);
    }

    private void appendHistory(@NonNull AlertEvent event) {
        try {
            JSONArray history = loadHistoryArray();
            JSONArray merged = new JSONArray();
            JSONObject item = new JSONObject();
            item.put("type", event.type);
            item.put("confidence", event.confidence);
            item.put("timestamp", event.timestamp);
            item.put("thumbnail_base64", encodeBase64(event.thumbnail));

            merged.put(item);
            for (int i = 0; i < history.length() && merged.length() < MAX_HISTORY; i++) {
                JSONObject existing = history.optJSONObject(i);
                if (existing != null) {
                    merged.put(existing);
                }
            }

            prefs.edit().putString(PREF_HISTORY_JSON, merged.toString()).apply();
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "append history failed: " + e.getMessage());
        }
    }

    @NonNull
    private JSONArray loadHistoryArray() {
        String raw = prefs.getString(PREF_HISTORY_JSON, "[]");
        try {
            return new JSONArray(raw);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    @Nullable
    private byte[] buildThumbnailJpeg(@Nullable Bitmap frame) {
        if (frame == null) return null;
        try {
            int width = frame.getWidth();
            int height = frame.getHeight();
            if (width <= 0 || height <= 0) return null;

            final int maxSide = 360;
            float scale = Math.min(1f, maxSide / (float) Math.max(width, height));
            int targetWidth = Math.max(1, Math.round(width * scale));
            int targetHeight = Math.max(1, Math.round(height * scale));
            Bitmap scaled = Bitmap.createScaledBitmap(frame, targetWidth, targetHeight, true);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 75, output);
            return output.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static Bitmap decodeBitmap(@Nullable byte[] jpeg) {
        if (jpeg == null || jpeg.length == 0) return null;
        try {
            return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        } catch (Exception e) {
            return null;
        }
    }

    private static String displayType(@NonNull String type) {
        switch (normalizeType(type)) {
            case TYPE_PERSON:
                return "人";
            case TYPE_VEHICLE:
                return "车";
            case TYPE_ANIMAL:
                return "动物";
            case TYPE_PACKAGE:
                return "包裹";
            default:
                return type;
        }
    }

    @NonNull
    private static String formatTime(long timestamp) {
        @SuppressWarnings("SimpleDateFormat")
        SimpleDateFormat formatter = new SimpleDateFormat("HH:mm", Locale.getDefault());
        return formatter.format(new Date(timestamp));
    }

    private static int importanceRank(@Nullable String type) {
        String normalized = normalizeType(type);
        switch (normalized) {
            case TYPE_PERSON:
                return 4;
            case TYPE_VEHICLE:
                return 3;
            case TYPE_ANIMAL:
                return 2;
            case TYPE_PACKAGE:
                return 1;
            default:
                return 0;
        }
    }

    private static boolean isSupportedType(@Nullable String type) {
        return importanceRank(type) > 0;
    }

    @NonNull
    private static String normalizeType(@Nullable String type) {
        return type == null ? "" : type.trim().toLowerCase(Locale.US);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int safeInt(@Nullable String raw, int fallback) {
        if (TextUtils.isEmpty(raw)) return fallback;
        try {
            return Integer.parseInt(raw);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    @NonNull
    private static String encodeBase64(@Nullable byte[] data) {
        if (data == null || data.length == 0) return "";
        return android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
    }

    @Nullable
    private static byte[] decodeBase64(@Nullable String raw) {
        if (TextUtils.isEmpty(raw)) return null;
        try {
            return android.util.Base64.decode(raw, android.util.Base64.NO_WRAP);
        } catch (Exception ignored) {
            return null;
        }
    }
}
