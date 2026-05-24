package com.volc.voice.asr;

import com.volc.voice.protocol.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Volcengine ASR WebSocket client using bidirectional streaming protocol.
 */
public class VolcAsrClient {

    public interface Callbacks {
        void onResult(String text, boolean isFinal);
        void onError(String error);
        void onStatus(String status);
    }

    private enum State {
        DISCONNECTED, CONNECTING, CONNECTED, SESSION_ACTIVE, DISCONNECTING
    }

    private static final String DEFAULT_ENDPOINT = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async";
    private static final int RECONNECT_MAX_DELAY_MS = 30000;
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final long RECONNECT_LOG_INTERVAL_MS = 5000;

    private final String appId;
    private final String accessToken;
    private final String resourceId;
    private final String endpoint;
    private final boolean autoReconnect;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "asr-reconnect");
        t.setDaemon(true);
        return t;
    });

    private volatile State state = State.DISCONNECTED;
    private volatile boolean stopping;
    private WebSocket ws;
    private Callbacks callbacks;
    private String sessionId;
    private int reconnectAttempts;
    private ScheduledFuture<?> reconnectFuture;
    /** Shared connect future to coalesce concurrent connection attempts */
    private CompletableFuture<Void> pendingConnect;
    /** Timestamp of last reconnect log to avoid flooding */
    private long lastReconnectLogTime;

    // Session lifecycle futures (resolved by background receiver)
    private CompletableFuture<Void> sessionStartFuture;
    private CompletableFuture<Void> sessionFinishFuture;

    public VolcAsrClient(String appId, String accessToken, String resourceId) {
        this(appId, accessToken, resourceId, true, DEFAULT_ENDPOINT);
    }

    public VolcAsrClient(String appId, String accessToken, String resourceId,
                         boolean autoReconnect, String endpoint) {
        this.appId = appId;
        this.accessToken = accessToken;
        this.resourceId = resourceId;
        this.autoReconnect = autoReconnect;
        this.endpoint = endpoint;
        this.httpClient = HttpClient.newBuilder().build();
    }

    public void setCallbacks(Callbacks cb) {
        this.callbacks = cb;
    }

    public boolean isConnected() {
        return state == State.CONNECTED || state == State.SESSION_ACTIVE;
    }

    public boolean isSessionActive() {
        return state == State.SESSION_ACTIVE;
    }

    // --- Connection lifecycle ---

    public synchronized CompletableFuture<Void> connect() {
        // Already connected or connecting — return the shared future
        if (state == State.CONNECTED || state == State.SESSION_ACTIVE) {
            return CompletableFuture.completedFuture(null);
        }
        if (state == State.CONNECTING && pendingConnect != null) {
            return pendingConnect;
        }
        if (state != State.DISCONNECTED) {
            return CompletableFuture.completedFuture(null);
        }

        state = State.CONNECTING;
        stopping = false;

        var connectId = UUID.randomUUID().toString();

        CompletableFuture<WebSocket> wsFuture = httpClient.newWebSocketBuilder()
            .header("X-Api-App-Key", appId)
            .header("X-Api-Access-Key", accessToken)
            .header("X-Api-Resource-Id", resourceId)
            .header("X-Api-Connect-Id", connectId)
            .buildAsync(URI.create(endpoint), new WebSocket.Listener() {
                @Override
                public void onOpen(WebSocket w) {
                    ws = w;
                    ProtocolHelper.register(w);
                    w.request(1);
                }

                @Override
                public CompletionStage<?> onBinary(WebSocket w, ByteBuffer data, boolean last) {
                    byte[] bytes = new byte[data.remaining()];
                    data.get(bytes);
                    ProtocolHelper.onBinaryMessage(w, bytes);
                    w.request(1);
                    return null;
                }

                @Override
                public CompletionStage<?> onClose(WebSocket w, int statusCode, String reason) {
                    ProtocolHelper.unregister(w, reason);
                    boolean wasActive = state != State.DISCONNECTED;
                    long now = System.currentTimeMillis();
                    if (wasActive || now - lastReconnectLogTime > RECONNECT_LOG_INTERVAL_MS) {
                        System.err.println("[ASR] WS closed: code=" + statusCode + " reason=\"" + reason + "\" wasActive=" + wasActive);
                        lastReconnectLogTime = now;
                    }
                    state = State.DISCONNECTED;
                    sessionId = null;
                    ws = null;

                    if (wasActive && !stopping && autoReconnect) {
                        scheduleReconnect();
                    }
                    return null;
                }

                @Override
                public void onError(WebSocket w, Throwable error) {
                    long now = System.currentTimeMillis();
                    if (now - lastReconnectLogTime > RECONNECT_LOG_INTERVAL_MS) {
                        System.err.println("[ASR] WS error: " + error.getMessage());
                        lastReconnectLogTime = now;
                    }
                }
            });

        var future = wsFuture.thenCompose(w -> {
            // Protocol handshake
            return ProtocolHelper.startConnection(w)
                .thenCompose(v -> ProtocolHelper.receiveMessage(w))
                .thenAccept(msg -> {
                    if (msg.type == MessageType.FULL_SERVER_RESPONSE
                        && msg.event == EventType.CONNECTION_STARTED) {
                        state = State.CONNECTED;
                        reconnectAttempts = 0;
                        pendingConnect = null;
                        System.out.println("[ASR] Connection established");
                        startReceiver();
                    } else if (msg.type == MessageType.ERROR) {
                        throw new RuntimeException("Server error on connect: code=" + msg.errorCode);
                    } else {
                        throw new RuntimeException("Unexpected message: type="
                            + MessageType.typeName(msg.type) + " event=" + msg.event);
                    }
                });
        }).exceptionallyCompose(err -> {
            state = State.DISCONNECTED;
            pendingConnect = null;
            long now = System.currentTimeMillis();
            if (now - lastReconnectLogTime > RECONNECT_LOG_INTERVAL_MS) {
                System.err.println("[ASR] Connection failed: " + err.getMessage());
                lastReconnectLogTime = now;
            }
            if (!stopping && autoReconnect) {
                scheduleReconnect();
            }
            return CompletableFuture.failedFuture(err);
        });

        pendingConnect = future;
        return future;
    }

    // --- Session lifecycle ---

    public CompletableFuture<Void> startSession() {
        if (ws == null || state == State.DISCONNECTED) {
            return CompletableFuture.failedFuture(new IllegalStateException("ASR not connected"));
        }
        if (state == State.SESSION_ACTIVE) {
            return finishSession().thenCompose(v -> startSession());
        }

        sessionId = UUID.randomUUID().toString();

        var configPayload = String.format(
            "{\"user\":{\"uid\":\"%s\"}," +
            "\"audio\":{\"format\":\"wav\",\"codec\":\"raw\",\"rate\":16000,\"bits\":16,\"channel\":1,\"language\":\"zh-CN\"}," +
            "\"request\":{\"model_name\":\"bigmodel\",\"enable_itn\":true,\"enable_punc\":true," +
            "\"enable_ddc\":true,\"enable_nonstream\":false,\"result_type\":\"single\",\"show_utterances\":true}}",
            UUID.randomUUID());

        sessionStartFuture = new CompletableFuture<>();

        return ProtocolHelper.startSession(ws,
                configPayload.getBytes(StandardCharsets.UTF_8), sessionId)
            .thenCompose(v -> sessionStartFuture)
            .thenAccept(v -> {
                state = State.SESSION_ACTIVE;
                System.out.println("[ASR] Session started: " + sessionId);
            });
    }

    public CompletableFuture<Void> sendAudio(byte[] pcmData, boolean isLast) {
        if (ws == null || state != State.SESSION_ACTIVE) {
            return CompletableFuture.completedFuture(null);
        }
        int flag = isLast ? MessageType.LAST_NO_SEQ : MessageType.NO_SEQ;
        return ProtocolHelper.audioOnly(ws, pcmData, flag);
    }

    public CompletableFuture<Void> finishSession() {
        if (ws == null || sessionId == null) return CompletableFuture.completedFuture(null);
        if (state != State.SESSION_ACTIVE) return CompletableFuture.completedFuture(null);

        sessionFinishFuture = new CompletableFuture<>();

        return ProtocolHelper.finishSession(ws, sessionId)
            .thenCompose(v -> {
                var timeout = CompletableFuture.runAsync(() -> {
                    try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
                return CompletableFuture.anyOf(sessionFinishFuture, timeout);
            })
            .thenAccept(v -> {
                state = State.CONNECTED;
                sessionFinishFuture = null;
                System.out.println("[ASR] Session finished: " + sessionId);
                sessionId = null;
            });
    }

    public void disconnect() {
        stopping = true;

        if (reconnectFuture != null) {
            reconnectFuture.cancel(false);
            reconnectFuture = null;
        }

        CompletableFuture.runAsync(() -> {
            try {
                if (ws != null && state != State.DISCONNECTED) {
                    if (state == State.SESSION_ACTIVE) {
                        finishSession().get(3, TimeUnit.SECONDS);
                    }
                    ProtocolHelper.finishConnection(ws).get(2, TimeUnit.SECONDS);
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "").get(2, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                // ignore
            }
            state = State.DISCONNECTED;
            ws = null;
            sessionId = null;
            if (callbacks != null) callbacks.onStatus("ASR disconnected");
        });
        scheduler.shutdown();
    }

    // --- Private ---

    private void scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            if (callbacks != null) callbacks.onError("ASR max reconnect attempts (" + MAX_RECONNECT_ATTEMPTS + ") reached");
            return;
        }
        int delay = Math.min(1000 * (int) Math.pow(2, reconnectAttempts), RECONNECT_MAX_DELAY_MS);
        reconnectAttempts++;
        long now = System.currentTimeMillis();
        if (now - lastReconnectLogTime > RECONNECT_LOG_INTERVAL_MS) {
            String status = "ASR reconnecting in " + (delay / 1000) + "s (attempt " + reconnectAttempts + ")";
            if (callbacks != null) callbacks.onStatus(status);
            System.out.println("[ASR] Reconnecting in " + delay + "ms (attempt " + reconnectAttempts + ")");
            lastReconnectLogTime = now;
        }

        reconnectFuture = scheduler.schedule(() -> {
            reconnectFuture = null;
            if (!stopping) {
                connect().exceptionally(err -> { return null; });
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void startReceiver() {
        CompletableFuture.runAsync(() -> receiveLoop());
    }

    private void receiveLoop() {
        try {
            while (ws != null && state != State.DISCONNECTED && !stopping) {
                var msg = ProtocolHelper.receiveMessage(ws).get();

                System.out.println("[ASR-DEBUG] msg type=" + MessageType.typeName(msg.type)
                    + " event=" + (msg.event >= 0 ? EventType.name(msg.event) : "none")
                    + " payloadLen=" + msg.payload.length);

                // Session lifecycle events
                if (msg.event == EventType.SESSION_STARTED) {
                    if (sessionStartFuture != null) {
                        sessionStartFuture.complete(null);
                        sessionStartFuture = null;
                    }
                    continue;
                }
                if (msg.event == EventType.SESSION_FINISHED) {
                    if (sessionFinishFuture != null) {
                        sessionFinishFuture.complete(null);
                        sessionFinishFuture = null;
                    }
                    continue;
                }

                switch (msg.type) {
                    case MessageType.FULL_SERVER_RESPONSE:
                        if (msg.payload.length > 0) {
                            parseAsrResult(new String(msg.payload, StandardCharsets.UTF_8));
                        }
                        break;
                    case MessageType.AUDIO_ONLY_SERVER:
                        if (msg.payload.length > 0 && callbacks != null) {
                            callbacks.onResult(new String(msg.payload, StandardCharsets.UTF_8), false);
                        }
                        break;
                    case MessageType.FRONT_END_RESULT_SERVER:
                        if (msg.payload.length > 0) {
                            parseFrontEndResult(new String(msg.payload, StandardCharsets.UTF_8));
                        }
                        break;
                    case MessageType.ERROR:
                        int errCode = msg.errorCode != null ? msg.errorCode : 0;
                        String errPayload = msg.payload.length > 0
                            ? new String(msg.payload, StandardCharsets.UTF_8) : "(empty)";
                        System.err.println("[ASR] Error frame: code=" + errCode + ", payload=" + errPayload);

                        if (sessionStartFuture != null && !sessionStartFuture.isDone()) {
                            sessionStartFuture.completeExceptionally(
                                new RuntimeException("ASR StartSession failed: code=" + errCode + ", payload=" + errPayload));
                        }
                        if (errCode == 45000081) {
                            state = State.CONNECTED;
                            sessionId = null;
                            System.out.println("[ASR] Session timed out (idle), reset to Connected");
                        }
                        break;
                }
            }
        } catch (Exception e) {
            if (!stopping) {
                System.err.println("[ASR] Receive error: " + e.getMessage());
            }
        }
    }

    private void parseAsrResult(String text) {
        if (callbacks == null) return;
        try {
            var json = new org.json.JSONObject(text);

            // payload_msg.result
            if (json.has("payload_msg")) {
                var pm = json.getJSONObject("payload_msg");
                if (pm.has("result")) {
                    var results = pm.getJSONArray("result");
                    for (int i = 0; i < results.length(); i++) {
                        var r = results.getJSONObject(i);
                        if (r.has("text")) {
                            callbacks.onResult(r.getString("text"), r.optBoolean("is_final", false));
                        }
                    }
                }
                if (pm.has("utterances")) {
                    var utterances = pm.getJSONArray("utterances");
                    for (int i = 0; i < utterances.length(); i++) {
                        var u = utterances.getJSONObject(i);
                        if (u.has("text")) {
                            callbacks.onResult(u.getString("text"), u.optBoolean("definite", false));
                        }
                    }
                }
            }
        } catch (org.json.JSONException e) {
            // Not JSON, skip
        }
    }

    private void parseFrontEndResult(String text) {
        if (callbacks == null) return;
        try {
            var json = new org.json.JSONObject(text);
            if (json.has("result")) {
                var r = json.getJSONObject("result");
                if (r.has("text")) {
                    callbacks.onResult(r.getString("text"), r.optBoolean("is_final", false));
                }
            }
        } catch (org.json.JSONException e) {
            // Not JSON, skip
        }
    }
}
