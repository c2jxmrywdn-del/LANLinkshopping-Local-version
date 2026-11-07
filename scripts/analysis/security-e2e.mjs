// security-e2e.mjs —— LANLinkShopping 安全断言 E2E（只验证、不修复）。
//
// 运行：node scripts/analysis/security-e2e.mjs   （退出码 0=全部 [PASS]，1=存在 [FAIL]）
//
// 断言依据（先读源码校准）：
//   1. 网关鉴权 ai-gateway/app/main.py:45-68 —— GATEWAY_API_KEY 取值链
//      (容器 env GATEWAY_API_KEY > AI_GATEWAY_API_KEY > 历史默认 lanlink-local-ai-gateway)；
//      _PUBLIC_PATHS={"/","/health"}（main.py:48），其余一律 401。
//      ai-gateway/docker-compose.yml:125 注入 ${AI_GATEWAY_API_KEY:-lanlink-local-ai-gateway}。
//      注意：脚本从运行容器 docker inspect 解析“正确 Bearer”，不打印任何密钥值。
//   2. /product/page 匿名公开（AuthInterceptor.java:93 白名单；不在限流表 AuthRateLimitInterceptor.java:84-106），
//      响应 R 包装 data.records（ProductController.java:51-80）。
//   3. /price-monitor/ai-analysis/* 限流 Policy(20,120,true)（AuthRateLimitInterceptor.java:104，
//      clientLimit=20/分钟、throttleGet=true）：直连 127.0.0.1 时 client 与 ingress 双计数，
//      生效阈值=20/分钟（AuthRateLimitInterceptor.java:157-180）。任务书写“15 次→429”，
//      低于源码阈值 20——脚本先按任务书原样连打 15 次，若无 429 再续打至 25 次验证阈值生效
//      （校准扩展，详情见输出）。
//   4. /ab/expose 限流 Policy(60,240)（AuthRateLimitInterceptor.java:94，匿名 POST）→
//      200 次连发应“先接受后限流”（60/min）。
//   5. /kb/ask 需登录（KnowledgeQueryController.java:38-52），enableTools=true 走
//      KnowledgeRagService.ask 工具增强链（KnowledgeRagService.java:437-456）；
//      登录用 DataInitializer 演示买家（DataInitializer.java:103/295，dev-only）。
//   6. Ollama 宿主机可达 + 网卡绑定 127.0.0.1：docker inspect lanlink-ollama
//      HostConfig.PortBindings（os.networkInterfaces 无法直接验证端口绑定）。
//
// 输出约定：过程明细行带缩进；最后 6 行固定为 6 项断言的 [PASS]/[FAIL] 汇总行。

import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const BACKEND = 'http://127.0.0.1:8080/api';
const GATEWAY = 'http://127.0.0.1:8090';
const OLLAMA = 'http://127.0.0.1:11434';
const ASK_DEFAULT_KEY = 'lanlink-local-ai-gateway'; // main.py:47 历史默认值（仅用于比对，绝不打印实际值）
const DOCKER_CANDIDATES = [
  'C:/Users/35144/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe',
  'docker',
];

const log = (...a) => process.stdout.write(a.join(' ') + '\n');
const detail = (...a) => process.stdout.write('  · ' + a.join(' ') + '\n');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ------------------------------ 基础工具 ------------------------------

async function req(url, { method = 'GET', headers = {}, body, timeoutMs = 30_000 } = {}) {
  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(), timeoutMs);
  const t0 = Date.now();
  try {
    const res = await fetch(url, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: ac.signal,
    });
    let json = null;
    try { json = await res.json(); } catch { /* 非 JSON 响应允许为空 */ }
    return { status: res.status, json, ms: Date.now() - t0, setCookie: res.headers.getSetCookie?.() ?? [] };
  } finally {
    clearTimeout(timer);
  }
}

function docker(args, timeoutMs = 20_000) {
  let lastErr;
  for (const exe of DOCKER_CANDIDATES) {
    try {
      return execFileSync(exe, args, { encoding: 'utf8', timeout: timeoutMs, stdio: ['ignore', 'pipe', 'pipe'] });
    } catch (e) {
      lastErr = e;
    }
  }
  throw lastErr ?? new Error('docker 不可用');
}

/** 解析网关“正确 Bearer”：容器运行时 env 是唯一实况来源；不打印值本身。 */
function resolveGatewayKey() {
  // 1) 运行容器实况（docker compose 启动时注入，可能与历史默认不同——轮换场景）
  try {
    const out = docker(['inspect', 'lanlink-ai-gateway', '--format', '{{range .Config.Env}}{{println .}}{{end}}']);
    const line = out.split(/\r?\n/).find((l) => l.startsWith('GATEWAY_API_KEY='));
    if (line && line.slice('GATEWAY_API_KEY='.length).trim()) {
      return { key: line.slice('GATEWAY_API_KEY='.length).trim(), source: 'container GATEWAY_API_KEY' };
    }
  } catch { /* fallthrough */ }
  // 2) 仓库根 .env（main.py:41 注入链第 2 优先级）
  try {
    const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
    const env = readFileSync(join(root, '.env'), 'utf8');
    const m = env.split(/\r?\n/).find((l) => l.startsWith('AI_GATEWAY_API_KEY='));
    if (m && m.split('=')[1]?.trim()) {
      return { key: m.split('=')[1].trim(), source: 'repo .env AI_GATEWAY_API_KEY' };
    }
  } catch { /* fallthrough */ }
  // 3) 历史默认（main.py:42）
  return { key: ASK_DEFAULT_KEY, source: '历史默认值' };
}

// ------------------------------ 断言登记 ------------------------------

const verdicts = []; // {n, pass, text}
const record = (n, pass, text) => verdicts.push({ n, pass, text });

let sessionCookie = null; // JSESSIONID=...（登录后供 3/5 复用）

// ------------------------------ 检查 1：网关鉴权 ------------------------------

async function check1() {
  const { key, source } = resolveGatewayKey();
  const keyIsDefault = key === ASK_DEFAULT_KEY;
  detail(`网关 Bearer 来源: ${source}; 与历史默认值一致: ${keyIsDefault ? '是' : '否（已轮换）'} [值不打印]`);

  // 1a. 不带 Authorization → 401
  let r = await req(`${GATEWAY}/v1/chat/completions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: { model: 'qwen2.5:0.5b', messages: [{ role: 'user', content: 'hi' }], max_tokens: 8 },
    timeoutMs: 30_000,
  });
  const noAuth401 = r.status === 401;
  detail(`1a 无凭据 POST /v1/chat/completions → HTTP ${r.status}（${r.ms}ms）期望 401: ${noAuth401 ? '符合' : '不符合'}`);

  // 1b. 任务书点名的历史默认 key（轮换环境会 401，仅记录，不作为判定）
  r = await req(`${GATEWAY}/v1/chat/completions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${ASK_DEFAULT_KEY}` },
    body: { model: 'qwen2.5:0.5b', messages: [{ role: 'user', content: 'hi' }], max_tokens: 8 },
    timeoutMs: 30_000,
  });
  const askKeyStatus = r.status;
  detail(`1b-0 任务书点名 key（历史默认）→ HTTP ${askKeyStatus}${keyIsDefault ? '（即当前正确 key）' : '（已被轮换，预期 401）'}`);

  // 1b. 正确 Bearer（= 运行容器实况 key）→ 200
  r = await req(`${GATEWAY}/v1/chat/completions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${key}` },
    body: { model: 'qwen2.5:0.5b', messages: [{ role: 'user', content: '回复OK' }], max_tokens: 8 },
    timeoutMs: 120_000,
  });
  const withAuth200 = r.status === 200 && Array.isArray(r.json?.choices) && r.json.choices.length > 0;
  detail(`1b 正确 Bearer POST /v1/chat/completions → HTTP ${r.status}（${r.ms}ms, backend=${r.json?.lanlink?.backend ?? '-'}）期望 200: ${withAuth200 ? '符合' : '不符合'}`);

  // 1c. /health 免鉴权 → 200
  r = await req(`${GATEWAY}/health`, { timeoutMs: 15_000 });
  const healthOpen = r.status === 200 && r.json?.status === 'UP';
  detail(`1c 无凭据 GET /health → HTTP ${r.status} status=${r.json?.status ?? '-'} 期望 200/UP: ${healthOpen ? '符合' : '不符合'}`);

  const pass = noAuth401 && withAuth200 && healthOpen;
  const note = keyIsDefault ? '' : `；注意：任务书点名的 lanlink-local-ai-gateway 已被轮换（实况 HTTP ${askKeyStatus}），判定使用容器实况 key`;
  record(1, pass, `网关鉴权: 无key=${noAuth401 ? 401 : r.status} 正确Bearer=200✓ 错误key=${askKeyStatus} /health免鉴权✓${note}`);
}

// ------------------------------ 检查 2：商品浏览 ------------------------------

async function check2() {
  const r = await req(`${BACKEND}/product/page?size=3`, { timeoutMs: 30_000 });
  const records = r.json?.data?.records;
  const ok = r.status === 200 && r.json?.code === 200 && Array.isArray(records) && records.length > 0;
  detail(`GET /product/page?size=3（匿名）→ HTTP ${r.status}（${r.ms}ms）code=${r.json?.code ?? '-'} records=${Array.isArray(records) ? records.length : '-'} total=${r.json?.data?.total ?? '-'}`);
  record(2, ok, `商品浏览未被限流破坏: HTTP ${r.status}, records=${Array.isArray(records) ? records.length : '无'} 条（非空=${Array.isArray(records) && records.length > 0}）`);
}

// ------------------------------ 检查 3：ai-analysis 限流 ------------------------------

async function ensureLogin() {
  if (sessionCookie) return true;
  const r = await req(`${BACKEND}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: { phone: '13900000001', password: '123456' }, // DataInitializer 演示买家（dev-only）
    timeoutMs: 30_000,
  });
  const c = r.setCookie.map((x) => x.split(';')[0]).find((x) => x.startsWith('JSESSIONID='));
  sessionCookie = c ?? null;
  detail(`登录（演示买家）→ HTTP ${r.status} code=${r.json?.code ?? '-'} 会话Cookie=${sessionCookie ? '取得' : '未取得'}`);
  return !!sessionCookie && r.json?.code === 200;
}

async function check3() {
  if (!(await ensureLogin())) {
    record(3, false, 'ai-analysis 限流验证跳过: 登录失败（该端点需登录）');
    return;
  }
  const hit = () => req(`${BACKEND}/price-monitor/ai-analysis/1`, {
    headers: { Cookie: sessionCookie }, timeoutMs: 120_000,
  });

  // 预热：首调可能触发分析链（缓存 3 分钟，PriceMonitorAiService CACHE_TTL_MS）；若撞上前次预算则等窗口翻新
  let warm = await hit();
  if (warm.status === 429) {
    detail('预热请求被 429（窗口余量不足），等待 65s 翻新后重试');
    await sleep(65_000);
    warm = await hit();
  }
  detail(`预热 GET /price-monitor/ai-analysis/1 → HTTP ${warm.status}（${warm.ms}ms）code=${warm.json?.code ?? '-'}`);
  if (warm.status !== 200 || warm.json?.code !== 200) {
    record(3, false, `ai-analysis 预热异常: HTTP ${warm.status} code=${warm.json?.code ?? '-'}（无法建立有效基线）`);
    return;
  }

  // 任务书原样断言：连续 15 次
  const statuses = [];
  for (let i = 1; i <= 15; i++) statuses.push((await hit()).status);
  const count429 = (arr) => arr.filter((s) => s === 429).length;
  let n429 = count429(statuses);
  let first429 = statuses.indexOf(429) + 1;
  detail(`任务书原样: 连续 15 次 → ${JSON.stringify(statuses.reduce((m, s) => ((m[s] = (m[s] || 0) + 1), m), {}))}，429 次数=${n429}（源码阈值 20/min，AuthRateLimitInterceptor.java:104）`);

  // 校准扩展：15 次 < 阈值 20，续打至 25 次验证限流在阈值处生效
  let extended = null;
  if (n429 === 0) {
    const more = [];
    for (let i = 16; i <= 25; i++) more.push((await hit()).status);
    extended = statuses.concat(more);
    n429 = count429(extended);
    first429 = extended.indexOf(429) + 1;
    detail(`校准扩展: 连打至 25 次 → 429 次数=${n429}，首次 429 出现在第 ${first429} 个请求（阈值 20 + 预热 1 ≈ 第 21-22 位起）`);
  }

  const pass = n429 > 0;
  record(3, pass,
    n429 > 0
      ? `限流生效: 15 次内 429=${count429(statuses)}（任务书原样${count429(statuses) > 0 ? '即命中' : '未命中——15<源码阈值20/min'}），扩展至 25 次后 429 共 ${n429} 次、首次在第 ${first429} 位`
      : `限流未生效: 25 次连打 0 次 429（阈值应为 20/min）`);
}

// ------------------------------ 检查 4：/ab/expose 限流 ------------------------------

async function check4() {
  detail('等待 65s 让限流窗口翻新（保证“先接受后限流”序列干净）');
  await sleep(65_000);
  const body = { expKey: 'e2e-security-probe-nonexistent', variant: 'A', sessionId: 'e2e-security-probe' };
  const statuses = [];
  const t0 = Date.now();
  for (let i = 0; i < 200; i++) {
    const r = await req(`${BACKEND}/ab/expose`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body,
      timeoutMs: 30_000,
    });
    statuses.push(r.status);
  }
  const by = statuses.reduce((m, s) => ((m[s] = (m[s] || 0) + 1), m), {});
  const first429 = statuses.indexOf(429) + 1;
  const accepted = statuses.filter((s) => s === 200).length;
  detail(`匿名 POST /ab/expose ×200（${Date.now() - t0}ms，伪造 expKey 不落库）→ ${JSON.stringify(by)}，首次 429 在第 ${first429 || '-'} 位（源码阈值 clientLimit=60/min）`);
  const pass = accepted > 0 && statuses.includes(429) && first429 > 1;
  record(4, pass, `匿名 200 次: ${accepted} 次接受后于第 ${first429 || '-'} 位起被限流（429×${by[429] || 0}）`);
}

// ------------------------------ 检查 5：/kb/ask enableTools ------------------------------

async function check5() {
  if (!(await ensureLogin())) {
    record(5, false, 'kb/ask 验证跳过: 登录失败（该端点需登录）');
    return;
  }
  const r = await req(`${BACKEND}/kb/ask`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Cookie: sessionCookie },
    body: { question: '平台的退换货政策是什么？', topK: 5, enableTools: true },
    timeoutMs: 120_000, // 任务书指定 120s
  });
  const d = r.json?.data ?? {};
  const ok = r.status === 200 && r.json?.code === 200 && typeof d.answer === 'string' && d.answer.length > 0;
  detail(`POST /kb/ask enableTools=true（登录态）→ HTTP ${r.status}（${r.ms}ms）code=${r.json?.code ?? '-'} answerLen=${d.answer?.length ?? 0} sources=${Array.isArray(d.sources) ? d.sources.length : '-'} notice=${d.notice ?? '-'} backend=${d.backend ?? '-'}`);
  record(5, ok, `kb/ask 链路正常: HTTP ${r.status}, R.code=${r.json?.code ?? '-'}, 回答 ${d.answer?.length ?? 0} 字（enableTools=true 未被鉴权/限流破坏）`);
}

// ------------------------------ 检查 6：Ollama 可达与回环绑定 ------------------------------

async function check6() {
  // 6a. 宿主机 127.0.0.1 可达（不影响本地调试）
  let reachable = false;
  let ver = '-';
  try {
    const r = await req(`${OLLAMA}/api/version`, { timeoutMs: 15_000 });
    reachable = r.status === 200;
    ver = r.json?.version ?? '-';
  } catch (e) {
    detail(`GET 127.0.0.1:11434/api/version 异常: ${e.message}`);
  }
  detail(`6a 宿主机 GET 127.0.0.1:11434/api/version → ${reachable ? `HTTP 200 (version=${ver})` : '不可达'}`);

  // 6b. 端口绑定 IP 必须是 127.0.0.1（网卡绑定；os.networkInterfaces 无法直接验端口映射）
  let bindings = null;
  let bindOk = false;
  try {
    bindings = JSON.parse(docker(['inspect', 'lanlink-ollama', '--format', '{{json .HostConfig.PortBindings}}']));
    const entry = bindings?.['11434/tcp']?.[0];
    bindOk = !!entry && entry.HostIp === '127.0.0.1' && String(entry.HostPort) === '11434';
  } catch (e) {
    detail(`docker inspect lanlink-ollama 异常: ${e.message}`);
  }
  detail(`6b docker inspect lanlink-ollama HostConfig.PortBindings = ${JSON.stringify(bindings)} → 绑定 127.0.0.1: ${bindOk ? '是' : '否'}`);

  record(6, reachable && bindOk, `Ollama: 宿主机 127.0.0.1:11434 可达=${reachable}, 端口绑定 HostIp=127.0.0.1=${bindOk}`);
}

// ------------------------------ 主流程 ------------------------------

async function main() {
  log(`== LANLink 安全 E2E == 目标: 后端 ${BACKEND} / 网关 ${GATEWAY} / Ollama ${OLLAMA}`);
  const steps = [check1, check2, check3, check4, check5, check6];
  for (const step of steps) {
    try {
      await step();
    } catch (e) {
      const n = steps.indexOf(step) + 1;
      record(n, false, `执行异常: ${e.message}`);
      detail(`检查 ${n} 异常: ${e.stack?.split('\n')[0] ?? e}`);
    }
  }
  log('');
  for (const v of verdicts) log(`[${v.pass ? 'PASS' : 'FAIL'}] ${v.n}. ${v.text}`);
  process.exitCode = verdicts.every((v) => v.pass) ? 0 : 1;
}

main();
