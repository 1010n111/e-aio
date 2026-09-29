package com.eaio.platform.infrastructure.monitor;

import com.eaio.platform.api.dto.MetricSnapshotDTO;
import com.eaio.platform.application.cache.CacheManager;
import com.eaio.platform.application.dict.DictResolver;
import com.eaio.platform.application.file.AuditFallbackRecorder;
import com.eaio.platform.application.file.FileStorageErrorRecorder;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads application side facts. Every external probe degrades to an explicit UNKNOWN/zero value.
 */
@Component
public class MetricSnapshotReader {
  private static final Logger log = LoggerFactory.getLogger(MetricSnapshotReader.class);

  private final ObjectProvider<JdbcTemplate> jdbc;
  private final ObjectProvider<RedisConnectionFactory> redis;
  private final ObjectProvider<CacheManager> cache;
  private final ObjectProvider<AuditFallbackRecorder> audit;
  private final ObjectProvider<DictResolver> dict;
  private final ObjectProvider<FileStorageErrorRecorder> storageErrors;

  public MetricSnapshotReader(
      ObjectProvider<JdbcTemplate> jdbc,
      ObjectProvider<RedisConnectionFactory> redis,
      ObjectProvider<CacheManager> cache,
      ObjectProvider<AuditFallbackRecorder> audit,
      ObjectProvider<DictResolver> dict,
      ObjectProvider<FileStorageErrorRecorder> storageErrors) {
    this.jdbc = jdbc;
    this.redis = redis;
    this.cache = cache;
    this.audit = audit;
    this.dict = dict;
    this.storageErrors = storageErrors;
  }

  public MetricSnapshotDTO read() {
    MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    var os = ManagementFactory.getOperatingSystemMXBean();
    long gcCount = 0;
    long gcTime = 0;
    for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
      if (gc.getCollectionCount() > 0) {
        gcCount += gc.getCollectionCount();
      }
      if (gc.getCollectionTime() > 0) {
        gcTime += gc.getCollectionTime();
      }
    }
    var heap = memory.getHeapMemoryUsage();
    var nonHeap = memory.getNonHeapMemoryUsage();
    MetricSnapshotDTO.Redis redisState = redisHealth();
    long failures =
        count(
            "SELECT count(*) FROM eaio_platform.job_run WHERE status IN ('FAILED','TIMEOUT') "
                + "AND start_time >= now() - interval '24 hours'");
    long dead = count("SELECT count(*) FROM eaio_platform.event_delivery WHERE status = 'DEAD'");
    long openAlerts = count("SELECT count(*) FROM eaio_platform.alert WHERE status = 'OPEN'");
    CacheManager cacheManager = cache.getIfAvailable();
    long cacheErrors = cacheManager == null ? 0 : cacheManager.opErrorCount();
    long auditCount = audit.getIfAvailable() == null ? 0 : audit.getIfAvailable().fallbackCount();
    long auditFailures = audit.getIfAvailable() == null ? 0 : audit.getIfAvailable().failureCount();
    long dictMisses = dict.getIfAvailable() == null ? 0 : dict.getIfAvailable().labelMissCount();
    double processLoad = os.getSystemLoadAverage();
    return new MetricSnapshotDTO(
        Instant.now(),
        new MetricSnapshotDTO.Jvm(
            heap.getUsed(), heap.getMax(), nonHeap.getUsed(), gcCount, gcTime),
        new MetricSnapshotDTO.Cpu(processLoad, processLoad),
        new MetricSnapshotDTO.Threads(threads.getThreadCount(), threads.getPeakThreadCount()),
        new MetricSnapshotDTO.DbPool(0, 0, 0),
        redisState,
        new MetricSnapshotDTO.Jobs(failures),
        new MetricSnapshotDTO.Cache(
            cacheManager == null ? 0 : cacheManager.cacheHitCount(),
            cacheManager == null ? 0 : cacheManager.cacheMissCount(),
            cacheErrors,
            cacheManager == null ? 0 : cacheManager.cacheHitRate()),
        new MetricSnapshotDTO.Events(dead),
        new MetricSnapshotDTO.Alerts(openAlerts),
        new MetricSnapshotDTO.Fallback(
            auditCount, auditFailures, dictMisses,
            storageErrors.getIfAvailable() == null ? 0 : storageErrors.getIfAvailable().count(),
            missingRequiredParams()));
  }

  public Map<String, String> health() {
    Map<String, String> state = new LinkedHashMap<>();
    state.put("database", probeDatabase() ? "UP" : "UNKNOWN");
    state.put("redis", redisHealth().status());
    state.put("jvm", "UP");
    return state;
  }

  private boolean probeDatabase() {
    try {
      return count("SELECT 1") == 1;
    } catch (RuntimeException e) {
      log.warn("监测数据库探针不可用，健康状态返回 UNKNOWN：{}", e.toString());
      return false;
    }
  }

  private MetricSnapshotDTO.Redis redisHealth() {
    RedisConnectionFactory factory = redis.getIfAvailable();
    if (factory == null) {
      return new MetricSnapshotDTO.Redis("UNKNOWN", -1);
    }
    long start = System.nanoTime();
    try (RedisConnection connection = factory.getConnection()) {
      connection.ping();
      return new MetricSnapshotDTO.Redis(
          "UP", Duration.ofNanos(System.nanoTime() - start).toMillis());
    } catch (RuntimeException e) {
      log.warn("监测 Redis 探针不可用，健康状态返回 UNKNOWN：{}", e.toString());
      return new MetricSnapshotDTO.Redis("UNKNOWN", -1);
    }
  }

    private long count(String sql) {
        JdbcTemplate template = jdbc.getIfAvailable();
        if (template == null) {
            throw new IllegalStateException("数据库探针不可用：未配置 JdbcTemplate");
        }
        try {
            return template.queryForObject(sql, Long.class);
        } catch (RuntimeException e) {
            throw new IllegalStateException("数据库指标查询失败", e);
        }
  }

    private long missingRequiredParams() {
        JdbcTemplate template = jdbc.getIfAvailable();
        if (template == null) {
            throw new IllegalStateException("数据库参数指标不可用：未配置 JdbcTemplate");
        }
        try {
            return template.queryForObject(
          "SELECT count(*) FROM eaio_platform.param WHERE builtin = true "
                  + "AND deleted = false AND param_value IS NULL",
              Long.class);
        } catch (RuntimeException e) {
            throw new IllegalStateException("参数指标查询失败", e);
        }
  }
}
