<template>
<div class="sys"><a-page-header title="会员等级" sub-title="查看成长体系与等级权益" @back="$router.push('/membership')"/>
<a-card :bordered="false" class="card"><a-steps :current="currentIndex" size="small" responsive><a-step v-for="l in levels" :key="l.levelId" :title="l.levelName" :description="l.minGrowth + ' 成长值'"/></a-steps></a-card>
<a-card :bordered="false" class="card" title="完整等级权益"><a-table :data-source="levels" :columns="cols" row-key="levelId" :pagination="false"><template #bodyCell="{column,record}"><template v-if="column.key==='rate'">{{ discount(record.discountRate) }}</template><template v-if="column.key==='current'"><a-tag v-if="record.levelId===data?.level?.levelId" color="gold">当前等级</a-tag></template></template></a-table></a-card></div>
</template>
<script setup>
import {ref,onMounted,computed} from 'vue';import {membershipApi} from '../api'
const data=ref(null),levels=ref([])
const cols=[{title:'等级',dataIndex:'levelName'},{title:'成长值门槛',dataIndex:'minGrowth'},{title:'权益说明',dataIndex:'description'},{title:'等级折扣',key:'rate'},{title:'状态',key:'current'}]
const currentIndex=computed(()=>Math.max(0,levels.value.findIndex(x=>x.levelId===data.value?.level?.levelId)))
const discount=r=>!r||Number(r)>=1?'无折扣':(Number(r)*10).toFixed(1)+' 折'
onMounted(async()=>{data.value=await membershipApi.my();levels.value=data.value?.levels||[]})
</script>
<style scoped>.sys{max-width:1000px;margin:auto}.card{border-radius:14px;margin-bottom:16px}</style>