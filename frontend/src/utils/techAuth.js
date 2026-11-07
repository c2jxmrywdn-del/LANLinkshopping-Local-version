import { useUserStore } from '../store/user'

/** 等技术服务页面的登录态恢复完成；游客返回 logged=false，不抛错。 */
export async function ensureTechUser() {
  const user = useUserStore()
  if (!user.logged) {
    try {
      await user.fetchMe()
    } catch (e) {
      // 游客：保持未登录状态
    }
  }
  return user
}
