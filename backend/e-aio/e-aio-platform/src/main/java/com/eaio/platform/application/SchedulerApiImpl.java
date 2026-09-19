package com.eaio.platform.application;

import java.util.Map;

import com.eaio.common.api.PageResult;
import com.eaio.platform.api.SchedulerApi;
import com.eaio.platform.api.dto.JobDefinition;
import com.eaio.platform.api.dto.JobRunDTO;
import com.eaio.platform.api.dto.JobRunQuery;
import org.springframework.stereotype.Component;

/**
 * {@link SchedulerApi} 的跨模块实现（P1 册 2.4.3：**薄壳**，全部委托 {@link JobAppService}）。
 *
 * <p>薄是有意的：REST 与跨模块入口共用同一套校验、锁与状态机；实现类里一旦长出逻辑，两条入口就会各长
 * 一份（与 {@code ParamApiImpl}/{@code DictApiImpl} 同款口径）。放 {@code application} 层（裁决 P1-C1）。
 *
 * <p>方法**不开启事务**（5.4：跨模块调用的事务由调用方决定）：写路径的原子性由单条语句与唯一键保证
 * （{@code register} 是"读 + 插入/更新"，跨模块并发注册同一 {@code jobCode} 时靠 {@code uk_job_code}
 * 让后者失败——那种情况是同一个模块的两个实例同时启动，重试即可）。
 */
@Component
public class SchedulerApiImpl implements SchedulerApi {

    private final JobAppService service;

    public SchedulerApiImpl(JobAppService service) {
        this.service = service;
    }

    @Override
    public void register(JobDefinition definition) {
        service.register(definition);
    }

    @Override
    public long trigger(String jobCode, Map<String, String> params) {
        return service.trigger(jobCode, params);
    }

    @Override
    public void pause(String jobCode) {
        service.pause(jobCode);
    }

    @Override
    public void resume(String jobCode) {
        service.resume(jobCode);
    }

    @Override
    public PageResult<JobRunDTO> listRuns(JobRunQuery query) {
        return service.pageRuns(query);
    }
}
