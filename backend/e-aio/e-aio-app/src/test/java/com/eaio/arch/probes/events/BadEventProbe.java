package com.eaio.arch.probes.events;

/**
 * 控制组探针：事件 record 的第一个组件不是 {@code eventId}（P1 册 6.3 的
 * {@code eventRecordsHaveEventIdFirst} 必须抓到它）。
 *
 * <p>包名含 {@code .events} 是刻意的：规则按"事件包里的 record"识别事件，探针必须落在规则的作用面内，
 * 否则"抓到违规"靠的是空作用面而不是规则本身（P0 曾出现过这类假牙齿）。
 */
public record BadEventProbe(String paramKey, String changeType) {
}
