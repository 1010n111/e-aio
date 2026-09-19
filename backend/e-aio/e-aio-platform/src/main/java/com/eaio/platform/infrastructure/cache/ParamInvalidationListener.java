package com.eaio.platform.infrastructure.cache;

import com.eaio.platform.application.param.ParamResolver;
import com.eaio.platform.events.ParamChangedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 参数变更后的缓存失效（P1 册 3.1.4 的 {@code InvalidationListener}）：3.1.4 的"三件事"都在这里发生——
 * 清本机 L1（该键全部上下文变体）、删 L2（全部组织变体）、广播给其他实例。
 *
 * <p>用 {@link TransactionPhase#AFTER_COMMIT}：事务回滚了就不该清缓存（那会让别的实例把**没提交**的值
 * 当成新值去回源）。发布方在事务内注册事件、提交后回调，本监听器跑在发布方线程里（同步、无队列）。
 *
 * <p><b>{@code refresh} 刻意不广播</b>（3.1.4 只把广播挂在改值事件上）：刷新是"绕过接口改库后的本机兜底"，
 * 其他实例的 L1 仍由 60s TTL 收敛。要跨实例立刻一致，走改值路径——它的语义是"值变了"，而刷新的语义是
 * "我这台机器的缓存脏了"，两者不是一回事。
 */
@Component
public class ParamInvalidationListener {

    private final ParamResolver resolver;
    private final ParamInvalidationPublisher publisher;

    public ParamInvalidationListener(ParamResolver resolver, ParamInvalidationPublisher publisher) {
        this.resolver = resolver;
        this.publisher = publisher;
    }

    /** 提交后：清本机 L1 + 删 L2（全部组织变体）+ 广播（其他实例清它们的 L1）。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onParamChanged(ParamChangedEvent event) {
        resolver.invalidate(event.paramKey());
        publisher.publish(event.paramKey());
    }
}
