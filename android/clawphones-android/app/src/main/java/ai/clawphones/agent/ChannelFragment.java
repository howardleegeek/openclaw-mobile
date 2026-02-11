package ai.clawphones.agent;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.termux.R;
import com.termux.shared.logger.Logger;

/**
 * Step 3 of setup: Channel setup (simplified)
 * 
 * Flow:
 * 1. User opens @ClawPhonesSetupBot to create bot and get User ID
 * 2. User enters Bot Token + User ID in app
 * 3. App configures and starts gateway
 */
public class ChannelFragment extends Fragment {

    private static final String LOG_TAG = "ChannelFragment";
    private static final String SETUP_BOT_URL = "https://t.me/ClawPhonesSetupBot";

    private Button mOpenSetupBotButton;
    private EditText mTokenInput;
    private EditText mUserIdInput;
    private Button mConnectButton;
    private TextView mErrorMessage;

    private ClawPhonesService mService;
    private boolean mBound = false;

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            ClawPhonesService.LocalBinder binder = (ClawPhonesService.LocalBinder) service;
            mService = binder.getService();
            mBound = true;
            Logger.logDebug(LOG_TAG, "Service connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBound = false;
            Logger.logDebug(LOG_TAG, "Service disconnected");
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_clawphones_channel, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Find views
        mOpenSetupBotButton = view.findViewById(R.id.channel_open_setup_bot);
        mTokenInput = view.findViewById(R.id.channel_token_input);
        mUserIdInput = view.findViewById(R.id.channel_userid_input);
        mConnectButton = view.findViewById(R.id.channel_connect_button);
        mErrorMessage = view.findViewById(R.id.channel_error_message);

        // Setup click handlers
        mOpenSetupBotButton.setOnClickListener(v -> openSetupBot());
        mConnectButton.setOnClickListener(v -> connect());

        Logger.logDebug(LOG_TAG, "ChannelFragment view created");
    }

    @Override
    public void onStart() {
        super.onStart();
        if (!ClawPhonesApp.bindClawPhonesService(requireActivity(), mConnection)) {
            Logger.logError(LOG_TAG, "Failed to bind ClawPhonesService");
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (mBound) {
            try {
                requireActivity().unbindService(mConnection);
            } catch (IllegalArgumentException e) {
                Logger.logDebug(LOG_TAG, "Service was already unbound");
            }
            mBound = false;
        }
    }

    private void openSetupBot() {
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(SETUP_BOT_URL));
        startActivity(browserIntent);
    }

    private void connect() {
        // Clear previous error
        mErrorMessage.setVisibility(View.GONE);

        String token = mTokenInput.getText().toString().trim();
        String userId = mUserIdInput.getText().toString().trim();

        // Validate inputs
        if (TextUtils.isEmpty(token)) {
            showError(getString(R.string.channel_error_enter_bot_token));
            return;
        }

        if (TextUtils.isEmpty(userId)) {
            showError(getString(R.string.channel_error_enter_user_id));
            return;
        }

        // Basic token format validation (Telegram bot tokens are like "123456789:ABC-DEF...")
        if (!token.matches("^\\d+:[A-Za-z0-9_-]+$")) {
            showError(getString(R.string.channel_error_invalid_bot_token_format));
            return;
        }

        // Basic User ID validation (should be numeric)
        if (!userId.matches("^\\d+$")) {
            showError(getString(R.string.channel_error_invalid_user_id_format));
            return;
        }

        // Disable button during processing
        mConnectButton.setEnabled(false);
        mConnectButton.setText(getString(R.string.channel_button_connecting));

        // Write channel config (Telegram)
        boolean success = ChannelSetupHelper.writeChannelConfig("telegram", token, userId);
        if (!success) {
            showError(getString(R.string.channel_error_write_config_failed));
            resetButton();
            return;
        }

        // Start gateway
        startGateway();
    }

    private void startGateway() {
        if (!mBound || mService == null) {
            showError(getString(R.string.channel_error_service_not_ready));
            resetButton();
            return;
        }

        Logger.logInfo(LOG_TAG, "Starting gateway...");

        mService.startGateway(result -> {
            // Check if fragment is still attached
            if (!isAdded() || getActivity() == null || getActivity().isFinishing()) {
                return;
            }
            
            requireActivity().runOnUiThread(() -> {
                // Double-check in UI thread
                if (!isAdded() || getActivity() == null || getActivity().isFinishing()) {
                    return;
                }
                
                if (result.success) {
                    Logger.logInfo(LOG_TAG, "Gateway started successfully");
                    Toast.makeText(requireContext(), getString(R.string.channel_toast_connected_starting), Toast.LENGTH_LONG).show();

                    // Setup complete, advance to next step
                    SetupActivity activity = (SetupActivity) getActivity();
                    if (activity != null && !activity.isFinishing()) {
                        activity.goToNextStep();
                    }
                } else {
                    Logger.logError(LOG_TAG, "Failed to start gateway: " + result.stderr);
                    
                    String errorMsg = result.stderr;
                    if (TextUtils.isEmpty(errorMsg)) {
                        errorMsg = result.stdout;
                    }
                    if (TextUtils.isEmpty(errorMsg)) {
                        errorMsg = getString(R.string.channel_error_unknown_with_exit_code, result.exitCode);
                    }
                    
                    showError(getString(R.string.channel_error_start_gateway, errorMsg));
                    resetButton();
                }
            });
        });
    }

    private void showError(String message) {
        mErrorMessage.setText(message);
        mErrorMessage.setVisibility(View.VISIBLE);
    }

    private void resetButton() {
        mConnectButton.setEnabled(true);
        mConnectButton.setText(getString(R.string.channel_button_connect_start));
    }
}
