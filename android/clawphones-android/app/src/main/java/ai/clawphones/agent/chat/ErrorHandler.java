package ai.clawphones.agent.chat;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.CountDownTimer;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.Nullable;

import com.google.android.material.snackbar.Snackbar;
import com.termux.shared.logger.Logger;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Unified API error handling for chat/auth/settings flows.
 *
 * UX rules:
 * - No network -> banner
 * - 401 -> clear token + jump login
 * - 429 -> banner with countdown
 * - 500+ -> service unavailable banner
 * - timeout -> retry banner
 */
public final class ErrorHandler {

    private static final String LOG_TAG = "ErrorHandler";

    private static final String MSG_NO_NETWORK = "无网络连接";
    private static final String MSG_SERVICE_UNAVAILABLE = "服务暂时不可用";
    private static final String MSG_TIMEOUT_RETRY = "请求超时，点击重试";
    private static final String MSG_RATE_LIMIT = "请稍后再试";
    private static final String ACTION_RETRY = "重试";

    private static final Pattern RETRY_AFTER_PATTERN = Pattern.compile("\"retry_after\"\\s*:\\s*(\\d+)");
    private static final Pattern FIRST_NUMBER_PATTERN = Pattern.compile("(\\d+)");

    private static CountDownTimer sRateLimitTimer;
    private static Snackbar sRateLimitSnackbar;

    private ErrorHandler() {
    }

    public static boolean handle(Context context, Throwable error) {
        return handle(context, null, error, null, true);
    }

    public static boolean handle(Context context, @Nullable View anchorView, Throwable error) {
        return handle(context, anchorView, error, null, true);
    }

    public static boolean handle(Context context, @Nullable View anchorView, Throwable error,
                                 @Nullable Runnable retryAction) {
        return handle(context, anchorView, error, retryAction, true);
    }

    public static boolean handle(Context context, @Nullable View anchorView, Throwable error,
                                 @Nullable Runnable retryAction, boolean redirectOnUnauthorized) {
        if (context == null || error == null) return false;

        Throwable root = rootCause(error);
        if (root instanceof ClawPhonesAPI.ApiException) {
            ClawPhonesAPI.ApiException apiError = (ClawPhonesAPI.ApiException) root;
            int status = apiError.statusCode;
            if (status == 401) {
                if (!redirectOnUnauthorized) return false;
                runOnUiThread(context, () -> {
                    ClawPhonesAPI.clearToken(context);
                    redirectToLogin(context);
                });
                return true;
            }
            if (status == 429) {
                int retryAfter = resolveRetryAfterSeconds(apiError);
                runOnUiThread(context, () -> showRateLimitBanner(context, anchorView, retryAfter));
                return true;
            }
            if (status >= 500) {
                runOnUiThread(context, () -> showBanner(context, anchorView, MSG_SERVICE_UNAVAILABLE,
                    Snackbar.LENGTH_LONG, null));
                return true;
            }
            if (isTimeoutByMessage(apiError.getMessage())) {
                runOnUiThread(context, () -> showBanner(context, anchorView, MSG_TIMEOUT_RETRY,
                    Snackbar.LENGTH_INDEFINITE, retryAction));
                return true;
            }
        }

        if (isNetworkUnavailable(context, root)) {
            runOnUiThread(context, () -> showBanner(context, anchorView, MSG_NO_NETWORK,
                Snackbar.LENGTH_INDEFINITE, null));
            return true;
        }

        if (isTimeout(root)) {
            runOnUiThread(context, () -> showBanner(context, anchorView, MSG_TIMEOUT_RETRY,
                Snackbar.LENGTH_INDEFINITE, retryAction));
            return true;
        }

        return false;
    }

    private static void redirectToLogin(Context context) {
        Intent intent = new Intent(context, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
    }

    private static void showRateLimitBanner(Context context, @Nullable View anchorView, int seconds) {
        View anchor = resolveAnchorView(context, anchorView);
        if (anchor == null) {
            Logger.logWarn(LOG_TAG, "Cannot show 429 banner: missing anchor view");
            return;
        }

        cancelRateLimitCountdown();

        final int totalSeconds = Math.max(1, seconds);
        sRateLimitSnackbar = Snackbar.make(anchor, formatRateLimitText(totalSeconds), Snackbar.LENGTH_INDEFINITE);
        sRateLimitSnackbar.show();

        sRateLimitTimer = new CountDownTimer(totalSeconds * 1000L, 1000L) {
            @Override
            public void onTick(long millisUntilFinished) {
                if (sRateLimitSnackbar == null) return;
                int remaining = (int) Math.max(1L, millisUntilFinished / 1000L);
                sRateLimitSnackbar.setText(formatRateLimitText(remaining));
            }

            @Override
            public void onFinish() {
                if (sRateLimitSnackbar != null) {
                    sRateLimitSnackbar.setText(MSG_RATE_LIMIT);
                    sRateLimitSnackbar.dismiss();
                }
                sRateLimitSnackbar = null;
                sRateLimitTimer = null;
            }
        };
        sRateLimitTimer.start();
    }

    private static String formatRateLimitText(int seconds) {
        return String.format(Locale.getDefault(), "%s（%ds）", MSG_RATE_LIMIT, Math.max(1, seconds));
    }

    private static void showBanner(Context context, @Nullable View anchorView, String message, int duration,
                                   @Nullable Runnable retryAction) {
        View anchor = resolveAnchorView(context, anchorView);
        if (anchor == null) {
            Logger.logWarn(LOG_TAG, "Cannot show banner: missing anchor view for message: " + message);
            return;
        }

        Snackbar snackbar = Snackbar.make(anchor, message, duration);
        if (retryAction != null) {
            snackbar.setAction(ACTION_RETRY, v -> retryAction.run());
        }
        snackbar.show();
    }

    @Nullable
    private static View resolveAnchorView(Context context, @Nullable View anchorView) {
        if (anchorView != null) return anchorView;
        if (context instanceof Activity) {
            Activity activity = (Activity) context;
            return activity.findViewById(android.R.id.content);
        }
        return null;
    }

    private static void cancelRateLimitCountdown() {
        if (sRateLimitTimer != null) {
            sRateLimitTimer.cancel();
            sRateLimitTimer = null;
        }
        if (sRateLimitSnackbar != null) {
            sRateLimitSnackbar.dismiss();
            sRateLimitSnackbar = null;
        }
    }

    private static boolean isTimeout(Throwable error) {
        return error instanceof SocketTimeoutException || error instanceof InterruptedIOException;
    }

    private static boolean isNetworkUnavailable(Context context, Throwable error) {
        if (!isNetworkConnected(context)) return true;
        return error instanceof UnknownHostException
            || error instanceof ConnectException
            || error instanceof NoRouteToHostException;
    }

    private static boolean isNetworkConnected(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null) return false;
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Network state check failed: " + e.getMessage());
            return true;
        }
    }

    private static boolean isTimeoutByMessage(@Nullable String message) {
        if (TextUtils.isEmpty(message)) return false;
        String lower = message.toLowerCase(Locale.US);
        return lower.contains("timeout") || lower.contains("timed out");
    }

    private static int resolveRetryAfterSeconds(ClawPhonesAPI.ApiException error) {
        if (error.retryAfterSeconds > 0) {
            return error.retryAfterSeconds;
        }
        String message = error.getMessage();
        if (TextUtils.isEmpty(message)) {
            return 30;
        }
        Matcher jsonRetryAfter = RETRY_AFTER_PATTERN.matcher(message);
        if (jsonRetryAfter.find()) {
            int value = safeInt(jsonRetryAfter.group(1), 30);
            if (value > 0) return value;
        }
        Matcher firstNumber = FIRST_NUMBER_PATTERN.matcher(message);
        if (firstNumber.find()) {
            int value = safeInt(firstNumber.group(1), 30);
            if (value > 0) return value;
        }
        return 30;
    }

    private static int safeInt(@Nullable String raw, int fallback) {
        if (TextUtils.isEmpty(raw)) return fallback;
        try {
            return Integer.parseInt(raw);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static void runOnUiThread(Context context, Runnable action) {
        if (action == null) return;
        if (context instanceof Activity) {
            ((Activity) context).runOnUiThread(action);
            return;
        }
        action.run();
    }
}
