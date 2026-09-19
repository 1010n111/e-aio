package com.eaio.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.eaio.common.exception.BusinessException;
import com.eaio.common.id.IdGenerator;
import com.eaio.platform.api.dto.DictItemSaveCmd;
import com.eaio.platform.api.dto.DictTypeSaveCmd;
import com.eaio.platform.application.dict.DictDtoMapperImpl;
import com.eaio.platform.application.dict.DictResolver;
import com.eaio.platform.application.param.ParamContextProvider;
import com.eaio.platform.domain.dict.DictItem;
import com.eaio.platform.domain.dict.DictType;
import com.eaio.platform.domain.param.ParamContext;
import com.eaio.platform.events.DictChangedEvent;
import com.eaio.platform.infrastructure.persistence.DictStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * {@link DictAppService} 写路径的单测（P1 册 3.2.6 的后四条测试点 + 段内错误码口径）。
 *
 * <p>DTO 映射用**真实**的生成实现（{@code DictDtoMapperImpl}）：它是纯函数，mock 掉只会让"字段漏映射"
 * 这类问题跑到集成测试才暴露。
 */
class DictAppServiceTest {

    private final DictStore store = mock(DictStore.class);
    private final DictResolver resolver = mock(DictResolver.class);
    private final ParamContextProvider contexts = mock(ParamContextProvider.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final IdGenerator idGenerator = mock(IdGenerator.class);

    private DictAppService service;

    @BeforeEach
    void setUp() {
        given(contexts.current()).willReturn(ParamContext.systemOnly());
        given(idGenerator.nextId()).willReturn(1001L);
        given(idGenerator.nextStr()).willReturn("evt-1");
        given(store.updateById(any())).willReturn(1);
        given(store.updateItemById(any())).willReturn(1);
        service = new DictAppService(store, resolver, new DictDtoMapperImpl(), contexts, events, idGenerator);
    }

    @Test
    @DisplayName("新增重复 item_value：抛 20007（3.2.6）")
    void duplicateItemValueThrows20007() {
        given(store.rowByTypeCode("it_status")).willReturn(type("it_status", false));
        given(store.rowByTypeValue("it_status", "RUNNING")).willReturn(item("RUNNING"));

        assertThatThrownBy(() -> service.addItem(cmd("it_status", "RUNNING")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(20007));
        verify(store, never()).insertItem(any());
    }

    @Test
    @DisplayName("删除有项的类型：抛 20008（3.2.6）")
    void deleteTypeWithItemsThrows20008() {
        given(store.rowById(1L)).willReturn(type("it_status", false));
        given(store.countItems("it_status")).willReturn(3L);

        assertThatThrownBy(() -> service.delType(1L, 0))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(20008));
        verify(store, never()).deleteById(any());
    }

    @Test
    @DisplayName("内置类型也先报 20008（「在用」比「内置」更可执行：先删/停用项）")
    void builtinTypeWithItemsReportsInUseFirst() {
        given(store.rowById(21L)).willReturn(type("platform_param_level", true));
        given(store.countItems("platform_param_level")).willReturn(3L);

        assertThatThrownBy(() -> service.delType(21L, 0))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(20008));
    }

    @Test
    @DisplayName("内置类型无项时抛 20005（段内唯一的内置只读码，跨能力复用）")
    void builtinTypeWithoutItemsThrows20005() {
        given(store.rowById(21L)).willReturn(type("platform_param_level", true));
        given(store.countItems("platform_param_level")).willReturn(0L);

        assertThatThrownBy(() -> service.delType(21L, 0))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(20005));
        verify(store, never()).deleteById(any());
    }

    @Test
    @DisplayName("内置类型下的项不可删（票面验收「内置类型/项不可删」）")
    void deleteItemOfBuiltinTypeThrows20005() {
        given(store.rowItemById(31L)).willReturn(item("SYSTEM"));
        given(store.rowByTypeCode("it_status")).willReturn(type("it_status", true));

        assertThatThrownBy(() -> service.delItem(31L, 0))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(20005));
        verify(store, never()).deleteItemById(any());
    }

    @Test
    @DisplayName("改项 label：发 DictChangedEvent（eventId 在前）+ 提交后由监听器失效缓存（3.2.6）")
    void updateItemPublishesEvent() {
        given(store.rowByTypeValue("it_status", "RUNNING")).willReturn(item("RUNNING"));

        service.upItem(cmd("it_status", "RUNNING"));

        ArgumentCaptor<DictChangedEvent> captor = ArgumentCaptor.forClass(DictChangedEvent.class);
        verify(events).publishEvent(captor.capture());
        DictChangedEvent event = captor.getValue();
        assertThat(event.eventId()).isEqualTo("evt-1");
        assertThat(event.occurredAt()).isNotNull();
        assertThat(event.typeCode()).isEqualTo("it_status");
        assertThat(event.itemValue()).isEqualTo("RUNNING");
        assertThat(event.action()).isEqualTo("UP");
        assertThat(event.operatorId()).isZero();
    }

    @Test
    @DisplayName("失效在事件监听器里发生（提交后），服务本身不直接动缓存")
    void invalidationHappensInListenerNotInService() {
        given(store.rowByTypeValue("it_status", "RUNNING")).willReturn(item("RUNNING"));

        service.upItem(cmd("it_status", "RUNNING"));

        verify(resolver, never()).invalidate(anyString());
    }

    @Test
    @DisplayName("新增类型编码重复：抛 20003（5.2 给该端点的唯一业务码）")
    void duplicateTypeCodeThrows20003() {
        given(store.rowByTypeCode("it_status")).willReturn(type("it_status", false));

        assertThatThrownBy(() -> service.addType(new DictTypeSaveCmd("it_status", "状态", "ENABLED", null, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(20003));
    }

    @Test
    @DisplayName("改类型/项缺 version：10003（乐观锁，5.3）")
    void updateWithoutVersionThrows10003() {
        assertThatThrownBy(() -> service.upType(new DictTypeSaveCmd("it_status", "状态", "ENABLED", null, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(10003));
        assertThatThrownBy(() -> service.upItem(new DictItemSaveCmd("it_status", "RUNNING", "执行中",
                10, "ENABLED", false, null, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(10003));
    }

    @Test
    @DisplayName("version 过期：10003，且不落库")
    void staleVersionThrows10003() {
        DictType existing = type("it_status", false);
        existing.setVersion(3);
        given(store.rowByTypeCode("it_status")).willReturn(existing);
        given(store.updateById(any())).willReturn(0);

        assertThatThrownBy(() -> service.upType(new DictTypeSaveCmd("it_status", "状态", "ENABLED", null, 1)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(10003));
    }

    @Test
    @DisplayName("项落到不存在的类型：20003")
    void addItemOnUnknownTypeThrows20003() {
        given(store.rowByTypeCode("nope")).willReturn(null);

        assertThatThrownBy(() -> service.addItem(cmd("nope", "V")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(20003));
    }

    @Test
    @DisplayName("非法 status：10000（不让 PG 的 CHECK 约束错误泄漏成 10500）")
    void invalidStatusThrows10000() {
        assertThatThrownBy(() -> service.addType(new DictTypeSaveCmd("it_status", "状态", "PAUSED", null, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(10000));
    }

    @Test
    @DisplayName("extJson / isDefault / sortNo 完整落到实体（JSONB 列不是摆设）")
    void itemFieldsAreCarriedToEntity() {
        given(store.rowByTypeCode("it_status")).willReturn(type("it_status", false));
        ArgumentCaptor<DictItem> captor = ArgumentCaptor.forClass(DictItem.class);

        service.addItem(new DictItemSaveCmd("it_status", "RUNNING", "执行中", 30, "ENABLED", true,
                "{\"color\":\"#409eff\"}", null));

        verify(store).insertItem(captor.capture());
        DictItem saved = captor.getValue();
        assertThat(saved.getExtJson()).isEqualTo("{\"color\":\"#409eff\"}");
        assertThat(saved.getIsDefault()).isTrue();
        assertThat(saved.getSortNo()).isEqualTo(30);
        assertThat(saved.getStatus()).isEqualTo("ENABLED");
        assertThat(saved.getId()).isEqualTo(1001L);
        assertThat(saved.getVersion()).isZero();
        assertThat(saved.getDeleted()).isFalse();
    }

    @Test
    @DisplayName("status 空值：Add 按 ENABLED；Up 保持原状态（不能悄悄把停用项启用回来）")
    void blankStatusFallsBackSensibly() {
        given(store.rowByTypeCode("it_status")).willReturn(type("it_status", false));
        ArgumentCaptor<DictItem> added = ArgumentCaptor.forClass(DictItem.class);

        service.addItem(new DictItemSaveCmd("it_status", "V", "标签", 0, null, null, null, null));
        verify(store).insertItem(added.capture());
        assertThat(added.getValue().getStatus()).as("Add 空状态按 DDL 默认值 ENABLED")
                .isEqualTo("ENABLED");

        DictItem disabled = item("RUNNING");
        disabled.setStatus("DISABLED");
        given(store.rowByTypeValue("it_status", "RUNNING")).willReturn(disabled);

        service.upItem(new DictItemSaveCmd("it_status", "RUNNING", "标签", 10, null, false, null, 0));

        assertThat(disabled.getStatus()).as("Up 空状态 = 保持原状态").isEqualTo("DISABLED");
    }

    private static DictItemSaveCmd cmd(String typeCode, String itemValue) {
        return new DictItemSaveCmd(typeCode, itemValue, "执行中", 10, "ENABLED", false, null, 0);
    }

    private static DictType type(String typeCode, boolean builtin) {
        DictType type = new DictType();
        type.setId(1L);
        type.setTypeCode(typeCode);
        type.setTypeName("状态");
        type.setStatus("ENABLED");
        type.setBuiltin(builtin);
        return type;
    }

    private static DictItem item(String itemValue) {
        DictItem item = new DictItem();
        item.setId(1001L);
        item.setTypeCode("it_status");
        item.setItemValue(itemValue);
        item.setItemLabel("执行中");
        item.setSortNo(10);
        item.setStatus("ENABLED");
        return item;
    }
}
