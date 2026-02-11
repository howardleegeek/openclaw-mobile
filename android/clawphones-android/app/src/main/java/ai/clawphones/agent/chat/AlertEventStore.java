package ai.clawphones.agent.chat;

import android.content.Context;
import android.graphics.RectF;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Local alert timeline storage.
 * Metadata is stored in JSON, thumbnail/frame bytes are stored as files.
 */
public final class AlertEventStore {

    private static final String DIR_NAME = "clawvision_alerts";
    private static final String THUMBNAILS_DIR = "thumbnails";
    private static final String METADATA_FILE = "events.json";
    private static final long RETENTION_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final int MAX_EVENTS = 1000;

    private final Context mAppContext;

    public AlertEventStore(@NonNull Context context) {
        mAppContext = context.getApplicationContext();
        ensureDirectories();
    }

    public synchronized List<AlertEvent> loadEvents() {
        ensureDirectories();
        List<AlertEvent> metadata = readMetadata();
        List<AlertEvent> pruned = prune(metadata);
        saveMetadata(pruned);
        cleanupOrphanedThumbnails(new HashSet<>(collectIds(pruned)));

        List<AlertEvent> out = new ArrayList<>(pruned.size());
        for (AlertEvent item : pruned) {
            out.add(item.withThumbnailData(readThumbnail(item.id)));
        }
        return out;
    }

    public synchronized void saveEvent(@NonNull AlertEvent event) {
        ensureDirectories();

        if (event.thumbnailData != null && event.thumbnailData.length > 0) {
            writeThumbnail(event.id, event.thumbnailData);
        }

        List<AlertEvent> metadata = readMetadata();
        metadata.removeIf(existing -> TextUtils.equals(existing.id, event.id));
        metadata.add(event.withThumbnailData(null));

        List<AlertEvent> pruned = prune(metadata);
        saveMetadata(pruned);
        cleanupOrphanedThumbnails(new HashSet<>(collectIds(pruned)));
    }

    public synchronized boolean deleteEvent(@NonNull String id) {
        ensureDirectories();
        List<AlertEvent> metadata = readMetadata();
        boolean removed = metadata.removeIf(item -> TextUtils.equals(item.id, id));
        saveMetadata(metadata);
        File thumbnail = thumbnailFile(id);
        if (thumbnail.exists()) {
            //noinspection ResultOfMethodCallIgnored
            thumbnail.delete();
        }
        return removed;
    }

    @Nullable
    public synchronized AlertEvent getEventById(@NonNull String id) {
        List<AlertEvent> events = loadEvents();
        for (AlertEvent event : events) {
            if (TextUtils.equals(event.id, id)) {
                return event;
            }
        }
        return null;
    }

    private List<String> collectIds(List<AlertEvent> events) {
        List<String> ids = new ArrayList<>(events.size());
        for (AlertEvent event : events) {
            ids.add(event.id);
        }
        return ids;
    }

    private List<AlertEvent> prune(List<AlertEvent> events) {
        long cutoff = System.currentTimeMillis() - RETENTION_MS;
        List<AlertEvent> filtered = new ArrayList<>();
        for (AlertEvent event : events) {
            if (event.timestampMs >= cutoff) {
                filtered.add(event.withThumbnailData(null));
            }
        }

        filtered.sort(new Comparator<AlertEvent>() {
            @Override
            public int compare(AlertEvent a, AlertEvent b) {
                return Long.compare(b.timestampMs, a.timestampMs);
            }
        });

        if (filtered.size() > MAX_EVENTS) {
            return new ArrayList<>(filtered.subList(0, MAX_EVENTS));
        }
        return filtered;
    }

    private void ensureDirectories() {
        File base = baseDir();
        if (!base.exists()) {
            //noinspection ResultOfMethodCallIgnored
            base.mkdirs();
        }
        File thumbs = thumbnailsDir();
        if (!thumbs.exists()) {
            //noinspection ResultOfMethodCallIgnored
            thumbs.mkdirs();
        }
    }

    private File baseDir() {
        return new File(mAppContext.getFilesDir(), DIR_NAME);
    }

    private File thumbnailsDir() {
        return new File(baseDir(), THUMBNAILS_DIR);
    }

    private File metadataFile() {
        return new File(baseDir(), METADATA_FILE);
    }

    private File thumbnailFile(@NonNull String id) {
        return new File(thumbnailsDir(), id + ".jpg");
    }

    private List<AlertEvent> readMetadata() {
        File file = metadataFile();
        if (!file.exists()) return new ArrayList<>();

        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = fis.read(buffer)) > 0) {
                bos.write(buffer, 0, n);
            }
            String raw = bos.toString(StandardCharsets.UTF_8.name());
            JSONArray array = new JSONArray(raw);
            List<AlertEvent> out = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj == null) continue;
                String id = obj.optString("id", "").trim();
                if (id.isEmpty()) continue;
                out.add(new AlertEvent(
                    id,
                    obj.optString("type", "unknown"),
                    (float) obj.optDouble("confidence", 0),
                    obj.optLong("timestamp", 0L),
                    obj.optDouble("latitude", 0),
                    obj.optDouble("longitude", 0),
                    parseBoundingBox(obj.optJSONObject("bounding_box")),
                    null
                ));
            }
            return out;
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    private void saveMetadata(List<AlertEvent> metadata) {
        JSONArray array = new JSONArray();
        for (AlertEvent event : metadata) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("id", event.id);
                obj.put("type", event.type);
                obj.put("confidence", event.confidence);
                obj.put("timestamp", event.timestampMs);
                obj.put("latitude", event.latitude);
                obj.put("longitude", event.longitude);
                if (event.boundingBox != null) {
                    JSONObject box = new JSONObject();
                    box.put("x", event.boundingBox.left);
                    box.put("y", event.boundingBox.top);
                    box.put("width", event.boundingBox.width());
                    box.put("height", event.boundingBox.height());
                    obj.put("bounding_box", box);
                }
                array.put(obj);
            } catch (Exception ignored) {
            }
        }

        try (FileOutputStream fos = new FileOutputStream(metadataFile())) {
            fos.write(array.toString().getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } catch (Exception ignored) {
        }
    }

    @Nullable
    private RectF parseBoundingBox(@Nullable JSONObject box) {
        if (box == null) return null;
        float x = (float) box.optDouble("x", Float.NaN);
        float y = (float) box.optDouble("y", Float.NaN);
        float width = (float) box.optDouble("width", Float.NaN);
        float height = (float) box.optDouble("height", Float.NaN);
        if (Float.isNaN(x) || Float.isNaN(y) || Float.isNaN(width) || Float.isNaN(height)) {
            return null;
        }
        return new RectF(x, y, x + width, y + height);
    }

    @Nullable
    private byte[] readThumbnail(@NonNull String id) {
        File file = thumbnailFile(id);
        if (!file.exists()) return null;
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = fis.read(buffer)) > 0) {
                bos.write(buffer, 0, n);
            }
            return bos.toByteArray();
        } catch (Exception ignored) {
            return null;
        }
    }

    private void writeThumbnail(@NonNull String id, @NonNull byte[] data) {
        File file = thumbnailFile(id);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
            fos.flush();
        } catch (Exception ignored) {
        }
    }

    private void cleanupOrphanedThumbnails(@NonNull Set<String> validIds) {
        File[] files = thumbnailsDir().listFiles();
        if (files == null || files.length == 0) return;

        Set<String> validNames = new HashSet<>();
        for (String id : validIds) {
            validNames.add(id + ".jpg");
        }

        for (File file : files) {
            if (!validNames.contains(file.getName())) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    public static final class AlertEvent {
        @NonNull public final String id;
        @NonNull public final String type;
        public final float confidence;
        public final long timestampMs;
        public final double latitude;
        public final double longitude;
        @Nullable public final RectF boundingBox;
        @Nullable public final byte[] thumbnailData;

        public AlertEvent(
            @NonNull String id,
            @NonNull String type,
            float confidence,
            long timestampMs,
            double latitude,
            double longitude,
            @Nullable RectF boundingBox,
            @Nullable byte[] thumbnailData
        ) {
            this.id = id;
            this.type = type;
            this.confidence = confidence;
            this.timestampMs = timestampMs;
            this.latitude = latitude;
            this.longitude = longitude;
            this.boundingBox = boundingBox;
            this.thumbnailData = thumbnailData;
        }

        @NonNull
        public AlertEvent withThumbnailData(@Nullable byte[] thumbnail) {
            return new AlertEvent(
                id,
                type,
                confidence,
                timestampMs,
                latitude,
                longitude,
                boundingBox == null ? null : new RectF(boundingBox),
                thumbnail
            );
        }

        @NonNull
        public String normalizedType() {
            return type.trim().toLowerCase(Locale.US);
        }
    }
}
