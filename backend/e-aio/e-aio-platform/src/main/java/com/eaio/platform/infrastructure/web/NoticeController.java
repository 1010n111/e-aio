package com.eaio.platform.infrastructure.web;

import com.eaio.common.api.PageResult;
import com.eaio.platform.api.dto.NoticeDTO;
import com.eaio.platform.api.dto.NoticeDeleteCmd;
import com.eaio.platform.api.dto.NoticeIdCmd;
import com.eaio.platform.api.dto.NoticePublishCmd;
import com.eaio.platform.api.dto.NoticeQuery;
import com.eaio.platform.application.NoticeAppService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 公告 REST 入口，正文始终按纯文本返回。 */
@RestController
public class NoticeController {
    private final NoticeAppService service;

    public NoticeController(NoticeAppService service) {
        this.service = service;
    }
    @PostMapping("/platform/notice/GetPage")
    @PreAuthorize("hasAuthority('platform:notice:list')")
    public PageResult<NoticeDTO> getPage(@RequestBody(required = false) NoticeQuery query) {
        return service.page(query);
    }
    @PostMapping("/platform/notice/Get")
    @PreAuthorize("hasAuthority('platform:notice:get')")
    public NoticeDTO get(@Valid @RequestBody NoticeIdCmd cmd) {
        return service.get(cmd.id());
    }
    @PostMapping("/platform/notice/GetUnread")
    public PageResult<NoticeDTO> getUnread() {
        return service.unread();
    }
    @PostMapping("/platform/notice/Add")
    @PreAuthorize("hasAuthority('platform:notice:add')")
    public NoticeDTO add(@Valid @RequestBody NoticePublishCmd cmd) {
        return service.save(cmd);
    }
    @PostMapping("/platform/notice/Up")
    @PreAuthorize("hasAuthority('platform:notice:up')")
    public NoticeDTO up(@Valid @RequestBody NoticePublishCmd cmd) {
        return service.save(cmd);
    }
    @PostMapping("/platform/notice/Publish")
    @PreAuthorize("hasAuthority('platform:notice:publish')")
    public NoticeDTO publish(@Valid @RequestBody NoticeIdCmd cmd) {
        return service.publish(cmd.id(), null);
    }
    @PostMapping("/platform/notice/Revoke")
    @PreAuthorize("hasAuthority('platform:notice:publish')")
    public void revoke(@Valid @RequestBody NoticeIdCmd cmd) {
        service.revoke(cmd.id());
    }
    @PostMapping("/platform/notice/Del")
    @PreAuthorize("hasAuthority('platform:notice:del')")
    public void del(@Valid @RequestBody NoticeDeleteCmd cmd) {
        service.delete(cmd.id(), cmd.version());
    }
    @PostMapping("/platform/notice/MarkRead")
    public void markRead(@Valid @RequestBody NoticeIdCmd cmd) {
        service.markRead(cmd.id());
    }
}
