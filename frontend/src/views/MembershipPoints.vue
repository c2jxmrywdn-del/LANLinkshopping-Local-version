<template>
<div class="sys"><a-page-header title="积分中心" sub-title="积分余额、获取与使用记录" @back="$router.push('/membership')"/>
<div class="stats"><a-card :bordered="false"><span>当前积分</span><b>{{points}}</b></a-card><a-card :bordered="false"><span>可抵扣价值</span><b>¥{{value}}</b></a-card><a-card :bordered="false"><span>成长值</span><b>{{growth}}</b></a-card></div>
<a-card :bordered="false" class="card" title="积分流水"><a-table :data-source="logs" :columns="cols" row-key="logId" :pagination="{pageSize:10}"><template #bodyCell="{column,record}"><template v-if="column.key==='change'"><span :class="Number(record.changeVal)>=0?'plus':'minus'">{{Number(record.changeVal)>=0?'+':''}}{{record.changeVal}}</span></template><template v-else-if="column.key==='time'">{{(record.createTime||'').replace('T',' ')}}</template><template v-else-if="column.key==='detail'"><a-button type="link" size="small" @click="$router.push('/membership/points/'+record.logId)">详情</a-button></template></template></a-table></a-card>
</div></template>
<script setup>
import {ref,computed,onMounted} from 'vue';import {membershipApi} from '../api'
const data=ref(null),logs=ref([]);const points=computed(()=>data.value?.card?.points||0),growth=computed(()=>data.value?.card?.growth||0),value=computed(()=>((Number(points.value)/(data.value?.rule?.redeemPointsPerYuan||1000))).toFixed(2))
const cols=[{title:'变动',key:'change',width:100},{title:'说明',dataIndex:'remark'},{title:'关联订单',dataIndex:'refOrderNo'},{title:'时间',key:'time',width:180},{title:'',key:'detail',width:70}]
onMounted(async()=>{data.value=await membershipApi.my();logs.value=data.value?.pointsLog||[]})
</script>
<style scoped>.sys{max-width:1000px;margin:auto}.stats{display:grid;grid-template-columns:repeat(3,1fr);gap:14px;margin-bottom:16px}.stats span{display:block;color:#667085;font-size:12px}.stats b{display:block;font-size:28px;margin-top:8px;color:var(--ll-navy)}.card{border-radius:14px}.plus{color:#b45309;font-weight:700}.minus{color:#9b2d20;font-weight:700}@media(max-width:640px){.stats{grid-template-columns:1fr}}</style>