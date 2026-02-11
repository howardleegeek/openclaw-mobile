package ai.clawphones.agent;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.Log;

import com.termux.shared.logger.Logger;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ai.clawphones.agent.chat.ClawPhonesAPI;

/**
 * Lightweight crash reporter: stores crash payloads locally and uploads on next launch.
 */
public final class CrashReporter {

    private static final String LOG_TAG = "CrashReporter";
    private static final String CRASH_DIR_NAME = "crash_logs";
    private static final int MAX_CRASH_FILES = 50;
    private static final int MAX_STACKTRACE_LENGTH = 5000;
    private static final long NON_FATAL_DEDUP_MS = 5L * 60L * 1000L;

    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor();
    private static final Object sFileLock = new Object();
    private static final ConcurrentHashMap<String, Long> sRecentNonFatal = new ConcurrentHashMap<>();

    private static volatile boolean sInitialized = false;
    private static volatile Thread.UncaughtExceptionHandler sDefaultHandler;
    private static volatile String sLastUserAction = "";

    private CrashReporter() {
    }

    public static void init(Context context) {
        if (context == null) return;
        final Context appContext = context.getApplicationContext();

        if (!sInitialized) {
            synchronized (CrashReporter.class) {
                if (!sInitialized) {
                    sDefaultHandler = Thread.getDefaultUncaughtExceptionHandler();
                    Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
                        try {
                            saveCrashToFile(appContext, throwable, true);
                        } catch (Exception e) {
                            Logger.logWarn(LOG_TAG, "Failed to persist fatal crash: " + e.getMessage());
                        }

                        if (sDefaultHandler != null) {
                            sDefaultHandler.uncaughtException(thread, throwable);
                        }
                    });
                    sInitialized = true;
                }
            }
        }

        uploadPendingCrashes(appContext);
    }

    public static void setLastAction(String action) {
        sLastUserAction = action == null ? "" : action.trim();
    }

    public static void reportNonFatal(Context context, Throwable t, String userAction) {
        if (context == null || t == null) return;
        final Context appContext = context.getApplicationContext();
        final String action = userAction == null ? "" : userAction.trim();

        sExecutor.execute(() -> {
            if (!action.isEmpty()) {
                setLastAction(action);
            }

            String stacktrace = fullStacktrace(t);
            if (isRecentDuplicate(stacktrace)) {
                return;
            }

            saveCrashToFile(appContext, t, false);
            uploadPendingCrashesInternal(appContext);
        });
    }

    private static boolean isRecentDuplicate(String stacktrace) {
        long now = System.currentTimeMillis();
        long cutoff = now - NON_FATAL_DEDUP_MS;

        for (String key : sRecentNonFatal.keySet()) {
            Long ts = sRecentNonFatal.get(key);
            if (ts == null || ts < cutoff) {
                sRecentNonFatal.remove(key);
            }
        }

        String hash = Integer.toHexString(stacktrace.hashCode());
        Long lastTs = sRecentNonFatal.get(hash);
        if (lastTs != null && (now - lastTs) < NON_FATAL_DEDUP_MS) {
            return true;
        }

        sRecentNonFatal.put(hash, now);
        return false;
    }

    private static void uploadPendingCrashes(Context context) {
        if (context == null) return;
        final Context appContext = context.getApplicationContext();
        sExecutor.execute(() -> uploadPendingCrashesInternal(appContext));
    }

    private static void uploadPendingCrashesInternal(Context context) {
        File dir = new File(context.getFilesDir(), CRASH_DIR_NAME);
        if (!dir.exists() || !dir.isDirectory()) return;

        String token = ClawPhonesAPI.getToken(context);
        if (token == null || token.trim().isEmpty()) return;

        File[] files = dir.listFiles((d, name) -> name != null && name.endsWith(".json"));
        if (files == null || files.length == 0) return;

        Arrays.sort(files, Comparator.comparingLong(File::lastModified));

        for (File file : files) {
            try {
                String json = readFile(file);
                ClawPhonesAPI.postCrashReport(token, json);
                if (!file.delete()) {
                    Logger.logWarn(LOG_TAG, "Uploaded crash but failed to delete file: " + file.getName());
                }
            } catch (Exception e) {
                Logger.logWarn(LOG_TAG, "Crash upload failed: " + e.getMessage());
            }
        }

        synchronized (sFileLock) {
            trimOldCrashFiles(dir);
        }
    }

    private static void saveCrashToFile(Context context, Throwable throwable, boolean fatal) {
        if (context == null || throwable == null) return;

        synchronized (sFileLock) {
            try {
                File dir = new File(context.getFilesDir(), CRASH_DIR_NAME);
                if (!dir.exists() && !dir.mkdirs()) {
                    Logger.logWarn(LOG_TAG, "Unable to create crash log dir");
                    return;
                }

                long nowMs = System.currentTimeMillis();
                long nowSec = nowMs / 1000L;

                JSONObject payload = new JSONObject();
                payload.put("platform", "android");
                payload.put("app_version", appVersion(context));
                payload.put("device_model", safe(Build.MODEL));
                payload.put("os_version", "Android " + safe(Build.VERSION.RELEASE));
                payload.put("stacktrace", truncate(fullStacktrace(throwable), MAX_STACKTRACE_LENGTH));
                payload.put("user_action", sLastUserAction);
                payload.put("fatal", fatal);
                payload.put("timestamp", nowSec);

                File out = new File(dir, nowMs + ".json");
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                }

                trimOldCrashFiles(dir);
            } catch (Exception e) {
                Logger.logWarn(LOG_TAG, "Failed to save crash: " + e.getMessage());
            }
        }
    }

    private static void trimOldCrashFiles(File dir) {
        if (dir == null || !dir.exists()) return;

        File[] files = dir.listFiles((d, name) -> name != null && name.endsWith(".json"));
        if (files == null || files.length <= MAX_CRASH_FILES) return;

        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        int removeCount = files.length - MAX_CRASH_FILES;
        for (int i = 0; i < removeCount; i++) {
            if (!files[i].delete()) {
                Logger.logWarn(LOG_TAG, "Failed deleting old crash file: " + files[i].getName());
            }
        }
    }

    private static String readFile(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static String appVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            String versionName = info == null ? null : info.versionName;
            return versionName == null ? "unknown" : versionName;
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private static String fullStacktrace(Throwable t) {
        String stack = Log.getStackTraceString(t);
        if (stack == null || stack.trim().isEmpty()) {
            return String.valueOf(t);
        }
        return stack;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) return "";
        if (value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
