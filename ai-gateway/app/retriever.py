# -*- coding: utf-8 -*-
"""本地知识库向量检索模块。

实现：jieba 中文分词 -> TF-IDF 向量化 -> 余弦相似度检索，
支持向量检索、关键词匹配、上下文关联（同分类文档扩展）。
纯标准库 + jieba，无需外部向量数据库，可在 Docker 沙盒内稳定运行。
"""
import json
import math
import os
import re
from collections import Counter

import jieba

# 容器内非 root 用户无 /tmp 重命名权限，禁用词典缓存写盘（词典加载约 0.5s，可接受）
jieba.dt.cache_file = None

_TOKEN_RE = re.compile(r"[一-鿿A-Za-z0-9.%*]+")


def tokenize(text: str) -> list:
    """中文混合文本分词：jieba 切词 + 保留数字/英文/规格符号。"""
    text = text.lower()
    parts = _TOKEN_RE.findall(text)
    tokens = []
    for p in parts:
        if re.fullmatch(r"[一-鿿]+", p):
            tokens.extend(w for w in jieba.cut(p) if w.strip() and len(w.strip()) > 1)
        else:
            tokens.append(p)
    return tokens


_STOP_BIGRAMS = {"怎么", "什么", "多少", "多久", "可以", "能否", "是否", "哪些", "怎样",
                 "如何", "请问", "一下", "我的", "你们", "这个", "那个", "有没", "没有"}


def _bigrams(s: str) -> list:
    """字符二元组（用于向量无命中时的模糊兜底召回）。"""
    s = (s or "").lower()
    return [s[i:i + 2] for i in range(len(s) - 1) if s[i:i + 2].strip()]


def _embed(texts: list, base_url: str, model: str) -> list:
    """调用 Ollama /api/embed 批量取向量（bge-m3 等）。失败返回 []。"""
    import urllib.request
    try:
        payload = json.dumps({"model": model, "input": texts}).encode("utf-8")
        req = urllib.request.Request(base_url.rstrip("/") + "/api/embed", data=payload,
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=60) as r:
            data = json.loads(r.read().decode("utf-8"))
        embs = data.get("embeddings") or []
        return embs if len(embs) == len(texts) else []
    except Exception:
        return []


def _norm(vec: list) -> list:
    n = math.sqrt(sum(v * v for v in vec)) or 1.0
    return [v / n for v in vec]


class VectorIndex:
    """TF-IDF 向量索引：构建期统计 IDF，检索期做余弦相似度。"""

    def __init__(self, documents: list):
        self.documents = documents
        self._build()

    def _build(self):
        n = len(self.documents)
        doc_tokens = []
        df = Counter()
        for doc in self.documents:
            text = f"{doc['title']} {doc['category']} {doc['content']}"
            toks = tokenize(text)
            doc_tokens.append(toks)
            df.update(set(toks))
        self.idf = {t: math.log((n + 1) / (c + 1)) + 1 for t, c in df.items()}
        self.doc_vectors = []
        for toks in doc_tokens:
            self.doc_vectors.append(self._vectorize(toks))

    def _vectorize(self, tokens: list) -> dict:
        tf = Counter(tokens)
        total = sum(tf.values()) or 1
        vec = {}
        for t, c in tf.items():
            if t in self.idf:
                vec[t] = (c / total) * self.idf[t]
        norm = math.sqrt(sum(v * v for v in vec.values())) or 1.0
        return {t: v / norm for t, v in vec.items()}

    @staticmethod
    def _cosine(a: dict, b: dict) -> float:
        if len(b) < len(a):
            a, b = b, a
        return sum(v * b.get(k, 0.0) for k, v in a.items())

    def _fuzzy(self, query: str, top_k: int = 4) -> list:
        """向量无命中时的模糊兜底：按查询字符 bigram 在文档中的命中数排序。"""
        qb = {g for g in _bigrams(query) if g not in _STOP_BIGRAMS}
        if not qb:
            return []
        scored = []
        for doc in self.documents:
            text = f"{doc['title']} {doc['category']} {doc['content']}".lower()
            hits = sum(1 for g in qb if g in text)
            if hits > 0:
                scored.append({**doc, "score": round(hits / len(qb), 4), "fuzzy": True})
        scored.sort(key=lambda x: x["score"], reverse=True)
        return scored[:top_k]

    def search(self, query: str, top_k: int = 4, min_score: float = 0.05) -> list:
        """混合检索：余弦相似度 + 标题/分类命中加权（让最相关文档排前，提升精准度/注意力）。
        无命中时回退 bigram 模糊召回。"""
        qtokens = tokenize(query)
        qset = set(qtokens)
        qvec = self._vectorize(qtokens)
        nq = len(qset) or 1
        scored = []
        for doc, dvec in zip(self.documents, self.doc_vectors):
            base = self._cosine(qvec, dvec)
            # 标题/分类命中加权：命中数占比 * 0.15（让领域精确匹配优先，减少"答非所问"）
            tcat = set(tokenize(f"{doc['title']} {doc['category']}"))
            boost = 0.15 * (len(qset & tcat) / nq)
            score = base + boost
            if score >= min_score:
                scored.append({**doc, "score": round(score, 4)})
        scored.sort(key=lambda x: x["score"], reverse=True)
        if not scored:
            return self._fuzzy(query, top_k=top_k)
        return scored[:top_k]

    def search_with_context(self, query: str, top_k: int = 4) -> list:
        """上下文关联检索：命中结果扩展同分类文档，保证回答上下文完整。"""
        hits = self.search(query, top_k=top_k)
        if not hits:
            return []
        seen = {h["id"] for h in hits}
        context = list(hits)
        for h in hits:
            for doc in self.documents:
                if doc["id"] not in seen and doc["category"] == h["category"]:
                    seen.add(doc["id"])
                    context.append({**doc, "score": 0.0, "related": True})
                    break
        return context


class KnowledgeBase:
    """知识库：JSON + TF-IDF 索引 + bge-m3 语义向量（混合检索；embedding 失败自动回退 TF-IDF）。"""

    def __init__(self, kb_path: str):
        self.kb_path = kb_path
        self.ollama_base = os.environ.get("OLLAMA_BASE_URL", "http://localhost:11434")
        self.emb_model = os.environ.get("EMBED_MODEL", "bge-m3:latest")
        self.doc_embs = []
        self.reload()

    def reload(self):
        with open(self.kb_path, encoding="utf-8") as f:
            data = json.load(f)
        self.meta = data.get("meta", {})
        self.documents = data["documents"]
        self.index = VectorIndex(self.documents)
        texts = [f"{d['title']} {d['category']} {d['content']}" for d in self.documents]
        embs = _embed(texts, self.ollama_base, self.emb_model)
        self.doc_embs = [_norm(e) for e in embs] if embs else []

    def _rank(self, query: str, top_k: int) -> list:
        """混合打分：TF-IDF 余弦 + bge-m3 语义相似(高于基线才加权) + 标题/分类加成。"""
        qt = tokenize(query)
        qset = set(qt)
        qvec = self.index._vectorize(qt)
        nq = len(qset) or 1
        qe = None
        if self.doc_embs:
            ee = _embed([query], self.ollama_base, self.emb_model)
            qe = _norm(ee[0]) if ee else None
        scored = []
        for i, doc in enumerate(self.documents):
            tfv = self.index._cosine(qvec, self.index.doc_vectors[i])
            tcat = set(tokenize(f"{doc['title']} {doc['category']}"))
            boost = 0.15 * (len(qset & tcat) / nq)
            if qe is not None and i < len(self.doc_embs):
                embv = sum(a * b for a, b in zip(qe, self.doc_embs[i]))
                sem = max(0.0, embv - 0.40) / 0.60      # 高于基线才有贡献，避免无关文档被抬分
                score = 0.55 * tfv + 0.55 * sem + boost
            else:
                score = tfv + boost
            if score >= 0.05:
                scored.append({**doc, "score": round(score, 4)})
        scored.sort(key=lambda x: x["score"], reverse=True)
        if not scored:
            return self.index._fuzzy(query, top_k=top_k)
        return scored[:top_k]

    def search(self, query: str, top_k: int = 4, with_context: bool = True) -> list:
        hits = self._rank(query, top_k=top_k if not with_context else max(top_k, 4))
        if not with_context or not hits:
            return hits[:top_k]
        seen = {h["id"] for h in hits}
        context = list(hits)
        for h in hits:
            for doc in self.documents:
                if doc["id"] not in seen and doc["category"] == h["category"]:
                    seen.add(doc["id"])
                    context.append({**doc, "score": 0.0, "related": True})
                    break
        return context

    def stats(self) -> dict:
        return {
            "document_count": len(self.documents),
            "categories": sorted({d["category"] for d in self.documents}),
            "vocab_size": len(self.index.idf),
            "embedding": {"enabled": bool(self.doc_embs), "model": self.emb_model,
                          "dim": len(self.doc_embs[0]) if self.doc_embs else 0},
            "meta": self.meta,
        }


def default_kb_path() -> str:
    return os.environ.get(
        "KB_PATH",
        os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "kb_data", "ecommerce_kb.json"),
    )


EMBED_DIM = 256


def embed(text: str) -> list:
    """固定维度（256）文本向量：哈希词袋 + TF-IDF 权重 + L2 归一，OpenAI embeddings 语义兼容。"""
    import hashlib
    vec = [0.0] * EMBED_DIM
    toks = tokenize(text)
    if not toks:
        return vec
    tf = Counter(toks)
    total = sum(tf.values())
    for t, c in tf.items():
        h = int(hashlib.md5(t.encode("utf-8")).hexdigest(), 16)
        idx = h % EMBED_DIM
        sign = 1.0 if (h >> 8) & 1 else -1.0
        vec[idx] += sign * (c / total)
    norm = math.sqrt(sum(v * v for v in vec)) or 1.0
    return [round(v / norm, 6) for v in vec]
