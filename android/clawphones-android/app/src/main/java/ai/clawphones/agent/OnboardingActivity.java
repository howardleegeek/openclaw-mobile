package ai.clawphones.agent;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.termux.R;

import java.util.Arrays;
import java.util.List;

/**
 * First-launch onboarding flow for ClawPhones.
 */
public class OnboardingActivity extends AppCompatActivity {

    public static final String PREFS_NAME = "clawphones_prefs";
    public static final String KEY_HAS_SEEN_ONBOARDING = "has_seen_onboarding";

    private ViewPager2 mViewPager;
    private LinearLayout mIndicators;
    private Button mPrimaryButton;

    private List<OnboardingPage> mPages;

    private final ViewPager2.OnPageChangeCallback mPageChangeCallback = new ViewPager2.OnPageChangeCallback() {
        @Override
        public void onPageSelected(int position) {
            updateIndicators(position);
            updatePrimaryButton(position);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (hasSeenOnboarding()) {
            openLoginAndFinish();
            return;
        }

        setContentView(R.layout.activity_onboarding);

        mPages = Arrays.asList(
            new OnboardingPage(android.R.drawable.ic_dialog_info,
                R.string.onboarding_title_ai_assistant,
                R.string.onboarding_desc_ai_assistant),
            new OnboardingPage(android.R.drawable.ic_menu_manage,
                R.string.onboarding_title_multiple_models,
                R.string.onboarding_desc_multiple_models),
            new OnboardingPage(android.R.drawable.ic_lock_lock,
                R.string.onboarding_title_data_privacy,
                R.string.onboarding_desc_data_privacy),
            new OnboardingPage(android.R.drawable.ic_media_play,
                R.string.onboarding_title_get_started,
                R.string.onboarding_desc_get_started)
        );

        mViewPager = findViewById(R.id.onboarding_view_pager);
        mIndicators = findViewById(R.id.onboarding_indicators);
        mPrimaryButton = findViewById(R.id.btn_onboarding_primary);
        Button skipButton = findViewById(R.id.btn_onboarding_skip);

        mViewPager.setAdapter(new OnboardingPagerAdapter(mPages));
        mViewPager.registerOnPageChangeCallback(mPageChangeCallback);

        setupIndicators(mPages.size());
        updateIndicators(0);
        updatePrimaryButton(0);

        skipButton.setOnClickListener(v -> completeOnboarding());
        mPrimaryButton.setOnClickListener(v -> {
            int current = mViewPager.getCurrentItem();
            if (current >= mPages.size() - 1) {
                completeOnboarding();
                return;
            }
            mViewPager.setCurrentItem(current + 1, true);
        });
    }

    @Override
    protected void onDestroy() {
        if (mViewPager != null) {
            mViewPager.unregisterOnPageChangeCallback(mPageChangeCallback);
        }
        super.onDestroy();
    }

    private boolean hasSeenOnboarding() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getBoolean(KEY_HAS_SEEN_ONBOARDING, false);
    }

    private void completeOnboarding() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_HAS_SEEN_ONBOARDING, true).apply();
        openLoginAndFinish();
    }

    private void openLoginAndFinish() {
        Intent intent = new Intent(this, ai.clawphones.agent.chat.LoginActivity.class);
        startActivity(intent);
        finish();
    }

    private void setupIndicators(int count) {
        mIndicators.removeAllViews();

        for (int i = 0; i < count; i++) {
            View indicator = new View(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dpToPx(8), dpToPx(8));
            params.setMarginStart(dpToPx(5));
            params.setMarginEnd(dpToPx(5));
            indicator.setLayoutParams(params);
            indicator.setBackgroundResource(R.drawable.onboarding_indicator_inactive);
            mIndicators.addView(indicator);
        }
    }

    private void updateIndicators(int activePosition) {
        int childCount = mIndicators.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View dot = mIndicators.getChildAt(i);
            dot.setBackgroundResource(
                i == activePosition
                    ? R.drawable.onboarding_indicator_active
                    : R.drawable.onboarding_indicator_inactive
            );
        }
    }

    private void updatePrimaryButton(int pagePosition) {
        if (pagePosition >= mPages.size() - 1) {
            mPrimaryButton.setText(R.string.onboarding_button_get_started);
        } else {
            mPrimaryButton.setText(R.string.onboarding_button_next);
        }
    }

    private int dpToPx(int dp) {
        return Math.round(getResources().getDisplayMetrics().density * dp);
    }

    private static final class OnboardingPage {
        final int iconRes;
        final int titleRes;
        final int descriptionRes;

        OnboardingPage(int iconRes, int titleRes, int descriptionRes) {
            this.iconRes = iconRes;
            this.titleRes = titleRes;
            this.descriptionRes = descriptionRes;
        }
    }

    private static final class OnboardingPagerAdapter
        extends RecyclerView.Adapter<OnboardingPagerAdapter.OnboardingPageViewHolder> {

        private final List<OnboardingPage> pages;

        OnboardingPagerAdapter(List<OnboardingPage> pages) {
            this.pages = pages;
        }

        @NonNull
        @Override
        public OnboardingPageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_onboarding_page, parent, false);
            return new OnboardingPageViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull OnboardingPageViewHolder holder, int position) {
            OnboardingPage page = pages.get(position);
            holder.icon.setImageResource(page.iconRes);
            holder.title.setText(page.titleRes);
            holder.description.setText(page.descriptionRes);
        }

        @Override
        public int getItemCount() {
            return pages.size();
        }

        static final class OnboardingPageViewHolder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView title;
            final TextView description;

            OnboardingPageViewHolder(@NonNull View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.onboarding_page_icon);
                title = itemView.findViewById(R.id.onboarding_page_title);
                description = itemView.findViewById(R.id.onboarding_page_description);
            }
        }
    }
}
