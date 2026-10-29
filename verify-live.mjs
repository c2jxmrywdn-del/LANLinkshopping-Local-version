/* 直播商业闭环 E2E v2：清场→创建(待审)→平台批准→开播→观看进出场→点击→归因下单→支付→复盘→驳回治理
   注意：互动端点有 IP 限流（join/leave 1/15s、like 1/2s、click 1/2s），断言用 >= 口径 */
const B = 'http://127.0.0.1:8080/api'
let cookie = ''
let failed = 0
function assert(cond, label, extra) {
  console.log(`[${cond ? 'PASS' : 'FAIL'}] ${label}${extra ? ' | ' + extra : ''}`)
  if (!cond) failed++
}
async function login(phone) {
  const r = await fetch(B + '/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ phone, password: '123456' }) })
  cookie = (r.headers.getSetCookie?.() || []).map(c => c.split(';')[0]).join('; ')
}
async function call(method, path, body) {
  const res = await fetch(B + path, { method, headers: { 'Content-Type': 'application/json', Cookie: cookie }, body: body ? JSON.stringify(body) : undefined })
  let json = null
  try { json = await res.json() } catch (e) {}
  return json
}

async function main() {
  // 0. 管理员清场：强制下线该商户全部未结束房间（含种子与历史中断遗留）
  await login('13800000000')
  const allRooms = await call('GET', '/admin/live/rooms')
  for (const r of (allRooms?.data || [])) {
    if (r.status !== 2) await call('POST', '/admin/live/' + r.roomId + '/force-close')
  }

  // 1. 商户创建直播间（挂自有商品，固定待审核+预告）
  await login('13700000002')
  const create = await call('POST', '/live/merchant/create', {
    title: 'E2E 直播 · 工业品实景评测', coverUrl: '', streamUrl: '', notice: 'E2E 测试直播间',
    products: [{ prodId: 1, livePrice: 3599, liveStock: 50 }, { prodId: 2, livePrice: 369, liveStock: 80 }]
  })
  const roomId = create?.data?.roomId
  assert(create?.code === 200 && roomId, '商户创建直播间(挂自有商品)', `roomId=${roomId}`)
  assert(create?.data?.status === 0 && create?.data?.reviewStatus === 0, '新房间=预告+待平台审核',
    `status=${create?.data?.status} review=${create?.data?.reviewStatus}`)

  const dup = await call('POST', '/live/merchant/create', {
    title: '重复活跃直播间', products: [{ prodId: 2, livePrice: 1 }]
  })
  assert(dup?.code !== 200, '一商户仅一个活跃直播间', `msg=${dup?.message}`)

  const foreign = await call('POST', '/live/merchant/create', {
    title: '越权直播', products: [{ prodId: 99, livePrice: 1 }]
  })
  assert(foreign?.code !== 200, '挂载他人商品被拒')

  // 2. 未审核开播被拒（平台准入门槛）
  const earlyStart = await call('POST', '/live/merchant/' + roomId + '/status', { status: 1 })
  assert(earlyStart?.code !== 200, '未审核开播被拒')

  // 3. 平台审核批准 → 商户开播（走 startIfApproved 条件更新）
  await login('13800000000')
  const approve = await call('POST', '/admin/live/' + roomId + '/review', { approved: true })
  assert(approve?.code === 200, '平台审核批准')
  await login('13700000002')
  const start = await call('POST', '/live/merchant/' + roomId + '/status', { status: 1 })
  assert(start?.code === 200, '审核后开播成功')

  // 4. 观众进出场 + 峰值（IP 限流：第二次 join 被静默吞掉）
  cookie = ''
  const join = await call('POST', '/live/room/' + roomId + '/join')
  assert(join?.code === 200 && Number(join.data) >= 2, '观众进入(在线+1)', 'viewers=' + join?.data)
  await call('POST', '/live/room/' + roomId + '/join') // 同 IP 15s 内第二次 → 静默忽略
  await call('POST', '/live/room/' + roomId + '/leave') // leave 限流窗口与 join 独立 → 允许
  const detail = await call('GET', '/live/room/' + roomId)
  assert(Number(detail?.data?.viewerCount) >= 1, '在线数核算(限流口径)', 'viewers=' + detail?.data?.viewerCount)
  assert(Number(detail?.data?.peakViewerCount) >= Number(detail?.data?.viewerCount), '峰值>=在线', 'peak=' + detail?.data?.peakViewerCount)

  // 5. 商品详情徽章端点
  const byP = await call('GET', '/live/room-by-product/1')
  assert(byP?.code === 200 && byP.data, '商品详情直播中徽章端点', 'roomId=' + byP?.data?.roomId)

  // 6. 讲解商品点击埋点（IP 限流 1/2s，单次点击可过）
  const click = await call('POST', '/live/room/' + roomId + '/product/1/click')
  assert(click?.code === 200 && Number(click?.data?.livePrice) === 3599, '讲解点击埋点(返回直播价)', 'clicks=' + click?.data?.clickCount)

  // 7. 买家归因下单 → 支付（购物车含直播间挂载商品，归因证据链校验应通过）
  await login('13900000001')
  await call('POST', '/cart/add', { prodId: 1, quantity: 1 })
  const cart = await call('GET', '/cart/list')
  const rows = cart?.data?.items || cart?.data || []
  const row = rows.find(i => i.prodId === 1)
  assert(!!row, '购物车含商品1', 'cartId=' + row?.cartId)
  const ord = await call('POST', '/order/checkout', {
    cartIds: [row.cartId], receiver: 'Live E2E', phone: '13900000001', address: 'Live Road 1',
    payType: 'corporate', liveRoomId: roomId, idempotentKey: 'live-e2e-' + Date.now()
  })
  const order = ord?.data
  assert(ord?.code === 200 && order?.orderNo, '归因下单成功', 'orderNo=' + order?.orderNo)
  assert(Number(order?.liveId) === Number(roomId), '订单落 liveId 归因', 'liveId=' + order?.liveId)
  await call('POST', '/payment/create', { orderNo: order.orderNo, channel: 'mock' })
  const confirm = await call('POST', '/payment/mock-confirm?orderNo=' + order.orderNo)
  assert(confirm?.code === 200, 'mock 支付')

  // 8. 商户复盘
  await login('13700000002')
  const stats = await call('GET', '/live/merchant/' + roomId + '/stats')
  assert(stats?.code === 200 && Number(stats.data?.totalOrders) >= 1, '复盘：归因成交单数>=1',
    'orders=' + stats?.data?.totalOrders + ' gmv=' + stats?.data?.totalGmv)
  const p1 = (stats?.data?.products || []).find(x => x.prodId === 1)
  assert(p1 && Number(p1.orders) >= 1 && Number(p1.clickCount) >= 1, '复盘：商品漏斗(点击/成交)',
    'click=' + p1?.clickCount + ' orders=' + p1?.orders)

  // 9. 关播 + 平台驳回预告房间（下线处置）
  const close = await call('POST', '/live/merchant/' + roomId + '/status', { status: 2 })
  assert(close?.code === 200, '商户关播')
  const after = await call('GET', '/live/room/' + roomId)
  assert(after?.data?.status === 2 && after?.data?.endTime, '关播后 status=2 且写 end_time')

  await login('13800000000')
  const reject = await call('POST', '/admin/live/2/review', { approved: false, reason: 'E2E 驳回' })
  assert(reject?.code === 200, '平台驳回预告房间')
  const room2 = await call('GET', '/live/room/2')
  assert(room2?.data?.reviewStatus === 2, '驳回后 review=2', 'review=' + room2?.data?.reviewStatus)

  const adminRooms = await call('GET', '/admin/live/rooms')
  assert(adminRooms?.code === 200 && adminRooms.data.length >= 1, '管理端直播间总览', 'rooms=' + adminRooms?.data?.length)
  const adminStats = await call('GET', '/admin/live/' + roomId + '/stats')
  assert(adminStats?.code === 200, '管理端复盘看板')

  console.log(failed === 0 ? '\n=== LIVE CLOSURE E2E ALL PASSED ===' : '\n=== ' + failed + ' FAILED ===')
  process.exit(failed === 0 ? 0 : 1)
}
main().catch(e => { console.error('crashed:', e.message); process.exit(1) })
