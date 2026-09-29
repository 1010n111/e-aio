package com.eaio.platform.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaio.common.exception.SystemException;
import com.eaio.platform.api.dto.ExcelTaskErrorQuery;
import com.eaio.platform.api.dto.ExcelTaskQuery;
import com.eaio.platform.domain.excel.ExcelTask;
import com.eaio.platform.domain.excel.ExcelTaskError;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class ExcelTaskStore {

    private final ObjectProvider<ExcelTaskMapper> tasks;
    private final ObjectProvider<ExcelTaskErrorMapper> errors;

    public ExcelTaskStore(ObjectProvider<ExcelTaskMapper> tasks, ObjectProvider<ExcelTaskErrorMapper> errors) {
        this.tasks = tasks;
        this.errors = errors;
    }

    public ExcelTask row(long id) {
        return taskMapper().selectById(id);
    }

    public int insert(ExcelTask task) {
        return taskMapper().insert(task);
    }

    public IPage<ExcelTask> page(ExcelTaskQuery query) {
        ExcelTaskQuery actual = query == null
                ? new ExcelTaskQuery(null, null, null, null, 1, 20, null, null) : query;
        LambdaQueryWrapper<ExcelTask> wrapper = new LambdaQueryWrapper<>();
        if (actual.taskType() != null && !actual.taskType().isBlank()) {
            wrapper.eq(ExcelTask::getTaskType, actual.taskType().trim().toUpperCase());
        }
        if (actual.bizType() != null && !actual.bizType().isBlank()) {
            wrapper.eq(ExcelTask::getBizType, actual.bizType().trim());
        }
        if (actual.status() != null && !actual.status().isBlank()) {
            wrapper.eq(ExcelTask::getStatus, actual.status().trim().toUpperCase());
        }
        if (actual.submitterId() != null && actual.submitterId() > 0) {
            wrapper.eq(ExcelTask::getSubmitterId, actual.submitterId());
        }
        if ("asc".equalsIgnoreCase(actual.orderDir())) {
            wrapper.orderByAsc(ExcelTask::getCreatedAt);
        } else {
            wrapper.orderByDesc(ExcelTask::getCreatedAt);
        }
        return taskMapper().selectPage(new Page<>(pageNum(actual.pageNum()), pageSize(actual.pageSize())), wrapper);
    }

    public ExcelTask duplicate(String type, String bizType, Long submitterId, String sha256) {
        return taskMapper().runningDuplicate(type, bizType, submitterId, sha256);
    }

    public int markDeduplicated(long id) {
        return taskMapper().markDeduplicated(id);
    }

    public int markRunning(long id) {
        return taskMapper().markRunning(id);
    }

    public int updateProgress(long id, String status, int progress, int total, int success, int failed) {
        return taskMapper().updateProgress(id, status, progress, total, success, failed);
    }
    public int finish(long id, String status, int total, int success, int failed, Long result, Long error,
            String message) {
        return taskMapper().finish(id, status, total, success, failed, result, error, message);
    }
    public int cancel(long id) {
        return taskMapper().cancel(id);
    }

    public int deletePending(long id) {
        return taskMapper().deletePending(id);
    }

    public long countErrors(long id) {
        return taskMapper().countErrors(id);
    }

    public void insertError(ExcelTaskError error) {
        errorMapper().insert(error);
    }

    public IPage<ExcelTaskError> pageErrors(ExcelTaskErrorQuery query, long id) {
        int pageNum = query.pageNum() == null || query.pageNum() < 1 ? 1 : query.pageNum();
        int pageSize = query.pageSize() == null || query.pageSize() < 1 || query.pageSize() > 200
                ? 20 : query.pageSize();
        return errorMapper().selectPage(new Page<>(pageNum, pageSize), new LambdaQueryWrapper<ExcelTaskError>()
                .eq(ExcelTaskError::getTaskId, id).orderByAsc(ExcelTaskError::getRowNum)
                .orderByAsc(ExcelTaskError::getId));
    }

    private ExcelTaskMapper taskMapper() {
        ExcelTaskMapper mapper = tasks.getIfAvailable();
        if (mapper == null) {
            throw new SystemException("Excel 任务不可用：未配置数据库");
        }
        return mapper;
    }

    private ExcelTaskErrorMapper errorMapper() {
        ExcelTaskErrorMapper mapper = errors.getIfAvailable();
        if (mapper == null) {
            throw new SystemException("Excel 错误明细不可用：未配置数据库");
        }
        return mapper;
    }

    private static long pageNum(Integer value) {
        return value == null || value < 1 ? 1 : value;
    }

    private static long pageSize(Integer value) {
        return value == null || value < 1 || value > 200 ? 20 : value;
    }
}
