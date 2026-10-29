import { readFileSync, writeFileSync } from 'node:fs'
const f = 'verify-live.mjs'
let s = readFileSync(f, 'utf8')
let n = 0
// 1) 创建断言改为"待审核预告"
s = s.replace("assert(create?.code === 200 && roomId, '商户开播(挂自有商品)', `roomId=${roomId}`)",
  "assert(create?.code === 200 && roomId, '商户创建直播间(挂自有商品,待审核)', `roomId=${roomId}`)\n  assert(create?.data?.status === 0 && create?.data?.reviewStatus === 0, '新房间=预告+待平台审核', `status=${create?.data?.status} review=${create?.data?.reviewStatus}`)")
// 2) 未审核开播被拒
s = s.replace("  // 2. 观众进出场 + 峰值\n  cookie = ''",
  `  // 1b. 未审核开播被拒（平台准入门槛）
  const earlyStart = await call('POST', \`/live/merchant/\${roomId}/status\`, { status: 1 })
  assert(earlyStart?.code !== 200, '未审核开播被拒(准入门槛)', \`msg=\${earlyStart?.message}\`)

  // 1c. 平台审核批准
  await login('13800000000')
  const approve = await call('POST', \`/admin/live/rooms/\${roomId}/review\`, { approved: true })
  assert(approve?.code === 200, '平台审核批准', \`msg=\${approve?.message}\`)

  // 1d. 商户开播（status→1）
  await login('13700000002')
  const start = await call('POST', \`/live/merchant/\${roomId}/status\`, { status: 1 })
  assert(start?.code === 200, '审核后开播成功', \`msg=\${start?.message}\`)

  // 2. 观众进出场 + 峰值
  cookie = ''`)
// 3) 关播断言后追加管理端下架验证（用预告房间 2）
s = s.replace("  await login('13800000000')\n  const adminRooms = await call('GET', '/admin/live/rooms')",
  `  // 7b. 平台驳回预告房间 2 → 下线处置
  await login('13800000000')
  const reject = await call('POST', '/admin/live/rooms/2/review', { approved: false, reason: 'E2E 驳回' })
  assert(reject?.code === 200, '平台驳回预告房间')
  const room2 = await call('GET', '/live/room/2')
  assert(room2?.data?.reviewStatus === 2 && room2?.data?.status === 2, '驳回后 review=2 且下线', `review=${room2?.data?.reviewStatus} status=${room2?.data?.status}`)

  const adminRooms = await call('GET', '/admin/live/rooms')`)
writeFileSync(f, s)
console.log('patched', n || 'inline', '| has review steps:', s.includes('平台审核批准'), s.includes('平台驳回预告房间'))
