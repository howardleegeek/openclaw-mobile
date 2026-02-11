package ai.clawphones.agent.chat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Represents a compute job for edge AI processing.
 * Jobs can be claimed, executed, and submitted by EdgeComputeService nodes.
 */
public class ComputeJob {

    private static final Gson GSON = new GsonBuilder()
        .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .create();

    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();
    private static final Type LIST_TYPE = new TypeToken<List<Object>>() {}.getType();

    // Enum: JobType - the type of ML task
    public enum JobType {
        IMAGE_LABELING("image_labeling"),
        TEXT_RECOGNITION("text_recognition"),
        OBJECT_DETECTION("object_detection"),
        SPEECH_RECOGNITION("speech_recognition");

        private final String apiValue;

        JobType(String apiValue) {
            this.apiValue = apiValue;
        }

        public String getApiValue() {
            return apiValue;
        }

        @Nullable
        public static JobType fromApiValue(@Nullable String value) {
            if (value == null) return null;
            String lower = value.toLowerCase(Locale.US);
            for (JobType type : values()) {
                if (type.apiValue.equals(lower)) {
                    return type;
                }
            }
            return null;
        }
    }

    // Enum: JobStatus - the current state of the job
    public enum JobStatus {
        PENDING("pending"),
        CLAIMED("claimed"),
        PROCESSING("processing"),
        COMPLETED("completed"),
        FAILED("failed"),
        EXPIRED("expired");

        private final String apiValue;

        JobStatus(String apiValue) {
            this.apiValue = apiValue;
        }

        public String getApiValue() {
            return apiValue;
        }

        @Nullable
        public static JobStatus fromApiValue(@Nullable String value) {
            if (value == null) return null;
            String lower = value.toLowerCase(Locale.US);
            for (JobStatus status : values()) {
                if (status.apiValue.equals(lower)) {
                    return status;
                }
            }
            return null;
        }
    }

    // Core job fields
    private String jobId;
    private JobType type;
    private JobStatus status;
    private long createdAt;
    private long expiresAt;
    private long claimedAt;
    private String claimedBy;
    private long completedAt;

    // Input data (base64 encoded strings or URLs)
    private String inputData;
    private Map<String, Object> inputMetadata;

    // Output results
    private String outputData;
    private Map<String, Object> outputMetadata;
    private String errorMessage;

    // Priority and retry
    private int priority;
    private int maxRetries;
    private int retryCount;

    // Device constraints for claiming
    private int minBatteryLevel;
    private float minCpuPerformance;
    private boolean requiresCamera;

    // Empty constructor for deserialization
    public ComputeJob() {
    }

    // Constructor with minimal fields
    public ComputeJob(@NonNull JobType type, @NonNull String inputData) {
        this.jobId = UUID.randomUUID().toString();
        this.type = type;
        this.status = JobStatus.PENDING;
        this.inputData = inputData;
        this.createdAt = System.currentTimeMillis();
        this.expiresAt = createdAt + (15 * 60 * 1000); // 15 minutes
        this.priority = 0;
        this.maxRetries = 3;
        this.retryCount = 0;
        this.minBatteryLevel = 30;
        this.minCpuPerformance = 0.5f;
        this.requiresCamera = false;
    }

    @Nullable
    public static ComputeJob fromJson(@Nullable String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            JsonObject obj = GSON.fromJson(json, JsonObject.class);
            if (obj == null || obj.isJsonNull()) {
                return null;
            }

            ComputeJob job = new ComputeJob();
            job.jobId = getString(obj, "jobId");
            job.type = JobType.fromApiValue(getString(obj, "type"));
            job.status = JobStatus.fromApiValue(getString(obj, "status"));
            job.createdAt = getLong(obj, "createdAt");
            job.expiresAt = getLong(obj, "expiresAt");
            job.claimedAt = getLong(obj, "claimedAt");
            job.claimedBy = getString(obj, "claimedBy");
            job.completedAt = getLong(obj, "completedAt");
            job.inputData = getString(obj, "inputData");
            job.inputMetadata = getObject(obj, "inputMetadata");
            job.outputData = getString(obj, "outputData");
            job.outputMetadata = getObject(obj, "outputMetadata");
            job.errorMessage = getString(obj, "errorMessage");
            job.priority = getInt(obj, "priority");
            job.maxRetries = getInt(obj, "maxRetries");
            job.retryCount = getInt(obj, "retryCount");
            job.minBatteryLevel = getInt(obj, "minBatteryLevel");
            job.minCpuPerformance = getFloat(obj, "minCpuPerformance");
            job.requiresCamera = getBoolean(obj, "requiresCamera");

            return job;
        } catch (JsonSyntaxException e) {
            return null;
        }
    }

    @NonNull
    public String toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("jobId", jobId);
        obj.addProperty("type", type != null ? type.getApiValue() : null);
        obj.addProperty("status", status != null ? status.getApiValue() : null);
        obj.addProperty("createdAt", createdAt);
        obj.addProperty("expiresAt", expiresAt);
        obj.addProperty("claimedAt", claimedAt > 0 ? claimedAt : null);
        obj.addProperty("claimedBy", claimedBy);
        obj.addProperty("completedAt", completedAt > 0 ? completedAt : null);
        obj.addProperty("inputData", inputData);
        addElement(obj, "inputMetadata", inputMetadata);
        obj.addProperty("outputData", outputData);
        addElement(obj, "outputMetadata", outputMetadata);
        obj.addProperty("errorMessage", errorMessage);
        obj.addProperty("priority", priority);
        obj.addProperty("maxRetries", maxRetries);
        obj.addProperty("retryCount", retryCount);
        obj.addProperty("minBatteryLevel", minBatteryLevel);
        obj.addProperty("minCpuPerformance", minCpuPerformance);
        obj.addProperty("requiresCamera", requiresCamera);
        return GSON.toJson(obj);
    }

    @NonNull
    public static List<ComputeJob> fromJsonArray(@Nullable String jsonArray) {
        if (jsonArray == null || jsonArray.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            JsonArray array = GSON.fromJson(jsonArray, JsonArray.class);
            if (array == null || array.isJsonNull()) {
                return new ArrayList<>();
            }

            List<ComputeJob> jobs = new ArrayList<>();
            for (JsonElement element : array) {
                if (element.isJsonObject()) {
                    ComputeJob job = fromJson(element.toString());
                    if (job != null) {
                        jobs.add(job);
                    }
                }
            }
            return jobs;
        } catch (JsonSyntaxException e) {
            return new ArrayList<>();
        }
    }

    // Getters and Setters
    @Nullable
    public String getJobId() {
        return jobId;
    }

    public void setJobId(@Nullable String jobId) {
        this.jobId = jobId;
    }

    @Nullable
    public JobType getType() {
        return type;
    }

    public void setType(@Nullable JobType type) {
        this.type = type;
    }

    @Nullable
    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(@Nullable JobStatus status) {
        this.status = status;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(long expiresAt) {
        this.expiresAt = expiresAt;
    }

    public long getClaimedAt() {
        return claimedAt;
    }

    public void setClaimedAt(long claimedAt) {
        this.claimedAt = claimedAt;
    }

    @Nullable
    public String getClaimedBy() {
        return claimedBy;
    }

    public void setClaimedBy(@Nullable String claimedBy) {
        this.claimedBy = claimedBy;
    }

    public long getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(long completedAt) {
        this.completedAt = completedAt;
    }

    @Nullable
    public String getInputData() {
        return inputData;
    }

    public void setInputData(@Nullable String inputData) {
        this.inputData = inputData;
    }

    @Nullable
    public Map<String, Object> getInputMetadata() {
        return inputMetadata;
    }

    public void setInputMetadata(@Nullable Map<String, Object> inputMetadata) {
        this.inputMetadata = inputMetadata;
    }

    @Nullable
    public String getOutputData() {
        return outputData;
    }

    public void setOutputData(@Nullable String outputData) {
        this.outputData = outputData;
    }

    @Nullable
    public Map<String, Object> getOutputMetadata() {
        return outputMetadata;
    }

    public void setOutputMetadata(@Nullable Map<String, Object> outputMetadata) {
        this.outputMetadata = outputMetadata;
    }

    @Nullable
    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(@Nullable String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public int getMinBatteryLevel() {
        return minBatteryLevel;
    }

    public void setMinBatteryLevel(int minBatteryLevel) {
        this.minBatteryLevel = minBatteryLevel;
    }

    public float getMinCpuPerformance() {
        return minCpuPerformance;
    }

    public void setMinCpuPerformance(float minCpuPerformance) {
        this.minCpuPerformance = minCpuPerformance;
    }

    public boolean isRequiresCamera() {
        return requiresCamera;
    }

    public void setRequiresCamera(boolean requiresCamera) {
        this.requiresCamera = requiresCamera;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > expiresAt;
    }

    public boolean canRetry() {
        return retryCount < maxRetries;
    }

    // Helper methods for JSON parsing
    private static String getString(JsonObject obj, String name) {
        JsonElement element = obj.get(name);
        if (element == null || element.isJsonNull()) return null;
        return element.getAsString();
    }

    private static long getLong(JsonObject obj, String name) {
        JsonElement element = obj.get(name);
        if (element == null || element.isJsonNull()) return 0;
        return element.getAsLong();
    }

    private static int getInt(JsonObject obj, String name) {
        JsonElement element = obj.get(name);
        if (element == null || element.isJsonNull()) return 0;
        return element.getAsInt();
    }

    private static float getFloat(JsonObject obj, String name) {
        JsonElement element = obj.get(name);
        if (element == null || element.isJsonNull()) return 0f;
        return element.getAsFloat();
    }

    private static boolean getBoolean(JsonObject obj, String name) {
        JsonElement element = obj.get(name);
        if (element == null || element.isJsonNull()) return false;
        return element.getAsBoolean();
    }

    private static Map<String, Object> getObject(JsonObject obj, String name) {
        JsonElement element = obj.get(name);
        if (element == null || element.isJsonNull()) return null;
        return GSON.fromJson(element, MAP_TYPE);
    }

    private static void addElement(JsonObject obj, String name, Object value) {
        if (value == null) {
            obj.add(name, null);
        } else {
            JsonElement element = GSON.toJsonTree(value);
            obj.add(name, element);
        }
    }
}
