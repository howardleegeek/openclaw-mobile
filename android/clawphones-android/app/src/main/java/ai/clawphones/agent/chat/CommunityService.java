package ai.clawphones.agent.chat;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class CommunityService {

    private static final String LOG_TAG = "CommunityService";
    private static final String BASE_URL = ClawPhonesAPI.BASE_URL;
    private static final String PREFS = "clawphones_community";
    private static final String SECURE_PREFS = "clawphones_community_secure";
    private static final String PREF_TOKEN = "community_token";
    private static final String PREF_SECURE_MIGRATED = "secure_migrated_v1";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private static volatile CommunityService instance;

    private final Context appContext;
    private final OkHttpClient httpClient;
    private final ExecutorService backgroundExecutor;
    private final CommunityCache communityCache;

    public static final class CommunityException extends Exception {
        public final int statusCode;

        CommunityException(int statusCode, @NonNull String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }

    public static final class Alert {
        @NonNull public final String id;
        @NonNull public final String communityId;
        @NonNull public final String message;
        @NonNull public final String senderId;
        @Nullable public final String senderName;
        public final double lat;
        public final double lon;
        public final long createdAt;

        public Alert(
            @NonNull String id,
            @NonNull String communityId,
            @NonNull String message,
            @NonNull String senderId,
            @Nullable String senderName,
            double lat,
            double lon,
            long createdAt
        ) {
            this.id = id;
            this.communityId = communityId;
            this.message = message;
            this.senderId = senderId;
            this.senderName = senderName;
            this.lat = lat;
            this.lon = lon;
            this.createdAt = createdAt;
        }

        @NonNull
        public static Alert fromJson(@NonNull JSONObject json) throws JSONException {
            String id = json.optString("id", "");
            String communityId = json.optString("community_id", "");
            String message = json.optString("message", "");
            String senderId = json.optString("sender_id", "");

            if (TextUtils.isEmpty(id) || TextUtils.isEmpty(communityId) ||
                TextUtils.isEmpty(message) || TextUtils.isEmpty(senderId)) {
                throw new JSONException("Missing required fields");
            }

            String senderName = json.optString("sender_name", null);
            if (json.isNull("sender_name") || TextUtils.isEmpty(senderName)) {
                senderName = null;
            }

            double lat = json.optDouble("lat", 0.0);
            double lon = json.optDouble("lon", 0.0);
            long createdAt = json.optLong("created_at", 0L);

            return new Alert(id, communityId, message, senderId, senderName, lat, lon, createdAt);
        }
    }

    public interface CreateCommunityCallback {
        void onSuccess(Community community);
        void onError(Exception error);
    }

    public interface JoinCommunityCallback {
        void onSuccess(Community community);
        void onError(Exception error);
    }

    public interface LeaveCommunityCallback {
        void onSuccess();
        void onError(Exception error);
    }

    public interface FetchCommunitiesCallback {
        void onSuccess(List<Community> communities);
        void onError(Exception error);
    }

    public interface FetchAlertsCallback {
        void onSuccess(List<Alert> alerts);
        void onError(Exception error);
    }

    public interface InviteLinkCallback {
        void onSuccess(String inviteLink);
        void onError(Exception error);
    }

    public interface BroadcastAlertCallback {
        void onSuccess(Alert alert);
        void onError(Exception error);
    }

    private CommunityService(@NonNull Context context) {
        this(context.getApplicationContext(), ClawPhonesAPI.getOkHttpClient());
    }

    private CommunityService(@NonNull Context context, @NonNull OkHttpClient okHttpClient) {
        this.appContext = context;
        this.httpClient = okHttpClient;
        this.backgroundExecutor = Executors.newSingleThreadExecutor();
        this.communityCache = new CommunityCache(context);
    }

    @NonNull
    public static CommunityService getInstance(@NonNull Context context) {
        if (instance == null) {
            synchronized (CommunityService.class) {
                if (instance == null) {
                    instance = new CommunityService(context);
                }
            }
        }
        return instance;
    }

    public void createCommunity(
        @NonNull String name,
        @Nullable String description,
        double centerLat,
        double centerLon,
        @NonNull List<String> h3Cells,
        @NonNull CreateCommunityCallback callback
    ) {
        backgroundExecutor.execute(() -> {
            try {
                Community community = createCommunitySync(name, description, centerLat, centerLon, h3Cells);
                callback.onSuccess(community);
            } catch (Exception e) {
                callback.onError(e);
            }
        });
    }

    public Community createCommunitySync(
        @NonNull String name,
        @Nullable String description,
        double centerLat,
        double centerLon,
        @NonNull List<String> h3Cells
    ) throws IOException, CommunityException, JSONException {
        String token = resolveAuthToken();
        JSONObject body = new JSONObject();
        body.put("name", name);
        if (description != null) {
            body.put("description", description);
        } else {
            body.put("description", JSONObject.NULL);
        }
        body.put("center_lat", centerLat);
        body.put("center_lon", centerLon);

        JSONArray cellsArray = new JSONArray();
        for (String cell : h3Cells) {
            cellsArray.put(cell);
        }
        body.put("h3_cells", cellsArray);

        JSONObject response = executeJson(postRequest("/v1/communities", body, token));
        Community community = Community.fromJson(response);
        communityCache.insert(community);
        return community;
    }

    public void joinCommunity(
        @NonNull String inviteCode,
        @NonNull JoinCommunityCallback callback
    ) {
        backgroundExecutor.execute(() -> {
            try {
                Community community = joinCommunitySync(inviteCode);
                callback.onSuccess(community);
            } catch (Exception e) {
                callback.onError(e);
            }
        });
    }

    public Community joinCommunitySync(@NonNull String inviteCode)
        throws IOException, CommunityException, JSONException {
        String token = resolveAuthToken();
        JSONObject body = new JSONObject();
        body.put("invite_code", inviteCode);

        JSONObject response = executeJson(postRequest("/v1/communities/join", body, token));
        Community community = Community.fromJson(response);
        communityCache.insert(community);
        return community;
    }

    public void leaveCommunity(
        @NonNull String communityId,
        @NonNull LeaveCommunityCallback callback
    ) {
        backgroundExecutor.execute(() -> {
            try {
                leaveCommunitySync(communityId);
                callback.onSuccess();
            } catch (Exception e) {
                callback.onError(e);
            }
        });
    }

    public void leaveCommunitySync(@NonNull String communityId)
        throws IOException, CommunityException {
        String token = resolveAuthToken();
        String url = BASE_URL + "/v1/communities/" + communityId + "/leave";
        Request request = new Request.Builder()
            .url(url)
            .delete()
            .addHeader("Accept", "application/json")
            .addHeader("Authorization", "Bearer " + token)
            .build();
        executeJson(request);
        communityCache.delete(communityId);
    }

    public void fetchMyCommunities(@NonNull FetchCommunitiesCallback callback) {
        backgroundExecutor.execute(() -> {
            try {
                List<Community> communities = fetchMyCommunitiesSync();
                callback.onSuccess(communities);
            } catch (Exception e) {
                callback.onError(e);
            }
        });
    }

    public List<Community> fetchMyCommunitiesSync()
        throws IOException, CommunityException, JSONException {
        String token = resolveAuthToken();
        String url = BASE_URL + "/v1/communities";
        Request request = new Request.Builder()
            .url(url)
            .get()
            .addHeader("Accept", "application/json")
            .addHeader("Authorization", "Bearer " + token)
            .build();
        JSONObject response = executeJson(request);

        List<Community> communities = new ArrayList<>();
        JSONArray array = response.optJSONArray("communities");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null) {
                    try {
                        Community community = Community.fromJson(item);
                        communities.add(community);
                        communityCache.insert(community);
                    } catch (JSONException e) {
                        Logger.logWarn(LOG_TAG, "Failed to parse community: " + e.getMessage());
                    }
                }
            }
        }
        return communities;
    }

    public void fetchCommunityAlerts(
        @NonNull String communityId,
        @Nullable Integer limit,
        @Nullable Integer offset,
        @NonNull FetchAlertsCallback callback
    ) {
        backgroundExecutor.execute(() -> {
            try {
                List<Alert> alerts = fetchCommunityAlertsSync(communityId, limit, offset);
                callback.onSuccess(alerts);
            } catch (Exception e) {
                callback.onError(e);
            }
        });
    }

    public List<Alert> fetchCommunityAlertsSync(
        @NonNull String communityId,
        @Nullable Integer limit,
        @Nullable Integer offset
    ) throws IOException, CommunityException, JSONException {
        String token = resolveAuthToken();
        HttpUrl.Builder urlBuilder = HttpUrl.parse(BASE_URL + "/v1/communities/" + communityId + "/alerts").newBuilder();
        if (limit != null && limit > 0) {
            urlBuilder.addQueryParameter("limit", String.valueOf(limit));
        }
        if (offset != null && offset >= 0) {
            urlBuilder.addQueryParameter("offset", String.valueOf(offset));
        }

        Request request = new Request.Builder()
            .url(urlBuilder.build())
            .get()
            .addHeader("Accept", "application/json")
            .addHeader("Authorization", "Bearer " + token)
            .build();
        JSONObject response = executeJson(request);

        List<Alert> alerts = new ArrayList<>();
        JSONArray array = response.optJSONArray("alerts");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null) {
                    try {
                        alerts.add(Alert.fromJson(item));
                    } catch (JSONException e) {
                        Logger.logWarn(LOG_TAG, "Failed to parse alert: " + e.getMessage());
                    }
                }
            }
        }
        return alerts;
    }

    public void getInviteLink(
        @NonNull String communityId,
        @NonNull InviteLinkCallback callback
    ) {
        backgroundExecutor.execute(() -> {
            try {
                String link = getInviteLinkSync(communityId);
                callback.onSuccess(link);
            } catch (Exception e) {
                callback.onError(e);
            }
        });
    }

    public String getInviteLinkSync(@NonNull String communityId)
        throws IOException, CommunityException, JSONException {
        String token = resolveAuthToken();
        String url = BASE_URL + "/v1/communities/" + communityId + "/invite-link";
        Request request = new Request.Builder()
            .url(url)
            .get()
            .addHeader("Accept", "application/json")
            .addHeader("Authorization", "Bearer " + token)
            .build();
        JSONObject response = executeJson(request);
        return response.optString("invite_link", "");
    }

    public void broadcastAlert(
        @NonNull String communityId,
        @NonNull String message,
        double lat,
        double lon,
        @NonNull BroadcastAlertCallback callback
    ) {
        backgroundExecutor.execute(() -> {
            try {
                Alert alert = broadcastAlertSync(communityId, message, lat, lon);
                callback.onSuccess(alert);
            } catch (Exception e) {
                callback.onError(e);
            }
        });
    }

    public Alert broadcastAlertSync(
        @NonNull String communityId,
        @NonNull String message,
        double lat,
        double lon
    ) throws IOException, CommunityException, JSONException {
        String token = resolveAuthToken();
        JSONObject body = new JSONObject();
        body.put("message", message);
        body.put("lat", lat);
        body.put("lon", lon);

        String url = BASE_URL + "/v1/communities/" + communityId + "/broadcast";
        JSONObject response = executeJson(postRequest(url, body, token));
        return Alert.fromJson(response);
    }

    public List<Community> getCachedCommunities() {
        return communityCache.getAll();
    }

    @Nullable
    public Community getCachedCommunity(@NonNull String communityId) {
        return communityCache.get(communityId);
    }

    public void clearCache() {
        communityCache.clear();
    }

    private String resolveAuthToken() throws CommunityException {
        String token = ClawPhonesAPI.getToken(appContext);
        if (token == null || token.trim().isEmpty()) {
            throw new CommunityException(401, "missing bearer token");
        }
        return token.trim();
    }

    @NonNull
    private Request postRequest(@NonNull String path, @NonNull JSONObject body, @NonNull String token) {
        String url = BASE_URL + path;
        return new Request.Builder()
            .url(url)
            .post(RequestBody.create(JSON_MEDIA_TYPE, body.toString()))
            .addHeader("Accept", "application/json")
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer " + token)
            .build();
    }

    @NonNull
    private JSONObject executeJson(@NonNull Request request) throws IOException, CommunityException, JSONException {
        Response response = null;
        try {
            response = httpClient.newCall(request).execute();
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new CommunityException(response.code(), body.isEmpty() ? response.message() : body);
            }
            if (body.trim().isEmpty()) {
                return new JSONObject();
            }
            return new JSONObject(body);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static final class CommunityCache {
        private static final String DB_NAME = "clawphones_community.db";
        private static final int DB_VERSION = 1;
        private static final String TABLE_COMMUNITIES = "communities";

        private static final String COL_ID = "id";
        private static final String COL_NAME = "name";
        private static final String COL_DESCRIPTION = "description";
        private static final String COL_CENTER_LAT = "center_lat";
        private static final String COL_CENTER_LON = "center_lon";
        private static final String COL_H3_CELLS = "h3_cells";
        private static final String COL_MEMBER_COUNT = "member_count";
        private static final String COL_INVITE_CODE = "invite_code";
        private static final String COL_CREATED_AT = "created_at";
        private static final String COL_UPDATED_AT = "updated_at";

        private final SQLiteOpenHelper helper;

        CommunityCache(@NonNull Context context) {
            helper = new SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
                @Override
                public void onCreate(SQLiteDatabase db) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_COMMUNITIES + " ("
                        + COL_ID + " TEXT PRIMARY KEY, "
                        + COL_NAME + " TEXT NOT NULL, "
                        + COL_DESCRIPTION + " TEXT, "
                        + COL_CENTER_LAT + " REAL NOT NULL, "
                        + COL_CENTER_LON + " REAL NOT NULL, "
                        + COL_H3_CELLS + " TEXT, "
                        + COL_MEMBER_COUNT + " INTEGER NOT NULL DEFAULT 0, "
                        + COL_INVITE_CODE + " TEXT, "
                        + COL_CREATED_AT + " INTEGER NOT NULL, "
                        + COL_UPDATED_AT + " INTEGER NOT NULL"
                        + ")");
                }

                @Override
                public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
                    if (oldVersion < 1) onCreate(db);
                }
            };
        }

        void insert(@NonNull Community community) {
            SQLiteDatabase db = helper.getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put(COL_ID, community.id);
            values.put(COL_NAME, community.name);
            values.put(COL_DESCRIPTION, community.description);
            values.put(COL_CENTER_LAT, community.centerLat);
            values.put(COL_CENTER_LON, community.centerLon);
            values.put(COL_H3_CELLS, serializeH3Cells(community.h3Cells));
            values.put(COL_MEMBER_COUNT, community.memberCount);
            values.put(COL_INVITE_CODE, community.inviteCode);
            values.put(COL_CREATED_AT, community.createdAt);
            values.put(COL_UPDATED_AT, System.currentTimeMillis());
            db.insertWithOnConflict(TABLE_COMMUNITIES, null, values, SQLiteDatabase.CONFLICT_REPLACE);
        }

        void delete(@NonNull String communityId) {
            SQLiteDatabase db = helper.getWritableDatabase();
            db.delete(TABLE_COMMUNITIES, COL_ID + " = ?", new String[]{communityId});
        }

        void clear() {
            SQLiteDatabase db = helper.getWritableDatabase();
            db.delete(TABLE_COMMUNITIES, null, null);
        }

        @Nullable
        Community get(@NonNull String communityId) {
            SQLiteDatabase db = helper.getReadableDatabase();
            Cursor c = null;
            try {
                c = db.query(
                    TABLE_COMMUNITIES,
                    null,
                    COL_ID + " = ?",
                    new String[]{communityId},
                    null,
                    null,
                    null
                );
                if (!c.moveToFirst()) return null;
                return fromCursor(c);
            } finally {
                if (c != null) c.close();
            }
        }

        @NonNull
        List<Community> getAll() {
            SQLiteDatabase db = helper.getReadableDatabase();
            Cursor c = null;
            try {
                c = db.query(
                    TABLE_COMMUNITIES,
                    null,
                    null,
                    null,
                    null,
                    null,
                    COL_NAME + " ASC"
                );
                List<Community> list = new ArrayList<>();
                while (c.moveToNext()) {
                    Community community = fromCursor(c);
                    if (community != null) {
                        list.add(community);
                    }
                }
                return list;
            } finally {
                if (c != null) c.close();
            }
        }

        @Nullable
        private Community fromCursor(@NonNull Cursor c) {
            try {
                String id = c.getString(c.getColumnIndexOrThrow(COL_ID));
                String name = c.getString(c.getColumnIndexOrThrow(COL_NAME));
                String description = c.getString(c.getColumnIndexOrThrow(COL_DESCRIPTION));
                double centerLat = c.getDouble(c.getColumnIndexOrThrow(COL_CENTER_LAT));
                double centerLon = c.getDouble(c.getColumnIndexOrThrow(COL_CENTER_LON));
                String h3CellsJson = c.getString(c.getColumnIndexOrThrow(COL_H3_CELLS));
                int memberCount = c.getInt(c.getColumnIndexOrThrow(COL_MEMBER_COUNT));
                String inviteCode = c.getString(c.getColumnIndexOrThrow(COL_INVITE_CODE));
                long createdAt = c.getLong(c.getColumnIndexOrThrow(COL_CREATED_AT));

                List<String> h3Cells = parseH3Cells(h3CellsJson);
                return new Community(id, name, description, centerLat, centerLon, h3Cells, memberCount, inviteCode, createdAt);
            } catch (Exception e) {
                Logger.logWarn(LOG_TAG, "Failed to build community from cursor: " + e.getMessage());
                return null;
            }
        }

        @Nullable
        private String serializeH3Cells(@NonNull List<String> h3Cells) {
            if (h3Cells.isEmpty()) return null;
            try {
                JSONArray array = new JSONArray();
                for (String cell : h3Cells) {
                    array.put(cell);
                }
                return array.toString();
            } catch (Exception e) {
                return null;
            }
        }

        @NonNull
        private List<String> parseH3Cells(@Nullable String json) {
            List<String> list = new ArrayList<>();
            if (json == null || json.trim().isEmpty()) {
                return list;
            }
            try {
                JSONArray array = new JSONArray(json);
                for (int i = 0; i < array.length(); i++) {
                    String cell = array.optString(i);
                    if (!TextUtils.isEmpty(cell)) {
                        list.add(cell);
                    }
                }
            } catch (JSONException e) {
                Logger.logWarn(LOG_TAG, "Failed to parse h3_cells: " + e.getMessage());
            }
            return list;
        }
    }
}
