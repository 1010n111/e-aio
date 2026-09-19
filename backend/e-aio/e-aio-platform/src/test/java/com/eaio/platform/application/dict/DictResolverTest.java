package com.eaio.platform.application.dict;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;

import com.eaio.common.exception.BusinessException;
import com.eaio.platform.domain.dict.DictItem;
import com.eaio.platform.domain.dict.DictType;
import com.eaio.platform.infrastructure.cache.DictL2Cache;
import com.eaio.platform.infrastructure.persistence.DictStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * {@link DictResolver} 的单测（P1 册 3.2.6 的前两条测试点）。
 *
 * <p>用 mock 的 {@link DictStore} 是因为要断言的正是"回源次数"——真实库里数不出"查了几次"，
 * 集成测试里只能靠"直连改库读到旧值"间接证明（见 {@code DictCacheIT}）。
 */
class DictResolverTest {

    private final DictStore store = mock(DictStore.class);
    private final DictL2Cache l2 = mock(DictL2Cache.class);

    private DictResolver resolver;

    @BeforeEach
    void setUp() {
        given(l2.get(anyString())).willReturn(Optional.empty());
        given(store.rowByTypeCode(anyString())).willReturn(type("it_status"));
        given(store.itemsByTypeCode(anyString())).willReturn(List.of(item("RUNNING", "执行中", "ENABLED"),
                item("DONE", "已完成", "ENABLED")));
        resolver = new DictResolver(store, l2, new MockEnvironment());
    }

    @Test
    @DisplayName("两次 getItems 同 typeCode：DB 只查 1 次（第二次命中 L1）")
    void secondReadHitsL1() {
        assertThat(resolver.enabledItems("it_status")).hasSize(2);

        assertThat(resolver.enabledItems("it_status")).hasSize(2);

        verify(store, times(1)).itemsByTypeCode("it_status");
        verify(l2, times(1)).put(anyString(), any());
    }

    @Test
    @DisplayName("L1 关掉时每次回源（配置开关 eaio.cache.local.enabled 生效）")
    void disabledL1AlwaysGoesToDatabase() {
        MockEnvironment environment = new MockEnvironment().withProperty("eaio.cache.local.enabled", "false");
        DictResolver noL1 = new DictResolver(store, l2, environment);

        noL1.enabledItems("it_status");
        noL1.enabledItems("it_status");

        verify(store, times(2)).itemsByTypeCode("it_status");
    }

    @Test
    @DisplayName("停用项语义：getItems 不含它，getLabel 仍能解析它（3.2.5「停用 ≠ 不存在」）")
    void disabledItemIsHiddenFromItemsButResolvableByLabel() {
        given(store.itemsByTypeCode(anyString())).willReturn(List.of(item("RUNNING", "执行中", "ENABLED"),
                item("OLD", "旧值", "DISABLED")));

        assertThat(resolver.enabledItems("it_status")).as("getItems 不含停用项")
                .extracting(DictItem::getItemValue).containsExactly("RUNNING");
        assertThat(resolver.getLabel("it_status", "OLD")).as("停用项的历史值仍显示标签").isEqualTo("旧值");
    }

    @Test
    @DisplayName("getLabel 未命中：返回入参原值且不抛异常（3.2.1 测试点）")
    void labelMissReturnsOriginalValue() {
        assertThatCode(() -> resolver.getLabel("it_status", "NOPE")).doesNotThrowAnyException();
        assertThat(resolver.getLabel("it_status", "NOPE")).isEqualTo("NOPE");
        assertThat(resolver.getLabel("it_status", null)).isNull();
    }

    @Test
    @DisplayName("getLabel 遇不存在的 typeCode：同样返回原值 + WARN，不抛 20003（不阻断列表页渲染）")
    void labelOnUnknownTypeReturnsOriginalValue() {
        given(store.rowByTypeCode("nope")).willReturn(null);

        assertThat(resolver.getLabel("nope", "X")).isEqualTo("X");
    }

    @Test
    @DisplayName("getItems 遇不存在的 typeCode：抛 20003（配置错误必须显式暴露，3.2.5）")
    void itemsOnUnknownTypeThrows20003() {
        given(store.rowByTypeCode("nope")).willReturn(null);

        assertThatThrownBy(() -> resolver.enabledItems("nope"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(20003));
    }

    @Test
    @DisplayName("类型不存在时不写缓存（不缓存空值：脏 typeCode 不该被藏 60s）")
    void unknownTypeIsNotCached() {
        given(store.rowByTypeCode("nope")).willReturn(null);

        assertThatThrownBy(() -> resolver.items("nope")).isInstanceOf(BusinessException.class);

        verify(l2, never()).put(anyString(), any());
    }

    @Test
    @DisplayName("失效：L1 被清（下次回源）+ L2 该键被删")
    void invalidateClearsBothLayers() {
        resolver.enabledItems("it_status");

        resolver.invalidate("it_status");

        verify(l2).evict("it_status");
        resolver.enabledItems("it_status");
        verify(store, times(2)).itemsByTypeCode("it_status");
    }

    @Test
    @DisplayName("L2 命中：不回源 DB，且结果进入 L1")
    void l2HitSkipsDatabase() {
        given(store.rowByTypeCode("it_status")).willReturn(null);
        given(l2.get("it_status")).willReturn(Optional.of(List.of(item("S1", "一", "ENABLED"))));

        assertThat(resolver.enabledItems("it_status")).hasSize(1);
        verify(store, never()).itemsByTypeCode(anyString());
    }

    private static DictType type(String typeCode) {
        DictType type = new DictType();
        type.setTypeCode(typeCode);
        type.setTypeName("测试类型");
        type.setStatus("ENABLED");
        return type;
    }

    private static DictItem item(String value, String label, String status) {
        DictItem item = new DictItem();
        item.setTypeCode("it_status");
        item.setItemValue(value);
        item.setItemLabel(label);
        item.setStatus(status);
        item.setSortNo(10);
        return item;
    }
}
