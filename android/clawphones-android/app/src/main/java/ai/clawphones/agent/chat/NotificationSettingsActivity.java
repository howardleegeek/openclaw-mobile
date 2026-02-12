package ai.clawphones.agent.chat;

import android.app.TimePickerDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.SwitchCompat;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.NotificationManagerCompat;

import com.termux.R;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Notification Settings Activity - Notification preferences and quiet hours management.
 *
 * Features:
 * - Notification permission status indicator
 * - Category toggles: community, tasks, edge compute, security
 * - Quiet hours with start/end time pickers
 * - Sound selection and vibration toggle
 * - Auto-save with SharedPreferences
 *
 * Matches iOS: NotificationSettingsView.swift
 */
public class NotificationSettingsActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "notification_settings";

    // Preference keys (matching iOS UserDefaults keys)
    private static final String PREF_COMMUNITY = "notif_community";
    private static final String PREF_TASKS = "notif_tasks";
    private static final String PREF_EDGE = "notif_edge";
    private static final String PREF_SECURITY = "notif_security";
    private static final String PREF_QUIET_ENABLED = "notif_quiet_enabled";
    private static final String PREF_QUIET_START_HOUR = "notif_quiet_start_hour";
    private static final String PREF_QUIET_START_MINUTE = "notif_quiet_start_minute";
    private static final String PREF_QUIET_END_HOUR = "notif_quiet_end_hour";
    private static final String PREF_QUIET_END_MINUTE = "notif_quiet_end_minute";
    private static final String PREF_SOUND_ENABLED = "notif_sound_enabled";
    private static final String PREF_SOUND_TYPE = "notif_sound_type";
    private static final String PREF_VIBRATION = "notif_vibration";

    // Sound options (matching iOS NotificationSound enum)
    private static final String[] SOUND_VALUES = {
        "default", "chime", "bell", "ping", "silent"
    };
    private static final String[] SOUND_LABELS = {
        "Default", "Chime", "Bell", "Ping", "Silent"
    };

    // UI Components - Permission Status
    private TextView mPermissionIcon;
    private TextView mPermissionTitle;
    private TextView mPermissionDesc;
    private TextView mPermissionAction;

    // UI Components - Category Toggles
    private SwitchCompat mCommunitySwitch;
    private SwitchCompat mTasksSwitch;
    private SwitchCompat mEdgeComputeSwitch;
    private SwitchCompat mSecuritySwitch;

    // UI Components - Quiet Hours
    private SwitchCompat mQuietHoursSwitch;
    private LinearLayout mQuietHoursDetails;
    private TextView mQuietStartTime;
    private TextView mQuietEndTime;
    private TextView mQuietHoursDesc;

    // UI Components - Sound & Vibration
    private SwitchCompat mSoundSwitch;
    private LinearLayout mSoundPickerContainer;
    private Spinner mSoundSpinner;
    private SwitchCompat mVibrationSwitch;

    // UI Components - Save
    private TextView mSaveButton;
    private ProgressBar mSaveProgress;

    // State
    private int mQuietStartHour = 22;
    private int mQuietStartMinute = 0;
    private int mQuietEndHour = 8;
    private int mQuietEndMinute = 0;
    private volatile boolean mSaving = false;

    private SharedPreferences mPrefs;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat mTimeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        setupLayout();
        loadSettings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatus();
    }

    // ==================== Layout Setup ====================

    private void setupLayout() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
        ));
        scrollView.setBackgroundColor(getColor(android.R.color.background_light));

        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(0, 0, 0, 48);

        // Title bar
        TextView titleBar = new TextView(this);
        titleBar.setText("Notification Settings");
        titleBar.setTextSize(22);
        titleBar.setTypeface(null, android.graphics.Typeface.BOLD);
        titleBar.setPadding(24, 48, 24, 24);
        titleBar.setBackgroundColor(getColor(android.R.color.white));
        mainLayout.addView(titleBar);

        // Section 1: Permission Status
        mainLayout.addView(createPermissionSection());

        // Section 2: Notification Categories
        mainLayout.addView(createSectionHeader("Notification Categories"));
        mainLayout.addView(createCategorySection());
        mainLayout.addView(createSectionFooter("Select the types of notifications you want to receive."));

        // Section 3: Quiet Hours
        mainLayout.addView(createSectionHeader("Quiet Hours"));
        mainLayout.addView(createQuietHoursSection());
        mainLayout.addView(createSectionFooter("During quiet hours, notifications except security alerts will be silenced."));

        // Section 4: Sound & Vibration
        mainLayout.addView(createSectionHeader("Sound & Vibration"));
        mainLayout.addView(createSoundSection());
        mainLayout.addView(createSectionFooter("Customize notification sound and vibration feedback."));

        // Section 5: Save Button
        mainLayout.addView(createSaveSection());

        scrollView.addView(mainLayout);
        setContentView(scrollView);
    }

    // ==================== Section: Permission Status ====================

    private View createPermissionSection() {
        LinearLayout card = createCard();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(24, 24, 24, 24);

        // Icon
        mPermissionIcon = new TextView(this);
        mPermissionIcon.setTextSize(28);
        mPermissionIcon.setPadding(0, 0, 16, 0);
        card.addView(mPermissionIcon);

        // Text container
        LinearLayout textLayout = new LinearLayout(this);
        textLayout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        );
        textLayout.setLayoutParams(textParams);

        mPermissionTitle = new TextView(this);
        mPermissionTitle.setTextSize(16);
        mPermissionTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        textLayout.addView(mPermissionTitle);

        mPermissionDesc = new TextView(this);
        mPermissionDesc.setTextSize(12);
        mPermissionDesc.setTextColor(getColor(android.R.color.darker_gray));
        mPermissionDesc.setPadding(0, 4, 0, 0);
        textLayout.addView(mPermissionDesc);

        card.addView(textLayout);

        // Action button
        mPermissionAction = new TextView(this);
        mPermissionAction.setTextSize(14);
        mPermissionAction.setTypeface(null, android.graphics.Typeface.BOLD);
        mPermissionAction.setTextColor(getColor(android.R.color.holo_blue_dark));
        mPermissionAction.setPadding(16, 8, 16, 8);
        mPermissionAction.setVisibility(View.GONE);
        mPermissionAction.setOnClickListener(v -> openNotificationSettings());
        card.addView(mPermissionAction);

        updatePermissionStatus();
        return card;
    }

    private void updatePermissionStatus() {
        boolean enabled = NotificationManagerCompat.from(this).areNotificationsEnabled();

        if (mPermissionIcon != null) {
            mPermissionIcon.setText(enabled ? "\u2705" : "\u26a0\ufe0f");
        }
        if (mPermissionTitle != null) {
            mPermissionTitle.setText(enabled ? "Push Notifications Enabled" : "Push Notifications Disabled");
        }
        if (mPermissionDesc != null) {
            mPermissionDesc.setText(enabled
                    ? "You will receive important notifications"
                    : "Go to system settings to enable notifications");
        }
        if (mPermissionAction != null) {
            mPermissionAction.setVisibility(enabled ? View.GONE : View.VISIBLE);
            mPermissionAction.setText("Settings");
        }
    }

    private void openNotificationSettings() {
        android.content.Intent intent = new android.content.Intent();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            intent.setAction(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
        } else {
            intent.setAction("android.settings.APP_NOTIFICATION_SETTINGS");
            intent.putExtra("app_package", getPackageName());
            intent.putExtra("app_uid", getApplicationInfo().uid);
        }
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Unable to open notification settings", Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== Section: Notification Categories ====================

    private View createCategorySection() {
        LinearLayout card = createCard();

        mCommunitySwitch = createCategoryRow(card,
                "\ud83d\udc65", "Community Alerts",
                "Community messages and activity notifications",
                true);

        addDivider(card);

        mTasksSwitch = createCategoryRow(card,
                "\ud83d\udcdd", "Task Notifications",
                "Task assignment, completion and expiry reminders",
                true);

        addDivider(card);

        mEdgeComputeSwitch = createCategoryRow(card,
                "\ud83d\udda5\ufe0f", "Edge Compute",
                "Compute task status updates",
                true);

        addDivider(card);

        mSecuritySwitch = createCategoryRow(card,
                "\ud83d\udee1\ufe0f", "Security Alerts",
                "Device security and anomaly activity alerts",
                true);

        return card;
    }

    private SwitchCompat createCategoryRow(LinearLayout parent, String icon, String title, String desc, boolean defaultOn) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(24, 16, 24, 16);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        // Icon
        TextView iconView = new TextView(this);
        iconView.setText(icon);
        iconView.setTextSize(22);
        iconView.setPadding(0, 0, 16, 0);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        iconView.setLayoutParams(iconParams);
        row.addView(iconView);

        // Text container
        LinearLayout textLayout = new LinearLayout(this);
        textLayout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        );
        textLayout.setLayoutParams(textParams);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(15);
        textLayout.addView(titleView);

        TextView descView = new TextView(this);
        descView.setText(desc);
        descView.setTextSize(12);
        descView.setTextColor(getColor(android.R.color.darker_gray));
        descView.setPadding(0, 2, 0, 0);
        textLayout.addView(descView);

        row.addView(textLayout);

        // Switch
        SwitchCompat toggle = new SwitchCompat(this);
        toggle.setChecked(defaultOn);
        row.addView(toggle);

        parent.addView(row);
        return toggle;
    }

    // ==================== Section: Quiet Hours ====================

    private View createQuietHoursSection() {
        LinearLayout card = createCard();

        // Enable toggle
        LinearLayout enableRow = new LinearLayout(this);
        enableRow.setOrientation(LinearLayout.HORIZONTAL);
        enableRow.setPadding(24, 16, 24, 16);
        enableRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView enableLabel = new TextView(this);
        enableLabel.setText("Enable Quiet Hours");
        enableLabel.setTextSize(15);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        );
        enableLabel.setLayoutParams(labelParams);
        enableRow.addView(enableLabel);

        mQuietHoursSwitch = new SwitchCompat(this);
        mQuietHoursSwitch.setChecked(false);
        mQuietHoursSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mQuietHoursDetails.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });
        enableRow.addView(mQuietHoursSwitch);

        card.addView(enableRow);

        // Details container (hidden by default)
        mQuietHoursDetails = new LinearLayout(this);
        mQuietHoursDetails.setOrientation(LinearLayout.VERTICAL);
        mQuietHoursDetails.setVisibility(View.GONE);

        addDivider(mQuietHoursDetails);

        // Start time row
        mQuietStartTime = createTimePickerRow(mQuietHoursDetails, "Start Time", mQuietStartHour, mQuietStartMinute,
                (hour, minute) -> {
                    mQuietStartHour = hour;
                    mQuietStartMinute = minute;
                    updateQuietStartTimeDisplay();
                    updateQuietHoursDescription();
                });

        addDivider(mQuietHoursDetails);

        // End time row
        mQuietEndTime = createTimePickerRow(mQuietHoursDetails, "End Time", mQuietEndHour, mQuietEndMinute,
                (hour, minute) -> {
                    mQuietEndHour = hour;
                    mQuietEndMinute = minute;
                    updateQuietEndTimeDisplay();
                    updateQuietHoursDescription();
                });

        addDivider(mQuietHoursDetails);

        // Description row
        LinearLayout descRow = new LinearLayout(this);
        descRow.setOrientation(LinearLayout.HORIZONTAL);
        descRow.setPadding(24, 12, 24, 12);
        descRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView moonIcon = new TextView(this);
        moonIcon.setText("\ud83c\udf19");
        moonIcon.setTextSize(16);
        moonIcon.setPadding(0, 0, 8, 0);
        descRow.addView(moonIcon);

        mQuietHoursDesc = new TextView(this);
        mQuietHoursDesc.setTextSize(13);
        mQuietHoursDesc.setTextColor(getColor(android.R.color.darker_gray));
        descRow.addView(mQuietHoursDesc);

        mQuietHoursDetails.addView(descRow);
        card.addView(mQuietHoursDetails);

        updateQuietHoursDescription();

        return card;
    }

    private TextView createTimePickerRow(LinearLayout parent, String label, int defaultHour, int defaultMinute,
                                         OnTimeSetCallback callback) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(24, 16, 24, 16);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(15);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        );
        labelView.setLayoutParams(labelParams);
        row.addView(labelView);

        TextView timeValue = new TextView(this);
        timeValue.setText(formatTime(defaultHour, defaultMinute));
        timeValue.setTextSize(15);
        timeValue.setTextColor(getColor(android.R.color.holo_blue_dark));
        timeValue.setPadding(16, 8, 16, 8);
        timeValue.setOnClickListener(v -> {
            new TimePickerDialog(NotificationSettingsActivity.this,
                    (view, hourOfDay, minute) -> {
                        timeValue.setText(formatTime(hourOfDay, minute));
                        callback.onTimeSet(hourOfDay, minute);
                    },
                    defaultHour, defaultMinute, true
            ).show();
        });
        row.addView(timeValue);

        parent.addView(row);
        return timeValue;
    }

    private void updateQuietStartTimeDisplay() {
        if (mQuietStartTime != null) {
            mQuietStartTime.setText(formatTime(mQuietStartHour, mQuietStartMinute));
        }
    }

    private void updateQuietEndTimeDisplay() {
        if (mQuietEndTime != null) {
            mQuietEndTime.setText(formatTime(mQuietEndHour, mQuietEndMinute));
        }
    }

    private void updateQuietHoursDescription() {
        if (mQuietHoursDesc != null) {
            mQuietHoursDesc.setText("Quiet hours: "
                    + formatTime(mQuietStartHour, mQuietStartMinute) + " - "
                    + formatTime(mQuietEndHour, mQuietEndMinute));
        }
    }

    // ==================== Section: Sound & Vibration ====================

    private View createSoundSection() {
        LinearLayout card = createCard();

        // Sound enable toggle
        LinearLayout soundRow = new LinearLayout(this);
        soundRow.setOrientation(LinearLayout.HORIZONTAL);
        soundRow.setPadding(24, 16, 24, 16);
        soundRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView soundLabel = new TextView(this);
        soundLabel.setText("Enable Sound");
        soundLabel.setTextSize(15);
        LinearLayout.LayoutParams soundLabelParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        );
        soundLabel.setLayoutParams(soundLabelParams);
        soundRow.addView(soundLabel);

        mSoundSwitch = new SwitchCompat(this);
        mSoundSwitch.setChecked(true);
        mSoundSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mSoundPickerContainer.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });
        soundRow.addView(mSoundSwitch);

        card.addView(soundRow);

        // Sound picker container
        mSoundPickerContainer = new LinearLayout(this);
        mSoundPickerContainer.setOrientation(LinearLayout.VERTICAL);

        addDivider(mSoundPickerContainer);

        // Sound picker row
        LinearLayout pickerRow = new LinearLayout(this);
        pickerRow.setOrientation(LinearLayout.HORIZONTAL);
        pickerRow.setPadding(24, 12, 24, 12);
        pickerRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView pickerLabel = new TextView(this);
        pickerLabel.setText("Notification Sound");
        pickerLabel.setTextSize(15);
        LinearLayout.LayoutParams pickerLabelParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        );
        pickerLabel.setLayoutParams(pickerLabelParams);
        pickerRow.addView(pickerLabel);

        mSoundSpinner = new Spinner(this);
        ArrayAdapter<String> soundAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, SOUND_LABELS
        );
        soundAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mSoundSpinner.setAdapter(soundAdapter);
        pickerRow.addView(mSoundSpinner);

        mSoundPickerContainer.addView(pickerRow);
        card.addView(mSoundPickerContainer);

        addDivider(card);

        // Vibration toggle
        LinearLayout vibrationRow = new LinearLayout(this);
        vibrationRow.setOrientation(LinearLayout.HORIZONTAL);
        vibrationRow.setPadding(24, 16, 24, 16);
        vibrationRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView vibrationLabel = new TextView(this);
        vibrationLabel.setText("Enable Vibration");
        vibrationLabel.setTextSize(15);
        LinearLayout.LayoutParams vibrationLabelParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        );
        vibrationLabel.setLayoutParams(vibrationLabelParams);
        vibrationRow.addView(vibrationLabel);

        mVibrationSwitch = new SwitchCompat(this);
        mVibrationSwitch.setChecked(true);
        vibrationRow.addView(mVibrationSwitch);

        card.addView(vibrationRow);

        return card;
    }

    // ==================== Section: Save ====================

    private View createSaveSection() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setPadding(24, 32, 24, 24);
        container.setGravity(android.view.Gravity.CENTER);

        mSaveProgress = new ProgressBar(this);
        mSaveProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        progressParams.setMargins(0, 0, 12, 0);
        mSaveProgress.setLayoutParams(progressParams);
        container.addView(mSaveProgress);

        mSaveButton = new TextView(this);
        mSaveButton.setText("Save Settings");
        mSaveButton.setTextSize(16);
        mSaveButton.setTypeface(null, android.graphics.Typeface.BOLD);
        mSaveButton.setTextColor(getColor(android.R.color.white));
        mSaveButton.setBackgroundColor(getColor(android.R.color.holo_blue_dark));
        mSaveButton.setPadding(48, 16, 48, 16);
        mSaveButton.setGravity(android.view.Gravity.CENTER);
        mSaveButton.setOnClickListener(v -> saveSettings());
        container.addView(mSaveButton);

        return container;
    }

    // ==================== Settings Persistence ====================

    private void loadSettings() {
        mCommunitySwitch.setChecked(mPrefs.getBoolean(PREF_COMMUNITY, true));
        mTasksSwitch.setChecked(mPrefs.getBoolean(PREF_TASKS, true));
        mEdgeComputeSwitch.setChecked(mPrefs.getBoolean(PREF_EDGE, true));
        mSecuritySwitch.setChecked(mPrefs.getBoolean(PREF_SECURITY, true));

        boolean quietEnabled = mPrefs.getBoolean(PREF_QUIET_ENABLED, false);
        mQuietHoursSwitch.setChecked(quietEnabled);
        mQuietHoursDetails.setVisibility(quietEnabled ? View.VISIBLE : View.GONE);

        mQuietStartHour = mPrefs.getInt(PREF_QUIET_START_HOUR, 22);
        mQuietStartMinute = mPrefs.getInt(PREF_QUIET_START_MINUTE, 0);
        mQuietEndHour = mPrefs.getInt(PREF_QUIET_END_HOUR, 8);
        mQuietEndMinute = mPrefs.getInt(PREF_QUIET_END_MINUTE, 0);
        updateQuietStartTimeDisplay();
        updateQuietEndTimeDisplay();
        updateQuietHoursDescription();

        boolean soundEnabled = mPrefs.getBoolean(PREF_SOUND_ENABLED, true);
        mSoundSwitch.setChecked(soundEnabled);
        mSoundPickerContainer.setVisibility(soundEnabled ? View.VISIBLE : View.GONE);

        String soundType = mPrefs.getString(PREF_SOUND_TYPE, "default");
        int soundIndex = 0;
        for (int i = 0; i < SOUND_VALUES.length; i++) {
            if (SOUND_VALUES[i].equals(soundType)) {
                soundIndex = i;
                break;
            }
        }
        mSoundSpinner.setSelection(soundIndex, false);

        mVibrationSwitch.setChecked(mPrefs.getBoolean(PREF_VIBRATION, true));
    }

    private void saveSettings() {
        if (mSaving) return;

        boolean notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled();
        if (!notificationsEnabled) {
            Toast.makeText(this, "Please enable notifications in system settings first", Toast.LENGTH_LONG).show();
            return;
        }

        mSaving = true;
        mSaveButton.setText("Saving...");
        mSaveButton.setEnabled(false);
        mSaveProgress.setVisibility(View.VISIBLE);

        // Simulate API call delay (matching iOS behavior)
        mMainHandler.postDelayed(() -> {
            SharedPreferences.Editor editor = mPrefs.edit();

            // Category toggles
            editor.putBoolean(PREF_COMMUNITY, mCommunitySwitch.isChecked());
            editor.putBoolean(PREF_TASKS, mTasksSwitch.isChecked());
            editor.putBoolean(PREF_EDGE, mEdgeComputeSwitch.isChecked());
            editor.putBoolean(PREF_SECURITY, mSecuritySwitch.isChecked());

            // Quiet hours
            editor.putBoolean(PREF_QUIET_ENABLED, mQuietHoursSwitch.isChecked());
            editor.putInt(PREF_QUIET_START_HOUR, mQuietStartHour);
            editor.putInt(PREF_QUIET_START_MINUTE, mQuietStartMinute);
            editor.putInt(PREF_QUIET_END_HOUR, mQuietEndHour);
            editor.putInt(PREF_QUIET_END_MINUTE, mQuietEndMinute);

            // Sound & vibration
            editor.putBoolean(PREF_SOUND_ENABLED, mSoundSwitch.isChecked());
            int selectedSoundIndex = mSoundSpinner.getSelectedItemPosition();
            if (selectedSoundIndex >= 0 && selectedSoundIndex < SOUND_VALUES.length) {
                editor.putString(PREF_SOUND_TYPE, SOUND_VALUES[selectedSoundIndex]);
            }
            editor.putBoolean(PREF_VIBRATION, mVibrationSwitch.isChecked());

            editor.apply();

            mSaving = false;
            mSaveButton.setText("Save Settings");
            mSaveButton.setEnabled(true);
            mSaveProgress.setVisibility(View.GONE);

            Toast.makeText(NotificationSettingsActivity.this,
                    "Notification settings saved", Toast.LENGTH_SHORT).show();
        }, 1000);
    }

    // ==================== UI Helper Methods ====================

    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(getColor(android.R.color.white));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, 0);
        card.setLayoutParams(params);
        return card;
    }

    private View createSectionHeader(String title) {
        TextView header = new TextView(this);
        header.setText(title);
        header.setTextSize(13);
        header.setTypeface(null, android.graphics.Typeface.BOLD);
        header.setTextColor(getColor(android.R.color.darker_gray));
        header.setPadding(24, 32, 24, 8);
        header.setAllCaps(true);
        return header;
    }

    private View createSectionFooter(String text) {
        TextView footer = new TextView(this);
        footer.setText(text);
        footer.setTextSize(12);
        footer.setTextColor(getColor(android.R.color.darker_gray));
        footer.setPadding(24, 8, 24, 0);
        return footer;
    }

    private void addDivider(LinearLayout parent) {
        View divider = new View(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
        );
        params.setMargins(24, 0, 0, 0);
        divider.setLayoutParams(params);
        divider.setBackgroundColor(getColor(android.R.color.darker_gray));
        divider.setAlpha(0.2f);
        parent.addView(divider);
    }

    private String formatTime(int hour, int minute) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        return mTimeFormat.format(cal.getTime());
    }

    // ==================== Callback Interface ====================

    private interface OnTimeSetCallback {
        void onTimeSet(int hourOfDay, int minute);
    }
}
