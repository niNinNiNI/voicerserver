# ESP32 Volcengine Bridge Server — 架构文档

## 1. 项目概述

本项目是一个 **Java 17 桥接服务器**，运行于 Linux 单板计算机（Orange Pi / Raspberry Pi），在 ESP32 固件与火山引擎云端语音服务之间双向中转数据，提供语音识别（ASR）和语音合成（TTS）能力。

```
ESP32 <--WebSocket--> Java Server <--HTTP/WebSocket--> 火山引擎云端
     (JSON + PCM/Opus 二进制)                    (ASR整段识别 + TTS流式)
```

**技术栈：** Java 17, Maven, Jetty 11 (embedded HTTP + WebSocket), 火山引擎二进制协议

---

## 2. 模块架构

```
┌──────────────────────────────────────────────────────────────┐
│                      VoiceServerApp                          │
│                 (主入口, 参数解析, .env加载)                    │
└──────────────┬───────────────────────────────────────────────┘
               │ 创建
               ▼
┌──────────────────────────────────────────────────────────────┐
│                       LocalServer                            │
│          (HTTP健康检查 + WebSocket + ASR→TTS管线)               │
│                                                              │
│  ┌─────────────┐  ┌─────────────┐  ┌──────────────────┐     │
│  │ Esp32Socket │  │ AucAsrClient│  │  VolcTtsClient    │     │
│  │  Handler    │  │  (HTTP AUC) │  │  (WebSocket双向)  │     │
│  │ (WebSocket) │  └──────┬──────┘  └────────┬─────────┘     │
│  └─────────────┘         │                  │                │
│                          │                  │                │
│                    ┌─────┴──────┐    ┌──────┴─────────┐     │
│                    │ PCM→WAV    │    │ ProtocolHelper  │     │
│                    │ Base64编码  │    │ MessageCodec    │     │
│                    │ 提交+轮询   │    │ OggDemuxer      │     │
│                    │ PCM录音保存 │    │                  │     │
│                    └────────────┘    └────────────────┘     │
└──────────────────────────────────────────────────────────────┘
```

---

## 3. 组件详述

### 3.1 VoiceServerApp — 主入口

[VoiceServerApp.java](src/main/java/com/volc/voice/VoiceServerApp.java)

职责：
- 解析命令行参数（`--appid`, `--access-token`, `--port` 等）
- 加载 `.env` 文件中的环境变量
- 命令行参数优先级高于 `.env` 文件
- 自动推断 TTS Resource ID（`S_` 前缀音色 → `volc.megatts.default`，其余 → `volc.service_type.10029`）
- 创建 `LocalServer.Options` 并启动服务

### 3.2 LocalServer — 服务核心

[LocalServer.java](src/main/java/com/volc/voice/server/LocalServer.java)

职责：
- **HTTP 健康检查端点**：`GET /health` 返回服务状态 JSON
- **WebSocket 端点**：`/` 路径，由 `Esp32SocketHandler` 处理
- **ASR→TTS 自动管线**：识别到文本 → 自动合成语音播报
- **TTS 合成重试**：合成失败时自动重试（最多 1 次，间隔 1.5s）
- **PCM 缓冲与诊断**：缓冲 ESP32 发来的 PCM 音频，录制结束后一次性提交 AUC 识别
- **PCM 录音保存**：每次录音结束后保存 PCM 原始文件到 `recordings/` 目录
- **OGG 录音**：保存每次 TTS 合成的 OGG 音频到 `recordings/` 目录

关键内部状态：
| 字段 | 类型 | 说明 |
|------|------|------|
| `esp32Session` | `volatile Session` | 当前 ESP32 WebSocket 连接 |
| `asrRecording` | `volatile boolean` | 是否正在录音 |
| `ttsSynthesizing` | `volatile boolean` | 是否正在 TTS 合成 |
| `pcmBuffer` | `ByteArrayOutputStream` | PCM 音频缓冲 |
| `pcmChunkCount` | `int` | PCM 块计数 |
| `pcmDiagCount` | `int` | PCM 诊断计数器（用于周期性打印） |
| `lastRecordingBytes` | `int` | 最近一次录音的字节数 |
| `lastRecordingChunks` | `int` | 最近一次录音的块数 |
| `oggBuffer` | `ByteArrayOutputStream` | TTS OGG 音频缓冲 |
| `ttsPacketsSent` | `int` | 当前 TTS 会话已发送 Opus 包数 |
| `msgQueue` | `CompletableFuture<Void>` | JSON 控制消息串行队列 |

#### WebSocket 消息协议

**ESP32 → 服务器：**

| 类型 | 格式 | 方向 | 说明 |
|------|------|------|------|
| `asr_start` | JSON `{"type":"asr_start"}` | 走串行队列 | 开启录音缓冲 |
| PCM 音频 | 二进制帧 | 快路径（跳过队列） | 16kHz/16bit/单声道 |
| `asr_stop` | JSON `{"type":"asr_stop"}` | 走串行队列 | 停止录音，提交 AUC 识别 |
| `tts_request` | JSON `{"type":"tts_request","text":"..."}` | 走串行队列 | 请求 TTS 合成 |
| `ping` | JSON `{"type":"ping"}` | 走串行队列 | 心跳检测 |

**服务器 → ESP32：**

| 类型 | 格式 | 说明 |
|------|------|------|
| `status` | JSON | 连接状态通知 |
| `asr_result` | JSON `{"type":"asr_result","text":"...","is_final":bool}` | 识别结果 |
| `tts_start` | JSON `{"type":"tts_start","sample_rate":24000,"channels":1}` | 合成开始 |
| Opus 音频 | 二进制帧 | TTS 音频数据 |
| `tts_end` | JSON `{"type":"tts_end"}` | 播放完毕 |
| `pong` | JSON | 心跳响应 |
| `error` | JSON `{"type":"error","message":"..."}` | 错误通知 |

#### 消息分流机制

WebSocket 消息通过两种方式分流：

1. **文本帧**（`onWebSocketText`）：直接当作 JSON 控制消息处理，走串行队列
2. **二进制帧**（`onWebSocketBinary`）：尝试 `new JSONObject(new String(data))` 解析，成功则为 JSON 控制消息（走串行队列），失败则为 PCM 音频（走快路径直接缓冲）

这种双重机制保证了兼容性——当 ESP32 使用 WebSocket 文本帧发送 JSON 时走快速路径；当 ESP32 将所有数据作为二进制帧发送时，通过 JSON 解析试探来分流。

#### PCM 音频诊断

`handleEspBinary()` 方法包含多层诊断：

- **采样值打印**：每 1 个和每 50 个 PCM 块打印前 8 个采样值（16bit 有符号整数），用于验证音频数据有效性
- **全零检测**：如果整个 PCM 块所有字节为 `0x00`，输出 WARN 日志，提示麦克风可能静音或被禁用
- **全 0xFF 检测**：如果整个 PCM 块所有字节为 `0xFF`，输出 WARN 日志，提示 I2S DIN 引脚可能浮空
- **PCM 录音保存**：`savePcmRecording()` 将每次录音的原始 PCM 数据保存到 `recordings/pcm_<时间戳>_<字节数>b.pcm`，同时打印 ffplay 回放命令

### 3.3 AucAsrClient — ASR 客户端（HTTP 整段文件识别）

[AucAsrClient.java](src/main/java/com/volc/voice/asr/AucAsrClient.java)

**当前主 ASR 路径**，使用火山引擎 AUC HTTP API：

```
PCM 数据
  → pcmToWav() 转换为 WAV 格式
  → Base64 编码
  → POST /api/v3/auc/bigmodel/submit （提交任务，最多重试 3 次）
  → POST /api/v3/auc/bigmodel/query （轮询结果，每秒一次）
  → parseResponse() 解析 JSON 结果
  → callbacks.onResult(text, isFinal)
```

关键设计决策：
- **connect() 始终成功**（HTTP 是无状态的，无需预先连接）
- **isConnected() 始终返回 true**
- **重试策略**：提交阶段超时重试最多 3 次，指数退避（1s → 2s → 4s）
- **轮询阶段**：无重试，遇到错误直接回调 `onError`
- **结果解析**：支持 4 种 JSON 响应路径（`payload_msg.result`、`Data.Result`、`result`、`utterances`），每种路径还递归处理嵌套的 `utterances` 和 `result` 数组

PCM→WAV 转换参数：
- 固定 16bit，单声道
- 采样率由调用方传入（ESP32 数据固定 16000 Hz）

### 3.4 VolcTtsClient — TTS 客户端（WebSocket 双向流式）

[VolcTtsClient.java](src/main/java/com/volc/voice/tts/VolcTtsClient.java)

使用火山引擎 TTS 双向 WebSocket 协议：

```
连接建立
  → StartConnection
  → 等待 ConnectionStarted 事件
  → startPing() 启动心跳（30s 间隔）

合成流程 (synthesize):
  → 自动重连（如果断开）
  → StartSession (包含 speaker、audio_params 等配置)
  → 等待 SessionStarted 事件
  → TaskRequest (发送待合成文本)
  → receiveAudioLoop() 循环接收音频
     → AUDIO_ONLY_SERVER → 提取 OGG page → OggDemuxer → Opus 包
     → FULL_SERVER_RESPONSE → TTS_SENTENCE_START/END 事件
     → TTS_ENDED → 主动 finishSession
     → SESSION_FINISHED → 结束
  → finishSession() 清理
```

关键设计：
- **OGG 容器解复用**：`OggDemuxer` 从 OGG 页中提取原始 Opus 包
- **双回调**：`onOpusPacket(byte[])` 给 ESP32（每个 Opus 包，实时播放），`onOggPage(byte[])` 给录音保存（完整 OGG 页）
- **自动重连**：连接断开时指数退避重连（1s → 2s → ... → 30s max，最多 10 次）
- **合成前自动重连**：如果 `synthesize()` 时连接已断开，自动触发重连后再合成
- **心跳**：每 30 秒 WebSocket Ping
- **finishSession 幂等性**：如果 session 已被服务端结束（`state != SESSION_ACTIVE`），再次调用 `finishSession()` 不会重复操作，仅清理 `sessionId`
- **stopPing 安全**：在 disconnect、reconnect 和 onClose 路径中都会调用 `stopPing()`，防止泄漏

#### TTS 状态机

```
DISCONNECTED → CONNECTING → CONNECTED → SESSION_ACTIVE → CONNECTED → ...
      ▲              │              ▲              │
      │              ▼              │              ▼
      └────── (on error/disconnect) └──── (session finished/canceled)
```

### 3.5 OggDemuxer — OGG 容器解复用器

[OggDemuxer.java](src/main/java/com/volc/voice/ogg/OggDemuxer.java)

职责：从 OGG 容器字节流中提取原始 Opus 音频包。

实现要点：
- 按 RFC 3533 解析 OGG 页结构（OggS 魔数 → 页头 → 段表 → 段数据）
- 跳过 BOS（OpusHead）页和首个非 BOS 页（OpusTags）
- 根据段表大小将段数据拆分为 Opus 包（段大小 < 255 表示包边界）
- 内部使用 `ByteBuffer` 维护未消费数据

### 3.6 ProtocolHelper / MessageCodec — 火山引擎二进制协议

[ProtocolHelper.java](src/main/java/com/volc/voice/protocol/ProtocolHelper.java)
[MessageCodec.java](src/main/java/com/volc/voice/protocol/MessageCodec.java)

实现火山引擎语音服务的二进制协议栈：

**MessageCodec（编解码器）：**
- 固定头：4bit version + 4bit headerSize + 4bit type + 4bit flag + 4bit serialization + 4bit compression
- 扩展字段顺序：Event(int32) → SessionId(长度前缀+UTF-8) → ConnectId(长度前缀+UTF-8) → Sequence(int32) → ErrorCode(uint32)
- Payload：4字节长度前缀 + 数据

**ProtocolHelper（消息队列）：**
- 每个 WebSocket 维护独立的消息队列和等待者队列
- `register(ws)` — 注册新 WebSocket
- `receiveMessage(ws)` — 阻塞式等待下一条消息（返回 CompletableFuture）
- `onBinaryMessage(ws, data)` — 喂入收到的二进制消息
- `unregister(ws, reason)` — 清理，拒绝所有等待者

**高级协议方法：**
- `startConnection` / `finishConnection`
- `startSession` / `finishSession` / `cancelSession`
- `audioOnly` — 发送纯音频帧（用于流式 ASR）
- `taskRequest` — 发送任务请求（用于 TTS 文本提交）

### 3.7 Message / MessageType / EventType — 数据模型

| 类 | 职责 |
|------|------|
| [Message.java](src/main/java/com/volc/voice/protocol/Message.java) | 消息数据结构（version, type, flag, event, sessionId, payload 等） |
| [MessageType.java](src/main/java/com/volc/voice/protocol/MessageType.java) | 消息类型常量（FULL_CLIENT_REQUEST, AUDIO_ONLY_CLIENT, FULL_SERVER_RESPONSE 等） |
| [EventType.java](src/main/java/com/volc/voice/protocol/EventType.java) | 事件类型常量（START_CONNECTION, SESSION_STARTED, TTS_SENTENCE_START 等 50+ 种事件） |

### 3.8 VolcAsrClient — 流式 ASR（**未使用，死代码**）

[VolcAsrClient.java](src/main/java/com/volc/voice/asr/VolcAsrClient.java)

使用火山引擎流式 ASR WebSocket 协议。支持实时音频流转发、中间结果（`isFinal: false`）和会话管理。当前代码中**无任何地方引用或实例化此类** — 详见第 5 节审计。

---

## 4. 完整数据流

### 4.1 ASR → TTS 自动管线

```
ESP32                          LocalServer                       云端
  │                                 │                              │
  │── {"type":"asr_start"} ────────→│ asrRecording=true             │
  │── [PCM binary] ────────────────→│ pcmBuffer.write(data)        │
  │── [PCM binary] ────────────────→│ (持续缓冲+诊断)                │
  │── [PCM binary] ────────────────→│ pcmBuffer.write(data)        │
  │── {"type":"asr_stop"} ─────────→│ asrRecording=false            │
  │                                 │ savePcmRecording()           │
  │                                 │                              │
  │                                 │── PCM→WAV, Base64 ──────────→│
  │                                 │── POST /submit ─────────────→│  (最多重试3次)
  │                                 │←── TaskID ───────────────────│
  │                                 │── POST /query (轮询) ────────→│  (每秒一次)
  │                                 │←── 识别结果 JSON ────────────│
  │←── {"type":"asr_result"} ──────│                              │
  │                                 │                              │
  │                      (isFinal=true && text非空时自动触发TTS)     │
  │                                 │                              │
  │                                 │── StartSession ─────────────→│
  │←── {"type":"tts_start"} ───────│                              │
  │                                 │── TaskRequest(text) ────────→│
  │                                 │←── Opus Audio ──────────────│
  │←── [Opus binary] ──────────────│  (OggDemuxer拆出Opus包)       │
  │←── [Opus binary] ──────────────│                              │
  │                                 │←── SESSION_FINISHED ────────│
  │←── {"type":"tts_end"} ─────────│  (保存OGG录音 → recordings/)  │
```

### 4.2 独立 TTS 请求（ESP32 直接请求）

```
ESP32 ── {"type":"tts_request","text":"你好"} ──→ LocalServer
                                                     │
                                                     ├── ttsSynthesizing 检查（防止重复）
                                                     ├── 发送 tts_start 给 ESP32
                                                     ├── tts.synthesize("你好")
                                                     │     └── 失败时 synthesizeWithRetry 重试1次
                                                     ├── 回调 onOpusPacket → 二进制发送
                                                     ├── 回调 onOggPage → oggBuffer 缓冲
                                                     ├── onSessionFinished → 发送 tts_end
                                                     └── saveRecording() 保存 OGG 文件
```

### 4.3 健康检查数据流

```
Client ── GET /health ──→ LocalServer
                             │
                             ├── esp32Session != null && isOpen()
                             ├── asr.isConnected()  → 始终 true (HTTP模式)
                             ├── asrRecording
                             ├── lastRecordingBytes / lastRecordingChunks
                             ├── tts.isConnected()  → CONNECTED 或 SESSION_ACTIVE
                             └── ttsSynthesizing
```

---

## 5. 质量审计

### 5.1 死代码

#### **A. VolcAsrClient.java — 整个类未被使用** ⚠️

[VolcAsrClient.java](src/main/java/com/volc/voice/asr/VolcAsrClient.java)（423 行）实现了火山引擎流式 ASR WebSocket 客户端，包含完整的连接管理、会话管理、音频流发送、结果解析、自动重连等功能。

但是：
- `LocalServer` 仅导入和使用 `AucAsrClient`，不使用 `VolcAsrClient`
- `VoiceServerApp` 不使用 `VolcAsrClient`
- 项目中**没有任何代码**实例化或引用 `VolcAsrClient`

**影响：** 增加编译产物体积（已编译到 target 中），增加了维护负担。但 `ProtocolHelper`、`MessageCodec`、`Message`、`MessageType`、`EventType` 仍被 `VolcTtsClient` 使用，所以协议层代码不可删除。

**建议：** 如果确认后续不会切换到流式 ASR，删除此文件。

#### **B. VolcAsrClient 中的 State 枚举**

`VolcAsrClient.State`（DISCONNECTED, CONNECTING, CONNECTED, SESSION_ACTIVE, DISCONNECTING）仅在此类中使用。由于 VolcAsrClient 未被使用，此枚举也是死代码。注意 `VolcTtsClient` 有自己的同名 `State` 枚举（定义在 TTS 类内部），两者独立。

### 5.2 流程风险

#### **A. Binary PCM 检测方法存在兼容路径** ⚠️ 低

[LocalServer.java:311-325](src/main/java/com/volc/voice/server/LocalServer.java#L311-L325)

当前实现有两层分流：
1. `onWebSocketText` — 直接处理 JSON 控制消息（推荐路径）
2. `onWebSocketBinary` — 通过 `new JSONObject(new String(data))` 试探区分 JSON/PCM

如果 ESP32 固件将所有数据都以二进制帧发送，则回退到试探解析方式。试探方式存在两个问题：
- **误判风险**：合法的 PCM 数据理论上可能恰好解码为有效 UTF-8 且格式符合 JSON（概率极低）
- **性能开销**：每个 PCM 块都要执行 `new String(data)` 和 `new JSONObject(...)` 并捕获异常

**建议：** 在 ESP32 固件侧使用 WebSocket 文本帧发 JSON、二进制帧发 PCM，利用 WebSocket 原生的文本/二进制帧区分机制。当前服务器已支持此模式（通过 `onWebSocketText`），只需固件侧配合即可移除试探逻辑。

#### **B. AUC HttpClient 重试时未重建连接池** ⚠️ 低

[AucAsrClient.java:188-189](src/main/java/com/volc/voice/asr/AucAsrClient.java#L188-L189)

```java
if (isTimeout && attempt < MAX_RETRIES) {
    System.err.println("[AUC] Submit attempt " + attempt + " timed out, will retry");
    // Create a fresh HttpClient in case of stale connection pool
    continue;
}
```

注释写"创建新的 HttpClient"，但代码只是 `continue`，未实际创建新的 HttpClient 实例。`httpClient` 字段是 `final` 的，无法重新赋值。

**影响：** 如果超时是由连接池中的过时连接导致，重试会继续使用同一连接池，重试可能也会失败。

#### **C. receiveAudioLoop 中 blocking get() 无超时** ⚠️ 低

[VolcTtsClient.java:394](src/main/java/com/volc/voice/tts/VolcTtsClient.java#L394)

```java
Message msg = ProtocolHelper.receiveMessage(ws).get();
```

这是一个无限期阻塞的 `CompletableFuture.get()`。如果 WebSocket 连接静默断开（没有触发 `onClose`），此调用将永久阻塞。Jetty WebSocket 的 idle timeout（10 分钟）是唯一的兜底机制。

#### **D. TTS synthesize 失败后的重试依赖自动重连** ⚠️ 低

[LocalServer.java:269-289](src/main/java/com/volc/voice/server/LocalServer.java#L269-L289)

`synthesizeWithRetry()` 在 TTS 合成异常时等待 1.5s 后重试。但这 1.5s 内 TTS 客户端需要完成自动重连（指数退避从 1s 开始），如果重连尚未完成，重试的 `synthesize()` 会触发 `VolcTtsClient` 内部的自动重连逻辑（`synthesize()` 方法 line 217），形成嵌套重连。

### 5.3 线程安全

#### **A. LocalServer.msgQueue 串行化正确** ✓

JSON 消息通过 `msgQueue = msgQueue.thenAccept(v -> ...)` 链式执行，保证顺序处理。PCM 二进制走快路径不经过队列，避免音频延迟。设计合理。

#### **B. esp32Session 的 volatile + 快照** ✓

`sendToEsp()` 和 TTS 回调方法先将 `esp32Session` 读到局部变量 `s`，然后检查 `s.isOpen()`，避免了 TOCTOU 问题。

#### **C. ProtocolHelper 的 static Map 安全** ✓

`queues` 和 `waiters` 是 `ConcurrentHashMap`，队列是 `ConcurrentLinkedQueue`。每个 WebSocket 的回调在 Jetty 的 WebSocket 线程中串行执行，不会并发操作同一队列。

### 5.4 资源泄漏检查

| 资源 | 清理方式 | 状态 |
|------|----------|------|
| ASR Scheduler | `disconnect()` 中 `scheduler.shutdown()` | ✓ (仅 VolcAsrClient 有 scheduler) |
| TTS Scheduler + Ping | `disconnect()` 中 `stopPing()` + `scheduler.shutdown()` | ✓ |
| Jetty Server | `stop()` 中 `server.stop()` | ✓ |
| HttpClient (AUC) | 无显式关闭（Java 的 HttpClient 在进程退出时清理） | ✓ 可接受 |
| HttpClient (TTS/ASR WebSocket) | 无显式关闭 | ✓ 可接受 |
| ByteArrayOutputStream | 纯内存流，GC 自动回收 | ✓ |
| OggDemuxer ByteBuffer | `reset()` 方法重建 | ✓ |

### 5.5 文档不一致

| 位置 | 内容 | 实际 |
|------|------|------|
| `.env.example:3` | `VOLC_ASR_RESOURCE_ID=volc.bigasr.sauc.duration` | `VoiceServerApp.java:33` 默认值是 `volc.bigasr.auc` |

### 5.6 缺少 .gitignore

项目没有 `.gitignore` 文件，`recordings/` 目录中的大量 PCM/OGG 文件、`target/` 编译产物和 `.env` 密钥文件可能被意外提交。

---

## 6. 配置项汇总

| 配置项 | `.env` 变量 | CLI 参数 | 默认值 |
|--------|------------|----------|--------|
| 火山引擎 AppId | `VOLC_APP_ID` | `--appid` | 无 |
| 火山引擎 AccessToken | `VOLC_ACCESS_TOKEN` | `--access-token` | 无 |
| 监听端口 | - | `--port` | `3000` |
| ASR 资源 ID | `VOLC_ASR_RESOURCE_ID` | `--asr-resource-id` | `volc.bigasr.auc` |
| TTS 资源 ID | `VOLC_TTS_RESOURCE_ID` | `--tts-resource-id` | 自动推断 |
| TTS 音色 | `VOLC_VOICE_TYPE` | `--voice-type` | `zh_female_shuangkuaisisi_moon_bigtts` |
| TTS 采样率 | - | `--sample-rate` | `24000` |

---

## 7. 部署与运维

### 编译

```bash
mvn clean package -DskipTests
```

产物：`target/voicerserver-1.0.0-jar-with-dependencies.jar`（胖包，可直接运行）

### 运行

```bash
java -jar target/voicerserver-1.0.0-jar-with-dependencies.jar
```

### 健康检查

```bash
curl http://localhost:3000/health
```

```json
{
  "status": "ok",
  "esp32Connected": true,
  "asrConnected": true,
  "asrRecording": false,
  "lastRecordingBytes": 33920,
  "lastRecordingChunks": 53,
  "ttsConnected": true,
  "ttsSynthesizing": false
}
```

### 录制文件

`recordings/` 目录保存两类文件：
- `pcm_<时间戳>_<字节数>b.pcm` — 原始 PCM 录音（可用 `ffplay -f s16le -ar 16000 -ac 1 <file>` 回放）
- `tts_<时间戳>_<sessionId前缀>.ogg` — TTS 合成音频（可用任意 OGG/Opus 播放器回放）

### 测试工具

- [test_ws_client.cjs](test_ws_client.cjs) — 模拟 ESP32，测试 WebSocket 双向通信（ping/pong、TTS 请求、ASR 流程）
- [test_loopback.cjs](test_loopback.cjs) — 闭环测试：TTS 合成 → OGG 解码 → ASR 文件识别 → 验证文字一致性

---

*文档生成时间：2026-05-24，基于当前代码版本。*
