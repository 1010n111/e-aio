package com.eaio.platform.domain.event;

import java.util.Locale;

/**
 * 事件投递状态（P1 册 4.3.14 的 {@code ck_event_delivery_status}；落库 {@code VARCHAR(32)} + CHECK，
 * 不用 PG ENUM）。
 *
 * <pre>
 *   RETRYING ──提交后同步投递全部监听器成功──→ DONE（终态，保留 7 天后清理）
 *      │  ──任一监听器抛异常且 attempt + 1 ≤ max_attempt──→ RETRYING（attempt+1 + 退避到期时刻）
 *      └─ ──任一监听器抛异常且 attempt + 1 &gt; max_attempt──→ DEAD（终态，人工重放才能回到 RETRYING）
 * </pre>
 *
 * <p>三态刻意只有三个（3.9.2 的 DDL 冻结）：没有 {@code SENDING}/{@code FAILED} 这类中间态——
 * 投递是**同步**的，没有"正在投递"这个可观测状态；而"失败"不是终态，它就是 {@code RETRYING}
 * （还有下一次）或 {@code DEAD}（没有下一次）之一。
 */
public enum EventDeliveryStatus {

    /** 已登记，待投递或待重投（{@code next_retry_time} 为空 = 首次投递还没失败过）。 */
    RETRYING,

    /** 全部监听器成功（终态）。 */
    DONE,

    /** 超过 {@code max_attempt}（死信，终态；不再被扫描器捞出，保留至人工处理）。 */
    DEAD;

    /** 是否终态（终态不会被自动流程改写；{@code DEAD} 只能由人工重放改回 {@code RETRYING}）。 */
    public boolean isTerminal() {
        return this == DONE || this == DEAD;
    }

    /** 名字是否合法；非法名在写路径抛 10000（参数非法），不进 CHECK 约束。 */
    public static boolean isValid(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.trim().toUpperCase(Locale.ROOT);
        for (EventDeliveryStatus status : values()) {
            if (status.name().equals(normalized)) {
                return true;
            }
        }
        return false;
    }
}
