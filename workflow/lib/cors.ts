// 允许跨域调用（Vue 前端 → Next.js 服务）。生产环境用 ALLOWED_ORIGIN 收紧。
// ALLOWED_ORIGIN 可为 "*"，或逗号分隔的来源白名单；命中则原样回显该来源（无凭据模式）。
export function corsHeaders(reqOrigin?: string | null): Record<string, string> {
  const raw = process.env.ALLOWED_ORIGIN ?? "*";
  const allowlist = raw.split(",").map((s) => s.trim()).filter(Boolean);
  let allowOrigin = "*";
  if (allowlist.length === 1 && allowlist[0] === "*") {
    allowOrigin = "*";
  } else if (reqOrigin && allowlist.includes(reqOrigin)) {
    allowOrigin = reqOrigin;
  } else if (reqOrigin && allowlist.length) {
    // 不在白名单：仍返回首个允许来源，浏览器会据此拦截（保持保守默认）
    allowOrigin = allowlist[0];
  }
  return {
    "Access-Control-Allow-Origin": allowOrigin,
    "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type",
    "Vary": "Origin",
  };
}
