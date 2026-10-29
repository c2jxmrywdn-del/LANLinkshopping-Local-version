package com.lanlink.shopping.module.aikb;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis 向量存储（双引擎）：
 *   原生引擎：Redis Stack（redis/redis-stack-server:7.4.0-v8）含 Query Engine —— FT.CREATE HNSW
 *   向量索引 + FT.SEARCH KNN，毫秒级 ANN 检索；索引按 aikb:vblob:* 前缀 HASH 自动构建。
 *   回退引擎：原生模块缺失（redis:7-alpine 纯内核）→ 全量向量载入内存 + 余弦线性扫描
 *   （本地知识库量级毫秒级）。两条路径写同一份持久化数据，可随镜像升级无缝切换。
 *
 * 性能设计（原生引擎路径）：
 *   - search() 不再 loadCache() 全量载入文档（含向量）后再检索；直接 FT.SEARCH KNN，
 *     查询串 "(@source:{x})=>[KNN $k @vec $qvec AS score]"，PARAMS/DIALECT 2/RETURN 5 字段；
 *   - vblob HASH 除 vec/source 外同时冗余 refId/title/text（RediSearch 未建索引的字段仍随文档
 *     HASH 返回），FT 命中即可直接组装 Hit，无需回表；旧数据缺少这些字段时按命中 key 逐条
 *     HGETALL aikb:doc:{id} 补元数据（不回载全量缓存）。
 *   - 仅回退引擎（RediSearch 不可用）才对缓存做余弦扫描。
 *
 * 存储设计：
 *   HASH  aikb:doc:{id}    → 元数据（source/refId/title/text/vec_base64）—— String 模板写
 *   HASH  aikb:vblob:{id}  → vec=原始 float32 字节 + source TAG + refId/title/text（FT 索引源）
 *   SET   aikb:src:{src} / aikb:all
 */
@Service
public class RedisVectorStore {

    public static final String INDEX_NAME = "aikb-idx";
    private static final String VBLOB_PREFIX = "aikb:vblob:";
    private static final String DOC_PREFIX = "aikb:doc:";
    /** 维度记录键：替代 FT.INFO（其响应含 RESP3 double，Spring 自定义命令输出通道无法解析） */
    private static final String DIM_KEY = "aikb:idx:dim";
    /** FT.SEARCH KNN 命中需要 RETURN 的字段（source 为索引 TAG，其余为 HASH 冗余字段） */
    private static final String[] KNN_RETURN_FIELDS = {"source", "refId", "title", "text", "score"};

    private final StringRedisTemplate redis;
    private final String redisHost;
    private final int redisPort;
    private final String redisPassword;
    private final Object cacheLock = new Object();
    /** 独立 Lettuce 客户端：FT 命令专用（ArrayOutput 解析），不影响 Spring 缓存通道 */
    private volatile io.lettuce.core.RedisClient ftClient;
    private volatile io.lettuce.core.api.StatefulRedisConnection<byte[], byte[]> ftConn;
    private volatile Map<String, CachedDoc> cache;
    private volatile boolean cacheLoaded;

    /** 原生 FT 引擎状态：true=可用（初始乐观值，未探测即尝试建索引）/ false=模块缺失（永久回退暴力扫描） */
    private final AtomicBoolean ftAvailable = new AtomicBoolean(true);
    private volatile boolean ftIndexReady = false;
    private volatile int indexDim = -1;
    /**
     * HNSW 查询期召回参数 EF_RUNTIME（RediSearch KNN 子句）：越大候选池越大、召回越全，延迟略升。
     * 默认 64；可用环境变量 AI_KB_EF_RUNTIME 覆盖。查询时会保证 ≥ topK。
     * 用字段注入（非构造器参数），保持 4 参构造器兼容既有单测。
     */
    @org.springframework.beans.factory.annotation.Value("${AI_KB_EF_RUNTIME:64}")
    private volatile int efRuntime = 64;
    /** 运行期 EF_RUNTIME 查询属性是否被当前 RediSearch 接受（首次失败后自动改用索引默认值） */
    private volatile boolean efRuntimeSupported = true;

    /** 检索延迟统计（管理端观测；最近 200 次滑动 == 全量累计平均，二者等价用于趋势展示） */
    private final AtomicLong searchCount = new AtomicLong();
    private final AtomicLong searchTotalMs = new AtomicLong();
    private final AtomicLong searchLastMs = new AtomicLong();

    public RedisVectorStore(StringRedisTemplate redis,
            @org.springframework.beans.factory.annotation.Value("${spring.data.redis.host:localhost}") String redisHost,
            @org.springframework.beans.factory.annotation.Value("${spring.data.redis.port:6379}") int redisPort,
            @org.springframework.beans.factory.annotation.Value("${spring.data.redis.password:}") String redisPassword) {
        this.redis = redis;
        this.redisHost = redisHost;
        this.redisPort = redisPort;
        this.redisPassword = redisPassword;
    }

    public record CachedDoc(String id, String source, String refId, String title, String text, float[] vector) {}

    public record Hit(String id, String source, String refId, String title, String text, double score) {}

    /** FT.SEARCH KNN 单条命中（已归一为相似度口径，与余弦回退一致） */
    record KnnRow(String id, String source, String refId, String title, String text, double score) {}

    /** 稳定文档 ID：sha256(source|chunkText) 前 24 位（内容变化 → 新文档，旧的由源重灌清理） */
    public static String docId(String source, String chunkText) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((source + "|" + chunkText).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString((source + "|" + chunkText).hashCode());
        }
    }

    /** 写入/更新一块（幂等：同 ID 覆盖）。向量字节同步写入 vblob 供原生 FT 索引。返回文档 id。 */
    public String upsert(String source, String refId, String title, String chunkText, float[] vector) {
        String id = docId(source, chunkText);
        byte[] vec = toBytes(vector);
        String s = source == null ? "" : source;
        String r = refId == null ? "" : refId;
        String t = title == null ? "" : title;
        String x = chunkText == null ? "" : chunkText;
        redis.opsForHash().putAll(DOC_PREFIX + id, Map.of(
                "source", s,
                "refId", r,
                "title", t,
                "text", x,
                "dim", String.valueOf(vector.length),
                "vec", Base64.getEncoder().encodeToString(vec),
                "ingestedAt", LocalDateTime.now().toString()));
        // 原生 FT 索引源：vec 原始 float32 字节 + source TAG + 冗余元数据（FT 命中直接 RETURN，免回表）
        if (ftAvailable()) {
            ensureIndex(vector.length);
            if (ftIndexReady) {
                try {
                    redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) conn -> {
                        var hc = conn.hashCommands();
                        byte[] key = (VBLOB_PREFIX + id).getBytes(StandardCharsets.UTF_8);
                        // 一次 HSET 写全部字段（RediSearch 未建索引的字段也随 HASH 返回，供 FT RETURN 使用）
                        Map<byte[], byte[]> fields = new LinkedHashMap<>();
                        fields.put("vec".getBytes(StandardCharsets.UTF_8), vec);
                        fields.put("source".getBytes(StandardCharsets.UTF_8), s.getBytes(StandardCharsets.UTF_8));
                        fields.put("refId".getBytes(StandardCharsets.UTF_8), r.getBytes(StandardCharsets.UTF_8));
                        fields.put("title".getBytes(StandardCharsets.UTF_8), t.getBytes(StandardCharsets.UTF_8));
                        fields.put("text".getBytes(StandardCharsets.UTF_8), x.getBytes(StandardCharsets.UTF_8));
                        hc.hMSet(key, fields);
                        return null;
                    });
                } catch (Exception e) {
                    markFtBroken(e);
                }
            }
        }
        redis.opsForSet().add("aikb:src:" + source, id);
        redis.opsForSet().add("aikb:all", id);
        synchronized (cacheLock) {
            if (cacheLoaded && cache != null) {
                cache.put(id, new CachedDoc(id, source, refId, title, chunkText, vector));
            }
        }
        return id;
    }

    /** 删除整个来源（重灌前置步骤），并清缓存 */
    public void deleteSource(String source) {
        Set<String> ids = redis.opsForSet().members("aikb:src:" + source);
        if (ids != null && !ids.isEmpty()) {
            redis.delete(ids.stream().map(id -> DOC_PREFIX + id).toList());
            redis.delete(ids.stream().map(id -> VBLOB_PREFIX + id).toList());
            redis.opsForSet().remove("aikb:all", ids.toArray());
        }
        redis.delete("aikb:src:" + source);
        synchronized (cacheLock) {
            if (cache != null) {
                cache.values().removeIf(d -> source.equals(d.source()));
            }
        }
    }

    /** 单条知识视图（不含向量），供管理端列表与编辑 */
    public record DocInfo(String id, String source, String refId, String title, String text) {}

    /** 删除单条文档（按内容哈希 id）。返回是否确有该文档被移除。 */
    public boolean deleteDoc(String id) {
        if (id == null || id.isBlank()) return false;
        CachedDoc existing;
        synchronized (cacheLock) {
            existing = cache != null ? cache.get(id) : null;
        }
        if (existing == null) {
            Map<Object, Object> h = redis.opsForHash().entries(DOC_PREFIX + id);
            if (h == null || h.isEmpty()) return false;
            existing = new CachedDoc(id, str(h.get("source")), str(h.get("refId")),
                    str(h.get("title")), str(h.get("text")), new float[0]);
        }
        redis.delete(DOC_PREFIX + id);
        redis.delete(VBLOB_PREFIX + id);
        redis.opsForSet().remove("aikb:all", id);
        if (existing.source() != null && !existing.source().isBlank()) {
            redis.opsForSet().remove("aikb:src:" + existing.source(), id);
        }
        synchronized (cacheLock) {
            if (cache != null) cache.remove(id);
        }
        return true;
    }

    /** 列出知识条目（可按来源过滤，限量；用于管理端浏览）。 */
    public List<DocInfo> list(String sourceFilter, int limit) {
        Map<String, CachedDoc> docs = loadCache();
        List<DocInfo> out = new ArrayList<>();
        for (CachedDoc d : docs.values()) {
            if (sourceFilter != null && !sourceFilter.isBlank() && !sourceFilter.equals(d.source())) continue;
            out.add(new DocInfo(d.id(), d.source(), d.refId(), d.title(), d.text()));
            if (out.size() >= Math.max(1, limit)) break;
        }
        out.sort((a, b) -> {
            int sa = a.source() == null ? 0 : a.source().compareTo(b.source() == null ? "" : b.source());
            if (sa != 0) return sa;
            return String.valueOf(a.title()).compareTo(String.valueOf(b.title()));
        });
        return out;
    }

    /** 按 来源+业务键(refId) 列出条目（用于删除某条 learned/manual 知识的全部分块）。 */
    public List<DocInfo> listByRef(String source, String refId) {
        List<DocInfo> out = new ArrayList<>();
        if (source == null || refId == null) return out;
        for (CachedDoc d : loadCache().values()) {
            if (source.equals(d.source()) && refId.equals(d.refId())) {
                out.add(new DocInfo(d.id(), d.source(), d.refId(), d.title(), d.text()));
            }
        }
        return out;
    }

    /**
     * 检索：原生 KNN（可用时，直接 FT.SEARCH，不回载全量缓存）→ 余弦暴力回退（仅回退引擎）。
     */
    public List<Hit> search(float[] query, int topK, String sourceFilter) {
        long t0 = System.nanoTime();
        try {
            if (query == null || query.length == 0) return List.of();
            // 原生 KNN 路径：不再 loadCache()
            if (ftAvailable()) {
                ensureIndex(query.length);
                if (ftIndexReady) {
                    try {
                        List<Hit> hits = knnSearch(query, topK, sourceFilter);
                        if (hits != null) return hits;
                    } catch (Exception e) {
                        markFtBroken(e);
                    }
                }
            }
            // 回退引擎：全量向量载入 + 余弦扫描（保留原逻辑）
            Map<String, CachedDoc> docs = loadCache();
            return rank(query, docs, topK, sourceFilter);
        } finally {
            recordSearchLatency((System.nanoTime() - t0) / 1_000_000L);
        }
    }

    /** 余弦 topK 排序（纯函数，可测）：可按 doc.source 过滤 */
    public static List<Hit> rank(float[] query, Map<String, CachedDoc> docs, int topK, String sourceFilter) {
        if (docs.isEmpty() || query == null || query.length == 0) return List.of();
        List<Hit> scored = new ArrayList<>();
        for (CachedDoc d : docs.values()) {
            if (sourceFilter != null && !sourceFilter.isBlank() && !sourceFilter.equals(d.source())) continue;
            if (d.vector() == null || d.vector().length != query.length) continue;
            double s = cosine(query, d.vector());
            if (s > 1e-6) scored.add(new Hit(d.id(), d.source(), d.refId(), d.title(), d.text(), s));
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        return scored.subList(0, Math.min(topK, scored.size()));
    }

    // ==================== 原生 FT 引擎（Redis Stack / Redis 8 Query Engine） ====================
    //
    // 关键实现约束：Spring Data Redis 的 connection.execute("FT.XXX", ...) 对自定义命令固定使用
    // ByteArrayOutput —— 它既不支持数组响应（multi）也不支持 RESP3 double（set(double) 抛
    // UnsupportedOperationException），而 FT.INFO/FT.SEARCH 响应必然含嵌套数组与浮点分数。
    // 因此 FT 命令走独立 Lettuce 连接 + ArrayOutput（嵌套 List：bulk→byte[]、int→Long、double→Double）。

    private boolean ftAvailable() {
        return ftAvailable.get();
    }

    private void markFtBroken(Exception e) {
        // 首次失败：记录原因（含堆栈）并永久切回暴力扫描（普通 redis 内核无 FT 命令）
        if (ftAvailable.getAndSet(false)) {
            log.warn("[aikb] native FT engine unavailable → falling back to in-memory cosine: {}",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), e);
        }
    }

    /** 拼接异常链全部 message：Spring/Lettuce 包装后顶层仅为 "Error in execution"/"Unknown redis exception"，真实错误在 cause 链里 */
    private static String fullMessage(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null) sb.append(t.getMessage()).append("; ");
        }
        return sb.toString();
    }

    /** 懒建独立 Lettuce 连接（仅 FT 命令使用，不影响 Spring 缓存通道） */
    private io.lettuce.core.api.StatefulRedisConnection<byte[], byte[]> ftConnection() {
        io.lettuce.core.api.StatefulRedisConnection<byte[], byte[]> c = ftConn;
        if (c == null) {
            synchronized (cacheLock) {
                if (ftConn == null) {
                    io.lettuce.core.RedisURI.Builder b = io.lettuce.core.RedisURI.builder()
                            .withHost(redisHost).withPort(redisPort);
                    if (redisPassword != null && !redisPassword.isBlank()) b.withPassword(redisPassword);
                    ftClient = io.lettuce.core.RedisClient.create(b.build());
                    ftConn = ftClient.connect(new io.lettuce.core.codec.ByteArrayCodec());
                }
                c = ftConn;
            }
        }
        return c;
    }

    /** 执行 FT 自定义命令：ArrayOutput 解析（数组/byte[]/Long/Double），10s 超时 */
    private Object ftCommand(String command, Object... args) {
        io.lettuce.core.protocol.CommandArgs<byte[], byte[]> a =
                new io.lettuce.core.protocol.CommandArgs<>(io.lettuce.core.codec.ByteArrayCodec.INSTANCE);
        for (Object arg : args) {
            if (arg instanceof byte[] bytes) a.add(bytes);
            else a.add(String.valueOf(arg).getBytes(StandardCharsets.UTF_8));
        }
        io.lettuce.core.protocol.ProtocolKeyword kw = new io.lettuce.core.protocol.ProtocolKeyword() {
            @Override public byte[] getBytes() { return command.getBytes(StandardCharsets.US_ASCII); }
            @Override public String toString() { return command; }
        };
        try {
            return ftConnection().async()
                    .dispatch(kw,
                            new io.lettuce.core.output.ArrayOutput<>(io.lettuce.core.codec.ByteArrayCodec.INSTANCE), a)
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("FT command failed: " + command, e);
        } catch (java.lang.InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("FT command interrupted: " + command, e);
        }
    }

    private byte[][] buildCreateArgs(int dim) {
        return new byte[][]{
                INDEX_NAME.getBytes(StandardCharsets.UTF_8),
                "ON".getBytes(StandardCharsets.UTF_8),
                "HASH".getBytes(StandardCharsets.UTF_8),
                "PREFIX".getBytes(StandardCharsets.UTF_8),
                "1".getBytes(StandardCharsets.UTF_8),
                VBLOB_PREFIX.getBytes(StandardCharsets.UTF_8),
                "SCHEMA".getBytes(StandardCharsets.UTF_8),
                "source".getBytes(StandardCharsets.UTF_8),
                "TAG".getBytes(StandardCharsets.UTF_8),
                "vec".getBytes(StandardCharsets.UTF_8),
                "VECTOR".getBytes(StandardCharsets.UTF_8),
                "HNSW".getBytes(StandardCharsets.UTF_8),
                "6".getBytes(StandardCharsets.UTF_8),
                "TYPE".getBytes(StandardCharsets.UTF_8),
                "FLOAT32".getBytes(StandardCharsets.UTF_8),
                "DIM".getBytes(StandardCharsets.UTF_8),
                String.valueOf(dim).getBytes(StandardCharsets.UTF_8),
                "DISTANCE_METRIC".getBytes(StandardCharsets.UTF_8),
                "COSINE".getBytes(StandardCharsets.UTF_8)};
    }

    /** 懒建索引：DIM 记录在 aikb:idx:dim（替代 FT.INFO）；维度变化或索引缺失 → 创建/重建 */
    private void ensureIndex(int dim) {
        if (!ftAvailable() || ftIndexReady) return;
        try {
            String saved = redis.opsForValue().get(DIM_KEY);
            if (saved != null) {
                int existing;
                try { existing = Integer.parseInt(saved.trim()); } catch (Exception e) { existing = -1; }
                if (existing == dim) {
                    ftIndexReady = true;
                    indexDim = dim;
                    return;
                }
                log.info("[aikb] FT index dim {} → {} (embed model changed), recreating", existing, dim);
                try { ftCommand("FT.DROPINDEX", INDEX_NAME); } catch (Exception ignored) { }
                redis.delete(DIM_KEY);
            }
            try {
                ftCommand("FT.CREATE", (Object[]) buildCreateArgs(dim));
            } catch (Exception ce) {
                // 索引已存在但无 dim 记录（旧版本遗留）：无维度信息 → 重建一次保证与当前模型匹配
                if (!fullMessage(ce).contains("Index already exists")) throw ce;
                try { ftCommand("FT.DROPINDEX", INDEX_NAME); } catch (Exception ignored) { }
                redis.delete(DIM_KEY);
                ftCommand("FT.CREATE", (Object[]) buildCreateArgs(dim));
            }
            redis.opsForValue().set(DIM_KEY, String.valueOf(dim));
            ftIndexReady = true;
            indexDim = dim;
            log.info("[aikb] FT.CREATE {} (HNSW, DIM={}) — native vector index ready", INDEX_NAME, dim);
        } catch (Exception e) {
            // 模块真缺失（unknown command）或 FT 通道异常 → 永久回退暴力扫描
            markFtBroken(e);
        }
    }

    /** KNN 检索（原生）：FT.SEARCH 直接返回元数据，不再依赖内存缓存 */
    private List<Hit> knnSearch(float[] query, int topK, String sourceFilter) {
        int k = Math.max(1, topK);
        // HNSW 查询期召回：EF_RUNTIME 必须 ≥ k；默认 64，可用 AI_KB_EF_RUNTIME 调整
        int ef = Math.max(k, Math.max(1, efRuntime));
        if (efRuntimeSupported) {
            try {
                Object resp = ftCommand("FT.SEARCH", buildKnnArgs(query, k, ef, sourceFilter, true));
                return toHits(parseKnnResponse(resp));
            } catch (Exception e) {
                // 个别 RediSearch 版本不接受运行期 EF_RUNTIME：降回索引默认值，绝不因此整体降级
                log.warn("[aikb] KNN with EF_RUNTIME failed, retry without it: {}", e.getMessage());
                efRuntimeSupported = false;
            }
        }
        Object resp = ftCommand("FT.SEARCH", buildKnnArgs(query, k, k, sourceFilter, false));
        return toHits(parseKnnResponse(resp));
    }

    /**
     * 组装 FT.SEARCH KNN 参数。
     * withEf=true 使用查询属性语法（RediSearch ≥2.6）：
     *   *=>[KNN $k @vec $qvec]=>{$EF_RUNTIME: $ef; $YIELD_DISTANCE_AS: score}  PARAMS 6 ...
     * withEf=false 使用经典别名语法：*=>[KNN $k @vec $qvec AS score]  PARAMS 4 ...
     */
    private Object[] buildKnnArgs(float[] query, int k, int ef, String sourceFilter, boolean withEf) {
        String prefix = (sourceFilter != null && !sourceFilter.isBlank())
                ? "(@source:{" + escapeTag(sourceFilter) + "})=>" : "*=>";
        String expr = withEf
                ? "[KNN $k @vec $qvec]=>{$EF_RUNTIME: $ef; $YIELD_DISTANCE_AS: score}"
                : "[KNN $k @vec $qvec AS score]";
        List<Object> args = new ArrayList<>();
        args.add(INDEX_NAME);
        args.add(prefix + expr);
        args.add("PARAMS");
        if (withEf) {
            args.add("6");
            args.add("qvec");
            args.add(toBytes(query));
            args.add("k");
            args.add(String.valueOf(k));
            args.add("ef");
            args.add(String.valueOf(ef));
        } else {
            args.add("4");
            args.add("qvec");
            args.add(toBytes(query));
            args.add("k");
            args.add(String.valueOf(k));
        }
        args.add("RETURN");
        args.add(String.valueOf(KNN_RETURN_FIELDS.length));
        for (String f : KNN_RETURN_FIELDS) args.add(f);
        // KNN 默认 LIMIT 0 10：显式放开到 k，保证 topK>10 时不被默认截断
        args.add("LIMIT");
        args.add("0");
        args.add(String.valueOf(k));
        args.add("DIALECT");
        args.add("2");
        return args.toArray();
    }

    /** TAG 过滤值转义（来源名含空格/特殊字符时不破坏 FT 查询语法） */
    private static String escapeTag(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (",.<>{}[]\"':;!@#$%^&*()-+=~| /\\".indexOf(c) >= 0) sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 解析 FT.SEARCH KNN 响应（ArrayOutput 均展平为 List）：
     *   RESP2：[total, key, [field, val, ...], key, [..], ...]
     *   RESP3：results 数组，每项为扁平 map（id / extra_attributes{score} / values{...}）
     * 兼容 Lettuce 以 Map 形式给出嵌套结构的场景；score 统一从 cosine 距离换算为相似度（1-distance）。
     */
    static List<KnnRow> parseKnnResponse(Object resp) {
        List<KnnRow> out = new ArrayList<>();
        if (resp == null) return out;
        List<?> resultRows = resp3Results(resp);
        if (resultRows != null) {
            for (Object o : resultRows) {
                if (o == null) continue;
                Map<String, String> fields = rowFields(o);
                String id = normalizeId(utf8Nullable(fields.get("id")));
                if (id == null && o instanceof List<?> row) id = normalizeId(utf8Nullable(flatGet(row, "id")));
                if (id == null) continue;
                out.add(new KnnRow(id, fields.get("source"), fields.get("refId"),
                        fields.get("title"), fields.get("text"), similarityOf(fields.get("score"))));
            }
            return out;
        }
        // RESP2：[total, key, [field, val, ...], key, [..], ...]
        if (!(resp instanceof List<?> rows)) return out;
        for (int i = 1; i + 1 < rows.size(); i += 2) {
            String id = normalizeId(utf8Nullable(rows.get(i)));
            if (id == null) continue;
            Map<String, String> fields = rowFields(rows.get(i + 1));
            out.add(new KnnRow(id, fields.get("source"), fields.get("refId"),
                    fields.get("title"), fields.get("text"), similarityOf(fields.get("score"))));
        }
        return out;
    }

    /** 取 RESP3 响应中的 results 数组；非 RESP3 结构返回 null */
    private static List<?> resp3Results(Object resp) {
        if (resp instanceof List<?> rows) {
            for (int i = 0; i + 1 < rows.size(); i += 2) {
                if ("results".equals(utf8Nullable(rows.get(i))) && rows.get(i + 1) instanceof List<?> l) return l;
            }
            return null;
        }
        if (resp instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if ("results".equals(utf8Nullable(e.getKey())) && e.getValue() instanceof List<?> l) return l;
            }
        }
        return null;
    }

    /** 单条命中行 → 字段 map：兼容 RESP3 扁平 map（extra_attributes/values）与 RESP2 扁平字段数组 */
    private static Map<String, String> rowFields(Object row) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (row instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = utf8Nullable(e.getKey());
                if (key == null) continue;
                Object v = e.getValue();
                if (v instanceof List<?> l) collectFields(fields, l);
                else if (v instanceof Map<?, ?> m) collectMapFields(fields, m);
                else if (v != null) fields.put(key, utf8(v));
            }
            return fields;
        }
        if (row instanceof List<?> list) {
            collectFields(fields, flatGet(list, "extra_attributes"));
            collectFields(fields, flatGet(list, "values"));
            if (fields.isEmpty()) collectFields(fields, list);
        }
        return fields;
    }

    private static void collectMapFields(Map<String, String> out, Map<?, ?> map) {
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = utf8Nullable(e.getKey());
            if (key == null) continue;
            Object v = e.getValue();
            if (v == null || v instanceof Map<?, ?> || v instanceof List<?>) continue;
            out.put(key, utf8(v));
        }
    }

    /** 扁平数组 [k1, v1, k2, v2, ...] → map（跳过嵌套结构） */
    private static void collectFields(Map<String, String> out, Object flat) {
        if (!(flat instanceof List<?> list)) return;
        for (int i = 0; i + 1 < list.size(); i += 2) {
            String key = utf8Nullable(list.get(i));
            if (key == null) continue;
            Object v = list.get(i + 1);
            if (v == null || v instanceof List<?> || v instanceof Map<?, ?>) continue;
            out.put(key, utf8(v));
        }
    }

    /** 扁平 map List（[k1, v1, k2, v2, ...]）按键取值 */
    private static Object flatGet(List<?> row, String key) {
        for (int i = 0; i + 1 < row.size(); i += 2) {
            if (key.equals(utf8Nullable(row.get(i)))) return row.get(i + 1);
        }
        return null;
    }

    /** KNN 分数（COSINE 距离）→ 相似度：与余弦回退 rank() 同口径（越大越相似） */
    private static double similarityOf(String rawScore) {
        if (rawScore == null) return 0.0;
        try {
            double d = Double.parseDouble(rawScore.trim());
            if (!Double.isFinite(d)) return 0.0;
            return Math.max(-1.0, Math.min(1.0, 1.0 - d));
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static String normalizeId(String key) {
        if (key == null || key.isBlank()) return null;
        return key.startsWith(VBLOB_PREFIX) ? key.substring(VBLOB_PREFIX.length()) : key;
    }

    private static String utf8Nullable(Object o) {
        if (o == null) return null;
        if (o instanceof byte[] b) return new String(b, StandardCharsets.UTF_8);
        if (o instanceof CharSequence cs) return cs.toString();
        return null;
    }

    private static String utf8(Object o) {
        return o instanceof byte[] b ? new String(b, StandardCharsets.UTF_8) : String.valueOf(o);
    }

    /** KNN 命中 → Hit：RETURN 字段缺失（旧数据）时按 key 逐条 HGETALL 补元数据（不回载全量缓存） */
    private List<Hit> toHits(List<KnnRow> rows) {
        if (rows.isEmpty()) return List.of();
        List<Hit> out = new ArrayList<>(rows.size());
        for (KnnRow r : rows) {
            String source = r.source(), refId = r.refId(), title = r.title(), text = r.text();
            if (source == null || refId == null || title == null || text == null) {
                Map<Object, Object> h = docHash(r.id());
                if (source == null) source = str(h.get("source"));
                if (refId == null) refId = str(h.get("refId"));
                if (title == null) title = str(h.get("title"));
                if (text == null) text = str(h.get("text"));
            }
            out.add(new Hit(r.id(), source == null ? "" : source, refId == null ? "" : refId,
                    title == null ? "" : title, text == null ? "" : text, r.score()));
        }
        return out;
    }

    /** 逐命中回表读 aikb:doc:{id}（仅旧数据缺少 vblob 冗余字段时触发） */
    private Map<Object, Object> docHash(String id) {
        try {
            Map<Object, Object> h = redis.opsForHash().entries(DOC_PREFIX + id);
            return h == null ? Map.of() : h;
        } catch (Exception e) {
            log.debug("[aikb] doc hash lookup failed for {}: {}", id, e.getMessage());
            return Map.of();
        }
    }

    @jakarta.annotation.PreDestroy
    void shutdownFtClient() {
        try { if (ftConn != null) ftConn.close(); } catch (Exception ignored) { }
        try { if (ftClient != null) ftClient.shutdown(); } catch (Exception ignored) { }
    }

    // ==================== 统计 / 工具 ====================

    /** 来源 → 文档数 统计 */
    public Map<String, Long> statsBySource() {
        Map<String, CachedDoc> docs = loadCache();
        Map<String, Long> out = new HashMap<>();
        for (CachedDoc d : docs.values()) out.merge(d.source(), 1L, Long::sum);
        return out;
    }

    public long totalDocs() { return loadCache().size(); }

    /** 当前向量维度（取任一文档；空库返回 0） */
    public int dim() {
        if (indexDim > 0) return indexDim;
        for (CachedDoc d : loadCache().values()) return d.vector().length;
        return 0;
    }

    /** 当前检索引擎：redisearch-knn（原生 KNN）/ cosine-fallback（内存余弦扫描） */
    public String indexMode() {
        return probeIndex() ? "redisearch-knn" : "cosine-fallback";
    }

    /** 原生 KNN 索引是否可用 */
    public boolean indexReady() {
        return probeIndex();
    }

    /** HNSW 查询期召回参数（当前生效值，已归一为 ≥1） */
    public int efRuntime() {
        return Math.max(1, efRuntime);
    }

    /** 检索延迟统计：count / avgMs / lastMs（全部为累计口径） */
    public Map<String, Object> searchStats() {
        long c = searchCount.get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", c);
        m.put("avgMs", c == 0 ? 0.0 : Math.round(searchTotalMs.get() * 100.0 / c) / 100.0);
        m.put("lastMs", searchLastMs.get());
        m.put("efRuntime", Math.max(1, efRuntime));
        return m;
    }

    private void recordSearchLatency(long ms) {
        long v = Math.max(0, ms);
        searchCount.incrementAndGet();
        searchTotalMs.addAndGet(v);
        searchLastMs.set(v);
    }

    /**
     * 索引状态探测（低频管理接口用）：DIM 记录存在则沿用已建索引（不重建），
     * 无 DIM 记录（空库/首次灌库前）返回 false，待第一次 upsert 建索引。
     */
    private boolean probeIndex() {
        if (ftIndexReady) return true;
        if (!ftAvailable() || redis == null) return false;
        try {
            String saved = redis.opsForValue().get(DIM_KEY);
            if (saved == null || saved.isBlank()) return false;
            int dim;
            try { dim = Integer.parseInt(saved.trim()); } catch (Exception e) { return false; }
            ensureIndex(dim);
        } catch (Exception e) {
            log.debug("[aikb] index probe failed: {}", e.getMessage());
        }
        return ftIndexReady;
    }

    private Map<String, CachedDoc> loadCache() {
        synchronized (cacheLock) {
            if (cacheLoaded && cache != null) return cache;
            Set<String> ids = redis.opsForSet().members("aikb:all");
            Map<String, CachedDoc> loaded = new HashMap<>();
            if (ids != null) {
                for (String id : ids) {
                    Map<Object, Object> h = redis.opsForHash().entries(DOC_PREFIX + id);
                    if (h == null || h.isEmpty()) continue;
                    String vecB64 = (String) h.get("vec");
                    if (vecB64 == null) continue;
                    try {
                        byte[] bytes = Base64.getDecoder().decode(vecB64);
                        float[] v = new float[bytes.length / 4];
                        ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(v);
                        loaded.put(id, new CachedDoc(id, str(h.get("source")), str(h.get("refId")),
                                str(h.get("title")), str(h.get("text")), v));
                    } catch (Exception ignored) { /* 坏块跳过 */ }
                }
            }
            cache = loaded;
            cacheLoaded = true;
            return loaded;
        }
    }

    /** 全量重灌后调用：强制下次检索重新加载 */
    public void invalidateCache() {
        synchronized (cacheLock) {
            cacheLoaded = false;
            cache = null;
        }
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    /** 余弦相似度（向量已归一化时即点积，但保留完整公式兼容未归一化输入） */
    static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length && i < b.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static byte[] toBytes(float[] v) {
        // RediSearch 向量字节按小端读取：必须显式 LITTLE_ENDIAN（ByteBuffer 默认大端，错序会被解读为 NaN 导致 COSINE 全 NaN）
        ByteBuffer buf = ByteBuffer.allocate(v.length * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (float x : v) buf.putFloat(x);
        return buf.array();
    }

    public record Doc(String source, String refId, String title, String text, float[] vector) {}

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(RedisVectorStore.class);
}
