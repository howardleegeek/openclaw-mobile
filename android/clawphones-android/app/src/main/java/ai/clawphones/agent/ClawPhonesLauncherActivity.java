package ai.clawphones.agent;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.annotation.Nullable;

/**
 * App entry activity.
 * Routes first-time users to onboarding, otherwise directly to login flow.
 */
public class ClawPhonesLauncherActivity extends Activity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashReporter.init(getApplicationContext());

        SharedPreferences prefs = getSharedPreferences(OnboardingActivity.PREFS_NAME, MODE_PRIVATE);
        boolean hasSeenOnboarding = prefs.getBoolean(OnboardingActivity.KEY_HAS_SEEN_ONBOARDING, false);

        Class<?> nextActivity = hasSeenOnboarding
            ? ai.clawphones.agent.chat.LoginActivity.class
            : OnboardingActivity.class;

        startActivity(new Intent(this, nextActivity));
        finish();
    }
}
