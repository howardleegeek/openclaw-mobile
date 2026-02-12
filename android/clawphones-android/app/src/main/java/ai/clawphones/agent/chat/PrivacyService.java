package ai.clawphones.agent.chat;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Service for managing privacy settings and operations.
 * Uses background thread execution for all network operations.
 */
public class PrivacyService {
    private static final String PREFS_NAME = "PrivacyPrefs";
    private static final String KEY_SETTINGS = "privacy_settings";
    private static final String BASE_URL = "https://api.openclaw.com/privacy";
    private static final String API_KEY = "your_api_key_here";

    private final Context context;
    private final SharedPreferences prefs;
    private final ExecutorService executorService;
    private final Handler mainHandler;

    public PrivacyService(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.executorService = Executors.newFixedThreadPool(4);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Fetch current privacy settings
     */
    public void fetchSettings(PrivacyCallback<PrivacyConfig.PrivacySettings> callback) {
        executorService.execute(() -> {
            try {
                PrivacyConfig.PrivacySettings settings = loadSettingsFromPrefs();
                notifySuccess(callback, settings);
            } catch (Exception e) {
                notifyError(callback, e);
            }
        });
    }

    /**
     * Update privacy settings
     */
    public void updateSettings(PrivacyConfig.PrivacySettings settings, PrivacyCallback<Boolean> callback) {
        executorService.execute(() -> {
            try {
                saveSettingsToPrefs(settings);

                // Sync with server
                JSONObject json = settingsToJson(settings);
                String response = makePostRequest(BASE_URL + "/settings", json.toString());

                JSONObject responseJson = new JSONObject(response);
                boolean success = responseJson.optBoolean("success", false);

                if (success) {
                    notifySuccess(callback, true);
                } else {
                    notifyError(callback, new Exception("Failed to update settings on server"));
                }
            } catch (Exception e) {
                notifyError(callback, e);
            }
        });
    }

    /**
     * Request data export
     */
    public void requestDataExport(PrivacyCallback<PrivacyConfig.DataExportRequest> callback) {
        executorService.execute(() -> {
            try {
                String requestId = UUID.randomUUID().toString();
                PrivacyConfig.DataExportRequest request = new PrivacyConfig.DataExportRequest(requestId, new Date());

                JSONObject json = new JSONObject();
                json.put("requestId", requestId);

                String response = makePostRequest(BASE_URL + "/export/request", json.toString());
                JSONObject responseJson = new JSONObject(response);

                if (responseJson.optBoolean("success", false)) {
                    String downloadUrl = responseJson.optString("downloadUrl");
                    request.setDownloadUrl(downloadUrl);
                    request.setStatus("processing");
                    notifySuccess(callback, request);
                } else {
                    notifyError(callback, new Exception("Export request failed"));
                }
            } catch (Exception e) {
                notifyError(callback, e);
            }
        });
    }

    /**
     * Check export status
     */
    public void checkExportStatus(String requestId, PrivacyCallback<PrivacyConfig.DataExportRequest> callback) {
        executorService.execute(() -> {
            try {
                String response = makeGetRequest(BASE_URL + "/export/status?requestId=" + requestId);
                JSONObject responseJson = new JSONObject(response);

                PrivacyConfig.DataExportRequest request = new PrivacyConfig.DataExportRequest();
                request.setRequestId(requestId);
                request.setStatus(responseJson.optString("status", "pending"));
                request.setDownloadUrl(responseJson.optString("downloadUrl"));

                if ("completed".equals(request.getStatus())) {
                    long expiryTime = responseJson.optLong("expiryTime");
                    request.setExpiryTime(new Date(expiryTime));
                }

                notifySuccess(callback, request);
            } catch (Exception e) {
                notifyError(callback, e);
            }
        });
    }

    /**
     * Delete all user data
     */
    public void deleteAllData(String confirmation, PrivacyCallback<Boolean> callback) {
        executorService.execute(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("confirmation", confirmation);

                String response = makePostRequest(BASE_URL + "/delete", json.toString());
                JSONObject responseJson = new JSONObject(response);

                boolean success = responseJson.optBoolean("success", false);

                if (success) {
                    // Clear local data
                    prefs.edit().clear().apply();
                    logAuditAction("account_deleted", "All data deleted by user");
                    notifySuccess(callback, true);
                } else {
                    notifyError(callback, new Exception("Deletion failed on server"));
                }
            } catch (Exception e) {
                notifyError(callback, e);
            }
        });
    }

    /**
     * Fetch privacy audit log
     */
    public void fetchAuditLog(Date startDate, Date endDate, PrivacyCallback<List<PrivacyConfig.PrivacyAuditLog>> callback) {
        executorService.execute(() -> {
            try {
                String url = BASE_URL + "/audit?start=" + startDate.getTime() + "&end=" + endDate.getTime();
                String response = makeGetRequest(url);
                JSONObject responseJson = new JSONObject(response);

                JSONArray logsArray = responseJson.optJSONArray("logs");
                List<PrivacyConfig.PrivacyAuditLog> logs = new ArrayList<>();

                if (logsArray != null) {
                    for (int i = 0; i < logsArray.length(); i++) {
                        JSONObject logObj = logsArray.getJSONObject(i);
                        PrivacyConfig.PrivacyAuditLog log = new PrivacyConfig.PrivacyAuditLog();
                        log.setLogId(logObj.optString("logId"));
                        log.setAction(logObj.optString("action"));
                        log.setDetails(logObj.optString("details"));
                        log.setIpAddress(logObj.optString("ipAddress"));
                        log.setUserAgent(logObj.optString("userAgent"));

                        long timestamp = logObj.optLong("timestamp");
                        log.setTimestamp(new Date(timestamp));

                        logs.add(log);
                    }
                }

                notifySuccess(callback, logs);
            } catch (Exception e) {
                notifyError(callback, e);
            }
        });
    }

    /**
     * Update user consent
     */
    public void updateConsent(PrivacyConfig.ConsentType consentType, boolean granted, String version, PrivacyCallback<Boolean> callback) {
        executorService.execute(() -> {
            try {
                PrivacyConfig.ConsentRecord record = new PrivacyConfig.ConsentRecord(consentType, granted, version);

                JSONObject json = new JSONObject();
                json.put("consentType", consentType.getKey());
                json.put("granted", granted);
                json.put("version", version);

                String response = makePostRequest(BASE_URL + "/consent", json.toString());
                JSONObject responseJson = new JSONObject(response);

                boolean success = responseJson.optBoolean("success", false);

                if (success) {
                    saveConsentLocally(record);
                    logAuditAction("consent_updated", consentType.getKey() + ": " + granted);
                    notifySuccess(callback, true);
                } else {
                    notifyError(callback, new Exception("Failed to update consent"));
                }
            } catch (Exception e) {
                notifyError(callback, e);
            }
        });
    }

    /**
     * Shutdown executor service
     */
    public void shutdown() {
        executorService.shutdown();
    }

    // Private helper methods

    private PrivacyConfig.PrivacySettings loadSettingsFromPrefs() {
        PrivacyConfig.PrivacySettings settings = new PrivacyConfig.PrivacySettings();
        String jsonStr = prefs.getString(KEY_SETTINGS, null);

        if (jsonStr != null) {
            try {
                JSONObject json = new JSONObject(jsonStr);
                settings.setFaceBlurEnabled(json.optBoolean("faceBlurEnabled", true));
                settings.setPlateBlurEnabled(json.optBoolean("plateBlurEnabled", true));
                settings.setLocationPrecision(PrivacyConfig.LocationPrecision.fromKey(
                    json.optString("locationPrecision", PrivacyConfig.LocationPrecision.APPROXIMATE.getKey())
                ));
                settings.setDataRetentionDays(json.optInt("dataRetentionDays", 30));
                settings.setShareAnalytics(json.optBoolean("shareAnalytics", false));
            } catch (Exception e) {
                // Use defaults on error
            }
        }

        return settings;
    }

    private void saveSettingsToPrefs(PrivacyConfig.PrivacySettings settings) {
        try {
            JSONObject json = settingsToJson(settings);
            prefs.edit().putString(KEY_SETTINGS, json.toString()).apply();
        } catch (Exception e) {
            // Ignore save errors
        }
    }

    private JSONObject settingsToJson(PrivacyConfig.PrivacySettings settings) throws Exception {
        JSONObject json = new JSONObject();
        json.put("faceBlurEnabled", settings.isFaceBlurEnabled());
        json.put("plateBlurEnabled", settings.isPlateBlurEnabled());
        json.put("locationPrecision", settings.getLocationPrecision().getKey());
        json.put("dataRetentionDays", settings.getDataRetentionDays());
        json.put("shareAnalytics", settings.isShareAnalytics());
        return json;
    }

    private void saveConsentLocally(PrivacyConfig.ConsentRecord record) {
        String key = "consent_" + record.getConsentType().getKey();
        prefs.edit().putBoolean(key, record.isGranted()).apply();
    }

    private void logAuditAction(String action, String details) {
        // In a real implementation, this would send to server
        executorService.execute(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("action", action);
                json.put("details", details);
                json.put("timestamp", System.currentTimeMillis());
                makePostRequest(BASE_URL + "/audit/log", json.toString());
            } catch (Exception e) {
                // Silently fail
            }
        });
    }

    private String makeGetRequest(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + API_KEY);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(30000);

        return readResponse(conn);
    }

    private String makePostRequest(String urlString, String body) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + API_KEY);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(30000);
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = body.getBytes("utf-8");
            os.write(input, 0, input.length);
        }

        return readResponse(conn);
    }

    private String readResponse(HttpURLConnection conn) throws Exception {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "utf-8"))) {
            StringBuilder response = new StringBuilder();
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine.trim());
            }
            return response.toString();
        } finally {
            conn.disconnect();
        }
    }

    private <T> void notifySuccess(PrivacyCallback<T> callback, T result) {
        mainHandler.post(() -> callback.onSuccess(result));
    }

    private <T> void notifyError(PrivacyCallback<T> callback, Exception e) {
        mainHandler.post(() -> callback.onError(e));
    }

    /**
     * Callback interface for async privacy operations
     */
    public interface PrivacyCallback<T> {
        void onSuccess(T result);
        void onError(Exception e);
    }
}
