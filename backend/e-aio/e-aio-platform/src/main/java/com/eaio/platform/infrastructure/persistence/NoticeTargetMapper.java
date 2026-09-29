package com.eaio.platform.infrastructure.persistence;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaio.platform.domain.notice.NoticeTarget;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface NoticeTargetMapper extends BaseMapper<NoticeTarget> {

    @Delete("DELETE FROM eaio_platform.notice_target WHERE notice_id = #{noticeId}")
    int deleteByNoticeId(@Param("noticeId") long noticeId);

    @Select("SELECT * FROM eaio_platform.notice_target WHERE notice_id = #{noticeId}")
    List<NoticeTarget> selectByNoticeId(@Param("noticeId") long noticeId);
}
