package com.eaio.platform.events;

import java.time.Instant;

/**
 * 文件逻辑删除事件（P1 册 3.9.1 的事件清单第 6 行）。
 *
 * <p>发布时机：逻辑删除**提交后**；消费者：audit。物理清理由任务 {@code platform.file.orphan.clean} 负责
 * （软删超过 {@code platform.file.purge-days} 才删盘），本事件只表达"这一行不可再读了"。
 *
 * @param eventId    事件 ID（消费侧幂等键，必须是第一个组件）
 * @param occurredAt 发生时间（第二个组件，统一约定）
 * @param fileId     文件 ID
 * @param operatorId 操作者 ID（可空：无上下文时为 {@code null}）
 */
public record FileDeletedEvent(
        String eventId,
        Instant occurredAt,
        long fileId,
        Long operatorId) implements PlatformEvent {
}
