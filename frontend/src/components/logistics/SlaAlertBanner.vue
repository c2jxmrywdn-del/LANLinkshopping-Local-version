<template>
  <div v-if="alerts && alerts.length" class="sla-alert-banner">
    <div class="banner-left">
      <span class="pulse-icon-wrap">
        <AlertTriangle :size="15" class="text-amber-600 animate-bounce" aria-hidden="true" />
      </span>
      <div class="banner-content">
        <span class="banner-badge">在途停滞预警 ({{ alerts.length }})</span>
        <span class="banner-marquee" :title="currentAlert?.content">
          {{ currentAlert?.content || '有在途运单超时未更新轨迹，建议立即跟进' }}
        </span>
      </div>
    </div>
    <div class="banner-right">
      <a-button
        type="primary"
        size="small"
        class="expedite-btn"
        :loading="expediting"
        @click="handleExpedite"
      >
        <Flame :size="12" class="inline mr-1" />一键加急催派
      </a-button>
      <a-button type="link" size="small" class="detail-link" @click="emit('openDashboard')">
        控制塔大屏 <ArrowRight :size="12" class="inline ml-0.5" />
      </a-button>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, onMounted, onBeforeUnmount } from 'vue'
import { message } from 'ant-design-vue'
import { AlertTriangle, Flame, ArrowRight } from 'lucide-vue-next'
import { logisticsApi } from '../../api'

const props = defineProps({
  alerts: {
    type: Array,
    default: () => []
  }
})

const emit = defineEmits(['openDashboard', 'refresh'])

const activeIdx = ref(0)
const expediting = ref(false)
let timer = 0

const currentAlert = computed(() => {
  if (!props.alerts || !props.alerts.length) return null
  return props.alerts[activeIdx.value % props.alerts.length]
})

function startMarquee() {
  stopMarquee()
  if (props.alerts && props.alerts.length > 1) {
    timer = setInterval(() => {
      activeIdx.value = (activeIdx.value + 1) % props.alerts.length
    }, 4500)
  }
}

function stopMarquee() {
  if (timer) {
    clearInterval(timer)
    timer = 0
  }
}

async function handleExpedite() {
  if (!currentAlert.value?.waybillId) return
  expediting.value = true
  try {
    await logisticsApi.expediteWaybill(currentAlert.value.waybillId, '商户大屏一键加急催派')
    message.success('已向承运商调度中心下发加急督办指令')
    emit('refresh')
  } catch (e) {
    // 拦截器处理
  } finally {
    expediting.value = false
  }
}

onMounted(startMarquee)
onBeforeUnmount(stopMarquee)
</script>

<style scoped>
.sla-alert-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 14px;
  background: linear-gradient(90deg, #fffbeb 0%, #fef3c7 100%);
  border: 1px solid #fde68a;
  border-radius: 10px;
  margin-bottom: 12px;
  box-shadow: 0 1px 2px rgba(245, 158, 11, 0.05);
}

.banner-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: 1;
}

.pulse-icon-wrap {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: rgba(245, 158, 11, 0.15);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.banner-content {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.banner-badge {
  font-size: 11px;
  font-weight: 700;
  color: #b45309;
  background: #fef3c7;
  border: 1px solid #fcd34d;
  padding: 1px 6px;
  border-radius: 4px;
  white-space: nowrap;
}

.banner-marquee {
  font-size: 12px;
  color: #92400e;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.banner-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.expedite-btn {
  background: #d97706 !important;
  border-color: #d97706 !important;
  font-size: 11.5px;
  font-weight: 600;
  height: 28px;
  padding: 0 10px;
  border-radius: 6px;
}
.expedite-btn:hover {
  background: #b45309 !important;
}

.detail-link {
  font-size: 11.5px;
  color: #b45309;
  padding: 0 4px;
}

@media (max-width: 768px) {
  .sla-alert-banner {
    flex-direction: column;
    align-items: flex-start;
  }
  .banner-right {
    width: 100%;
    justify-content: space-between;
    margin-top: 4px;
  }
}
</style>
