package com.volc.voice.protocol;

public final class EventType {
    private EventType() {}

    public static final int NONE = 0;

    // Connection events (1-99)
    public static final int START_CONNECTION = 1;
    public static final int FINISH_CONNECTION = 2;
    public static final int CONNECTION_STARTED = 50;
    public static final int CONNECTION_FAILED = 51;
    public static final int CONNECTION_FINISHED = 52;

    // Session events (100-199)
    public static final int START_SESSION = 100;
    public static final int CANCEL_SESSION = 101;
    public static final int FINISH_SESSION = 102;
    public static final int SESSION_STARTED = 150;
    public static final int SESSION_CANCELED = 151;
    public static final int SESSION_FINISHED = 152;
    public static final int SESSION_FAILED = 153;
    public static final int USAGE_RESPONSE = 154;

    // General events (200-299)
    public static final int TASK_REQUEST = 200;
    public static final int UPDATE_CONFIG = 201;
    public static final int AUDIO_MUTED = 250;

    // TTS events (300-399)
    public static final int SAY_HELLO = 300;
    public static final int TTS_SENTENCE_START = 350;
    public static final int TTS_SENTENCE_END = 351;
    public static final int TTS_RESPONSE = 352;
    public static final int TTS_ENDED = 359;
    public static final int PODCAST_ROUND_START = 360;
    public static final int PODCAST_ROUND_RESPONSE = 361;
    public static final int PODCAST_ROUND_END = 362;

    // ASR events (450-499)
    public static final int ASR_INFO = 450;
    public static final int ASR_RESPONSE = 451;
    public static final int ASR_ENDED = 459;

    // Chat events (500-599)
    public static final int CHAT_TTS_TEXT = 500;
    public static final int CHAT_RESPONSE = 550;
    public static final int CHAT_ENDED = 559;

    // Subtitle events (650-699)
    public static final int SOURCE_SUBTITLE_START = 650;
    public static final int SOURCE_SUBTITLE_RESPONSE = 651;
    public static final int SOURCE_SUBTITLE_END = 652;
    public static final int TRANSLATION_SUBTITLE_START = 653;
    public static final int TRANSLATION_SUBTITLE_RESPONSE = 654;
    public static final int TRANSLATION_SUBTITLE_END = 655;

    public static String name(int event) {
        return switch (event) {
            case NONE -> "None";
            case START_CONNECTION -> "StartConnection";
            case FINISH_CONNECTION -> "FinishConnection";
            case CONNECTION_STARTED -> "ConnectionStarted";
            case CONNECTION_FAILED -> "ConnectionFailed";
            case CONNECTION_FINISHED -> "ConnectionFinished";
            case START_SESSION -> "StartSession";
            case CANCEL_SESSION -> "CancelSession";
            case FINISH_SESSION -> "FinishSession";
            case SESSION_STARTED -> "SessionStarted";
            case SESSION_CANCELED -> "SessionCanceled";
            case SESSION_FINISHED -> "SessionFinished";
            case SESSION_FAILED -> "SessionFailed";
            case USAGE_RESPONSE -> "UsageResponse";
            case TASK_REQUEST -> "TaskRequest";
            case UPDATE_CONFIG -> "UpdateConfig";
            case AUDIO_MUTED -> "AudioMuted";
            case SAY_HELLO -> "SayHello";
            case TTS_SENTENCE_START -> "TTSSentenceStart";
            case TTS_SENTENCE_END -> "TTSSentenceEnd";
            case TTS_RESPONSE -> "TTSResponse";
            case TTS_ENDED -> "TTSEnded";
            case PODCAST_ROUND_START -> "PodcastRoundStart";
            case PODCAST_ROUND_RESPONSE -> "PodcastRoundResponse";
            case PODCAST_ROUND_END -> "PodcastRoundEnd";
            case ASR_INFO -> "ASRInfo";
            case ASR_RESPONSE -> "ASRResponse";
            case ASR_ENDED -> "ASREnded";
            case CHAT_TTS_TEXT -> "ChatTTSText";
            case CHAT_RESPONSE -> "ChatResponse";
            case CHAT_ENDED -> "ChatEnded";
            case SOURCE_SUBTITLE_START -> "SourceSubtitleStart";
            case SOURCE_SUBTITLE_RESPONSE -> "SourceSubtitleResponse";
            case SOURCE_SUBTITLE_END -> "SourceSubtitleEnd";
            case TRANSLATION_SUBTITLE_START -> "TranslationSubtitleStart";
            case TRANSLATION_SUBTITLE_RESPONSE -> "TranslationSubtitleResponse";
            case TRANSLATION_SUBTITLE_END -> "TranslationSubtitleEnd";
            default -> "Unknown(" + event + ")";
        };
    }
}
