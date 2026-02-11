package ai.clawphones.agent.chat;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class TaskListActivity extends AppCompatActivity {

    private static final String TAB_AVAILABLE = "available";
    private static final String TAB_ACTIVE = "active";
    private static final String TAB_COMPLETED = "completed";

    private final String[] mTabKeys = new String[]{TAB_AVAILABLE, TAB_ACTIVE, TAB_COMPLETED};

    private final List<ClawTask> mAllTasks = new ArrayList<>();

    private TaskMarketService mTaskService;
    private RecyclerView mRecycler;
    private TextView mEmptyState;
    private TextView[] mTabLabels;
    private View[] mTabIndicators;
    private Switch mAutoAcceptSwitch;
    private TaskAdapter mAdapter;

    private String mCurrentTab = TAB_AVAILABLE;
    private boolean mAutoAcceptEnabled = false;

    private final SimpleDateFormat mClockFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat mDateFormat = new SimpleDateFormat("M/d", Locale.getDefault());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_task_list);

        mTaskService = new TaskMarketService(getApplicationContext());
        mRecycler = findViewById(R.id.task_list_recycler);
        mEmptyState = findViewById(R.id.task_list_empty);
        mAutoAcceptSwitch = findViewById(R.id.task_auto_accept_switch);

        Toolbar toolbar = findViewById(R.id.task_list_toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            toolbar.setNavigationOnClickListener(v -> finish());
        }

        mTabLabels = new TextView[]{
            findViewById(R.id.tab_available),
            findViewById(R.id.tab_active),
            findViewById(R.id.tab_completed)
        };

        mTabIndicators = new View[]{
            findViewById(R.id.tab_indicator_available),
            findViewById(R.id.tab_indicator_active),
            findViewById(R.id.tab_indicator_completed)
        };

        for (int i = 0; i < mTabLabels.length; i++) {
            final int index = i;
            mTabLabels[i].setOnClickListener(v -> switchTab(index));
        }

        mAutoAcceptSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mAutoAcceptEnabled = isChecked;
            mTaskService.setAutoAccept(isChecked);
        });

        mAdapter = new TaskAdapter(this::openTaskDetail);
        mRecycler.setLayoutManager(new LinearLayoutManager(this));
        mRecycler.setAdapter(mAdapter);

        switchTab(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadTasks();
    }

    private void switchTab(int index) {
        if (index < 0 || index >= mTabKeys.length) return;

        mCurrentTab = mTabKeys[index];
        for (int i = 0; i < mTabLabels.length; i++) {
            mTabLabels[i].setTextColor(i == index ? 0xFFE8A853 : 0xFF888888);
            mTabIndicators[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
        }

        loadTasks();
    }

    private void loadTasks() {
        mAllTasks.clear();
        mAllTasks.addAll(mTaskService.getAllTasks());
        renderRows();
    }

    private void renderRows() {
        List<ClawTask> filtered = new ArrayList<>();
        for (ClawTask task : mAllTasks) {
            if (matchesTab(task)) {
                filtered.add(task);
            }
        }

        mAdapter.replaceAll(filtered);
        boolean isEmpty = filtered.isEmpty();
        mEmptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        mRecycler.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    private boolean matchesTab(ClawTask task) {
        ClawTask.TaskStatus status = task.getStatus();
        switch (mCurrentTab) {
            case TAB_AVAILABLE:
                return status == ClawTask.TaskStatus.AVAILABLE;
            case TAB_ACTIVE:
                return status == ClawTask.TaskStatus.ASSIGNED || status == ClawTask.TaskStatus.IN_PROGRESS;
            case TAB_COMPLETED:
                return status == ClawTask.TaskStatus.COMPLETED;
            default:
                return false;
        }
    }

    private void openTaskDetail(ClawTask task) {
        Intent intent = new Intent(this, TaskDetailActivity.class);
        intent.putExtra(TaskDetailActivity.EXTRA_TASK_ID, task.getTaskId());
        startActivity(intent);
    }

    private String formatTimestamp(long timestampMs) {
        Date date = new Date(timestampMs);
        long now = System.currentTimeMillis();
        long diff = now - timestampMs;
        long hours = diff / (1000 * 60 * 60);

        if (hours < 24 && isSameDay(timestampMs, now)) {
            return mClockFormat.format(date);
        }
        return mDateFormat.format(date);
    }

    private boolean isSameDay(long time1, long time2) {
        long day1 = time1 / (1000 * 60 * 60 * 24);
        long day2 = time2 / (1000 * 60 * 60 * 24);
        return day1 == day2;
    }

    private int statusBadgeColor(ClawTask.TaskStatus status) {
        switch (status) {
            case AVAILABLE:
                return 0xFF4CAF50;
            case ASSIGNED:
                return 0xFFFF9800;
            case IN_PROGRESS:
                return 0xFF2196F3;
            case COMPLETED:
                return 0xFF9E9E9E;
            case EXPIRED:
                return 0xFFF44336;
            default:
                return 0xFF888888;
        }
    }

    private String statusText(ClawTask.TaskStatus status) {
        switch (status) {
            case AVAILABLE:
                return getString(R.string.task_status_available);
            case ASSIGNED:
                return getString(R.string.task_status_assigned);
            case IN_PROGRESS:
                return getString(R.string.task_status_in_progress);
            case COMPLETED:
                return getString(R.string.task_status_completed);
            case EXPIRED:
                return getString(R.string.task_status_expired);
            default:
                return getString(R.string.task_status_unknown);
        }
    }

    private interface OnTaskClickListener {
        void onTaskClick(ClawTask task);
    }

    private final class TaskAdapter extends RecyclerView.Adapter<TaskAdapter.TaskViewHolder> {

        private final List<ClawTask> mTasks = new ArrayList<>();
        private final OnTaskClickListener mClickListener;

        TaskAdapter(OnTaskClickListener clickListener) {
            mClickListener = clickListener;
        }

        void replaceAll(List<ClawTask> tasks) {
            mTasks.clear();
            if (tasks != null) {
                mTasks.addAll(tasks);
            }
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public TaskViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_task, parent, false);
            return new TaskViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull TaskViewHolder holder, int position) {
            holder.bind(mTasks.get(position));
        }

        @Override
        public int getItemCount() {
            return mTasks.size();
        }

        final class TaskViewHolder extends RecyclerView.ViewHolder {
            private final TextView titleText;
            private final TextView rewardText;
            private final TextView timeText;
            private final TextView statusText;
            private final View statusBadge;

            TaskViewHolder(@NonNull View itemView) {
                super(itemView);
                titleText = itemView.findViewById(R.id.task_item_title);
                rewardText = itemView.findViewById(R.id.task_item_reward);
                timeText = itemView.findViewById(R.id.task_item_time);
                statusText = itemView.findViewById(R.id.task_item_status);
                statusBadge = itemView.findViewById(R.id.task_item_status_badge);
            }

            void bind(@NonNull ClawTask task) {
                titleText.setText(task.getTitle());
                rewardText.setText(getString(R.string.task_reward_format, task.getReward()));
                timeText.setText(formatTimestamp(task.getExpiresAt()));

                ClawTask.TaskStatus status = task.getStatus();
                statusText.setText(statusText(status));
                statusBadge.setBackgroundColor(statusBadgeColor(status));

                itemView.setOnClickListener(v -> {
                    if (mClickListener != null) {
                        mClickListener.onTaskClick(task);
                    }
                });
            }
        }
    }
}
