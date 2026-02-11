package ai.clawphones.agent;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.TermuxInstaller;
import com.termux.shared.logger.Logger;

/**
 * "Zero-setup" preinstall flow: auto install OpenClaw, write config, and start gateway.
 *
 * This screen should be the only thing users see before they can talk.
 */
public class PreinstallActivity extends Activity {

    private static final String LOG_TAG = "PreinstallActivity";

    private TextView mStatusText;
    private TextView mErrorText;
    private ProgressBar mProgress;
    private Button mRetryButton;
    private Button mManualSetupButton;
    private Button mSkipToChatButton;

    private ClawPhonesService mService;
    private boolean mBound = false;
    private boolean mStarted = false;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            ClawPhonesService.LocalBinder binder = (ClawPhonesService.LocalBinder) service;
            mService = binder.getService();
            mBound = true;
            Logger.logDebug(LOG_TAG, "Service connected");
            maybeStartFlow();
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
        setContentView(R.layout.activity_clawphones_preinstall);

        mStatusText = findViewById(R.id.preinstall_status_text);
        mErrorText = findViewById(R.id.preinstall_error_text);
        mProgress = findViewById(R.id.preinstall_progress);
        mRetryButton = findViewById(R.id.preinstall_retry_button);
        mManualSetupButton = findViewById(R.id.preinstall_manual_setup_button);

        mRetryButton.setOnClickListener(v -> {
            hideError();
            mStarted = false;
            maybeStartFlow();
        });

        mManualSetupButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, SetupActivity.class);
            intent.putExtra(SetupActivity.EXTRA_START_STEP, SetupActivity.STEP_API_KEY);
            startActivity(intent);
        });

        mSkipToChatButton = findViewById(R.id.preinstall_skip_to_chat);
        mSkipToChatButton.setOnClickListener(v -> {
            Logger.logInfo(LOG_TAG, "User skipped OpenClaw install, going directly to AI Chat");
            openChat();
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!ClawPhonesApp.bindClawPhonesService(this, mConnection)) {
            Logger.logError(LOG_TAG, "Failed to bind ClawPhonesService");
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mBound) {
            unbindService(mConnection);
            mBound = false;
        }
    }

    private void maybeStartFlow() {
        if (!mBound || mService == null) return;
        if (mStarted) return;
        mStarted = true;
        runFlow();
    }

    private void runFlow() {
        // 0) Ensure bootstrap extracted
        if (!ClawPhonesService.isBootstrapInstalled()) {
            setStatus(getString(R.string.preinstall_status_initializing));
            TermuxInstaller.setupBootstrapIfNeeded(this, this::runFlow);
            return;
        }

        // 1) Ensure OpenClaw installed
        if (!ClawPhonesService.isOpenclawInstalled()) {
            setStatus(getString(R.string.preinstall_status_installing));
            mService.installOpenclaw(new ClawPhonesService.InstallProgressCallback() {
                @Override
                public void onStepStart(int step, String message) {
                    if (message != null && !message.trim().isEmpty()) setStatus(message);
                }

                @Override
                public void onStepComplete(int step) {
                    // noop
                }

                @Override
                public void onError(String error) {
                    Logger.logError(LOG_TAG, "Install failed: " + error);
                    showError(getString(R.string.preinstall_error_install_failed, error));
                }

                @Override
                public void onComplete() {
                    Logger.logInfo(LOG_TAG, "OpenClaw installed");
                    runFlow();
                }
            });
            return;
        }

        // 2) Ensure config provisioned (device_token -> env + model).
        setStatus(getString(R.string.preinstall_status_activating));
        boolean provisioned = PreinstallProvisioner.ensureProvisioned(this);
        if (!provisioned) {
            showError(getString(R.string.preinstall_error_missing_device_token));
            return;
        }

        // 3) Start gateway (best effort; agent CLI can fallback to embedded if needed).
        setStatus(getString(R.string.preinstall_status_starting));
        mService.startGateway(result -> {
            if (!result.success) {
                Logger.logError(LOG_TAG, "Gateway start failed: " + result.stdout);
                showError(getString(R.string.preinstall_error_start_failed, result.stdout));
                return;
            }

            startGatewayMonitorService();
            openChat();
        });
    }

    private void openChat() {
        Intent intent = new Intent(this, ai.clawphones.agent.chat.ChatActivity.class);
        startActivity(intent);
        finish();
    }

    private void startGatewayMonitorService() {
        try {
            ClawPhonesApp.startGatewayMonitorService(this);
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to start monitor service: " + e.getMessage());
        }
    }

    private void setStatus(String status) {
        mStatusText.setText(status);
        mProgress.setVisibility(View.VISIBLE);
    }

    private void showError(String message) {
        mErrorText.setText(message);
        mErrorText.setVisibility(View.VISIBLE);
        mRetryButton.setVisibility(View.VISIBLE);
        mManualSetupButton.setVisibility(View.VISIBLE);
        mSkipToChatButton.setVisibility(View.VISIBLE);
        mProgress.setVisibility(View.GONE);
    }

    private void hideError() {
        mErrorText.setVisibility(View.GONE);
        mRetryButton.setVisibility(View.GONE);
        mManualSetupButton.setVisibility(View.GONE);
        mSkipToChatButton.setVisibility(View.GONE);
        mProgress.setVisibility(View.VISIBLE);
    }
}
