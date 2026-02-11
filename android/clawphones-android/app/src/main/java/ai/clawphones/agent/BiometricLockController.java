package ai.clawphones.agent;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.termux.R;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import ai.clawphones.agent.chat.ClawPhonesAPI;

/**
 * App-level biometric lock controller.
 * - Stores lock preference.
 * - Detects background -> foreground transitions.
 * - Requires biometric auth before re-entering protected session.
 */
public final class BiometricLockController {

    private static final String PREFS_NAME = "clawphones_security";
    private static final String KEY_BIOMETRIC_LOCK_ENABLED = "biometric_lock_enabled";

    private static final AtomicBoolean sInitialized = new AtomicBoolean(false);
    private static final AtomicBoolean sPromptInProgress = new AtomicBoolean(false);

    private static volatile int sStartedActivities = 0;
    private static volatile boolean sNeedsUnlock = false;

    private BiometricLockController() {}

    public static void init(@NonNull Application application) {
        if (!sInitialized.compareAndSet(false, true)) return;

        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
            }

            @Override
            public void onActivityStarted(@NonNull Activity activity) {
                synchronized (BiometricLockController.class) {
                    sStartedActivities += 1;
                }
            }

            @Override
            public void onActivityResumed(@NonNull Activity activity) {
                maybeAuthenticate(activity, null);
            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {
            }

            @Override
            public void onActivityStopped(@NonNull Activity activity) {
                synchronized (BiometricLockController.class) {
                    if (sStartedActivities > 0) {
                        sStartedActivities -= 1;
                    }

                    if (sStartedActivities == 0
                        && !activity.isChangingConfigurations()
                        && isSessionProtected(activity)) {
                        sNeedsUnlock = true;
                    }
                }
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {
            }
        });
    }

    public static boolean shouldShowSettingsToggle(@NonNull Context context) {
        return BiometricManager.from(context).canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS;
    }

    public static boolean isLockEnabled(@NonNull Context context) {
        return getPrefs(context).getBoolean(KEY_BIOMETRIC_LOCK_ENABLED, false);
    }

    public static void setLockEnabled(@NonNull Context context, boolean enabled) {
        boolean next = enabled && shouldShowSettingsToggle(context);
        getPrefs(context).edit().putBoolean(KEY_BIOMETRIC_LOCK_ENABLED, next).apply();
        if (!next) {
            sNeedsUnlock = false;
            sPromptInProgress.set(false);
        }
    }

    public static void authenticateIfNeeded(@NonNull FragmentActivity activity, @Nullable Runnable onUnlocked) {
        maybeAuthenticate(activity, onUnlocked);
    }

    private static void maybeAuthenticate(@NonNull Activity activity, @Nullable Runnable onUnlocked) {
        if (!(activity instanceof FragmentActivity)) {
            if (onUnlocked != null) onUnlocked.run();
            return;
        }

        if (!isSessionProtected(activity)) {
            sNeedsUnlock = false;
            if (onUnlocked != null) onUnlocked.run();
            return;
        }

        if (!sNeedsUnlock) {
            if (onUnlocked != null) onUnlocked.run();
            return;
        }

        if (!sPromptInProgress.compareAndSet(false, true)) {
            return;
        }

        showBiometricPrompt((FragmentActivity) activity, onUnlocked);
    }

    private static boolean isSessionProtected(@NonNull Context context) {
        if (!isLockEnabled(context)) return false;

        if (!shouldShowSettingsToggle(context)) {
            setLockEnabled(context, false);
            return false;
        }

        return hasSessionToken(context);
    }

    private static boolean hasSessionToken(@NonNull Context context) {
        String token = ClawPhonesAPI.getToken(context);
        return !TextUtils.isEmpty(token);
    }

    private static SharedPreferences getPrefs(@NonNull Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static void showBiometricPrompt(@NonNull FragmentActivity activity, @Nullable Runnable onUnlocked) {
        Executor executor = ContextCompat.getMainExecutor(activity);
        AtomicInteger failedAttempts = new AtomicInteger(0);
        AtomicBoolean fallbackStarted = new AtomicBoolean(false);
        final BiometricPrompt[] promptHolder = new BiometricPrompt[1];

        BiometricPrompt.AuthenticationCallback callback = new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                completeUnlock(onUnlocked);
            }

            @Override
            public void onAuthenticationFailed() {
                showVerifyIdentityMessage(activity);
                int attempts = failedAttempts.incrementAndGet();
                if (attempts >= 3 && fallbackStarted.compareAndSet(false, true)) {
                    BiometricPrompt prompt = promptHolder[0];
                    if (prompt != null) {
                        prompt.cancelAuthentication();
                    }
                    showDeviceCredentialPrompt(activity, onUnlocked);
                }
            }

            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                // Ignore cancel events triggered by switching to password fallback.
                if (fallbackStarted.get()
                    && (errorCode == BiometricPrompt.ERROR_CANCELED
                    || errorCode == BiometricPrompt.ERROR_USER_CANCELED
                    || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON)) {
                    return;
                }
                blockAppAccess(activity);
            }
        };

        promptHolder[0] = new BiometricPrompt(activity, executor, callback);
        promptHolder[0].authenticate(buildBiometricPromptInfo(activity));
    }

    private static void showDeviceCredentialPrompt(@NonNull FragmentActivity activity, @Nullable Runnable onUnlocked) {
        Executor executor = ContextCompat.getMainExecutor(activity);
        BiometricPrompt prompt = new BiometricPrompt(activity, executor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                completeUnlock(onUnlocked);
            }

            @Override
            public void onAuthenticationFailed() {
                showVerifyIdentityMessage(activity);
            }

            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                blockAppAccess(activity);
            }
        });
        prompt.authenticate(buildDeviceCredentialPromptInfo(activity));
    }

    private static BiometricPrompt.PromptInfo buildBiometricPromptInfo(@NonNull Context context) {
        return new BiometricPrompt.PromptInfo.Builder()
            .setTitle(context.getString(R.string.biometric_prompt_title))
            .setSubtitle(context.getString(R.string.biometric_prompt_subtitle))
            .setNegativeButtonText(context.getString(R.string.biometric_prompt_cancel))
            .build();
    }

    private static BiometricPrompt.PromptInfo buildDeviceCredentialPromptInfo(@NonNull Context context) {
        return new BiometricPrompt.PromptInfo.Builder()
            .setTitle(context.getString(R.string.biometric_prompt_title))
            .setSubtitle(context.getString(R.string.biometric_prompt_fallback_subtitle))
            .setDeviceCredentialAllowed(true)
            .build();
    }

    private static void completeUnlock(@Nullable Runnable onUnlocked) {
        sNeedsUnlock = false;
        sPromptInProgress.set(false);
        if (onUnlocked != null) {
            onUnlocked.run();
        }
    }

    private static void blockAppAccess(@NonNull Activity activity) {
        sNeedsUnlock = true;
        sPromptInProgress.set(false);
        showVerifyIdentityMessage(activity);
        activity.moveTaskToBack(true);
    }

    private static void showVerifyIdentityMessage(@NonNull Context context) {
        Toast.makeText(context, context.getString(R.string.biometric_verify_identity), Toast.LENGTH_SHORT).show();
    }
}
