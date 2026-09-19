package com.eaio.platform.infrastructure.cache;

import com.eaio.platform.application.dict.DictResolver;
import com.eaio.platform.events.DictChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 字典变更后的缓存失效（P1 册 3.2.3 的"三件事"：清本机 L1 + 删 L2 + 广播）。
 *
 * <p><b>用普通 {@link EventListener}（T8 起）</b>：5.5 的消费契约把"提交后才投递"落在发布方——
 * {@code PlatformEventDispatcher} 在 {@code AFTER_COMMIT} 回调里同步调用监听器。监听器自己再挂
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 时，**重投与人工重放路径没有事务**，投递器会把行
 * 写成 {@code DONE} 而缓存失效被静默跳过（"状态成功、语义没执行"）。语义没有变化：事务回滚时投递器
 * 根本不投递，"回滚了就不清缓存"仍成立，只是保证点从监听器换到了投递器。
 *
 * <p>{@code refresh} 刻意不广播（与 T3 的 {@code ParamResolver.refresh} 同款口径）：刷新是"这台机器的
 * 缓存脏了"的兜底，不是"值变了"；其他实例的 L1 仍由 60s TTL 收敛。
 */
@Component
public class DictInvalidationListener {

    private final DictResolver resolver;
    private final DictInvalidationPublisher publisher;

    public DictInvalidationListener(DictResolver resolver, DictInvalidationPublisher publisher) {
        this.resolver = resolver;
        this.publisher = publisher;
    }

    /** 提交后（由 dispatcher 投递）：清本机 L1 + 删 L2 + 广播（其他实例清它们的 L1）。 */
    @EventListener
    public void onDictChanged(DictChangedEvent event) {
        resolver.invalidate(event.typeCode());
        publisher.publish(event.typeCode());
    }
}
