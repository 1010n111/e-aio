package com.eaio.platform.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.eaio.common.api.ErrorCode;
import com.eaio.common.api.PageResult;
import com.eaio.common.exception.BusinessException;
import com.eaio.common.id.IdGenerator;
import com.eaio.common.json.JsonUtils;
import com.eaio.platform.api.NotifyTemplateApi;
import com.eaio.platform.api.PlatformErrorCode;
import com.eaio.platform.api.dto.NotifyTemplateDTO;
import com.eaio.platform.api.dto.NotifyTemplateQuery;
import com.eaio.platform.api.dto.NotifyTemplateSaveCmd;
import com.eaio.platform.domain.notice.NotifyTemplate;
import com.eaio.platform.domain.notice.TemplateRenderer;
import com.eaio.platform.infrastructure.persistence.NotifyTemplateStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 通知模板保存与纯文本渲染。 */
@Service
public class NotifyTemplateAppService {

    private final NotifyTemplateStore store;
    private final IdGenerator ids;
    private final TemplateRenderer renderer;

    public NotifyTemplateAppService(NotifyTemplateStore store, IdGenerator ids, TemplateRenderer renderer) {
        this.store = store;
        this.ids = ids;
        this.renderer = renderer;
    }

    @Transactional
    public NotifyTemplateDTO save(NotifyTemplateSaveCmd cmd) {
        String code = cmd.templateCode().trim();
        String channel = cmd.channel().trim().toUpperCase();
        if (!List.of("SITE", "EMAIL", "SMS").contains(channel)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "通知渠道非法：" + channel);
        }
        String status = cmd.status() == null || cmd.status().isBlank() ? "ENABLED"
                : cmd.status().trim().toUpperCase();
        if (!List.of("ENABLED", "DISABLED").contains(status)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "通知模板状态非法：" + status);
        }
        List<String> variables = cleanVariables(cmd.variables());
        renderer.validateDeclarations(cmd.titleTemplate(), cmd.contentTemplate(), variables);
        NotifyTemplate row = store.rowByCode(code);
        boolean isNew = row == null;
        if (isNew) {
            row = new NotifyTemplate();
            row.setId(ids.nextId());
            row.setCreatedAt(Instant.now());
            row.setCreatedBy(0L);
            row.setVersion(0);
            row.setDeleted(false);
        } else if (cmd.version() == null || row.getVersion() == null || !row.getVersion().equals(cmd.version())) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "通知模板已被他人修改");
        }
        row.setTemplateCode(code);
        row.setTemplateName(cmd.templateName().trim());
        row.setChannel(channel);
        row.setTitleTemplate(cmd.titleTemplate());
        row.setContentTemplate(cmd.contentTemplate());
        Map<String, Boolean> declarations = new LinkedHashMap<>();
        variables.forEach(variable -> declarations.put(variable, true));
        row.setVariablesJson(variables.isEmpty() ? null : JsonUtils.toJson(declarations));
        row.setStatus(status);
        row.setRemark(cmd.remark());
        row.setUpdatedAt(Instant.now());
        row.setUpdatedBy(0L);
        if (isNew) {
            try {
                store.insert(row);
            } catch (RuntimeException ex) {
                throw new BusinessException(PlatformErrorCode.NOTIFY_TEMPLATE_CODE_DUPLICATED,
                        "模板编码已存在：" + code);
            }
        } else {
            if (store.updateById(row) == 0) {
                throw new BusinessException(ErrorCode.DATA_CONFLICT, "通知模板已被他人修改");
            }
        }
        return toDto(row);
    }

    public NotifyTemplateApi.RenderedTemplate render(String code, Map<String, String> params) {
        NotifyTemplate row = require(code);
        List<String> variables = variables(row);
        TemplateRenderer.Rendered rendered = renderer.render(row.getTitleTemplate(), row.getContentTemplate(), variables,
                params);
        return new NotifyTemplateApi.RenderedTemplate(rendered.title(), rendered.content());
    }

    public NotifyTemplateDTO get(String code) {
        return toDto(require(code));
    }
    public PageResult<NotifyTemplateDTO> page(NotifyTemplateQuery query) {
        IPage<NotifyTemplate> page = store.page(query);
        return PageResult.from(page.getCurrent(), page.getSize(), page.getTotal(),
                page.getRecords().stream().map(this::toDto).toList());
    }

    @Transactional
    public void delete(String code, int version) {
        NotifyTemplate row = require(code);
        if (row.isBuiltin()) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "内置通知模板不可删除");
        }
        if (row.getVersion() == null || row.getVersion() != version) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "通知模板已被他人修改");
        }
        store.deleteById(row);
    }

    private NotifyTemplate require(String code) {
        NotifyTemplate row = store.rowByCode(code.trim());
        if (row == null) {
            throw new BusinessException(PlatformErrorCode.NOTIFY_TEMPLATE_NOT_FOUND, "通知模板不存在：" + code);
        }
        return row;
    }

    private NotifyTemplateDTO toDto(NotifyTemplate row) {
        return new NotifyTemplateDTO(row.getId(), row.getTemplateCode(), row.getTemplateName(), row.getChannel(),
                row.getTitleTemplate(), row.getContentTemplate(), variables(row), row.getStatus(), row.isBuiltin(),
                row.getVersion() == null ? 0 : row.getVersion());
    }

    private static List<String> cleanVariables(List<String> input) {
        if (input == null) {
            return List.of();
        }
        return input.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
    }

    private static List<String> variables(NotifyTemplate row) {
        if (row.getVariablesJson() == null || row.getVariablesJson().isBlank()) {
            return List.of();
        }
        try {
            return new ArrayList<>(JsonUtils.toMap(row.getVariablesJson()).keySet());
        } catch (RuntimeException ex) {
            return List.of();
        }
    }
}
