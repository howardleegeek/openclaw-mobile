package ai.clawphones.agent.chat;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Thin wrapper around SpeechRecognizer configured with RecognizerIntent.
 */
final class SpeechHelper implements RecognitionListener {

    interface Callback {
        void onStatus(@NonNull String status, boolean active);
        void onPartialText(@NonNull String text);
        void onFinalText(@NonNull String text);
        void onError(@NonNull String message);
    }

    private final Context mContext;
    private final Callback mCallback;
    @Nullable private SpeechRecognizer mRecognizer;
    private boolean mListening = false;

    SpeechHelper(@NonNull Context context, @NonNull Callback callback) {
        mContext = context.getApplicationContext();
        mCallback = callback;
    }

    boolean isRecognitionAvailable() {
        return SpeechRecognizer.isRecognitionAvailable(mContext);
    }

    void startListening(@Nullable Locale locale) {
        if (!isRecognitionAvailable()) {
            mCallback.onError("这台设备不支持语音识别");
            return;
        }

        ensureRecognizer();
        if (mRecognizer == null) {
            mCallback.onError("语音识别初始化失败");
            return;
        }

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, mContext.getPackageName());
        if (locale != null) {
            String lang = locale.toLanguageTag();
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
        }

        try {
            mListening = true;
            mCallback.onStatus("正在启动录音…", true);
            mRecognizer.startListening(intent);
        } catch (Exception e) {
            mListening = false;
            mCallback.onError("无法启动语音识别");
        }
    }

    void stopListening() {
        if (mRecognizer == null || !mListening) return;
        try {
            mCallback.onStatus("识别中…", true);
            mRecognizer.stopListening();
        } catch (Exception e) {
            mListening = false;
            mCallback.onError("语音识别已中断");
        }
    }

    void cancelListening() {
        if (mRecognizer == null) return;
        try {
            mRecognizer.cancel();
        } catch (Exception ignored) {
        }
        mListening = false;
    }

    void release() {
        if (mRecognizer != null) {
            try {
                mRecognizer.cancel();
                mRecognizer.destroy();
            } catch (Exception ignored) {
            }
            mRecognizer = null;
        }
        mListening = false;
    }

    private void ensureRecognizer() {
        if (mRecognizer != null) return;
        try {
            mRecognizer = SpeechRecognizer.createSpeechRecognizer(mContext);
            mRecognizer.setRecognitionListener(this);
        } catch (Exception ignored) {
            mRecognizer = null;
        }
    }

    @Override
    public void onReadyForSpeech(Bundle params) {
        mCallback.onStatus("请开始说话…", true);
    }

    @Override
    public void onBeginningOfSpeech() {
        mCallback.onStatus("录音中…", true);
    }

    @Override
    public void onRmsChanged(float rmsdB) {
        // No-op
    }

    @Override
    public void onBufferReceived(byte[] buffer) {
        // No-op
    }

    @Override
    public void onEndOfSpeech() {
        mCallback.onStatus("识别中…", true);
    }

    @Override
    public void onError(int error) {
        mListening = false;
        mCallback.onError(mapError(error));
    }

    @Override
    public void onResults(Bundle results) {
        mListening = false;
        String text = extractTopResult(results);
        if (TextUtils.isEmpty(text)) {
            mCallback.onError("没有识别到语音");
            return;
        }
        mCallback.onFinalText(text);
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        String partial = extractTopResult(partialResults);
        if (!TextUtils.isEmpty(partial)) {
            mCallback.onPartialText(partial);
        }
    }

    @Override
    public void onEvent(int eventType, Bundle params) {
        // No-op
    }

    @Nullable
    private static String extractTopResult(@Nullable Bundle bundle) {
        if (bundle == null) return null;
        ArrayList<String> matches = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null || matches.isEmpty()) return null;
        return matches.get(0);
    }

    @NonNull
    private static String mapError(int code) {
        switch (code) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "录音出错，请重试";
            case SpeechRecognizer.ERROR_CLIENT:
                return "语音识别已取消";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "缺少麦克风权限";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "网络异常，语音识别失败";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "没有听清楚，请再说一次";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "语音识别器忙，请稍后再试";
            case SpeechRecognizer.ERROR_SERVER:
                return "语音识别服务暂时不可用";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "没有检测到语音";
            default:
                return "语音识别失败";
        }
    }
}
