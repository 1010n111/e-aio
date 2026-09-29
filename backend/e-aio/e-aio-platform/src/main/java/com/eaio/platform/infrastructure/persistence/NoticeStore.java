package com.eaio.platform.infrastructure.persistence;

import java.time.Instant;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaio.common.exception.SystemException;
import com.eaio.platform.api.dto.NoticeQuery;
import com.eaio.platform.domain.notice.Notice;
import com.eaio.platform.domain.notice.NoticeTarget;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** 公告、收件目标与已读回执的持久化门面。 */
@Component
public class NoticeStore {

    private final ObjectProvider<NoticeMapper> notices;
    private final ObjectProvider<NoticeTargetMapper> targets;
    private final ObjectProvider<NoticeReadMapper> reads;

    public NoticeStore(ObjectProvider<NoticeMapper> notices, ObjectProvider<NoticeTargetMapper> targets,
            ObjectProvider<NoticeReadMapper> reads) {
        this.notices = notices;
        this.targets = targets;
        this.reads = reads;
    }

    public Notice rowById(long id) {
        return noticeMapper().selectById(id);
    }
    public IPage<Notice> page(NoticeQuery query) {
        NoticeQuery actual = query == null ? new NoticeQuery(null, null, null, null, null, null, null) : query;
        LambdaQueryWrapper<Notice> wrapper = new LambdaQueryWrapper<>();
        if (actual.title() != null && !actual.title().isBlank()) {
            wrapper.like(Notice::getTitle, actual.title().trim());
        }
        if (actual.publishStatus() != null && !actual.publishStatus().isBlank()) {
            wrapper.eq(Notice::getPublishStatus, actual.publishStatus().trim().toUpperCase());
        }
        if (actual.scopeType() != null && !actual.scopeType().isBlank()) {
            wrapper.eq(Notice::getScopeType, actual.scopeType().trim().toUpperCase());
        }
        wrapper.orderByDesc(Notice::isTopFlag).orderByDesc(Notice::getPublishTime).orderByDesc(Notice::getId);
        return noticeMapper().selectPage(new Page<>(pageNum(actual.pageNum()), pageSize(actual.pageSize())), wrapper);
    }

    public int insert(Notice row) {
        return noticeMapper().insert(row);
    }
    public int updateById(Notice row) {
        return noticeMapper().updateById(row);
    }
    public int deleteById(Notice row) {
        return noticeMapper().deleteById(row);
    }
    public int publishDraft(long id, Instant now) {
        return noticeMapper().publishDraft(id, now);
    }
    public List<Notice> dueDrafts(Instant now) {
        return noticeMapper().selectDueDrafts(now);
    }
    public void insertTarget(NoticeTarget row) {
        targetMapper().insert(row);
    }
    public void deleteTargets(long noticeId) {
        targetMapper().deleteByNoticeId(noticeId);
    }
    public List<NoticeTarget> targets(long noticeId) {
        return targetMapper().selectByNoticeId(noticeId);
    }
    public int insertReadIgnore(long id, long noticeId, long userId) {
        return readMapper().insertIgnore(id, noticeId, userId);
    }
    public long countRead(long noticeId, long userId) {
        return readMapper().countByNoticeAndUser(noticeId, userId);
    }
    public List<Notice> unread(Long userId, Long orgId, Instant now, int limit, int offset) {
        return noticeMapper().selectUnread(userId, orgId, now, limit, offset);
    }
    public long countUnread(Long userId, Long orgId, Instant now) {
        return noticeMapper().countUnread(userId, orgId, now);
    }
    public boolean visibleTo(long noticeId, long userId, long orgId, Instant now) {
        return noticeMapper().countVisible(noticeId, userId, orgId, now) > 0;
    }
    private NoticeMapper noticeMapper() {
        NoticeMapper mapper = notices.getIfAvailable();
        if (mapper == null) {
            throw new SystemException("公告不可用：未配置数据库");
        }
        return mapper;
    }
    private NoticeTargetMapper targetMapper() {
        NoticeTargetMapper mapper = targets.getIfAvailable();
        if (mapper == null) {
            throw new SystemException("公告收件目标不可用：未配置数据库");
        }
        return mapper;
    }
    private NoticeReadMapper readMapper() {
        NoticeReadMapper mapper = reads.getIfAvailable();
        if (mapper == null) {
            throw new SystemException("公告已读回执不可用：未配置数据库");
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
