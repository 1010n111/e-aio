package com.eaio.platform.infrastructure.persistence;

import java.util.Collection;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaio.platform.domain.file.FileBinding;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 文件绑定表 Mapper（P1 册 4.3.8）。
 *
 * <p>绑定是**幂等写**，靠唯一键 {@code uk_file_binding_triple(file_id, biz_type, biz_id)} 承担：
 * 重复绑定撞唯一键即"已经绑上了"，不报错、不产生第二行（5.4 的 {@code bind} 约定）。
 * 先 count 再 insert 会在并发下漏判，直接让数据库判更可靠（少一次往返也更少一处竞态）。
 */
@Mapper
public interface FileBindingMapper extends BaseMapper<FileBinding> {

    /**
     * 绑定（幂等）：{@code ON CONFLICT DO NOTHING} + 返回受影响行数（0 = 早就绑过了）。
     *
     * <p>{@code ON CONFLICT} 不指定冲突目标，因此任何唯一键冲突都按"已存在"处理——本表只有
     * {@code uk_file_binding_triple} 一个唯一约束（PK 由雪花 ID 生成，不会撞）。
     *
     * <p>本表**没有 {@code deleted} 列**（P1-3 册 4.2 的统一列例外清单："关系行，解绑即物理删"），
     * 所以既没有逻辑删除条件，也不需要部分唯一索引。
     */
    @Update("""
            INSERT INTO eaio_platform.file_binding (id, file_id, biz_type, biz_id, created_at, created_by)
            VALUES (#{id}, #{fileId}, #{bizType}, #{bizId}, #{createdAt}, #{createdBy})
            ON CONFLICT DO NOTHING
            """)
    int bindIfAbsent(@Param("id") long id, @Param("fileId") long fileId, @Param("bizType") String bizType,
            @Param("bizId") long bizId, @Param("createdAt") java.time.Instant createdAt,
            @Param("createdBy") Long createdBy);

    /** 这批文件里已绑定到该业务对象的 ID（绑定后回执"哪些是新增、哪些本来就有"）。 */
    @Select("""
            <script>
            SELECT b.file_id FROM eaio_platform.file_binding b
            WHERE b.biz_type = #{bizType} AND b.biz_id = #{bizId}
              AND b.file_id IN
            <foreach item="id" collection="fileIds" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Long> selectBoundFileIds(@Param("bizType") String bizType, @Param("bizId") long bizId,
            @Param("fileIds") Collection<Long> fileIds);

    /** 按业务对象反查文件 ID（业务模块"这个客户的附件有哪些"的入口）。 */
    @Select("""
            SELECT b.file_id FROM eaio_platform.file_binding b
            WHERE b.biz_type = #{bizType} AND b.biz_id = #{bizId}
            ORDER BY b.created_at, b.id
            """)
    List<Long> selectFileIdsByBiz(@Param("bizType") String bizType, @Param("bizId") long bizId);

    /**
     * 物理删除一批文件的全部绑定行：孤儿清理要物理删 {@code file} 行，而 {@code fk_file_binding_file}
     * 会拦住删除——绑定行本身没有保留价值（"这个文件曾绑过谁"由业务侧的表回答）。
     */
    @Update("""
            <script>
            DELETE FROM eaio_platform.file_binding WHERE file_id IN
            <foreach item="id" collection="fileIds" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int purgeByFileIds(@Param("fileIds") Collection<Long> fileIds);
}
