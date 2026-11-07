/**
 * 物流走廊路径割分纯函数（SVG 引擎与高德引擎共用）。
 *
 * 按累计段长比例（近似按节点间大圆距离）求割点，把整条节点链切成
 * 「已走过 / 未走过」两段，并给出割点处的插值头部坐标——供高德双段
 * Polyline 的 setPath 与车标 Marker 的 setPosition 每帧同步（A1/A3）。
 */

/** 两经纬点间大圆距离（km）——与 logisticsGis.haversineDistance 同公式，此处独立成纯函数避免循环依赖 */
function haversineKm(lng1, lat1, lng2, lat2) {
  const R = 6371
  const rad = d => (d * Math.PI) / 180
  const dLat = rad(lat2 - lat1)
  const dLng = rad(lng2 - lng1)
  const a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
    + Math.cos(rad(lat1)) * Math.cos(rad(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2)
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}

/**
 * 按进度百分比对节点链求割点。
 * @param {Array<{lng:number, lat:number, name?:string}>} nodes 经纬度节点链（≥2 个）
 * @param {number} progressPct 回放进度 0~100
 * @returns {{passed: Array<[number,number]>, todo: Array<[number,number]>, head: {lng:number, lat:number, headingAngle:number}, fraction:number}}
 *   passed/todo 均含割点（共享割点保证两段线首尾相接）；head 为割点插值坐标与航向角（度，正北顺时针）；
 *   nodes 不足 2 个时返回 passed/todo 为空、head 取首节点。
 */
export function sliceRouteAtFraction(nodes, progressPct) {
  if (!nodes || nodes.length === 0) {
    return { passed: [], todo: [], head: null, fraction: 0 }
  }
  if (nodes.length === 1) {
    const head = { lng: nodes[0].lng, lat: nodes[0].lat, headingAngle: 0 }
    return { passed: [[nodes[0].lng, nodes[0].lat]], todo: [[nodes[0].lng, nodes[0].lat]], head, fraction: 0 }
  }

  const fraction = Math.max(0, Math.min(1, progressPct / 100))

  // 各段累计长度（大圆距离）与总长
  const segLens = []
  let total = 0
  for (let i = 0; i < nodes.length - 1; i++) {
    const len = haversineKm(nodes[i].lng, nodes[i].lat, nodes[i + 1].lng, nodes[i + 1].lat)
    segLens.push(len)
    total += len
  }

  const target = total * fraction
  // 定位割点所在段：累计长度首次 ≥ target 的段
  let acc = 0
  let segIdx = segLens.length - 1
  for (let i = 0; i < segLens.length; i++) {
    if (acc + segLens[i] >= target) {
      segIdx = i
      break
    }
    acc += segLens[i]
  }
  const segT = segLens[segIdx] > 0 ? (target - acc) / segLens[segIdx] : 0
  const t = Math.max(0, Math.min(1, segT))

  const from = nodes[segIdx]
  const to = nodes[segIdx + 1]
  const head = {
    lng: from.lng + (to.lng - from.lng) * t,
    lat: from.lat + (to.lat - from.lat) * t,
    headingAngle: bearingDeg(from.lng, from.lat, to.lng, to.lat),
  }

  const passed = []
  for (let i = 0; i <= segIdx; i++) passed.push([nodes[i].lng, nodes[i].lat])
  passed.push([head.lng, head.lat])

  const todo = [[head.lng, head.lat]]
  for (let i = segIdx + 1; i < nodes.length; i++) todo.push([nodes[i].lng, nodes[i].lat])

  return { passed, todo, head, fraction }
}

/** 正北顺时针航向角（度）；两点重合时返回 0 */
export function bearingDeg(lng1, lat1, lng2, lat2) {
  const rad = d => (d * Math.PI) / 180
  const deg = r => (r * 180) / Math.PI
  const dLng = rad(lng2 - lng1)
  const y = Math.sin(dLng) * Math.cos(rad(lat2))
  const x = Math.cos(rad(lat1)) * Math.sin(rad(lat2)) - Math.sin(rad(lat1)) * Math.cos(rad(lat2)) * Math.cos(dLng)
  if (Math.abs(y) < 1e-12 && Math.abs(x) < 1e-12) return 0
  return (deg(Math.atan2(y, x)) + 360) % 360
}
