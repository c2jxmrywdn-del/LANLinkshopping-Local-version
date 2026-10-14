<template>
  <div class="page">
    <a-page-header title="优惠详情" sub-title="查看规则与适用范围" @back="$router.push('/promotions')" />
    <a-spin :spinning="loading">
      <a-result v-if="!promotion && !loading" status="404" title="优惠不存在或已结束">
        <template #extra><a-button type="primary" @click="$router.push('/promotions')">返回优惠中心</a-button></template>
      </a-result>
      <template v-else-if="promotion">
        <a-card :bordered="false" class="hero-card">
          <div class="eyebrow">PROMOTION / {{promotion.promoId}}</div>
          <a-tag color="red">{{typeName}}</a-tag>
          <h1>{{promotion.title}}</h1>
          <div class="benefit">{{benefit}}</div>
          <p>{{scopeName}} · {{timeText}}</p>
        </a-card>
        <div class="grid">
          <a-card :bordered="false" class="card" title="优惠规则">
            <a-descriptions :column="1">
              <a-descriptions-item label="优惠类型">{{typeName}}</a-descriptions-item>
              <a-descriptions-item label="使用门槛">{{promotion.threshold ? '满 ¥'+money(promotion.threshold) : '无门槛'}}</a-descriptions-item>
              <a-descriptions-item label="优惠力度">{{benefit}}</a-descriptions-item>
              <a-descriptions-item label="适用范围">{{scopeName}}</a-descriptions-item>
            </a-descriptions>
          </a-card>
          <a-card :bordered="false" class="card" title="使用说明">
            <a-timeline>
              <a-timeline-item>在有效期内进入商城选择商品</a-timeline-item>
              <a-timeline-item>满足门槛与行业范围后，结算系统自动计算优惠</a-timeline-item>
              <a-timeline-item>最终优惠金额以订单结算结果为准</a-timeline-item>
            </a-timeline>
            <a-alert type="info" show-icon message="提示" description="优惠规则由平台统一结算，前端展示仅用于预览，不应作为最终账务依据。" />
          </a-card>
        </div>
        <div class="actions"><a-button type="primary" size="large" @click="$router.push('/mall')">去商城选购</a-button><a-button size="large" @click="$router.push('/promotions')">查看其他优惠</a-button></div>
      </template>
    </a-spin>
  </div>
</template>
<script setup>
import {ref,computed,onMounted} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {promotionApi} from '../api'
const route=useRoute(),router=useRouter(),promotion=ref(null),loading=ref(false)
const money=v=>Number(v||0).toFixed(2),fmt=v=>v?String(v).replace('T',' ').slice(0,16):''
const typeName=computed(()=>promotion.value?.type==='full_reduce'?'满减':'折扣')
const scopeName=computed(()=>promotion.value?.scope==='all'?'全场适用':'指定行业适用')
const benefit=computed(()=>promotion.value?.type==='full_reduce'?'减 ¥'+money(promotion.value?.benefitAmount):(Number(promotion.value?.discountRate||1)*10).toFixed(1)+' 折')
const timeText=computed(()=>fmt(promotion.value?.startTime)+' ～ '+(promotion.value?.endTime?fmt(promotion.value?.endTime):'长期'))
onMounted(async()=>{loading.value=true;try{const list=await promotionApi.list();promotion.value=(list||[]).find(x=>String(x.promoId)===String(route.params.id))||null}finally{loading.value=false}})
</script>
<style scoped>
.page{max-width:980px;margin:0 auto}.hero-card{border-radius:22px;background:var(--ll-brand-hero-gradient);color:#fff;margin-bottom:16px}.eyebrow{font-size:11px;letter-spacing:.16em;opacity:.68}.hero-card h1{color:#fff;font-size:32px;margin:12px 0 4px}.benefit{font-size:38px;font-weight:800;color:#f6d38a;margin:14px 0}.hero-card p{color:rgba(255,255,255,.7);margin:0}.grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}.card{border-radius:18px}.actions{display:flex;justify-content:center;gap:10px;margin:20px 0}@media(max-width:640px){.grid{grid-template-columns:1fr}.hero-card h1{font-size:26px}.actions{flex-direction:column}}
</style>