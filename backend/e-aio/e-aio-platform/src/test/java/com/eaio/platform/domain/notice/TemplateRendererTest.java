package com.eaio.platform.domain.notice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import com.eaio.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

class TemplateRendererTest {

    private final TemplateRenderer renderer = new TemplateRenderer();

    @Test
    void replacesDeclaredVariablesInTitleAndContent() {
        TemplateRenderer.Rendered rendered = renderer.render("你好 ${name}", "编号 ${id}",
                List.of("name", "id"), Map.of("name", "小明", "id", "A-1"));

        assertThat(rendered.title()).isEqualTo("你好 小明");
        assertThat(rendered.content()).isEqualTo("编号 A-1");
    }

    @Test
    void reportsMissingAndUndeclaredVariablesWithDifferentCodes() {
        assertThatThrownBy(() -> renderer.render(null, "你好 ${name}", List.of("name"), Map.of()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(20042));
        assertThatThrownBy(() -> renderer.render(null, "你好 ${name}", List.of("other"), Map.of("name", "小明")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(20043));
    }
}
