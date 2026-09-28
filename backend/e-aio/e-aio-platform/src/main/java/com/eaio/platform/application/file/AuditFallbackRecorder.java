package com.eaio.platform.application.file;

import java.util.concurrent.atomic.AtomicLong;

import com.eaio.platform.api.dto.AuditRecord;
import com.eaio.platform.api.port.AuditPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 审计降级（P1 册 3.3.6、2.4.2 的降级表）。
 *
 * <p><b>审计端口缺席不等于不记审计</b>：audit 模块尚未交付时，每条记录都落结构化日志（logger 名逐字
 * {@code com.eaio.platform.audit.fallback}，字段与 {@link AuditRecord} 一一对应）并递增
 * {@code platform.audit.fallback.count}。这样"谁下载了不该下载的文件"在降级期也查得到——静默丢弃等于
 * 把一次安全事件变成日志空白（3.3.6 明文要求"拒绝下载也要留痕"）。
 *
 * <p>计数用进程内 {@link AtomicLong}：P1 还没有 {@code MonitorApi}（它随监测票交付，届时由它暴露
 * {@code platform.audit.fallback.count}）。这里是**唯一**记录该计数的地方，未来接入监测时只加读出口，
 * 不改写入口。
 *
 * <p><b>留痕失败绝不回滚业务</b>：{@code AuditPort} 实现抛异常时只记 ERROR 并计入 {@code failures}
 * ——文件已经落盘/已删，让"审计没写成"把用户的文件操作回滚掉是更坏的结果（3.3.6 的取舍）。
 */
@Component
public class AuditFallbackRecorder {

    /** 降级日志的 logger 名（3.3.6 逐字；运维按它建日志告警）。 */
    public static final String FALLBACK_LOGGER_NAME = "com.eaio.platform.audit.fallback";

    private static final Logger fallbackLog = LoggerFactory.getLogger(FALLBACK_LOGGER_NAME);
    private static final Logger log = LoggerFactory.getLogger(AuditFallbackRecorder.class);

    private final AtomicLong fallbackCount = new AtomicLong();
    private final AtomicLong failureCount = new AtomicLong();

    /**
     * 记录一条审计：端口在就交给它，缺席就降级；两边都不抛异常。
     *
     * @param port   审计端口（可空 = audit 未交付）
     * @param record 审计事实
     */
    public void record(AuditPort port, AuditRecord record) {
        if (port == null) {
            fallback(record);
            return;
        }
        try {
            port.record(record);
        } catch (RuntimeException e) {
            // 端口在了却写失败：这是"审计退化"而不是"业务失败"，ERROR 让运维看到，计数让指标看到
            failureCount.incrementAndGet();
            log.error("审计端口写入失败（业务不回滚，已计数 platform.audit.fallback.failures）：action={} fileId={}",
                    record.action(), record.fileId(), e);
        }
    }

    /** 降级计数（{@code platform.audit.fallback.count}）。 */
    public long fallbackCount() {
        return fallbackCount.get();
    }

    /** 端口存在但写入失败的次数（诊断用，7.1 没有专用码，不对外暴露）。 */
    public long failureCount() {
        return failureCount.get();
    }

    private void fallback(AuditRecord record) {
        long count = fallbackCount.incrementAndGet();
        fallbackLog.warn("审计降级（未装配 AuditPort）：action={} resource={} fileId={} bizType={} bizId={} "
                        + "operatorId={} orgId={} result={} sizeBytes={} sha256={} traceId={} fallbackCount={}",
                record.action(), record.resource(), record.fileId(), record.bizType(), record.bizId(),
                record.operatorId(), record.orgId(), record.result(), record.sizeBytes(), record.sha256(),
                record.traceId(), count);
    }
}
