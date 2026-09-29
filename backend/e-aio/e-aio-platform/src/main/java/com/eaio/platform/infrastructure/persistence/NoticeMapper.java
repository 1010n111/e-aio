package com.eaio.platform.infrastructure.persistence;

import java.time.Instant;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaio.platform.domain.notice.Notice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface NoticeMapper extends BaseMapper<Notice> {

    @Select("SELECT n.* FROM eaio_platform.notice n WHERE n.deleted = false "
            + "AND n.publish_status = 'PUBLISHED' AND n.publish_time <= #{now} "
            + "AND (n.expire_time IS NULL OR n.expire_time > #{now}) "
            + "AND (n.scope_type = 'ALL' OR EXISTS (SELECT 1 FROM eaio_platform.notice_target t "
            + "WHERE t.notice_id = n.id AND ((t.target_type = 'USER' AND t.target_id = #{userId}) "
            + "OR (t.target_type = 'ORG' AND t.target_id = #{orgId})))) "
            + "AND NOT EXISTS (SELECT 1 FROM eaio_platform.notice_read r "
            + "WHERE r.notice_id = n.id AND r.user_id = #{userId}) "
            + "ORDER BY n.top_flag DESC, n.publish_time DESC, n.id DESC LIMIT #{limit} OFFSET #{offset}")
    List<Notice> selectUnread(@Param("userId") Long userId, @Param("orgId") Long orgId,
            @Param("now") Instant now, @Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT count(*) FROM eaio_platform.notice n WHERE n.deleted = false "
            + "AND n.publish_status = 'PUBLISHED' AND n.publish_time <= #{now} "
            + "AND (n.expire_time IS NULL OR n.expire_time > #{now}) "
            + "AND (n.scope_type = 'ALL' OR EXISTS (SELECT 1 FROM eaio_platform.notice_target t "
            + "WHERE t.notice_id = n.id AND ((t.target_type = 'USER' AND t.target_id = #{userId}) "
            + "OR (t.target_type = 'ORG' AND t.target_id = #{orgId})))) "
            + "AND NOT EXISTS (SELECT 1 FROM eaio_platform.notice_read r "
            + "WHERE r.notice_id = n.id AND r.user_id = #{userId})")
    long countUnread(@Param("userId") Long userId, @Param("orgId") Long orgId, @Param("now") Instant now);

    @Select("SELECT count(*) FROM eaio_platform.notice n WHERE n.id = #{noticeId} AND n.deleted = false "
            + "AND n.publish_status = 'PUBLISHED' AND n.publish_time <= #{now} "
            + "AND (n.expire_time IS NULL OR n.expire_time > #{now}) "
            + "AND (n.scope_type = 'ALL' OR EXISTS (SELECT 1 FROM eaio_platform.notice_target t "
            + "WHERE t.notice_id = n.id AND ((t.target_type = 'USER' AND t.target_id = #{userId}) "
            + "OR (t.target_type = 'ORG' AND t.target_id = #{orgId}))))")
    long countVisible(@Param("noticeId") long noticeId, @Param("userId") long userId,
            @Param("orgId") long orgId, @Param("now") Instant now);

    @Update("UPDATE eaio_platform.notice SET publish_status = 'PUBLISHED', publish_time = #{now}, "
            + "updated_at = #{now} WHERE id = #{id} AND deleted = false AND publish_status = 'DRAFT'")
    int publishDraft(@Param("id") long id, @Param("now") Instant now);

    @Select("SELECT * FROM eaio_platform.notice WHERE deleted = false AND publish_status = 'DRAFT' "
            + "AND publish_time IS NOT NULL AND publish_time <= #{now} ORDER BY publish_time, id")
    List<Notice> selectDueDrafts(@Param("now") Instant now);
}
