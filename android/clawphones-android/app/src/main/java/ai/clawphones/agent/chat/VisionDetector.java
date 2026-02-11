package ai.clawphones.agent.chat;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.objects.DetectedObject;
import com.google.mlkit.vision.objects.ObjectDetection;
import com.google.mlkit.vision.objects.ObjectDetector;
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions;
import com.termux.shared.logger.Logger;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * On-device vision detector (ML Kit): object + face detection with coarse type mapping.
 */
public class VisionDetector implements Closeable {

    private static final String LOG_TAG = "VisionDetector";

    public static final String TYPE_PERSON = "person";
    public static final String TYPE_VEHICLE = "vehicle";
    public static final String TYPE_ANIMAL = "animal";
    public static final String TYPE_PACKAGE = "package";
    public static final String TYPE_UNKNOWN = "unknown";

    private static final float DEFAULT_CONFIDENCE_THRESHOLD = 0.5f;
    private static final float FACE_DETECTION_CONFIDENCE = 0.95f;
    private static final long LATENCY_TARGET_MS = 300L;

    private static final String[] PERSON_KEYWORDS = {
        "person", "human", "face", "man", "woman", "boy", "girl", "pedestrian", "people"
    };
    private static final String[] VEHICLE_KEYWORDS = {
        "vehicle", "car", "truck", "bus", "bike", "bicycle", "motorcycle", "motorbike",
        "scooter", "train", "plane", "airplane", "boat", "ship", "van", "taxi"
    };
    private static final String[] ANIMAL_KEYWORDS = {
        "animal", "dog", "cat", "bird", "horse", "cow", "sheep", "goat", "pig",
        "elephant", "monkey", "bear", "tiger", "lion", "deer", "rabbit", "fish"
    };
    private static final String[] PACKAGE_KEYWORDS = {
        "package", "parcel", "box", "carton", "delivery", "mail", "envelope",
        "baggage", "luggage", "suitcase", "backpack", "bag"
    };

    private final ObjectDetector objectDetector;
    private final FaceDetector faceDetector;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile float confidenceThreshold;
    private volatile int maxInputSide = 1280;

    public static class Detection {
        public String type;       // person, vehicle, animal, package, unknown
        public float confidence;  // 0.0-1.0
        public Rect boundingBox;  // pixel coordinates

        Detection(@NonNull String type, float confidence, @NonNull Rect boundingBox) {
            this.type = type;
            this.confidence = confidence;
            this.boundingBox = new Rect(boundingBox);
        }
    }

    public interface DetectionCallback {
        void onResult(List<Detection> detections);
        void onError(Exception e);
    }

    public VisionDetector() {
        this(DEFAULT_CONFIDENCE_THRESHOLD);
    }

    public VisionDetector(float confidenceThreshold) {
        this.confidenceThreshold = clampThreshold(confidenceThreshold);

        ObjectDetectorOptions objectOptions = new ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
            .enableClassification()
            .enableMultipleObjects()
            .build();
        objectDetector = ObjectDetection.getClient(objectOptions);

        FaceDetectorOptions faceOptions = new FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .build();
        faceDetector = FaceDetection.getClient(faceOptions);
    }

    public void setConfidenceThreshold(float confidenceThreshold) {
        this.confidenceThreshold = clampThreshold(confidenceThreshold);
    }

    public float getConfidenceThreshold() {
        return confidenceThreshold;
    }

    public void setMaxInputSide(int maxInputSide) {
        this.maxInputSide = Math.max(320, maxInputSide);
    }

    public int getMaxInputSide() {
        return maxInputSide;
    }

    public void detect(@Nullable Bitmap image, @NonNull DetectionCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback == null");
        }
        if (image == null) {
            postError(callback, new IllegalArgumentException("image == null"));
            return;
        }

        final long startedAt = SystemClock.elapsedRealtime();
        final Bitmap inferenceBitmap = maybeScaleForLatency(image);
        final InputImage inputImage = InputImage.fromBitmap(inferenceBitmap, 0);
        final Task<List<DetectedObject>> objectTask = objectDetector.process(inputImage);
        final Task<List<Face>> faceTask = faceDetector.process(inputImage);

        Tasks.whenAllComplete(objectTask, faceTask)
            .addOnCompleteListener(allDoneTask -> {
                if (allDoneTask.isCanceled()) {
                    postError(callback, new RuntimeException("Vision detection canceled"));
                    return;
                }

                final ArrayList<Detection> detections = new ArrayList<>();
                Exception firstError = null;

                if (objectTask.isSuccessful()) {
                    detections.addAll(mapObjects(objectTask.getResult()));
                } else {
                    firstError = unwrapTaskError(objectTask, "object detection failed");
                }

                if (faceTask.isSuccessful()) {
                    mergeFaces(detections, faceTask.getResult());
                } else if (firstError == null) {
                    firstError = unwrapTaskError(faceTask, "face detection failed");
                }

                long latencyMs = SystemClock.elapsedRealtime() - startedAt;
                if (latencyMs > LATENCY_TARGET_MS) {
                    Logger.logWarn(LOG_TAG, "detect latency " + latencyMs + "ms exceeds target " + LATENCY_TARGET_MS + "ms");
                } else {
                    Logger.logDebug(LOG_TAG, "detect latency " + latencyMs + "ms");
                }

                if (detections.isEmpty() && firstError != null) {
                    postError(callback, firstError);
                    return;
                }

                postResult(callback, detections);
            });
    }

    @Override
    public void close() {
        try {
            objectDetector.close();
        } catch (Exception ignored) {
        }
        try {
            faceDetector.close();
        } catch (Exception ignored) {
        }
    }

    private List<Detection> mapObjects(@Nullable List<DetectedObject> objects) {
        if (objects == null || objects.isEmpty()) {
            return Collections.emptyList();
        }

        float threshold = confidenceThreshold;
        ArrayList<Detection> detections = new ArrayList<>();
        for (DetectedObject object : objects) {
            Detection mapped = mapObject(object, threshold);
            if (mapped != null) detections.add(mapped);
        }
        return detections;
    }

    @Nullable
    private Detection mapObject(@NonNull DetectedObject object, float threshold) {
        List<DetectedObject.Label> labels = object.getLabels();
        if (labels == null || labels.isEmpty()) return null;

        String bestType = TYPE_UNKNOWN;
        float bestConfidence = 0f;

        for (DetectedObject.Label label : labels) {
            String type = mapLabelToType(label.getText());
            Float rawConfidence = label.getConfidence();
            float confidence = normalizeConfidence(rawConfidence == null ? 0f : rawConfidence);

            boolean betterType = TYPE_UNKNOWN.equals(bestType) && !TYPE_UNKNOWN.equals(type);
            if (betterType || confidence > bestConfidence) {
                bestType = type;
                bestConfidence = confidence;
            }
        }

        if (bestConfidence < threshold) {
            return null;
        }

        return new Detection(bestType, bestConfidence, object.getBoundingBox());
    }

    private void mergeFaces(@NonNull List<Detection> detections, @Nullable List<Face> faces) {
        if (faces == null || faces.isEmpty()) return;
        if (FACE_DETECTION_CONFIDENCE < confidenceThreshold) return;

        for (Face face : faces) {
            Rect box = face.getBoundingBox();
            if (isDuplicatePersonDetection(detections, box)) continue;
            detections.add(new Detection(TYPE_PERSON, FACE_DETECTION_CONFIDENCE, box));
        }
    }

    private boolean isDuplicatePersonDetection(@NonNull List<Detection> detections, @NonNull Rect faceBox) {
        for (Detection detection : detections) {
            if (!TYPE_PERSON.equals(detection.type) || detection.boundingBox == null) continue;
            if (intersectionOverUnion(detection.boundingBox, faceBox) > 0.65f) {
                return true;
            }
        }
        return false;
    }

    private static float intersectionOverUnion(@NonNull Rect a, @NonNull Rect b) {
        int left = Math.max(a.left, b.left);
        int top = Math.max(a.top, b.top);
        int right = Math.min(a.right, b.right);
        int bottom = Math.min(a.bottom, b.bottom);

        int intersectionWidth = Math.max(0, right - left);
        int intersectionHeight = Math.max(0, bottom - top);
        long intersection = (long) intersectionWidth * intersectionHeight;
        if (intersection <= 0L) return 0f;

        long areaA = (long) Math.max(0, a.width()) * Math.max(0, a.height());
        long areaB = (long) Math.max(0, b.width()) * Math.max(0, b.height());
        long union = areaA + areaB - intersection;
        if (union <= 0L) return 0f;
        return (float) intersection / (float) union;
    }

    @NonNull
    private static String mapLabelToType(@Nullable String rawLabel) {
        if (TextUtils.isEmpty(rawLabel)) return TYPE_UNKNOWN;
        String label = rawLabel.toLowerCase(Locale.US);

        if (containsAny(label, PERSON_KEYWORDS)) return TYPE_PERSON;
        if (containsAny(label, VEHICLE_KEYWORDS)) return TYPE_VEHICLE;
        if (containsAny(label, ANIMAL_KEYWORDS)) return TYPE_ANIMAL;
        if (containsAny(label, PACKAGE_KEYWORDS)) return TYPE_PACKAGE;
        return TYPE_UNKNOWN;
    }

    private static boolean containsAny(@NonNull String value, @NonNull String[] keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) return true;
        }
        return false;
    }

    private static float clampThreshold(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return DEFAULT_CONFIDENCE_THRESHOLD;
        }
        return Math.max(0f, Math.min(1f, value));
    }

    private static float normalizeConfidence(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, value));
    }

    @NonNull
    private Bitmap maybeScaleForLatency(@NonNull Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longestSide = Math.max(width, height);
        int targetLongestSide = Math.max(320, maxInputSide);
        if (longestSide <= targetLongestSide) return source;

        float scale = targetLongestSide / (float) longestSide;
        int targetWidth = Math.max(1, Math.round(width * scale));
        int targetHeight = Math.max(1, Math.round(height * scale));
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true);
    }

    private static <T> Exception unwrapTaskError(@NonNull Task<T> task, @NonNull String fallbackMessage) {
        Exception error = task.getException();
        return error != null ? error : new RuntimeException(fallbackMessage);
    }

    private void postResult(@NonNull DetectionCallback callback, @NonNull List<Detection> detections) {
        mainHandler.post(() -> callback.onResult(detections));
    }

    private void postError(@NonNull DetectionCallback callback, @NonNull Exception error) {
        mainHandler.post(() -> callback.onError(error));
    }
}
