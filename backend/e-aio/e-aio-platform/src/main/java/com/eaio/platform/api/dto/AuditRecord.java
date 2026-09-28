package com.eaio.platform.api.dto;

/**
 * 审计记录（P1 册 2.4.2 的降级表与 3.3.6 的留痕字段）。
 *
 * <p><b>为什么在 platform 侧定义</b>：{@code AuditPort} 是 platform 的**自有端口**（audit 模块反向实现并
 * 由应用壳注入适配器），端口上的数据类型也归 platform——反过来依赖 {@code audit.api} 会让 platform 对一个
 * 尚未交付的模块产生编译期依赖（ADR-0005 零反向依赖）。
 *
 * <p>{@code action} 是平台内的动作词（{@code UPLOAD}/{@code DOWNLOAD}/{@code DOWNLOAD_DENIED}/
 * {@code DELETE}/{@code BIND}），不是 audit 的枚举：audit 侧若有自己的动作字典，在适配器里映射。
 *
 * @param action     动作（平台动作词，见上）
 * @param resource   资源类型（本票恒为 {@code FILE}）
 * @param fileId     文件 ID
 * @param bizType    业务类型后缀（可空；未绑定时为空）
 * @param bizId      业务对象 ID（可空）
 * @param operatorId 操作者 ID（可空；无上下文时为 {@code null}，**不伪造 0 号用户**）
 * @param orgId      操作者组织 ID（可空，同上）
 * @param result     结果（{@code SUCCESS}/{@code DENIED}/{@code FAILED}）
 * @param sizeBytes  文件字节数（下载/上传留痕用；未知为 {@code null}）
 * @param sha256     文件摘要（上传/删除留痕用；未知为 {@code null}）
 * @param traceId    链路 ID（从日志上下文取，便于与请求日志对齐）
 */
public record AuditRecord(
        String action,
        String resource,
        Long fileId,
        String bizType,
        Long bizId,
        Long operatorId,
        Long orgId,
        String result,
        Long sizeBytes,
        String sha256,
        String traceId) {
}
