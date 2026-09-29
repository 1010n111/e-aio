package com.eaio.platform.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaio.common.exception.SystemException;
import com.eaio.platform.api.dto.AlertRuleQuery;
import com.eaio.platform.domain.monitor.AlertRule;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class AlertRuleStore {
  private final ObjectProvider<AlertRuleMapper> mappers;

  public AlertRuleStore(ObjectProvider<AlertRuleMapper> mappers) {
    this.mappers = mappers;
  }

  public AlertRule rowByCode(String code) {
    return mapper().selectOne(new LambdaQueryWrapper<AlertRule>().eq(AlertRule::getRuleCode, code));
  }

  public List<AlertRule> enabled() {
    return mapper()
        .selectList(
            new LambdaQueryWrapper<AlertRule>()
                .eq(AlertRule::getEnabled, true)
                .orderByAsc(AlertRule::getId));
  }

  public IPage<AlertRule> page(AlertRuleQuery query) {
    AlertRuleQuery q =
        query == null ? new AlertRuleQuery(null, null, null, 1, 20, null, null) : query;
    LambdaQueryWrapper<AlertRule> w = new LambdaQueryWrapper<>();
    if (q.ruleCode() != null && !q.ruleCode().isBlank()) {
      w.like(AlertRule::getRuleCode, q.ruleCode().trim());
    }
    if (q.metricKey() != null && !q.metricKey().isBlank()) {
      w.eq(AlertRule::getMetricKey, q.metricKey().trim());
    }
    if (q.enabled() != null) {
      w.eq(AlertRule::getEnabled, q.enabled());
    }
    if (!"asc".equalsIgnoreCase(q.orderDir())) {
      w.orderByDesc(AlertRule::getId);
    } else {
      w.orderByAsc(AlertRule::getId);
    }
    return mapper().selectPage(new Page<>(page(q.pageNum()), size(q.pageSize())), w);
  }

  public int insert(AlertRule row) {
    return mapper().insert(row);
  }

  public int update(AlertRule row) {
    return mapper().updateById(row);
  }

  public int delete(AlertRule row) {
    return mapper().deleteById(row);
  }

  private AlertRuleMapper mapper() {
    AlertRuleMapper mapper = mappers.getIfAvailable();
    if (mapper == null) {
      throw new SystemException("告警规则不可用：未配置数据库");
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
