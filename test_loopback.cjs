/**
 * 闭环测试: TTS 合成 → OGG 解码 → ASR 文件识别 → 验证文字一致
 *
 * 流程:
 *   1. 发送 tts_request，等待 tts_end（服务器自动保存完整 OGG 文件）
 *   2. 取最新的 OGG 录音，ffmpeg 转换为 16kHz PCM
 *   3. 重新连接，发送 asr_start → 快速发送所有 PCM → asr_stop
 *      （服务器缓冲完整 PCM，转为 WAV，通过 HTTP 提交至 AUC 文件识别 API）
 *   4. 对比 ASR 识别结果与原始文字
 *
 * 用法: node test_loopback.cjs
 * 需要: ffmpeg, Node.js 22+
 */

const { execSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const SERVER = 'ws://localhost:3000';
const TEST_TEXT = '你好世界';
const RECORDINGS_DIR = path.join(__dirname, 'recordings');

// ========== Phase 1: TTS 合成 ==========

async function phase1_tts() {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(SERVER);
    ws.binaryType = 'arraybuffer';

    let opusCount = 0;
    let ttsDone = false;

    const timeout = setTimeout(() => {
      ws.close();
      reject(new Error('Phase 1 timeout'));
    }, 20000);

    ws.addEventListener('open', () => {
      console.log('[Phase1] Connected, requesting TTS...');
      ws.send(JSON.stringify({ type: 'ping' }));
    });

    ws.addEventListener('message', (event) => {
      if (event.data instanceof ArrayBuffer) {
        opusCount++;
        return;
      }

      const msg = JSON.parse(event.data.toString());

      switch (msg.type) {
        case 'pong':
          console.log(`[Phase1] Sending tts_request: "${TEST_TEXT}"`);
          ws.send(JSON.stringify({ type: 'tts_request', text: TEST_TEXT }));
          break;

        case 'tts_start':
          console.log('[Phase1] TTS synthesis started...');
          break;

        case 'tts_end':
          ttsDone = true;
          clearTimeout(timeout);
          console.log(`[Phase1] TTS complete: ${opusCount} Opus packets received`);
          setTimeout(() => {
            ws.close();
            resolve();
          }, 500);
          break;

        case 'status':
          break;

        case 'error':
          clearTimeout(timeout);
          ws.close();
          reject(new Error(`TTS error: ${msg.message}`));
          break;
      }
    });

    ws.addEventListener('error', (err) => {
      clearTimeout(timeout);
      reject(new Error(`WebSocket error: ${err.message}`));
    });
  });
}

// ========== Phase 2: 解码最新 OGG 录音 → PCM ==========

function phase2_decode() {
  const files = fs.readdirSync(RECORDINGS_DIR)
    .filter(f => f.endsWith('.ogg'))
    .map(f => ({
      name: f,
      path: path.join(RECORDINGS_DIR, f),
      mtime: fs.statSync(path.join(RECORDINGS_DIR, f)).mtimeMs
    }))
    .sort((a, b) => b.mtime - a.mtime);

  if (files.length === 0) throw new Error('No OGG recordings found');

  const latestOgg = files[0];
  const pcmPath = path.join(RECORDINGS_DIR, 'test_asr_input.pcm');

  console.log(`[Phase2] Using recording: ${latestOgg.name} (${fs.statSync(latestOgg.path).size} bytes)`);

  execSync(
    `ffmpeg -i "${latestOgg.path}" -f s16le -acodec pcm_s16le -ar 16000 -ac 1 "${pcmPath}" -y -loglevel error`,
    { stdio: 'inherit' }
  );

  const pcmData = fs.readFileSync(pcmPath);
  const duration = pcmData.length / 32000;
  console.log(`[Phase2] Decoded PCM: ${pcmData.length} bytes (${duration.toFixed(2)}s at 16kHz/16bit/mono)`);
  return pcmData;
}

// ========== Phase 3: ASR 文件识别 ==========

async function phase3_asr(pcmData) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(SERVER);
    ws.binaryType = 'arraybuffer';

    const CHUNK_SIZE = 640; // 20ms at 16kHz/16bit
    let asrResults = [];
    let sessionReady = false;
    let pcmSent = false;

    const timeout = setTimeout(() => {
      ws.close();
      reject(new Error('Phase 3 timeout (60s)'));
    }, 60000);

    ws.addEventListener('open', () => {
      console.log('[Phase3] Connected, starting ASR session...');
      ws.send(JSON.stringify({ type: 'asr_start' }));
      ws.send(JSON.stringify({ type: 'ping' })); // trigger status response
    });

    ws.addEventListener('message', (event) => {
      if (event.data instanceof ArrayBuffer) return;

      const msg = JSON.parse(event.data.toString());

      switch (msg.type) {
        case 'status':
          if (!sessionReady) {
            sessionReady = true;
            console.log('[Phase3] Session ready, sending all PCM data...');

            // Send all PCM in chunks as fast as possible
            let offset = 0;
            while (offset < pcmData.length) {
              const end = Math.min(offset + CHUNK_SIZE, pcmData.length);
              ws.send(Buffer.from(pcmData.slice(offset, end)));
              offset = end;
            }

            pcmSent = true;
            console.log(`[Phase3] All PCM sent (${pcmData.length} bytes in ${Math.ceil(pcmData.length / CHUNK_SIZE)} chunks)`);

            // Small delay then stop
            setTimeout(() => {
              console.log('[Phase3] Sending asr_stop...');
              ws.send(JSON.stringify({ type: 'asr_stop' }));
            }, 200);
          }
          break;

        case 'asr_result':
          console.log(`[Phase3] ASR ${msg.is_final ? 'FINAL' : 'PARTIAL'}: "${msg.text}"`);
          asrResults.push({ text: msg.text, isFinal: msg.is_final });
          if (msg.is_final) {
            clearTimeout(timeout);
            setTimeout(() => { ws.close(); resolve(asrResults); }, 300);
          }
          break;

        case 'pong':
          break;

        case 'error':
          console.error(`[Phase3] Error: ${msg.message}`);
          break;
      }
    });

    ws.addEventListener('error', (err) => {
      clearTimeout(timeout);
      reject(new Error(`WebSocket error: ${err.message}`));
    });
  });
}

// ========== 主流程 ==========

async function main() {
  console.log('========================================');
  console.log('  闭环测试: TTS → OGG → PCM → AUC ASR');
  console.log('========================================');
  console.log(`  测试文字: "${TEST_TEXT}"`);
  console.log('');

  try {
    // Phase 1: TTS 合成
    console.log('--- Phase 1: TTS 合成 ---');
    await phase1_tts();
    console.log('');

    // 等待服务器状态复位
    await new Promise(r => setTimeout(r, 1000));

    // Phase 2: 取最新 OGG 解码为 PCM
    console.log('--- Phase 2: OGG 解码为 PCM ---');
    const pcmData = phase2_decode();
    console.log('');

    // Phase 3: ASR 文件识别
    console.log('--- Phase 3: ASR 文件识别 (AUC HTTP) ---');
    const results = await phase3_asr(pcmData);
    console.log('');

    // ========== 汇总 ==========
    console.log('========================================');
    console.log('  闭环测试结果');
    console.log('========================================');
    console.log(`  原始文字:  "${TEST_TEXT}"`);

    const finalResults = results.filter(r => r.isFinal);
    const partialResults = results.filter(r => !r.isFinal);
    console.log(`  部分结果:  ${partialResults.map(r => `"${r.text}"`).join(', ') || '(无)'}`);
    console.log(`  最终结果:  ${finalResults.map(r => `"${r.text}"`).join(', ') || '(无)'}`);

    if (finalResults.length > 0) {
      const recognized = finalResults[finalResults.length - 1].text;
      const originalClean = TEST_TEXT.replace(/[，。！？、\s]/g, '');
      const recognizedClean = recognized.replace(/[，。！？、\s]/g, '');

      console.log('');
      if (originalClean === recognizedClean) {
        console.log('  ✓ 闭环验证成功！识别结果与原始文字完全匹配');
      } else {
        console.log(`  △ 差异: 原始="${originalClean}" 识别="${recognizedClean}"`);
      }
    } else {
      console.log('  ✗ 未收到最终识别结果');
    }
  } catch (err) {
    console.error('\n✗ 测试失败:', err.message);
    process.exit(1);
  }
}

main();
