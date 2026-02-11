package ai.clawphones.agent.chat;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;

import com.termux.R;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class AlertDetailActivity extends AppCompatActivity {

    public static final String EXTRA_EVENT_ID = "alert_event_id";

    private AlertEventStore mStore;
    @Nullable private AlertEventStore.AlertEvent mEvent;

    private BoundingBoxImageView mImageView;
    private TextView mTypeView;
    private TextView mConfidenceView;
    private TextView mTimeView;
    private TextView mLocationView;

    private final SimpleDateFormat mDateTimeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_alert_detail);

        mStore = new AlertEventStore(getApplicationContext());

        Toolbar toolbar = findViewById(R.id.alert_detail_toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            toolbar.setNavigationOnClickListener(v -> finish());
        }

        mImageView = findViewById(R.id.alert_detail_image);
        mTypeView = findViewById(R.id.alert_detail_type);
        mConfidenceView = findViewById(R.id.alert_detail_confidence);
        mTimeView = findViewById(R.id.alert_detail_time);
        mLocationView = findViewById(R.id.alert_detail_location);
        Button shareButton = findViewById(R.id.alert_detail_share);
        Button deleteButton = findViewById(R.id.alert_detail_delete);

        String eventId = getIntent().getStringExtra(EXTRA_EVENT_ID);
        if (TextUtils.isEmpty(eventId)) {
            finish();
            return;
        }

        mEvent = mStore.getEventById(eventId);
        if (mEvent == null) {
            Toast.makeText(this, R.string.alert_not_found, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        renderEvent(mEvent);

        shareButton.setOnClickListener(v -> shareEvent(mEvent));
        deleteButton.setOnClickListener(v -> confirmDelete(mEvent));
    }

    private void renderEvent(AlertEventStore.AlertEvent event) {
        if (event.thumbnailData != null && event.thumbnailData.length > 0) {
            Bitmap bitmap = BitmapFactory.decodeByteArray(event.thumbnailData, 0, event.thumbnailData.length);
            if (bitmap != null) {
                mImageView.setImageBitmap(bitmap);
            } else {
                mImageView.setImageResource(android.R.drawable.ic_menu_report_image);
            }
        } else {
            mImageView.setImageResource(android.R.drawable.ic_menu_report_image);
        }

        RectF box = event.boundingBox == null ? null : new RectF(event.boundingBox);
        mImageView.setBoundingBox(box);

        mTypeView.setText(capitalizeType(event.normalizedType()));
        mConfidenceView.setText(getString(R.string.alert_confidence_format, confidencePercent(event.confidence)));
        mTimeView.setText(mDateTimeFormat.format(new Date(event.timestampMs)));
        mLocationView.setText(
            String.format(Locale.US, "%.6f, %.6f", event.latitude, event.longitude)
        );
    }

    private void shareEvent(@Nullable AlertEventStore.AlertEvent event) {
        if (event == null) return;

        String text = getString(
            R.string.alert_share_text,
            capitalizeType(event.normalizedType()),
            confidencePercent(event.confidence),
            mDateTimeFormat.format(new Date(event.timestampMs)),
            String.format(Locale.US, "%.6f, %.6f", event.latitude, event.longitude)
        );

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.putExtra(Intent.EXTRA_TEXT, text);

        Uri imageUri = buildShareImageUri(event);
        if (imageUri != null) {
            intent.setType("image/jpeg");
            intent.putExtra(Intent.EXTRA_STREAM, imageUri);
        } else {
            intent.setType("text/plain");
        }

        startActivity(Intent.createChooser(intent, getString(R.string.alert_share)));
    }

    @Nullable
    private Uri buildShareImageUri(AlertEventStore.AlertEvent event) {
        if (event.thumbnailData == null || event.thumbnailData.length == 0) return null;
        try {
            File exportDir = new File(getFilesDir(), "exports");
            if (!exportDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                exportDir.mkdirs();
            }
            File shareFile = new File(exportDir, "alert_share_" + event.id + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(shareFile)) {
                fos.write(event.thumbnailData);
                fos.flush();
            }
            return FileProvider.getUriForFile(
                this,
                getPackageName() + ".export.fileprovider",
                shareFile
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private void confirmDelete(@Nullable AlertEventStore.AlertEvent event) {
        if (event == null) return;
        new AlertDialog.Builder(this)
            .setMessage(getString(R.string.alert_delete_confirm))
            .setNegativeButton(R.string.alert_cancel, null)
            .setPositiveButton(R.string.alert_delete, (dialog, which) -> {
                mStore.deleteEvent(event.id);
                Toast.makeText(this, R.string.alert_deleted, Toast.LENGTH_SHORT).show();
                finish();
            })
            .show();
    }

    private int confidencePercent(float confidence) {
        if (confidence <= 1f) {
            return Math.max(0, Math.min(100, Math.round(confidence * 100f)));
        }
        return Math.max(0, Math.min(100, Math.round(confidence)));
    }

    private String capitalizeType(String value) {
        if (TextUtils.isEmpty(value)) return "Unknown";
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
