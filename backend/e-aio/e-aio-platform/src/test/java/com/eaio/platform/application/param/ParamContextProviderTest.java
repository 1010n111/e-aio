package com.eaio.platform.application.param;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import com.eaio.platform.api.port.OrgContextPort;
import com.eaio.platform.domain.param.ParamContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/** 上下文提供者的降级口径（P1 册 2.4.2：端口缺席 = 只看 SYSTEM 级，更严格的退化）。 */
class ParamContextProviderTest {

    private static ParamContextProvider provider(DefaultListableBeanFactory factory) {
        return new ParamContextProvider(factory.getBeanProvider(OrgContextPort.class));
    }

    @Test
    @DisplayName("端口缺席（iam 未交付）：退化为 SYSTEM-only，不抛异常")
    void absentPortDegradesToSystemOnly() {
        assertThat(provider(new DefaultListableBeanFactory()).current()).isEqualTo(ParamContext.systemOnly());
    }

    @Test
    @DisplayName("端口存在且有上下文：原样映射 orgId/userId")
    void portContextIsMapped() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("orgContextPort",
                (OrgContextPort) () -> Optional.of(new OrgContextPort.OrgContext(7L, 9L)));

        assertThat(provider(factory).current()).isEqualTo(new ParamContext(7L, 9L));
    }

    @Test
    @DisplayName("端口存在但无请求上下文（定时任务/预热）：同样退化为 SYSTEM-only")
    void emptyContextDegradesToSystemOnly() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("orgContextPort", (OrgContextPort) Optional::<OrgContextPort.OrgContext>empty);

        assertThat(provider(factory).current()).isEqualTo(ParamContext.systemOnly());
    }
}
