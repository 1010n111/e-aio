package com.eaio.platform.application.notice;

import com.eaio.platform.api.JobHandler;
import com.eaio.platform.api.dto.JobContext;
import com.eaio.platform.application.NoticeAppService;
import org.springframework.stereotype.Component;

/** 到点发布公告草稿。 */
@Component
public class NoticePublishScanHandler implements JobHandler {
    public static final String CODE = "platform.notice.publish.scan";
    private final NoticeAppService service;

    public NoticePublishScanHandler(NoticeAppService service) {
        this.service = service;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public void execute(JobContext ctx) {
        service.publishDue();
    }
}
