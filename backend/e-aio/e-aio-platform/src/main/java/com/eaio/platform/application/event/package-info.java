/**
 * 事件可靠性的应用层（P1 册 3.9.2）：发件箱登记、提交后同步投递、重投扫描、死信与人工重放。
 *
 * <p>四件东西的分工是刻意的：
 * <ul>
 *   <li>{@code PlatformEventPublisher}：事务内登记（轻量 outbox）+ 请求提交后投递；</li>
 *   <li>{@code PlatformEventDispatcher}：提交回调/重投共用的唯一投递路径，负责把结果写回状态机；</li>
 *   <li>{@code EventRetryScanner}：内置任务 {@code platform.event.retry} 的处理点——重投到期行 +
 *       顺带清理超期 {@code DONE}；</li>
 *   <li>{@code EventDeliveryAppService}：管理面的列表/详情/人工重放（REST 与跨模块共用，裁决 2.4.3）。</li>
 * </ul>
 *
 * <p>退避公式不在这里重写：复用 {@code domain/job/JobRetryPolicy}（同一条
 * {@code backoff × 2^(attempt-1)}、同一 30 分钟封顶、同一溢出防护）。
 */
package com.eaio.platform.application.event;
