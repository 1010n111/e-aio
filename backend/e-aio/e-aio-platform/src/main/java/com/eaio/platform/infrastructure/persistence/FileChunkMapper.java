package com.eaio.platform.infrastructure.persistence;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaio.platform.domain.file.FileChunk;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FileChunkMapper extends BaseMapper<FileChunk> {

    @Insert("""
            INSERT INTO eaio_platform.file_chunk
                (id, session_id, chunk_index, chunk_size, sha256, storage_path, created_at, created_by)
            VALUES
                (#{id}, #{sessionId}, #{chunkIndex}, #{chunkSize}, #{sha256}, #{storagePath}, #{createdAt}, #{createdBy})
            ON CONFLICT (session_id, chunk_index) DO UPDATE
                SET chunk_size = EXCLUDED.chunk_size, sha256 = EXCLUDED.sha256,
                    storage_path = EXCLUDED.storage_path, created_at = EXCLUDED.created_at,
                    created_by = EXCLUDED.created_by
            """)
    int upsert(FileChunk chunk);

    @Select("""
            SELECT * FROM eaio_platform.file_chunk
             WHERE session_id = #{sessionId}
             ORDER BY chunk_index
            """)
    List<FileChunk> selectBySessionId(@Param("sessionId") long sessionId);

    @Select("""
            SELECT count(*) FROM eaio_platform.file_chunk
             WHERE session_id = #{sessionId}
            """)
    int countBySessionId(@Param("sessionId") long sessionId);

    @Delete("DELETE FROM eaio_platform.file_chunk WHERE session_id = #{sessionId}")
    int deleteBySessionId(@Param("sessionId") long sessionId);
}
