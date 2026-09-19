package com.eaio.platform.events;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 事件类型注册表（代码注册的**唯一可反序列化集合**）：{@code event_delivery.event_type} 是数据库里的
 * 一个字符串，重投时按它决定"用哪个 record 解 {@code payload_json}"。
 *
 * <p><b>为什么不用 {@code Class.forName(event_type)}</b>：那是"DB 字符串 → 代码路径"的反射入口，
 * 与 3.4.1 明确否决的"按 DB 字符串反射调用任意 Bean 方法"（{@code job.handler_code} 的 RCE 面）
 * 是同一类做法——能写 {@code event_delivery} 表就能让进程加载任意类并触发它的静态初始化。
 * 可执行的类型只能来自**代码里登记**的这张表；未登记的类型在重投时记 ERROR 并直接置死信
 * （不尝试加载、不无限重试，3.9.3）。
 *
 * <p><b>新增事件要同步登记这里</b>（漏登记的表现是"首次投递成功、重投永远解不开 → 死信"）。
 * 这条纪律由 {@code EventTypesTest} 机械检查：扫描 {@code com.eaio.platform.events} 包里所有实现
 * {@link PlatformEvent} 的 record，逐个断言已登记。
 *
 * <p>键是 {@code Class#getName()}（全限定类名），登记值为类型本身 —— 写入 {@code event_type} 的
 * 就是它（{@code PlatformEventPublisher}），两侧共用同一个来源，不存在"写一套名字、读另一套名字"。
 */
public final class EventTypes {

    private static final Map<String, Class<? extends PlatformEvent>> REGISTERED = registered();

    private EventTypes() {
    }

    /** 按 {@code event_type} 取事件类型；未登记返回空（调用方按"解不开"处理：ERROR + 死信）。 */
    public static Optional<Class<? extends PlatformEvent>> resolve(String eventType) {
        return eventType == null ? Optional.empty() : Optional.ofNullable(REGISTERED.get(eventType.trim()));
    }

    /** 已登记的类型名（诊断/测试用）。 */
    public static Set<String> registeredNames() {
        return REGISTERED.keySet();
    }

    private static Map<String, Class<? extends PlatformEvent>> registered() {
        Map<String, Class<? extends PlatformEvent>> types = new LinkedHashMap<>();
        // P1 册 3.9.1 的事件清单：目前只在 platform 内发布/消费的前四个（其余能力的票落地时追加）
        types.put(ParamChangedEvent.class.getName(), ParamChangedEvent.class);
        types.put(DictChangedEvent.class.getName(), DictChangedEvent.class);
        types.put(JobFailedEvent.class.getName(), JobFailedEvent.class);
        // 死信事实本身不进发件箱（见 InternalFactEvent），登记它只是为了"事件清单完整"
        types.put(EventDeadLetteredEvent.class.getName(), EventDeadLetteredEvent.class);
        return Map.copyOf(types);
    }
}
