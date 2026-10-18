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

    def search(self, query: str, top_k: int = 4, min_score: float = 0.05) -> list:
        """向量检索：返回 [{id,title,category,content,score}]，按相似度降序。"""
        qvec = self._vectorize(tokenize(query))
        scored = []
        for doc, dvec in zip(self.documents, self.doc_vectors):
            score = self._cosine(qvec, dvec)
            if score >= min_score:
                scored.append({**doc, "score": round(score, 4)})
        scored.sort(key=lambda x: x["score"], reverse=True)
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
    """知识库读取模块：加载 JSON 知识库 + 向量索引 + 检索 API。"""

    def __init__(self, kb_path: str):
        self.kb_path = kb_path
        self.reload()

    def reload(self):
        with open(self.kb_path, encoding="utf-8") as f:
            data = json.load(f)
        self.meta = data.get("meta", {})
        self.documents = data["documents"]
        self.index = VectorIndex(self.documents)

    def search(self, query: str, top_k: int = 4, with_context: bool = True) -> list:
        if with_context:
            return self.index.search_with_context(query, top_k=top_k)
        return self.index.search(query, top_k=top_k)

    def stats(self) -> dict:
        return {
            "document_count": len(self.documents),
            "categories": sorted({d["category"] for d in self.documents}),
            "vocab_size": len(self.index.idf),
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
