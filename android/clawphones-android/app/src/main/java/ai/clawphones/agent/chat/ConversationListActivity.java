package ai.clawphones.agent.chat;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.widget.ImageButton;
import com.termux.R;

import org.json.JSONException;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ConversationListActivity extends AppCompatActivity {

    private RecyclerView mRecycler;
    private TextView mEmptyState;
    private ConversationAdapter mAdapter;

    private ExecutorService mExecutor;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean mDestroyed = false;

    private String mToken;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mToken = ClawPhonesAPI.getToken(this);
        if (TextUtils.isEmpty(mToken)) {
            redirectToLogin();
            return;
        }

        setContentView(R.layout.activity_conversation_list);

        Toolbar toolbar = findViewById(R.id.conversation_toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            toolbar.setTitle("ClawPhones");
        }

        ImageButton logout = findViewById(R.id.conversation_logout);
        if (logout != null) {
            logout.setOnClickListener(v -> confirmLogout());
        }

        mRecycler = findViewById(R.id.conversations_recycler);
        mEmptyState = findViewById(R.id.empty_state);

        mAdapter = new ConversationAdapter(new ArrayList<>(), this::openConversation);
        mRecycler.setLayoutManager(new LinearLayoutManager(this));
        mRecycler.setAdapter(mAdapter);

        mExecutor = Executors.newSingleThreadExecutor();

        attachSwipeToDelete();

        ImageButton fab = findViewById(R.id.new_conversation_fab);
        if (fab != null) {
            fab.setOnClickListener(v -> {
                Intent i = new Intent(ConversationListActivity.this, ChatActivity.class);
                startActivity(i);
            });
        }

        loadConversations();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mToken = ClawPhonesAPI.getToken(this);
        if (TextUtils.isEmpty(mToken)) {
            redirectToLogin();
            return;
        }
        loadConversations();
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

    private void attachSwipeToDelete() {
        ItemTouchHelper.SimpleCallback swipe = new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder,
                                  @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                int position = viewHolder.getBindingAdapterPosition();
                ClawPhonesAPI.ConversationSummary item = mAdapter.getItemAt(position);
                if (item == null) {
                    mAdapter.notifyDataSetChanged();
                    return;
                }

                new AlertDialog.Builder(ConversationListActivity.this)
                    .setMessage("确定删除这个对话吗？")
                    .setNegativeButton("取消", (d, w) -> mAdapter.notifyItemChanged(position))
                    .setOnCancelListener(d -> mAdapter.notifyItemChanged(position))
                    .setPositiveButton("删除", (d, w) -> deleteConversation(item.id, position))
                    .show();
            }
        };
        new ItemTouchHelper(swipe).attachToRecyclerView(mRecycler);
    }

    private void deleteConversation(String conversationId, int fallbackPosition) {
        execSafe(() -> {
            try {
                ClawPhonesAPI.deleteConversation(mToken, conversationId);
                runSafe(() -> {
                    int idx = mAdapter.removeById(conversationId);
                    if (idx >= 0) {
                        mAdapter.notifyItemRemoved(idx);
                    } else if (fallbackPosition >= 0) {
                        mAdapter.notifyItemChanged(fallbackPosition);
                    }
                    updateEmptyState();
                });
            } catch (ClawPhonesAPI.ApiException e) {
                runSafe(() -> {
                    if (e.statusCode == 401) {
                        ClawPhonesAPI.clearToken(ConversationListActivity.this);
                        redirectToLogin();
                        return;
                    }
                    mAdapter.notifyItemChanged(fallbackPosition);
                    toast("删除失败");
                });
            } catch (IOException e) {
                runSafe(() -> {
                    mAdapter.notifyItemChanged(fallbackPosition);
                    toast("删除失败");
                });
            }
        });
    }

    private void loadConversations() {
        if (TextUtils.isEmpty(mToken)) {
            redirectToLogin();
            return;
        }

        execSafe(() -> {
            try {
                List<ClawPhonesAPI.ConversationSummary> conversations =
                    new ArrayList<>(ClawPhonesAPI.listConversations(mToken));
                Collections.sort(conversations, new Comparator<ClawPhonesAPI.ConversationSummary>() {
                    @Override
                    public int compare(ClawPhonesAPI.ConversationSummary a, ClawPhonesAPI.ConversationSummary b) {
                        long at = a.updatedAt > 0 ? a.updatedAt : a.createdAt;
                        long bt = b.updatedAt > 0 ? b.updatedAt : b.createdAt;
                        return Long.compare(bt, at);
                    }
                });

                runSafe(() -> {
                    mAdapter.replaceAll(conversations);
                    updateEmptyState();
                });
            } catch (ClawPhonesAPI.ApiException e) {
                runSafe(() -> {
                    if (e.statusCode == 401) {
                        ClawPhonesAPI.clearToken(ConversationListActivity.this);
                        redirectToLogin();
                        return;
                    }
                    toast("加载失败");
                });
            } catch (IOException | JSONException e) {
                runSafe(() -> toast("加载失败"));
            }
        });
    }

    private void openConversation(ClawPhonesAPI.ConversationSummary item) {
        if (item == null || TextUtils.isEmpty(item.id)) return;
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra("conversation_id", item.id);
        i.putExtra("title", TextUtils.isEmpty(item.title) ? "新对话" : item.title);
        startActivity(i);
    }

    private void updateEmptyState() {
        boolean empty = mAdapter.getItemCount() == 0;
        if (mEmptyState != null) {
            mEmptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        }
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this)
            .setMessage("确定退出登录吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("退出", (d, w) -> {
                ClawPhonesAPI.clearToken(ConversationListActivity.this);
                redirectToLogin();
            })
            .show();
    }

    private void redirectToLogin() {
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    private void runSafe(Runnable r) {
        if (mDestroyed) return;
        mMainHandler.post(() -> {
            if (!mDestroyed) r.run();
        });
    }

    private void execSafe(Runnable r) {
        ExecutorService exec = mExecutor;
        if (exec != null && !exec.isShutdown()) {
            try {
                exec.execute(r);
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
            }
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private static String formatRelativeTime(long timestampSeconds) {
        if (timestampSeconds <= 0) return "-";

        long nowSeconds = System.currentTimeMillis() / 1000L;
        long diff = nowSeconds - timestampSeconds;
        if (diff < 0) diff = 0;

        if (diff < 60) return "刚刚";
        if (diff < 5 * 60) return (diff / 60) + "分钟前";
        if (diff < 60 * 60) return (diff / 60) + "分钟前";
        if (diff < 24 * 60 * 60) return (diff / 3600) + "小时前";

        Calendar now = Calendar.getInstance();
        Calendar target = Calendar.getInstance();
        target.setTimeInMillis(timestampSeconds * 1000L);

        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(target, yesterday)) {
            return "昨天";
        }

        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(new Date(timestampSeconds * 1000L));
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
            && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private interface OnConversationClickListener {
        void onClick(ClawPhonesAPI.ConversationSummary item);
    }

    private static final class ConversationAdapter extends RecyclerView.Adapter<ConversationAdapter.VH> {

        private final ArrayList<ClawPhonesAPI.ConversationSummary> mItems;
        private final OnConversationClickListener mListener;

        ConversationAdapter(ArrayList<ClawPhonesAPI.ConversationSummary> items,
                            OnConversationClickListener listener) {
            mItems = items;
            mListener = listener;
        }

        void replaceAll(List<ClawPhonesAPI.ConversationSummary> newItems) {
            mItems.clear();
            if (newItems != null) {
                mItems.addAll(newItems);
            }
            notifyDataSetChanged();
        }

        int removeById(String conversationId) {
            for (int i = 0; i < mItems.size(); i++) {
                if (TextUtils.equals(mItems.get(i).id, conversationId)) {
                    mItems.remove(i);
                    return i;
                }
            }
            return -1;
        }

        @Nullable
        ClawPhonesAPI.ConversationSummary getItemAt(int position) {
            if (position < 0 || position >= mItems.size()) return null;
            return mItems.get(position);
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_conversation, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            ClawPhonesAPI.ConversationSummary item = mItems.get(position);
            holder.bind(item, mListener);
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }

        static final class VH extends RecyclerView.ViewHolder {
            private final TextView title;
            private final TextView time;
            private final TextView count;

            VH(@NonNull View itemView) {
                super(itemView);
                title = itemView.findViewById(R.id.conversation_title);
                time = itemView.findViewById(R.id.conversation_time);
                count = itemView.findViewById(R.id.conversation_count);
            }

            void bind(ClawPhonesAPI.ConversationSummary item, OnConversationClickListener listener) {
                String safeTitle = TextUtils.isEmpty(item.title) ? "新对话" : item.title;
                long ts = item.updatedAt > 0 ? item.updatedAt : item.createdAt;

                title.setText(safeTitle);
                time.setText(formatRelativeTime(ts));
                count.setText(item.messageCount + "条消息");

                itemView.setOnClickListener(v -> {
                    if (listener != null) listener.onClick(item);
                });
            }
        }
    }
}
