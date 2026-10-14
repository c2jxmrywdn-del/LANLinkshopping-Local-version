import { ref } from 'vue'

const busy = ref(false)
const reason = ref('congestion')
const startedAt = ref(0)
const retryAfter = ref(3)
let hideTimer = null
const listeners = new Set()

function notify() {
  listeners.forEach(fn => fn({ busy: busy.value, reason: reason.value }))
}

export function showBusyLoading(options = {}) {
  clearTimeout(hideTimer)
  reason.value = options.reason || 'congestion'
  retryAfter.value = Number(options.retryAfter || 3)
  startedAt.value = Date.now()
  busy.value = true
  notify()
}

export function hideBusyLoading(delay = 0) {
  clearTimeout(hideTimer)
  if (!delay) {
    busy.value = false
    notify()
    return
  }
  hideTimer = setTimeout(() => {
    busy.value = false
    notify()
  }, delay)
}

export function subscribeBusyLoading(fn) {
  listeners.add(fn)
  return () => listeners.delete(fn)
}

export { busy, reason, startedAt, retryAfter }
