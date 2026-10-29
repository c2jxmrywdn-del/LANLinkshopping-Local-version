// bench-qwen.mjs — 本地 qwen2.5:0.5b 性能探测 + Redis 业务影响监控
// 只读探测脚本：不改任何项目源码；全程 ≤3 分钟；单请求 ≤60s；探完立即退出。
// 用法: node bench-qwen.mjs
import { readFileSync } from 'node:fs';
import { execSync, spawnSync } from 'node:child_process';
import { performance } from 'node:perf_hooks';

const T0 = performance.now();
const OVERALL_DEADLINE_MS = 165_000; // 总时限红线（<3min）
const PER_REQ_TIMEOUT_MS = 55_000;   // 单请求 ≤60s 红线

const OLLAMA_CHAT = 'http://127.0.0.1:11434/api/chat';
const MODEL = 'qwen2.5:0.5b';
const REDIS_CONTAINER = 'shopping-redis-1';
const OLLAMA_CONTAINER = 'lanlink-ollama';

const PROMPT_INTRO = '你好，用一句话介绍你自己。';
const PROMPT_B2B = '用 100 字介绍 B2B 电商平台的核心功能。';
const PROMPT_LONG = '平台支持企业账期、对公转账与批量报价。'.repeat(120) + '\n请总结平台支持的支付方式。';

// ---------- 1. 读取 .env 中 REDIS_PASSWORD（不回显明文） ----------
let REDIS_PASSWORD = '';
try {
  const envText = readFileSync(new URL('./.env', import.meta.url), 'utf8');
  const m = envText.match(/^\s*REDIS_PASSWORD\s*=\s*(.+?)\s*$/m);
  if (m) REDIS_PASSWORD = m[1].trim().replace(/^["']|["']$/g, '');
} catch { /* 稍后在采样中体现失败 */ }
const PWD_SAFE = /^[A-Za-z0-9_.\-]+$/.test(REDIS_PASSWORD); // cmd 安全字符集
console.log(`[env] REDIS_PASSWORD: ${REDIS_PASSWORD ? `已读取(长度 ${REDIS_PASSWORD.length}, 安全字符集=${PWD_SAFE})` : '读取失败!'}`);

// ---------- 2. Redis PING 采样器（每 2s，execSync docker exec） ----------
const redisSamples = []; // { t(自启动ms), ms, ok, tag }
let sampleTag = 'baseline';
function pingRedisOnce() {
  const s = performance.now();
  let ok = false;
  try {
    if (PWD_SAFE) {
      const out = execSync(
        `docker exec ${REDIS_CONTAINER} redis-cli -a ${REDIS_PASSWORD} PING`,
        { stdio: 'pipe', encoding: 'utf8', timeout: 10_000, windowsHide: true },
      );
      ok = String(out).includes('PONG');
    } else {
      // 密码含特殊字符时退化为无 shell 的数组参数调用，避免 cmd 转义问题
      const r = spawnSync('docker',
        ['exec', REDIS_CONTAINER, 'redis-cli', '-a', REDIS_PASSWORD, 'PING'],
        { timeout: 10_000, windowsHide: true, encoding: 'utf8' });
      ok = r.status === 0 && String(r.stdout || '').includes('PONG');
    }
  } catch { ok = false; }
  const ms = performance.now() - s;
  redisSamples.push({ t: Math.round(performance.now() - T0), ms, ok, tag: sampleTag });
  return ms;
}
const samplerTimer = setInterval(pingRedisOnce, 2000);

// 预热采样 2 次作为基线（含 docker exec 自身开销）
pingRedisOnce();
pingRedisOnce();

// ---------- 3. 流式 chat 请求：首 token 延迟 + tokens/s（行数-1 ÷ 秒） ----------
async function chatOnce(label, prompt, numCtx, { quiet = false } = {}) {
  const ac = new AbortController();
  const kill = setTimeout(() => ac.abort(new Error(`单请求 ${PER_REQ_TIMEOUT_MS / 1000}s 超时截断`)), PER_REQ_TIMEOUT_MS);
  const started = performance.now();
  let firstTokenMs = null;
  let parsedLines = 0;      // 非空 JSON 行（含最后一行 done:true）
  let finalChunk = null;
  let error = null;

  try {
    const res = await fetch(OLLAMA_CHAT, {
      method: 'POST',
      signal: ac.signal,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        model: MODEL,
        messages: [{ role: 'user', content: prompt }],
        stream: true,
        keep_alive: '5m',
        options: { num_ctx: numCtx, temperature: 0.2 },
      }),
    });
    if (!res.ok) throw new Error(`HTTP ${res.status} ${(await res.text().catch(() => '')).slice(0, 200)}`);

    const decoder = new TextDecoder();
    let buf = '';
    const reader = res.body.getReader();
    outer:
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });
      let nl;
      while ((nl = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, nl).trim();
        buf = buf.slice(nl + 1);
        if (!line) continue;
        try {
          const obj = JSON.parse(line);
          if (obj.done) { finalChunk = obj; break outer; }
          parsedLines++;
          if (firstTokenMs === null) firstTokenMs = performance.now() - started;
        } catch { /* 忽略无法解析的残行 */ }
      }
    }
  } catch (e) {
    error = (e && e.message) || String(e);
    if (e && e.name === 'AbortError') error = `单请求 ${PER_REQ_TIMEOUT_MS / 1000}s 超时截断`;
  } finally {
    clearTimeout(kill);
  }

  const totalMs = performance.now() - started;
  const tokens = Math.max(parsedLines - 1, 0); // 行数-1（去掉最后 done 行）
  const tps = totalMs > 0 ? +(tokens / (totalMs / 1000)).toFixed(1) : 0;
  const r = {
    label, ok: !error, truncated: !!error,
    firstTokenMs: firstTokenMs === null ? null : Math.round(firstTokenMs),
    tokens, totalMs: Math.round(totalMs), tps,
    evalCount: finalChunk?.eval_count ?? null,
    promptEvalCount: finalChunk?.prompt_eval_count ?? null,
    error,
  };
  if (!quiet) printItem(r);
  return r;
}

function fmtItem(r) {
  if (!r) return '(无结果)';
  if (r.skipped) return `${r.label}: SKIP(${r.error || '时限'})`;
  const ft = r.firstTokenMs === null ? 'N/A' : `${r.firstTokenMs}ms`;
  const flag = r.ok ? '' : ` [截断:${r.error}]`;
  const ev = r.evalCount !== null ? ` (ollama官方计数 eval=${r.evalCount}${r.promptEvalCount !== null ? `, prompt_eval=${r.promptEvalCount}` : ''})` : '';
  return `${r.label}: first-token=${ft}, tokens=${r.tokens}${ev}, tokens/s=${r.tps}, 总耗时=${(r.totalMs / 1000).toFixed(1)}s${flag}`;
}
function printItem(r) { console.log(`  -> ${fmtItem(r)}`); }

// ---------- 4. 阶段调度（总时限守护） ----------
const results = [];
async function phase(name, fn, reserveMs = 20_000) {
  const elapsed = performance.now() - T0;
  if (elapsed > OVERALL_DEADLINE_MS - reserveMs) {
    const skipped = { label: name, skipped: true, error: `总时限 ${OVERALL_DEADLINE_MS / 1000}s 临近，剩余预算不足` };
    results.push(skipped);
    console.log(`\n=== ${name} ===\n  -> SKIP: ${skipped.error}`);
    return;
  }
  console.log(`\n=== ${name} === (已用时 ${(elapsed / 1000).toFixed(1)}s)`);
  const r = await fn();
  results.push(r);
}

// ---------- 5. ollama ps（看 CONTEXT 列实际生效值） ----------
function ollamaPs() {
  try {
    return execSync(`docker exec ${OLLAMA_CONTAINER} ollama ps`,
      { encoding: 'utf8', stdio: 'pipe', timeout: 15_000, windowsHide: true }).trim();
  } catch (e) {
    return `(ollama ps 执行失败: ${(e && e.message) || e})`;
  }
}

// ---------- 主流程 ----------
try {
  console.log(`\n[probe] 目标=${OLLAMA_CHAT} 模型=${MODEL} 总时限=${OVERALL_DEADLINE_MS / 1000}s 单请求≤${PER_REQ_TIMEOUT_MS / 1000}s`);
  console.log('[probe] Redis 基线采样 2 次完成（见最终报告）');
  sampleTag = 'load';

  await phase('warmup (num_ctx=4096)', () => chatOnce('warmup', PROMPT_INTRO, 4096));
  await phase('bench-4096 (num_ctx=4096)', () => chatOnce('bench-4096', PROMPT_B2B, 4096));
  await phase('bench-16384 (num_ctx=16384)', () => chatOnce('bench-16384', PROMPT_B2B, 16384));
  await phase(`long-input-16384 (num_ctx=16384, 输入 ${PROMPT_LONG.length} 字符)`, () => chatOnce('long-input-16384', PROMPT_LONG, 16384));
  await phase('concurrent-2x-16384 (2 路并发短 prompt)', async () => {
    const started = performance.now();
    const [a, b] = await Promise.all([
      chatOnce('conc-1', PROMPT_INTRO, 16384, { quiet: true }),
      chatOnce('conc-2', PROMPT_INTRO, 16384, { quiet: true }),
    ]);
    const totalMs = Math.round(performance.now() - started);
    const combined = { label: 'concurrent-2x', ok: a.ok && b.ok, totalMs, a, b };
    console.log(`  -> 并发 2 路总耗时=${(totalMs / 1000).toFixed(1)}s`);
    console.log(`     路1: ${fmtItem(a)}`);
    console.log(`     路2: ${fmtItem(b)}`);
    return combined;
  });
} catch (e) {
  console.error(`\n[probe] 异常: ${(e && e.stack) || e}`);
} finally {
  // 停止采样
  clearInterval(samplerTimer);
  sampleTag = 'post';

  // ---------- 6. ollama ps 确认上下文 ----------
  console.log('\n=== docker exec lanlink-ollama ollama ps（CONTEXT 实际生效值） ===');
  const psOut = ollamaPs();
  console.log(psOut.split('\n').map((l) => '  ' + l).join('\n'));

  // ---------- 7. Redis 稳定性报告 ----------
  const all = redisSamples;
  const okS = all.filter((s) => s.ok);
  const failS = all.filter((s) => !s.ok);
  const baseS = okS.filter((s) => s.tag === 'baseline');
  const loadS = okS.filter((s) => s.tag === 'load');
  const avg = (arr) => (arr.length ? +(arr.reduce((a, s) => a + s.ms, 0) / arr.length).toFixed(1) : NaN);
  const max = (arr) => (arr.length ? +Math.max(...arr.map((s) => s.ms)).toFixed(1) : NaN);
  const loadAvg = avg(loadS), loadMax = max(loadS);

  console.log('\n=== Redis PING 稳定性（含 docker exec 自身开销） ===');
  console.log(`  samples=${all.length} (ok=${okS.length}, fail=${failS.length})`);
  console.log(`  baseline: avg=${avg(baseS)}ms (n=${baseS.length})`);
  console.log(`  探测期间: avg=${loadAvg}ms, max=${loadMax}ms (n=${loadS.length})`);
  if (failS.length) console.log(`  失败样本: ${failS.map((s) => `t+${s.t}ms`).join(', ')}`);
  const verdict = !Number.isFinite(loadMax)
    ? '无有效样本，无法判定（请检查 docker/redis）'
    : loadMax < 200
      ? `max=${loadMax}ms < 200ms → 业务无感知`
      : `max=${loadMax}ms >= 200ms → 超出阈值，需结合基线(${avg(baseS)}ms)判断是否为 docker exec 开销所致`;
  console.log(`  结论: ${verdict}`);

  // ---------- 8. 性能汇总表 ----------
  console.log('\n=== 性能汇总 ===');
  const head = ['项目', 'first-token', 'tokens/s', '总耗时', 'tokens', '备注'];
  const rows = results.map((r) => {
    if (r.skipped) return [r.label, '-', '-', '-', '-', r.error || 'SKIP'];
    if (r.label === 'concurrent-2x') {
      return [r.label, `${r.a.firstTokenMs ?? '?'}/${r.b.firstTokenMs ?? '?'}ms`,
        `${r.a.tps}/${r.b.tps}`, `${(r.totalMs / 1000).toFixed(1)}s`,
        `${r.a.tokens}+${r.b.tokens}`, r.ok ? '2路并行(Promise.all)' : '含截断'];
    }
    return [r.label,
      r.firstTokenMs === null ? 'N/A' : `${r.firstTokenMs}ms`,
      String(r.tps), `${(r.totalMs / 1000).toFixed(1)}s`, String(r.tokens),
      r.ok ? (r.evalCount !== null ? `eval_count=${r.evalCount}` : '') : `截断:${r.error}`];
  });
  const widths = head.map((h, i) => Math.max(h.length, ...rows.map((r) => (r[i] || '').length)));
  const line = (cells) => cells.map((c, i) => String(c).padEnd(widths[i])).join('  |  ');
  console.log('  ' + line(head));
  console.log('  ' + widths.map((w) => '-'.repeat(w)).join('--+--'));
  rows.forEach((r) => console.log('  ' + line(r)));
  console.log(`\n总耗时 ${(Math.round(performance.now() - T0) / 1000).toFixed(1)}s（限额 180s）`);
  process.exit(0); // 探完立即结束进程
}
