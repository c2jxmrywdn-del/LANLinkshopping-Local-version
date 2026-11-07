# -*- coding: utf-8 -*-
"""全量图片重复检测（只读）：md5 精确 + dhash 感知（汉明≤6），并查集分组。
用法: python scripts/img/compute-dupes.py
输出: out/imgreplace/dupes.json {"groups","redoIds","avoid"}（avoid=全部现存图 md5，重做商品必须全部避开）
      stdout 末行 {"dupGroups","redoCount","scanned"}
"""
import json
import hashlib
from pathlib import Path
from PIL import Image

ROOT = Path(r"D:/桌面/LANLinkShopping/Shopping")
PUB = ROOT / "frontend/public/products"
OUT = ROOT / "out/imgreplace"

ids = [int(l.split("\t")[0]) for l in (OUT / "prods.tsv").read_text(encoding="utf-8").strip().splitlines() if l.strip()]


def find_file(pid):
    for ext in ("webp", "jpg", "jpeg", "png"):
        f = PUB / f"{pid}.{ext}"
        if f.exists():
            return f
    return None


def dhash64(img):
    g = img.convert("L").resize((9, 8), Image.LANCZOS)
    px = list(g.getdata())
    v = 0
    for row in range(8):
        for col in range(8):
            v = (v << 1) | (1 if px[row * 9 + col] > px[row * 9 + col + 1] else 0)
    return v


ham = lambda a, b: bin(a ^ b).count("1")

items = {}  # pid -> {file, md5, dh}
for pid in ids:
    f = find_file(pid)
    if f is None:
        continue
    b = f.read_bytes()
    md5 = hashlib.md5(b).hexdigest()
    try:
        dh = dhash64(Image.open(f))
    except Exception:  # noqa: BLE001 — 解码失败的图只参与 md5 比对
        dh = None
    items[pid] = {"file": f.name, "md5": md5, "dh": dh}

parent = {pid: pid for pid in items}


def find(x):
    while parent[x] != x:
        parent[x] = parent[parent[x]]
        x = parent[x]
    return x


def union(a, b):
    ra, rb = find(a), find(b)
    if ra != rb:
        parent[max(ra, rb)] = min(ra, rb)


by_md5 = {}
for pid, it in items.items():
    by_md5.setdefault(it["md5"], []).append(pid)
for grp in by_md5.values():
    for x in grp[1:]:
        union(grp[0], x)

with_dh = [(pid, it["dh"]) for pid, it in items.items() if it["dh"] is not None]
pairs = 0
for i in range(len(with_dh)):
    a, da = with_dh[i]
    for j in range(i + 1, len(with_dh)):
        b, db = with_dh[j]
        if find(a) == find(b):
            continue
        if ham(da, db) <= 6:
            union(a, b)
            pairs += 1

groups = {}
for pid in items:
    groups.setdefault(find(pid), []).append(pid)
dups = {k: sorted(v) for k, v in groups.items() if len(v) > 1}

redo, detail = [], []
for grp in sorted(dups.values()):
    keep = grp[0]
    redo.extend(grp[1:])
    detail.append({"keep": keep, "redo": grp[1:], "files": [items[p]["file"] for p in grp]})

avoid = sorted({it["md5"] for it in items.values()})
(OUT / "dupes.json").write_text(
    json.dumps({"groups": detail, "redoIds": sorted(redo), "avoid": {"hashes": avoid}}, ensure_ascii=False, indent=1),
    encoding="utf-8",
)
print(json.dumps({"dupGroups": len(dups), "redoCount": len(redo), "nearDupPairs": pairs, "scanned": len(items)}))
