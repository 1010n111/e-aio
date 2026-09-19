package com.eaio.platform.events;

/**
 * 内部事实事件（平台自身在投递回调里发布的"结果性事实"）：**不进发件箱**，因此没有
 * {@code event_delivery} 登记行，投递失败也没有重试语义（只记 ERROR）。
 *
 * <p>为什么需要这个标记（而不是让所有 {@link PlatformEvent} 一视同仁）：发件箱服务于"业务事务里产生、
 * 必须可靠送达"的事件；死信事实是本就在投递回调里产生的**结果**——把它也登记一次会造出
 * "死信的死信"链条（每一个死信事实如果投递失败又变成一条死信），而它的价值恰恰是"尽快告诉告警能力"。
 * 投递方（{@code PlatformEventDispatcher}）据此把"没有登记行"从 WARN 噪声降为正常路径。
 *
 * <p>实现类是 {@code record}，且前两个组件仍是 {@code eventId}/{@code occurredAt}
 * （{@code ArchitectureTest#eventRecordsHaveEventIdFirst} 对事件包全部 record 生效，没有例外）。
 */
public interface InternalFactEvent extends PlatformEvent {
}
