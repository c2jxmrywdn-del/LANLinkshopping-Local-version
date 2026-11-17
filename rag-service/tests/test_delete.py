"""`RagEngine.adelete` 的清理逻辑单测（不连真实 LightRAG / Redis）。

为什么用自建 fake 而不是跑真 LightRAG
------------------------------------
`adelete` 的价值全在「上游删除漏掉什么、我们怎么补」。真实 LightRAG 的删除
路径需要 LLM 抽取（几十秒一次），无法在单测里覆盖「doc_status 已丢」「上游
返回 not_found」「共享实体仍有其它来源」这些关键分支。这里用最小 fake 精确
构造每种残留形态，锁住清理规则本身。
"""
from __future__ import annotations

import json

import pytest

from app import engine as engine_mod
from app.engine import RagEngine

SEP = "<SEP>"


class FakeKV:
    """最小 KV 替身：get_by_id / get_by_ids / delete / upsert。"""

    def __init__(self, data: dict | None = None, name: str = "kv"):
        self.data = dict(data or {})
        self.name = name
        self.deleted: list[str] = []
        self.upserted: list[dict] = []

    async def get_by_id(self, key):
        rec = self.data.get(key)
        return dict(rec) if rec else None

    async def get_by_ids(self, ids):
        return [dict(self.data[i]) if i in self.data else None for i in ids]

    async def delete(self, ids):
        for i in ids:
            self.deleted.append(i)
            self.data.pop(i, None)

    async def upsert(self, payload):
        self.upserted.append(payload)
        self.data.update(payload)


class FakeVDB(FakeKV):
    """向量库替身：接口与 KV 一致。"""


class FakeGraph:
    """NetworkX 图的替身，只保留 adelete 用到的读写方法。"""

    def __init__(self, nodes: dict | None = None, edges: dict | None = None):
        self.nodes = dict(nodes or {})
        self.edges = dict(edges or {})
        self.removed_nodes: list[str] = []
        self.removed_edges: list[tuple[str, str]] = []

    async def get_all_nodes(self):
        return [{"id": n, **d} for n, d in self.nodes.items()]

    async def get_all_edges(self):
        return [{"source": u, "target": v, **d} for (u, v), d in self.edges.items()]

    async def has_node(self, name):
        return name in self.nodes

    async def has_edge(self, src, tgt):
        return (src, tgt) in self.edges

    async def get_node(self, name):
        return self.nodes.get(name)

    async def get_edge(self, src, tgt):
        return self.edges.get((src, tgt))

    async def remove_nodes(self, names):
        for n in names:
            self.removed_nodes.append(n)
            self.nodes.pop(n, None)
            for key in [k for k in self.edges if n in k]:
                self.edges.pop(key, None)

    async def remove_edges(self, pairs):
        for pair in pairs:
            self.removed_edges.append(pair)
            self.edges.pop(pair, None)


class FakeLightRAG:
    """把 adelete 依赖的属性聚在一起，便于按用例替换。"""

    def __init__(self, *, doc_status=None, full_docs=None, text_chunks=None,
                 full_entities=None, full_relations=None, entity_chunks=None,
                 relation_chunks=None, chunks_vdb=None, entities_vdb=None,
                 relationships_vdb=None, graph=None, delete_result=None,
                 delete_error: Exception | None = None):
        self.doc_status = doc_status if doc_status is not None else FakeKV(name="doc_status")
        self.full_docs = full_docs if full_docs is not None else FakeKV(name="full_docs")
        self.text_chunks = text_chunks if text_chunks is not None else FakeKV(name="text_chunks")
        self.full_entities = full_entities if full_entities is not None else FakeKV(name="full_entities")
        self.full_relations = full_relations if full_relations is not None else FakeKV(name="full_relations")
        self.entity_chunks = entity_chunks if entity_chunks is not None else FakeKV(name="entity_chunks")
        self.relation_chunks = relation_chunks if relation_chunks is not None else FakeKV(name="relation_chunks")
        self.chunks_vdb = chunks_vdb if chunks_vdb is not None else FakeVDB(name="chunks_vdb")
        self.entities_vdb = entities_vdb if entities_vdb is not None else FakeVDB(name="entities_vdb")
        self.relationships_vdb = relationships_vdb if relationships_vdb is not None else FakeVDB(name="relationships_vdb")
        self.chunk_entity_relation_graph = graph if graph is not None else FakeGraph()
        self._delete_result = delete_result
        self._delete_error = delete_error
        self.delete_calls: list[str] = []
        self.insert_done_calls = 0

    async def adelete_by_doc_id(self, doc_id):
        self.delete_calls.append(doc_id)
        if self._delete_error is not None:
            raise self._delete_error
        return self._delete_result

    async def _insert_done(self):
        self.insert_done_calls += 1


class _Result:
    def __init__(self, status: str, message: str = ""):
        self.status = status
        self.message = message


class FakeRag:
    def __init__(self, lightrag):
        self.lightrag = lightrag


@pytest.fixture
def make_engine(monkeypatch):
    """构造一个 get() 直接返回 fake 的引擎，不触碰 RAGAnything 初始化。"""

    def _make(lightrag: FakeLightRAG) -> RagEngine:
        eng = RagEngine()
        rag = FakeRag(lightrag)

        async def _get():
            return rag

        monkeypatch.setattr(eng, "get", _get)
        return eng

    return _make


# ---------------------------------------------------------------- 纯函数规则


def test_solely_from_doc_requires_all_refs_inside_chunk_set():
    rec = {"source_id": f"chunk-a{SEP}chunk-b"}
    assert RagEngine._solely_from_doc(rec, {"chunk-a", "chunk-b"}) is True
    # 只要还有一个来源不在删除集合里，就不能整条删——否则会误删其它文档的内容
    assert RagEngine._solely_from_doc(rec, {"chunk-a"}) is False


def test_solely_from_doc_false_when_source_id_missing():
    # source_id 为空时无法判定归属，必须保守不删
    assert RagEngine._solely_from_doc({}, {"chunk-a"}) is False
    assert RagEngine._solely_from_doc({"source_id": ""}, {"chunk-a"}) is False


def test_record_belongs_to_doc_uses_chunk_refs_and_file_path():
    assert RagEngine._record_belongs_to_doc({"source_id": "chunk-a"}, "d1", {"chunk-a"})
    # source_id 被截断时退回 file_path 判断
    assert RagEngine._record_belongs_to_doc({"file_path": "d1.parsed"}, "d1", set())
    assert not RagEngine._record_belongs_to_doc({"source_id": "chunk-z"}, "d1", {"chunk-a"})


def test_relation_vdb_ids_cover_both_directions():
    ids = RagEngine._relation_vdb_ids("A", "B")
    assert len(ids) == 2
    assert ids[0] != ids[1]
    # 与上游 compute_mdhash_id 口径一致（反向也要能删到）
    from lightrag.utils import compute_mdhash_id

    assert ids[0] == compute_mdhash_id("AB", prefix="rel-")
    assert ids[1] == compute_mdhash_id("BA", prefix="rel-")


# ---------------------------------------------------------------- 正常路径


async def test_adelete_cleans_all_stores(make_engine, monkeypatch):
    monkeypatch.setattr(engine_mod.settings, "working_dir", "/nonexistent-rag")
    lr = FakeLightRAG(
        doc_status=FakeKV({"d1": {"status": "processed", "chunks_list": ["c1", "c2"]}}),
        full_docs=FakeKV({"d1": {"content": "x"}}),
        text_chunks=FakeKV({
            "c1": {"full_doc_id": "d1"},
            "c2": {"full_doc_id": "d1"},
            "c9": {"full_doc_id": "other"},
        }),
        full_entities=FakeKV({"d1": {"entity_names": ["Acme"]}}),
        full_relations=FakeKV({"d1": {"relation_pairs": [["Acme", "Beta"]]}}),
        entity_chunks=FakeKV({"Acme": {"chunk_ids": ["c1", "c2"]}}),
        relation_chunks=FakeKV({f"Acme{SEP}Beta": {"chunk_ids": ["c1"]}}),
        chunks_vdb=FakeVDB({"c1": {"id": "c1"}, "c2": {"id": "c2"}}),
        entities_vdb=FakeVDB({}),
        relationships_vdb=FakeVDB({}),
        graph=FakeGraph(
            nodes={"Acme": {"source_id": f"c1{SEP}c2", "file_path": "d1.parsed"}},
            edges={("Acme", "Beta"): {"source_id": "c1", "file_path": "d1.parsed"}},
        ),
        delete_result=_Result("success"),
    )
    eng = make_engine(lr)
    out = await eng.adelete("d1")

    assert out["docId"] == "d1"
    assert out["existed"] is True
    assert out["residual"] is False
    assert lr.delete_calls == ["d1"]
    # KV 五件套全清
    assert "d1" not in lr.doc_status.data
    assert "d1" not in lr.full_docs.data
    assert "d1" not in lr.full_entities.data
    assert "d1" not in lr.full_relations.data
    assert "c1" not in lr.text_chunks.data and "c2" not in lr.text_chunks.data
    # 其它文档的 chunk 不能被牵连
    assert "c9" in lr.text_chunks.data
    # 向量与图
    assert lr.chunks_vdb.data == {}
    assert lr.chunk_entity_relation_graph.removed_nodes == ["Acme"]
    assert lr.chunk_entity_relation_graph.removed_edges == [("Acme", "Beta")]
    # 落盘必须调用，否则内存改了磁盘没改
    assert lr.insert_done_calls == 1
    assert out["deleted"]["text_chunks"] == 2
    assert out["deleted"]["graph_nodes"] == 1
    assert out["deleted"]["graph_edges"] == 1


async def test_adelete_keeps_shared_entity_node(make_engine, monkeypatch):
    """共享实体仍有其它来源时，节点与向量都不能删。"""
    monkeypatch.setattr(engine_mod.settings, "working_dir", "/nonexistent-rag")
    from lightrag.utils import compute_mdhash_id

    ent_id = compute_mdhash_id("Acme", prefix="ent-")
    lr = FakeLightRAG(
        doc_status=FakeKV({"d1": {"status": "processed", "chunks_list": ["c1"]}}),
        text_chunks=FakeKV({"c1": {"full_doc_id": "d1"}, "c2": {"full_doc_id": "d2"}}),
        full_entities=FakeKV({"d1": {"entity_names": ["Acme"]}}),
        entities_vdb=FakeVDB({ent_id: {"entity_name": "Acme"}}),
        graph=FakeGraph(nodes={"Acme": {"source_id": f"c1{SEP}c2"}}),
        delete_result=_Result("success"),
    )
    eng = make_engine(lr)
    out = await eng.adelete("d1")

    assert lr.chunk_entity_relation_graph.removed_nodes == []
    assert "Acme" in lr.chunk_entity_relation_graph.nodes
    assert ent_id in lr.entities_vdb.data
    assert out["deleted"]["graph_nodes"] == 0
    assert out["deleted"]["entities_vdb"] == 0
    assert out["residual"] is False


async def test_adelete_drops_orphan_entity_vector(make_engine, monkeypatch):
    """图里已无该实体，但向量索引还留着 → 必须补删（孤儿向量）。"""
    monkeypatch.setattr(engine_mod.settings, "working_dir", "/nonexistent-rag")
    from lightrag.utils import compute_mdhash_id

    ent_id = compute_mdhash_id("Ghost", prefix="ent-")
    lr = FakeLightRAG(
        doc_status=FakeKV({"d1": {"status": "processed", "chunks_list": ["c1"]}}),
        text_chunks=FakeKV({"c1": {"full_doc_id": "d1"}}),
        full_entities=FakeKV({"d1": {"entity_names": ["Ghost"]}}),
        entities_vdb=FakeVDB({ent_id: {"entity_name": "Ghost"}}),
        graph=FakeGraph(nodes={}),  # 图里已经没有这个实体
        delete_result=_Result("success"),
    )
    eng = make_engine(lr)
    out = await eng.adelete("d1")

    assert ent_id not in lr.entities_vdb.data
    assert out["deleted"]["entities_vdb"] == 1


# ---------------------------------------------------------------- 幂等 / 异常


async def test_adelete_is_idempotent_for_unknown_doc(make_engine, monkeypatch):
    """文档不存在时返回 200 语义（existed=False），不抛错、不误删。"""
    monkeypatch.setattr(engine_mod.settings, "working_dir", "/nonexistent-rag")
    lr = FakeLightRAG(delete_result=_Result("not_found", "Document missing not found."))
    eng = make_engine(lr)
    out = await eng.adelete("missing")

    assert out["existed"] is False
    assert out["residual"] is False
    assert sum(out["deleted"].values()) == 0
    assert lr.delete_calls == ["missing"]


async def test_adelete_second_call_is_noop(make_engine, monkeypatch):
    monkeypatch.setattr(engine_mod.settings, "working_dir", "/nonexistent-rag")
    lr = FakeLightRAG(
        doc_status=FakeKV({"d1": {"status": "processed", "chunks_list": ["c1"]}}),
        text_chunks=FakeKV({"c1": {"full_doc_id": "d1"}}),
        delete_result=_Result("success"),
    )
    eng = make_engine(lr)
    first = await eng.adelete("d1")
    second = await eng.adelete("d1")

    assert first["existed"] is True
    assert second["existed"] is False
    assert sum(second["deleted"].values()) == 0
    assert second["residual"] is False


async def test_adelete_recovers_when_doc_status_already_gone(make_engine, monkeypatch):
    """doc_status 已被上次失败删除清掉时，仍要按 text_chunks 找到残留并删干净。

    这正是线上「调了删除、接口返回 success，但 hybrid 还能查到」的主因：
    上游只看 doc_status，行没了就 `not_found` 直接返回。
    """
    monkeypatch.setattr(engine_mod.settings, "working_dir", "/nonexistent-rag")
    lr = FakeLightRAG(
        doc_status=FakeKV({}),  # 行已经没了
        text_chunks=FakeKV({
            "c1": {"full_doc_id": "d1"},
            "c2": {"full_doc_id": "d1"},
        }),
        chunks_vdb=FakeVDB({"c1": {"id": "c1"}, "c2": {"id": "c2"}}),
        graph=FakeGraph(
            nodes={"Orphan": {"source_id": f"c1{SEP}c2", "file_path": "d1.parsed"}},
        ),
        delete_result=_Result("not_found"),
    )
    eng = make_engine(lr)
    out = await eng.adelete("d1")

    assert out["existed"] is True  # 通过 text_chunks 判定「曾经存在」
    assert lr.text_chunks.data == {}
    assert lr.chunks_vdb.data == {}
    assert lr.chunk_entity_relation_graph.removed_nodes == ["Orphan"]
    assert out["deleted"]["text_chunks"] == 2
    # 必须给出「上游没找到、我们按残留清理」的提示，便于运维理解日志
    assert any("doc_status" in w for w in out["warnings"])


async def test_adelete_continues_when_upstream_raises(make_engine, monkeypatch):
    monkeypatch.setattr(engine_mod.settings, "working_dir", "/nonexistent-rag")
    lr = FakeLightRAG(
        doc_status=FakeKV({"d1": {"status": "processed", "chunks_list": ["c1"]}}),
        text_chunks=FakeKV({"c1": {"full_doc_id": "d1"}}),
        delete_error=RuntimeError("pipeline busy"),
    )
    eng = make_engine(lr)
    out = await eng.adelete("d1")

    assert lr.text_chunks.data == {}
    assert any("pipeline busy" in w for w in out["warnings"])


async def test_adelete_reports_residual_when_delete_silently_fails(make_engine, monkeypatch):
    """删除「说成功但没真删」时必须 residual=True，不能假装干净。"""
    monkeypatch.setattr(engine_mod.settings, "working_dir", "/nonexistent-rag")

    class StubbornKV(FakeKV):
        async def delete(self, ids):
            return None  # 静默不删

    lr = FakeLightRAG(
        doc_status=StubbornKV({"d1": {"status": "processed", "chunks_list": ["c1"]}}),
        text_chunks=FakeKV({"c1": {"full_doc_id": "d1"}}),
        delete_result=_Result("success"),
    )
    eng = make_engine(lr)
    out = await eng.adelete("d1")

    assert out["residual"] is True
    assert any("doc_status" in w for w in out["warnings"])


async def test_adelete_rejects_empty_doc_id(make_engine, monkeypatch):
    monkeypatch.setattr(engine_mod.settings, "working_dir", "/nonexistent-rag")
    eng = make_engine(FakeLightRAG(delete_result=_Result("success")))
    with pytest.raises(ValueError, match="docId"):
        await eng.adelete("   ")


async def test_adelete_trims_doc_id(make_engine, monkeypatch):
    monkeypatch.setattr(engine_mod.settings, "working_dir", "/nonexistent-rag")
    lr = FakeLightRAG(delete_result=_Result("not_found"))
    eng = make_engine(lr)
    out = await eng.adelete("  d1  ")
    assert out["docId"] == "d1"
    assert lr.delete_calls == ["d1"]


# ---------------------------------------------------------------- 追踪表清理


async def test_purge_chunk_tracking_keeps_remaining_refs(make_engine, monkeypatch):
    """entity_chunks 里还有别的 chunk 时只做减法，不整行删。"""
    monkeypatch.setattr(engine_mod.settings, "working_dir", "/nonexistent-rag")
    lr = FakeLightRAG(
        doc_status=FakeKV({"d1": {"status": "processed", "chunks_list": ["c1"]}}),
        text_chunks=FakeKV({"c1": {"full_doc_id": "d1"}, "c2": {"full_doc_id": "d2"}}),
        full_entities=FakeKV({"d1": {"entity_names": ["Acme"]}}),
        entity_chunks=FakeKV({"Acme": {"chunk_ids": ["c1", "c2"], "count": 2}}),
        graph=FakeGraph(nodes={"Acme": {"source_id": f"c1{SEP}c2"}}),
        delete_result=_Result("success"),
    )
    eng = make_engine(lr)
    await eng.adelete("d1")

    assert lr.entity_chunks.data["Acme"]["chunk_ids"] == ["c2"]
    assert lr.entity_chunks.data["Acme"]["count"] == 1


async def test_purge_chunk_tracking_deletes_row_without_refs(make_engine, monkeypatch):
    monkeypatch.setattr(engine_mod.settings, "working_dir", "/nonexistent-rag")
    lr = FakeLightRAG(
        doc_status=FakeKV({"d1": {"status": "processed", "chunks_list": ["c1"]}}),
        text_chunks=FakeKV({"c1": {"full_doc_id": "d1"}}),
        full_entities=FakeKV({"d1": {"entity_names": ["Acme"]}}),
        entity_chunks=FakeKV({"Acme": {"chunk_ids": ["c1"], "count": 1}}),
        delete_result=_Result("success"),
    )
    eng = make_engine(lr)
    await eng.adelete("d1")

    assert "Acme" not in lr.entity_chunks.data


# ---------------------------------------------------------------- 查询缓存


async def test_adelete_purges_orphan_graph_nodes_from_prior_failure(make_engine, monkeypatch):
    """上一轮删除「删了 chunk、没删图」留下的孤儿节点必须被兜底清掉。

    这是「删除后 hybrid/global 仍能查到」的最后一道残留：节点 source_id 全部
    指向已不存在的 chunk，它不属于任何现存文档，却仍参与检索。
    """
    monkeypatch.setattr(engine_mod.settings, "working_dir", "/nonexistent-rag")
    from lightrag.utils import compute_mdhash_id

    ghost_ent = compute_mdhash_id("Ghost Node", prefix="ent-")
    lr = FakeLightRAG(
        # 本次删除的文档本身没有残留，只有「历史孤儿」
        doc_status=FakeKV({"d1": {"status": "processed", "chunks_list": ["c1"]}}),
        text_chunks=FakeKV({"c1": {"full_doc_id": "d1"}, "c2": {"full_doc_id": "d2"}}),
        # 向量自身带 source_id，孤儿向量清扫靠它判定（与真实数据形态一致）
        entities_vdb=FakeRedisVDB({
            ghost_ent: {"entity_name": "Ghost Node", "source_id": "chunk-deleted-long-ago"},
        }),
        graph=FakeGraph(
            nodes={
                "Ghost Node": {"source_id": "chunk-deleted-long-ago"},
                # 来源仍存在的节点必须保留
                "Live Node": {"source_id": "c2"},
                # source_id 为空的节点无法判定，必须保留
                "Unknown Node": {},
            },
            edges={
                ("Ghost Node", "Live Node"): {"source_id": "chunk-deleted-long-ago"},
                ("Live Node", "Unknown Node"): {"source_id": "c2"},
            },
        ),
        delete_result=_Result("success"),
    )
    eng = make_engine(lr)
    out = await eng.adelete("d1")

    assert "Ghost Node" not in lr.chunk_entity_relation_graph.nodes
    assert "Live Node" in lr.chunk_entity_relation_graph.nodes
    assert "Unknown Node" in lr.chunk_entity_relation_graph.nodes
    assert ("Live Node", "Unknown Node") in lr.chunk_entity_relation_graph.edges
    assert ghost_ent not in lr.entities_vdb.data
    assert out["deleted"]["orphan_graph_nodes"] == 1
    assert out["deleted"]["orphan_graph_edges"] == 1
    assert out["residual"] is False


async def test_adelete_orphan_sweep_skips_when_chunk_table_unavailable(make_engine, monkeypatch):
    """拿不到 text_chunks 全表时不能盲删图节点（否则可能误删有效实体）。"""
    monkeypatch.setattr(engine_mod.settings, "working_dir", "/nonexistent-rag")

    class OpaqueKV(FakeKV):
        """模拟外部 KV：没有 _data/data 可遍历。"""

        def __init__(self):
            super().__init__({})
            self._hidden = {}

    lr = FakeLightRAG(
        doc_status=FakeKV({"d1": {"status": "processed", "chunks_list": ["c1"]}}),
        text_chunks=OpaqueKV(),
        graph=FakeGraph(nodes={"Some Node": {"source_id": "chunk-x"}}),
        delete_result=_Result("success"),
    )
    eng = make_engine(lr)
    out = await eng.adelete("d1")

    assert "Some Node" in lr.chunk_entity_relation_graph.nodes
    assert out["deleted"].get("orphan_graph_nodes", 0) == 0


class FakeRedisVDB(FakeVDB):
    """模拟 Redis 向量库：提供 index_name / _client 以走 FT.SEARCH 反查。"""

    def __init__(self, data: dict | None = None, *, rows_by_path: dict | None = None):
        super().__init__(data)
        self.index_name = "rag-idx:_:entities"
        self.key_prefix = "rag:vblob:_:entities:"
        self.rows_by_path = dict(rows_by_path or {})
        self._client = self
        self.search_commands: list[list] = []

    async def execute_command(self, *args):
        self.search_commands.append(list(args))
        query = args[2] if len(args) > 2 else ""
        # 取出 @file_path:{...} 里的值（测试只关心命中哪一批 key）
        doc = None
        for needle, value in self.rows_by_path.items():
            if needle in query:
                doc = value
        return [len(doc or []), *[self.key_prefix + i for i in (doc or [])]]

    async def scan_iter(self, match, count=1000):
        for vid in list(self.data):
            yield self.key_prefix + vid

    async def hgetall(self, key):
        vid = key[len(self.key_prefix):] if key.startswith(self.key_prefix) else key
        return dict(self.data.get(vid) or {})


async def test_adelete_purges_entity_vectors_by_file_path(make_engine, monkeypatch):
    """实体名被改写、按 hash 算不出向量 id 时，靠 file_path 兜底删除。"""
    monkeypatch.setattr(engine_mod.settings, "working_dir", "/nonexistent-rag")
    from lightrag.utils import compute_mdhash_id

    renamed = "ent-renamed-by-llm"
    lr = FakeLightRAG(
        doc_status=FakeKV({"d1": {"status": "processed", "chunks_list": ["c1"]}}),
        text_chunks=FakeKV({"c1": {"full_doc_id": "d1"}}),
        full_entities=FakeKV({"d1": {"entity_names": ["Original Name"]}}),
        entities_vdb=FakeRedisVDB(
            {renamed: {"entity_name": "Rewritten Name"}},
            rows_by_path={"d1": [renamed]},
        ),
        graph=FakeGraph(nodes={}),  # 图里已经没有该实体
        delete_result=_Result("success"),
    )
    eng = make_engine(lr)
    out = await eng.adelete("d1")

    assert renamed not in lr.entities_vdb.data
    assert out["deleted"]["entities_vdb"] == 1
    # 确实下发了按 file_path 的 TAG 查询
    assert any("@file_path:" in str(c[2]) for c in lr.entities_vdb.search_commands)


def test_escape_tag_value_covers_redis_specials():
    escaped = RagEngine._escape_tag_value("ACME (中国) 有限公司")
    # 括号与空格必须转义，否则 RediSearch 直接 Syntax error
    assert "\\(" in escaped and "\\)" in escaped and "\\ " in escaped


# ---------------------------------------------------------------- 查询缓存


def test_purge_query_cache_keeps_extract_entries(tmp_path, monkeypatch):
    monkeypatch.setattr(engine_mod.settings, "working_dir", str(tmp_path))
    path = tmp_path / "kv_store_llm_response_cache.json"
    path.write_text(json.dumps({
        "default:query:aaa": {"cache_type": "query", "return": "旧答案"},
        "default:extract:bbb": {"cache_type": "extract", "return": "抽取结果"},
    }), encoding="utf-8")

    removed = RagEngine().purge_query_cache()

    assert removed == 1
    left = json.loads(path.read_text(encoding="utf-8"))
    assert list(left) == ["default:extract:bbb"]


def test_purge_query_cache_missing_file_is_zero(tmp_path, monkeypatch):
    monkeypatch.setattr(engine_mod.settings, "working_dir", str(tmp_path))
    assert RagEngine().purge_query_cache() == 0


def test_purge_query_cache_swallows_corrupt_json(tmp_path, monkeypatch):
    monkeypatch.setattr(engine_mod.settings, "working_dir", str(tmp_path))
    (tmp_path / "kv_store_llm_response_cache.json").write_text("{not json", encoding="utf-8")
    assert RagEngine().purge_query_cache() == 0

