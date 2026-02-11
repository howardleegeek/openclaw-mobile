package ai.clawphones.agent;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.termux.R;
import com.termux.shared.logger.Logger;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ai.clawphones.agent.chat.ClawPhonesAPI;
import ai.clawphones.agent.chat.ErrorHandler;

/**
 * FCM entrypoint: token sync + notification rendering.
 */
public class ClawPhonesMessagingService extends FirebaseMessagingService {

    private static final String LOG_TAG = "ClawPhonesMessaging";
    private static final String CHANNEL_ID = "clawphones_push";
    private static final String CHANNEL_NAME = "ClawPhones Notifications";
    private static final ExecutorService PUSH_EXECUTOR = Executors.newSingleThreadExecutor();

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        submitPushToken(getApplicationContext(), token);
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        String title = null;
        String body = null;

        RemoteMessage.Notification notification = remoteMessage.getNotification();
        if (notification != null) {
            title = notification.getTitle();
            body = notification.getBody();
        }

        Map<String, String> data = remoteMessage.getData();
        if ((title == null || title.trim().isEmpty()) && data != null) {
            title = data.get("title");
        }
        if ((body == null || body.trim().isEmpty()) && data != null) {
            body = data.get("body");
        }

        if (title == null || title.trim().isEmpty()) {
            title = "ClawPhones";
        }
        if (body == null || body.trim().isEmpty()) {
            body = "You have a new system announcement.";
        }

        showNotification(title.trim(), body.trim());
    }

    public static void syncToken(Context context) {
        if (context == null) return;
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (!task.isSuccessful()) {
                Exception e = task.getException();
                Logger.logWarn(LOG_TAG, "Failed to fetch FCM token: " + (e == null ? "unknown" : e.getMessage()));
                return;
            }
            String token = task.getResult();
            submitPushToken(context.getApplicationContext(), token);
        });
    }

    private static void submitPushToken(Context context, String rawToken) {
        if (context == null) return;
        if (rawToken == null) return;
        String token = rawToken.trim();
        if (token.isEmpty()) return;

        ClawPhonesAPI.cachePushToken(context, token);

        PUSH_EXECUTOR.execute(() -> {
            try {
                ClawPhonesAPI.registerPushToken(context, "android", token);
                Logger.logInfo(LOG_TAG, "Push token registered");
            } catch (ClawPhonesAPI.ApiException e) {
                boolean handled = ErrorHandler.handle(context, null, e, null, false);
                if (e.statusCode == 401) {
                    ClawPhonesAPI.clearToken(context);
                } else if (!handled) {
                    Logger.logWarn(LOG_TAG, "Push token registration rejected: " + e.getMessage());
                }
            } catch (IOException | org.json.JSONException e) {
                if (!ErrorHandler.handle(context, null, e, null, false)) {
                    Logger.logWarn(LOG_TAG, "Push token registration failed: " + e.getMessage());
                }
            }
        });
    }

    private void showNotification(String title, String body) {
        createChannelIfNeeded();

        Intent intent = new Intent(this, ClawPhonesLauncherActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, pendingFlags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent);

        NotificationManagerCompat.from(this).notify((int) (System.currentTimeMillis() & 0x7fffffff), builder.build());
    }

    private void createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel existing = manager.getNotificationChannel(CHANNEL_ID);
        if (existing != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("System announcements from ClawPhones");
        manager.createNotificationChannel(channel);
    }
}
