const B = 'http://127.0.0.1:8080/api'
async function main() {
  const r = await fetch(B + '/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ phone: '13800000000', password: '123456' }) })
  const cookie = (r.headers.getSetCookie?.() || []).map(c => c.split(';')[0]).join('; ')
  const res = await fetch(B + '/admin/live/rooms/2/review', { method: 'POST', headers: { 'Content-Type': 'application/json', Cookie: cookie }, body: JSON.stringify({ approved: false, reason: 'E2E 驳回' }) })
  const j = await res.json().catch(() => null)
  console.log('HTTP', res.status, JSON.stringify(j))
  const room = await fetch(B + '/live/room/2').then(x => x.json())
  console.log('room2:', JSON.stringify(room?.data && { status: room.data.status, review: room.data.reviewStatus, reason: room.data.rejectReason }))
}
main()
