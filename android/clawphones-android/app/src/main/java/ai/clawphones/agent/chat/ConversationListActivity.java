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
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
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

import ai.clawphones.agent.CrashReporter;

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
            toolbar.setTitle(getString(R.string.application_name));
        }

        ImageButton logout = findViewById(R.id.conversation_logout);
        if (logout != null) {
            logout.setOnClickListener(v -> confirmLogout());
        }
        ImageButton settings = findViewById(R.id.conversation_settings);
        if (settings != null) {
            settings.setOnClickListener(v -> {
                Intent intent = new Intent(ConversationListActivity.this, ChatSettingsActivity.class);
                startActivity(intent);
            });
        }

        mRecycler = findViewById(R.id.conversations_recycler);
        mEmptyState = findViewById(R.id.empty_state);

        mAdapter = new ConversationAdapter(new ArrayList<>(), this::openConversation, this::onConversationLongPressed);
        mRecycler.setLayoutManager(new LinearLayoutManager(this));
        mRecycler.setAdapter(mAdapter);

        mExecutor = Executors.newSingleThreadExecutor();

        attachSwipeToDelete();

        FloatingActionButton fab = findViewById(R.id.new_conversation_fab);
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
                showDeleteConfirmation(item, position, true);
            }
        };
        new ItemTouchHelper(swipe).attachToRecyclerView(mRecycler);
    }

    private void onConversationLongPressed(ClawPhonesAPI.ConversationSummary item, int fallbackPosition) {
        showDeleteConfirmation(item, fallbackPosition, false);
    }

    private void showDeleteConfirmation(@Nullable ClawPhonesAPI.ConversationSummary item,
                                        int fallbackPosition,
                                        boolean fromSwipe) {
        if (item == null || TextUtils.isEmpty(item.id)) {
            if (fromSwipe) restoreSwipedItem(fallbackPosition);
            return;
        }

        new AlertDialog.Builder(ConversationListActivity.this)
            .setMessage(getString(R.string.conversation_dialog_delete_confirm))
            .setNegativeButton(getString(R.string.conversation_action_cancel), (d, w) -> {
                if (fromSwipe) restoreSwipedItem(fallbackPosition);
            })
            .setOnCancelListener(d -> {
                if (fromSwipe) restoreSwipedItem(fallbackPosition);
            })
            .setPositiveButton(getString(R.string.conversation_action_delete),
                (d, w) -> deleteConversation(item.id, fallbackPosition, fromSwipe))
            .show();
    }

    private void restoreSwipedItem(int fallbackPosition) {
        if (fallbackPosition >= 0 && fallbackPosition < mAdapter.getItemCount()) {
            mAdapter.notifyItemChanged(fallbackPosition);
        } else {
            mAdapter.notifyDataSetChanged();
        }
    }

    private void deleteConversation(String conversationId, int fallbackPosition, boolean fromSwipe) {
        execSafe(() -> {
            try {
                ClawPhonesAPI.deleteConversation(ConversationListActivity.this, conversationId);
                runSafe(() -> {
                    int idx = mAdapter.removeById(conversationId);
                    if (idx >= 0) {
                        mAdapter.notifyItemRemoved(idx);
                    } else if (fromSwipe) {
                        restoreSwipedItem(fallbackPosition);
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
                    if (fromSwipe) restoreSwipedItem(fallbackPosition);
                    toast(getString(R.string.conversation_error_delete_failed));
                });
            } catch (IOException e) {
                runSafe(() -> {
                    if (fromSwipe) restoreSwipedItem(fallbackPosition);
                    toast(getString(R.string.conversation_error_delete_failed));
                });
            }
        });
    }

    private void loadConversations() {
        if (TextUtils.isEmpty(mToken)) {
            redirectToLogin();
            return;
        }
        CrashReporter.setLastAction("loading_conversations");

        execSafe(() -> {
            try {
                List<ClawPhonesAPI.ConversationSummary> conversations =
                    new ArrayList<>(ClawPhonesAPI.listConversations(ConversationListActivity.this));
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
                if (e.statusCode != 401) {
                    CrashReporter.reportNonFatal(ConversationListActivity.this, e, "loading_conversations");
                }
                runSafe(() -> {
                    if (e.statusCode == 401) {
                        ClawPhonesAPI.clearToken(ConversationListActivity.this);
                        redirectToLogin();
                        return;
                    }
                    toast(getString(R.string.conversation_error_load_failed));
                });
            } catch (IOException | JSONException e) {
                CrashReporter.reportNonFatal(ConversationListActivity.this, e, "loading_conversations");
                runSafe(() -> toast(getString(R.string.conversation_error_load_failed)));
            }
        });
    }

    private void openConversation(ClawPhonesAPI.ConversationSummary item) {
        if (item == null || TextUtils.isEmpty(item.id)) return;
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra("conversation_id", item.id);
        i.putExtra("title", TextUtils.isEmpty(item.title) ? getString(R.string.chat_new_conversation) : item.title);
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
            .setMessage(getString(R.string.conversation_dialog_logout_confirm))
            .setNegativeButton(getString(R.string.conversation_action_cancel), null)
            .setPositiveButton(getString(R.string.conversation_action_logout), (d, w) -> {
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

    private static String formatRelativeTime(android.content.Context context, long timestampSeconds) {
        if (timestampSeconds <= 0) return context.getString(R.string.conversation_time_unknown);

        long nowSeconds = System.currentTimeMillis() / 1000L;
        long diff = nowSeconds - timestampSeconds;
        if (diff < 0) diff = 0;

        if (diff < 60) return context.getString(R.string.conversation_time_just_now);
        if (diff < 5 * 60) return context.getString(R.string.conversation_time_minutes_ago, diff / 60);
        if (diff < 60 * 60) return context.getString(R.string.conversation_time_minutes_ago, diff / 60);
        if (diff < 24 * 60 * 60) return context.getString(R.string.conversation_time_hours_ago, diff / 3600);

        Calendar now = Calendar.getInstance();
        Calendar target = Calendar.getInstance();
        target.setTimeInMillis(timestampSeconds * 1000L);

        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(target, yesterday)) {
            return context.getString(R.string.conversation_time_yesterday);
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

    private interface OnConversationLongClickListener {
        void onLongClick(ClawPhonesAPI.ConversationSummary item, int adapterPosition);
    }

    private static final class ConversationAdapter extends RecyclerView.Adapter<ConversationAdapter.VH> {

        private final ArrayList<ClawPhonesAPI.ConversationSummary> mItems;
        private final OnConversationClickListener mListener;
        private final OnConversationLongClickListener mLongListener;

        ConversationAdapter(ArrayList<ClawPhonesAPI.ConversationSummary> items,
                            OnConversationClickListener listener,
                            OnConversationLongClickListener longListener) {
            mItems = items;
            mListener = listener;
            mLongListener = longListener;
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
            holder.bind(item, mListener, mLongListener);
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

            void bind(ClawPhonesAPI.ConversationSummary item,
                      OnConversationClickListener listener,
                      OnConversationLongClickListener longListener) {
                android.content.Context context = itemView.getContext();
                String safeTitle = TextUtils.isEmpty(item.title)
                    ? context.getString(R.string.chat_new_conversation)
                    : item.title;
                long ts = item.updatedAt > 0 ? item.updatedAt : item.createdAt;

                title.setText(safeTitle);
                time.setText(formatRelativeTime(context, ts));
                count.setText(context.getString(R.string.conversation_message_count, item.messageCount));

                itemView.setOnClickListener(v -> {
                    if (listener != null) listener.onClick(item);
                });
                itemView.setOnLongClickListener(v -> {
                    if (longListener == null) return false;
                    longListener.onLongClick(item, getBindingAdapterPosition());
                    return true;
                });
            }
        }
    }
}
