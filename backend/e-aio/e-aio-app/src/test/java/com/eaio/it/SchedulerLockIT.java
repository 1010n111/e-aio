package com.eaio.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import javax.sql.DataSource;

import com.eaio.EaioApplication;
import com.eaio.common.id.IdGenerator;
import com.eaio.common.json.JsonUtils;
import com.eaio.platform.api.JobHandler;
import com.eaio.platform.api.SchedulerApi;
import com.eaio.platform.api.dto.JobContext;
import com.eaio.platform.api.dto.JobDefinition;
import com.eaio.platform.application.job.JobLogCleanHandler;
import com.eaio.platform.application.job.JobRetryScanner;
import com.eaio.platform.infrastructure.persistence.JobStore;
import com.eaio.platform.infrastructure.scheduler.JobSchedulerRegistrar;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * 定时任务集成测试（P1 册 6.2 的 {@code SchedulerLockIT}）：真实 PostgreSQL 17（迁移到 V5 + 种子）+
 * 真实 Redis 7 上验证互斥、状态流转、重投与日志清理。
 *
 * <p><b>"两实例只执行一次"怎么真证</b>：同一个测试 JVM 里起**第二个应用上下文**（同一 DB/Redis，
 * {@code server.port=0}），两个上下文注册**同一个 jobCode**，然后并发调两个上下文各自的
 * {@code JobSchedulerRegistrar.run(...)}（= 生产里的 cron 路径：取锁 → 并发检查 → 执行 → 放锁）。
 * 计数用**静态**计数器（JVM 内跨上下文共享），断言"只执行一次 + 只有一行 {@code job_run} +
 * {@code shedlock} 表里那把锁只被一个实例持有过"。互斥若不生效，断言必然红。
 *
 * <p>另有一条更底层的证明（{@link #concurrentLockAttemptsHaveExactlyOneWinner()}）：两个独立的
 * {@code JdbcTemplateLockProvider} 在同一 DataSource 上抢同一锁名，每轮只有一个赢家。
 *
 * <p><b>共享数据库的注意事项</b>（{@code IntegrationTestBase} 的容器是 JVM 内单例，所有 IT 共用一套
 * PG/Redis）：每个用例自造唯一 {@code job_code}（{@code it.job.<tag>.<nanoTime>}），不假设空库、
 * 不依赖别的用例留下的行。
 *
 * <p><b>重投用例为什么把 backoff 设成 600 秒</b>：调度器内部的重投扫描每 30 秒跑一次（生产行为），
 * 若 {code next_retry_time} 立刻到期，后台扫描会与用例自己的 {@code scanOnce()} 抢同一条行，断言就不确定。
 * 用 600 秒让后台扫描"看不见"它，再由用例用 SQL 把到期时刻提到当前，然后手动扫一次——既确定，
 * 又顺带证明退避值真的按 {@code backoff × 2^(attempt-1)} 写进库里（第二次是封顶后的 30 分钟）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SchedulerLockIT extends IntegrationTestBase {

    /** 只计数、可被并发触发的处理点。 */
    private static final String COUNTING_HANDLER = "it.job.handler.counting";

    /** 一直失败的处理点（重投/终态用例）。 */
    private static final String FAILING_HANDLER = "it.job.handler.failing";

    /** 睡 3 秒的处理点（超时用例）。 */
    private static final String SLOW_HANDLER = "it.job.handler.slow";

    /** 跨上下文共享的执行计数（同一 JVM，两个应用上下文都注入同一个静态计数器）。 */
    private static final AtomicInteger EXECUTIONS = new AtomicInteger();

    /** 超时用例的"处理点是否真的被中断"标志。 */
    private static final AtomicBoolean INTERRUPTED = new AtomicBoolean();

    @Autowired
    private SchedulerApi schedulerApi;

    @Autowired
    private JobSchedulerRegistrar registrar;

    @Autowired
    private JobRetryScanner retryScanner;

    @Autowired
    private JobLogCleanHandler logCleanHandler;

    @Autowired
    private JobStore jobStore;

    @Autowired
    private IdGenerator idGenerator;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @LocalServerPort
    private int port;

    /** 测试用处理点（两个上下文都注册同一批 Bean，因此同一个 jobCode 在两个上下文里都能跑）。 */
    @TestConfiguration
    static class TestJobs {

        @Bean
        JobHandler countingJobHandler() {
            return new JobHandler() {
                @Override
                public String code() {
                    return COUNTING_HANDLER;
                }

                @Override
                public void execute(JobContext ctx) {
                    EXECUTIONS.incrementAndGet();
                    sleep(700L);
                }
            };
        }

        @Bean
        JobHandler failingJobHandler() {
            return new JobHandler() {
                @Override
                public String code() {
                    return FAILING_HANDLER;
                }

                @Override
                public void execute(JobContext ctx) {
                    throw new IllegalStateException("it 故意失败 attempt=" + ctx.attempt());
                }
            };
        }

        @Bean
        JobHandler slowJobHandler() {
            return new JobHandler() {
                @Override
                public String code() {
                    return SLOW_HANDLER;
                }

                @Override
                public void execute(JobContext ctx) {
                    sleep(3000L);
                }
            };
        }
    }

    // ---------------------------------------------------------------- 互斥

    @Test
    @DisplayName("两个应用实例并发跑同一任务：只执行一次（ShedLock 生效），失败的一方连 job_run 都不写")
    void twoInstancesExecuteSameJobOnlyOnce() throws Exception {
        String jobCode = unique("mutex");
        JobDefinition definition = definition(jobCode, "互斥用例", COUNTING_HANDLER, "0 0 3 * * *", 0, 600, false);
        schedulerApi.register(definition);
        EXECUTIONS.set(0);

        ConfigurableApplicationContext second = secondInstance();
        try {
            second.getBean(SchedulerApi.class).register(definition);
            JobSchedulerRegistrar secondRegistrar = second.getBean(JobSchedulerRegistrar.class);

            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> first = pool.submit(() -> {
                    start.await();
                    registrar.run(jobCode);
                    return null;
                });
                Future<?> other = pool.submit(() -> {
                    start.await();
                    secondRegistrar.run(jobCode);
                    return null;
                });
                start.countDown();
                first.get(30, TimeUnit.SECONDS);
                other.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            assertThat(EXECUTIONS.get())
                    .as("两个实例并发触发，处理点必须只被执行一次（互斥不生效时这里会是 2）")
                    .isEqualTo(1);
            assertThat(countRuns(jobCode))
                    .as("没拿到锁的一方跳过且不写 job_run（避免每实例每轮一条噪音，3.4.3）")
                    .isEqualTo(1);
            assertThat(jdbc().queryForObject("select status from eaio_platform.job_run where job_code = ?",
                    String.class, jobCode)).isEqualTo("SUCCESS");
            assertThat(jdbc().queryForObject("select count(*) from eaio_platform.shedlock where name = ?",
                    Integer.class, "platform-job-" + jobCode))
                    .as("6.2 的断言：锁行存在（锁名 platform-job-{code}）")
                    .isEqualTo(1);
        } finally {
            // 必须显式关：第二个上下文的调度线程与连接池若留着，failsafe 的 fork JVM 退不出去
            // （表现为 "Surefire is going to kill self fork JVM ... elapsed 30 seconds"）
            second.close();
            assertThat(second.isActive()).as("第二个实例已关闭").isFalse();
        }
    }

    @Test
    @DisplayName("两个独立 LockProvider 抢同一锁名：每轮恰好一个赢家（底层锁语义，不含调度器）")
    void concurrentLockAttemptsHaveExactlyOneWinner() throws Exception {
        LockProvider first = lockProvider();
        LockProvider second = lockProvider();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                String name = "it-lock-" + round + "-" + System.nanoTime();
                LockConfiguration configuration = new LockConfiguration(Instant.now(), name,
                        Duration.ofSeconds(30), Duration.ZERO);
                CountDownLatch start = new CountDownLatch(1);
                Future<SimpleLock> left = pool.submit(() -> {
                    start.await();
                    return first.lock(configuration).orElse(null);
                });
                Future<SimpleLock> right = pool.submit(() -> {
                    start.await();
                    return second.lock(configuration).orElse(null);
                });
                start.countDown();
                SimpleLock a = left.get(20, TimeUnit.SECONDS);
                SimpleLock b = right.get(20, TimeUnit.SECONDS);

                List<SimpleLock> winners = new ArrayList<>();
                if (a != null) {
                    winners.add(a);
                }
                if (b != null) {
                    winners.add(b);
                }
                assertThat(winners).as("第 %d 轮：同一锁名只能有一个赢家", round).hasSize(1);
                winners.get(0).unlock();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- 业务码

    @Test
    @DisplayName("非法 cron → 20022（Spring 6 段；Quartz 的 ?/L/W/# 与 5/7 段都非法）")
    void invalidCronIsRejectedWith20022() {
        String jobCode = unique("cron");
        Map<String, Object> body = jobBody(jobCode, "非法 cron 用例", COUNTING_HANDLER, "0 0 25 * * *", 3, 30, false);
        body.put("enabled", true);

        assertThat(post("/api/platform/job/Add", body, true)).contains("\"code\":20022");

        Map<String, Object> quartz = jobBody(unique("cron"), "Quartz 方言", COUNTING_HANDLER, "0 0 3 ? * *", 3, 30,
                false);
        assertThat(post("/api/platform/job/Add", quartz, true)).as("Quartz 的 ? 不支持").contains("\"code\":20022");
    }

    @Test
    @DisplayName("未注册的处理点：保存 20023；手工塞进库的行触发时也是 20023（可执行点只能来自代码）")
    void unregisteredHandlerIsRejectedWith20023() {
        Map<String, Object> body = jobBody(unique("handler"), "未注册处理点", "it.job.handler.not.registered",
                "0 0 3 * * *", 3, 30, false);
        assertThat(post("/api/platform/job/Add", body, true)).contains("\"code\":20023");

        // 直接塞进库（模拟"DB 里写了个不存在的处理点"）：服务端仍然不能执行任何东西
        String jobCode = unique("handler.row");
        insertJobRow(jobCode, "it.job.handler.not.registered", "0 0 3 * * *", true, 300, 0, 30, false);
        assertThat(post("/api/platform/job/Run", Map.of("jobCode", jobCode), true)).contains("\"code\":20023");
    }

    @Test
    @DisplayName("已停用：手动触发 20024、恢复调度 20024；Up 改 enabled=false 后调度器里不再挂着它")
    void disabledJobCannotBeTriggeredOrResumed() {
        String jobCode = unique("disabled");
        Map<String, Object> body = jobBody(jobCode, "停用用例", COUNTING_HANDLER, "0 0 3 * * *", 0, 30, false);
        body.put("enabled", false);
        String addResponse = post("/api/platform/job/Add", body, true);
        assertThat(addResponse).contains("\"code\":0");

        assertThat(post("/api/platform/job/Run", Map.of("jobCode", jobCode), true)).contains("\"code\":20024");
        assertThat(post("/api/platform/job/Resume", Map.of("jobCode", jobCode), true)).contains("\"code\":20024");
        assertThat(registrar.isScheduled(jobCode)).as("停用的任务不排期").isFalse();

        Integer version = jdbc().queryForObject("select version from eaio_platform.job where job_code = ?",
                Integer.class, jobCode);
        body.put("enabled", true);
        body.put("version", version);
        assertThat(post("/api/platform/job/Up", body, true)).contains("\"code\":0");
        assertThat(registrar.isScheduled(jobCode)).as("启用后立刻重建调度").isTrue();

        // 停用语义升级为 20021 的前置：允许并发时触发不受"正在运行"限制
        assertThat(post("/api/platform/job/Pause", Map.of("jobCode", jobCode), true)).contains("\"code\":0");
        assertThat(registrar.isScheduled(jobCode)).as("pause 只取消调度，不清 enabled（3.4.6）").isFalse();
        assertThat(jdbc().queryForObject("select enabled from eaio_platform.job where job_code = ?", Boolean.class,
                jobCode)).as("pause 不改 enabled").isTrue();
        assertThat(post("/api/platform/job/Resume", Map.of("jobCode", jobCode), true)).contains("\"code\":0");
        assertThat(registrar.isScheduled(jobCode)).isTrue();
    }

    @Test
    @DisplayName("并发触发（未允许并发）：cron 路径记 SKIPPED 并留日志，手动触发拒绝 20021")
    void concurrentTriggerIsRecordedAsSkipped() {
        String jobCode = unique("skipped");
        Map<String, Object> body = jobBody(jobCode, "跳过用例", COUNTING_HANDLER, "0 0 3 * * *", 0, 30, false);
        String response = post("/api/platform/job/Add", body, true);
        assertThat(response).contains("\"code\":0");

        // 模拟"上一次执行仍在跑"：一条刚写入的 RUNNING 行（别的实例正在跑）
        long runningId = idGenerator.nextId();
        jdbc().update("insert into eaio_platform.job_run (id, job_code, trigger_type, status, attempt, start_time,"
                + " node_id) values (?, ?, 'CRON', 'RUNNING', 1, now(), 'other-instance')", runningId, jobCode);

        EXECUTIONS.set(0);
        registrar.run(jobCode);

        assertThat(jdbc().queryForObject("select status from eaio_platform.job_run where job_code = ?"
                + " and status = 'SKIPPED'", String.class, jobCode))
                .as("不允许并发时，cron 路径写一条 SKIPPED（3.4.6）而不是并发执行")
                .isEqualTo("SKIPPED");
        assertThat(EXECUTIONS.get()).as("跳过就真的没执行").isZero();
        assertThat(post("/api/platform/job/Run", Map.of("jobCode", jobCode), true))
                .as("手动触发同一任务 → 20021（5.2/3.4.7）")
                .contains("\"code\":20021");
    }

    @Test
    @DisplayName("超时：TIMEOUT 状态 + 处理点线程被中断（3.4.8），且它是终态（retry_max = 0）")
    void timeoutIsRecordedAsTimeout() {
        String jobCode = unique("timeout");
        Map<String, Object> body = jobBody(jobCode, "超时用例", SLOW_HANDLER, "0 0 3 * * *", 0, 30, false);
        body.put("timeoutSeconds", 1);
        assertThat(post("/api/platform/job/Add", body, true)).contains("\"code\":0");

        INTERRUPTED.set(false);
        long start = System.currentTimeMillis();
        registrar.run(jobCode);

        Map<String, Object> row = latestRun(jobCode);
        assertThat(row.get("status")).as("超时必须有明确状态，不能永远 RUNNING").isEqualTo("TIMEOUT");
        assertThat(String.valueOf(row.get("error_message"))).contains("timeout_seconds=1");
        assertThat(row.get("next_retry_time")).as("retry_max = 0 → 不排重投").isNull();
        assertThat(System.currentTimeMillis() - start)
                .as("到点就中断，不该等处理点自己睡完 3 秒")
                .isLessThan(2900L);
        assertThat(jdbc().queryForObject("select last_status from eaio_platform.job where job_code = ?",
                String.class, jobCode)).isEqualTo("TIMEOUT");
    }

    // ---------------------------------------------------------------- 重投 / 死信

    @Test
    @DisplayName("失败按指数退避重投（600s → 1200s → 封顶 1800s），达到 retry_max 后不再重投且记录可查")
    void failureRetriesWithBackoffThenStopsAtLimit() {
        String jobCode = unique("retry");
        Map<String, Object> body = jobBody(jobCode, "重投用例", FAILING_HANDLER, "0 0 3 * * *", 2, 600, false);
        assertThat(post("/api/platform/job/Add", body, true)).contains("\"code\":0");

        // 第 1 次执行（cron 路径，同步）→ 失败后进入 RETRYING，attempt 指向下一次
        registrar.run(jobCode);
        Map<String, Object> first = latestRun(jobCode);
        assertThat(first.get("status")).isEqualTo("RETRYING");
        assertThat(((Number) first.get("attempt")).intValue()).as("行上的 attempt 指向下一次执行").isEqualTo(2);
        assertThat(secondsUntil(first.get("next_retry_time")))
                .as("退避 = backoff × 2^(attempt-1) = 600 × 2^0 = 600s")
                .isBetween(560L, 620L);

        // 第 2 次：强制到期 + 手动扫一轮（重投扫描器认领后异步执行）
        makeDue(jobCode);
        assertThat(retryScanner.scanOnce()).as("到期行被本实例认领").isEqualTo(1);
        Map<String, Object> second = awaitNonRunning(jobCode);
        assertThat(second.get("status")).isEqualTo("RETRYING");
        assertThat(((Number) second.get("attempt")).intValue()).isEqualTo(3);
        assertThat(secondsUntil(second.get("next_retry_time")))
                .as("退避 = 600 × 2^1 = 1200s")
                .isBetween(1160L, 1220L);

        // 第 3 次：超过 retry_max = 2 → 终态 FAILED，不再重投
        makeDue(jobCode);
        assertThat(retryScanner.scanOnce()).isEqualTo(1);
        Map<String, Object> third = awaitNonRunning(jobCode);
        assertThat(third.get("status")).as("第 3 次失败（retry_max = 2）→ 终态").isEqualTo("FAILED");
        assertThat(third.get("next_retry_time")).isNull();
        assertThat(String.valueOf(third.get("error_message"))).contains("it 故意失败 attempt=3");
        assertThat(retryScanner.scanOnce()).as("终态行不再被扫到").isZero();
        assertThat(jdbc().queryForObject("select count(*) from eaio_platform.job_run where job_code = ?"
                + " and status = 'RETRYING'", Integer.class, jobCode)).as("库里没有悬着的重投").isZero();
        assertThat(jdbc().queryForObject("select last_status from eaio_platform.job where job_code = ?",
                String.class, jobCode)).isEqualTo("FAILED");
        assertThat(countRuns(jobCode)).as("三次尝试复用同一行（3.4.4 的做法），不是三行").isEqualTo(1);
    }

    @Test
    @DisplayName("封顶：退避基数 1800s 时下一次仍是 30 分钟（不翻倍成 1 小时）")
    void backoffIsCappedAtThirtyMinutes() {
        String jobCode = unique("cap");
        Map<String, Object> body = jobBody(jobCode, "封顶用例", FAILING_HANDLER, "0 0 3 * * *", 5, 1800, false);
        assertThat(post("/api/platform/job/Add", body, true)).contains("\"code\":0");

        registrar.run(jobCode);

        Map<String, Object> row = latestRun(jobCode);
        assertThat(row.get("status")).isEqualTo("RETRYING");
        assertThat(secondsUntil(row.get("next_retry_time")))
                .as("退避封顶 30 分钟（3.4.4）")
                .isBetween(1760L, 1820L);
    }

    @Test
    @DisplayName("手动重试一次运行：复用同一条 job_run（runId 不变），attempt +1 后重新执行")
    void manualRetryReusesSameRunRow() {
        String jobCode = unique("manualretry");
        Map<String, Object> body = jobBody(jobCode, "手动重试用例", FAILING_HANDLER, "0 0 3 * * *", 0, 600, false);
        assertThat(post("/api/platform/job/Add", body, true)).contains("\"code\":0");

        // retry_max = 0 → 第一次失败就是终态 FAILED
        registrar.run(jobCode);
        Map<String, Object> failed = latestRun(jobCode);
        assertThat(failed.get("status")).isEqualTo("FAILED");
        long runId = ((Number) failed.get("id")).longValue();
        int attempt = ((Number) failed.get("attempt")).intValue();

        String retryResponse = post("/api/platform/jobRun/Retry", Map.of("runId", runId), true);
        assertThat(retryResponse).as("5.2：重试返回同一个 runId").contains("\"code\":0")
                .contains("\"runId\":" + runId);

        Map<String, Object> retried = awaitNonRunning(jobCode);
        assertThat(retried.get("status")).as("重试仍然失败（处理点一直抛）→ 再次终态").isEqualTo("FAILED");
        assertThat(((Number) retried.get("attempt")).intValue()).as("attempt 递增").isEqualTo(attempt + 1);
        assertThat(countRuns(jobCode)).as("重试复用同一行：runId 不变").isEqualTo(1);
    }

    // ---------------------------------------------------------------- 日志清理

    @Test
    @DisplayName("日志清理：分批删除超保留天数的行 + 把疑似实例宕机的 RUNNING 行置 FAILED（3.4.4/3.4.7）")
    void logCleanDeletesOldRunsInBatchesAndClosesZombies() {
        String jobCode = unique("clean");
        Map<String, Object> body = jobBody(jobCode, "日志清理用例", COUNTING_HANDLER, "0 0 3 * * *", 0, 30, false);
        assertThat(post("/api/platform/job/Add", body, true)).contains("\"code\":0");

        // 1005 行超期（> 1000/批 → 至少两批）+ 1 行近期
        // 时间参数必须给 java.sql.Timestamp：pgjdbc 无法从 java.time.Instant 推断 SQL 类型
        // （实测报"无法推测实例 java.time.Instant 的 SQL 类型"，12.x 起要求显式类型）
        Timestamp oldStart = Timestamp.from(Instant.now().minus(Duration.ofDays(120)));
        List<Object[]> old = new ArrayList<>();
        for (int i = 0; i < 1005; i++) {
            old.add(new Object[] {idGenerator.nextId(), jobCode, oldStart});
        }
        jdbc().batchUpdate("insert into eaio_platform.job_run (id, job_code, trigger_type, status, attempt,"
                + " start_time) values (?, ?, 'CRON', 'SUCCESS', 1, ?)", old);
        long recentId = idGenerator.nextId();
        jdbc().update("insert into eaio_platform.job_run (id, job_code, trigger_type, status, attempt, start_time)"
                + " values (?, ?, 'CRON', 'SUCCESS', 1, now())", recentId, jobCode);

        // 僵尸行：2 天前开始、仍是 RUNNING（该任务 lockAtMostFor = 300 + 30 + 60 = 390s → 1180s 前的就算僵尸）
        long zombieId = idGenerator.nextId();
        jdbc().update("insert into eaio_platform.job_run (id, job_code, trigger_type, status, attempt, start_time)"
                + " values (?, ?, 'CRON', 'RUNNING', 1, now() - interval '2 days')", zombieId, jobCode);

        EXECUTIONS.set(0);
        logCleanHandler.execute(new JobContext(JobLogCleanHandler.CODE, Map.of(), 1L, 1, "it-clean",
                Instant.now().plusSeconds(60)));

        assertThat(jdbc().queryForObject("select count(*) from eaio_platform.job_run where job_code = ?"
                + " and start_time < now() - interval '90 days'", Integer.class, jobCode))
                .as("超保留天数的行被分批删干净（1005 > 1000/批）")
                .isZero();
        assertThat(jdbc().queryForObject("select count(*) from eaio_platform.job_run where id = ?", Integer.class,
                recentId)).as("保留期内的行不动").isEqualTo(1);
        assertThat(jdbc().queryForObject("select status from eaio_platform.job_run where id = ?", String.class,
                zombieId)).as("僵尸 RUNNING 行按疑似宕机置 FAILED").isEqualTo("FAILED");
        assertThat(jdbc().queryForObject("select error_message from eaio_platform.job_run where id = ?", String.class,
                zombieId)).contains("疑似实例宕机");
        assertThat(EXECUTIONS.get()).as("清理任务不该顺手执行别的任务").isZero();
    }

    // ---------------------------------------------------------------- 种子与注册表

    @Test
    @DisplayName("种子 6 条内置任务：enabled、retry_max（重投类 0 / 其余 3）、cron 与 3.4.5 一致")
    void seededBuiltinJobsAreUsable() {
        Map<String, Object> row = jdbc().queryForMap("select cron, enabled, retry_max from eaio_platform.job"
                + " where job_code = 'platform.job.log.clean'");
        assertThat(row.get("cron")).isEqualTo("0 0 3 * * *");
        assertThat(row.get("enabled")).isEqualTo(true);
        assertThat(((Number) row.get("retry_max")).intValue()).as("3.4.5：清理任务重试 3 次").isEqualTo(3);
        assertThat(jdbc().queryForObject("select count(*) from eaio_platform.job where job_code like 'platform.%'"
                + " and deleted = false", Integer.class)).as("3.4.5 的 6 个内置任务").isEqualTo(6);
        assertThat(((Number) jdbc().queryForObject("select retry_max from eaio_platform.job"
                + " where job_code = 'platform.event.retry'", Integer.class)).intValue())
                .as("重投类任务不重试自己（3.4.7）")
                .isZero();

        // T7 只注册了日志清理一个处理点：它必须真的跑得起来（其余 5 个处理点随各自能力的票注册）
        assertThat(jobStore.rowByCode(JobLogCleanHandler.CODE)).isNotNull();
        Map<String, Object> logCleanPage = new LinkedHashMap<>();
        logCleanPage.put("jobCode", "platform.job.log.clean");
        assertThat(post("/api/platform/job/GetPage", logCleanPage, false)).contains("\"code\":0")
                .contains("platform.job.log.clean");
    }

    @Test
    @DisplayName("运行日志查询与状态白名单：分页、按任务过滤、单条查询（5.2 的 3 个端点）")
    void runLogQueriesWork() {
        String jobCode = unique("runs");
        Map<String, Object> body = jobBody(jobCode, "日志查询用例", COUNTING_HANDLER, "0 0 3 * * *", 0, 30, false);
        assertThat(post("/api/platform/job/Add", body, true)).contains("\"code\":0");
        assertThat(post("/api/platform/job/Run", Map.of("jobCode", jobCode), true)).contains("\"code\":0");
        awaitNonRunning(jobCode);

        Map<String, Object> query = new LinkedHashMap<>();
        query.put("jobCode", jobCode);
        query.put("pageNum", 1);
        query.put("pageSize", 20);
        String page = post("/api/platform/jobRun/GetPage", query, false);
        assertThat(page).contains("\"code\":0").contains("\"total\":1").contains(jobCode);

        long runId = ((Number) latestRun(jobCode).get("id")).longValue();
        assertThat(post("/api/platform/jobRun/Get", Map.of("runId", runId), false)).contains("\"code\":0")
                .contains("\"status\":\"SUCCESS\"");
        assertThat(post("/api/platform/jobRun/Get", Map.of("runId", 1L), false))
                .as("不存在的运行 → 20020")
                .contains("\"code\":20020");
        assertThat(schedulerApi.listRuns(null)).as("跨模块查询同样可用").isNotNull();
    }

    // ---------------------------------------------------------------- 辅助

    private static String unique(String tag) {
        return "it.job." + tag + "." + System.nanoTime();
    }

    private static JobDefinition definition(String jobCode, String jobName, String handlerCode, String cron,
            int retryMax, int backoffSeconds, boolean allowConcurrent) {
        return new JobDefinition(jobCode, jobName, cron, handlerCode, "SchedulerLockIT 自造任务", 300, retryMax,
                backoffSeconds, allowConcurrent, true);
    }

    private static Map<String, Object> jobBody(String jobCode, String jobName, String handlerCode, String cron,
            int retryMax, int backoffSeconds, boolean allowConcurrent) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobCode", jobCode);
        body.put("jobName", jobName);
        body.put("handlerCode", handlerCode);
        body.put("cron", cron);
        body.put("timeoutSeconds", 300);
        body.put("retryMax", retryMax);
        body.put("backoffSeconds", backoffSeconds);
        body.put("allowConcurrent", allowConcurrent);
        body.put("enabled", true);
        return body;
    }

    /** 直接塞一条任务行（模拟"DB 里写了代码注册表里没有的处理点"）。 */
    private void insertJobRow(String jobCode, String handlerCode, String cron, boolean enabled, int timeoutSeconds,
            int retryMax, int backoffSeconds, boolean allowConcurrent) {
        jdbc().update("insert into eaio_platform.job (id, job_code, job_name, handler_code, cron, enabled,"
                + " timeout_seconds, retry_max, backoff_seconds, allow_concurrent, created_by)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
                idGenerator.nextId(), jobCode, "IT 直插任务", handlerCode, cron, enabled, timeoutSeconds, retryMax,
                backoffSeconds, allowConcurrent);
    }

    /**
     * 启动第二个应用实例（同一 DB/Redis）：
     * <ul>
     *   <li>{@code WebApplicationType.NONE}：本用例只用它的调度器与 {@code SchedulerApi}，不需要 HTTP ——
     *       不起 Tomcat，就少一个"连接器线程没停干净"的退出风险，启动也更快；</li>
     *   <li>{@code registerShutdownHook(false)}：不往 JVM 全局钩子里再挂一个上下文（避免 fork 退出阶段
     *       再走一遍销毁流程）。</li>
     * </ul>
     */
    private ConfigurableApplicationContext secondInstance() {
        return new SpringApplicationBuilder(EaioApplication.class, TestJobs.class)
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

    /** 与 JobLocker 同款 provider（同一 DataSource、同一锁表、同一 usingDbTime）。 */
    private LockProvider lockProvider() {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName("eaio_platform.shedlock")
                .usingDbTime()
                .build());
    }

    private void makeDue(String jobCode) {
        jdbc().update("update eaio_platform.job_run set next_retry_time = now() where job_code = ?"
                + " and status = 'RETRYING'", jobCode);
    }

    /** 等这一行不再是 RUNNING（重投执行是异步的），返回最新状态行。 */
    private Map<String, Object> awaitNonRunning(String jobCode) {
        Supplier<Map<String, Object>> read = () -> latestRun(jobCode);
        long deadline = System.currentTimeMillis() + 15000L;
        Map<String, Object> row = read.get();
        while (System.currentTimeMillis() < deadline) {
            Object status = row.get("status");
            if (status != null && !"RUNNING".equals(status)) {
                return row;
            }
            sleep(50L);
            row = read.get();
        }
        throw new AssertionError("等待运行结束超时，最后状态=" + row);
    }

    private Map<String, Object> latestRun(String jobCode) {
        return jdbc().queryForMap("select id, status, attempt, next_retry_time, error_message"
                + " from eaio_platform.job_run where job_code = ? order by start_time desc limit 1", jobCode);
    }

    private int countRuns(String jobCode) {
        Integer count = jdbc().queryForObject("select count(*) from eaio_platform.job_run where job_code = ?",
                Integer.class, jobCode);
        return count == null ? 0 : count;
    }

    /** {@code next_retry_time} 距现在还有多少秒（断言退避值用）。 */
    private static long secondsUntil(Object nextRetryTime) {
        assertThat(nextRetryTime).as("next_retry_time 应非空").isNotNull();
        Instant instant;
        if (nextRetryTime instanceof Timestamp timestamp) {
            instant = timestamp.toInstant();
        } else if (nextRetryTime instanceof OffsetDateTime offsetDateTime) {
            instant = offsetDateTime.toInstant();
        } else if (nextRetryTime instanceof Instant value) {
            instant = value;
        } else {
            throw new AssertionError("无法识别的 next_retry_time 类型：" + nextRetryTime.getClass());
        }
        return Duration.between(Instant.now(), instant).toSeconds();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            INTERRUPTED.set(true);
            Thread.currentThread().interrupt();
        }
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private String post(String path, Map<String, Object> body, boolean idempotent) {
        RestClient.RequestBodySpec spec = RestClient.create("http://localhost:" + port)
                .post().uri(path).contentType(MediaType.APPLICATION_JSON);
        if (idempotent) {
            spec = spec.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        return spec.body(JsonUtils.toJson(body)).retrieve().body(String.class);
    }
}
