package com.volc.voice.protocol;

public final class MessageType {
    private MessageType() {}

    public static final int INVALID = 0;
    public static final int FULL_CLIENT_REQUEST = 0b1;
    public static final int AUDIO_ONLY_CLIENT = 0b10;
    public static final int FULL_SERVER_RESPONSE = 0b1001;
    public static final int AUDIO_ONLY_SERVER = 0b1011;
    public static final int FRONT_END_RESULT_SERVER = 0b1100;
    public static final int ERROR = 0b1111;

    public static final int NO_SEQ = 0;
    public static final int POSITIVE_SEQ = 0b1;
    public static final int LAST_NO_SEQ = 0b10;
    public static final int NEGATIVE_SEQ = 0b11;
    public static final int WITH_EVENT = 0b100;

    public static final int VERSION_1 = 1;
    public static final int VERSION_2 = 2;
    public static final int VERSION_3 = 3;
    public static final int VERSION_4 = 4;

    public static final int HEADER_SIZE_4 = 1;
    public static final int HEADER_SIZE_8 = 2;
    public static final int HEADER_SIZE_12 = 3;
    public static final int HEADER_SIZE_16 = 4;

    public static final int SERIALIZATION_RAW = 0;
    public static final int SERIALIZATION_JSON = 0b1;
    public static final int SERIALIZATION_THRIFT = 0b11;
    public static final int SERIALIZATION_CUSTOM = 0b1111;

    public static final int COMPRESSION_NONE = 0;
    public static final int COMPRESSION_GZIP = 0b1;
    public static final int COMPRESSION_CUSTOM = 0b1111;

    public static String typeName(int type) {
        return switch (type) {
            case FULL_CLIENT_REQUEST -> "FullClientRequest";
            case AUDIO_ONLY_CLIENT -> "AudioOnlyClient";
            case FULL_SERVER_RESPONSE -> "FullServerResponse";
            case AUDIO_ONLY_SERVER -> "AudioOnlyServer";
            case FRONT_END_RESULT_SERVER -> "FrontEndResultServer";
            case ERROR -> "Error";
            default -> "Unknown(" + type + ")";
        };
    }
}
