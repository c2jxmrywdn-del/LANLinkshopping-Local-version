// Keep this policy aligned with backend WebConfig.CsrfInterceptor mappings.
const CSRF_PROTECTED_PATHS = [
  /^\/user(?:\/|$)/,
  /^\/admin\/marketing-email(?:\/|$)/,
  /^\/admin\/customer-communications(?:\/|$)/
]

export function isCsrfProtectedMutation(config = {}) {
  const method = String(config.method || 'get').toLowerCase()
  if (['get', 'head', 'options'].includes(method)) return false

  // Axios normally provides relative endpoint paths; strip a query string defensively.
  const url = String(config.url || '').split('?')[0]
  return CSRF_PROTECTED_PATHS.some((pattern) => pattern.test(url))
}
