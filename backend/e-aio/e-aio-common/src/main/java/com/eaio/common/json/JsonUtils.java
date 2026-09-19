package com.eaio.common.json;

import java.util.List;
import java.util.Map;

import com.eaio.common.exception.JsonException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON 门面（契约 V1，P0 册 4.2）。
 *
 * <p><b>不暴露任何 JSON 库类型</b>：签名里只有 JDK 类型，底层实现为 Boot 4 管理的 Jackson 3
 * （实现类 {@code tools.jackson.databind.ObjectMapper}）。业务模块不得直接使用 Jackson API，
 * 便于将来整体替换实现。
 *
 * <p>失败语义：序列化/反序列化失败统一抛 {@link JsonException}（系统错误，消息可读），
 * 不把底层异常类型泄漏给调用方。
 */
public final class JsonUtils {

    /** 唯一实例：Jackson 的 ObjectMapper 是线程安全的（配置完成后）。 */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            // 未知字段不报错：前后端各自演进时，多一个字段不应导致整条接口失败
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final TypeReference<List<Object>> LIST_TYPE = new TypeReference<>() {
    };

    private JsonUtils() {
    }

    /** 序列化为 JSON 字符串。 */
    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JacksonException e) {
            throw JsonException.of("序列化", e);
        }
    }

    /** 反序列化为对象。 */
    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JacksonException e) {
            throw JsonException.of("反序列化", e);
        }
    }

    /**
     * 反序列化为对象列表（多态签名）。
     *
     * <p>刻意不提供 {@code TypeReference} 重载：那会把 Jackson 类型写进调用方代码，门面就漏了。
     */
    public static <T> List<T> toList(String json, Class<T> elementType) {
        try {
            return MAPPER.readValue(json, MAPPER.getTypeFactory().constructCollectionType(List.class, elementType));
        } catch (JacksonException e) {
            throw JsonException.of("反序列化", e);
        }
    }

    /** 反序列化为 Map。 */
    public static Map<String, Object> toMap(String json) {
        try {
            return MAPPER.readValue(json, MAP_TYPE);
        } catch (JacksonException e) {
            throw JsonException.of("反序列化", e);
        }
    }

    /** 反序列化为无类型 List（原始 JSON 数组）。 */
    public static List<Object> toList(String json) {
        try {
            return MAPPER.readValue(json, LIST_TYPE);
        } catch (JacksonException e) {
            throw JsonException.of("反序列化", e);
        }
    }

    /** 结构转换（同进程内对象 → 目标类型），不做字符串中转。 */
    public static <T> T convert(Object value, Class<T> type) {
        try {
            return MAPPER.convertValue(value, type);
        } catch (JacksonException e) {
            throw JsonException.of("转换", e);
        }
    }
}
