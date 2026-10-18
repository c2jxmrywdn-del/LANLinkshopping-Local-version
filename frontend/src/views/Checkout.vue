<template>
  <div class="wrap">
    <a-card title="确认订单" class="left">
      <!-- 收货地址选择：来自个人中心，默认地址自动带入 -->
      <div class="addr-section" v-if="addrList.length">
        <div class="addr-title">收货地址 <span class="addr-sub">来自个人中心</span></div>
        <div class="addr-grid">
          <div v-for="a in addrList" :key="a.id"
               class="addr-card" :class="{ active: selectedAddrId === a.id }"
               @click="pickAddr(a)">
            <div class="addr-head">
              <span class="addr-name">{{ a.receiver }}</span>
              <span class="addr-phone">{{ a.phone }}</span>
              <a-tag v-if="a.isDefault === 1" color="red" class="addr-dtag">默认</a-tag>
            </div>
            <div class="addr-line">{{ a.region ? a.region + ' ' : '' }}{{ a.detail }}</div>
          </div>
        </div>
      </div>
      <a-alert v-else-if="addrLoaded" type="info" show-icon class="addr-empty"
               message="还没有收货地址"
               description="可前往个人中心 → 收货地址 添加，或在下方直接填写。" />

      <a-form :model="form" layout="vertical" @finish="submit">
        <a-form-item label="收货人" :rules="[{ required: true }]"><a-input v-model:value="form.receiver" /></a-form-item>
        <a-form-item label="联系电话" :rules="[{ required: true }]"><a-input v-model:value="form.phone" /></a-form-item>
        <a-form-item label="收货地址" :rules="[{ required: true }]"><a-textarea v-model:value="form.address" :rows="2" /></a-form-item>
        <a-form-item label="支付方式">
          <a-radio-group v-model:value="form.payType">
            <a-radio value="corporate">对公转账</a-radio>
            <a-radio value="balance">企业钱包<span v-if="walletLoaded" class="wallet-tip">（余额 ¥{{ walletBalance.toFixed(2) }}<template v-if="walletInsufficient">，不足</template>）</span></a-radio>
            <a-radio value="term" :disabled="termUnavailable">账期/尾款
              <span v-if="creditLoaded" class="wallet-tip">（可用授信 ¥{{ creditAvailable.toFixed(2) }}<template v-if="creditStatus !== 'ACTIVE'">，未开通</template><template v-else-if="termUnavailable">，额度不足</template>）</span>
            </a-radio>
          </a-radio-group>
          <div v-if="form.payType === 'term' && creditStatus !== 'ACTIVE'" class="wallet-warn">
            当前没有生效的企业账期，可前往 <a @click="$router.push('/credit-term')">账期管理</a> 申请授信。
          </div>
          <div v-else-if="form.payType === 'term' && termUnavailable" class="wallet-warn">
            当前账期可用额度不足，本单需 ¥{{ payableTotal }}，可前往 <a @click="$router.push('/credit-term')">账期管理</a> 查看额度。
          </div>
          <div v-if="form.payType === 'balance' && walletInsufficient" class="wallet-warn">
            钱包余额不足，可在支付前前往 <a @click="$router.push('/wallet')">我的钱包</a> 充值。
          </div>
        </a-form-item>
        <a-form-item label="备注"><a-input v-model:value="form.remark" /></a-form-item>
        <a-button type="primary" html-type="submit" size="large" block :loading="loading">提交订单</a-button>
      </a-form>
    </a-card>
    <a-card title="订单商品" class="right">
      <div v-for="i in cart.checkedItems" :key="i.cartId" class="row">
        <span>{{ i.title }} × {{ i.quantity }}</span><span>¥{{ i.subtotal }}</span>
      </div>
      <a-divider />
      <!-- 积分抵现（会员系统）：余额实时试算，合计联动 -->
      <div class="points-box" v-if="pointsLoaded">
        <div class="points-head">
          <a-checkbox v-model:checked="usePoints">积分抵现</a-checkbox>
          <span class="points-balance">可用 <b>{{ memberPoints }}</b> 积分</span>
        </div>
        <template v-if="usePoints">
          <div class="points-input" v-if="memberPoints > 0">
            <a-input-number v-model:value="pointsInput" :min="0" :max="memberPoints"
                            :step="pointsStep" :precision="0" style="width: 100%"
                            placeholder="输入使用的积分数" />
            <a-button size="small" @click="useAllPoints">全部</a-button>
          </div>
          <div class="points-hint">
            {{ pointsHint }}
            <span v-if="user.isVip" class="points-vip">VIP 消费享 ×{{ rule.vipEarnMultiplier }} 积分</span>
          </div>
        </template>
        <div class="points-hint" v-else>{{ rule.redeemPointsPerYuan }} 积分抵 1 元（最小抵扣 1 元），单笔最多抵应付金额的 {{ rule.redeemMaxPercent }}%</div>
      </div>
      <div class="row points-deduct" v-if="redeemAmount > 0">
        <span>积分抵扣（{{ quotePoints }} 积分）</span><b class="deduct">-¥{{ redeemAmount }}</b>
      </div>
      <div class="row total"><span>实付金额</span><b>¥{{ payableTotal }}</b></div>
    </a-card>
  </div>
</template>

<script setup>
import { reactive, ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { orderApi, paymentApi, userApi, membershipApi, walletApi, creditTermApi } from '../api'
import { validateCheckoutForm } from '../utils/validationRules'
import { useCartStore } from '../store/cart'
import { useUserStore } from '../store/user'

const router = useRouter()
const cart = useCartStore()
const user = useUserStore()
const loading = ref(false)
const form = reactive({ receiver: '', phone: '', address: '', remark: '', payType: 'corporate' })
// 幂等键（防重复下单）：submit 首次生成、成功后重置、失败重试保留同一 key
const idempotentKey = ref('')

// ===== 个人中心地址联动：默认地址自动带入，点击卡片一键填充 =====
const addrList = ref([])
const addrLoaded = ref(false)
const selectedAddrId = ref(null)

// ===== 积分抵现（会员系统）：余额加载 + 防抖试算 + 合计联动 =====
// 规则：1000 积分 = 1 元，抵扣最小单位 1 元（不足 1000 积分的零头不参与抵扣）
const pointsLoaded = ref(false)
const memberPoints = ref(0)
const rule = ref({ pointsPerYuanEarn: 1, redeemPointsPerYuan: 1000, redeemMaxPercent: 10, vipEarnMultiplier: 2 })
const usePoints = ref(false)
const pointsInput = ref(null)
const quotePoints = ref(0)   // 试算返回的实际抵扣积分（归整到 1000 整数倍）
const redeemAmount = ref(0)  // 试算返回的抵扣金额（元，整元）
const maxPoints = ref(0)     // 本单可抵扣的最大积分（上限金额 × 1000）
let quoteTimer = null

// 应付合计 = 商品金额 - 积分抵扣（促销/会员折扣以后端结算为准）
const payableTotal = computed(() => Math.max(0, cart.total - redeemAmount.value).toFixed(2))

// 积分输入步进：按 1 元 = 1000 积分整元调整
const pointsStep = computed(() => rule.value.redeemPointsPerYuan || 1000)

const pointsHint = computed(() => {
  const per = rule.value.redeemPointsPerYuan
  if (redeemAmount.value > 0) {
    return `已抵扣 ¥${redeemAmount.value}（${quotePoints.value} 积分），${per} 积分 = 1 元，单笔上限 ${rule.value.redeemMaxPercent}%`
  }
  if (memberPoints.value > 0 && memberPoints.value < per) {
    return `积分不足 ${per} 分（1 元）暂不可抵扣，差 ${per - memberPoints.value} 积分`
  }
  return `${per} 积分 = 1 元（最小抵扣 1 元，不足 ${per} 分的零头不抵扣），单笔最多抵应付金额的 ${rule.value.redeemMaxPercent}%`
})

function useAllPoints() {
  // 全部 = 取余额与本单上限的较小值（后端试算仍会归整到 1000 整数倍）
  if (memberPoints.value > 0) {
    pointsInput.value = maxPoints.value > 0 ? Math.min(memberPoints.value, maxPoints.value) : memberPoints.value
  }
}

// 输入变化后 400ms 防抖试算，避免频繁请求
watch([pointsInput, usePoints], () => {
  clearTimeout(quoteTimer)
  if (!usePoints.value || !pointsInput.value || pointsInput.value <= 0) {
    quotePoints.value = 0
    redeemAmount.value = 0
    return
  }
  quoteTimer = setTimeout(runQuote, 400)
})

async function runQuote() {
  if (!cart.total || !pointsInput.value) return
  try {
    const q = await membershipApi.redeemQuote(pointsInput.value, cart.total)
    quotePoints.value = q?.points || 0
    redeemAmount.value = Number(q?.amount) || 0
    maxPoints.value = q?.maxPoints || 0
  } catch (e) {
    quotePoints.value = 0
    redeemAmount.value = 0
    maxPoints.value = 0
  }
}

// ===== 企业钱包余额展示（payType=balance 时联动提示） =====
const walletLoaded = ref(false)
const walletBalance = ref(0)
const walletInsufficient = computed(() => walletBalance.value < (cart.total - redeemAmount.value))
const creditLoaded = ref(false)
const creditStatus = ref('NOT_APPLIED')
const creditAvailable = ref(0)
const termUnavailable = computed(() => creditStatus.value !== 'ACTIVE' || creditAvailable.value < (cart.total - redeemAmount.value))

onMounted(async () => {
  try {
    addrList.value = (await userApi.addressList()) || []
  } catch (e) {
    /* 拦截器已提示；未登录等场景保持手工填写 */
  } finally {
    addrLoaded.value = true
  }
  const def = addrList.value.find(a => a.isDefault === 1) || addrList.value[0]
  if (def) applyAddr(def)
  // 加载会员积分余额与规则（失败不阻塞结算流程）
  try {
    const m = await membershipApi.my()
    memberPoints.value = m?.card?.points || 0
    if (m?.rule) rule.value = m.rule
  } catch (e) { /* 未登录或接口异常时隐藏积分区块 */ }
  finally { pointsLoaded.value = true }
  // 加载钱包余额（企业钱包支付提示）
  try {
    const w = await walletApi.my()
    walletBalance.value = Number(w?.balance || 0)
  } catch (e) { walletBalance.value = 0 }
  finally { walletLoaded.value = true }
  // 加载账期额度（账期支付方式实时校验）
  try {
    const c = await creditTermApi.overview()
    creditStatus.value = c?.status || 'NOT_APPLIED'
    creditAvailable.value = Number(c?.account?.availableLimit || 0)
  } catch (e) { creditStatus.value = 'NOT_APPLIED'; creditAvailable.value = 0 }
  finally { creditLoaded.value = true }
})

function applyAddr(a) {
  selectedAddrId.value = a.id
  form.receiver = a.receiver
  form.phone = a.phone
  form.address = (a.region ? a.region + ' ' : '') + a.detail
}

function pickAddr(a) {
  applyAddr(a)
  message.success(`已选用 ${a.receiver} 的地址`, 2)
}

async function submit() {
  if (!cart.checkedItems.length) { message.warning('没有勾选的商品'); return }
  // L3 形式校验：规则与后端 CheckoutDTO 注解同契约（validationRules.js），金额/价格由后端权威重算
  const check = validateCheckoutForm(form)
  if (!check.ok) { message.warning(check.msg); return }
  loading.value = true
  try {
    // 幂等键：同一结算动作（含失败重试）复用同一 key，服务端据此拦截重复下单
    if (!idempotentKey.value) idempotentKey.value = window.crypto?.randomUUID?.() || ('ck-' + Date.now() + '-' + Math.random().toString(36).slice(2))
    const order = await orderApi.checkout({
      cartIds: cart.checkedItems.map(i => i.cartId),
      receiver: form.receiver, phone: form.phone, address: form.address,
      remark: form.remark, payType: form.payType,
      // 积分抵现：勾选且输入有效时携带（后端按实际应付金额同口径校验）
      usePoints: usePoints.value && pointsInput.value > 0 ? pointsInput.value : null,
      idempotentKey: idempotentKey.value
    })
    idempotentKey.value = '' // 下单成功，重置幂等键，允许下一次新结算
    await cart.load()
    if (form.payType === 'term') {
      Modal.info({
        title: '账期订单已创建',
        content: `订单号 ${order.orderNo}，本次占用授信 ¥${order.totalAmount}。账单已进入账期中心，请按到期日完成还款。`,
        okText: '查看账期账单',
        onOk: () => router.push('/credit-term/bills')
      })
    } else {
      Modal.confirm({
        title: '下单成功',
        content: `订单号 ${order.orderNo}，应付 ¥${order.totalAmount}。是否立即支付？`,
        okText: '立即支付', cancelText: '稍后支付',
        onOk: async () => { try { if (form.payType === 'balance') { const vo = await paymentApi.create(order.orderNo, 'wallet'); if (!vo?.payInfo?.paid) throw new Error('钱包支付未确认') } else { const vo = await paymentApi.create(order.orderNo, 'mock'); if (vo?.mock) await paymentApi.mockConfirm(order.orderNo) } message.success('支付成功'); router.push('/orders') } catch (e) {} },
        onCancel: () => router.push('/orders')
      })
    }
  } catch (e) {
    // 校验被拦截（幂等重复/价格篡改/库存不足等）由后端返回业务错误，拦截器已提示
    message.error(e?.message || '下单失败，请稍后重试')
  } finally { loading.value = false }
}
</script>

<style scoped>
.wrap { display: flex; gap: 24px; align-items: flex-start; }
.left { flex: 1; border-radius: var(--ll-radius-xl, 18px); } .right { width: 360px; border-radius: var(--ll-radius-xl, 18px); }
.row { display: flex; justify-content: space-between; padding: 6px 0; }
.total b { color: var(--ll-price, #e4393c); font-size: 20px; letter-spacing: -.02em; }
/* 地址选择区 */
.addr-section { margin-bottom: 20px; }
.addr-title { font-weight: 600; margin-bottom: 10px; }
.addr-sub { font-size: 12px; font-weight: 400; color: var(--ll-muted, #64748b); margin-left: 8px; }
.addr-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 10px; }
.addr-card { border: 1.5px solid var(--ll-border, #e5e7eb); border-radius: var(--ll-radius-md, 10px); padding: 10px 12px; cursor: pointer;
             transition: border-color .2s ease, box-shadow .2s ease, background-color .2s ease, transform .2s var(--ll-ease-out, ease); }
.addr-card:hover { border-color: var(--ll-skyblue-border, #5b8fb5); transform: translateY(-1px); }
.addr-card.active { border-color: var(--ll-primary, #1e6eb8); background: rgba(30, 110, 184, .06);
                    box-shadow: 0 0 0 1px var(--ll-primary, #1e6eb8) inset; }
.addr-head { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.addr-name { font-weight: 600; }
.addr-phone { color: var(--ll-muted, #64748b); font-size: 13px; }
.addr-dtag { margin-left: auto; margin-right: 0; }
.addr-line { font-size: 13px; color: var(--ll-gray, #4b5563); }
.addr-empty { margin-bottom: 16px; }
/* 积分抵现 */
.points-box { background: rgba(30, 110, 184, .04); border: 1px dashed rgba(30, 110, 184, .25);
              border-radius: 10px; padding: 10px 12px; margin-bottom: 12px; }
.points-head { display: flex; justify-content: space-between; align-items: center; }
.points-balance { font-size: 12px; color: var(--ll-muted, #64748b); }
.points-balance b { color: var(--ll-primary, #1e6eb8); }
.points-input { display: flex; gap: 8px; margin-top: 8px; }
.points-hint { font-size: 12px; color: var(--ll-muted, #64748b); margin-top: 6px; line-height: 1.6; }
.points-vip { display: inline-block; margin-left: 6px; padding: 0 6px; border-radius: 8px;
              background: rgba(180, 83, 9, .1); color: #b45309; font-weight: 600; }
.points-deduct { color: var(--ll-gray, #4b5563); font-size: 13px; }
.points-deduct .deduct { color: #10b981; font-size: 15px; }
/* 企业钱包余额提示 */
.wallet-tip { font-size: 12px; color: var(--ll-muted, #64748b); }
.wallet-warn { margin-top: 6px; font-size: 12px; color: #b45309; }
.wallet-warn a { color: var(--ll-primary, #1e6eb8); cursor: pointer; }
/* 响应式：平板/手机改为单列，订单商品卡自适应宽度 */
@media (max-width: 900px) {
  .wrap { flex-direction: column; }
  .right { width: 100%; }
}
</style>
