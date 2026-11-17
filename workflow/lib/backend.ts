import type { ImportItem } from "./types";

const API_BASE = process.env.BACKEND_API_BASE ?? "http://localhost:8080/api";
const SVC_PHONE = process.env.WORKFLOW_BACKEND_PHONE ?? "";
const SVC_PASSWORD = process.env.WORKFLOW_BACKEND_PASSWORD ?? "";

/** 服务账号密码最小长度：弱口令会在启动首个导入任务时告警（审计 P1-4） */
const MIN_SERVICE_PASSWORD_LENGTH = 12;

let weakPasswordWarned = false;

export interface BackendAuth {
  cookie: string;
  csrf: string;
}

/**
 * 用服务账号登录 Spring Boot 后端，拿到会话 Cookie 与 CSRF 令牌。
 * 说明：Cookie/令牌会进入工作流事件日志（可恢复所需），仅建议用于演示服务账号，
 * 生产环境应改用一次性、低权限、可轮换的服务令牌。
 */
export async function loginToBackend(): Promise<BackendAuth> {
  "use step";

  if (!SVC_PHONE || !SVC_PASSWORD) {
    throw new Error("未配置 WORKFLOW_BACKEND_PHONE / WORKFLOW_BACKEND_PASSWORD");
  }
  // 审计 P1-4：历史上该账号是 6 位纯数字弱口令。入口已由 WORKFLOW_IMPORT_TOKEN 收紧，
  // 这里再做一次弱口令告警，避免"凭据泄露即全量发布"在无人察觉的情况下长期存在。
  if (!weakPasswordWarned && SVC_PASSWORD.length < MIN_SERVICE_PASSWORD_LENGTH) {
    weakPasswordWarned = true;
    console.warn(
      `[workflow] WORKFLOW_BACKEND_PASSWORD 长度 ${SVC_PASSWORD.length} < ${MIN_SERVICE_PASSWORD_LENGTH}，` +
        "属于弱口令；请改用随机强密码或专用低权限服务令牌（审计 P1-4）",
    );
  }

  const csrfRes = await fetch(`${API_BASE}/user/csrf-token`, { credentials: "include" });
  const csrfJson = (await csrfRes.json().catch(() => ({}))) as {
    data?: { token?: string }; token?: string;
  };
  const csrf = csrfJson?.data?.token ?? csrfJson?.token ?? "";

  const loginRes = await fetch(`${API_BASE}/auth/login`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(csrf ? { "X-CSRF-TOKEN": csrf } : {}),
    },
    body: JSON.stringify({ phone: SVC_PHONE, password: SVC_PASSWORD }),
  });
  const setCookies =
    typeof (loginRes.headers as unknown as { getSetCookie?: () => string[] }).getSetCookie === "function"
      ? (loginRes.headers as unknown as { getSetCookie: () => string[] }).getSetCookie()
      : [loginRes.headers.get("set-cookie")].filter(Boolean) as string[];
  const cookie = setCookies.map((c) => c.split(";")[0]).join("; ");

  const body = (await loginRes.json().catch(() => ({}))) as { code?: number; message?: string };
  if (body.code !== 200 || !cookie) {
    // 登录失败视为可重试（可能是后端瞬时不可用）
    throw new Error(`后端登录失败: ${body.message ?? loginRes.status}`);
  }
  return { cookie, csrf };
}

export interface CreateOutcome {
  ok: boolean;
  prodId?: number;
  error?: string;
}

/**
 * 单条商品发布：POST /api/product/publish。
 * 5xx / 网络错误抛出以触发重试；业务失败（如商户未过审）返回 ok:false 不重试。
 */
export async function createProduct(auth: BackendAuth, item: ImportItem): Promise<CreateOutcome> {
  "use step";

  const res = await fetch(`${API_BASE}/product/publish`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Cookie: auth.cookie,
      ...(auth.csrf ? { "X-CSRF-TOKEN": auth.csrf } : {}),
    },
    body: JSON.stringify({
      title: item.title,
      brand: item.brand ?? null,
      spec: item.spec ?? null,
      price: item.price,
      indId: item.indId,
      catId: item.catId,
      coverUrl: item.coverUrl,
      stock: item.stock,
      detail: item.detail ?? null,
    }),
  });

  if (res.status >= 500) {
    throw new Error(`后端 ${res.status}`); // 触发 step 重试
  }

  const body = (await res.json().catch(() => ({}))) as {
    code?: number; message?: string; data?: { prodId?: number };
  };
  if (body.code === 200) {
    return { ok: true, prodId: body.data?.prodId };
  }
  return { ok: false, error: body.message ?? `HTTP ${res.status}` };
}

createProduct.maxRetries = 3;
