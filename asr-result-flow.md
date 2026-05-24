# ASR 识别结果传输流程（Java 服务器）

## 概述

Java 服务器使用火山引擎 **AUC（Audio Understanding and Comprehension）HTTP 整段文件识别**。ESP32 录音结束后，服务器将完整 PCM 音频通过 HTTP 提交至 AUC 服务，轮询获取识别结果后通过 WebSocket 回传 ESP32。

## 数据流

```
ESP32                    Java Server                   火山引擎 AUC
  |                          |                              |
  |-- {"type":"asr_start"}-->|                              |
  |-- [PCM binary chunks] -->| (缓冲到内存 pcmBuffer)        |
  |-- {"type":"asr_stop"}-->|                              |
  |                          |-- PCM 转 WAV, Base64 编码 -->|
  |                          |-- POST /submit ------------->|
  |                          |-- POST /query (轮询) ------->|
  |                          |<-- 识别结果 ------------------|
  |<- {"type":"asr_result"}  |                              |
  |                          |-- 自动触发 TTS ------------->|
```

## 详细步骤

### 1. ESP32 发送 asr_start

ESP32 通过 WebSocket 发送 JSON 控制消息，服务器开始缓冲 PCM 音频。

```json
{"type": "asr_start"}
```

相关代码: [LocalServer.java:330-336](../java-demos/voicerserver/src/main/java/com/volc/voice/server/LocalServer.java#L330-L336)

### 2. ESP32 发送 PCM 音频流

ESP32 通过 WebSocket 二进制帧持续发送 16kHz/16bit/单声道 PCM 音频数据，服务器将数据写入 `pcmBuffer`（内存缓冲）。

相关代码: [LocalServer.java:378-420](../java-demos/voicerserver/src/main/java/com/volc/voice/server/LocalServer.java#L378-L420)

### 3. ESP32 发送 asr_stop，触发识别

ESP32 发送停止消息，服务器将缓冲的完整 PCM 数据提交至 AUC 服务。

```json
{"type": "asr_stop"}
```

相关代码: [LocalServer.java:338-358](../java-demos/voicerserver/src/main/java/com/volc/voice/server/LocalServer.java#L338-L358)

### 4. AUC 提交与轮询

`AucAsrClient` 将 PCM 转换为 WAV 格式并 Base64 编码，通过 HTTP POST 提交至 AUC `/submit` 端点，然后轮询 `/query` 端点直到识别完成。

相关代码: [AucAsrClient.java:79-91](../java-demos/voicerserver/src/main/java/com/volc/voice/asr/AucAsrClient.java#L79-L91)

### 5. 解析识别结果

`AucAsrClient.parseResponse()` 解析 AUC 返回的 JSON，提取识别文本，调用 `callbacks.onResult(text, isFinal)`。

相关代码: [AucAsrClient.java:257-355](../java-demos/voicerserver/src/main/java/com/volc/voice/asr/AucAsrClient.java#L257-L355)

### 6. 服务器发送结果给 ESP32

`LocalServer` 在回调中通过 `sendToEsp()` 以 JSON 格式将识别结果发送给 ESP32。

相关代码: [LocalServer.java:79-86](../java-demos/voicerserver/src/main/java/com/volc/voice/server/LocalServer.java#L79-L86)

**消息格式:**

```json
{
  "type": "asr_result",
  "text": "识别的文字内容",
  "is_final": true
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | string | 固定为 `"asr_result"` |
| `text` | string | 识别出的文字 |
| `is_final` | boolean | AUC 为整段识别，结果始终为 `true` |

### 7. 自动触发 TTS

当 `is_final` 为 `true` 且文本非空时，服务器自动调用 TTS 将识别文字合成为语音播放。

相关代码: [LocalServer.java:87-89](../java-demos/voicerserver/src/main/java/com/volc/voice/server/LocalServer.java#L87-L89)

## 与 Node.js 版本的区别

| 特性 | Java 服务器 | Node.js 服务器 |
|------|------------|---------------|
| ASR 方式 | AUC HTTP 整段文件识别 | 流式 WebSocket ASR |
| 中间结果 | 不支持（仅最终结果） | 支持 `is_final: false` 部分结果 |
| 音频提交 | 录音结束后一次性提交 | 实时流式发送 |
| 识别延迟 | 需等待完整录音 + HTTP 轮询 | 边说边识别，延迟更低 |

## 发送到 ESP32 的所有消息类型

| 消息类型 | 格式 | 说明 |
|----------|------|------|
| `asr_result` | `{"type":"asr_result","text":"...","is_final":true}` | ASR 识别结果 |
| `tts_start` | `{"type":"tts_start","sample_rate":24000,"channels":1}` | TTS 合成开始 |
| Opus 音频 | 二进制帧 | TTS 合成音频数据 |
| `tts_end` | `{"type":"tts_end"}` | TTS 播放完毕 |
| `status` | `{"type":"status","message":"..."}` | 连接状态 |
| `pong` | `{"type":"pong"}` | 心跳响应 |
| `error` | `{"type":"error","message":"..."}` | 错误信息 |

## 关键文件

| 文件 | 作用 |
|------|------|
| [LocalServer.java](../java-demos/voicerserver/src/main/java/com/volc/voice/server/LocalServer.java) | 本地服务器，管理 ESP32 连接、消息转发和 ASR→TTS 管线 |
| [AucAsrClient.java](../java-demos/voicerserver/src/main/java/com/volc/voice/asr/AucAsrClient.java) | AUC HTTP 客户端，PCM→WAV 转换、提交、轮询、结果解析 |
| [VolcTtsClient.java](../java-demos/voicerserver/src/main/java/com/volc/voice/tts/VolcTtsClient.java) | TTS WebSocket 客户端，流式语音合成 |
