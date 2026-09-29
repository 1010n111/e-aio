package com.eaio.platform.application;

import com.eaio.common.api.PageResult;
import com.eaio.platform.api.NoticeApi;
import com.eaio.platform.api.dto.NoticeDTO;
import com.eaio.platform.api.dto.NoticePublishCmd;
import com.eaio.platform.api.dto.NoticeQuery;
import java.util.List;
import org.springframework.stereotype.Component;

/** NoticeApi 跨模块薄适配器。 */
@Component
public class NoticeApiImpl implements NoticeApi {
    private final NoticeAppService service;

    public NoticeApiImpl(NoticeAppService service) {
        this.service = service;
    }

    @Override
    public long publish(NoticePublishCmd cmd) {
        return service.save(cmd).id();
    }
    @Override
    public List<NoticeDTO> getUnread() {
        return service.unread().getRecords();
    }
    @Override
    public void markRead(long noticeId) {
        service.markRead(noticeId);
    }
    @Override
    public PageResult<NoticeDTO> getPage(NoticeQuery query) {
        return service.page(query);
    }
}
