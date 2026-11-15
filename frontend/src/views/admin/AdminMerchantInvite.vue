<script setup>
/**
 * 运营后台：商家入驻邀约码。
 *
 * <p>与「前台邀请链接」的区别：
 * <ul>
 *   <li>前台邀请（任何登录角色）→ 被邀请人注册后是<b>普通用户</b>；</li>
 *   <li>本页（仅平台运营）→ 生成入驻邀约码，被邀请人在商家入驻表单里填写该码，
 *       <b>提交资质并通过审核后</b>才成为商户，注册接口不会直接给商户身份。</li>
 * </ul>
 *
 * <p>权限：路由 meta.admin=true + 后端 403 双拦截；非运营即使直接访问 URL 也会被路由守卫挡回首页。
 */
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import InviteLinkCard from '../../components/invite/InviteLinkCard.vue'
import { inviteApi } from '../../api/invite'

const loading = ref(false)
const issuing = ref(false)
const link = ref(null)
const maxUses = ref(0)
const error = ref('')

const merchantApplyUrl = computed(() => link.value?.url || '')

async function load() {
  loading.value = true
  error.value = ''
  try {
    // 复用前台组件的数据源：运营调用 /invite/link 拿到的仍是 buyer 邀请，
    // 所以这里必须显式走运营专属端点生成 merchant_apply 码。
    link.value = await inviteApi.issueMerchantApplyCode(undefined, false)
  } catch (e) {
    error.value = e?.message || '生成入驻邀约码失败'
  } finally {
    loading.value = false
  }
}

async function regenerate() {
  issuing.value = true
  try {
    const uses = Number(maxUses.value)
    link.value = await inviteApi.issueMerchantApplyCode(
      Number.isFinite(uses) && uses > 0 ? uses : undefined,
      true
    )
    message.success('已生成新的入驻邀约码，旧码立即失效')
  } catch (e) {
    // 拦截器已按后端 message 提示；这里只兜底本地错误文案
    error.value = e?.message || '重新生成失败'
  } finally {
    issuing.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="admin-merchant-invite">
    <header class="page-head">
      <div>
        <h2>商家入驻邀约</h2>
        <p class="hint">
          生成专属入驻邀约码，发送给目标商家。对方在「商家入驻」表单选择「邀约入驻」并填写该码，
          提交资质、<strong>审核通过后</strong>才会获得商户身份。
        </p>
      </div>
      <div class="uses-input">
        <label for="max-uses">使用次数上限</label>
        <a-input-number id="max-uses" v-model:value="maxUses" :min="0" :max="10000" :step="1" />
        <span class="tip">填 0 表示不限次数</span>
      </div>
    </header>

    <a-alert v-if="error" type="error" show-icon :message="error" class="err" />

    <a-spin :spinning="loading">
      <InviteLinkCard
        v-if="link"
        :auto-load="false"
        :initial-link="link"
        :show-rotate="false"
        show-code
        @rotate="regenerate"
      />
      <a-empty v-else-if="!loading" description="暂无入驻邀约码" />
    </a-spin>

    <div v-if="link" class="actions">
      <a-button type="primary" :loading="issuing" @click="regenerate">重新生成（旧码立即失效）</a-button>
      <span v-if="merchantApplyUrl" class="url-tip">入驻落地页：{{ merchantApplyUrl }}</span>
    </div>
  </div>
</template>

<style scoped>
.admin-merchant-invite { display: flex; flex-direction: column; gap: 16px; }
.page-head { display: flex; flex-wrap: wrap; gap: 16px; align-items: flex-start; justify-content: space-between; }
.page-head h2 { margin: 0 0 6px; font-size: 18px; }
.hint { margin: 0; max-width: 640px; color: var(--ll-gray2, #6b7280); font-size: 13px; line-height: 1.6; }
.uses-input { display: flex; flex-direction: column; gap: 4px; }
.uses-input label { font-size: 12px; color: var(--ll-gray2, #6b7280); }
.tip { font-size: 12px; color: var(--ll-gray2, #9ca3af); }
.err { margin: 0; }
.actions { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; }
.url-tip { font-size: 12px; color: var(--ll-gray2, #6b7280); word-break: break-all; }
@media (max-width: 767px) {
  .page-head { flex-direction: column; }
}
</style>
