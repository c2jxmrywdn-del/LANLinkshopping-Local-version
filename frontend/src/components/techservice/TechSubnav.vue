<template>
  <nav class="tech-subnav" aria-label="技术服务导航">
    <router-link
      v-for="item in items"
      :key="item.to"
      :to="item.to"
      :class="{ 'is-active': isActive(item) }"
    >
      <component :is="item.icon" :size="15" :stroke-width="2" />
      <span>{{ isEn ? item.en : item.zh }}</span>
    </router-link>
  </nav>
</template>

<script setup>
import { useRoute } from 'vue-router'
import { useTechLang } from '../../utils/techLang'
import {
  Sparkles,
  MessageSquare,
  Users,
  Search,
  BadgeCheck,
  Archive,
  Layers,
  ArrowLeftRight,
  FileSignature,
  FlaskConical,
  ScrollText,
  BookOpen
} from 'lucide-vue-next'

const route = useRoute()
const { isEn } = useTechLang()

const items = [
  { to: '/tech', zh: '总览', en: 'Overview', icon: Sparkles },
  { to: '/tech/exchange', zh: '技术交流', en: 'Exchange', icon: MessageSquare },
  { to: '/tech/experts', zh: '专家库', en: 'Experts', icon: Users },
  { to: '/tech/demands', zh: '需求大厅', en: 'Demands', icon: Search },
  { to: '/tech/achievements', zh: '成果库', en: 'Achievements', icon: BadgeCheck },
  { to: '/tech/appraisal', zh: '成果鉴定', en: 'Appraisal', icon: Archive },
  { to: '/tech/hosting', zh: '托管服务', en: 'Hosting', icon: Layers },
  { to: '/tech/workspace', zh: '交易工作台', en: 'Workspace', icon: ArrowLeftRight },
  { to: '/tech/contracts', zh: '合同与结算', en: 'Contracts', icon: FileSignature },
  { to: '/tech/org-tasks', zh: '机构任务', en: 'Org Tasks', icon: Layers },
  { to: '/tech/pilot', zh: '中试资源', en: 'Pilot', icon: FlaskConical },
  { to: '/tech/open-license', zh: '开放许可', en: 'Open License', icon: ScrollText },
  { to: '/tech/policies', zh: '政策与机构', en: 'Policy', icon: BookOpen }
]

function isActive(item) {
  if (item.to === '/tech') return route.path === '/tech' || route.path === '/tech/'
  return route.path === item.to || route.path.startsWith(item.to + '/')
}
</script>

<style scoped>
.tech-subnav a { line-height: 1; }
</style>
