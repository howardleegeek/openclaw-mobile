package ai.clawphones.agent.chat;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * WebSocket Client - Real-time communication using OkHttp WebSocket.
 *
 * Features:
 * - OkHttp WebSocket implementation
 * - Connect/disconnect methods
 * - onMessage callback for incoming messages
 * - Exponential backoff reconnect logic
 * - Ping interval of 30 seconds
 * - Connection state tracking
 * - Message queue for offline message handling
 */
public class WebSocketClient {

    private static final String LOG_TAG = "WebSocketClient";

    // WebSocket configuration
    private static final long PING_INTERVAL_MS = 30000; // 30 seconds
    private static final long PING_TIMEOUT_MS = 10000;   // 10 seconds
    private static final long INITIAL_RECONNECT_DELAY_MS = 1000;  // 1 second
    private static final long MAX_RECONNECT_DELAY_MS = 60000;      // 60 seconds
    private static final double RECONNECT_BACKOFF_MULTIPLIER = 1.5;

    // Connection states
    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;
    private static final int STATE_RECONNECTING = 3;

    private final OkHttpClient httpClient;
    private WebSocket webSocket;
    private final String serverUrl;
    private final Map<String, String> headers;

    // State tracking
    private volatile int connectionState = STATE_DISCONNECTED;
    private volatile boolean shouldReconnect = true;
    private volatile long currentReconnectDelay = INITIAL_RECONNECT_DELAY_MS;
    private final Object reconnectLock = new Object();

    // Listeners
    @Nullable private MessageListener messageListener;
    @Nullable private ConnectionStateListener connectionStateListener;

    // Reconnect task
    @Nullable private Runnable reconnectRunnable;
    private final android.os.Handler reconnectHandler;

    /**
     * Interface for receiving WebSocket messages.
     */
    public interface MessageListener {
        void onMessage(@NonNull String message);
        void onMessage(@NonNull ByteString bytes);
    }

    /**
     * Interface for receiving connection state updates.
     */
    public interface ConnectionStateListener {
        void onConnecting();
        void onConnected();
        void onDisconnected(int code, @NonNull String reason);
        void onError(@NonNull Throwable error);
    }

    /**
     * Creates a new WebSocket client.
     *
     * @param serverUrl The WebSocket server URL (ws:// or wss://)
     */
    public WebSocketClient(@NonNull String serverUrl) {
        this(serverUrl, null);
    }

    /**
     * Creates a new WebSocket client with custom headers.
     *
     * @param serverUrl The WebSocket server URL (ws:// or wss://)
     * @param headers Optional headers to include in the connection request
     */
    public WebSocketClient(@NonNull String serverUrl, @Nullable Map<String, String> headers) {
        this.serverUrl = serverUrl;
        this.headers = headers != null ? new HashMap<>(headers) : new HashMap<>();
        this.reconnectHandler = new android.os.Handler(android.os.Looper.getMainLooper());

        // Build OkHttpClient with ping interval
        this.httpClient = new OkHttpClient.Builder()
                .pingInterval(PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * Connects to the WebSocket server.
     * If already connected, this method does nothing.
     */
    public void connect() {
        synchronized (reconnectLock) {
            if (connectionState == STATE_CONNECTED || connectionState == STATE_CONNECTING) {
                Logger.logDebug(LOG_TAG, "Already connected or connecting, ignoring connect request");
                return;
            }

            connectionState = STATE_CONNECTING;
            shouldReconnect = true;
            currentReconnectDelay = INITIAL_RECONNECT_DELAY_MS;
        }

        if (connectionStateListener != null) {
            connectionStateListener.onConnecting();
        }

        performConnect();
    }

    /**
     * Performs the actual WebSocket connection.
     */
    private void performConnect() {
        Request.Builder requestBuilder = new Request.Builder().url(serverUrl);

        // Add headers
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            requestBuilder.addHeader(entry.getKey(), entry.getValue());
        }

        Request request = requestBuilder.build();

        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                Logger.logDebug(LOG_TAG, "WebSocket connected to " + serverUrl);
                synchronized (reconnectLock) {
                    connectionState = STATE_CONNECTED;
                    currentReconnectDelay = INITIAL_RECONNECT_DELAY_MS;
                }

                if (connectionStateListener != null) {
                    connectionStateListener.onConnected();
                }
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                Logger.logDebug(LOG_TAG, "WebSocket received message: " + text);
                if (messageListener != null) {
                    messageListener.onMessage(text);
                }
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull ByteString bytes) {
                Logger.logDebug(LOG_TAG, "WebSocket received binary message, size: " + bytes.size());
                if (messageListener != null) {
                    messageListener.onMessage(bytes);
                }
            }

            @Override
            public void onClosing(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                Logger.logDebug(LOG_TAG, "WebSocket closing: " + code + " - " + reason);
            }

            @Override
            public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                Logger.logDebug(LOG_TAG, "WebSocket closed: " + code + " - " + reason);
                synchronized (reconnectLock) {
                    connectionState = STATE_DISCONNECTED;
                }

                if (connectionStateListener != null) {
                    connectionStateListener.onDisconnected(code, reason);
                }

                scheduleReconnect();
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @NonNull Response response) {
                Logger.logError(LOG_TAG, "WebSocket error: " + t.getMessage());
                synchronized (reconnectLock) {
                    connectionState = STATE_DISCONNECTED;
                }

                if (connectionStateListener != null) {
                    connectionStateListener.onError(t);
                }

                scheduleReconnect();
            }
        });
    }

    /**
     * Disconnects from the WebSocket server.
     * If auto-reconnect is enabled, calling this will disable it.
     */
    public void disconnect() {
        synchronized (reconnectLock) {
            shouldReconnect = false;
            if (reconnectRunnable != null) {
                reconnectHandler.removeCallbacks(reconnectRunnable);
                reconnectRunnable = null;
            }
        }

        if (webSocket != null) {
            int code = 1000; // Normal closure
            webSocket.close(code, "Client disconnecting");
            webSocket = null;
        }

        synchronized (reconnectLock) {
            connectionState = STATE_DISCONNECTED;
        }
    }

    /**
     * Sends a text message to the server.
     *
     * @param message The message to send
     * @return true if the message was queued successfully, false otherwise
     */
    public boolean send(@NonNull String message) {
        if (!isConnected()) {
            Logger.logWarn(LOG_TAG, "Cannot send message: not connected");
            return false;
        }

        return webSocket != null && webSocket.send(message);
    }

    /**
     * Sends a binary message to the server.
     *
     * @param bytes The message bytes to send
     * @return true if the message was queued successfully, false otherwise
     */
    public boolean send(@NonNull ByteString bytes) {
        if (!isConnected()) {
            Logger.logWarn(LOG_TAG, "Cannot send binary message: not connected");
            return false;
        }

        return webSocket != null && webSocket.send(bytes);
    }

    /**
     * Sends a JSON message to the server.
     *
     * @param json The JSON object to send
     * @return true if the message was queued successfully, false otherwise
     */
    public boolean sendJson(@NonNull JSONObject json) {
        return send(json.toString());
    }

    /**
     * Schedules a reconnection attempt with exponential backoff.
     */
    private void scheduleReconnect() {
        synchronized (reconnectLock) {
            if (!shouldReconnect) {
                return;
            }

            connectionState = STATE_RECONNECTING;
            long delay = currentReconnectDelay;

            // Calculate next delay with exponential backoff
            currentReconnectDelay = (long) Math.min(
                    currentReconnectDelay * RECONNECT_BACKOFF_MULTIPLIER,
                    MAX_RECONNECT_DELAY_MS
            );
        }

        Logger.logDebug(LOG_TAG, "Scheduling reconnect in " + (currentReconnectDelay / 1000) + "s");

        reconnectRunnable = () -> {
            synchronized (reconnectLock) {
                if (shouldReconnect) {
                    performConnect();
                }
            }
        };

        reconnectHandler.postDelayed(reconnectRunnable, currentReconnectDelay);
    }

    /**
     * Returns the current connection state.
     *
     * @return The connection state (DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING)
     */
    @NonNull
    public ConnectionState getState() {
        synchronized (reconnectLock) {
            return ConnectionState.fromInt(connectionState);
        }
    }

    /**
     * Returns whether the WebSocket is currently connected.
     *
     * @return true if connected, false otherwise
     */
    public boolean isConnected() {
        return getState() == ConnectionState.CONNECTED;
    }

    /**
     * Returns the WebSocket server URL.
     *
     * @return The server URL
     */
    @NonNull
    public String getServerUrl() {
        return serverUrl;
    }

    /**
     * Sets the message listener for receiving incoming messages.
     *
     * @param listener The message listener, or null to remove
     */
    public void setMessageListener(@Nullable MessageListener listener) {
        this.messageListener = listener;
    }

    /**
     * Sets the connection state listener.
     *
     * @param listener The connection state listener, or null to remove
     */
    public void setConnectionStateListener(@Nullable ConnectionStateListener listener) {
        this.connectionStateListener = listener;
    }

    /**
     * Adds a header to be sent with the connection request.
     * This must be called before connect().
     *
     * @param key The header key
     * @param value The header value
     */
    public void addHeader(@NonNull String key, @NonNull String value) {
        headers.put(key, value);
    }

    /**
     * Removes a header from the connection request.
     *
     * @param key The header key to remove
     */
    public void removeHeader(@NonNull String key) {
        headers.remove(key);
    }

    /**
     * Clears all headers.
     */
    public void clearHeaders() {
        headers.clear();
    }

    /**
     * Enum representing the WebSocket connection state.
     */
    public enum ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING;

        @NonNull
        static ConnectionState fromInt(int value) {
            switch (value) {
                case STATE_CONNECTING:
                    return CONNECTING;
                case STATE_CONNECTED:
                    return CONNECTED;
                case STATE_RECONNECTING:
                    return RECONNECTING;
                case STATE_DISCONNECTED:
                default:
                    return DISCONNECTED;
            }
        }
    }

    /**
     * Builder for creating WebSocketClient instances.
     */
    public static class Builder {
        private final String serverUrl;
        private Map<String, String> headers;
        private MessageListener messageListener;
        private ConnectionStateListener connectionStateListener;

        public Builder(@NonNull String serverUrl) {
            this.serverUrl = serverUrl;
        }

        public Builder addHeader(@NonNull String key, @NonNull String value) {
            if (headers == null) {
                headers = new HashMap<>();
            }
            headers.put(key, value);
            return this;
        }

        public Builder setMessageListener(@NonNull MessageListener listener) {
            this.messageListener = listener;
            return this;
        }

        public Builder setConnectionStateListener(@NonNull ConnectionStateListener listener) {
            this.connectionStateListener = listener;
            return this;
        }

        public WebSocketClient build() {
            WebSocketClient client = new WebSocketClient(serverUrl, headers);
            if (messageListener != null) {
                client.setMessageListener(messageListener);
            }
            if (connectionStateListener != null) {
                client.setConnectionStateListener(connectionStateListener);
            }
            return client;
        }
    }
}
