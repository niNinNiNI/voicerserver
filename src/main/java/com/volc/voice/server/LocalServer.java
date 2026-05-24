package com.volc.voice.server;

import com.volc.voice.asr.AucAsrClient;
import com.volc.voice.tts.VolcTtsClient;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class LocalServer {

    public static class Options {
        public int port = 3000;
        public String appId;
        public String accessToken;
        public String asrResourceId = "volc.bigasr.auc";
        public String ttsResourceId = "volc.service_type.10029";
        public String voiceType = "zh_female_shuangkuaisisi_moon_bigtts";
        public int ttsSampleRate = 24000;
    }

    private final Options options;
    private final AucAsrClient asr;
    private final VolcTtsClient tts;
    private final Server server;
    private final Path recordingsDir;

    private volatile Session esp32Session;
    private volatile boolean asrRecording;
    private volatile boolean ttsSynthesizing;
    private int ttsPacketsSent;
    private final ByteArrayOutputStream oggBuffer = new ByteArrayOutputStream();

    // PCM buffering for complete file ASR
    private final ByteArrayOutputStream pcmBuffer = new ByteArrayOutputStream();
    private int pcmChunkCount;
    private int pcmDiagCount;
    private int lastRecordingBytes;
    private int lastRecordingChunks;

    // Serial queue for JSON control messages
    private CompletableFuture<Void> msgQueue = CompletableFuture.completedFuture(null);

    public LocalServer(Options options) {
        this.options = options;
        this.recordingsDir = Paths.get("recordings");

        this.asr = new AucAsrClient(
            options.appId, options.accessToken);
        this.tts = new VolcTtsClient(
            options.appId, options.accessToken, options.ttsResourceId, options.voiceType);

        this.server = new Server(options.port);
        setupCallbacks();
    }

    // --- Callback wiring ---

    private void setupCallbacks() {
        asr.setCallbacks(new AucAsrClient.Callbacks() {
            @Override
            public void onResult(String text, boolean isFinal) {
                System.out.println("[ASR] " + (isFinal ? "FINAL" : "PARTIAL") + ": \"" + text + "\"");
                sendToEsp(new JSONObject()
                    .put("type", "asr_result")
                    .put("text", text)
                    .put("is_final", isFinal));

            }

            @Override
            public void onError(String error) {
                System.err.println("[ASR] Error: " + error);
                sendToEsp(new JSONObject().put("type", "error").put("message", error));
            }

            @Override
            public void onStatus(String status) {
                System.out.println("[ASR] Status: " + status);
            }
        });

        tts.setCallbacks(new VolcTtsClient.Callbacks() {
            @Override
            public void onOpusPacket(byte[] packet) {
                ttsPacketsSent++;
                var s = esp32Session;
                if (s != null && s.isOpen()) {
                    try {
                        s.getRemote().sendBytes(ByteBuffer.wrap(packet));
                        if (ttsPacketsSent % 50 == 1) {
                            System.out.println("[TTS] Sent " + ttsPacketsSent + " Opus packets to ESP32 (last: " + packet.length + " bytes)");
                        }
                    } catch (IOException e) {
                        System.err.println("[TTS] Send error: " + e.getMessage());
                    }
                } else if (s == null) {
                    System.err.println("[TTS] Cannot send Opus packet " + ttsPacketsSent + ": ESP32 not connected");
                } else {
                    System.err.println("[TTS] Cannot send Opus packet " + ttsPacketsSent + ": ESP32 session closed");
                }
            }

            @Override
            public void onOggPage(byte[] page) {
                try {
                    oggBuffer.write(page);
                } catch (IOException e) {
                    // shouldn't happen with ByteArrayOutputStream
                }
            }

            @Override
            public void onSentenceStart() {
                System.out.println("[TTS] Sentence started");
            }

            @Override
            public void onSentenceEnd() {
                System.out.println("[TTS] Sentence ended");
            }

            @Override
            public void onSessionFinished(String sessionId) {
                System.out.println("[TTS] Playback complete — total Opus packets sent: " + ttsPacketsSent);
                sendToEsp(new JSONObject().put("type", "tts_end"));
                ttsSynthesizing = false;
                saveRecording(sessionId);
            }

            @Override
            public void onError(String error) {
                System.err.println("[TTS] Error: " + error);
                sendToEsp(new JSONObject().put("type", "error").put("message", error));
                ttsSynthesizing = false;
            }

            @Override
            public void onStatus(String status) {
                System.out.println("[TTS] Status: " + status);
            }
        });
    }

    // --- Server lifecycle ---

    public void start() throws Exception {
        // Connect to cloud services
        CompletableFuture.allOf(asr.connect(), tts.connect())
            .thenAccept(v -> System.out.println("[Server] Cloud connections established"))
            .exceptionally(err -> {
                System.err.println("[Server] Failed to connect to cloud: " + err.getMessage());
                return null;
            });

        // Setup HTTP servlet for health endpoint
        var context = new ServletContextHandler();
        context.setContextPath("/");

        context.addServlet(new ServletHolder(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.setHeader("Access-Control-Allow-Origin", "*");
                resp.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                resp.setHeader("Access-Control-Allow-Headers", "Content-Type");

                if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
                    resp.setStatus(204);
                    return;
                }

                if ("GET".equalsIgnoreCase(req.getMethod()) && "/health".equals(req.getPathInfo())) {
                    var json = new JSONObject()
                        .put("status", "ok")
                        .put("esp32Connected", esp32Session != null && esp32Session.isOpen())
                        .put("asrConnected", asr.isConnected())
                        .put("asrRecording", asrRecording)
                        .put("lastRecordingBytes", lastRecordingBytes)
                        .put("lastRecordingChunks", lastRecordingChunks)
                        .put("ttsConnected", tts.isConnected())
                        .put("ttsSynthesizing", ttsSynthesizing)
                        .put("voiceType", options.voiceType);
                    resp.setContentType("application/json");
                    resp.setStatus(200);
                    resp.getWriter().write(json.toString());
                    return;
                }

                resp.setContentType("application/json");
                resp.setStatus(404);
                resp.getWriter().write("{\"error\":\"not found\"}");
            }
        }), "/*");

        server.setHandler(context);

        // Configure WebSocket on the same context
        JettyWebSocketServletContainerInitializer.configure(context, (servletContext, wsContainer) -> {
            wsContainer.setIdleTimeout(Duration.ofMinutes(10));
            wsContainer.addMapping("/", (req, resp) -> new Esp32SocketHandler());
        });

        server.start();
        System.out.println("[Server] Listening on port " + options.port);
        printLocalIps();

        // Graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[Server] Shutting down...");
            stop();
        }));
    }

    public void stop() {
        try {
            tts.disconnect();
            asr.disconnect();
            server.stop();
        } catch (Exception e) {
            System.err.println("[Server] Stop error: " + e.getMessage());
        }
    }

    // --- ASR -> TTS pipeline ---

    private void triggerTts(String text) {
        if (ttsSynthesizing) {
            System.out.println("[Server] TTS already synthesizing, skipping");
            sendToEsp(new JSONObject().put("type", "tts_busy"));
            return;
        }

        System.out.println("[DEBUG] triggerTts: text=\"" + text + "\" voice=\"" + options.voiceType + "\"");
        ttsSynthesizing = true;
        ttsPacketsSent = 0;
        oggBuffer.reset();

        var msg = new JSONObject()
            .put("type", "tts_start")
            .put("sample_rate", options.ttsSampleRate)
            .put("channels", 1);
        sendToEsp(msg);

        synthesizeWithRetry(text, 1);
    }

    private void synthesizeWithRetry(String text, int retriesLeft) {
        tts.synthesize(text)
            .whenComplete((result, err) -> {
                if (err != null) {
                    System.err.println("[Server] TTS synthesis error: " + err.getMessage()
                        + " (retries left: " + retriesLeft + ")");
                    if (retriesLeft > 0) {
                        // Wait for reconnect, then retry
                        CompletableFuture.delayedExecutor(1500, TimeUnit.MILLISECONDS)
                            .execute(() -> synthesizeWithRetry(text, retriesLeft - 1));
                        return;
                    }
                    sendToEsp(new JSONObject().put("type", "error").put("message", "TTS error: " + err.getMessage()));
                }
                // Safety net: ensure flag is reset even if onSessionFinished wasn't called
                if (ttsSynthesizing) {
                    System.err.println("[Server] TTS synthesizing flag still set after synthesis — resetting");
                    sendToEsp(new JSONObject().put("type", "tts_end"));
                    ttsSynthesizing = false;
                }
            });
    }

    // --- ESP32 WebSocket handler ---

    private class Esp32SocketHandler implements WebSocketListener {
        @Override
        public void onWebSocketConnect(Session session) {
            System.out.println("[Server] ESP32 connected");
            session.setIdleTimeout(Duration.ofMinutes(10));

            if (esp32Session != null && esp32Session != session) {
                esp32Session.close();
            }
            esp32Session = session;

            sendToEsp(new JSONObject()
                .put("type", "status")
                .put("message", "Connected to server"));
        }

        @Override
        public void onWebSocketBinary(byte[] payload, int offset, int length) {
            // Binary PCM — fast-path, no serial queue
            byte[] data = new byte[length];
            System.arraycopy(payload, offset, data, 0, length);

            try {
                new JSONObject(new String(data)); // try to parse as JSON
            } catch (Exception e) {
                // Not JSON — binary PCM
                handleEspBinary(data);
                return;
            }
            // JSON control — serialize
            msgQueue = msgQueue.thenAccept(v -> handleEspMessage(data));
        }

        @Override
        public void onWebSocketText(String message) {
            msgQueue = msgQueue.thenAccept(v -> handleEspMessage(message.getBytes()));
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason) {
            System.out.println("[Server] ESP32 disconnected");
            esp32Session = null;

            if (pcmChunkCount > 0) {
                System.out.println("[Server] ASR total PCM: " + pcmChunkCount
                    + " chunks, " + pcmBuffer.size() + " bytes");
            }
            asrRecording = false;
        }

        @Override
        public void onWebSocketError(Throwable error) {
            System.err.println("[Server] ESP32 WS error: " + error.getMessage());
        }
    }

    // --- ESP32 message handling ---

    private void handleEspMessage(byte[] data) {
        var json = new JSONObject(new String(data));
        String type = json.optString("type");

        switch (type) {
            case "asr_start":
                System.out.println("[Server] ESP32 ASR start (file mode)");
                asrRecording = true;
                pcmChunkCount = 0;
                pcmDiagCount = 0;
                pcmBuffer.reset();
                break;

            case "asr_stop":
                System.out.println("[Server] ESP32 ASR stop");
                asrRecording = false;

                byte[] pcmData = pcmBuffer.toByteArray();
                lastRecordingBytes = pcmData.length;
                lastRecordingChunks = pcmChunkCount;
                System.out.println("[Server] ASR collected " + pcmData.length + " bytes in "
                    + pcmChunkCount + " chunks");

                if (pcmData.length > 0) {
                    savePcmRecording(pcmData);
                    asr.recognize(pcmData, 16000, 16, 1)
                        .exceptionally(err -> {
                            System.err.println("[Server] AUC recognition error: " + err.getMessage());
                            sendToEsp(new JSONObject().put("type", "error")
                                .put("message", "ASR error: " + err.getMessage()));
                            return null;
                        });
                }
                break;

            case "tts_request":
                String text = json.optString("text", "测试语音");
                String voice = json.optString("voice", null);
                System.out.println("[Server] ESP32 TTS request: \"" + text + "\"");
                triggerTts(text);
                break;

            case "ping":
                sendToEsp(new JSONObject().put("type", "pong"));
                break;

            default:
                System.err.println("[Server] Unknown ESP message type: " + type);
                break;
        }
    }

    // Fast-path for binary PCM — buffer for complete file submission
    private void handleEspBinary(byte[] data) {
        if (!asrRecording) return;

        try {
            pcmBuffer.write(data);
            pcmChunkCount++;
            pcmDiagCount++;

            // Diagnostic: print sample values periodically
            if (pcmDiagCount == 1 || pcmDiagCount % 50 == 0) {
                int sampleCount = Math.min(8, data.length / 2);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < sampleCount * 2; i += 2) {
                    short sample = (short) ((data[i + 1] << 8) | (data[i] & 0xFF));
                    sb.append(String.format("%6d ", sample));
                }
                System.out.printf("[DEBUG] PCM chunk #%d (%d bytes): samples=[%s]%n",
                    pcmChunkCount, data.length, sb.toString().trim());
            }

            // All-zero detection: microphone may be silent or muted
            boolean allZero = true;
            for (byte b : data) {
                if (b != 0) { allZero = false; break; }
            }
            if (allZero) {
                System.out.println("[WARN] PCM chunk #" + pcmChunkCount
                    + " is ALL ZERO — microphone may be silent or muted!");
            }

            // All-FF detection: I2S pin may be floating
            boolean allFF = true;
            for (byte b : data) {
                if (b != (byte) 0xFF) { allFF = false; break; }
            }
            if (allFF) {
                System.out.println("[WARN] PCM chunk #" + pcmChunkCount
                    + " is ALL 0xFF — I2S DIN may be floating!");
            }
        } catch (IOException e) {
            // ByteArrayOutputStream doesn't throw
        }
    }

    private void savePcmRecording(byte[] pcmData) {
        if (pcmData.length == 0) return;

        try {
            Files.createDirectories(recordingsDir);
            var ts = LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss-SSS"));
            var filename = "pcm_" + ts + "_" + pcmData.length + "b.pcm";
            var filepath = recordingsDir.resolve(filename);
            Files.write(filepath, pcmData,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            System.out.println("[DEBUG] PCM recording saved: " + filepath);
            System.out.println("[DEBUG] Playback: ffplay -f s16le -ar 16000 -ac 1 "
                + filepath.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("[Server] Failed to save PCM: " + e.getMessage());
        }
    }

    // --- ESP32 helpers ---

    private void sendToEsp(JSONObject msg) {
        var s = esp32Session;
        if (s != null && s.isOpen()) {
            try {
                s.getRemote().sendString(msg.toString());
            } catch (IOException e) {
                System.err.println("[Server] Send to ESP error: " + e.getMessage());
            }
        }
    }

    private void saveRecording(String sessionId) {
        if (oggBuffer.size() == 0) return;

        try {
            Files.createDirectories(recordingsDir);
            var ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss-SSS"));
            var filename = "tts_" + ts + "_" + sessionId.substring(0, Math.min(8, sessionId.length())) + ".ogg";
            var filepath = recordingsDir.resolve(filename);
            Files.write(filepath, oggBuffer.toByteArray(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            System.out.println("[Server] Recording saved: " + filepath + " (" + oggBuffer.size() + " bytes)");
        } catch (IOException e) {
            System.err.println("[Server] Failed to save recording: " + e.getMessage());
        }
        oggBuffer.reset();
    }

    private void printLocalIps() {
        try {
            System.out.println("[Server] Local IPs:");
            var interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                var iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                var addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    var addr = addresses.nextElement();
                    if (addr instanceof Inet4Address) {
                        System.out.println("  http://" + addr.getHostAddress() + ":" + options.port);
                        System.out.println("  ws://" + addr.getHostAddress() + ":" + options.port);
                    }
                }
            }
            System.out.println("  http://localhost:" + options.port);
            System.out.println("  ws://localhost:" + options.port);
            System.out.println("[Server] Health check: http://localhost:" + options.port + "/health");
        } catch (SocketException e) {
            System.err.println("[Server] Failed to list interfaces: " + e.getMessage());
        }
    }
}
