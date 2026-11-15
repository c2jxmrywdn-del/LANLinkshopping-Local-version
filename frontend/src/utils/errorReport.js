/**
 * 运行时错误上报（P0-7）：window.onerror / unhandledrejection 统一收口。
 *
 * 职责与边界（依 main.js 引用契约定版）：
 * - 去重：同 message+stack 指纹在会话内只上报一次；
 * - 会话上限：单页会话最多上报 N 条，超限静默丢弃，防错误风暴；
 * - traffic events 静默通道：错误事件走 api/index.js 的 trafficApi 静默上报
 *   （接口自身吞错，任何失败都不产生二次异常、不弹任何 UI）。
 *
 * 错误上报是旁路监控：本模块绝不抛错——所有路径包 try/catch，失败即放弃本条。
 */

const SESSION_KEY = 'll_error_report_count'
const MAX_PER_SESSION = 30
// 指纹去重表存内存即可：刷新页面后同错误再现允许重新上报（服务端仍有幂等聚合）
const seenFingerprints = new Set()
let installed = false
let reported = 0

function fingerprint(message, stack) {
  return String(message || '') + '|' + String(stack || '').split('\n').slice(0, 4).join('\n')
}

function sessionCount() {
  try {
    return Number(sessionStorage.getItem(SESSION_KEY) || 0)
  } catch {
    return reported // 隐私模式退化为内存计数
  }
}

function bumpSessionCount() {
  reported += 1
  try {
    sessionStorage.setItem(SESSION_KEY, String(reported))
  } catch {
    // 隐私模式：内存计数已足够
  }
}

/** 静默上报：经 traffic 通道，任何异常吞掉（监控绝不产生二次错误） */
function reportSilently(payload) {
  try {
    // 动态引入避免启动期循环依赖（api/index.js → axios → 组件树）
    import('../api').then(({ trafficApi }) => {
      try {
        trafficApi?.reportError?.({
          message: payload.message,
          stack: payload.stack,
          page_path: typeof location !== 'undefined' ? location.pathname : '',
          userAgent: typeof navigator !== 'undefined' ? navigator.userAgent : '',
        })
      } catch {
        // 上报接口缺失/失败：静默放弃
      }
    }).catch(() => {})
  } catch {
    // 静默放弃
  }
}

function handle(kind, message, stack, extra) {
  try {
    const fp = fingerprint(message, stack)
    if (seenFingerprints.has(fp)) return
    if (sessionCount() >= MAX_PER_SESSION) return
    seenFingerprints.add(fp)
    bumpSessionCount()
    reportSilently({ kind, message: String(message || '').slice(0, 500),
      stack: String(stack || '').slice(0, 2000), ...extra })
  } catch {
    // 绝不抛错
  }
}

/**
 * 安装全局错误收口（app 参数保留：预留 Vue errorHandler 挂载点，当前用不到）。
 * 幂等：重复调用只生效一次（Vite HMR 模块重载安全）。
 */
export function installErrorReport(app) {
  if (installed || typeof window === 'undefined') return
  installed = true

  window.addEventListener('error', (event) => {
    handle('error', event.message, event.error?.stack, {
      filename: event.filename || '', lineno: event.lineno || 0,
    })
  })

  window.addEventListener('unhandledrejection', (event) => {
    const reason = event.reason
    handle('unhandledrejection',
      reason instanceof Error ? reason.message : String(reason ?? ''),
      reason instanceof Error ? reason.stack : '', {})
  })
}

/** 供测试：重置单例状态 */
export function __resetErrorReportForTest() {
  installed = false
  seenFingerprints.clear()
  reported = 0
  try { sessionStorage.removeItem(SESSION_KEY) } catch { /* ignore */ }
}
