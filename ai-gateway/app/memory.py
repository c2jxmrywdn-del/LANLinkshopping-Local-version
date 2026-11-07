# -*- coding: utf-8 -*-
"""会话记忆与上下文管理（供 ai-gateway 多轮不遗忘 + 防超上下文）。

策略：
  - 每个 session_id 维护完整对话历史（进程内；多 worker 需换 Redis，见文末）。
  - 组装请求时：system + 「早前对话摘要」(滚动压缩) + 最近 KEEP_RECENT 轮原文。
  - 当历史超过预算，把最旧的若干轮折叠进"摘要"（用 LLM 摘要；LLM 不可用时降级为截断要点）。
"""
import os
import time
from collections import OrderedDict

# 目标：喂给模型的 messages 估算 token 不超过 BUDGET（llama.cpp 上下文 12288，留生成余量）
BUDGET = int(os.environ.get("SESSION_TOKEN_BUDGET", "8000"))
KEEP_RECENT = int(os.environ.get("SESSION_KEEP_RECENT", "8"))   # 保留最近 N 轮原文
SUMMARY_TRIGGER = int(os.environ.get("SESSION_SUMMARY_TRIGGER", "12"))  # 超过 N 轮开始摘要
# 每来源 IP 可同时持有的会话数上限：防匿名脚本用随机 session_id 灌满全局 5000 条 LRU、
# 驱逐真实会话（来源 IP 由 TCP 对端给出，不可伪造）
MAX_SESSIONS_PER_IP = int(os.environ.get("SESSION_MAX_PER_IP", "50"))
_MAX_TRACKED_IPS = 2000

_sessions = OrderedDict()
_ip_sessions = OrderedDict()   # owner_ip -> OrderedDict(session_id -> None)，LRU


def _est_tokens(msgs):
    # 中文粗估：字符数/2（偏保守）
    return sum(len(str(m.get("content", ""))) for m in msgs) // 2 + len(msgs) * 4


def append_turn(session_id, role, content, owner_ip=None):
    h = _sessions.setdefault(session_id, [])
    h.append({"role": role, "content": content, "ts": time.time()})
    if len(_sessions) > 5000:                      # 简单 LRU 上限，防内存膨胀
        _sessions.popitem(last=False)
    if owner_ip:
        if owner_ip not in _ip_sessions and len(_ip_sessions) >= _MAX_TRACKED_IPS:
            _ip_sessions.popitem(last=False)
        bucket = _ip_sessions.setdefault(owner_ip, OrderedDict())
        bucket[session_id] = None
        bucket.move_to_end(session_id)
        while len(bucket) > MAX_SESSIONS_PER_IP:
            old_sid, _ = bucket.popitem(last=False)
            if old_sid != session_id:
                _sessions.pop(old_sid, None)       # 连带清历史，防止绕过全局 LRU 计数


def history(session_id):
    return list(_sessions.get(session_id, []))


def clear(session_id):
    _sessions.pop(session_id, None)
    for bucket in _ip_sessions.values():
        bucket.pop(session_id, None)


def build_messages(session_id, system_prompt, summarize=None):
    """预算驱动：默认全量保留(含当前这轮 user)；仅当估算 token 超 BUDGET 才摘要旧轮+截断。
    summarize(list_of_msg)->str 可选（用 LLM 压缩旧轮）。"""
    h = _sessions.get(session_id, [])
    meta = {"session_id": session_id, "turns": len(h) // 2, "summarized": False}

    def assemble(start_idx, summary=None):
        m = [{"role": "system", "content": system_prompt}]
        if summary:
            m.append({"role": "system", "content": "【早前对话摘要】" + summary})
        for msg in h[start_idx:]:
            m.append({"role": msg["role"], "content": msg["content"]})
        return m

    # 1) 全量保留（含末尾当前 user）—— 绝大多数会话走这条，不丢细节
    msgs = assemble(0)
    if _est_tokens(msgs) <= BUDGET:
        meta["context_tokens_est"] = _est_tokens(msgs)
        return msgs, meta

    # 2) 超预算：保留最近 KEEP_RECENT*2 条原文，更早的摘要压缩
    start = max(0, len(h) - KEEP_RECENT * 2)
    dropped = h[:start]
    summary = None
    if summarize is not None and dropped:
        try:
            summary = summarize(dropped)
        except Exception:
            summary = None
    if not summary and dropped:  # 降级：抽取旧轮要点
        summary = "\n".join(f"{d['role']}:{str(d['content'])[:60]}" for d in dropped)[-1800:]
    msgs = assemble(start, summary)
    meta["summarized"] = True

    # 3) 仍超：丢最近里最旧的一对，直到合适（始终保留 system + 摘要 + 末尾当前 user）
    while _est_tokens(msgs) > BUDGET and len(msgs) > 3:
        del msgs[2:4]
        meta["truncated"] = True
    meta["context_tokens_est"] = _est_tokens(msgs)
    return msgs, meta
