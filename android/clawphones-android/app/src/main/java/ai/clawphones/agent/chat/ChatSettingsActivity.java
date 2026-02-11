package ai.clawphones.agent.chat;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.termux.R;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ai.clawphones.agent.CrashReporter;

public class ChatSettingsActivity extends AppCompatActivity {

    private static final List<String> PERSONA_ORDER = Arrays.asList(
        "assistant", "coder", "writer", "translator", "custom");

    private ProgressBar mLoading;
    private TextView mPlanTier;
    private TextView mPlanUsage;
    private Spinner mPersonaSpinner;
    private EditText mCustomPromptInput;
    private Spinner mLanguageSpinner;
    private Button mRefreshPlanButton;
    private Button mSaveAIButton;
    private Button mSaveLanguageButton;

    private ArrayAdapter<String> mPersonaAdapter;
    private ArrayAdapter<String> mLanguageAdapter;
    private final ArrayList<Option> mPersonaOptions = new ArrayList<>();
    private final ArrayList<Option> mLanguageOptions = new ArrayList<>();

    private ExecutorService mExecutor;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean mDestroyed = false;
    private volatile boolean mBusy = false;

    private String mToken;

    private static final class Option {
        final String value;
        final String label;

        Option(String value, String label) {
            this.value = value;
            this.label = label;
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mToken = ClawPhonesAPI.getToken(this);
        if (TextUtils.isEmpty(mToken)) {
            redirectToLogin(getString(R.string.chat_login_expired));
            return;
        }

        setContentView(R.layout.activity_chat_settings);

        Toolbar toolbar = findViewById(R.id.chat_settings_toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert);
            toolbar.setNavigationOnClickListener(v -> finish());
        }

        mLoading = findViewById(R.id.settings_loading);
        mPlanTier = findViewById(R.id.settings_plan_tier);
        mPlanUsage = findViewById(R.id.settings_plan_usage);
        mPersonaSpinner = findViewById(R.id.settings_persona_spinner);
        mCustomPromptInput = findViewById(R.id.settings_custom_prompt_input);
        mLanguageSpinner = findViewById(R.id.settings_language_spinner);
        mRefreshPlanButton = findViewById(R.id.settings_refresh_plan_btn);
        mSaveAIButton = findViewById(R.id.settings_save_ai_btn);
        mSaveLanguageButton = findViewById(R.id.settings_save_language_btn);

        setupPersonaSpinner();
        setupLanguageSpinner();

        mExecutor = Executors.newSingleThreadExecutor();

        if (mRefreshPlanButton != null) {
            mRefreshPlanButton.setOnClickListener(v -> loadSettings());
        }
        if (mSaveAIButton != null) {
            mSaveAIButton.setOnClickListener(v -> saveAIConfig());
        }
        if (mSaveLanguageButton != null) {
            mSaveLanguageButton.setOnClickListener(v -> saveLanguage());
        }

        loadSettings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mToken = ClawPhonesAPI.getToken(this);
        if (TextUtils.isEmpty(mToken)) {
            redirectToLogin(getString(R.string.chat_login_expired));
        }
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

    private void setupPersonaSpinner() {
        mPersonaAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>());
        mPersonaAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        if (mPersonaSpinner != null) {
            mPersonaSpinner.setAdapter(mPersonaAdapter);
        }
        rebuildPersonaOptions(null, "assistant");
    }

    private void setupLanguageSpinner() {
        mLanguageOptions.clear();
        mLanguageOptions.add(new Option("auto", getString(R.string.settings_language_auto)));
        mLanguageOptions.add(new Option("zh", getString(R.string.settings_language_zh)));
        mLanguageOptions.add(new Option("en", getString(R.string.settings_language_en)));

        ArrayList<String> labels = new ArrayList<>();
        for (Option option : mLanguageOptions) {
            labels.add(option.label);
        }

        mLanguageAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        mLanguageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        if (mLanguageSpinner != null) {
            mLanguageSpinner.setAdapter(mLanguageAdapter);
        }
        selectSpinnerValue(mLanguageSpinner, mLanguageOptions, "auto");
    }

    private void loadSettings() {
        if (mBusy) return;
        setBusy(true);
        CrashReporter.setLastAction("loading_settings");

        execSafe(() -> {
            try {
                ClawPhonesAPI.UserProfile profile = ClawPhonesAPI.getUserProfile(ChatSettingsActivity.this);
                ClawPhonesAPI.AIConfig aiConfig = ClawPhonesAPI.getAIConfig(ChatSettingsActivity.this);
                ClawPhonesAPI.UserPlan plan = ClawPhonesAPI.getUserPlan(ChatSettingsActivity.this);
                runSafe(() -> {
                    bindProfile(profile);
                    bindAIConfig(aiConfig);
                    bindPlan(plan);
                    setBusy(false);
                });
            } catch (ClawPhonesAPI.ApiException e) {
                CrashReporter.reportNonFatal(ChatSettingsActivity.this, e, "loading_settings_api");
                runSafe(() -> {
                    if (e.statusCode == 401) {
                        ClawPhonesAPI.clearToken(ChatSettingsActivity.this);
                        redirectToLogin(getString(R.string.chat_login_expired));
                        return;
                    }
                    toast(getString(R.string.settings_error_load_failed, safeApiMessage(e)));
                    setBusy(false);
                });
            } catch (IOException | JSONException e) {
                CrashReporter.reportNonFatal(ChatSettingsActivity.this, e, "loading_settings");
                runSafe(() -> {
                    toast(getString(R.string.settings_error_load_failed, safeMessage(e)));
                    setBusy(false);
                });
            }
        });
    }

    private void saveAIConfig() {
        if (mBusy) return;
        final String persona = getSelectedValue(mPersonaSpinner, mPersonaOptions, "assistant");
        final String prompt = safeTrim(mCustomPromptInput == null ? "" : mCustomPromptInput.getText().toString());

        setBusy(true);
        CrashReporter.setLastAction("saving_ai_config");

        execSafe(() -> {
            try {
                ClawPhonesAPI.AIConfig saved = ClawPhonesAPI.updateAIConfig(ChatSettingsActivity.this, persona, prompt, null);
                runSafe(() -> {
                    bindAIConfig(saved);
                    toast(getString(R.string.settings_saved_ai));
                    setBusy(false);
                });
            } catch (ClawPhonesAPI.ApiException e) {
                CrashReporter.reportNonFatal(ChatSettingsActivity.this, e, "saving_ai_config_api");
                runSafe(() -> {
                    if (e.statusCode == 401) {
                        ClawPhonesAPI.clearToken(ChatSettingsActivity.this);
                        redirectToLogin(getString(R.string.chat_login_expired));
                        return;
                    }
                    toast(getString(R.string.settings_error_save_ai, safeApiMessage(e)));
                    setBusy(false);
                });
            } catch (IOException | JSONException e) {
                CrashReporter.reportNonFatal(ChatSettingsActivity.this, e, "saving_ai_config");
                runSafe(() -> {
                    toast(getString(R.string.settings_error_save_ai, safeMessage(e)));
                    setBusy(false);
                });
            }
        });
    }

    private void saveLanguage() {
        if (mBusy) return;
        final String language = getSelectedValue(mLanguageSpinner, mLanguageOptions, "auto");

        setBusy(true);
        CrashReporter.setLastAction("saving_language");

        execSafe(() -> {
            try {
                ClawPhonesAPI.UserProfile profile =
                    ClawPhonesAPI.updateUserProfile(ChatSettingsActivity.this, null, language);
                runSafe(() -> {
                    bindProfile(profile);
                    toast(getString(R.string.settings_saved_language));
                    setBusy(false);
                });
            } catch (ClawPhonesAPI.ApiException e) {
                CrashReporter.reportNonFatal(ChatSettingsActivity.this, e, "saving_language_api");
                runSafe(() -> {
                    if (e.statusCode == 401) {
                        ClawPhonesAPI.clearToken(ChatSettingsActivity.this);
                        redirectToLogin(getString(R.string.chat_login_expired));
                        return;
                    }
                    toast(getString(R.string.settings_error_save_language, safeApiMessage(e)));
                    setBusy(false);
                });
            } catch (IOException | JSONException e) {
                CrashReporter.reportNonFatal(ChatSettingsActivity.this, e, "saving_language");
                runSafe(() -> {
                    toast(getString(R.string.settings_error_save_language, safeMessage(e)));
                    setBusy(false);
                });
            }
        });
    }

    private void bindProfile(ClawPhonesAPI.UserProfile profile) {
        if (profile == null) return;
        selectSpinnerValue(mLanguageSpinner, mLanguageOptions, profile.language);
    }

    private void bindAIConfig(ClawPhonesAPI.AIConfig aiConfig) {
        if (aiConfig == null) return;
        rebuildPersonaOptions(aiConfig.personas, aiConfig.persona);
        if (mCustomPromptInput != null) {
            mCustomPromptInput.setText(aiConfig.customPrompt == null ? "" : aiConfig.customPrompt);
        }
    }

    private void bindPlan(ClawPhonesAPI.UserPlan plan) {
        if (plan == null) return;
        if (mPlanTier != null) {
            mPlanTier.setText(getString(
                R.string.settings_plan_tier_value,
                toTierDisplay(plan.currentTier)
            ));
        }
        if (mPlanUsage != null) {
            if (plan.dailyTokenLimit > 0) {
                mPlanUsage.setText(getString(
                    R.string.settings_plan_usage_value,
                    plan.todayUsedTokens,
                    plan.dailyTokenLimit
                ));
            } else {
                mPlanUsage.setText(getString(
                    R.string.settings_plan_usage_unknown,
                    plan.todayUsedTokens
                ));
            }
        }
    }

    private void rebuildPersonaOptions(@Nullable List<String> fromServer, String selected) {
        ArrayList<String> ordered = new ArrayList<>(PERSONA_ORDER);
        if (fromServer != null) {
            for (String raw : fromServer) {
                String normalized = normalizePersona(raw);
                if (TextUtils.isEmpty(normalized)) continue;
                if (!ordered.contains(normalized)) {
                    ordered.add(normalized);
                }
            }
        }

        mPersonaOptions.clear();
        for (String persona : ordered) {
            mPersonaOptions.add(new Option(persona, personaLabel(persona)));
        }

        ArrayList<String> labels = new ArrayList<>();
        for (Option option : mPersonaOptions) {
            labels.add(option.label);
        }

        mPersonaAdapter.clear();
        mPersonaAdapter.addAll(labels);
        mPersonaAdapter.notifyDataSetChanged();

        selectSpinnerValue(mPersonaSpinner, mPersonaOptions, normalizePersona(selected));
    }

    private void setBusy(boolean busy) {
        mBusy = busy;
        int loadingVisibility = busy ? View.VISIBLE : View.GONE;
        if (mLoading != null) mLoading.setVisibility(loadingVisibility);

        boolean enabled = !busy;
        if (mPersonaSpinner != null) mPersonaSpinner.setEnabled(enabled);
        if (mCustomPromptInput != null) mCustomPromptInput.setEnabled(enabled);
        if (mLanguageSpinner != null) mLanguageSpinner.setEnabled(enabled);
        if (mRefreshPlanButton != null) mRefreshPlanButton.setEnabled(enabled);
        if (mSaveAIButton != null) mSaveAIButton.setEnabled(enabled);
        if (mSaveLanguageButton != null) mSaveLanguageButton.setEnabled(enabled);
    }

    private void redirectToLogin(@Nullable String message) {
        if (!TextUtils.isEmpty(message)) {
            toast(message);
        }
        Intent intent = new Intent(this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void runSafe(Runnable runnable) {
        if (mDestroyed) return;
        mMainHandler.post(() -> {
            if (!mDestroyed) runnable.run();
        });
    }

    private void execSafe(Runnable runnable) {
        ExecutorService exec = mExecutor;
        if (exec != null && !exec.isShutdown()) {
            try {
                exec.execute(runnable);
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
            }
        }
    }

    private String safeApiMessage(ClawPhonesAPI.ApiException e) {
        String msg = e.getMessage();
        if (TextUtils.isEmpty(msg)) {
            return getString(R.string.chat_error_http_status, e.statusCode);
        }
        try {
            org.json.JSONObject err = new org.json.JSONObject(msg);
            String detail = err.optString("detail", null);
            if (!TextUtils.isEmpty(detail)) return detail;
        } catch (Exception ignored) {
        }
        if (msg.length() > 140) {
            msg = msg.substring(0, 140) + "…";
        }
        return msg;
    }

    private String safeMessage(Exception e) {
        String msg = e.getMessage();
        if (TextUtils.isEmpty(msg)) return getString(R.string.chat_error_unknown);
        if (msg.length() > 140) msg = msg.substring(0, 140) + "…";
        return msg;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizePersona(String persona) {
        if (persona == null) return "assistant";
        String p = persona.trim().toLowerCase();
        if (p.isEmpty()) return "assistant";
        if ("general".equals(p)) return "assistant";
        if ("coding".equals(p)) return "coder";
        if ("writing".equals(p)) return "writer";
        if ("translation".equals(p)) return "translator";
        return p;
    }

    private String toTierDisplay(String tier) {
        String normalized = tier == null ? "" : tier.trim().toLowerCase();
        switch (normalized) {
            case "pro":
                return "Pro";
            case "max":
                return "Max";
            case "free":
                return "Free";
            default:
                if (TextUtils.isEmpty(normalized)) return "-";
                return normalized.toUpperCase();
        }
    }

    private String personaLabel(String persona) {
        switch (normalizePersona(persona)) {
            case "coder":
                return getString(R.string.settings_persona_coder);
            case "writer":
                return getString(R.string.settings_persona_writer);
            case "translator":
                return getString(R.string.settings_persona_translator);
            case "custom":
                return getString(R.string.settings_persona_custom);
            default:
                return getString(R.string.settings_persona_assistant);
        }
    }

    private static String getSelectedValue(Spinner spinner, List<Option> options, String fallback) {
        if (spinner == null || options == null || options.isEmpty()) return fallback;
        int index = spinner.getSelectedItemPosition();
        if (index < 0 || index >= options.size()) return fallback;
        String value = options.get(index).value;
        if (TextUtils.isEmpty(value)) return fallback;
        return value;
    }

    private static void selectSpinnerValue(Spinner spinner, List<Option> options, String value) {
        if (spinner == null || options == null || options.isEmpty()) return;
        String normalized = value == null ? "" : value.trim().toLowerCase();
        int target = 0;
        for (int i = 0; i < options.size(); i++) {
            String item = options.get(i).value;
            if (item != null && item.equalsIgnoreCase(normalized)) {
                target = i;
                break;
            }
        }
        spinner.setSelection(target, false);
    }
}
