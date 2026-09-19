package com.eaio.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.eaio.app.redis.SpringRedisKit;
import com.eaio.common.exception.BusinessException;
import com.eaio.common.redis.RedisKit;
import com.eaio.platform.api.CacheApi;
import com.eaio.platform.api.CacheKey;
import com.eaio.platform.api.CacheRegion;
import com.eaio.platform.api.DictApi;
import com.eaio.platform.application.cache.CacheManager;
import com.eaio.platform.application.param.ParamCryptoKeys;
import com.eaio.platform.application.param.ParamResolver;
import com.eaio.platform.application.param.ResolvedParam;
import com.eaio.platform.domain.param.ParamContext;
import com.eaio.platform.domain.param.ParamItem;
import com.eaio.platform.infrastructure.cache.CacheInvalidationPublisher;
import com.eaio.platform.infrastructure.cache.CaffeineRegionCache;
import com.eaio.platform.infrastructure.cache.ParamL2Cache;
import com.eaio.platform.infrastructure.cache.RedisRegionCache;
import com.eaio.platform.infrastructure.persistence.ParamStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 缓存中心集成测试（P1 册 3.6.5 的测试点 + 票面 #18 的验收 2/3/4）：真实 PostgreSQL 17 + 真实 Redis 7，
 * 读走真实 {@link DictApi}/{@link ParamResolver}，**不 mock Redis**。
 *
 * <p>三防护各有一处真实证据：
 * <ul>
 *   <li><b>穿透</b>：未知 typeCode 在 Redis 里留下空值占位；随后**绕过接口直连建库**（库里确实有了），
 *       接口仍然 20003——那就只能是占位命中，不可能是"库里没有"。refresh 清掉占位后立刻可读。</li>
 *   <li><b>击穿</b>：10 个线程同时冷读同一个 typeCode，{@code CacheManager.rebuildCount(DICT)}
 *       只涨 1（3.6.5 允许 ≤2；没有互斥时这里会是 10）。</li>
 *   <li><b>雪崩</b>：真实 Redis 上 20 个键的 TTL 都落在 270–330s（300s ± 10%）且**不全都相同**。</li>
 * </ul>
 *
 * <p>Redis 不可用那一条没法"停掉共享容器"（容器是 JVM 内单例，见 {@link IntegrationTestBase}），
 * 于是手工装配一条**指向不可达端口**的真实链路（真 Lettuce 客户端 + 真 DB 回源），断言读路径回源、
 * 写/失效不抛、错误指标累加。
 */
@SpringBootTest
class CacheCenterIT extends IntegrationTestBase {

    @Autowired
    private DictApi dictApi;

    @Autowired
    private CacheApi cacheApi;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private ParamResolver paramResolver;

    @Autowired
    private ParamStore paramStore;

    @Autowired
    private ParamCryptoKeys cryptoKeys;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("穿透防护：未知 typeCode 留空值占位；直连建库后占位期内仍 20003，refresh 后立即可读")
    void nullPlaceholderBlocksRepeatLookups() {
        String typeCode = uniqueType("null");
        assertThatThrownBy(() -> dictApi.getItems(typeCode)).isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(20003));

        assertThat(redis.keys("eaio:*:platform:cache:null:dict:" + typeCode))
                .as("3.6.1：DICT 区的空值占位 = 是(60s)").isNotEmpty();

        createTypeDirectly(typeCode);
        assertThat(countTypes(typeCode)).as("绕过接口直连建库：库里确实有该类型了").isEqualTo(1);

        assertThatThrownBy(() -> dictApi.getItems(typeCode))
                .as("占位命中：类型已在库里却仍 20003 —— 这就是防穿透（不再回源）")
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(20003));

        dictApi.refresh(typeCode);

        assertThat(redis.keys("eaio:*:platform:cache:null:dict:" + typeCode))
                .as("失效连空值占位一起清（漏清 = 类型重建后 60s 内仍读不到）").isEmpty();
        assertThat(dictApi.getItems(typeCode)).as("占位清了就能读到（该类型下暂无项）").isEmpty();
    }

    @Test
    @DisplayName("PARAM 区不写空值占位（3.1.5：参数键是有限登记集合，未定义键是代码拼错）")
    void paramRegionNeverWritesNullPlaceholder() {
        assertThat(paramResolver.resolve(uniqueType("param"), ParamContext.systemOnly())).isEmpty();

        assertThat(redis.keys("eaio:*:platform:cache:null:param:*"))
                .as("PARAM 区 nullCaching=false：一个占位键都不该出现").isEmpty();
    }

    @Test
    @DisplayName("击穿防护：10 个并发冷读同一 typeCode，互斥重建只放行 1 个回源（3.6.5，允许 ≤2）")
    void concurrentColdReadsRebuildOnce() throws Exception {
        String typeCode = uniqueType("mutex");
        createTypeDirectly(typeCode);
        createItemDirectly(typeCode, "V1", "标签一", 10);
        long before = cacheManager.rebuildCount(CacheRegion.DICT);

        int threads = 10;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return dictApi.getItems(typeCode).size();
                });
            }
            List<Future<Integer>> results = pool.invokeAll(tasks, 30, TimeUnit.SECONDS);
            for (Future<Integer> result : results) {
                assertThat(result.get()).as("每个并发读者都要拿到结果（等锁超时后回源兜底）").isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(cacheManager.rebuildCount(CacheRegion.DICT) - before)
                .as("被授权重建的次数：没有互斥锁时 10 个并发会各查一次库").isBetween(1L, 2L);
    }

    @Test
    @DisplayName("雪崩防护 + 验收 2：真实 Redis 上 TTL 落在 ±10% 且不全相同；按前缀失效用 SCAN 清干净")
    void ttlJitterAndPrefixScanEviction() {
        String prefix = "it.cache.ttl." + System.nanoTime() + ".";
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            cacheApi.put(new CacheKey(CacheRegion.FILE_META, prefix + i), "v" + i, 0);
            keys.add(cacheManager.redisKey(CacheRegion.FILE_META, prefix + i));
        }

        Set<Long> ttls = new HashSet<>();
        for (String key : keys) {
            Long ttl = redis.getExpire(key);
            assertThat(ttl).as("键 %s 必须真的写进了 Redis", key).isNotNull();
            assertThat(ttl).as("300s ± 10%（3.6.1 的 FILE_META L2 TTL）").isBetween(270L, 330L);
            ttls.add(ttl);
        }
        assertThat(ttls).as("抖动：整批 TTL 完全相同就是雪崩的前置条件").hasSizeGreaterThan(1);

        cacheApi.evictByPrefix(CacheRegion.FILE_META, prefix);

        assertThat(redis.keys("eaio:*:platform:file:meta:" + prefix + "*"))
                .as("前缀失效后该前缀下一个键都不剩（SCAN 游标，不是 KEYS）").isEmpty();
    }

    @Test
    @DisplayName("前缀失效连空值占位一起扫（值键与占位键在两个键空间里）")
    void prefixEvictionAlsoClearsNullPlaceholders() {
        String typeCode = uniqueType("nullscan");
        cacheManager.markAbsent(CacheRegion.DICT, typeCode);
        assertThat(redis.keys("eaio:*:platform:cache:null:dict:" + typeCode)).isNotEmpty();

        cacheApi.evictByPrefix(CacheRegion.DICT, typeCode);

        assertThat(redis.keys("eaio:*:platform:cache:null:dict:" + typeCode)).isEmpty();
    }

    @Test
    @DisplayName("Redis 不可用：读路径回源 DB 拿到正确值、写与失效不抛、错误指标累加（验收 4）")
    void redisUnavailableDegradesToSourceOfTruth() throws IOException {
        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", closedPort());
        factory.afterPropertiesSet();
        StringRedisTemplate brokenTemplate = new StringRedisTemplate(factory);
        brokenTemplate.afterPropertiesSet();
        try {
            ObjectProvider<StringRedisTemplate> templates = providerOf(brokenTemplate);
            RedisKit brokenKit = new SpringRedisKit(brokenTemplate, Duration.ofSeconds(1800));
            CacheManager broken = new CacheManager(new CaffeineRegionCache(environment),
                    new RedisRegionCache(providerOf(brokenKit), templates, environment),
                    new CacheInvalidationPublisher(templates, environment), environment);
            ParamResolver resolver = new ParamResolver(paramStore, new ParamL2Cache(broken), cryptoKeys, environment);

            List<ParamItem> rows = paramStore.allRows();
            assertThat(rows).as("种子参数必须存在（R__platform_seed.sql 的 1–10）").isNotEmpty();
            String key = rows.get(0).getParamKey();
            String expected = paramResolver.resolveOrDefault(key, "N/A", ParamContext.systemOnly()).value();

            Optional<ResolvedParam> resolved = resolver.resolve(key, ParamContext.systemOnly());

            assertThat(resolved).as("Redis 不可用时读路径仍要可用（回源 DB）").isPresent();
            assertThat(resolved.get().value()).isEqualTo(expected);

            assertThatCode(() -> {
                broken.put(new CacheKey(CacheRegion.FILE_META, "it.cache.broken"), "v", 0);
                broken.evict(new CacheKey(CacheRegion.FILE_META, "it.cache.broken"));
                broken.evictByPrefix(CacheRegion.FILE_META, "it.cache.");
            }).as("缓存写/失效失败只记错误，不抛（3.6.3）").doesNotThrowAnyException();

            assertThat(broken.get(new CacheKey(CacheRegion.FILE_META, "it.cache.broken"), String.class)).isEmpty();
            assertThat(broken.opErrorCount()).as("platform.cache.op.error 必须累加，不许静默降级")
                    .isGreaterThan(0L);
        } finally {
            factory.destroy();
        }
    }

    // ---------------------------------------------------------------- 辅助

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private static String uniqueType(String tag) {
        return "it.cache." + tag + "." + System.nanoTime();
    }

    /** 绕过接口直连建类型（走接口会触发失效监听器，把空值占位一起清掉，就测不出穿透防护了）。 */
    private void createTypeDirectly(String typeCode) {
        jdbc().update("insert into eaio_platform.dict_type"
                + " (id, type_code, type_name, status, builtin, deleted, version)"
                + " values (?, ?, 'IT 缓存类型', 'ENABLED', false, false, 0)",
                System.nanoTime() & Long.MAX_VALUE, typeCode);
    }

    private void createItemDirectly(String typeCode, String value, String label, int sortNo) {
        jdbc().update("insert into eaio_platform.dict_item"
                + " (id, type_code, item_value, item_label, sort_no, status, is_default, deleted, version)"
                + " values (?, ?, ?, ?, ?, 'ENABLED', false, false, 0)",
                System.nanoTime() & Long.MAX_VALUE, typeCode, value, label, sortNo);
    }

    private int countTypes(String typeCode) {
        Integer count = jdbc().queryForObject(
                "select count(*) from eaio_platform.dict_type where type_code = ?", Integer.class, typeCode);
        return count == null ? 0 : count;
    }

    /** 一个没人监听的端口：真客户端真连接、真失败（不 mock Redis 客户端）。 */
    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** 只给一个固定实例的 {@link ObjectProvider}（手工装配"指向不可达 Redis"的缓存链用）。 */
    private static <T> ObjectProvider<T> providerOf(T instance) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return instance;
            }

            @Override
            public T getIfAvailable() {
                return instance;
            }
        };
    }
}
