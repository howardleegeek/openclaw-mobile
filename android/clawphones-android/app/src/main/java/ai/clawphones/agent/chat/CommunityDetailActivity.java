package ai.clawphones.agent.chat;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.termux.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CommunityDetailActivity extends AppCompatActivity {

    private static final String EXTRA_COMMUNITY_ID = "community_id";

    private Toolbar mToolbar;
    private ImageButton mShareButton;
    private TextView mName;
    private TextView mDescription;
    private TextView mMemberCount;
    private TextView mInviteCode;
    private RecyclerView mAlertsRecycler;
    private RecyclerView mMembersRecycler;
    private TextView mEmptyAlerts;
    private TextView mEmptyMembers;

    private AlertAdapter mAlertAdapter;
    private MemberAdapter mMemberAdapter;

    private ExecutorService mExecutor;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean mDestroyed = false;

    private String mToken;
    private String mCommunityId;
    private Community mCommunity;
    private final List<AlertInfo> mAlerts = new ArrayList<>();
    private final List<MemberInfo> mMembers = new ArrayList<>();

    private static class AlertInfo {
        final String id;
        final String type;
        final String description;
        final long timestamp;
        final int confidence;
        final double lat;
        final double lon;

        AlertInfo(String id, String type, String description, long timestamp, int confidence, double lat, double lon) {
            this.id = id;
            this.type = type;
            this.description = description;
            this.timestamp = timestamp;
            this.confidence = confidence;
            this.lat = lat;
            this.lon = lon;
        }
    }

    private static class MemberInfo {
        final String id;
        final String name;
        final String avatar;
        final long joinedAt;

        MemberInfo(String id, String name, String avatar, long joinedAt) {
            this.id = id;
            this.name = name;
            this.avatar = avatar;
            this.joinedAt = joinedAt;
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mCommunityId = getIntent().getStringExtra(EXTRA_COMMUNITY_ID);
        if (TextUtils.isEmpty(mCommunityId)) {
            finish();
            return;
        }

        mToken = ClawPhonesAPI.getToken(this);
        if (TextUtils.isEmpty(mToken)) {
            redirectToLogin();
            return;
        }

        setContentView(R.layout.activity_community_detail);

        mToolbar = findViewById(R.id.detail_toolbar);
        mShareButton = findViewById(R.id.detail_share);
        mName = findViewById(R.id.detail_name);
        mDescription = findViewById(R.id.detail_description);
        mMemberCount = findViewById(R.id.detail_member_count);
        mInviteCode = findViewById(R.id.detail_invite_code);
        mAlertsRecycler = findViewById(R.id.detail_alerts_recycler);
        mMembersRecycler = findViewById(R.id.detail_members_recycler);
        mEmptyAlerts = findViewById(R.id.detail_empty_alerts);
        mEmptyMembers = findViewById(R.id.detail_empty_members);

        if (mToolbar != null) {
            setSupportActionBar(mToolbar);
            mToolbar.setNavigationOnClickListener(v -> finish());
        }

        if (mShareButton != null) {
            mShareButton.setOnClickListener(this::shareCommunity);
        }

        mAlertAdapter = new AlertAdapter(mAlerts, this::openAlertDetail);
        mAlertsRecycler.setLayoutManager(new LinearLayoutManager(this));
        mAlertsRecycler.setAdapter(mAlertAdapter);

        mMemberAdapter = new MemberAdapter(mMembers);
        mMembersRecycler.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        mMembersRecycler.setAdapter(mMemberAdapter);

        mExecutor = Executors.newSingleThreadExecutor();

        loadCommunityDetail();
    }

    @Override
    protected void onDestroy() {
        mDestroyed = true;
        mMainHandler.removeCallbacksAndMessages(null);
        if (mExecutor != null) {
            try {
                mExecutor.shutdownNow();
            } catch (Exception ignored) {
            }
            mExecutor = null;
        }
        super.onDestroy();
    }

    private void loadCommunityDetail() {
        execSafe(() -> {
            try {
                mCommunity = CommunityService.getCommunityDetail(
                    CommunityDetailActivity.this,
                    mCommunityId
                );

                JSONObject alertsJson = CommunityService.getCommunityAlerts(
                    CommunityDetailActivity.this,
                    mCommunityId,
                    20,
                    0
                );

                JSONObject membersJson = CommunityService.getCommunityMembers(
                    CommunityDetailActivity.this,
                    mCommunityId,
                    20,
                    0
                );

                runSafe(() -> {
                    renderCommunity(mCommunity);
                    renderAlerts(alertsJson);
                    renderMembers(membersJson);
                });
            } catch (IOException | JSONException e) {
                runSafe(() -> {
                    toast(getString(R.string.community_error_load_failed));
                });
            }
        });
    }

    private void renderCommunity(Community community) {
        if (community == null) return;

        if (mToolbar != null) {
            mToolbar.setTitle(community.name);
        }

        mName.setText(community.name);
        mDescription.setText(community.description != null ? community.description : getString(R.string.community_no_description));
        mMemberCount.setText(community.memberCount + " " + getString(R.string.community_members));
        mInviteCode.setText(community.inviteCode != null ? community.inviteCode : getString(R.string.community_no_invite_code));
    }

    private void renderAlerts(JSONObject json) throws JSONException {
        mAlerts.clear();

        JSONArray alertsArray = json.optJSONArray("alerts");
        if (alertsArray != null) {
            for (int i = 0; i < alertsArray.length(); i++) {
                JSONObject alertJson = alertsArray.getJSONObject(i);
                AlertInfo alert = new AlertInfo(
                    alertJson.optString("id", ""),
                    alertJson.optString("type", "unknown"),
                    alertJson.optString("description", ""),
                    alertJson.optLong("timestamp", 0L),
                    alertJson.optInt("confidence", 0),
                    alertJson.optDouble("lat", 0.0),
                    alertJson.optDouble("lon", 0.0)
                );
                mAlerts.add(alert);
            }
        }

        mAlertAdapter.notifyDataSetChanged();
        mEmptyAlerts.setVisibility(mAlerts.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void renderMembers(JSONObject json) throws JSONException {
        mMembers.clear();

        JSONArray membersArray = json.optJSONArray("members");
        if (membersArray != null) {
            for (int i = 0; i < membersArray.length(); i++) {
                JSONObject memberJson = membersArray.getJSONObject(i);
                MemberInfo member = new MemberInfo(
                    memberJson.optString("id", ""),
                    memberJson.optString("name", "Unknown"),
                    memberJson.optString("avatar", null),
                    memberJson.optLong("joined_at", 0L)
                );
                mMembers.add(member);
            }
        }

        mMemberAdapter.notifyDataSetChanged();
        mEmptyMembers.setVisibility(mMembers.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void shareCommunity(View v) {
        if (mCommunity == null) {
            toast(getString(R.string.community_error_not_loaded));
            return;
        }

        String shareText = getString(R.string.community_share_text,
            mCommunity.name,
            mCommunity.memberCount,
            mCommunity.inviteCode != null ? mCommunity.inviteCode : ""
        );

        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(android.content.Intent.EXTRA_TEXT, shareText);
        startActivity(android.content.Intent.createChooser(intent, getString(R.string.community_share_title)));
    }

    private void openAlertDetail(AlertInfo alert) {
        toast(getString(R.string.community_alert_detail) + ": " + alert.type);
    }

    private void redirectToLogin() {
        android.content.Intent i = new android.content.Intent(this, LoginActivity.class);
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    private void runSafe(Runnable r) {
        if (mDestroyed) return;
        mMainHandler.post(() -> {
            if (!mDestroyed) r.run();
        });
    }

    private void execSafe(Runnable r) {
        ExecutorService exec = mExecutor;
        if (exec != null && !exec.isShutdown()) {
            try {
                exec.execute(r);
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
            }
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private static String formatTimestamp(long timestampMs) {
        Date date = new Date(timestampMs);
        Calendar target = Calendar.getInstance();
        target.setTime(date);
        Calendar now = Calendar.getInstance();

        if (sameDay(target, now)) {
            return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(date);
        }

        now.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(target, now)) {
            return "昨天";
        }

        return new SimpleDateFormat("MM-dd", Locale.getDefault()).format(date);
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
            && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private interface OnAlertClickListener {
        void onClick(AlertInfo alert);
    }

    private final class AlertAdapter extends RecyclerView.Adapter<AlertAdapter.VH> {

        private final List<AlertInfo> mItems;
        private final OnAlertClickListener mListener;

        AlertAdapter(List<AlertInfo> items, OnAlertClickListener listener) {
            mItems = items;
            mListener = listener;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_alert, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            AlertInfo item = mItems.get(position);
            holder.bind(item, mListener);
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }

        final class VH extends RecyclerView.ViewHolder {
            private final ImageView thumbnail;
            private final ImageView typeIcon;
            private final TextView typeText;
            private final TextView timeLabel;
            private final TextView confidenceText;

            VH(@NonNull View itemView) {
                super(itemView);
                thumbnail = itemView.findViewById(R.id.alert_item_thumbnail);
                typeIcon = itemView.findViewById(R.id.alert_item_type_icon);
                typeText = itemView.findViewById(R.id.alert_item_type);
                timeLabel = itemView.findViewById(R.id.alert_item_time);
                confidenceText = itemView.findViewById(R.id.alert_item_confidence);
            }

            void bind(AlertInfo alert, OnAlertClickListener listener) {
                thumbnail.setImageResource(android.R.drawable.ic_menu_report_image);

                int iconRes = getAlertIcon(alert.type);
                typeIcon.setImageResource(iconRes);
                typeText.setText(capitalize(alert.type));
                timeLabel.setText(formatTimestamp(alert.timestamp * 1000L));
                confidenceText.setText(getString(R.string.alert_confidence_format, alert.confidence) + "%");

                itemView.setOnClickListener(v -> {
                    if (listener != null) listener.onClick(alert);
                });
            }

            private int getAlertIcon(String type) {
                if ("person".equalsIgnoreCase(type)) {
                    return android.R.drawable.ic_menu_myplaces;
                } else if ("vehicle".equalsIgnoreCase(type)) {
                    return android.R.drawable.ic_menu_directions;
                } else if ("animal".equalsIgnoreCase(type)) {
                    return android.R.drawable.ic_menu_gallery;
                }
                return android.R.drawable.ic_dialog_alert;
            }

            private String capitalize(String s) {
                if (TextUtils.isEmpty(s)) return s;
                return s.substring(0, 1).toUpperCase(Locale.getDefault()) + s.substring(1);
            }
        }
    }

    private final class MemberAdapter extends RecyclerView.Adapter<MemberAdapter.VH> {

        private final List<MemberInfo> mItems;

        MemberAdapter(List<MemberInfo> items) {
            mItems = items;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_community_member, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            MemberInfo item = mItems.get(position);
            holder.bind(item);
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }

        final class VH extends RecyclerView.ViewHolder {
            private final ImageView avatar;
            private final TextView name;

            VH(@NonNull View itemView) {
                super(itemView);
                avatar = itemView.findViewById(R.id.member_avatar);
                name = itemView.findViewById(R.id.member_name);
            }

            void bind(MemberInfo member) {
                avatar.setImageResource(android.R.drawable.ic_menu_myplaces);
                name.setText(member.name);
            }
        }
    }
}
