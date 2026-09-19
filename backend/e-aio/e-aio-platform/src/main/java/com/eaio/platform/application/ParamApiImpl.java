package com.eaio.platform.application;

import java.util.List;
import java.util.Map;

import com.eaio.platform.api.ParamApi;
import com.eaio.platform.api.dto.ParamDTO;
import com.eaio.platform.api.dto.ParamSaveCmd;
import com.eaio.platform.application.param.ResolvedParam;
import com.eaio.platform.domain.param.ParamValueCodec;
import org.springframework.stereotype.Component;

/**
 * {@link ParamApi} 的跨模块实现（P1 册 2.4.3：**薄壳**，1–3 行，全部委托 {@link ParamAppService}）。
 *
 * <p>薄是有意的：REST 与跨模块入口共用同一套校验、留痕、事件与失效；实现类里一旦长出逻辑，
 * 两条入口就会各长一份，"REST 改了、跨模块没改"是最难查的一类不一致。
 *
 * <p>放 {@code application} 层（裁决 P1-C1/P1-C2：{@code api} 包不放实现）。
 */
@Component
public class ParamApiImpl implements ParamApi {

    private final ParamAppService service;

    public ParamApiImpl(ParamAppService service) {
        this.service = service;
    }

    @Override
    public ParamDTO get(String key) {
        return service.get(key);
    }

    @Override
    public int getInt(String key, int defaultValue) {
        ResolvedParam resolved = service.resolveOrDefault(key, String.valueOf(defaultValue));
        return ParamValueCodec.toInt(resolved.value(), key);
    }

    @Override
    public boolean getBool(String key, boolean defaultValue) {
        ResolvedParam resolved = service.resolveOrDefault(key, String.valueOf(defaultValue));
        return ParamValueCodec.toBool(resolved.value(), key);
    }

    @Override
    public String getString(String key, String defaultValue) {
        return service.resolveOrDefault(key, defaultValue).value();
    }

    @Override
    public List<ParamDTO> listByGroup(String paramGroup) {
        return service.listRowsByGroup(paramGroup);
    }

    @Override
    public Map<String, ParamDTO> getAll() {
        return service.getAll();
    }

    @Override
    public ParamDTO set(ParamSaveCmd cmd) {
        return service.setFromModule(cmd);
    }

    @Override
    public void refresh(String key) {
        service.refresh(key);
    }
}
