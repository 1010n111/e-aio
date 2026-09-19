/**
 * 领域事件：本模块发布、其他模块消费的事实（P1 册 3.9.1）。
 *
 * <p>全部为 {@code record}，前两个字段固定为 {@code eventId}（消费侧幂等键）与 {@code occurredAt}，
 * 载荷只放 ID 与标量（不放实体、不放跨模块 DTO）；P1 册 6.3 为此定了断言
 * {@code eventRecordsHaveEventIdFirst}，随第一个事件落地时补进 {@code ArchitectureTest}。
 *
 * <p>投递口径：与业务写入同事务登记（轻量发件箱），提交后同步投递给监听方；
 * 监听方抛异常按退避重试，超限转死信，死信可人工重放。
 */
package com.eaio.platform.events;
