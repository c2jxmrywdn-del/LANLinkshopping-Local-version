import { timingSafeEqual } from "node:crypto";

/**
 * 导入接口鉴权（审计 P1-4）。
 *
 * 背景：`POST /api/import` 原先无任何认证，本机任意进程都能借 `workflow/.env` 里的
 * 服务账号启动商品发布工作流。现在改为 fail-closed：
 *  - 未配置 `WORKFLOW_IMPORT_TOKEN` → 拒绝全部请求（不提供"无配置即放行"的降级路径）；
 *  - 配置后要求 `Authorization: Bearer <token>` 或 `X-Import-Token: <token>`；
 *  - 常量时间比较，避免按字节比较泄漏前缀。
 *
 * 前端（`frontend/src/api/index.js`）通过 `VITE_WORKFLOW_IMPORT_TOKEN` 携带该 token。
 * 生产部署应把 workflow 放在 nginx 后面，由服务端注入该头，避免 token 进入浏览器包。
 */

export const IMPORT_TOKEN_HEADER = "x-import-token";

function safeEqual(a: string, b: string): boolean {
  const bufA = Buffer.from(a, "utf8");
  const bufB = Buffer.from(b, "utf8");
  // timingSafeEqual 要求等长；长度不同直接返回 false（长度本身不是秘密）
  if (bufA.length !== bufB.length) return false;
  return timingSafeEqual(bufA, bufB);
}

function extractToken(headers: Headers): string | null {
  const direct = headers.get(IMPORT_TOKEN_HEADER);
  if (direct && direct.trim()) return direct.trim();

  const authorization = headers.get("authorization");
  if (authorization) {
    const match = /^Bearer\s+(.+)$/i.exec(authorization.trim());
    if (match) return match[1].trim();
  }
  return null;
}

/**
 * 校验导入请求。返回 null 表示通过，否则返回应直接响应的错误。
 */
export function authorizeImport(headers: Headers): { status: number; error: string } | null {
  const expected = process.env.WORKFLOW_IMPORT_TOKEN?.trim() ?? "";
  if (!expected) {
    // fail-closed：未配置 token 时不允许任何调用，避免"忘了配置=完全开放"
    return {
      status: 503,
      error: "导入接口未启用：服务端缺少 WORKFLOW_IMPORT_TOKEN 配置",
    };
  }

  const provided = extractToken(headers);
  if (!provided) {
    return { status: 401, error: "缺少导入凭证（Authorization: Bearer 或 X-Import-Token）" };
  }
  if (!safeEqual(expected, provided)) {
    return { status: 403, error: "导入凭证无效" };
  }
  return null;
}
