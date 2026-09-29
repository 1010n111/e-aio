package com.eaio.platform.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.eaio.common.api.PageResult;
import com.eaio.common.exception.BusinessException;
import com.eaio.common.id.IdGenerator;
import com.eaio.platform.api.PlatformErrorCode;
import com.eaio.platform.api.dto.NoticeDTO;
import com.eaio.platform.api.dto.NoticePublishCmd;
import com.eaio.platform.api.dto.NoticeQuery;
import com.eaio.platform.api.port.OrgContextPort;
import com.eaio.platform.application.event.PlatformEventPublisher;
import com.eaio.platform.domain.notice.Notice;
import com.eaio.platform.domain.notice.NoticeTarget;
import com.eaio.platform.events.NoticePublishedEvent;
import com.eaio.platform.infrastructure.persistence.NoticeStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 公告发布、收件范围、未读和已读回执。 */
@Service
public class NoticeAppService {

    private final NoticeStore store;
    private final ObjectProvider<OrgContextPort> contexts;
    private final IdGenerator ids;
    private final PlatformEventPublisher events;

    public NoticeAppService(NoticeStore store, ObjectProvider<OrgContextPort> contexts, IdGenerator ids,
            PlatformEventPublisher events) {
        this.store = store;
        this.contexts = contexts;
        this.ids = ids;
        this.events = events;
    }

    @Transactional
    public NoticeDTO save(NoticePublishCmd cmd) {
        Notice existing = cmd.id() == null ? null : require(cmd.id());
        boolean preserveTargets = preserveTargets(existing, cmd);
        validate(cmd, preserveTargets);
        Context context = current();
        Instant now = Instant.now();
        Notice row = existing == null ? new Notice() : existing;
        boolean isNew = row.getId() == null;
        String previousStatus = row.getPublishStatus();
        if (isNew) {
            row.setId(ids.nextId());
            row.setCreatedAt(now);
            row.setCreatedBy(context.userId());
            row.setVersion(0);
            row.setDeleted(false);
        }
        row.setTitle(cmd.title().trim());
        row.setContent(cmd.content());
        row.setScopeType(cmd.scopeType().trim().toUpperCase());
        row.setPublishTime(cmd.publishTime() == null ? now : cmd.publishTime());
        row.setExpireTime(cmd.expireTime());
        row.setTopFlag(cmd.topFlag());
        row.setPublisherId(context.userId());
        row.setPublisherOrgId(context.orgId());
        row.setUpdatedAt(now);
        row.setUpdatedBy(context.userId());
        row.setPublishStatus(row.getPublishTime().isAfter(now) ? "DRAFT" : "PUBLISHED");
        if (isNew) {
            store.insert(row);
        } else if (store.updateById(row) == 0) {
            throw new BusinessException(com.eaio.common.api.ErrorCode.DATA_CONFLICT, "公告已被他人修改");
        }
        if (!preserveTargets) {
            store.deleteTargets(row.getId());
            insertTargets(row.getId(), row.getScopeType(), cmd.targetType(), cmd.targetIds(), context.userId(), now);
        }
        if ("PUBLISHED".equals(row.getPublishStatus()) && !"PUBLISHED".equals(previousStatus)) {
            publishEvent(row);
        }
        return toDto(row, false);
    }

    @Transactional
    public NoticeDTO publish(long id, Instant requestedTime) {
        Notice row = require(id);
        Instant now = Instant.now();
        Instant time = requestedTime == null ? now : requestedTime;
        if (row.getExpireTime() != null && !row.getExpireTime().isAfter(time)) {
            throw new BusinessException(PlatformErrorCode.NOTICE_PUBLISH_INVALID, "公告过期时间必须晚于发布时间");
        }
        String previousStatus = row.getPublishStatus();
        row.setPublishTime(time);
        row.setPublishStatus(time.isAfter(now) ? "DRAFT" : "PUBLISHED");
        row.setUpdatedAt(now);
        if (store.updateById(row) == 0) {
            throw new BusinessException(com.eaio.common.api.ErrorCode.DATA_CONFLICT, "公告已被他人修改");
        }
        if ("PUBLISHED".equals(row.getPublishStatus()) && !"PUBLISHED".equals(previousStatus)) {
            publishEvent(row);
        }
        return toDto(row, false);
    }

    @Transactional
    public void publishDue() {
        Instant now = Instant.now();
        for (Notice row : store.dueDrafts(now)) {
            if (store.publishDraft(row.getId(), now) > 0) {
                row.setPublishStatus("PUBLISHED");
                row.setPublishTime(now);
                publishEvent(row);
            }
        }
    }

    @Transactional
    public void revoke(long id) {
        Notice row = require(id);
        row.setPublishStatus("REVOKED");
        row.setUpdatedAt(Instant.now());
        if (store.updateById(row) == 0) {
            throw new BusinessException(com.eaio.common.api.ErrorCode.DATA_CONFLICT, "公告已被他人修改");
        }
    }

    @Transactional
    public void delete(long id, int version) {
        Notice row = require(id);
        requireVersion(row, version);
        store.deleteById(row);
    }

    public NoticeDTO get(long id) {
        return toDto(require(id), false);
    }
    public PageResult<NoticeDTO> page(NoticeQuery query) {
        IPage<Notice> page = store.page(query);
        return PageResult.from(page.getCurrent(), page.getSize(), page.getTotal(),
                page.getRecords().stream().map(row -> toDto(row, false)).toList());
    }

    public PageResult<NoticeDTO> unread() {
        Context context = current();
        int size = 20;
        List<Notice> rows = store.unread(context.userId(), context.orgId(), Instant.now(), size, 0);
        return PageResult.from(1, size, store.countUnread(context.userId(), context.orgId(), Instant.now()),
                rows.stream().map(row -> toDto(row, false)).toList());
    }

    @Transactional
    public void markRead(long id) {
        Notice row = require(id);
        Context context = current();
        if (context.userId() <= 0) {
            throw new BusinessException(com.eaio.common.api.ErrorCode.UNAUTHORIZED, "未登录用户不能标记已读");
        }
        if (!store.visibleTo(row.getId(), context.userId(), context.orgId(), Instant.now())) {
            throw new BusinessException(PlatformErrorCode.NOTICE_NOT_FOUND, "公告不存在或当前用户不可见：id=" + id);
        }
        store.insertReadIgnore(ids.nextId(), row.getId(), context.userId());
    }

    private void validate(NoticePublishCmd cmd, boolean preserveTargets) {
        String scope = normalize(cmd.scopeType());
        if (!List.of("ALL", "ORG", "USER").contains(scope)) {
            throw new BusinessException(PlatformErrorCode.NOTICE_PUBLISH_INVALID, "公告范围非法：" + cmd.scopeType());
        }
        if (!"ALL".equals(scope) && !preserveTargets && (cmd.targetIds() == null || cmd.targetIds().isEmpty()
                || !cmd.targetIds().stream().allMatch(id -> id != null && id > 0)
                || !scope.equals(normalize(cmd.targetType())))) {
            throw new BusinessException(PlatformErrorCode.NOTICE_PUBLISH_INVALID, "公告收件目标不能为空且必须匹配范围");
        }
        Instant publish = cmd.publishTime() == null ? Instant.now() : cmd.publishTime();
        if (cmd.expireTime() != null && !cmd.expireTime().isAfter(publish)) {
            throw new BusinessException(PlatformErrorCode.NOTICE_PUBLISH_INVALID, "公告过期时间必须晚于发布时间");
        }
    }

    /** 管理列表 DTO 不暴露目标明细，编辑时空目标表示保留当前收件范围，避免误清空。 */
    private boolean preserveTargets(Notice existing, NoticePublishCmd cmd) {
        if (existing == null || cmd.targetIds() == null || !cmd.targetIds().isEmpty()) {
            return false;
        }
        String scope = normalize(cmd.scopeType());
        return !"ALL".equals(scope) && scope.equals(normalize(existing.getScopeType()))
                && (cmd.targetType() == null || scope.equals(normalize(cmd.targetType())))
                && !store.targets(existing.getId()).isEmpty();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private void insertTargets(long noticeId, String scope, String type, List<Long> ids, long userId, Instant now) {
        if ("ALL".equals(scope)) {
            return;
        }
        String normalizedType = type.trim().toUpperCase();
        for (Long targetId : ids) {
            NoticeTarget target = new NoticeTarget();
            target.setId(this.ids.nextId());
            target.setNoticeId(noticeId);
            target.setTargetType(normalizedType);
            target.setTargetId(targetId);
            target.setCreatedAt(now);
            target.setCreatedBy(userId);
            store.insertTarget(target);
        }
    }

    private void publishEvent(Notice row) {
        events.publish(new NoticePublishedEvent(ids.nextStr(), Instant.now(), row.getId(), row.getScopeType(),
                row.getPublisherId()));
    }

    private Notice require(long id) {
        Notice row = store.rowById(id);
        if (row == null) {
            throw new BusinessException(PlatformErrorCode.NOTICE_NOT_FOUND, "公告不存在：id=" + id);
        }
        return row;
    }

    private static void requireVersion(Notice row, int version) {
        if (row.getVersion() == null || row.getVersion() != version) {
            throw new BusinessException(com.eaio.common.api.ErrorCode.DATA_CONFLICT, "公告已被他人修改");
        }
    }

    private NoticeDTO toDto(Notice row, boolean read) {
        return new NoticeDTO(row.getId(), row.getTitle(), row.getContent(), row.getScopeType(), row.getPublishStatus(),
                row.getPublishTime(), row.getExpireTime(), row.isTopFlag(), read, row.getPublisherId(),
                row.getVersion() == null ? 0 : row.getVersion());
    }

    private Context current() {
        OrgContextPort port = contexts.getIfAvailable();
        Optional<OrgContextPort.OrgContext> context = port == null ? Optional.empty() : port.current();
        return context.map(value -> new Context(value.orgId(), value.userId())).orElse(new Context(0, 0));
    }

    private record Context(long orgId, long userId) {
    }
}
