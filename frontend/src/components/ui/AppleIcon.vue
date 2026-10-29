<template>
  <!-- 如果传入的是 Vue 组件（对象或函数） -->
  <component
    :is="resolvedComponent"
    v-if="resolvedComponent"
    class="apple-vector-icon"
    :size="size"
    :stroke-width="strokeWidth"
    :aria-hidden="label ? undefined : 'true'"
    :aria-label="label || undefined"
    :role="label ? 'img' : undefined"
  />

  <!-- 如果没有匹配到预设组件，或者默认回退，渲染 Apple 标准 Squircle App Grid 矢量图标 (严禁文字或 Emoji 回退) -->
  <svg
    v-else
    viewBox="0 0 24 24"
    class="apple-vector-icon"
    :width="size"
    :height="size"
    fill="none"
    stroke="currentColor"
    :stroke-width="strokeWidth"
    stroke-linecap="round"
    stroke-linejoin="round"
    :aria-hidden="label ? undefined : 'true'"
    :aria-label="label || undefined"
    :role="label ? 'img' : undefined"
  >
    <rect width="7" height="7" x="3" y="3" rx="1.5" />
    <rect width="7" height="7" x="14" y="3" rx="1.5" />
    <rect width="7" height="7" x="14" y="14" rx="1.5" />
    <rect width="7" height="7" x="3" y="14" rx="1.5" />
  </svg>
</template>

<script setup>
/**
 * AppleIcon.vue — 21st.dev 标准 Apple UI 矢量图标渲染器
 * 
 * 核心规范:
 * 1. 100% 纯矢量 SVG 渲染 (基于 Lucide / Tabler / Apple HIG 标准几何路径)
 * 2. 彻底杜绝 Emoji
 * 3. 彻底杜绝使用文字、字母或拼音首字母作为图标替代物 (严禁 (item.label || 'A').slice(0, 1))
 * 4. 支持直接传入 Lucide 组件、markRaw(Comp) 或 21st.dev 预设图标名字符串
 */
import { computed } from 'vue'
import {
  Sparkles,
  TicketPercent,
  Ticket,
  Zap,
  Flame,
  Layers,
  Store,
  Building2,
  ShoppingCart,
  Package,
  Truck,
  CreditCard,
  Wallet,
  ArrowUp,
  Gift,
  Bell,
  Search,
  ShieldCheck,
  ShieldAlert,
  Lock,
  FileText,
  Volume2,
  Square,
  Image,
  FolderUp,
  LayoutGrid,
  CheckCircle2,
  ChevronRight,
  Clock,
  Compass,
  FileSpreadsheet,
  Rocket,
  MessageCircle,
  Phone,
  Mail,
  Timer,
  AlertTriangle,
  HelpCircle,
  Check,
  X,
  Star,
  Gem,
  CircleDot,
  ArrowUpRight,
  RotateCcw,
  Diamond,
  Ban,
  SearchX,
  RefreshCw,
  ChevronDown,
  LineChart
} from 'lucide-vue-next'

const props = defineProps({
  /** 图标组件对象、函数或预设名称字符串 (如 'sparkles', 'cart', 'credit') */
  name: {
    type: [Object, Function, String],
    default: null
  },
  /** 尺寸 (px) */
  size: {
    type: [Number, String],
    default: 20
  },
  /** 描边粗细 */
  strokeWidth: {
    type: [Number, String],
    default: 1.75
  },
  /** 可访问名称：传值时作为 role="img" 的形象图标，否则对辅助技术隐藏（装饰图标） */
  label: {
    type: String,
    default: ''
  }
})

// 预设名称与 21st.dev Lucide 矢量图标映射表
const ICON_MAP = {
  // 活动 / 发现 / 大促
  sparkles: Sparkles,
  activity: Sparkles,
  all: Sparkles,
  compass: Compass,
  target: Sparkles,
  
  // 领券 / 票据
  ticket: Ticket,
  coupon: TicketPercent,
  ticketpercent: TicketPercent,
  
  // 秒杀 / 闪电 / 热门
  zap: Zap,
  lightning: Zap,
  promo: Zap,
  flash: Zap,
  flame: Flame,
  hot: Flame,
  
  // 画廊 / 空间卡片
  layers: Layers,
  carousel: Layers,
  gallery: Layers,
  
  // 商城 / 企业采购
  store: Store,
  mall: Store,
  building: Building2,
  
  // 购物车
  cart: ShoppingCart,
  shoppingcart: ShoppingCart,
  
  // 订单 / 物流
  order: Package,
  orders: Package,
  package: Package,
  truck: Truck,
  logistics: Truck,
  
  // 资产 / 账期 / 支付
  credit: CreditCard,
  creditcard: CreditCard,
  wallet: Wallet,
  
  // 激励 / 礼物
  gift: Gift,
  reward: Gift,
  
  // 导航
  top: ArrowUp,
  arrowup: ArrowUp,
  chevronright: ChevronRight,
  chevrondown: ChevronDown,
  grid: LayoutGrid,
  appgrid: LayoutGrid,
  
  // 状态与辅助
  bell: Bell,
  search: Search,
  shield: ShieldCheck,
  security: ShieldCheck,
  shieldalert: ShieldAlert,
  lock: Lock,
  file: FileText,
  spreadsheet: FileSpreadsheet,
  volume: Volume2,
  square: Square,
  image: Image,
  upload: FolderUp,
  rocket: Rocket,
  check: CheckCircle2,
  clock: Clock,
  chart: LineChart,
  trend: LineChart,

  // 沟通 / 联系
  message: MessageCircle,
  chat: MessageCircle,
  messagecircle: MessageCircle,
  phone: Phone,
  mail: Mail,
  email: Mail,
  timer: Timer,

  // 提示 / 校验
  warning: AlertTriangle,
  alert: AlertTriangle,
  help: HelpCircle,
  question: HelpCircle,
  confirm: Check,
  success: Check,
  close: X,
  error: X,
  ban: Ban,
  empty: SearchX,

  // 徽章 / 装饰
  star: Star,
  gem: Gem,
  diamond: Diamond,
  dot: CircleDot,
  circledot: CircleDot,
  target2: CircleDot,

  // 方向
  arrowupright: ArrowUpRight,
  up: ArrowUpRight,
  reset: RotateCcw,
  refund: RotateCcw,
  refresh: RefreshCw
}

const resolvedComponent = computed(() => {
  if (!props.name) return null
  
  // 如果直接传入了 Vue 组件 (对象或函数)
  if (typeof props.name === 'object' || typeof props.name === 'function') {
    return props.name
  }
  
  // 如果是字符串名，做标准化小写匹配
  if (typeof props.name === 'string') {
    const key = props.name.trim().toLowerCase().replace(/[-_]/g, '')
    return ICON_MAP[key] || null
  }
  
  return null
})
</script>

<style scoped>
.apple-vector-icon {
  display: inline-block;
  vertical-align: middle;
  flex-shrink: 0;
  stroke-linecap: round;
  stroke-linejoin: round;
}
</style>
