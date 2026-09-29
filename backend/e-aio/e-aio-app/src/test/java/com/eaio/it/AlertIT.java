package com.eaio.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.eaio.common.exception.BusinessException;
import com.eaio.platform.api.PlatformErrorCode;
import com.eaio.platform.api.dto.AlertDTO;
import com.eaio.platform.api.dto.AlertQuery;
import com.eaio.platform.api.dto.AlertRuleDTO;
import com.eaio.platform.api.dto.AlertRuleQuery;
import com.eaio.platform.api.dto.AlertRuleSaveCmd;
import com.eaio.platform.api.port.OrgContextPort;
import com.eaio.platform.application.monitor.AlertAppService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/** #24：持续时长、抑制、三态告警、站内公告和环境隔离键。 */
@SpringBootTest
class AlertIT extends IntegrationTestBase {

    private static final long ORG_ID = 9500L;
    private static final long USER_ID = 9501L;

    @TestConfiguration
    static class OrgContextStub {
        static final AtomicReference<OrgContextPort.OrgContext> CURRENT = new AtomicReference<>();

        @Bean
        OrgContextPort orgContextPort() {
            return () -> Optional.ofNullable(CURRENT.get());
        }
    }

    @Autowired
    private AlertAppService alerts;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void signIn() {
        OrgContextStub.CURRENT.set(new OrgContextPort.OrgContext(ORG_ID, USER_ID));
    }

    @Test
    @DisplayName("持续时长未到不告警，到时触发且抑制窗口只累加；公告、确认、解决均落库")
    void durationSilenceAndLifecycle() {
        String code = "it.alert." + UUID.randomUUID();
        AlertRuleDTO rule = alerts.addRule(new AlertRuleSaveCmd(code, "IT 告警", "platform.job.failure", "GT",
                BigDecimal.valueOf(-1), 30, "WARN", 600, true, true, "it", null));
        alerts.evaluate();
        assertThat(alerts.pageAlerts(new AlertQuery(code, null, null, 1, 20, null, null)).getRecords()).isEmpty();
        assertThat(redis.hasKey("eaio:it:platform:alert:pending:" + code)).isTrue();

        jdbc.update("update eaio_platform.alert_rule set duration_seconds = 0 where id = ?", rule.id());
        alerts.evaluate();
        AlertDTO first = only(code);
        assertThat(first.noticeId()).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from eaio_platform.notice where id = ?", Integer.class,
                first.noticeId())).isEqualTo(1);
        alerts.evaluate();
        AlertDTO suppressed = only(code);
        assertThat(suppressed.id()).isEqualTo(first.id());
        assertThat(suppressed.triggerCount()).isEqualTo(2);

        AlertDTO acked = alerts.ack(first.id());
        assertThat(acked.status()).isEqualTo("ACKED");
        assertThat(acked.ackBy()).isEqualTo(USER_ID);
        AlertDTO resolved = alerts.resolve(first.id());
        assertThat(resolved.status()).isEqualTo("RESOLVED");
        assertThat(resolved.resolveTime()).isNotNull();
    }

    @Test
    @DisplayName("内置规则不可删除；不匹配规则不产生告警；健康与指标快照可读取")
    void builtinRuleAndMetrics() {
        AlertRuleDTO builtin = alerts.pageRules(new AlertRuleQuery("job.failure", null, null, 1, 20, null, null))
                .getRecords().stream().findFirst().orElseThrow();
        assertThat(builtin.builtin()).isTrue();
        assertThatThrownBy(() -> alerts.deleteRule(builtin.ruleCode(), builtin.version()))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(PlatformErrorCode.ALERT_RULE_INVALID.getCode()));

        String quiet = "it.alert.quiet." + UUID.randomUUID();
        alerts.addRule(new AlertRuleSaveCmd(quiet, "不匹配", "platform.job.failure", "GT",
                BigDecimal.valueOf(100_000_000_000L), 0, "INFO", 30, false, true, null, null));
        alerts.evaluate();
        assertThat(alerts.pageAlerts(new AlertQuery(quiet, null, null, 1, 20, null, null)).getRecords()).isEmpty();
        assertThat(alerts.health()).containsEntry("database", "UP").containsEntry("redis", "UP");
        assertThat(alerts.metrics().capturedAt()).isNotNull();
    }

    private AlertDTO only(String code) {
        return alerts.pageAlerts(new AlertQuery(code, null, null, 1, 20, null, null)).getRecords().stream()
                .findFirst().orElseThrow();
    }
}
