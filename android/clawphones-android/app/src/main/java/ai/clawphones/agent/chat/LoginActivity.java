package ai.clawphones.agent.chat;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Patterns;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;

import org.json.JSONException;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ai.clawphones.agent.CrashReporter;

/**
 * Simple login/register screen for ClawPhones backend auth.
 */
public class LoginActivity extends AppCompatActivity {

    private EditText mEmail;
    private EditText mPassword;
    private EditText mName;
    private Button mSubmitButton;
    private TextView mToggle;

    private boolean mRegisterMode = false;

    private ExecutorService mExecutor;
    private volatile boolean mDestroyed = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // If we already have a token, skip login.
        String existing = ClawPhonesAPI.getToken(this);
        if (!TextUtils.isEmpty(existing)) {
            openConversationListAndFinish();
            return;
        }

        setContentView(R.layout.activity_login);

        mEmail = findViewById(R.id.login_email);
        mPassword = findViewById(R.id.login_password);
        mName = findViewById(R.id.login_name);
        mSubmitButton = findViewById(R.id.login_submit);
        mToggle = findViewById(R.id.login_toggle);

        mExecutor = Executors.newSingleThreadExecutor();

        updateModeUi();

        mSubmitButton.setOnClickListener(v -> doAuth(mRegisterMode));
        mToggle.setOnClickListener(v -> {
            mRegisterMode = !mRegisterMode;
            updateModeUi();
        });
    }

    @Override
    protected void onDestroy() {
        // Shutdown executor BEFORE super to prevent callbacks on destroyed activity
        mDestroyed = true;
        if (mExecutor != null) {
            try {
                mExecutor.shutdownNow();
            } catch (Exception ignored) {}
            mExecutor = null;
        }
        super.onDestroy();
    }

    private void updateModeUi() {
        if (mName != null) {
            mName.setVisibility(mRegisterMode ? View.VISIBLE : View.GONE);
        }
        if (mSubmitButton != null) {
            mSubmitButton.setText(mRegisterMode
                ? R.string.login_button_register
                : R.string.login_button_login);
        }
        if (mToggle != null) {
            mToggle.setText(mRegisterMode
                ? R.string.login_toggle_to_login
                : R.string.login_toggle_to_register);
        }
    }

    private void doAuth(boolean register) {
        CrashReporter.setLastAction(register ? "register" : "login");
        String email = safeTrim(mEmail.getText().toString());
        String password = safeTrim(mPassword.getText().toString());
        String name = mRegisterMode && mName != null ? safeTrim(mName.getText().toString()) : "";

        // Validate email using Android's built-in pattern
        if (TextUtils.isEmpty(email) || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            toast(getString(R.string.login_error_invalid_email));
            return;
        }
        if (TextUtils.isEmpty(password) || password.length() < 8) {
            toast(getString(R.string.login_error_password_too_short));
            return;
        }
        if (register && TextUtils.isEmpty(name)) {
            toast(getString(R.string.login_error_empty_name));
            return;
        }

        setBusy(true);

        ExecutorService exec = mExecutor;
        if (exec == null || exec.isShutdown()) {
            setBusy(false);
            return;
        }

        try {
            exec.execute(() -> {
                try {
                    ClawPhonesAPI.AuthToken authToken;
                    if (register) {
                        authToken = ClawPhonesAPI.register(email, password, name);
                    } else {
                        authToken = ClawPhonesAPI.login(email, password);
                    }
                    ClawPhonesAPI.saveToken(LoginActivity.this, authToken.token, authToken.expiresAt);
                    if (!mDestroyed) {
                        runOnUiThread(this::openConversationListAndFinish);
                    }
                } catch (IOException | JSONException e) {
                    if (!mDestroyed) {
                        runOnUiThread(() -> {
                            setBusy(false);
                            String msg = e.getMessage();
                            if (msg == null || msg.isEmpty()) msg = getString(R.string.login_error_unknown);
                            toast(getString(R.string.login_error_request_failed, msg));
                        });
                    }
                } catch (ClawPhonesAPI.ApiException e) {
                    if (!mDestroyed) {
                        runOnUiThread(() -> {
                            setBusy(false);
                            String msg = e.getMessage();
                            if (msg == null || msg.trim().isEmpty()) {
                                msg = getString(R.string.login_error_http_status, e.statusCode);
                            }
                            // Try to extract "detail" from JSON error
                            try {
                                org.json.JSONObject errJson = new org.json.JSONObject(msg);
                                String detail = errJson.optString("detail", null);
                                if (detail != null && !detail.trim().isEmpty()) msg = detail;
                            } catch (Exception ignored) {}
                            String action = register
                                ? getString(R.string.login_action_register)
                                : getString(R.string.login_action_login);
                            toast(getString(R.string.login_error_action_failed, action, msg));
                        });
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Executor shut down during doAuth
        }
    }

    private void openConversationListAndFinish() {
        startActivity(new Intent(this, ConversationListActivity.class));
        finish();
    }

    private void setBusy(boolean busy) {
        if (mSubmitButton != null) {
            mSubmitButton.setEnabled(!busy);
            mSubmitButton.setAlpha(busy ? 0.6f : 1.0f);
            if (busy) {
                mSubmitButton.setText(mRegisterMode
                    ? R.string.login_loading_register
                    : R.string.login_loading_login);
            } else {
                mSubmitButton.setText(mRegisterMode
                    ? R.string.login_button_register
                    : R.string.login_button_login);
            }
        }
        if (mToggle != null) {
            mToggle.setEnabled(!busy);
            mToggle.setAlpha(busy ? 0.6f : 1.0f);
        }
        if (mEmail != null) mEmail.setEnabled(!busy);
        if (mPassword != null) mPassword.setEnabled(!busy);
        if (mName != null) mName.setEnabled(!busy);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private static String safeTrim(String s) {
        if (s == null) return "";
        return s.trim();
    }
}
