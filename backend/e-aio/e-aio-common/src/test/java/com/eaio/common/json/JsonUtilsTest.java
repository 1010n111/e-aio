package com.eaio.common.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import com.eaio.common.exception.JsonException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** JSON 门面：只走 JDK 类型进出，失败统一抛 JsonException。 */
class JsonUtilsTest {

    /** 简单载体。 */
    public static class Order {

        private String no;
        private int amount;

        public Order() {
        }

        public Order(String no, int amount) {
            this.no = no;
            this.amount = amount;
        }

        public String getNo() {
            return no;
        }

        public int getAmount() {
            return amount;
        }
    }

    @Test
    @DisplayName("序列化与反序列化往返一致")
    void roundTrip() {
        Order order = new Order("A-1", 42);

        Order parsed = JsonUtils.fromJson(JsonUtils.toJson(order), Order.class);

        assertThat(parsed.getNo()).isEqualTo("A-1");
        assertThat(parsed.getAmount()).isEqualTo(42);
    }

    @Test
    @DisplayName("多态签名：泛型集合按元素类型解析，调用方不接触 JSON 库类型")
    void toListWithElementType() {
        List<Order> orders = JsonUtils.toList("[{\"no\":\"A-1\",\"amount\":1},{\"no\":\"A-2\",\"amount\":2}]",
                Order.class);

        assertThat(orders).hasSize(2);
        assertThat(orders.get(1).getNo()).isEqualTo("A-2");
    }

    @Test
    @DisplayName("toMap / toList 走 JDK 类型")
    void toMapAndToList() {
        Map<String, Object> map = JsonUtils.toMap("{\"a\":1,\"b\":\"x\"}");
        List<Object> list = JsonUtils.toList("[1,2,3]");

        assertThat(map).containsEntry("a", 1).containsEntry("b", "x");
        assertThat(list).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("未知字段不报错：前后端各自演进不该打断接口")
    void unknownFieldIsIgnored() {
        Order parsed = JsonUtils.fromJson("{\"no\":\"A-1\",\"amount\":1,\"future\":true}", Order.class);

        assertThat(parsed.getNo()).isEqualTo("A-1");
    }

    @Test
    @DisplayName("结构转换不经过字符串中转")
    void convertBetweenTypes() {
        Map<String, Object> source = Map.of("no", "A-9", "amount", 7);

        Order order = JsonUtils.convert(source, Order.class);

        assertThat(order.getNo()).isEqualTo("A-9");
        assertThat(order.getAmount()).isEqualTo(7);
    }

    @Test
    @DisplayName("非法 JSON 抛 JsonException，且带可读消息")
    void invalidJsonThrowsJsonException() {
        assertThatThrownBy(() -> JsonUtils.toMap("{not json"))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("反序列化");
    }

    @Test
    @DisplayName("序列化失败同样归为 JsonException（不泄漏底层异常类型）")
    void serializationFailureIsWrapped() {
        Object selfReferencing = new Object() {
            @SuppressWarnings("unused")
            public Object getSelf() {
                return this;
            }
        };

        assertThatThrownBy(() -> JsonUtils.toJson(selfReferencing))
                .isInstanceOf(JsonException.class);
    }

    @Test
    @DisplayName("门面签名不出现具体 JSON 库类型（只允许 JDK 与 e-aio 类型）")
    void facadeExposesNoJacksonTypes() {
        for (var method : JsonUtils.class.getDeclaredMethods()) {
            for (Class<?> type : method.getParameterTypes()) {
                assertThat(type.getName())
                        .as("方法 %s 的参数类型不得是 Jackson 类型", method.getName())
                        .doesNotStartWith("tools.jackson")
                        .doesNotStartWith("com.fasterxml.jackson");
            }
            assertThat(method.getReturnType().getName())
                    .as("方法 %s 的返回类型不得是 Jackson 类型", method.getName())
                    .doesNotStartWith("tools.jackson")
                    .doesNotStartWith("com.fasterxml.jackson");
        }
    }
}
