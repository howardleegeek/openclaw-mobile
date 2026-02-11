package ai.clawphones.agent.chat;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Basic in-app AI chat UI backed by ClawPhones API.
 */
public class ChatActivity extends Activity {

    private RecyclerView mRecycler;
    private EditText mInput;
    private ImageButton mSend;

    private final ArrayList<ChatMessage> mMessages = new ArrayList<>();
    private ChatAdapter mAdapter;

    private ExecutorService mExecutor;
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

        mRecycler = findViewById(R.id.messages_recycler);
        mInput = findViewById(R.id.message_input);
        mSend = findViewById(R.id.message_send);

        mAdapter = new ChatAdapter(mMessages);
        mRecycler.setLayoutManager(new LinearLayoutManager(this));
        mRecycler.setAdapter(mAdapter);

        mExecutor = Executors.newSingleThreadExecutor();

        addAssistantMessage("Hi! 你可以直接开始聊天。");
        createConversation();

        mSend.setOnClickListener(v -> onSend());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mExecutor != null) {
            try {
                mExecutor.shutdownNow();
            } catch (Exception ignored) {}
            mExecutor = null;
        }
    }

    private void createConversation() {
        if (mBusy) return;
        mBusy = true;
        setInputEnabled(false);

        final int idx = addAssistantMessage("正在创建对话…");

        mExecutor.execute(() -> {
            try {
                String id = ClawPhonesAPI.createConversation(mToken);
                runOnUiThread(() -> {
                    mConversationId = id;
                    updateAssistantMessage(idx, "对话已创建，可以开始提问。");
                    mBusy = false;
                    setInputEnabled(true);
                });
            } catch (IOException | JSONException e) {
                runOnUiThread(() -> {
                    updateAssistantMessage(idx, "创建对话失败: " + e.getMessage());
                    mBusy = false;
                    setInputEnabled(true);
                });
            } catch (ClawPhonesAPI.ApiException e) {
                runOnUiThread(() -> {
                    if (e.statusCode == 401) {
                        ClawPhonesAPI.clearToken(ChatActivity.this);
                        redirectToLogin("登录已过期，请重新登录");
                        return;
                    }
                    updateAssistantMessage(idx, "创建对话失败: " + safeErr(e));
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

        mInput.setText("");
        addUserMessage(text);

        mBusy = true;
        setInputEnabled(false);

        final int idx = addAssistantMessage("…");

        mExecutor.execute(() -> {
            try {
                String reply = ClawPhonesAPI.chat(mToken, mConversationId, text);
                runOnUiThread(() -> {
                    updateAssistantMessage(idx, reply);
                    mBusy = false;
                    setInputEnabled(true);
                });
            } catch (IOException | JSONException e) {
                runOnUiThread(() -> {
                    updateAssistantMessage(idx, "请求失败: " + e.getMessage());
                    mBusy = false;
                    setInputEnabled(true);
                });
            } catch (ClawPhonesAPI.ApiException e) {
                runOnUiThread(() -> {
                    if (e.statusCode == 401) {
                        ClawPhonesAPI.clearToken(ChatActivity.this);
                        redirectToLogin("登录已过期，请重新登录");
                        return;
                    }
                    updateAssistantMessage(idx, "请求失败: " + safeErr(e));
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
        mInput.setEnabled(enabled);
        mSend.setEnabled(enabled);
        float alpha = enabled ? 1.0f : 0.6f;
        mInput.setAlpha(alpha);
        mSend.setAlpha(alpha);
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
        mRecycler.post(() -> {
            if (mAdapter.getItemCount() > 0) {
                mRecycler.scrollToPosition(mAdapter.getItemCount() - 1);
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

    private static String safeErr(ClawPhonesAPI.ApiException e) {
        String msg = e.getMessage();
        if (msg == null || msg.trim().isEmpty()) msg = "HTTP " + e.statusCode;
        if (msg.length() > 400) msg = msg.substring(0, 400) + "…";
        return msg;
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
            View v = android.view.LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message, parent, false);
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
                text.setMovementMethod(LinkMovementMethod.getInstance());
            }

            void bind(String markdown, boolean isUser) {
                text.setText(renderMarkdown(markdown));
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

        private static CharSequence renderMarkdown(String markdown) {
            if (markdown == null) markdown = "";
            // Simple Markdown -> HTML conversion (bold/italic/code/links/newlines).
            String html = TextUtils.htmlEncode(markdown);
            html = html.replaceAll("`([^`]+)`", "<tt>$1</tt>");
            html = html.replaceAll("\\*\\*([^*]+)\\*\\*", "<b>$1</b>");
            html = html.replaceAll("(?<!\\*)\\*([^*]+)\\*(?!\\*)", "<i>$1</i>");
            html = html.replaceAll("\\[([^\\]]+)\\]\\((https?://[^\\)]+)\\)", "<a href=\"$2\">$1</a>");
            html = html.replace("\n", "<br/>");
            return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY);
        }
    }
}
