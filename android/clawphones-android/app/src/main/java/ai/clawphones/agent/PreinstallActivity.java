package ai.clawphones.agent;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
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
 * "傻瓜可用" 预装引导：自动安装 OpenClaw + 自动写配置 + 自动启动 gateway。
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
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, ClawPhonesService.class);
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

    private void maybeStartFlow() {
        if (!mBound || mService == null) return;
        if (mStarted) return;
        mStarted = true;
        runFlow();
    }

    private void runFlow() {
        // 0) Ensure bootstrap extracted
        if (!ClawPhonesService.isBootstrapInstalled()) {
            setStatus("正在初始化环境…");
            TermuxInstaller.setupBootstrapIfNeeded(this, this::runFlow);
            return;
        }

        // 1) Ensure OpenClaw installed
        if (!ClawPhonesService.isOpenclawInstalled()) {
            setStatus("正在安装（约 1-3 分钟）…");
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
                    showError("安装失败。\n\n" + error);
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
        setStatus("正在激活…");
        boolean provisioned = PreinstallProvisioner.ensureProvisioned(this);
        if (!provisioned) {
            showError("缺少出厂 Token。\n\n工厂需要写入系统属性：persist.oyster.device_token\n（调试：也可以写入 app 私有文件 device_token.txt）");
            return;
        }

        // 3) Start gateway (best effort; agent CLI can fallback to embedded if needed).
        setStatus("正在启动…");
        mService.startGateway(result -> {
            if (!result.success) {
                Logger.logError(LOG_TAG, "Gateway start failed: " + result.stdout);
                showError("启动失败。\n\n" + result.stdout);
                return;
            }

            startGatewayMonitorService();
            openChat();
        });
    }

    private void openChat() {
        Intent intent = new Intent(this, ChatActivity.class);
        startActivity(intent);
        finish();
    }

    private void startGatewayMonitorService() {
        try {
            Intent serviceIntent = new Intent(this, GatewayMonitorService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
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
        mProgress.setVisibility(View.GONE);
    }

    private void hideError() {
        mErrorText.setVisibility(View.GONE);
        mRetryButton.setVisibility(View.GONE);
        mManualSetupButton.setVisibility(View.GONE);
        mProgress.setVisibility(View.VISIBLE);
    }
}
