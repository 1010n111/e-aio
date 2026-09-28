package com.eaio.platform.application.file;

import static org.assertj.core.api.Assertions.assertThat;

import com.eaio.platform.api.dto.AuditRecord;
import com.eaio.platform.api.port.AuditPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 审计降级记录的测试（P1 册 3.3.6、2.4.2 的降级表；票面验收第 4 条）。
 *
 * <p>三条口径：端口缺席 → 结构化日志 + 计数；端口在 → 交给端口（不计数）；端口写失败 → ERROR + 计数
 * 且**不抛出**（留痕失败绝不回滚文件写入）。
 */
class AuditFallbackRecorderTest {

    private static final AuditRecord RECORD = new AuditRecord("DOWNLOAD", "FILE", 1L, null, null, 7L, 0L,
            "SUCCESS", 3L, "abc", "trace-1");

    @Test
    @DisplayName("端口缺席：降级计数 +1，logger 名逐字 com.eaio.platform.audit.fallback")
    void absentPortCountsFallback() {
        AuditFallbackRecorder recorder = new AuditFallbackRecorder();

        recorder.record(null, RECORD);
        recorder.record(null, RECORD);

        assertThat(recorder.fallbackCount()).as("platform.audit.fallback.count").isEqualTo(2L);
        assertThat(AuditFallbackRecorder.FALLBACK_LOGGER_NAME)
                .as("运维按这个名字建日志告警，逐字不能变").isEqualTo("com.eaio.platform.audit.fallback");
        assertThat(recorder.failureCount()).isZero();
    }

    @Test
    @DisplayName("端口在位：交给端口，不计数、不抛出")
    void presentPortTakesRecord() {
        AuditFallbackRecorder recorder = new AuditFallbackRecorder();
        final int[] taken = {0};

        recorder.record(record -> taken[0]++, RECORD);

        assertThat(taken[0]).isEqualTo(1);
        assertThat(recorder.fallbackCount()).isZero();
        assertThat(recorder.failureCount()).isZero();
    }

    @Test
    @DisplayName("端口写失败：ERROR + 失败计数 +1，异常不外抛（绝不回滚业务）")
    void portFailureDoesNotThrow() {
        AuditFallbackRecorder recorder = new AuditFallbackRecorder();
        AuditPort failing = record -> {
            throw new IllegalStateException("audit 库不可用");
        };

        recorder.record(failing, RECORD);

        assertThat(recorder.failureCount()).isEqualTo(1L);
        assertThat(recorder.fallbackCount()).as("端口在但不通不算降级：它是审计故障，不是降级").isZero();
    }
}
