<template>
  <div class="page">
    <div class="head"><div><div class="eyebrow">ACTIVITY / MY</div><h1>我的活动</h1><p>查看参与记录、奖励积分与活动状态。</p></div><a-button @click="$router.push('/activity')">活动广场</a-button></div>
    <a-card :bordered="false" class="card">
      <a-list :data-source="items" :pagination="{pageSize:10}">
        <template #renderItem="{item}">
          <a-list-item>
            <a-list-item-meta :title="titles[item.activityId] || ('活动 #'+item.activityId)" :description="'参与时间：'+String(item.joinTime||'').replace('T',' ')"/>
            <template #extra>
              <div class="extra"><a-tag color="gold">+{{item.bonusPoints||0}} 积分</a-tag><a-button type="link" size="small" @click="$router.push('/activity/'+item.activityId)">查看活动</a-button></div>
            </template>
          </a-list-item>
        </template>
      </a-list>
      <a-empty v-if="!items.length" description="还没有参加活动"><template #description>还没有参加活动，去活动广场看看</template><a-button type="primary" @click="$router.push('/activity')">发现活动</a-button></a-empty>
    </a-card>
  </div>
</template>
<script setup>
import {ref,onMounted} from 'vue'
import {activityApi} from '../api'
const items=ref([]),titles=ref({})
onMounted(async()=>{try{const [mine,list]=await Promise.all([activityApi.my(),activityApi.list()]);items.value=mine||[];titles.value=Object.fromEntries((list||[]).map(x=>[x.activityId,x.title]))}catch(e){}})
</script>
<style scoped>
.page{max-width:900px;margin:0 auto}.head{display:flex;justify-content:space-between;gap:18px;margin-bottom:18px}.eyebrow{font-size:11px;letter-spacing:.16em;color:var(--ll-primary)}h1{margin:4px 0}.head p{color:var(--ll-muted);margin:0}.card{border-radius:18px}.extra{display:flex;align-items:center;gap:4px}
@media(max-width:560px){.head{flex-direction:column}.extra{flex-direction:column;align-items:flex-end}}
</style>