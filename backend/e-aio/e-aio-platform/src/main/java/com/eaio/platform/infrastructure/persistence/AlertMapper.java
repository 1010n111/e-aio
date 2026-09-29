package com.eaio.platform.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaio.platform.domain.monitor.Alert;
import java.math.BigDecimal;
import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AlertMapper extends BaseMapper<Alert> {
  @Insert("INSERT INTO eaio_platform.alert "
      + "(id, rule_id, rule_code, severity, status, title, detail, metric_value, threshold_value, "
      + "first_trigger_time, last_trigger_time, trigger_count, created_at, created_by, version) "
      + "VALUES (#{row.id}, #{row.ruleId}, #{row.ruleCode}, #{row.severity}, 'OPEN', #{row.title}, "
      + "#{row.detail}, #{row.metricValue}, #{row.thresholdValue}, #{row.firstTriggerTime}, "
      + "#{row.lastTriggerTime}, #{row.triggerCount}, #{row.createdAt}, #{row.createdBy}, #{row.version}) "
      + "ON CONFLICT (rule_code) WHERE status = 'OPEN' DO NOTHING")
  int insertOpenIfAbsent(@Param("row") Alert row);

  @Update(
      "UPDATE eaio_platform.alert SET last_trigger_time=#{time}, metric_value=#{value}, "
          + "trigger_count=trigger_count+1, updated_at=#{time}, version=version+1 "
          + "WHERE rule_code=#{ruleCode} AND status <> 'RESOLVED'")
  int incrementOpen(
      @Param("ruleCode") String ruleCode,
      @Param("time") Instant time,
      @Param("value") BigDecimal value);

  @Update(
      "UPDATE eaio_platform.alert SET status='ACKED', ack_by=#{operator}, ack_time=#{time}, "
          + "updated_at=#{time}, updated_by=#{operator}, version=version+1 "
          + "WHERE id=#{id} AND status='OPEN'")
  int ack(@Param("id") long id, @Param("operator") long operator, @Param("time") Instant time);

  @Update(
      "UPDATE eaio_platform.alert SET status='RESOLVED', resolve_time=#{time}, "
          + "updated_at=#{time}, updated_by=#{operator}, version=version+1 "
          + "WHERE id=#{id} AND status <> 'RESOLVED'")
  int resolve(@Param("id") long id, @Param("operator") long operator, @Param("time") Instant time);

  @Update(
      "UPDATE eaio_platform.alert SET notice_id=#{noticeId}, updated_at=now(), version=version+1 "
          + "WHERE id=#{id}")
  int attachNotice(@Param("id") long id, @Param("noticeId") long noticeId);
}
