package ai.clawphones.agent.chat;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.termux.R;

import org.json.JSONException;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Simple login/register screen for ClawPhones backend auth.
 */
public class LoginActivity extends Activity {

    private EditText mEmail;
    private EditText mPassword;
    private Button mLoginButton;
    private Button mRegisterButton;

    private ExecutorService mExecutor;

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
        super.onDestroy();
        if (mExecutor != null) {
            try {
                mExecutor.shutdownNow();
            } catch (Exception ignored) {}
            mExecutor = null;
        }
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

        if (TextUtils.isEmpty(email) || !email.contains("@")) {
            toast("请输入有效邮箱");
            return;
        }
        if (TextUtils.isEmpty(password) || password.length() < 8) {
            toast("密码至少 8 位");
            return;
        }

        setBusy(true);

        mExecutor.execute(() -> {
            try {
                String token;
                if (register) {
                    token = ClawPhonesAPI.register(email, password, email.split("@")[0]);
                } else {
                    token = ClawPhonesAPI.login(email, password);
                }
                ClawPhonesAPI.saveToken(LoginActivity.this, token);
                runOnUiThread(this::openChatAndFinish);
            } catch (IOException | JSONException e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    toast("请求失败: " + e.getMessage());
                });
            } catch (ClawPhonesAPI.ApiException e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    String msg = e.getMessage();
                    if (msg == null || msg.trim().isEmpty()) msg = "HTTP " + e.statusCode;
                    toast((register ? "注册" : "登录") + "失败: " + msg);
                });
            }
        });
    }

    private void openChatAndFinish() {
        startActivity(new Intent(this, ChatActivity.class));
        finish();
    }

    private void setBusy(boolean busy) {
        if (mLoginButton != null) {
            mLoginButton.setEnabled(!busy);
            mLoginButton.setAlpha(busy ? 0.6f : 1.0f);
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

