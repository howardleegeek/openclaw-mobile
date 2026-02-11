package ai.clawphones.agent.chat;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import com.termux.R;

import org.json.JSONException;

import java.io.IOException;
import java.text.NumberFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ai.clawphones.agent.CrashReporter;

/**
 * Displays current subscription tier, today's token usage, and tier comparison.
 */
public class PlanActivity extends AppCompatActivity {

    private static final String[] TIER_ORDER = new String[]{"free", "pro", "max"};

    private TextView mCurrentTierValue;
    private ProgressBar mUsageProgress;
    private TextView mUsageDetail;
    private TextView mUsagePercent;
    private TextView mErrorText;

    private final Map<String, TierCardViews> mTierCards = new HashMap<>();

    private ExecutorService mExecutor;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean mDestroyed = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (TextUtils.isEmpty(ClawPhonesAPI.getToken(this))) {
            redirectToLogin();
            return;
        }

        setContentView(R.layout.activity_plan);

        Toolbar toolbar = findViewById(R.id.plan_toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            toolbar.setTitle(getString(R.string.plan_title));
            toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert);
            toolbar.setNavigationOnClickListener(v -> finish());
        }

        mCurrentTierValue = findViewById(R.id.plan_current_tier_value);
        mUsageProgress = findViewById(R.id.plan_usage_progress);
        mUsageDetail = findViewById(R.id.plan_usage_detail);
        mUsagePercent = findViewById(R.id.plan_usage_percent);
        mErrorText = findViewById(R.id.plan_error_text);

        bindTierCards();
        applyProgressTint();

        mExecutor = Executors.newSingleThreadExecutor();
        loadPlan();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (TextUtils.isEmpty(ClawPhonesAPI.getToken(this))) {
            redirectToLogin();
            return;
        }
        loadPlan();
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

    private void bindTierCards() {
        mTierCards.clear();
        mTierCards.put("free", new TierCardViews(
            findViewById(R.id.plan_tier_free_card),
            findViewById(R.id.plan_tier_free_name),
            findViewById(R.id.plan_tier_free_current_badge),
            findViewById(R.id.plan_tier_free_context_value),
            findViewById(R.id.plan_tier_free_output_value),
            findViewById(R.id.plan_tier_free_daily_value)
        ));
        mTierCards.put("pro", new TierCardViews(
            findViewById(R.id.plan_tier_pro_card),
            findViewById(R.id.plan_tier_pro_name),
            findViewById(R.id.plan_tier_pro_current_badge),
            findViewById(R.id.plan_tier_pro_context_value),
            findViewById(R.id.plan_tier_pro_output_value),
            findViewById(R.id.plan_tier_pro_daily_value)
        ));
        mTierCards.put("max", new TierCardViews(
            findViewById(R.id.plan_tier_max_card),
            findViewById(R.id.plan_tier_max_name),
            findViewById(R.id.plan_tier_max_current_badge),
            findViewById(R.id.plan_tier_max_context_value),
            findViewById(R.id.plan_tier_max_output_value),
            findViewById(R.id.plan_tier_max_daily_value)
        ));
    }

    private void applyProgressTint() {
        if (mUsageProgress == null) return;
        int accent = ContextCompat.getColor(this, R.color.clawphones_accent);
        int track = ContextCompat.getColor(this, R.color.clawphones_surface);
        mUsageProgress.setProgressTintList(ColorStateList.valueOf(accent));
        mUsageProgress.setProgressBackgroundTintList(ColorStateList.valueOf(track));
    }

    private void loadPlan() {
        showLoadingState();
        CrashReporter.setLastAction("loading_user_plan");

        execSafe(() -> {
            try {
                final ClawPhonesAPI.UserPlan plan = ClawPhonesAPI.getUserPlan(PlanActivity.this);
                runSafe(() -> renderPlan(plan));
            } catch (ClawPhonesAPI.ApiException e) {
                if (e.statusCode != 401) {
                    CrashReporter.reportNonFatal(PlanActivity.this, e, "loading_user_plan");
                }
                runSafe(() -> {
                    if (e.statusCode == 401) {
                        ClawPhonesAPI.clearToken(PlanActivity.this);
                        redirectToLogin();
                        return;
                    }
                    showError(getString(R.string.plan_error_load_failed));
                });
            } catch (IOException | JSONException e) {
                CrashReporter.reportNonFatal(PlanActivity.this, e, "loading_user_plan");
                runSafe(() -> showError(getString(R.string.plan_error_load_failed)));
            }
        });
    }

    private void showLoadingState() {
        if (mErrorText != null) {
            mErrorText.setVisibility(View.GONE);
        }
        if (mCurrentTierValue != null) {
            mCurrentTierValue.setText(getString(R.string.plan_loading));
        }
        if (mUsageDetail != null) {
            mUsageDetail.setText(getString(R.string.plan_loading));
        }
        if (mUsagePercent != null) {
            mUsagePercent.setText(getString(R.string.plan_value_unknown));
        }
        if (mUsageProgress != null) {
            mUsageProgress.setProgress(0);
        }

        for (String tier : TIER_ORDER) {
            TierCardViews views = mTierCards.get(tier);
            if (views == null) continue;
            views.tierName.setText(formatTierName(tier));
            views.currentBadge.setVisibility(View.GONE);
            views.container.setBackgroundResource(R.drawable.clawphones_plan_card_bg);
            views.contextValue.setText(getString(R.string.plan_value_unknown));
            views.outputValue.setText(getString(R.string.plan_value_unknown));
            views.dailyValue.setText(getString(R.string.plan_value_unknown));
        }
    }

    private void renderPlan(@Nullable ClawPhonesAPI.UserPlan plan) {
        if (plan == null) {
            showError(getString(R.string.plan_error_load_failed));
            return;
        }

        if (mErrorText != null) {
            mErrorText.setVisibility(View.GONE);
        }

        String currentTier = normalizeTier(plan.currentTier);
        if (mCurrentTierValue != null) {
            mCurrentTierValue.setText(formatTierName(currentTier));
        }

        long used = Math.max(0L, plan.todayUsedTokens);
        long limit = Math.max(0L, plan.dailyTokenLimit);

        int progress = 0;
        if (limit > 0L) {
            progress = (int) Math.min(100L, (used * 100L) / Math.max(1L, limit));
        }

        if (mUsageProgress != null) {
            mUsageProgress.setProgress(progress);
        }

        if (mUsageDetail != null) {
            if (limit > 0L) {
                mUsageDetail.setText(getString(
                    R.string.plan_usage_detail,
                    formatNumber(used),
                    formatNumber(limit)
                ));
            } else {
                mUsageDetail.setText(getString(
                    R.string.plan_usage_detail_no_limit,
                    formatNumber(used)
                ));
            }
        }

        if (mUsagePercent != null) {
            if (limit > 0L) {
                mUsagePercent.setText(getString(R.string.plan_usage_percent, progress));
            } else {
                mUsagePercent.setText(getString(R.string.plan_usage_percent_unavailable));
            }
        }

        HashMap<String, ClawPhonesAPI.PlanTier> tierMap = new HashMap<>();
        List<ClawPhonesAPI.PlanTier> tiers = plan.tiers;
        if (tiers != null) {
            for (ClawPhonesAPI.PlanTier tier : tiers) {
                if (tier == null || TextUtils.isEmpty(tier.tier)) continue;
                tierMap.put(normalizeTier(tier.tier), tier);
            }
        }

        for (String tierName : TIER_ORDER) {
            TierCardViews views = mTierCards.get(tierName);
            if (views == null) continue;

            ClawPhonesAPI.PlanTier tier = tierMap.get(tierName);
            boolean isCurrent = !TextUtils.isEmpty(currentTier) && TextUtils.equals(currentTier, tierName);

            views.tierName.setText(formatTierName(tierName));
            views.currentBadge.setVisibility(isCurrent ? View.VISIBLE : View.GONE);
            views.container.setBackgroundResource(
                isCurrent ? R.drawable.clawphones_plan_card_current_bg : R.drawable.clawphones_plan_card_bg
            );

            if (tier != null) {
                views.contextValue.setText(formatMetricValue(tier.contextLength));
                views.outputValue.setText(formatMetricValue(tier.outputLimit));
                views.dailyValue.setText(formatDailyCapValue(tier.dailyCap));
            } else {
                views.contextValue.setText(getString(R.string.plan_value_unknown));
                views.outputValue.setText(getString(R.string.plan_value_unknown));
                views.dailyValue.setText(getString(R.string.plan_value_unknown));
            }
        }
    }

    private void showError(String message) {
        if (mErrorText != null) {
            mErrorText.setText(message);
            mErrorText.setVisibility(View.VISIBLE);
        }
        toast(message);
    }

    private String normalizeTier(@Nullable String tier) {
        if (tier == null) return "";
        String t = tier.trim().toLowerCase(Locale.US);
        if ("basic".equals(t)) return "free";
        if ("plus".equals(t)) return "pro";
        return t;
    }

    private String formatTierName(@Nullable String tier) {
        String t = normalizeTier(tier);
        if ("free".equals(t)) return getString(R.string.plan_tier_free);
        if ("pro".equals(t)) return getString(R.string.plan_tier_pro);
        if ("max".equals(t)) return getString(R.string.plan_tier_max);
        if (!TextUtils.isEmpty(t)) return t.toUpperCase(Locale.US);
        return getString(R.string.plan_value_unknown);
    }

    private String formatMetricValue(long value) {
        if (value <= 0L) {
            return getString(R.string.plan_value_unknown);
        }
        return getString(R.string.plan_metric_tokens, formatNumber(value));
    }

    private String formatDailyCapValue(long value) {
        if (value <= 0L) {
            return getString(R.string.plan_value_unknown);
        }
        return getString(R.string.plan_metric_tokens_per_day, formatNumber(value));
    }

    private String formatNumber(long value) {
        NumberFormat format = NumberFormat.getIntegerInstance(Locale.getDefault());
        return format.format(Math.max(0L, value));
    }

    private void redirectToLogin() {
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    private void runSafe(@NonNull Runnable r) {
        if (mDestroyed) return;
        mMainHandler.post(() -> {
            if (!mDestroyed) {
                r.run();
            }
        });
    }

    private void execSafe(@NonNull Runnable r) {
        ExecutorService exec = mExecutor;
        if (exec != null && !exec.isShutdown()) {
            try {
                exec.execute(r);
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
            }
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private static final class TierCardViews {
        final LinearLayout container;
        final TextView tierName;
        final TextView currentBadge;
        final TextView contextValue;
        final TextView outputValue;
        final TextView dailyValue;

        TierCardViews(LinearLayout container, TextView tierName, TextView currentBadge,
                      TextView contextValue, TextView outputValue, TextView dailyValue) {
            this.container = container;
            this.tierName = tierName;
            this.currentBadge = currentBadge;
            this.contextValue = contextValue;
            this.outputValue = outputValue;
            this.dailyValue = dailyValue;
        }
    }
}
