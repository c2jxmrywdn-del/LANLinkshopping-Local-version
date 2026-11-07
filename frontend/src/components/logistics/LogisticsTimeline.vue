<template>
  <div class="ll-timeline" :class="{ 'is-loading': loading }">
    <!-- 加载骨架 -->
    <div v-if="loading && !ordered.length" class="tl-skeleton" aria-hidden="true">
      <div v-for="i in 3" :key="i" class="tl-skel-row">
        <span class="tl-skel-dot"></span>
        <span class="tl-skel-bar" :style="{ width: 72 - i * 12 + '%' }"></span>
      </div>
    </div>

    <!-- 空状态 -->
    <div v-else-if="!ordered.length" class="tl-empty">
      <Inbox :size="18" />
      <span>暂无轨迹</span>
    </div>

    <ol v-else class="tl-list">
      <li
        v-for="(n, i) in visibleNodes"
        :key="n.traceId ?? i"
        class="tl-item"
        :class="[{ 'is-latest': i === 0, 'is-abnormal': !!abnormalKind(n) }, 'tone-' + toneOf(n)]"
        @click="emit('node-click', n)"
      >
        <span class="tl-rail" aria-hidden="true"></span>
        <span class="tl-dot" :class="'tone-' + toneOf(n)" aria-hidden="true">
          <component :is="iconOf(n)" :size="13" />
        </span>
        <div class="tl-card">
          <div class="tl-head">
            <span class="tl-status" :class="'tone-' + toneOf(n)">{{ labelOf(n) }}</span>
            <span v-if="i === 0" class="tl-latest-tag">最新</span>
            <span class="tl-time">{{ fmtTime(n.traceTime) }}</span>
          </div>
          <div v-if="n.location || n.city" class="tl-loc">
            <MapPin :size="12" />
            <span>{{ n.location || n.city }}</span>
          </div>
          <p v-if="n.description" class="tl-desc">{{ n.description }}</p>
          <!-- 异常节点：红描边卡片 + 处理建议行（判定口径对齐后端 LogisticsTraceParser 关键词） -->
          <div v-if="abnormalKind(n)" class="tl-advice">
            <LifeBuoy :size="12" class="tl-advice-ico" />
            <span class="tl-advice-label">处理建议</span>
            <span class="tl-advice-text">{{ SUGGESTIONS[abnormalKind(n)] }}</span>
          </div>
          <div v-if="sourceLabel(n)" class="tl-src">{{ sourceLabel(n) }}</div>
        </div>
      </li>
    </ol>

    <button
      v-if="ordered.length > collapsedCount"
      type="button"
      class="tl-toggle"
      @click="expanded = !expanded"
    >
      <component :is="expanded ? ChevronUp : ChevronDown" :size="13" />
      <span>{{ expanded ? '收起' : '展开全部' }}</span>
    </button>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import {
  Inbox, MapPin, LifeBuoy, ChevronDown, ChevronUp,
  PackageOpen, PackageCheck, Truck, Send,
  CheckCircle2, Undo2, SearchX, AlertTriangle
} from 'lucide-vue-next'

const props = defineProps({
  /** GET /logistics/trace/{waybillId} 原始返回：LogisticsTrace[]（后端按 traceTime 升序） */
  traces: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  /** 默认展示的最新节点数，超出折叠 */
  collapsedCount: { type: Number, default: 6 }
})

const emit = defineEmits(['node-click'])

const expanded = ref(false)

/* 最新节点置顶：后端升序返回，前端倒排；同刻按 traceId 大者靠前 */
const ordered = computed(() => {
  return (props.traces || []).filter(Boolean).sort((a, b) => {
    const ta = String(a.traceTime || '')
    const tb = String(b.traceTime || '')
    if (ta !== tb) return ta < tb ? 1 : -1
    return Number(b.traceId || 0) - Number(a.traceId || 0)
  })
})

const visibleNodes = computed(() => {
  if (expanded.value || ordered.value.length <= props.collapsedCount) return ordered.value
  return ordered.value.slice(0, props.collapsedCount)
})

/* 状态分色（口径对齐平台 9 态与 LogisticsTraceParser 状态词表） */
const STATUS_META = {
  pending_ship: { label: '待发货', icon: PackageOpen, tone: 'info' },
  picked_up: { label: '已揽收', icon: PackageCheck, tone: 'info' },
  in_transit: { label: '干线运输', icon: Truck, tone: 'run' },
  out_for_delivery: { label: '末端派送', icon: Send, tone: 'run' },
  delivered: { label: '已签收', icon: CheckCircle2, tone: 'ok' },
  returned: { label: '已退回', icon: Undo2, tone: 'warn' },
  lost: { label: '疑似丢失', icon: SearchX, tone: 'bad' },
  exception: { label: '运输异常', icon: AlertTriangle, tone: 'bad' }
}
const ABNORMAL_STATUSES = new Set(['exception', 'returned', 'lost'])

function labelOf(n) {
  const meta = STATUS_META[String(n.status || '')]
  return meta ? meta.label : String(n.status || '')
}
function iconOf(n) {
  const meta = STATUS_META[String(n.status || '')]
  return (meta && meta.icon) || MapPin
}
function toneOf(n) {
  if (abnormalKind(n)) return 'bad'
  const meta = STATUS_META[String(n.status || '')]
  return (meta && meta.tone) || 'info'
}

/* 异常种类判定（关键词与后端 LogisticsTraceParser.parse 保持一致） */
function abnormalKind(n) {
  const st = String(n.status || '')
  const d = String(n.description || '')
  if (st === 'lost' || /丢失|丢件|lost/i.test(d)) return 'lost'
  if (st === 'returned' || /退回|退件|returned/i.test(d)) return 'returned'
  if (/拒收|拒签|refused/i.test(d)) return 'reject'
  if (/破损|损坏|damage/i.test(d)) return 'damage'
  if (/滞留|超时|异常停留|delay/i.test(d)) return 'delay'
  if (Number(n.isAbnormal) === 1 || ABNORMAL_STATUSES.has(st)) return 'generic'
  return ''
}

/* 异常处理建议（按种类给可行动动作，而非泛化提示） */
const SUGGESTIONS = {
  lost: '建议立即联系承运商核实，并启动丢件理赔流程',
  returned: '建议确认退回原因，并与买家协商二次发货',
  reject: '建议联系买家确认拒收原因，安排重新派送或退款',
  damage: '建议拍照留证，并提交售后与理赔申请',
  delay: '建议联系承运商催件，并持续关注后续节点',
  generic: '建议联系承运商核实运单状态，必要时转售后处理'
}

function sourceLabel(n) {
  if (n.source === 'api') return '接口同步'
  if (n.source === 'manual') return '人工录入'
  return ''
}

function fmtTime(v) {
  if (!v) return ''
  return String(v).replace('T', ' ').slice(0, 16)
}
</script>

<style scoped>
.ll-timeline {
  width: 100%;
  color: var(--ll-ink, #0f172a);
}

/* 骨架 */
.tl-skeleton { display: flex; flex-direction: column; gap: 14px; padding: 6px 0; }
.tl-skel-row { display: flex; align-items: center; gap: 12px; }
.tl-skel-dot {
  width: 26px; height: 26px; border-radius: 50%;
  background: linear-gradient(90deg, #eef2f7, #f8fafc, #eef2f7);
  animation: tlPulse 1.4s ease-in-out infinite;
}
.tl-skel-bar {
  height: 54px; border-radius: 12px;
  background: linear-gradient(90deg, #eef2f7, #f8fafc, #eef2f7);
  animation: tlPulse 1.4s ease-in-out infinite;
}
@keyframes tlPulse { 0%, 100% { opacity: .65; } 50% { opacity: 1; } }

/* 空状态 */
.tl-empty {
  display: flex; align-items: center; justify-content: center; gap: 8px;
  padding: 28px 12px;
  color: var(--ll-muted, #667085);
  font-size: 13px;
  background: var(--ll-surface-soft, #f8fafc);
  border: 1px dashed var(--ll-border, #e2e8f0);
  border-radius: 12px;
}

/* 轨道 */
.tl-list { list-style: none; margin: 0; padding: 0; }
.tl-item {
  position: relative;
  display: grid;
  grid-template-columns: 26px 1fr;
  column-gap: 10px;
  padding-bottom: 12px;
  cursor: pointer;
}
.tl-item:last-child { padding-bottom: 0; }
.tl-rail {
  position: absolute;
  left: 12px; top: 26px; bottom: -2px;
  width: 2px;
  background: var(--ll-border, #e2e8f0);
}
.tl-item:last-child .tl-rail { display: none; }

/* 状态分色节点圆点 */
.tl-dot {
  width: 26px; height: 26px;
  border-radius: 50%;
  display: inline-flex; align-items: center; justify-content: center;
  color: #fff;
  z-index: 1;
  transition: box-shadow .2s ease;
}
.tl-dot.tone-info { background: #0ea5e9; }
.tl-dot.tone-run { background: #0284c7; }
.tl-dot.tone-ok { background: #10b981; }
.tl-dot.tone-warn { background: #f59e0b; }
.tl-dot.tone-bad { background: #ef4444; }

/* 节点卡片 */
.tl-card {
  background: var(--ll-card, #fff);
  border: 1px solid var(--ll-border, #eef2f7);
  border-radius: 12px;
  padding: 10px 12px;
  transition: border-color .2s ease, box-shadow .2s ease, background .2s ease;
}
.tl-item:hover .tl-card { border-color: rgba(2, 132, 199, .4); }
.tl-item.is-latest .tl-card {
  border-color: rgba(2, 132, 199, .45);
  background: linear-gradient(180deg, #f0f9ff 0%, #ffffff 90%);
  box-shadow: 0 4px 14px rgba(2, 132, 199, .10);
}
.tl-item.is-latest .tl-dot { box-shadow: 0 0 0 4px rgba(2, 132, 199, .16); }
/* 异常节点红描边 */
.tl-item.is-abnormal .tl-card {
  border: 1.5px solid rgba(239, 68, 68, .55);
  background: rgba(254, 242, 242, .45);
}
.tl-item.is-abnormal .tl-dot { box-shadow: 0 0 0 4px rgba(239, 68, 68, .14); }

.tl-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.tl-status {
  font-size: 11.5px; font-weight: 700;
  padding: 2px 8px; border-radius: 999px;
  white-space: nowrap;
}
.tl-status.tone-info { background: rgba(14, 165, 233, .12); color: #0369a1; }
.tl-status.tone-run { background: rgba(2, 132, 199, .12); color: #075985; }
.tl-status.tone-ok { background: rgba(16, 185, 129, .12); color: #047857; }
.tl-status.tone-warn { background: rgba(245, 158, 11, .14); color: #b45309; }
.tl-status.tone-bad { background: rgba(239, 68, 68, .12); color: #b91c1c; }
.tl-latest-tag {
  font-size: 10.5px; font-weight: 800;
  color: #0284c7;
  background: rgba(2, 132, 199, .12);
  border: 1px solid rgba(2, 132, 199, .35);
  padding: 1px 7px; border-radius: 999px;
  letter-spacing: .04em;
}
.tl-item.is-abnormal .tl-latest-tag {
  color: #b91c1c;
  background: rgba(239, 68, 68, .10);
  border-color: rgba(239, 68, 68, .4);
}
.tl-time { margin-left: auto; font-size: 11.5px; color: var(--ll-muted, #94a3b8); font-variant-numeric: tabular-nums; white-space: nowrap; }

.tl-loc {
  display: flex; align-items: center; gap: 4px;
  margin-top: 6px;
  font-size: 12px; color: var(--ll-muted, #64748b);
}
.tl-loc span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tl-desc {
  margin: 6px 0 0;
  font-size: 13px; line-height: 1.6;
  color: var(--ll-ink, #1e293b);
}
.tl-item.is-latest .tl-desc { font-weight: 600; }

/* 处理建议 */
.tl-advice {
  display: flex; align-items: flex-start; gap: 6px;
  margin-top: 8px;
  padding: 7px 9px;
  border-radius: 8px;
  background: rgba(239, 68, 68, .08);
  border-left: 3px solid #ef4444;
  font-size: 12px;
  line-height: 1.5;
}
.tl-advice-ico { flex: none; margin-top: 1px; color: #ef4444; }
.tl-advice-label { flex: none; font-weight: 800; color: #b91c1c; }
.tl-advice-text { color: #7f1d1d; }

.tl-src { margin-top: 6px; font-size: 10.5px; color: var(--ll-muted, #94a3b8); }

/* 折叠开关 */
.tl-toggle {
  margin-top: 10px;
  display: inline-flex; align-items: center; gap: 4px;
  padding: 4px 12px;
  border-radius: 999px;
  border: 1px solid var(--ll-border, #e2e8f0);
  background: var(--ll-card, #fff);
  color: #0284c7;
  font-size: 12px; font-weight: 600;
  cursor: pointer;
  transition: background .2s ease, border-color .2s ease;
}
.tl-toggle:hover { background: #f0f9ff; border-color: rgba(2, 132, 199, .45); }
</style>
