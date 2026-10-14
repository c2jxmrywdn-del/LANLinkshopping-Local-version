<template>
  <article class="first-place-leaderboard" :class="[`is-${tone}`, `is-${size}`]">
    <div class="first-place-leaderboard__person">
      <div class="first-place-leaderboard__portrait" :class="avatarRingClassName">
        <UserAvatar
          :src="avatarSrc"
          :name="name"
          :alt="avatarAlt || name"
          :size="avatarSize"
          :gender="gender"
          :role="role"
          :ring="false"
          class="first-place-leaderboard__avatar"
        />
        <svg v-if="crownVisible" class="first-place-leaderboard__crown" viewBox="0 0 24 24" aria-hidden="true">
          <path d="M3 18.5h18l-1.8-10-4.6 3.2L12 4 9.4 11.7 4.8 8.5 3 18.5Z" fill="currentColor" />
          <path d="M4.5 21h15" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" />
        </svg>
        <span v-if="typeof rank === 'number'" class="first-place-leaderboard__rank">{{ rank }}</span>
      </div>
      <div class="first-place-leaderboard__name">{{ name }}</div>
      <span v-if="amountLabel" class="first-place-leaderboard__amount" :class="pillClassName">{{ amountLabel }}</span>
    </div>

    <div class="first-place-leaderboard__score">
      <div class="first-place-leaderboard__score-labels">
        <span>{{ label }}</span>
        <span>{{ scoreValue }}%</span>
      </div>
      <div class="first-place-leaderboard__track" role="progressbar" :aria-label="label"
        :aria-valuenow="scoreValue" aria-valuemin="0" aria-valuemax="100">
        <div class="first-place-leaderboard__fill" :class="progressClassName" :style="{ width: `${scoreValue}%` }" />
      </div>
    </div>
  </article>
</template>

<script setup>
import { computed } from 'vue'
import UserAvatar from './UserAvatar.vue'

const props = defineProps({
  name: { type: String, required: true },
  amount: { type: [Number, String], default: undefined },
  amountPrefix: { type: String, default: '$' },
  avatarSrc: { type: String, default: '' },
  avatarAlt: { type: String, default: '' },
  gender: { type: String, default: 'secret' },
  role: { type: String, default: 'user' },
  rank: { type: Number, default: undefined },
  score: { type: Number, default: 0 },
  label: { type: String, default: 'Score' },
  showCrown: { type: Boolean, default: undefined },
  tone: { type: String, default: 'emerald', validator: v => ['emerald', 'blue', 'zinc'].includes(v) },
  size: { type: String, default: 'md', validator: v => ['sm', 'md', 'lg'].includes(v) },
  avatarRingClassName: { type: String, default: '' },
  progressClassName: { type: String, default: '' },
  pillClassName: { type: String, default: '' }
})

const scoreValue = computed(() => {
  const value = Number(props.score)
  return Number.isFinite(value) ? Math.round(Math.max(0, Math.min(100, value))) : 0
})
const crownVisible = computed(() => props.showCrown === undefined ? props.rank === 1 : props.showCrown)
const avatarSize = computed(() => ({ sm: 56, md: 64, lg: 80 }[props.size]))
const amountLabel = computed(() => {
  if (props.amount === undefined || props.amount === null || props.amount === '') return ''
  if (typeof props.amount === 'string') return props.amount
  try { return `${props.amountPrefix}${props.amount.toLocaleString()}` }
  catch { return `${props.amountPrefix}${props.amount}` }
})
</script>

<style scoped>
.first-place-leaderboard {
  --lb-pad: 24px; --lb-avatar: 64px; --lb-name: 18px; --lb-pill-font: 12px; --lb-caption: 13px;
  position: relative; width: 100%; min-width: 0; border-radius: 16px; padding: var(--lb-pad);
  margin-top: 30px; box-shadow: 0 8px 24px rgba(15,23,42,.06);
  color: var(--ll-ink, #0F172A); transition: transform .2s ease, box-shadow .2s ease;
}
.first-place-leaderboard:hover { transform: translateY(-2px); box-shadow: 0 12px 30px rgba(15,23,42,.09); }
.first-place-leaderboard.is-emerald { background: rgba(236,253,245,.88); border: 1px solid #D1FAE5; }
.first-place-leaderboard.is-blue { background: rgba(239,246,255,.92); border: 1px solid #DBEAFE; }
.first-place-leaderboard.is-zinc { background: rgba(244,244,245,.9); border: 1px solid #E4E4E7; }
.first-place-leaderboard__person { display: flex; flex-direction: column; align-items: center; gap: 10px; }
.first-place-leaderboard__portrait {
  position: relative; display: grid; place-items: center; width: var(--lb-avatar); height: var(--lb-avatar);
  margin-top: -48px; border-radius: 50%; background: #fff; outline: 4px solid rgba(200,164,92,.68);
  box-shadow: 0 0 0 4px #fff;
}
.first-place-leaderboard.is-blue .first-place-leaderboard__portrait { outline-color: rgba(30,110,184,.55); }
.first-place-leaderboard.is-zinc .first-place-leaderboard__portrait { outline-color: rgba(113,113,122,.45); }
.first-place-leaderboard__avatar { width: 100%; height: 100%; }
.first-place-leaderboard__crown {
  position: absolute; width: 23px; height: 23px; top: -19px; left: 50%; transform: translateX(-50%);
  color: #EAB308; filter: drop-shadow(0 2px 3px rgba(120,53,15,.16));
}
.first-place-leaderboard__rank {
  position: absolute; right: -5px; bottom: -3px; display: grid; place-items: center;
  width: 24px; height: 24px; border: 2px solid #fff; border-radius: 50%;
  background: #FACC15; color: #713F12; font-size: 11px; font-weight: 800;
}
.first-place-leaderboard__name {
  max-width: 100%; color: #064E3B; font-size: var(--lb-name); line-height: 1.3;
  text-align: center; font-weight: 750; overflow-wrap: anywhere;
}
.first-place-leaderboard.is-blue .first-place-leaderboard__name { color: #1E3A8A; }
.first-place-leaderboard.is-zinc .first-place-leaderboard__name { color: #27272A; }
.first-place-leaderboard__amount {
  max-width: 100%; padding: 6px 12px; border-radius: 999px; background: rgba(5,150,105,.94);
  color: #ECFDF5; font-size: var(--lb-pill-font); line-height: 1.3; font-weight: 750; overflow-wrap: anywhere;
}
.first-place-leaderboard.is-blue .first-place-leaderboard__amount { background: rgba(37,99,235,.94); color: #EFF6FF; }
.first-place-leaderboard.is-zinc .first-place-leaderboard__amount { background: #27272A; color: #FAFAFA; }
.first-place-leaderboard__score { margin-top: 22px; }
.first-place-leaderboard__score-labels {
  display: flex; align-items: center; justify-content: space-between; gap: 10px;
  margin-bottom: 8px; color: #52525B; font-size: var(--lb-caption);
}
.first-place-leaderboard__track { height: 8px; overflow: hidden; border-radius: 999px; background: rgba(16,185,129,.18); }
.first-place-leaderboard.is-blue .first-place-leaderboard__track { background: rgba(37,99,235,.16); }
.first-place-leaderboard.is-zinc .first-place-leaderboard__track { background: rgba(113,113,122,.16); }
.first-place-leaderboard__fill { height: 100%; border-radius: inherit; background: #10B981; transition: width .35s ease; }
.first-place-leaderboard.is-blue .first-place-leaderboard__fill { background: #2563EB; }
.first-place-leaderboard.is-zinc .first-place-leaderboard__fill { background: #52525B; }
.first-place-leaderboard__fill.custom { background: var(--lb-custom-progress, #059669) !important; }
.first-place-leaderboard.is-sm { --lb-pad: 16px; --lb-avatar: 56px; --lb-name: 16px; --lb-pill-font: 11px; --lb-caption: 12px; }
.first-place-leaderboard.is-md { --lb-pad: 24px; --lb-avatar: 64px; --lb-name: 18px; --lb-pill-font: 12px; --lb-caption: 13px; }
.first-place-leaderboard.is-lg { --lb-pad: 32px; --lb-avatar: 80px; --lb-name: 20px; --lb-pill-font: 14px; --lb-caption: 14px; }
@media (max-width: 540px) {
  .first-place-leaderboard { padding: 20px; }
  .first-place-leaderboard.is-lg { --lb-pad: 22px; --lb-avatar: 70px; }
}
@media (prefers-reduced-motion: reduce) { .first-place-leaderboard, .first-place-leaderboard__fill { transition: none; } }
</style>
