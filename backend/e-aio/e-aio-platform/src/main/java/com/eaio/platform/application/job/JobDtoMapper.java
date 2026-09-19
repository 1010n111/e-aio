package com.eaio.platform.application.job;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.eaio.common.json.JsonUtils;
import com.eaio.platform.api.dto.JobDTO;
import com.eaio.platform.api.dto.JobRunDTO;
import com.eaio.platform.domain.job.Job;
import com.eaio.platform.domain.job.JobRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 任务实体 ↔ DTO 映射（P1 册 5.3）。
 *
 * <p><b>为什么手写而不是 MapStruct</b>（与 {@code DictDtoMapper} 的差异，登记在《实现注记（T7）》）：
 * 本映射有两处**不是字段搬运**，MapStruct 只能用 {@code expression} 硬写，读起来比手写更差：
 * <ol>
 *   <li>{@code params_json}（JSONB 文本）↔ {@code Map<String,String>}，还要处理"列里是合法 JSON 但值不是
 *       字符串"的情况（有人直接改库写成 {@code {"days": 30}}）；</li>
 *   <li>{@link JobDTO} 的 {@code scheduled}/{@code running}/{@code nextRunTime} 是**运行期事实**，
 *       不在 {@code job} 行上（分别来自调度器与 {@code job_run}），必须由调用方传进来。</li>
 * </ol>
 */
@Component
public class JobDtoMapper {

    private static final Logger log = LoggerFactory.getLogger(JobDtoMapper.class);

    /**
     * {@code params_json} → {@code Map<String,String>}。
     *
     * <p>解析失败**不让整个查询失败**：一个手改坏的任务参数不该让任务列表页打不开；此时按"无参数"处理
     * 并记 WARN（任务执行同样会拿到空参数——与"参数坏了就整个任务不能跑"相比，前者可观测、后者不可用）。
     */
    public Map<String, String> decodeParams(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = JsonUtils.toMap(paramsJson);
            Map<String, String> params = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                if (entry.getValue() != null) {
                    params.put(entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
            return Map.copyOf(params);
        } catch (RuntimeException e) {
            log.warn("任务参数不是合法 JSON 对象，按无参数处理：{}（{}）", paramsJson, e.getMessage());
            return Map.of();
        }
    }

    /** {@code Map<String,String>} → {@code params_json}；空参数写 {@code null}（不是 {@code "{}"}）。 */
    public String encodeParams(Map<String, String> params) {
        return params == null || params.isEmpty() ? null : JsonUtils.toJson(params);
    }

    /**
     * 任务行 → DTO。
     *
     * @param job         任务行
     * @param scheduled   本实例当前是否挂着 cron 调度（{@code pause} 后为 false）
     * @param running     是否存在未结束的运行（按 {@code job_run} 判定，含其他实例）
     * @param nextRunTime 展示用的下次触发时刻（只读展示；暂停/停用时为 {@code null}）
     */
    public JobDTO toDto(Job job, boolean scheduled, boolean running, Instant nextRunTime) {
        return new JobDTO(
                job.getId() == null ? 0L : job.getId(),
                job.getJobCode(),
                job.getJobName(),
                job.getHandlerCode(),
                job.getCron(),
                decodeParams(job.getParamsJson()),
                job.isEnabled(),
                job.getTimeoutSeconds(),
                job.getRetryMax(),
                job.getBackoffSeconds(),
                job.isAllowConcurrent(),
                nextRunTime,
                job.getLastRunTime(),
                job.getLastStatus(),
                job.getRemark(),
                scheduled,
                running,
                job.getVersion() == null ? 0 : job.getVersion());
    }

    /** 运行日志行 → DTO（字段一一对应，5.3）。 */
    public JobRunDTO toDto(JobRun run) {
        return new JobRunDTO(
                run.getId() == null ? 0L : run.getId(),
                run.getJobCode(),
                run.getTriggerType(),
                run.getStatus(),
                run.getAttempt() == null ? 0 : run.getAttempt(),
                run.getStartTime(),
                run.getEndTime(),
                run.getDurationMs(),
                run.getNextRetryTime(),
                run.getErrorMessage(),
                run.getTraceId());
    }
}
