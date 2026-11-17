# -*- coding: utf-8 -*-
"""资讯指纹与相似度（第四轮定稿）：精确 sha256 / 标题双口径相似 / simhash 提示层。"""
import hashlib
import re
import unicodedata

_WS_PUNCT = re.compile(r"[\W_\s\u3000-\u303F\uFF00-\uFFEF]+")



def normalize_text(text: str) -> str:
    """NFKC 归一化 → 去全部空白与标点 → 小写（正文与标题指纹共用）。"""
    if not text:
        return ""
    s = unicodedata.normalize("NFKC", text)
    return re.sub(r"[\W_\s\u3000-\u303F]+", "", s, flags=re.UNICODE).lower()


def sha256_hex(text: str) -> str:
    return hashlib.sha256((text or "").encode("utf-8")).hexdigest()


def body_fingerprint(body: str) -> str:
    """正文指纹：normalize 后 sha256（64hex）。"""
    return sha256_hex(normalize_text(body))


def title_fingerprint(title: str) -> str:
    return sha256_hex(normalize_title(title))


def normalize_title(title: str) -> str:
    return normalize_text(title)


def title_ratio(a: str, b: str) -> float:
    """标题相似度双口径取大：2-gram Jaccard（语序重排敏感）与
    SequenceMatcher.ratio（空白/前后缀敏感）——任一过阈值即视为近重。"""
    na, nb = normalize_title(a), normalize_title(b)
    if not na or not nb:
        return 0.0
    if na == nb:
        return 1.0
    seq = _sequence_ratio(na, nb)
    jac = _bigram_jaccard(na, nb)
    return max(seq, jac)


def _sequence_ratio(a: str, b: str) -> float:
    import difflib
    return difflib.SequenceMatcher(None, a, b).ratio()


def _bigrams(s: str) -> set[str]:
    return {s[i:i + 2] for i in range(len(s) - 1)}


def _bigram_jaccard(a: str, b: str) -> float:
    ga, gb = _bigrams(a), _bigrams(b)
    if not ga or not gb:
        return 0.0
    inter = len(ga & gb)
    return inter / (len(ga) + len(gb) - inter)


# ---- simhash（64bit FNV-1a 字符 bigram；提示层，不参与硬拒） ----

def _fnv1a64(data: bytes) -> int:
    h = 0xCBF29CE484222325
    for b in data:
        h ^= b
        h = (h * 0x100000001B3) & 0xFFFFFFFFFFFFFFFF
    return h


def simhash64(text: str) -> int:
    s = normalize_text(text)
    if len(s) < 2:
        return _fnv1a64(s.encode("utf-8")) if s else 0
    v = [0] * 64
    for i in range(len(s) - 1):
        h = _fnv1a64(s[i:i + 2].encode("utf-8"))
        for bit in range(64):
            v[bit] += 1 if (h >> bit) & 1 else -1
    out = 0
    for bit in range(64):
        if v[bit] > 0:
            out |= 1 << bit
    return out


def hamming64(a: int, b: int) -> int:
    return bin((a ^ b) & 0xFFFFFFFFFFFFFFFF).count("1")


def simhash_hex(text: str) -> str:
    return f"{simhash64(text):016x}"
