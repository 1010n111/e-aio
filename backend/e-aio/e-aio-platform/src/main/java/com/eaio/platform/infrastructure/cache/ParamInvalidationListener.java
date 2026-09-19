package com.eaio.platform.infrastructure.cache;

import com.eaio.platform.application.param.ParamResolver;
import com.eaio.platform.events.ParamChangedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 参数变更后的缓存失效（P1 册 3.1.4 的 {@code InvalidationListener}）。
 *
 * <p>用 {@link TransactionPhase#AFTER_COMMIT}：事务回滚了就不该清缓存（那会让别的实例把**没提交**的值
 * 当成新值去回源）。发布方在事务内注册事件、提交后回调，本监听器跑在发布方线程里（同步、无队列）。
 *
 * <p>跨实例广播（Redis Pub/Sub {@code platform:ch:invalidation}，3.1.4 三件事里的第三件）本票**未做**：
 * 它需要订阅容器与广播载荷格式，属 T6（缓存收口）/T8（事件可靠性）的范围；缺它时的不一致窗口 =
 * 其他实例的 L1 TTL（60s，即 3.1.5 登记的代价），不是"永远不一致"。这里不写占位代码，避免看起来已广播。
 */
@Component
public class ParamInvalidationListener {

    private final ParamResolver resolver;

    public ParamInvalidationListener(ParamResolver resolver) {
        this.resolver = resolver;
    }

    /** 提交后：清本机 L1（该键全部上下文变体）+ 删 L2（全部组织变体）。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onParamChanged(ParamChangedEvent event) {
        resolver.invalidate(event.paramKey());
    }
}
