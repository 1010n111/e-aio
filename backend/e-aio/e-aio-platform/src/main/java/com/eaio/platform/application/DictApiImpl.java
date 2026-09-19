package com.eaio.platform.application;

import java.util.List;

import com.eaio.common.api.PageResult;
import com.eaio.platform.api.DictApi;
import com.eaio.platform.api.dto.DictItemDTO;
import com.eaio.platform.api.dto.DictItemSaveCmd;
import com.eaio.platform.api.dto.DictTypeDTO;
import com.eaio.platform.api.dto.DictTypeQuery;
import com.eaio.platform.api.dto.DictTypeSaveCmd;
import org.springframework.stereotype.Component;

/**
 * {@link DictApi} 的跨模块实现（P1 册 2.4.3：**薄壳**，全部委托 {@link DictAppService}）。
 *
 * <p>薄是有意的：REST 与跨模块入口共用同一套校验、事件与失效；实现类里一旦长出逻辑，两条入口
 * 就会各长一份（与 {@code ParamApiImpl} 同款口径）。放 {@code application} 层（裁决 P1-C1/P1-C2）。
 */
@Component
public class DictApiImpl implements DictApi {

    private final DictAppService service;

    public DictApiImpl(DictAppService service) {
        this.service = service;
    }

    @Override
    public List<DictItemDTO> getItems(String typeCode) {
        return service.getItems(typeCode);
    }

    @Override
    public String getLabel(String typeCode, String value) {
        return service.getLabel(typeCode, value);
    }

    @Override
    public PageResult<DictTypeDTO> getPage(DictTypeQuery query) {
        return service.pageTypes(query);
    }

    @Override
    public DictTypeDTO add(DictTypeSaveCmd cmd) {
        return service.addType(cmd);
    }

    @Override
    public DictTypeDTO up(DictTypeSaveCmd cmd) {
        return service.upType(cmd);
    }

    @Override
    public void del(long id, int version) {
        service.delType(id, version);
    }

    @Override
    public void refresh(String typeCode) {
        service.refresh(typeCode);
    }

    @Override
    public DictItemDTO addItem(DictItemSaveCmd cmd) {
        return service.addItem(cmd);
    }

    @Override
    public DictItemDTO upItem(DictItemSaveCmd cmd) {
        return service.upItem(cmd);
    }

    @Override
    public void delItem(long id, int version) {
        service.delItem(id, version);
    }
}
