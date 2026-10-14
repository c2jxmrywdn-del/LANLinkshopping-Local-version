<template>
  <div class="cart-page">
    <div class="head"><div><div class="eyebrow">CART / CHECKOUT</div><h2>我的购物车</h2><p>挑选商品后再进入结算，选择状态会自动保存。</p></div><a-button @click="$router.push('/mall')">继续购物</a-button></div>
    <a-card :bordered="false" class="cart-card" v-if="cart.items.length">
      <div class="select-bar">
        <a-checkbox :checked="allChecked" :indeterminate="someChecked && !allChecked" @change="e=>cart.setAllChecked(e.target.checked)">全选</a-checkbox>
        <span>共 {{cart.items.length}} 件商品 · 已选 {{cart.checkedItems.length}} 件</span>
        <a-button type="link" danger :disabled="!cart.checkedItems.length" @click="removeChecked">删除已选</a-button>
      </div>
      <a-table :data-source="cart.items" :columns="cols" row-key="cartId" :pagination="false" :scroll="{x:760}">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'check'"><a-checkbox :checked="Boolean(record.checked)" @change="e=>cart.setChecked(record.cartId,e.target.checked)"/></template>
          <template v-else-if="column.key === 'prod'"><a @click="$router.push('/product/' + record.prodId)"><span class="prod-title">{{ record.title }}</span></a></template>
          <template v-else-if="column.key === 'price'">¥{{ Number(record.price||0).toFixed(2) }}</template>
          <template v-else-if="column.key === 'qty'"><a-input-number :value="record.quantity" :min="1" :max="record.stock" @change="v=>cart.setQty(record.cartId,v)"/></template>
          <template v-else-if="column.key === 'sub'"><b>¥{{ Number(record.subtotal||0).toFixed(2) }}</b></template>
          <template v-else-if="column.key === 'op'"><a-popconfirm title="删除这件商品？" ok-text="删除" cancel-text="取消" @confirm="cart.remove(record.cartId)"><a-button type="link" danger size="small">删除</a-button></a-popconfirm></template>
        </template>
      </a-table>
      <div class="summary">
        <div><span>已选 {{cart.checkedItems.length}} 件</span><small>勾选商品才会进入结算</small></div>
        <div class="summary-right"><span>合计</span><strong>¥{{ Number(cart.total||0).toFixed(2) }}</strong><a-button type="primary" size="large" :disabled="!cart.checkedItems.length" @click="$router.push('/checkout')">去结算</a-button></div>
      </div>
    </a-card>
    <a-empty v-else description="购物车是空的">
      <a-button type="primary" @click="$router.push('/mall')">去逛逛</a-button>
    </a-empty>
  </div>
</template>

<script setup>
import {computed,onMounted} from 'vue'
import {useCartStore} from '../store/cart'
const cart=useCartStore()
const allChecked=computed(()=>cart.items.length>0 && cart.items.every(i=>Boolean(i.checked)))
const someChecked=computed(()=>cart.items.some(i=>Boolean(i.checked)))
const cols=[{title:'',key:'check',width:50},{title:'商品',key:'prod',width:330},{title:'单价',key:'price',width:120},{title:'数量',key:'qty',width:150},{title:'小计',key:'sub',width:130},{title:'操作',key:'op',width:90}]
async function removeChecked(){await Promise.all(cart.checkedItems.map(i=>cart.remove(i.cartId)))}
onMounted(()=>cart.load())
</script>

<style scoped>
.cart-page{max-width:1080px;margin:0 auto}.head{display:flex;justify-content:space-between;align-items:flex-start;gap:20px;margin-bottom:18px}.eyebrow{font-size:11px;letter-spacing:.16em;color:var(--ll-primary)}h2{margin:5px 0}.head p{margin:0;color:var(--ll-muted)}.cart-card{border-radius:18px}.select-bar{display:flex;align-items:center;gap:16px;padding:0 0 14px;border-bottom:1px solid var(--ll-border,#e5e7eb);color:var(--ll-muted);font-size:13px}.select-bar span{flex:1}.prod-title{color:var(--ll-ink);font-weight:600}.summary{display:flex;justify-content:space-between;align-items:center;gap:20px;margin-top:18px;padding:16px 0 2px;border-top:1px solid #eceae5}.summary small{display:block;color:var(--ll-muted);margin-top:3px}.summary-right{display:flex;align-items:center;gap:14px}.summary-right strong{font-size:25px;color:#9b2d20}@media(max-width:640px){.head{flex-direction:column}.select-bar{align-items:flex-start}.summary{flex-direction:column;align-items:stretch}.summary-right{justify-content:space-between}}
</style>