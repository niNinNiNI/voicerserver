package com.volc.voice.protocol;

public class Message {
    public int version = MessageType.VERSION_1;
    public int headerSize = MessageType.HEADER_SIZE_4;
    public int type;
    public int flag;
    public int serialization = MessageType.SERIALIZATION_JSON;
    public int compression = MessageType.COMPRESSION_NONE;
    public int event = -1;
    public String sessionId;
    public String connectId;
    public Integer sequence;
    public Integer errorCode;
    public byte[] payload = new byte[0];

    public Message() {}

    public Message(int type, int flag) {
        this.type = type;
        this.flag = flag;
    }

    @Override
    public String toString() {
        var sb = new StringBuilder();
        sb.append("MsgType: ").append(MessageType.typeName(type));
        if (event >= 0) {
            sb.append(", EventType: ").append(EventType.name(event));
        }
        if (sequence != null) {
            sb.append(", Sequence: ").append(sequence);
        }
        if (errorCode != null) {
            sb.append(", ErrorCode: ").append(errorCode);
            sb.append(", Payload: ").append(new String(payload));
        } else if (payload.length > 0) {
            sb.append(", PayloadSize: ").append(payload.length);
        }
        return sb.toString();
    }
}
