package com.eaio.platform.application.monitor;

import com.eaio.common.api.ErrorCode;
import com.eaio.common.api.PageResult;
import com.eaio.common.exception.BusinessException;
import com.eaio.common.id.IdGenerator;
import com.eaio.common.redis.RedisKeys;
import com.eaio.platform.api.MonitorApi;
import com.eaio.platform.api.PlatformErrorCode;
import com.eaio.platform.api.dto.AlertDTO;
import com.eaio.platform.api.dto.AlertQuery;
import com.eaio.platform.api.dto.AlertRuleDTO;
import com.eaio.platform.api.dto.AlertRuleQuery;
import com.eaio.platform.api.dto.AlertRuleSaveCmd;
import com.eaio.platform.api.dto.MetricSnapshotDTO;
import com.eaio.platform.api.dto.NoticePublishCmd;
import com.eaio.platform.application.NoticeAppService;
import com.eaio.platform.application.param.ParamContextProvider;
import com.eaio.platform.application.param.ParamResolver;
import com.eaio.platform.domain.param.ParamContext;
import com.eaio.platform.domain.monitor.Alert;
import com.eaio.platform.domain.monitor.AlertRule;
import com.eaio.platform.events.EventDeadLetteredEvent;
import com.eaio.platform.events.JobFailedEvent;
import com.eaio.platform.infrastructure.monitor.MetricSnapshotReader;
import com.eaio.platform.infrastructure.persistence.AlertRuleStore;
import com.eaio.platform.infrastructure.persistence.AlertStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Monitoring facade and the single alert state transition owner. */
@Service
public class AlertAppService implements MonitorApi {
  private static final Logger log = LoggerFactory.getLogger(AlertAppService.class);
  private static final BigDecimal MIN_THRESHOLD = new BigDecimal("-100000000000");
  private static final BigDecimal MAX_THRESHOLD = new BigDecimal("100000000000");
  private static final Set<String> SUPPORTED_METRICS = Set.of(
      "platform.job.failure", "eaio_platform_job_failure_total",
      "platform.event.dead-letter", "eaio_platform_event_dead_letter_total",
      "platform.cache.degraded", "eaio_platform_cache_op_error_total",
      "platform.audit.fallback.count", "platform.file.storage.error", "platform.param.missing",
      "platform.dict.label.miss");
  private final AlertRuleStore rules;
  private final AlertStore alerts;
  private final MetricSnapshotReader snapshots;
  private final IdGenerator ids;
  private final ParamContextProvider contexts;
  private final ParamResolver params;
  private final ObjectProvider<StringRedisTemplate> redis;
  private final NoticeAppService notices;
  private final String pendingPrefix;
  private final Map<String, Long> pending = new ConcurrentHashMap<>();

  public AlertAppService(
      AlertRuleStore rules,
      AlertStore alerts,
      MetricSnapshotReader snapshots,
      IdGenerator ids,
      ParamContextProvider contexts,
      ParamResolver params,
      ObjectProvider<StringRedisTemplate> redis,
      NoticeAppService notices,
      Environment environment) {
    this.rules = rules;
    this.alerts = alerts;
    this.snapshots = snapshots;
    this.ids = ids;
    this.contexts = contexts;
    this.params = params;
    this.redis = redis;
    this.notices = notices;
    String env = RedisKeys.envOf(environment.getProperty("eaio.env"), environment.getActiveProfiles());
    this.pendingPrefix = RedisKeys.of(env, "platform", "alert", "pending") + ":";
  }

  @Override
  public MetricSnapshotDTO metrics() {
    Map<String, String> health = snapshots.health();
    if (health.values().stream().anyMatch("UNKNOWN"::equals)) {
      throw new BusinessException(PlatformErrorCode.MONITOR_UNAVAILABLE,
          "监测数据不可用：" + health);
    }
    try {
      return snapshots.read();
    } catch (RuntimeException e) {
      throw new BusinessException(PlatformErrorCode.MONITOR_UNAVAILABLE.getCode(), "监测数据采集失败", e);
    }
  }

  @Override
  public Map<String, String> health() {
    return snapshots.health();
  }

  @Override
  public List<AlertDTO> alerts() {
    return alerts.open().stream().map(this::alertDto).toList();
  }

  public PageResult<AlertDTO> pageAlerts(AlertQuery query) {
    var page = alerts.page(query);
    return PageResult.from(
        page.getCurrent(),
        page.getSize(),
        page.getTotal(),
        page.getRecords().stream().map(this::alertDto).toList());
  }

  public PageResult<AlertRuleDTO> pageRules(AlertRuleQuery query) {
    var page = rules.page(query);
    return PageResult.from(
        page.getCurrent(),
        page.getSize(),
        page.getTotal(),
        page.getRecords().stream().map(this::ruleDto).toList());
  }

  @Transactional
  public AlertRuleDTO addRule(AlertRuleSaveCmd cmd) {
    if (rules.rowByCode(cmd.ruleCode().trim()) != null) {
      throw new BusinessException(ErrorCode.DATA_CONFLICT, "告警规则编码已存在：" + cmd.ruleCode());
    }
    validate(cmd);
    AlertRule row = new AlertRule();
    row.setId(ids.nextId());
    row.setRuleCode(cmd.ruleCode().trim());
    apply(row, cmd, true);
    row.setBuiltin(false);
    row.setCreatedAt(Instant.now());
    row.setCreatedBy(operator());
    row.setVersion(0);
    row.setDeleted(false);
    rules.insert(row);
    return ruleDto(row);
  }

  @Transactional
  public AlertRuleDTO updateRule(AlertRuleSaveCmd cmd) {
    if (cmd.version() == null) {
      throw new BusinessException(ErrorCode.DATA_CONFLICT, "更新告警规则必须带 version");
    }
    AlertRule row = requireRule(cmd.ruleCode());
    validate(cmd);
    apply(row, cmd, false);
    row.setUpdatedAt(Instant.now());
    row.setUpdatedBy(operator());
    row.setVersion(cmd.version());
    if (rules.update(row) == 0) {
      throw new BusinessException(ErrorCode.DATA_CONFLICT, "告警规则已被他人修改");
    }
    return ruleDto(row);
  }

  @Transactional
  public void deleteRule(String code, int version) {
    AlertRule row = requireRule(code);
    if (Boolean.TRUE.equals(row.getBuiltin())) {
      throw new BusinessException(PlatformErrorCode.ALERT_RULE_INVALID, "内置告警规则不可删除，只可停用：" + code);
    }
    if (row.getVersion() == null || row.getVersion() != version) {
      throw new BusinessException(ErrorCode.DATA_CONFLICT, "告警规则已被他人修改");
    }
    row.setVersion(version);
    rules.delete(row);
  }

  @Transactional
  public AlertDTO ack(long id) {
    Alert current = requireAlert(id);
    if ("ACKED".equals(current.getStatus())) {
      return alertDto(current);
    }
    if (!"OPEN".equals(current.getStatus())) {
      throw new BusinessException(ErrorCode.DATA_CONFLICT, "告警当前状态不可确认：" + current.getStatus());
    }
    if (!alerts.ack(id, operator(), Instant.now())) {
      Alert latest = requireAlert(id);
      if ("ACKED".equals(latest.getStatus())) {
        return alertDto(latest);
      }
      throw new BusinessException(ErrorCode.DATA_CONFLICT, "告警状态已被他人修改：" + id);
    }
    return alertDto(requireAlert(id));
  }

  @Transactional
  public AlertDTO resolve(long id) {
    Alert current = requireAlert(id);
    if ("RESOLVED".equals(current.getStatus())) {
      return alertDto(current);
    }
    if (!alerts.resolve(id, operator(), Instant.now())) {
      Alert latest = requireAlert(id);
      if ("RESOLVED".equals(latest.getStatus())) {
        return alertDto(latest);
      }
      throw new BusinessException(ErrorCode.DATA_CONFLICT, "告警状态已被他人修改：" + id);
    }
    return alertDto(requireAlert(id));
  }

  /** Called by the registered one-minute job. */
  public void evaluate() {
    MetricSnapshotDTO snapshot = snapshots.read();
    for (AlertRule rule : rules.enabled()) {
      BigDecimal value = metric(snapshot, rule.getMetricKey());
      if (value != null) {
        evaluateRule(rule, value, "指标越线：" + rule.getMetricKey() + "=" + value);
      } else {
        clearPending(rule.getRuleCode());
      }
    }
  }

  @EventListener
  public void onJobFailed(JobFailedEvent event) {
    signal(
        "job.failure",
        BigDecimal.valueOf(Math.max(1, event.failCount())),
        "任务连续失败：jobCode=" + event.jobCode() + "，错误=" + event.errorMessage());
  }

  @EventListener
  public void onDeadLetter(EventDeadLetteredEvent event) {
    signal(
        "event.dead-letter",
        BigDecimal.ONE,
        "事件进入死信：eventId=" + event.originalEventId() + "，错误=" + event.lastError());
  }

  private void signal(String code, BigDecimal value, String detail) {
    AlertRule rule;
    try {
      rule = rules.rowByCode(code);
    } catch (RuntimeException e) {
      log.warn("告警事件无法读取规则：{}", code);
      return;
    }
    if (rule == null || !Boolean.TRUE.equals(rule.getEnabled())) {
      return;
    }
    evaluateRule(rule, value, detail, true);
  }

  private void evaluateRule(AlertRule rule, BigDecimal value, String detail) {
    evaluateRule(rule, value, detail, false);
  }

  private void evaluateRule(AlertRule rule, BigDecimal value, String detail, boolean immediate) {
    BigDecimal threshold = thresholdFor(rule);
    if (!matches(rule.getOperator(), value, threshold)) {
      clearPending(rule.getRuleCode());
      return;
    }
    long now = System.currentTimeMillis();
    long first = pendingSince(rule.getRuleCode());
    if (first == 0) {
      first = now;
      savePending(rule.getRuleCode(), first, rule.getDurationSeconds());
    }
    if (!immediate && now - first < rule.getDurationSeconds() * 1000L) {
      return;
    }
    if (rule.getSilenceSeconds() != null && rule.getSilenceSeconds() > 0) {
      Alert open = alerts.openByRule(rule.getRuleCode());
      if (open != null
          && now - open.getLastTriggerTime().toEpochMilli() < rule.getSilenceSeconds() * 1000L) {
        alerts.incrementOpen(rule.getRuleCode(), Instant.now(), value);
        return;
      }
    }
    Alert row = new Alert();
    row.setId(ids.nextId());
    row.setRuleId(rule.getId());
    row.setRuleCode(rule.getRuleCode());
    row.setSeverity(rule.getSeverity());
    row.setStatus("OPEN");
    row.setTitle(rule.getRuleName());
    row.setDetail(detail);
    row.setMetricValue(value);
    row.setThresholdValue(threshold);
    row.setFirstTriggerTime(Instant.now());
    row.setLastTriggerTime(row.getFirstTriggerTime());
    row.setTriggerCount(1);
    row.setCreatedAt(row.getFirstTriggerTime());
    row.setCreatedBy(0L);
    row.setVersion(0);
    if (alerts.insertOpenIfAbsent(row) == 1 && Boolean.TRUE.equals(rule.getNotifySite())) {
      publishNotice(row);
    } else if (alerts.openByRule(rule.getRuleCode()) != null) {
      alerts.incrementOpen(rule.getRuleCode(), Instant.now(), value);
    }
    clearPending(rule.getRuleCode());
  }

  private void publishNotice(Alert row) {
    try {
      var notice = notices.save(new NoticePublishCmd(null, row.getTitle(),
          row.getDetail() == null ? row.getTitle() : row.getDetail(), "ALL", null, null,
          Instant.now(), null, false));
      alerts.attachNotice(row.getId(), notice.id());
    } catch (RuntimeException e) {
      log.warn("告警已落库但站内公告写入失败：alertId={}，原因={}", row.getId(), e.toString());
    }
  }

  private long pendingSince(String code) {
    String key = pendingKey(code);
    try {
      String value =
          redis.getIfAvailable() == null ? null : redis.getIfAvailable().opsForValue().get(key);
      if (value != null) {
        return Long.parseLong(value);
      }
    } catch (RuntimeException ignored) {
      log.debug("读取告警持续时长状态失败，使用本机降级：{}", ignored.toString());
    }
    return pending.getOrDefault(code, 0L);
  }

  private void savePending(String code, long first, int durationSeconds) {
    pending.put(code, first);
    try {
      StringRedisTemplate template = redis.getIfAvailable();
      if (template != null) {
        template
            .opsForValue()
            .set(
                pendingKey(code),
                Long.toString(first),
                Math.max(1, durationSeconds * 2L),
                java.util.concurrent.TimeUnit.SECONDS);
      }
    } catch (RuntimeException e) {
      log.debug("告警持续时长状态无法写 Redis，使用本机降级：{}", e.toString());
    }
  }

  private void clearPending(String code) {
    pending.remove(code);
    try {
      if (redis.getIfAvailable() != null) {
        redis.getIfAvailable().delete(pendingKey(code));
      }
    } catch (RuntimeException ignored) {
      log.debug("清理告警持续时长状态失败：{}", ignored.toString());
    }
  }

  private String pendingKey(String code) {
    return pendingPrefix + code;
  }

  private AlertRule requireRule(String code) {
    AlertRule row = rules.rowByCode(code == null ? "" : code.trim());
    if (row == null) {
      throw new BusinessException(PlatformErrorCode.ALERT_RULE_NOT_FOUND, "告警规则不存在：" + code);
    }
    return row;
  }

  private Alert requireAlert(long id) {
    Alert row = alerts.row(id);
    if (row == null) {
      throw new BusinessException(PlatformErrorCode.ALERT_NOT_FOUND, "告警记录不存在：" + id);
    }
    return row;
  }

  private long operator() {
    return contexts.current().userId();
  }

  private static boolean matches(String op, BigDecimal value, BigDecimal threshold) {
    int c = value.compareTo(threshold);
    return switch (op == null ? "" : op.toUpperCase()) {
      case "GT" -> c > 0;
      case "GTE" -> c >= 0;
      case "LT" -> c < 0;
      case "LTE" -> c <= 0;
      case "EQ" -> c == 0;
      default -> false;
    };
  }

  private static BigDecimal metric(MetricSnapshotDTO s, String key) {
    return switch (key == null ? "" : key) {
      case "platform.job.failure", "eaio_platform_job_failure_total" ->
          BigDecimal.valueOf(s.jobs().failures24h());
      case "platform.event.dead-letter", "eaio_platform_event_dead_letter_total" ->
          BigDecimal.valueOf(s.events().deadLetters());
      case "platform.cache.degraded", "eaio_platform_cache_op_error_total" ->
          BigDecimal.valueOf(s.cache().errors());
      case "platform.audit.fallback.count" -> BigDecimal.valueOf(s.fallback().audit());
      case "platform.file.storage.error" -> BigDecimal.valueOf(s.fallback().fileStorageErrors());
      case "platform.param.missing" -> BigDecimal.valueOf(s.fallback().paramMissing());
      case "platform.dict.label.miss" -> BigDecimal.valueOf(s.fallback().dictMisses());
      default -> null;
    };
  }

  private BigDecimal thresholdFor(AlertRule rule) {
    if (!"job.failure".equals(rule.getRuleCode())) {
      return rule.getThreshold();
    }
    String fallback = rule.getThreshold() == null ? "3" : rule.getThreshold().toPlainString();
    try {
      String value = params.resolveOrDefault("platform.job.alert.fail-threshold", fallback,
          ParamContext.systemOnly()).value();
      return new BigDecimal(value.trim()).max(BigDecimal.ONE);
    } catch (RuntimeException e) {
      log.warn("任务失败告警阈值读取失败，使用规则阈值：{}", fallback);
      return new BigDecimal(fallback);
    }
  }

  private static void validate(AlertRuleSaveCmd cmd) {
    String metricKey = cmd.metricKey() == null ? "" : cmd.metricKey().trim();
    String operator = cmd.operator() == null ? "" : cmd.operator().trim().toUpperCase();
    String severity = cmd.severity() == null ? "" : cmd.severity().trim().toUpperCase();
    if (!SUPPORTED_METRICS.contains(metricKey)
        || !List.of("GT", "GTE", "LT", "LTE", "EQ").contains(operator)
        || !List.of("INFO", "WARN", "CRITICAL").contains(severity)
        || cmd.threshold() == null
        || cmd.threshold().compareTo(MIN_THRESHOLD) < 0
        || cmd.threshold().compareTo(MAX_THRESHOLD) > 0
        || (cmd.durationSeconds() != null && cmd.durationSeconds() < 0)
        || (cmd.silenceSeconds() != null && cmd.silenceSeconds() < 0)) {
      throw new BusinessException(
          PlatformErrorCode.ALERT_RULE_INVALID, "告警规则 metricKey/operator/severity/阈值非法");
    }
  }

  private static void apply(AlertRule row, AlertRuleSaveCmd cmd, boolean add) {
    row.setRuleName(cmd.ruleName().trim());
    row.setMetricKey(cmd.metricKey().trim());
    row.setOperator(cmd.operator().trim().toUpperCase());
    row.setThreshold(cmd.threshold());
    row.setDurationSeconds(cmd.durationSeconds() == null ? 0 : cmd.durationSeconds());
    row.setSeverity(cmd.severity().trim().toUpperCase());
    row.setSilenceSeconds(cmd.silenceSeconds() == null ? 1800 : cmd.silenceSeconds());
    row.setNotifySite(cmd.notifySite() == null || cmd.notifySite());
    row.setEnabled(cmd.enabled() == null || cmd.enabled());
    row.setRemark(cmd.remark());
  }

  private AlertDTO alertDto(Alert row) {
    return new AlertDTO(
        row.getId(),
        row.getRuleCode(),
        row.getSeverity(),
        row.getStatus(),
        row.getTitle(),
        row.getDetail(),
        row.getMetricValue(),
        row.getThresholdValue(),
        row.getFirstTriggerTime(),
        row.getLastTriggerTime(),
        row.getTriggerCount() == null ? 0 : row.getTriggerCount(),
        row.getAckBy(),
        row.getAckTime(),
        row.getResolveTime(),
        row.getNoticeId(),
        row.getVersion() == null ? 0 : row.getVersion());
  }

  private AlertRuleDTO ruleDto(AlertRule row) {
    return new AlertRuleDTO(
        row.getId(),
        row.getRuleCode(),
        row.getRuleName(),
        row.getMetricKey(),
        row.getOperator(),
        row.getThreshold(),
        row.getDurationSeconds(),
        row.getSeverity(),
        row.getSilenceSeconds(),
        Boolean.TRUE.equals(row.getNotifySite()),
        Boolean.TRUE.equals(row.getEnabled()),
        Boolean.TRUE.equals(row.getBuiltin()),
        row.getRemark(),
        row.getVersion() == null ? 0 : row.getVersion());
  }
}
