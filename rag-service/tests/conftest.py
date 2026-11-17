"""共享 fixture：构造 RedisVectorStorage 实例与 fake Redis 客户端。"""
from __future__ import annotations

import pytest

from app.redis_vector_store import RedisVectorStorage

from .support import FakeEmbedding, FakeRedis

DIM = 1024


@pytest.fixture
def embedding() -> FakeEmbedding:
    return FakeEmbedding(dim=DIM)


@pytest.fixture
def make_storage(embedding):
    """构造适配器实例；global_config 与生产 lightrag_kwargs 保持一致。"""

    def _make(
        *,
        workspace: str = "",
        namespace: str = "chunks",
        meta_fields: set[str] | None = None,
        threshold: float | None = 0.1,
        batch: int = 16,
        embedding_func=None,
    ) -> RedisVectorStorage:
        # threshold=None 用于验证「缺阈值必须报错」，因此不能无条件包 dict
        kwargs: dict = {"cosine_better_than_threshold": threshold}
        return RedisVectorStorage(
            namespace=namespace,
            workspace=workspace,
            global_config={
                "embedding_batch_num": batch,
                "vector_db_storage_cls_kwargs": kwargs,
            },
            embedding_func=embedding_func or embedding,
            meta_fields=set(meta_fields if meta_fields is not None else {"content", "entity_name"}),
        )

    return _make


@pytest.fixture
def storage(make_storage) -> RedisVectorStorage:
    return make_storage()


@pytest.fixture
def fake_redis() -> FakeRedis:
    return FakeRedis()
