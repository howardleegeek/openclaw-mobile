package ai.clawphones.agent.chat;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class TaskMarketService {

    private static final String TAG = "TaskMarketService";
    private static final String BASE_URL = "https://api.clawphones.ai/tasks";
    private static final int MAX_CONCURRENT_TASKS = 3;
    private static final long AUTO_MATCH_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5);

    private static TaskMarketService instance;

    private final OkHttpClient httpClient;
    private final SharedPreferences encryptedPrefs;
    private final TaskDatabase taskDatabase;
    private final ExecutorService executorService;
    private final Handler mainHandler;
    private Runnable autoMatchRunnable;
    private boolean autoMatchEnabled = false;

    private TaskMarketService(Context context) {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            this.encryptedPrefs = EncryptedSharedPreferences.create(
                    context,
                    "task_market_prefs",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException e) {
            throw new RuntimeException("Failed to create encrypted preferences", e);
        }

        this.taskDatabase = TaskDatabase.getInstance(context);
        this.executorService = Executors.newCachedThreadPool();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public static synchronized TaskMarketService getInstance(Context context) {
        if (instance == null) {
            instance = new TaskMarketService(context.getApplicationContext());
        }
        return instance;
    }

    public interface TaskCallback<T> {
        void onSuccess(T result);
        void onError(String error);
    }

    public void fetchAvailableTasks(double latitude, double longitude, double radiusKm, TaskCallback<List<ClawTask>> callback) {
        executorService.execute(() -> {
            try {
                String url = String.format("%s/available?lat=%f&lon=%f&radius=%f", BASE_URL, latitude, longitude, radiusKm);
                String authToken = getAuthToken();

                Request request = new Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer " + authToken)
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        notifyError(callback, "Failed to fetch tasks: " + response.code());
                        return;
                    }

                    String responseBody = response.body().string();
                    JSONObject json = new JSONObject(responseBody);
                    JSONArray tasksArray = json.getJSONArray("tasks");

                    List<ClawTask> tasks = new ArrayList<>();
                    for (int i = 0; i < tasksArray.length(); i++) {
                        ClawTask task = ClawTask.fromJson(tasksArray.getJSONObject(i));
                        tasks.add(task);
                        taskDatabase.cacheTask(task);
                    }

                    notifySuccess(callback, tasks);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error fetching available tasks", e);
                notifyError(callback, "Error: " + e.getMessage());
            }
        });
    }

    public void acceptTask(String taskId, TaskCallback<ClawTask> callback) {
        executorService.execute(() -> {
            try {
                if (getActiveTaskCount() >= MAX_CONCURRENT_TASKS) {
                    notifyError(callback, "Maximum concurrent tasks reached (" + MAX_CONCURRENT_TASKS + ")");
                    return;
                }

                String url = String.format("%s/%s/accept", BASE_URL, taskId);
                String authToken = getAuthToken();

                RequestBody emptyBody = RequestBody.create("", MediaType.parse("application/json"));
                Request request = new Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer " + authToken)
                        .post(emptyBody)
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        notifyError(callback, "Failed to accept task: " + response.code());
                        return;
                    }

                    String responseBody = response.body().string();
                    JSONObject json = new JSONObject(responseBody);
                    ClawTask task = ClawTask.fromJson(json.getJSONObject("task"));

                    task.setStatus(ClawTask.TaskStatus.ASSIGNED);
                    task.setAssignedAt(System.currentTimeMillis());
                    taskDatabase.cacheTask(task);

                    notifySuccess(callback, task);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error accepting task", e);
                notifyError(callback, "Error: " + e.getMessage());
            }
        });
    }

    public void submitTaskResult(String taskId, JSONObject resultData, TaskCallback<ClawTask> callback) {
        executorService.execute(() -> {
            try {
                String url = String.format("%s/%s/submit", BASE_URL, taskId);
                String authToken = getAuthToken();

                RequestBody body = RequestBody.create(resultData.toString(), MediaType.parse("application/json"));
                Request request = new Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer " + authToken)
                        .post(body)
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        notifyError(callback, "Failed to submit task: " + response.code());
                        return;
                    }

                    String responseBody = response.body().string();
                    JSONObject json = new JSONObject(responseBody);
                    ClawTask task = ClawTask.fromJson(json.getJSONObject("task"));

                    task.setStatus(ClawTask.TaskStatus.COMPLETED);
                    task.setCompletedAt(System.currentTimeMillis());
                    taskDatabase.cacheTask(task);

                    notifySuccess(callback, task);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error submitting task result", e);
                notifyError(callback, "Error: " + e.getMessage());
            }
        });
    }

    public void fetchMyTasks(TaskCallback<List<ClawTask>> callback) {
        executorService.execute(() -> {
            try {
                String url = String.format("%s/my", BASE_URL);
                String authToken = getAuthToken();

                Request request = new Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer " + authToken)
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        notifyError(callback, "Failed to fetch my tasks: " + response.code());
                        return;
                    }

                    String responseBody = response.body().string();
                    JSONObject json = new JSONObject(responseBody);
                    JSONArray tasksArray = json.getJSONArray("tasks");

                    List<ClawTask> tasks = new ArrayList<>();
                    for (int i = 0; i < tasksArray.length(); i++) {
                        ClawTask task = ClawTask.fromJson(tasksArray.getJSONObject(i));
                        tasks.add(task);
                        taskDatabase.cacheTask(task);
                    }

                    notifySuccess(callback, tasks);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error fetching my tasks", e);
                notifyError(callback, "Error: " + e.getMessage());
            }
        });
    }

    public void fetchEarnings(TaskCallback<EarningsSummary> callback) {
        executorService.execute(() -> {
            try {
                String url = String.format("%s/earnings", BASE_URL);
                String authToken = getAuthToken();

                Request request = new Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer " + authToken)
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        notifyError(callback, "Failed to fetch earnings: " + response.code());
                        return;
                    }

                    String responseBody = response.body().string();
                    JSONObject json = new JSONObject(responseBody);

                    EarningsSummary earnings = new EarningsSummary(
                            json.getDouble("total_earned"),
                            json.getDouble("available_balance"),
                            json.getInt("tasks_completed"),
                            json.optInt("tasks_in_progress", 0)
                    );

                    notifySuccess(callback, earnings);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error fetching earnings", e);
                notifyError(callback, "Error: " + e.getMessage());
            }
        });
    }

    public void startAutoMatch(double latitude, double longitude) {
        if (autoMatchEnabled) {
            Log.d(TAG, "Auto-match already enabled");
            return;
        }

        autoMatchEnabled = true;
        autoMatchRunnable = new Runnable() {
            @Override
            public void run() {
                if (!autoMatchEnabled) return;

                autoMatchAndAccept(latitude, longitude, new TaskCallback<Boolean>() {
                    @Override
                    public void onSuccess(Boolean result) {
                        Log.d(TAG, "Auto-match cycle completed");
                    }

                    @Override
                    public void onError(String error) {
                        Log.w(TAG, "Auto-match cycle failed: " + error);
                    }
                });

                if (autoMatchEnabled) {
                    mainHandler.postDelayed(this, AUTO_MATCH_INTERVAL_MS);
                }
            }
        };

        mainHandler.postDelayed(autoMatchRunnable, AUTO_MATCH_INTERVAL_MS);
        Log.d(TAG, "Auto-match started");
    }

    public void stopAutoMatch() {
        autoMatchEnabled = false;
        if (autoMatchRunnable != null) {
            mainHandler.removeCallbacks(autoMatchRunnable);
            autoMatchRunnable = null;
        }
        Log.d(TAG, "Auto-match stopped");
    }

    private void autoMatchAndAccept(double latitude, double longitude, TaskCallback<Boolean> callback) {
        if (getActiveTaskCount() >= MAX_CONCURRENT_TASKS) {
            notifySuccess(callback, false);
            return;
        }

        fetchAvailableTasks(latitude, longitude, 10.0, new TaskCallback<List<ClawTask>>() {
            @Override
            public void onSuccess(List<ClawTask> tasks) {
                if (tasks.isEmpty()) {
                    notifySuccess(callback, false);
                    return;
                }

                acceptTask(tasks.get(0).getTaskId(), new TaskCallback<ClawTask>() {
                    @Override
                    public void onSuccess(ClawTask task) {
                        Log.d(TAG, "Auto-matched task: " + task.getTaskId());
                        notifySuccess(callback, true);
                    }

                    @Override
                    public void onError(String error) {
                        notifyError(callback, error);
                    }
                });
            }

            @Override
            public void onError(String error) {
                notifyError(callback, error);
            }
        });
    }

    private int getActiveTaskCount() {
        List<ClawTask> activeTasks = taskDatabase.getActiveTasks();
        return activeTasks.size();
    }

    private String getAuthToken() {
        return encryptedPrefs.getString("auth_token", "");
    }

    private <T> void notifySuccess(TaskCallback<T> callback, T result) {
        mainHandler.post(() -> callback.onSuccess(result));
    }

    private <T> void notifyError(TaskCallback<T> callback, String error) {
        mainHandler.post(() -> callback.onError(error));
    }

    public void shutdown() {
        stopAutoMatch();
        executorService.shutdown();
    }

    public static class EarningsSummary {
        public final double totalEarned;
        public final double availableBalance;
        public final int tasksCompleted;
        public final int tasksInProgress;

        public EarningsSummary(double totalEarned, double availableBalance, int tasksCompleted, int tasksInProgress) {
            this.totalEarned = totalEarned;
            this.availableBalance = availableBalance;
            this.tasksCompleted = tasksCompleted;
            this.tasksInProgress = tasksInProgress;
        }
    }

    private static class TaskDatabase {
        private static final String DB_NAME = "task_cache.db";
        private static final int DB_VERSION = 1;
        private static TaskDatabase instance;

        private final Context context;

        private TaskDatabase(Context context) {
            this.context = context;
        }

        public static synchronized TaskDatabase getInstance(Context context) {
            if (instance == null) {
                instance = new TaskDatabase(context);
            }
            return instance;
        }

        public void cacheTask(ClawTask task) {
            // Simple in-memory caching for now
            // In production, implement proper SQLite caching
        }

        public List<ClawTask> getActiveTasks() {
            // In production, query SQLite for active tasks
            return new ArrayList<>();
        }

        public List<ClawTask> getAllTasks() {
            // Return all cached tasks
            return new ArrayList<>();
        }

        public ClawTask getTaskById(String taskId) {
            // In production, query SQLite for specific task
            return null;
        }
    }

    public void setAutoAccept(boolean enabled) {
        autoMatchEnabled = enabled;
    }

    public boolean acceptTask(String taskId) {
        return false;
    }

    public boolean startTask(String taskId) {
        return false;
    }

    public boolean completeTask(String taskId) {
        return false;
    }

    public int getTaskProgress(String taskId) {
        return 0;
    }

    public String getTaskResults(String taskId) {
        return null;
    }

    public List<ClawTask> getAllTasks() {
        return taskDatabase.getAllTasks();
    }

    public ClawTask getTaskById(String taskId) {
        return taskDatabase.getTaskById(taskId);
    }
}
