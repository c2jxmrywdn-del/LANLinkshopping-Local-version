#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""前端 API 调用 vs 后端 REST 端点 契约核对。

用法（仓库根目录）：
    python scripts/analysis/cross_contract_check.py

输出：MISS 行 = 前端调用了但后端无匹配端点；EXTRA 行 = 后端有但前端未调用。
路径归一化：${x} 与 {x} 统一折叠为 {}，去 query、合并斜杠、去尾斜杠。
"""
from __future__ import annotations

import collections
import os
import re
import sys

BACKEND_ROOT = "backend/src/main/java/com/lanlink/shopping"
FRONTEND_ROOT = "frontend/src"

CLASS_MAPPING = re.compile(r'@RequestMapping\(\s*(?:value\s*=\s*)?\{?\s*"([^"]+)"')
METHOD_MAPPING = re.compile(
    r"@(Get|Post|Put|Delete|Patch)Mapping"
    r"(\(\s*(?:value\s*=\s*)?\{?\s*\"([^\"]*)\"\s*\))?"
)
FRONTEND_CALL = re.compile(r"request\.(get|post|put|delete|patch)\(\s*[`'\"]([^`'\"]+)")


def normalize(path: str) -> str:
    path = path.split("?")[0]
    path = re.sub(r"\$\{[^}]*\}", "{}", path)
    path = re.sub(r"\{[^}]*\}", "{}", path)
    path = re.sub(r"/+", "/", path)
    return path.rstrip("/") or "/"


def backend_endpoints() -> list[tuple[str, str, str]]:
    found: list[tuple[str, str, str]] = []
    for dirpath, _, filenames in os.walk(BACKEND_ROOT):
        for name in filenames:
            if not name.endswith(".java"):
                continue
            full = os.path.join(dirpath, name)
            with open(full, encoding="utf-8", errors="replace") as fh:
                src = fh.read()
            match = CLASS_MAPPING.search(src)
            base = match.group(1) if match else ""
            if base and not base.startswith("/"):
                base = "/" + base
            for m in METHOD_MAPPING.finditer(src):
                verb = m.group(1).upper()
                sub = m.group(3) or ""
                path = (base.rstrip("/") + "/" + sub.lstrip("/")) if sub else base
                found.append((verb, normalize(path), os.path.relpath(full)))
    return found


def frontend_calls() -> list[tuple[str, str, str]]:
    found: list[tuple[str, str, str]] = []
    for dirpath, _, filenames in os.walk(FRONTEND_ROOT):
        for name in filenames:
            if not name.endswith(".js") or ".test." in name:
                continue
            full = os.path.join(dirpath, name)
            with open(full, encoding="utf-8", errors="replace") as fh:
                src = fh.read()
            for m in FRONTEND_CALL.finditer(src):
                found.append((m.group(1).upper(), m.group(2), os.path.relpath(full)))
    return found


def main() -> int:
    backend = backend_endpoints()
    frontend = frontend_calls()
    by_path: dict[str, set[str]] = collections.defaultdict(set)
    for verb, path, _ in backend:
        by_path[path].add(verb)

    print(f"backend endpoints: {len(backend)}")
    print(f"frontend calls   : {len(frontend)}")

    missing = []
    for verb, raw, src in frontend:
        path = normalize(raw)
        if path not in by_path:
            missing.append((verb, path, src))
        elif verb not in by_path[path]:
            missing.append((f"{verb}(backend:{','.join(sorted(by_path[path]))})", path, src))

    seen: set[tuple[str, str]] = set()
    print(f"\n=== MISS: frontend call has no backend match ({len(missing)}) ===")
    for verb, path, src in missing:
        key = (verb.split("(")[0], path)
        if key in seen:
            continue
        seen.add(key)
        print(f"{verb:26} {path:52} {src}")

    called_paths = {normalize(raw) for _, raw, _ in frontend}
    extra = sorted({(p, ",".join(sorted(v))) for p, v in by_path.items() if p not in called_paths})
    print(f"\n=== EXTRA: backend endpoint not called by frontend ({len(extra)}) ===")
    for path, verbs in extra:
        print(f"{verbs:26} {path}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
