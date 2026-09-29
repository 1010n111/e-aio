package com.eaio.platform.events;

import java.time.Instant;

/** 公告已发布事实。 */
public record NoticePublishedEvent(
        String eventId,
        Instant occurredAt,
        long noticeId,
        String scopeType,
        Long publisherId) implements PlatformEvent {
}
