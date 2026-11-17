"""测试替身：不连真实 Redis，纯内存模拟 RediSearch 交互。

为什么自建而不是 fakeredis
--------------------------
1. fakeredis 不实现 FT.CREATE / FT.INFO / FT.SEARCH，无法覆盖本适配器的核心路径；
2. 本套测试需要**精确控制** FT.INFO / FT.SEARCH 的返回形态（RESP3 dict 与
   RESP2 扁平 list），因为历史缺陷正是「只处理了一种形态」；
3. 需要断言下发给 Redis 的原始命令 token（RETURN 的字段计数、LIMIT、DD 等），
   只有记录完整命令列表才能锁住这些回归。
"""
from __future__ import annotations

from typing import Any

from redis.exceptions import ResponseError


class FakeEmbedding:
    """最小可用的 EmbeddingFunc 替身：只要求 embedding_dim + async __call__。"""

    def __init__(self, dim: int = 1024, rows: list[list[float]] | None = None):
        self.embedding_dim = dim
        self._rows = rows
        self.calls: list[dict[str, Any]] = []

    async def __call__(self, texts, context: str = "document", **kwargs):
        self.calls.append({"texts": list(texts), "context": context})
        if self._rows is not None:
            return [list(r) for r in self._rows]
        return [[float(i % 7) for i in range(self.embedding_dim)] for _ in texts]


class FakePipeline:
    """记录 HSET/HGET/HGETALL，execute 时写入 owner.hashes。"""

    def __init__(self, owner: "FakeRedis"):
        self.owner = owner
        self.calls: list[tuple] = []

    def hset(self, key, mapping=None, **kwargs):
        self.calls.append(("hset", key, mapping))
        return self

    def hgetall(self, key):
        self.calls.append(("hgetall", key))
        return self

    def hget(self, key, field):
        self.calls.append(("hget", key, field))
        return self

    async def execute(self):
        out = []
        for call in self.calls:
            if call[0] == "hset":
                _, key, mapping = call
                self.owner.hashes[key] = dict(mapping or {})
                out.append(1)
            elif call[0] == "hgetall":
                out.append(dict(self.owner.hashes.get(call[1], {})))
            elif call[0] == "hget":
                out.append(self.owner.hashes.get(call[1], {}).get(call[2]))
        return out


class FakeRedis:
    """只实现适配器用到的那几个方法。"""

    def __init__(self, responses: dict[str, Any] | None = None):
        # key 是命令名（"FT.INFO"/"FT.SEARCH"/...）；value 可以是常量或 callable
        self.responses = responses or {}
        self.commands: list[list[Any]] = []
        self.hashes: dict[str, dict] = {}
        self.deleted: list[str] = []
        self.closed = False

    async def execute_command(self, *args):
        self.commands.append(list(args))
        handler = self.responses.get(args[0])
        if callable(handler):
            return handler(*args)
        if handler is not None:
            return handler
        return None

    def pipeline(self, transaction: bool = True):
        return FakePipeline(self)

    async def hgetall(self, key):
        return dict(self.hashes.get(key, {}))

    async def hget(self, key, field):
        return self.hashes.get(key, {}).get(field)

    async def delete(self, *keys):
        self.deleted.extend(keys)
        return len(keys)

    async def aclose(self):
        self.closed = True


def unknown_index(*_args):
    raise ResponseError("Unknown index name")


def ft_info_dim(dim: int):
    """构造 redis-py 8.x 的 RESP3 FT.INFO 返回：dict + attributes 列表。"""

    def handler(*_args):
        return {
            b"index_name": b"rag-idx:_:chunks",
            b"index_options": [],
            b"attributes": [
                {
                    b"identifier": b"vec",
                    b"attribute": b"vec",
                    b"type": b"VECTOR",
                    b"algorithm": b"HNSW",
                    b"data_type": b"FLOAT32",
                    b"dim": dim,
                    b"distance_metric": b"COSINE",
                }
            ],
        }

    return handler
