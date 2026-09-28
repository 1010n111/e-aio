package com.eaio.platform.events;

import java.time.Instant;

/**
 * 文件上传事件（P1 册 3.9.1 的事件清单第 5 行）。
 *
 * <p>发布时机：上传（或分片合并）**事务提交后**；消费者：audit（落 WORM）、业务模块（如 CRM 客户附件索引）。
 *
 * <p>载荷只放 ID 与标量（5.5 的消费契约）：不放文件内容、不放存储路径——存储路径是存储适配器的实现细节，
 * 出了模块只会变成绕过权限读盘的口子。
 *
 * @param eventId       事件 ID（消费侧幂等键，必须是第一个组件）
 * @param occurredAt    发生时间（第二个组件，统一约定）
 * @param fileId        文件 ID
 * @param bizType       业务类型后缀（可空：上传时未绑定则为空）
 * @param bizId         业务对象 ID（可空）
 * @param sizeBytes     字节数
 * @param sha256        服务端重算的摘要
 * @param uploaderId    上传者 ID
 * @param uploaderOrgId 上传者组织 ID（{@code 0} = 无组织上下文）
 */
public record FileUploadedEvent(
        String eventId,
        Instant occurredAt,
        long fileId,
        String bizType,
        Long bizId,
        long sizeBytes,
        String sha256,
        Long uploaderId,
        long uploaderOrgId) implements PlatformEvent {
}
