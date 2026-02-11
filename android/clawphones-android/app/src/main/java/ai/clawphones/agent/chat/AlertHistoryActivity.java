package ai.clawphones.agent.chat;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class AlertHistoryActivity extends AppCompatActivity {

    private static final String FILTER_ALL = "all";
    private static final String FILTER_PERSON = "person";
    private static final String FILTER_VEHICLE = "vehicle";
    private static final String FILTER_ANIMAL = "animal";

    private final String[] mFilterValues = new String[]{
        FILTER_ALL, FILTER_PERSON, FILTER_VEHICLE, FILTER_ANIMAL
    };

    private final List<AlertEventStore.AlertEvent> mAllEvents = new ArrayList<>();

    private AlertEventStore mStore;
    private RecyclerView mRecycler;
    private TextView mEmptyState;
    private Spinner mFilterSpinner;
    private AlertAdapter mAdapter;

    private final SimpleDateFormat mClockFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat mMonthDayClockFormat = new SimpleDateFormat("M/d HH:mm", Locale.getDefault());
    private final SimpleDateFormat mDateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_alert_history);

        mStore = new AlertEventStore(getApplicationContext());
        mRecycler = findViewById(R.id.alert_history_recycler);
        mEmptyState = findViewById(R.id.alert_history_empty);
        mFilterSpinner = findViewById(R.id.alert_filter_spinner);

        Toolbar toolbar = findViewById(R.id.alert_history_toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            toolbar.setNavigationOnClickListener(v -> finish());
        }

        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_item,
            new String[]{
                getString(R.string.alert_filter_all),
                getString(R.string.alert_filter_person),
                getString(R.string.alert_filter_vehicle),
                getString(R.string.alert_filter_animal)
            }
        );
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mFilterSpinner.setAdapter(spinnerAdapter);
        mFilterSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener() {
            @Override
            public void onItemSelected() {
                renderRows();
            }
        });

        mAdapter = new AlertAdapter(this::openDetail);
        mRecycler.setLayoutManager(new LinearLayoutManager(this));
        mRecycler.setAdapter(mAdapter);

        loadEvents();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadEvents();
    }

    private void loadEvents() {
        mAllEvents.clear();
        mAllEvents.addAll(mStore.loadEvents());
        renderRows();
    }

    private void renderRows() {
        String filter = selectedFilter();
        List<RowItem> rows = new ArrayList<>();
        String currentSection = null;

        for (AlertEventStore.AlertEvent event : mAllEvents) {
            if (!matchesFilter(event, filter)) continue;

            String section = sectionTitle(event.timestampMs);
            if (!TextUtils.equals(currentSection, section)) {
                rows.add(RowItem.header(section));
                currentSection = section;
            }
            rows.add(RowItem.event(event));
        }

        mAdapter.replaceAll(rows);
        boolean isEmpty = rows.isEmpty();
        mEmptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        mRecycler.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    private String selectedFilter() {
        int position = mFilterSpinner.getSelectedItemPosition();
        if (position < 0 || position >= mFilterValues.length) {
            return FILTER_ALL;
        }
        return mFilterValues[position];
    }

    private boolean matchesFilter(AlertEventStore.AlertEvent event, String filter) {
        if (FILTER_ALL.equals(filter)) return true;
        return TextUtils.equals(event.normalizedType(), filter);
    }

    private String sectionTitle(long timestampMs) {
        Date date = new Date(timestampMs);
        Calendar target = Calendar.getInstance();
        target.setTime(date);

        Calendar now = Calendar.getInstance();
        if (sameDay(target, now)) {
            return getString(R.string.alert_section_today);
        }

        now.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(target, now)) {
            return getString(R.string.alert_section_yesterday);
        }

        return mDateFormat.format(date);
    }

    private String formatTimestamp(long timestampMs) {
        Date date = new Date(timestampMs);
        Calendar target = Calendar.getInstance();
        target.setTime(date);
        Calendar now = Calendar.getInstance();

        if (sameDay(target, now) || isYesterday(target)) {
            return mClockFormat.format(date);
        }
        return mMonthDayClockFormat.format(date);
    }

    private boolean isYesterday(Calendar target) {
        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        return sameDay(target, yesterday);
    }

    private boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
            && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private int confidencePercent(float confidence) {
        if (confidence <= 1f) {
            return Math.max(0, Math.min(100, Math.round(confidence * 100f)));
        }
        return Math.max(0, Math.min(100, Math.round(confidence)));
    }

    private int typeIconRes(AlertEventStore.AlertEvent event) {
        String type = event.normalizedType();
        if (FILTER_PERSON.equals(type)) {
            return android.R.drawable.ic_menu_myplaces;
        }
        if (FILTER_VEHICLE.equals(type)) {
            return android.R.drawable.ic_menu_directions;
        }
        if (FILTER_ANIMAL.equals(type)) {
            return android.R.drawable.ic_menu_gallery;
        }
        return android.R.drawable.ic_dialog_alert;
    }

    private void openDetail(AlertEventStore.AlertEvent event) {
        Intent intent = new Intent(this, AlertDetailActivity.class);
        intent.putExtra(AlertDetailActivity.EXTRA_EVENT_ID, event.id);
        startActivity(intent);
    }

    private interface OnRowClickListener {
        void onEventClick(AlertEventStore.AlertEvent event);
    }

    private final class AlertAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private final List<RowItem> mRows = new ArrayList<>();
        private final OnRowClickListener mClickListener;

        AlertAdapter(OnRowClickListener clickListener) {
            mClickListener = clickListener;
        }

        void replaceAll(List<RowItem> rows) {
            mRows.clear();
            if (rows != null) {
                mRows.addAll(rows);
            }
            notifyDataSetChanged();
        }

        @Override
        public int getItemViewType(int position) {
            return mRows.get(position).viewType;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == RowItem.TYPE_HEADER) {
                TextView tv = new TextView(parent.getContext());
                tv.setTextSize(13f);
                tv.setTextColor(0xFF8A8A8A);
                int horizontal = dp(parent, 8);
                int top = dp(parent, 14);
                int bottom = dp(parent, 6);
                tv.setPadding(horizontal, top, horizontal, bottom);
                return new HeaderVH(tv);
            }

            View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_alert, parent, false);
            return new EventVH(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            RowItem row = mRows.get(position);
            if (holder instanceof HeaderVH) {
                ((HeaderVH) holder).bind(row.headerTitle);
                return;
            }
            ((EventVH) holder).bind(row.event);
        }

        @Override
        public int getItemCount() {
            return mRows.size();
        }

        final class HeaderVH extends RecyclerView.ViewHolder {
            private final TextView title;

            HeaderVH(@NonNull View itemView) {
                super(itemView);
                title = (TextView) itemView;
            }

            void bind(@Nullable String text) {
                title.setText(text == null ? "" : text);
            }
        }

        final class EventVH extends RecyclerView.ViewHolder {
            private final ImageView thumbnail;
            private final ImageView typeIcon;
            private final TextView typeText;
            private final TextView timeLabel;
            private final TextView confidenceText;

            EventVH(@NonNull View itemView) {
                super(itemView);
                thumbnail = itemView.findViewById(R.id.alert_item_thumbnail);
                typeIcon = itemView.findViewById(R.id.alert_item_type_icon);
                typeText = itemView.findViewById(R.id.alert_item_type);
                timeLabel = itemView.findViewById(R.id.alert_item_time);
                confidenceText = itemView.findViewById(R.id.alert_item_confidence);
            }

            void bind(@Nullable AlertEventStore.AlertEvent event) {
                if (event == null) return;

                if (event.thumbnailData != null && event.thumbnailData.length > 0) {
                    Bitmap bitmap = BitmapFactory.decodeByteArray(
                        event.thumbnailData,
                        0,
                        event.thumbnailData.length
                    );
                    if (bitmap != null) {
                        thumbnail.setImageBitmap(bitmap);
                    } else {
                        thumbnail.setImageResource(android.R.drawable.ic_menu_report_image);
                    }
                } else {
                    thumbnail.setImageResource(android.R.drawable.ic_menu_report_image);
                }

                typeIcon.setImageResource(typeIconRes(event));
                typeText.setText(capitalizeType(event.normalizedType()));
                timeLabel.setText(formatTimestamp(event.timestampMs));
                confidenceText.setText(
                    getString(R.string.alert_confidence_format, confidencePercent(event.confidence))
                );

                itemView.setOnClickListener(v -> {
                    if (mClickListener != null) {
                        mClickListener.onEventClick(event);
                    }
                });
            }
        }
    }

    private String capitalizeType(String value) {
        if (TextUtils.isEmpty(value)) return "Unknown";
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static int dp(ViewGroup parent, int value) {
        float density = parent.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private static final class RowItem {
        static final int TYPE_HEADER = 0;
        static final int TYPE_EVENT = 1;

        final int viewType;
        @Nullable final String headerTitle;
        @Nullable final AlertEventStore.AlertEvent event;

        private RowItem(int viewType, @Nullable String headerTitle, @Nullable AlertEventStore.AlertEvent event) {
            this.viewType = viewType;
            this.headerTitle = headerTitle;
            this.event = event;
        }

        static RowItem header(@NonNull String title) {
            return new RowItem(TYPE_HEADER, title, null);
        }

        static RowItem event(@NonNull AlertEventStore.AlertEvent event) {
            return new RowItem(TYPE_EVENT, null, event);
        }
    }

    private abstract static class SimpleItemSelectedListener implements android.widget.AdapterView.OnItemSelectedListener {
        @Override
        public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
            onItemSelected();
        }

        @Override
        public void onNothingSelected(android.widget.AdapterView<?> parent) {
        }

        public abstract void onItemSelected();
    }
}
