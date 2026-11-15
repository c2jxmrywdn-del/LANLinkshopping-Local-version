// 收件地址省份前缀提取（与后端 LogisticsFreightCalculator.extractProvince 同口径的简化版）。
// ShipModal 用它从订单收件地址回填 recommend 请求的 receiverProvince（此前恒传 ''）。
export const PROVINCE_PREFIXES = [
  '内蒙古', '广西', '西藏', '宁夏', '新疆', '香港', '澳门',
  '北京', '天津', '上海', '重庆', '河北', '山西', '辽宁', '吉林', '黑龙江',
  '江苏', '浙江', '安徽', '福建', '江西', '山东', '河南', '湖北', '湖南',
  '广东', '海南', '四川', '贵州', '云南', '陕西', '甘肃', '青海', '台湾'
]

export function provinceFromAddress(addr) {
  if (!addr) return ''
  for (const p of PROVINCE_PREFIXES) {
    if (addr.startsWith(p) || addr.includes(p)) return p
  }
  return ''
}
