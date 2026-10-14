<template>
  <div class="traffic-page">
    <section class="hero">
      <div class="hero-copy">
        <div class="eyebrow"><span class="dot"></span> 商户经营数据中心</div>
        <h1>流量管理</h1>
        <p>从流量来源到成交转化，一屏掌握店铺增长机会。</p>
      </div>
      <div class="hero-actions">
        <a-radio-group v-model:value="days" button-style="solid" @change="reload">
          <a-radio-button :value="7">近 7 天</a-radio-button>
          <a-radio-button :value="30">近 30 天</a-radio-button>
          <a-radio-button :value="90">近 90 天</a-radio-button>
        </a-radio-group>
        <a-button class="refresh" @click="reload"><span class="refresh-icon">↻</span> 刷新数据</a-button>
      </div>
    </section>

    <section class="kpi-grid">
      <div v-for="(m,i) in metrics" :key="m.label" class="kpi-card" :class="'tone-'+i">
        <div class="kpi-top"><span>{{m.label}}</span><span class="kpi-icon">{{m.icon}}</span></div>
        <div class="kpi-value">{{m.value}}</div>
        <div class="kpi-foot" :class="m.cls">{{m.sub}}</div>
      </div>
    </section>

    <a-tabs v-model:activeKey="tab" class="traffic-tabs">
      <a-tab-pane key="overview" tab="经营总览">
        <div class="grid-main">
          <a-card :bordered="false" class="panel trend-panel">
            <template #title><div class="panel-title"><div><strong>销售趋势</strong><small>支付金额与订单走势</small></div><span class="period">{{days}}D</span></div></template>
            <div v-if="trend.length" class="chart">
              <div class="grid-line line-1"></div><div class="grid-line line-2"></div><div class="grid-line line-3"></div>
              <div class="bars">
                <div v-for="d in trend" :key="d.date" class="bar-wrap" :title="`${d.date} ¥${fmt(d.amount)} / ${d.orders}单`">
                  <div class="bar" :style="{height:barHeight(d)}"></div>
                </div>
              </div>
            </div>
            <a-empty v-else :image="aEmpty.PRESENTED_IMAGE_SIMPLE" description="暂无趋势数据"/>
            <div class="axis"><span>{{trend[0]?.date || '—'}}</span><span>{{trend[Math.max(0,trend.length-1)]?.date || '—'}}</span></div>
          </a-card>

          <a-card :bordered="false" class="panel opportunity">
            <template #title><div class="panel-title"><div><strong>增长机会</strong><small>基于当前转化表现</small></div></div></template>
            <div class="opportunity-score"><span>经营健康度</span><strong>{{healthScore}}<em>/100</em></strong></div>
            <div class="score-track"><i :style="{width:healthScore+'%'}"></i></div>
            <div class="opportunity-item" v-for="d in diagnosis.slice(0,3)" :key="d.code">
              <span class="status" :class="d.level"></span><div><b>{{d.title}}</b><p>{{d.suggestion}}</p></div>
            </div>
            <div v-if="!diagnosis.length" class="healthy"><span>✓</span> 当前没有明显流量异常</div>
          </a-card>
        </div>

        <div class="grid-two">
          <a-card :bordered="false" class="panel">
            <template #title><div class="panel-title"><div><strong>流量来源</strong><small>按访客贡献排序</small></div><a-button type="link" @click="tab='sources'">查看全部 →</a-button></div></template>
            <div class="source-rank">
              <div v-for="(r,i) in sources.slice(0,5)" :key="r.source" class="source-row">
                <span class="rank">{{String(i+1).padStart(2,'0')}}</span><div class="source-name"><b>{{r.source}}</b><i><span :style="{width:sourceWidth(r.uv)+'%'}"></span></i></div><strong>{{r.uv||0}}</strong><small>UV</small>
              </div>
            </div>
            <a-empty v-if="!sources.length" :image="aEmpty.PRESENTED_IMAGE_SIMPLE" description="暂无来源数据"/>
          </a-card>

          <a-card :bordered="false" class="panel">
            <template #title><div class="panel-title"><div><strong>转化漏斗</strong><small>访客到支付的关键路径</small></div><a-button type="link" @click="tab='conversion'">深入分析 →</a-button></div></template>
            <div class="funnel-modern">
              <div v-for="(x,i) in funnelItems" :key="x.label" class="funnel-step">
                <div class="funnel-label"><span>{{x.label}}</span><b>{{x.value}}</b></div>
                <div class="funnel-track"><i :style="{width:x.width+'%'}"></i></div>
                <small v-if="i>0">{{x.rate}}% 环节转化</small>
              </div>
            </div>
          </a-card>
        </div>
      </a-tab-pane>

      <a-tab-pane key="sources" tab="流量来源">
        <a-card :bordered="false" class="panel table-panel">
          <template #title><div class="panel-title"><div><strong>来源效果</strong><small>不同渠道带来的访客与成交表现</small></div></div></template>
          <a-table :data-source="sources" :pagination="{pageSize:10,showSizeChanger:false}" row-key="source" :columns="sourceCols"/>
        </a-card>
      </a-tab-pane>

      <a-tab-pane key="products" tab="商品流量">
        <a-card :bordered="false" class="panel table-panel">
          <template #title><div class="panel-title"><div><strong>商品流量表现</strong><small>识别高流量、高意向商品</small></div></div></template>
          <a-table :data-source="products" :pagination="{pageSize:10,showSizeChanger:false}" row-key="prodId" :columns="productCols"/>
        </a-card>
      </a-tab-pane>

      <a-tab-pane key="conversion" tab="转化分析">
        <a-card :bordered="false" class="panel table-panel">
          <template #title><div class="panel-title"><div><strong>商品转化分析</strong><small>找到“浏览高、成交低”的优化对象</small></div></div></template>
          <a-table :data-source="conversion.products" row-key="prodId" :pagination="{pageSize:10,showSizeChanger:false}" :columns="funnelCols"/>
        </a-card>
      </a-tab-pane>

      <a-tab-pane key="diagnosis" tab="流量诊断">
        <div class="diagnosis-grid">
          <div v-for="d in diagnosis" :key="d.code" class="diagnosis-card" :class="d.level">
            <div class="diagnosis-head"><span>{{d.level==='success'?'✓':'!'}}</span><b>{{d.title}}</b></div>
            <p>{{d.suggestion}}</p><a-button v-if="d.level!=='success'" type="link">查看建议 →</a-button>
          </div>
          <div v-if="!diagnosis.length" class="empty-diagnosis">暂无诊断结果</div>
        </div>
      </a-tab-pane>
    </a-tabs>
  </div>
</template>

<script setup>
import {ref,computed,onMounted} from 'vue'
import {trafficApi} from '../api'
import { Empty } from 'ant-design-vue'
const aEmpty=Empty
const tab=ref('overview'),days=ref(30),ov=ref({}),trend=ref([]),sources=ref([]),products=ref([]),conversion=ref({products:[]}),diagnosis=ref([])
const fmt=n=>Number(n||0).toFixed(2)
const metrics=computed(()=>[
 {label:'近30天销售额',value:'¥'+fmt(ov.value.recentAmount),sub:ov.value.chainGrowthPct==null?'环比暂无数据':`环比 ${Number(ov.value.chainGrowthPct)>=0?'↑':'↓'} ${Math.abs(Number(ov.value.chainGrowthPct)).toFixed(1)}%`,cls:Number(ov.value.chainGrowthPct)>=0?'up':'down',icon:'¥'},
 {label:'近30天订单',value:ov.value.recentOrders||0,sub:'已支付订单',icon:'↗'},
 {label:'客单价',value:'¥'+fmt(ov.value.avgOrderValue),sub:'近30天平均',icon:'◆'},
 {label:'动销率',value:(ov.value.activeRatePct||0)+'%',sub:`动销 ${ov.value.activeProducts||0} / 在售 ${ov.value.onSaleProducts||0}`,icon:'◈'},
 {label:'累计销售额',value:'¥'+fmt(ov.value.totalAmount),sub:'历史已支付',icon:'◷'},
 {label:'累计销量',value:ov.value.totalSold||0,sub:'累计成交件数',icon:'▦'}
])
const sourceCols=[{title:'来源',dataIndex:'source'},{title:'访客 UV',dataIndex:'uv'},{title:'商品浏览',dataIndex:'productViews'},{title:'加购',dataIndex:'carts'},{title:'支付',dataIndex:'pays'}]
const productCols=[{title:'商品',dataIndex:'title'},{title:'浏览 UV',dataIndex:'views'},{title:'加购 UV',dataIndex:'carts'},{title:'支付 UV',dataIndex:'pays'}]
const funnelCols=[{title:'商品',dataIndex:'title'},{title:'浏览 UV',dataIndex:'views'},{title:'加购 UV',dataIndex:'carts'},{title:'支付 UV',dataIndex:'pays'}]
const maxAmount=computed(()=>Math.max(...trend.value.map(x=>Number(x.amount||0)),.01))
const maxUv=computed(()=>Math.max(...sources.value.map(x=>Number(x.uv||0)),1))
const barHeight=d=>Math.max(5,Number(d.amount||0)/maxAmount.value*100)+'%'
const sourceWidth=v=>Math.max(6,Number(v||0)/maxUv.value*100)
const healthScore=computed(()=>Math.max(62,Math.min(98,100-(diagnosis.value.filter(x=>x.level!=='success').length*9))))
const funnelItems=computed(()=>{const c=conversion.value||{};const base=Number(c.visits||0)||1;return [{label:'访问',value:c.visits||0,width:100,rate:'—'},{label:'商品浏览',value:c.views||0,width:Math.max(12,Number(c.views||0)/base*100),rate:c.viewRate||0},{label:'加购',value:c.carts||0,width:Math.max(8,Number(c.carts||0)/base*100),rate:c.cartRate||0},{label:'支付',value:c.pays||0,width:Math.max(5,Number(c.pays||0)/base*100),rate:c.payRate||0}]})
async function reload(){const d=days.value;try{const [o,t,s,p,c,di]=await Promise.all([trafficApi.overview(),trafficApi.trend(d),trafficApi.sources(d),trafficApi.products(d),trafficApi.conversion(d),trafficApi.diagnosis(d)]);ov.value=o||{};trend.value=t||[];sources.value=s||[];products.value=p||[];conversion.value=c||{products:[]};diagnosis.value=di||[]}catch(e){}}
onMounted(reload)
</script>

<style scoped>
.traffic-page{max-width:1280px;margin:0 auto;padding:8px 4px 40px;color:#172033}
.hero{display:flex;justify-content:space-between;align-items:flex-end;padding:24px 4px 22px}.eyebrow{font-size:12px;color:#6b7280;margin-bottom:7px;letter-spacing:.04em}.dot{display:inline-block;width:7px;height:7px;border-radius:50%;background:#9b2d20;margin-right:7px}.hero h1{font-size:30px;line-height:1.15;margin:0;font-weight:750;letter-spacing:-.03em}.hero p{margin:8px 0 0;color:#7a8495;font-size:14px}.hero-actions{display:flex;gap:10px;align-items:center}.refresh{height:34px;border-radius:8px}.refresh-icon{font-size:17px;margin-right:5px}
.kpi-grid{display:grid;grid-template-columns:repeat(6,1fr);gap:12px;margin-bottom:18px}.kpi-card{position:relative;background:#fff;border:1px solid #e9e7e1;border-radius:14px;padding:17px 18px;min-height:112px;box-shadow:0 4px 18px rgba(30,35,45,.035);overflow:hidden}.kpi-card:after{content:"";position:absolute;right:-22px;bottom:-26px;width:72px;height:72px;border-radius:50%;background:#f5f0e8}.kpi-top{display:flex;justify-content:space-between;color:#737d8e;font-size:12px}.kpi-icon{color:#9b2d20;font-size:16px}.kpi-value{font-size:23px;font-weight:750;margin:12px 0 8px;letter-spacing:-.02em}.kpi-foot{font-size:11px;color:#7b8491}.up{color:#16805a}.down{color:#b43b2f}
.traffic-tabs :deep(.ant-tabs-nav){margin-bottom:16px}.traffic-tabs :deep(.ant-tabs-tab){padding:9px 2px;margin-right:28px;font-size:14px}.traffic-tabs :deep(.ant-tabs-ink-bar){background:#9b2d20}
.grid-main{display:grid;grid-template-columns:1.75fr 1fr;gap:16px;margin-bottom:16px}.grid-two{display:grid;grid-template-columns:1fr 1fr;gap:16px}.panel{border-radius:14px;border:1px solid #ebe8e2;box-shadow:0 5px 20px rgba(30,35,45,.035);height:100%}.panel :deep(.ant-card-head){border-bottom:1px solid #f0eee9;padding:0 20px;min-height:62px}.panel :deep(.ant-card-body){padding:20px}.panel-title{display:flex;align-items:center;justify-content:space-between;width:100%}.panel-title strong{display:block;font-size:15px;color:#1d2737}.panel-title small{display:block;color:#9299a5;font-size:11px;font-weight:400;margin-top:3px}.period{font-size:11px;color:#9b2d20;background:#fbf4ef;border-radius:20px;padding:4px 8px}
.chart{height:205px;position:relative;padding:0 2px}.grid-line{position:absolute;left:0;right:0;border-top:1px dashed #ebe9e5}.line-1{top:25%}.line-2{top:50%}.line-3{top:75%}.bars{height:100%;display:flex;align-items:flex-end;gap:4px;position:relative;z-index:1}.bar-wrap{flex:1;height:100%;display:flex;align-items:flex-end;min-width:2px}.bar{width:100%;min-height:4px;background:linear-gradient(180deg,#b64a39,#9b2d20);border-radius:3px 3px 0 0;opacity:.9;transition:height .25s,opacity .2s}.bar-wrap:hover .bar{opacity:1}.axis{display:flex;justify-content:space-between;color:#a0a6b0;font-size:10px;margin-top:8px}
.opportunity-score{display:flex;align-items:flex-end;justify-content:space-between}.opportunity-score span{font-size:12px;color:#7e8795}.opportunity-score strong{font-size:32px;letter-spacing:-.04em}.opportunity-score em{font-size:11px;color:#a0a6b0;font-style:normal;font-weight:400}.score-track{height:6px;background:#f0ede8;border-radius:99px;margin:10px 0 18px;overflow:hidden}.score-track i{display:block;height:100%;background:#9b2d20;border-radius:99px}.opportunity-item{display:flex;gap:10px;padding:9px 0;border-top:1px solid #f1efeb}.opportunity-item b{font-size:12px}.opportunity-item p{margin:3px 0 0;color:#9299a5;font-size:11px;line-height:1.5}.status{width:7px;height:7px;border-radius:50%;margin-top:5px;background:#c98b2b}.status.success{background:#16805a}.status.info{background:#78879a}
.source-rank{display:flex;flex-direction:column;gap:14px}.source-row{display:grid;grid-template-columns:28px 1fr 48px 24px;align-items:center;gap:8px}.rank{font-size:11px;color:#a4a9b2}.source-name b{font-size:12px;font-weight:600}.source-name i{display:block;height:4px;background:#f0eee9;border-radius:4px;margin-top:6px;overflow:hidden}.source-name i span{display:block;height:100%;background:#c8a45c;border-radius:4px}.source-row strong{text-align:right;font-size:13px}.source-row small{color:#a0a6b0;font-size:10px}
.funnel-modern{padding-top:2px}.funnel-step{margin-bottom:13px}.funnel-label{display:flex;justify-content:space-between;font-size:12px}.funnel-label b{font-size:13px}.funnel-track{height:9px;background:#f1eee9;border-radius:99px;margin-top:6px;overflow:hidden}.funnel-track i{display:block;height:100%;background:#13233a;border-radius:99px}.funnel-step:last-child .funnel-track i{background:#9b2d20}.funnel-step small{color:#a0a6b0;font-size:10px;display:block;margin-top:4px}
.table-panel{margin-bottom:10px}.table-panel :deep(.ant-table-thead > tr > th){background:#fbf9f1;color:#596273;font-size:12px}.table-panel :deep(.ant-table-tbody > tr > td){font-size:12px}
.diagnosis-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:14px}.diagnosis-card{background:#fff;border:1px solid #ebe8e2;border-radius:14px;padding:20px;min-height:145px}.diagnosis-card.warning{border-top:3px solid #c98b2b}.diagnosis-card.info{border-top:3px solid #78879a}.diagnosis-card.success{border-top:3px solid #16805a}.diagnosis-head{display:flex;align-items:center;gap:9px}.diagnosis-head span{width:22px;height:22px;border-radius:50%;display:grid;place-items:center;background:#f5f0e8;color:#9b2d20;font-size:12px}.diagnosis-card.success .diagnosis-head span{color:#16805a}.diagnosis-card p{font-size:12px;color:#7f8794;line-height:1.6;margin:12px 0}.diagnosis-card :deep(.ant-btn-link){padding:0;color:#9b2d20;font-size:12px}.empty-diagnosis{grid-column:1/-1;text-align:center;padding:60px;color:#9ca3af}
@media(max-width:1050px){.kpi-grid{grid-template-columns:repeat(3,1fr)}.grid-main{grid-template-columns:1fr}.diagnosis-grid{grid-template-columns:1fr 1fr}}
@media(max-width:700px){.traffic-page{padding:0 8px 30px}.hero{align-items:flex-start;gap:16px;flex-direction:column}.hero-actions{width:100%;flex-wrap:wrap}.hero-actions .ant-radio-group{width:100%}.hero-actions .ant-radio-button-wrapper{width:33.33%;text-align:center}.kpi-grid{grid-template-columns:repeat(2,1fr)}.kpi-card{padding:14px}.kpi-value{font-size:19px}.grid-two,.diagnosis-grid{grid-template-columns:1fr}.panel :deep(.ant-card-body){padding:15px}.source-row{grid-template-columns:24px 1fr 42px 20px}}
</style>