package ai.clawphones.agent.chat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.termux.R;

import org.json.JSONObject;

import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Push Notification Service - Firebase Cloud Messaging (FCM) integration.
 *
 * Features:
 * - Extends FirebaseMessagingService for FCM message handling
 * - Token registration via POST /v1/push/register
 * - Four notification channels with different importance levels
 * - createNotificationChannels() for channel setup
 * - Handles different notification types: job updates, alerts, messages, system
 */
public class PushNotificationService extends FirebaseMessagingService {

    private static final String LOG_TAG = "PushNotificationService";

    // Notification Channels
    private static final String CHANNEL_JOB_UPDATES = "job_updates";
    private static final String CHANNEL_ALERTS = "alerts";
    private static final String CHANNEL_MESSAGES = "messages";
    private static final String CHANNEL_SYSTEM = "system";

    // Channel IDs for different notification types
    private static final String TYPE_JOB_UPDATE = "job_update";
    private static final String TYPE_ALERT = "alert";
    private static final String TYPE_MESSAGE = "message";
    private static final String TYPE_SYSTEM = "system";

    // Notification IDs
    private static final int NOTIFICATION_ID_JOB = 2001;
    private static final int NOTIFICATION_ID_ALERT = 2002;
    private static final int NOTIFICATION_ID_MESSAGE = 2003;
    private static final int NOTIFICATION_ID_SYSTEM = 2004;

    // API Configuration
    private static final String PREFS_NAME = "push_notification_prefs";
    private static final String PREF_API_URL = "api_url";
    private static final String PREF_DEVICE_ID = "device_id";
    private static final String PREF_REGISTERED = "token_registered";
    private static final String DEFAULT_API_URL = "https://api.clawphones.dev";

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient = new OkHttpClient();

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels();
    }

    /**
     * Called when a new FCM token is generated for the device.
     * Registers the token with the backend via POST /v1/push/register.
     *
     * @param token The new FCM registration token
     */
    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        Log.d(LOG_TAG, "New FCM token received: " + token.substring(0, 20) + "...");

        registerTokenWithServer(token);
    }

    /**
     * Called when a new message is received from FCM.
     * Handles different notification types and displays appropriate notifications.
     *
     * @param remoteMessage The remote message from FCM
     */
    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        Log.d(LOG_TAG, "Message received from: " + remoteMessage.getFrom());

        // Check if message contains data payload
        if (remoteMessage.getData().size() > 0) {
            Map<String, String> data = remoteMessage.getData();
            handleDataMessage(data);
        }

        // Check if message contains notification payload
        if (remoteMessage.getNotification() != null) {
            RemoteMessage.Notification notification = remoteMessage.getNotification();
            handleNotificationMessage(notification);
        }
    }

    /**
     * Registers the FCM token with the backend server.
     *
     * @param token The FCM registration token
     */
    private void registerTokenWithServer(@NonNull String token) {
        String apiUrl = getApiUrl();
        String deviceId = getDeviceId();

        if (apiUrl == null || deviceId == null) {
            Log.w(LOG_TAG, "Cannot register token: API URL or Device ID missing");
            return;
        }

        try {
            JSONObject jsonBody = new JSONObject();
            jsonBody.put("device_id", deviceId);
            jsonBody.put("token", token);
            jsonBody.put("platform", "android");

            RequestBody body = RequestBody.create(jsonBody.toString(), JSON_MEDIA_TYPE);
            String url = apiUrl + "/v1/push/register";

            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull Exception e) {
                    Log.e(LOG_TAG, "Failed to register token with server: " + e.getMessage());
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    try {
                        if (response.isSuccessful()) {
                            Log.d(LOG_TAG, "Token registered successfully");
                            setTokenRegistered(true);
                        } else {
                            String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                            Log.e(LOG_TAG, "Failed to register token: " + response.code() + " - " + errorBody);
                        }
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "Error handling register response: " + e.getMessage());
                    } finally {
                        response.close();
                    }
                }
            });
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error building registration request: " + e.getMessage());
        }
    }

    /**
     * Handles messages with data payload.
     * Determines the notification type and creates appropriate notification.
     *
     * @param data The message data payload
     */
    private void handleDataMessage(@NonNull Map<String, String> data) {
        String type = data.get("type");
        String title = data.get("title");
        String body = data.get("body");
        String channelId = CHANNEL_MESSAGES;
        int notificationId = NOTIFICATION_ID_MESSAGE;

        if (type == null) {
            type = TYPE_MESSAGE;
        }

        // Determine notification channel and ID based on type
        switch (type) {
            case TYPE_JOB_UPDATE:
                channelId = CHANNEL_JOB_UPDATES;
                notificationId = NOTIFICATION_ID_JOB;
                break;
            case TYPE_ALERT:
                channelId = CHANNEL_ALERTS;
                notificationId = NOTIFICATION_ID_ALERT;
                break;
            case TYPE_SYSTEM:
                channelId = CHANNEL_SYSTEM;
                notificationId = NOTIFICATION_ID_SYSTEM;
                break;
            case TYPE_MESSAGE:
            default:
                channelId = CHANNEL_MESSAGES;
                notificationId = NOTIFICATION_ID_MESSAGE;
                break;
        }

        // Set default title/body if not provided
        if (title == null || title.isEmpty()) {
            title = getDefaultTitle(type);
        }
        if (body == null || body.isEmpty()) {
            body = getDefaultBody(type);
        }

        createAndShowNotification(channelId, notificationId, title, body, data);
    }

    /**
     * Handles messages with notification payload.
     *
     * @param notification The notification payload from FCM
     */
    private void handleNotificationMessage(@NonNull RemoteMessage.Notification notification) {
        String title = notification.getTitle();
        String body = notification.getBody();
        String channelId = CHANNEL_MESSAGES;
        int notificationId = NOTIFICATION_ID_MESSAGE;

        // Determine channel based on notification tag (if present)
        String tag = notification.getTag();
        if (tag != null) {
            switch (tag) {
                case TYPE_JOB_UPDATE:
                    channelId = CHANNEL_JOB_UPDATES;
                    notificationId = NOTIFICATION_ID_JOB;
                    break;
                case TYPE_ALERT:
                    channelId = CHANNEL_ALERTS;
                    notificationId = NOTIFICATION_ID_ALERT;
                    break;
                case TYPE_SYSTEM:
                    channelId = CHANNEL_SYSTEM;
                    notificationId = NOTIFICATION_ID_SYSTEM;
                    break;
            }
        }

        if (title == null) {
            title = getString(R.string.app_name);
        }
        if (body == null) {
            body = "";
        }

        createAndShowNotification(channelId, notificationId, title, body, null);
    }

    /**
     * Creates and displays a notification.
     *
     * @param channelId The notification channel ID
     * @param notificationId The notification ID
     * @param title The notification title
     * @param body The notification body
     * @param extras Optional extra data to include in the intent
     */
    private void createAndShowNotification(@NonNull String channelId, int notificationId,
                                           @NonNull String title, @NonNull String body,
                                           @Nullable Map<String, String> extras) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setPriority(getNotificationPriority(channelId));

        // Create intent to open chat activity on tap
        Intent intent = new Intent(this, ChatActivity.class);
        if (extras != null) {
            for (Map.Entry<String, String> entry : extras.entrySet()) {
                intent.putExtra(entry.getKey(), entry.getValue());
            }
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        builder.setContentIntent(pendingIntent);

        // Show the notification
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            Notification notification = builder.build();
            manager.notify(notificationId, notification);
        }
    }

    /**
     * Creates all notification channels.
     * Four channels with different importance levels:
     * - Job Updates: HIGH importance
     * - Alerts: URGENT importance (heads-up)
     * - Messages: DEFAULT importance
     * - System: LOW importance
     */
    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }

        // Job Updates Channel - HIGH importance
        NotificationChannel jobChannel = new NotificationChannel(
                CHANNEL_JOB_UPDATES,
                "Job Updates",
                NotificationManager.IMPORTANCE_HIGH
        );
        jobChannel.setDescription("Notifications about compute job status changes");
        jobChannel.enableVibration(true);
        jobChannel.enableLights(true);
        manager.createNotificationChannel(jobChannel);

        // Alerts Channel - URGENT importance (heads-up)
        NotificationChannel alertChannel = new NotificationChannel(
                CHANNEL_ALERTS,
                "Alerts",
                NotificationManager.IMPORTANCE_HIGH
        );
        alertChannel.setDescription("Important alerts requiring immediate attention");
        alertChannel.enableVibration(true);
        alertChannel.enableLights(true);
        alertChannel.setSound(
                android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
                null
        );
        manager.createNotificationChannel(alertChannel);

        // Messages Channel - DEFAULT importance
        NotificationChannel messageChannel = new NotificationChannel(
                CHANNEL_MESSAGES,
                "Messages",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        messageChannel.setDescription("Chat and message notifications");
        messageChannel.enableVibration(true);
        manager.createNotificationChannel(messageChannel);

        // System Channel - LOW importance
        NotificationChannel systemChannel = new NotificationChannel(
                CHANNEL_SYSTEM,
                "System",
                NotificationManager.IMPORTANCE_LOW
        );
        systemChannel.setDescription("System notifications and updates");
        systemChannel.enableVibration(false);
        systemChannel.enableLights(false);
        manager.createNotificationChannel(systemChannel);

        Log.d(LOG_TAG, "Notification channels created");
    }

    /**
     * Returns the appropriate notification priority for a channel.
     */
    private int getNotificationPriority(@NonNull String channelId) {
        switch (channelId) {
            case CHANNEL_ALERTS:
            case CHANNEL_JOB_UPDATES:
                return NotificationCompat.PRIORITY_HIGH;
            case CHANNEL_MESSAGES:
                return NotificationCompat.PRIORITY_DEFAULT;
            case CHANNEL_SYSTEM:
                return NotificationCompat.PRIORITY_LOW;
            default:
                return NotificationCompat.PRIORITY_DEFAULT;
        }
    }

    /**
     * Returns default title for a notification type.
     */
    private String getDefaultTitle(@NonNull String type) {
        switch (type) {
            case TYPE_JOB_UPDATE:
                return "Job Update";
            case TYPE_ALERT:
                return "Alert";
            case TYPE_SYSTEM:
                return "System";
            default:
                return "New Message";
        }
    }

    /**
     * Returns default body for a notification type.
     */
    private String getDefaultBody(@NonNull String type) {
        switch (type) {
            case TYPE_JOB_UPDATE:
                return "A job status has been updated.";
            case TYPE_ALERT:
                return "An alert requires your attention.";
            case TYPE_SYSTEM:
                return "System notification.";
            default:
                return "You have a new message.";
        }
    }

    // SharedPreferences helpers

    @Nullable
    private String getApiUrl() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(PREF_API_URL, DEFAULT_API_URL);
    }

    @Nullable
    private String getDeviceId() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(PREF_DEVICE_ID, null);
    }

    private boolean isTokenRegistered() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(PREF_REGISTERED, false);
    }

    private void setTokenRegistered(boolean registered) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_REGISTERED, registered)
                .apply();
    }
}
