package com.volc.voice.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Binary marshal/unmarshal for the Volcengine speech protocol.
 *
 * Wire format:
 *   Byte 0: version(4bit) | headerSize(4bit)
 *   Byte 1: type(4bit) | flag(4bit)
 *   Byte 2: serialization(4bit) | compression(4bit)
 *   Bytes 3..headerEnd-1: padding to 4*headerSize
 *   Extensions (order depends on type/flag):
 *     Event:      4 bytes big-endian int32  (if WithEvent flag)
 *     SessionId:  4-byte length + UTF-8     (non-connection events only)
 *     ConnectId:  4-byte length + UTF-8     (connection response events only)
 *     Sequence:   4 bytes big-endian int32  (if PositiveSeq/NegativeSeq)
 *     ErrorCode:  4 bytes big-endian uint32 (if Error type)
 *   Payload: 4-byte length + data
 */
public final class MessageCodec {
    private MessageCodec() {}

    public static byte[] marshal(Message msg) {
        int headerSize = 4 * msg.headerSize;
        var buf = ByteBuffer.allocate(4096);

        // Fixed header
        buf.put((byte) ((msg.version << 4) | msg.headerSize));
        buf.put((byte) ((msg.type << 4) | msg.flag));
        buf.put((byte) ((msg.serialization << 4) | msg.compression));

        // Pad to header size
        while (buf.position() < headerSize) {
            buf.put((byte) 0);
        }

        // Extensions
        if (msg.flag == MessageType.WITH_EVENT) {
            buf.putInt(msg.event);
            writeSessionId(buf, msg);
        }

        if (needsSequence(msg)) {
            buf.putInt(msg.sequence != null ? msg.sequence : 0);
        }

        if (msg.type == MessageType.ERROR && msg.errorCode != null) {
            buf.putInt(msg.errorCode);
        }

        // Payload
        buf.putInt(msg.payload.length);
        if (msg.payload.length > 0) {
            buf.put(msg.payload);
        }

        byte[] result = new byte[buf.position()];
        buf.flip();
        buf.get(result);
        return result;
    }

    public static Message unmarshal(byte[] data) {
        if (data.length < 3) {
            throw new IllegalArgumentException("data too short: " + data.length);
        }

        var buf = ByteBuffer.wrap(data);
        var msg = new Message();

        int b0 = buf.get() & 0xFF;
        int b1 = buf.get() & 0xFF;
        int b2 = buf.get() & 0xFF;

        msg.version = b0 >> 4;
        msg.headerSize = b0 & 0x0F;
        msg.type = b1 >> 4;
        msg.flag = b1 & 0x0F;
        msg.serialization = b2 >> 4;
        msg.compression = b2 & 0x0F;

        int headerEnd = 4 * msg.headerSize;
        buf.position(headerEnd);

        // Read extensions
        if (msg.flag == MessageType.WITH_EVENT) {
            msg.event = buf.getInt();
            readSessionId(buf, msg);
            readConnectId(buf, msg);
        }

        if (needsSequence(msg)) {
            msg.sequence = buf.getInt();
        }

        if (msg.type == MessageType.ERROR) {
            msg.errorCode = buf.getInt();
        }

        // Payload
        int payloadLen = buf.getInt();
        if (payloadLen > 0) {
            msg.payload = new byte[payloadLen];
            buf.get(msg.payload);
        }

        return msg;
    }

    private static boolean needsSequence(Message msg) {
        if (msg.type == MessageType.ERROR) return false;
        return msg.flag == MessageType.POSITIVE_SEQ
            || msg.flag == MessageType.NEGATIVE_SEQ;
    }

    private static void writeSessionId(ByteBuffer buf, Message msg) {
        if (msg.event < 0) return;

        // Connection lifecycle events don't carry sessionId
        switch (msg.event) {
            case EventType.START_CONNECTION:
            case EventType.FINISH_CONNECTION:
            case EventType.CONNECTION_STARTED:
            case EventType.CONNECTION_FAILED:
                return;
        }

        byte[] bytes = msg.sessionId != null
            ? msg.sessionId.getBytes(StandardCharsets.UTF_8)
            : new byte[0];
        buf.putInt(bytes.length);
        if (bytes.length > 0) {
            buf.put(bytes);
        }
    }

    private static void readSessionId(ByteBuffer buf, Message msg) {
        if (msg.event < 0) return;

        switch (msg.event) {
            case EventType.START_CONNECTION:
            case EventType.FINISH_CONNECTION:
            case EventType.CONNECTION_STARTED:
            case EventType.CONNECTION_FAILED:
            case EventType.CONNECTION_FINISHED:
                return;
        }

        int len = buf.getInt();
        if (len > 0) {
            byte[] bytes = new byte[len];
            buf.get(bytes);
            msg.sessionId = new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static void readConnectId(ByteBuffer buf, Message msg) {
        if (msg.event < 0) return;

        switch (msg.event) {
            case EventType.CONNECTION_STARTED:
            case EventType.CONNECTION_FAILED:
            case EventType.CONNECTION_FINISHED:
                break;
            default:
                return;
        }

        int len = buf.getInt();
        if (len > 0) {
            byte[] bytes = new byte[len];
            buf.get(bytes);
            msg.connectId = new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
