package com.eaio.platform.api;

import com.eaio.common.api.PageResult;
import com.eaio.platform.api.dto.NoticeDTO;
import com.eaio.platform.api.dto.NoticePublishCmd;
import com.eaio.platform.api.dto.NoticeQuery;
import java.util.List;

/** 公告与站内消息的跨模块契约。 */
public interface NoticeApi {

    long publish(NoticePublishCmd cmd);

    List<NoticeDTO> getUnread();

    void markRead(long noticeId);

    PageResult<NoticeDTO> getPage(NoticeQuery query);
}
