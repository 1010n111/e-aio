package com.eaio.app.web;

import java.util.Map;

import com.eaio.common.api.ErrorCode;
import com.eaio.common.api.Result;
import com.eaio.common.id.IdGenerator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台演示接口（P0 册 3.10 步骤 3/4）：证明"一条真实请求走完入站链路再以统一返回体出去"。
 *
 * <p><b>这不是业务接口</b>：P0 不做业务建模（platform 的错误码段 20000–20999 只登记不建空枚举）。
 * 它的价值是让入站链路的四件事——统一 POST 入口、traceId 回填、异常映射、幂等占位——有一个
 * 可被真实 HTTP 请求触发的对象；P1 platform 首个真实接口落地时删除本类。
 *
 * <p>它只用 common 的 **api 包**（{@link ErrorCode}/{@link Result}/{@link IdGenerator}）：
 * 引用 common 内部实现包会被 Modulith 判为越界（{@code ArchitectureTest#moduleBoundariesHold}），
 * 所以"演示接口"也不能图方便乱引。
 */
@RestController
@RequestMapping("/platform/demo")
public class DemoController {

    /** 演示数据载体（内联 record，不占用业务 DTO 的位置）。 */
    public record EchoRequest(@NotBlank String message) {
    }

    /**
     * 演示用业务失败。
     *
     * <p>刻意不复用 common 的 {@code BusinessException}：那属于 common 的内部实现包，
     * 应用壳引用它同样越界。P1 有真实业务模块后，异常类型由模块的 api 包提供。
     */
    public static class DemoBusinessException extends RuntimeException {

        public DemoBusinessException(String message) {
            super(message);
        }
    }

    /**
     * 回显：验证统一 POST + JSON、参数校验、响应回填与 traceId。
     */
    @PostMapping("/Echo")
    public Map<String, Object> echo(@Valid @RequestBody EchoRequest request) {
        // 直接返回业务数据：出站由 ApiResponseAdvice 统一包成 Result 并回填 traceId
        return Map.of(
                "message", request.message(),
                "id", new IdGenerator(0).nextStr(),
                "traceId", String.valueOf(MDC.get(TraceIdFilter.MDC_KEY)));
    }

    /** 抛业务异常：验证业务错误码原样返回、且不泄漏堆栈（号码取自通用段，演示用）。 */
    @PostMapping("/Fail")
    public void fail() {
        throw new DemoBusinessException("演示用业务失败");
    }

    /** 抛未预期异常：验证兜底映射为系统错误、对外无内部信息。 */
    @PostMapping("/Crash")
    public void crash() {
        throw new IllegalStateException("演示用内部异常：调用方不应看到这句话");
    }

    /** 参数校验失败：验证校验类异常映射。 */
    @PostMapping("/Validate")
    public void validate(@Valid @RequestBody EchoRequest request) {
        // 校验通过则什么都不做：本接口只用来触发校验分支
    }

    /** 供异常处理器取演示错误码。 */
    static ErrorCode demoErrorCode() {
        return ErrorCode.DATA_CONFLICT;
    }
}
