package ai.clawphones.agent.chat;

import android.Manifest;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ai.clawphones.agent.CrashReporter;

/**
 * In-app AI chat UI backed by ClawPhones API.
 *
 * Handles: conversation create/resume, message send/receive, 401 auto-logout,
 * Markdown rendering, and graceful lifecycle management.
 */
public class ChatActivity extends AppCompatActivity {

    private RecyclerView mRecycler;
    private EditText mInput;
    private TextView mSpeechStatus;
    private ImageButton mSend;
    private ImageButton mMic;
    private View mMicPulse;
    private ProgressBar mSendProgress;

    private final ArrayList<ChatMessage> mMessages = new ArrayList<>();
    private ChatAdapter mAdapter;

    private ExecutorService mExecutor;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean mDestroyed = false;
    private boolean mBusy = false;
    private long mLastUpdateMs = 0L;
    @Nullable private Runnable mPendingUpdate = null;
    private static final long UPDATE_THROTTLE_MS = 50L;
    private static final long SPEECH_DONE_RESET_MS = 1_200L;
    private static final int REQUEST_RECORD_AUDIO = 7021;

    private enum SpeechUiState {
        IDLE,
        LISTENING,
        PROCESSING,
        DONE
    }

    private String mToken;
    private String mConversationId;
    private String mLastUserText = "";
    @Nullable private SpeechHelper mSpeechHelper;
    @Nullable private AnimatorSet mMicPulseAnimator;
    @Nullable private Runnable mPendingSpeechIdleReset = null;
    @NonNull private SpeechUiState mSpeechUiState = SpeechUiState.IDLE;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mToken = ClawPhonesAPI.getToken(this);
        if (TextUtils.isEmpty(mToken)) {
            redirectToLogin(null);
            return;
        }

        setContentView(R.layout.activity_chat);

        Toolbar toolbar = findViewById(R.id.chat_toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            String title = safeTrim(getIntent().getStringExtra("title"));
            toolbar.setTitle(TextUtils.isEmpty(title) ? getString(R.string.chat_new_conversation) : title);
            toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert);
            toolbar.setNavigationOnClickListener(v -> finish());

            MenuItem logoutItem = toolbar.getMenu().add(getString(R.string.chat_menu_logout));
            logoutItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
            toolbar.setOnMenuItemClickListener(item -> {
                confirmLogout();
                return true;
            });
        }

        mRecycler = findViewById(R.id.messages_recycler);
        mInput = findViewById(R.id.message_input);
        mSpeechStatus = findViewById(R.id.speech_status);
        mMic = findViewById(R.id.message_mic);
        mMicPulse = findViewById(R.id.message_mic_pulse);
        mSend = findViewById(R.id.message_send);
        mSendProgress = findViewById(R.id.message_send_progress);

        mAdapter = new ChatAdapter(mMessages);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        mRecycler.setLayoutManager(lm);
        mRecycler.setAdapter(mAdapter);

        mExecutor = Executors.newSingleThreadExecutor();

        String existingConversationId = safeTrim(getIntent().getStringExtra("conversation_id"));
        if (!TextUtils.isEmpty(existingConversationId)) {
            mConversationId = existingConversationId;
            loadHistory(existingConversationId);
        } else {
            addAssistantMessage(getString(R.string.chat_welcome_message));
            createConversation();
        }

        mSend.setOnClickListener(v -> onSend());
        initSpeechInput();

        mInput.setOnEditorActionListener((v, actionId, event) -> {
            onSend();
            return true;
        });
    }

    @Override
    protected void onDestroy() {
        mDestroyed = true;
        clearPendingSpeechIdleReset();
        stopMicPulseAnimation();
        if (mSpeechHelper != null) {
            mSpeechHelper.release();
            mSpeechHelper = null;
        }
        mMainHandler.removeCallbacksAndMessages(null);
        if (mExecutor != null) {
            try {
                mExecutor.shutdownNow();
            } catch (Exception ignored) {
            }
            mExecutor = null;
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_RECORD_AUDIO) return;

        boolean granted = grantResults.length > 0
            && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            setSpeechUiState(SpeechUiState.IDLE, null);
            toast(getString(R.string.chat_speech_permission_granted));
        } else {
            setSpeechUiState(SpeechUiState.IDLE, null);
            setSpeechStatus(getString(R.string.chat_speech_need_permission), false);
            toast(getString(R.string.chat_speech_need_permission));
        }
    }

    /** Post to UI thread safely — skips if activity is destroyed. */
    private void runSafe(Runnable r) {
        if (mDestroyed) return;
        mMainHandler.post(() -> {
            if (!mDestroyed) r.run();
        });
    }

    /** Execute on background thread safely — skips if executor is shut down. */
    private void execSafe(Runnable r) {
        ExecutorService exec = mExecutor;
        if (exec != null && !exec.isShutdown()) {
            try {
                exec.execute(r);
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
            }
        }
    }

    private void loadHistory(String conversationId) {
        if (TextUtils.isEmpty(conversationId)) return;

        mBusy = true;
        setInputEnabled(false);

        execSafe(() -> {
            try {
                List<Map<String, Object>> rows = new ArrayList<>(
                    ClawPhonesAPI.getMessages(ChatActivity.this, conversationId));

                Collections.sort(rows, new Comparator<Map<String, Object>>() {
                    @Override
                    public int compare(Map<String, Object> a, Map<String, Object> b) {
                        return Long.compare(asLong(a.get("created_at")), asLong(b.get("created_at")));
                    }
                });

                runSafe(() -> {
                    mMessages.clear();
                    for (Map<String, Object> row : rows) {
                        String role = asString(row.get("role"));
                        String content = asString(row.get("content"));
                        if (TextUtils.isEmpty(content)) continue;
                        ChatMessage.Role messageRole = "user".equalsIgnoreCase(role)
                            ? ChatMessage.Role.USER
                            : ChatMessage.Role.ASSISTANT;
                        mMessages.add(new ChatMessage(messageRole, content, false));
                    }
                    mAdapter.notifyDataSetChanged();
                    scrollToBottom();
                    mBusy = false;
                    setInputEnabled(true);
                });
            } catch (ClawPhonesAPI.ApiException e) {
                if (e.statusCode != 401) {
                    CrashReporter.reportNonFatal(ChatActivity.this, e, "loading_history");
                }
                runSafe(() -> {
                    if (e.statusCode == 401) {
                        ClawPhonesAPI.clearToken(ChatActivity.this);
                        redirectToLogin(getString(R.string.chat_login_expired));
                        return;
                    }
                    toast(getString(R.string.chat_error_load_history));
                    mBusy = false;
                    setInputEnabled(true);
                });
            } catch (IOException | JSONException e) {
                CrashReporter.reportNonFatal(ChatActivity.this, e, "loading_history");
                runSafe(() -> {
                    toast(getString(R.string.chat_error_load_history));
                    mBusy = false;
                    setInputEnabled(true);
                });
            }
        });
    }

    private void createConversation() {
        if (mBusy) return;
        mBusy = true;
        setInputEnabled(false);

        final int idx = addAssistantMessage(getString(R.string.chat_status_connecting));

        execSafe(() -> {
            try {
                String id = ClawPhonesAPI.createConversation(ChatActivity.this);
                runSafe(() -> {
                    mConversationId = id;
                    updateAssistantMessage(idx, getString(R.string.chat_status_connected_ready));
                    mBusy = false;
                    setInputEnabled(true);
                });
            } catch (IOException e) {
                CrashReporter.reportNonFatal(ChatActivity.this, e, "creating_conversation");
                runSafe(() -> {
                    updateAssistantMessage(idx, getString(R.string.chat_error_network, safeMsg(e)));
                    mBusy = false;
                    setInputEnabled(true);
                });
            } catch (JSONException e) {
                CrashReporter.reportNonFatal(ChatActivity.this, e, "creating_conversation");
                runSafe(() -> {
                    updateAssistantMessage(idx, getString(R.string.chat_error_parse_data));
                    mBusy = false;
                    setInputEnabled(true);
                });
            } catch (ClawPhonesAPI.ApiException e) {
                if (e.statusCode != 401) {
                    CrashReporter.reportNonFatal(ChatActivity.this, e, "creating_conversation");
                }
                runSafe(() -> {
                    if (e.statusCode == 401) {
                        ClawPhonesAPI.clearToken(ChatActivity.this);
                        redirectToLogin(getString(R.string.chat_login_expired));
                        return;
                    }
                    updateAssistantMessage(idx, getString(R.string.chat_error_connect_failed, safeErr(e)));
                    mBusy = false;
                    setInputEnabled(true);
                });
            }
        });
    }

    private void onSend() {
        if (mBusy) return;
        CrashReporter.setLastAction("sending_message");

        String text = safeTrim(mInput.getText().toString());
        if (TextUtils.isEmpty(text)) return;

        if (TextUtils.isEmpty(mConversationId)) {
            toast(getString(R.string.chat_status_initializing));
            return;
        }

        mLastUserText = text;
        mInput.setText("");
        addUserMessage(text);

        mBusy = true;
        setInputEnabled(false);
        setSendingState(true);

        final int idx = addAssistantMessage(getString(R.string.chat_status_thinking), true);

        execSafe(() -> {
            final StringBuilder accumulated = new StringBuilder();
            ClawPhonesAPI.chatStream(ChatActivity.this, mConversationId, text, new ClawPhonesAPI.StreamCallback() {
                @Override
                public void onDelta(String delta) {
                    accumulated.append(delta);
                    final String current = accumulated.toString();
                    runSafe(() -> updateAssistantMessageThrottled(idx, current));
                }

                @Override
                public void onComplete(String fullContent, String messageId) {
                    runSafe(() -> {
                        clearPendingUpdate();
                        String finalContent = fullContent;
                        if (TextUtils.isEmpty(finalContent)) {
                            finalContent = accumulated.toString();
                        }
                        updateAssistantMessage(idx, finalContent);
                        mBusy = false;
                        setInputEnabled(true);
                        setSendingState(false);
                    });
                }

                @Override
                public void onError(Exception error) {
                    CrashReporter.reportNonFatal(ChatActivity.this, error, "streaming_response");
                    runSafe(() -> {
                        clearPendingUpdate();
                        if (error instanceof ClawPhonesAPI.ApiException
                            && ((ClawPhonesAPI.ApiException) error).statusCode == 401) {
                            ClawPhonesAPI.clearToken(ChatActivity.this);
                            redirectToLogin(getString(R.string.chat_login_expired));
                            return;
                        }

                        String partial = accumulated.toString();
                        if (!partial.isEmpty()) {
                            updateAssistantMessage(idx,
                                getString(R.string.chat_error_partial_interrupted, partial));
                        } else {
                            updateAssistantMessage(idx, getString(R.string.chat_error_send_failed));
                        }
                        mBusy = false;
                        setInputEnabled(true);
                        setSendingState(false);
                    });
                }
            });
        });
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this)
            .setMessage(getString(R.string.chat_dialog_logout_confirm))
            .setNegativeButton(getString(R.string.chat_action_cancel), null)
            .setPositiveButton(getString(R.string.chat_action_logout), (d, w) -> {
                ClawPhonesAPI.clearToken(ChatActivity.this);
                redirectToLogin(null);
            })
            .show();
    }

    private void redirectToLogin(@Nullable String message) {
        if (!TextUtils.isEmpty(message)) {
            toast(message);
        }
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    private void setInputEnabled(boolean enabled) {
        if (mInput != null) {
            mInput.setEnabled(enabled);
            mInput.setAlpha(enabled ? 1.0f : 0.6f);
        }
        if (mMic != null) {
            mMic.setEnabled(enabled);
            mMic.setAlpha(enabled ? 1.0f : 0.5f);
        }
        if (mSend != null) {
            mSend.setEnabled(enabled);
            mSend.setAlpha(enabled ? 1.0f : 0.5f);
        }
        if (!enabled && mSpeechHelper != null) {
            mSpeechHelper.cancelListening();
            setSpeechUiState(SpeechUiState.IDLE, null);
        }
    }

    private void setSendingState(boolean sending) {
        if (mSend != null) {
            mSend.setVisibility(sending ? View.INVISIBLE : View.VISIBLE);
        }
        if (mSendProgress != null) {
            mSendProgress.setVisibility(sending ? View.VISIBLE : View.GONE);
        }
    }

    private void initSpeechInput() {
        if (mMic == null) return;

        mSpeechHelper = new SpeechHelper(this, new SpeechHelper.Callback() {
            @Override
            public void onStatus(@NonNull String status, boolean active) {
                runSafe(() -> {
                    if (!active) return;
                    if (status.contains("识别")) {
                        setSpeechUiState(SpeechUiState.PROCESSING, null);
                    } else {
                        setSpeechUiState(SpeechUiState.LISTENING, null);
                    }
                });
            }

            @Override
            public void onPartialText(@NonNull String text) {
                runSafe(() -> setSpeechUiState(SpeechUiState.LISTENING, text));
            }

            @Override
            public void onFinalText(@NonNull String text) {
                runSafe(() -> applyRecognizedText(text));
            }

            @Override
            public void onError(@NonNull String message) {
                runSafe(() -> {
                    setSpeechUiState(SpeechUiState.IDLE, null);
                    setSpeechStatus(message, false);
                });
            }
        });

        if (mSpeechHelper == null || !mSpeechHelper.isRecognitionAvailable()) {
            mMic.setEnabled(false);
            mMic.setAlpha(0.5f);
            setSpeechStatus(getString(R.string.chat_speech_not_supported), false);
            stopMicPulseAnimation();
            return;
        }

        setSpeechUiState(SpeechUiState.IDLE, null);

        mMic.setOnTouchListener((v, event) -> {
            if (mBusy) return true;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                if (!hasRecordPermission()) {
                    ActivityCompat.requestPermissions(
                        ChatActivity.this,
                        new String[]{Manifest.permission.RECORD_AUDIO},
                        REQUEST_RECORD_AUDIO
                    );
                    setSpeechUiState(SpeechUiState.IDLE, null);
                    setSpeechStatus(getString(R.string.chat_speech_need_permission), false);
                    return true;
                }
                if (mSpeechHelper != null) {
                    mSpeechHelper.startListening(Locale.getDefault());
                    setSpeechUiState(SpeechUiState.LISTENING, null);
                }
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (mSpeechHelper != null) {
                    mSpeechHelper.stopListening();
                    setSpeechUiState(SpeechUiState.PROCESSING, null);
                }
                v.performClick();
                return true;
            }
            return false;
        });
    }

    private boolean hasRecordPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED;
    }

    private void applyRecognizedText(@NonNull String text) {
        String recognized = safeTrim(text);
        if (TextUtils.isEmpty(recognized)) {
            setSpeechUiState(SpeechUiState.IDLE, null);
            setSpeechStatus(getString(R.string.chat_speech_error_no_match), false);
            return;
        }
        mInput.setText(recognized);
        mInput.setSelection(recognized.length());
        setSpeechUiState(SpeechUiState.DONE, null);
    }

    private void setSpeechStatus(@NonNull String text, boolean active) {
        if (mSpeechStatus == null) return;
        if (TextUtils.isEmpty(text)) {
            mSpeechStatus.setVisibility(View.GONE);
            return;
        }
        mSpeechStatus.setText(text);
        mSpeechStatus.setVisibility(View.VISIBLE);
        mSpeechStatus.setTextColor(active
            ? ContextCompat.getColor(this, R.color.clawphones_accent)
            : ContextCompat.getColor(this, R.color.clawphones_secondary_text));
    }

    private void setSpeechUiState(@NonNull SpeechUiState state, @Nullable String partialText) {
        clearPendingSpeechIdleReset();
        mSpeechUiState = state;
        updateMicVisualState();

        switch (state) {
            case IDLE:
                setSpeechStatus(getString(R.string.chat_speech_state_idle), false);
                break;
            case LISTENING:
                if (!TextUtils.isEmpty(partialText)) {
                    setSpeechStatus(getString(R.string.chat_speech_status_recording_partial, partialText), true);
                } else {
                    setSpeechStatus(getString(R.string.chat_speech_state_listening), true);
                }
                break;
            case PROCESSING:
                setSpeechStatus(getString(R.string.chat_speech_state_processing), true);
                break;
            case DONE:
                setSpeechStatus(getString(R.string.chat_speech_state_done), true);
                mPendingSpeechIdleReset = () -> {
                    if (mSpeechUiState == SpeechUiState.DONE) {
                        setSpeechUiState(SpeechUiState.IDLE, null);
                    }
                };
                mMainHandler.postDelayed(mPendingSpeechIdleReset, SPEECH_DONE_RESET_MS);
                break;
        }
    }

    private void clearPendingSpeechIdleReset() {
        if (mPendingSpeechIdleReset == null) return;
        mMainHandler.removeCallbacks(mPendingSpeechIdleReset);
        mPendingSpeechIdleReset = null;
    }

    private void updateMicVisualState() {
        if (mMic == null) return;

        mMic.animate().cancel();
        switch (mSpeechUiState) {
            case LISTENING:
                mMic.setBackgroundResource(R.drawable.clawphones_button_bg);
                mMic.setColorFilter(ContextCompat.getColor(this, R.color.clawphones_background));
                mMic.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120L).start();
                mMic.setElevation(10f);
                startMicPulseAnimation();
                break;
            case PROCESSING:
                mMic.setBackgroundResource(R.drawable.clawphones_button_outline_bg);
                mMic.setColorFilter(ContextCompat.getColor(this, R.color.clawphones_accent));
                mMic.animate().scaleX(1.03f).scaleY(1.03f).setDuration(120L).start();
                mMic.setElevation(6f);
                stopMicPulseAnimation();
                break;
            case DONE:
                mMic.setBackgroundResource(R.drawable.clawphones_button_outline_bg);
                mMic.setColorFilter(ContextCompat.getColor(this, R.color.clawphones_accent));
                mMic.animate().scaleX(1.02f).scaleY(1.02f).setDuration(120L).start();
                mMic.setElevation(2f);
                stopMicPulseAnimation();
                break;
            case IDLE:
            default:
                mMic.setBackgroundResource(R.drawable.clawphones_button_outline_bg);
                mMic.setColorFilter(ContextCompat.getColor(this, R.color.clawphones_accent));
                mMic.animate().scaleX(1f).scaleY(1f).setDuration(120L).start();
                mMic.setElevation(0f);
                stopMicPulseAnimation();
                break;
        }
    }

    private void startMicPulseAnimation() {
        if (mMicPulse == null) return;
        if (mMicPulseAnimator != null && mMicPulseAnimator.isRunning()) return;

        mMicPulse.setVisibility(View.VISIBLE);
        mMicPulse.setAlpha(0.28f);
        mMicPulse.setScaleX(1f);
        mMicPulse.setScaleY(1f);

        ObjectAnimator scaleX = ObjectAnimator.ofFloat(mMicPulse, View.SCALE_X, 1f, 1.3f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(mMicPulse, View.SCALE_Y, 1f, 1.3f);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(mMicPulse, View.ALPHA, 0.28f, 0f);
        scaleX.setRepeatCount(ValueAnimator.INFINITE);
        scaleY.setRepeatCount(ValueAnimator.INFINITE);
        alpha.setRepeatCount(ValueAnimator.INFINITE);
        scaleX.setRepeatMode(ValueAnimator.RESTART);
        scaleY.setRepeatMode(ValueAnimator.RESTART);
        alpha.setRepeatMode(ValueAnimator.RESTART);

        mMicPulseAnimator = new AnimatorSet();
        mMicPulseAnimator.setDuration(900L);
        mMicPulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        mMicPulseAnimator.playTogether(scaleX, scaleY, alpha);
        mMicPulseAnimator.start();
    }

    private void stopMicPulseAnimation() {
        if (mMicPulseAnimator != null) {
            mMicPulseAnimator.cancel();
            mMicPulseAnimator = null;
        }
        if (mMicPulse != null) {
            mMicPulse.setVisibility(View.INVISIBLE);
            mMicPulse.setAlpha(0f);
            mMicPulse.setScaleX(1f);
            mMicPulse.setScaleY(1f);
        }
    }

    private int addUserMessage(String text) {
        int idx = mMessages.size();
        mMessages.add(new ChatMessage(ChatMessage.Role.USER, text, false));
        mAdapter.notifyItemInserted(idx);
        scrollToBottom();
        return idx;
    }

    private int addAssistantMessage(String text) {
        return addAssistantMessage(text, false);
    }

    private int addAssistantMessage(String text, boolean isThinking) {
        int idx = mMessages.size();
        mMessages.add(new ChatMessage(ChatMessage.Role.ASSISTANT, text, isThinking));
        mAdapter.notifyItemInserted(idx);
        scrollToBottom();
        return idx;
    }

    private void updateAssistantMessage(int index, String text) {
        if (index < 0 || index >= mMessages.size()) return;
        ChatMessage m = mMessages.get(index);
        m.text = text;
        m.isThinking = false;
        mAdapter.notifyItemChanged(index);
        scrollToBottom();
    }

    private void updateAssistantMessageThrottled(int index, String text) {
        if (index < 0 || index >= mMessages.size()) return;
        ChatMessage m = mMessages.get(index);
        m.text = text;
        m.isThinking = false;

        long now = System.currentTimeMillis();
        long elapsed = now - mLastUpdateMs;
        if (elapsed >= UPDATE_THROTTLE_MS) {
            clearPendingUpdate();
            mLastUpdateMs = now;
            mAdapter.notifyItemChanged(index);
            scrollToBottom();
            return;
        }

        if (mPendingUpdate != null) {
            mMainHandler.removeCallbacks(mPendingUpdate);
        }
        final int pendingIndex = index;
        long delay = UPDATE_THROTTLE_MS - elapsed;
        mPendingUpdate = () -> {
            mLastUpdateMs = System.currentTimeMillis();
            mPendingUpdate = null;
            if (pendingIndex < 0 || pendingIndex >= mMessages.size()) return;
            mAdapter.notifyItemChanged(pendingIndex);
            scrollToBottom();
        };
        mMainHandler.postDelayed(mPendingUpdate, Math.max(1L, delay));
    }

    private void clearPendingUpdate() {
        if (mPendingUpdate == null) return;
        mMainHandler.removeCallbacks(mPendingUpdate);
        mPendingUpdate = null;
    }

    private void scrollToBottom() {
        if (mRecycler == null) return;
        mRecycler.post(() -> {
            if (mAdapter != null && mAdapter.getItemCount() > 0) {
                mRecycler.smoothScrollToPosition(mAdapter.getItemCount() - 1);
            }
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private static String safeTrim(String s) {
        if (s == null) return "";
        return s.trim();
    }

    private String safeMsg(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.trim().isEmpty()) return getString(R.string.chat_error_unknown);
        if (msg.length() > 200) msg = msg.substring(0, 200) + "…";
        return msg;
    }

    private String safeErr(ClawPhonesAPI.ApiException e) {
        String msg = e.getMessage();
        if (msg == null || msg.trim().isEmpty()) {
            return getString(R.string.chat_error_http_status, e.statusCode);
        }
        try {
            org.json.JSONObject errJson = new org.json.JSONObject(msg);
            String detail = errJson.optString("detail", null);
            if (detail != null && !detail.trim().isEmpty()) return detail;
        } catch (Exception ignored) {
        }
        if (msg.length() > 200) msg = msg.substring(0, 200) + "…";
        return msg;
    }

    private static String asString(Object value) {
        if (value == null) return "";
        return String.valueOf(value);
    }

    private static long asLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return 0L;
        }
    }

    static final class ChatMessage {
        enum Role { USER, ASSISTANT }

        final Role role;
        String text;
        boolean isThinking;

        ChatMessage(Role role, String text, boolean isThinking) {
            this.role = role;
            this.text = text;
            this.isThinking = isThinking;
        }
    }

    static final class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.VH> {
        private static final int TYPE_AI = 0;
        private static final int TYPE_USER = 1;

        private final ArrayList<ChatMessage> messages;

        ChatAdapter(ArrayList<ChatMessage> messages) {
            this.messages = messages;
        }

        @Override
        public int getItemViewType(int position) {
            ChatMessage m = messages.get(position);
            return m.role == ChatMessage.Role.USER ? TYPE_USER : TYPE_AI;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = android.view.LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_message, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            ChatMessage m = messages.get(position);
            boolean isUser = m.role == ChatMessage.Role.USER;
            boolean isThinking = !isUser && m.isThinking;
            holder.bind(m.text, isUser, isThinking);
        }

        @Override
        public int getItemCount() {
            return messages.size();
        }

        static final class VH extends RecyclerView.ViewHolder {
            final TextView text;
            final View bubble;

            VH(@NonNull View itemView) {
                super(itemView);
                text = itemView.findViewById(R.id.message_text);
                bubble = itemView.findViewById(R.id.message_bubble);
                if (text != null) {
                    text.setMovementMethod(LinkMovementMethod.getInstance());
                }
            }

            void bind(String markdown, boolean isUser, boolean isThinking) {
                if (text != null) {
                    if (isThinking) {
                        text.setText(markdown);
                        text.setTypeface(null, Typeface.ITALIC);
                        text.setTextColor(0xFF888888);
                    } else {
                        text.setText(renderMarkdown(markdown));
                        text.setTypeface(null, Typeface.NORMAL);
                        text.setTextColor(isUser ? Color.WHITE : 0xFFF5F0E6);
                    }
                }
                if (bubble != null) {
                    android.widget.FrameLayout.LayoutParams lp =
                        (android.widget.FrameLayout.LayoutParams) bubble.getLayoutParams();
                    if (isUser) {
                        lp.gravity = android.view.Gravity.END;
                        bubble.setBackgroundResource(R.drawable.chat_bubble_user);
                    } else {
                        lp.gravity = android.view.Gravity.START;
                        bubble.setBackgroundResource(R.drawable.chat_bubble_assistant);
                    }
                    bubble.setLayoutParams(lp);
                }
            }
        }

        /**
         * Render basic Markdown to HTML. Uses bounded regex patterns to
         * prevent catastrophic backtracking (ReDoS) on malicious input.
         */
        private static CharSequence renderMarkdown(String markdown) {
            if (markdown == null) markdown = "";
            if (markdown.length() > 50_000) {
                markdown = markdown.substring(0, 50_000) + "…";
            }
            try {
                String html = TextUtils.htmlEncode(markdown);
                html = html.replaceAll("```([^`]{0,10000})```", "<pre>$1</pre>");
                html = html.replaceAll("`([^`]{1,500})`", "<tt><b>$1</b></tt>");
                html = html.replaceAll("\\*\\*([^*]{1,500})\\*\\*", "<b>$1</b>");
                html = html.replaceAll("(?<!\\*)\\*([^*]{1,500})\\*(?!\\*)", "<i>$1</i>");
                html = html.replaceAll("\\[([^\\]]{1,200})\\]\\((https?://[^\\)]{1,500})\\)", "<a href=\"$2\">$1</a>");
                html = html.replaceAll("(?m)^- (.+)", "• $1");
                html = html.replace("\n", "<br/>");
                return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY);
            } catch (Exception e) {
                return markdown;
            }
        }
    }
}
