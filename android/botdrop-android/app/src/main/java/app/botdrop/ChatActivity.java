package app.botdrop;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;
import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;

/**
 * Minimal in-app chat UI (text + voice) for preinstalled devices.
 * For non-technical users: one screen, one big mic, no setup.
 */
public class ChatActivity extends Activity {

    private static final String LOG_TAG = "ChatActivity";
    private static final int REQ_VOICE = 2001;
    private static final int REQ_RECORD_AUDIO = 2002;

    private static final String PREFS = "botdrop_chat";
    private static final String PREF_SESSION_ID = "session_id";

    private RecyclerView mRecycler;
    private EditText mInput;
    private ImageButton mSend;
    private ImageButton mMic;

    private final ArrayList<ChatMessage> mMessages = new ArrayList<>();
    private ChatAdapter mAdapter;

    private BotDropService mService;
    private boolean mBound = false;
    private boolean mBusy = false;

    private String mSessionId;

    private TextToSpeech mTts;
    private boolean mSpeakReplies = true;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BotDropService.LocalBinder binder = (BotDropService.LocalBinder) service;
            mService = binder.getService();
            mBound = true;
            Logger.logDebug(LOG_TAG, "Service connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
            mBound = false;
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_botdrop_chat);

        mRecycler = findViewById(R.id.chat_recycler);
        mInput = findViewById(R.id.chat_input);
        mSend = findViewById(R.id.chat_send);
        mMic = findViewById(R.id.chat_mic);

        mSessionId = getOrCreateSessionId();

        mAdapter = new ChatAdapter(mMessages);
        mRecycler.setLayoutManager(new LinearLayoutManager(this));
        mRecycler.setAdapter(mAdapter);

        // Initial hint
        addAssistantMessage("你好，我是 Nana。\n点一下麦克风，说出你的问题。");

        mSend.setOnClickListener(v -> onSendClicked());
        mMic.setOnClickListener(v -> onMicClicked());

        mTts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                // Prefer Chinese voice for China-market devices.
                Locale locale = Locale.SIMPLIFIED_CHINESE;
                int res = mTts.setLanguage(locale);
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    mTts.setLanguage(Locale.getDefault());
                }
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, BotDropService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mBound) {
            unbindService(mConnection);
            mBound = false;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mTts != null) {
            try {
                mTts.stop();
                mTts.shutdown();
            } catch (Exception ignored) {}
            mTts = null;
        }
    }

    private void onSendClicked() {
        String text = mInput.getText().toString().trim();
        if (text.isEmpty()) return;
        mInput.setText("");
        sendUserMessage(text);
    }

    private void onMicClicked() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                new String[]{android.Manifest.permission.RECORD_AUDIO},
                REQ_RECORD_AUDIO);
            return;
        }
        startVoiceInput();
    }

    private void startVoiceInput() {
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "说点什么…");
            startActivityForResult(intent, REQ_VOICE);
        } catch (Exception e) {
            addAssistantMessage("这台手机没有语音识别。你可以直接打字。");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VOICE && resultCode == RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                String text = results.get(0);
                if (text != null && !text.trim().isEmpty()) {
                    sendUserMessage(text.trim());
                }
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startVoiceInput();
            } else {
                addAssistantMessage("需要麦克风权限才能语音对话。你也可以直接打字。");
            }
        }
    }

    private void sendUserMessage(String text) {
        if (mBusy) return;
        addUserMessage(text);

        if (!mBound || mService == null) {
            addAssistantMessage("系统正在启动，请稍后再试。");
            return;
        }

        mBusy = true;
        setInputEnabled(false);

        final int placeholderIndex = addAssistantMessage("正在想…");

        mService.runAgentTurn(mSessionId, text, result -> {
            String reply;
            if (!result.success) {
                reply = "出错了。\n\n" + safeTrim(result.stdout);
            } else {
                reply = parseAgentReply(result.stdout);
                if (reply == null || reply.trim().isEmpty()) {
                    reply = "我没听清，再说一次？";
                }
            }

            updateAssistantMessage(placeholderIndex, reply);

            if (mSpeakReplies) speak(reply);
            mBusy = false;
            setInputEnabled(true);
        });
    }

    private void setInputEnabled(boolean enabled) {
        mInput.setEnabled(enabled);
        mSend.setEnabled(enabled);
        mMic.setEnabled(enabled);
        float alpha = enabled ? 1.0f : 0.5f;
        mSend.setAlpha(alpha);
        mMic.setAlpha(alpha);
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

    private void speak(String text) {
        if (mTts == null) return;
        if (text == null) return;
        String t = text.trim();
        if (t.isEmpty()) return;
        try {
            mTts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "reply");
        } catch (Exception ignored) {}
    }

    private String getOrCreateSessionId() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String existing = sp.getString(PREF_SESSION_ID, null);
        if (existing != null && existing.trim().length() >= 8) return existing.trim();
        String next = UUID.randomUUID().toString();
        sp.edit().putString(PREF_SESSION_ID, next).apply();
        return next;
    }

    private String parseAgentReply(String stdout) {
        if (stdout == null) return "";
        String s = extractJsonObject(stdout);
        try {
            JSONObject obj = new JSONObject(s);
            JSONObject result = obj.optJSONObject("result");
            if (result != null) {
                JSONArray payloads = result.optJSONArray("payloads");
                if (payloads != null && payloads.length() > 0) {
                    StringBuilder out = new StringBuilder();
                    for (int i = 0; i < payloads.length(); i++) {
                        JSONObject p = payloads.optJSONObject(i);
                        if (p == null) continue;
                        String text = p.optString("text", "");
                        if (text != null && !text.trim().isEmpty()) {
                            if (out.length() > 0) out.append("\n");
                            out.append(text.trim());
                        }
                        // mediaUrl/mediaUrls are possible; keep minimal for now.
                    }
                    if (out.length() > 0) return out.toString();
                }
            }
            String summary = obj.optString("summary", "");
            if (summary != null && !summary.trim().isEmpty()) return summary.trim();
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to parse agent JSON: " + e.getMessage());
        }
        return safeTrim(stdout);
    }

    private String extractJsonObject(String stdout) {
        String s = stdout.trim();
        int idx = s.indexOf('{');
        if (idx >= 0) {
            return s.substring(idx).trim();
        }
        return s;
    }

    private static String safeTrim(String s) {
        if (s == null) return "";
        String t = s.trim();
        // Avoid flooding the UI if the command dumped logs.
        if (t.length() > 4000) return t.substring(0, 4000) + "\n…";
        return t;
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
        private final ArrayList<ChatMessage> messages;

        ChatAdapter(ArrayList<ChatMessage> messages) {
            this.messages = messages;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chat_message, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            ChatMessage m = messages.get(position);
            holder.text.setText(m.text);

            boolean isUser = m.role == ChatMessage.Role.USER;
            holder.applyRole(isUser);
        }

        @Override
        public int getItemCount() {
            return messages.size();
        }

        static final class VH extends RecyclerView.ViewHolder {
            final View bubble;
            final TextView text;

            VH(@NonNull View itemView) {
                super(itemView);
                bubble = itemView.findViewById(R.id.chat_bubble);
                text = itemView.findViewById(R.id.chat_text);
            }

            void applyRole(boolean isUser) {
                ViewGroup.LayoutParams lp = bubble.getLayoutParams();
                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    // no-op: margins handled by layout
                }

                ViewGroup parent = (ViewGroup) bubble.getParent();
                if (parent instanceof android.widget.FrameLayout) {
                    android.widget.FrameLayout.LayoutParams flp = (android.widget.FrameLayout.LayoutParams) bubble.getLayoutParams();
                    flp.gravity = isUser ? (android.view.Gravity.END) : (android.view.Gravity.START);
                    bubble.setLayoutParams(flp);
                }

                bubble.setBackgroundResource(isUser ? R.drawable.chat_bubble_user : R.drawable.chat_bubble_assistant);
                text.setTextColor(isUser ? 0xFF1A1A1A : 0xFFF5F0E6);
            }
        }
    }
}
