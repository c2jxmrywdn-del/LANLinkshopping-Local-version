<template>
  <div class="page">
    <a-button type="link" class="back" @click="$router.push('/activity')"><ArrowLeft :size="14" style="vertical-align:-2px;margin-right:3px" /> 活动中心</a-button>
    <div class="card">
      <div class="eyebrow">CAMPAIGNS / RULES</div>
      <h1>活动规则</h1>
      <a-collapse ghost>
        <a-collapse-panel key="1">
          <template #header><span class="rl-head"><Ticket :size="15" /> 领券活动</span></template>
          <p>领券活动由平台运营上架，进入"活动中心 → 领券专区"点击领取；优惠券存入"我的券包"，在提交订单时选用核销。每张券注明使用门槛（满 X 可用或无门槛）、适用范围（全场或指定行业）与有效期；折扣类优惠券设有最高优惠上限。领取有数量限制（每人限领张数 + 活动发放总量），先到先得，抢完即止。</p>
        </a-collapse-panel>
        <a-collapse-panel key="2">
          <template #header><span class="rl-head"><Zap :size="15" /> 促销活动（满减 / 折扣 / 限时秒杀）</span></template>
          <p>促销活动无需领取，结算时订单金额满足门槛即自动享受。促销优惠与会员等级折扣互斥，系统自动按对您更优的一种结算；同类多个促销同时生效时取优惠最大的一种。限时秒杀有严格时间窗，逾时自动恢复原价，以下单结算时间为准。</p>
        </a-collapse-panel>
        <a-collapse-panel key="3">
          <template #header><span class="rl-head"><Gift :size="15" /> 积分激励（注册有礼 / 消费有礼）</span></template>
          <p>注册有礼类活动可手动参与，参与成功积分即时到账，同一活动每位用户仅可参与一次；消费有礼类活动在订单支付成功后由系统自动记录参与并发放积分，无需报名。积分写入会员积分流水，可在会员中心查看。</p>
        </a-collapse-panel>
        <a-collapse-panel key="4">
          <template #header><span class="rl-head"><Store :size="15" /> 商户激励与活动报名</span></template>
          <p>入驻商户可在"商户中心 → 营销活动"报名平台大促与激励活动。商户激励活动按活动时间窗内本店铺已支付订单的销售额计算进度，达到目标销售额后可领取积分奖励，奖励发放至商户负责人的积分账户，同一活动每个店铺仅可领取一次。</p>
        </a-collapse-panel>
        <a-collapse-panel key="5">
          <template #header><span class="rl-head"><Undo2 :size="15" /> 退款与回退</span></template>
          <p>订单取消或全额退款时，本单核销的优惠券自动退回券包（若仍在有效期内可再次使用）；抵扣积分按积分规则返还。部分退款不回退优惠券。</p>
        </a-collapse-panel>
        <a-collapse-panel key="6">
          <template #header><span class="rl-head"><TriangleAlert :size="15" /> 异常处理</span></template>
          <p>领取/参与请求均有幂等与防并发保护，网络拥堵时请勿重复提交；如遇"已抢完""已达限领"等提示，以页面状态为准。活动最终解释权归平台运营所有。</p>
        </a-collapse-panel>
      </a-collapse>
    </div>
  </div>
</template>
<script setup>
import { Ticket, Zap, Gift, Store, Undo2, TriangleAlert } from 'lucide-vue-next'
</script>
<style scoped>
.page { max-width: 780px; margin: 0 auto; }
.back { padding: 0; margin-bottom: 10px; }
.rl-head { display: inline-flex; align-items: center; gap: 8px; }
.rl-head svg { color: var(--ll-primary, #1E6EB8); }
.card { border-radius: 22px; padding: 12px 18px; background: var(--ll-card, #fff);
  box-shadow: var(--ll-shadow-sm, 0 1px 2px rgba(16,24,40,.06)); }
.eyebrow { font-size: 11px; letter-spacing: .16em; color: var(--ll-primary, #1E6EB8); font-weight: 600; }
h1 { margin: 6px 0 14px; font-size: 26px; letter-spacing: -.02em; color: var(--ll-ink, #0F172A); }
p { color: var(--ll-muted, #64748B); }
</style>
