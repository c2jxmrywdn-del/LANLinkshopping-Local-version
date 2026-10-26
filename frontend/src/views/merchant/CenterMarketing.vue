<template>
  <div class="mk-page">
    <div class="mk-head">
      <div>
        <h2>营销活动</h2>
        <p>报名平台大促、冲刺销售目标，赢取积分激励奖励。</p>
      </div>
      <a-button @click="$router.push('/activity')">查看用户端活动</a-button>
    </div>

    <a-spin :spinning="loading">
      <!-- 商户激励：进度冲刺 -->
      <div class="mk-section" v-if="incentives.length">
        <div class="mk-sec-title"><Store :size="15" :stroke-width="2.2" style="vertical-align:-2px;margin-right:6px;color:#047857" /> 激励奖励 <span>按活动时间窗内本店已支付销售额计算进度</span></div>
        <div class="mk-grid">
          <MagicCard v-for="c in incentives" :key="c.campaignId"
                     class="mk-card mk-incentive" :gradient-size="260"
                     gradient-from="#1E6EB8" gradient-to="#38bdf8"
                     gradient-color="rgba(30, 110, 184, .07)">
            <div class="mi-top">
              <span class="mk-chip green">商户激励</span>
              <span class="mi-window">{{ fmtDate(c.startTime) }} ~ {{ fmtDate(c.endTime) || '长期' }}</span>
            </div>
            <h3>{{ c.title }}</h3>
            <div class="mi-target">
              <span>销售目标 ¥{{ money(c.targetAmount) }}</span>
              <span>奖励 <b>{{ c.rewardPoints }}</b> 积分</span>
            </div>
            <div class="mi-progress">
              <div class="mi-bar"><i :style="{ width: (c.progressPercent || 0) + '%' }" :class="{ done: c.targetReached }"></i></div>
              <div class="mi-progress-meta">
                <span>已完成 <b>¥{{ money(c.salesAmount) }}</b></span>
                <span class="mi-percent">{{ c.progressPercent || 0 }}%</span>
              </div>
            </div>
            <div class="mi-foot">
              <template v-if="!c.enrolled">
                <a-button type="primary" @click="enroll(c)">报名参加</a-button>
              </template>
              <template v-else-if="c.rewarded">
                <a-tag color="green">奖励已发放</a-tag>
              </template>
              <template v-else-if="c.targetReached">
                <a-button type="primary" danger @click="claimReward(c)">领取 {{ c.rewardPoints }} 积分奖励</a-button>
              </template>
              <template v-else>
                <a-tag color="blue">已报名 · 冲刺中</a-tag>
              </template>
              <span class="mi-hint" v-if="c.enrolled && !c.targetReached && !c.rewarded">
                还差 ¥{{ money(c.targetAmount - c.salesAmount) }} 达标
              </span>
            </div>
          </MagicCard>
        </div>
      </div>
      <a-empty v-else-if="!loading" description="暂无可报名的激励活动" style="padding:32px 0" />

      <!-- 平台大促：开放报名的活动 -->
      <div class="mk-section" v-if="joinables.length">
        <div class="mk-sec-title"><Flame :size="15" :stroke-width="2.2" style="vertical-align:-2px;margin-right:6px;color:#e0354b" /> 平台大促 <span>报名后店铺商品将参与活动会场</span></div>
        <div class="mk-grid">
          <MagicCard v-for="c in joinables" :key="c.campaignId"
                     class="mk-card" :gradient-size="260"
                     gradient-from="#1E6EB8" gradient-to="#38bdf8"
                     gradient-color="rgba(30, 110, 184, .07)">
            <div class="mi-top">
              <span class="mk-chip" :class="typeClass(c.type)">{{ typeLabel(c.type) }}</span>
              <span class="mi-window">{{ fmtDate(c.startTime) }} ~ {{ fmtDate(c.endTime) || '长期' }}</span>
            </div>
            <h3>{{ c.title }}</h3>
            <p class="mk-desc">{{ c.description }}</p>
            <div class="mi-foot">
              <span class="mk-scope">{{ c.indName ? c.indName + '行业' : '全场' }}{{ c.enrolled ? ' · 已报名' : '' }}</span>
              <a-button v-if="!c.enrolled" type="primary" ghost @click="enroll(c)">立即报名</a-button>
              <a-tag v-else color="green">已报名</a-tag>
            </div>
          </MagicCard>
        </div>
      </div>
    </a-spin>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { campaignApi } from '../../api'
import MagicCard from '../../components/ui/MagicCard.vue'
import { Store, Flame } from 'lucide-vue-next'

const campaigns = ref([])
const loading = ref(false)

const incentives = computed(() => campaigns.value.filter(c => c.type === 'merchant_incentive'))
const joinables = computed(() => campaigns.value.filter(c => c.type !== 'merchant_incentive'))

async function load() {
  loading.value = true
  try { campaigns.value = (await campaignApi.merchantList()) || [] }
  catch (e) { campaigns.value = [] } finally { loading.value = false }
}

async function enroll(c) {
  try {
    await campaignApi.merchantJoin(c.campaignId)
    message.success('报名成功')
    load()
  } catch (e) { /* 拦截器已提示 */ }
}
async function claimReward(c) {
  try {
    await campaignApi.merchantClaimReward(c.campaignId)
    message.success('激励奖励已发放至积分账户')
    load()
  } catch (e) { /* 拦截器已提示 */ }
}

const typeLabel = (t) => ({ full_reduce: '满减大促', discount: '折扣大促', flash_sale: '限时秒杀' }[t] || '平台活动')
const typeClass = (t) => ({ full_reduce: 'blue', discount: 'blue', flash_sale: 'red' }[t] || 'blue')
const fmtDate = (v) => v ? String(v).slice(0, 10) : ''
const money = (v) => Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

onMounted(load)
</script>

<style scoped>
.mk-page { padding: 6px 2px; }
.mk-head { display: flex; justify-content: space-between; align-items: center; gap: 16px; margin-bottom: 20px; }
.mk-head h2 { margin: 0 0 4px; font-size: 22px; letter-spacing: -.02em; color: var(--mc-ink, var(--ll-ink, #1d1d1f)); }
.mk-head p { margin: 0; font-size: 13px; color: var(--mc-muted, var(--ll-muted, #6e6e73)); }

.mk-section { margin-bottom: 26px; }
.mk-sec-title { font-size: 15px; font-weight: 700; color: var(--mc-ink, var(--ll-ink, #1d1d1f)); margin-bottom: 12px; }
.mk-sec-title span { font-size: 12px; font-weight: 400; color: var(--mc-muted, var(--ll-muted, #6e6e73)); margin-left: 10px; }

.mk-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(330px, 1fr)); gap: 14px; }
.mk-card { background: var(--mc-card, #fff); border: 1px solid var(--mc-border, #e8e6e1);
  border-radius: 18px; padding: 18px 20px; display: flex; flex-direction: column;
  transition: transform .25s var(--ll-ease-out, ease), box-shadow .25s; }
.mk-card:hover { transform: translateY(-3px); box-shadow: 0 14px 34px rgba(19, 35, 58, .08); }

.mi-top { display: flex; justify-content: space-between; align-items: center; gap: 8px; margin-bottom: 10px; }
.mk-chip { font-size: 11.5px; color: #fff; padding: 3px 10px; border-radius: 999px; }
.mk-chip.green { background: linear-gradient(135deg, #047857, #34d399); }
.mk-chip.blue { background: linear-gradient(135deg, #1E6EB8, #38bdf8); }
.mk-chip.red { background: linear-gradient(135deg, #e0354b, #ff7a59); }
.mi-window { font-size: 11.5px; color: var(--mc-muted, var(--ll-muted, #6e6e73)); }
.mk-card h3 { margin: 0 0 8px; font-size: 15.5px; color: var(--mc-ink, var(--ll-ink, #1d1d1f)); }
.mk-desc { margin: 0 0 12px; font-size: 13px; color: var(--mc-muted, var(--ll-muted, #6e6e73)); line-height: 1.6;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; min-height: 42px; }

.mi-target { display: flex; justify-content: space-between; align-items: baseline; font-size: 13px;
  color: var(--mc-text2, var(--ll-muted, #3a3a3c)); margin-bottom: 10px; }
.mi-target b { font-size: 17px; color: #b45309; }

.mi-progress { margin-bottom: 14px; }
.mi-bar { height: 8px; border-radius: 999px; background: var(--mc-soft, #f5f5f7); overflow: hidden; }
.mi-bar i { display: block; height: 100%; border-radius: 999px;
  background: linear-gradient(90deg, #1E6EB8, #38bdf8); transition: width .6s var(--ll-ease-out, ease); }
.mi-bar i.done { background: linear-gradient(90deg, #047857, #34d399); }
.mi-progress-meta { display: flex; justify-content: space-between; font-size: 12px;
  color: var(--mc-muted, var(--ll-muted, #6e6e73)); margin-top: 6px; }
.mi-progress-meta b { color: var(--mc-ink, var(--ll-ink, #1d1d1f)); }
.mi-percent { font-variant-numeric: tabular-nums; font-weight: 700; }

.mi-foot { margin-top: auto; display: flex; justify-content: space-between; align-items: center; gap: 10px; }
.mi-hint { font-size: 12px; color: #d93025; }
.mk-scope { font-size: 12.5px; color: var(--mc-muted, var(--ll-muted, #6e6e73)); }

@media (max-width: 700px) {
  .mk-head { flex-direction: column; align-items: flex-start; }
  .mk-grid { grid-template-columns: 1fr; }
}
</style>
