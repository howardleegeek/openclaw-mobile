package ai.clawphones.agent.chat;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.termux.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class TaskDetailActivity extends AppCompatActivity {

    public static final String EXTRA_TASK_ID = "task_id";

    private TaskMarketService mTaskService;
    private ClawTask mCurrentTask;

    private ScrollView mScrollView;
    private TextView mTitleText;
    private TextView mTypeText;
    private TextView mRewardText;
    private TextView mStatusText;
    private TextView mDescriptionText;
    private TextView mRequirementsText;
    private TextView mLocationText;
    private TextView mTimeText;
    private TextView mAssignedToText;
    private TextView mProgressText;
    private TextView mResultsText;
    private Button mAcceptButton;
    private Button mStartButton;
    private Button mCompleteButton;
    private ProgressBar mProgressBar;
    private View mProgressContainer;
    private View mResultsContainer;

    private final SimpleDateFormat mDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_task_detail);

        mTaskService = new TaskMarketService(getApplicationContext());
        mScrollView = findViewById(R.id.task_detail_scroll);
        mTitleText = findViewById(R.id.task_detail_title);
        mTypeText = findViewById(R.id.task_detail_type);
        mRewardText = findViewById(R.id.task_detail_reward);
        mStatusText = findViewById(R.id.task_detail_status);
        mDescriptionText = findViewById(R.id.task_detail_description);
        mRequirementsText = findViewById(R.id.task_detail_requirements);
        mLocationText = findViewById(R.id.task_detail_location);
        mTimeText = findViewById(R.id.task_detail_time);
        mAssignedToText = findViewById(R.id.task_detail_assigned_to);
        mProgressText = findViewById(R.id.task_detail_progress);
        mResultsText = findViewById(R.id.task_detail_results);
        mAcceptButton = findViewById(R.id.task_button_accept);
        mStartButton = findViewById(R.id.task_button_start);
        mCompleteButton = findViewById(R.id.task_button_complete);
        mProgressBar = findViewById(R.id.task_progress_bar);
        mProgressContainer = findViewById(R.id.task_progress_container);
        mResultsContainer = findViewById(R.id.task_results_container);

        Toolbar toolbar = findViewById(R.id.task_detail_toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            toolbar.setNavigationOnClickListener(v -> finish());
        }

        mAcceptButton.setOnClickListener(v -> acceptTask());
        mStartButton.setOnClickListener(v -> startTask());
        mCompleteButton.setOnClickListener(v -> completeTask());

        String taskId = getIntent().getStringExtra(EXTRA_TASK_ID);
        if (!TextUtils.isEmpty(taskId)) {
            loadTask(taskId);
        } else {
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mCurrentTask != null) {
            loadTask(mCurrentTask.getTaskId());
        }
    }

    private void loadTask(String taskId) {
        mCurrentTask = mTaskService.getTaskById(taskId);
        if (mCurrentTask == null) {
            finish();
            return;
        }

        renderTask();
    }

    private void renderTask() {
        ClawTask task = mCurrentTask;

        mTitleText.setText(task.getTitle());
        mTypeText.setText(typeText(task.getType()));
        mRewardText.setText(getString(R.string.task_reward_format, task.getReward()));
        mStatusText.setText(statusText(task.getStatus()));
        mDescriptionText.setText(task.getDescription());

        String requirements = task.getRequirements();
        if (TextUtils.isEmpty(requirements)) {
            mRequirementsText.setVisibility(View.GONE);
            findViewById(R.id.task_detail_requirements_label).setVisibility(View.GONE);
        } else {
            mRequirementsText.setVisibility(View.VISIBLE);
            findViewById(R.id.task_detail_requirements_label).setVisibility(View.VISIBLE);
            mRequirementsText.setText(requirements);
        }

        mLocationText.setText(
            getString(R.string.task_location_format,
                String.valueOf(task.getLatitude()), String.valueOf(task.getLongitude()))
        );

        String timeInfo = getString(R.string.task_time_created) + ": " + formatTime(task.getCreatedAt()) +
            "\n" + getString(R.string.task_time_expires) + ": " + formatTime(task.getExpiresAt());
        mTimeText.setText(timeInfo);

        if (task.getAssignedTo() != null) {
            mAssignedToText.setVisibility(View.VISIBLE);
            mAssignedToText.setText(getString(R.string.task_assigned_to, task.getAssignedTo()));
        } else {
            mAssignedToText.setVisibility(View.GONE);
        }

        updateActionButtons();
    }

    private void updateActionButtons() {
        ClawTask.TaskStatus status = mCurrentTask.getStatus();

        mAcceptButton.setVisibility(View.GONE);
        mStartButton.setVisibility(View.GONE);
        mCompleteButton.setVisibility(View.GONE);
        mProgressContainer.setVisibility(View.GONE);
        mResultsContainer.setVisibility(View.GONE);

        switch (status) {
            case AVAILABLE:
                mAcceptButton.setVisibility(View.VISIBLE);
                break;
            case ASSIGNED:
                mStartButton.setVisibility(View.VISIBLE);
                break;
            case IN_PROGRESS:
                mCompleteButton.setVisibility(View.VISIBLE);
                mProgressContainer.setVisibility(View.VISIBLE);
                int progress = mTaskService.getTaskProgress(mCurrentTask.getTaskId());
                mProgressBar.setProgress(progress);
                mProgressText.setText(getString(R.string.task_progress_format, progress));
                break;
            case COMPLETED:
                mResultsContainer.setVisibility(View.VISIBLE);
                String results = mTaskService.getTaskResults(mCurrentTask.getTaskId());
                if (TextUtils.isEmpty(results)) {
                    mResultsText.setText(getString(R.string.task_results_empty));
                } else {
                    mResultsText.setText(results);
                }
                break;
            case EXPIRED:
                mResultsContainer.setVisibility(View.VISIBLE);
                mResultsText.setText(getString(R.string.task_expired));
                break;
        }
    }

    private void acceptTask() {
        boolean success = mTaskService.acceptTask(mCurrentTask.getTaskId());
        if (success) {
            loadTask(mCurrentTask.getTaskId());
        }
    }

    private void startTask() {
        boolean success = mTaskService.startTask(mCurrentTask.getTaskId());
        if (success) {
            loadTask(mCurrentTask.getTaskId());
        }
    }

    private void completeTask() {
        boolean success = mTaskService.completeTask(mCurrentTask.getTaskId());
        if (success) {
            loadTask(mCurrentTask.getTaskId());
        }
    }

    private String typeText(ClawTask.TaskType type) {
        switch (type) {
            case PHOTO_SURVEY:
                return getString(R.string.task_type_photo_survey);
            case MONITORING:
                return getString(R.string.task_type_monitoring);
            case ENVIRONMENTAL:
                return getString(R.string.task_type_environmental);
            case TRAFFIC:
                return getString(R.string.task_type_traffic);
            case RETAIL:
                return getString(R.string.task_type_retail);
            default:
                return getString(R.string.task_type_unknown);
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

    private String formatTime(long timestampMs) {
        return mDateFormat.format(new Date(timestampMs));
    }
}
