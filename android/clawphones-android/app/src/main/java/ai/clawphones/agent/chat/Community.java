package ai.clawphones.agent.chat;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Community {
    @NonNull public final String id;
    @NonNull public final String name;
    @Nullable public final String description;
    public final double centerLat;
    public final double centerLon;
    @NonNull public final List<String> h3Cells;
    public final int memberCount;
    @Nullable public final String inviteCode;
    public final long createdAt;

    public Community(
        @NonNull String id,
        @NonNull String name,
        @Nullable String description,
        double centerLat,
        double centerLon,
        @NonNull List<String> h3Cells,
        int memberCount,
        @Nullable String inviteCode,
        long createdAt
    ) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.centerLat = centerLat;
        this.centerLon = centerLon;
        this.h3Cells = h3Cells != null ? Collections.unmodifiableList(new ArrayList<>(h3Cells)) : Collections.emptyList();
        this.memberCount = memberCount;
        this.inviteCode = inviteCode;
        this.createdAt = createdAt;
    }

    @NonNull
    public static Community fromJson(@NonNull JSONObject json) throws JSONException {
        String id = json.optString("id", "");
        if (TextUtils.isEmpty(id)) {
            throw new JSONException("Missing id field");
        }

        String name = json.optString("name", "");
        if (TextUtils.isEmpty(name)) {
            throw new JSONException("Missing name field");
        }

        String description = json.optString("description", null);
        if (json.isNull("description") || TextUtils.isEmpty(description)) {
            description = null;
        }

        double centerLat = json.optDouble("center_lat", 0.0);
        double centerLon = json.optDouble("center_lon", 0.0);
        int memberCount = json.optInt("member_count", 0);
        long createdAt = json.optLong("created_at", 0L);

        List<String> h3Cells = new ArrayList<>();
        JSONArray cellsArray = json.optJSONArray("h3_cells");
        if (cellsArray != null) {
            for (int i = 0; i < cellsArray.length(); i++) {
                String cell = cellsArray.optString(i, null);
                if (!TextUtils.isEmpty(cell)) {
                    h3Cells.add(cell);
                }
            }
        }

        String inviteCode = json.optString("invite_code", null);
        if (json.isNull("invite_code") || TextUtils.isEmpty(inviteCode)) {
            inviteCode = null;
        }

        return new Community(id, name, description, centerLat, centerLon, h3Cells, memberCount, inviteCode, createdAt);
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);

        if (description != null) {
            json.put("description", description);
        } else {
            json.put("description", JSONObject.NULL);
        }

        json.put("center_lat", centerLat);
        json.put("center_lon", centerLon);
        json.put("member_count", memberCount);
        json.put("created_at", createdAt);

        JSONArray cellsArray = new JSONArray();
        for (String cell : h3Cells) {
            cellsArray.put(cell);
        }
        json.put("h3_cells", cellsArray);

        if (inviteCode != null) {
            json.put("invite_code", inviteCode);
        } else {
            json.put("invite_code", JSONObject.NULL);
        }

        return json;
    }
}
