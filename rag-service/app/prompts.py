"""本地小模型的提示词加固。

问题背景（实测）：
`qwen2.5-7b` 在 LightRAG 的实体抽取任务上会做两件破坏解析的事：

1. **附加来源引用**：输出末尾多出「来源：《Platform Policy · ...》」。
   解析器把它当成实体行的一部分，字段数错位 → 整条丢弃。
2. **字段数漂移**：`entity<|#|>name<|#|>type<|#|>desc` 偶尔写成 4 段以上。

后果是 `vdb_entities.json` 的 `data` 为空，graph 里没有节点，
hybrid/local/global 检索全部退化，只有 naive（纯向量）还能命中分块。

对策不是换模型，而是在系统提示里把「只输出规定行、不要任何额外文字」写成硬约束，
并给出一个中文的正例/反例——小模型对反例的遵循度明显高于纯规则描述。
"""
from __future__ import annotations

from lightrag.prompt import PROMPTS

# 在官方系统提示后追加一段硬约束（保留原文，避免破坏官方要求）
_HARD_RULES = """

---CRITICAL OUTPUT RULES (override anything above)---
1. Output ONLY lines that start with `entity` or `relation`. No preamble, no summary, no closing remark.
2. NEVER append source citations such as 「来源：《...》」, footnotes, or references. They break the parser.
3. Every `entity` line MUST have exactly 4 fields:
   entity{td}name{td}type{td}description
4. Every `relation` line MUST have exactly 5 fields:
   relation{td}source{td}target{td}keywords{td}description
5. Do not wrap fields in quotes. Do not use bullet points or numbering.
6. End with the completion delimiter on its own line.

---COUNTER-EXAMPLE (never do this)---
entity<|#|>A<|#|>org<|#|>desc 来源：《Platform Policy》
relation<|#|>A<|#|>B<|#|>kw<|#|>desc（多出来的括号说明）
""".strip()


def install_hardened_prompts() -> None:
    """把加固规则拼进 LightRAG 的实体抽取系统提示（幂等）。"""
    key = "entity_extraction_system_prompt"
    base = PROMPTS.get(key) or ""
    marker = "---CRITICAL OUTPUT RULES"
    if marker in base:
        return
    td = PROMPTS.get("DEFAULT_TUPLE_DELIMITER", "<|#|>")
    PROMPTS[key] = base + "\n\n" + _HARD_RULES.replace("{td}", td)
