package com.volc.voice.asr;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Volcengine AUC (Audio Understanding and Comprehension) ASR client
 * using HTTP-based complete file recognition.
 *
 * Flow:
 *   1. Collect complete PCM audio from ESP32
 *   2. Convert to WAV, base64-encode
 *   3. POST to /api/v3/auc/bigmodel/submit
 *   4. Poll /api/v3/auc/bigmodel/query until result is ready
 *   5. Parse and return transcription
 */
public class AucAsrClient {

    public interface Callbacks {
        void onResult(String text, boolean isFinal);
        void onError(String error);
        void onStatus(String status);
    }

    private static final String SUBMIT_URL = "https://openspeech-direct.zijieapi.com/api/v3/auc/bigmodel/submit";
    private static final String QUERY_URL = "https://openspeech-direct.zijieapi.com/api/v3/auc/bigmodel/query";
    private static final String RESOURCE_ID = "volc.bigasr.auc";

    private static final int MAX_RETRIES = 3;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(30);

    private final String appId;
    private final String accessToken;
    private final HttpClient httpClient;
    private volatile Callbacks callbacks;
    private volatile boolean stopping;

    public AucAsrClient(String appId, String accessToken) {
        this.appId = appId;
        this.accessToken = accessToken;
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
    }

    public void setCallbacks(Callbacks cb) {
        this.callbacks = cb;
    }

    public CompletableFuture<Void> connect() {
        if (callbacks != null) callbacks.onStatus("AUC ASR ready (HTTP mode)");
        return CompletableFuture.completedFuture(null);
    }

    public boolean isConnected() {
        return true; // HTTP API is stateless, always available
    }

    public void disconnect() {
        stopping = true;
    }

    /**
     * Recognize complete audio file using AUC HTTP API.
     * Converts raw PCM to WAV, submits, and polls for the final transcription.
     */
    public CompletableFuture<Void> recognize(byte[] pcmData, int sampleRate, int bitsPerSample, int channels) {
        if (pcmData == null || pcmData.length == 0) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            byte[] wavData = pcmToWav(pcmData, sampleRate, bitsPerSample, channels);
            return Base64.getEncoder().encodeToString(wavData);
        }).thenCompose(base64Audio -> {
            String taskId = UUID.randomUUID().toString();
            return submitTask(base64Audio, sampleRate, taskId)
                .thenCompose(taskInfo -> pollTask(taskInfo[0], taskInfo[1]));
        });
    }

    // --- Private ---

    private CompletableFuture<String[]> submitTask(String base64Audio, int sampleRate, String initialTaskId) {
        final String[] taskIdHolder = new String[]{initialTaskId};
        return CompletableFuture.supplyAsync(() -> {
            JSONObject payload = new JSONObject()
                .put("user", new JSONObject().put("uid", UUID.randomUUID().toString()))
                .put("audio", new JSONObject()
                    .put("data", base64Audio)
                    .put("format", "wav")
                    .put("codec", "raw")
                    .put("rate", sampleRate)
                    .put("bits", 16)
                    .put("channel", 1)
                    .put("language", "zh-CN"))
                .put("request", new JSONObject()
                    .put("model_name", "bigmodel")
                    .put("enable_itn", true)
                    .put("enable_punc", true)
                    .put("enable_ddc", true)
                    .put("enable_speaker_info", true)
                    .put("show_utterances", true));

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(SUBMIT_URL))
                .timeout(REQUEST_TIMEOUT)
                .header("X-Api-App-Key", appId)
                .header("X-Api-Access-Key", accessToken)
                .header("X-Api-Resource-Id", RESOURCE_ID)
                .header("X-Api-Request-Id", taskIdHolder[0])
                .header("X-Api-Sequence", "-1")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

            Exception lastException = null;
            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                try {
                    if (attempt > 1) {
                        long delay = (long) Math.pow(2, attempt - 1) * 1000;
                        System.out.println("[AUC] Submit retry " + attempt + "/" + MAX_RETRIES
                            + " after " + delay + "ms...");
                        Thread.sleep(delay);
                    }

                    System.out.println("[AUC] Submitting task: " + taskIdHolder[0]
                        + ", audio=" + base64Audio.length() + " chars base64"
                        + (attempt > 1 ? " (attempt " + attempt + ")" : ""));

                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                    String code = response.headers().firstValue("X-Api-Status-Code").orElse("");
                    String message = response.headers().firstValue("X-Api-Message").orElse("");
                    String xTtLogid = response.headers().firstValue("X-Tt-Logid").orElse("");

                    System.out.println("[AUC] Submit response: code=" + code
                        + " message=" + message + " logid=" + xTtLogid);

                    // Parse TaskID from response body
                    String body = response.body();
                    if (body != null && !body.isEmpty()) {
                        try {
                            JSONObject json = new JSONObject(body);
                            if (json.has("Data")) {
                                JSONObject data = json.getJSONObject("Data");
                                if (data.has("TaskID")) {
                                    taskIdHolder[0] = data.getString("TaskID");
                                    System.out.println("[AUC] Server TaskID: " + taskIdHolder[0]);
                                }
                            }
                        } catch (Exception e) {
                            System.out.println("[AUC] Could not parse submit response body: " + e.getMessage());
                        }
                    }

                    if (!"20000000".equals(code)) {
                        throw new RuntimeException("AUC submit failed: code=" + code + " message=" + message);
                    }

                    if (callbacks != null) callbacks.onStatus("AUC task submitted: " + taskIdHolder[0]);
                    return new String[]{taskIdHolder[0], xTtLogid};

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("AUC submit interrupted", e);
                } catch (Exception e) {
                    lastException = e;
                    String errMsg = e.getMessage();
                    boolean isTimeout = errMsg != null &&
                        (errMsg.contains("timed out") || errMsg.contains("timeout"));

                    if (isTimeout && attempt < MAX_RETRIES) {
                        System.err.println("[AUC] Submit attempt " + attempt + " timed out, will retry");
                        // Create a fresh HttpClient in case of stale connection pool
                        continue;
                    }
                    if (!isTimeout || attempt >= MAX_RETRIES) {
                        break;
                    }
                }
            }

            throw new RuntimeException("AUC submit error (after " + MAX_RETRIES
                + " attempts): " + (lastException != null ? lastException.getMessage() : "unknown"),
                lastException);
        });
    }

    private CompletableFuture<Void> pollTask(String taskId, String xTtLogid) {
        return CompletableFuture.supplyAsync(() -> {
            int pollCount = 0;
            while (!stopping) {
                try {
                    pollCount++;
                    JSONObject queryPayload = new JSONObject();

                    HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(QUERY_URL))
                        .timeout(QUERY_TIMEOUT)
                        .header("X-Api-App-Key", appId)
                        .header("X-Api-Access-Key", accessToken)
                        .header("X-Api-Resource-Id", RESOURCE_ID)
                        .header("X-Api-Request-Id", taskId)
                        .header("X-Tt-Logid", xTtLogid)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(queryPayload.toString()))
                        .build();

                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    String code = response.headers().firstValue("X-Api-Status-Code").orElse("");

                    if ("20000000".equals(code)) {
                        System.out.println("[AUC] Task completed after " + pollCount + " polls");
                        System.out.println("[AUC] Raw response: " + response.body());
                        parseResponse(response.body());
                        return null;
                    } else if ("20000001".equals(code) || "20000002".equals(code)) {
                        // Still processing or queued
                        if (pollCount % 5 == 1) {
                            System.out.println("[AUC] Polling... (" + pollCount + ") status=" + code);
                        }
                    } else {
                        String message = response.headers().firstValue("X-Api-Message").orElse("");
                        String msg = "AUC query failed: code=" + code + " message=" + message;
                        System.err.println("[AUC] " + msg);
                        if (callbacks != null) callbacks.onError(msg);
                        return null;
                    }

                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                } catch (Exception e) {
                    System.err.println("[AUC] Poll error: " + e.getMessage());
                    if (callbacks != null) callbacks.onError("AUC poll error: " + e.getMessage());
                    return null;
                }
            }
            return null;
        });
    }

    private void parseResponse(String body) {
        if (callbacks == null || body == null || body.isEmpty()) return;

        try {
            JSONObject json = new JSONObject(body);

            // Path 1: payload_msg.result[].text (streaming-compatible format)
            if (json.has("payload_msg")) {
                JSONObject pm = json.getJSONObject("payload_msg");
                extractResults(pm);
                return;
            }

            // Path 2: Data.Result (AUC response format)
            if (json.has("Data")) {
                JSONObject data = json.getJSONObject("Data");
                if (data.has("Result")) {
                    Object resultObj = data.get("Result");
                    if (resultObj instanceof JSONObject) {
                        extractResults((JSONObject) resultObj);
                    } else if (resultObj instanceof JSONArray) {
                        JSONArray results = (JSONArray) resultObj;
                        for (int i = 0; i < results.length(); i++) {
                            extractResults(results.getJSONObject(i));
                        }
                    }
                    return;
                }
                // Data may contain text directly
                if (data.has("text")) {
                    callbacks.onResult(data.getString("text"), true);
                    return;
                }
            }

            // Path 3: result at top level (object or array)
            if (json.has("result")) {
                Object resultObj = json.get("result");
                if (resultObj instanceof JSONObject) {
                    extractResults((JSONObject) resultObj);
                } else if (resultObj instanceof JSONArray) {
                    JSONArray results = (JSONArray) resultObj;
                    for (int i = 0; i < results.length(); i++) {
                        extractResults(results.getJSONObject(i));
                    }
                }
                return;
            }

            // Path 4: utterances at top level
            if (json.has("utterances")) {
                JSONArray utterances = json.getJSONArray("utterances");
                for (int i = 0; i < utterances.length(); i++) {
                    JSONObject u = utterances.getJSONObject(i);
                    if (u.has("text")) {
                        callbacks.onResult(u.getString("text"), u.optBoolean("definite", false));
                    }
                }
                return;
            }

            System.out.println("[AUC] Response parsed but no recognized text found");
        } catch (Exception e) {
            System.err.println("[AUC] Failed to parse response: " + e.getMessage());
            System.err.println("[AUC] Raw body: " + body);
        }
    }

    private void extractResults(JSONObject obj) {
        // utterances first (more detailed)
        if (obj.has("utterances")) {
            JSONArray utterances = obj.getJSONArray("utterances");
            for (int i = 0; i < utterances.length(); i++) {
                JSONObject u = utterances.getJSONObject(i);
                if (u.has("text")) {
                    String text = u.getString("text");
                    boolean definite = u.optBoolean("definite", true);
                    callbacks.onResult(text, definite);
                    System.out.println("[AUC] Utterance: \"" + text + "\" definite=" + definite);
                }
            }
        }

        // result array
        if (obj.has("result")) {
            JSONArray results = obj.getJSONArray("result");
            for (int i = 0; i < results.length(); i++) {
                JSONObject r = results.getJSONObject(i);
                if (r.has("text")) {
                    callbacks.onResult(r.getString("text"), r.optBoolean("is_final", false));
                }
            }
        }

        // direct text field
        if (obj.has("text") && !obj.has("utterances") && !obj.has("result")) {
            callbacks.onResult(obj.getString("text"), true);
        }
    }

    // --- PCM to WAV conversion ---

    static byte[] pcmToWav(byte[] pcmData, int sampleRate, int bitsPerSample, int channels) {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int dataSize = pcmData.length;
        int fileSize = 36 + dataSize;

        byte[] wav = new byte[44 + dataSize];

        // RIFF header
        wav[0] = 'R'; wav[1] = 'I'; wav[2] = 'F'; wav[3] = 'F';
        writeIntLE(wav, 4, fileSize);
        wav[8] = 'W'; wav[9] = 'A'; wav[10] = 'V'; wav[11] = 'E';

        // fmt chunk
        wav[12] = 'f'; wav[13] = 'm'; wav[14] = 't'; wav[15] = ' ';
        writeIntLE(wav, 16, 16);         // chunk size
        writeShortLE(wav, 20, 1);        // PCM format
        writeShortLE(wav, 22, channels);
        writeIntLE(wav, 24, sampleRate);
        writeIntLE(wav, 28, byteRate);
        writeShortLE(wav, 32, blockAlign);
        writeShortLE(wav, 34, bitsPerSample);

        // data chunk
        wav[36] = 'd'; wav[37] = 'a'; wav[38] = 't'; wav[39] = 'a';
        writeIntLE(wav, 40, dataSize);

        System.arraycopy(pcmData, 0, wav, 44, dataSize);

        return wav;
    }

    private static void writeIntLE(byte[] buf, int offset, int value) {
        buf[offset]     = (byte) (value & 0xff);
        buf[offset + 1] = (byte) ((value >> 8) & 0xff);
        buf[offset + 2] = (byte) ((value >> 16) & 0xff);
        buf[offset + 3] = (byte) ((value >> 24) & 0xff);
    }

    private static void writeShortLE(byte[] buf, int offset, int value) {
        buf[offset]     = (byte) (value & 0xff);
        buf[offset + 1] = (byte) ((value >> 8) & 0xff);
    }
}
