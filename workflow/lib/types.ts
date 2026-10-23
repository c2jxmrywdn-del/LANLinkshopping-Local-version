// 商品批量导入的数据结构（必须可序列化，才能作为 workflow/step 参数）
export interface ImportItem {
  title: string;
  brand?: string;
  spec?: string;
  price: number;
  indId: number;
  catId: number;
  coverUrl: string;
  stock: number;
  detail?: string;
}

export interface ImportResult {
  index: number;
  title: string;
  ok: boolean;
  prodId?: number;
  error?: string;
}

export interface ImportSummary {
  total: number;
  succeeded: number;
  failed: number;
  results: ImportResult[];
}
