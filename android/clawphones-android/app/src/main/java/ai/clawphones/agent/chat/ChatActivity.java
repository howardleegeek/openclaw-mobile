package ai.clawphones.agent.chat;

import android.Manifest;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
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
import java.util.HashMap;

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
    private final HashMap<Long, Integer> mQueuedMessageIndexes = new HashMap<>();
    private ChatAdapter mAdapter;
    private MessageQueue mMessageQueue;
    private ConversationCache mCache;

    private ExecutorService mExecutor;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean mDestroyed = false;
    private boolean mBusy = false;
    private long mLastUpdateMs = 0L;
    @Nullable private Runnable mPendingUpdate = null;
    private static final long UPDATE_THROTTLE_MS = 50L;
    private static final long SPEECH_DONE_RESET_MS = 1_200L;
    private static final int MAX_QUEUE_RETRY = 3;
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

    @Nullable private BroadcastReceiver mConnectivityReceiver;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mToken = ClawPhonesAPI.getToken(this);
        if (TextUtils.isEmpty(mToken)) {
            redirectToLogin(null);
            return;
        }

        mCache = new ConversationCache(getApplicationContext());

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

        mAdapter = new ChatAdapter(mMessages, this::onRetryQueuedMessage);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        mRecycler.setLayoutManager(lm);
        mRecycler.setAdapter(mAdapter);

        mMessageQueue = new MessageQueue(this);
        mExecutor = Executors.newSingleThreadExecutor();
        registerConnectivityReceiver();

        String existingConversationId = safeTrim(getIntent().getStringExtra("conversation_id"));
        if (!TextUtils.isEmpty(existingConversationId)) {
            mConversationId = existingConversationId;
            loadHistory(existingConversationId);
        } else {
            addAssistantMessage(getString(R.string.chat_welcome_message));
            createConversation();
            restoreQueuedMessagesWithoutConversation();
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
        unregisterConnectivityReceiver();
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
        if (mCache != null) {
            try {
                mCache.close();
            } catch (Exception ignored) {
            }
            mCache = null;
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
            boolean usedCache = false;
            if (mCache != null) {
                List<Map<String, Object>> cachedRows = new ArrayList<>(mCache.getRecentMessages(conversationId));
                if (!cachedRows.isEmpty()) {
                    Collections.sort(cachedRows, new Comparator<Map<String, Object>>() {
                        @Override
                        public int compare(Map<String, Object> a, Map<String, Object> b) {
                            return Long.compare(asLong(a.get("created_at")), asLong(b.get("created_at")));
                        }
                    });
                    usedCache = true;
                    List<Map<String, Object>> safeCachedRows = cachedRows;
                    runSafe(() -> {
                        applyHistoryRows(safeCachedRows, conversationId);
                        mBusy = false;
                        setInputEnabled(true);
                        tryFlushPendingMessages();
                    });
                }
            }
            final boolean hadCache = usedCache;

            try {
                List<Map<String, Object>> rows = new ArrayList<>(
                    ClawPhonesAPI.getMessages(ChatActivity.this, conversationId));

                Collections.sort(rows, new Comparator<Map<String, Object>>() {
                    @Override
                    public int compare(Map<String, Object> a, Map<String, Object> b) {
                        return Long.compare(asLong(a.get("created_at")), asLong(b.get("created_at")));
                    }
                });

                if (mCache != null) {
                    mCache.upsertMessages(conversationId, rows);
                    rows = new ArrayList<>(mCache.getRecentMessages(conversationId));
                }

                List<Map<String, Object>> safeRows = rows;

                runSafe(() -> {
                    applyHistoryRows(safeRows, conversationId);
                    mBusy = false;
                    setInputEnabled(true);
                    tryFlushPendingMessages();
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
                    if (!hadCache) {
                        toast(getString(R.string.chat_error_load_history));
                    }
                    mBusy = false;
                    setInputEnabled(true);
                });
            } catch (IOException | JSONException e) {
                CrashReporter.reportNonFatal(ChatActivity.this, e, "loading_history");
                runSafe(() -> {
                    if (!hadCache) {
                        toast(getString(R.string.chat_error_load_history));
                    }
                    mBusy = false;
                    setInputEnabled(true);
                });
            }
        });
    }

    private void applyHistoryRows(@NonNull List<Map<String, Object>> rows, @Nullable String conversationId) {
        mMessages.clear();
        mQueuedMessageIndexes.clear();
        for (Map<String, Object> row : rows) {
            String role = asString(row.get("role"));
            String content = asString(row.get("content"));
            ParsedVisionContent parsed = parseVisionContent(content);
            if (TextUtils.isEmpty(parsed.text) && TextUtils.isEmpty(parsed.imageUrl)) continue;
            ChatMessage.Role messageRole = "user".equalsIgnoreCase(role)
                ? ChatMessage.Role.USER
                : ChatMessage.Role.ASSISTANT;
            mMessages.add(new ChatMessage(messageRole, parsed.text, false, parsed.imageUrl));
        }
        restoreQueuedMessagesForConversation(conversationId);
        mAdapter.notifyDataSetChanged();
        scrollToBottom();
    }

    private void syncConversationHistoryToCache(@Nullable String conversationId) {
        if (TextUtils.isEmpty(conversationId)) return;
        execSafe(() -> {
            try {
                List<Map<String, Object>> rows = new ArrayList<>(
                    ClawPhonesAPI.getMessages(ChatActivity.this, conversationId)
                );
                Collections.sort(rows, new Comparator<Map<String, Object>>() {
                    @Override
                    public int compare(Map<String, Object> a, Map<String, Object> b) {
                        return Long.compare(asLong(a.get("created_at")), asLong(b.get("created_at")));
                    }
                });
                if (mCache != null) {
                    mCache.upsertMessages(conversationId, rows);
                }
            } catch (Exception e) {
                CrashReporter.reportNonFatal(ChatActivity.this, e, "syncing_history_cache");
            }
        });
    }

    private void createConversation() {
        createConversation(null);
    }

    private void createConversation(@Nullable Runnable onReady) {
        if (mBusy) return;
        mBusy = true;
        setInputEnabled(false);

        final int idx = addAssistantMessage(getString(R.string.chat_status_connecting));

        execSafe(() -> {
            try {
                String id = ClawPhonesAPI.createConversation(ChatActivity.this);
                runSafe(() -> {
                    mConversationId = id;
                    if (mCache != null) {
                        long now = System.currentTimeMillis() / 1000L;
                        mCache.upsertConversation(new ClawPhonesAPI.ConversationSummary(
                            id,
                            safeTrim(getIntent().getStringExtra("title")),
                            now,
                            now,
                            0
                        ));
                    }
                    if (mMessageQueue != null) {
                        mMessageQueue.assignConversationIdForEmpty(id);
                    }
                    updateAssistantMessage(idx, getString(R.string.chat_status_connected_ready));
                    mBusy = false;
                    setInputEnabled(true);
                    tryFlushPendingMessages();
                    if (onReady != null) {
                        onReady.run();
                    }
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

        mLastUserText = text;
        mInput.setText("");

        if (canSendImmediately()) {
            int userIndex = addUserMessage(text);
            sendMessageOnline(mConversationId, text, null, null, userIndex);
            return;
        }

        queueMessageForLater(text, mConversationId);
        if (isNetworkConnected()) {
            if (TextUtils.isEmpty(mConversationId)) {
                createConversation();
            } else {
                tryFlushPendingMessages();
            }
        }
    }

    private boolean canSendImmediately() {
        return !TextUtils.isEmpty(mConversationId) && isNetworkConnected();
    }

    private void sendMessageOnline(
        @Nullable String conversationId,
        @NonNull String text,
        @Nullable String imageUrl,
        @Nullable Long queueId,
        @Nullable Integer userIndex
    ) {
        if (TextUtils.isEmpty(conversationId)) {
            if (queueId != null) {
                if (mMessageQueue != null) mMessageQueue.markPending(queueId);
                setQueuedMessageState(queueId, ChatMessage.DeliveryState.SENDING, 0);
                if (isNetworkConnected() && TextUtils.isEmpty(mConversationId)) {
                    createConversation();
                }
                return;
            }

            if (!TextUtils.isEmpty(imageUrl)) {
                if (isNetworkConnected() && TextUtils.isEmpty(mConversationId)) {
                    createConversation(() -> sendMessageOnline(mConversationId, text, imageUrl, null, userIndex));
                    return;
                }
                toast(getString(R.string.chat_image_send_failed));
                return;
            }

            if (userIndex == null) {
                queueMessageForLater(text, "");
            } else {
                queueExistingUserMessage(userIndex, text, "");
            }
            return;
        }

        mBusy = true;
        setInputEnabled(false);
        setSendingState(true);

        if (queueId != null) {
            int currentRetry = 0;
            Integer idx = mQueuedMessageIndexes.get(queueId);
            if (idx != null && idx >= 0 && idx < mMessages.size()) {
                currentRetry = mMessages.get(idx).retryCount;
            }
            setQueuedMessageState(queueId, ChatMessage.DeliveryState.SENDING, currentRetry);
        }

        final int assistantIndex = addAssistantMessage(getString(R.string.chat_status_thinking), true);
        final String targetConversationId = conversationId;

        execSafe(() -> {
            final StringBuilder accumulated = new StringBuilder();
            ClawPhonesAPI.chatStream(
                ChatActivity.this,
                targetConversationId,
                text,
                imageUrl,
                new ClawPhonesAPI.StreamCallback() {
                    @Override
                    public void onDelta(String delta) {
                        accumulated.append(delta);
                        final String current = accumulated.toString();
                        runSafe(() -> updateAssistantMessageThrottled(assistantIndex, current));
                    }

                    @Override
                    public void onComplete(String fullContent, String messageId) {
                        runSafe(() -> {
                            clearPendingUpdate();
                            String finalContent = fullContent;
                            if (TextUtils.isEmpty(finalContent)) {
                                finalContent = accumulated.toString();
                            }
                            updateAssistantMessage(assistantIndex, finalContent);
                            if (queueId != null) {
                                if (mMessageQueue != null) mMessageQueue.remove(queueId);
                                markQueuedMessageSent(queueId);
                            }
                            syncConversationHistoryToCache(targetConversationId);
                            finishSendingCycle();
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

                            if (queueId != null) {
                                removeMessageAt(assistantIndex);
                                handleQueuedSendFailure(queueId);
                                finishSendingCycle();
                                return;
                            }

                            if (isLikelyOffline(error) && TextUtils.isEmpty(imageUrl)) {
                                removeMessageAt(assistantIndex);
                                if (userIndex != null) {
                                    queueExistingUserMessage(userIndex, text, targetConversationId);
                                } else {
                                    queueMessageForLater(text, targetConversationId);
                                }
                                finishSendingCycle();
                                return;
                            }

                            String partial = accumulated.toString();
                            if (!partial.isEmpty()) {
                                updateAssistantMessage(
                                    assistantIndex,
                                    getString(R.string.chat_error_partial_interrupted, partial)
                                );
                            } else {
                                updateAssistantMessage(assistantIndex, getString(R.string.chat_error_send_failed));
                            }
                            finishSendingCycle();
                        });
                    }
                }
            );
        });
    }

    private void finishSendingCycle() {
        mBusy = false;
        setInputEnabled(true);
        setSendingState(false);
        tryFlushPendingMessages();
    }

    private void queueMessageForLater(@NonNull String text, @Nullable String conversationId) {
        if (mMessageQueue == null) return;
        long queueId = mMessageQueue.enqueue(text, conversationId);
        int index = addUserMessage(text, queueId, ChatMessage.DeliveryState.SENDING, 0);
        mQueuedMessageIndexes.put(queueId, index);
    }

    private void queueExistingUserMessage(int userIndex, @NonNull String text, @Nullable String conversationId) {
        if (mMessageQueue == null) return;
        long queueId = mMessageQueue.enqueue(text, conversationId);
        if (userIndex >= 0 && userIndex < mMessages.size()) {
            ChatMessage message = mMessages.get(userIndex);
            message.queueId = queueId;
            message.deliveryState = ChatMessage.DeliveryState.SENDING;
            message.retryCount = 0;
            mQueuedMessageIndexes.put(queueId, userIndex);
            mAdapter.notifyItemChanged(userIndex);
        }
    }

    private void handleQueuedSendFailure(long queueId) {
        if (mMessageQueue == null) return;
        int retryCount = mMessageQueue.incrementRetryCount(queueId);
        if (retryCount >= MAX_QUEUE_RETRY) {
            mMessageQueue.markFailed(queueId);
            setQueuedMessageState(queueId, ChatMessage.DeliveryState.FAILED, retryCount);
            return;
        }
        mMessageQueue.markPending(queueId);
        setQueuedMessageState(queueId, ChatMessage.DeliveryState.SENDING, retryCount);
        mMainHandler.postDelayed(this::tryFlushPendingMessages, 800L);
    }

    private void onRetryQueuedMessage(long queueId) {
        if (mMessageQueue == null) return;
        if (!isNetworkConnected()) {
            toast(getString(R.string.chat_queue_waiting_network));
            return;
        }
        mMessageQueue.resetForManualRetry(queueId);
        setQueuedMessageState(queueId, ChatMessage.DeliveryState.SENDING, 0);
        tryFlushPendingMessages();
    }

    private void tryFlushPendingMessages() {
        if (mDestroyed || mBusy || mMessageQueue == null) return;
        if (!isNetworkConnected()) return;

        MessageQueue.PendingMessage next =
            mMessageQueue.getNextPendingToSendForConversation(mConversationId);
        if (next == null) return;

        String targetConversationId = safeTrim(next.conversationId);
        if (TextUtils.isEmpty(targetConversationId)) {
            if (TextUtils.isEmpty(mConversationId)) {
                createConversation();
                return;
            }
            targetConversationId = mConversationId;
            mMessageQueue.updateConversationId(next.id, targetConversationId);
        }

        mMessageQueue.markSending(next.id);
        setQueuedMessageState(next.id, ChatMessage.DeliveryState.SENDING, next.retryCount);

        if (!mQueuedMessageIndexes.containsKey(next.id)) {
            int index = addUserMessage(next.message, next.id, ChatMessage.DeliveryState.SENDING, next.retryCount);
            mQueuedMessageIndexes.put(next.id, index);
        }
        sendMessageOnline(targetConversationId, next.message, null, next.id, mQueuedMessageIndexes.get(next.id));
    }

    private void restoreQueuedMessagesForConversation(@Nullable String conversationId) {
        if (mMessageQueue == null || TextUtils.isEmpty(conversationId)) return;
        List<MessageQueue.PendingMessage> queued = mMessageQueue.listQueuedForConversation(conversationId);
        for (MessageQueue.PendingMessage pending : queued) {
            appendOrUpdateQueuedMessage(pending);
        }
    }

    private void restoreQueuedMessagesWithoutConversation() {
        if (mMessageQueue == null) return;
        List<MessageQueue.PendingMessage> queued = mMessageQueue.listQueuedWithoutConversation();
        for (MessageQueue.PendingMessage pending : queued) {
            appendOrUpdateQueuedMessage(pending);
        }
    }

    private void appendOrUpdateQueuedMessage(@NonNull MessageQueue.PendingMessage pending) {
        ChatMessage.DeliveryState deliveryState = deliveryStateFromQueueStatus(pending.status);
        if (mQueuedMessageIndexes.containsKey(pending.id)) {
            setQueuedMessageState(pending.id, deliveryState, pending.retryCount);
            return;
        }
        int index = addUserMessage(pending.message, pending.id, deliveryState, pending.retryCount);
        mQueuedMessageIndexes.put(pending.id, index);
    }

    private ChatMessage.DeliveryState deliveryStateFromQueueStatus(@Nullable String status) {
        if (MessageQueue.STATUS_FAILED.equals(status)) {
            return ChatMessage.DeliveryState.FAILED;
        }
        return ChatMessage.DeliveryState.SENDING;
    }

    private void setQueuedMessageState(@Nullable Long queueId,
                                       @NonNull ChatMessage.DeliveryState deliveryState,
                                       int retryCount) {
        if (queueId == null) return;
        Integer index = mQueuedMessageIndexes.get(queueId);
        if (index == null || index < 0 || index >= mMessages.size()) return;
        ChatMessage message = mMessages.get(index);
        message.queueId = queueId;
        message.deliveryState = deliveryState;
        message.retryCount = Math.max(0, retryCount);
        mAdapter.notifyItemChanged(index);
    }

    private void markQueuedMessageSent(long queueId) {
        Integer index = mQueuedMessageIndexes.remove(queueId);
        if (index == null || index < 0 || index >= mMessages.size()) return;
        ChatMessage message = mMessages.get(index);
        message.queueId = -1L;
        message.deliveryState = ChatMessage.DeliveryState.NONE;
        message.retryCount = 0;
        mAdapter.notifyItemChanged(index);
    }

    private void removeMessageAt(int index) {
        if (index < 0 || index >= mMessages.size()) return;
        ChatMessage removed = mMessages.remove(index);
        if (removed.queueId > 0L) {
            mQueuedMessageIndexes.remove(removed.queueId);
        }

        ArrayList<Long> keys = new ArrayList<>(mQueuedMessageIndexes.keySet());
        for (Long key : keys) {
            Integer current = mQueuedMessageIndexes.get(key);
            if (current == null) continue;
            if (current > index) {
                mQueuedMessageIndexes.put(key, current - 1);
            }
        }
        mAdapter.notifyItemRemoved(index);
    }

    private void registerConnectivityReceiver() {
        if (mConnectivityReceiver != null) return;
        mConnectivityReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, Intent intent) {
                if (intent == null) return;
                if (!"android.net.conn.CONNECTIVITY_CHANGE".equals(intent.getAction())) return;
                if (isNetworkConnected()) {
                    tryFlushPendingMessages();
                }
            }
        };
        try {
            registerReceiver(mConnectivityReceiver, new IntentFilter("android.net.conn.CONNECTIVITY_CHANGE"));
        } catch (Exception ignored) {
        }
    }

    private void unregisterConnectivityReceiver() {
        if (mConnectivityReceiver == null) return;
        try {
            unregisterReceiver(mConnectivityReceiver);
        } catch (Exception ignored) {
        }
        mConnectivityReceiver = null;
    }

    private boolean isNetworkConnected() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null) return false;
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception ignored) {
            return true;
        }
    }

    private boolean isLikelyOffline(@NonNull Throwable error) {
        if (!isNetworkConnected()) return true;
        Throwable root = rootCause(error);
        return root instanceof java.net.UnknownHostException
            || root instanceof java.net.ConnectException
            || root instanceof java.net.NoRouteToHostException
            || root instanceof java.net.SocketTimeoutException
            || root instanceof java.io.InterruptedIOException;
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
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
        return addUserMessage(text, -1L, ChatMessage.DeliveryState.NONE, 0);
    }

    private int addUserMessage(String text, long queueId,
                               @NonNull ChatMessage.DeliveryState deliveryState,
                               int retryCount) {
        int idx = mMessages.size();
        ChatMessage message = new ChatMessage(ChatMessage.Role.USER, text, false);
        message.queueId = queueId;
        message.deliveryState = deliveryState;
        message.retryCount = Math.max(0, retryCount);
        mMessages.add(message);
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
        enum DeliveryState { NONE, SENDING, FAILED }

        final Role role;
        String text;
        boolean isThinking;
        long queueId = -1L;
        DeliveryState deliveryState = DeliveryState.NONE;
        int retryCount = 0;

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
        private final RetryClickListener retryClickListener;

        interface RetryClickListener {
            void onRetry(long queueId);
        }

        ChatAdapter(ArrayList<ChatMessage> messages, @Nullable RetryClickListener retryClickListener) {
            this.messages = messages;
            this.retryClickListener = retryClickListener;
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
            holder.bind(m, isUser, isThinking, retryClickListener);
        }

        @Override
        public int getItemCount() {
            return messages.size();
        }

        static final class VH extends RecyclerView.ViewHolder {
            final TextView text;
            final View bubble;
            final TextView statusText;
            final TextView retryButton;

            VH(@NonNull View itemView) {
                super(itemView);
                text = itemView.findViewById(R.id.message_text);
                bubble = itemView.findViewById(R.id.message_bubble);
                statusText = itemView.findViewById(R.id.message_status);
                retryButton = itemView.findViewById(R.id.message_retry);
                if (text != null) {
                    text.setMovementMethod(LinkMovementMethod.getInstance());
                }
            }

            void bind(ChatMessage message, boolean isUser, boolean isThinking,
                      @Nullable RetryClickListener retryClickListener) {
                if (text != null) {
                    if (isThinking) {
                        text.setText(message.text);
                        text.setTypeface(null, Typeface.ITALIC);
                        text.setTextColor(0xFF888888);
                    } else {
                        text.setText(renderMarkdown(message.text));
                        text.setTypeface(null, Typeface.NORMAL);
                        if (isUser) {
                            if (message.deliveryState == ChatMessage.DeliveryState.SENDING) {
                                text.setTextColor(0xFFB8B8B8);
                            } else {
                                text.setTextColor(Color.WHITE);
                            }
                        } else {
                            text.setTextColor(0xFFF5F0E6);
                        }
                    }
                }
                if (statusText != null) {
                    if (isUser && message.deliveryState == ChatMessage.DeliveryState.SENDING) {
                        statusText.setVisibility(View.VISIBLE);
                        statusText.setText(itemView.getContext().getString(R.string.chat_queue_sending));
                        statusText.setTextColor(0xFF8E8E8E);
                    } else if (isUser && message.deliveryState == ChatMessage.DeliveryState.FAILED) {
                        statusText.setVisibility(View.VISIBLE);
                        statusText.setText(itemView.getContext().getString(R.string.chat_queue_failed));
                        statusText.setTextColor(0xFFE57373);
                    } else {
                        statusText.setVisibility(View.GONE);
                    }
                }
                if (retryButton != null) {
                    boolean showRetry = isUser
                        && message.deliveryState == ChatMessage.DeliveryState.FAILED
                        && message.queueId > 0L
                        && retryClickListener != null;
                    retryButton.setVisibility(showRetry ? View.VISIBLE : View.GONE);
                    retryButton.setOnClickListener(showRetry
                        ? v -> retryClickListener.onRetry(message.queueId)
                        : null);
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

        private static final int MAX_MARKDOWN_LENGTH = 50_000;
        private static final Pattern BLOCK_MATH_PATTERN = Pattern.compile("(?s)\\$\\$\\s*(.+?)\\s*\\$\\$");
        private static final Pattern INLINE_MATH_PATTERN = Pattern.compile("(?<!\\$)\\$([^$\\n]{1,500})\\$(?!\\$)");
        private static final Pattern TABLE_SEPARATOR_PATTERN = Pattern.compile("^\\s*\\|?(\\s*:?-{3,}:?\\s*\\|)+\\s*:?-{3,}:?\\s*\\|?\\s*$");
        private static final Pattern THEMATIC_BREAK_PATTERN = Pattern.compile("^\\s*([-*_]\\s*){3,}$");
        private static final Pattern ORDERED_LIST_PATTERN = Pattern.compile("^(\\s*)(\\d+)\\.\\s+(.+)$");
        private static final Pattern UNORDERED_LIST_PATTERN = Pattern.compile("^(\\s*)[-*+]\\s+(.+)$");

        private static CharSequence renderMarkdown(String markdown) {
            String source = markdown == null ? "" : markdown.replace("\r\n", "\n");
            if (source.length() > MAX_MARKDOWN_LENGTH) {
                source = source.substring(0, MAX_MARKDOWN_LENGTH) + "…";
            }

            try {
                String normalized = preprocessTables(source);
                normalized = normalizeMath(normalized);

                String html = TextUtils.htmlEncode(normalized);
                html = transformQuoteBlocks(html);
                html = transformListLines(html);
                html = html.replaceAll("```([^`]{0,20000})```", "<pre>$1</pre>");
                html = html.replaceAll("`([^`]{1,1000})`", "<tt><b>$1</b></tt>");
                html = html.replaceAll("\\*\\*([^*]{1,1000})\\*\\*", "<b>$1</b>");
                html = html.replaceAll("(?<!\\*)\\*([^*]{1,1000})\\*(?!\\*)", "<i>$1</i>");
                html = html.replaceAll("\\[([^\\]]{1,400})\\]\\((https?://[^\\)]{1,2000})\\)", "<a href=\"$2\">$1</a>");
                html = html.replaceAll("(?m)^\\s*([-*_]\\s*){3,}$", "<hr/>");
                html = html.replace("\n", "<br/>");

                Spanned spanned = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY);
                return applyQuoteBackground(spanned);
            } catch (Exception e) {
                return source;
            }
        }

        private static CharSequence applyQuoteBackground(Spanned spanned) {
            if (!(spanned instanceof Spannable)) {
                return spanned;
            }

            Spannable spannable = (Spannable) spanned;
            QuoteSpan[] quoteSpans = spannable.getSpans(0, spannable.length(), QuoteSpan.class);
            for (QuoteSpan span : quoteSpans) {
                int start = spannable.getSpanStart(span);
                int end = spannable.getSpanEnd(span);
                int flags = spannable.getSpanFlags(span);
                spannable.removeSpan(span);
                spannable.setSpan(new QuoteSpan(0xFF8E8E8E), start, end, flags);
                spannable.setSpan(new BackgroundColorSpan(0x22FFFFFF), start, end, flags);
            }

            return spannable;
        }

        private static String normalizeMath(String markdown) {
            Matcher blockMatcher = BLOCK_MATH_PATTERN.matcher(markdown);
            StringBuffer blockBuffer = new StringBuffer();
            while (blockMatcher.find()) {
                String expression = blockMatcher.group(1) == null ? "" : blockMatcher.group(1).trim();
                String replacement = "```math\n" + expression + "\n```";
                blockMatcher.appendReplacement(blockBuffer, Matcher.quoteReplacement(replacement));
            }
            blockMatcher.appendTail(blockBuffer);

            Matcher inlineMatcher = INLINE_MATH_PATTERN.matcher(blockBuffer.toString());
            StringBuffer inlineBuffer = new StringBuffer();
            while (inlineMatcher.find()) {
                String expression = inlineMatcher.group(1) == null ? "" : inlineMatcher.group(1).trim();
                String replacement = expression.isEmpty() ? inlineMatcher.group(0) : "`" + expression + "`";
                inlineMatcher.appendReplacement(inlineBuffer, Matcher.quoteReplacement(replacement));
            }
            inlineMatcher.appendTail(inlineBuffer);
            return inlineBuffer.toString();
        }

        private static String transformQuoteBlocks(String encodedMarkdown) {
            String[] lines = encodedMarkdown.split("\n", -1);
            StringBuilder output = new StringBuilder(encodedMarkdown.length() + 64);
            int index = 0;

            while (index < lines.length) {
                String line = lines[index];
                String trimmed = line.trim();
                if (trimmed.startsWith("&gt;")) {
                    StringBuilder quoteBody = new StringBuilder();
                    while (index < lines.length) {
                        String quoteLine = lines[index].trim();
                        if (!quoteLine.startsWith("&gt;")) break;

                        String content = quoteLine.substring(4);
                        if (content.startsWith(" ")) content = content.substring(1);
                        if (quoteBody.length() > 0) quoteBody.append("<br/>");
                        quoteBody.append(content);
                        index++;
                    }
                    output.append("<blockquote>").append(quoteBody).append("</blockquote>");
                    if (index < lines.length) output.append("\n");
                    continue;
                }

                output.append(line);
                if (index < lines.length - 1) output.append("\n");
                index++;
            }

            return output.toString();
        }

        private static String transformListLines(String encodedMarkdown) {
            String[] lines = encodedMarkdown.split("\n", -1);
            StringBuilder output = new StringBuilder(encodedMarkdown.length() + 64);

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                Matcher ordered = ORDERED_LIST_PATTERN.matcher(line);
                Matcher unordered = UNORDERED_LIST_PATTERN.matcher(line);

                if (ordered.matches()) {
                    output.append(renderIndentedListPrefix(ordered.group(1)));
                    output.append(ordered.group(2)).append(". ").append(ordered.group(3));
                } else if (unordered.matches()) {
                    output.append(renderIndentedListPrefix(unordered.group(1)));
                    output.append("• ").append(unordered.group(2));
                } else if (THEMATIC_BREAK_PATTERN.matcher(line.trim()).matches()) {
                    output.append(line.trim());
                } else {
                    output.append(line);
                }

                if (i < lines.length - 1) output.append("\n");
            }

            return output.toString();
        }

        private static String renderIndentedListPrefix(String spaces) {
            int indent = spaces == null ? 0 : spaces.length() / 2;
            if (indent <= 0) return "";

            StringBuilder prefix = new StringBuilder(indent * 24);
            for (int i = 0; i < indent; i++) {
                prefix.append("&nbsp;&nbsp;&nbsp;&nbsp;");
            }
            return prefix.toString();
        }

        private static boolean containsMarkdownTable(String markdown) {
            if (markdown == null || markdown.isEmpty()) return false;
            String[] lines = markdown.replace("\r\n", "\n").split("\n");
            for (int i = 0; i + 1 < lines.length; i++) {
                if (isLikelyTableRow(lines[i]) && isTableSeparator(lines[i + 1])) {
                    return true;
                }
            }
            return false;
        }

        private static String preprocessTables(String markdown) {
            String[] lines = markdown.split("\n", -1);
            StringBuilder output = new StringBuilder(markdown.length() + 64);
            int index = 0;

            while (index < lines.length) {
                if (index + 1 < lines.length && isLikelyTableRow(lines[index]) && isTableSeparator(lines[index + 1])) {
                    ArrayList<String> tableLines = new ArrayList<>();
                    tableLines.add(lines[index]);
                    tableLines.add(lines[index + 1]);
                    index += 2;
                    while (index < lines.length && isLikelyTableRow(lines[index])) {
                        tableLines.add(lines[index]);
                        index++;
                    }

                    String table = renderAlignedTable(tableLines);
                    output.append("```table\n").append(table).append("\n```");
                    if (index < lines.length) output.append("\n");
                    continue;
                }

                output.append(lines[index]);
                if (index < lines.length - 1) output.append("\n");
                index++;
            }

            return output.toString();
        }

        private static boolean isLikelyTableRow(String line) {
            return line != null && line.contains("|");
        }

        private static boolean isTableSeparator(String line) {
            if (line == null) return false;
            return TABLE_SEPARATOR_PATTERN.matcher(line).matches();
        }

        private static String renderAlignedTable(List<String> markdownTableLines) {
            ArrayList<List<String>> rows = new ArrayList<>();
            for (int i = 0; i < markdownTableLines.size(); i++) {
                if (i == 1 && isTableSeparator(markdownTableLines.get(i))) continue;
                rows.add(parseTableCells(markdownTableLines.get(i)));
            }
            if (rows.isEmpty()) return "";

            int columnCount = 0;
            for (List<String> row : rows) {
                columnCount = Math.max(columnCount, row.size());
            }
            if (columnCount == 0) return "";

            int[] widths = new int[columnCount];
            for (int i = 0; i < columnCount; i++) widths[i] = 3;
            for (List<String> row : rows) {
                for (int col = 0; col < columnCount; col++) {
                    String value = col < row.size() ? row.get(col) : "";
                    widths[col] = Math.max(widths[col], value.length());
                }
            }

            StringBuilder out = new StringBuilder();
            out.append(renderTableRow(rows.get(0), widths)).append("\n");
            out.append(renderTableSeparator(widths));
            for (int i = 1; i < rows.size(); i++) {
                out.append("\n").append(renderTableRow(rows.get(i), widths));
            }
            return out.toString();
        }

        private static List<String> parseTableCells(String line) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
            if (trimmed.endsWith("|")) trimmed = trimmed.substring(0, trimmed.length() - 1);

            String[] parts = trimmed.split("\\|", -1);
            ArrayList<String> cells = new ArrayList<>(parts.length);
            for (String part : parts) {
                cells.add(part.trim());
            }
            return cells;
        }

        private static String renderTableRow(List<String> row, int[] widths) {
            StringBuilder line = new StringBuilder();
            for (int col = 0; col < widths.length; col++) {
                String value = col < row.size() ? row.get(col) : "";
                line.append("| ").append(padRight(value, widths[col])).append(" ");
            }
            line.append("|");
            return line.toString();
        }

        private static String renderTableSeparator(int[] widths) {
            StringBuilder line = new StringBuilder();
            for (int width : widths) {
                int span = Math.max(3, width);
                line.append("| ").append(repeat("-", span)).append(" ");
            }
            line.append("|");
            return line.toString();
        }

        private static String padRight(String value, int width) {
            if (value == null) value = "";
            if (value.length() >= width) return value;
            return value + repeat(" ", width - value.length());
        }

        private static String repeat(String text, int count) {
            StringBuilder builder = new StringBuilder(Math.max(0, count) * text.length());
            for (int i = 0; i < count; i++) {
                builder.append(text);
            }
            return builder.toString();
        }
    }
}
