package ai.clawphones.agent.chat;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import okio.ByteString;

/**
 * Live Alert Feed Activity - Real-time alert feed with WebSocket integration.
 *
 * Features:
 * - WebSocket real-time alert feed via WebSocketClient
 * - RecyclerView with alert items (type icon, title, time, location)
 * - Filter chips (All / Motion / Person / Vehicle / Sound / Community)
 * - Severity filter (All / Low / Medium / High / Critical)
 * - Connection status indicator with auto-reconnect
 * - Caps alerts at 100 entries (most recent first)
 *
 * Matches iOS: LiveAlertFeedView.swift
 */
public class LiveAlertFeedActivity extends AppCompatActivity {

    private static final String LOG_TAG = "LiveAlertFeed";
    private static final int MAX_ALERTS = 100;
    private static final String API_BASE_URL = "https://api.openclaw.ai";

    // Alert types (matching iOS LiveAlertType enum)
    private static final String TYPE_MOTION = "motion_detected";
    private static final String TYPE_PERSON = "person_detected";
    private static final String TYPE_VEHICLE = "vehicle_detected";
    private static final String TYPE_SOUND = "sound_alert";
    private static final String TYPE_COMMUNITY = "community_alert";

    // Severity levels (matching iOS AlertSeverity enum)
    private static final String SEVERITY_LOW = "low";
    private static final String SEVERITY_MEDIUM = "medium";
    private static final String SEVERITY_HIGH = "high";
    private static final String SEVERITY_CRITICAL = "critical";

    // Filter values
    private static final String FILTER_ALL = "all";

    // Type filter chips
    private static final String[] TYPE_FILTER_VALUES = {
            FILTER_ALL, TYPE_MOTION, TYPE_PERSON, TYPE_VEHICLE, TYPE_SOUND, TYPE_COMMUNITY
    };
    private static final String[] TYPE_FILTER_LABELS = {
            "All", "Motion", "Person", "Vehicle", "Sound", "Community"
    };

    // Severity filter chips
    private static final String[] SEVERITY_FILTER_VALUES = {
            FILTER_ALL, SEVERITY_LOW, SEVERITY_MEDIUM, SEVERITY_HIGH, SEVERITY_CRITICAL
    };
    private static final String[] SEVERITY_FILTER_LABELS = {
            "All", "Low", "Medium", "High", "Critical"
    };

    // UI Components
    private LinearLayout mTypeFilterContainer;
    private LinearLayout mSeverityFilterContainer;
    private RecyclerView mAlertRecycler;
    private LinearLayout mEmptyStateContainer;
    private TextView mEmptyIcon;
    private TextView mEmptyTitle;
    private TextView mEmptySubtitle;
    private TextView mConnectionStatus;
    private View mConnectionDot;

    // State
    private final List<LiveAlertItem> mAllAlerts = new ArrayList<>();
    private final List<LiveAlertItem> mFilteredAlerts = new ArrayList<>();
    private AlertFeedAdapter mAdapter;
    private WebSocketClient mWebSocketClient;
    private String mSelectedTypeFilter = FILTER_ALL;
    private String mSelectedSeverityFilter = FILTER_ALL;
    private boolean mIsConnected = false;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat mTimeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private final SimpleDateFormat mIso8601Format;

    // Track filter chip TextViews for highlight updates
    private final List<TextView> mTypeChips = new ArrayList<>();
    private final List<TextView> mSeverityChips = new ArrayList<>();

    {
        mIso8601Format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        mIso8601Format.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupLayout();
        connectWebSocket();
    }

    @Override
    protected void onDestroy() {
        disconnectWebSocket();
        super.onDestroy();
    }

    // ==================== Layout Setup ====================

    private void setupLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF2F2F7); // systemGroupedBackground

        // Title bar with connection status
        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setPadding(24, 48, 24, 16);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("Live Alerts");
        title.setTextSize(22);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        );
        title.setLayoutParams(titleParams);
        titleBar.addView(title);

        // Connection status indicator
        LinearLayout statusContainer = new LinearLayout(this);
        statusContainer.setOrientation(LinearLayout.HORIZONTAL);
        statusContainer.setGravity(Gravity.CENTER_VERTICAL);
        statusContainer.setPadding(16, 8, 16, 8);

        GradientDrawable statusBg = new GradientDrawable();
        statusBg.setCornerRadius(24);
        statusBg.setColor(0xFFF2F2F7);
        statusContainer.setBackground(statusBg);

        mConnectionDot = new View(this);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(10, 10);
        dotParams.setMargins(0, 0, 8, 0);
        mConnectionDot.setLayoutParams(dotParams);
        GradientDrawable dotShape = new GradientDrawable();
        dotShape.setShape(GradientDrawable.OVAL);
        dotShape.setColor(Color.GRAY);
        mConnectionDot.setBackground(dotShape);
        statusContainer.addView(mConnectionDot);

        mConnectionStatus = new TextView(this);
        mConnectionStatus.setText("Offline");
        mConnectionStatus.setTextSize(12);
        mConnectionStatus.setTextColor(Color.GRAY);
        statusContainer.addView(mConnectionStatus);

        titleBar.addView(statusContainer);
        root.addView(titleBar);

        // Filter section
        LinearLayout filterSection = new LinearLayout(this);
        filterSection.setOrientation(LinearLayout.VERTICAL);
        filterSection.setPadding(16, 12, 16, 12);
        filterSection.setBackgroundColor(0xFFF2F2F7);

        // Type filter row
        LinearLayout typeFilterRow = new LinearLayout(this);
        typeFilterRow.setOrientation(LinearLayout.HORIZONTAL);
        typeFilterRow.setGravity(Gravity.CENTER_VERTICAL);
        typeFilterRow.setPadding(0, 0, 0, 8);

        TextView typeLabel = new TextView(this);
        typeLabel.setText("Type");
        typeLabel.setTextSize(13);
        typeLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        typeLabel.setTextColor(0xFF8E8E93);
        typeLabel.setPadding(8, 0, 12, 0);
        typeFilterRow.addView(typeLabel);

        HorizontalScrollView typeScroll = new HorizontalScrollView(this);
        typeScroll.setHorizontalScrollBarEnabled(false);
        mTypeFilterContainer = new LinearLayout(this);
        mTypeFilterContainer.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < TYPE_FILTER_VALUES.length; i++) {
            TextView chip = createFilterChip(TYPE_FILTER_LABELS[i], TYPE_FILTER_VALUES[i], true);
            mTypeFilterContainer.addView(chip);
            mTypeChips.add(chip);
        }
        typeScroll.addView(mTypeFilterContainer);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        );
        typeScroll.setLayoutParams(scrollParams);
        typeFilterRow.addView(typeScroll);
        filterSection.addView(typeFilterRow);

        // Severity filter row
        LinearLayout severityFilterRow = new LinearLayout(this);
        severityFilterRow.setOrientation(LinearLayout.HORIZONTAL);
        severityFilterRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView severityLabel = new TextView(this);
        severityLabel.setText("Severity");
        severityLabel.setTextSize(13);
        severityLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        severityLabel.setTextColor(0xFF8E8E93);
        severityLabel.setPadding(8, 0, 12, 0);
        severityFilterRow.addView(severityLabel);

        HorizontalScrollView severityScroll = new HorizontalScrollView(this);
        severityScroll.setHorizontalScrollBarEnabled(false);
        mSeverityFilterContainer = new LinearLayout(this);
        mSeverityFilterContainer.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < SEVERITY_FILTER_VALUES.length; i++) {
            TextView chip = createFilterChip(SEVERITY_FILTER_LABELS[i], SEVERITY_FILTER_VALUES[i], false);
            mSeverityFilterContainer.addView(chip);
            mSeverityChips.add(chip);
        }
        severityScroll.addView(mSeverityFilterContainer);
        LinearLayout.LayoutParams sevScrollParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        );
        severityScroll.setLayoutParams(sevScrollParams);
        severityFilterRow.addView(severityScroll);
        filterSection.addView(severityFilterRow);

        root.addView(filterSection);

        // Divider
        View divider = new View(this);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
        ));
        divider.setBackgroundColor(0xFFC6C6C8);
        root.addView(divider);

        // Empty state container
        mEmptyStateContainer = new LinearLayout(this);
        mEmptyStateContainer.setOrientation(LinearLayout.VERTICAL);
        mEmptyStateContainer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams emptyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
        );
        emptyParams.weight = 1;
        mEmptyStateContainer.setLayoutParams(emptyParams);
        mEmptyStateContainer.setPadding(48, 96, 48, 96);

        mEmptyIcon = new TextView(this);
        mEmptyIcon.setTextSize(48);
        mEmptyIcon.setGravity(Gravity.CENTER);
        mEmptyStateContainer.addView(mEmptyIcon);

        mEmptyTitle = new TextView(this);
        mEmptyTitle.setTextSize(18);
        mEmptyTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        mEmptyTitle.setTextColor(0xFF8E8E93);
        mEmptyTitle.setGravity(Gravity.CENTER);
        mEmptyTitle.setPadding(0, 16, 0, 0);
        mEmptyStateContainer.addView(mEmptyTitle);

        mEmptySubtitle = new TextView(this);
        mEmptySubtitle.setTextSize(14);
        mEmptySubtitle.setTextColor(0xFFAEAEB2);
        mEmptySubtitle.setGravity(Gravity.CENTER);
        mEmptySubtitle.setPadding(0, 8, 0, 0);
        mEmptyStateContainer.addView(mEmptySubtitle);

        root.addView(mEmptyStateContainer);

        // RecyclerView for alerts
        mAlertRecycler = new RecyclerView(this);
        LinearLayout.LayoutParams recyclerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
        );
        recyclerParams.weight = 1;
        mAlertRecycler.setLayoutParams(recyclerParams);
        mAlertRecycler.setLayoutManager(new LinearLayoutManager(this));
        mAlertRecycler.setPadding(16, 16, 16, 16);
        mAlertRecycler.setClipToPadding(false);
        mAlertRecycler.setBackgroundColor(0xFFF2F2F7);

        mAdapter = new AlertFeedAdapter(mFilteredAlerts, mTimeFormat);
        mAlertRecycler.setAdapter(mAdapter);
        mAlertRecycler.setVisibility(View.GONE);
        root.addView(mAlertRecycler);

        setContentView(root);

        updateEmptyState();
        updateFilterChipHighlights();
    }

    // ==================== Filter Chips ====================

    private TextView createFilterChip(String label, String value, boolean isTypeFilter) {
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextSize(13);
        chip.setPadding(20, 8, 20, 8);
        chip.setTag(value);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(4, 0, 4, 0);
        chip.setLayoutParams(params);

        chip.setOnClickListener(v -> {
            if (isTypeFilter) {
                mSelectedTypeFilter = value;
                updateFilterChipHighlights();
            } else {
                mSelectedSeverityFilter = value;
                updateFilterChipHighlights();
            }
            applyFilters();
        });

        return chip;
    }

    private void updateFilterChipHighlights() {
        for (TextView chip : mTypeChips) {
            boolean selected = chip.getTag().equals(mSelectedTypeFilter);
            applyChipStyle(chip, selected);
        }
        for (TextView chip : mSeverityChips) {
            boolean selected = chip.getTag().equals(mSelectedSeverityFilter);
            applyChipStyle(chip, selected);
        }
    }

    private void applyChipStyle(TextView chip, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(16);
        if (selected) {
            bg.setColor(0xFF007AFF);
            chip.setTextColor(Color.WHITE);
        } else {
            bg.setColor(0xFFE5E5EA);
            chip.setTextColor(0xFF3C3C43);
        }
        chip.setBackground(bg);
    }

    // ==================== Filtering ====================

    private void applyFilters() {
        mFilteredAlerts.clear();
        for (LiveAlertItem alert : mAllAlerts) {
            boolean typeMatch = FILTER_ALL.equals(mSelectedTypeFilter) ||
                    mSelectedTypeFilter.equals(alert.type);
            boolean severityMatch = FILTER_ALL.equals(mSelectedSeverityFilter) ||
                    mSelectedSeverityFilter.equals(alert.severity);
            if (typeMatch && severityMatch) {
                mFilteredAlerts.add(alert);
            }
        }
        mAdapter.notifyDataSetChanged();
        updateEmptyState();
    }

    private void updateEmptyState() {
        boolean isEmpty = mFilteredAlerts.isEmpty();
        mEmptyStateContainer.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        mAlertRecycler.setVisibility(isEmpty ? View.GONE : View.VISIBLE);

        if (isEmpty) {
            if (mIsConnected) {
                mEmptyIcon.setText("\ud83d\udd15"); // bell with slash
                mEmptyTitle.setText("No Alerts");
                mEmptySubtitle.setText("Waiting for real-time alert data...");
            } else {
                mEmptyIcon.setText("\ud83d\udcf6"); // antenna
                mEmptyTitle.setText("Not Connected");
                mEmptySubtitle.setText("Connecting to alert service...");
            }
        }
    }

    // ==================== Connection Status ====================

    private void updateConnectionStatus(boolean connected) {
        mIsConnected = connected;
        mMainHandler.post(() -> {
            if (mConnectionStatus != null) {
                mConnectionStatus.setText(connected ? "Online" : "Offline");
                mConnectionStatus.setTextColor(connected ? 0xFF34C759 : Color.GRAY);
            }
            if (mConnectionDot != null) {
                GradientDrawable dotShape = new GradientDrawable();
                dotShape.setShape(GradientDrawable.OVAL);
                dotShape.setColor(connected ? 0xFF34C759 : Color.GRAY);
                mConnectionDot.setBackground(dotShape);
            }
            updateEmptyState();
        });
    }

    // ==================== WebSocket Connection ====================

    private void connectWebSocket() {
        SharedPreferences prefs = getSharedPreferences("clawphones_prefs", MODE_PRIVATE);
        String token = prefs.getString("auth_token", null);
        if (token == null) {
            token = ClawPhonesAPI.getToken(this);
        }

        if (TextUtils.isEmpty(token)) {
            Toast.makeText(this, "Authentication token not found", Toast.LENGTH_SHORT).show();
            return;
        }

        String wsUrl = API_BASE_URL
                .replace("https://", "wss://")
                .replace("http://", "ws://")
                + "/ws/alerts";

        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + token);

        mWebSocketClient = new WebSocketClient.Builder(wsUrl)
                .addHeader("Authorization", "Bearer " + token)
                .setMessageListener(new WebSocketClient.MessageListener() {
                    @Override
                    public void onMessage(@NonNull String message) {
                        handleWebSocketMessage(message);
                    }

                    @Override
                    public void onMessage(@NonNull ByteString bytes) {
                        String text = bytes.utf8();
                        handleWebSocketMessage(text);
                    }
                })
                .setConnectionStateListener(new WebSocketClient.ConnectionStateListener() {
                    @Override
                    public void onConnecting() {
                        Log.d(LOG_TAG, "WebSocket connecting...");
                    }

                    @Override
                    public void onConnected() {
                        Log.d(LOG_TAG, "WebSocket connected");
                        updateConnectionStatus(true);
                    }

                    @Override
                    public void onDisconnected(int code, @NonNull String reason) {
                        Log.d(LOG_TAG, "WebSocket disconnected: " + code + " - " + reason);
                        updateConnectionStatus(false);
                    }

                    @Override
                    public void onError(@NonNull Throwable error) {
                        Log.e(LOG_TAG, "WebSocket error: " + error.getMessage());
                        updateConnectionStatus(false);
                        mMainHandler.post(() -> {
                            Toast.makeText(LiveAlertFeedActivity.this,
                                    "WebSocket error: " + error.getMessage(),
                                    Toast.LENGTH_SHORT).show();
                        });
                    }
                })
                .build();

        mWebSocketClient.connect();
    }

    private void disconnectWebSocket() {
        if (mWebSocketClient != null) {
            mWebSocketClient.disconnect();
            mWebSocketClient = null;
        }
        mIsConnected = false;
    }

    private void handleWebSocketMessage(String message) {
        try {
            JSONObject json = new JSONObject(message);

            String id = json.optString("id", "");
            String type = json.optString("type", "");
            String h3Location = json.optString("h3_location", "");
            String severity = json.optString("severity", SEVERITY_LOW);
            String description = json.optString("description", "");
            String timestampStr = json.optString("timestamp", "");

            Date timestamp;
            try {
                timestamp = mIso8601Format.parse(timestampStr);
            } catch (ParseException e) {
                timestamp = new Date();
            }

            LiveAlertItem alert = new LiveAlertItem(
                    id, type, h3Location, severity, description, timestamp
            );

            mMainHandler.post(() -> {
                // Insert at the beginning (most recent first)
                mAllAlerts.add(0, alert);

                // Keep only the last MAX_ALERTS
                while (mAllAlerts.size() > MAX_ALERTS) {
                    mAllAlerts.remove(mAllAlerts.size() - 1);
                }

                applyFilters();
            });
        } catch (JSONException e) {
            Log.e(LOG_TAG, "Failed to parse alert: " + e.getMessage());
        }
    }

    // ==================== Data Models ====================

    static class LiveAlertItem {
        final String id;
        final String type;
        final String h3Location;
        final String severity;
        final String description;
        final Date timestamp;

        LiveAlertItem(String id, String type, String h3Location,
                      String severity, String description, Date timestamp) {
            this.id = id;
            this.type = type;
            this.h3Location = h3Location;
            this.severity = severity;
            this.description = description;
            this.timestamp = timestamp != null ? timestamp : new Date();
        }

        /** Returns display name for alert type (matching iOS displayName) */
        String getDisplayType() {
            switch (type) {
                case TYPE_MOTION:  return "Motion Detected";
                case TYPE_PERSON:  return "Person Detected";
                case TYPE_VEHICLE: return "Vehicle Detected";
                case TYPE_SOUND:   return "Sound Alert";
                case TYPE_COMMUNITY: return "Community Alert";
                default: return type;
            }
        }

        /** Returns emoji icon for alert type (matching iOS iconName) */
        String getTypeIcon() {
            switch (type) {
                case TYPE_MOTION:  return "\ud83c\udf0a"; // wave
                case TYPE_PERSON:  return "\ud83d\udc64"; // person
                case TYPE_VEHICLE: return "\ud83d\ude97"; // car
                case TYPE_SOUND:   return "\ud83d\udd0a"; // speaker
                case TYPE_COMMUNITY: return "\u26a0\ufe0f"; // warning
                default: return "\ud83d\udd14"; // bell
            }
        }

        /** Returns severity display name (matching iOS displayName) */
        String getSeverityDisplay() {
            switch (severity) {
                case SEVERITY_LOW:      return "Low";
                case SEVERITY_MEDIUM:   return "Medium";
                case SEVERITY_HIGH:     return "High";
                case SEVERITY_CRITICAL: return "Critical";
                default: return severity;
            }
        }

        /** Returns severity color (matching iOS severity.color) */
        int getSeverityColor() {
            switch (severity) {
                case SEVERITY_LOW:      return 0xFF007AFF; // blue
                case SEVERITY_MEDIUM:   return 0xFFFF9500; // yellow/orange
                case SEVERITY_HIGH:     return 0xFFFF9500; // orange
                case SEVERITY_CRITICAL: return 0xFFFF3B30; // red
                default: return Color.GRAY;
            }
        }
    }

    // ==================== RecyclerView Adapter ====================

    static class AlertFeedAdapter extends RecyclerView.Adapter<AlertFeedAdapter.ViewHolder> {

        private final List<LiveAlertItem> alerts;
        private final SimpleDateFormat timeFormat;

        AlertFeedAdapter(List<LiveAlertItem> alerts, SimpleDateFormat timeFormat) {
            this.alerts = alerts;
            this.timeFormat = timeFormat;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout card = new LinearLayout(parent.getContext());
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setPadding(16, 16, 16, 16);
            card.setGravity(Gravity.CENTER_VERTICAL);

            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setCornerRadius(12);
            cardBg.setColor(Color.WHITE);
            card.setBackground(cardBg);

            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            cardParams.setMargins(0, 0, 0, 12);
            card.setLayoutParams(cardParams);

            // Icon circle
            TextView iconView = new TextView(parent.getContext());
            iconView.setTextSize(22);
            iconView.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(56, 56);
            iconParams.setMargins(0, 0, 12, 0);
            iconView.setLayoutParams(iconParams);
            iconView.setId(android.R.id.icon);
            card.addView(iconView);

            // Content container
            LinearLayout content = new LinearLayout(parent.getContext());
            content.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            );
            content.setLayoutParams(contentParams);

            // Title row (type + severity badge)
            LinearLayout titleRow = new LinearLayout(parent.getContext());
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView typeText = new TextView(parent.getContext());
            typeText.setTextSize(14);
            typeText.setTypeface(null, android.graphics.Typeface.BOLD);
            typeText.setId(android.R.id.text1);
            titleRow.addView(typeText);

            TextView severityBadge = new TextView(parent.getContext());
            severityBadge.setTextSize(10);
            severityBadge.setTypeface(null, android.graphics.Typeface.BOLD);
            severityBadge.setTextColor(Color.WHITE);
            severityBadge.setPadding(12, 4, 12, 4);
            severityBadge.setId(android.R.id.text2);
            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            badgeParams.setMargins(8, 0, 0, 0);
            severityBadge.setLayoutParams(badgeParams);
            titleRow.addView(severityBadge);

            content.addView(titleRow);

            // Location row
            TextView locationText = new TextView(parent.getContext());
            locationText.setTextSize(12);
            locationText.setTextColor(0xFF8E8E93);
            locationText.setPadding(0, 4, 0, 0);
            locationText.setId(android.R.id.summary);
            content.addView(locationText);

            // Description
            TextView descText = new TextView(parent.getContext());
            descText.setTextSize(12);
            descText.setTextColor(0xFF8E8E93);
            descText.setPadding(0, 4, 0, 0);
            descText.setMaxLines(2);
            descText.setEllipsize(TextUtils.TruncateAt.END);
            descText.setId(android.R.id.content);
            content.addView(descText);

            // Timestamp
            TextView timeText = new TextView(parent.getContext());
            timeText.setTextSize(11);
            timeText.setTextColor(0xFFAEAEB2);
            timeText.setPadding(0, 4, 0, 0);
            timeText.setId(android.R.id.custom);
            content.addView(timeText);

            card.addView(content);

            // Chevron
            TextView chevron = new TextView(parent.getContext());
            chevron.setText("\u203a");
            chevron.setTextSize(20);
            chevron.setTextColor(0xFFAEAEB2);
            chevron.setPadding(8, 0, 0, 0);
            card.addView(chevron);

            return new ViewHolder(card);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            LiveAlertItem alert = alerts.get(position);

            // Icon with severity-tinted background
            holder.iconView.setText(alert.getTypeIcon());
            GradientDrawable iconBg = new GradientDrawable();
            iconBg.setShape(GradientDrawable.OVAL);
            int severityColor = alert.getSeverityColor();
            iconBg.setColor((severityColor & 0x00FFFFFF) | 0x26000000); // 15% opacity
            holder.iconView.setBackground(iconBg);

            // Type display name
            holder.typeText.setText(alert.getDisplayType());

            // Severity badge
            holder.severityBadge.setText(alert.getSeverityDisplay());
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setCornerRadius(24);
            badgeBg.setColor(severityColor);
            holder.severityBadge.setBackground(badgeBg);

            // Location
            if (!TextUtils.isEmpty(alert.h3Location)) {
                holder.locationText.setVisibility(View.VISIBLE);
                holder.locationText.setText("\ud83d\udccd " + alert.h3Location);
            } else {
                holder.locationText.setVisibility(View.GONE);
            }

            // Description
            holder.descText.setText(alert.description);

            // Timestamp
            holder.timeText.setText(timeFormat.format(alert.timestamp));
        }

        @Override
        public int getItemCount() {
            return alerts.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            final TextView iconView;
            final TextView typeText;
            final TextView severityBadge;
            final TextView locationText;
            final TextView descText;
            final TextView timeText;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                iconView = itemView.findViewById(android.R.id.icon);
                typeText = itemView.findViewById(android.R.id.text1);
                severityBadge = itemView.findViewById(android.R.id.text2);
                locationText = itemView.findViewById(android.R.id.summary);
                descText = itemView.findViewById(android.R.id.content);
                timeText = itemView.findViewById(android.R.id.custom);
            }
        }
    }
}
