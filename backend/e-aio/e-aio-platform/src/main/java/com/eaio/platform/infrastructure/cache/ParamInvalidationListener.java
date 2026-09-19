package com.eaio.platform.infrastructure.cache;

import com.eaio.platform.application.param.ParamResolver;
import com.eaio.platform.events.ParamChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 参数变更后的缓存失效（P1 册 3.1.4 的 {@code InvalidationListener}）：3.1.4 的"三件事"都在这里发生——
 * 清本机 L1（该键全部上下文变体）、删 L2（全部组织变体）、广播给其他实例。
 *
 * <p><b>用普通 {@link EventListener}（T8 起）</b>：5.5 的消费契约把"提交后才投递"落在发布方——
 * {@code PlatformEventDispatcher} 在 {@code AFTER_COMMIT} 回调里同步调用监听器。监听器自己再挂
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 会有两个坏结果：
 * <ol>
 *   <li>重投路径（{@code platform.event.retry} 扫描器）**没有事务**，AFTER_COMMIT 的监听器被静默跳过
 *       ——投递记录被写成 {@code DONE}，缓存却没清，是最坏的一类"状态显示成功、语义没执行"；</li>
 *   <li>人工重放同理。</li>
 * </ol>
 * 语义没有变化：事务回滚时 dispatcher 根本不会投递（{@code AFTER_COMMIT} 不触发），因此"回滚了就不清
 * 缓存"这条性质仍然成立，只是保证它的地方从监听器换到了投递器。
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

    /** 提交后（由 dispatcher 投递）：清本机 L1 + 删 L2（全部组织变体）+ 广播（其他实例清它们的 L1）。 */
    @EventListener
    public void onParamChanged(ParamChangedEvent event) {
        resolver.invalidate(event.paramKey());
        publisher.publish(event.paramKey());
    }
}
