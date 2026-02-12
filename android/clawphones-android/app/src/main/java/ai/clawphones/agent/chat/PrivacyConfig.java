package ai.clawphones.agent.chat;

import java.util.Date;

/**
 * Configuration model for privacy settings and related data structures.
 */
public class PrivacyConfig {

    /**
     * Privacy settings configuration
     */
    public static class PrivacySettings {
        private boolean faceBlurEnabled;
        private boolean plateBlurEnabled;
        private LocationPrecision locationPrecision;
        private int dataRetentionDays;
        private boolean shareAnalytics;

        public PrivacySettings() {
            this.faceBlurEnabled = true;
            this.plateBlurEnabled = true;
            this.locationPrecision = LocationPrecision.APPROXIMATE;
            this.dataRetentionDays = 30;
            this.shareAnalytics = false;
        }

        public boolean isFaceBlurEnabled() {
            return faceBlurEnabled;
        }

        public void setFaceBlurEnabled(boolean faceBlurEnabled) {
            this.faceBlurEnabled = faceBlurEnabled;
        }

        public boolean isPlateBlurEnabled() {
            return plateBlurEnabled;
        }

        public void setPlateBlurEnabled(boolean plateBlurEnabled) {
            this.plateBlurEnabled = plateBlurEnabled;
        }

        public LocationPrecision getLocationPrecision() {
            return locationPrecision;
        }

        public void setLocationPrecision(LocationPrecision locationPrecision) {
            this.locationPrecision = locationPrecision;
        }

        public int getDataRetentionDays() {
            return dataRetentionDays;
        }

        public void setDataRetentionDays(int dataRetentionDays) {
            this.dataRetentionDays = dataRetentionDays;
        }

        public boolean isShareAnalytics() {
            return shareAnalytics;
        }

        public void setShareAnalytics(boolean shareAnalytics) {
            this.shareAnalytics = shareAnalytics;
        }
    }

    /**
     * Location precision levels
     */
    public enum LocationPrecision {
        EXACT("location_precision_exact"),
        APPROXIMATE("location_precision_approximate"),
        CITY_LEVEL("location_precision_city_level"),
        DISABLED("location_precision_disabled");

        private final String key;

        LocationPrecision(String key) {
            this.key = key;
        }

        public String getKey() {
            return key;
        }

        public static LocationPrecision fromKey(String key) {
            for (LocationPrecision precision : values()) {
                if (precision.key.equals(key)) {
                    return precision;
                }
            }
            return APPROXIMATE;
        }
    }

    /**
     * Request for data export
     */
    public static class DataExportRequest {
        private String requestId;
        private Date requestTime;
        private String status;
        private Date expiryTime;
        private String downloadUrl;

        public DataExportRequest() {
        }

        public DataExportRequest(String requestId, Date requestTime) {
            this.requestId = requestId;
            this.requestTime = requestTime;
            this.status = "pending";
        }

        public String getRequestId() {
            return requestId;
        }

        public void setRequestId(String requestId) {
            this.requestId = requestId;
        }

        public Date getRequestTime() {
            return requestTime;
        }

        public void setRequestTime(Date requestTime) {
            this.requestTime = requestTime;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Date getExpiryTime() {
            return expiryTime;
        }

        public void setExpiryTime(Date expiryTime) {
            this.expiryTime = expiryTime;
        }

        public String getDownloadUrl() {
            return downloadUrl;
        }

        public void setDownloadUrl(String downloadUrl) {
            this.downloadUrl = downloadUrl;
        }
    }

    /**
     * Privacy audit log entry
     */
    public static class PrivacyAuditLog {
        private String logId;
        private Date timestamp;
        private String action;
        private String details;
        private String ipAddress;
        private String userAgent;

        public PrivacyAuditLog() {
        }

        public PrivacyAuditLog(String action, String details) {
            this.timestamp = new Date();
            this.action = action;
            this.details = details;
        }

        public String getLogId() {
            return logId;
        }

        public void setLogId(String logId) {
            this.logId = logId;
        }

        public Date getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(Date timestamp) {
            this.timestamp = timestamp;
        }

        public String getAction() {
            return action;
        }

        public void setAction(String action) {
            this.action = action;
        }

        public String getDetails() {
            return details;
        }

        public void setDetails(String details) {
            this.details = details;
        }

        public String getIpAddress() {
            return ipAddress;
        }

        public void setIpAddress(String ipAddress) {
            this.ipAddress = ipAddress;
        }

        public String getUserAgent() {
            return userAgent;
        }

        public void setUserAgent(String userAgent) {
            this.userAgent = userAgent;
        }
    }

    /**
     * User consent record
     */
    public static class ConsentRecord {
        private String consentId;
        private Date consentTime;
        private ConsentType consentType;
        private boolean granted;
        private String version;

        public ConsentRecord() {
        }

        public ConsentRecord(ConsentType consentType, boolean granted, String version) {
            this.consentTime = new Date();
            this.consentType = consentType;
            this.granted = granted;
            this.version = version;
        }

        public String getConsentId() {
            return consentId;
        }

        public void setConsentId(String consentId) {
            this.consentId = consentId;
        }

        public Date getConsentTime() {
            return consentTime;
        }

        public void setConsentTime(Date consentTime) {
            this.consentTime = consentTime;
        }

        public ConsentType getConsentType() {
            return consentType;
        }

        public void setConsentType(ConsentType consentType) {
            this.consentType = consentType;
        }

        public boolean isGranted() {
            return granted;
        }

        public void setGranted(boolean granted) {
            this.granted = granted;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }
    }

    /**
     * Types of consent
     */
    public enum ConsentType {
        DATA_COLLECTION("consent_data_collection"),
        ANALYTICS("consent_analytics"),
        MARKETING("consent_marketing"),
        LOCATION_SHARING("consent_location_sharing"),
        PERSONALIZED_ADS("consent_personalized_ads");

        private final String key;

        ConsentType(String key) {
            this.key = key;
        }

        public String getKey() {
            return key;
        }

        public static ConsentType fromKey(String key) {
            for (ConsentType type : values()) {
                if (type.key.equals(key)) {
                    return type;
                }
            }
            return DATA_COLLECTION;
        }
    }
}
