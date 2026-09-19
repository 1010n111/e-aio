package com.eaio.platform.domain.param;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 分级覆盖的纯函数测试（P1 册 3.1.2：{@code ParamOverlayResolver} 无 IO，规则在这里穷举）。 */
class ParamOverlayResolverTest {

    private static final long ORG = 1001L;
    private static final long USER = 2001L;

    private static ParamItem row(ParamLevel level, long ownerId, String value) {
        ParamItem item = new ParamItem();
        item.setParamKey("platform.file.max-size");
        item.setParamLevel(level.name());
        item.setOwnerId(ownerId);
        item.setParamValue(value);
        item.setValueType("INT");
        item.setHotReload(true);
        return item;
    }

    @Test
    @DisplayName("只有系统级：系统级生效")
    void systemOnly() {
        List<ParamItem> rows = List.of(row(ParamLevel.SYSTEM, 0L, "sys"));

        assertThat(ParamOverlayResolver.effective(rows, new ParamContext(ORG, USER)))
                .get().extracting(ParamItem::getParamValue).isEqualTo("sys");
    }

    @Test
    @DisplayName("组织级覆盖系统级")
    void orgOverridesSystem() {
        List<ParamItem> rows = List.of(row(ParamLevel.SYSTEM, 0L, "sys"), row(ParamLevel.ORG, ORG, "org"));

        assertThat(ParamOverlayResolver.effective(rows, new ParamContext(ORG, 0L)))
                .get().extracting(ParamItem::getParamValue).isEqualTo("org");
        assertThat(ParamOverlayResolver.effective(rows, new ParamContext(ORG, USER)))
                .get().extracting(ParamItem::getParamLevel).isEqualTo("ORG");
    }

    @Test
    @DisplayName("用户级覆盖组织级与系统级")
    void userOverridesAll() {
        List<ParamItem> rows = List.of(row(ParamLevel.SYSTEM, 0L, "sys"), row(ParamLevel.ORG, ORG, "org"),
                row(ParamLevel.USER, USER, "user"));

        assertThat(ParamOverlayResolver.effective(rows, new ParamContext(ORG, USER)))
                .get().extracting(ParamItem::getParamValue).isEqualTo("user");
        assertThat(ParamOverlayResolver.effectiveLevel(rows, new ParamContext(ORG, USER))).isEqualTo("USER");
    }

    @Test
    @DisplayName("候选顺序不影响结果（乱序输入同样取最高优先级）")
    void orderDoesNotMatter() {
        List<ParamItem> rows = List.of(row(ParamLevel.USER, USER, "user"), row(ParamLevel.SYSTEM, 0L, "sys"),
                row(ParamLevel.ORG, ORG, "org"));

        assertThat(ParamOverlayResolver.effective(rows, new ParamContext(ORG, USER)))
                .get().extracting(ParamItem::getParamValue).isEqualTo("user");
    }

    @Test
    @DisplayName("其他组织/其他用户的行不参与覆盖（越权读到别人的值是最严重的一类缺陷）")
    void otherOwnerRowsAreIgnored() {
        List<ParamItem> rows = List.of(row(ParamLevel.SYSTEM, 0L, "sys"), row(ParamLevel.ORG, ORG + 1, "other-org"),
                row(ParamLevel.USER, USER + 1, "other-user"));

        assertThat(ParamOverlayResolver.effective(rows, new ParamContext(ORG, USER)))
                .get().extracting(ParamItem::getParamValue).isEqualTo("sys");
        assertThat(ParamOverlayResolver.effectiveLevel(rows, new ParamContext(ORG, USER))).isEqualTo("SYSTEM");
    }

    @Test
    @DisplayName("无用户上下文时不认用户级行；无组织上下文时不认组织级行")
    void contextAbsenceMeansNoOverride() {
        List<ParamItem> rows = List.of(row(ParamLevel.SYSTEM, 0L, "sys"), row(ParamLevel.ORG, ORG, "org"),
                row(ParamLevel.USER, USER, "user"));

        assertThat(ParamOverlayResolver.effective(rows, ParamContext.systemOnly()))
                .get().extracting(ParamItem::getParamValue).isEqualTo("sys");
        assertThat(ParamOverlayResolver.effective(rows, new ParamContext(ORG, 0L)))
                .get().extracting(ParamItem::getParamValue).isEqualTo("org");
    }

    @Test
    @DisplayName("没有候选行 / 候选为空：返回空（键未定义，不写空值占位）")
    void emptyCandidates() {
        assertThat(ParamOverlayResolver.effective(List.of(), new ParamContext(ORG, USER))).isEmpty();
        assertThat(ParamOverlayResolver.effective(null, new ParamContext(ORG, USER))).isEmpty();
        assertThat(ParamOverlayResolver.effectiveLevel(List.of(), new ParamContext(ORG, USER))).isNull();
    }
}
