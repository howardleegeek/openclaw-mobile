package ai.clawphones.agent;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import ai.clawphones.agent.chat.ClawPhonesAPI;

import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * Dashboard activity - main screen after setup is complete.
 * Shows gateway status, connected channels, and control buttons.
 * Auto-refreshes status every 5 seconds.
 */
public class DashboardActivity extends Activity {

    private static final String LOG_TAG = "DashboardActivity";
    public static final String NOTIFICATION_CHANNEL_ID = "clawphones_gateway";
    private static final int STATUS_REFRESH_INTERVAL_MS = 5000; // 5 seconds

    private TextView mStatusText;
    private TextView mUptimeText;
    private View mStatusIndicator;
    private TextView mTelegramStatus;
    private TextView mDiscordStatus;
    private Button mStartButton;
    private Button mStopButton;
    private Button mRestartButton;
    private View mSshCard;
    private TextView mSshInfoText;
    private View mUpdateBanner;
    private TextView mUpdateBannerText;

    private ClawPhonesService mClawPhonesService;
    private boolean mBound = false;
    private Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mStatusRefreshRunnable;

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            ClawPhonesService.LocalBinder binder = (ClawPhonesService.LocalBinder) service;
            mClawPhonesService = binder.getService();
            mBound = true;
            Logger.logDebug(LOG_TAG, "Service connected");
            
            // Start status refresh
            startStatusRefresh();
            
            // Start gateway monitor service
            startGatewayMonitorService();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBound = false;
            mClawPhonesService = null;
            Logger.logDebug(LOG_TAG, "Service disconnected");
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_clawphones_dashboard);

        // Create notification channel
        createNotificationChannel();

        // Initialize views
        mStatusText = findViewById(R.id.status_text);
        mUptimeText = findViewById(R.id.uptime_text);
        mStatusIndicator = findViewById(R.id.status_indicator);
        mTelegramStatus = findViewById(R.id.telegram_status);
        mDiscordStatus = findViewById(R.id.discord_status);
        mStartButton = findViewById(R.id.btn_start);
        mStopButton = findViewById(R.id.btn_stop);
        mRestartButton = findViewById(R.id.btn_restart);
        Button openTerminalButton = findViewById(R.id.btn_open_terminal);
        Button openAiChatButton = findViewById(R.id.btn_ai_chat);

        // Setup button listeners
        mStartButton.setOnClickListener(v -> startGateway());
        mStopButton.setOnClickListener(v -> stopGateway());
        mRestartButton.setOnClickListener(v -> restartGateway());
        openTerminalButton.setOnClickListener(v -> openTerminal());
        openAiChatButton.setOnClickListener(v -> openAiChat());

        mSshCard = findViewById(R.id.ssh_card);
        mSshInfoText = findViewById(R.id.ssh_info_text);

        // Update banner
        mUpdateBanner = findViewById(R.id.update_banner);
        mUpdateBannerText = findViewById(R.id.update_banner_text);

        // Load channel info
        loadChannelInfo();

        // Load SSH info
        loadSshInfo();

        // Bind to service lazily after app-level deferred init has been queued.
        if (!ClawPhonesApp.bindClawPhonesService(this, mConnection)) {
            Logger.logError(LOG_TAG, "Failed to bind ClawPhonesService");
        }

        // Check for app updates (also picks up results from launcher check)
        UpdateChecker.check(this, (latestVersion, downloadUrl, notes) -> showUpdateBanner(latestVersion, downloadUrl));

        // Also check stored result in case launcher already fetched it
        String[] stored = UpdateChecker.getAvailableUpdate(this);
        if (stored != null) {
            showUpdateBanner(stored[0], stored[1]);
        }
    }

    private void openAiChat() {
        String token = ClawPhonesAPI.getToken(this);
        Class<?> target = (token != null && !token.trim().isEmpty())
            ? ai.clawphones.agent.chat.ChatActivity.class
            : ai.clawphones.agent.chat.LoginActivity.class;
        startActivity(new Intent(this, target));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        // Cancel all pending callbacks to prevent memory leak
        mHandler.removeCallbacksAndMessages(null);
        mStatusRefreshRunnable = null;
        
        // Unbind from service
        if (mBound) {
            unbindService(mConnection);
            mBound = false;
        }
    }

    /**
     * Create notification channel for gateway monitor service
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.dashboard_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.dashboard_notification_channel_desc));
            channel.setShowBadge(false);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * Start the gateway monitor service
     */
    private void startGatewayMonitorService() {
        ClawPhonesApp.startGatewayMonitorService(this);
    }

    /**
     * Start periodic status refresh
     */
    private void startStatusRefresh() {
        mStatusRefreshRunnable = new Runnable() {
            @Override
            public void run() {
                refreshStatus();
                mHandler.postDelayed(this, STATUS_REFRESH_INTERVAL_MS);
            }
        };
        mHandler.post(mStatusRefreshRunnable);
    }

    /**
     * Refresh gateway status and uptime
     */
    private void refreshStatus() {
        if (!mBound || mClawPhonesService == null) {
            return;
        }

        // Check if gateway is running
        mClawPhonesService.isGatewayRunning(result -> {
            boolean isRunning = result.success && result.stdout.trim().equals("running");
            updateStatusUI(isRunning);

            // Get uptime if running
            if (isRunning) {
                mClawPhonesService.getGatewayUptime(uptimeResult -> {
                    if (uptimeResult.success) {
                        String uptime = uptimeResult.stdout.trim();
                        if (!uptime.equals("—")) {
                            mUptimeText.setText(getString(R.string.dashboard_uptime, uptime));
                        } else {
                            mUptimeText.setText("—");
                        }
                    }
                });
            }
        });
    }

    /**
     * Update the status UI based on gateway state
     */
    private void updateStatusUI(boolean isRunning) {
        if (isRunning) {
            mStatusText.setText(getString(R.string.dashboard_status_running));
            mStatusIndicator.setBackgroundResource(R.drawable.status_indicator_running);
            setButtonState(mStartButton, false, true);
            setButtonState(mStopButton, true, false);
            setButtonState(mRestartButton, true, true);
        } else {
            mStatusText.setText(getString(R.string.dashboard_status_stopped));
            mStatusIndicator.setBackgroundResource(R.drawable.status_indicator_stopped);
            mUptimeText.setText("—");
            setButtonState(mStartButton, true, true);
            setButtonState(mStopButton, false, false);
            setButtonState(mRestartButton, false, true);
        }
    }

    private void setButtonState(Button button, boolean enabled, boolean isFilled) {
        button.setEnabled(enabled);
        if (enabled) {
            button.setAlpha(1.0f);
            button.setTextColor(isFilled ? ContextCompat.getColor(this, R.color.clawphones_background) : ContextCompat.getColor(this, R.color.clawphones_accent));
        } else {
            button.setAlpha(0.5f);
            button.setTextColor(ContextCompat.getColor(this, R.color.clawphones_secondary_text));
        }
    }

    private void showUpdateBanner(String latestVersion, String downloadUrl) {
        mUpdateBannerText.setText(getString(R.string.dashboard_update_available, latestVersion));
        mUpdateBanner.setVisibility(View.VISIBLE);

        findViewById(R.id.btn_update_download).setOnClickListener(v -> {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl));
            startActivity(browserIntent);
        });

        findViewById(R.id.btn_update_dismiss).setOnClickListener(v -> {
            mUpdateBanner.setVisibility(View.GONE);
            UpdateChecker.dismiss(this, latestVersion);
        });
    }

    /**
     * Load channel configuration and update UI
     */
    private void loadChannelInfo() {
        try {
            JSONObject config = ClawPhonesConfig.readConfig();
            if (config.has("channels")) {
                JSONObject channels = config.getJSONObject("channels");

                // Check Telegram
                if (channels.has("telegram")) {
                    mTelegramStatus.setText(getString(R.string.dashboard_channel_connected));
                    mTelegramStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connected));
                } else {
                    mTelegramStatus.setText(getString(R.string.dashboard_channel_disconnected));
                    mTelegramStatus.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected));
                }

                // Check Discord
                if (channels.has("discord")) {
                    mDiscordStatus.setText(getString(R.string.dashboard_channel_connected));
                    mDiscordStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connected));
                } else {
                    mDiscordStatus.setText(getString(R.string.dashboard_channel_disconnected));
                    mDiscordStatus.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected));
                }
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to load channel info: " + e.getMessage());
        }
    }

    /**
     * Start the gateway
     */
    private void startGateway() {
        if (!mBound || mClawPhonesService == null) {
            return;
        }

        Toast.makeText(this, getString(R.string.dashboard_toast_starting_gateway), Toast.LENGTH_SHORT).show();
        mStartButton.setEnabled(false);

        mClawPhonesService.startGateway(result -> {
            if (result.success) {
                Toast.makeText(this, getString(R.string.dashboard_toast_gateway_started), Toast.LENGTH_SHORT).show();
                refreshStatus();
            } else {
                Toast.makeText(this, getString(R.string.dashboard_toast_start_gateway_failed), Toast.LENGTH_SHORT).show();
                mStartButton.setEnabled(true);
                Logger.logError(LOG_TAG, "Start failed: " + result.stderr);
            }
        });
    }

    /**
     * Stop the gateway
     */
    private void stopGateway() {
        if (!mBound || mClawPhonesService == null) {
            return;
        }

        Toast.makeText(this, getString(R.string.dashboard_toast_stopping_gateway), Toast.LENGTH_SHORT).show();
        mStopButton.setEnabled(false);

        mClawPhonesService.stopGateway(result -> {
            if (result.success) {
                Toast.makeText(this, getString(R.string.dashboard_toast_gateway_stopped), Toast.LENGTH_SHORT).show();
                refreshStatus();
            } else {
                Toast.makeText(this, getString(R.string.dashboard_toast_stop_gateway_failed), Toast.LENGTH_SHORT).show();
                mStopButton.setEnabled(true);
                Logger.logError(LOG_TAG, "Stop failed: " + result.stderr);
            }
        });
    }

    /**
     * Restart the gateway
     */
    private void restartGateway() {
        if (!mBound || mClawPhonesService == null) {
            return;
        }

        Toast.makeText(this, getString(R.string.dashboard_toast_restarting_gateway), Toast.LENGTH_SHORT).show();
        mRestartButton.setEnabled(false);

        mClawPhonesService.restartGateway(result -> {
            if (result.success) {
                Toast.makeText(this, getString(R.string.dashboard_toast_gateway_restarted), Toast.LENGTH_SHORT).show();
                refreshStatus();
            } else {
                Toast.makeText(this, getString(R.string.dashboard_toast_restart_gateway_failed), Toast.LENGTH_SHORT).show();
                mRestartButton.setEnabled(true);
                Logger.logError(LOG_TAG, "Restart failed: " + result.stderr);
            }
        });
    }

    /**
     * Load SSH connection info and display in the dashboard
     */
    private void loadSshInfo() {
        String ip = getDeviceIp();
        if (ip == null) ip = getString(R.string.dashboard_device_ip_placeholder);

        // Read SSH password from file
        String password = readSshPassword();
        if (password == null) password = getString(R.string.dashboard_password_not_set);

        mSshInfoText.setText(getString(R.string.dashboard_ssh_info, ip, password));
        mSshCard.setVisibility(View.VISIBLE);
    }

    private String readSshPassword() {
        try {
            java.io.File pwFile = new java.io.File(
                TermuxConstants.TERMUX_HOME_DIR_PATH + "/.ssh_password");
            if (pwFile.exists()) {
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(pwFile));
                String password = reader.readLine();
                reader.close();
                if (password != null) return password.trim();
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to read SSH password: " + e.getMessage());
        }
        return null;
    }

    private String getDeviceIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to get device IP: " + e.getMessage());
        }
        return null;
    }

    /**
     * Open terminal activity
     */
    private void openTerminal() {
        Intent intent = new Intent(this, TermuxActivity.class);
        startActivity(intent);
    }
}
