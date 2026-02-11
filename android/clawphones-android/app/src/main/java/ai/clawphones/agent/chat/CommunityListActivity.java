package ai.clawphones.agent.chat;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
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

public final class CommunityListActivity extends AppCompatActivity {

    private static final int MAX_COMMUNITIES = 50;

    private RecyclerView mRecycler;
    private SwipeRefreshLayout mRefreshLayout;
    private TextView mEmptyState;
    private CommunityAdapter mAdapter;

    private ExecutorService mExecutor;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean mDestroyed = false;

    private String mToken;
    private final List<Community> mCommunities = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mToken = ClawPhonesAPI.getToken(this);
        if (TextUtils.isEmpty(mToken)) {
            redirectToLogin();
            return;
        }

        setContentView(R.layout.activity_community_list);

        Toolbar toolbar = findViewById(R.id.community_toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            toolbar.setTitle(R.string.community_list_title);
        }

        mRecycler = findViewById(R.id.community_recycler);
        mRefreshLayout = findViewById(R.id.community_refresh);
        mEmptyState = findViewById(R.id.community_empty);

        mAdapter = new CommunityAdapter(mCommunities, this::openCommunityDetail);
        mRecycler.setLayoutManager(new LinearLayoutManager(this));
        mRecycler.setAdapter(mAdapter);

        mExecutor = Executors.newSingleThreadExecutor();

        mRefreshLayout.setOnRefreshListener(this::loadCommunities);

        FloatingActionButton fab = findViewById(R.id.community_fab);
        if (fab != null) {
            fab.setOnClickListener(this::showCommunityMenu);
        }

        loadCommunities();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mToken = ClawPhonesAPI.getToken(this);
        if (TextUtils.isEmpty(mToken)) {
            redirectToLogin();
            return;
        }
        loadCommunities();
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

    private void showCommunityMenu(View v) {
        String[] options = new String[]{
            getString(R.string.community_menu_create),
            getString(R.string.community_menu_join)
        };
        new AlertDialog.Builder(this)
            .setItems(options, (dialog, which) -> {
                if (which == 0) {
                    showCreateDialog();
                } else {
                    showJoinDialog();
                }
            })
            .show();
    }

    private void showCreateDialog() {
        View view = LayoutInflater.from(this)
            .inflate(R.layout.dialog_create_community, null);
        EditText nameInput = view.findViewById(R.id.community_name);
        EditText descInput = view.findViewById(R.id.community_description);

        new AlertDialog.Builder(this)
            .setTitle(R.string.community_create_title)
            .setView(view)
            .setNegativeButton(R.string.chat_action_cancel, null)
            .setPositiveButton(R.string.community_create_button, (d, w) -> {
                String name = nameInput.getText() != null ? nameInput.getText().toString().trim() : "";
                String desc = descInput.getText() != null ? descInput.getText().toString().trim() : "";
                createCommunity(name, desc);
            })
            .show();
    }

    private void showJoinDialog() {
        View view = LayoutInflater.from(this)
            .inflate(R.layout.dialog_join_community, null);
        EditText codeInput = view.findViewById(R.id.join_invite_code);

        new AlertDialog.Builder(this)
            .setTitle(R.string.community_join_title)
            .setView(view)
            .setNegativeButton(R.string.chat_action_cancel, null)
            .setPositiveButton(R.string.community_join_button, (d, w) -> {
                String code = codeInput.getText() != null ? codeInput.getText().toString().trim() : "";
                joinCommunity(code);
            })
            .show();
    }

    private void createCommunity(String name, String description) {
        if (TextUtils.isEmpty(name)) {
            toast(getString(R.string.community_error_name_required));
            return;
        }

        execSafe(() -> {
            try {
                Community created = CommunityService.createCommunity(
                    CommunityListActivity.this,
                    name,
                    description
                );
                runSafe(() -> {
                    mCommunities.add(0, created);
                    mAdapter.notifyDataSetChanged();
                    updateEmptyState();
                    toast(getString(R.string.community_create_success));
                });
            } catch (IOException | JSONException e) {
                runSafe(() -> {
                    toast(getString(R.string.community_error_create_failed));
                });
            }
        });
    }

    private void joinCommunity(String inviteCode) {
        if (TextUtils.isEmpty(inviteCode)) {
            toast(getString(R.string.community_error_code_required));
            return;
        }

        execSafe(() -> {
            try {
                Community joined = CommunityService.joinCommunity(
                    CommunityListActivity.this,
                    inviteCode
                );
                runSafe(() -> {
                    mCommunities.add(0, joined);
                    mAdapter.notifyDataSetChanged();
                    updateEmptyState();
                    toast(getString(R.string.community_join_success));
                });
            } catch (IOException | JSONException e) {
                runSafe(() -> {
                    toast(getString(R.string.community_error_join_failed));
                });
            }
        });
    }

    private void loadCommunities() {
        mRefreshLayout.setRefreshing(true);

        execSafe(() -> {
            try {
                List<Community> communities = CommunityService.listCommunities(
                    CommunityListActivity.this,
                    MAX_COMMUNITIES,
                    0
                );
                mCommunities.clear();
                mCommunities.addAll(communities);
                Collections.sort(mCommunities, new Comparator<Community>() {
                    @Override
                    public int compare(Community a, Community b) {
                        return Long.compare(b.createdAt, a.createdAt);
                    }
                });

                runSafe(() -> {
                    mAdapter.notifyDataSetChanged();
                    updateEmptyState();
                    mRefreshLayout.setRefreshing(false);
                });
            } catch (IOException | JSONException e) {
                runSafe(() -> {
                    mRefreshLayout.setRefreshing(false);
                    if (mCommunities.isEmpty()) {
                        toast(getString(R.string.community_error_load_failed));
                    }
                });
            }
        });
    }

    private void openCommunityDetail(Community community) {
        if (community == null || TextUtils.isEmpty(community.id)) return;
        // TODO: Navigate to CommunityDetailActivity
        toast(getString(R.string.community_detail_open));
    }

    private void updateEmptyState() {
        boolean empty = mCommunities.isEmpty();
        mEmptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
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

    private static String formatTimestamp(long timestampSeconds) {
        if (timestampSeconds <= 0) return "-";

        long nowSeconds = System.currentTimeMillis() / 1000L;
        long diff = nowSeconds - timestampSeconds;
        if (diff < 0) diff = 0;

        if (diff < 60) return "刚刚";
        if (diff < 60 * 60) return (diff / 60) + "分钟前";
        if (diff < 24 * 60 * 60) return (diff / 3600) + "小时前";
        if (diff < 7 * 24 * 60 * 60) return (diff / 86400) + "天前";

        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(new Date(timestampSeconds * 1000L));
    }

    private interface OnCommunityClickListener {
        void onClick(Community community);
    }

    private static final class CommunityAdapter extends RecyclerView.Adapter<CommunityAdapter.VH> {

        private final List<Community> mItems;
        private final OnCommunityClickListener mListener;

        CommunityAdapter(List<Community> items, OnCommunityClickListener listener) {
            mItems = items;
            mListener = listener;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_community, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            Community item = mItems.get(position);
            holder.bind(item, mListener);
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }

        static final class VH extends RecyclerView.ViewHolder {
            private final TextView name;
            private final TextView description;
            private final TextView memberCount;
            private final TextView time;

            VH(@NonNull View itemView) {
                super(itemView);
                name = itemView.findViewById(R.id.community_name);
                description = itemView.findViewById(R.id.community_description);
                memberCount = itemView.findViewById(R.id.community_member_count);
                time = itemView.findViewById(R.id.community_time);
            }

            void bind(Community item, OnCommunityClickListener listener) {
                name.setText(item.name);
                description.setText(item.description != null ? item.description : "");
                memberCount.setText(String.valueOf(item.memberCount) + " 成员");
                time.setText(formatTimestamp(item.createdAt));

                itemView.setOnClickListener(v -> {
                    if (listener != null) listener.onClick(item);
                });
            }
        }
    }

    private android.content.Intent Intent = null;
}
