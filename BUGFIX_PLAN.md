# 修复方案文档：TTS 语音未正常发送到硬件端

> 文档日期：2026-05-24 | 版本：v1.0

---

## 1. 问题现象

运行 `java -jar target/voicerserver-1.0.0-jar-with-dependencies.jar` 后，ESP32 的 TTS 请求被服务端拒绝，语音无法下发到硬件。

日志关键行：

```
[ASR] FINAL: "语音介绍一下自己。"
[TTS] synthesize: ...
MsgType: FullClientRequest, EventType: StartSession, PayloadSize: 249
                                                            ← 卡住，无 SessionStarted
[Server] ESP32 TTS request: "你好，我是 AIRI，..."
[Server] TTS already synthesizing, skipping               ← ESP32 的正式请求被阻塞
```

两个故障叠加：
1. ASR 识别完成后自动触发了一次不该发生的 TTS
2. 这次自动 TTS 的 `StartSession` 发出后永久卡死，导致 `ttsSynthesizing` 锁永不释放，ESP32 的正式 TTS 请求被跳过

---

## 2. 根因分析

### 2.1 逻辑错误：ASR 回调中自动触发 TTS

**文件：** [LocalServer.java:90-92](src/main/java/com/volc/voice/server/LocalServer.java#L90-L92)

```java
asr.setCallbacks(new AucAsrClient.Callbacks() {
    @Override
    public void onResult(String text, boolean isFinal) {
        // ... send asr_result to ESP32 ...

        if (isFinal && !text.trim().isEmpty()) {
            triggerTts(text.trim());   // ← 多余：ASR 结果不应转为语音
        }
    }
});
```

**预期行为：**
- ASR 管线只负责识别 PCM 音频 → 返回文本结果给 ESP32
- TTS 管线**仅**由 ESP32 主动发起 `tts_request` 消息触发

**实际行为：**
- ASR 识别完成后自动调用 `triggerTts()`，用识别文本合成语音
- 这次自动 TTS 占用了 `ttsSynthesizing = true` 锁
- ESP32 后续发来的真正 TTS 请求因锁被占用而被拒绝

**触发条件：** 每次 ASR 录音识别完成且文本非空时必然触发。

### 2.2 并发 Bug：ProtocolHelper.receiveMessage 竞态条件

**文件：** [ProtocolHelper.java:69-81](src/main/java/com/volc/voice/protocol/ProtocolHelper.java#L69-L81)

```java
// 原代码（有竞态）
public static CompletableFuture<Message> receiveMessage(WebSocket ws) {
    var queue = queues.get(ws);
    if (queue != null && !queue.isEmpty()) {   // ① 检查队列为空
        return CompletableFuture.completedFuture(queue.poll());
    }

    var future = new CompletableFuture<Message>();
    var waiterQueue = waiters.get(ws);
    if (waiterQueue != null) {
        waiterQueue.add(future);               // ② 注册等待者
    }
    return future;
}
```

**竞态窗口：** 步骤 ① 和 ② 之间。如果火山引擎的 `SessionStarted` 响应恰好在此时到达：

| 时序 | 线程 A（业务逻辑） | 线程 B（Jetty WebSocket） |
|------|---------------------|------------------------------|
| T1 | `receiveMessage()` → ① `queue.isEmpty()` → true | |
| T2 | | 服务端返回 `SessionStarted` |
| T3 | | `onBinaryMessage()` → `waiters.poll()` → null（尚未注册） |
| T4 | | `onBinaryMessage()` → `queue.add(msg)` → 消息入队 |
| T5 | `waiterQueue.add(future)` → 注册完成 | |
| T6 | future 永久挂起，无人唤醒 | |

**后果：** `waitForEvent(EventType.SESSION_STARTED)` 的 `CompletableFuture` 永远不会完成，整个 TTS 管线卡死。

---

## 3. 修复方案

### 3.1 修复 1：ProtocolHelper 竞态条件（已应用）

**变更：** `receiveMessage` 改为**先注册等待者，再检查队列**。

```java
// 修复后代码
public static CompletableFuture<Message> receiveMessage(WebSocket ws) {
    var queue = queues.get(ws);
    var waiterQueue = waiters.get(ws);
    if (waiterQueue == null || queue == null) {
        return CompletableFuture.failedFuture(
            new IllegalStateException("WebSocket not registered"));
    }

    // 先注册兴趣——消息无论何时到达都能正确投递
    var future = new CompletableFuture<Message>();
    waiterQueue.add(future);

    // 再检查队列——防止消息在注册前已入队
    var msg = queue.poll();
    if (msg != null && future.complete(msg)) {
        waiterQueue.remove(future);
    }

    return future;
}
```

**正确性证明：**

- **消息先于注册到达：** `q.poll()` 取到消息，`future.complete(msg)` 自己唤醒
- **消息在注册后到达：** `onBinaryMessage` 从 `waiters` 队列取出 future 并完成它
- **消息在注册期间到达：** 要么入队后被 `q.poll()` 取到，要么被 `onBinaryMessage` 投递给等待者——两者至少有一个会命中

**变更文件：** [ProtocolHelper.java](src/main/java/com/volc/voice/protocol/ProtocolHelper.java) — `receiveMessage` 方法

### 3.2 修复 2：移除 ASR 回调中的自动 TTS（已应用）

**变更：** 删除 `onResult` 回调中自动调用 `triggerTts()` 的代码。

**修改前：**

```java
@Override
public void onResult(String text, boolean isFinal) {
    System.out.println("[ASR] " + (isFinal ? "FINAL" : "PARTIAL") + ": \"" + text + "\"");
    sendToEsp(new JSONObject()
        .put("type", "asr_result")
        .put("text", text)
        .put("is_final", isFinal));

    if (isFinal && !text.trim().isEmpty()) {   // ← 删除此三行
        triggerTts(text.trim());               //
    }                                          //
}
```

**修改后：**

```java
@Override
public void onResult(String text, boolean isFinal) {
    System.out.println("[ASR] " + (isFinal ? "FINAL" : "PARTIAL") + ": \"" + text + "\"");
    sendToEsp(new JSONObject()
        .put("type", "asr_result")
        .put("text", text)
        .put("is_final", isFinal));
}
```

**影响范围：**
- `triggerTts()` 方法保留不变，TTS 合成逻辑不受影响
- 唯一的 TTS 入口变为 `handleEspMessage` 中的 `tts_request` 分支（[LocalServer.java:387-392](src/main/java/com/volc/voice/server/LocalServer.java#L387-L392)）
- ASR 管线完全解耦，只负责返回文本

**变更文件：** [LocalServer.java](src/main/java/com/volc/voice/server/LocalServer.java) — `setupCallbacks` 方法中 `asr.setCallbacks` 的 `onResult` 实现

### 3.3 增强 1：TTS 忙时通知 ESP32（已应用）

**问题：** 修复 2 之后，`triggerTts` 在 `ttsSynthesizing = true` 时静默丢弃请求，ESP32 无法获知请求被跳过，可能无限等待语音数据。

**变更：** 跳过时发送 `tts_busy` 消息，让 ESP32 知道当前忙，可以稍后重试。

```java
// 修复后代码
if (ttsSynthesizing) {
    System.out.println("[Server] TTS already synthesizing, skipping");
    sendToEsp(new JSONObject().put("type", "tts_busy"));
    return;
}
```

**变更文件：** [LocalServer.java](src/main/java/com/volc/voice/server/LocalServer.java) — `triggerTts` 方法门控

### 3.4 增强 2：健康检查暴露 voiceType（已应用）

**变更：** `/health` 端点新增 `voiceType` 字段，便于调试时确认当前音色配置。

```java
.put("voiceType", options.voiceType);
```

**变更文件：** [LocalServer.java](src/main/java/com/volc/voice/server/LocalServer.java) — `/health` GET 处理

---

## 4. 修复后数据流

### 4.1 ASR 识别流程（仅返回文本）

```
ESP32                        LocalServer                      火山引擎
  │                              │                                │
  │── {"type":"asr_start"} ────→│ asrRecording = true            │
  │── [PCM binary] × N ────────→│ pcmBuffer 缓冲 + 诊断           │
  │── {"type":"asr_stop"} ─────→│ asrRecording = false           │
  │                              │ PCM → WAV → Base64             │
  │                              │── POST /submit ──────────────→│
  │                              │── POST /query (轮询) ────────→│
  │                              │←── 识别结果 ──────────────────│
  │←── {"type":"asr_result",    │                                │
  │      "text":"语音介绍...",    │  不触发 TTS ✓                  │
  │      "is_final":true} ──────│                                │
  │                              │                                │
  │  (ASR 流程结束，TTS 管线空闲)  │                                │
```

### 4.2 TTS 合成流程（仅由 ESP32 显式请求触发）

```
ESP32                        LocalServer                      火山引擎
  │                              │                                │
  │── {"type":"tts_request",    │                                │
  │     "text":"你好，我是       │                                │
  │     AIRI..."} ──────────────→│ triggerTts(text)               │
  │                              │ ttsSynthesizing = true         │
  │←── {"type":"tts_start"} ────│                                │
  │                              │── StartSession ──────────────→│
  │                              │── TaskRequest(text) ─────────→│
  │                              │←── OGG Opus audio ───────────│
  │←── [Opus binary] × N ──────│  (OggDemuxer 拆包，实时推送)    │
  │                              │←── SESSION_FINISHED ─────────│
  │←── {"type":"tts_end"} ─────│  ttsSynthesizing = false        │
  │                              │                                │
  │  (TTS 流程结束，可以接受下一次请求) │                             │
```

---

## 5. 测试验证

### 5.1 验证场景

| 序号 | 场景 | 预期结果 |
|------|------|----------|
| 1 | ESP32 发送 asr_start + PCM + asr_stop | 返回 asr_result，**不自动播报**，`ttsSynthesizing` 保持 false |
| 2 | ESP32 发送 tts_request | TTS 合成 → Opus 音频推送 → tts_end |
| 3 | ASR 进行中，ESP32 发送 tts_request | TTS 正常处理（ASR 不占用 TTS 锁） |
| 4 | TTS 进行中，ESP32 再发送 tts_request | 返回 "TTS already synthesizing, skipping" |
| 5 | 连续多次 ASR + TTS 交替 | 每次独立执行，互不干扰 |
| 6 | ESP32 在 TTS 进行中发送 ASR 请求 | ASR 正常识别，不受 TTS 影响（验证并行独立性） |
| 7 | ASR 完成后立即发送 tts_request（零延迟） | TTS 正常启动，锁空闲，无残留占用 |
| 8 | TTS 进行中 ESP32 再发送 tts_request | 返回 `tts_busy`，ESP32 可据此重试 |
| 9 | ASR 在 TTS 进行中开始/停止 | ASR 结果正常返回，不触发 TTS，`ttsSynthesizing` 不变 |

### 5.2 验证方法

```bash
# 重新编译
mvn clean package -DskipTests

# 启动服务
java -jar target/voicerserver-1.0.0-jar-with-dependencies.jar

# 使用 test_ws_client.cjs 或 ESP32 硬件测试
```

关键日志检查点：
- `[ASR] FINAL` 之后**不应出现** `[DEBUG] triggerTts`
- `[ASR] FINAL` 之后 `ttsSynthesizing` 保持 `false`（通过 `/health` 端点验证）
- ESP32 发送 `tts_request` 后应看到 `[TTS] Session started`、`[TTS] Sent N Opus packets`、`[TTS] Playback complete`

---

## 6. 变更汇总

| # | 文件 | 变更类型 | 行数 | 状态 |
|---|------|----------|------|------|
| 1 | `ProtocolHelper.java` — `receiveMessage` | Bug 修复 | ~15 行 | ✅ 已应用 |
| 2 | `LocalServer.java` — `onResult` 回调 | 逻辑修复 | 删除 3 行 | ✅ 已应用 |
| 3 | `LocalServer.java` — `triggerTts` 门控 | 功能增强 | +1 行 | ✅ 已应用 |
| 4 | `LocalServer.java` — `/health` 端点 | 调试增强 | +1 行 | ✅ 已应用 |

---

## 7. 附录：现有 TTS 入口调用链

修复后，`triggerTts()` 的唯一合法调用路径：

```
Esp32SocketHandler.onWebSocketText()
  └→ msgQueue.thenAccept(v → handleEspMessage(data))
       └→ switch("tts_request"):
            triggerTts(text)
              ├→ ttsSynthesizing = true
              ├→ sendToEsp({"type": "tts_start", ...})
              └→ synthesizeWithRetry(text, 1)
                   └→ tts.synthesize(text)
                        ├→ connect()  (auto-reconnect if needed)
                        ├→ startSession()
                        ├→ waitForEvent(SESSION_STARTED)
                        ├→ taskRequest(text)
                        ├→ receiveAudioLoop()
                        │    ├→ Opus audio → onOpusPacket → sendToEsp()
                        │    └→ SESSION_FINISHED → onSessionFinished
                        └→ finishSession()
                             └→ onSessionFinished:
                                  sendToEsp({"type": "tts_end"})
                                  ttsSynthesizing = false
                                  saveRecording()
```

---

## 8. 附录：已知技术债务（不在本次修复范围）

### 8.1 `receiveAudioLoop` 中的阻塞 `.get()`

**文件：** [VolcTtsClient.java:394](src/main/java/com/volc/voice/tts/VolcTtsClient.java#L394)

```java
Message msg = ProtocolHelper.receiveMessage(ws).get(); // 无超时的阻塞 get
```

该调用在 `thenComposeAsync` 的专用线程中运行，功能正常。但如果 WebSocket 异常断开且 `receiveMessage` 返回的 future 永远不完成，此线程将永久阻塞。修复 1 已消除首次消息的竞态窗口，但音频循环内的后续消息调用缺乏超时保护。

**建议（后续迭代）：** 使用 `get(timeout, TimeUnit.SECONDS)` 并捕获 `TimeoutException`，触发重连或错误回调。
