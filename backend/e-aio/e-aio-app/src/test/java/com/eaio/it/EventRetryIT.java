package com.eaio.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import com.eaio.EaioApplication;
import com.eaio.common.id.IdGenerator;
import com.eaio.common.json.JsonUtils;
import com.eaio.platform.api.ParamApi;
import com.eaio.platform.application.event.EventRetryScanner;
import com.eaio.platform.application.event.PlatformEventDispatcher;
import com.eaio.platform.application.event.PlatformEventPublisher;
import com.eaio.platform.domain.event.EventDeliveryStatus;
import com.eaio.platform.events.ParamChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

/**
 * 事件可靠性的集成测试（P1 册 6.2 的 {@code EventRetryIT}）：真实 PostgreSQL 17（迁移到 V7 + 种子）+
 * 真实 Redis 7 上验证"业务回滚不留登记、失败重投、退避、死信、人工重放、幂等消费、跨实例互斥、
 * 重投真的打到监听方"。
 *
 * <p>六个场景与 3.9.4 / 票面验收一一对应：
 * <ol>
 *   <li><b>业务回滚</b>：登记与业务写同一事务 → {@code event_delivery} 无行且监听方一次都没被调用；</li>
 *   <li><b>失败重投</b>：监听方抛异常 → {@code RETRYING}/{@code attempt=2}/退避到期时刻 → 到期重投成功 → {@code DONE}；</li>
 *   <li><b>死信与人工重放</b>：达到 {@code max_attempt} → {@code DEAD} 且不再被扫描；真实 HTTP 重放 →
 *       {@code RETRYING}/{@code attempt=1}/{@code last_error} 保留历史 → 重投成功 → {@code DONE}；</li>
 *   <li><b>幂等消费</b>：同一事件重复投递 → 监听方按 {@code eventId} 去重，副作用只有一次；</li>
 *   <li><b>两实例并发扫描</b>：同一个 JVM 起第二个应用上下文（同一 DB/Redis）→ ShedLock 保证同一条只被重投一次；</li>
 *   <li><b>重投真的打到真实监听方</b>：预热 L1 → 直接改库 → 造一条待重投的 {@code ParamChangedEvent} 登记 →
 *       扫描器重投 → 该键的 L1 被清（读到新值）且登记行 {@code DONE}。只断言 status 会把"状态成功、
 *       缓存没清"这类缺陷放过去，所以这里断言的是**值真的变了**。</li>
 * </ol>
 *
 * <p><b>共享库</b>（{@link IntegrationTestBase} 的容器是 JVM 内单例，所有 IT 共用一套 PG/Redis）：
 * 每个用例自造唯一 {@code eventId}/{@code param_key}，不假设空库，也不依赖别的用例留下的行。
 * 后台调度器每 30 秒会跑一次 {@code platform.event.retry}（生产行为），因此断言写在**终态**上：
 * 谁投的都一样，唯一不能变的是"同一条只投一次"。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EventRetryIT extends IntegrationTestBase {

    /** 探针只处理本类造的键（其他 IT 的真实参数变更事件会被忽略）。 */
    private static final String KEY_PREFIX = "it.eventretry.";

    /** 投递探针：按 {@code paramKey} 前缀生效，计数与失败预算都是静态的（两个上下文共享同一个 JVM）。 */
    public static final class Probe {

        /** 每个键被投递的次数（含失败的那几次）。 */
        static final Map<String, AtomicInteger> DELIVERIES = new ConcurrentHashMap<>();

        /** 每个键的副作用次数（按 {@code eventId} 去重后）。 */
        static final Map<String, AtomicInteger> SIDE_EFFECTS = new ConcurrentHashMap<>();

        /** 已产生副作用的 {@code eventId}（消费侧幂等去重，5.5 的契约）。 */
        static final Set<String> SEEN_EVENT_IDS = ConcurrentHashMap.newKeySet();

        /** 每个键还有几次"故意失败"的预算（0/缺省 = 成功）。 */
        static final Map<String, AtomicInteger> FAIL_BUDGETS = new ConcurrentHashMap<>();

        /** 投递时故意睡多久（毫秒）——给并发扫描用例撑开"谁先拿到锁"的窗口。 */
        static final AtomicInteger SLOW_MILLIS = new AtomicInteger();

        static void reset() {
            DELIVERIES.clear();
            SIDE_EFFECTS.clear();
            SEEN_EVENT_IDS.clear();
            FAIL_BUDGETS.clear();
            SLOW_MILLIS.set(0);
        }

        static int deliveries(String paramKey) {
            return DELIVERIES.getOrDefault(paramKey, new AtomicInteger()).get();
        }

        static int sideEffects(String paramKey) {
            return SIDE_EFFECTS.getOrDefault(paramKey, new AtomicInteger()).get();
        }

        @EventListener
        public void onParamChanged(ParamChangedEvent event) {
            String paramKey = event.paramKey();
            if (paramKey == null || !paramKey.startsWith(KEY_PREFIX)) {
                return;
            }
            DELIVERIES.computeIfAbsent(paramKey, key -> new AtomicInteger()).incrementAndGet();
            AtomicInteger budget = FAIL_BUDGETS.get(paramKey);
            if (budget != null && budget.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
                throw new IllegalStateException("it 故意失败：" + paramKey);
            }
            int slow = SLOW_MILLIS.get();
            if (slow > 0) {
                sleep(slow);
            }
            if (SEEN_EVENT_IDS.add(event.eventId())) {
                SIDE_EFFECTS.computeIfAbsent(paramKey, key -> new AtomicInteger()).incrementAndGet();
            }
        }
    }

    /** 事务探针：把"业务写 + 事件登记"包在一个事务里，用于证明回滚一起回滚。 */
    public static class TransactionalProbe {

        private final PlatformEventPublisher publisher;

        TransactionalProbe(PlatformEventPublisher publisher) {
            this.publisher = publisher;
        }

        @Transactional
        public void publishThenRollback(ParamChangedEvent event) {
            publisher.publish(event);
            throw new IllegalStateException("it 故意回滚：登记必须一起回滚");
        }

        @Transactional
        public void publishThenCommit(ParamChangedEvent event) {
            publisher.publish(event);
        }
    }

    /** 两个上下文都注册同一批探针（第二个上下文只用它的扫描器）。 */
    @TestConfiguration
    static class Probes {

        @Bean
        Probe eventProbe() {
            return new Probe();
        }

        @Bean
        TransactionalProbe transactionalProbe(PlatformEventPublisher publisher) {
            return new TransactionalProbe(publisher);
        }
    }

    @Autowired
    private TransactionalProbe transactionalProbe;

    @Autowired
    private PlatformEventDispatcher dispatcher;

    @Autowired
    private EventRetryScanner scanner;

    @Autowired
    private ParamApi paramApi;

    @Autowired
    private IdGenerator idGenerator;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @LocalServerPort
    private int port;

    @BeforeEach
    void resetProbe() {
        Probe.reset();
    }

    // ---------------------------------------------------------------- ① 回滚

    @Test
    @DisplayName("业务回滚：event_delivery 无行，且监听方一次都没被调用（登记与业务同事务）")
    void rollbackLeavesNoDeliveryRowAndNoListenerCall() {
        String paramKey = uniqueKey("rollback");
        ParamChangedEvent event = event(paramKey);

        assertThatThrownBy(() -> transactionalProbe.publishThenRollback(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("故意回滚");

        assertThat(countDeliveries(event.eventId())).as("业务回滚 → 登记一起回滚，不留悬空行").isZero();
        assertThat(Probe.deliveries(paramKey)).as("事务没提交 → 监听方完全不被调用").isZero();
        assertThat(Probe.sideEffects(paramKey)).isZero();
    }

    // ---------------------------------------------------------------- ② 失败重投

    @Test
    @DisplayName("监听方抛异常：登记行 RETRYING/attempt=2/退避已设 → 到期重投成功 → DONE + finish_time")
    void listenerFailureSchedulesRetryThenRetrySucceeds() {
        String paramKey = uniqueKey("retry");
        ParamChangedEvent event = event(paramKey);
        // 第一次投递故意失败，重投时成功
        Probe.FAIL_BUDGETS.put(paramKey, new AtomicInteger(1));

        transactionalProbe.publishThenCommit(event);

        Map<String, Object> failed = deliveryRow(event.eventId());
        assertThat(failed.get("status")).isEqualTo(EventDeliveryStatus.RETRYING.name());
        assertThat(((Number) failed.get("attempt_count")).intValue()).as("1 → 2（下一次）").isEqualTo(2);
        assertThat(failed.get("next_retry_time")).as("退避到期时刻必须落库，否则永远不重投").isNotNull();
        assertThat(String.valueOf(failed.get("last_error"))).contains("第 1 次投递失败");
        assertThat(Probe.deliveries(paramKey)).as("首次投递确实打到了监听方（然后失败）").isEqualTo(1);

        // 让后台 30 秒的扫描周期"看不见"它之后，再把它提前到期并手动扫一轮（确定性的重投）
        makeDue(event.eventId());
        scanner.scanOnce();

        Map<String, Object> done = deliveryRow(event.eventId());
        assertThat(done.get("status")).isEqualTo(EventDeliveryStatus.DONE.name());
        assertThat(done.get("finish_time")).as("成功必须写 finish_time（3.9.4）").isNotNull();
        assertThat(Probe.deliveries(paramKey)).as("重投真的又打了一次监听方").isEqualTo(2);
        assertThat(Probe.sideEffects(paramKey)).as("两次投递、一次副作用（按 eventId 去重）").isEqualTo(1);
    }

    // ---------------------------------------------------------------- ③ 死信与人工重放

    @Test
    @DisplayName("达到 max_attempt → DEAD 且不再被扫描；真实 HTTP 重放 → RETRYING/attempt=1/保留历史 → 重投成功")
    void exhaustedAttemptsBecomeDeadLetterAndManualReplayResetsIt() {
        String paramKey = uniqueKey("dead");
        String eventId = "it-evt-" + UUID.randomUUID();
        Probe.FAIL_BUDGETS.put(paramKey, new AtomicInteger(5));
        // 已经失败 4 次、第 5 次马上要跑的行（attempt_count = max_attempt = 5）
        insertRetryingDelivery(eventId, paramKey, 5, 5, Instant.now());

        scanner.scanOnce();

        Map<String, Object> dead = deliveryRow(eventId);
        assertThat(dead.get("status")).as("第 5 次仍失败 → 死信（不再自动重投）")
                .isEqualTo(EventDeliveryStatus.DEAD.name());
        assertThat(((Number) dead.get("attempt_count")).intValue()).isEqualTo(5);
        assertThat(String.valueOf(dead.get("last_error"))).contains("第 5 次投递失败");
        assertThat(countRetrying(eventId)).as("死信不在重投队列里").isZero();

        assertThat(scanner.scanOnce()).as("再扫一轮也不会重投死信").isZero();
        assertThat(deliveryRow(eventId).get("status")).isEqualTo(EventDeliveryStatus.DEAD.name());

        // 人工重放（真实 HTTP + 幂等键）：DEAD → RETRYING、attempt=1、last_error 追加一行保留历史
        Probe.FAIL_BUDGETS.put(paramKey, new AtomicInteger(0));
        String replay = post("/api/platform/eventDelivery/Replay", Map.of("eventId", eventId), true);
        assertThat(replay).contains("\"code\":0").contains("\"status\":\"RETRYING\"")
                .contains("\"attemptCount\":1");
        Map<String, Object> replayed = deliveryRow(eventId);
        assertThat(replayed.get("status")).isEqualTo(EventDeliveryStatus.RETRYING.name());
        assertThat(((Number) replayed.get("attempt_count")).intValue()).isEqualTo(1);
        assertThat(String.valueOf(replayed.get("last_error")))
                .as("历史保留：旧错误 + 人工重放注记两行都在")
                .contains("第 5 次投递失败")
                .contains("人工重放");

        // 重放后由 platform.event.retry 真正重投：这次监听方成功 → DONE
        makeDue(eventId);
        scanner.scanOnce();
        assertThat(deliveryRow(eventId).get("status")).isEqualTo(EventDeliveryStatus.DONE.name());
        assertThat(Probe.sideEffects(paramKey)).as("重放让事件真的又投给了监听方").isEqualTo(1);
    }

    @Test
    @DisplayName("详情端点：不存在 → 20070；GetPage 能按 status=DEAD 查到死信")
    void deliveryEndpointsExposeDeadLetters() {
        String paramKey = uniqueKey("query");
        String eventId = "it-evt-" + UUID.randomUUID();
        Probe.FAIL_BUDGETS.put(paramKey, new AtomicInteger(5));
        insertRetryingDelivery(eventId, paramKey, 5, 5, Instant.now());
        scanner.scanOnce();

        Map<String, Object> query = new LinkedHashMap<>();
        query.put("status", "DEAD");
        query.put("eventId", eventId);
        assertThat(post("/api/platform/eventDelivery/GetPage", query, false)).contains("\"code\":0")
                .contains(eventId);
        assertThat(post("/api/platform/eventDelivery/Get", Map.of("eventId", eventId), false))
                .contains("\"code\":0").contains("\"status\":\"DEAD\"");
        assertThat(post("/api/platform/eventDelivery/Get", Map.of("eventId", "no-such-event"), false))
                .contains("\"code\":20070");
    }

    // ---------------------------------------------------------------- ④ 幂等消费

    @Test
    @DisplayName("同一事件重复投递给幂等监听方：监听方按 eventId 去重，副作用只有一次")
    void duplicateDeliveryToIdempotentListenerHasSingleSideEffect() {
        String paramKey = uniqueKey("idem");
        ParamChangedEvent event = event(paramKey);

        transactionalProbe.publishThenCommit(event);
        assertThat(deliveryRow(event.eventId()).get("status")).isEqualTo(EventDeliveryStatus.DONE.name());

        // 模拟"重投/人工重放又投了一次"（行已是 DONE，条件更新不会把它拉回重投队列）
        assertThat(dispatcher.deliver(event)).isTrue();
        assertThat(dispatcher.deliver(event)).isTrue();

        assertThat(Probe.deliveries(paramKey)).as("监听方被调用了 3 次（首次 + 两次重复投递）").isEqualTo(3);
        assertThat(Probe.sideEffects(paramKey)).as("按 eventId 去重后副作用只有一次").isEqualTo(1);
        assertThat(deliveryRow(event.eventId()).get("status")).isEqualTo(EventDeliveryStatus.DONE.name());
    }

    // ---------------------------------------------------------------- ⑤ 两实例并发扫描

    @Test
    @DisplayName("两个独立 Spring 上下文并发扫描同一批行：同一条只被重投一次（ShedLock）")
    void twoContextsConcurrentlyScanTheSameRowOnlyOnce() throws Exception {
        String paramKey = uniqueKey("concurrent");
        String eventId = "it-evt-" + UUID.randomUUID();
        Probe.SLOW_MILLIS.set(700);
        // 先放到"后台扫描看不见"的未来，再在并发扫描前一刻提到期
        insertRetryingDelivery(eventId, paramKey, 1, 5, Instant.now().plus(Duration.ofMinutes(10)));

        ConfigurableApplicationContext second = secondInstance();
        try {
            EventRetryScanner secondScanner = second.getBean(EventRetryScanner.class);
            makeDue(eventId);

            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Integer> first = pool.submit(() -> {
                    start.await();
                    return scanner.scanOnce();
                });
                Future<Integer> other = pool.submit(() -> {
                    start.await();
                    return secondScanner.scanOnce();
                });
                start.countDown();
                first.get(30, TimeUnit.SECONDS);
                other.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            assertThat(Probe.deliveries(paramKey))
                    .as("两个实例并发扫描同一条：处理点只能被执行一次（ShedLock 不生效时这里会是 2）")
                    .isEqualTo(1);
            assertThat(Probe.sideEffects(paramKey)).isEqualTo(1);
            assertThat(deliveryRow(eventId).get("status")).isEqualTo(EventDeliveryStatus.DONE.name());
            assertThat(jdbc().queryForObject("select count(*) from eaio_platform.shedlock where name = ?",
                    Integer.class, "platform-internal-event-retry-scan"))
                    .as("扫描锁真的进过锁表（锁名与任务锁 platform-job-* 不同命名空间）")
                    .isEqualTo(1);
        } finally {
            second.close();
            assertThat(second.isActive()).as("第二个实例已关闭").isFalse();
        }
    }

    // ---------------------------------------------------------------- ⑥ 重投打到真实监听方

    @Test
    @DisplayName("重投真的打到真实监听方：预热 L1 → 直接改库 → 重投 ParamChangedEvent → L1 被清（读到新值）")
    void retryRedeliversToRealInvalidationListenerAndClearsL1() {
        String paramKey = uniqueKey("cache");
        long paramId = idGenerator.nextId();
        jdbc().update("insert into eaio_platform.param (id, param_key, param_level, owner_id, param_value,"
                + " value_type, param_group, encrypted, builtin, hot_reload, created_by, version, deleted)"
                + " values (?, ?, 'SYSTEM', 0, 'A', 'STRING', 'it', false, false, true, 0, 0, false)",
                paramId, paramKey);
        try {
            assertThat(paramApi.get(paramKey).paramValue()).as("预热：读到旧值 A").isEqualTo("A");
            // 绕过写路径直接改库：没有事件、缓存也不会被清
            jdbc().update("update eaio_platform.param set param_value = 'B' where param_key = ?"
                    + " and param_level = 'SYSTEM' and owner_id = 0", paramKey);
            assertThat(paramApi.get(paramKey).paramValue()).as("L1 命中：仍然读到旧的 A（这正是要被重投清掉的）")
                    .isEqualTo("A");

            String eventId = "it-evt-" + UUID.randomUUID();
            insertRetryingDelivery(eventId, paramKey, 1, 5, Instant.now());
            scanner.scanOnce();

            assertThat(deliveryRow(eventId).get("status")).as("重投成功 → DONE").isEqualTo(
                    EventDeliveryStatus.DONE.name());
            assertThat(paramApi.get(paramKey).paramValue())
                    .as("重投真的执行了 ParamInvalidationListener（L1 被清 → 回源读到新值 B）")
                    .isEqualTo("B");
        } finally {
            jdbc().update("delete from eaio_platform.param where id = ?", paramId);
        }
    }

    // ---------------------------------------------------------------- 保留期清理

    @Test
    @DisplayName("顺带清理：超保留天数（7 天）的 DONE 行被删，DEAD 行永不自动删（3.9.3）")
    void cleanupDeletesOldDoneRowsButKeepsDeadOnes() {
        String doneEventId = "it-evt-" + UUID.randomUUID();
        String deadEventId = "it-evt-" + UUID.randomUUID();
        insertTerminalDelivery(doneEventId, EventDeliveryStatus.DONE, Instant.now().minus(Duration.ofDays(30)));
        insertTerminalDelivery(deadEventId, EventDeliveryStatus.DEAD, Instant.now().minus(Duration.ofDays(30)));

        scanner.scanOnce();

        assertThat(countDeliveries(doneEventId)).as("超过保留天数（默认 7 天）的 DONE 行被物理删除").isZero();
        assertThat(countDeliveries(deadEventId)).as("DEAD 保留至人工处理，永不自动删").isEqualTo(1);
    }

    // ---------------------------------------------------------------- 辅助

    private static String uniqueKey(String tag) {
        return KEY_PREFIX + tag + "." + UUID.randomUUID();
    }

    private static ParamChangedEvent event(String paramKey) {
        return new ParamChangedEvent("it-evt-" + UUID.randomUUID(), Instant.now(), "UP", paramKey, "SYSTEM", 0L);
    }

    /** 直接塞一条待重投的登记行（模拟"业务已提交、投递失败后的状态"）。 */
    private void insertRetryingDelivery(String eventId, String paramKey, int attempt, int maxAttempt,
            Instant nextRetryTime) {
        ParamChangedEvent payload = new ParamChangedEvent(eventId, Instant.now(), "UP", paramKey, "SYSTEM", 0L);
        jdbc().update("insert into eaio_platform.event_delivery (id, event_id, event_type, payload_json, status,"
                + " attempt_count, max_attempt, next_retry_time, last_error, created_at, version)"
                + " values (?, ?, ?, ?::jsonb, 'RETRYING', ?, ?, ?, ?, now(), 0)",
                idGenerator.nextId(), eventId, ParamChangedEvent.class.getName(), JsonUtils.toJson(payload),
                attempt, maxAttempt, Timestamp.from(nextRetryTime), "第 " + attempt + " 次投递失败：it 预置");
    }

    /** 直接塞一条终态行（保留期清理用例）。 */
    private void insertTerminalDelivery(String eventId, EventDeliveryStatus status, Instant finishTime) {
        jdbc().update("insert into eaio_platform.event_delivery (id, event_id, event_type, payload_json, status,"
                + " attempt_count, max_attempt, next_retry_time, last_error, finish_time, created_at, version)"
                + " values (?, ?, ?, ?::jsonb, ?, 5, 5, NULL, 'it 预置', ?, now(), 0)",
                idGenerator.nextId(), eventId, ParamChangedEvent.class.getName(),
                JsonUtils.toJson(new ParamChangedEvent(eventId, Instant.now(), "UP", uniqueKey("terminal"), "SYSTEM", 0L)),
                status.name(), Timestamp.from(finishTime));
    }

    /** 提前到期（让本轮扫描看得见它）。 */
    private void makeDue(String eventId) {
        jdbc().update("update eaio_platform.event_delivery set next_retry_time = now() where event_id = ?", eventId);
    }

    private Map<String, Object> deliveryRow(String eventId) {
        return jdbc().queryForMap("select status, attempt_count, next_retry_time, last_error, finish_time"
                + " from eaio_platform.event_delivery where event_id = ?", eventId);
    }

    private int countDeliveries(String eventId) {
        Integer count = jdbc().queryForObject("select count(*) from eaio_platform.event_delivery where event_id = ?",
                Integer.class, eventId);
        return count == null ? 0 : count;
    }

    private int countRetrying(String eventId) {
        Integer count = jdbc().queryForObject("select count(*) from eaio_platform.event_delivery"
                + " where event_id = ? and status = 'RETRYING'", Integer.class, eventId);
        return count == null ? 0 : count;
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    /** 起第二个应用实例（同一 DB/Redis，不起 Tomcat）：只为拿它的 EventRetryScanner 并发扫描。 */
    private ConfigurableApplicationContext secondInstance() {
        return new SpringApplicationBuilder(EaioApplication.class, Probes.class)
                .web(WebApplicationType.NONE)
                .profiles("it")
                .registerShutdownHook(false)
                .properties(
                        "spring.datasource.url=" + environment.getProperty("spring.datasource.url"),
                        "spring.datasource.username=" + environment.getProperty("spring.datasource.username"),
                        "spring.datasource.password=" + environment.getProperty("spring.datasource.password"),
                        "spring.data.redis.host=" + environment.getProperty("spring.data.redis.host"),
                        "spring.data.redis.port=" + environment.getProperty("spring.data.redis.port"))
                .run();
    }

    private String post(String path, Map<String, Object> body, boolean idempotent) {
        RestClient.RequestBodySpec spec = RestClient.create("http://localhost:" + port)
                .post().uri(path).contentType(MediaType.APPLICATION_JSON);
        if (idempotent) {
            spec = spec.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        return spec.body(JsonUtils.toJson(body)).retrieve().body(String.class);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
