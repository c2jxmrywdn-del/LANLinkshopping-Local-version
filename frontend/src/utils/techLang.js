import { computed } from 'vue'
import { locale } from '../i18n'

/**
 * 技术服务模块的轻量双语：页面静态文案跟随全局 locale 切换；
 * 需求/成果等业务数据保持原文，避免机器翻译造成技术语义失真。
 */
export function useTechLang() {
  const isEn = computed(() => locale.value === 'en-US')
  const tx = (zh, en) => (isEn.value ? en : zh)
  return { isEn, tx }
}
