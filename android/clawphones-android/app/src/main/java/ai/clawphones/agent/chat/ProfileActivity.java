package ai.clawphones.agent.chat;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import com.termux.R;

import java.util.ArrayList;
import java.util.List;

import ai.clawphones.agent.CrashReporter;

public class ProfileActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "profile_prefs";
    private static final String KEY_LANGUAGE = "language";

    private TextView userNameView;
    private TextView userEmailView;
    private ImageView avatarView;

    private Spinner languageSpinner;
    private int selectedLanguageIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String token = ClawPhonesAPI.getToken(this);
        if (TextUtils.isEmpty(token)) {
            redirectToLogin();
            return;
        }

        setContentView(R.layout.activity_profile);

        setupToolbar();
        setupViews();
        setupLanguageSpinner();
        setupClickListeners();
        loadUserProfile();
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

    private void setupToolbar() {
        Toolbar toolbar = findViewById(R.id.profile_toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            toolbar.setTitle(getString(R.string.profile_title));

            if (getSupportActionBar() != null) {
                getSupportActionBar().setDisplayHomeAsUpEnabled(true);
                getSupportActionBar().setDisplayShowHomeEnabled(true);
            }

            toolbar.setNavigationOnClickListener(v -> onBackPressed());
        }
    }

    private void setupViews() {
        userNameView = findViewById(R.id.profile_user_name);
        userEmailView = findViewById(R.id.profile_user_email);
        avatarView = findViewById(R.id.profile_avatar);

        languageSpinner = findViewById(R.id.profile_language_spinner);
    }

    private void setupLanguageSpinner() {
        List<String> languages = new ArrayList<>();
        languages.add(getString(R.string.profile_language_chinese));
        languages.add(getString(R.string.profile_language_english));

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_item,
            languages
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languageSpinner.setAdapter(adapter);

        int savedLanguageIndex = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_LANGUAGE, 0);
        languageSpinner.setSelection(savedLanguageIndex, false);
        selectedLanguageIndex = savedLanguageIndex;

        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position != selectedLanguageIndex) {
                    selectedLanguageIndex = position;
                    saveLanguageSetting(position);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void setupClickListeners() {
        View walletItem = findViewById(R.id.profile_item_wallet);
        if (walletItem != null) {
            walletItem.setOnClickListener(v -> openWallet());
        }

        View rewardsItem = findViewById(R.id.profile_item_rewards);
        if (rewardsItem != null) {
            rewardsItem.setOnClickListener(v -> openRewards());
        }

        View leaderboardItem = findViewById(R.id.profile_item_leaderboard);
        if (leaderboardItem != null) {
            leaderboardItem.setOnClickListener(v -> openLeaderboard());
        }

        View earningsItem = findViewById(R.id.profile_item_earnings);
        if (earningsItem != null) {
            earningsItem.setOnClickListener(v -> openEarnings());
        }

        View privacyItem = findViewById(R.id.profile_item_privacy);
        if (privacyItem != null) {
            privacyItem.setOnClickListener(v -> openPrivacyCenter());
        }

        View performanceItem = findViewById(R.id.profile_item_performance);
        if (performanceItem != null) {
            performanceItem.setOnClickListener(v -> openPerformanceDashboard());
        }

        View logoutItem = findViewById(R.id.profile_item_logout);
        if (logoutItem != null) {
            logoutItem.setOnClickListener(v -> showLogoutConfirmation());
        }
    }

    private void loadUserProfile() {
        userNameView.setText(getString(R.string.profile_user_name_placeholder));
        userEmailView.setText(getString(R.string.profile_user_email_placeholder));

        avatarView.setImageDrawable(
            ContextCompat.getDrawable(this, R.drawable.ic_profile_avatar_placeholder)
        );
    }

    private void saveLanguageSetting(int index) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LANGUAGE, index)
            .apply();

        String languageName = index == 0
            ? getString(R.string.profile_language_chinese)
            : getString(R.string.profile_language_english);
        Toast.makeText(
            this,
            getString(R.string.profile_language_saved, languageName),
            Toast.LENGTH_SHORT
        ).show();
    }

    private void openWallet() {
        CrashReporter.setLastAction("profile_wallet");
        Intent intent = new Intent(this, WalletActivity.class);
        startActivity(intent);
    }

    private void openRewards() {
        CrashReporter.setLastAction("profile_rewards");
        Intent intent = new Intent(this, RewardsActivity.class);
        startActivity(intent);
    }

    private void openLeaderboard() {
        CrashReporter.setLastAction("profile_leaderboard");
        Intent intent = new Intent(this, LeaderboardActivity.class);
        startActivity(intent);
    }

    private void openEarnings() {
        CrashReporter.setLastAction("profile_earnings");
        Intent intent = new Intent(this, EarningsActivity.class);
        startActivity(intent);
    }

    private void openPrivacyCenter() {
        CrashReporter.setLastAction("profile_privacy");
        Intent intent = new Intent(this, PrivacyCenterActivity.class);
        startActivity(intent);
    }

    private void openPerformanceDashboard() {
        CrashReporter.setLastAction("profile_performance");
        Intent intent = new Intent(this, PerformanceDashboardActivity.class);
        startActivity(intent);
    }

    private void showLogoutConfirmation() {
        new AlertDialog.Builder(this)
            .setMessage(getString(R.string.profile_logout_confirm))
            .setNegativeButton(getString(R.string.conversation_action_cancel), null)
            .setPositiveButton(getString(R.string.conversation_action_logout), (dialog, which) -> {
                ClawPhonesAPI.clearToken(this);
                redirectToLogin();
            })
            .show();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
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
}
