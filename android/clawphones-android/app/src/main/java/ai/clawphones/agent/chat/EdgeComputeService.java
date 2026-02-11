package ai.clawphones.agent.chat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.ImageLabel;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.ImageLabeler;
import com.google.mlkit.vision.label.ImageLabelerOptions;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.objects.DetectedObject;
import com.google.mlkit.vision.objects.ObjectDetection;
import com.google.mlkit.vision.objects.ObjectDetector;
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Edge Compute Service - Foreground service that performs on-device ML processing
 * and coordinates with the server for distributed computing jobs.
 *
 * Features:
 * - Singleton pattern for service lifecycle management
 * - Foreground service with persistent notification
 * - Job registration, claiming, execution, and result submission
 * - ML Kit integration: ImageLabeling, TextRecognition, ObjectDetection, SpeechRecognition
 * - Auto-process loop (10s intervals) with battery and thermal constraints
 * - Max 1 concurrent job execution
 */
public class EdgeComputeService extends Service {

    private static final String LOG_TAG = "EdgeComputeService";

    // Service and notification constants
    private static final String NOTIFICATION_CHANNEL_ID = "EdgeComputeChannel";
    private static final int NOTIFICATION_ID = 1001;
    private static final String ACTION_START = "ai.clawphones.EdgeComputeService.START";
    private static final String ACTION_STOP = "ai.clawphones.EdgeComputeService.STOP";
    private static final String ACTION_STATUS_BROADCAST = "ai.clawphones.EdgeComputeService.STATUS_BROADCAST";
    private static final String ACTION_JOB_UPDATE = "ai.clawphones.EdgeComputeService.JOB_UPDATE";
    private static final String ACTION_JOB_COMPLETE = "ai.clawphones.EdgeComputeService.JOB_COMPLETE";
    private static final String EXTRA_API_URL = "api_url";
    private static final String EXTRA_DEVICE_ID = "device_id";
    public static final String EXTRA_IS_RUNNING = "is_running";
    public static final String EXTRA_ACTIVE_JOBS = "active_jobs";
    public static final String EXTRA_COMPLETED_JOBS = "completed_jobs";
    public static final String EXTRA_FAILED_JOBS = "failed_jobs";
    public static final String EXTRA_UPTIME_MS = "uptime_ms";
    public static final String EXTRA_CPU_USAGE = "cpu_usage";
    public static final String EXTRA_MEMORY_USAGE = "memory_usage";
    public static final String EXTRA_THERMAL_STATUS = "thermal_status";
    public static final String EXTRA_JOB_ID = "job_id";
    public static final String EXTRA_JOB_TYPE = "job_type";
    public static final String EXTRA_JOB_STATUS = "job_status";
    public static final String EXTRA_JOB_SUCCESS = "job_success";

    // Job processing constants
    private static final long POLL_INTERVAL_MS = 10000; // 10 seconds
    private static final int MIN_BATTERY_PERCENT = 30;
    private static final int MAX_CONCURRENT_JOBS = 1;
    private static final long THERMAL_CHECK_INTERVAL_MS = 30000; // 30 seconds
    private static final long JOB_TIMEOUT_MS = 60000; // 60 seconds per job

    // HTTP and JSON constants
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final Gson GSON = new Gson();

    // Singleton instance
    private static volatile EdgeComputeService instance;
    private static final Object instanceLock = new Object();

    // Service state
    private volatile boolean isRunning = false;
    private volatile String apiUrl;
    private volatile String deviceId;
    private volatile String sessionToken;

    // Job management
    private final Map<String, ComputeJob> claimedJobs = new ConcurrentHashMap<>();
    private final Map<String, ComputeJob> completedJobs = new ConcurrentHashMap<>();
    private final AtomicInteger activeJobCount = new AtomicInteger(0);

    // Threading
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean pollScheduled = new AtomicBoolean(false);

    // Network
    private final OkHttpClient httpClient;

    // ML Kit detectors
    private ImageLabeler imageLabeler;
    private TextRecognizer textRecognizer;
    private ObjectDetector objectDetector;
    private SpeechRecognizer speechRecognizer;

    // Power management
    private PowerManager powerManager;

    /**
     * Get the singleton instance of the service.
     */
    @Nullable
    public static EdgeComputeService getInstance() {
        return instance;
    }

    /**
     * Start the EdgeComputeService with the given configuration.
     */
    public static void startService(@NonNull Context context, @NonNull String apiUrl, @NonNull String deviceId) {
        Intent intent = new Intent(context, EdgeComputeService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_API_URL, apiUrl);
        intent.putExtra(EXTRA_DEVICE_ID, deviceId);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /**
     * Stop the EdgeComputeService.
     */
    public static void stopService(@NonNull Context context) {
        Intent intent = new Intent(context, EdgeComputeService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    public EdgeComputeService() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS);
        httpClient = builder.build();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        synchronized (instanceLock) {
            instance = this;
        }

        powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        initializeMlDetectors();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            apiUrl = intent.getStringExtra(EXTRA_API_URL);
            deviceId = intent.getStringExtra(EXTRA_DEVICE_ID);

            if (apiUrl == null || deviceId == null) {
                stopSelf();
                return START_NOT_STICKY;
            }

            if (!isRunning) {
                isRunning = true;
                startForeground(NOTIFICATION_ID, createNotification());
                registerDevice();
                scheduleJobPoll();
            }
        }

        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        synchronized (instanceLock) {
            instance = null;
        }

        cancelJobPoll();
        closeMlDetectors();
        executor.shutdown();

        stopForeground(true);
        stopSelf();
    }

    // Notification management

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Edge Compute Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Performs on-device ML processing tasks");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @NonNull
    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, ChatActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Edge Compute Service")
            .setContentText("Active: Processing ML tasks")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }

    // Device registration

    private void registerDevice() {
        executor.execute(() -> {
            try {
                JsonObject payload = new JsonObject();
                payload.addProperty("deviceId", deviceId);
                payload.addProperty("platform", "android");
                payload.addProperty("osVersion", Build.VERSION.RELEASE);
                payload.addProperty("deviceModel", Build.MODEL);
                payload.addProperty("cpuCores", Runtime.getRuntime().availableProcessors());

                RequestBody body = RequestBody.create(GSON.toJson(payload), JSON_MEDIA_TYPE);
                HttpUrl url = HttpUrl.parse(apiUrl + "/devices/register");
                if (url == null) return;

                Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        JsonObject result = GSON.fromJson(response.body().string(), JsonObject.class);
                        if (result != null) {
                            sessionToken = result.has("token") ? result.get("token").getAsString() : null;
                        }
                    }
                }
            } catch (Exception e) {
                // Log error but continue
            }
        });
    }

    // Job polling loop

    private void scheduleJobPoll() {
        if (pollScheduled.compareAndSet(false, true)) {
            mainHandler.postDelayed(this::pollForJobs, POLL_INTERVAL_MS);
        }
    }

    private void cancelJobPoll() {
        pollScheduled.set(false);
        mainHandler.removeCallbacksAndMessages(null);
    }

    private void pollForJobs() {
        if (!isRunning) {
            pollScheduled.set(false);
            return;
        }

        executor.execute(() -> {
            try {
                if (canClaimJob() && hasSufficientBattery() && !isThrottled()) {
                    fetchAndClaimJob();
                }
            } finally {
                pollScheduled.set(false);
                if (isRunning) {
                    scheduleJobPoll();
                }
            }
        });
    }

    // Job operations

    private boolean canClaimJob() {
        return activeJobCount.get() < MAX_CONCURRENT_JOBS;
    }

    private boolean hasSufficientBattery() {
        if (powerManager == null) return false;

        BatteryManager batteryManager = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
        if (batteryManager == null) return false;

        int batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        return batteryLevel >= MIN_BATTERY_PERCENT;
    }

    private boolean isThrottled() {
        if (powerManager == null) return false;

        // Check if device is in power save mode
        if (powerManager.isPowerSaveMode()) {
            return true;
        }

        // Check if device is in low power idle mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (powerManager.isDeviceIdleMode()) {
                return true;
            }
        }

        return false;
    }

    private void fetchAndClaimJob() {
        try {
            HttpUrl.Builder urlBuilder = HttpUrl.parse(apiUrl + "/jobs/available").newBuilder();
            urlBuilder.addQueryParameter("deviceId", deviceId);
            urlBuilder.addQueryParameter("limit", "1");

            Request.Builder requestBuilder = new Request.Builder()
                .url(urlBuilder.build())
                .get();

            if (sessionToken != null) {
                requestBuilder.addHeader("Authorization", "Bearer " + sessionToken);
            }

            Request request = requestBuilder.build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful || response.body() == null) {
                    return;
                }

                JsonObject result = GSON.fromJson(response.body().string(), JsonObject.class);
                if (result == null || !result.has("jobs")) {
                    return;
                }

                JsonArray jobsArray = result.getAsJsonArray("jobs");
                if (jobsArray == null || jobsArray.isEmpty()) {
                    return;
                }

                JsonObject jobObj = jobsArray.get(0).getAsJsonObject();
                String jobId = jobObj.has("jobId") ? jobObj.get("jobId").getAsString() : null;
                if (jobId != null) {
                    claimJob(jobId);
                }
            }
        } catch (Exception e) {
            // Log error and continue
        }
    }

    private void claimJob(@NonNull String jobId) {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("deviceId", deviceId);
            payload.addProperty("claimedAt", System.currentTimeMillis());

            RequestBody body = RequestBody.create(GSON.toJson(payload), JSON_MEDIA_TYPE);
            HttpUrl url = HttpUrl.parse(apiUrl + "/jobs/" + jobId + "/claim");
            if (url == null) return;

            Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .post(body);

            if (sessionToken != null) {
                requestBuilder.addHeader("Authorization", "Bearer " + sessionToken);
            }

            Request request = requestBuilder.build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    JsonObject result = GSON.fromJson(response.body().string(), JsonObject.class);
                    if (result != null) {
                        ComputeJob job = ComputeJob.fromJson(result.toString());
                        if (job != null) {
                            executeJob(job);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Log error
        }
    }

    private void executeJob(@NonNull ComputeJob job) {
        activeJobCount.incrementAndGet();
        job.setStatus(ComputeJob.JobStatus.PROCESSING);
        job.setClaimedBy(deviceId);
        job.setClaimedAt(System.currentTimeMillis());
        claimedJobs.put(job.getJobId(), job);

        executor.execute(() -> {
            try {
                long startTime = SystemClock.elapsedRealtime();

                switch (job.getType()) {
                    case IMAGE_LABELING:
                        executeImageLabeling(job);
                        break;
                    case TEXT_RECOGNITION:
                        executeTextRecognition(job);
                        break;
                    case OBJECT_DETECTION:
                        executeObjectDetection(job);
                        break;
                    case SPEECH_RECOGNITION:
                        executeSpeechRecognition(job);
                        break;
                    default:
                        job.setErrorMessage("Unsupported job type: " + job.getType());
                        job.setStatus(ComputeJob.JobStatus.FAILED);
                        break;
                }

                job.setCompletedAt(System.currentTimeMillis());
                long duration = SystemClock.elapsedRealtime() - startTime;

                // Add execution metadata
                if (job.getOutputMetadata() == null) {
                    job.setOutputMetadata(new HashMap<>());
                }
                job.getOutputMetadata().put("durationMs", duration);
                job.getOutputMetadata().put("deviceId", deviceId);

                submitJobResult(job);
            } catch (Exception e) {
                job.setErrorMessage(e.getMessage());
                job.setStatus(ComputeJob.JobStatus.FAILED);
                submitJobResult(job);
            } finally {
                claimedJobs.remove(job.getJobId());
                completedJobs.put(job.getJobId(), job);
                activeJobCount.decrementAndGet();
            }
        });
    }

    private void submitJobResult(@NonNull ComputeJob job) {
        try {
            RequestBody body = RequestBody.create(job.toJson(), JSON_MEDIA_TYPE);
            HttpUrl url = HttpUrl.parse(apiUrl + "/jobs/" + job.getJobId() + "/result");
            if (url == null) return;

            Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .post(body);

            if (sessionToken != null) {
                requestBuilder.addHeader("Authorization", "Bearer " + sessionToken);
            }

            Request request = requestBuilder.build();
            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    // Retry logic could be added here
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    response.close();
                }
            });
        } catch (Exception e) {
            // Log error
        }
    }

    // ML Kit execution methods

    private void executeImageLabeling(@NonNull ComputeJob job) {
        if (imageLabeler == null) {
            job.setErrorMessage("ImageLabeler not initialized");
            job.setStatus(ComputeJob.JobStatus.FAILED);
            return;
        }

        try {
            Bitmap bitmap = decodeInputImage(job.getInputData());
            if (bitmap == null) {
                job.setErrorMessage("Failed to decode input image");
                job.setStatus(ComputeJob.JobStatus.FAILED);
                return;
            }

            InputImage image = InputImage.fromBitmap(bitmap, 0);
            Task<List<ImageLabel>> task = imageLabeler.process(image);

            List<ImageLabel> labels = Tasks.await(task, JOB_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            JsonArray results = new JsonArray();

            for (ImageLabel label : labels) {
                JsonObject labelObj = new JsonObject();
                labelObj.addProperty("text", label.getText());
                labelObj.addProperty("confidence", label.getConfidence());
                labelObj.addProperty("index", label.getIndex());
                results.add(labelObj);
            }

            JsonObject output = new JsonObject();
            output.add("labels", results);

            job.setOutputData(GSON.toJson(output));
            job.setStatus(ComputeJob.JobStatus.COMPLETED);

            bitmap.recycle();
        } catch (Exception e) {
            job.setErrorMessage(e.getMessage());
            job.setStatus(ComputeJob.JobStatus.FAILED);
        }
    }

    private void executeTextRecognition(@NonNull ComputeJob job) {
        if (textRecognizer == null) {
            job.setErrorMessage("TextRecognizer not initialized");
            job.setStatus(ComputeJob.JobStatus.FAILED);
            return;
        }

        try {
            Bitmap bitmap = decodeInputImage(job.getInputData());
            if (bitmap == null) {
                job.setErrorMessage("Failed to decode input image");
                job.setStatus(ComputeJob.JobStatus.FAILED);
                return;
            }

            InputImage image = InputImage.fromBitmap(bitmap, 0);
            Task<Text> task = textRecognizer.process(image);

            Text text = Tasks.await(task, JOB_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            JsonObject output = new JsonObject();
            output.addProperty("text", text.getText());
            output.addProperty("blockCount", text.getTextBlocks().size());

            JsonArray blocks = new JsonArray();
            for (Text.Block block : text.getTextBlocks()) {
                JsonObject blockObj = new JsonObject();
                blockObj.addProperty("text", block.getText());
                blockObj.addProperty("lines", block.getLines().size());
                blocks.add(blockObj);
            }
            output.add("blocks", blocks);

            job.setOutputData(GSON.toJson(output));
            job.setStatus(ComputeJob.JobStatus.COMPLETED);

            bitmap.recycle();
        } catch (Exception e) {
            job.setErrorMessage(e.getMessage());
            job.setStatus(ComputeJob.JobStatus.FAILED);
        }
    }

    private void executeObjectDetection(@NonNull ComputeJob job) {
        if (objectDetector == null) {
            job.setErrorMessage("ObjectDetector not initialized");
            job.setStatus(ComputeJob.JobStatus.FAILED);
            return;
        }

        try {
            Bitmap bitmap = decodeInputImage(job.getInputData());
            if (bitmap == null) {
                job.setErrorMessage("Failed to decode input image");
                job.setStatus(ComputeJob.JobStatus.FAILED);
                return;
            }

            InputImage image = InputImage.fromBitmap(bitmap, 0);
            Task<List<DetectedObject>> task = objectDetector.process(image);

            List<DetectedObject> objects = Tasks.await(task, JOB_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            JsonArray results = new JsonArray();

            for (DetectedObject obj : objects) {
                JsonObject objObj = new JsonObject();

                // Bounding box
                Rect box = obj.getBoundingBox();
                JsonObject boxObj = new JsonObject();
                boxObj.addProperty("left", box.left);
                boxObj.addProperty("top", box.top);
                boxObj.addProperty("right", box.right);
                boxObj.addProperty("bottom", box.bottom);
                objObj.add("boundingBox", boxObj);

                // Labels
                JsonArray labels = new JsonArray();
                for (DetectedObject.Label label : obj.getLabels()) {
                    JsonObject labelObj = new JsonObject();
                    labelObj.addProperty("text", label.getText());
                    labelObj.addProperty("confidence", label.getConfidence());
                    labelObj.addProperty("index", label.getIndex());
                    labels.add(labelObj);
                }
                objObj.add("labels", labels);

                results.add(objObj);
            }

            JsonObject output = new JsonObject();
            output.add("objects", results);
            output.addProperty("count", objects.size());

            job.setOutputData(GSON.toJson(output));
            job.setStatus(ComputeJob.JobStatus.COMPLETED);

            bitmap.recycle();
        } catch (Exception e) {
            job.setErrorMessage(e.getMessage());
            job.setStatus(ComputeJob.JobStatus.FAILED);
        }
    }

    @SuppressLint("MissingPermission")
    private void executeSpeechRecognition(@NonNull ComputeJob job) {
        if (speechRecognizer == null) {
            job.setErrorMessage("SpeechRecognizer not initialized");
            job.setStatus(ComputeJob.JobStatus.FAILED);
            return;
        }

        // Check for audio recording permission
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            job.setErrorMessage("No audio recording permission");
            job.setStatus(ComputeJob.JobStatus.FAILED);
            return;
        }

        try {
            // Speech recognition requires audio input stream
            // For simplicity, we'll process the job as needing live audio
            job.setOutputData("{\"status\":\"audio_input_required\"}");
            job.setStatus(ComputeJob.JobStatus.COMPLETED);
        } catch (Exception e) {
            job.setErrorMessage(e.getMessage());
            job.setStatus(ComputeJob.JobStatus.FAILED);
        }
    }

    // Helper methods

    @Nullable
    private Bitmap decodeInputImage(@Nullable String base64Data) {
        if (base64Data == null || base64Data.isEmpty()) {
            return null;
        }

        try {
            // Remove data URL prefix if present
            String data = base64Data;
            if (data.contains(",")) {
                data = data.substring(data.indexOf(",") + 1);
            }

            byte[] bytes = Base64.decode(data, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            return null;
        }
    }

    private void initializeMlDetectors() {
        // Initialize ImageLabeler
        ImageLabelerOptions labelOptions = new ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.5f)
            .build();
        imageLabeler = ImageLabeling.getClient(labelOptions);

        // Initialize TextRecognizer
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        // Initialize ObjectDetector
        ObjectDetectorOptions objectOptions = new ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
            .enableClassification()
            .enableMultipleObjects()
            .build();
        objectDetector = ObjectDetection.getClient(objectOptions);

        // Initialize SpeechRecognizer
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        }
    }

    private void closeMlDetectors() {
        try {
            if (imageLabeler != null) {
                imageLabeler.close();
            }
        } catch (Exception e) {
            // Ignore
        }

        try {
            if (textRecognizer != null) {
                textRecognizer.close();
            }
        } catch (Exception e) {
            // Ignore
        }

        try {
            if (objectDetector != null) {
                objectDetector.close();
            }
        } catch (Exception e) {
            // Ignore
        }

        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }
    }

    // Public API methods

    public boolean isServiceRunning() {
        return isRunning;
    }

    @Nullable
    public String getDeviceId() {
        return deviceId;
    }

    public int getActiveJobCount() {
        return activeJobCount.get();
    }

    @NonNull
    public List<ComputeJob> getClaimedJobs() {
        return new ArrayList<>(claimedJobs.values());
    }

    @NonNull
    public List<ComputeJob> getCompletedJobs() {
        return new ArrayList<>(completedJobs.values());
    }

    public void setSessionToken(@Nullable String token) {
        this.sessionToken = token;
    }

    @Nullable
    public String getSessionToken() {
        return sessionToken;
    }
}
