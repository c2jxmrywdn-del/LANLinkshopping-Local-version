<template>
  <a-modal
    :open="open"
    :title="'评价商品'"
    :confirm-loading="submitting"
    ok-text="提交评价"
    cancel-text="取消"
    @ok="submit"
    @cancel="$emit('update:open', false)"
  >
    <div class="rv-product">{{ item?.prodName }}</div>
    <div class="rv-row">
      <span class="rv-label">评分</span>
      <a-rate v-model:value="rating" :count="5" />
      <span class="rv-score">{{ rating }} 星</span>
    </div>
    <a-textarea
      v-model:value="content"
      :rows="4"
      :maxlength="500"
      show-count
      placeholder="说说商品质量、服务和物流（最多 500 字）"
    />
    <a-checkbox v-model:checked="anonymous" class="rv-anon">匿名展示（不显示买家标识）</a-checkbox>
  </a-modal>
</template>

<script setup>
import { ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { reviewApi } from '../../api'

const props = defineProps({
  open: { type: Boolean, default: false },
  orderNo: { type: String, default: '' },
  item: { type: Object, default: null }
})
const emit = defineEmits(['update:open', 'submitted'])

const rating = ref(5)
const content = ref('')
const anonymous = ref(false)
const submitting = ref(false)

watch(() => props.open, (v) => {
  if (v) { rating.value = 5; content.value = ''; anonymous.value = false }
})

async function submit() {
  if (!props.item) return
  submitting.value = true
  try {
    await reviewApi.submit(props.orderNo, {
      itemId: props.item.itemId,
      rating: rating.value,
      content: content.value,
      anonymous: anonymous.value
    })
    message.success('评价已提交')
    emit('update:open', false)
    emit('submitted', props.item.itemId)
  } catch (e) {
    // 拦截器已提示（409 重复评价会带服务端文案）
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.rv-product { font-weight: 600; margin-bottom: 12px; }
.rv-row { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.rv-label { color: #6b7280; }
.rv-score { color: #b45309; font-weight: 600; }
.rv-anon { margin-top: 12px; display: block; }
</style>
