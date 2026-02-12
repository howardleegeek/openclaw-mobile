package ai.clawphones.agent.chat;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;

import java.util.ArrayList;
import java.util.List;

import ai.clawphones.agent.CrashReporter;

public class ExploreActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private ExploreAdapter adapter;
    private List<ExploreItem> items;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String token = ClawPhonesAPI.getToken(this);
        if (TextUtils.isEmpty(token)) {
            redirectToLogin();
            return;
        }

        setContentView(R.layout.activity_explore);

        Toolbar toolbar = findViewById(R.id.explore_toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            toolbar.setTitle(getString(R.string.explore_title));
        }

        recyclerView = findViewById(R.id.explore_recycler);

        setupExploreItems();
        setupRecyclerView();
    }

    @Override
    protected void onResume() {
        super.onResume();

        String token = ClawPhonesAPI.getToken(this);
        if (TextUtils.isEmpty(token)) {
            redirectToLogin();
            return;
        }
    }

    private void setupExploreItems() {
        items = new ArrayList<>();

        items.add(new ExploreItem(
            ExploreItem.TYPE_NODE_MODE,
            getString(R.string.explore_node_mode),
            getString(R.string.explore_node_mode_subtitle),
            R.drawable.ic_node_mode
        ));

        items.add(new ExploreItem(
            ExploreItem.TYPE_EDGE_COMPUTE,
            getString(R.string.explore_edge_compute),
            getString(R.string.explore_edge_compute_subtitle),
            R.drawable.ic_edge_compute
        ));

        items.add(new ExploreItem(
            ExploreItem.TYPE_COVERAGE_MAP,
            getString(R.string.explore_coverage_map),
            getString(R.string.explore_coverage_map_subtitle),
            R.drawable.ic_coverage_map
        ));

        items.add(new ExploreItem(
            ExploreItem.TYPE_DEVELOPER_PORTAL,
            getString(R.string.explore_developer_portal),
            getString(R.string.explore_developer_portal_subtitle),
            R.drawable.ic_developer_portal
        ));

        items.add(new ExploreItem(
            ExploreItem.TYPE_USAGE_DASHBOARD,
            getString(R.string.explore_usage_dashboard),
            getString(R.string.explore_usage_dashboard_subtitle),
            R.drawable.ic_usage_dashboard
        ));

        items.add(new ExploreItem(
            ExploreItem.TYPE_PLUGIN_MARKET,
            getString(R.string.explore_plugin_market),
            getString(R.string.explore_plugin_market_subtitle),
            R.drawable.ic_plugin_market
        ));

        items.add(new ExploreItem(
            ExploreItem.TYPE_PERFORMANCE_DASHBOARD,
            getString(R.string.explore_performance_dashboard),
            getString(R.string.explore_performance_dashboard_subtitle),
            R.drawable.ic_performance_dashboard
        ));
    }

    private void setupRecyclerView() {
        adapter = new ExploreAdapter(items, this::onItemClick);
        recyclerView.setLayoutManager(new GridLayoutManager(this, 2));
        recyclerView.setAdapter(adapter);
    }

    private void onItemClick(ExploreItem item) {
        CrashReporter.setLastAction("explore_click_" + item.type);

        Intent intent = null;
        switch (item.type) {
            case ExploreItem.TYPE_NODE_MODE:
                intent = new Intent(this, NodeModeActivity.class);
                break;
            case ExploreItem.TYPE_EDGE_COMPUTE:
                intent = new Intent(this, EdgeComputeActivity.class);
                break;
            case ExploreItem.TYPE_COVERAGE_MAP:
                intent = new Intent(this, CoverageMapActivity.class);
                break;
            case ExploreItem.TYPE_DEVELOPER_PORTAL:
                intent = new Intent(this, DeveloperPortalActivity.class);
                break;
            case ExploreItem.TYPE_USAGE_DASHBOARD:
                intent = new Intent(this, UsageDashboardActivity.class);
                break;
            case ExploreItem.TYPE_PLUGIN_MARKET:
                intent = new Intent(this, PluginMarketActivity.class);
                break;
            case ExploreItem.TYPE_PERFORMANCE_DASHBOARD:
                intent = new Intent(this, PerformanceDashboardActivity.class);
                break;
        }

        if (intent != null) {
            startActivity(intent);
        }
    }

    @Override
    public void onBackPressed() {
        Intent intent = new Intent(this, MainHubActivity.class);
        startActivity(intent);
        finish();
    }

    private void redirectToLogin() {
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private static class ExploreItem {
        static final int TYPE_NODE_MODE = 1;
        static final int TYPE_EDGE_COMPUTE = 2;
        static final int TYPE_COVERAGE_MAP = 3;
        static final int TYPE_DEVELOPER_PORTAL = 4;
        static final int TYPE_USAGE_DASHBOARD = 5;
        static final int TYPE_PLUGIN_MARKET = 6;
        static final int TYPE_PERFORMANCE_DASHBOARD = 7;

        final int type;
        final String title;
        final String subtitle;
        final int iconRes;

        ExploreItem(int type, String title, String subtitle, int iconRes) {
            this.type = type;
            this.title = title;
            this.subtitle = subtitle;
            this.iconRes = iconRes;
        }
    }

    private static class ExploreAdapter extends RecyclerView.Adapter<ExploreAdapter.VH> {
        private final List<ExploreItem> items;
        private final OnItemClickListener listener;

        interface OnItemClickListener {
            void onItemClick(ExploreItem item);
        }

        ExploreAdapter(List<ExploreItem> items, OnItemClickListener listener) {
            this.items = items;
            this.listener = listener;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_explore_card, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            ExploreItem item = items.get(position);
            holder.bind(item, listener);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            private final ImageView iconView;
            private final TextView titleView;
            private final TextView subtitleView;
            private final View cardView;

            VH(View itemView) {
                super(itemView);
                cardView = itemView.findViewById(R.id.explore_card);
                iconView = itemView.findViewById(R.id.explore_icon);
                titleView = itemView.findViewById(R.id.explore_title);
                subtitleView = itemView.findViewById(R.id.explore_subtitle);
            }

            void bind(ExploreItem item, OnItemClickListener listener) {
                Context context = itemView.getContext();

                if (item.iconRes != 0) {
                    iconView.setImageResource(item.iconRes);
                } else {
                    iconView.setImageResource(android.R.drawable.ic_menu_info_details);
                }

                titleView.setText(item.title);
                subtitleView.setText(item.subtitle);

                itemView.setOnClickListener(v -> {
                    if (listener != null) {
                        listener.onItemClick(item);
                    }
                });
            }
        }
    }
}
