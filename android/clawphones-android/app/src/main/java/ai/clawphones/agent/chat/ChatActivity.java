package ai.clawphones.agent.chat;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-app AI chat UI backed by ClawPhones API.
 *
 * Handles: conversation create/resume, message send/receive, 401 auto-logout,
 * Markdown rendering, and graceful lifecycle management.
 */
public class ChatActivity extends AppCompatActivity {

    private RecyclerView mRecycler;
    private EditText mInput;
    private ImageButton mSend;
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

    private String mToken;
    private String mConversationId;
    private String mLastUserText = "";

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
            toolbar.setTitle(TextUtils.isEmpty(title) ? "新对话" : title);
            toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert);
            toolbar.setNavigationOnClickListener(v -> finish());

            MenuItem logoutItem = toolbar.getMenu().add("退出登录");
            logoutItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
            toolbar.setOnMenuItemClickListener(item -> {
                confirmLogout();
                return true;
            });
        }

        mRecycler = findViewById(R.id.messages_recycler);
        mInput = findViewById(R.id.message_input);
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
            addAssistantMessage("你好！有什么我可以帮你的吗？");
            createConversation();
        }

        mSend.setOnClickListener(v -> onSend());

        mInput.setOnEditorActionListener((v, actionId, event) -> {
            onSend();
            return true;
        });
    }

    @Override
    protected void onDestroy() {
        mDestroyed = true;
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
                    ClawPhonesAPI.getMessages(mToken, conversationId));

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
                        mMessages.add(new ChatMessage(messageRole, content));
                    }
                    mAdapter.notifyDataSetChanged();
                    scrollToBottom();
                    mBusy = false;
                    setInputEnabled(true);
                });
            } catch (ClawPhonesAPI.ApiException e) {
                runSafe(() -> {
                    if (e.statusCode == 401) {
                        ClawPhonesAPI.clearToken(ChatActivity.this);
                        redirectToLogin("登录已过期，请重新登录");
                        return;
                    }
                    toast("加载历史失败");
                    mBusy = false;
                    setInputEnabled(true);
                });
            } catch (IOException | JSONException e) {
                runSafe(() -> {
                    toast("加载历史失败");
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

        final int idx = addAssistantMessage("正在连接…");

        execSafe(() -> {
            try {
                String id = ClawPhonesAPI.createConversation(mToken);
                runSafe(() -> {
                    mConversationId = id;
                    updateAssistantMessage(idx, "已连接，可以开始提问。");
                    mBusy = false;
                    setInputEnabled(true);
                });
            } catch (IOException e) {
                runSafe(() -> {
                    updateAssistantMessage(idx, "网络错误: " + safeMsg(e));
                    mBusy = false;
                    setInputEnabled(true);
                });
            } catch (JSONException e) {
                runSafe(() -> {
                    updateAssistantMessage(idx, "数据解析错误");
                    mBusy = false;
                    setInputEnabled(true);
                });
            } catch (ClawPhonesAPI.ApiException e) {
                runSafe(() -> {
                    if (e.statusCode == 401) {
                        ClawPhonesAPI.clearToken(ChatActivity.this);
                        redirectToLogin("登录已过期，请重新登录");
                        return;
                    }
                    updateAssistantMessage(idx, "连接失败: " + safeErr(e));
                    mBusy = false;
                    setInputEnabled(true);
                });
            }
        });
    }

    private void onSend() {
        if (mBusy) return;

        String text = safeTrim(mInput.getText().toString());
        if (TextUtils.isEmpty(text)) return;

        if (TextUtils.isEmpty(mConversationId)) {
            toast("正在初始化对话，请稍等…");
            return;
        }

        mLastUserText = text;
        mInput.setText("");
        addUserMessage(text);

        mBusy = true;
        setInputEnabled(false);
        setSendingState(true);

        final int idx = addAssistantMessage("思考中...");

        execSafe(() -> {
            final StringBuilder accumulated = new StringBuilder();
            ClawPhonesAPI.chatStream(mToken, mConversationId, text, new ClawPhonesAPI.StreamCallback() {
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
                    runSafe(() -> {
                        clearPendingUpdate();
                        if (error instanceof ClawPhonesAPI.ApiException
                            && ((ClawPhonesAPI.ApiException) error).statusCode == 401) {
                            ClawPhonesAPI.clearToken(ChatActivity.this);
                            redirectToLogin("登录已过期，请重新登录");
                            return;
                        }

                        String partial = accumulated.toString();
                        if (!partial.isEmpty()) {
                            updateAssistantMessage(idx, partial + "\n\n⚠️ 连接中断");
                        } else {
                            updateAssistantMessage(idx, "⚠️ 发送失败");
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
            .setMessage("确定退出登录吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("退出", (d, w) -> {
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
        if (mSend != null) {
            mSend.setEnabled(enabled);
            mSend.setAlpha(enabled ? 1.0f : 0.5f);
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

    private int addUserMessage(String text) {
        int idx = mMessages.size();
        mMessages.add(new ChatMessage(ChatMessage.Role.USER, text));
        mAdapter.notifyItemInserted(idx);
        scrollToBottom();
        return idx;
    }

    private int addAssistantMessage(String text) {
        int idx = mMessages.size();
        mMessages.add(new ChatMessage(ChatMessage.Role.ASSISTANT, text));
        mAdapter.notifyItemInserted(idx);
        scrollToBottom();
        return idx;
    }

    private void updateAssistantMessage(int index, String text) {
        if (index < 0 || index >= mMessages.size()) return;
        ChatMessage m = mMessages.get(index);
        m.text = text;
        mAdapter.notifyItemChanged(index);
        scrollToBottom();
    }

    private void updateAssistantMessageThrottled(int index, String text) {
        if (index < 0 || index >= mMessages.size()) return;
        ChatMessage m = mMessages.get(index);
        m.text = text;

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

    private static String safeMsg(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.trim().isEmpty()) return "未知错误";
        if (msg.length() > 200) msg = msg.substring(0, 200) + "…";
        return msg;
    }

    private static String safeErr(ClawPhonesAPI.ApiException e) {
        String msg = e.getMessage();
        if (msg == null || msg.trim().isEmpty()) return "HTTP " + e.statusCode;
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

        ChatMessage(Role role, String text) {
            this.role = role;
            this.text = text;
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
            boolean isThinking = !isUser && "思考中...".equals(m.text);
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
                        text.setText("思考中...");
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
