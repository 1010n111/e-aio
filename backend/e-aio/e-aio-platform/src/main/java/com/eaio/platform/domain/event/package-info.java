/**
 * 事件投递领域（P1 册 3.9）：投递登记行的状态机与载荷快照口径。
 *
 * <p>本包只有实体与枚举，没有策略类：退避公式复用 {@code domain/job/JobRetryPolicy}
 * （同一 {@code backoff × 2^(attempt-1)} + 同一 30 分钟封顶 + 同一溢出防护，见《实现注记（T8）》），
 * 事件侧的差异只有"上限语义"（{@code max_attempt} = 最大投递次数，不是"可重试次数"），
 * 那一条判定写在 {@code PlatformEventDispatcher} 里，不值得再包一层类。
 */
package com.eaio.platform.domain.event;
