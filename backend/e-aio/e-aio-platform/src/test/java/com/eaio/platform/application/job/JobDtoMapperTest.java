package com.eaio.platform.application.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link JobDtoMapper} 的单测（P1 册 5.3 的 {@code params} 与 4.3.9 的 {@code params_json} JSONB 列）。
 */
class JobDtoMapperTest {

    private final JobDtoMapper mapper = new JobDtoMapper();

    @Test
    @DisplayName("参数往返：Map → JSONB 文本 → Map（保持键值）")
    void roundTrip() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("days", "90");
        params.put("batchSize", "1000");

        String json = mapper.encodeParams(params);
        assertThat(json).contains("\"days\":\"90\"").contains("\"batchSize\":\"1000\"");
        assertThat(mapper.decodeParams(json)).isEqualTo(params);
    }

    @Test
    @DisplayName("空参数写 null（不是 {}）：列可空，空对象是多余的状态")
    void emptyParamsEncodedAsNull() {
        assertThat(mapper.encodeParams(null)).isNull();
        assertThat(mapper.encodeParams(Map.of())).isNull();
        assertThat(mapper.decodeParams(null)).isEmpty();
        assertThat(mapper.decodeParams("   ")).isEmpty();
    }

    @Test
    @DisplayName("列里是合法 JSON 但值不是字符串（有人直接改库）：按字符串读，不抛 ClassCastException")
    void nonStringValuesAreCoerced() {
        assertThat(mapper.decodeParams("{\"days\":30,\"flag\":true}"))
                .containsEntry("days", "30")
                .containsEntry("flag", "true");
    }

    @Test
    @DisplayName("坏 JSON 不让任务列表打不开：按无参数处理（任务仍可被触发/编辑覆盖）")
    void brokenJsonFallsBackToEmpty() {
        assertThat(mapper.decodeParams("not-json")).isEmpty();
        assertThat(mapper.decodeParams("[1,2,3]")).as("JSON 数组不是参数对象").isEmpty();
    }
}
