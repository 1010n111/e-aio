package com.eaio.platform.application.param;

import java.util.concurrent.atomic.AtomicBoolean;

import com.eaio.platform.api.port.OrgContextPort;
import com.eaio.platform.domain.param.ParamContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 当前请求的组织/用户上下文（P1 册 2.4.2、3.1.3）。
 *
 * <p>只用构造器注入 {@link ObjectProvider}（P1-C4：可选依赖**禁** {@code @Autowired(required = false)}
 * 字段注入）：iam 未交付时端口缺席是**预期状态**，不是错误。
 *
 * <p>缺席时的降级：恒返回 {@link ParamContext#systemOnly()} —— 参数解析只看 SYSTEM 级。这是**更严格的
 * 退化**（组织级/用户级覆盖不生效），不是"看到全部"（P1 册 3.8 不得静默降级为放行）。降级只告警一次
 * （含 traceId），避免每读一个参数刷一行日志。
 */
@Component
public class ParamContextProvider {

    private static final Logger log = LoggerFactory.getLogger(ParamContextProvider.class);

    private final ObjectProvider<OrgContextPort> orgContextPort;
    private final AtomicBoolean degradationWarned = new AtomicBoolean();

    public ParamContextProvider(ObjectProvider<OrgContextPort> orgContextPort) {
        this.orgContextPort = orgContextPort;
    }

    /** 当前上下文；无端口或无请求上下文时退化为 SYSTEM-only。 */
    public ParamContext current() {
        OrgContextPort port = orgContextPort.getIfAvailable();
        if (port == null) {
            warnDegradation();
            return ParamContext.systemOnly();
        }
        return port.current()
                .map(context -> new ParamContext(context.orgId(), context.userId()))
                .orElseGet(ParamContext::systemOnly);
    }

    private void warnDegradation() {
        if (degradationWarned.compareAndSet(false, true)) {
            log.warn("未装配 OrgContextPort（iam 尚未交付）：参数解析退化为只看 SYSTEM 级，"
                    + "组织级/用户级覆盖不生效；traceId={}", MDC.get("traceId"));
        }
    }
}
