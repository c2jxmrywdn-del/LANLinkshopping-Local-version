const B = 'http://127.0.0.1:8080/api'
async function main() {
  const r = await fetch(B + '/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ phone: '13800000000', password: '123456' }) })
  const cookie = (r.headers.getSetCookie?.() || []).map(c => c.split(';')[0]).join('; ')
  const res = await fetch(B + '/admin/live/rooms/2/review', { method: 'POST', headers: { 'Content-Type': 'application/json', Cookie: cookie }, body: JSON.stringify({ approved: false, reason: 'PROBE 驳回' }) })
  const j = await res.json().catch(() => null)
  console.log('HTTP', res.status, 'code', j?.code, 'msg', j?.message)
  console.log('NOW', new Date().toISOString())
}
main()
