package ai.clawphones.agent.chat;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Patterns;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;

import org.json.JSONException;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Simple login/register screen for ClawPhones backend auth.
 */
public class LoginActivity extends AppCompatActivity {

    private EditText mEmail;
    private EditText mPassword;
    private Button mLoginButton;
    private Button mRegisterButton;

    private ExecutorService mExecutor;
    private volatile boolean mDestroyed = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // If we already have a token, skip login.
        String existing = ClawPhonesAPI.getToken(this);
        if (!TextUtils.isEmpty(existing)) {
            openChatAndFinish();
            return;
        }

        setContentView(R.layout.activity_login);

        mEmail = findViewById(R.id.login_email);
        mPassword = findViewById(R.id.login_password);
        mLoginButton = findViewById(R.id.login_button);
        mRegisterButton = findViewById(R.id.register_button);

        mExecutor = Executors.newSingleThreadExecutor();

        mLoginButton.setOnClickListener(v -> onLogin());
        mRegisterButton.setOnClickListener(v -> onRegister());
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

    private void onLogin() {
        doAuth(false);
    }

    private void onRegister() {
        doAuth(true);
    }

    private void doAuth(boolean register) {
        String email = safeTrim(mEmail.getText().toString());
        String password = safeTrim(mPassword.getText().toString());

        // Validate email using Android's built-in pattern
        if (TextUtils.isEmpty(email) || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            toast("\u8bf7\u8f93\u5165\u6709\u6548\u90ae\u7bb1");
            return;
        }
        if (TextUtils.isEmpty(password) || password.length() < 8) {
            toast("\u5bc6\u7801\u81f3\u5c11 8 \u4f4d");
            return;
        }

        setBusy(true);

        ExecutorService exec = mExecutor;
        if (exec == null || exec.isShutdown()) return;

        try {
            exec.execute(() -> {
                try {
                    String token;
                    if (register) {
                        token = ClawPhonesAPI.register(email, password, email.split("@")[0]);
                    } else {
                        token = ClawPhonesAPI.login(email, password);
                    }
                    ClawPhonesAPI.saveToken(LoginActivity.this, token);
                    if (!mDestroyed) {
                        runOnUiThread(this::openChatAndFinish);
                    }
                } catch (IOException | JSONException e) {
                    if (!mDestroyed) {
                        runOnUiThread(() -> {
                            setBusy(false);
                            String msg = e.getMessage();
                            if (msg == null || msg.isEmpty()) msg = "\u672a\u77e5\u9519\u8bef";
                            toast("\u8bf7\u6c42\u5931\u8d25: " + msg);
                        });
                    }
                } catch (ClawPhonesAPI.ApiException e) {
                    if (!mDestroyed) {
                        runOnUiThread(() -> {
                            setBusy(false);
                            String msg = e.getMessage();
                            if (msg == null || msg.trim().isEmpty()) msg = "HTTP " + e.statusCode;
                            // Try to extract "detail" from JSON error
                            try {
                                org.json.JSONObject errJson = new org.json.JSONObject(msg);
                                String detail = errJson.optString("detail", null);
                                if (detail != null && !detail.trim().isEmpty()) msg = detail;
                            } catch (Exception ignored) {}
                            toast((register ? "\u6ce8\u518c" : "\u767b\u5f55") + "\u5931\u8d25: " + msg);
                        });
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Executor shut down during doAuth
        }
    }

    private void openChatAndFinish() {
        startActivity(new Intent(this, ChatActivity.class));
        finish();
    }

    private void setBusy(boolean busy) {
        if (mLoginButton != null) {
            mLoginButton.setEnabled(!busy);
            mLoginButton.setAlpha(busy ? 0.6f : 1.0f);
            mLoginButton.setText(busy ? "\u8bf7\u7a0d\u5019\u2026" : "\u767b\u5f55");
        }
        if (mRegisterButton != null) {
            mRegisterButton.setEnabled(!busy);
            mRegisterButton.setAlpha(busy ? 0.6f : 1.0f);
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private static String safeTrim(String s) {
        if (s == null) return "";
        return s.trim();
    }
}
