/**
 * WebSocket 测试客户端 — 模拟 ESP32 行为，验证双向通信。
 * 用法: node test_ws_client.cjs
 * 需要 Node.js 22+（内置 WebSocket）
 */

const SERVER = 'ws://localhost:3000';
const TEST_TEXT = '你好世界，这是一个双向通信测试';

let receivedTypes = new Set();
let receivedOpusPackets = 0;
let ttsStarted = false;
let ttsEnded = false;
let pongReceived = false;

const ws = new WebSocket(SERVER);
ws.binaryType = 'arraybuffer';

ws.addEventListener('open', () => {
  console.log('[CLIENT] Connected to server');

  // Step 1: 发送 ping 测试心跳
  console.log('[CLIENT] Sending ping...');
  ws.send(JSON.stringify({ type: 'ping' }));
});

ws.addEventListener('message', (event) => {
  if (event.data instanceof ArrayBuffer) {
    const buf = Buffer.from(event.data);
    receivedOpusPackets++;
    if (receivedOpusPackets === 1) {
      console.log(`[CLIENT] Received first Opus packet (${buf.length} bytes)`);
    }
    return;
  }

  const msg = JSON.parse(event.data.toString());
  const type = msg.type;
  receivedTypes.add(type);
  console.log(`[CLIENT] Received JSON: type="${type}"`, JSON.stringify(msg).slice(0, 120));

  switch (type) {
    case 'pong':
      pongReceived = true;
      console.log('[CLIENT] ✓ Heartbeat OK');
      // Step 2: 测试 TTS
      console.log(`[CLIENT] Sending tts_request: "${TEST_TEXT}"`);
      ws.send(JSON.stringify({ type: 'tts_request', text: TEST_TEXT }));
      break;

    case 'tts_start':
      ttsStarted = true;
      console.log(`[CLIENT] ✓ TTS started (sample_rate=${msg.sample_rate}, channels=${msg.channels})`);
      break;

    case 'tts_end':
      ttsEnded = true;
      console.log(`[CLIENT] ✓ TTS ended (received ${receivedOpusPackets} Opus packets)`);

      // Step 3: 测试 ASR 流程（发送 start/stop）
      setTimeout(() => {
        console.log('[CLIENT] Sending asr_start...');
        ws.send(JSON.stringify({ type: 'asr_start' }));
        setTimeout(() => {
          console.log('[CLIENT] Sending asr_stop...');
          ws.send(JSON.stringify({ type: 'asr_stop' }));
          setTimeout(() => {
            printSummary();
            ws.close();
          }, 1000);
        }, 500);
      }, 500);
      break;

    case 'asr_result':
      console.log(`[CLIENT] ✓ ASR result (is_final=${msg.is_final}): "${msg.text}"`);
      break;

    case 'status':
      console.log(`[CLIENT] Status: ${msg.message}`);
      break;

    case 'error':
      console.error(`[CLIENT] ✗ Error: ${msg.message}`);
      break;
  }
});

ws.addEventListener('close', () => {
  console.log('[CLIENT] Disconnected');
  printSummary();
  process.exit(0);
});

ws.addEventListener('error', (err) => {
  console.error(`[CLIENT] WebSocket error: ${err.message}`);
  process.exit(1);
});

// 超时保护
setTimeout(() => {
  console.error('[CLIENT] ✗ Timeout after 30s');
  printSummary();
  ws.close();
  process.exit(1);
}, 30000);

function printSummary() {
  console.log('\n========== 测试结果汇总 ==========');
  const checks = [
    ['心跳 (ping/pong)', pongReceived],
    ['TTS 开始', ttsStarted],
    ['TTS 结束', ttsEnded],
    ['接收 Opus 音频包', receivedOpusPackets > 0],
  ];

  let allPassed = true;
  for (const [name, ok] of checks) {
    console.log(`  ${ok ? '✓' : '✗'} ${name}`);
    if (!ok) allPassed = false;
  }

  console.log(`\nOpus 包总数: ${receivedOpusPackets}`);
  console.log(`接收消息类型: ${[...receivedTypes].join(', ')}`);
  console.log(allPassed ? '\n✓ 双向通信测试通过' : '\n✗ 部分测试未通过');
}
