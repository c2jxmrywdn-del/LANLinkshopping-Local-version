<template>
  <a-modal
    :open="open"
    :title="txt.title"
    :confirm-loading="submitting"
    :ok-text="txt.submit"
    :cancel-text="txt.cancel"
    :ok-button-props="{ disabled: rating < 1 }"
    @ok="submit"
    @cancel="$emit('update:open', false)"
  >
    <div class="rv-product">{{ item?.prodName }}</div>
    <div class="rv-row">
      <span class="rv-label">{{ txt.score }}</span>
      <a-rate v-model:value="rating" :count="5" />
      <span class="rv-score">{{ rating }} {{ txt.star }}</span>
    </div>
    <a-textarea
      v-model:value="content"
      :rows="4"
      :maxlength="500"
      show-count
      :placeholder="txt.placeholder"
    />
    <div v-if="mode === 'order'" class="rv-attach">
      <div class="rv-attach-head">
        <span class="rv-label">{{ txt.attach }}</span>
        <a-button size="small" :disabled="attachments.length >= 9" @click="pickFile">
          <ImagePlus :size="13" class="inline-block mr-1 align-middle" /> {{ txt.addImage }}
        </a-button>
        <input ref="fileInput" type="file" accept="image/*" multiple hidden @change="onPick" />
      </div>
      <div v-if="attachments.length" class="rv-attach-list">
        <span v-for="(a, i) in attachments" :key="a + i" class="rv-attach-item">
          {{ shortName(a) }}
          <button type="button" class="rv-attach-del" :aria-label="txt.removeImage" @click="removeAttachment(i)">
            <X :size="12" />
          </button>
        </span>
      </div>
      <p v-if="uploadError" class="rv-attach-err">{{ txt.uploadFailed }}</p>
    </div>
    <a-checkbox v-model:checked="anonymous" class="rv-anon">{{ txt.anonymous }}</a-checkbox>
  </a-modal>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { ImagePlus, X } from 'lucide-vue-next'
import { reviewApi, chatApi } from '../../api'
import { locale } from '../../i18n'

const props = defineProps({
  open: { type: Boolean, default: false },
  orderNo: { type: String, default: '' },
  item: { type: Object, default: null },
  // item：旧行级链路（t_product_review）；order：订单维度新链路（t_order_review，含附件）
  mode: { type: String, default: 'item' }
})
const emit = defineEmits(['update:open', 'submitted'])

const rating = ref(5)
const content = ref('')
const anonymous = ref(false)
const submitting = ref(false)
const attachments = ref([])
const uploading = ref(false)
const uploadError = ref(false)
const fileInput = ref(null)

// 整句在组件内成句：避免插值把一句话拆成多个文本节点后逐节点翻译导致语序错乱
const txt = computed(() => locale.value === 'en-US' ? {
  title: props.mode === 'order' ? 'Review this order item' : 'Review this product',
  submit: 'Submit review',
  cancel: 'Cancel',
  score: 'Rating',
  star: rating.value === 1 ? 'star' : 'stars',
  placeholder: 'How were the product quality, service and delivery? (up to 500 characters)',
  anonymous: 'Post anonymously (hide buyer identity)',
  submitted: 'Review submitted',
  pickRating: 'Please select a star rating first',
  attach: 'Photos (optional)',
  addImage: 'Add image',
  removeImage: 'Remove',
  uploadFailed: 'Some images failed to upload, please retry'
} : {
  title: props.mode === 'order' ? '评价该订单商品' : '评价商品',
  submit: '提交评价',
  cancel: '取消',
  score: '评分',
  star: '星',
  placeholder: '说说商品质量、服务和物流（最多 500 字）',
  anonymous: '匿名展示（不显示买家标识）',
  submitted: '评价已提交',
  pickRating: '请先选择星级',
  attach: '晒图（可选）',
  addImage: '添加图片',
  removeImage: '移除',
  uploadFailed: '部分图片上传失败，请重试'
})

watch(() => props.open, (v) => {
  if (v) { rating.value = 5; content.value = ''; anonymous.value = false; attachments.value = []; uploadError.value = false }
})

function pickFile() { fileInput.value?.click() }

async function onPick(e) {
  const files = Array.from(e.target.files || [])
  e.target.value = ''
  if (!files.length) return
  const room = 9 - attachments.value.length
  if (files.length > room) message.warning(locale.value === 'en-US'
    ? `Up to 9 images (keeping the first ${room})` : `最多 9 张，已截取前 ${room} 张`)
  uploading.value = true
  uploadError.value = false
  try {
    for (const f of files.slice(0, room)) {
      // 买家侧图片上传复用聊天媒体端点（kind=image，8MB 上限，魔数校验）：
      // attachmentApi 的业务域枚举没有评价域，评价不入 t_attachment 台账
      const res = await chatApi.upload(f, 'image')
      const url = res?.url || res?.data?.url
      if (url) attachments.value.push(String(url))
      else uploadError.value = true
    }
  } catch (e2) {
    uploadError.value = true
  } finally {
    uploading.value = false
  }
}

function removeAttachment(i) { attachments.value.splice(i, 1) }

function shortName(a) {
  const s = String(a)
  const base = s.slice(s.lastIndexOf('/') + 1)
  return base.length > 18 ? base.slice(0, 15) + '...' : base
}

async function submit() {
  if (!props.item) return
  if (rating.value < 1) { message.warning(txt.value.pickRating); return }
  if (uploading.value) return
  submitting.value = true
  try {
    if (props.mode === 'order') {
      await reviewApi.submitOrderReview(props.orderNo, {
        orderItemId: props.item.itemId,
        rating: rating.value,
        content: content.value,
        attachments: attachments.value,
        anonymous: anonymous.value
      })
    } else {
      await reviewApi.submit(props.orderNo, {
        itemId: props.item.itemId,
        rating: rating.value,
        content: content.value,
        anonymous: anonymous.value
      })
    }
    message.success(txt.value.submitted)
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
.rv-attach { margin-top: 12px; }
.rv-attach-head { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.rv-attach-list { display: flex; flex-wrap: wrap; gap: 8px; }
.rv-attach-item {
  display: inline-flex; align-items: center; gap: 4px;
  font-size: 12px; color: #475569; background: #f8fafc;
  border: 1px solid #e2e8f0; border-radius: 9999px; padding: 2px 8px;
}
.rv-attach-del { display: inline-flex; border: none; background: none; cursor: pointer; color: #94a3b8; padding: 0; }
.rv-attach-del:hover { color: #d93025; }
.rv-attach-err { margin: 6px 0 0; font-size: 12px; color: #d93025; }
</style>
