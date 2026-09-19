package com.eaio.platform.application.event;

import com.eaio.platform.application.param.ParamResolver;
import com.eaio.platform.domain.param.ParamContext;
import org.springframework.stereotype.Component;

/**
 * 事件可靠性相关参数的读取口径（P1 册 7.2 的三个 {@code platform.event.*} 键）。
 *
 * <p>与 {@code JobParams} 同一先例：键字符串与默认值一处声明，避免"某个类里把键名抄错一位、于是永远
 * 只拿默认值"这类静默故障（参数中心里写错一位就是另一个键，解析不到也不会报错）。
 *
 * <p>读取上下文用 {@link ParamContext#systemOnly()}：投递登记发生在业务请求里，但重投扫描发生在
 * 调度线程上（没有用户与组织），三个键都是 SYSTEM 级语义（3.1.3）——统一只看 SYSTEM 级，
 * 不放行 ORG/USER 覆盖。
 */
@Component
public class EventParams {

    /** 最大投递尝试次数（7.2：默认 5；超过即死信）。 */
    public static final String RETRY_MAX = "platform.event.retry-max";

    /** 重试退避基数秒（7.2：默认 30；指数退避，封顶 30min）。 */
    public static final String RETRY_BACKOFF_SECONDS = "platform.event.retry-backoff-seconds";

    /** {@code DONE} 记录保留天数（7.2：默认 7；{@code DEAD} 不自动清理）。 */
    public static final String DELIVERY_RETAIN_DAYS = "platform.event.delivery-retain-days";

    private static final int DEFAULT_RETRY_MAX = 5;
    private static final int DEFAULT_BACKOFF_SECONDS = 30;
    private static final int DEFAULT_RETAIN_DAYS = 7;

    private final ParamResolver params;

    public EventParams(ParamResolver params) {
        this.params = params;
    }

    /** 最大投递尝试次数；非法值（非数字/≤0）回落默认值（参数中心读的是人填的字符串）。 */
    public int retryMax() {
        return intValue(RETRY_MAX, DEFAULT_RETRY_MAX);
    }

    /** 退避基数秒；非法值回落默认值（0 会让"退避"退化成"下一轮扫描立刻重投"）。 */
    public int backoffSeconds() {
        return intValue(RETRY_BACKOFF_SECONDS, DEFAULT_BACKOFF_SECONDS);
    }

    /** {@code DONE} 保留天数；非法值回落默认值。 */
    public int retainDays() {
        return intValue(DELIVERY_RETAIN_DAYS, DEFAULT_RETAIN_DAYS);
    }

    private int intValue(String key, int defaultValue) {
        String raw = params.resolveOrDefault(key, String.valueOf(defaultValue), ParamContext.systemOnly()).value();
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
