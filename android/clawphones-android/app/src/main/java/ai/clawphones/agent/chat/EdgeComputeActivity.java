package ai.clawphones.agent.chat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.termux.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Edge Compute Activity - UI for managing the Edge Compute Service.
 *
 * Features:
 * - Toggle switch to enable/disable edge compute service
 * - Current job card showing active job details
 * - Stats section with jobs completed, uptime, battery level
 * - Device health section showing CPU, memory, thermal status
 * - Job history RecyclerView showing completed and failed jobs
 */
public class EdgeComputeActivity extends AppCompatActivity {

    private static final String LOG_TAG = "EdgeComputeActivity";

    // UI Components
    private SwitchMaterial mServiceToggle;
    private LinearLayout mCurrentJobCard;
    private TextView mCurrentJobId;
    private TextView mCurrentJobType;
    private TextView mCurrentJobStatus;
    private TextView mCurrentJobProgress;

    private TextView mJobsCompletedValue;
    private TextView mJobsFailedValue;
    private TextView mUptimeValue;
    private TextView mBatteryLevelValue;

    private TextView mCpuUsageValue;
    private TextView mMemoryUsageValue;
    private TextView mThermalStatusValue;

    private RecyclerView mJobHistoryRecyclerView;
    private JobHistoryAdapter mJobHistoryAdapter;

    // State tracking
    private boolean mIgnoreToggleChanges = false;
    private boolean mReceiverRegistered = false;
    private int mJobsCompleted = 0;
    private int mJobsFailed = 0;
    private long mServiceStartTime = 0L;

    private final List<ComputeJob> mJobHistory = new ArrayList<>();

    // Broadcast receiver for service updates
    private final BroadcastReceiver mServiceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (EdgeComputeService.ACTION_STATUS_BROADCAST.equals(action)) {
                updateServiceStatus(intent);
            } else if (EdgeComputeService.ACTION_JOB_UPDATE.equals(action)) {
                updateJobStatus(intent);
            } else if (EdgeComputeService.ACTION_JOB_COMPLETE.equals(action)) {
                handleJobComplete(intent);
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edge_compute);

        initViews();
        setupListeners();
        setupJobHistory();
        updateIdleState();
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerServiceReceiver();
        requestServiceStatus();
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterServiceReceiver();
    }

    private void initViews() {
        mServiceToggle = findViewById(R.id.edge_compute_toggle);
        mCurrentJobCard = findViewById(R.id.current_job_card);
        mCurrentJobId = findViewById(R.id.current_job_id);
        mCurrentJobType = findViewById(R.id.current_job_type);
        mCurrentJobStatus = findViewById(R.id.current_job_status);
        mCurrentJobProgress = findViewById(R.id.current_job_progress);

        mJobsCompletedValue = findViewById(R.id.stats_completed_value);
        mJobsFailedValue = findViewById(R.id.stats_failed_value);
        mUptimeValue = findViewById(R.id.stats_uptime_value);
        mBatteryLevelValue = findViewById(R.id.stats_battery_value);

        mCpuUsageValue = findViewById(R.id.health_cpu_value);
        mMemoryUsageValue = findViewById(R.id.health_memory_value);
        mThermalStatusValue = findViewById(R.id.health_thermal_value);

        mJobHistoryRecyclerView = findViewById(R.id.job_history_recycler);
    }

    private void setupListeners() {
        mServiceToggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (mIgnoreToggleChanges) return;
                if (isChecked) {
                    startEdgeComputeService();
                } else {
                    stopEdgeComputeService();
                }
            }
        });

        Button refreshButton = findViewById(R.id.refresh_button);
        if (refreshButton != null) {
            refreshButton.setOnClickListener(v -> requestServiceStatus());
        }

        Button clearHistoryButton = findViewById(R.id.clear_history_button);
        if (clearHistoryButton != null) {
            clearHistoryButton.setOnClickListener(v -> clearJobHistory());
        }
    }

    private void setupJobHistory() {
        mJobHistoryAdapter = new JobHistoryAdapter(mJobHistory);
        mJobHistoryRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        mJobHistoryRecyclerView.setAdapter(mJobHistoryAdapter);
    }

    private void startEdgeComputeService() {
        try {
            String apiUrl = getApiUrl();
            String deviceId = getDeviceId();

            if (apiUrl == null || deviceId == null) {
                toast("Missing API URL or Device ID");
                setToggleChecked(false);
                return;
            }

            EdgeComputeService.startService(this, apiUrl, deviceId);
            mServiceStartTime = System.currentTimeMillis();
        } catch (Exception e) {
            toast("Failed to start edge compute service");
            setToggleChecked(false);
        }
    }

    private void stopEdgeComputeService() {
        try {
            EdgeComputeService.stopService(this);
            mServiceStartTime = 0L;
        } catch (Exception e) {
            toast("Failed to stop edge compute service");
        }
    }

    private void requestServiceStatus() {
        EdgeComputeService service = EdgeComputeService.getInstance();
        if (service == null || !service.isServiceRunning()) {
            setToggleChecked(false);
            updateIdleState();
            return;
        }

        setToggleChecked(true);
        updateActiveState(service);
    }

    private void updateServiceStatus(@NonNull Intent intent) {
        boolean running = intent.getBooleanExtra(EdgeComputeService.EXTRA_IS_RUNNING, false);
        setToggleChecked(running);

        if (running) {
            int activeJobs = intent.getIntExtra(EdgeComputeService.EXTRA_ACTIVE_JOBS, 0);
            int completedJobs = intent.getIntExtra(EdgeComputeService.EXTRA_COMPLETED_JOBS, 0);
            int failedJobs = intent.getIntExtra(EdgeComputeService.EXTRA_FAILED_JOBS, 0);
            long uptimeMs = intent.getIntExtra(EdgeComputeService.EXTRA_UPTIME_MS, 0);

            mJobsCompleted = completedJobs;
            mJobsFailed = failedJobs;

            updateStats(activeJobs, completedJobs, failedJobs, uptimeMs);
            updateDeviceHealth(intent);
        } else {
            updateIdleState();
        }
    }

    private void updateJobStatus(@NonNull Intent intent) {
        String jobId = intent.getStringExtra(EdgeComputeService.EXTRA_JOB_ID);
        String jobType = intent.getStringExtra(EdgeComputeService.EXTRA_JOB_TYPE);
        String status = intent.getStringExtra(EdgeComputeService.EXTRA_JOB_STATUS);

        if (jobId != null) {
            updateCurrentJob(jobId, jobType, status);
        }
    }

    private void handleJobComplete(@NonNull Intent intent) {
        String jobId = intent.getStringExtra(EdgeComputeService.EXTRA_JOB_ID);
        boolean success = intent.getBooleanExtra(EdgeComputeService.EXTRA_JOB_SUCCESS, false);

        if (jobId != null) {
            EdgeComputeService service = EdgeComputeService.getInstance();
            if (service != null) {
                ComputeJob job = findJobById(jobId, service);
                if (job != null) {
                    addJobToHistory(job);
                }
            }

            if (success) {
                mJobsCompleted++;
            } else {
                mJobsFailed++;
            }
            updateJobCounts();
        }

        requestServiceStatus();
    }

    @Nullable
    private ComputeJob findJobById(@NonNull String jobId, @NonNull EdgeComputeService service) {
        for (ComputeJob job : service.getCompletedJobs()) {
            if (jobId.equals(job.getJobId())) {
                return job;
            }
        }
        for (ComputeJob job : service.getClaimedJobs()) {
            if (jobId.equals(job.getJobId())) {
                return job;
            }
        }
        return null;
    }

    private void updateActiveState(@NonNull EdgeComputeService service) {
        List<ComputeJob> activeJobs = service.getClaimedJobs();

        if (!activeJobs.isEmpty()) {
            ComputeJob job = activeJobs.get(0);
            updateCurrentJob(job.getJobId(), job.getType().name(), job.getStatus().name());
            mCurrentJobCard.setVisibility(View.VISIBLE);
        } else {
            mCurrentJobCard.setVisibility(View.GONE);
        }

        updateStats(service.getActiveJobCount(), mJobsCompleted, mJobsFailed,
                    mServiceStartTime > 0 ? System.currentTimeMillis() - mServiceStartTime : 0);
    }

    private void updateIdleState() {
        mCurrentJobCard.setVisibility(View.GONE);
        updateStats(0, 0, 0, 0);
        updateDeviceHealth(null);
    }

    private void updateCurrentJob(@Nullable String jobId, @Nullable String jobType, @Nullable String status) {
        if (jobId != null) {
            mCurrentJobId.setText("Job ID: " + jobId);
        } else {
            mCurrentJobId.setText("No active job");
        }

        if (jobType != null) {
            mCurrentJobType.setText(formatJobType(jobType));
        } else {
            mCurrentJobType.setText("Unknown");
        }

        if (status != null) {
            mCurrentJobStatus.setText(formatStatus(status));
        } else {
            mCurrentJobStatus.setText("Pending");
        }

        mCurrentJobProgress.setText("Processing...");
    }

    private void updateStats(int activeJobs, int completed, int failed, long uptimeMs) {
        mJobsCompletedValue.setText(String.valueOf(completed));
        mJobsFailedValue.setText(String.valueOf(failed));
        mUptimeValue.setText(formatDuration(uptimeMs));

        BatteryManager batteryManager = (BatteryManager) getSystemService(BATTERY_SERVICE);
        if (batteryManager != null) {
            int batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            mBatteryLevelValue.setText(batteryLevel + "%");
        } else {
            mBatteryLevelValue.setText("N/A");
        }
    }

    private void updateDeviceHealth(@Nullable Intent intent) {
        if (intent == null) {
            mCpuUsageValue.setText("--");
            mMemoryUsageValue.setText("--");
            mThermalStatusValue.setText("--");
            return;
        }

        int cpuUsage = intent.getIntExtra(EdgeComputeService.EXTRA_CPU_USAGE, 0);
        int memoryUsage = intent.getIntExtra(EdgeComputeService.EXTRA_MEMORY_USAGE, 0);
        String thermalStatus = intent.getStringExtra(EdgeComputeService.EXTRA_THERMAL_STATUS);

        mCpuUsageValue.setText(cpuUsage + "%");
        mMemoryUsageValue.setText(memoryUsage + "%");
        mThermalStatusValue.setText(thermalStatus != null ? thermalStatus : "Normal");
    }

    private void updateJobCounts() {
        mJobsCompletedValue.setText(String.valueOf(mJobsCompleted));
        mJobsFailedValue.setText(String.valueOf(mJobsFailed));
    }

    private void addJobToHistory(@NonNull ComputeJob job) {
        mJobHistory.add(0, job);
        if (mJobHistory.size() > 50) {
            mJobHistory.remove(mJobHistory.size() - 1);
        }
        mJobHistoryAdapter.notifyDataSetChanged();
    }

    private void clearJobHistory() {
        mJobHistory.clear();
        mJobHistoryAdapter.notifyDataSetChanged();
    }

    private void registerServiceReceiver() {
        if (mReceiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(EdgeComputeService.ACTION_STATUS_BROADCAST);
        filter.addAction(EdgeComputeService.ACTION_JOB_UPDATE);
        filter.addAction(EdgeComputeService.ACTION_JOB_COMPLETE);
        ContextCompat.registerReceiver(this, mServiceReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        mReceiverRegistered = true;
    }

    private void unregisterServiceReceiver() {
        if (!mReceiverRegistered) return;
        unregisterReceiver(mServiceReceiver);
        mReceiverRegistered = false;
    }

    private void setToggleChecked(boolean checked) {
        mIgnoreToggleChanges = true;
        mServiceToggle.setChecked(checked);
        mIgnoreToggleChanges = false;
    }

    @Nullable
    private String getApiUrl() {
        return getSharedPreferences("edge_compute_prefs", MODE_PRIVATE)
                .getString("api_url", null);
    }

    @Nullable
    private String getDeviceId() {
        return getSharedPreferences("edge_compute_prefs", MODE_PRIVATE)
                .getString("device_id", null);
    }

    private String formatJobType(String type) {
        try {
            return ComputeJob.JobType.valueOf(type).getDisplayName();
        } catch (IllegalArgumentException e) {
            return type;
        }
    }

    private String formatStatus(String status) {
        try {
            return ComputeJob.JobStatus.valueOf(status).getDisplayName();
        } catch (IllegalArgumentException e) {
            return status;
        }
    }

    private String formatDuration(long durationMs) {
        if (durationMs <= 0) return "00:00:00";

        long totalSeconds = durationMs / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    // Adapter for job history RecyclerView
    private static class JobHistoryAdapter extends RecyclerView.Adapter<JobHistoryAdapter.ViewHolder> {
        private final List<ComputeJob> mJobs;

        JobHistoryAdapter(List<ComputeJob> jobs) {
            mJobs = jobs;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_job_history, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            ComputeJob job = mJobs.get(position);
            holder.bind(job);
        }

        @Override
        public int getItemCount() {
            return mJobs.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView mJobId;
            TextView mJobType;
            TextView mJobStatus;
            TextView mJobDuration;

            ViewHolder(View itemView) {
                super(itemView);
                mJobId = itemView.findViewById(R.id.job_history_id);
                mJobType = itemView.findViewById(R.id.job_history_type);
                mJobStatus = itemView.findViewById(R.id.job_history_status);
                mJobDuration = itemView.findViewById(R.id.job_history_duration);
            }

            void bind(ComputeJob job) {
                mJobId.setText(job.getJobId());
                mJobType.setText(job.getType().getDisplayName());
                mJobStatus.setText(job.getStatus().getDisplayName());

                if (job.getCompletedAt() > 0 && job.getClaimedAt() > 0) {
                    long duration = job.getCompletedAt() - job.getClaimedAt();
                    mJobDuration.setText(formatMs(duration));
                } else {
                    mJobDuration.setText("--");
                }

                int statusColorRes;
                switch (job.getStatus()) {
                    case COMPLETED:
                        statusColorRes = R.color.job_status_success;
                        break;
                    case FAILED:
                        statusColorRes = R.color.job_status_error;
                        break;
                    default:
                        statusColorRes = R.color.job_status_pending;
                        break;
                }
                mJobStatus.setTextColor(itemView.getContext().getResources().getColor(statusColorRes));
            }

            private String formatMs(long ms) {
                long seconds = ms / 1000;
                if (seconds < 60) {
                    return seconds + "s";
                }
                long minutes = seconds / 60;
                long remainder = seconds % 60;
                return minutes + "m " + remainder + "s";
            }
        }
    }
}
