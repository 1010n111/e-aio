package com.eaio.platform.api.dto;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 手动触发入参（P1 册 5.3 的 {@code JobTriggerCmd}）：{@code /platform/job/Run}。
 *
 * <p>Java 面 {@code SchedulerApi.trigger(jobCode, params)} 不额外包装 DTO（5.3 明写）；本记录只服务于
 * HTTP 入参绑定。
 *
 * @param jobCode 任务编码（必填）
 * @param params  本次执行参数；空则用 {@code job.params_json} 里保存的参数
 */
public record JobTriggerCmd(
        @NotBlank @Size(max = 64) String jobCode,
        Map<String, String> params) {
}
