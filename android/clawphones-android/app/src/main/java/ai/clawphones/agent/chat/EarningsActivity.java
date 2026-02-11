package ai.clawphones.agent.chat;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class EarningsActivity extends AppCompatActivity {

    private TaskMarketService mTaskService;
    private RecyclerView mRecycler;
    private TextView mEmptyState;
    private TextView mTotalEarnedText;
    private TextView mAvailableBalanceText;
    private TextView mTasksCompletedText;
    private TextView mTasksInProgressText;
    private EarningsAdapter mAdapter;

    private TaskMarketService.EarningsSummary mEarningsSummary;
    private final List<EarningEntry> mEarningsHistory = new ArrayList<>();

    private final DecimalFormat mCurrencyFormat = new DecimalFormat("#,##0.00");
    private final SimpleDateFormat mDateFormat = new SimpleDateFormat("M/d HH:mm", Locale.getDefault());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_earnings);

        mTaskService = new TaskMarketService(getApplicationContext());
        mRecycler = findViewById(R.id.earnings_history_recycler);
        mEmptyState = findViewById(R.id.earnings_empty);
        mTotalEarnedText = findViewById(R.id.earnings_total);
        mAvailableBalanceText = findViewById(R.id.earnings_balance);
        mTasksCompletedText = findViewById(R.id.earnings_completed);
        mTasksInProgressText = findViewById(R.id.earnings_in_progress);

        Toolbar toolbar = findViewById(R.id.earnings_toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            toolbar.setNavigationOnClickListener(v -> finish());
        }

        mAdapter = new EarningsAdapter();
        mRecycler.setLayoutManager(new LinearLayoutManager(this));
        mRecycler.setAdapter(mAdapter);

        loadEarnings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadEarnings();
    }

    private void loadEarnings() {
        mTaskService.fetchEarnings(new TaskMarketService.TaskCallback<TaskMarketService.EarningsSummary>() {
            @Override
            public void onSuccess(TaskMarketService.EarningsSummary result) {
                mEarningsSummary = result;
                updateDashboard();
                loadHistory();
            }

            @Override
            public void onError(String error) {
                showError(error);
            }
        });
    }

    private void updateDashboard() {
        if (mEarningsSummary == null) return;

        mTotalEarnedText.setText(
            getString(R.string.earnings_total_format, mCurrencyFormat.format(mEarningsSummary.totalEarned))
        );
        mAvailableBalanceText.setText(
            getString(R.string.earnings_balance_format, mCurrencyFormat.format(mEarningsSummary.availableBalance))
        );
        mTasksCompletedText.setText(
            getString(R.string.earnings_tasks_completed, mEarningsSummary.tasksCompleted)
        );
        mTasksInProgressText.setText(
            getString(R.string.earnings_tasks_in_progress, mEarningsSummary.tasksInProgress)
        );
    }

    private void loadHistory() {
        mEarningsHistory.clear();

        mTaskService.fetchMyTasks(new TaskMarketService.TaskCallback<List<ClawTask>>() {
            @Override
            public void onSuccess(List<ClawTask> tasks) {
                for (ClawTask task : tasks) {
                    if (task.getStatus() == ClawTask.TaskStatus.COMPLETED) {
                        EarningEntry entry = new EarningEntry(
                            task.getTaskId(),
                            task.getTitle(),
                            task.getReward(),
                            task.getCompletedAt() > 0 ? task.getCompletedAt() : System.currentTimeMillis()
                        );
                        mEarningsHistory.add(entry);
                    }
                }

                mAdapter.replaceAll(mEarningsHistory);
                boolean isEmpty = mEarningsHistory.isEmpty();
                mEmptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
                mRecycler.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onError(String error) {
                showError(error);
            }
        });
    }

    private void showError(String error) {
        mEmptyState.setText(getString(R.string.earnings_error, error));
        mEmptyState.setVisibility(View.VISIBLE);
        mRecycler.setVisibility(View.GONE);
    }

    private String formatTimestamp(long timestampMs) {
        return mDateFormat.format(new Date(timestampMs));
    }

    private static class EarningEntry {
        final String taskId;
        final String title;
        final double amount;
        final long timestamp;

        EarningEntry(String taskId, String title, double amount, long timestamp) {
            this.taskId = taskId;
            this.title = title;
            this.amount = amount;
            this.timestamp = timestamp;
        }
    }

    private final class EarningsAdapter extends RecyclerView.Adapter<EarningsAdapter.EarningViewHolder> {

        private final List<EarningEntry> mEntries = new ArrayList<>();

        void replaceAll(List<EarningEntry> entries) {
            mEntries.clear();
            if (entries != null) {
                mEntries.addAll(entries);
            }
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public EarningViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_earning, parent, false);
            return new EarningViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull EarningViewHolder holder, int position) {
            holder.bind(mEntries.get(position));
        }

        @Override
        public int getItemCount() {
            return mEntries.size();
        }

        final class EarningViewHolder extends RecyclerView.ViewHolder {
            private final TextView titleText;
            private final TextView amountText;
            private final TextView timeText;

            EarningViewHolder(@NonNull View itemView) {
                super(itemView);
                titleText = itemView.findViewById(R.id.earning_item_title);
                amountText = itemView.findViewById(R.id.earning_item_amount);
                timeText = itemView.findViewById(R.id.earning_item_time);
            }

            void bind(@NonNull EarningEntry entry) {
                titleText.setText(entry.title);
                amountText.setText("+" + mCurrencyFormat.format(entry.amount));
                amountText.setTextColor(0xFF4CAF50);
                timeText.setText(formatTimestamp(entry.timestamp));
            }
        }
    }
}
