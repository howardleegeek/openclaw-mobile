package ai.clawphones.agent.chat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.PolygonOptions;
import com.termux.R;
import com.uber.h3core.H3Core;
import com.uber.h3core.util.GeoCoord;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ai.clawphones.agent.CrashReporter;

public class CoverageMapActivity extends AppCompatActivity implements OnMapReadyCallback {

    private static final int REQUEST_LOCATION_PERMISSION = 8123;

    private MapView mMapView;
    private ProgressBar mLoading;
    @Nullable private GoogleMap mMap;
    @Nullable private LatLng mCurrentUserLocation;

    private ExecutorService mExecutor;
    @Nullable private H3Core mH3;

    private final ArrayList<CoverageCell> mCells = new ArrayList<>();
    private final ArrayList<CoverageNode> mNodes = new ArrayList<>();
    private boolean mHasCameraFocus = false;

    private enum CoverageStatus {
        RECENT,
        STALE,
        EMPTY
    }

    private static final class CoverageCell {
        final String id;
        final CoverageStatus status;
        final ArrayList<LatLng> points;

        CoverageCell(String id, CoverageStatus status, ArrayList<LatLng> points) {
            this.id = id;
            this.status = status;
            this.points = points;
        }
    }

    private static final class CoverageNode {
        final String id;
        final String title;
        final boolean isSelf;
        final LatLng coordinate;

        CoverageNode(String id, String title, boolean isSelf, LatLng coordinate) {
            this.id = id;
            this.title = title;
            this.isSelf = isSelf;
            this.coordinate = coordinate;
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_coverage_map);

        Toolbar toolbar = findViewById(R.id.coverage_toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert);
            toolbar.setNavigationOnClickListener(v -> finish());
        }

        mLoading = findViewById(R.id.coverage_loading);
        mMapView = findViewById(R.id.coverage_map_view);
        mMapView.onCreate(savedInstanceState);
        mMapView.getMapAsync(this);

        mExecutor = Executors.newSingleThreadExecutor();
        try {
            mH3 = H3Core.newInstance();
        } catch (Exception e) {
            mH3 = null;
        }

        fetchCoverageData();
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        googleMap.getUiSettings().setZoomControlsEnabled(true);
        googleMap.getUiSettings().setCompassEnabled(true);
        googleMap.getUiSettings().setMapToolbarEnabled(false);

        enableUserLocationIfPermitted();
        renderCoverage();
    }

    private void fetchCoverageData() {
        showLoading(true);
        ExecutorService executor = mExecutor;
        if (executor == null || executor.isShutdown()) {
            return;
        }

        executor.execute(() -> {
            try {
                Object payload = ClawPhonesAPI.getWorldCellsRaw(this, 24, 9);
                ArrayList<CoverageCell> cells = parseCells(payload);
                ArrayList<CoverageNode> nodes = parseNodes(payload);

                runOnUiThread(() -> {
                    mCells.clear();
                    mCells.addAll(cells);
                    mNodes.clear();
                    mNodes.addAll(nodes);
                    renderCoverage();
                    showLoading(false);
                });
            } catch (Exception e) {
                CrashReporter.reportNonFatal(this, e, "coverage_map_fetch");
                runOnUiThread(() -> {
                    showLoading(false);
                    Toast.makeText(this, getString(R.string.coverage_map_load_failed), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private ArrayList<CoverageCell> parseCells(Object payload) {
        ArrayList<CoverageCell> output = new ArrayList<>();
        JSONArray candidates = extractArray(payload,
            "cells", "items", "data", "coverage", "world_cells");
        if (candidates == null) return output;

        for (int i = 0; i < candidates.length(); i++) {
            JSONObject cell = candidates.optJSONObject(i);
            if (cell == null) continue;

            String cellId = firstString(cell, "cell_id", "cellId", "cell", "h3", "h3_index", "id");
            if (TextUtils.isEmpty(cellId)) {
                cellId = "cell-" + i;
            }

            CoverageStatus status = resolveStatus(cell);
            ArrayList<LatLng> points = parsePolygonPoints(
                firstAny(cell, "boundary", "polygon", "vertices", "coordinates")
            );

            if (points.size() < 3 && !TextUtils.isEmpty(cellId)) {
                points = polygonFromH3(cellId);
            }

            if (points.size() < 3) {
                LatLng center = parseCoordinate(firstAny(cell, "center", "centroid", "location", "position"));
                if (center == null) center = parseCoordinate(cell);
                if (center != null) {
                    points = approximateHexagon(center, 100.0);
                }
            }

            if (points.size() < 3) continue;
            output.add(new CoverageCell(cellId, status, points));
        }

        return output;
    }

    private ArrayList<CoverageNode> parseNodes(Object payload) {
        ArrayList<CoverageNode> output = new ArrayList<>();
        JSONArray candidates = extractArray(payload,
            "nodes", "neighbors", "neighbours", "peers", "devices", "data");
        if (candidates == null) return output;

        for (int i = 0; i < candidates.length(); i++) {
            JSONObject node = candidates.optJSONObject(i);
            if (node == null) continue;

            LatLng coordinate = parseCoordinate(firstAny(node, "location", "position", "center", "coordinates"));
            if (coordinate == null) {
                coordinate = parseCoordinate(node);
            }
            if (coordinate == null) continue;

            String id = firstString(node, "id", "node_id", "device_id", "peer_id", "name");
            if (TextUtils.isEmpty(id)) {
                id = "node-" + i;
            }

            boolean isSelf = firstBool(node, "is_self", "self", "mine", "own");
            String title = firstString(node, "label", "title", "name", "node_name");
            if (TextUtils.isEmpty(title)) {
                title = isSelf ? getString(R.string.coverage_map_self_node) : getString(R.string.coverage_map_neighbor_node);
            }

            output.add(new CoverageNode(id, title, isSelf, coordinate));
        }

        return output;
    }

    private CoverageStatus resolveStatus(JSONObject cell) {
        String rawStatus = firstString(cell, "status", "coverage_status", "coverage", "state");
        if (!TextUtils.isEmpty(rawStatus)) {
            String normalized = rawStatus.trim().toLowerCase(Locale.ROOT);
            if (normalized.contains("fresh") || normalized.contains("recent") || normalized.contains("active") || normalized.contains("hot")) {
                return CoverageStatus.RECENT;
            }
            if (normalized.contains("stale") || normalized.contains("old") || normalized.contains("warm") || normalized.contains("aging")) {
                return CoverageStatus.STALE;
            }
            if (normalized.contains("empty") || normalized.contains("none") || normalized.contains("cold") || normalized.contains("missing")) {
                return CoverageStatus.EMPTY;
            }
        }

        long lastSeen = parseEpochSeconds(firstAny(cell,
            "last_seen_at", "last_seen", "seen_at", "updated_at", "timestamp", "ts"));
        if (lastSeen <= 0L) return CoverageStatus.EMPTY;

        long ageSeconds = Math.max(0L, Instant.now().getEpochSecond() - lastSeen);
        if (ageSeconds <= 3600L) return CoverageStatus.RECENT;
        if (ageSeconds <= 24L * 3600L) return CoverageStatus.STALE;
        return CoverageStatus.EMPTY;
    }

    private long parseEpochSeconds(Object value) {
        if (value instanceof Number) {
            long raw = ((Number) value).longValue();
            return raw > 2_000_000_000L ? raw / 1000L : raw;
        }

        if (value instanceof String) {
            String text = ((String) value).trim();
            if (text.isEmpty()) return -1L;
            try {
                long numeric = Long.parseLong(text);
                return numeric > 2_000_000_000L ? numeric / 1000L : numeric;
            } catch (Exception ignored) {
            }
            try {
                return Instant.parse(text).getEpochSecond();
            } catch (Exception ignored) {
            }
        }

        return -1L;
    }

    private ArrayList<LatLng> polygonFromH3(String cellId) {
        ArrayList<LatLng> points = new ArrayList<>();
        if (mH3 == null || TextUtils.isEmpty(cellId)) return points;

        try {
            List<GeoCoord> boundary = mH3.h3ToGeoBoundary(cellId);
            if (boundary == null) return points;
            for (GeoCoord coord : boundary) {
                points.add(new LatLng(coord.lat, coord.lng));
            }
        } catch (Exception ignored) {
        }

        return points;
    }

    private ArrayList<LatLng> parsePolygonPoints(Object source) {
        ArrayList<LatLng> points = new ArrayList<>();
        if (source == null) return points;

        if (source instanceof JSONObject) {
            return parsePolygonPoints(((JSONObject) source).opt("coordinates"));
        }

        if (!(source instanceof JSONArray)) {
            return points;
        }

        JSONArray array = (JSONArray) source;
        if (array.length() == 0) return points;

        Object first = array.opt(0);
        if (first instanceof JSONArray) {
            JSONArray firstArray = (JSONArray) first;
            if (firstArray.length() > 0 && firstArray.opt(0) instanceof JSONArray) {
                return parsePairArray(firstArray);
            }
            return parsePairArray(array);
        }

        if (first instanceof JSONObject) {
            for (int i = 0; i < array.length(); i++) {
                LatLng coordinate = parseCoordinate(array.optJSONObject(i));
                if (coordinate != null) points.add(coordinate);
            }
            return stripClosingPoint(points);
        }

        return points;
    }

    private ArrayList<LatLng> parsePairArray(JSONArray pairs) {
        ArrayList<LatLng> points = new ArrayList<>();
        for (int i = 0; i < pairs.length(); i++) {
            JSONArray pair = pairs.optJSONArray(i);
            if (pair == null || pair.length() < 2) continue;
            double a = pair.optDouble(0, Double.NaN);
            double b = pair.optDouble(1, Double.NaN);
            if (Double.isNaN(a) || Double.isNaN(b)) continue;
            points.add(toCoordinate(a, b));
        }
        return stripClosingPoint(points);
    }

    private ArrayList<LatLng> stripClosingPoint(ArrayList<LatLng> points) {
        if (points.size() <= 3) return points;
        LatLng first = points.get(0);
        LatLng last = points.get(points.size() - 1);
        if (distanceMeters(first, last) < 0.5) {
            points.remove(points.size() - 1);
        }
        return points;
    }

    private LatLng toCoordinate(double a, double b) {
        if (Math.abs(a) <= 90.0 && Math.abs(b) <= 180.0) {
            return new LatLng(a, b);
        }
        return new LatLng(b, a);
    }

    private ArrayList<LatLng> approximateHexagon(LatLng center, double radiusMeters) {
        ArrayList<LatLng> points = new ArrayList<>(6);

        final double earthRadius = 6_378_137.0;
        final double angularDistance = radiusMeters / earthRadius;
        final double latRad = Math.toRadians(center.latitude);
        final double lonRad = Math.toRadians(center.longitude);

        for (int i = 0; i < 6; i++) {
            double bearing = Math.toRadians(i * 60.0);
            double sinLat = Math.sin(latRad) * Math.cos(angularDistance)
                + Math.cos(latRad) * Math.sin(angularDistance) * Math.cos(bearing);
            double newLat = Math.asin(sinLat);
            double y = Math.sin(bearing) * Math.sin(angularDistance) * Math.cos(latRad);
            double x = Math.cos(angularDistance) - Math.sin(latRad) * Math.sin(newLat);
            double newLon = lonRad + Math.atan2(y, x);

            points.add(new LatLng(Math.toDegrees(newLat), Math.toDegrees(newLon)));
        }

        return points;
    }

    @Nullable
    private LatLng parseCoordinate(@Nullable Object source) {
        if (source == null) return null;

        if (source instanceof JSONObject) {
            JSONObject obj = (JSONObject) source;
            if (obj.has("lat") || obj.has("latitude")) {
                double lat = readDouble(obj, "lat", "latitude");
                double lng = readDouble(obj, "lng", "lon", "longitude");
                if (!Double.isNaN(lat) && !Double.isNaN(lng)) {
                    return new LatLng(lat, lng);
                }
            }
            if (obj.has("coordinates")) {
                return parseCoordinate(obj.opt("coordinates"));
            }
            return null;
        }

        if (source instanceof JSONArray) {
            JSONArray arr = (JSONArray) source;
            if (arr.length() < 2) return null;
            double a = arr.optDouble(0, Double.NaN);
            double b = arr.optDouble(1, Double.NaN);
            if (Double.isNaN(a) || Double.isNaN(b)) return null;
            return toCoordinate(a, b);
        }

        return null;
    }

    private double readDouble(JSONObject obj, String... keys) {
        for (String key : keys) {
            if (!obj.has(key)) continue;
            Object value = obj.opt(key);
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            }
            if (value instanceof String) {
                try {
                    return Double.parseDouble(((String) value).trim());
                } catch (Exception ignored) {
                }
            }
        }
        return Double.NaN;
    }

    @Nullable
    private JSONArray extractArray(Object payload, String... keys) {
        if (payload instanceof JSONArray) {
            return (JSONArray) payload;
        }
        if (!(payload instanceof JSONObject)) {
            return null;
        }

        JSONObject root = (JSONObject) payload;
        for (String key : keys) {
            if (!root.has(key)) continue;
            Object value = root.opt(key);
            JSONArray direct = asJSONArray(value);
            if (direct != null) return direct;

            if (value instanceof JSONObject) {
                JSONObject nested = (JSONObject) value;
                JSONArray nestedArray = extractArray(nested,
                    "cells", "items", "nodes", "neighbors", "neighbours", "peers");
                if (nestedArray != null) return nestedArray;
            }
        }
        return null;
    }

    @Nullable
    private JSONArray asJSONArray(Object value) {
        return value instanceof JSONArray ? (JSONArray) value : null;
    }

    @Nullable
    private Object firstAny(JSONObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key)) {
                return obj.opt(key);
            }
        }
        return null;
    }

    @Nullable
    private String firstString(JSONObject obj, String... keys) {
        for (String key : keys) {
            if (!obj.has(key)) continue;
            Object value = obj.opt(key);
            if (value == null) continue;
            String text = String.valueOf(value).trim();
            if (!text.isEmpty() && !"null".equalsIgnoreCase(text)) {
                return text;
            }
        }
        return null;
    }

    private boolean firstBool(JSONObject obj, String... keys) {
        for (String key : keys) {
            if (!obj.has(key)) continue;
            Object value = obj.opt(key);
            if (value instanceof Boolean) return (Boolean) value;
            if (value instanceof Number) return ((Number) value).intValue() != 0;
            if (value instanceof String) {
                String text = ((String) value).trim().toLowerCase(Locale.ROOT);
                if (Arrays.asList("true", "1", "yes", "y").contains(text)) return true;
                if (Arrays.asList("false", "0", "no", "n").contains(text)) return false;
            }
        }
        return false;
    }

    private float hueForNode(CoverageNode node) {
        return node.isSelf ? BitmapDescriptorFactory.HUE_AZURE : BitmapDescriptorFactory.HUE_ORANGE;
    }

    private int fillColorFor(CoverageStatus status) {
        switch (status) {
            case RECENT:
                return Color.argb(90, 67, 160, 71);
            case STALE:
                return Color.argb(88, 251, 192, 45);
            case EMPTY:
            default:
                return Color.argb(78, 120, 120, 120);
        }
    }

    private int strokeColorFor(CoverageStatus status) {
        switch (status) {
            case RECENT:
                return Color.argb(230, 56, 142, 60);
            case STALE:
                return Color.argb(230, 245, 181, 0);
            case EMPTY:
            default:
                return Color.argb(220, 99, 99, 99);
        }
    }

    private void renderCoverage() {
        GoogleMap map = mMap;
        if (map == null) return;

        map.clear();

        LatLngBounds.Builder boundsBuilder = new LatLngBounds.Builder();
        boolean hasBoundsPoint = false;

        for (CoverageCell cell : mCells) {
            if (cell.points.size() < 3) continue;
            PolygonOptions polygon = new PolygonOptions()
                .addAll(cell.points)
                .strokeWidth(2f)
                .strokeColor(strokeColorFor(cell.status))
                .fillColor(fillColorFor(cell.status));
            map.addPolygon(polygon);
            for (LatLng point : cell.points) {
                boundsBuilder.include(point);
                hasBoundsPoint = true;
            }
        }

        for (CoverageNode node : mNodes) {
            map.addMarker(new MarkerOptions()
                .position(node.coordinate)
                .title(node.title)
                .icon(BitmapDescriptorFactory.defaultMarker(hueForNode(node))));
            boundsBuilder.include(node.coordinate);
            hasBoundsPoint = true;
        }

        if (mCurrentUserLocation != null) {
            map.addMarker(new MarkerOptions()
                .position(mCurrentUserLocation)
                .title(getString(R.string.coverage_map_self_node))
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
            boundsBuilder.include(mCurrentUserLocation);
            hasBoundsPoint = true;
        }

        if (!mHasCameraFocus) {
            if (hasBoundsPoint) {
                try {
                    map.moveCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 120));
                    mHasCameraFocus = true;
                    return;
                } catch (Exception ignored) {
                }
            }

            if (mCurrentUserLocation != null) {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(mCurrentUserLocation, 15f));
                mHasCameraFocus = true;
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void enableUserLocationIfPermitted() {
        GoogleMap map = mMap;
        if (map == null) return;

        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                REQUEST_LOCATION_PERMISSION
            );
            return;
        }

        try {
            map.setMyLocationEnabled(true);
        } catch (Exception ignored) {
        }
        updateLastKnownLocation();
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void updateLastKnownLocation() {
        if (!hasLocationPermission()) return;

        LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null) return;

        Location best = null;
        for (String provider : Arrays.asList(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )) {
            try {
                Location location = locationManager.getLastKnownLocation(provider);
                if (location == null) continue;
                if (best == null || location.getAccuracy() < best.getAccuracy()) {
                    best = location;
                }
            } catch (SecurityException ignored) {
            }
        }

        if (best != null) {
            mCurrentUserLocation = new LatLng(best.getLatitude(), best.getLongitude());
            renderCoverage();
        }
    }

    private double distanceMeters(LatLng a, LatLng b) {
        float[] results = new float[1];
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, results);
        return results[0];
    }

    private void showLoading(boolean loading) {
        if (mLoading == null) return;
        mLoading.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_LOCATION_PERMISSION) return;

        boolean granted = false;
        for (int result : grantResults) {
            if (result == PackageManager.PERMISSION_GRANTED) {
                granted = true;
                break;
            }
        }

        if (granted) {
            enableUserLocationIfPermitted();
        } else {
            Toast.makeText(this, getString(R.string.coverage_map_permission_denied), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mMapView.onResume();
    }

    @Override
    protected void onStart() {
        super.onStart();
        mMapView.onStart();
    }

    @Override
    protected void onStop() {
        mMapView.onStop();
        super.onStop();
    }

    @Override
    protected void onPause() {
        mMapView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mMapView.onDestroy();
        if (mExecutor != null) {
            mExecutor.shutdownNow();
            mExecutor = null;
        }
        super.onDestroy();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mMapView.onLowMemory();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        mMapView.onSaveInstanceState(outState);
    }
}
