package ai.clawphones.agent.chat;

import android.Manifest;
import android.app.Activity;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.ContentResolver;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Html;
import android.text.Spannable;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.QuoteSpan;
import android.util.LruCache;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;

import ai.clawphones.agent.CrashReporter;
import ai.clawphones.agent.analytics.AnalyticsManager;

/**
 * In-app AI chat UI backed by ClawPhones API.
 *
 * Handles: conversation create/resume, message send/receive, 401 auto-logout,
 * Markdown rendering, and graceful lifecycle management.
 */
public class ChatActivity extends AppCompatActivity {

    private RecyclerView mRecycler;
    private EditText mInput;
    private TextView mSpeechStatus;
    private TextView mAttachmentPreview;
    private ImageButton mAttach;
    private ImageButton mSend;
    private ImageButton mMic;
    private View mMicPulse;
    private ProgressBar mSendProgress;

    private final ArrayList<ChatMessage> mMessages = new ArrayList<>();
    private final HashMap<Long, Integer> mQueuedMessageIndexes = new HashMap<>();
    private ChatAdapter mAdapter;
    private MessageQueue mMessageQueue;
    private ConversationCache mCache;

    private ExecutorService mExecutor;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean mDestroyed = false;
    private boolean mBusy = false;
    private long mLastUpdateMs = 0L;
    @Nullable private Runnable mPendingUpdate = null;
    private static final long UPDATE_THROTTLE_MS = 50L;
    private static final long SPEECH_DONE_RESET_MS = 1_200L;
    private static final int MAX_QUEUE_RETRY = 3;
    private static final int REQUEST_RECORD_AUDIO = 7021;
    private static final int REQUEST_CAMERA_PERMISSION = 7022;
    private static final int REQUEST_PICK_IMAGE = 7101;
    private static final int REQUEST_CAPTURE_IMAGE = 7102;
    private static final int REQUEST_PICK_FILE = 7103;
    private static final int MENU_ITEM_COVERAGE_MAP = 9101;
    private static final int MENU_ITEM_LOGOUT = 9102;
    private static final int MESSAGE_PAGE_SIZE = 80;
    private static final int PAGINATION_PREFETCH_TRIGGER = 6;
    private static final int MAX_PENDING_ATTACHMENTS = 3;
    private static final int MAX_IMAGE_SIZE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_FILE_SIZE_BYTES = 20 * 1024 * 1024;
    private static final int IMAGE_MAX_WIDTH = 1024;
    private static final int IMAGE_QUALITY = 80;
    private static final List<String> ALLOWED_FILE_MIME_TYPES = Arrays.asList(
        "application/pdf", "text/plain", "text/csv", "application/json", "text/markdown"
    );

    private final ArrayList<ChatMessage> mAllHistoryMessages = new ArrayList<>();
    private final ArrayList<PendingAttachment> mPendingAttachments = new ArrayList<>();
    private int mNextHistoryLoadStart = 0;
    private boolean mLoadingOlderHistory = false;
    @Nullable private Uri mPendingCameraUri;

    private enum SpeechUiState {
        IDLE,
        LISTENING,
        PROCESSING,
        DONE
    }

    private String mToken;
    private String mConversationId;
    private String mLastUserText = "";
    @Nullable private SpeechHelper mSpeechHelper;
    @Nullable private AnimatorSet mMicPulseAnimator;
    @Nullable private Runnable mPendingSpeechIdleReset = null;
    @NonNull private SpeechUiState mSpeechUiState = SpeechUiState.IDLE;

    @Nullable private BroadcastReceiver mConnectivityReceiver;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mToken = ClawPhonesAPI.getToken(this);
        if (TextUtils.isEmpty(mToken)) {
            redirectToLogin(null);
            return;
        }

        mCache = new ConversationCache(getApplicationContext());

        setContentView(R.layout.activity_chat);

        Toolbar toolbar = findViewById(R.id.chat_toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            String title = safeTrim(getIntent().getStringExtra("title"));
            toolbar.setTitle(TextUtils.isEmpty(title) ? getString(R.string.chat_new_conversation) : title);
            toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert);
            toolbar.setNavigationOnClickListener(v -> finish());

            MenuItem coverageMapItem = toolbar.getMenu().add(0, MENU_ITEM_COVERAGE_MAP, 0, getString(R.string.chat_menu_coverage_map));
            coverageMapItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);

            MenuItem logoutItem = toolbar.getMenu().add(0, MENU_ITEM_LOGOUT, 1, getString(R.string.chat_menu_logout));
            logoutItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
            toolbar.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == MENU_ITEM_COVERAGE_MAP) {
                    startActivity(new Intent(this, CoverageMapActivity.class));
                    return true;
                }
                if (item.getItemId() == MENU_ITEM_LOGOUT) {
                    confirmLogout();
                    return true;
                }
                return true;
            });
        }

        mRecycler = findViewById(R.id.messages_recycler);
        mInput = findViewById(R.id.message_input);
        mSpeechStatus = findViewById(R.id.speech_status);
        mAttachmentPreview = findViewById(R.id.attachment_preview);
        mMic = findViewById(R.id.message_mic);
        mMicPulse = findViewById(R.id.message_mic_pulse);
        mAttach = findViewById(R.id.message_attach);
        mSend = findViewById(R.id.message_send);
        mSendProgress = findViewById(R.id.message_send_progress);

        mAdapter = new ChatAdapter(mMessages, this::onRetryQueuedMessage);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        mRecycler.setLayoutManager(lm);
        mRecycler.setItemAnimator(null);
        mRecycler.setItemViewCacheSize(16);
        mRecycler.setAdapter(mAdapter);
        mRecycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy >= 0) return;
                maybeLoadOlderHistory();
            }
        });

        mMessageQueue = new MessageQueue(this);
        mExecutor = Executors.newSingleThreadExecutor();
        registerConnectivityReceiver();

        String existingConversationId = safeTrim(getIntent().getStringExtra("conversation_id"));
        if (!TextUtils.isEmpty(existingConversationId)) {
            mConversationId = existingConversationId;
            loadHistory(existingConversationId);
        } else {
            addAssistantMessage(getString(R.string.chat_welcome_message));
            createConversation();
            restoreQueuedMessagesWithoutConversation();
        }

        mSend.setOnClickListener(v -> onSend());
        if (mAttach != null) {
            mAttach.setOnClickListener(v -> showAttachmentPicker());
        }
        initSpeechInput();

        mInput.setOnEditorActionListener((v, actionId, event) -> {
            onSend();
            return true;
        });
    }

    @Override
    protected void onDestroy() {
        mDestroyed = true;
        unregisterConnectivityReceiver();
        clearPendingSpeechIdleReset();
        stopMicPulseAnimation();
        if (mAdapter != null) {
            mAdapter.clearMemoryCaches();
        }
        if (mSpeechHelper != null) {
            mSpeechHelper.release();
            mSpeechHelper = null;
        }
        mMainHandler.removeCallbacksAndMessages(null);
        if (mExecutor != null) {
            try {
                mExecutor.shutdownNow();
            } catch (Exception ignored) {
            }
            mExecutor = null;
        }
        if (mCache != null) {
            try {
                mCache.close();
            } catch (Exception ignored) {
            }
            mCache = null;
        }
        super.onDestroy();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (mAdapter != null) {
            mAdapter.onTrimMemory(level);
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && mRecycler != null) {
            mRecycler.getRecycledViewPool().clear();
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && mAdapter != null) {
            mAdapter.clearMemoryCaches();
        }

        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && mAllHistoryMessages.size() > MESSAGE_PAGE_SIZE * 2) {
            int pruneCount = mAllHistoryMessages.size() - (MESSAGE_PAGE_SIZE * 2);
            if (pruneCount > 0) {
                mAllHistoryMessages.subList(0, pruneCount).clear();
                mNextHistoryLoadStart = Math.max(0, mNextHistoryLoadStart - pruneCount);
            }
        }

        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            clearPendingUpdate();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                setSpeechUiState(SpeechUiState.IDLE, null);
                toast(getString(R.string.chat_speech_permission_granted));
            } else {
                setSpeechUiState(SpeechUiState.IDLE, null);
                setSpeechStatus(getString(R.string.chat_speech_need_permission), false);
                toast(getString(R.string.chat_speech_need_permission));
            }
            return;
        }

        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                openCameraPicker();
            } else {
                toast("Camera permission denied");
            }
        }
    }

    /** Post to UI thread safely — skips if activity is destroyed. */
    private void runSafe(Runnable r) {
        if (mDestroyed) return;
        mMainHandler.post(() -> {
            if (!mDestroyed) r.run();
        });
    }

    /** Execute on background thread safely — skips if executor is shut down. */
    private void execSafe(Runnable r) {
        ExecutorService exec = mExecutor;
        if (exec != null && !exec.isShutdown()) {
            try {
                exec.execute(r);
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
            }
        }
    }

    private void showAttachmentPicker() {
        if (mBusy) return;
        if (mPendingAttachments.size() >= MAX_PENDING_ATTACHMENTS) {
            toast("最多可添加 3 个附件");
            return;
        }
        String[] options = new String[]{"拍照", "相册", "文件"};
        new AlertDialog.Builder(this)
            .setTitle("添加附件")
            .setItems(options, (dialog, which) -> {
                if (which == 0) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(
                            this,
                            new String[]{Manifest.permission.CAMERA},
                            REQUEST_CAMERA_PERMISSION
                        );
                        return;
                    }
                    openCameraPicker();
                } else if (which == 1) {
                    openImagePicker();
                } else if (which == 2) {
                    openFilePicker();
                }
            })
            .show();
    }

    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_PICK_IMAGE);
    }

    private void openCameraPicker() {
        try {
            File exportDir = new File(getFilesDir(), "exports");
            if (!exportDir.exists() && !exportDir.mkdirs()) {
                toast("无法创建相机临时目录");
                return;
            }
            File output = File.createTempFile("chat_capture_", ".jpg", exportDir);
            mPendingCameraUri = FileProvider.getUriForFile(
                this,
                getPackageName() + ".export.fileprovider",
                output
            );
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, mPendingCameraUri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivityForResult(intent, REQUEST_CAPTURE_IMAGE);
        } catch (Exception e) {
            CrashReporter.reportNonFatal(this, e, "opening_camera_picker");
            toast("打开相机失败");
        }
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(
            Intent.EXTRA_MIME_TYPES,
            new String[]{
                "application/pdf",
                "text/plain",
                "text/csv",
                "application/json",
                "text/markdown",
                "image/*"
            }
        );
        startActivityForResult(intent, REQUEST_PICK_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK) return;

        if (requestCode == REQUEST_PICK_IMAGE) {
            handlePickedAttachment(data == null ? null : data.getData(), true);
            return;
        }
        if (requestCode == REQUEST_CAPTURE_IMAGE) {
            handlePickedAttachment(mPendingCameraUri, true);
            mPendingCameraUri = null;
            return;
        }
        if (requestCode == REQUEST_PICK_FILE) {
            handlePickedAttachment(data == null ? null : data.getData(), false);
        }
    }

    private void handlePickedAttachment(@Nullable Uri uri, boolean forceImage) {
        if (uri == null) {
            toast("未选中文件");
            return;
        }
        if (mPendingAttachments.size() >= MAX_PENDING_ATTACHMENTS) {
            toast("最多可添加 3 个附件");
            return;
        }

        try {
            String filename = resolveDisplayName(uri);
            String mimeType = resolveMimeType(uri, filename, forceImage);
            byte[] data;

            if (mimeType.startsWith("image/") || forceImage) {
                data = compressImage(uri);
                mimeType = "image/jpeg";
                if (!filename.toLowerCase(Locale.US).endsWith(".jpg")
                    && !filename.toLowerCase(Locale.US).endsWith(".jpeg")) {
                    filename = filename + ".jpg";
                }
            } else {
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    if (is == null) {
                        toast("读取文件失败");
                        return;
                    }
                    data = readAllBytes(is);
                }
            }

            int maxBytes = mimeType.startsWith("image/") ? MAX_IMAGE_SIZE_BYTES : MAX_FILE_SIZE_BYTES;
            if (data.length > maxBytes) {
                toast("文件过大");
                return;
            }

            if (!mimeType.startsWith("image/") && !ALLOWED_FILE_MIME_TYPES.contains(mimeType)) {
                toast("不支持的文件类型");
                return;
            }

            mPendingAttachments.add(new PendingAttachment(data, filename, mimeType));
            refreshAttachmentPreview();
        } catch (Exception e) {
            CrashReporter.reportNonFatal(this, e, "handling_attachment");
            toast("附件处理失败");
        }
    }

    private void uploadAttachmentsThenSend(@NonNull String text, @NonNull List<PendingAttachment> attachments) {
        if (attachments.isEmpty()) {
            int userIndex = addUserMessage(text);
            sendMessageOnline(mConversationId, text, null, null, userIndex);
            return;
        }
        if (TextUtils.isEmpty(mConversationId)) {
            createConversation(() -> uploadAttachmentsThenSend(text, attachments));
            return;
        }

        final String targetConversationId = mConversationId;
        mBusy = true;
        setInputEnabled(false);
        setSendingState(true);

        execSafe(() -> {
            ArrayList<String> fileIds = new ArrayList<>();
            ArrayList<ClawPhonesAPI.UploadedFile> uploadedFiles = new ArrayList<>();
            try {
                for (PendingAttachment attachment : attachments) {
                    ClawPhonesAPI.UploadedFile uploaded = ClawPhonesAPI.uploadFileBlocking(
                        ChatActivity.this,
                        targetConversationId,
                        attachment.data,
                        attachment.filename,
                        attachment.mimeType
                    );
                    if (uploaded == null || TextUtils.isEmpty(uploaded.fileId)) {
                        throw new IOException("upload returned empty file_id");
                    }
                    uploadedFiles.add(uploaded);
                    fileIds.add(uploaded.fileId);
                }
            } catch (Exception e) {
                CrashReporter.reportNonFatal(ChatActivity.this, e, "uploading_attachments");
                runSafe(() -> {
                    mPendingAttachments.addAll(attachments);
                    refreshAttachmentPreview();
                    mBusy = false;
                    setInputEnabled(true);
                    setSendingState(false);
                    toast("附件上传失败，请重试");
                });
                return;
            }

            final String localDisplay = buildAttachmentSummaryText(text, uploadedFiles);
            final String firstImageUrl = firstImageUrl(uploadedFiles);
            runSafe(() -> {
                mBusy = false;
                setInputEnabled(true);
                setSendingState(false);
                int userIndex = addUserMessage(localDisplay, firstImageUrl);
                sendMessageOnline(targetConversationId, text, fileIds, null, userIndex);
            });
        });
    }

    private void refreshAttachmentPreview() {
        if (mAttachmentPreview == null) return;
        if (mPendingAttachments.isEmpty()) {
            mAttachmentPreview.setText("");
            mAttachmentPreview.setVisibility(View.GONE);
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mPendingAttachments.size(); i++) {
            PendingAttachment item = mPendingAttachments.get(i);
            String icon = item.mimeType.startsWith("image/") ? "🖼 " : "📄 ";
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(icon).append(item.filename).append(" (").append(formatBytes(item.data.length)).append(")");
        }
        mAttachmentPreview.setText(sb.toString());
        mAttachmentPreview.setVisibility(View.VISIBLE);
    }

    private static String buildAttachmentSummaryText(@NonNull String text, @NonNull List<ClawPhonesAPI.UploadedFile> uploadedFiles) {
        StringBuilder sb = new StringBuilder();
        for (ClawPhonesAPI.UploadedFile uploaded : uploadedFiles) {
            if (uploaded == null) continue;
            String icon = uploaded.mimeType.startsWith("image/") ? "🖼 " : "📄 ";
            if (sb.length() > 0) sb.append("\n");
            sb.append(icon)
                .append(uploaded.filename)
                .append(" (")
                .append(formatBytes(uploaded.size))
                .append(")");
        }
        String trimmedText = safeTrim(text);
        if (!trimmedText.isEmpty()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(trimmedText);
        }
        return sb.toString();
    }

    @Nullable
    private static String firstImageUrl(@NonNull List<ClawPhonesAPI.UploadedFile> uploadedFiles) {
        for (ClawPhonesAPI.UploadedFile uploaded : uploadedFiles) {
            if (uploaded == null) continue;
            if (TextUtils.isEmpty(uploaded.fileId)) continue;
            if (!uploaded.mimeType.startsWith("image/")) continue;
            return ClawPhonesAPI.BASE_URL + "/v1/files/" + uploaded.fileId;
        }
        return null;
    }

    private byte[] compressImage(@NonNull Uri uri) throws IOException {
        ContentResolver resolver = getContentResolver();
        try (InputStream is = resolver.openInputStream(uri)) {
            if (is == null) throw new IOException("cannot open image");
            Bitmap original = BitmapFactory.decodeStream(is);
            if (original == null) throw new IOException("decode image failed");

            int width = original.getWidth();
            int height = original.getHeight();
            Bitmap scaled = original;
            if (width > IMAGE_MAX_WIDTH) {
                int scaledHeight = Math.max(1, (int) ((height * 1f * IMAGE_MAX_WIDTH) / Math.max(1, width)));
                scaled = Bitmap.createScaledBitmap(original, IMAGE_MAX_WIDTH, scaledHeight, true);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, IMAGE_QUALITY, baos);
            byte[] out = baos.toByteArray();

            if (scaled != original) {
                scaled.recycle();
            }
            original.recycle();
            return out;
        }
    }

    private static byte[] readAllBytes(@NonNull InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    @NonNull
    private String resolveDisplayName(@NonNull Uri uri) {
        String fallback = "file_" + System.currentTimeMillis();
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = safeTrim(cursor.getString(index));
                    if (!name.isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return fallback;
    }

    @NonNull
    private String resolveMimeType(@NonNull Uri uri, @NonNull String filename, boolean forceImage) {
        if (forceImage) return "image/jpeg";
        String mimeType = safeTrim(getContentResolver().getType(uri));
        if (!mimeType.isEmpty()) return mimeType;
        String guessed = URLConnection.guessContentTypeFromName(filename);
        if (guessed != null && !guessed.trim().isEmpty()) return guessed.trim();
        return "application/octet-stream";
    }

    @NonNull
    private static String formatBytes(long sizeBytes) {
        if (sizeBytes <= 0) return "0 B";
        if (sizeBytes < 1024) return sizeBytes + " B";
        if (sizeBytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", sizeBytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", sizeBytes / (1024.0 * 1024.0));
    }

    private void loadHistory(String conversationId) {
        if (TextUtils.isEmpty(conversationId)) return;

        mBusy = true;
        setInputEnabled(false);

        execSafe(() -> {
            boolean usedCache = false;
            if (mCache != null) {
                List<Map<String, Object>> cachedRows = new ArrayList<>(mCache.getRecentMessages(conversationId));
                if (!cachedRows.isEmpty()) {
                    Collections.sort(cachedRows, new Comparator<Map<String, Object>>() {
                        @Override
                        public int compare(Map<String, Object> a, Map<String, Object> b) {
                            return Long.compare(asLong(a.get("created_at")), asLong(b.get("created_at")));
                        }
                    });
                    usedCache = true;
                    List<Map<String, Object>> safeCachedRows = cachedRows;
                    runSafe(() -> {
                        applyHistoryRows(safeCachedRows, conversationId);
                        mBusy = false;
                        setInputEnabled(true);
                        tryFlushPendingMessages();
                    });
                }
            }
            final boolean hadCache = usedCache;

            try {
                List<Map<String, Object>> rows = new ArrayList<>(
                    ClawPhonesAPI.getMessages(ChatActivity.this, conversationId));

                Collections.sort(rows, new Comparator<Map<String, Object>>() {
                    @Override
                    public int compare(Map<String, Object> a, Map<String, Object> b) {
                        return Long.compare(asLong(a.get("created_at")), asLong(b.get("created_at")));
                    }
                });

                if (mCache != null) {
                    mCache.upsertMessages(conversationId, rows);
                    rows = new ArrayList<>(mCache.getRecentMessages(conversationId));
                }

                List<Map<String, Object>> safeRows = rows;

                runSafe(() -> {
                    applyHistoryRows(safeRows, conversationId);
                    mBusy = false;
                    setInputEnabled(true);
                    tryFlushPendingMessages();
                });
            } catch (ClawPhonesAPI.ApiException e) {
                if (e.statusCode != 401) {
                    CrashReporter.reportNonFatal(ChatActivity.this, e, "loading_history");
                }
                runSafe(() -> {
                    if (e.statusCode == 401) {
                        ClawPhonesAPI.clearToken(ChatActivity.this);
                        redirectToLogin(getString(R.string.chat_login_expired));
                        return;
                    }
                    if (!hadCache) {
                        toast(getString(R.string.chat_error_load_history));
                    }
                    mBusy = false;
                    setInputEnabled(true);
                });
            } catch (IOException | JSONException e) {
                CrashReporter.reportNonFatal(ChatActivity.this, e, "loading_history");
                runSafe(() -> {
                    if (!hadCache) {
                        toast(getString(R.string.chat_error_load_history));
                    }
                    mBusy = false;
                    setInputEnabled(true);
                });
            }
        });
    }

    private void applyHistoryRows(@NonNull List<Map<String, Object>> rows, @Nullable String conversationId) {
        mAllHistoryMessages.clear();
        for (Map<String, Object> row : rows) {
            String role = asString(row.get("role"));
            String content = asString(row.get("content"));
            ParsedVisionContent parsed = parseVisionContent(content);
            if (TextUtils.isEmpty(parsed.text) && TextUtils.isEmpty(parsed.imageUrl)) continue;
            ChatMessage.Role messageRole = "user".equalsIgnoreCase(role)
                ? ChatMessage.Role.USER
                : ChatMessage.Role.ASSISTANT;
            mAllHistoryMessages.add(new ChatMessage(messageRole, parsed.text, false, parsed.imageUrl));
        }

        mMessages.clear();
        mQueuedMessageIndexes.clear();
        int total = mAllHistoryMessages.size();
        int start = Math.max(0, total - MESSAGE_PAGE_SIZE);
        if (start < total) {
            mMessages.addAll(mAllHistoryMessages.subList(start, total));
        }
        mNextHistoryLoadStart = start;
        mLoadingOlderHistory = false;

        restoreQueuedMessagesForConversation(conversationId);
        mAdapter.notifyDataSetChanged();
        scrollToBottom();
    }

    private void maybeLoadOlderHistory() {
        if (mLoadingOlderHistory || mNextHistoryLoadStart <= 0 || mRecycler == null) return;
        if (!(mRecycler.getLayoutManager() instanceof LinearLayoutManager)) return;

        LinearLayoutManager layoutManager = (LinearLayoutManager) mRecycler.getLayoutManager();
        if (layoutManager == null) return;

        int firstVisible = layoutManager.findFirstVisibleItemPosition();
        if (firstVisible < 0 || firstVisible > PAGINATION_PREFETCH_TRIGGER) return;

        int currentStart = mNextHistoryLoadStart;
        int nextStart = Math.max(0, currentStart - MESSAGE_PAGE_SIZE);
        if (nextStart >= currentStart) return;

        mLoadingOlderHistory = true;
        List<ChatMessage> olderChunk = new ArrayList<>(mAllHistoryMessages.subList(nextStart, currentStart));
        if (olderChunk.isEmpty()) {
            mLoadingOlderHistory = false;
            return;
        }

        mMessages.addAll(0, olderChunk);
        shiftQueuedIndexes(olderChunk.size());
        mAdapter.notifyItemRangeInserted(0, olderChunk.size());

        int previousOffset = 0;
        View firstView = layoutManager.findViewByPosition(firstVisible);
        if (firstView != null) {
            previousOffset = firstView.getTop();
        }
        layoutManager.scrollToPositionWithOffset(firstVisible + olderChunk.size(), previousOffset);

        mNextHistoryLoadStart = nextStart;
        mLoadingOlderHistory = false;
    }

    private void shiftQueuedIndexes(int delta) {
        if (delta <= 0 || mQueuedMessageIndexes.isEmpty()) return;
        ArrayList<Long> keys = new ArrayList<>(mQueuedMessageIndexes.keySet());
        for (Long key : keys) {
            Integer current = mQueuedMessageIndexes.get(key);
            if (current == null) continue;
            mQueuedMessageIndexes.put(key, current + delta);
        }
    }

    private void syncConversationHistoryToCache(@Nullable String conversationId) {
        if (TextUtils.isEmpty(conversationId)) return;
        execSafe(() -> {
            try {
                List<Map<String, Object>> rows = new ArrayList<>(
                    ClawPhonesAPI.getMessages(ChatActivity.this, conversationId)
                );
                Collections.sort(rows, new Comparator<Map<String, Object>>() {
                    @Override
                    public int compare(Map<String, Object> a, Map<String, Object> b) {
                        return Long.compare(asLong(a.get("created_at")), asLong(b.get("created_at")));
                    }
                });
                if (mCache != null) {
                    mCache.upsertMessages(conversationId, rows);
                }
            } catch (Exception e) {
                CrashReporter.reportNonFatal(ChatActivity.this, e, "syncing_history_cache");
            }
        });
    }

    private void createConversation() {
        createConversation(null);
    }

    private void createConversation(@Nullable Runnable onReady) {
        if (mBusy) return;
        mBusy = true;
        setInputEnabled(false);

        final int idx = addAssistantMessage(getString(R.string.chat_status_connecting));

        execSafe(() -> {
            try {
                String id = ClawPhonesAPI.createConversation(ChatActivity.this);
                runSafe(() -> {
                    mConversationId = id;
                    Map<String, Object> analyticsProps = new HashMap<>();
                    analyticsProps.put("conversation_id", id);
                    AnalyticsManager.getInstance(getApplicationContext()).track("conversation_created", analyticsProps);
                    if (mCache != null) {
                        long now = System.currentTimeMillis() / 1000L;
                        mCache.upsertConversation(new ClawPhonesAPI.ConversationSummary(
                            id,
                            safeTrim(getIntent().getStringExtra("title")),
                            now,
                            now,
                            0
                        ));
                    }
                    if (mMessageQueue != null) {
                        mMessageQueue.assignConversationIdForEmpty(id);
                    }
                    updateAssistantMessage(idx, getString(R.string.chat_status_connected_ready));
                    mBusy = false;
                    setInputEnabled(true);
                    tryFlushPendingMessages();
                    if (onReady != null) {
                        onReady.run();
                    }
                });
            } catch (IOException e) {
                CrashReporter.reportNonFatal(ChatActivity.this, e, "creating_conversation");
                runSafe(() -> {
                    updateAssistantMessage(idx, getString(R.string.chat_error_network, safeMsg(e)));
                    mBusy = false;
                    setInputEnabled(true);
                });
            } catch (JSONException e) {
                CrashReporter.reportNonFatal(ChatActivity.this, e, "creating_conversation");
                runSafe(() -> {
                    updateAssistantMessage(idx, getString(R.string.chat_error_parse_data));
                    mBusy = false;
                    setInputEnabled(true);
                });
            } catch (ClawPhonesAPI.ApiException e) {
                if (e.statusCode != 401) {
                    CrashReporter.reportNonFatal(ChatActivity.this, e, "creating_conversation");
                }
                runSafe(() -> {
                    if (e.statusCode == 401) {
                        ClawPhonesAPI.clearToken(ChatActivity.this);
                        redirectToLogin(getString(R.string.chat_login_expired));
                        return;
                    }
                    updateAssistantMessage(idx, getString(R.string.chat_error_connect_failed, safeErr(e)));
                    mBusy = false;
                    setInputEnabled(true);
                });
            }
        });
    }

    private void onSend() {
        if (mBusy) return;
        CrashReporter.setLastAction("sending_message");

        String text = safeTrim(mInput.getText().toString());
        boolean hasPendingAttachments = !mPendingAttachments.isEmpty();
        if (TextUtils.isEmpty(text) && !hasPendingAttachments) return;

        if (hasPendingAttachments && !isNetworkConnected()) {
            toast(getString(R.string.chat_error_connect_failed, "attachments require network"));
            return;
        }

        final String textToSend = text;
        final ArrayList<PendingAttachment> attachmentsToSend = new ArrayList<>(mPendingAttachments);

        mLastUserText = textToSend;
        mInput.setText("");

        Map<String, Object> analyticsProps = new HashMap<>();
        analyticsProps.put("conversation_id", TextUtils.isEmpty(mConversationId) ? "" : mConversationId);
        analyticsProps.put("message_length", textToSend.length());
        analyticsProps.put("queued", !canSendImmediately());
        analyticsProps.put("attachment_count", attachmentsToSend.size());
        AnalyticsManager.getInstance(getApplicationContext()).track("message_sent", analyticsProps);

        if (!attachmentsToSend.isEmpty()) {
            mPendingAttachments.clear();
            refreshAttachmentPreview();
            if (TextUtils.isEmpty(mConversationId)) {
                createConversation(() -> uploadAttachmentsThenSend(textToSend, attachmentsToSend));
            } else {
                uploadAttachmentsThenSend(textToSend, attachmentsToSend);
            }
            return;
        }

        if (canSendImmediately()) {
            int userIndex = addUserMessage(textToSend);
            sendMessageOnline(mConversationId, textToSend, null, null, userIndex);
            return;
        }

        queueMessageForLater(textToSend, mConversationId);
        if (isNetworkConnected()) {
            if (TextUtils.isEmpty(mConversationId)) {
                createConversation();
            } else {
                tryFlushPendingMessages();
            }
        }
    }

    private boolean canSendImmediately() {
        return !TextUtils.isEmpty(mConversationId) && isNetworkConnected();
    }

    private void sendMessageOnline(
        @Nullable String conversationId,
        @NonNull String text,
        @Nullable List<String> fileIds,
        @Nullable Long queueId,
        @Nullable Integer userIndex
    ) {
        final ArrayList<String> normalizedFileIds = new ArrayList<>();
        if (fileIds != null) {
            for (String fileId : fileIds) {
                if (fileId == null) continue;
                String normalized = fileId.trim();
                if (!normalized.isEmpty()) normalizedFileIds.add(normalized);
            }
        }
        final boolean hasFileIds = !normalizedFileIds.isEmpty();

        if (TextUtils.isEmpty(conversationId)) {
            if (queueId != null) {
                if (mMessageQueue != null) mMessageQueue.markPending(queueId);
                setQueuedMessageState(queueId, ChatMessage.DeliveryState.SENDING, 0);
                if (isNetworkConnected() && TextUtils.isEmpty(mConversationId)) {
                    createConversation();
                }
                return;
            }

            if (hasFileIds) {
                if (isNetworkConnected() && TextUtils.isEmpty(mConversationId)) {
                    createConversation(() -> sendMessageOnline(
                        mConversationId,
                        text,
                        new ArrayList<>(normalizedFileIds),
                        null,
                        userIndex
                    ));
                    return;
                }
                toast(getString(R.string.chat_image_send_failed));
                return;
            }

            if (userIndex == null) {
                queueMessageForLater(text, "");
            } else {
                queueExistingUserMessage(userIndex, text, "");
            }
            return;
        }

        mBusy = true;
        setInputEnabled(false);
        setSendingState(true);

        if (queueId != null) {
            int currentRetry = 0;
            Integer idx = mQueuedMessageIndexes.get(queueId);
            if (idx != null && idx >= 0 && idx < mMessages.size()) {
                currentRetry = mMessages.get(idx).retryCount;
            }
            setQueuedMessageState(queueId, ChatMessage.DeliveryState.SENDING, currentRetry);
        }

        final int assistantIndex = addAssistantMessage(getString(R.string.chat_status_thinking), true);
        final String targetConversationId = conversationId;

        execSafe(() -> {
            final StringBuilder accumulated = new StringBuilder();
            ClawPhonesAPI.chatStream(
                ChatActivity.this,
                targetConversationId,
                text,
                normalizedFileIds,
                new ClawPhonesAPI.StreamCallback() {
                    @Override
                    public void onDelta(String delta) {
                        accumulated.append(delta);
                        final String current = accumulated.toString();
                        runSafe(() -> updateAssistantMessageThrottled(assistantIndex, current));
                    }

                    @Override
                    public void onComplete(String fullContent, String messageId) {
                        runSafe(() -> {
                            clearPendingUpdate();
                            String finalContent = fullContent;
                            if (TextUtils.isEmpty(finalContent)) {
                                finalContent = accumulated.toString();
                            }
                            updateAssistantMessage(assistantIndex, finalContent);
                            if (queueId != null) {
                                if (mMessageQueue != null) mMessageQueue.remove(queueId);
                                markQueuedMessageSent(queueId);
                            }
                            syncConversationHistoryToCache(targetConversationId);
                            finishSendingCycle();
                        });
                    }

                    @Override
                    public void onError(Exception error) {
                        CrashReporter.reportNonFatal(ChatActivity.this, error, "streaming_response");
                        runSafe(() -> {
                            clearPendingUpdate();
                            if (error instanceof ClawPhonesAPI.ApiException
                                && ((ClawPhonesAPI.ApiException) error).statusCode == 401) {
                                ClawPhonesAPI.clearToken(ChatActivity.this);
                                redirectToLogin(getString(R.string.chat_login_expired));
                                return;
                            }

                            if (queueId != null) {
                                removeMessageAt(assistantIndex);
                                handleQueuedSendFailure(queueId);
                                finishSendingCycle();
                                return;
                            }

                            if (isLikelyOffline(error) && !hasFileIds) {
                                removeMessageAt(assistantIndex);
                                if (userIndex != null) {
                                    queueExistingUserMessage(userIndex, text, targetConversationId);
                                } else {
                                    queueMessageForLater(text, targetConversationId);
                                }
                                finishSendingCycle();
                                return;
                            }

                            String partial = accumulated.toString();
                            if (!partial.isEmpty()) {
                                updateAssistantMessage(
                                    assistantIndex,
                                    getString(R.string.chat_error_partial_interrupted, partial)
                                );
                            } else {
                                updateAssistantMessage(assistantIndex, getString(R.string.chat_error_send_failed));
                            }
                            finishSendingCycle();
                        });
                    }
                }
            );
        });
    }

    private void finishSendingCycle() {
        mBusy = false;
        setInputEnabled(true);
        setSendingState(false);
        tryFlushPendingMessages();
    }

    private void queueMessageForLater(@NonNull String text, @Nullable String conversationId) {
        if (mMessageQueue == null) return;
        long queueId = mMessageQueue.enqueue(text, conversationId);
        int index = addUserMessage(text, queueId, ChatMessage.DeliveryState.SENDING, 0);
        mQueuedMessageIndexes.put(queueId, index);
    }

    private void queueExistingUserMessage(int userIndex, @NonNull String text, @Nullable String conversationId) {
        if (mMessageQueue == null) return;
        long queueId = mMessageQueue.enqueue(text, conversationId);
        if (userIndex >= 0 && userIndex < mMessages.size()) {
            ChatMessage message = mMessages.get(userIndex);
            message.queueId = queueId;
            message.deliveryState = ChatMessage.DeliveryState.SENDING;
            message.retryCount = 0;
            mQueuedMessageIndexes.put(queueId, userIndex);
            mAdapter.notifyItemChanged(userIndex);
        }
    }

    private void handleQueuedSendFailure(long queueId) {
        if (mMessageQueue == null) return;
        int retryCount = mMessageQueue.incrementRetryCount(queueId);
        if (retryCount >= MAX_QUEUE_RETRY) {
            mMessageQueue.markFailed(queueId);
            setQueuedMessageState(queueId, ChatMessage.DeliveryState.FAILED, retryCount);
            return;
        }
        mMessageQueue.markPending(queueId);
        setQueuedMessageState(queueId, ChatMessage.DeliveryState.SENDING, retryCount);
        mMainHandler.postDelayed(this::tryFlushPendingMessages, 800L);
    }

    private void onRetryQueuedMessage(long queueId) {
        if (mMessageQueue == null) return;
        if (!isNetworkConnected()) {
            toast(getString(R.string.chat_queue_waiting_network));
            return;
        }
        mMessageQueue.resetForManualRetry(queueId);
        setQueuedMessageState(queueId, ChatMessage.DeliveryState.SENDING, 0);
        tryFlushPendingMessages();
    }

    private void tryFlushPendingMessages() {
        if (mDestroyed || mBusy || mMessageQueue == null) return;
        if (!isNetworkConnected()) return;

        MessageQueue.PendingMessage next =
            mMessageQueue.getNextPendingToSendForConversation(mConversationId);
        if (next == null) return;

        String targetConversationId = safeTrim(next.conversationId);
        if (TextUtils.isEmpty(targetConversationId)) {
            if (TextUtils.isEmpty(mConversationId)) {
                createConversation();
                return;
            }
            targetConversationId = mConversationId;
            mMessageQueue.updateConversationId(next.id, targetConversationId);
        }

        mMessageQueue.markSending(next.id);
        setQueuedMessageState(next.id, ChatMessage.DeliveryState.SENDING, next.retryCount);

        if (!mQueuedMessageIndexes.containsKey(next.id)) {
            int index = addUserMessage(next.message, next.id, ChatMessage.DeliveryState.SENDING, next.retryCount);
            mQueuedMessageIndexes.put(next.id, index);
        }
        sendMessageOnline(targetConversationId, next.message, null, next.id, mQueuedMessageIndexes.get(next.id));
    }

    private void restoreQueuedMessagesForConversation(@Nullable String conversationId) {
        if (mMessageQueue == null || TextUtils.isEmpty(conversationId)) return;
        List<MessageQueue.PendingMessage> queued = mMessageQueue.listQueuedForConversation(conversationId);
        for (MessageQueue.PendingMessage pending : queued) {
            appendOrUpdateQueuedMessage(pending);
        }
    }

    private void restoreQueuedMessagesWithoutConversation() {
        if (mMessageQueue == null) return;
        List<MessageQueue.PendingMessage> queued = mMessageQueue.listQueuedWithoutConversation();
        for (MessageQueue.PendingMessage pending : queued) {
            appendOrUpdateQueuedMessage(pending);
        }
    }

    private void appendOrUpdateQueuedMessage(@NonNull MessageQueue.PendingMessage pending) {
        ChatMessage.DeliveryState deliveryState = deliveryStateFromQueueStatus(pending.status);
        if (mQueuedMessageIndexes.containsKey(pending.id)) {
            setQueuedMessageState(pending.id, deliveryState, pending.retryCount);
            return;
        }
        int index = addUserMessage(pending.message, pending.id, deliveryState, pending.retryCount);
        mQueuedMessageIndexes.put(pending.id, index);
    }

    private ChatMessage.DeliveryState deliveryStateFromQueueStatus(@Nullable String status) {
        if (MessageQueue.STATUS_FAILED.equals(status)) {
            return ChatMessage.DeliveryState.FAILED;
        }
        return ChatMessage.DeliveryState.SENDING;
    }

    private void setQueuedMessageState(@Nullable Long queueId,
                                       @NonNull ChatMessage.DeliveryState deliveryState,
                                       int retryCount) {
        if (queueId == null) return;
        Integer index = mQueuedMessageIndexes.get(queueId);
        if (index == null || index < 0 || index >= mMessages.size()) return;
        ChatMessage message = mMessages.get(index);
        message.queueId = queueId;
        message.deliveryState = deliveryState;
        message.retryCount = Math.max(0, retryCount);
        mAdapter.notifyItemChanged(index);
    }

    private void markQueuedMessageSent(long queueId) {
        Integer index = mQueuedMessageIndexes.remove(queueId);
        if (index == null || index < 0 || index >= mMessages.size()) return;
        ChatMessage message = mMessages.get(index);
        message.queueId = -1L;
        message.deliveryState = ChatMessage.DeliveryState.NONE;
        message.retryCount = 0;
        mAdapter.notifyItemChanged(index);
    }

    private void removeMessageAt(int index) {
        if (index < 0 || index >= mMessages.size()) return;
        ChatMessage removed = mMessages.remove(index);
        if (removed.queueId > 0L) {
            mQueuedMessageIndexes.remove(removed.queueId);
        }

        ArrayList<Long> keys = new ArrayList<>(mQueuedMessageIndexes.keySet());
        for (Long key : keys) {
            Integer current = mQueuedMessageIndexes.get(key);
            if (current == null) continue;
            if (current > index) {
                mQueuedMessageIndexes.put(key, current - 1);
            }
        }
        mAdapter.notifyItemRemoved(index);
    }

    private void registerConnectivityReceiver() {
        if (mConnectivityReceiver != null) return;
        mConnectivityReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, Intent intent) {
                if (intent == null) return;
                if (!"android.net.conn.CONNECTIVITY_CHANGE".equals(intent.getAction())) return;
                if (isNetworkConnected()) {
                    tryFlushPendingMessages();
                }
            }
        };
        try {
            registerReceiver(mConnectivityReceiver, new IntentFilter("android.net.conn.CONNECTIVITY_CHANGE"));
        } catch (Exception ignored) {
        }
    }

    private void unregisterConnectivityReceiver() {
        if (mConnectivityReceiver == null) return;
        try {
            unregisterReceiver(mConnectivityReceiver);
        } catch (Exception ignored) {
        }
        mConnectivityReceiver = null;
    }

    private boolean isNetworkConnected() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null) return false;
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception ignored) {
            return true;
        }
    }

    private boolean isLikelyOffline(@NonNull Throwable error) {
        if (!isNetworkConnected()) return true;
        Throwable root = rootCause(error);
        return root instanceof java.net.UnknownHostException
            || root instanceof java.net.ConnectException
            || root instanceof java.net.NoRouteToHostException
            || root instanceof java.net.SocketTimeoutException
            || root instanceof java.io.InterruptedIOException;
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this)
            .setMessage(getString(R.string.chat_dialog_logout_confirm))
            .setNegativeButton(getString(R.string.chat_action_cancel), null)
            .setPositiveButton(getString(R.string.chat_action_logout), (d, w) -> {
                ClawPhonesAPI.clearToken(ChatActivity.this);
                redirectToLogin(null);
            })
            .show();
    }

    private void redirectToLogin(@Nullable String message) {
        if (!TextUtils.isEmpty(message)) {
            toast(message);
        }
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    private void setInputEnabled(boolean enabled) {
        if (mInput != null) {
            mInput.setEnabled(enabled);
            mInput.setAlpha(enabled ? 1.0f : 0.6f);
        }
        if (mMic != null) {
            mMic.setEnabled(enabled);
            mMic.setAlpha(enabled ? 1.0f : 0.5f);
        }
        if (mSend != null) {
            mSend.setEnabled(enabled);
            mSend.setAlpha(enabled ? 1.0f : 0.5f);
        }
        if (mAttach != null) {
            mAttach.setEnabled(enabled);
            mAttach.setAlpha(enabled ? 1.0f : 0.5f);
        }
        if (!enabled && mSpeechHelper != null) {
            mSpeechHelper.cancelListening();
            setSpeechUiState(SpeechUiState.IDLE, null);
        }
    }

    private void setSendingState(boolean sending) {
        if (mSend != null) {
            mSend.setVisibility(sending ? View.INVISIBLE : View.VISIBLE);
        }
        if (mSendProgress != null) {
            mSendProgress.setVisibility(sending ? View.VISIBLE : View.GONE);
        }
    }

    private void initSpeechInput() {
        if (mMic == null) return;

        mSpeechHelper = new SpeechHelper(this, new SpeechHelper.Callback() {
            @Override
            public void onStatus(@NonNull String status, boolean active) {
                runSafe(() -> {
                    if (!active) return;
                    if (status.contains("识别")) {
                        setSpeechUiState(SpeechUiState.PROCESSING, null);
                    } else {
                        setSpeechUiState(SpeechUiState.LISTENING, null);
                    }
                });
            }

            @Override
            public void onPartialText(@NonNull String text) {
                runSafe(() -> setSpeechUiState(SpeechUiState.LISTENING, text));
            }

            @Override
            public void onFinalText(@NonNull String text) {
                runSafe(() -> applyRecognizedText(text));
            }

            @Override
            public void onError(@NonNull String message) {
                runSafe(() -> {
                    setSpeechUiState(SpeechUiState.IDLE, null);
                    setSpeechStatus(message, false);
                });
            }
        });

        if (mSpeechHelper == null || !mSpeechHelper.isRecognitionAvailable()) {
            mMic.setEnabled(false);
            mMic.setAlpha(0.5f);
            setSpeechStatus(getString(R.string.chat_speech_not_supported), false);
            stopMicPulseAnimation();
            return;
        }

        setSpeechUiState(SpeechUiState.IDLE, null);

        mMic.setOnTouchListener((v, event) -> {
            if (mBusy) return true;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                if (!hasRecordPermission()) {
                    ActivityCompat.requestPermissions(
                        ChatActivity.this,
                        new String[]{Manifest.permission.RECORD_AUDIO},
                        REQUEST_RECORD_AUDIO
                    );
                    setSpeechUiState(SpeechUiState.IDLE, null);
                    setSpeechStatus(getString(R.string.chat_speech_need_permission), false);
                    return true;
                }
                if (mSpeechHelper != null) {
                    mSpeechHelper.startListening(Locale.getDefault());
                    setSpeechUiState(SpeechUiState.LISTENING, null);
                }
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (mSpeechHelper != null) {
                    mSpeechHelper.stopListening();
                    setSpeechUiState(SpeechUiState.PROCESSING, null);
                }
                v.performClick();
                return true;
            }
            return false;
        });
    }

    private boolean hasRecordPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED;
    }

    private void applyRecognizedText(@NonNull String text) {
        String recognized = safeTrim(text);
        if (TextUtils.isEmpty(recognized)) {
            setSpeechUiState(SpeechUiState.IDLE, null);
            setSpeechStatus(getString(R.string.chat_speech_error_no_match), false);
            return;
        }
        mInput.setText(recognized);
        mInput.setSelection(recognized.length());
        setSpeechUiState(SpeechUiState.DONE, null);
    }

    private void setSpeechStatus(@NonNull String text, boolean active) {
        if (mSpeechStatus == null) return;
        if (TextUtils.isEmpty(text)) {
            mSpeechStatus.setVisibility(View.GONE);
            return;
        }
        mSpeechStatus.setText(text);
        mSpeechStatus.setVisibility(View.VISIBLE);
        mSpeechStatus.setTextColor(active
            ? ContextCompat.getColor(this, R.color.clawphones_accent)
            : ContextCompat.getColor(this, R.color.clawphones_secondary_text));
    }

    private void setSpeechUiState(@NonNull SpeechUiState state, @Nullable String partialText) {
        clearPendingSpeechIdleReset();
        mSpeechUiState = state;
        updateMicVisualState();

        switch (state) {
            case IDLE:
                setSpeechStatus(getString(R.string.chat_speech_state_idle), false);
                break;
            case LISTENING:
                if (!TextUtils.isEmpty(partialText)) {
                    setSpeechStatus(getString(R.string.chat_speech_status_recording_partial, partialText), true);
                } else {
                    setSpeechStatus(getString(R.string.chat_speech_state_listening), true);
                }
                break;
            case PROCESSING:
                setSpeechStatus(getString(R.string.chat_speech_state_processing), true);
                break;
            case DONE:
                setSpeechStatus(getString(R.string.chat_speech_state_done), true);
                mPendingSpeechIdleReset = () -> {
                    if (mSpeechUiState == SpeechUiState.DONE) {
                        setSpeechUiState(SpeechUiState.IDLE, null);
                    }
                };
                mMainHandler.postDelayed(mPendingSpeechIdleReset, SPEECH_DONE_RESET_MS);
                break;
        }
    }

    private void clearPendingSpeechIdleReset() {
        if (mPendingSpeechIdleReset == null) return;
        mMainHandler.removeCallbacks(mPendingSpeechIdleReset);
        mPendingSpeechIdleReset = null;
    }

    private void updateMicVisualState() {
        if (mMic == null) return;

        mMic.animate().cancel();
        switch (mSpeechUiState) {
            case LISTENING:
                mMic.setBackgroundResource(R.drawable.clawphones_button_bg);
                mMic.setColorFilter(ContextCompat.getColor(this, R.color.clawphones_background));
                mMic.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120L).start();
                mMic.setElevation(10f);
                startMicPulseAnimation();
                break;
            case PROCESSING:
                mMic.setBackgroundResource(R.drawable.clawphones_button_outline_bg);
                mMic.setColorFilter(ContextCompat.getColor(this, R.color.clawphones_accent));
                mMic.animate().scaleX(1.03f).scaleY(1.03f).setDuration(120L).start();
                mMic.setElevation(6f);
                stopMicPulseAnimation();
                break;
            case DONE:
                mMic.setBackgroundResource(R.drawable.clawphones_button_outline_bg);
                mMic.setColorFilter(ContextCompat.getColor(this, R.color.clawphones_accent));
                mMic.animate().scaleX(1.02f).scaleY(1.02f).setDuration(120L).start();
                mMic.setElevation(2f);
                stopMicPulseAnimation();
                break;
            case IDLE:
            default:
                mMic.setBackgroundResource(R.drawable.clawphones_button_outline_bg);
                mMic.setColorFilter(ContextCompat.getColor(this, R.color.clawphones_accent));
                mMic.animate().scaleX(1f).scaleY(1f).setDuration(120L).start();
                mMic.setElevation(0f);
                stopMicPulseAnimation();
                break;
        }
    }

    private void startMicPulseAnimation() {
        if (mMicPulse == null) return;
        if (mMicPulseAnimator != null && mMicPulseAnimator.isRunning()) return;

        mMicPulse.setVisibility(View.VISIBLE);
        mMicPulse.setAlpha(0.28f);
        mMicPulse.setScaleX(1f);
        mMicPulse.setScaleY(1f);

        ObjectAnimator scaleX = ObjectAnimator.ofFloat(mMicPulse, View.SCALE_X, 1f, 1.3f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(mMicPulse, View.SCALE_Y, 1f, 1.3f);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(mMicPulse, View.ALPHA, 0.28f, 0f);
        scaleX.setRepeatCount(ValueAnimator.INFINITE);
        scaleY.setRepeatCount(ValueAnimator.INFINITE);
        alpha.setRepeatCount(ValueAnimator.INFINITE);
        scaleX.setRepeatMode(ValueAnimator.RESTART);
        scaleY.setRepeatMode(ValueAnimator.RESTART);
        alpha.setRepeatMode(ValueAnimator.RESTART);

        mMicPulseAnimator = new AnimatorSet();
        mMicPulseAnimator.setDuration(900L);
        mMicPulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        mMicPulseAnimator.playTogether(scaleX, scaleY, alpha);
        mMicPulseAnimator.start();
    }

    private void stopMicPulseAnimation() {
        if (mMicPulseAnimator != null) {
            mMicPulseAnimator.cancel();
            mMicPulseAnimator = null;
        }
        if (mMicPulse != null) {
            mMicPulse.setVisibility(View.INVISIBLE);
            mMicPulse.setAlpha(0f);
            mMicPulse.setScaleX(1f);
            mMicPulse.setScaleY(1f);
        }
    }

    private int addUserMessage(String text) {
        return addUserMessage(text, null, -1L, ChatMessage.DeliveryState.NONE, 0);
    }

    private int addUserMessage(String text, @Nullable String imageUrl) {
        return addUserMessage(text, imageUrl, -1L, ChatMessage.DeliveryState.NONE, 0);
    }

    private int addUserMessage(String text, long queueId,
                               @NonNull ChatMessage.DeliveryState deliveryState,
                               int retryCount) {
        return addUserMessage(text, null, queueId, deliveryState, retryCount);
    }

    private int addUserMessage(String text, @Nullable String imageUrl,
                               long queueId,
                               @NonNull ChatMessage.DeliveryState deliveryState,
                               int retryCount) {
        int idx = mMessages.size();
        ChatMessage message = new ChatMessage(ChatMessage.Role.USER, text, false, imageUrl);
        message.queueId = queueId;
        message.deliveryState = deliveryState;
        message.retryCount = Math.max(0, retryCount);
        mMessages.add(message);
        mAdapter.notifyItemInserted(idx);
        scrollToBottom();
        return idx;
    }

    private int addAssistantMessage(String text) {
        return addAssistantMessage(text, false);
    }

    private int addAssistantMessage(String text, boolean isThinking) {
        int idx = mMessages.size();
        mMessages.add(new ChatMessage(ChatMessage.Role.ASSISTANT, text, isThinking));
        mAdapter.notifyItemInserted(idx);
        scrollToBottom();
        return idx;
    }

    private void updateAssistantMessage(int index, String text) {
        if (index < 0 || index >= mMessages.size()) return;
        ChatMessage m = mMessages.get(index);
        m.text = text;
        m.isThinking = false;
        mAdapter.notifyItemChanged(index);
        scrollToBottom();
    }

    private void updateAssistantMessageThrottled(int index, String text) {
        if (index < 0 || index >= mMessages.size()) return;
        ChatMessage m = mMessages.get(index);
        m.text = text;
        m.isThinking = false;

        long now = System.currentTimeMillis();
        long elapsed = now - mLastUpdateMs;
        if (elapsed >= UPDATE_THROTTLE_MS) {
            clearPendingUpdate();
            mLastUpdateMs = now;
            mAdapter.notifyItemChanged(index);
            scrollToBottom();
            return;
        }

        if (mPendingUpdate != null) {
            mMainHandler.removeCallbacks(mPendingUpdate);
        }
        final int pendingIndex = index;
        long delay = UPDATE_THROTTLE_MS - elapsed;
        mPendingUpdate = () -> {
            mLastUpdateMs = System.currentTimeMillis();
            mPendingUpdate = null;
            if (pendingIndex < 0 || pendingIndex >= mMessages.size()) return;
            mAdapter.notifyItemChanged(pendingIndex);
            scrollToBottom();
        };
        mMainHandler.postDelayed(mPendingUpdate, Math.max(1L, delay));
    }

    private void clearPendingUpdate() {
        if (mPendingUpdate == null) return;
        mMainHandler.removeCallbacks(mPendingUpdate);
        mPendingUpdate = null;
    }

    private void scrollToBottom() {
        if (mRecycler == null) return;
        mRecycler.post(() -> {
            if (mAdapter != null && mAdapter.getItemCount() > 0) {
                mRecycler.smoothScrollToPosition(mAdapter.getItemCount() - 1);
            }
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private static String safeTrim(String s) {
        if (s == null) return "";
        return s.trim();
    }

    private String safeMsg(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.trim().isEmpty()) return getString(R.string.chat_error_unknown);
        if (msg.length() > 200) msg = msg.substring(0, 200) + "…";
        return msg;
    }

    private String safeErr(ClawPhonesAPI.ApiException e) {
        String msg = e.getMessage();
        if (msg == null || msg.trim().isEmpty()) {
            return getString(R.string.chat_error_http_status, e.statusCode);
        }
        try {
            org.json.JSONObject errJson = new org.json.JSONObject(msg);
            String detail = errJson.optString("detail", null);
            if (detail != null && !detail.trim().isEmpty()) return detail;
        } catch (Exception ignored) {
        }
        if (msg.length() > 200) msg = msg.substring(0, 200) + "…";
        return msg;
    }

    private static String asString(Object value) {
        if (value == null) return "";
        return String.valueOf(value);
    }

    private static long asLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static final Pattern MARKDOWN_IMAGE_PATTERN =
        Pattern.compile("!\\[[^\\]]*\\]\\((https?://[^\\s)]+)\\)");
    private static final String MESSAGE_META_OPEN = "[[MESSAGE_META]]";
    private static final String MESSAGE_META_CLOSE = "[[/MESSAGE_META]]";

    @NonNull
    private static ParsedVisionContent parseVisionContent(@Nullable String raw) {
        String trimmed = safeTrim(raw);
        if (trimmed.isEmpty()) {
            return new ParsedVisionContent("", null);
        }

        MessageMetaSplit metaSplit = splitMessageMeta(trimmed);
        String visibleBody = safeTrim(metaSplit.body);
        String fileSummary = extractFileSummary(metaSplit.meta);
        String imageFromMeta = extractFirstImageUrl(metaSplit.meta);

        StringBuilder text = new StringBuilder();
        String[] imageUrl = new String[1];

        if (!TextUtils.isEmpty(imageFromMeta)) {
            imageUrl[0] = imageFromMeta;
        }

        if (looksLikeJson(visibleBody)) {
            try {
                Object parsed = new org.json.JSONTokener(visibleBody).nextValue();
                extractVisionFromObject(parsed, text, imageUrl);
            } catch (Exception ignored) {
            }
        }

        if (text.length() == 0) {
            text.append(visibleBody);
        }
        if (!TextUtils.isEmpty(fileSummary)) {
            if (text.length() > 0) {
                text.insert(0, fileSummary + "\n\n");
            } else {
                text.append(fileSummary);
            }
        }
        if (TextUtils.isEmpty(imageUrl[0])) {
            imageUrl[0] = extractMarkdownImageUrl(text.toString());
        }

        String normalizedText = normalizeVisibleText(text.toString(), imageUrl[0]);
        return new ParsedVisionContent(normalizedText, imageUrl[0]);
    }

    private static boolean looksLikeJson(@NonNull String value) {
        return (value.startsWith("{") && value.endsWith("}"))
            || (value.startsWith("[") && value.endsWith("]"));
    }

    private static void extractVisionFromObject(@Nullable Object node,
                                                @NonNull StringBuilder text,
                                                @NonNull String[] imageUrl) {
        if (node == null) return;

        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            appendTextCandidate(text, object.optString("text", ""));
            appendTextCandidate(text, object.optString("content", ""));
            appendTextCandidate(text, object.optString("message", ""));
            appendTextCandidate(text, object.optString("caption", ""));
            appendImageCandidate(imageUrl, object.optString("image_url", ""));
            appendImageCandidate(imageUrl, object.optString("imageUrl", ""));
            appendImageCandidate(imageUrl, object.optString("url", ""));

            Object contentNode = object.opt("content");
            if (contentNode instanceof JSONArray || contentNode instanceof JSONObject) {
                extractVisionFromObject(contentNode, text, imageUrl);
            }
            Object partsNode = object.opt("parts");
            if (partsNode instanceof JSONArray || partsNode instanceof JSONObject) {
                extractVisionFromObject(partsNode, text, imageUrl);
            }
            Object imageNode = object.opt("image");
            if (imageNode instanceof JSONArray || imageNode instanceof JSONObject) {
                extractVisionFromObject(imageNode, text, imageUrl);
            } else if (imageNode instanceof String) {
                appendImageCandidate(imageUrl, String.valueOf(imageNode));
            }
            return;
        }

        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                extractVisionFromObject(array.opt(i), text, imageUrl);
            }
            return;
        }

        if (node instanceof String) {
            appendTextCandidate(text, String.valueOf(node));
        }
    }

    private static void appendTextCandidate(@NonNull StringBuilder target, @Nullable String candidate) {
        String normalized = safeTrim(candidate);
        if (normalized.isEmpty()) return;
        if (looksLikeUrl(normalized)) return;
        if (target.length() > 0) {
            target.append("\n");
        }
        target.append(normalized);
    }

    private static void appendImageCandidate(@NonNull String[] imageUrlHolder, @Nullable String candidate) {
        if (!TextUtils.isEmpty(imageUrlHolder[0])) return;
        String normalized = safeTrim(candidate);
        if (!looksLikeUrl(normalized)) return;
        String lower = normalized.toLowerCase(Locale.US);
        if (!(lower.endsWith(".png")
            || lower.endsWith(".jpg")
            || lower.endsWith(".jpeg")
            || lower.endsWith(".webp")
            || lower.endsWith(".gif")
            || lower.contains("image"))) {
            if (!lower.startsWith("https://")) return;
        }
        imageUrlHolder[0] = normalized;
    }

    private static boolean looksLikeUrl(@Nullable String value) {
        if (value == null) return false;
        String normalized = value.trim().toLowerCase(Locale.US);
        return normalized.startsWith("http://") || normalized.startsWith("https://");
    }

    @Nullable
    private static String extractMarkdownImageUrl(@NonNull String text) {
        Matcher matcher = MARKDOWN_IMAGE_PATTERN.matcher(text);
        if (matcher.find()) {
            return safeTrim(matcher.group(1));
        }
        return null;
    }

    @NonNull
    private static String normalizeVisibleText(@NonNull String text, @Nullable String imageUrl) {
        String normalized = text.replace("\r\n", "\n").trim();
        if (TextUtils.isEmpty(imageUrl)) {
            return normalized;
        }

        Matcher matcher = MARKDOWN_IMAGE_PATTERN.matcher(normalized);
        String withoutMarkdownImage = matcher.replaceAll("").trim();
        if (!withoutMarkdownImage.isEmpty()) {
            return withoutMarkdownImage;
        }
        return normalized;
    }

    @NonNull
    private static MessageMetaSplit splitMessageMeta(@NonNull String raw) {
        if (!raw.startsWith(MESSAGE_META_OPEN)) {
            return new MessageMetaSplit(raw, null);
        }
        int endIndex = raw.indexOf(MESSAGE_META_CLOSE, MESSAGE_META_OPEN.length());
        if (endIndex <= MESSAGE_META_OPEN.length()) {
            return new MessageMetaSplit(raw, null);
        }

        String metaJson = raw.substring(MESSAGE_META_OPEN.length(), endIndex);
        String body = raw.substring(endIndex + MESSAGE_META_CLOSE.length());
        try {
            JSONObject meta = new JSONObject(metaJson);
            return new MessageMetaSplit(body, meta);
        } catch (Exception ignored) {
            return new MessageMetaSplit(body, null);
        }
    }

    @NonNull
    private static String extractFileSummary(@Nullable JSONObject meta) {
        if (meta == null) return "";
        JSONArray files = meta.optJSONArray("files");
        if (files == null || files.length() == 0) return "";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file == null) continue;
            String name = safeTrim(file.optString("name", "file"));
            if (name.isEmpty()) name = "file";
            String mime = safeTrim(file.optString("type", ""));
            long size = file.optLong("size", 0L);
            String icon = mime.startsWith("image/") ? "🖼 " : "📄 ";
            if (sb.length() > 0) sb.append("\n");
            sb.append(icon).append(name);
            if (size > 0) {
                sb.append(" (").append(formatBytes(size)).append(")");
            }
        }
        return sb.toString();
    }

    @Nullable
    private static String extractFirstImageUrl(@Nullable JSONObject meta) {
        if (meta == null) return null;
        JSONArray files = meta.optJSONArray("files");
        if (files == null) return null;
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file == null) continue;
            String mime = safeTrim(file.optString("type", ""));
            if (!mime.startsWith("image/")) continue;
            String url = safeTrim(file.optString("url", ""));
            if (url.startsWith("http://") || url.startsWith("https://")) {
                return url;
            }
            if (url.startsWith("/")) {
                return ClawPhonesAPI.BASE_URL + url;
            }
            String fileId = safeTrim(file.optString("id", ""));
            if (!fileId.isEmpty()) {
                return ClawPhonesAPI.BASE_URL + "/v1/files/" + fileId;
            }
        }
        return null;
    }

    static final class MessageMetaSplit {
        final String body;
        @Nullable final JSONObject meta;

        MessageMetaSplit(@Nullable String body, @Nullable JSONObject meta) {
            this.body = body == null ? "" : body;
            this.meta = meta;
        }
    }

    static final class ParsedVisionContent {
        final String text;
        @Nullable final String imageUrl;

        ParsedVisionContent(@Nullable String text, @Nullable String imageUrl) {
            this.text = text == null ? "" : text;
            this.imageUrl = TextUtils.isEmpty(safeTrim(imageUrl)) ? null : safeTrim(imageUrl);
        }
    }

    static final class PendingAttachment {
        final byte[] data;
        final String filename;
        final String mimeType;

        PendingAttachment(@NonNull byte[] data, @NonNull String filename, @NonNull String mimeType) {
            this.data = data;
            this.filename = filename;
            this.mimeType = mimeType;
        }
    }

    static final class ChatMessage {
        enum Role { USER, ASSISTANT }
        enum DeliveryState { NONE, SENDING, FAILED }

        private static final AtomicLong NEXT_STABLE_ID = new AtomicLong(1L);

        final long stableId;
        final Role role;
        String text;
        boolean isThinking;
        @Nullable final String imageUrl;
        long queueId = -1L;
        DeliveryState deliveryState = DeliveryState.NONE;
        int retryCount = 0;

        ChatMessage(Role role, String text, boolean isThinking) {
            this(role, text, isThinking, null);
        }

        ChatMessage(Role role, String text, boolean isThinking, @Nullable String imageUrl) {
            this.stableId = NEXT_STABLE_ID.getAndIncrement();
            this.role = role;
            this.text = text;
            this.isThinking = isThinking;
            this.imageUrl = TextUtils.isEmpty(safeTrim(imageUrl)) ? null : safeTrim(imageUrl);
        }
    }

    static final class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.VH> {
        private static final int TYPE_AI = 0;
        private static final int TYPE_USER = 1;
        private static final MessageImageLoader IMAGE_LOADER = new MessageImageLoader();

        private final ArrayList<ChatMessage> messages;
        private final RetryClickListener retryClickListener;

        interface RetryClickListener {
            void onRetry(long queueId);
        }

        ChatAdapter(ArrayList<ChatMessage> messages, @Nullable RetryClickListener retryClickListener) {
            this.messages = messages;
            this.retryClickListener = retryClickListener;
            setHasStableIds(true);
        }

        @Override
        public int getItemViewType(int position) {
            ChatMessage m = messages.get(position);
            return m.role == ChatMessage.Role.USER ? TYPE_USER : TYPE_AI;
        }

        @Override
        public long getItemId(int position) {
            return messages.get(position).stableId;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = android.view.LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_message, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            ChatMessage m = messages.get(position);
            boolean isUser = m.role == ChatMessage.Role.USER;
            boolean isThinking = !isUser && m.isThinking;
            holder.bind(m, isUser, isThinking, retryClickListener);
        }

        @Override
        public void onViewRecycled(@NonNull VH holder) {
            holder.unbind();
            super.onViewRecycled(holder);
        }

        @Override
        public int getItemCount() {
            return messages.size();
        }

        void onTrimMemory(int level) {
            IMAGE_LOADER.onTrimMemory(level);
        }

        void clearMemoryCaches() {
            IMAGE_LOADER.clear();
        }

        static final class VH extends RecyclerView.ViewHolder {
            final TextView text;
            final ImageView image;
            final View bubble;
            final TextView statusText;
            final TextView retryButton;

            VH(@NonNull View itemView) {
                super(itemView);
                text = itemView.findViewById(R.id.message_text);
                image = itemView.findViewById(R.id.message_image);
                bubble = itemView.findViewById(R.id.message_bubble);
                statusText = itemView.findViewById(R.id.message_status);
                retryButton = itemView.findViewById(R.id.message_retry);
                if (text != null) {
                    text.setMovementMethod(LinkMovementMethod.getInstance());
                }
            }

            void bind(ChatMessage message, boolean isUser, boolean isThinking,
                      @Nullable RetryClickListener retryClickListener) {
                if (image != null) {
                    if (!TextUtils.isEmpty(message.imageUrl)) {
                        image.setVisibility(View.VISIBLE);
                        IMAGE_LOADER.loadInto(
                            image,
                            message.imageUrl,
                            ClawPhonesAPI.getToken(itemView.getContext())
                        );
                    } else {
                        IMAGE_LOADER.cancel(image);
                        image.setImageDrawable(null);
                        image.setVisibility(View.GONE);
                    }
                }

                if (text != null) {
                    if (isThinking) {
                        text.setText(message.text);
                        text.setTypeface(null, Typeface.ITALIC);
                        text.setTextColor(0xFF888888);
                    } else {
                        text.setText(renderMarkdown(message.text));
                        text.setTypeface(null, Typeface.NORMAL);
                        if (isUser) {
                            if (message.deliveryState == ChatMessage.DeliveryState.SENDING) {
                                text.setTextColor(0xFFB8B8B8);
                            } else {
                                text.setTextColor(Color.WHITE);
                            }
                        } else {
                            text.setTextColor(0xFFF5F0E6);
                        }
                    }
                }
                if (statusText != null) {
                    if (isUser && message.deliveryState == ChatMessage.DeliveryState.SENDING) {
                        statusText.setVisibility(View.VISIBLE);
                        statusText.setText(itemView.getContext().getString(R.string.chat_queue_sending));
                        statusText.setTextColor(0xFF8E8E8E);
                    } else if (isUser && message.deliveryState == ChatMessage.DeliveryState.FAILED) {
                        statusText.setVisibility(View.VISIBLE);
                        statusText.setText(itemView.getContext().getString(R.string.chat_queue_failed));
                        statusText.setTextColor(0xFFE57373);
                    } else {
                        statusText.setVisibility(View.GONE);
                    }
                }
                if (retryButton != null) {
                    boolean showRetry = isUser
                        && message.deliveryState == ChatMessage.DeliveryState.FAILED
                        && message.queueId > 0L
                        && retryClickListener != null;
                    retryButton.setVisibility(showRetry ? View.VISIBLE : View.GONE);
                    retryButton.setOnClickListener(showRetry
                        ? v -> retryClickListener.onRetry(message.queueId)
                        : null);
                }
                if (bubble != null) {
                    android.widget.FrameLayout.LayoutParams lp =
                        (android.widget.FrameLayout.LayoutParams) bubble.getLayoutParams();
                    if (isUser) {
                        lp.gravity = android.view.Gravity.END;
                        bubble.setBackgroundResource(R.drawable.chat_bubble_user);
                    } else {
                        lp.gravity = android.view.Gravity.START;
                        bubble.setBackgroundResource(R.drawable.chat_bubble_assistant);
                    }
                    bubble.setLayoutParams(lp);
                }
            }

            void unbind() {
                if (image != null) {
                    IMAGE_LOADER.cancel(image);
                    image.setImageDrawable(null);
                    image.setVisibility(View.GONE);
                }
                if (text != null) {
                    text.setText(null);
                }
                if (retryButton != null) {
                    retryButton.setOnClickListener(null);
                }
            }
        }

        static final class MessageImageLoader {
            private static final int DEFAULT_THUMB_SIZE_PX = 512;
            private static final int MAX_DOWNLOAD_BYTES = 5 * 1024 * 1024;
            private static final int THREAD_POOL_SIZE = 2;
            private static final int CACHE_BYTES = 32 * 1024 * 1024;

            private final LruCache<String, Bitmap> bitmapCache = new LruCache<String, Bitmap>(CACHE_BYTES) {
                @Override
                protected int sizeOf(@NonNull String key, @NonNull Bitmap value) {
                    return value.getByteCount();
                }
            };
            private final ExecutorService decodeExecutor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
            private final Handler uiHandler = new Handler(Looper.getMainLooper());

            void loadInto(@NonNull ImageView target, @Nullable String rawUrl, @Nullable String authToken) {
                String imageUrl = safeTrim(rawUrl);
                if (imageUrl.isEmpty()) {
                    cancel(target);
                    target.setImageDrawable(null);
                    target.setVisibility(View.GONE);
                    return;
                }

                String cacheKey = buildCacheKey(imageUrl, DEFAULT_THUMB_SIZE_PX);
                target.setTag(cacheKey);
                Bitmap cached = bitmapCache.get(cacheKey);
                if (cached != null && !cached.isRecycled()) {
                    target.setImageBitmap(cached);
                    return;
                }

                target.setImageDrawable(null);
                decodeExecutor.execute(() -> {
                    Bitmap decoded = decodeFromNetwork(
                        imageUrl,
                        DEFAULT_THUMB_SIZE_PX,
                        DEFAULT_THUMB_SIZE_PX,
                        authToken
                    );
                    if (decoded == null) return;
                    bitmapCache.put(cacheKey, decoded);

                    uiHandler.post(() -> {
                        Object currentTag = target.getTag();
                        if (!(currentTag instanceof String) || !cacheKey.equals(currentTag)) {
                            return;
                        }
                        target.setImageBitmap(decoded);
                    });
                });
            }

            void cancel(@NonNull ImageView target) {
                target.setTag(null);
            }

            void onTrimMemory(int level) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
                    || level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
                    bitmapCache.evictAll();
                    return;
                }
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                    bitmapCache.trimToSize(Math.max(0, CACHE_BYTES / 4));
                } else if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                    bitmapCache.trimToSize(Math.max(0, CACHE_BYTES / 2));
                }
            }

            void clear() {
                bitmapCache.evictAll();
            }

            @Nullable
            private Bitmap decodeFromNetwork(
                @NonNull String url,
                int reqWidth,
                int reqHeight,
                @Nullable String authToken
            ) {
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(8_000);
                    conn.setReadTimeout(10_000);
                    conn.setDoInput(true);
                    conn.setRequestProperty("Accept", "image/*");
                    if (url.startsWith(ClawPhonesAPI.BASE_URL + "/v1/files/")
                        && authToken != null
                        && !authToken.trim().isEmpty()) {
                        conn.setRequestProperty("Authorization", "Bearer " + authToken.trim());
                    }

                    int code = conn.getResponseCode();
                    if (code < 200 || code >= 300) return null;

                    byte[] bytes;
                    try (InputStream in = conn.getInputStream();
                         ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[8 * 1024];
                        int read;
                        int total = 0;
                        while ((read = in.read(buffer)) != -1) {
                            total += read;
                            if (total > MAX_DOWNLOAD_BYTES) {
                                return null;
                            }
                            out.write(buffer, 0, read);
                        }
                        bytes = out.toByteArray();
                    }

                    return decodeDownsampled(bytes, reqWidth, reqHeight);
                } catch (Exception ignored) {
                    return null;
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }

            @Nullable
            private Bitmap decodeDownsampled(@NonNull byte[] imageBytes, int reqWidth, int reqHeight) {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

                BitmapFactory.Options decode = new BitmapFactory.Options();
                decode.inSampleSize = computeInSampleSize(bounds, reqWidth, reqHeight);
                decode.inPreferredConfig = Bitmap.Config.RGB_565;
                decode.inDither = true;

                Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, decode);
                if (bitmap == null) return null;

                if (bitmap.getWidth() > reqWidth || bitmap.getHeight() > reqHeight) {
                    float scale = Math.min(
                        reqWidth / (float) Math.max(1, bitmap.getWidth()),
                        reqHeight / (float) Math.max(1, bitmap.getHeight())
                    );
                    if (scale > 0f && scale < 1f) {
                        int scaledWidth = Math.max(1, Math.round(bitmap.getWidth() * scale));
                        int scaledHeight = Math.max(1, Math.round(bitmap.getHeight() * scale));
                        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true);
                        if (scaled != bitmap) {
                            bitmap.recycle();
                            bitmap = scaled;
                        }
                    }
                }
                return bitmap;
            }

            private int computeInSampleSize(@NonNull BitmapFactory.Options options, int reqWidth, int reqHeight) {
                int inSampleSize = 1;
                int width = Math.max(1, options.outWidth);
                int height = Math.max(1, options.outHeight);
                int safeReqWidth = Math.max(1, reqWidth);
                int safeReqHeight = Math.max(1, reqHeight);

                while ((height / inSampleSize) > safeReqHeight || (width / inSampleSize) > safeReqWidth) {
                    inSampleSize <<= 1;
                }
                return Math.max(1, inSampleSize);
            }

            private String buildCacheKey(@NonNull String imageUrl, int size) {
                return imageUrl + "#" + size;
            }
        }

        private static final int MAX_MARKDOWN_LENGTH = 50_000;
        private static final Pattern BLOCK_MATH_PATTERN = Pattern.compile("(?s)\\$\\$\\s*(.+?)\\s*\\$\\$");
        private static final Pattern INLINE_MATH_PATTERN = Pattern.compile("(?<!\\$)\\$([^$\\n]{1,500})\\$(?!\\$)");
        private static final Pattern TABLE_SEPARATOR_PATTERN = Pattern.compile("^\\s*\\|?(\\s*:?-{3,}:?\\s*\\|)+\\s*:?-{3,}:?\\s*\\|?\\s*$");
        private static final Pattern THEMATIC_BREAK_PATTERN = Pattern.compile("^\\s*([-*_]\\s*){3,}$");
        private static final Pattern ORDERED_LIST_PATTERN = Pattern.compile("^(\\s*)(\\d+)\\.\\s+(.+)$");
        private static final Pattern UNORDERED_LIST_PATTERN = Pattern.compile("^(\\s*)[-*+]\\s+(.+)$");

        private static CharSequence renderMarkdown(String markdown) {
            String source = markdown == null ? "" : markdown.replace("\r\n", "\n");
            if (source.length() > MAX_MARKDOWN_LENGTH) {
                source = source.substring(0, MAX_MARKDOWN_LENGTH) + "…";
            }

            try {
                String normalized = preprocessTables(source);
                normalized = normalizeMath(normalized);

                String html = TextUtils.htmlEncode(normalized);
                html = transformQuoteBlocks(html);
                html = transformListLines(html);
                html = html.replaceAll("```([^`]{0,20000})```", "<pre>$1</pre>");
                html = html.replaceAll("`([^`]{1,1000})`", "<tt><b>$1</b></tt>");
                html = html.replaceAll("\\*\\*([^*]{1,1000})\\*\\*", "<b>$1</b>");
                html = html.replaceAll("(?<!\\*)\\*([^*]{1,1000})\\*(?!\\*)", "<i>$1</i>");
                html = html.replaceAll("\\[([^\\]]{1,400})\\]\\((https?://[^\\)]{1,2000})\\)", "<a href=\"$2\">$1</a>");
                html = html.replaceAll("(?m)^\\s*([-*_]\\s*){3,}$", "<hr/>");
                html = html.replace("\n", "<br/>");

                Spanned spanned = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY);
                return applyQuoteBackground(spanned);
            } catch (Exception e) {
                return source;
            }
        }

        private static CharSequence applyQuoteBackground(Spanned spanned) {
            if (!(spanned instanceof Spannable)) {
                return spanned;
            }

            Spannable spannable = (Spannable) spanned;
            QuoteSpan[] quoteSpans = spannable.getSpans(0, spannable.length(), QuoteSpan.class);
            for (QuoteSpan span : quoteSpans) {
                int start = spannable.getSpanStart(span);
                int end = spannable.getSpanEnd(span);
                int flags = spannable.getSpanFlags(span);
                spannable.removeSpan(span);
                spannable.setSpan(new QuoteSpan(0xFF8E8E8E), start, end, flags);
                spannable.setSpan(new BackgroundColorSpan(0x22FFFFFF), start, end, flags);
            }

            return spannable;
        }

        private static String normalizeMath(String markdown) {
            Matcher blockMatcher = BLOCK_MATH_PATTERN.matcher(markdown);
            StringBuffer blockBuffer = new StringBuffer();
            while (blockMatcher.find()) {
                String expression = blockMatcher.group(1) == null ? "" : blockMatcher.group(1).trim();
                String replacement = "```math\n" + expression + "\n```";
                blockMatcher.appendReplacement(blockBuffer, Matcher.quoteReplacement(replacement));
            }
            blockMatcher.appendTail(blockBuffer);

            Matcher inlineMatcher = INLINE_MATH_PATTERN.matcher(blockBuffer.toString());
            StringBuffer inlineBuffer = new StringBuffer();
            while (inlineMatcher.find()) {
                String expression = inlineMatcher.group(1) == null ? "" : inlineMatcher.group(1).trim();
                String replacement = expression.isEmpty() ? inlineMatcher.group(0) : "`" + expression + "`";
                inlineMatcher.appendReplacement(inlineBuffer, Matcher.quoteReplacement(replacement));
            }
            inlineMatcher.appendTail(inlineBuffer);
            return inlineBuffer.toString();
        }

        private static String transformQuoteBlocks(String encodedMarkdown) {
            String[] lines = encodedMarkdown.split("\n", -1);
            StringBuilder output = new StringBuilder(encodedMarkdown.length() + 64);
            int index = 0;

            while (index < lines.length) {
                String line = lines[index];
                String trimmed = line.trim();
                if (trimmed.startsWith("&gt;")) {
                    StringBuilder quoteBody = new StringBuilder();
                    while (index < lines.length) {
                        String quoteLine = lines[index].trim();
                        if (!quoteLine.startsWith("&gt;")) break;

                        String content = quoteLine.substring(4);
                        if (content.startsWith(" ")) content = content.substring(1);
                        if (quoteBody.length() > 0) quoteBody.append("<br/>");
                        quoteBody.append(content);
                        index++;
                    }
                    output.append("<blockquote>").append(quoteBody).append("</blockquote>");
                    if (index < lines.length) output.append("\n");
                    continue;
                }

                output.append(line);
                if (index < lines.length - 1) output.append("\n");
                index++;
            }

            return output.toString();
        }

        private static String transformListLines(String encodedMarkdown) {
            String[] lines = encodedMarkdown.split("\n", -1);
            StringBuilder output = new StringBuilder(encodedMarkdown.length() + 64);

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                Matcher ordered = ORDERED_LIST_PATTERN.matcher(line);
                Matcher unordered = UNORDERED_LIST_PATTERN.matcher(line);

                if (ordered.matches()) {
                    output.append(renderIndentedListPrefix(ordered.group(1)));
                    output.append(ordered.group(2)).append(". ").append(ordered.group(3));
                } else if (unordered.matches()) {
                    output.append(renderIndentedListPrefix(unordered.group(1)));
                    output.append("• ").append(unordered.group(2));
                } else if (THEMATIC_BREAK_PATTERN.matcher(line.trim()).matches()) {
                    output.append(line.trim());
                } else {
                    output.append(line);
                }

                if (i < lines.length - 1) output.append("\n");
            }

            return output.toString();
        }

        private static String renderIndentedListPrefix(String spaces) {
            int indent = spaces == null ? 0 : spaces.length() / 2;
            if (indent <= 0) return "";

            StringBuilder prefix = new StringBuilder(indent * 24);
            for (int i = 0; i < indent; i++) {
                prefix.append("&nbsp;&nbsp;&nbsp;&nbsp;");
            }
            return prefix.toString();
        }

        private static boolean containsMarkdownTable(String markdown) {
            if (markdown == null || markdown.isEmpty()) return false;
            String[] lines = markdown.replace("\r\n", "\n").split("\n");
            for (int i = 0; i + 1 < lines.length; i++) {
                if (isLikelyTableRow(lines[i]) && isTableSeparator(lines[i + 1])) {
                    return true;
                }
            }
            return false;
        }

        private static String preprocessTables(String markdown) {
            String[] lines = markdown.split("\n", -1);
            StringBuilder output = new StringBuilder(markdown.length() + 64);
            int index = 0;

            while (index < lines.length) {
                if (index + 1 < lines.length && isLikelyTableRow(lines[index]) && isTableSeparator(lines[index + 1])) {
                    ArrayList<String> tableLines = new ArrayList<>();
                    tableLines.add(lines[index]);
                    tableLines.add(lines[index + 1]);
                    index += 2;
                    while (index < lines.length && isLikelyTableRow(lines[index])) {
                        tableLines.add(lines[index]);
                        index++;
                    }

                    String table = renderAlignedTable(tableLines);
                    output.append("```table\n").append(table).append("\n```");
                    if (index < lines.length) output.append("\n");
                    continue;
                }

                output.append(lines[index]);
                if (index < lines.length - 1) output.append("\n");
                index++;
            }

            return output.toString();
        }

        private static boolean isLikelyTableRow(String line) {
            return line != null && line.contains("|");
        }

        private static boolean isTableSeparator(String line) {
            if (line == null) return false;
            return TABLE_SEPARATOR_PATTERN.matcher(line).matches();
        }

        private static String renderAlignedTable(List<String> markdownTableLines) {
            ArrayList<List<String>> rows = new ArrayList<>();
            for (int i = 0; i < markdownTableLines.size(); i++) {
                if (i == 1 && isTableSeparator(markdownTableLines.get(i))) continue;
                rows.add(parseTableCells(markdownTableLines.get(i)));
            }
            if (rows.isEmpty()) return "";

            int columnCount = 0;
            for (List<String> row : rows) {
                columnCount = Math.max(columnCount, row.size());
            }
            if (columnCount == 0) return "";

            int[] widths = new int[columnCount];
            for (int i = 0; i < columnCount; i++) widths[i] = 3;
            for (List<String> row : rows) {
                for (int col = 0; col < columnCount; col++) {
                    String value = col < row.size() ? row.get(col) : "";
                    widths[col] = Math.max(widths[col], value.length());
                }
            }

            StringBuilder out = new StringBuilder();
            out.append(renderTableRow(rows.get(0), widths)).append("\n");
            out.append(renderTableSeparator(widths));
            for (int i = 1; i < rows.size(); i++) {
                out.append("\n").append(renderTableRow(rows.get(i), widths));
            }
            return out.toString();
        }

        private static List<String> parseTableCells(String line) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
            if (trimmed.endsWith("|")) trimmed = trimmed.substring(0, trimmed.length() - 1);

            String[] parts = trimmed.split("\\|", -1);
            ArrayList<String> cells = new ArrayList<>(parts.length);
            for (String part : parts) {
                cells.add(part.trim());
            }
            return cells;
        }

        private static String renderTableRow(List<String> row, int[] widths) {
            StringBuilder line = new StringBuilder();
            for (int col = 0; col < widths.length; col++) {
                String value = col < row.size() ? row.get(col) : "";
                line.append("| ").append(padRight(value, widths[col])).append(" ");
            }
            line.append("|");
            return line.toString();
        }

        private static String renderTableSeparator(int[] widths) {
            StringBuilder line = new StringBuilder();
            for (int width : widths) {
                int span = Math.max(3, width);
                line.append("| ").append(repeat("-", span)).append(" ");
            }
            line.append("|");
            return line.toString();
        }

        private static String padRight(String value, int width) {
            if (value == null) value = "";
            if (value.length() >= width) return value;
            return value + repeat(" ", width - value.length());
        }

        private static String repeat(String text, int count) {
            StringBuilder builder = new StringBuilder(Math.max(0, count) * text.length());
            for (int i = 0; i < count; i++) {
                builder.append(text);
            }
            return builder.toString();
        }
    }
}
