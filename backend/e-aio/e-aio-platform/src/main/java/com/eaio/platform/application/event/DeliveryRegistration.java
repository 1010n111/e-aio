package com.eaio.platform.application.event;

import com.eaio.platform.events.PlatformEvent;

/**
 * "提交后投递"的内部信封（不是平台事件、不进 {@code event_delivery}、不跨模块）。
 *
 * <p><b>为什么必须有这一层</b>：业务事务内只能**登记**，不能投递。如果在事务内直接
 * {@code publishEvent(事件本身)}，普通 {@code @EventListener}（5.5 的消费契约）会在当前线程**立刻**同步执行
 * ——那等于"在业务事务里投递"：业务回滚时监听方已经跑过了（违反 3.9.2 第 1 条"业务回滚 → 登记也不发出"），
 * 而且提交后投递器还会再投一次（同一次业务写投两遍）。
 *
 * <p>因此事务内发布的是这个信封，只有 {@link PlatformEventDispatcher} 的
 * {@code AFTER_COMMIT} 回调认它；真正的事件对象在**提交之后**由投递器发布给全部监听方
 * （重投路径也走同一个投递方法，所以两条路径的落库口径与消费体验完全一致）。
 *
 * <p>包级可见是刻意的：它只是一次调用的凭据，不是给别处引用的类型。
 *
 * @param event 本次登记的平台事件
 */
record DeliveryRegistration(PlatformEvent event) {
}
