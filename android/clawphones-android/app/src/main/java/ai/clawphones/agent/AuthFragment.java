package ai.clawphones.agent;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.termux.R;
import com.termux.shared.logger.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Fragment for AI provider authentication.
 * Shows provider selection, then switches to auth input within the same fragment.
 */
public class AuthFragment extends Fragment {

    private static final String LOG_TAG = "AuthFragment";

    // Provider selection views
    private View mProviderSelectionView;
    private LinearLayout mPopularProvidersContainer;
    private LinearLayout mMoreProvidersContainer;
    private TextView mMoreToggle;
    
    // Auth input views (inflated from fragment_clawphones_auth_input.xml)
    private View mAuthInputView;
    private TextView mBackButton;
    private TextView mTitle;
    private TextView mInstructions;
    private LinearLayout mInputFieldContainer;
    private TextView mInputLabel;
    private EditText mInputField;
    private ImageButton mToggleVisibility;
    private Button mOAuthButton;
    private LinearLayout mStatusContainer;
    private TextView mStatusText;
    private Button mVerifyButton;
    
    private List<ProviderInfo> mPopularProviders;
    private List<ProviderInfo> mMoreProviders;
    
    private ProviderInfo mSelectedProvider;
    private ProviderInfo.AuthMethod mSelectedAuthMethod;
    private boolean mMoreExpanded = false;
    private boolean mPasswordVisible = false;
    
    // Keep track of all provider views for radio button management
    private List<View> mAllProviderViews = new ArrayList<>();

    private ClawPhonesService mService;
    private boolean mBound = false;

    // Track delayed callbacks to prevent memory leaks
    private Runnable mNavigationRunnable;

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            ClawPhonesService.LocalBinder binder = (ClawPhonesService.LocalBinder) service;
            mService = binder.getService();
            mBound = true;
            Logger.logDebug(LOG_TAG, "Service connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
            mBound = false;
            Logger.logDebug(LOG_TAG, "Service disconnected");
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // Create a container to hold both views
        LinearLayout containerLayout = new LinearLayout(requireContext());
        containerLayout.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        containerLayout.setOrientation(LinearLayout.VERTICAL);

        // Inflate provider selection view
        mProviderSelectionView = inflater.inflate(R.layout.fragment_clawphones_auth, containerLayout, false);
        containerLayout.addView(mProviderSelectionView);
        
        // Inflate auth input view
        mAuthInputView = inflater.inflate(R.layout.fragment_clawphones_auth_input, containerLayout, false);
        mAuthInputView.setVisibility(View.GONE);
        containerLayout.addView(mAuthInputView);

        setupProviderSelectionView();
        setupAuthInputView();

        return containerLayout;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (!ClawPhonesApp.bindClawPhonesService(requireActivity(), mConnection)) {
            Logger.logError(LOG_TAG, "Failed to bind ClawPhonesService");
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (mBound) {
            requireActivity().unbindService(mConnection);
            mBound = false;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        // Remove pending delayed callbacks to prevent memory leak
        if (mNavigationRunnable != null && mVerifyButton != null) {
            mVerifyButton.removeCallbacks(mNavigationRunnable);
            mNavigationRunnable = null;
        }

        // Release view references to prevent memory leak
        mAllProviderViews.clear();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Ensure service is unbound even if onStop() wasn't called
        // (e.g., if fragment was destroyed while in background)
        if (mBound) {
            try {
                requireActivity().unbindService(mConnection);
                Logger.logDebug(LOG_TAG, "Service unbound in onDestroy()");
            } catch (IllegalArgumentException e) {
                // Service was not bound or already unbound
                Logger.logDebug(LOG_TAG, "Service was already unbound");
            }
            mBound = false;
            mService = null;
        }
    }

    private void setupProviderSelectionView() {
        mPopularProvidersContainer = mProviderSelectionView.findViewById(R.id.auth_popular_providers);
        mMoreProvidersContainer = mProviderSelectionView.findViewById(R.id.auth_more_providers);
        mMoreToggle = mProviderSelectionView.findViewById(R.id.auth_more_toggle);

        // Load provider data
        mPopularProviders = ProviderInfo.getPopularProviders();
        mMoreProviders = ProviderInfo.getMoreProviders();

        // Populate popular providers
        for (ProviderInfo provider : mPopularProviders) {
            View providerView = createProviderView(provider);
            mPopularProvidersContainer.addView(providerView);
            mAllProviderViews.add(providerView);
        }

        // Populate more providers
        for (ProviderInfo provider : mMoreProviders) {
            View providerView = createProviderView(provider);
            mMoreProvidersContainer.addView(providerView);
            mAllProviderViews.add(providerView);
        }

        // Set up "More providers" toggle
        mMoreToggle.setOnClickListener(v -> toggleMoreProviders());
    }

    private void setupAuthInputView() {
        mBackButton = mAuthInputView.findViewById(R.id.auth_input_back);
        mTitle = mAuthInputView.findViewById(R.id.auth_input_title);
        mInstructions = mAuthInputView.findViewById(R.id.auth_input_instructions);
        mInputFieldContainer = mAuthInputView.findViewById(R.id.auth_input_field_container);
        mInputLabel = mAuthInputView.findViewById(R.id.auth_input_label);
        mInputField = mAuthInputView.findViewById(R.id.auth_input_field);
        mToggleVisibility = mAuthInputView.findViewById(R.id.auth_input_toggle_visibility);
        mOAuthButton = mAuthInputView.findViewById(R.id.auth_input_oauth_button);
        mStatusContainer = mAuthInputView.findViewById(R.id.auth_input_status_container);
        mStatusText = mAuthInputView.findViewById(R.id.auth_input_status_text);
        mVerifyButton = mAuthInputView.findViewById(R.id.auth_input_verify_button);

        // Set up back button
        mBackButton.setOnClickListener(v -> showProviderSelection());

        // Set up visibility toggle
        mToggleVisibility.setOnClickListener(v -> togglePasswordVisibility());

        // Set up OAuth button
        mOAuthButton.setOnClickListener(v -> handleOAuth());

        // Set up verify button
        mVerifyButton.setOnClickListener(v -> verifyAndContinue());
    }

    private View createProviderView(ProviderInfo provider) {
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        View view = inflater.inflate(R.layout.item_provider, null);

        RadioButton radio = view.findViewById(R.id.provider_radio);
        TextView name = view.findViewById(R.id.provider_name);
        TextView description = view.findViewById(R.id.provider_description);
        TextView recommendedBadge = view.findViewById(R.id.provider_recommended_badge);

        name.setText(provider.getName());
        description.setText(provider.getDescription());
        
        if (provider.isRecommended()) {
            recommendedBadge.setVisibility(View.VISIBLE);
        } else {
            recommendedBadge.setVisibility(View.GONE);
        }

        // Handle click on entire card
        view.setOnClickListener(v -> onProviderSelected(provider, radio));

        return view;
    }

    private void onProviderSelected(ProviderInfo provider, RadioButton selectedRadio) {
        Logger.logInfo(LOG_TAG, "Provider selected: " + provider.getName());
        
        mSelectedProvider = provider;

        // Update all radio buttons
        for (View providerView : mAllProviderViews) {
            RadioButton radio = providerView.findViewById(R.id.provider_radio);
            radio.setChecked(radio == selectedRadio);
        }

        // Show auth input for this provider
        showAuthInput(provider);
    }

    private void toggleMoreProviders() {
        mMoreExpanded = !mMoreExpanded;
        
        if (mMoreExpanded) {
            mMoreProvidersContainer.setVisibility(View.VISIBLE);
            mMoreToggle.setText(getString(R.string.auth_more_providers_expand));
        } else {
            mMoreProvidersContainer.setVisibility(View.GONE);
            mMoreToggle.setText(getString(R.string.auth_more_providers_collapse));
        }
    }

    private void showProviderSelection() {
        mProviderSelectionView.setVisibility(View.VISIBLE);
        mAuthInputView.setVisibility(View.GONE);
    }

    private void showAuthInput(ProviderInfo provider) {
        // Use primary auth method (first in list)
        mSelectedAuthMethod = provider.getAuthMethods().get(0);
        
        // Switch views
        mProviderSelectionView.setVisibility(View.GONE);
        mAuthInputView.setVisibility(View.VISIBLE);
        
        // Configure UI for the selected auth method
        setupUIForAuthMethod();
    }

    private void setupUIForAuthMethod() {
        mTitle.setText(mSelectedProvider.getName());
        mInputField.setText(""); // Clear previous input
        mStatusContainer.setVisibility(View.GONE);
        mPasswordVisible = false;

        switch (mSelectedAuthMethod) {
            case API_KEY:
                setupAPIKeyUI();
                break;
            case SETUP_TOKEN:
                setupSetupTokenUI();
                break;
            case OAUTH:
                setupOAuthUI();
                break;
        }
    }

    private void setupAPIKeyUI() {
        mInputLabel.setText(getString(R.string.auth_label_api_key));
        mInputField.setHint(getString(R.string.auth_hint_api_key));
        mInputField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        
        String instructions = getAPIKeyInstructions(mSelectedProvider.getId());
        mInstructions.setText(instructions);

        mInputFieldContainer.setVisibility(View.VISIBLE);
        mOAuthButton.setVisibility(View.GONE);
    }

    private void setupSetupTokenUI() {
        mInputLabel.setText(getString(R.string.auth_label_setup_token));
        mInputField.setHint(getString(R.string.auth_hint_setup_token));
        mInputField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        mInstructions.setText(getString(R.string.auth_setup_token_instructions));

        mInputFieldContainer.setVisibility(View.VISIBLE);
        mOAuthButton.setVisibility(View.GONE);
    }

    private void setupOAuthUI() {
        String instructions = getString(R.string.auth_oauth_instructions, mSelectedProvider.getName());
        mInstructions.setText(instructions);

        mInputFieldContainer.setVisibility(View.GONE);
        mOAuthButton.setVisibility(View.VISIBLE);
        mOAuthButton.setText(getString(R.string.auth_oauth_button));
    }

    private String getAPIKeyInstructions(String providerId) {
        switch (providerId) {
            case "anthropic":
                return getString(R.string.auth_api_instructions_anthropic);
            case "openai":
                return getString(R.string.auth_api_instructions_openai);
            case "google":
                return getString(R.string.auth_api_instructions_google);
            case "openrouter":
                return getString(R.string.auth_api_instructions_openrouter);
            default:
                return getString(R.string.auth_api_instructions_default, mSelectedProvider.getName());
        }
    }

    private void togglePasswordVisibility() {
        mPasswordVisible = !mPasswordVisible;
        
        if (mPasswordVisible) {
            mInputField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        } else {
            mInputField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        
        // Move cursor to end
        mInputField.setSelection(mInputField.getText().length());
    }

    private void handleOAuth() {
        Logger.logInfo(LOG_TAG, "OAuth requested for: " + mSelectedProvider.getId());
        
        showStatus(getString(R.string.auth_status_oauth_not_implemented), false);
        
        // TODO: Implement OAuth via openclaw CLI
    }

    private void verifyAndContinue() {
        String credential = mInputField.getText().toString().trim();

        if (TextUtils.isEmpty(credential)) {
            showStatus(getString(R.string.auth_error_enter_credential, mInputLabel.getText().toString()), false);
            return;
        }

        // Basic format validation
        if (!validateCredentialFormat(credential)) {
            showStatus(getString(R.string.auth_error_invalid_format), false);
            return;
        }

        // Show progress
        mVerifyButton.setEnabled(false);
        mVerifyButton.setText(getString(R.string.auth_button_verifying));
        showStatus(getString(R.string.auth_status_verifying), true);

        // Save to config and verify
        saveCredentials(credential);
    }

    private boolean validateCredentialFormat(String credential) {
        // Basic length check only — different providers have different formats
        return credential.length() >= 8;
    }

    private void saveCredentials(String credential) {
        String model = getDefaultModel(mSelectedProvider.getId());
        String providerId = mSelectedProvider.getId();

        Logger.logInfo(LOG_TAG, "Saving credentials for provider: " + providerId);

        // Write API key and provider config directly (no CLI dependency)
        boolean keyWritten = ClawPhonesConfig.setApiKey(providerId, credential);
        boolean providerWritten = ClawPhonesConfig.setProvider(providerId, model);

        if (keyWritten && providerWritten) {
            Logger.logInfo(LOG_TAG, "Auth configured successfully");
            showStatus(getString(R.string.auth_status_connected_model, providerId, model), true);

            // Auto-advance after short delay
            // Track runnable so we can remove it in onDestroyView() if needed
            mNavigationRunnable = () -> {
                if (!isAdded() || !isResumed()) return;
                SetupActivity activity = (SetupActivity) getActivity();
                if (activity != null && !activity.isFinishing()) {
                    activity.goToNextStep();
                }
            };
            mVerifyButton.postDelayed(mNavigationRunnable, 1500);
        } else {
            showStatus(getString(R.string.auth_error_write_config_failed), false);
            resetVerifyButton();
        }
    }

    private String getDefaultModel(String providerId) {
        switch (providerId) {
            case "anthropic":
                return "claude-sonnet-4-5";
            case "openai":
                return "gpt-4o";
            case "google":
                return "gemini-3-flash-preview";
            case "openrouter":
                return "anthropic/claude-sonnet-4";
            default:
                return "default";
        }
    }

    private void showStatus(String message, boolean success) {
        mStatusText.setText(message);
        mStatusContainer.setVisibility(View.VISIBLE);
    }

    private void resetVerifyButton() {
        mVerifyButton.setEnabled(true);
        mVerifyButton.setText(getString(R.string.auth_button_verify_continue));
    }
}
