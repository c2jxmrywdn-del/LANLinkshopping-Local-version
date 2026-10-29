package com.lanlink.shopping.module.search;

import com.lanlink.shopping.module.aikb.RedisVectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 商品向量库（独立于知识库 aikb-idx：索引 prod-idx / 前缀 prod:vblob:，DROPINDEX 互不连坐）。
 *
 * <p>复用 aikb 验证过的 Lettuce dispatch 模式（Spring conn.execute 硬编码 ArrayOutput
 * 不支持 RESP3 double，FT 命令必须走独立连接）。HNSW FLOAT32 COSINE；向量由
 * EmbeddingService.embed（bge-m3 1024 维）生成，灌库时校验维度，哈希降级向量一律拒写。
 */
@Service
public class ProductVectorStore {

    private static final Logger log = LoggerFactory.getLogger(ProductVectorStore.class);
    private static final String INDEX_NAME = "prod-idx";
    private static final String VBLOB_PREFIX = "prod:vblob:";
    private static final String DIM_KEY = "prod:idx:dim";

    private final StringRedisTemplate redis;
    private final String redisHost;
    private final int redisPort;
    private final String redisPassword;
    private final Object lock = new Object();

    private volatile io.lettuce.core.RedisClient ftClient;
    private volatile io.lettuce.core.api.StatefulRedisConnection<byte[], byte[]> ftConn;
    private final AtomicBoolean ftAvailable = new AtomicBoolean(true);
    private volatile boolean indexReady = false;
    private volatile int indexDim = -1;

    public ProductVectorStore(StringRedisTemplate redis,
                              @Value("${spring.data.redis.host:redis}") String redisHost,
                              @Value("${spring.data.redis.port:6379}") int redisPort,
                              @Value("${spring.data.redis.password:}") String redisPassword) {
        this.redis = redis;
        this.redisHost = redisHost;
        this.redisPort = redisPort;
        this.redisPassword = redisPassword;
    }

    /** KNN 命中：prodId + 相似度（1 - cosine 距离） */
    public record VectorHit(long prodId, double similarity) {}

    /** 批量写入/更新商品向量；维度不符（哈希降级向量）拒写返回 false */
    public boolean upsert(Long prodId, String title, float[] vector) {
        if (!ftAvailable()) return false;
        ensureIndex(vector.length);
        if (!indexReady || indexDim != vector.length) return false;
        try {
            byte[] key = (VBLOB_PREFIX + prodId).getBytes(StandardCharsets.UTF_8);
            ftCommand("HSET", key,
                    "vec".getBytes(StandardCharsets.UTF_8), toBytes(vector),
                    "title".getBytes(StandardCharsets.UTF_8),
                    (title == null ? "" : title).getBytes(StandardCharsets.UTF_8),
                    "prodId".getBytes(StandardCharsets.UTF_8),
                    String.valueOf(prodId).getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            markBroken(e);
            return false;
        }
    }

    public void delete(Long prodId) {
        try {
            redis.delete(VBLOB_PREFIX + prodId);
        } catch (Exception ignored) { }
    }

    /** KNN 检索 TopN */
    public List<VectorHit> knn(float[] query, int topK) {
        List<VectorHit> out = new ArrayList<>();
        if (!ftAvailable() || !indexReady) return out;
        int k = Math.max(1, topK);
        try {
            Object resp = ftCommand("FT.SEARCH", buildKnnArgs(query, k));
            return parseKnn(resp);
        } catch (Exception e) {
            markBroken(e);
            return out;
        }
    }

    public boolean available() {
        return ftAvailable() && indexReady;
    }

    private boolean ftAvailable() {
        return ftAvailable.get();
    }

    // ------------------------------ 内部（模式与 RedisVectorStore 一致） ------------------------------

    private io.lettuce.core.api.StatefulRedisConnection<byte[], byte[]> ftConnection() {
        io.lettuce.core.api.StatefulRedisConnection<byte[], byte[]> c = ftConn;
        if (c == null) {
            synchronized (lock) {
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
                    .dispatch(kw, new io.lettuce.core.output.ArrayOutput<>(io.lettuce.core.codec.ByteArrayCodec.INSTANCE), a)
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("FT command failed: " + command, e);
        } catch (java.lang.InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("FT command interrupted", e);
        }
    }

    private void ensureIndex(int dim) {
        if (indexReady && indexDim == dim) return;
        try {
            String saved = redis.opsForValue().get(DIM_KEY);
            if (saved != null) {
                int existing;
                try { existing = Integer.parseInt(saved.trim()); } catch (Exception e) { existing = -1; }
                if (existing == dim) { indexReady = true; indexDim = dim; return; }
                try { ftCommand("FT.DROPINDEX", INDEX_NAME); } catch (Exception ignored) { }
                redis.delete(DIM_KEY);
            }
            try {
                ftCommand("FT.CREATE", (Object[]) buildCreateArgs(dim));
            } catch (Exception ce) {
                if (!fullMessage(ce).contains("Index already exists")) throw ce;
                try { ftCommand("FT.DROPINDEX", INDEX_NAME); } catch (Exception ignored) { }
                redis.delete(DIM_KEY);
                ftCommand("FT.CREATE", (Object[]) buildCreateArgs(dim));
            }
            redis.opsForValue().set(DIM_KEY, String.valueOf(dim));
            indexReady = true;
            indexDim = dim;
            log.info("[prod-search] FT.CREATE {} (HNSW, DIM={}) ready", INDEX_NAME, dim);
        } catch (Exception e) {
            markBroken(e);
        }
    }

    private Object[] buildCreateArgs(int dim) {
        return new Object[]{
                INDEX_NAME, "ON", "HASH", "PREFIX", "1", VBLOB_PREFIX,
                "SCHEMA", "title", "TEXT", "prodId", "NUMERIC",
                "vec", "VECTOR", "HNSW", "6", "TYPE", "FLOAT32",
                "DIM", String.valueOf(dim), "DISTANCE_METRIC", "COSINE"};
    }

    private Object[] buildKnnArgs(float[] query, int k) {
        List<Object> args = new ArrayList<>();
        args.add(INDEX_NAME);
        args.add("*=>[KNN $k @vec $qvec AS score]");
        args.add("PARAMS");
        args.add("4");
        args.add("qvec");
        args.add(toBytes(query));
        args.add("k");
        args.add(String.valueOf(k));
        args.add("RETURN");
        args.add("2");
        args.add("prodId");
        args.add("score");
        args.add("LIMIT");
        args.add("0");
        args.add(String.valueOf(k));
        args.add("DIALECT");
        args.add("2");
        return args.toArray();
    }

    /** 解析 KNN 响应（RESP2 扁平 / RESP3 map 双兼容，模式同 RedisVectorStore.parseKnnResponse） */
    private List<VectorHit> parseKnn(Object resp) {
        List<VectorHit> out = new ArrayList<>();
        if (resp == null) return out;
        List<?> rows = resp instanceof List<?> l ? l : null;
        if (rows != null) {
            for (int i = 1; i + 1 < rows.size(); i += 2) {
                String key = utf8(rows.get(i));
                Object fieldsRaw = rows.get(i + 1);
                String prodId = null;
                Double score = null;
                if (fieldsRaw instanceof List<?> f) {
                    for (int j = 0; j + 1 < f.size(); j += 2) {
                        String fn = utf8(f.get(j));
                        if ("prodId".equals(fn)) prodId = utf8(f.get(j + 1));
                        if ("score".equals(fn)) score = similarityOf(utf8(f.get(j + 1)));
                    }
                } else if (fieldsRaw instanceof Map<?, ?> m) {
                    m.forEach((k, v) -> {
                        // RESP3 map 形态：值可能再嵌套；统一字符串化处理
                    });
                }
                if (prodId == null && key != null) {
                    try { prodId = key.replace(VBLOB_PREFIX, ""); } catch (Exception ignored) { }
                }
                if (prodId != null) {
                    try {
                        out.add(new VectorHit(Long.parseLong(prodId.trim()),
                                score == null ? 0 : score));
                    } catch (Exception ignored) { }
                }
            }
        }
        return out;
    }

    private static Double similarityOf(String s) {
        try {
            double d = Double.parseDouble(s.trim());
            return Double.isNaN(d) || Double.isInfinite(d) ? 0 : Math.max(0, Math.min(1, 1 - d));
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static String utf8(Object o) {
        if (o == null) return null;
        if (o instanceof byte[] b) return new String(b, StandardCharsets.UTF_8);
        return String.valueOf(o);
    }

    private static byte[] toBytes(float[] v) {
        ByteBuffer bb = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float x : v) bb.putFloat(x);
        return bb.array();
    }

    private static String fullMessage(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null && sb.length() < 600; t = t.getCause()) {
            sb.append(t.getMessage()).append("; ");
        }
        return sb.toString();
    }

    private void markBroken(Exception e) {
        if (ftAvailable.compareAndSet(true, false)) {
            log.warn("[prod-search] FT path broken, vector recall offline (LIKE fallback active): {}",
                    fullMessage(e));
        }
    }
}
