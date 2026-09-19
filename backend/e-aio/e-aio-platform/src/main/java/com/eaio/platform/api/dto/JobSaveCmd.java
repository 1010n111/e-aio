package com.eaio.platform.api.dto;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 任务写入命令（P1 册 5.3 的 {@code JobSaveCmd}）：管理页的 {@code Add}/{@code Up} 共用。
 *
 * <p>{@code jobCode} 是行身份，创建后不可改（3.4.6 的取舍：改编码 = 删了重建）。
 *
 * @param jobCode         任务编码（必填，≤64）
 * @param jobName         任务名称（必填，≤128）
 * @param handlerCode     处理点编码（必填，≤128；未注册抛 20023）
 * @param cron            Spring 6 段 cron（必填，≤64；非法抛 20022）
 * @param params          任务参数（键名含 password/secret/token/key 时保存拒绝，4.7）
 * @param enabled         是否启用；空则 Add 取 true、Up 保持原状态
 * @param timeoutSeconds  超时秒数；空则取 300
 * @param retryMax        重投上限（0–10）；空则取 3
 * @param backoffSeconds  退避基数秒；空则取 30
 * @param allowConcurrent 是否允许并发；空则取 false
 * @param remark          备注（≤255）
 * @param version         乐观锁版本（Add 可空，Up 必填）
 */
public record JobSaveCmd(
        @NotBlank @Size(max = 64) String jobCode,
        @NotBlank @Size(max = 128) String jobName,
        @NotBlank @Size(max = 128) String handlerCode,
        @NotBlank @Size(max = 64) String cron,
        Map<String, String> params,
        Boolean enabled,
        Integer timeoutSeconds,
        Integer retryMax,
        Integer backoffSeconds,
        Boolean allowConcurrent,
        @Size(max = 255) String remark,
        Integer version) {
}
