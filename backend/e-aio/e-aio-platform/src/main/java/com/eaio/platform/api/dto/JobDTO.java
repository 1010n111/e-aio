package com.eaio.platform.api.dto;

import java.time.Instant;
import java.util.Map;

/**
 * 任务元数据出参（P1 册 5.2 的 {@code JobDTO}；5.3 未列字段清单，本记录按 {@code job} 表 4.3.9 的列
 * 逐列投影，差异登记在《实现注记（T7）》）。
 *
 * <p>{@code params_json} 以 {@code Map<String,String>} 出参（与 {@code JobSaveCmd.params} 对称）：
 * 任务参数是"键 → 值"的标量集合，让前端拿到 JSON 字符串再自行解析是把 JSONB 的结构信息丢掉。
 *
 * @param id              行 ID（雪花）
 * @param jobCode         任务编码（行身份，创建后不可改）
 * @param jobName         任务名称
 * @param handlerCode     处理点编码（必须命中 {@code JobHandlerRegistry}）
 * @param cron            Spring 6 段 cron
 * @param params          任务参数（{@code params_json} 的投影；无参数为 null）
 * @param enabled         是否启用（false = 既不按 cron 触发，也拒绝手动触发，20024）
 * @param timeoutSeconds  超时秒数
 * @param retryMax        失败重投上限
 * @param backoffSeconds  退避基数秒
 * @param allowConcurrent 是否允许并发执行
 * @param nextRunTime     下次触发时刻（**仅供展示**，不参与调度决策，4.3.9）
 * @param lastRunTime     最后一次执行开始时刻
 * @param lastStatus      最后一次执行终态（{@code SUCCESS}/{@code FAILED}/{@code TIMEOUT}/{@code SKIPPED}）
 * @param remark          备注（跨模块 {@code register} 的 {@code description} 也落这一列）
 * @param scheduled       本实例当前是否挂着 cron 调度（{@code pause} 后为 false；运行期事实，不入库）
 * @param running         本任务当前是否有未结束的运行（含其他实例；管理页据此禁用"触发/删除"）
 * @param version         乐观锁版本
 */
public record JobDTO(
        long id,
        String jobCode,
        String jobName,
        String handlerCode,
        String cron,
        Map<String, String> params,
        boolean enabled,
        int timeoutSeconds,
        int retryMax,
        int backoffSeconds,
        boolean allowConcurrent,
        Instant nextRunTime,
        Instant lastRunTime,
        String lastStatus,
        String remark,
        boolean scheduled,
        boolean running,
        int version) {
}
