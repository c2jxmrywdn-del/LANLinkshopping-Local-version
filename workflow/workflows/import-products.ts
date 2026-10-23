import type { ImportItem, ImportResult, ImportSummary } from "../lib/types";
import { createProduct, loginToBackend } from "../lib/backend";

/**
 * 商品批量导入工作流（可恢复）：
 *  1) 登录服务账号（step）
 *  2) 逐条发布商品（step，失败自动重试；业务失败不重试）
 *  3) 汇总结果
 * 进度由事件日志持久化，进程重启后从断点继续。
 */
export async function importProductsWorkflow(items: ImportItem[]): Promise<ImportSummary> {
  "use workflow";

  const auth = await loginToBackend();
  const results: ImportResult[] = [];

  for (let i = 0; i < items.length; i++) {
    const item = items[i];
    try {
      const outcome = await createProduct(auth, item);
      results.push({ index: i, title: item.title, ok: outcome.ok, prodId: outcome.prodId, error: outcome.error });
    } catch (err) {
      results.push({
        index: i,
        title: item.title,
        ok: false,
        error: err instanceof Error ? err.message : String(err),
      });
    }
  }

  const succeeded = results.filter((r) => r.ok).length;
  return { total: items.length, succeeded, failed: items.length - succeeded, results };
}
