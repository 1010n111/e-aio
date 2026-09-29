package com.eaio.platform.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaio.common.exception.SystemException;
import com.eaio.platform.api.dto.AlertQuery;
import com.eaio.platform.domain.monitor.Alert;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class AlertStore {
  private final ObjectProvider<AlertMapper> mappers;

  public AlertStore(ObjectProvider<AlertMapper> mappers) {
    this.mappers = mappers;
  }

  public Alert row(long id) {
    return mapper().selectById(id);
  }

  public Alert openByRule(String code) {
    return mapper()
        .selectOne(
            new LambdaQueryWrapper<Alert>()
                .eq(Alert::getRuleCode, code)
                .ne(Alert::getStatus, "RESOLVED")
                .orderByDesc(Alert::getLastTriggerTime)
                .last("LIMIT 1"));
  }

  public List<Alert> open() {
    return mapper()
        .selectList(
            new LambdaQueryWrapper<Alert>()
                .eq(Alert::getStatus, "OPEN")
                .orderByDesc(Alert::getLastTriggerTime));
  }

  public IPage<Alert> page(AlertQuery query) {
    AlertQuery q = query == null ? new AlertQuery(null, null, "OPEN", 1, 20, null, null) : query;
    LambdaQueryWrapper<Alert> w = new LambdaQueryWrapper<>();
    if (q.ruleCode() != null && !q.ruleCode().isBlank()) {
      w.eq(Alert::getRuleCode, q.ruleCode().trim());
    }
    if (q.severity() != null && !q.severity().isBlank()) {
      w.eq(Alert::getSeverity, q.severity().trim().toUpperCase());
    }
    if (q.status() != null && !q.status().isBlank()) {
      w.eq(Alert::getStatus, q.status().trim().toUpperCase());
    }
    if (!"asc".equalsIgnoreCase(q.orderDir())) {
      w.orderByDesc(Alert::getLastTriggerTime);
    } else {
      w.orderByAsc(Alert::getLastTriggerTime);
    }
    return mapper().selectPage(new Page<>(page(q.pageNum()), size(q.pageSize())), w);
  }

  public int insert(Alert row) {
    return mapper().insert(row);
  }

  public int insertOpenIfAbsent(Alert row) {
    return mapper().insertOpenIfAbsent(row);
  }

  public boolean incrementOpen(String ruleCode, Instant time, BigDecimal value) {
    return mapper().incrementOpen(ruleCode, time, value) > 0;
  }

  public boolean ack(long id, long operator, Instant time) {
    return mapper().ack(id, operator, time) > 0;
  }

  public boolean resolve(long id, long operator, Instant time) {
    return mapper().resolve(id, operator, time) > 0;
  }

  public boolean attachNotice(long id, long noticeId) {
    return mapper().attachNotice(id, noticeId) > 0;
  }

  private AlertMapper mapper() {
    AlertMapper mapper = mappers.getIfAvailable();
    if (mapper == null) {
      throw new SystemException("告警记录不可用：未配置数据库");
    }
    return mapper;
  }

  private static long page(Integer n) {
    return n == null || n < 1 ? 1 : n;
  }

  private static long size(Integer n) {
    return n == null || n < 1 || n > 200 ? 20 : n;
  }
}
