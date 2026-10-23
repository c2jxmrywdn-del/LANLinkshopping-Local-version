import type { ImportItem } from "./types";

// 轻量 CSV 解析（无第三方依赖，够用于批量导入模板）
// 期望表头：title,brand,spec,price,indId,catId,coverUrl,stock,detail
function splitCsvLine(line: string): string[] {
  const out: string[] = [];
  let cur = "";
  let quoted = false;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (quoted) {
      if (ch === '"' && line[i + 1] === '"') { cur += '"'; i++; }
      else if (ch === '"') { quoted = false; }
      else { cur += ch; }
    } else if (ch === '"') {
      quoted = true;
    } else if (ch === ",") {
      out.push(cur); cur = "";
    } else {
      cur += ch;
    }
  }
  out.push(cur);
  return out;
}

export function parseCsv(text: string): ImportItem[] {
  const lines = text.replace(/\r\n/g, "\n").split("\n").filter((l) => l.trim().length);
  if (!lines.length) return [];
  const header = splitCsvLine(lines[0]).map((h) => h.trim().toLowerCase());
  const col = (name: string) => header.indexOf(name.toLowerCase());
  const idx = {
    title: col("title"), brand: col("brand"), spec: col("spec"),
    price: col("price"), indId: col("indId"), catId: col("catId"),
    coverUrl: col("coverUrl"), stock: col("stock"), detail: col("detail"),
  };
  const items: ImportItem[] = [];
  for (let i = 1; i < lines.length; i++) {
    const cells = splitCsvLine(lines[i]);
    const get = (n: keyof typeof idx) => (idx[n] >= 0 ? (cells[idx[n]] ?? "").trim() : "");
    const title = get("title");
    if (!title) continue;
    items.push({
      title,
      brand: get("brand") || undefined,
      spec: get("spec") || undefined,
      price: Number(get("price")) || 0,
      indId: Number(get("indId")) || 0,
      catId: Number(get("catId")) || 0,
      coverUrl: get("coverUrl"),
      stock: Number(get("stock")) || 0,
      detail: get("detail") || undefined,
    });
  }
  return items;
}

export function normalizeItems(raw: unknown): ImportItem[] {
  const arr = Array.isArray(raw) ? raw : [];
  return arr
    .filter((x): x is Record<string, unknown> => !!x && typeof x === "object")
    .map((x) => ({
      title: String(x.title ?? "").trim(),
      brand: x.brand ? String(x.brand) : undefined,
      spec: x.spec ? String(x.spec) : undefined,
      price: Number(x.price) || 0,
      indId: Number(x.indId) || 0,
      catId: Number(x.catId) || 0,
      coverUrl: x.coverUrl ? String(x.coverUrl).trim() : "",
      stock: Number(x.stock) || 0,
      detail: x.detail ? String(x.detail) : undefined,
    }))
    .filter((x) => x.title);
}
