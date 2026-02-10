package ai.clawphones.agent;

import android.util.Base64;

import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Helper for channel setup:
 * - Decode setup codes from @ClawPhonesSetupBot
 * - Write channel configuration to openclaw.json
 */
public class ChannelSetupHelper {

    private static final String LOG_TAG = "ChannelSetupHelper";

    /**
     * Data extracted from setup code
     */
    public static class SetupCodeData {
        public final String platform;
        public final String botToken;
        public final String ownerId;

        public SetupCodeData(String platform, String botToken, String ownerId) {
            this.platform = platform;
            this.botToken = botToken;
            this.ownerId = ownerId;
        }
    }

    /**
     * Decode setup code from @ClawPhonesSetupBot
     * Format: BOTDROP-{platform}-{base64_json}
     * 
     * Platform codes:
     * - tg = Telegram
     * - dc = Discord
     * 
     * Base64 JSON structure:
     * {
     *   "v": 1,
     *   "platform": "telegram" | "discord",
     *   "bot_token": "...",
     *   "owner_id": "...",
     *   "created_at": 1234567890
     * }
     * 
     * @param setupCode The setup code from @ClawPhonesSetupBot
     * @return SetupCodeData or null if invalid
     */
    public static SetupCodeData decodeSetupCode(String setupCode) {
        try {
            // Split: BOTDROP-tg-xxxxx or BOTDROP-dc-xxxxx
            String[] parts = setupCode.split("-", 3);
            if (parts.length != 3 || !parts[0].equals("BOTDROP")) {
                Logger.logError(LOG_TAG, "Invalid setup code format");
                return null;
            }

            String platformCode = parts[1];
            String base64Payload = parts[2];

            // Decode base64
            byte[] decodedBytes = Base64.decode(base64Payload, Base64.DEFAULT);
            String jsonString = new String(decodedBytes);

            // NOTE: Do not log jsonString - it contains sensitive bot_token
            Logger.logDebug(LOG_TAG, "Setup code decoded successfully");

            // Parse JSON
            JSONObject json = new JSONObject(jsonString);

            String platform = json.optString("platform", null);
            String botToken = json.optString("bot_token", null);
            String ownerId = json.optString("owner_id", null);

            // Fallback: infer platform from code if not in JSON
            if (platform == null) {
                platform = platformCode.equals("tg") ? "telegram" :
                          platformCode.equals("dc") ? "discord" : null;
            }

            if (platform == null || botToken == null || ownerId == null) {
                Logger.logError(LOG_TAG, "Missing required fields in setup code");
                return null;
            }

            Logger.logInfo(LOG_TAG, "Setup code decoded: platform=" + platform + ", ownerId=" + ownerId);
            return new SetupCodeData(platform, botToken, ownerId);

        } catch (IllegalArgumentException | JSONException e) {
            Logger.logError(LOG_TAG, "Failed to decode setup code: " + e.getMessage());
            return null;
        }
    }

    /**
     * Write channel configuration to openclaw.json
     *
     * For Telegram: { channels: { telegram: { enabled: true, botToken: "...", dmPolicy: "pairing" } } }
     * For Discord:  { channels: { discord: { enabled: true, token: "..." } } }
     *
     * @param platform "telegram" or "discord"
     * @param botToken Bot token
     * @param ownerId User ID who owns/controls the bot (used for allowFrom)
     * @return true if successful
     */
    public static boolean writeChannelConfig(String platform, String botToken, String ownerId) {
        try {
            JSONObject config = ClawPhonesConfig.readConfig();

            if (!config.has("channels")) {
                config.put("channels", new JSONObject());
            }

            JSONObject channels = config.getJSONObject("channels");

            if (platform.equals("telegram")) {
                JSONObject telegram = new JSONObject();
                telegram.put("enabled", true);
                telegram.put("botToken", botToken);
                telegram.put("dmPolicy", "allowlist");
                telegram.put("groupPolicy", "allowlist");
                telegram.put("streamMode", "partial");
                JSONArray allowFrom = new JSONArray();
                allowFrom.put(ownerId);
                telegram.put("allowFrom", allowFrom);
                channels.put("telegram", telegram);

            } else if (platform.equals("discord")) {
                JSONObject discord = new JSONObject();
                discord.put("enabled", true);
                discord.put("token", botToken);
                channels.put("discord", discord);

            } else {
                Logger.logError(LOG_TAG, "Unsupported platform: " + platform);
                return false;
            }

            // Enable channel plugin
            if (!config.has("plugins")) {
                config.put("plugins", new JSONObject());
            }
            JSONObject plugins = config.getJSONObject("plugins");
            if (!plugins.has("entries")) {
                plugins.put("entries", new JSONObject());
            }
            JSONObject entries = plugins.getJSONObject("entries");
            JSONObject pluginEntry = new JSONObject();
            pluginEntry.put("enabled", true);
            entries.put(platform, pluginEntry);

            Logger.logInfo(LOG_TAG, "Writing channel config for platform: " + platform);
            return ClawPhonesConfig.writeConfig(config);

        } catch (JSONException e) {
            Logger.logError(LOG_TAG, "Failed to write channel config: " + e.getMessage());
            return false;
        }
    }
}
