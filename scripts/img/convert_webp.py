# -*- coding: utf-8 -*-
"""商品图片统一转 WebP：jpg/png → webp（quality 82, method 6），成功后删除源图并生成 SQL。
用法: python scripts/img/convert_webp.py [--apply]
  默认只转换并写 out/imgreplace/update-webp.sql
  --apply 额外删除源文件（转换成功才删）
输出: stdout 末行 {"converted","skipped","failed","failIds","sqlLines"}；有失败时退出码 2
"""
import json
import sys
from pathlib import Path
from PIL import Image

ROOT = Path(r"D:/桌面/LANLinkShopping/Shopping")
PUB = ROOT / "frontend/public/products"
OUT = ROOT / "out/imgreplace"
APPLY = "--apply" in sys.argv

ids = [int(l.split("\t")[0]) for l in (OUT / "prods.tsv").read_text(encoding="utf-8").strip().splitlines() if l.strip()]

converted, skipped, failed = 0, 0, []
sql = []
for pid in ids:
    webp = PUB / f"{pid}.webp"
    src = None
    for ext in ("jpg", "jpeg", "png"):
        cand = PUB / f"{pid}.{ext}"
        if cand.exists():
            src = cand
            break
    if src is None:
        if webp.exists():
            skipped += 1
            sql.append(f"UPDATE t_product SET cover_url='/products/{pid}.webp' WHERE prod_id={pid};")
            continue
        failed.append(pid)
        continue
    try:
        img = Image.open(src)
        img.save(webp, "WEBP", quality=82, method=6)  # 有源图必转换：旧同名 webp 一并覆盖（如商品 1-10 的旧演示图）
        data = webp.read_bytes()
        if data[:4] != b"RIFF" or data[8:12] != b"WEBP" or len(data) < 2000:
            raise ValueError(f"bad webp output {len(data)}B")
        if APPLY:
            src.unlink()
        converted += 1
        sql.append(f"UPDATE t_product SET cover_url='/products/{pid}.webp' WHERE prod_id={pid};")
    except Exception as e:  # noqa: BLE001 — 单张失败不阻断整批
        failed.append(pid)
        if webp.exists():
            webp.unlink()
        print(f"#{pid} FAIL {src.name}: {e}", file=sys.stderr)

(OUT / "update-webp.sql").write_text("\n".join(sql) + "\n", encoding="ascii")
print(json.dumps({"converted": converted, "skipped": skipped, "failed": len(failed), "failIds": failed[:50], "sqlLines": len(sql)}))
if failed:
    sys.exit(2)
