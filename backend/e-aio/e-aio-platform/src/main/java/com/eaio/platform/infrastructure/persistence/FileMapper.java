package com.eaio.platform.infrastructure.persistence;

import java.time.Instant;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaio.platform.domain.file.FileMetaFile;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 文件元数据表 Mapper（P1 册 3.3.2 的 {@code infrastructure/persistence}，MyBatis-Plus）。
 *
 * <p>两条**绕过 {@code @TableLogic}** 的显式 SQL：孤儿清理要按"无绑定"与"软删时间"扫描，
 * 而逻辑删除的实体查询会自动追加 {@code deleted = false`，扫不到软删行（3.3.7 的两步清理）。
 * 其余查询走 {@code BaseMapper} + {@code LambdaQueryWrapper}，逻辑删除条件由框架追加。
 */
@Mapper
public interface FileMapper extends BaseMapper<FileMetaFile> {

    /** 候选孤儿：未删除、创建时间早于 {@code before}、且**没有任何绑定**（分批，按创建时间升序）。 */
    @Select("""
            SELECT f.* FROM eaio_platform.file f
            WHERE f.deleted = false AND f.created_at < #{before}
              AND NOT EXISTS (SELECT 1 FROM eaio_platform.file_binding b WHERE b.file_id = f.id)
            ORDER BY f.created_at
            LIMIT #{limit}
            """)
    List<FileMetaFile> selectUnboundBefore(@Param("before") Instant before, @Param("limit") int limit);

    /** 软删且软删时间早于 {@code before} 的行（物理清理的候选；按软删时间升序）。 */
    @Select("""
            SELECT f.* FROM eaio_platform.file f
            WHERE f.deleted = true AND f.updated_at IS NOT NULL AND f.updated_at < #{before}
            ORDER BY f.updated_at
            LIMIT #{limit}
            """)
    List<FileMetaFile> selectPurgeableBefore(@Param("before") Instant before, @Param("limit") int limit);

    /** 物理删除已软删行；不能调用 BaseMapper.deleteById（{@code @TableLogic} 会再次生成 UPDATE）。 */
    @Delete("DELETE FROM eaio_platform.file WHERE id = #{id} AND deleted = true")
    int purgeById(@Param("id") long id);

    /** 某文件是否仍有绑定（软删前的复核：扫描与软删之间可能刚被业务绑定；本表无 deleted 列）。 */
    @Select("""
            SELECT count(*) FROM eaio_platform.file_binding b WHERE b.file_id = #{fileId}
            """)
    long countActiveBindings(@Param("fileId") long fileId);
}
