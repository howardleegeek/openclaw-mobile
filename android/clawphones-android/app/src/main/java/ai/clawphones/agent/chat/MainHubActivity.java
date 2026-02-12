package ai.clawphones.agent.chat;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.termux.R;

import ai.clawphones.agent.CrashReporter;

public class MainHubActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "main_hub_prefs";
    private static final String KEY_SELECTED_TAB = "selected_tab";
    private static final String KEY_UNREAD_ALERTS = "unread_alerts";

    private static final int TAB_CHAT = 0;
    private static final int TAB_COMMUNITY = 1;
    private static final int TAB_TASKS = 2;
    private static final int TAB_EXPLORE = 3;
    private static final int TAB_PROFILE = 4;

    private BottomNavigationView bottomNav;
    private FrameLayout frameLayout;
    private SharedPreferences prefs;

    private int mUnreadAlerts = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String token = ClawPhonesAPI.getToken(this);
        if (TextUtils.isEmpty(token)) {
            redirectToLogin();
            return;
        }

        setContentView(R.layout.activity_main_hub);

        bottomNav = findViewById(R.id.bottom_navigation);
        frameLayout = findViewById(R.id.content_frame);

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        setupBottomNavigation();

        loadUnreadAlerts();

        int selectedTab = prefs.getInt(KEY_SELECTED_TAB, TAB_CHAT);
        if (savedInstanceState != null) {
            selectedTab = savedInstanceState.getInt(KEY_SELECTED_TAB, selectedTab);
        }
        bottomNav.setSelectedItemId(selectedTab);
        handleTabSelection(selectedTab);
    }

    @Override
    protected void onResume() {
        super.onResume();

        String token = ClawPhonesAPI.getToken(this);
        if (TextUtils.isEmpty(token)) {
            redirectToLogin();
            return;
        }

        updateUnreadBadge();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_SELECTED_TAB, bottomNav.getSelectedItemId());
    }

    @Override
    protected void onPause() {
        super.onPause();
        int selectedTab = bottomNav.getSelectedItemId();
        prefs.edit().putInt(KEY_SELECTED_TAB, selectedTab).apply();
    }

    private void setupBottomNavigation() {
        bottomNav.setOnItemSelectedListener(this::onNavigationItemSelected);

        bottomNav.setItemIconTintList(null);

        updateTabIcons(bottomNav.getSelectedItemId());
    }

    private boolean onNavigationItemSelected(@NonNull MenuItem item) {
        int itemId = item.getItemId();

        updateTabIcons(itemId);

        handleTabSelection(itemId);

        return true;
    }

    private void handleTabSelection(int itemId) {
        CrashReporter.setLastAction("hub_tab_" + itemId);

        Intent intent = null;

        switch (itemId) {
            case R.id.nav_chat:
                intent = new Intent(this, ConversationListActivity.class);
                break;
            case R.id.nav_community:
                intent = new Intent(this, CommunityListActivity.class);
                break;
            case R.id.nav_tasks:
                intent = new Intent(this, TaskListActivity.class);
                break;
            case R.id.nav_explore:
                intent = new Intent(this, ExploreActivity.class);
                break;
            case R.id.nav_profile:
                intent = new Intent(this, ProfileActivity.class);
                break;
        }

        if (intent != null) {
            startActivity(intent);
            finish();
        }
    }

    private void updateTabIcons(int selectedItemId) {
        for (int i = 0; i < bottomNav.getMenu().size(); i++) {
            MenuItem item = bottomNav.getMenu().getItem(i);
            if (item.getItemId() == selectedItemId) {
                item.getIcon().setColorFilter(
                    ContextCompat.getColor(this, R.color.nav_selected_color),
                    PorterDuff.Mode.SRC_IN
                );
            } else {
                item.getIcon().setColorFilter(
                    ContextCompat.getColor(this, R.color.nav_unselected_color),
                    PorterDuff.Mode.SRC_IN
                );
            }
        }
    }

    private void loadUnreadAlerts() {
        mUnreadAlerts = prefs.getInt(KEY_UNREAD_ALERTS, 0);
        updateUnreadBadge();
    }

    private void updateUnreadBadge() {
        MenuItem communityItem = bottomNav.getMenu().findItem(R.id.nav_community);
        if (communityItem != null) {
            int badge = 0;
            if (mUnreadAlerts > 0) {
                badge = mUnreadAlerts;
            }
            if (badge > 0) {
                communityItem.setTitle(getString(R.string.nav_community_with_badge, badge));
            } else {
                communityItem.setTitle(getString(R.string.nav_community));
            }
        }
    }

    public void setUnreadAlerts(int count) {
        mUnreadAlerts = count;
        prefs.edit().putInt(KEY_UNREAD_ALERTS, count).apply();
        updateUnreadBadge();
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
}
