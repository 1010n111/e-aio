package com.eaio.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 错误码分段契约（P0 册 3.2.3）：成功码唯一、通用段落在 10000–19999、数值不重复。
 *
 * <p>业务模块段的断言需要模块登记表，属 P1（T4 票已登记为待补规则）。
 */
class ErrorCodeTest {

    @Test
    @DisplayName("成功码为 0 且全枚举唯一")
    void successCodeIsZeroAndUnique() {
        assertThat(ErrorCode.SUCCESS_CODE).isZero();
        assertThat(ErrorCode.SUCCESS.getCode()).isZero();

        List<Integer> zeros = Arrays.stream(ErrorCode.values())
                .map(ErrorCode::getCode)
                .filter(code -> code == ErrorCode.SUCCESS_CODE)
                .toList();
        assertThat(zeros).containsExactly(ErrorCode.SUCCESS_CODE);
    }

    @Test
    @DisplayName("通用段错误码全部落在 10000–19999")
    void genericCodesStayInsideGenericSegment() {
        assertThat(genericCodes())
                .isNotEmpty()
                .allSatisfy(code -> assertThat(code)
                        .isBetween(ErrorCode.GENERIC_CODE_MIN, ErrorCode.GENERIC_CODE_MAX));
    }

    @Test
    @DisplayName("通用段不含业务段号码（业务段不被提前占用）")
    void genericCodesNeverReachBusinessSegment() {
        assertThat(genericCodes()).allSatisfy(code -> assertThat(code)
                .isLessThan(ErrorCode.BUSINESS_CODE_MIN));
        assertThat(ErrorCode.BUSINESS_CODE_MIN).isEqualTo(20000);
        assertThat(ErrorCode.MODULE_CODE_SEGMENT).isEqualTo(1000);
    }

    @Test
    @DisplayName("错误码数值唯一（新增时不得撞号）")
    void codesAreUnique() {
        Map<Integer, Long> duplicates = Arrays.stream(ErrorCode.values())
                .collect(Collectors.groupingBy(ErrorCode::getCode, Collectors.counting()))
                .entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(duplicates).isEmpty();
    }

    @Test
    @DisplayName("每个错误码都有非空中文消息")
    void everyCodeHasMessage() {
        assertThat(ErrorCode.values()).allSatisfy(errorCode -> {
            assertThat(errorCode.getMessage()).isNotBlank();
            assertThat(errorCode.getMessage()).isNotEqualTo("null");
        });
    }

    @Test
    @DisplayName("分段边界常量只读且与约定一致")
    void segmentConstantsAreStable() throws Exception {
        for (String name : List.of("GENERIC_CODE_MIN", "GENERIC_CODE_MAX", "SUCCESS_CODE",
                "BUSINESS_CODE_MIN", "MODULE_CODE_SEGMENT")) {
            Field field = ErrorCode.class.getField(name);
            assertThat(Modifier.isStatic(field.getModifiers())).as(name).isTrue();
            assertThat(Modifier.isFinal(field.getModifiers())).as(name).isTrue();
        }
        assertThat(ErrorCode.GENERIC_CODE_MIN).isEqualTo(10000);
        assertThat(ErrorCode.GENERIC_CODE_MAX).isEqualTo(19999);
    }

    @Test
    @DisplayName("P0 已定义的通用错误码与设计一致（改动即是契约变更）")
    void knownGenericCodesMatchDesign() {
        assertThat(ErrorCode.PARAM_INVALID.getCode()).isEqualTo(10000);
        assertThat(ErrorCode.PARAM_MISSING.getCode()).isEqualTo(10001);
        assertThat(ErrorCode.DATA_NOT_FOUND.getCode()).isEqualTo(10002);
        assertThat(ErrorCode.DATA_CONFLICT.getCode()).isEqualTo(10003);
        assertThat(ErrorCode.UNAUTHORIZED.getCode()).isEqualTo(10401);
        assertThat(ErrorCode.FORBIDDEN.getCode()).isEqualTo(10403);
        assertThat(ErrorCode.SYSTEM_ERROR.getCode()).isEqualTo(10500);
        assertThat(ErrorCode.IDEMPOTENT_REPLAY.getCode()).isEqualTo(10501);
        assertThat(ErrorCode.IDEMPOTENCY_UNAVAILABLE.getCode()).isEqualTo(10502);
    }

    private static List<Integer> genericCodes() {
        return Arrays.stream(ErrorCode.values())
                .map(ErrorCode::getCode)
                .filter(code -> code != ErrorCode.SUCCESS_CODE)
                .toList();
    }
}
