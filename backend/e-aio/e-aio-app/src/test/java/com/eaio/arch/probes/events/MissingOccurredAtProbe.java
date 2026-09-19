package com.eaio.arch.probes.events;

/**
 * 控制组探针：事件 record 的**第二个**组件不是 {@code occurredAt}（P1 册 3.9.1 要求前两个组件为
 * {@code eventId}/{@code occurredAt}；{@code ArchitectureTest#eventRecordsHaveEventIdFirst} 必须抓到它）。
 *
 * <p>第一个组件是对的，专门用来证明"只查第一位"的老断言不够——第二轮检查也有牙。
 */
public record MissingOccurredAtProbe(String eventId, String paramKey) {
}
