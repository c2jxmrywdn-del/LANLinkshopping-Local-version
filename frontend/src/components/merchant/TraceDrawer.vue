<template>
  <a-drawer :open="open" title="物流轨迹" width="420" placement="right" @close="$emit('update:open', false)">
    <template v-if="waybill">
      <div class="wb-head">
        <div class="wb-no">
          <span>运单号</span>
          <b>{{ waybill.waybillNo }}</b>
        </div>
        <a-tag :color="WAYBILL_STATUS[waybill.status]?.color">{{ WAYBILL_STATUS[waybill.status]?.text || waybill.status }}</a-tag>
      </div>
      <a-descriptions :column="1" size="small" class="wb-desc">
        <a-descriptions-item v-if="waybill.logisticsNo" label="快递单号">{{ waybill.logisticsNo }}</a-descriptions-item>
        <a-descriptions-item label="收货人">{{ waybill.receiverName }} {{ maskPhone(waybill.receiverPhone) }}</a-descriptions-item>
        <a-descriptions-item label="收货地址">{{ waybill.receiverAddress }}</a-descriptions-item>
        <a-descriptions-item label="发货时间">{{ fmt(waybill.shipTime) }}</a-descriptions-item>
      </a-descriptions>

      <a-spin :spinning="loading">
        <div v-if="traces.length" class="tl">
          <div v-for="(t, i) in traces" :key="i" class="tl-row" :class="{ latest: i === 0 }">
            <div class="tl-dot"></div>
            <div class="tl-body">
              <p class="tl-text">{{ t.description }}</p>
              <small>{{ t.location ? t.location + ' · ' : '' }}{{ fmt(t.traceTime) }}</small>
            </div>
          </div>
        </div>
        <a-empty v-else :image="aEmpty.PRESENTED_IMAGE_SIMPLE" description="暂无轨迹节点" />
      </a-spin>
    </template>
  </a-drawer>
</template>

<script setup>
import { ref, watch } from 'vue'
import { Empty } from 'ant-design-vue'
import { logisticsApi } from '../../api'
import { WAYBILL_STATUS } from '../../utils/merchantStatus'

const aEmpty = Empty
const props = defineProps({
  open: Boolean,
  waybill: { type: Object, default: null }
})
defineEmits(['update:open'])

const loading = ref(false)
const traces = ref([])

function fmt(t) { return (t || '').replace('T', ' ').slice(0, 16) }
function maskPhone(p) {
  const s = String(p || '')
  return s.length === 11 ? s.slice(0, 3) + '****' + s.slice(7) : s
}

watch(() => props.open, async (v) => {
  if (!v || !props.waybill?.waybillId) return
  loading.value = true
  traces.value = []
  try { traces.value = (await logisticsApi.traces(props.waybill.waybillId)) || [] } catch (e) { /* 拦截器已提示 */ }
  loading.value = false
})
</script>

<style scoped>
.wb-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.wb-no span { display: block; font-size: 11px; color: var(--mc-muted2, #86868b); }
.wb-no b { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 13.5px; color: var(--mc-ink, #1d1d1f); }
.wb-desc { margin-bottom: 18px; }
.tl { display: flex; flex-direction: column; }
.tl-row { position: relative; padding: 0 0 18px 22px; border-left: 2px solid var(--mc-border-soft, #eceae5); margin-left: 5px; }
.tl-row:last-child { border-left-color: transparent; padding-bottom: 2px; }
.tl-dot { position: absolute; left: -6px; top: 2px; width: 10px; height: 10px; border-radius: 50%; background: #c7c7cc; }
.tl-row.latest .tl-dot { background: #9b2d20; box-shadow: 0 0 0 4px rgba(155, 45, 32, .12); }
.tl-text { margin: 0; font-size: 13px; color: var(--mc-text2, #3a3a3c); line-height: 1.5; }
.tl-row.latest .tl-text { color: var(--mc-ink, #1d1d1f); font-weight: 600; }
.tl-body small { color: var(--mc-faint, #a1a1a6); font-size: 11px; }
</style>
