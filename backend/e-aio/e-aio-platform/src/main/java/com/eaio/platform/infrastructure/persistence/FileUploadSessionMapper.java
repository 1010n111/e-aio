package com.eaio.platform.infrastructure.persistence;

import java.time.Instant;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaio.platform.domain.file.FileUploadSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface FileUploadSessionMapper extends BaseMapper<FileUploadSession> {

    @Select("SELECT * FROM eaio_platform.file_upload_session WHERE upload_id = #{uploadId}")
    FileUploadSession selectByUploadId(@Param("uploadId") String uploadId);

    @Update("""
            UPDATE eaio_platform.file_upload_session
               SET status = 'MERGING', updated_at = #{now}
             WHERE id = #{id} AND status = 'OPEN' AND expire_time > #{now}
            """)
    int claimMerge(@Param("id") long id, @Param("now") Instant now);

    @Update("""
            UPDATE eaio_platform.file_upload_session
               SET status = 'EXPIRED', updated_at = #{now}
             WHERE id = #{id} AND status <> 'DONE'
            """)
    int expireOne(@Param("id") long id, @Param("now") Instant now);

    @Update("""
            UPDATE eaio_platform.file_upload_session
               SET status = 'OPEN', updated_at = #{now}
             WHERE id = #{id} AND status = 'MERGING'
            """)
    int reopenMerge(@Param("id") long id, @Param("now") Instant now);

    @Update("""
            UPDATE eaio_platform.file_upload_session
               SET status = 'DONE', file_id = #{fileId}, updated_at = #{now}
             WHERE id = #{id} AND status = 'MERGING'
            """)
    int markDone(@Param("id") long id, @Param("fileId") long fileId, @Param("now") Instant now);

    @Update("""
            UPDATE eaio_platform.file_upload_session
               SET status = 'EXPIRED', updated_at = #{now}
             WHERE status IN ('OPEN', 'MERGING') AND expire_time <= #{now}
            """)
    int expireBefore(@Param("now") Instant now);

    @Select("""
            SELECT * FROM eaio_platform.file_upload_session
             WHERE status = 'EXPIRED' AND expire_time < #{before}
             ORDER BY expire_time
             LIMIT #{limit}
            """)
    List<FileUploadSession> selectExpiredBefore(@Param("before") Instant before, @Param("limit") int limit);
}
