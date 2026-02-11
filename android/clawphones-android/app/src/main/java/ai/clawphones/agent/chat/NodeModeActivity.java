package ai.clawphones.agent.chat;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.termux.R;

import java.util.Locale;

/**
 * Node Mode UI for controlling ClawVision camera node foreground service.
 */
public class NodeModeActivity extends AppCompatActivity {

    private static final int REQUEST_NODE_PERMISSIONS = 8810;

    private final String[] mFrameRateOptions = new String[]{"0.5", "1", "2"};
    private final String[] mQualityOptions = new String[]{"Low", "Medium", "High"};
    private final String[] mSensitivityOptions = new String[]{"Low", "Medium", "High"};
    private final String[] mVoiceResponseOptions = new String[]{"deterrent", "welcome", "recording", "custom"};

    private SwitchMaterial mNodeSwitch;
    private View mCameraDot;
    private View mGpsDot;
    private View mUploadDot;
    private TextView mFramesValue;
    private TextView mEventsValue;
    private TextView mUptimeValue;

    private boolean mIgnoreSwitchChanges = false;
    private boolean mPendingStartAfterPermission = false;
    private boolean mReceiverRegistered = false;

    private final BroadcastReceiver mStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            if (!NodeModeService.ACTION_STATUS_BROADCAST.equals(intent.getAction())) return;
            bindServiceState(intent);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_node_mode);

        mNodeSwitch = findViewById(R.id.node_mode_switch);
        mCameraDot = findViewById(R.id.node_mode_camera_dot);
        mGpsDot = findViewById(R.id.node_mode_gps_dot);
        mUploadDot = findViewById(R.id.node_mode_upload_dot);
        mFramesValue = findViewById(R.id.node_mode_frames_value);
        mEventsValue = findViewById(R.id.node_mode_events_value);
        mUptimeValue = findViewById(R.id.node_mode_uptime_value);

        Button settingsButton = findViewById(R.id.node_mode_settings_btn);
        settingsButton.setOnClickListener(v -> showSettingsDialog());
        Button coverageButton = findViewById(R.id.node_mode_coverage_btn);
        if (coverageButton != null) {
            coverageButton.setOnClickListener(v -> startActivity(new Intent(this, CoverageMapActivity.class)));
        }
        Button historyButton = findViewById(R.id.node_mode_history_btn);
        if (historyButton != null) {
            historyButton.setOnClickListener(v -> startActivity(new Intent(this, AlertHistoryActivity.class)));
        }

        mNodeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (mIgnoreSwitchChanges) return;
            if (isChecked) {
                startNodeModeWithPermissionCheck();
            } else {
                mPendingStartAfterPermission = false;
                stopNodeModeService();
            }
        });

        renderIdleState();
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerStatusReceiver();
        requestNodeModeStatus();
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterStatusReceiver();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_NODE_PERMISSIONS) return;

        boolean granted = hasCameraPermission() && hasLocationPermission();
        if (granted) {
            if (mPendingStartAfterPermission) {
                startNodeModeService();
            }
        } else {
            toast(getString(R.string.node_mode_permission_denied));
            setSwitchChecked(false);
        }

        mPendingStartAfterPermission = false;
    }

    private void startNodeModeWithPermissionCheck() {
        if (!hasCameraPermission() || !hasLocationPermission()) {
            mPendingStartAfterPermission = true;
            requestNodePermissions();
            setSwitchChecked(false);
            return;
        }

        startNodeModeService();
    }

    private void startNodeModeService() {
        try {
            Intent startIntent = new Intent(this, NodeModeService.class);
            startIntent.setAction(NodeModeService.ACTION_START);
            ContextCompat.startForegroundService(this, startIntent);
            setSwitchChecked(true);
        } catch (Exception e) {
            toast(getString(R.string.node_mode_start_failed));
            setSwitchChecked(false);
        }
    }

    private void stopNodeModeService() {
        Intent stopIntent = new Intent(this, NodeModeService.class);
        stopIntent.setAction(NodeModeService.ACTION_STOP);
        startService(stopIntent);
    }

    private void requestNodeModeStatus() {
        Intent queryIntent = new Intent(this, NodeModeService.class);
        queryIntent.setAction(NodeModeService.ACTION_QUERY_STATUS);
        startService(queryIntent);
    }

    private void registerStatusReceiver() {
        if (mReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(NodeModeService.ACTION_STATUS_BROADCAST);
        ContextCompat.registerReceiver(this, mStatusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        mReceiverRegistered = true;
    }

    private void unregisterStatusReceiver() {
        if (!mReceiverRegistered) return;
        unregisterReceiver(mStatusReceiver);
        mReceiverRegistered = false;
    }

    private void bindServiceState(Intent intent) {
        boolean running = intent.getBooleanExtra(NodeModeService.EXTRA_IS_RUNNING, false);
        int cameraStatus = intent.getIntExtra(NodeModeService.EXTRA_CAMERA_STATUS, NodeModeService.STATUS_GRAY);
        int gpsStatus = intent.getIntExtra(NodeModeService.EXTRA_GPS_STATUS, NodeModeService.STATUS_GRAY);
        int uploadStatus = intent.getIntExtra(NodeModeService.EXTRA_UPLOAD_STATUS, NodeModeService.STATUS_GRAY);
        long frames = intent.getLongExtra(NodeModeService.EXTRA_FRAMES_CAPTURED, 0L);
        long events = intent.getLongExtra(NodeModeService.EXTRA_EVENTS_DETECTED, 0L);
        long uptimeMs = intent.getLongExtra(NodeModeService.EXTRA_UPTIME_MS, 0L);

        setSwitchChecked(running);
        setDotState(mCameraDot, cameraStatus);
        setDotState(mGpsDot, gpsStatus);
        setDotState(mUploadDot, uploadStatus);

        mFramesValue.setText(String.valueOf(frames));
        mEventsValue.setText(String.valueOf(events));
        mUptimeValue.setText(formatDuration(uptimeMs));
    }

    private void renderIdleState() {
        setSwitchChecked(false);
        setDotState(mCameraDot, NodeModeService.STATUS_GRAY);
        setDotState(mGpsDot, NodeModeService.STATUS_GRAY);
        setDotState(mUploadDot, NodeModeService.STATUS_GRAY);
        mFramesValue.setText("0");
        mEventsValue.setText("0");
        mUptimeValue.setText("00:00:00");
    }

    private void setSwitchChecked(boolean checked) {
        mIgnoreSwitchChanges = true;
        mNodeSwitch.setChecked(checked);
        mIgnoreSwitchChanges = false;
    }

    private void setDotState(View dotView, int state) {
        int resId;
        switch (state) {
            case NodeModeService.STATUS_GREEN:
                resId = R.drawable.node_status_dot_green;
                break;
            case NodeModeService.STATUS_RED:
                resId = R.drawable.node_status_dot_red;
                break;
            default:
                resId = R.drawable.node_status_dot_gray;
                break;
        }
        dotView.setBackgroundResource(resId);
    }

    private void showSettingsDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_node_settings, null, false);

        EditText relayUrlInput = dialogView.findViewById(R.id.node_settings_relay_url);
        Spinner frameRateSpinner = dialogView.findViewById(R.id.node_settings_frame_rate);
        Spinner jpegQualitySpinner = dialogView.findViewById(R.id.node_settings_jpeg_quality);
        Spinner motionSensitivitySpinner = dialogView.findViewById(R.id.node_settings_motion_sensitivity);
        SeekBar minBatterySeekBar = dialogView.findViewById(R.id.node_settings_min_battery_seek);
        TextView minBatteryValue = dialogView.findViewById(R.id.node_settings_min_battery_value);
        SwitchCompat wifiOnlySwitch = dialogView.findViewById(R.id.node_settings_wifi_only);
        SwitchCompat voiceEnabledSwitch = dialogView.findViewById(R.id.node_settings_voice_enabled);
        Spinner voiceResponseSpinner = dialogView.findViewById(R.id.node_settings_voice_response);
        EditText voiceCustomMessageInput = dialogView.findViewById(R.id.node_settings_voice_custom_message);
        SwitchCompat voiceOnlyPersonSwitch = dialogView.findViewById(R.id.node_settings_voice_only_person);
        SeekBar voiceCooldownSeekBar = dialogView.findViewById(R.id.node_settings_voice_cooldown_seek);
        TextView voiceCooldownValue = dialogView.findViewById(R.id.node_settings_voice_cooldown_value);

        String[] voiceResponseLabels = new String[]{
            getString(R.string.node_mode_voice_response_deterrent),
            getString(R.string.node_mode_voice_response_welcome),
            getString(R.string.node_mode_voice_response_recording),
            getString(R.string.node_mode_voice_response_custom)
        };

        bindSpinner(frameRateSpinner, mFrameRateOptions);
        bindSpinner(jpegQualitySpinner, mQualityOptions);
        bindSpinner(motionSensitivitySpinner, mSensitivityOptions);
        bindSpinner(voiceResponseSpinner, voiceResponseLabels);

        SharedPreferences prefs = getSettings();
        String relayUrl = prefs.getString(NodeModeService.PREF_RELAY_URL, NodeModeService.DEFAULT_RELAY_URL);
        String frameRate = prefs.getString(NodeModeService.PREF_FRAME_RATE, NodeModeService.DEFAULT_FRAME_RATE);
        String jpegQuality = prefs.getString(NodeModeService.PREF_JPEG_QUALITY, NodeModeService.DEFAULT_JPEG_QUALITY);
        String motionSensitivity = prefs.getString(
            NodeModeService.PREF_MOTION_SENSITIVITY,
            NodeModeService.DEFAULT_MOTION_SENSITIVITY
        );
        int minBattery = prefs.getInt(NodeModeService.PREF_MIN_BATTERY, NodeModeService.DEFAULT_MIN_BATTERY);
        boolean wifiOnly = prefs.getBoolean(NodeModeService.PREF_WIFI_ONLY, NodeModeService.DEFAULT_WIFI_ONLY);
        boolean voiceEnabled = prefs.getBoolean(
            NodeModeService.PREF_VOICE_ENABLED,
            NodeModeService.DEFAULT_VOICE_ENABLED
        );
        String voiceResponseType = prefs.getString(
            NodeModeService.PREF_VOICE_RESPONSE_TYPE,
            NodeModeService.DEFAULT_VOICE_RESPONSE_TYPE
        );
        String voiceCustomMessage = prefs.getString(
            NodeModeService.PREF_VOICE_CUSTOM_MESSAGE,
            NodeModeService.DEFAULT_VOICE_CUSTOM_MESSAGE
        );
        boolean voiceOnlyPerson = prefs.getBoolean(
            NodeModeService.PREF_VOICE_ONLY_PERSON,
            NodeModeService.DEFAULT_VOICE_ONLY_PERSON
        );
        int voiceCooldownSeconds = prefs.getInt(
            NodeModeService.PREF_VOICE_COOLDOWN_SECONDS,
            NodeModeService.DEFAULT_VOICE_COOLDOWN_SECONDS
        );

        relayUrlInput.setText(relayUrl);
        selectSpinnerValue(frameRateSpinner, mFrameRateOptions, frameRate);
        selectSpinnerValue(jpegQualitySpinner, mQualityOptions, jpegQuality);
        selectSpinnerValue(motionSensitivitySpinner, mSensitivityOptions, motionSensitivity);
        selectSpinnerValue(voiceResponseSpinner, mVoiceResponseOptions, voiceResponseType);

        minBattery = clamp(minBattery, 10, 50);
        minBatterySeekBar.setMax(40); // range 10-50
        minBatterySeekBar.setProgress(minBattery - 10);
        minBatteryValue.setText(getString(R.string.node_mode_settings_min_battery_value, minBattery));
        minBatterySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = progress + 10;
                minBatteryValue.setText(getString(R.string.node_mode_settings_min_battery_value, value));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        wifiOnlySwitch.setChecked(wifiOnly);
        voiceEnabledSwitch.setChecked(voiceEnabled);
        voiceCustomMessageInput.setText(voiceCustomMessage == null ? "" : voiceCustomMessage);
        voiceOnlyPersonSwitch.setChecked(voiceOnlyPerson);

        voiceCooldownSeconds = clamp(voiceCooldownSeconds, 10, 300);
        voiceCooldownSeekBar.setMax(290); // 10-300
        voiceCooldownSeekBar.setProgress(voiceCooldownSeconds - 10);
        voiceCooldownValue.setText(
            getString(R.string.node_mode_voice_cooldown_value, voiceCooldownSeconds)
        );
        voiceCooldownSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = progress + 10;
                voiceCooldownValue.setText(getString(R.string.node_mode_voice_cooldown_value, value));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        Runnable updateVoiceCustomState = () -> {
            int selected = safeSpinnerIndex(voiceResponseSpinner, mVoiceResponseOptions.length);
            boolean isCustom = "custom".equalsIgnoreCase(mVoiceResponseOptions[selected]);
            boolean enabled = voiceEnabledSwitch.isChecked() && isCustom;
            voiceCustomMessageInput.setEnabled(enabled);
            voiceCustomMessageInput.setAlpha(enabled ? 1f : 0.5f);
        };

        Runnable updateVoiceSectionState = () -> {
            boolean enabled = voiceEnabledSwitch.isChecked();
            voiceResponseSpinner.setEnabled(enabled);
            voiceOnlyPersonSwitch.setEnabled(enabled);
            voiceCooldownSeekBar.setEnabled(enabled);
            voiceCooldownValue.setAlpha(enabled ? 1f : 0.6f);
            updateVoiceCustomState.run();
        };

        voiceEnabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> updateVoiceSectionState.run());
        voiceResponseSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateVoiceCustomState.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                updateVoiceCustomState.run();
            }
        });
        updateVoiceSectionState.run();

        new AlertDialog.Builder(this)
            .setTitle(R.string.node_mode_settings_title)
            .setView(dialogView)
            .setNegativeButton(R.string.node_mode_settings_cancel, null)
            .setPositiveButton(R.string.node_mode_settings_save, (dialog, which) -> {
                String newRelayUrl = safeTrim(relayUrlInput.getText().toString());
                if (TextUtils.isEmpty(newRelayUrl)) {
                    newRelayUrl = NodeModeService.DEFAULT_RELAY_URL;
                }

                int selectedFrameRate = safeSpinnerIndex(frameRateSpinner, mFrameRateOptions.length);
                int selectedJpegQuality = safeSpinnerIndex(jpegQualitySpinner, mQualityOptions.length);
                int selectedMotionSensitivity = safeSpinnerIndex(motionSensitivitySpinner, mSensitivityOptions.length);
                int selectedVoiceResponse = safeSpinnerIndex(voiceResponseSpinner, mVoiceResponseOptions.length);

                getSettings().edit()
                    .putString(NodeModeService.PREF_RELAY_URL, newRelayUrl)
                    .putString(NodeModeService.PREF_FRAME_RATE, mFrameRateOptions[selectedFrameRate])
                    .putString(NodeModeService.PREF_JPEG_QUALITY, mQualityOptions[selectedJpegQuality])
                    .putString(NodeModeService.PREF_MOTION_SENSITIVITY, mSensitivityOptions[selectedMotionSensitivity])
                    .putInt(NodeModeService.PREF_MIN_BATTERY, minBatterySeekBar.getProgress() + 10)
                    .putBoolean(NodeModeService.PREF_WIFI_ONLY, wifiOnlySwitch.isChecked())
                    .putBoolean(NodeModeService.PREF_VOICE_ENABLED, voiceEnabledSwitch.isChecked())
                    .putString(NodeModeService.PREF_VOICE_RESPONSE_TYPE, mVoiceResponseOptions[selectedVoiceResponse])
                    .putString(NodeModeService.PREF_VOICE_CUSTOM_MESSAGE, safeTrim(voiceCustomMessageInput.getText().toString()))
                    .putBoolean(NodeModeService.PREF_VOICE_ONLY_PERSON, voiceOnlyPersonSwitch.isChecked())
                    .putInt(NodeModeService.PREF_VOICE_COOLDOWN_SECONDS, voiceCooldownSeekBar.getProgress() + 10)
                    .apply();

                toast(getString(R.string.node_mode_settings_saved));
                requestNodeModeStatus();
            })
            .show();
    }

    private void bindSpinner(Spinner spinner, String[] values) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_item,
            values
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    private void selectSpinnerValue(Spinner spinner, String[] values, @Nullable String target) {
        if (target == null) {
            spinner.setSelection(0);
            return;
        }

        String normalizedTarget = target.trim().toLowerCase(Locale.US);
        for (int i = 0; i < values.length; i++) {
            if (values[i].trim().toLowerCase(Locale.US).equals(normalizedTarget)) {
                spinner.setSelection(i);
                return;
            }
        }
        spinner.setSelection(0);
    }

    private int safeSpinnerIndex(@NonNull Spinner spinner, int optionLength) {
        if (optionLength <= 0) return 0;
        int selected = spinner.getSelectedItemPosition();
        if (selected < 0 || selected >= optionLength) return 0;
        return selected;
    }

    private void requestNodePermissions() {
        ActivityCompat.requestPermissions(
            this,
            new String[]{
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            },
            REQUEST_NODE_PERMISSIONS
        );
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
            || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            == PackageManager.PERMISSION_GRANTED;
    }

    private SharedPreferences getSettings() {
        return getSharedPreferences(NodeModeService.PREFS_NAME, MODE_PRIVATE);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private String safeTrim(@Nullable String text) {
        return text == null ? "" : text.trim();
    }

    private String formatDuration(long durationMs) {
        long totalSeconds = Math.max(0L, durationMs / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
    }
}
