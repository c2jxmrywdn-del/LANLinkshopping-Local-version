/**
 * LANLink 工业物流 GIS 矢量地图算法、空间投影与时空插值工具库
 * 包含国家骨干高速公路走廊路线插值与中国墨卡托投影算法
 */

import {
  CHINA_VIEWBOX,
  PROVINCES_DATA,
  MAJOR_RIVERS,
  TEN_DASH_LINES,
  HIGHWAY_CORRIDORS,
  MAJOR_CITIES,
  projectChinaGeoToSvg
} from './chinaMapData'

export {
  CHINA_VIEWBOX,
  PROVINCES_DATA,
  MAJOR_RIVERS,
  TEN_DASH_LINES,
  HIGHWAY_CORRIDORS,
  MAJOR_CITIES,
  projectChinaGeoToSvg
}

/**
 * 二次贝塞尔曲线坐标计算
 * @param {number} u 局部进度 [0, 1]
 * @param {{x: number, y: number}} p0 起点
 * @param {{x: number, y: number}} p1 控制点
 * @param {{x: number, y: number}} p2 终点
 * @returns {{x: number, y: number}}
 */
export function quadraticBezierPoint(u, p0, p1, p2) {
  const oneMinusU = 1 - u
  const curX = oneMinusU * oneMinusU * p0.x + 2 * oneMinusU * u * p1.x + u * u * p2.x
  const curY = oneMinusU * oneMinusU * p0.y + 2 * oneMinusU * u * p1.y + u * u * p2.y
  return { x: curX, y: curY }
}

/**
 * 二次贝塞尔曲线导数切线向量与航向角计算
 * @param {number} u 局部进度 [0, 1]
 * @param {{x: number, y: number}} p0 起点
 * @param {{x: number, y: number}} p1 控制点
 * @param {{x: number, y: number}} p2 终点
 * @returns {{dx: number, dy: number, angleDeg: number}}
 */
export function quadraticBezierTangent(u, p0, p1, p2) {
  const oneMinusU = 1 - u
  const dx = 2 * oneMinusU * (p1.x - p0.x) + 2 * u * (p2.x - p1.x)
  const dy = 2 * oneMinusU * (p1.y - p0.y) + 2 * u * (p2.y - p1.y)
  // 车头朝向向北 (90° 向上偏移)
  const angleDeg = (Math.atan2(dy, dx) * 180) / Math.PI + 90
  return { dx, dy, angleDeg }
}

/**
 * 多段贝塞尔曲线综合时空插值
 * @param {number} progressPct 总体进度 0 ~ 100
 * @param {Array<{x: number, y: number, name?: string}>} nodes 投影后的路线节点列表
 * @returns {{x: number, y: number, headingAngle: number, locationName: string}}
 */
export function interpolateTruckPosition(progressPct, nodes) {
  if (!nodes || nodes.length < 2) {
    return { x: 0, y: 0, headingAngle: 0, locationName: '筹备中' }
  }

  const frac = Math.max(0, Math.min(1, progressPct / 100))
  const segCount = nodes.length - 1
  const globalT = frac * segCount
  const segIdx = Math.min(segCount - 1, Math.floor(globalT))
  const localU = globalT - segIdx

  const p0 = nodes[segIdx]
  const p2 = nodes[segIdx + 1]
  const mx = (p0.x + p2.x) / 2
  const my = (p0.y + p2.y) / 2
  const p1 = {
    x: mx,
    y: my - Math.abs(p2.x - p0.x) * 0.12
  }

  const pt = quadraticBezierPoint(localU, p0, p1, p2)
  const tan = quadraticBezierTangent(localU, p0, p1, p2)

  let locName = '在途行驶中'
  if (frac <= 0.05) {
    locName = `${p0.name || '起点'} (装车待发)`
  } else if (frac >= 0.95) {
    locName = `${p2.name || '终点'} (末端签收)`
  } else {
    locName = `开往 ${p2.name || '下一枢纽'}`
  }

  return {
    x: pt.x,
    y: pt.y,
    headingAngle: tan.angleDeg,
    locationName: locName
  }
}

/**
 * 球面大圆距离 (Haversine 公式，单位：km)
 */
export function haversineDistance(lat1, lon1, lat2, lon2) {
  const R = 6371
  const dLat = ((lat2 - lat1) * Math.PI) / 180
  const dLon = ((lon2 - lon1) * Math.PI) / 180
  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos((lat1 * Math.PI) / 180) *
      Math.cos((lat2 * Math.PI) / 180) *
      Math.sin(dLon / 2) *
      Math.sin(dLon / 2)
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
  return Math.round(R * c)
}

/**
 * 全国 40+ 核心工业枢纽城市与省会中英文/拼音别名字典
 */
export const CITY_ALIAS_MAP = {
  chongqing: '重庆', cq: '重庆',
  beijing: '北京', bj: '北京', peking: '北京',
  shanghai: '上海', sh: '上海',
  guangzhou: '广州', gz: '广州', canton: '广州',
  shenzhen: '深圳', sz: '深圳',
  chengdu: '成都', cd: '成都',
  wuhan: '武汉', wh: '武汉',
  xian: '西安', xa: '西安', "xi'an": '西安',
  hangzhou: '杭州', hz: '杭州',
  nanjing: '南京', nj: '南京',
  zhengzhou: '郑州', zz: '郑州',
  tianjin: '天津', tj: '天津',
  changsha: '长沙', cs: '长沙',
  suzhou: '苏州', hefei: '合肥', fuzhou: '福州',
  xiamen: '厦门', jinan: '济南', qingdao: '青岛',
  kunming: '昆明', guiyang: '贵阳', nanchang: '南昌',
  shenyang: '沈阳', dalian: '大连', harbin: '哈尔滨',
  changchun: '长春', shijiazhuang: '石家庄', taiyuan: '太原',
  huhehaote: '呼和浩特', hohhot: '呼和浩特', nanning: '南宁',
  haikou: '海口', sanya: '三亚', lanzhou: '兰州',
  xining: '西宁', yinchuan: '银川', urumqi: '乌鲁木齐', lasa: '拉萨', lhasa: '拉萨',
  dongguan: '东莞', foshan: '佛山', ningbo: '宁波', wuxi: '无锡'
}

/**
 * 智能解析地址字符串中的目标城市（支持中文、英文、拼音与省份推导）
 */
export function parseCityFromAddress(addressStr) {
  if (!addressStr) return '北京'
  const raw = String(addressStr).trim()
  const lower = raw.toLowerCase()

  // 1. 优先匹配拼音与英文城市名
  for (const [alias, cnCity] of Object.entries(CITY_ALIAS_MAP)) {
    const regex = new RegExp(`(^|[^a-z])${alias}([^a-z]|$)`, 'i')
    if (regex.test(lower)) {
      return cnCity
    }
  }

  // 2. 中文核心城市名比对
  for (const city of MAJOR_CITIES) {
    if (raw.includes(city.name)) {
      return city.name
    }
  }

  // 3. 省份推断直辖市/省会
  const PROV_CAPITAL_MAP = {
    '重庆': '重庆', '北京': '北京', '上海': '上海', '天津': '天津',
    '广东': '广州', '四川': '成都', '湖北': '武汉', '湖南': '长沙',
    '陕西': '西安', '河南': '郑州', '河北': '石家庄', '山东': '济南',
    '江苏': '南京', '浙江': '杭州', '安徽': '合肥', '福建': '福州',
    '辽宁': '沈阳', '吉林': '长春', '黑龙江': '哈尔滨', '江西': '南昌',
    '山西': '太原', '贵州': '贵阳', '云南': '昆明', '广西': '南宁'
  }
  for (const [prov, cap] of Object.entries(PROV_CAPITAL_MAP)) {
    if (raw.includes(prov)) return cap
  }

  return '北京'
}

/**
 * 根据城市名查找空间坐标与工业枢纽信息（兼容中文与英文/拼音）
 */
export function findCityGeo(name) {
  if (!name) return null
  const clean = String(name).trim()
  const parsed = parseCityFromAddress(clean)
  return (
    MAJOR_CITIES.find(
      (c) => c.name === clean || c.name === parsed || clean.includes(c.name)
    ) || null
  )
}

/**
 * 沿国家骨干高速公路走廊合成真实途经节点
 * 例如：深圳 -> 北京，沿 G4 京港澳高速自动吸附途径广州、长沙、武汉、郑州、石家庄、北京
 */
export function synthesizeHighwayRoute(originName, destName, isReverse = false) {
  const originCity = findCityGeo(originName) || { name: originName || '深圳', lng: 114.05, lat: 22.54 }
  const destCity = findCityGeo(destName) || { name: destName || '北京', lng: 116.40, lat: 39.90 }

  let selectedCorridor = null
  let reverseOrder = false

  const isSouthOrigin = ['深圳', '广州', '东莞', '佛山', '香港'].some(c => originCity.name.includes(c))
  const isNorthDest = ['北京', '天津', '石家庄', '保定'].some(c => destCity.name.includes(c))
  const isNorthOrigin = ['北京', '天津', '石家庄', '保定'].some(c => originCity.name.includes(c))
  const isSouthDest = ['深圳', '广州', '东莞', '佛山', '香港'].some(c => destCity.name.includes(c))

  if (isSouthOrigin && isNorthDest) {
    selectedCorridor = HIGHWAY_CORRIDORS.G4
    reverseOrder = true
  } else if (isNorthOrigin && isSouthDest) {
    selectedCorridor = HIGHWAY_CORRIDORS.G4
    reverseOrder = false
  } else if (['上海', '苏州', '昆山', '南京'].some(c => originCity.name.includes(c)) && ['北京', '天津', '济南'].some(c => destCity.name.includes(c))) {
    selectedCorridor = HIGHWAY_CORRIDORS.G2
    reverseOrder = true
  } else if (['北京', '天津', '济南'].some(c => originCity.name.includes(c)) && ['上海', '苏州', '昆山', '南京'].some(c => destCity.name.includes(c))) {
    selectedCorridor = HIGHWAY_CORRIDORS.G2
    reverseOrder = false
  } else if (['上海', '南京', '武汉'].some(c => originCity.name.includes(c)) && ['重庆', '成都'].some(c => destCity.name.includes(c))) {
    selectedCorridor = HIGHWAY_CORRIDORS.G42
    reverseOrder = false
  } else if (['重庆', '成都'].some(c => originCity.name.includes(c)) && ['上海', '南京', '武汉'].some(c => destCity.name.includes(c))) {
    selectedCorridor = HIGHWAY_CORRIDORS.G42
    reverseOrder = true
  } else if (isSouthOrigin && ['重庆', '成都'].some(c => destCity.name.includes(c))) {
    const pts = [
      { name: '深圳', lng: 114.05, lat: 22.54 },
      { name: '广州', lng: 113.26, lat: 23.12 },
      { name: '韶关', lng: 113.59, lat: 24.81 },
      { name: '怀化', lng: 109.99, lat: 27.56 },
      { name: '黔江', lng: 108.77, lat: 29.53 },
      { name: '重庆', lng: 106.55, lat: 29.56 }
    ]
    if (destCity.name.includes('成都')) {
      pts.push({ name: '成都', lng: 104.06, lat: 30.67 })
    }
    selectedCorridor = {
      name: 'G65 渝湘高速西南走廊',
      waypoints: pts
    }
    reverseOrder = false
  } else if (['重庆', '成都'].some(c => originCity.name.includes(c)) && isSouthDest) {
    const pts = [
      { name: '深圳', lng: 114.05, lat: 22.54 },
      { name: '广州', lng: 113.26, lat: 23.12 },
      { name: '韶关', lng: 113.59, lat: 24.81 },
      { name: '怀化', lng: 109.99, lat: 27.56 },
      { name: '黔江', lng: 108.77, lat: 29.53 },
      { name: '重庆', lng: 106.55, lat: 29.56 }
    ]
    if (originCity.name.includes('成都')) {
      pts.push({ name: '成都', lng: 104.06, lat: 30.67 })
    }
    selectedCorridor = {
      name: 'G65 渝湘高速西南走廊',
      waypoints: pts
    }
    reverseOrder = true
  }

  let nodes = []
  if (selectedCorridor) {
    const list = reverseOrder ? [...selectedCorridor.waypoints].reverse() : selectedCorridor.waypoints
    nodes = list.map(wp => {
      const projPt = projectChinaGeoToSvg(wp.lng, wp.lat)
      let hubType = 'hub'
      if (wp.name === originCity.name) {
        hubType = isReverse ? 'return_origin' : 'warehouse'
      } else if (wp.name === destCity.name) {
        hubType = isReverse ? 'return_dest' : 'client'
      }
      return {
        name: wp.name,
        lng: wp.lng,
        lat: wp.lat,
        x: projPt.x,
        y: projPt.y,
        hubType
      }
    })
  } else {
    const p1 = projectChinaGeoToSvg(originCity.lng, originCity.lat)
    const p2 = projectChinaGeoToSvg(destCity.lng, destCity.lat)
    const midLng = (originCity.lng + destCity.lng) / 2
    const midLat = (originCity.lat + destCity.lat) / 2
    const pMid = projectChinaGeoToSvg(midLng, midLat)
    nodes = [
      { name: originCity.name, lng: originCity.lng, lat: originCity.lat, x: p1.x, y: p1.y, hubType: isReverse ? 'return_origin' : 'warehouse' },
      { name: '中继干线枢纽', lng: midLng, lat: midLat, x: pMid.x, y: pMid.y, hubType: 'transfer' },
      { name: destCity.name, lng: destCity.lng, lat: destCity.lat, x: p2.x, y: p2.y, hubType: isReverse ? 'return_dest' : 'client' }
    ]
  }

  const dist = haversineDistance(originCity.lat, originCity.lng, destCity.lat, destCity.lng)
  const baseCorridorName = selectedCorridor ? selectedCorridor.name : '国家骨干物流走廊'
  return {
    corridorName: isReverse ? `${baseCorridorName} (RMA逆向返厂通道)` : baseCorridorName,
    distanceKm: Math.round(dist * 1.25),
    nodes
  }
}
