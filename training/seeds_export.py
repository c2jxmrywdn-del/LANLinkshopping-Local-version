"""导出意图种子：Java CORPUS + 沙盒/会话回归用例 + DB 意图日志。

产出 data/seeds/seeds_v1.jsonl：
  {"seed_id","intent","text","source","lang"}
用法：python seeds_export.py
"""
import json
import os
import re
from pathlib import Path

import config
from config import ENTRY_TO_INTENT, INTENT_CODES, ROOT


def parse_java_corpus():
    path = ROOT / "backend/src/main/java/com/lanlink/shopping/support/IntentVectorService.java"
    text = path.read_text(encoding="utf-8", errors="ignore")
    out = []
    for i, m in enumerate(re.finditer(r'new CandidateExample\("([a-z_]+)",\s*"([^"]+)"\)', text), 1):
        intent, q = m.group(1), m.group(2).strip()
        if intent in INTENT_CODES and q:
            out.append({"seed_id": f"corpus-{intent}-{i}", "intent": intent, "text": q,
                        "source": "corpus", "lang": "zh"})
    return out


def parse_sandbox_cases():
    out = []
    path = ROOT / "backend/src/test/resources/support-learning-sandbox-cases.json"
    if not path.exists():
        return out
    data = json.loads(path.read_text(encoding="utf-8"))
    for i, item in enumerate(data, 1):
        q = (item.get("question") or "").strip()
        entry = item.get("expectedEntryId") or ""
        if not q or not entry:
            continue
        if item.get("learningEligible") is False:
            continue
        intent = ENTRY_TO_INTENT.get(entry)
        if intent:
            out.append({"seed_id": f"sandbox-{i}", "intent": intent, "text": q,
                        "source": "regression", "lang": "zh"})
    return out


def parse_conversation_cases():
    out = []
    path = ROOT / "support-learning-sandbox/conversation_regression_cases.json"
    if not path.exists():
        return out
    data = json.loads(path.read_text(encoding="utf-8"))
    for i, item in enumerate(data, 1):
        msgs = item.get("userMessages") or []
        q = (msgs[0] if msgs else "").strip()
        intent = ENTRY_TO_INTENT.get(str(item.get("expectedIntent") or ""))
        if q and intent:
            out.append({"seed_id": f"conv-{i}", "intent": intent, "text": q,
                        "source": "regression", "lang": "zh"})
    return out


SQL_INTENT_LOG = ("SELECT id, question_text, intent FROM t_support_intent_log "
                  "WHERE question_text IS NOT NULL AND CHAR_LENGTH(question_text) >= 4 "
                  "AND intent IN ({}) ORDER BY id DESC LIMIT 2000")


def _connect():
    """优先 pymysql（快），退回 mysql.connector；两者都设连接超时，绝不无限阻塞。"""
    timeout = int(os.getenv("TRAIN_DB_TIMEOUT", "5"))
    try:
        import pymysql
        return pymysql.connect(**config.DB, connect_timeout=timeout,
                               read_timeout=timeout * 4, write_timeout=timeout * 4), "pymysql"
    except ImportError:
        pass
    import mysql.connector
    return mysql.connector.connect(**config.DB, connection_timeout=timeout,
                                   get_warnings=False), "mysql.connector"


def parse_db_rows():
    out = []
    try:
        conn, driver = _connect()
    except Exception as exc:
        print(f"[warn] DB unreachable, intent_log seeds skipped: {exc}")
        return out
    try:
        cur = conn.cursor()
        cur.execute(SQL_INTENT_LOG.format(",".join(["%s"] * len(INTENT_CODES))), tuple(INTENT_CODES))
        for rid, q, intent in cur.fetchall():
            q = (q or "").strip()
            if q:
                out.append({"seed_id": f"db-{rid}", "intent": intent, "text": q,
                            "source": "intent_log", "lang": "mixed" if re.search(r"[A-Za-z]{3,}", q) else "zh"})
        cur.close()
    except Exception as exc:
        print(f"[warn] DB query failed, intent_log seeds skipped: {exc}")
    finally:
        try:
            conn.close()
        except Exception:
            pass
    return out


def main():
    rows = parse_java_corpus() + parse_sandbox_cases() + parse_conversation_cases() + parse_db_rows()
    seen, uniq = set(), []
    for r in rows:
        key = re.sub(r"\s+", "", r["text"]).lower()
        if key and key not in seen:
            seen.add(key)
            uniq.append(r)
    config.DATA_DIR.mkdir(parents=True, exist_ok=True)
    out = config.DATA_DIR / "seeds" / "seeds_v1.jsonl"
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "w", encoding="utf-8") as fp:
        for r in uniq:
            fp.write(json.dumps(r, ensure_ascii=False) + "\n")
    dist = {c: sum(1 for r in uniq if r["intent"] == c) for c in INTENT_CODES}
    print(json.dumps({"total": len(uniq), "out": str(out), "classes": dist}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
