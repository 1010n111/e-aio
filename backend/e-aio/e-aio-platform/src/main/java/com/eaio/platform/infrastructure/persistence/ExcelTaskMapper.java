package com.eaio.platform.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaio.platform.domain.excel.ExcelTask;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ExcelTaskMapper extends BaseMapper<ExcelTask> {

    @Select("SELECT * FROM eaio_platform.excel_task WHERE task_type = #{taskType} AND biz_type = #{bizType} "
            + "AND submitter_id = #{submitterId} AND source_sha256 = #{sha256} "
            + "AND status IN ('PENDING', 'RUNNING') ORDER BY created_at DESC LIMIT 1")
    ExcelTask runningDuplicate(@Param("taskType") String taskType, @Param("bizType") String bizType,
            @Param("submitterId") Long submitterId, @Param("sha256") String sha256);

    @Update("UPDATE eaio_platform.excel_task SET deduplicated = true, updated_at = now() "
            + "WHERE id = #{id} AND status IN ('PENDING', 'RUNNING')")
    int markDeduplicated(@Param("id") long id);

    @Update("UPDATE eaio_platform.excel_task SET status = 'RUNNING', start_time = now(), updated_at = now() "
            + "WHERE id = #{id} AND status = 'PENDING'")
    int markRunning(@Param("id") long id);

    @Update("UPDATE eaio_platform.excel_task SET status = #{status}, progress_percent = #{progress}, "
            + "total_rows = #{totalRows}, success_rows = #{successRows}, fail_rows = #{failRows}, "
            + "updated_at = now() WHERE id = #{id} AND status IN ('PENDING', 'RUNNING')")
    int updateProgress(@Param("id") long id, @Param("status") String status, @Param("progress") int progress,
            @Param("totalRows") int totalRows, @Param("successRows") int successRows,
            @Param("failRows") int failRows);

    @Update("UPDATE eaio_platform.excel_task SET status = #{status}, progress_percent = 100, "
            + "total_rows = #{totalRows}, success_rows = #{successRows}, fail_rows = #{failRows}, "
            + "result_file_id = #{resultFileId}, error_file_id = #{errorFileId}, error_message = #{message}, "
            + "finish_time = now(), updated_at = now() WHERE id = #{id} AND status <> 'CANCELLED'")
    int finish(@Param("id") long id, @Param("status") String status, @Param("totalRows") int totalRows,
            @Param("successRows") int successRows, @Param("failRows") int failRows,
            @Param("resultFileId") Long resultFileId, @Param("errorFileId") Long errorFileId,
            @Param("message") String message);

    @Update("UPDATE eaio_platform.excel_task SET status = 'CANCELLED', finish_time = now(), updated_at = now() "
            + "WHERE id = #{id} AND status IN ('PENDING', 'RUNNING')")
    int cancel(@Param("id") long id);

    @Delete("DELETE FROM eaio_platform.excel_task WHERE id = #{id} AND status = 'PENDING'")
    int deletePending(@Param("id") long id);

    @Select("SELECT count(*) FROM eaio_platform.excel_task_error WHERE task_id = #{taskId}")
    long countErrors(@Param("taskId") long taskId);
}
