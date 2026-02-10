package ai.clawphones.agent;

import android.content.Context;

import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Method;

/**
 * Factory/OEM provisioning helper.
 *
 * Goal: "傻瓜可用" (no API keys, no setup wizard). Read a per-device token from the OS,
 * write OpenClaw config automatically, and keep the app UI simple.
 *
 * Token source priority:
 * 1) Android system property: persist.oyster.device_token (recommended for factory/ROM)
 * 2) App-private file: /data/data/ai.clawphones.agent/files/device_token.txt (debug-friendly via run-as)
 *
 * NOTE: Never log the token value.
 */
public final class PreinstallProvisioner {

    private static final String LOG_TAG = "PreinstallProvisioner";

    // Factory-set system properties (preferred).
    private static final String PROP_DEVICE_TOKEN = "persist.oyster.device_token";
    // Optional. Used for SKU/QA forcing. Recommended to leave unset in production and let
    // the proxy route by token tier.
    // Supported: auto|deepseek|kimi|claude (aliases: free->deepseek, pro->kimi, max->claude)
    private static final String PROP_MODE = "persist.oyster.mode";
    private static final String PROP_OPENAI_BASE_URL = "persist.oyster.openai_base_url";
    private static final String PROP_MODEL = "persist.oyster.model";

    // Debug fallback (app private storage).
    private static final String DEVICE_TOKEN_FILE = "device_token.txt";

    private PreinstallProvisioner() {}

    public enum Mode {
        AUTO,
        DEEPSEEK,
        KIMI,
        CLAUDE
    }

    /**
     * Ensure OpenClaw is configured for a factory-provisioned device.
     *
     * Returns true if config is present or successfully provisioned.
     */
    public static boolean ensureProvisioned(Context context) {
        // If already configured, still ensure env is present (idempotent).
        String token = getDeviceToken(context);
        if (token == null) {
            Logger.logWarn(LOG_TAG, "No device token available (factory provisioning missing)");
            return ClawPhonesService.isOpenclawConfigured();
        }

        // Create base config if missing (model + gateway auth token + workspace).
        if (!ClawPhonesConfig.isConfigured()) {
            ModeConfig cfg = resolveModeConfig(context);
            boolean ok = ClawPhonesConfig.setProvider("openai", cfg.model);
            if (!ok) {
                Logger.logError(LOG_TAG, "Failed to write base OpenClaw config");
                return false;
            }
        }

        ModeConfig cfg = resolveModeConfig(context);
        boolean env1 = ClawPhonesConfig.setEnvVar("OPENAI_BASE_URL", cfg.baseUrl);
        boolean env2 = ClawPhonesConfig.setEnvVar("OPENAI_API_KEY", token);

        // Optional: hints for server-side subscription/routing.
        ClawPhonesConfig.setEnvVar("OYSTER_MODE", cfg.modeName);

        return env1 && env2;
    }

    private static final class ModeConfig {
        final String modeName;
        final String baseUrl;
        final String model;

        ModeConfig(String modeName, String baseUrl, String model) {
            this.modeName = modeName;
            this.baseUrl = baseUrl;
            this.model = model;
        }
    }

    private static ModeConfig resolveModeConfig(Context context) {
        // Allow factory overrides per SKU.
        String baseUrlOverride = getSystemProperty(PROP_OPENAI_BASE_URL);
        String modelOverride = getSystemProperty(PROP_MODEL);

        Mode mode = resolveMode(getSystemProperty(PROP_MODE));

        // Defaults: all requests go through Oyster's OpenAI-compatible proxy.
        // The proxy maps device_token -> subscription -> upstream provider (DeepSeek/Kimi/Claude).
        //
        // IMPORTANT: Keep OpenClaw's configured model conservative and stable (e.g. gpt-4o-mini),
        // since some OpenClaw builds validate model IDs against a registry. The proxy decides the
        // true upstream model.
        String baseUrl;
        String model;
        String modeName;

        switch (mode) {
            case CLAUDE:
                modeName = "claude";
                baseUrl = "https://api.openclaw.ai/claude/v1";
                model = "gpt-4o";
                break;
            case KIMI:
                modeName = "kimi";
                baseUrl = "https://api.openclaw.ai/kimi/v1";
                model = "gpt-4o";
                break;
            case DEEPSEEK:
                modeName = "deepseek";
                baseUrl = "https://api.openclaw.ai/deepseek/v1";
                model = "gpt-4o-mini";
                break;
            case AUTO:
            default:
                modeName = "auto";
                baseUrl = "https://api.openclaw.ai/v1";
                model = "gpt-4o-mini";
                break;
        }

        if (baseUrlOverride != null && !baseUrlOverride.trim().isEmpty()) {
            baseUrl = baseUrlOverride.trim();
        }
        if (modelOverride != null && !modelOverride.trim().isEmpty()) {
            model = modelOverride.trim();
        }

        return new ModeConfig(modeName, baseUrl, model);
    }

    private static Mode resolveMode(@Nullable String raw) {
        if (raw == null) return Mode.AUTO;
        String v = raw.trim().toLowerCase();
        if ("auto".equals(v)) return Mode.AUTO;
        if ("deepseek".equals(v) || "free".equals(v)) return Mode.DEEPSEEK;
        if ("kimi".equals(v) || "pro".equals(v)) return Mode.KIMI;
        if ("claude".equals(v) || "max".equals(v)) return Mode.CLAUDE;
        return Mode.AUTO;
    }

    @Nullable
    public static String getDeviceToken(Context context) {
        // 1) System property (factory/ROM).
        String fromProp = getSystemProperty(PROP_DEVICE_TOKEN);
        if (isUsableToken(fromProp)) return fromProp.trim();

        // 2) App-private file (debug).
        try {
            File f = new File(context.getFilesDir(), DEVICE_TOKEN_FILE);
            if (f.exists()) {
                try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                    String line = br.readLine();
                    if (isUsableToken(line)) return line.trim();
                }
            }
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed reading device token file: " + e.getMessage());
        }

        return null;
    }

    private static boolean isUsableToken(@Nullable String token) {
        if (token == null) return false;
        String t = token.trim();
        return t.length() >= 8;
    }

    @Nullable
    private static String getSystemProperty(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class);
            Object v = get.invoke(null, key);
            if (v instanceof String) {
                String s = ((String) v).trim();
                return s.isEmpty() ? null : s;
            }
        } catch (Exception ignored) {
            // Hidden API; reflection may fail on some builds.
        }
        return null;
    }
}
