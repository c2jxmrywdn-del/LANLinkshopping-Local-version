"""意图分类提示词与标签解析：训练与评测共用同一口径。"""
import json
import re

from config import INTENTS


def label_table() -> str:
    return "\n".join(f"- {code}: {zh}" for code, zh in INTENTS)


SYSTEM = (
    "你是 LANLinkshopping（聚合型 B2B 电商平台）的意图分类器。\n"
    "只输出 JSON：{\"intent\": \"<code>\"}，不要解释、不要多余文本。\n"
    "intent 必须取自下列 code 之一（不要自造）：\n"
    f"{label_table()}\n"
    "无法归入任何一类时输出 {\"intent\": \"other\"}。"
)


def build_messages(question: str) -> list:
    return [
        {"role": "system", "content": SYSTEM},
        {"role": "user", "content": question.strip()},
    ]


_FENCE = re.compile(r"```(?:json)?\s*([\s\S]*?)```", re.I)
_JSON = re.compile(r"\{[\s\S]*?\}")
_CODES = {code for code, _ in INTENTS}


def _valid(code: str) -> str:
    """白名单校验：只接受 11 个预定义 code 或 other，其余统一归为 other。"""
    code = (code or "").strip()
    return code if (code in _CODES or code == "other") else "other"


def parse_intent(text: str) -> str:
    """从模型输出中稳健提取 intent code（不在白名单内的一律归为 other）。"""
    if not text:
        return ""
    s = text.strip()
    m = _FENCE.search(s)
    if m:
        s = m.group(1).strip()
    m = _JSON.search(s)
    if m:
        try:
            return _valid(str(json.loads(m.group(0)).get("intent", "")))
        except Exception:
            pass
    # 兜底：直接匹配已知 code（优先最长匹配，避免 other 抢先）
    low = s.lower()
    for code in sorted(_CODES, key=len, reverse=True):
        if code in low:
            return code
    return "other" if "other" in low else ""
