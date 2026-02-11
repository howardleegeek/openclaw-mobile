package ai.clawphones.agent.chat;

import org.json.JSONException;
import org.json.JSONObject;

public class ClawTask {

    public enum TaskType {
        PHOTO_SURVEY,
        MONITORING,
        ENVIRONMENTAL,
        TRAFFIC,
        RETAIL
    }

    public enum TaskStatus {
        AVAILABLE,
        ASSIGNED,
        IN_PROGRESS,
        COMPLETED,
        EXPIRED
    }

    private String taskId;
    private TaskType type;
    private String title;
    private String description;
    private double reward;
    private double latitude;
    private double longitude;
    private TaskStatus status;
    private long createdAt;
    private long expiresAt;
    private long assignedAt;
    private long completedAt;
    private String assignedTo;
    private String requirements;

    public ClawTask() {}

    public ClawTask(String taskId, TaskType type, String title, String description, double reward,
                   double latitude, double longitude, TaskStatus status, long createdAt, long expiresAt) {
        this.taskId = taskId;
        this.type = type;
        this.title = title;
        this.description = description;
        this.reward = reward;
        this.latitude = latitude;
        this.longitude = longitude;
        this.status = status;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public static ClawTask fromJson(JSONObject json) throws JSONException {
        ClawTask task = new ClawTask();
        task.taskId = json.getString("task_id");
        task.type = TaskType.valueOf(json.getString("type"));
        task.title = json.optString("title", "");
        task.description = json.optString("description", "");
        task.reward = json.getDouble("reward");
        task.latitude = json.getDouble("latitude");
        task.longitude = json.getDouble("longitude");
        task.status = TaskStatus.valueOf(json.getString("status"));
        task.createdAt = json.getLong("created_at");
        task.expiresAt = json.getLong("expires_at");
        task.assignedAt = json.optLong("assigned_at", 0);
        task.completedAt = json.optLong("completed_at", 0);
        task.assignedTo = json.optString("assigned_to", null);
        task.requirements = json.optString("requirements", "");
        return task;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("task_id", taskId);
        json.put("type", type.name());
        json.put("title", title);
        json.put("description", description);
        json.put("reward", reward);
        json.put("latitude", latitude);
        json.put("longitude", longitude);
        json.put("status", status.name());
        json.put("created_at", createdAt);
        json.put("expires_at", expiresAt);
        json.put("assigned_at", assignedAt);
        json.put("completed_at", completedAt);
        if (assignedTo != null) {
            json.put("assigned_to", assignedTo);
        }
        json.put("requirements", requirements);
        return json;
    }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public TaskType getType() { return type; }
    public void setType(TaskType type) { this.type = type; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public double getReward() { return reward; }
    public void setReward(double reward) { this.reward = reward; }

    public double getLatitude() { return latitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }

    public double getLongitude() { return longitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }

    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(long expiresAt) { this.expiresAt = expiresAt; }

    public long getAssignedAt() { return assignedAt; }
    public void setAssignedAt(long assignedAt) { this.assignedAt = assignedAt; }

    public long getCompletedAt() { return completedAt; }
    public void setCompletedAt(long completedAt) { this.completedAt = completedAt; }

    public String getAssignedTo() { return assignedTo; }
    public void setAssignedTo(String assignedTo) { this.assignedTo = assignedTo; }

    public String getRequirements() { return requirements; }
    public void setRequirements(String requirements) { this.requirements = requirements; }
}
