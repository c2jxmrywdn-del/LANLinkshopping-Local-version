/**
 * WGS-84 ↔ GCJ-02 坐标系纯函数转换（eviltransform 算法）+ 地图消费入口。
 *
 * 只转展示链路、不落库（选型报告 §3.3 红线：库内坐标保持原始值）。
 * 高德 JS API 消费 GCJ-02；境外坐标在两个坐标系下等价，一律直通。
 *
 * ★ datum 实测结论（2026-11-06，out/refund-map-design.md 拍板 #9 落地验证）：
 *   后端 GeoCoordinateRegistry 城市字典坐标经高德 JSAPI 2.0 真实链路（AMap.Geocoder）
 *   抽查：北京字典点距高德 GCJ-02 城市中心 20m、上海 13m（WGS-84 假设下该偏移应为
 *   数百米）→ 字典坐标本身即 GCJ-02，源头大概率抓取自高德系数据。
 *   因此 DICT_DATUM_IS_GCJ02 = true：toAmapLngLat 对字典/落库坐标直通，wgs84ToGcj02
 *   不再叠加偏移（二次转换会把点推离约 500m）。若未来接入 GPS 真点序列（WGS-84），
 *   翻此开关即可走全量转换，函数与测试均保留。
 */

/** 字典/落库坐标 datum 开关：true = 已是 GCJ-02，地图消费直通（2026-11-06 高德逆地理抽查实测） */
export const DICT_DATUM_IS_GCJ02 = true

const PI = Math.PI
const AXIS = 6378245.0 // 克拉索夫斯基椭球长半轴
const EE = 0.00669342162296594323 // 第一偏心率的平方

function outOfChina(lng, lat) {
  return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271
}

function transformLat(x, y) {
  let ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
  ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0
  ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0
  ret += (160.0 * Math.sin(y / 12.0 * PI) + 320 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0
  return ret
}

function transformLng(x, y) {
  let ret = 300.0 + x + 2.0 * y + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
  ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0
  ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0
  ret += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0
  return ret
}

/**
 * WGS-84 → GCJ-02（火星坐标）。境内偏移约 100-700m；境外坐标恒等直通。
 * @param {number} lng WGS-84 经度
 * @param {number} lat WGS-84 纬度
 * @returns {[number, number]} [gcjLng, gcjLat]
 */
export function wgs84ToGcj02(lng, lat) {
  if (outOfChina(lng, lat)) return [lng, lat]
  let dLat = transformLat(lng - 105.0, lat - 35.0)
  let dLng = transformLng(lng - 105.0, lat - 35.0)
  const radLat = lat / 180.0 * PI
  let magic = Math.sin(radLat)
  magic = 1 - EE * magic * magic
  const sqrtMagic = Math.sqrt(magic)
  dLat = (dLat * 180.0) / ((AXIS * (1 - EE)) / (magic * sqrtMagic) * PI)
  dLng = (dLng * 180.0) / (AXIS / sqrtMagic * Math.cos(radLat) * PI)
  return [lng + dLng, lat + dLat]
}

/**
 * GCJ-02 → WGS-84（近似逆变换：一轮反向偏移，误差 <1e-6 度，展示用途足够）。
 * @param {number} lng GCJ-02 经度
 * @param {number} lat GCJ-02 纬度
 * @returns {[number, number]} [wgsLng, wgsLat]
 */
export function gcj02ToWgs84(lng, lat) {
  if (outOfChina(lng, lat)) return [lng, lat]
  const [gLng, gLat] = wgs84ToGcj02(lng, lat)
  return [lng * 2 - gLng, lat * 2 - gLat]
}

/**
 * 库内/字典坐标 → 高德地图消费坐标（统一入口）。
 * datum 开关为 true 时直通（实测字典即 GCJ-02），为 false 时按 WGS-84 全量转换。
 * @returns {[number, number]}
 */
export function toAmapLngLat(lng, lat) {
  if (DICT_DATUM_IS_GCJ02) return [lng, lat]
  return wgs84ToGcj02(lng, lat)
}
