# ESP32 Volcengine Bridge Server (Java)

Java 桥接服务器，运行在 Linux 单板计算机（Orange Pi / Raspberry Pi）上，负责在 ESP32 固件与火山引擎云端语音服务之间中转数据。

## 功能

- **ASR（语音识别）**：接收 ESP32 发来的 PCM 音频流，收集完整录音后通过 HTTP 提交至火山引擎 AUC 服务进行整段文件识别，将识别结果回传 ESP32
- **TTS（语音合成）**：根据识别到的文字，调用火山引擎 TTS 服务合成语音，将 Opus 音频流实时推送至 ESP32 播放

```
ESP32 <--WebSocket--> Java Server <--HTTP/WebSocket--> 火山引擎云端
       (PCM/JSON)                                   (ASR文件识别 + TTS流式)
```

## 环境要求

| 依赖 | 版本要求 |
|------|----------|
| JDK | 17+ |
| Maven | 3.6+ |

## 快速开始

### 1. 配置密钥

```bash
cp .env.example .env
```

编辑 `.env`，填入火山引擎应用凭证：

```env
VOLC_APP_ID=你的AppId
VOLC_ACCESS_TOKEN=你的AccessToken
VOLC_ASR_RESOURCE_ID=volc.bigasr.auc
VOLC_TTS_RESOURCE_ID=volc.service_type.10029
VOLC_VOICE_TYPE=zh_female_shuangkuaisisi_moon_bigtts
```

### 2. 编译打包

```bash
mvn clean package -DskipTests
```

编译产物位于 `target/`：

- `voicerserver-1.0.0.jar` — 薄包
- `voicerserver-1.0.0-jar-with-dependencies.jar` — 胖包（推荐）

### 3. 启动

```bash
java -jar target/voicerserver-1.0.0-jar-with-dependencies.jar
```

## 命令行参数

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `--appid` | 火山引擎 AppId | 从 `.env` 读取 |
| `--access-token` | 火山引擎 AccessToken | 从 `.env` 读取 |
| `--port` | HTTP/WebSocket 监听端口 | `3000` |
| `--voice-type` | TTS 音色 | `zh_female_shuangkuaisisi_moon_bigtts` |
| `--asr-resource-id` | ASR 资源 ID | `volc.bigasr.auc` |
| `--tts-resource-id` | TTS 资源 ID | `volc.service_type.10029` |
| `--sample-rate` | TTS 采样率 | `24000` |

## WebSocket 协议

ESP32 连接至 `ws://<server-ip>:3000/`

### ESP32 → 服务器

| 消息类型 | 格式 | 说明 |
|----------|------|------|
| `asr_start` | `{"type":"asr_start"}` | 开始录音 |
| PCM 音频 | 二进制 16kHz/16bit/单声道 | 录音音频流 |
| `asr_stop` | `{"type":"asr_stop"}` | 停止录音，触发识别 |
| `tts_request` | `{"type":"tts_request","text":"你好"}` | 请求 TTS 合成 |
| `ping` | `{"type":"ping"}` | 心跳 |

### 服务器 → ESP32

| 消息类型 | 格式 | 说明 |
|----------|------|------|
| `status` | `{"type":"status","message":"..."}` | 连接状态 |
| `asr_result` | `{"type":"asr_result","text":"...","is_final":true}` | 识别结果 |
| `tts_start` | `{"type":"tts_start","sample_rate":24000,"channels":1}` | 合成开始 |
| Opus 音频 | 二进制 | 合成音频数据 |
| `tts_end` | `{"type":"tts_end"}` | 播放完毕 |
| `pong` | `{"type":"pong"}` | 心跳响应 |
| `error` | `{"type":"error","message":"..."}` | 错误信息 |

### 典型流程

```
ESP32                    Server                      云端
  |                         |                          |
  |-- {"type":"asr_start"}->|                          |
  |-- [PCM binary data] --->| (缓冲到内存)              |
  |-- {"type":"asr_stop"}-->|                          |
  |                         |--- HTTP POST /submit --->|
  |                         |--- HTTP POST /query ---->|
  |                         |<-- AUC Result -----------|
  |<- {"type":"asr_result"} |                          |
  |                         |--- TTS StartSession ---->|
  |<- {"type":"tts_start"}  |                          |
  |                         |--- SendText ------------>|
  |                         |<-- Opus Audio -----------|
  |<- [Opus binary data] ---|                          |
  |<- {"type":"tts_end"}    |                          |
```

## 健康检查

```bash
curl http://localhost:3000/health
```

```json
{
  "status": "ok",
  "esp32Connected": true,
  "asrConnected": true,
  "asrRecording": false,
  "ttsConnected": true,
  "ttsSynthesizing": false
}
```

## 项目结构

```
voicerserver/
├── pom.xml                     # Maven 配置
├── .env / .env.example         # 环境变量
├── recordings/                 # TTS 录音输出
└── src/main/java/com/volc/voice/
    ├── VoiceServerApp.java     # 主入口
    ├── server/
    │   └── LocalServer.java    # HTTP + WebSocket 服务器
    ├── asr/
    │   ├── AucAsrClient.java   # AUC 整段文件识别
    │   └── VolcAsrClient.java  # 流式 ASR（旧）
    ├── tts/
    │   └── VolcTtsClient.java  # TTS WebSocket 客户端
    ├── ogg/
    │   └── OggDemuxer.java     # OGG/Opus 解复用
    └── protocol/
        ├── MessageCodec.java   # 二进制协议编解码
        ├── ProtocolHelper.java
        ├── Message.java
        ├── MessageType.java
        └── EventType.java
```

## 常见问题

**启动后 ESP32 无法连接？** 检查防火墙是否放行对应端口，确认 ESP32 连接的是服务器局域网 IP（启动时会打印）。

**ASR/TTS 连接失败？** 检查 `.env` 中的凭证是否正确，确认火山引擎账户余额充足。

**如何更改端口？** `java -jar ... --port 8080`

**如何切换音色？** 通过 `--voice-type` 参数或修改 `.env` 中的 `VOLC_VOICE_TYPE`。
