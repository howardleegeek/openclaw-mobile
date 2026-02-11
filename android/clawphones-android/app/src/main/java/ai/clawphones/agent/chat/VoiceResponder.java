package ai.clawphones.agent.chat;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;

import java.util.Locale;

/**
 * Text-to-speech responder for Node Mode detections.
 */
public final class VoiceResponder {

    private static final String LOG_TAG = "VoiceResponder";

    private final TextToSpeech mTts;
    private volatile boolean mReady = false;
    private long mLastSpokenAtMs = 0L;

    public enum ResponseType {
        DETERRENT("deterrent", "已录像，请勿靠近"),
        WELCOME("welcome", "欢迎回家"),
        RECORDING("recording", "此区域正在监控中"),
        CUSTOM("custom", "");

        final String key;
        final String defaultMessage;

        ResponseType(String key, String defaultMessage) {
            this.key = key;
            this.defaultMessage = defaultMessage;
        }

        @NonNull
        static ResponseType fromValue(@Nullable String raw) {
            if (raw == null) return RECORDING;
            String normalized = raw.trim().toLowerCase(Locale.US);
            for (ResponseType type : values()) {
                if (type.key.equals(normalized)) {
                    return type;
                }
            }
            return RECORDING;
        }
    }

    public static final class TriggerConfig {
        public boolean enabled = false;
        @NonNull public ResponseType responseType = ResponseType.RECORDING;
        @NonNull public String customMessage = "";
        public boolean onlyForPerson = true;
        public int cooldownSeconds = 60;

        @NonNull
        String resolveMessage() {
            if (responseType == ResponseType.CUSTOM) {
                String trimmed = customMessage.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
                return ResponseType.RECORDING.defaultMessage;
            }
            return responseType.defaultMessage;
        }

        static TriggerConfig fromPreferences(@NonNull SharedPreferences prefs) {
            TriggerConfig config = new TriggerConfig();
            config.enabled = prefs.getBoolean(
                NodeModeService.PREF_VOICE_ENABLED,
                NodeModeService.DEFAULT_VOICE_ENABLED
            );
            config.responseType = ResponseType.fromValue(
                prefs.getString(
                    NodeModeService.PREF_VOICE_RESPONSE_TYPE,
                    NodeModeService.DEFAULT_VOICE_RESPONSE_TYPE
                )
            );
            config.customMessage = nonNull(
                prefs.getString(
                    NodeModeService.PREF_VOICE_CUSTOM_MESSAGE,
                    NodeModeService.DEFAULT_VOICE_CUSTOM_MESSAGE
                )
            );
            config.onlyForPerson = prefs.getBoolean(
                NodeModeService.PREF_VOICE_ONLY_PERSON,
                NodeModeService.DEFAULT_VOICE_ONLY_PERSON
            );
            config.cooldownSeconds = clamp(
                prefs.getInt(
                    NodeModeService.PREF_VOICE_COOLDOWN_SECONDS,
                    NodeModeService.DEFAULT_VOICE_COOLDOWN_SECONDS
                ),
                1,
                3600
            );
            return config;
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }

        @NonNull
        private static String nonNull(@Nullable String value) {
            return value == null ? "" : value;
        }
    }

    public VoiceResponder(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        mTts = new TextToSpeech(appContext, status -> {
            if (status == TextToSpeech.SUCCESS) {
                mReady = true;
            } else {
                Logger.logError(LOG_TAG, "TextToSpeech init failed, status=" + status);
            }
        });
    }

    public void respond(@Nullable VisionDetector.Detection detection, @NonNull TriggerConfig config) {
        if (!mReady) return;
        if (!config.enabled) return;
        if (detection == null) return;

        if (config.onlyForPerson
            && !VisionDetector.TYPE_PERSON.equalsIgnoreCase(safeLower(detection.type))) {
            return;
        }

        long now = SystemClock.elapsedRealtime();
        long cooldownMs = Math.max(1, config.cooldownSeconds) * 1000L;
        if (now - mLastSpokenAtMs < cooldownMs) {
            return;
        }

        String message = config.resolveMessage();
        if (TextUtils.isEmpty(message)) return;

        applyLanguageForMessage(message);

        int result = mTts.speak(
            message,
            TextToSpeech.QUEUE_ADD,
            null,
            "node-voice-" + now
        );
        if (result == TextToSpeech.SUCCESS) {
            mLastSpokenAtMs = now;
        } else {
            Logger.logWarn(LOG_TAG, "TextToSpeech speak failed: " + result);
        }
    }

    public void shutdown() {
        mReady = false;
        try {
            mTts.stop();
        } catch (Exception ignored) {
        }
        try {
            mTts.shutdown();
        } catch (Exception ignored) {
        }
    }

    private void applyLanguageForMessage(@NonNull String message) {
        Locale preferred = containsChinese(message) ? Locale.CHINESE : Locale.US;
        int result = mTts.setLanguage(preferred);
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Locale fallback = preferred.equals(Locale.US) ? Locale.CHINESE : Locale.US;
            mTts.setLanguage(fallback);
        }
    }

    private static boolean containsChinese(@NonNull String message) {
        for (int i = 0; i < message.length(); i++) {
            char ch = message.charAt(i);
            if (ch >= 0x4E00 && ch <= 0x9FFF) {
                return true;
            }
        }
        return false;
    }

    @NonNull
    private static String safeLower(@Nullable String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.US);
    }
}
