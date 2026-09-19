package com.eaio.platform.events;

import java.time.Instant;

/**
 * 平台事件（P1 册 3.9.1 的统一约定的类型化载体）：{@code eventId} 是消费侧幂等键，
 * {@code occurredAt} 是**业务发生时间**（不是投递时间——重投后投递时间会变，用它算时序会错）。
 *
 * <p><b>为什么有接口而不是只用 {@code Object}</b>：发件箱（{@code PlatformEventPublisher}）要拿
 * {@code eventId} 做投递记录的主键、{@code occurredAt} 做留痕，重投扫描器要拿 {@code eventId} 回写
 * 状态。没有接口就只能靠反射取 record 组件，或收 {@code Object} 后在运行期才发现类型不对。
 * 本接口的两个访问器与 3.9.1 的"前两个字段固定"是同一件事的两种表达——字段位次由
 * {@code ArchitectureTest#eventRecordsHaveEventIdFirst} 机械检查。
 *
 * <p><b>实现约定</b>：实现类是 {@code record}，且**前两个组件必须是 {@code eventId}/{@code occurredAt}**
 * （顺序有意义：反射断言按组件位次检查）；载荷只放 ID 与标量（5.5 的消费契约）。
 */
public interface PlatformEvent {

    /** 事件 ID（雪花 {@code nextStr()}）：消费侧幂等键，重试与人工重放都靠它去重。 */
    String eventId();

    /** 业务发生时间（不是投递时间）。 */
    Instant occurredAt();
}
