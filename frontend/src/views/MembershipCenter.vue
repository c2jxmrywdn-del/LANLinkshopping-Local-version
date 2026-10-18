<template><div class="sys">
  <div class="hero"><div><div class="eyebrow membership-eyebrow"><MembershipIcon :size="30" label="会员图标" /><span>LANLINK MEMBERSHIP</span></div><h1>会员中心</h1><p>围绕等级、成长、积分与权益建立完整会员闭环。</p></div><div class="actions"><a-button type="primary" @click="$router.push('/membership/points')">积分中心</a-button><a-button @click="$router.push('/membership/benefits')">我的权益</a-button></div></div>
  <a-card v-if="data" :bordered="false" class="member"><div><div class="level">{{data.level?.levelName||'普通会员'}}</div><div class="meta">成长值 {{data.card?.growth||0}} · 积分 {{data.card?.points||0}}</div></div><div class="discount">{{rate}}<small>等级折扣</small></div><div class="progress"><div :style="{width:growthPct}"></div></div><span class="hint">{{nextHint}}</span></a-card>
  <!-- 勋章墙：等级维度（后端等级映射）+ 成就维度（成长/积分里程碑），获取/升级动效由 MembershipBadge 承担 -->
  <section v-if="data" class="badge-wall">
    <div class="wall-head"><h2>我的勋章</h2><span class="wall-sub">等级勋章随成长值解锁 · 成就勋章记录里程碑</span></div>
    <div class="wall-grid">
      <MembershipBadge v-for="(m, i) in levelMedals" :key="'lv' + m.tier"
        :tier="m.tier" :eyebrow="m.eyebrow" :title="m.title" :state="m.state"
        :delay="i * 90" :burst="m.state !== 'locked'" @detail="openDetail(m)" />
    </div>
    <div class="wall-head wall-head--sub"><h2>成就勋章</h2><span class="wall-sub">消费 · 积分 · 忠诚多维成就</span></div>
    <div class="wall-grid">
      <MembershipBadge v-for="(m, i) in achMedals" :key="'ac' + m.title"
        :tier="m.tier" :eyebrow="m.eyebrow" :title="m.title" :state="m.state"
        :delay="400 + i * 90" :burst="m.state !== 'locked'" @detail="openDetail(m)" />
    </div>
  </section>
  <!-- 勋章详情弹窗：大号徽章 + 状态/条件/进度/说明 -->
  <a-modal v-model:open="detailOpen" :footer="null" centered width="440px" class="medal-modal">
    <template #title><span class="modal-title">勋章详情</span></template>
    <div v-if="detail" class="medal-detail">
      <MembershipBadge :tier="detail.tier" :eyebrow="detail.eyebrow" :title="detail.title"
        :state="detail.state" size="lg" :burst="detailOpen" />
      <div class="md-rows">
        <div class="md-row"><span>状态</span><b :class="{ 'md-on': detail.state !== 'locked' }">{{ detail.state === 'locked' ? '未解锁' : detail.state === 'current' ? '当前等级' : '已获得' }}</b></div>
        <div class="md-row"><span>解锁条件</span><b>{{ detail.req }}</b></div>
        <div class="md-row md-row--col"><span>当前进度</span>
          <a-progress :percent="Math.round(detail.progress * 100)" :status="detail.state === 'locked' ? 'normal' : 'success'" size="small" />
        </div>
        <div class="md-row md-row--col"><span>勋章说明</span><p>{{ detail.desc }}</p></div>
      </div>
    </div>
  </a-modal>
  <div class="grid"><a-card v-for="x in cards" :key="x.title" :bordered="false" class="nav-card" @click="$router.push(x.to)"><div class="num">{{x.num}}</div><h3>{{x.title}}</h3><p>{{x.desc}}</p><span>进入 →</span></a-card></div>
  <a-card :bordered="false" class="levels" title="等级速览"><a-steps :current="currentIndex" responsive size="small"><a-step v-for="l in (data?.levels||[])" :key="l.levelId" :title="l.levelName" :description="l.minGrowth+' 成长值'"/></a-steps></a-card>
</div></template>
<script setup>
import {ref,computed,onMounted} from 'vue';import {membershipApi} from '../api';import MembershipIcon from '../components/ui/MembershipIcon.vue'
import MembershipBadge from '../components/ui/MembershipBadge.vue'
const data=ref(null);const cards=[{num:'01',title:'等级权益',desc:'成长值门槛、等级折扣与体系说明',to:'/membership/levels'},{num:'02',title:'积分中心',desc:'积分余额、获取、抵现与流水',to:'/membership/points'},{num:'03',title:'会员权益',desc:'折扣、积分、成长值等权益说明',to:'/membership/benefits'}];const rate=computed(()=>{const r=Number(data.value?.level?.discountRate||1);return r>=1?'无折扣':(r*10).toFixed(1)+' 折'});const currentIndex=computed(()=>Math.max(0,(data.value?.levels||[]).findIndex(x=>x.levelId===data.value?.level?.levelId)));const growthPct=computed(()=>{const ls=data.value?.levels||[],cur=data.value?.level?.minGrowth||0,n=ls.find(x=>x.minGrowth>cur);if(!n)return '100%';const got=(data.value?.card?.growth||0)-cur;return Math.max(5,Math.min(100,got/(n.minGrowth-cur)*100))+'%'});const nextHint=computed(()=>{const ls=data.value?.levels||[],cur=data.value?.level?.minGrowth||0,n=ls.find(x=>x.minGrowth>cur);return n?'距离 '+n.levelName+' 还需成长值 '+Math.max(0,n.minGrowth-(data.value?.card?.growth||0)):'已达最高等级'});
// ===== 勋章体系：等级维度（levelId→tier 映射）+ 成就维度（成长/积分里程碑阈值） =====
const TIER_BY_LEVEL={1:'regular',2:'silver',3:'gold',4:'diamond'};
const levelMedals=computed(()=>{const cur=data.value?.level?.levelId||1;const g=data.value?.card?.growth||0;return (data.value?.levels||[]).map(l=>({tier:TIER_BY_LEVEL[l.levelId]||'regular',eyebrow:'LEVEL '+l.levelId,title:l.levelName,state:l.levelId===cur?'current':(l.levelId<cur?'earned':'locked'),req:'成长值 ≥ '+l.minGrowth,desc:l.description||'等级勋章：随成长值累积自动晋升。',progress:l.minGrowth<=0?1:Math.min(1,g/l.minGrowth)}))});
const achMedals=computed(()=>{const g=data.value?.card?.growth||0,p=data.value?.card?.points||0;return [
 {tier:'azure',eyebrow:'GROWTH · 成长',title:'初出茅庐',state:g>=100?'earned':'locked',req:'成长值 ≥ 100',desc:'完成早期成长积累，迈出会员体系第一步。',progress:Math.min(1,g/100)},
 {tier:'regular',eyebrow:'POINTS · 积分',title:'积分达人',state:p>=1000?'earned':'locked',req:'积分 ≥ 1000',desc:'积分余额突破千位，可享积分抵现等玩法。',progress:Math.min(1,p/1000)},
 {tier:'gold',eyebrow:'MILESTONE · 里程碑',title:'成长先锋',state:g>=5000?'earned':'locked',req:'成长值 ≥ 5000',desc:'成长值达到黄金会员门槛级别的里程碑成就。',progress:Math.min(1,g/5000)},
 {tier:'diamond',eyebrow:'LOYALTY · 忠诚',title:'至尊挚友',state:g>=20000?'earned':'locked',req:'成长值 ≥ 20000',desc:'钻石级忠诚成就，平台长期深度伙伴象征。',progress:Math.min(1,g/20000)}]});
// ===== 勋章详情弹窗 =====
const detailOpen=ref(false);const detail=ref(null);
function openDetail(m){detail.value=m;detailOpen.value=true}
onMounted(async()=>{try{data.value=await membershipApi.my()}catch(e){}})</script>
<style scoped>
.sys{max-width:1040px;margin:0 auto}.hero{display:flex;justify-content:space-between;gap:20px;align-items:flex-start;margin-bottom:20px}.eyebrow{font-size:11px;letter-spacing:.18em;color:var(--ll-primary)}.membership-eyebrow{display:flex;align-items:center;gap:9px}h1{margin:5px 0;font-size:30px}.hero p{margin:0;color:var(--ll-muted)}.actions{display:flex;gap:8px}.member{border-radius:22px;background:var(--ll-brand-hero-gradient);color:#fff;margin-bottom:16px;position:relative;overflow:hidden}.level{font-size:28px;font-weight:800}.meta,.hint{opacity:.72}.discount{position:absolute;right:26px;top:24px;text-align:right;font-size:24px;font-weight:800}.discount small{display:block;font-size:11px;font-weight:400;opacity:.65}.progress{height:6px;border-radius:99px;background:rgba(255,255,255,.14);margin:22px 0 8px;overflow:hidden}.progress div{height:100%;background:#f6d38a;border-radius:99px}.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:14px;margin-bottom:16px}.nav-card,.levels{border-radius:18px}.nav-card{cursor:pointer;transition:transform .2s,box-shadow .2s}.nav-card:hover{transform:translateY(-3px);box-shadow:0 16px 40px rgba(19,35,58,.08)}.num{font-size:11px;color:#9b2d20;letter-spacing:.14em}.nav-card h3{margin:8px 0 4px}.nav-card p{color:var(--ll-muted);font-size:12px}.nav-card span{color:var(--ll-primary);font-size:12px}
/* 勋章墙：胶囊比例（260:54），自适应列宽，窄屏单列 */
.badge-wall{margin:4px 0 18px}.wall-head{display:flex;align-items:baseline;gap:10px;margin:0 0 10px}.wall-head h2{margin:0;font-size:17px;font-weight:800;color:var(--ll-ink)}.wall-head--sub{margin-top:16px}.wall-sub{font-size:12px;color:var(--ll-muted)}
.wall-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:14px 12px;align-items:start}
@media(max-width:620px){.wall-grid{grid-template-columns:1fr}}
/* 详情弹窗 */
.modal-title{font-weight:800}
.medal-detail{display:flex;flex-direction:column;gap:16px}
.md-rows{display:flex;flex-direction:column;gap:10px}
.md-row{display:flex;align-items:center;gap:12px;font-size:13px}
.md-row span{flex:0 0 64px;color:var(--ll-muted)}
.md-row b{font-weight:700;color:var(--ll-ink)}
.md-row b.md-on{color:var(--ll-primary)}
.md-row--col{flex-direction:column;align-items:stretch;gap:6px}
.md-row--col p{margin:0;color:var(--ll-gray,#4b5563);line-height:1.7;font-size:13px}
@media(max-width:800px){.grid{grid-template-columns:1fr 1fr}}@media(max-width:560px){.hero{flex-direction:column}.grid{grid-template-columns:1fr}.discount{position:static;text-align:left;margin-top:12px}}
</style>
