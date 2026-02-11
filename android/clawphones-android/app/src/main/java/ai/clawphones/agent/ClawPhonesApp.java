package ai.clawphones.agent;

import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.termux.app.TermuxApplication;
import com.termux.shared.logger.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * App entry that keeps cold-start work minimal and defers non-critical setup.
 */
public class ClawPhonesApp extends TermuxApplication {

    private static final String LOG_TAG = "ClawPhonesApp";

    private static final ExecutorService DEFERRED_INIT_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean DEFERRED_INIT_SCHEDULED = new AtomicBoolean(false);
    private static final AtomicBoolean CRASH_REPORTER_INITIALIZED = new AtomicBoolean(false);
    private static final AtomicBoolean SERVICE_WARMUP_DONE = new AtomicBoolean(false);

    @Override
    public void onCreate() {
        super.onCreate();
        ensureDeferredInit(getApplicationContext());
    }

    public static void ensureDeferredInit(Context context) {
        if (context == null) return;
        if (!DEFERRED_INIT_SCHEDULED.compareAndSet(false, true)) return;

        Context appContext = context.getApplicationContext();
        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(() -> DEFERRED_INIT_EXECUTOR.execute(() -> {
            initCrashReporter(appContext);
            warmServiceIntents(appContext);
        }));
    }

    public static boolean bindClawPhonesService(Context context, ServiceConnection connection) {
        ensureDeferredInit(context);
        Intent intent = new Intent(context, ClawPhonesService.class);
        return context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    public static void startGatewayMonitorService(Context context) {
        ensureDeferredInit(context);
        Intent intent = new Intent(context, GatewayMonitorService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to start gateway monitor service: " + e.getMessage());
        }
    }

    private static void initCrashReporter(Context appContext) {
        if (!CRASH_REPORTER_INITIALIZED.compareAndSet(false, true)) return;
        CrashReporter.init(appContext);
    }

    private static void warmServiceIntents(Context appContext) {
        if (!SERVICE_WARMUP_DONE.compareAndSet(false, true)) return;

        // Pre-create intents once so class loading is shifted away from first frame.
        new Intent(appContext, ClawPhonesService.class).setPackage(appContext.getPackageName());
        new Intent(appContext, GatewayMonitorService.class).setPackage(appContext.getPackageName());
        Logger.logDebug(LOG_TAG, "Deferred service warmup complete");
    }
}
