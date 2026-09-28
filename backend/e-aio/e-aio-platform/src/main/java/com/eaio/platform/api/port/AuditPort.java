package com.eaio.platform.api.port;

import com.eaio.platform.api.dto.AuditRecord;

/**
 * 审计端口（platform 自有端口，P1 册 2.4.2 / 3.3.6，ADR-0005）。
 *
 * <p><b>为什么是端口而不是依赖 audit 模块</b>：platform 编译期对 audit 零依赖（连 {@code audit.api} 都不依赖），
 * 而"文件的上传/下载/删除/绑定要留痕"是平台的义务。做法与 {@link OrgContextPort} 同款：platform 声明接口，
 * audit 侧实现，应用壳装配。
 *
 * <p><b>缺席（audit 未交付）时的降级是"显式降级"，不是静默丢弃</b>：{@code AuditPort} 缺席时，
 * 每一条记录都要落结构化日志（logger 名逐字 {@code com.eaio.platform.audit.fallback}，字段与
 * {@link AuditRecord} 一一对应）并递增 {@code platform.audit.fallback.count} 计数——"谁下载了不该下载的
 * 文件"必须能在降级期也查得到（3.3.6 明文：拒绝下载也要留痕）。
 *
 * <p><b>实现约定</b>：
 * <ol>
 *   <li>实现由 audit 模块提供并声明为 Bean，**不得落在 {@code com.eaio.platform..} 内**；</li>
 *   <li>{@link #record(AuditRecord)} 抛异常 = 这条留痕没写成，由调用方记 ERROR 并计数；
 *       **调用方不得因此回滚文件写入**（3.3.6：P1 没有强审计场景，磁盘上的文件与审计的一致性让位于可用性）。</li>
 * </ol>
 */
public interface AuditPort {

    /** 记录一条审计事实；实现方负责落 WORM/库表。抛异常表示留痕失败（调用方按降级处理）。 */
    void record(AuditRecord record);
}
