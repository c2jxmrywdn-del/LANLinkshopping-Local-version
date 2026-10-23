import { NextRequest, NextResponse } from "next/server";
import { start } from "workflow/api";
import { importProductsWorkflow } from "../../../workflows/import-products";
import { normalizeItems, parseCsv } from "../../../lib/csv";
import { corsHeaders } from "../../../lib/cors";
import type { ImportItem } from "../../../lib/types";

export const dynamic = "force-dynamic";

// 校验规则与后端 ProductService.publish 对齐，尽早给出可读错误，避免启动注定全失败的工作流
function validateItems(items: ImportItem[]): string[] {
  const errors: string[] = [];
  items.forEach((it, i) => {
    const row = i + 1;
    const t = (it.title ?? "").trim();
    if (t.length < 2 || t.length > 100) errors.push(`第 ${row} 行：商品名称需 2-100 个字符`);
    if (!(it.price > 0) || it.price > 9999999) errors.push(`第 ${row} 行：价格需为 0.01 ~ 9999999 之间的数值`);
    if (!(it.stock >= 1) || it.stock > 999999) errors.push(`第 ${row} 行：库存需为 1 ~ 999999 之间的整数`);
    if (!(it.indId > 0)) errors.push(`第 ${row} 行：缺少行业 ID（indId）`);
    if (!(it.catId > 0)) errors.push(`第 ${row} 行：缺少分类 ID（catId）`);
    if (!it.coverUrl || !it.coverUrl.trim()) errors.push(`第 ${row} 行：缺少商品主图 URL（coverUrl）`);
  });
  return errors;
}

export async function OPTIONS(req: NextRequest) {
  return new NextResponse(null, { status: 204, headers: corsHeaders(req.headers.get("origin")) });
}

export async function POST(req: NextRequest) {
  const origin = req.headers.get("origin");
  try {
    const payload = (await req.json()) as { format?: string; data?: unknown };
    const items =
      payload.format === "csv" && typeof payload.data === "string"
        ? parseCsv(payload.data)
        : normalizeItems(payload.data);

    if (!items.length) {
      return NextResponse.json({ error: "没有可导入的商品行" }, { status: 400, headers: corsHeaders(origin) });
    }

    const errors = validateItems(items);
    if (errors.length) {
      return NextResponse.json(
        { error: "数据校验未通过", errors: errors.slice(0, 50) },
        { status: 400, headers: corsHeaders(origin) },
      );
    }

    const run = await start(importProductsWorkflow, [items]);
    return NextResponse.json(
      { runId: run.runId, total: items.length },
      { status: 202, headers: corsHeaders(origin) },
    );
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    return NextResponse.json({ error: message }, { status: 500, headers: corsHeaders(origin) });
  }
}
