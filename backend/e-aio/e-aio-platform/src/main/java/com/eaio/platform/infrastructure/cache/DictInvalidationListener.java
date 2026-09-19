package com.eaio.platform.infrastructure.cache;

import com.eaio.platform.application.dict.DictResolver;
import com.eaio.platform.events.DictChangedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 字典变更后的缓存失效（P1 册 3.2.3 的"三件事"：清本机 L1 + 删 L2 + 广播）。
 *
 * <p>用 {@link TransactionPhase#AFTER_COMMIT}：事务回滚了就不该清缓存（那会让别的实例把**没提交**的值
 * 当成新值去回源）。发布方在事务内注册事件、提交后回调，本监听器跑在发布方线程里（同步、无队列）。
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

    /** 提交后：清本机 L1 + 删 L2 + 广播（其他实例清它们的 L1）。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDictChanged(DictChangedEvent event) {
        resolver.invalidate(event.typeCode());
        publisher.publish(event.typeCode());
    }
}
