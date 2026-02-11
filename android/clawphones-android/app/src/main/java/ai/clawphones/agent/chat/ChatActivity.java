package ai.clawphones.agent.chat;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-app AI chat UI backed by ClawPhones API.
 *
 * Handles: conversation creation, message send/receive, 401 auto-logout,
 * Markdown rendering, and graceful lifecycle management.
 */
public class ChatActivity extends AppCompatActivity {

    private RecyclerView mRecycler;
    private EditText mInput;
    private ImageButton mSend;

    private final ArrayList<ChatMessage> mMessages = new ArrayList<>();
    private ChatAdapter mAdapter;

    private ExecutorService mExecutor;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean mDestroyed = false;
    private boolean mBusy = false;

    private String mToken;
    private String mConversationId;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mToken = ClawPhonesAPI.getToken(this);
        if (TextUtils.isEmpty(mToken)) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_chat);

        // Setup toolbar with title and back navigation
        Toolbar toolbar = findViewById(R.id.chat_toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            toolbar.setTitle("ClawPhones AI");
            toolbar.setSubtitle("\u667a\u80fd\u52a9\u624b");
            toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert);
            toolbar.setNavigationOnClickListener(v -> finish());
        }

        mRecycler = findViewById(R.id.messages_recycler);
        mInput = findViewById(R.id.message_input);
        mSend = findViewById(R.id.message_send);

        mAdapter = new ChatAdapter(mMessages);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        mRecycler.setLayoutManager(lm);
        mRecycler.setAdapter(mAdapter);

        mExecutor = Executors.newSingleThreadExecutor();

        addAssistantMessage("\u4f60\u597d\uff01\u6709\u4ec0\u4e48\u6211\u53ef\u4ee5\u5e2e\u4f60\u7684\u5417\uff1f");
        createConversation();

        mSend.setOnClickListener(v -> onSend());

        // Also send on keyboard Enter
        mInput.setOnEditorActionListener((v, actionId, event) -> {
            onSend();
            return true;
        });
    }

    @Override
    protected void onDestroy() {
        // CRITICAL: Set destroyed flag and shutdown executor BEFORE super.onDestroy()
        // to prevent background threads from posting to a destroyed activity.
        mDestroyed = true;
        mMainHandler.removeCallbacksAndMessages(null);
        if (mExecutor != null) {
            try {
                mExecutor.shutdownNow();
            } catch (Exception ignored) {}
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
                // Executor was shut down between null-check and execute()
            }
        }
    }

    private void createConversation() {
        if (mBusy) return;
        mBusy = true;
        setInputEnabled(false);

        final int idx = addAssistantMessage("\u6b63\u5728\u8fde\u63a5\u2026");

        execSafe(() -> {
            try {
                String id = ClawPhonesAPI.createConversation(mToken);
                runSafe(() -> {
                    mConversationId = id;
                    updateAssistantMessage(idx, "\u5df2\u8fde\u63a5\uff0c\u53ef\u4ee5\u5f00\u59cb\u63d0\u95ee\u3002");
                    mBusy = false;
                    setInputEnabled(true);
                });
            } catch (IOException e) {
                runSafe(() -> {
                    updateAssistantMessage(idx, "\u7f51\u7edc\u9519\u8bef: " + safeMsg(e));
                    mBusy = false;
                    setInputEnabled(true);
                });
            } catch (JSONException e) {
                runSafe(() -> {
                    updateAssistantMessage(idx, "\u6570\u636e\u89e3\u6790\u9519\u8bef");
                    mBusy = false;
                    setInputEnabled(true);
                });
            } catch (ClawPhonesAPI.ApiException e) {
                runSafe(() -> {
                    if (e.statusCode == 401) {
                        ClawPhonesAPI.clearToken(ChatActivity.this);
                        redirectToLogin("\u767b\u5f55\u5df2\u8fc7\u671f\uff0c\u8bf7\u91cd\u65b0\u767b\u5f55");
                        return;
                    }
                    updateAssistantMessage(idx, "\u8fde\u63a5\u5931\u8d25: " + safeErr(e));
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
            toast("\u6b63\u5728\u521d\u59cb\u5316\u5bf9\u8bdd\uff0c\u8bf7\u7a0d\u7b49\u2026");
            return;
        }

        mInput.setText("");
        addUserMessage(text);

        mBusy = true;
        setInputEnabled(false);

        final int idx = addAssistantMessage("\u601d\u8003\u4e2d\u2026");

        execSafe(() -> {
            try {
                String reply = ClawPhonesAPI.chat(mToken, mConversationId, text);
                runSafe(() -> {
                    updateAssistantMessage(idx, reply);
                    mBusy = false;
                    setInputEnabled(true);
                });
            } catch (IOException e) {
                runSafe(() -> {
                    updateAssistantMessage(idx, "\u7f51\u7edc\u9519\u8bef: " + safeMsg(e));
                    mBusy = false;
                    setInputEnabled(true);
                });
            } catch (JSONException e) {
                runSafe(() -> {
                    updateAssistantMessage(idx, "\u6570\u636e\u89e3\u6790\u9519\u8bef");
                    mBusy = false;
                    setInputEnabled(true);
                });
            } catch (ClawPhonesAPI.ApiException e) {
                runSafe(() -> {
                    if (e.statusCode == 401) {
                        ClawPhonesAPI.clearToken(ChatActivity.this);
                        redirectToLogin("\u767b\u5f55\u5df2\u8fc7\u671f\uff0c\u8bf7\u91cd\u65b0\u767b\u5f55");
                        return;
                    }
                    updateAssistantMessage(idx, "\u8bf7\u6c42\u5931\u8d25: " + safeErr(e));
                    mBusy = false;
                    setInputEnabled(true);
                });
            }
        });
    }

    private void redirectToLogin(String message) {
        toast(message);
        startActivity(new Intent(this, LoginActivity.class));
        finish();
    }

    private void setInputEnabled(boolean enabled) {
        if (mInput != null) {
            mInput.setEnabled(enabled);
            mInput.setAlpha(enabled ? 1.0f : 0.6f);
        }
        if (mSend != null) {
            mSend.setEnabled(enabled);
            mSend.setAlpha(enabled ? 1.0f : 0.6f);
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
        if (msg == null || msg.trim().isEmpty()) return "\u672a\u77e5\u9519\u8bef";
        if (msg.length() > 200) msg = msg.substring(0, 200) + "\u2026";
        return msg;
    }

    private static String safeErr(ClawPhonesAPI.ApiException e) {
        String msg = e.getMessage();
        if (msg == null || msg.trim().isEmpty()) return "HTTP " + e.statusCode;
        // Try to extract "detail" from JSON error body
        try {
            org.json.JSONObject errJson = new org.json.JSONObject(msg);
            String detail = errJson.optString("detail", null);
            if (detail != null && !detail.trim().isEmpty()) return detail;
        } catch (Exception ignored) {}
        if (msg.length() > 200) msg = msg.substring(0, 200) + "\u2026";
        return msg;
    }

    // ── Data model ─────────────────────────────────────────────────────────────

    static final class ChatMessage {
        enum Role { USER, ASSISTANT }

        final Role role;
        String text;

        ChatMessage(Role role, String text) {
            this.role = role;
            this.text = text;
        }
    }

    // ── RecyclerView Adapter ───────────────────────────────────────────────────

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
            holder.bind(m.text, isUser);
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

            void bind(String markdown, boolean isUser) {
                if (text != null) {
                    text.setText(renderMarkdown(markdown));
                    // White text on user blue bubble, cream on assistant dark bubble
                    text.setTextColor(isUser ? Color.WHITE : 0xFFF5F0E6);
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
            // Limit input length to prevent regex DoS
            if (markdown.length() > 50_000) {
                markdown = markdown.substring(0, 50_000) + "\u2026";
            }
            try {
                String html = TextUtils.htmlEncode(markdown);
                // Code blocks (triple backtick -> <pre>) — bounded: max 10K chars inside
                html = html.replaceAll("```([^`]{0,10000})```", "<pre>$1</pre>");
                // Inline code — bounded: max 500 chars inside
                html = html.replaceAll("`([^`]{1,500})`", "<tt><b>$1</b></tt>");
                // Bold — bounded: max 500 chars inside
                html = html.replaceAll("\\*\\*([^*]{1,500})\\*\\*", "<b>$1</b>");
                // Italic — bounded: max 500 chars inside
                html = html.replaceAll("(?<!\\*)\\*([^*]{1,500})\\*(?!\\*)", "<i>$1</i>");
                // Links — bounded: max 200 chars each part
                html = html.replaceAll("\\[([^\\]]{1,200})\\]\\((https?://[^\\)]{1,500})\\)", "<a href=\"$2\">$1</a>");
                // Bullet points (- item)
                html = html.replaceAll("(?m)^- (.+)", "\u2022 $1");
                // Newlines
                html = html.replace("\n", "<br/>");
                return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY);
            } catch (Exception e) {
                // Fallback: plain text if rendering fails
                return markdown;
            }
        }
    }
}
