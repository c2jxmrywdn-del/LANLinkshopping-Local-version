"""RedisVectorStorage 纯逻辑单测（不连真实 Redis）。"""
from __future__ import annotations

import struct

import pytest
from redis.exceptions import ResponseError

from app import redis_vector_store as rvs
from app.redis_vector_store import RedisVectorStorage, _index_name, _key_prefix, _to_f32

from .support import FakeEmbedding, FakeRedis, ft_info_dim, unknown_index


# ---------------------------------------------------------------- 命名与隔离


@pytest.mark.parametrize(
    ("workspace", "namespace", "expected_index", "expected_prefix"),
    [
        ("", "chunks", "rag-idx:_:chunks", "rag:vblob:_:chunks:"),
        ("", "", "rag-idx:_:default", "rag:vblob:_:default:"),
        ("ws1", "entities", "rag-idx:ws1:entities", "rag:vblob:ws1:entities:"),
        # 特殊字符一律转下划线：RediSearch 索引名会进 FT.CREATE 命令行
        ("my ws:1", "rel/ations", "rag-idx:my_ws_1:rel_ations", "rag:vblob:my_ws_1:rel_ations:"),
        # 注意：str.isalnum() 是 Unicode 感知的，中文不会被替换。
        # 中文索引名 RediSearch 接受，但会让 redis-cli / 运维脚本的引用变麻烦，
        # 见报告里的运维提示。这里锁住现状，避免有人误以为已经做了 ASCII 化。
        ("中文空间", "chunks", "rag-idx:中文空间:chunks", "rag:vblob:中文空间:chunks:"),
    ],
)
def test_index_and_prefix_naming(workspace, namespace, expected_index, expected_prefix):
    assert _index_name(workspace, namespace) == expected_index
    assert _key_prefix(workspace, namespace) == expected_prefix


def test_namespace_isolation(make_storage):
    """不同 workspace/namespace 的索引名与 key 前缀必须互不重叠。"""
    a = make_storage(workspace="wsA", namespace="chunks")
    b = make_storage(workspace="wsB", namespace="chunks")
    c = make_storage(workspace="wsA", namespace="entities")
    assert len({a.index_name, b.index_name, c.index_name}) == 3
    assert not a.key_prefix.startswith(b.key_prefix)
    assert not a.key_prefix.startswith(c.key_prefix)
    # 也不得与既有 Java 侧两套索引冲突
    assert not a.key_prefix.startswith("aikb:vblob:")
    assert not a.key_prefix.startswith("prod:vblob:")


def test_missing_threshold_raises_at_construction(make_storage):
    """阈值必须显式给：缺省静默用 0.2 会让中文短查询被过滤光。"""
    with pytest.raises(ValueError, match="cosine_better_than_threshold"):
        make_storage(threshold=None)


# ---------------------------------------------------------------- 向量编码


def test_to_f32_is_little_endian_and_roundtrips():
    vector = [0.5, -1.25, 3.0]
    blob = _to_f32(vector)
    assert len(blob) == len(vector) * 4
    # 小端：0.5f = 0x3F000000 → 00 00 00 3F
    assert blob[:4] == b"\x00\x00\x00\x3f"
    assert blob[4:8] == b"\x00\x00\xa0\xbf"
    assert list(struct.unpack(f"<{len(vector)}f", blob)) == vector


def test_to_f32_1024_dim_is_4096_bytes():
    blob = _to_f32([0.1] * 1024)
    assert len(blob) == 4096
    back = struct.unpack("<1024f", blob)
    assert len(back) == 1024
    # float32 精度损失在 1e-6 内
    assert all(abs(b - 0.1) < 1e-6 for b in back)


# ---------------------------------------------------------------- FT.INFO 解析


def test_extract_dim_resp3_dict():
    info = {
        b"attributes": [
            {
                b"identifier": b"vec",
                b"type": b"VECTOR",
                b"dim": 1024,
            }
        ]
    }
    assert RedisVectorStorage._extract_dim(info) == 1024


def test_extract_dim_resp3_dict_str_keys():
    info = {"attributes": [{"identifier": "vec", "dim": 768}]}
    assert RedisVectorStorage._extract_dim(info) == 768


def test_extract_dim_flat_list_should_return_1024():
    """扁平 list 形态（RESP2 / 老版本 redis-py）也必须能取到维度。

    历史缺陷：`_extract_dim` 是 @staticmethod，但 list 分支写成
    `[self._decode(x) for x in info]`——`self` 在静态方法里不存在，抛 NameError
    后被外层 `except Exception` 吞掉，于是**恒返回 None**，维度冲突检查静默失效。
    已修复（改用类名调用），本用例即回归锁。
    """
    info = [
        b"index_name", b"rag-idx:_:chunks",
        b"attributes", b"identifier", b"vec",
        b"type", b"VECTOR",
        b"dim", b"1024",
    ]
    assert RedisVectorStorage._extract_dim(info) == 1024


def test_extract_dim_returns_none_when_absent():
    assert RedisVectorStorage._extract_dim({b"attributes": []}) is None
    assert RedisVectorStorage._extract_dim([b"index_name", b"x"]) is None
    assert RedisVectorStorage._extract_dim(None) is None
    assert RedisVectorStorage._extract_dim("garbage") is None


def test_extract_dim_ignores_non_dict_attribute_entries():
    info = {b"attributes": [b"not-a-dict", {b"identifier": b"other", b"dim": 512}]}
    assert RedisVectorStorage._extract_dim(info) is None


def test_extract_dim_returns_none_when_vec_entry_has_no_dim():
    info = {b"attributes": [{b"identifier": b"vec"}]}
    assert RedisVectorStorage._extract_dim(info) is None


def test_extract_dim_resp2_nested_attributes_is_known_gap():
    """已知缺陷（未修）：真实 RESP2 的 FT.INFO 是「嵌套 list」，
    dim 在 attributes 子列表里，而扁平分支只扫顶层 token，因此返回 None。

    后果：RESP2 连接下维度冲突检查被静默跳过（不会报错，但也不保护）。
    当前默认 RESP3（redis-py 8.x）不受影响。修复建议见
    docs/architecture-audit/_parts/rag-service-pytest.md「生产代码缺陷」。
    """
    resp2_info = [
        b"index_name", b"rag-idx:_:chunks",
        b"index_options", [],
        b"attributes", [
            [b"identifier", b"vec", b"type", b"VECTOR", b"dim", 1024],
            [b"identifier", b"workspace_id", b"type", b"TAG"],
        ],
    ]
    assert RedisVectorStorage._extract_dim(resp2_info) is None


def test_dict_get_accepts_str_and_bytes_keys():
    d = {b"attributes": 1, "num_docs": 2}
    assert RedisVectorStorage._dict_get(d, "attributes") == 1
    assert RedisVectorStorage._dict_get(d, "num_docs") == 2
    assert RedisVectorStorage._dict_get(d, "missing") is None


# ---------------------------------------------------------------- FT.SEARCH 解析


def test_parse_search_resp3_dict(storage):
    raw = {
        b"total_results": 1,
        b"results": [
            {
                b"id": b"rag:vblob:_:chunks:chunk-abc",
                b"extra_attributes": {
                    b"distance": b"0.25",
                    b"content": b"hello",
                    b"entity_name": b"ACME",
                },
            }
        ],
    }
    out = storage._parse_search(raw)
    assert len(out) == 1
    assert out[0]["id"] == "chunk-abc"
    assert out[0]["content"] == "hello"
    assert out[0]["entity_name"] == "ACME"
    # distance → similarity = 1 - 0.25
    assert out[0]["distance"] == pytest.approx(0.75)


def test_parse_search_resp2_flat_list(storage):
    raw = [
        2,
        b"rag:vblob:_:chunks:chunk-1", [b"distance", b"0.10", b"content", b"one"],
        b"rag:vblob:_:chunks:chunk-2", [b"distance", b"0.30", b"content", b"two"],
    ]
    out = storage._parse_search(raw)
    assert [r["id"] for r in out] == ["chunk-1", "chunk-2"]
    assert out[0]["content"] == "one"
    assert out[0]["distance"] == pytest.approx(0.9)
    assert out[1]["distance"] == pytest.approx(0.7)


def test_parse_search_filters_below_threshold(storage):
    """similarity < 0.1（即 distance > 0.9）必须被丢弃。"""
    raw = {
        b"results": [
            {b"id": b"rag:vblob:_:chunks:keep", b"extra_attributes": {b"distance": b"0.5"}},
            {b"id": b"rag:vblob:_:chunks:drop", b"extra_attributes": {b"distance": b"0.95"}},
        ]
    }
    out = storage._parse_search(raw)
    assert [r["id"] for r in out] == ["keep"]


def test_parse_search_empty_and_malformed(storage):
    assert storage._parse_search([]) == []
    assert storage._parse_search(None) == []
    assert storage._parse_search([0]) == []
    assert storage._parse_search({b"results": []}) == []
    # dict 里混入非 dict 条目：跳过而不是崩
    assert storage._parse_search({b"results": [b"junk"]}) == []
    # RESP2 里字段数组不是 list：跳过
    assert storage._parse_search([1, b"key", b"not-a-list"]) == []


def test_append_record_strips_prefix_and_converts_similarity(storage):
    out: list[dict] = []
    storage._append_record(out, b"rag:vblob:_:chunks:chunk-9", {"distance": "0.2", "content": "x"})
    assert out[0]["id"] == "chunk-9"
    assert out[0]["distance"] == pytest.approx(0.8)
    assert out[0]["content"] == "x"


def test_append_record_keeps_key_when_prefix_absent(storage):
    out: list[dict] = []
    storage._append_record(out, b"foreign:key", {"distance": "0"})
    assert out[0]["id"] == "foreign:key"


def test_append_record_missing_distance_defaults_to_no_match(storage):
    """没有 distance 时按 2.0 处理 → similarity -1 → 被阈值过滤。"""
    out: list[dict] = []
    storage._append_record(out, b"rag:vblob:_:chunks:x", {})
    assert out == []


def test_append_record_non_numeric_distance_is_filtered(storage):
    out: list[dict] = []
    storage._append_record(out, b"rag:vblob:_:chunks:x", {"distance": "not-a-number"})
    assert out == []


def test_search_keys_resp3_and_resp2(storage):
    resp3 = {
        b"results": [
            {b"id": b"rag:vblob:_:chunks:a"},
            {b"id": b"rag:vblob:_:chunks:b"},
        ]
    }
    assert storage._search_keys(resp3) == [
        "rag:vblob:_:chunks:a",
        "rag:vblob:_:chunks:b",
    ]
    resp2 = [2, b"rag:vblob:_:chunks:a", b"rag:vblob:_:chunks:b"]
    assert storage._search_keys(resp2) == [
        "rag:vblob:_:chunks:a",
        "rag:vblob:_:chunks:b",
    ]
    assert storage._search_keys([0]) == []
    assert storage._search_keys(None) == []


# ---------------------------------------------------------------- 建索引


def _patch_client(monkeypatch, fake: FakeRedis) -> None:
    monkeypatch.setattr(rvs.aioredis, "from_url", lambda *a, **k: fake)


async def test_initialize_creates_index_with_hnsw_params(storage, fake_redis, monkeypatch):
    fake_redis.responses["FT.INFO"] = unknown_index
    _patch_client(monkeypatch, fake_redis)

    await storage.initialize()

    assert [c[0] for c in fake_redis.commands] == ["FT.INFO", "FT.CREATE"]
    create = fake_redis.commands[1]
    assert create[1] == "rag-idx:_:chunks"
    assert create[2:8] == ["ON", "HASH", "PREFIX", "1", "rag:vblob:_:chunks:", "SCHEMA"]

    i = create.index("VECTOR")
    assert create[i + 1] == "HNSW"
    # COUNT 必须精确等于其后的 token 数，否则 RediSearch 会把参数当字段名
    count = int(create[i + 2])
    params = create[i + 3:i + 3 + count]
    assert len(params) == count
    assert params[params.index("DIM") + 1] == "1024"
    assert params[params.index("TYPE") + 1] == "FLOAT32"
    assert params[params.index("DISTANCE_METRIC") + 1] == "COSINE"
    # 元数据字段：除 content 外都建 TAG
    assert "entity_name" in create
    assert create[create.index("entity_name") + 1] == "TAG"
    assert "content" not in create
    assert storage._index_ready is True


async def test_initialize_reuses_index_when_dim_matches(storage, fake_redis, monkeypatch):
    fake_redis.responses["FT.INFO"] = ft_info_dim(1024)
    _patch_client(monkeypatch, fake_redis)

    await storage.initialize()

    assert [c[0] for c in fake_redis.commands] == ["FT.INFO"]
    assert storage._index_ready is True


async def test_initialize_raises_on_dim_mismatch(storage, fake_redis, monkeypatch):
    fake_redis.responses["FT.INFO"] = ft_info_dim(768)
    _patch_client(monkeypatch, fake_redis)

    with pytest.raises(RuntimeError) as exc:
        await storage.initialize()

    assert "整库重建" in str(exc.value)
    assert "768" in str(exc.value)
    # 不能静默重建：不应下发 FT.CREATE / DROPINDEX
    assert [c[0] for c in fake_redis.commands] == ["FT.INFO"]


async def test_initialize_propagates_unexpected_redis_error(storage, fake_redis, monkeypatch):
    def boom(*_a):
        raise ResponseError("NOAUTH Authentication required")

    fake_redis.responses["FT.INFO"] = boom
    _patch_client(monkeypatch, fake_redis)

    with pytest.raises(ResponseError):
        await storage.initialize()


async def test_initialize_is_idempotent(storage, fake_redis, monkeypatch):
    fake_redis.responses["FT.INFO"] = ft_info_dim(1024)
    _patch_client(monkeypatch, fake_redis)

    await storage.initialize()
    await storage.initialize()

    assert [c[0] for c in fake_redis.commands] == ["FT.INFO"]


# ---------------------------------------------------------------- 写入


async def test_upsert_writes_hash_with_prefix_and_metadata(storage, fake_redis):
    storage._client = fake_redis
    await storage.upsert(
        {
            "chunk-1": {"content": "hello", "entity_name": "ACME"},
            "chunk-2": {"content": "world"},
        }
    )
    assert set(fake_redis.hashes) == {
        "rag:vblob:_:chunks:chunk-1",
        "rag:vblob:_:chunks:chunk-2",
    }
    rec = fake_redis.hashes["rag:vblob:_:chunks:chunk-1"]
    assert len(rec["vec"]) == 4096
    assert rec["content"] == "hello"
    assert rec["entity_name"] == "ACME"
    assert rec["workspace_id"] == "_"
    # 未提供的 meta 字段不写入
    assert "entity_name" not in fake_redis.hashes["rag:vblob:_:chunks:chunk-2"]


async def test_upsert_batches_embedding_calls(make_storage, fake_redis):
    emb = FakeEmbedding(dim=8)
    storage = make_storage(batch=2, embedding_func=emb)
    storage._client = fake_redis

    await storage.upsert({f"c{i}": {"content": f"t{i}"} for i in range(5)})

    assert [len(c["texts"]) for c in emb.calls] == [2, 2, 1]
    assert len(fake_redis.hashes) == 5


async def test_upsert_logs_and_skips_when_embedding_count_mismatch(storage, fake_redis, caplog):
    storage.embedding_func = FakeEmbedding(dim=1024, rows=[[0.0] * 1024])  # 2 条数据只回 1 条
    storage._client = fake_redis

    with caplog.at_level("ERROR", logger="lightrag"):
        await storage.upsert({"a": {"content": "x"}, "b": {"content": "y"}})

    assert fake_redis.hashes == {}
    assert any("embedding 数量与数据不一致" in r.message for r in caplog.records)


async def test_upsert_skips_wrong_dimension_row(storage, fake_redis, caplog):
    storage.embedding_func = FakeEmbedding(dim=1024, rows=[[0.0] * 1024, [0.0] * 512])
    storage._client = fake_redis

    with caplog.at_level("ERROR", logger="lightrag"):
        await storage.upsert({"good": {"content": "x"}, "bad": {"content": "y"}})

    assert set(fake_redis.hashes) == {"rag:vblob:_:chunks:good"}
    assert any("维度不符" in r.message for r in caplog.records)


async def test_upsert_json_encodes_non_string_metadata(storage, fake_redis):
    storage._client = fake_redis
    await storage.upsert({"a": {"content": "x", "entity_name": {"nested": ["中文"]}}})
    rec = fake_redis.hashes["rag:vblob:_:chunks:a"]
    # ensure_ascii=False：中文原样保留，便于 redis-cli 直接查看
    assert rec["entity_name"] == '{"nested": ["中文"]}'


async def test_upsert_empty_data_is_noop(storage, fake_redis):
    storage._client = fake_redis
    await storage.upsert({})
    assert fake_redis.commands == []


async def test_upsert_requires_initialize(storage):
    with pytest.raises(RuntimeError, match="initialize"):
        await storage.upsert({"a": {"content": "x"}})


# ---------------------------------------------------------------- 查询


async def test_query_return_count_matches_fields_and_has_limit(storage, fake_redis):
    fake_redis.responses["FT.SEARCH"] = {
        b"results": [
            {b"id": b"rag:vblob:_:chunks:c1", b"extra_attributes": {b"distance": b"0.1", b"content": b"x"}}
        ]
    }
    storage._client = fake_redis

    out = await storage.query("q", top_k=7, query_embedding=[0.0] * 1024)

    cmd = fake_redis.commands[0]
    assert cmd[0] == "FT.SEARCH"
    assert cmd[1] == "rag-idx:_:chunks"
    # KNN 取候选数（top_k 的 3 倍），而不是 top_k 本身：
    # 历史缺陷是两者同值，阈值过滤后返回条数恒少于请求值。
    assert cmd[2] == "*=>[KNN 21 @vec $BLOB AS distance]"

    i = cmd.index("RETURN")
    declared = int(cmd[i + 1])
    fields = cmd[i + 2:i + 2 + declared]
    assert declared == len(fields)
    assert fields[0] == "distance"
    assert "content" in fields
    assert "entity_name" in fields
    # 历史缺陷：不写 LIMIT 时 top_k>10 会被静默截断。
    # LIMIT 取候选数（21），过滤后再由 Python 侧截断到 top_k=7。
    assert cmd[i + 2 + declared:i + 6 + declared] == ["LIMIT", "0", "21", "DIALECT"]
    assert cmd[-1] == "2"
    assert out[0]["id"] == "c1"
    assert out[0]["distance"] == pytest.approx(0.9)


async def test_query_return_count_scales_with_meta_fields(make_storage, fake_redis):
    storage = make_storage(
        namespace="relationships",
        meta_fields={"content", "src_id", "tgt_id", "file_path", "full_doc_id"},
    )
    fake_redis.responses["FT.SEARCH"] = {b"results": []}
    storage._client = fake_redis

    await storage.query("q", top_k=5, query_embedding=[0.0] * 1024)

    cmd = fake_redis.commands[0]
    i = cmd.index("RETURN")
    assert int(cmd[i + 1]) == 6  # distance + content + 4 个 meta


async def test_query_uses_embedding_func_when_not_supplied(storage, fake_redis):
    fake_redis.responses["FT.SEARCH"] = {b"results": []}
    storage._client = fake_redis

    await storage.query("hello", top_k=3)

    assert storage.embedding_func.calls[-1] == {"texts": ["hello"], "context": "query"}


async def test_query_rejects_wrong_embedding_dim(storage, fake_redis, caplog):
    storage._client = fake_redis
    with caplog.at_level("ERROR", logger="lightrag"):
        out = await storage.query("q", top_k=3, query_embedding=[0.0] * 16)
    assert out == []
    assert fake_redis.commands == []
    assert any("query 向量维度不符" in r.message for r in caplog.records)


async def test_query_clamps_top_k_to_at_least_one(storage, fake_redis):
    fake_redis.responses["FT.SEARCH"] = {b"results": []}
    storage._client = fake_redis
    await storage.query("q", top_k=0, query_embedding=[0.0] * 1024)
    cmd = fake_redis.commands[0]
    # top_k=0 被夹到 1，候选数 = max(1*3, 1) = 3
    assert cmd[2] == "*=>[KNN 3 @vec $BLOB AS distance]"


async def test_query_truncates_to_top_k_after_threshold(storage, fake_redis):
    """阈值过滤后仍必须截断到调用方要求的 top_k 条。

    回归背景：原实现 KNN k == LIMIT k == top_k，阈值砍掉部分结果后返回条数
    恒少于 top_k。现在 KNN 多取候选，过滤后在 Python 侧截断。
    """
    # 构造 5 条命中：前 2 条距离 > 0.9（similarity < 0.1 阈值，会被过滤）
    results = []
    for i in range(5):
        dist = "0.95" if i < 2 else "0.1"
        results.append({
            b"id": f"rag:vblob:_:chunks:c{i}".encode(),
            b"extra_attributes": {b"distance": dist.encode(), b"content": f"x{i}".encode()},
        })
    fake_redis.responses["FT.SEARCH"] = {b"results": results}
    storage._client = fake_redis

    out = await storage.query("q", top_k=2, query_embedding=[0.0] * 1024)

    # 5 条里 2 条被阈值过滤，剩 3 条，再截断到 top_k=2
    assert len(out) == 2
    assert [h["id"] for h in out] == ["c2", "c3"]


# ---------------------------------------------------------------- 读取 / 删除


async def test_get_by_id_and_ids_hide_vector(storage, fake_redis):
    storage._client = fake_redis
    fake_redis.hashes["rag:vblob:_:chunks:a"] = {
        b"vec": b"\x00" * 4096,
        b"content": b"hello",
        b"workspace_id": b"_",
    }

    one = await storage.get_by_id("a")
    assert one == {"id": "a", "content": "hello"}
    assert await storage.get_by_id("missing") is None

    many = await storage.get_by_ids(["a", "missing"])
    assert [r["id"] for r in many] == ["a"]
    assert await storage.get_by_ids([]) == []


async def test_get_vectors_by_ids_roundtrips_float32(storage, fake_redis):
    storage._client = fake_redis
    vector = [0.25, -0.5, 1.0]
    fake_redis.hashes["rag:vblob:_:chunks:a"] = {"vec": _to_f32(vector)}

    out = await storage.get_vectors_by_ids(["a", "missing"])
    assert out == {"a": vector}
    assert await storage.get_vectors_by_ids([]) == {}


async def test_get_vectors_by_ids_skips_corrupt_blob(storage, fake_redis):
    storage._client = fake_redis
    fake_redis.hashes["rag:vblob:_:chunks:bad"] = {"vec": b"\x00\x01\x02"}  # 不是 4 字节整数倍
    assert await storage.get_vectors_by_ids(["bad"]) == {}


async def test_delete_uses_prefixed_keys(storage, fake_redis):
    storage._client = fake_redis
    await storage.delete(["a", "b"])
    assert fake_redis.deleted == ["rag:vblob:_:chunks:a", "rag:vblob:_:chunks:b"]
    fake_redis.deleted.clear()
    await storage.delete([])
    assert fake_redis.deleted == []


async def test_delete_entity_uses_tag_search_then_del(storage, fake_redis):
    fake_redis.responses["FT.SEARCH"] = [1, b"rag:vblob:_:chunks:chunk-1"]
    storage._client = fake_redis

    await storage.delete_entity("ACME-1")

    cmd = fake_redis.commands[0]
    assert cmd[0] == "FT.SEARCH"
    assert cmd[2] == r"@entity_name:{ACME\-1}"
    assert "NOCONTENT" in cmd
    assert fake_redis.deleted == ["rag:vblob:_:chunks:chunk-1"]


async def test_delete_entity_ignores_field_not_in_meta(storage, fake_redis):
    storage._client = fake_redis
    await storage.delete_entity_relation("ACME")
    # 默认 meta_fields 没有 src_id / tgt_id → 不应发任何命令
    assert fake_redis.commands == []


async def test_delete_entity_empty_name_is_noop(storage, fake_redis):
    storage._client = fake_redis
    await storage.delete_entity("")
    await storage.delete_entity_relation("")
    assert fake_redis.commands == []


async def test_delete_by_tag_swallows_response_error(storage, fake_redis):
    def boom(*_a):
        raise ResponseError("Syntax error")

    fake_redis.responses["FT.SEARCH"] = boom
    storage._client = fake_redis
    await storage.delete_entity("ACME")  # 不抛
    assert fake_redis.deleted == []


@pytest.mark.parametrize(
    ("name", "expected"),
    [
        ("ACME-1", r"@entity_name:{ACME\-1}"),
        ("ACME 中国", r"@entity_name:{ACME\ 中国}"),
        ("A|B", r"@entity_name:{A\|B}"),
    ],
)
async def test_delete_by_tag_escapes_supported_chars(storage, fake_redis, name, expected):
    fake_redis.responses["FT.SEARCH"] = [0]
    storage._client = fake_redis
    await storage.delete_entity(name)
    assert fake_redis.commands[0][2] == expected


@pytest.mark.parametrize(
    "name",
    [
        "ACME (中国) 有限公司",
        "100%纯棉",
        "x{y}",
        "A,B",
        'say "hi"',
        "a<b>c",
    ],
)
def test_delete_by_tag_escapes_all_redisearch_specials(name):
    """RediSearch TAG 查询的**全部**语法字符都必须转义。

    回归背景：原实现只转了 `-` `|` 空格三个，于是
      `ACME (中国) 有限公司` → Syntax error near 有限公司
      `100%纯棉`             → Syntax error near 纯棉
      `x{y}`                 → Syntax error near x
    异常被 `except ResponseError` 吞成 WARNING 后直接返回，
    `delete_entity` **静默不删**，向量留在库里而实体已从图上消失（孤儿数据）。
    已对真实 Redis Stack 实测确认这 6 个输入修复前全部报 Syntax error。
    """
    escaped = RedisVectorStorage._escape_tag(name)
    # 转义后不应再有裸的语法字符：每个特殊字符前面必须紧跟反斜杠
    for i, ch in enumerate(escaped):
        if ch in RedisVectorStorage._TAG_SPECIALS and ch != "\\":
            assert i > 0 and escaped[i - 1] == "\\", f"{name!r} 的 {ch!r} 未转义: {escaped!r}"
    # 反斜杠自身也要转义（否则会被当成下一个字符的转义前缀）
    assert RedisVectorStorage._escape_tag("a\\b") == "a\\\\b"


async def test_delete_by_tag_sends_escaped_query_to_redis(storage, fake_redis):
    """端到端确认真正发给 Redis 的查询串已转义（不只是工具函数对）。"""
    fake_redis.responses["FT.SEARCH"] = [0]
    storage._client = fake_redis

    await storage.delete_entity("ACME (中国) 有限公司")

    query = fake_redis.commands[0][2]
    assert query == r"@entity_name:{ACME\ \(中国\)\ 有限公司}"


# ---------------------------------------------------------------- 生命周期


async def test_drop_uses_dd_and_recreates_index(storage, fake_redis):
    fake_redis.responses["FT.INFO"] = unknown_index
    storage._client = fake_redis

    result = await storage.drop()

    assert result["status"] == "success"
    assert fake_redis.commands[0] == ["FT.DROPINDEX", "rag-idx:_:chunks", "DD"]
    assert fake_redis.commands[1][0] == "FT.INFO"
    assert fake_redis.commands[2][0] == "FT.CREATE"
    assert storage._index_ready is True


async def test_drop_tolerates_missing_index(storage, fake_redis):
    def drop_missing(*_a):
        raise ResponseError("Unknown index name")

    fake_redis.responses["FT.INFO"] = unknown_index
    fake_redis.responses["FT.DROPINDEX"] = drop_missing
    storage._client = fake_redis

    result = await storage.drop()
    assert result["status"] == "success"
    assert any(c[0] == "FT.CREATE" for c in fake_redis.commands)


async def test_drop_reports_unexpected_error_without_recreating(storage, fake_redis):
    def boom(*_a):
        raise ResponseError("ERR unknown command")

    fake_redis.responses["FT.DROPINDEX"] = boom
    storage._client = fake_redis

    result = await storage.drop()
    assert result["status"] == "error"
    assert "unknown command" in result["message"]
    assert not any(c[0] == "FT.CREATE" for c in fake_redis.commands)


async def test_finalize_closes_client(storage, fake_redis):
    storage._client = fake_redis
    await storage.finalize()
    assert fake_redis.closed is True
    assert storage._client is None


async def test_finalize_swallows_close_errors(storage):
    class BrokenClient:
        async def aclose(self):
            raise RuntimeError("socket already closed")

    storage._client = BrokenClient()
    await storage.finalize()
    assert storage._client is None


async def test_index_done_callback_is_noop(storage):
    assert await storage.index_done_callback() is None
