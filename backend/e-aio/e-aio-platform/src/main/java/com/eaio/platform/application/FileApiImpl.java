package com.eaio.platform.application;

import com.eaio.common.api.PageResult;
import com.eaio.platform.api.FileApi;
import com.eaio.platform.api.dto.FileBindCmd;
import com.eaio.platform.api.dto.FileDTO;
import com.eaio.platform.api.dto.FileDownloadCmd;
import com.eaio.platform.api.dto.FileQuery;
import com.eaio.platform.api.dto.FileUploadCmd;
import com.eaio.platform.api.dto.FileUrlDTO;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * {@link FileApi} 的跨模块实现（P1 册 2.4.3：**薄壳**，全部委托 {@link FileAppService}）。
 *
 * <p>薄是有意的：REST 与跨模块入口共用同一套校验、事件与留痕；实现类里一旦长出逻辑，两条入口就会各长一份。
 *
 * <p><b>权限口径</b>（5.4）：{@link #download(FileDownloadCmd)} **不判权限**（调用方已鉴权）；
 * {@link #getUrl(long, int)} 同样不判——它是 Java 面的"给我一个链接"，HTTP 面自己判完权限才会调它。
 */
@Component
public class FileApiImpl implements FileApi {

    private final FileAppService service;

    public FileApiImpl(FileAppService service) {
        this.service = service;
    }

    @Override
    public FileDTO upload(FileUploadCmd cmd) {
        return service.upload(cmd);
    }

    @Override
    public Resource download(FileDownloadCmd cmd) {
        return service.download(cmd);
    }

    @Override
    public FileUrlDTO getUrl(long fileId, int expireSeconds) {
        return service.getUrl(fileId, expireSeconds);
    }

    @Override
    public FileDTO getMeta(long fileId) {
        return service.getMeta(fileId);
    }

    @Override
    public void del(long fileId) {
        service.del(fileId);
    }

    @Override
    public void bind(FileBindCmd cmd) {
        service.bind(cmd);
    }

    @Override
    public PageResult<FileDTO> getPage(FileQuery query) {
        return service.page(query);
    }
}
