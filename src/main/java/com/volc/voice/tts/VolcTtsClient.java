package com.volc.voice.tts;

import com.volc.voice.ogg.OggDemuxer;
import com.volc.voice.protocol.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Volcengine TTS WebSocket client using bidirectional streaming protocol.
 */
public class VolcTtsClient {

    public interface Callbacks {
        void onOpusPacket(byte[] packet);
        void onOggPage(byte[] page);
        void onSentenceStart();
        void onSentenceEnd();
        void onSessionFinished(String sessionId);
        void onError(String error);
        void onStatus(String status);
    }

    private enum State {
        DISCONNECTED, CONNECTING, CONNECTED, SESSION_ACTIVE, DISCONNECTING
    }

    private static final String DEFAULT_ENDPOINT = "wss://openspeech.bytedance.com/api/v3/tts/bidirection";
    private static final int RECONNECT_MAX_DELAY_MS = 30000;
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final long RECONNECT_LOG_INTERVAL_MS = 5000;

    private final String appId;
    private final String accessToken;
    private final String resourceId;
    private final String voiceType;
    private final String endpoint;
    private final boolean autoReconnect;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "tts-reconnect");
        t.setDaemon(true);
        return t;
    });

    private static final int PING_INTERVAL_SEC = 30;

    private volatile State state = State.DISCONNECTED;
    private volatile boolean stopping;
    private WebSocket ws;
    private Callbacks callbacks;
    private OggDemuxer demuxer = new OggDemuxer();
    private String sessionId;
    private int reconnectAttempts;
    private ScheduledFuture<?> reconnectFuture;
    private ScheduledFuture<?> pingFuture;
    /** Shared connect future to coalesce concurrent connection attempts */
    private CompletableFuture<Void> pendingConnect;
    /** Timestamp of last "Auto-reconnecting" log to avoid log flooding */
    private long lastReconnectLogTime;

    public VolcTtsClient(String appId, String accessToken, String resourceId, String voiceType) {
        this(appId, accessToken, resourceId, voiceType, true, DEFAULT_ENDPOINT);
    }

    public VolcTtsClient(String appId, String accessToken, String resourceId, String voiceType,
                         boolean autoReconnect, String endpoint) {
        this.appId = appId;
        this.accessToken = accessToken;
        this.resourceId = resourceId;
        this.voiceType = voiceType;
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

    public String getSessionId() {
        return sessionId;
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
                        System.err.println("[TTS] WS closed: code=" + statusCode + " reason=\"" + reason + "\" wasActive=" + wasActive);
                        lastReconnectLogTime = now;
                    }
                    stopPing();
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
                        System.err.println("[TTS] WS error: " + error.getMessage());
                        lastReconnectLogTime = now;
                    }
                }
            });

        var future = wsFuture.thenCompose(w ->
            ProtocolHelper.startConnection(w)
                .thenCompose(v -> waitForEvent(EventType.CONNECTION_STARTED))
                .thenAccept(msg -> {
                    state = State.CONNECTED;
                    reconnectAttempts = 0;
                    pendingConnect = null;
                    startPing();
                    if (callbacks != null) callbacks.onStatus("TTS connected");
                    System.out.println("[TTS] Connection established");
                })
        ).exceptionallyCompose(err -> {
            state = State.DISCONNECTED;
            pendingConnect = null;
            long now = System.currentTimeMillis();
            if (now - lastReconnectLogTime > RECONNECT_LOG_INTERVAL_MS) {
                System.err.println("[TTS] Connection failed: " + err.getMessage());
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

    // --- Synthesis ---

    /**
     * Synthesize a complete sentence. Handles the full session lifecycle.
     */
    public CompletableFuture<Void> synthesize(String text) {
        return synthesize(text, voiceType);
    }

    public CompletableFuture<Void> synthesize(String text, String voiceType) {
        if (ws == null || state == State.DISCONNECTED) {
            if (!autoReconnect || stopping) {
                return CompletableFuture.failedFuture(new IllegalStateException("TTS not connected"));
            }
            System.out.println("[TTS] Auto-reconnecting before synthesis...");
            if (reconnectFuture != null) {
                reconnectFuture.cancel(false);
                reconnectFuture = null;
            }
            reconnectAttempts = 0;
            return connect().thenCompose(v -> synthesize(text, voiceType));
        }
        if (state == State.SESSION_ACTIVE) {
            return CompletableFuture.failedFuture(new IllegalStateException("TTS session already active"));
        }

        String voice = voiceType != null ? voiceType : this.voiceType;
        System.out.println("[TTS] synthesize: voiceType=\"" + voiceType
            + "\" fallback=\"" + this.voiceType + "\" finalVoice=\"" + voice + "\"");

        sessionId = UUID.randomUUID().toString();
        demuxer.reset();

        // Start session
        var sessionPayload = String.format(
            "{\"user\":{\"uid\":\"%s\"}," +
            "\"namespace\":\"BidirectionalTTS\"," +
            "\"req_params\":{" +
            "\"speaker\":\"%s\"," +
            "\"audio_params\":{" +
            "\"format\":\"ogg_opus\"," +
            "\"sample_rate\":24000," +
            "\"bit_rate\":32000," +
            "\"enable_timestamp\":false}}}",
            UUID.randomUUID(), voice);

        return ProtocolHelper.startSession(ws,
                sessionPayload.getBytes(StandardCharsets.UTF_8), sessionId)
            .thenCompose(v -> waitForEvent(EventType.SESSION_STARTED))
            .thenAccept(msg -> {
                processAudioPayload(msg);
                state = State.SESSION_ACTIVE;
                System.out.println("[TTS] Session started: " + sessionId);
                if (callbacks != null) callbacks.onStatus("TTS session started: " + sessionId);
            })
            .thenCompose(v -> {
                // Send text and receive audio
                System.out.println("[TTS] Sending text (" + text.length() + " chars): \"" + text + "\"");

                var payload = String.format(
                    "{\"user\":{\"uid\":\"%s\"}," +
                    "\"req_params\":{\"text\":\"%s\"}," +
                    "\"namespace\":\"BidirectionalTTS\"}",
                    UUID.randomUUID(), escapeJson(text));

                return ProtocolHelper.taskRequest(ws,
                    payload.getBytes(StandardCharsets.UTF_8), sessionId)
                    .thenComposeAsync(v2 -> receiveAudioLoop());
            })
            .thenCompose(v -> finishSession());
    }

    public CompletableFuture<Void> finishSession() {
        if (ws == null || sessionId == null) {
            // Connection already lost — ensure cleanup callback fires
            if (callbacks != null && sessionId != null) {
                callbacks.onSessionFinished(sessionId);
            }
            return CompletableFuture.completedFuture(null);
        }
        if (state != State.SESSION_ACTIVE) {
            // Server already finished session (e.g. SESSION_FINISHED received in audio loop).
            // Callback was already invoked there, so just clean up.
            sessionId = null;
            return CompletableFuture.completedFuture(null);
        }

        var oldStopping = stopping;
        stopping = true;

        return ProtocolHelper.finishSession(ws, sessionId)
            .thenCompose(v -> {
                try {
                    return waitForEvent(EventType.SESSION_FINISHED);
                } catch (Exception e) {
                    return CompletableFuture.completedFuture(null);
                }
            })
            .thenAccept(msg -> {
                state = State.CONNECTED;
                stopping = oldStopping;
                if (callbacks != null) callbacks.onSessionFinished(sessionId);
                System.out.println("[TTS] Session finished: " + sessionId);
                sessionId = null;
            });
    }

    public CompletableFuture<Void> cancelSession() {
        if (ws == null || sessionId == null) return CompletableFuture.completedFuture(null);
        if (state != State.SESSION_ACTIVE) return CompletableFuture.completedFuture(null);

        stopping = true;
        return ProtocolHelper.cancelSession(ws, sessionId)
            .thenCompose(v -> {
                try {
                    return waitForEvent(EventType.SESSION_CANCELED);
                } catch (Exception e) {
                    return CompletableFuture.completedFuture(null);
                }
            })
            .thenAccept(msg -> {
                state = State.CONNECTED;
                stopping = false;
                sessionId = null;
                System.out.println("[TTS] Session canceled");
            });
    }

    public void disconnect() {
        stopping = true;
        stopPing();

        if (reconnectFuture != null) {
            reconnectFuture.cancel(false);
            reconnectFuture = null;
        }

        CompletableFuture.runAsync(() -> {
            try {
                if (ws != null && state != State.DISCONNECTED) {
                    if (state == State.SESSION_ACTIVE) {
                        cancelSession().get(3, TimeUnit.SECONDS);
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
            if (callbacks != null) callbacks.onStatus("TTS disconnected");
        });
        scheduler.shutdown();
    }

    // --- Private ---

    private CompletableFuture<Message> waitForEvent(int expectedEvent) {
        return waitForEvent(expectedEvent, 0);
    }

    private CompletableFuture<Message> waitForEvent(int expectedEvent, int skippedCount) {
        return ProtocolHelper.receiveMessage(ws).thenCompose(msg -> {
            if (msg.event == expectedEvent
                && (msg.type == MessageType.FULL_SERVER_RESPONSE
                    || msg.type == MessageType.AUDIO_ONLY_SERVER)) {
                if (skippedCount > 0) {
                    System.out.println("[TTS] Received expected event " + EventType.name(expectedEvent)
                        + " after " + skippedCount + " trailing messages");
                }
                processAudioPayload(msg);
                return CompletableFuture.completedFuture(msg);
            }
            if (msg.type == MessageType.ERROR) {
                String errPayload = msg.payload.length > 0
                    ? new String(msg.payload, StandardCharsets.UTF_8) : "(empty)";
                return CompletableFuture.failedFuture(new RuntimeException(
                    "Server error: code=" + msg.errorCode + ", payload=" + errPayload));
            }
            // Process audio on messages arriving while waiting for expected event
            processAudioPayload(msg);
            System.out.println("[TTS] Processing " + EventType.name(msg.event)
                + " while waiting for " + EventType.name(expectedEvent));
            return waitForEvent(expectedEvent, skippedCount + 1);
        });
    }

    private CompletableFuture<Void> receiveAudioLoop() {
        boolean[] firstAudioPage = {true};
        int sentenceCount = 0;
        int audioMsgCount = 0;
        boolean finished = false;

        try {
            while (ws != null && !stopping) {
                Message msg = ProtocolHelper.receiveMessage(ws).get();

                switch (msg.type) {
                    case MessageType.FULL_SERVER_RESPONSE:
                        if (msg.event == EventType.TTS_SENTENCE_START) {
                            sentenceCount++;
                            if (callbacks != null) callbacks.onSentenceStart();
                            System.out.println("[TTS] Sentence " + sentenceCount + " start");
                        } else if (msg.event == EventType.TTS_SENTENCE_END) {
                            if (callbacks != null) callbacks.onSentenceEnd();
                            System.out.println("[TTS] Sentence " + sentenceCount + " end");
                            if (!finished) {
                                ProtocolHelper.finishSession(ws, sessionId).get();
                                finished = true;
                            }
                        } else if (msg.event == EventType.TTS_ENDED) {
                            System.out.println("[TTS] TTS ended after " + sentenceCount
                                + " sentences, " + audioMsgCount + " audio chunks");
                            if (!finished) {
                                ProtocolHelper.finishSession(ws, sessionId).get();
                                finished = true;
                            }
                        } else if (msg.event == EventType.SESSION_FINISHED) {
                            state = State.CONNECTED;
                            if (callbacks != null) callbacks.onSessionFinished(sessionId);
                            System.out.println("[TTS] SessionFinished received");
                            return CompletableFuture.completedFuture(null);
                        }
                        break;

                    case MessageType.AUDIO_ONLY_SERVER:
                        if (firstAudioPage[0] && msg.payload.length > 0) {
                            var hex = bytesToHex(msg.payload, 32);
                            System.out.println("[DEBUG] First Volcengine audio payload len="
                                + msg.payload.length + " hex: " + hex);
                            firstAudioPage[0] = false;
                        }
                        audioMsgCount++;
                        processAudioPayload(msg);
                        if (msg.event == EventType.TTS_SENTENCE_START) {
                            sentenceCount++;
                            if (callbacks != null) callbacks.onSentenceStart();
                            System.out.println("[TTS] Sentence " + sentenceCount + " start (AudioOnlyServer)");
                        } else if (msg.event == EventType.TTS_SENTENCE_END) {
                            if (callbacks != null) callbacks.onSentenceEnd();
                            System.out.println("[TTS] Sentence " + sentenceCount + " end (AudioOnlyServer)");
                            if (!finished) {
                                ProtocolHelper.finishSession(ws, sessionId).get();
                                finished = true;
                            }
                        } else if (msg.event == EventType.TTS_ENDED) {
                            System.out.println("[TTS] TTS ended after " + sentenceCount
                                + " sentences, " + audioMsgCount + " audio chunks (AudioOnlyServer)");
                            if (!finished) {
                                ProtocolHelper.finishSession(ws, sessionId).get();
                                finished = true;
                            }
                        } else if (msg.event == EventType.SESSION_FINISHED) {
                            state = State.CONNECTED;
                            if (callbacks != null) callbacks.onSessionFinished(sessionId);
                            System.out.println("[TTS] SessionFinished received (AudioOnlyServer)");
                            return CompletableFuture.completedFuture(null);
                        }
                        break;

                    case MessageType.ERROR:
                        int errCode = msg.errorCode != null ? msg.errorCode : 0;
                        String errPayload = msg.payload.length > 0
                            ? new String(msg.payload, StandardCharsets.UTF_8) : "(empty)";
                        System.err.println("[TTS] Error frame: code=" + errCode + ", payload=" + errPayload);
                        break;
                }
            }
        } catch (Exception e) {
            if (!finished && !stopping && callbacks != null) {
                callbacks.onError("TTS receive error: " + e.getMessage());
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    private void processAudioPayload(Message msg) {
        if (msg.payload.length == 0) return;
        if (callbacks != null) callbacks.onOggPage(msg.payload);
        var opusPackets = demuxer.feed(msg.payload);
        for (var packet : opusPackets) {
            if (callbacks != null) callbacks.onOpusPacket(packet);
        }
    }

    private void scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            if (callbacks != null) callbacks.onError("TTS max reconnect attempts (" + MAX_RECONNECT_ATTEMPTS + ") reached");
            return;
        }
        stopPing();
        int delay = Math.min(1000 * (int) Math.pow(2, reconnectAttempts), RECONNECT_MAX_DELAY_MS);
        reconnectAttempts++;
        long now = System.currentTimeMillis();
        if (now - lastReconnectLogTime > RECONNECT_LOG_INTERVAL_MS) {
            String status = "TTS reconnecting in " + (delay / 1000) + "s (attempt " + reconnectAttempts + ")";
            if (callbacks != null) callbacks.onStatus(status);
            System.out.println("[TTS] Reconnecting in " + delay + "ms (attempt " + reconnectAttempts + ")");
            lastReconnectLogTime = now;
        }

        reconnectFuture = scheduler.schedule(() -> {
            reconnectFuture = null;
            if (!stopping) {
                connect().exceptionally(err -> { return null; });
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void startPing() {
        stopPing();
        pingFuture = scheduler.scheduleWithFixedDelay(() -> {
            var w = ws;
            if (w != null && (state == State.CONNECTED || state == State.SESSION_ACTIVE)) {
                w.sendPing(ByteBuffer.allocate(0));
            }
        }, PING_INTERVAL_SEC, PING_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    private void stopPing() {
        if (pingFuture != null) {
            pingFuture.cancel(false);
            pingFuture = null;
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String bytesToHex(byte[] data, int maxLen) {
        var sb = new StringBuilder();
        int len = Math.min(data.length, maxLen);
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02x ", data[i] & 0xFF));
        }
        return sb.toString().trim();
    }
}
