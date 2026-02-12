package ai.clawphones.agent.chat;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.SwitchCompat;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Privacy Center Activity - Main interface for privacy management.
 * Includes sections for data collection, retention, export, deletion, and audit logs.
 */
public class PrivacyCenterActivity extends AppCompatActivity {

    private PrivacyService privacyService;
    private PrivacyConfig.PrivacySettings currentSettings;

    // UI Components - Data Collection
    private SwitchCompat faceBlurSwitch;
    private SwitchCompat plateBlurSwitch;
    private Spinner locationPrecisionSpinner;

    // UI Components - Data Retention
    private TextView retentionDaysText;
    private androidx.appcompat.widget.AppCompatSeekBar retentionSeekBar;

    // UI Components - Data Export
    private Button requestExportButton;
    private ProgressBar exportProgressBar;
    private TextView exportStatusText;
    private String currentExportRequestId;

    // UI Components - Delete Account
    private Button deleteAccountButton;

    // UI Components - Audit Log
    private Button auditLogFilterButton;
    private RecyclerView auditLogRecyclerView;
    private AuditLogAdapter auditLogAdapter;
    private List<PrivacyConfig.PrivacyAuditLog> auditLogs = new ArrayList<>();

    private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        privacyService = new PrivacyService(this);
        setupLayout();
        loadPrivacySettings();
        loadAuditLogs();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (privacyService != null) {
            privacyService.shutdown();
        }
    }

    /**
     * Setup the ScrollView layout with all privacy sections
     */
    private void setupLayout() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
        ));
        scrollView.setPadding(0, 16, 0, 16);

        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(24, 24, 24, 24);

        // Header
        TextView titleView = createHeader("privacy_center_title");
        mainLayout.addView(titleView);

        // Section 1: Data Collection
        mainLayout.addView(createSectionDivider());
        mainLayout.addView(createSectionTitle("data_collection_title"));
        mainLayout.addView(createDataCollectionSection());

        // Section 2: Data Retention
        mainLayout.addView(createSectionDivider());
        mainLayout.addView(createSectionTitle("data_retention_title"));
        mainLayout.addView(createDataRetentionSection());

        // Section 3: Data Export
        mainLayout.addView(createSectionDivider());
        mainLayout.addView(createSectionTitle("data_export_title"));
        mainLayout.addView(createDataExportSection());

        // Section 4: Delete Account
        mainLayout.addView(createSectionDivider());
        mainLayout.addView(createSectionTitle("delete_account_title"));
        mainLayout.addView(createDeleteAccountSection());

        // Section 5: Audit Log
        mainLayout.addView(createSectionDivider());
        mainLayout.addView(createSectionTitle("audit_log_title"));
        mainLayout.addView(createAuditLogSection());

        scrollView.addView(mainLayout);
        setContentView(scrollView);
    }

    // ==================== Helper UI Methods ====================

    private TextView createHeader(String textKey) {
        TextView title = new TextView(this);
        title.setText(R.string.privacy_center_title);
        title.setTextSize(24);
        title.setTextColor(getColor(android.R.color.black));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, 24);
        return title;
    }

    private View createSectionDivider() {
        View divider = new View(this);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                2
        ));
        divider.setBackgroundColor(getColor(android.R.color.darker_gray));
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) divider.getLayoutParams();
        params.setMargins(0, 32, 0, 16);
        divider.setLayoutParams(params);
        return divider;
    }

    private TextView createSectionTitle(String textKey) {
        TextView title = new TextView(this);
        title.setText(getResourceString(textKey));
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, 16);
        return title;
    }

    // ==================== Section: Data Collection ====================

    private View createDataCollectionSection() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(0, 0, 0, 16);

        // Face Blur Toggle
        faceBlurSwitch = createToggleRow(layout, "face_blur_title", "face_blur_description");
        faceBlurSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> updateSettings());

        // Plate Blur Toggle
        plateBlurSwitch = createToggleRow(layout, "plate_blur_title", "plate_blur_description");
        plateBlurSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> updateSettings());

        // Location Precision Spinner
        TextView locationLabel = createLabel("location_precision_title");
        layout.addView(locationLabel);

        locationPrecisionSpinner = new Spinner(this);
        ArrayAdapter<PrivacyConfig.LocationPrecision> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, PrivacyConfig.LocationPrecision.values()
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        locationPrecisionSpinner.setAdapter(adapter);
        locationPrecisionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateSettings();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        layout.addView(locationPrecisionSpinner);

        return layout;
    }

    private SwitchCompat createToggleRow(LinearLayout parent, String titleKey, String descKey) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout textLayout = new LinearLayout(this);
        textLayout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        );
        textParams.setMargins(0, 8, 0, 8);
        textLayout.setLayoutParams(textParams);

        TextView title = new TextView(this);
        title.setText(getResourceString(titleKey));
        title.setTextSize(16);
        textLayout.addView(title);

        TextView desc = new TextView(this);
        desc.setText(getResourceString(descKey));
        desc.setTextSize(12);
        desc.setTextColor(getColor(android.R.color.darker_gray));
        textLayout.addView(desc);

        SwitchCompat toggle = new SwitchCompat(this);
        toggle.setChecked(true);

        row.addView(textLayout);
        row.addView(toggle);
        parent.addView(row);

        return toggle;
    }

    // ==================== Section: Data Retention ====================

    private View createDataRetentionSection() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(0, 0, 0, 16);

        TextView description = new TextView(this);
        description.setText(getResourceString("retention_description"));
        description.setTextSize(14);
        layout.addView(description);

        retentionDaysText = new TextView(this);
        retentionDaysText.setText(getResourceString("retention_days", "30"));
        retentionDaysText.setTextSize(16);
        retentionDaysText.setTypeface(null, android.graphics.Typeface.BOLD);
        retentionDaysText.setPadding(0, 16, 0, 8);
        layout.addView(retentionDaysText);

        retentionSeekBar = new androidx.appcompat.widget.AppCompatSeekBar(this);
        retentionSeekBar.setMax(365);
        retentionSeekBar.setProgress(30);
        retentionSeekBar.setOnSeekBarChangeListener(new androidx.appcompat.widget.AppCompatSeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(androidx.appcompat.widget.AppCompatSeekBar seekBar, int progress, boolean fromUser) {
                retentionDaysText.setText(getResourceString("retention_days", String.valueOf(progress)));
                if (fromUser) {
                    updateSettings();
                }
            }
            @Override
            public void onStartTrackingTouch(androidx.appcompat.widget.AppCompatSeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(androidx.appcompat.widget.AppCompatSeekBar seekBar) {
                updateSettings();
            }
        });
        layout.addView(retentionSeekBar);

        return layout;
    }

    // ==================== Section: Data Export ====================

    private View createDataExportSection() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(0, 0, 0, 16);

        TextView description = new TextView(this);
        description.setText(getResourceString("export_description"));
        description.setTextSize(14);
        layout.addView(description);

        requestExportButton = new Button(this);
        requestExportButton.setText(getResourceString("request_export_button"));
        requestExportButton.setOnClickListener(v -> requestDataExport());
        layout.addView(requestExportButton);

        exportProgressBar = new ProgressBar(this);
        exportProgressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 16, 0, 0);
        exportProgressBar.setLayoutParams(params);
        layout.addView(exportProgressBar);

        exportStatusText = new TextView(this);
        exportStatusText.setVisibility(View.GONE);
        exportStatusText.setTextSize(14);
        layout.addView(exportStatusText);

        return layout;
    }

    private void requestDataExport() {
        ProgressDialog progressDialog = new ProgressDialog(this);
        progressDialog.setMessage(getResourceString("export_processing"));
        progressDialog.setCancelable(false);
        progressDialog.show();

        privacyService.requestDataExport(new PrivacyService.PrivacyCallback<PrivacyConfig.DataExportRequest>() {
            @Override
            public void onSuccess(PrivacyConfig.DataExportRequest request) {
                progressDialog.dismiss();
                currentExportRequestId = request.getRequestId();
                startExportStatusPolling();
                Toast.makeText(PrivacyCenterActivity.this,
                        getResourceString("export_requested"), Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(Exception e) {
                progressDialog.dismiss();
                Toast.makeText(PrivacyCenterActivity.this,
                        getResourceString("export_error") + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void startExportStatusPolling() {
        exportProgressBar.setVisibility(View.VISIBLE);
        exportStatusText.setVisibility(View.VISIBLE);
        requestExportButton.setEnabled(false);

        pollExportStatus();
    }

    private void pollExportStatus() {
        if (currentExportRequestId == null) return;

        privacyService.checkExportStatus(currentExportRequestId,
                new PrivacyService.PrivacyCallback<PrivacyConfig.DataExportRequest>() {
            @Override
            public void onSuccess(PrivacyConfig.DataExportRequest request) {
                if ("completed".equals(request.getStatus())) {
                    exportProgressBar.setVisibility(View.GONE);
                    exportStatusText.setText(getResourceString("export_ready"));

                    Button downloadButton = new Button(PrivacyCenterActivity.this);
                    downloadButton.setText(getResourceString("download_export"));
                    downloadButton.setOnClickListener(v -> openDownloadLink(request.getDownloadUrl()));
                    ((LinearLayout) exportStatusText.getParent()).addView(downloadButton);
                } else if ("processing".equals(request.getStatus())) {
                    exportStatusText.setText(getResourceString("export_processing"));
                    // Poll again after 3 seconds
                    new android.os.Handler().postDelayed(() -> pollExportStatus(), 3000);
                } else {
                    exportProgressBar.setVisibility(View.GONE);
                    exportStatusText.setText(getResourceString("export_failed"));
                    requestExportButton.setEnabled(true);
                }
            }

            @Override
            public void onError(Exception e) {
                exportProgressBar.setVisibility(View.GONE);
                exportStatusText.setText(getResourceString("export_error"));
                requestExportButton.setEnabled(true);
            }
        });
    }

    private void openDownloadLink(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        startActivity(intent);
    }

    // ==================== Section: Delete Account ====================

    private View createDeleteAccountSection() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(0, 0, 0, 16);

        TextView warning = new TextView(this);
        warning.setText(getResourceString("delete_warning"));
        warning.setTextSize(14);
        warning.setTextColor(getColor(android.R.color.holo_red_dark));
        layout.addView(warning);

        deleteAccountButton = new Button(this);
        deleteAccountButton.setText(getResourceString("delete_account_button"));
        deleteAccountButton.setBackgroundColor(getColor(android.R.color.holo_red_dark));
        deleteAccountButton.setTextColor(getColor(android.R.color.white));
        deleteAccountButton.setOnClickListener(v -> showDeleteAccountDialog());
        layout.addView(deleteAccountButton);

        return layout;
    }

    private void showDeleteAccountDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getResourceString("delete_confirm_title"));
        builder.setMessage(getResourceString("delete_confirm_message"));

        View dialogView = LayoutInflater.from(this).inflate(
                R.layout.dialog_delete_account, null);

        EditText confirmInput = dialogView.findViewById(R.id.confirmInput);
        builder.setView(dialogView);

        builder.setPositiveButton(getResourceString("confirm"), (dialog, which) -> {
            String input = confirmInput.getText().toString();
            if ("CONFIRM".equals(input)) {
                executeAccountDeletion();
            } else {
                Toast.makeText(this, getResourceString("delete_wrong_confirm"), Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNegativeButton(getResourceString("cancel"), null);
        builder.show();
    }

    private void executeAccountDeletion() {
        ProgressDialog progressDialog = new ProgressDialog(this);
        progressDialog.setMessage(getResourceString("delete_processing"));
        progressDialog.setCancelable(false);
        progressDialog.show();

        privacyService.deleteAllData("CONFIRM", new PrivacyService.PrivacyCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                progressDialog.dismiss();
                Toast.makeText(PrivacyCenterActivity.this,
                        getResourceString("delete_success"), Toast.LENGTH_LONG).show();
                finish();
            }

            @Override
            public void onError(Exception e) {
                progressDialog.dismiss();
                Toast.makeText(PrivacyCenterActivity.this,
                        getResourceString("delete_error") + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    // ==================== Section: Audit Log ====================

    private View createAuditLogSection() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(0, 0, 0, 16);

        auditLogFilterButton = new Button(this);
        auditLogFilterButton.setText(getResourceString("filter_audit_log"));
        auditLogFilterButton.setOnClickListener(v -> showAuditFilterDialog());
        layout.addView(auditLogFilterButton);

        auditLogRecyclerView = new RecyclerView(this);
        auditLogRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        auditLogAdapter = new AuditLogAdapter(auditLogs);
        auditLogRecyclerView.setAdapter(auditLogAdapter);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                400
        );
        params.setMargins(0, 16, 0, 0);
        auditLogRecyclerView.setLayoutParams(params);
        layout.addView(auditLogRecyclerView);

        return layout;
    }

    private void loadAuditLogs() {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, -30);
        Date startDate = cal.getTime();
        Date endDate = new Date();

        privacyService.fetchAuditLog(startDate, endDate,
                new PrivacyService.PrivacyCallback<List<PrivacyConfig.PrivacyAuditLog>>() {
            @Override
            public void onSuccess(List<PrivacyConfig.PrivacyAuditLog> logs) {
                auditLogs.clear();
                auditLogs.addAll(logs);
                auditLogAdapter.notifyDataSetChanged();
            }

            @Override
            public void onError(Exception e) {
                Toast.makeText(PrivacyCenterActivity.this,
                        getResourceString("audit_error") + ": " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showAuditFilterDialog() {
        Calendar cal = Calendar.getInstance();

        new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            Calendar selected = Calendar.getInstance();
            selected.set(year, month, dayOfMonth);
            loadAuditLogFromDate(selected.getTime());
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void loadAuditLogFromDate(Date date) {
        privacyService.fetchAuditLog(date, new Date(),
                new PrivacyService.PrivacyCallback<List<PrivacyConfig.PrivacyAuditLog>>() {
            @Override
            public void onSuccess(List<PrivacyConfig.PrivacyAuditLog> logs) {
                auditLogs.clear();
                auditLogs.addAll(logs);
                auditLogAdapter.notifyDataSetChanged();
            }

            @Override
            public void onError(Exception e) {
                Toast.makeText(PrivacyCenterActivity.this,
                        getResourceString("audit_error") + ": " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ==================== Settings Management ====================

    private void loadPrivacySettings() {
        privacyService.fetchSettings(new PrivacyService.PrivacyCallback<PrivacyConfig.PrivacySettings>() {
            @Override
            public void onSuccess(PrivacyConfig.PrivacySettings settings) {
                currentSettings = settings;
                updateUIWithSettings(settings);
            }

            @Override
            public void onError(Exception e) {
                Toast.makeText(PrivacyCenterActivity.this,
                        getResourceString("load_settings_error") + ": " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateUIWithSettings(PrivacyConfig.PrivacySettings settings) {
        faceBlurSwitch.setChecked(settings.isFaceBlurEnabled());
        plateBlurSwitch.setChecked(settings.isPlateBlurEnabled());

        int precisionPosition = 0;
        PrivacyConfig.LocationPrecision[] precisions = PrivacyConfig.LocationPrecision.values();
        for (int i = 0; i < precisions.length; i++) {
            if (precisions[i] == settings.getLocationPrecision()) {
                precisionPosition = i;
                break;
            }
        }
        locationPrecisionSpinner.setSelection(precisionPosition);

        retentionSeekBar.setProgress(settings.getDataRetentionDays());
        retentionDaysText.setText(getResourceString("retention_days", String.valueOf(settings.getDataRetentionDays())));
    }

    private void updateSettings() {
        if (currentSettings == null) {
            currentSettings = new PrivacyConfig.PrivacySettings();
        }

        currentSettings.setFaceBlurEnabled(faceBlurSwitch.isChecked());
        currentSettings.setPlateBlurEnabled(plateBlurSwitch.isChecked());
        currentSettings.setLocationPrecision((PrivacyConfig.LocationPrecision) locationPrecisionSpinner.getSelectedItem());
        currentSettings.setDataRetentionDays(retentionSeekBar.getProgress());

        privacyService.updateSettings(currentSettings, new PrivacyService.PrivacyCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                // Settings saved successfully
            }

            @Override
            public void onError(Exception e) {
                Toast.makeText(PrivacyCenterActivity.this,
                        getResourceString("save_settings_error") + ": " + e.getMessage(), Toast.LENGTH_SHORT).show();
                // Revert UI on error
                loadPrivacySettings();
            }
        });
    }

    // ==================== Utility Methods ====================

    private String getResourceString(String key) {
        int resourceId = getResources().getIdentifier(key, "string", getPackageName());
        return resourceId != 0 ? getString(resourceId) : key;
    }

    private String getResourceString(String key, String param) {
        int resourceId = getResources().getIdentifier(key, "string", getPackageName());
        return resourceId != 0 ? getString(resourceId, param) : key + ": " + param;
    }

    private TextView createLabel(String textKey) {
        TextView label = new TextView(this);
        label.setText(getResourceString(textKey));
        label.setTextSize(14);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        label.setPadding(0, 8, 0, 4);
        return label;
    }

    // ==================== Audit Log Adapter ====================

    private static class AuditLogAdapter extends RecyclerView.Adapter<AuditLogAdapter.ViewHolder> {
        private List<PrivacyConfig.PrivacyAuditLog> logs;

        AuditLogAdapter(List<PrivacyConfig.PrivacyAuditLog> logs) {
            this.logs = logs;
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(
                    R.layout.item_audit_log, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            PrivacyConfig.PrivacyAuditLog log = logs.get(position);
            holder.actionText.setText(log.getAction());
            holder.detailsText.setText(log.getDetails());

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            holder.timestampText.setText(sdf.format(log.getTimestamp()));

            holder.ipText.setText(log.getIpAddress() != null ? log.getIpAddress() : "N/A");
        }

        @Override
        public int getItemCount() {
            return logs.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView actionText;
            TextView detailsText;
            TextView timestampText;
            TextView ipText;

            ViewHolder(View itemView) {
                super(itemView);
                actionText = itemView.findViewById(R.id.actionText);
                detailsText = itemView.findViewById(R.id.detailsText);
                timestampText = itemView.findViewById(R.id.timestampText);
                ipText = itemView.findViewById(R.id.ipText);
            }
        }
    }
}
