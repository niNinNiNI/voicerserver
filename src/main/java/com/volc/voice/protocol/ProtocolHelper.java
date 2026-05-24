package com.volc.voice.protocol;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * High-level protocol helpers and per-WebSocket message queue.
 */
public final class ProtocolHelper {
    private ProtocolHelper() {}

    // Per-WebSocket message queues for ReceiveMessage
    private static final Map<WebSocket, Queue<Message>> queues = new ConcurrentHashMap<>();
    private static final Map<WebSocket, Queue<CompletableFuture<Message>>> waiters = new ConcurrentHashMap<>();

    /**
     * Register a WebSocket listener that feeds the message queue.
     * Must be called once per WebSocket before using ReceiveMessage.
     */
    public static void register(WebSocket ws) {
        queues.put(ws, new ConcurrentLinkedQueue<>());
        waiters.put(ws, new ConcurrentLinkedQueue<>());
    }

    /**
     * Feed a received binary message into the queue for this WebSocket.
     */
    public static void onBinaryMessage(WebSocket ws, byte[] data) {
        var msg = MessageCodec.unmarshal(data);
        var waiterQueue = waiters.get(ws);
        if (waiterQueue != null && !waiterQueue.isEmpty()) {
            var future = waiterQueue.poll();
            if (future != null) {
                future.complete(msg);
                return;
            }
        }
        var queue = queues.get(ws);
        if (queue != null) {
            queue.add(msg);
        }
    }

    /**
     * Clean up resources for a disconnected WebSocket.
     * Rejects all pending waiters.
     */
    public static void unregister(WebSocket ws, String reason) {
        var waiterQueue = waiters.remove(ws);
        if (waiterQueue != null) {
            CompletableFuture<Message> future;
            while ((future = waiterQueue.poll()) != null) {
                future.completeExceptionally(new RuntimeException("WebSocket closed: " + reason));
            }
        }
        queues.remove(ws);
    }

    /**
     * Receive the next message from the WebSocket. Returns a CompletableFuture
     * that completes when a message arrives or the connection is closed.
     *
     * Registers a waiter BEFORE checking the queue to avoid the race where
     * a message arrives between isEmpty() and waiter registration:
     * onBinaryMessage sees no waiter, enqueues msg, then the waiter sits
     * forever because no producer will ever complete it.
     */
    public static CompletableFuture<Message> receiveMessage(WebSocket ws) {
        var queue = queues.get(ws);
        var waiterQueue = waiters.get(ws);
        if (waiterQueue == null || queue == null) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("WebSocket not registered"));
        }

        // Register interest first — if a message is already queued, we will
        // find it below and complete ourselves. If it arrives later, the
        // producer will find our waiter and wake us.
        var future = new CompletableFuture<Message>();
        waiterQueue.add(future);

        // Now check the queue — a message may have arrived before we
        // registered the waiter, or during registration.
        var msg = queue.poll();
        if (msg != null && future.complete(msg)) {
            waiterQueue.remove(future);
        }

        return future;
    }

    // --- High-level protocol helpers ---

    public static CompletableFuture<Void> startConnection(WebSocket ws) {
        var msg = new Message(MessageType.FULL_CLIENT_REQUEST, MessageType.WITH_EVENT);
        msg.event = EventType.START_CONNECTION;
        msg.payload = "{}".getBytes(StandardCharsets.UTF_8);
        System.out.println(msg);
        return ws.sendBinary(ByteBuffer.wrap(MessageCodec.marshal(msg)), true).thenApply(ignored -> null);
    }

    public static CompletableFuture<Void> finishConnection(WebSocket ws) {
        var msg = new Message(MessageType.FULL_CLIENT_REQUEST, MessageType.WITH_EVENT);
        msg.event = EventType.FINISH_CONNECTION;
        msg.payload = "{}".getBytes(StandardCharsets.UTF_8);
        System.out.println(msg);
        return ws.sendBinary(ByteBuffer.wrap(MessageCodec.marshal(msg)), true).thenApply(ignored -> null);
    }

    public static CompletableFuture<Void> startSession(WebSocket ws, byte[] payload, String sessionId) {
        var msg = new Message(MessageType.FULL_CLIENT_REQUEST, MessageType.WITH_EVENT);
        msg.event = EventType.START_SESSION;
        msg.sessionId = sessionId;
        msg.payload = payload;
        System.out.println(msg);
        return ws.sendBinary(ByteBuffer.wrap(MessageCodec.marshal(msg)), true).thenApply(ignored -> null);
    }

    public static CompletableFuture<Void> finishSession(WebSocket ws, String sessionId) {
        var msg = new Message(MessageType.FULL_CLIENT_REQUEST, MessageType.WITH_EVENT);
        msg.event = EventType.FINISH_SESSION;
        msg.sessionId = sessionId;
        msg.payload = "{}".getBytes(StandardCharsets.UTF_8);
        System.out.println(msg);
        return ws.sendBinary(ByteBuffer.wrap(MessageCodec.marshal(msg)), true).thenApply(ignored -> null);
    }

    public static CompletableFuture<Void> cancelSession(WebSocket ws, String sessionId) {
        var msg = new Message(MessageType.FULL_CLIENT_REQUEST, MessageType.WITH_EVENT);
        msg.event = EventType.CANCEL_SESSION;
        msg.sessionId = sessionId;
        msg.payload = "{}".getBytes(StandardCharsets.UTF_8);
        System.out.println(msg);
        return ws.sendBinary(ByteBuffer.wrap(MessageCodec.marshal(msg)), true).thenApply(ignored -> null);
    }

    public static CompletableFuture<Void> audioOnly(WebSocket ws, byte[] payload, int flag) {
        var msg = new Message(MessageType.AUDIO_ONLY_CLIENT, flag);
        msg.payload = payload;
        return ws.sendBinary(ByteBuffer.wrap(MessageCodec.marshal(msg)), true).thenApply(ignored -> null);
    }

    public static CompletableFuture<Void> taskRequest(WebSocket ws, byte[] payload, String sessionId) {
        var msg = new Message(MessageType.FULL_CLIENT_REQUEST, MessageType.WITH_EVENT);
        msg.event = EventType.TASK_REQUEST;
        msg.sessionId = sessionId;
        msg.payload = payload;
        return ws.sendBinary(ByteBuffer.wrap(MessageCodec.marshal(msg)), true).thenApply(ignored -> null);
    }
}
