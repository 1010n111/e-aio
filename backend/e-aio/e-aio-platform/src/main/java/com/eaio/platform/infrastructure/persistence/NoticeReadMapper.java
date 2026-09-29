package com.eaio.platform.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaio.platform.domain.notice.NoticeRead;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface NoticeReadMapper extends BaseMapper<NoticeRead> {

    @Insert("INSERT INTO eaio_platform.notice_read (id, notice_id, user_id, created_at, created_by) "
            + "VALUES (#{id}, #{noticeId}, #{userId}, now(), #{userId}) ON CONFLICT (notice_id, user_id) DO NOTHING")
    int insertIgnore(@Param("id") long id, @Param("noticeId") long noticeId, @Param("userId") long userId);

    @org.apache.ibatis.annotations.Select("SELECT count(*) FROM eaio_platform.notice_read "
            + "WHERE notice_id = #{noticeId} AND user_id = #{userId}")
    long countByNoticeAndUser(@Param("noticeId") long noticeId, @Param("userId") long userId);
}
