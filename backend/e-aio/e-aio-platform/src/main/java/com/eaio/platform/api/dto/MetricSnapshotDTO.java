package com.eaio.platform.api.dto;

import java.time.Instant;

public record MetricSnapshotDTO(
    Instant capturedAt,
    Jvm jvm,
    Cpu cpu,
    Threads threads,
    DbPool dbPool,
    Redis redis,
    Jobs jobs,
    Cache cache,
    Events events,
    Alerts alerts,
    Fallback fallback) {

  public record Jvm(
      long heapUsedBytes, long heapMaxBytes, long nonHeapUsedBytes, long gcCount, long gcTimeMs) {}

  public record Cpu(double processLoad, double systemLoad) {}

  public record Threads(int active, int peak) {}

  public record DbPool(int active, int idle, int pending) {}

  public record Redis(String status, long latencyMs) {}

  public record Jobs(long failures24h) {}

  public record Cache(long hits, long misses, long errors, double hitRate) {}

  public record Events(long deadLetters) {}

  public record Alerts(long open) {}

  public record Fallback(
      long audit,
      long auditWriteFailures,
      long dictMisses,
      long fileStorageErrors,
      long paramMissing) {}
}
