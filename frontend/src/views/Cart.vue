<template>
  <div class="cart-page">
    <div class="head"><div><div class="eyebrow">CART / CHECKOUT</div><h2>我的购物车</h2><p>挑选商品后再进入结算，选择状态会自动保存。</p></div><a-button @click="$router.push('/mall')">继续购物</a-button></div>
    <a-card :bordered="false" class="cart-card" v-if="cart.items.length">
      <!-- 满减差额提示条（凑单引导）：/campaign/cart-hint 服务端重算，取差额最小的未达门槛平台满减；无则整条隐藏 -->
      <div v-if="cartHint" class="cart-hint">
        <span class="hint-ico"><BadgePercent :size="16" :stroke-width="2" /></span>
        <span class="hint-text">
          {{ cartHint.campaignTitle ? `再凑 ¥${trimZero(cartHint.gap)} 可减 ¥${trimZero(cartHint.benefitAmount)}（${cartHint.campaignTitle}）` : `再凑 ¥${trimZero(cartHint.gap)} 可减 ¥${trimZero(cartHint.benefitAmount)}` }}
        </span>
        <a-button type="primary" size="small" class="hint-btn" @click="$router.push('/mall')">去凑单</a-button>
      </div>
      <div class="select-bar">
        <a-checkbox :checked="allChecked" :indeterminate="someChecked && !allChecked" @change="e=>cart.setAllChecked(e.target.checked)">全选</a-checkbox>
        <span>共 {{cart.count}} 件商品 · 已选 {{checkedQty}} 件</span>
        <a-button type="link" danger :disabled="!cart.checkedItems.length" @click="removeChecked">删除已选</a-button>
      </div>
      <a-table :data-source="cart.items" :columns="cols" row-key="cartId" :pagination="false" :scroll="{x:760}">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'check'"><a-checkbox :checked="Boolean(record.checked)" @change="e=>cart.setChecked(record.cartId,e.target.checked)"/></template>
          <template v-else-if="column.key === 'prod'">
            <a class="prod-cell" @click="$router.push('/product/' + record.prodId)">
              <span class="prod-thumb ll-img-zoom">
                <img v-if="record.coverUrl" :src="resolveStorageUrl(record.coverUrl)" :alt="record.title" loading="lazy" decoding="async" />
                <span v-else class="prod-thumb-fallback">{{ (record.title || '商').slice(0, 1) }}</span>
              </span>
              <span class="prod-title">{{ record.title }}</span>
            </a>
          </template>
          <template v-else-if="column.key === 'price'">¥{{ Number(record.price||0).toFixed(2) }}</template>
          <template v-else-if="column.key === 'qty'"><a-input-number :value="record.quantity" :min="1" :max="record.stock" @change="v=>cart.setQty(record.cartId,v)"/></template>
          <template v-else-if="column.key === 'sub'"><b>¥{{ Number(record.subtotal||0).toFixed(2) }}</b></template>
          <template v-else-if="column.key === 'op'"><a-popconfirm title="删除这件商品？" ok-text="删除" cancel-text="取消" @confirm="cart.remove(record.cartId)"><a-button type="link" danger size="small">删除</a-button></a-popconfirm></template>
        </template>
      </a-table>
      <div class="summary">
        <div><span>已选 {{checkedQty}} 件</span><small>勾选商品才会进入结算</small></div>
        <div class="summary-right"><span>合计</span><strong>¥{{ Number(cart.total||0).toFixed(2) }}</strong><a-button type="primary" size="large" :disabled="!cart.checkedItems.length" @click="$router.push('/checkout')">去结算</a-button></div>
      </div>
    </a-card>
    <a-empty v-else description="购物车是空的">
      <a-button type="primary" @click="$router.push('/mall')">去逛逛</a-button>
    </a-empty>
  </div>
</template>

<script setup>
import {computed,ref,watch,onMounted} from 'vue'
import {BadgePercent} from 'lucide-vue-next'
import {campaignApi} from '../api'
import {useCartStore} from '../store/cart'
import {resolveStorageUrl} from '../utils/storageUrl'
const cart=useCartStore()
// Pinia 是内存态：F5 刷新或从链接直达 /cart 时 items 还是空数组，
// 不主动拉一次就会把"有货的购物车"渲染成"购物车是空的"，下单链路直接断在这里。
onMounted(()=>{ cart.load().catch(()=>{}) })
const allChecked=computed(()=>cart.items.length>0 && cart.items.every(i=>Boolean(i.checked)))
const someChecked=computed(()=>cart.items.some(i=>Boolean(i.checked)))
const checkedQty=computed(()=>cart.checkedItems.reduce((n,i)=>n+Number(i.quantity||0),0))
const cols=[{title:'',key:'check',width:50},{title:'商品',key:'prod',width:330},{title:'单价',key:'price',width:120},{title:'数量',key:'qty',width:150},{title:'小计',key:'sub',width:130},{title:'操作',key:'op',width:90}]
async function removeChecked(){await Promise.all(cart.checkedItems.map(i=>cart.remove(i.cartId)))}

// ===== 满减差额提示（凑单引导）：有勾选按勾选行、无勾选按全部行取差额最小的未达门槛满减 =====
// 失败/未登录静默隐藏整条，不影响购物车主流程；不新增促销类型、不改变生效口径（只读）
const cartHint=ref(null)
const trimZero=(v)=>Number(v)%1===0?String(Number(v)):String(v)
const hintCartIds=computed(()=>(cart.checkedItems.length?cart.checkedItems:cart.items).map(i=>i.cartId).join(','))
watch(hintCartIds,async(ids)=>{
  if(!ids){cartHint.value=null;return}
  try{cartHint.value=(await campaignApi.cartHint(ids.split(',').map(Number)))||null}
  catch(e){cartHint.value=null}
},{immediate:true})
</script>

<style scoped>
.cart-page{max-width:1080px;margin:0 auto}.head{display:flex;justify-content:space-between;align-items:flex-start;gap:20px;margin-bottom:18px}.eyebrow{font-size:11px;letter-spacing:.16em;color:var(--ll-primary)}h2{margin:5px 0}.head p{margin:0;color:var(--ll-muted)}.cart-card{border-radius:var(--ll-radius-xl,18px)}
/* 满减差额提示条：浅天蓝渐变 + 金色图标，与全站小清新蓝语系一致 */
.cart-hint{display:flex;align-items:center;gap:10px;margin-bottom:14px;padding:10px 14px;border-radius:12px;border:1px solid rgba(30,110,184,.22);background:linear-gradient(100deg,rgba(30,110,184,.08),rgba(14,165,233,.05))}
.hint-ico{display:grid;place-items:center;width:28px;height:28px;border-radius:9px;color:#1E6EB8;background:rgba(30,110,184,.12);flex-shrink:0}
.hint-text{flex:1;min-width:0;font-size:13px;color:var(--ll-ink)}
.hint-btn{border-radius:999px}
.select-bar{display:flex;align-items:center;gap:16px;padding:0 0 14px;border-bottom:1px solid var(--ll-border,#e5e7eb);color:var(--ll-muted);font-size:13px}.select-bar span{flex:1}
/* 商品单元格：缩略图 + 标题，悬停图片轻微缩放 */
.prod-cell{display:flex;align-items:center;gap:12px}
.prod-thumb{flex:0 0 56px;width:56px;height:56px;border-radius:var(--ll-radius-sm,8px);background:var(--ll-thumb-bg,#eef1f6);display:grid;place-items:center;overflow:hidden}
.prod-thumb img{width:100%;height:100%;object-fit:cover}
.prod-thumb-fallback{color:var(--ll-thumb-fg,#b9c3d4);font-size:20px;font-weight:700}
.prod-title{color:var(--ll-ink);font-weight:600;transition:color .2s ease}
.prod-cell:hover .prod-title{color:var(--ll-primary)}
.summary{display:flex;justify-content:space-between;align-items:center;gap:20px;margin-top:18px;padding:16px 0 2px;border-top:1px solid var(--ll-border,#eceae5)}.summary small{display:block;color:var(--ll-muted);margin-top:3px}.summary-right{display:flex;align-items:center;gap:14px}.summary-right strong{font-size:25px;color:var(--ll-price,#9b2d20);letter-spacing:-.02em}
@media(max-width:640px){.head{flex-direction:column}.select-bar{align-items:flex-start}.cart-hint{flex-wrap:wrap}.hint-text{flex-basis:100%;order:3}.summary{flex-direction:column;align-items:stretch}.summary-right{justify-content:space-between}.prod-thumb{flex-basis:44px;width:44px;height:44px}}
</style>
