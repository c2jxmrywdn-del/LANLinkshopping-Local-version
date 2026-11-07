<template>
  <div class="tech-note" role="note">
    <ShieldCheck :size="20" :stroke-width="1.9" aria-hidden="true" />
    <div>
      <strong>{{ isEn ? 'Platform role & compliance boundary' : '平台角色与合规边界' }}</strong>
      <span v-if="isEn">
        This platform is an information and collaboration tool for technology services. It provides listing,
        consultation matching, process guidance and SaaS tools, but does not itself provide patent agency,
        asset appraisal, trading-venue operation or payment settlement. Statutory procedures are handled by
        qualified institutions or by rights holders directly with the CNIPA. Cooperation with institutions such
        as the Shanghai Technology Exchange is subject to written agreements; nothing on this page constitutes
        government recognition, endorsement or an appraisal conclusion. Algorithmic matching and quick screening
        are for reference only and are not pricing, investment or legal advice.
      </span>
      <span v-else>
        本平台是技术服务信息发布与协作工具平台，提供信息展示、咨询对接、流程辅导与 SaaS 工具服务；
        平台自身不从事专利代理、资产评估、交易场所运营或支付结算。相关法定手续由具备相应资质的机构办理，
        或由权利人自行向国家知识产权局办理。平台与上海技术交易所等机构的合作以双方书面协议为准；
        页面展示不构成政府认定、背书或评估结论。算法匹配与快筛结果仅供参考，不构成定价、投资或法律意见。
      </span>
    </div>
  </div>
</template>

<script setup>
import { ShieldCheck } from 'lucide-vue-next'
import { useTechLang } from '../../utils/techLang'

const { isEn } = useTechLang()
</script>
