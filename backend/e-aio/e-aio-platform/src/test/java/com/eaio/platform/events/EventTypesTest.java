package com.eaio.platform.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * 事件类型注册表的完整性守卫（P1 册 3.9.1 的清单纪律）。
 *
 * <p>重投按 {@code event_delivery.event_type} 从 {@link EventTypes} 找类型；**新增事件忘记登记**的表现是
 * "首次投递成功、重投永远解不开 → 死信"——一种只有出故障时才会发现的静默降级。这里扫描
 * {@code com.eaio.platform.events} 包下的**已编译类**，逐个断言实现 {@link PlatformEvent} 的 record
 * 都已登记（扫的是本模块自己的 class 文件，不是数据库字符串——与生产路径的安全口径一致）。
 */
class EventTypesTest {

    @Test
    @DisplayName("events 包里每个实现 PlatformEvent 的 record 都已登记（新增事件漏登记即红）")
    void everyPlatformEventRecordIsRegistered() throws Exception {
        List<String> unregistered = new ArrayList<>();
        List<String> checked = new ArrayList<>();
        for (Resource resource : new PathMatchingResourcePatternResolver()
                .getResources("classpath*:com/eaio/platform/events/*.class")) {
            String filename = resource.getFilename();
            if (filename == null) {
                continue;
            }
            Class<?> type = Class.forName("com.eaio.platform.events."
                    + filename.substring(0, filename.length() - ".class".length()));
            if (!type.isRecord() || !PlatformEvent.class.isAssignableFrom(type)) {
                continue;
            }
            checked.add(type.getSimpleName());
            if (EventTypes.resolve(type.getName()).isEmpty()) {
                unregistered.add(type.getName());
            }
        }

        assertThat(checked).as("作用面非空：events 包下必须有平台事件 record（否则本测试永远绿）")
                .isNotEmpty();
        assertThat(unregistered).as("未登记进 EventTypes 的事件（重投会解不开 → 死信）").isEmpty();
    }

    @Test
    @DisplayName("已知的四个 P1 事件类型可直接解析；库里伪造的类型名解析不到（不按 DB 字符串加载类）")
    void resolvesKnownTypesOnly() {
        assertThat(EventTypes.resolve(ParamChangedEvent.class.getName())).contains(ParamChangedEvent.class);
        assertThat(EventTypes.resolve(DictChangedEvent.class.getName())).contains(DictChangedEvent.class);
        assertThat(EventTypes.resolve(JobFailedEvent.class.getName())).contains(JobFailedEvent.class);
        assertThat(EventTypes.resolve(EventDeadLetteredEvent.class.getName()))
                .contains(EventDeadLetteredEvent.class);
        assertThat(EventTypes.resolve("java.lang.Runtime")).isEmpty();
        assertThat(EventTypes.resolve("com.eaio.platform.events.NotAThing")).isEmpty();
        assertThat(EventTypes.resolve(null)).isEmpty();
    }
}
