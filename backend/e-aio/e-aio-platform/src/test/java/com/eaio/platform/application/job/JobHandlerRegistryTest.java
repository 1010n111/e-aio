package com.eaio.platform.application.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.eaio.common.exception.BusinessException;
import com.eaio.platform.api.JobHandler;
import com.eaio.platform.api.dto.JobContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link JobHandlerRegistry} 的单测（P1 册 3.4.2/3.4.8）：重复 code 启动失败、未命中抛 20023。
 *
 * <p>这两个断言是"可执行点只能来自代码"这条安全边界的守门人：注册表一旦允许重复 code，
 * "跑的是哪一个"就取决于容器顺序；一旦未命中返回 null 而不是 20023，调用方就会在别处 NPE。
 */
class JobHandlerRegistryTest {

    @Test
    @DisplayName("注册两个同 code 的 handler → 启动期 IllegalStateException（消息含 code）")
    void duplicateCodeFailsFast() {
        JobHandler first = handler("platform.job.log.clean");
        JobHandler second = handler("platform.job.log.clean");

        assertThatThrownBy(() -> new JobHandlerRegistry(List.of(first, second)))
                .as("3.4.8 的断言：重复 code 必须在启动期暴露")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("platform.job.log.clean")
                .hasMessageContaining("重复");
    }

    @Test
    @DisplayName("code 为空/null 的 handler 也算配置错误（否则会注册出一个谁都调不到的键）")
    void blankCodeFailsFast() {
        assertThatThrownBy(() -> new JobHandlerRegistry(List.of(handler("  "))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("code() 为空");
    }

    @Test
    @DisplayName("require 未命中 → 20023；find/contains 给调用方跳过坏任务的机会（启动期只跳过自己）")
    void requireThrows20023WhenMissing() {
        JobHandlerRegistry registry = new JobHandlerRegistry(List.of(handler("it.job.a")));

        assertThatThrownBy(() -> registry.require("no.such.handler"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(20023))
                .hasMessageContaining("no.such.handler");
        assertThat(registry.find("no.such.handler")).isEmpty();
        assertThat(registry.contains("no.such.handler")).isFalse();
        assertThat(registry.contains("it.job.a")).isTrue();
        assertThat(registry.require("it.job.a").code()).isEqualTo("it.job.a");
        assertThat(registry.codes()).containsExactly("it.job.a");
    }

    @Test
    @DisplayName("空注册表合法：无库无任务的空应用必须能启动（P0 口径），不是错误")
    void emptyRegistryIsAllowed() {
        JobHandlerRegistry registry = new JobHandlerRegistry(List.of());

        assertThat(registry.codes()).isEmpty();
        assertThat(registry.contains("anything")).isFalse();
    }

    @Test
    @DisplayName("code 两侧空白被裁剪后再比对（DB 里手填的 handler_code 常见尾随空格）")
    void trimsCode() {
        JobHandlerRegistry registry = new JobHandlerRegistry(List.of(handler(" it.job.b ")));

        assertThat(registry.contains("it.job.b")).isTrue();
        assertThat(registry.contains(" it.job.b ")).as("两侧空白都不影响命中").isTrue();
    }

    private static JobHandler handler(String code) {
        return new JobHandler() {
            @Override
            public String code() {
                return code;
            }

            @Override
            public void execute(JobContext ctx) {
                // 注册表测试不执行处理点
            }
        };
    }
}
