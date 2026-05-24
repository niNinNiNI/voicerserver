package com.volc.voice;

import com.volc.voice.server.LocalServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Main entry point for the ESP32 Volcengine Bridge Server.
 *
 * Usage:
 *   java -jar voicerserver.jar --appid <id> --access-token <token>
 *   java -jar voicerserver.jar --appid <id> --access-token <token> --port 8080
 */
public class VoiceServerApp {

    private static String voiceToResourceId(String voice) {
        if (voice.startsWith("S_")) {
            return "volc.megatts.default";
        }
        return "volc.service_type.10029";
    }

    public static void main(String[] args) {
        var env = loadEnv();
        var cliArgs = parseArgs(args);

        String appId = cliArgs.getOrDefault("appid", env.getOrDefault("VOLC_APP_ID", ""));
        String accessToken = cliArgs.getOrDefault("access-token", env.getOrDefault("VOLC_ACCESS_TOKEN", ""));
        String asrResourceId = cliArgs.getOrDefault("asr-resource-id",
            env.getOrDefault("VOLC_ASR_RESOURCE_ID", "volc.bigasr.auc"));
        String ttsResourceId = cliArgs.getOrDefault("tts-resource-id",
            env.getOrDefault("VOLC_TTS_RESOURCE_ID", ""));
        String voiceType = cliArgs.getOrDefault("voice-type",
            env.getOrDefault("VOLC_VOICE_TYPE", "zh_female_shuangkuaisisi_moon_bigtts"));
        int port = Integer.parseInt(cliArgs.getOrDefault("port", "3000"));
        int sampleRate = Integer.parseInt(cliArgs.getOrDefault("sample-rate", "24000"));

        if (ttsResourceId.isEmpty()) {
            ttsResourceId = voiceToResourceId(voiceType);
        }

        System.out.println("=== ESP32 Volcengine Bridge Server (Java) ===");
        System.out.println("Port:           " + port);
        System.out.println("Voice:          " + voiceType);
        System.out.println("ASR Resource:   " + asrResourceId);
        System.out.println("TTS Resource:   " + ttsResourceId);
        System.out.println("TTS SampleRate: " + sampleRate);
        System.out.println();

        var options = new LocalServer.Options();
        options.port = port;
        options.appId = appId;
        options.accessToken = accessToken;
        options.asrResourceId = asrResourceId;
        options.ttsResourceId = ttsResourceId;
        options.voiceType = voiceType;
        options.ttsSampleRate = sampleRate;

        var srv = new LocalServer(options);
        try {
            srv.start();
        } catch (Exception e) {
            System.err.println("Failed to start server: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        var map = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--") && i + 1 < args.length) {
                String key = arg.substring(2).replace('_', '-');
                map.put(key, args[++i]);
            }
        }
        return map;
    }

    private static Map<String, String> loadEnv() {
        var env = new HashMap<String, String>();
        Path envFile = Paths.get(".env");
        if (Files.exists(envFile)) {
            try {
                for (String line : Files.readAllLines(envFile)) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        String key = line.substring(0, eq).trim();
                        String value = line.substring(eq + 1).trim();
                        if (value.startsWith("\"") && value.endsWith("\"")) {
                            value = value.substring(1, value.length() - 1);
                        }
                        env.put(key, value);
                    }
                }
            } catch (IOException e) {
                // .env file not found or unreadable, use defaults
            }
        }
        return env;
    }
}
