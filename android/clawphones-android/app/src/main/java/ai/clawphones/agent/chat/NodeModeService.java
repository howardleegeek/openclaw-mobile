package ai.clawphones.agent.chat;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.shared.logger.Logger;

import java.util.Locale;
import java.util.Random;

/**
 * Foreground service backing ClawVision Node mode.
 *
 * Current implementation emits simulated frame/event stats and runtime state
 * so UI and control flow are fully wired while camera pipeline is integrated.
 */
public class NodeModeService extends Service {

    private static final String LOG_TAG = "NodeModeService";

    public static final String ACTION_START = "ai.clawphones.agent.chat.action.NODE_MODE_START";
    public static final String ACTION_STOP = "ai.clawphones.agent.chat.action.NODE_MODE_STOP";
    public static final String ACTION_QUERY_STATUS = "ai.clawphones.agent.chat.action.NODE_MODE_QUERY";
    public static final String ACTION_STATUS_BROADCAST = "ai.clawphones.agent.chat.action.NODE_MODE_STATUS";

    public static final String EXTRA_IS_RUNNING = "extra_is_running";
    public static final String EXTRA_CAMERA_STATUS = "extra_camera_status";
    public static final String EXTRA_GPS_STATUS = "extra_gps_status";
    public static final String EXTRA_UPLOAD_STATUS = "extra_upload_status";
    public static final String EXTRA_FRAMES_CAPTURED = "extra_frames_captured";
    public static final String EXTRA_EVENTS_DETECTED = "extra_events_detected";
    public static final String EXTRA_UPTIME_MS = "extra_uptime_ms";

    public static final int STATUS_GRAY = 0;
    public static final int STATUS_GREEN = 1;
    public static final int STATUS_RED = 2;

    public static final String PREFS_NAME = "node_mode_settings";
    public static final String PREF_RELAY_URL = "relay_url";
    public static final String PREF_FRAME_RATE = "frame_rate";
    public static final String PREF_JPEG_QUALITY = "jpeg_quality";
    public static final String PREF_MOTION_SENSITIVITY = "motion_sensitivity";
    public static final String PREF_MIN_BATTERY = "min_battery";
    public static final String PREF_WIFI_ONLY = "wifi_only";

    public static final String DEFAULT_RELAY_URL = "https://relay.oysterlabs.ai/upload";
    public static final String DEFAULT_FRAME_RATE = "1";
    public static final String DEFAULT_JPEG_QUALITY = "Medium";
    public static final String DEFAULT_MOTION_SENSITIVITY = "Medium";
    public static final int DEFAULT_MIN_BATTERY = 20;
    public static final boolean DEFAULT_WIFI_ONLY = true;

    private static final String NOTIFICATION_CHANNEL_ID = "node_mode_service";
    private static final int NOTIFICATION_ID = 2101;
    private static final long TICK_INTERVAL_MS = 1000L;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Random mRandom = new Random();

    private boolean mRunning = false;
    private long mStartedAtElapsedMs = 0L;
    private long mFramesCaptured = 0L;
    private long mEventsDetected = 0L;
    private float mFrameAccumulator = 0f;

    private final Runnable mTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mRunning) return;
            updateSyntheticStats();
            broadcastStatus();
            updateNotification();
            mHandler.postDelayed(this, TICK_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        ensureDefaults();
        ensureNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;

        if (ACTION_STOP.equals(action)) {
            stopNodeMode();
            return START_NOT_STICKY;
        }

        if (ACTION_QUERY_STATUS.equals(action)) {
            broadcastStatus();
            if (!mRunning) {
                stopSelfResult(startId);
            }
            return START_NOT_STICKY;
        }

        startNodeMode();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        mRunning = false;
        broadcastStatus();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startNodeMode() {
        if (mRunning) {
            broadcastStatus();
            return;
        }

        mRunning = true;
        mStartedAtElapsedMs = SystemClock.elapsedRealtime();
        mFramesCaptured = 0L;
        mEventsDetected = 0L;
        mFrameAccumulator = 0f;

        Notification notification = buildNotification(getString(R.string.node_mode_notification_running));
        startForeground(NOTIFICATION_ID, notification);

        mHandler.removeCallbacks(mTickRunnable);
        mHandler.post(mTickRunnable);

        Logger.logInfo(LOG_TAG, "Node mode started");
    }

    private void stopNodeMode() {
        mRunning = false;
        mHandler.removeCallbacks(mTickRunnable);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
        broadcastStatus();
        Logger.logInfo(LOG_TAG, "Node mode stopped");
    }

    private void updateSyntheticStats() {
        SharedPreferences prefs = getSettings();
        float fps = parseFrameRate(prefs.getString(PREF_FRAME_RATE, DEFAULT_FRAME_RATE));
        mFrameAccumulator += fps;

        long newFrames = (long) mFrameAccumulator;
        if (newFrames > 0) {
            mFrameAccumulator -= newFrames;
            mFramesCaptured += newFrames;

            float eventProbability = getEventProbability(
                prefs.getString(PREF_MOTION_SENSITIVITY, DEFAULT_MOTION_SENSITIVITY)
            );
            for (int i = 0; i < newFrames; i++) {
                if (mRandom.nextFloat() < eventProbability) {
                    mEventsDetected++;
                }
            }
        }
    }

    private void updateNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || !mRunning) {
            return;
        }

        String content = getString(
            R.string.node_mode_notification_stats,
            mFramesCaptured,
            mEventsDetected
        );
        manager.notify(NOTIFICATION_ID, buildNotification(content));
    }

    private Notification buildNotification(String contentText) {
        Intent notificationIntent = new Intent(this, NodeModeActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.node_mode_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        }

        return builder.build();
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.node_mode_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.node_mode_notification_channel_desc));
        channel.setShowBadge(false);

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private void ensureDefaults() {
        SharedPreferences prefs = getSettings();
        if (!prefs.contains(PREF_RELAY_URL)) {
            prefs.edit()
                .putString(PREF_RELAY_URL, DEFAULT_RELAY_URL)
                .putString(PREF_FRAME_RATE, DEFAULT_FRAME_RATE)
                .putString(PREF_JPEG_QUALITY, DEFAULT_JPEG_QUALITY)
                .putString(PREF_MOTION_SENSITIVITY, DEFAULT_MOTION_SENSITIVITY)
                .putInt(PREF_MIN_BATTERY, DEFAULT_MIN_BATTERY)
                .putBoolean(PREF_WIFI_ONLY, DEFAULT_WIFI_ONLY)
                .apply();
        }
    }

    private void broadcastStatus() {
        Intent statusIntent = new Intent(ACTION_STATUS_BROADCAST);
        statusIntent.setPackage(getPackageName());
        statusIntent.putExtra(EXTRA_IS_RUNNING, mRunning);
        statusIntent.putExtra(EXTRA_CAMERA_STATUS, getCameraStatus());
        statusIntent.putExtra(EXTRA_GPS_STATUS, getGpsStatus());
        statusIntent.putExtra(EXTRA_UPLOAD_STATUS, getUploadStatus());
        statusIntent.putExtra(EXTRA_FRAMES_CAPTURED, mFramesCaptured);
        statusIntent.putExtra(EXTRA_EVENTS_DETECTED, mEventsDetected);
        statusIntent.putExtra(EXTRA_UPTIME_MS, getUptimeMs());
        sendBroadcast(statusIntent);
    }

    private int getCameraStatus() {
        if (!mRunning) return STATUS_GRAY;
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
            ? STATUS_GREEN
            : STATUS_RED;
    }

    private int getGpsStatus() {
        if (!mRunning) return STATUS_GRAY;

        boolean hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED;
        boolean hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            == PackageManager.PERMISSION_GRANTED;

        return (hasFine || hasCoarse) ? STATUS_GREEN : STATUS_RED;
    }

    private int getUploadStatus() {
        if (!mRunning) return STATUS_GRAY;

        SharedPreferences prefs = getSettings();
        String relayUrl = prefs.getString(PREF_RELAY_URL, DEFAULT_RELAY_URL);
        boolean wifiOnly = prefs.getBoolean(PREF_WIFI_ONLY, DEFAULT_WIFI_ONLY);
        int minBattery = prefs.getInt(PREF_MIN_BATTERY, DEFAULT_MIN_BATTERY);

        if (TextUtils.isEmpty(relayUrl)) {
            return STATUS_RED;
        }

        if (wifiOnly && !isWifiConnected()) {
            return STATUS_GRAY;
        }

        if (getBatteryPercent() < minBattery) {
            return STATUS_GRAY;
        }

        return STATUS_GREEN;
    }

    private long getUptimeMs() {
        if (!mRunning) return 0L;
        return SystemClock.elapsedRealtime() - mStartedAtElapsedMs;
    }

    private SharedPreferences getSettings() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    }

    private float parseFrameRate(@Nullable String frameRate) {
        if ("0.5".equals(frameRate)) return 0.5f;
        if ("2".equals(frameRate)) return 2f;
        return 1f;
    }

    private float getEventProbability(@Nullable String sensitivity) {
        if (sensitivity == null) return 0.10f;
        String normalized = sensitivity.trim().toLowerCase(Locale.US);
        if ("low".equals(normalized)) return 0.05f;
        if ("high".equals(normalized)) return 0.20f;
        return 0.10f;
    }

    private int getBatteryPercent() {
        Intent batteryIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryIntent == null) return 100;

        int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level < 0 || scale <= 0) return 100;
        return (int) ((level * 100f) / scale);
    }

    private boolean isWifiConnected() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        }

        //noinspection deprecation
        android.net.NetworkInfo info = cm.getActiveNetworkInfo();
        //noinspection deprecation
        return info != null && info.isConnected() && info.getType() == ConnectivityManager.TYPE_WIFI;
    }
}
