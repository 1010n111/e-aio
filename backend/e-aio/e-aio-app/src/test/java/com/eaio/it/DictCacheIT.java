package com.eaio.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import javax.sql.DataSource;

import com.eaio.common.exception.BusinessException;
import com.eaio.common.json.JsonUtils;
import com.eaio.platform.api.DictApi;
import com.eaio.platform.api.dto.DictItemDTO;
import com.eaio.platform.infrastructure.cache.DictInvalidationPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * 数据字典集成测试（P1 册 6.2 的 {@code DictCacheIT}）：真实 PostgreSQL 17（迁移到 V3 + 种子）+
 * 真实 Redis 7 上验证两级缓存、失效、标签语义、内置/在用保护与 11 个端点的写路径。
 *
 * <p><b>不 mock</b>：读走真实 {@link DictApi}（与应用内调用方同一条路径），写走真实 HTTP
 * （{@code RestClient} + {@code @LocalServerPort}，覆盖权限注解之外的整条入站链路：幂等过滤器、
 * 参数校验、{@code ApiResponseAdvice} 的返回体包装）。
 *
 * <p><b>"缓存命中不回源"怎么证明</b>：不断言 SQL 次数（真实库里数不出来），而是**绕过接口直连改库**——
 * 若命中缓存，接口仍返回旧值；{@code refresh} 之后才能读到新值。这条与 {@code ParamCenterIT} 同款。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DictCacheIT extends IntegrationTestBase {

    /** 种子类型（4.5）：3 个启用项，且 {@code builtin = true}。 */
    private static final String SEEDED_TYPE = "platform_param_level";

    @Autowired
    private DictApi dictApi;

    @Autowired
    private DataSource dataSource;

    /** 广播发布方：用它模拟"另一个实例改完值后发的失效消息"。 */
    @Autowired
    private DictInvalidationPublisher publisher;

    @Autowired
    private StringRedisTemplate redis;

    @LocalServerPort
    private int port;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    @Test
    @DisplayName("迁移到 V3：种子 4 个类型 + 15 个项，GetItems 按 sortNo 返回启用项（真实 HTTP）")
    void seededDictionaryIsMigratedAndReadable() {
        // 只数种子（本类其他用例会造 it.dict.* 数据，全局 count 会让用例之间有顺序依赖）
        assertThat(jdbc().queryForObject("select count(*) from eaio_platform.dict_type"
                + " where deleted = false and type_code like 'platform!_%' escape '!'", Integer.class))
                .as("4.5 的 4 个平台内置类型（21–24）").isEqualTo(4);
        assertThat(jdbc().queryForObject("select count(*) from eaio_platform.dict_item"
                + " where deleted = false and type_code like 'platform!_%' escape '!'", Integer.class))
                .as("册面写 14 项，逐项枚举实为 3+3+6+3=15 项（见《实现注记（T5）》）").isEqualTo(15);

        assertThat(dictApi.getItems(SEEDED_TYPE)).extracting(DictItemDTO::itemValue)
                .containsExactly("SYSTEM", "ORG", "USER");
        assertThat(jdbc().queryForObject("select builtin from eaio_platform.dict_type where type_code = ?",
                Boolean.class, SEEDED_TYPE)).as("种子类型是内置的，不可删").isTrue();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("typeCode", SEEDED_TYPE);
        String response = post("/api/platform/dictItem/GetItems", body, false);

        assertThat(response).contains("\"code\":0").contains("\"SYSTEM\"").contains("\"系统级\"");
    }

    @Test
    @DisplayName("缓存命中不回源（直连改库读旧值）；写路径改值后立刻失效读新值、L2 键被删")
    void cacheHitThenInvalidatedByWrite() {
        String typeCode = uniqueType("cache");
        createType(typeCode, "IT 缓存类型");
        long itemId = createItem(typeCode, "V1", "L1", 10, "ENABLED", false, null);

        assertThat(dictApi.getLabel(typeCode, "V1")).as("首次回源并写入 L1/L2").isEqualTo("L1");
        assertThat(redisKeys(typeCode)).as("L2 里应有该类型的键（4.6 键模式）").isNotEmpty();

        jdbc().update("update eaio_platform.dict_item set item_label = 'L1-direct'"
                + " where type_code = ? and item_value = 'V1'", typeCode);

        assertThat(dictApi.getLabel(typeCode, "V1")).as("L1 命中：直连改库后仍读缓存值").isEqualTo("L1");

        dictApi.refresh(typeCode);

        assertThat(dictApi.getLabel(typeCode, "V1")).as("refresh 后回源读到新值").isEqualTo("L1-direct");

        // 写路径（真实 HTTP）改 label：DictChangedEvent 提交后 → 清 L1 + 删 L2 + 广播
        Integer version = jdbc().queryForObject("select version from eaio_platform.dict_item where id = ?",
                Integer.class, itemId);
        Map<String, Object> up = new LinkedHashMap<>();
        up.put("typeCode", typeCode);
        up.put("itemValue", "V1");
        up.put("itemLabel", "L2");
        up.put("sortNo", 10);
        up.put("status", "ENABLED");
        up.put("isDefault", false);
        up.put("version", version);
        assertThat(post("/api/platform/dictItem/Up", up, true)).contains("\"code\":0");

        assertThat(redisKeys(typeCode)).as("写路径失效了 L2：键在下次回源前不存在").isEmpty();
        assertThat(dictApi.getLabel(typeCode, "V1")).as("无需 refresh 就读到新值").isEqualTo("L2");
    }

    @Test
    @DisplayName("跨实例失效广播：另一实例发布失效消息后，本实例立刻读到新值（不等 L1 的 60s TTL）")
    void crossInstanceInvalidationBroadcast() throws InterruptedException {
        String typeCode = uniqueType("broadcast");
        createType(typeCode, "IT 广播类型");
        createItem(typeCode, "V1", "L1", 10, "ENABLED", false, null);
        assertThat(dictApi.getLabel(typeCode, "V1")).as("预热 L1").isEqualTo("L1");

        jdbc().update("update eaio_platform.dict_item set item_label = 'L1-other'"
                + " where type_code = ? and item_value = 'V1'", typeCode);
        assertThat(dictApi.getLabel(typeCode, "V1")).as("未广播前 L1 命中：仍读缓存值").isEqualTo("L1");

        publisher.publish(typeCode);
        awaitLabel(typeCode, "L1-other");
    }

    @Test
    @DisplayName("停用项：getItems 不含它、getLabel 仍返回它的 label；值未命中返回原值不抛（3.2.1）")
    void disabledItemAndLabelMissSemantics() {
        String typeCode = uniqueType("label");
        createType(typeCode, "IT 标签类型");
        createItem(typeCode, "A", "标签 A", 10, "ENABLED", false, null);
        long disabledId = createItem(typeCode, "B", "标签 B", 20, "ENABLED", false, null);

        Integer version = jdbc().queryForObject("select version from eaio_platform.dict_item where id = ?",
                Integer.class, disabledId);
        Map<String, Object> up = new LinkedHashMap<>();
        up.put("typeCode", typeCode);
        up.put("itemValue", "B");
        up.put("itemLabel", "标签 B");
        up.put("sortNo", 20);
        up.put("status", "DISABLED");
        up.put("isDefault", false);
        up.put("version", version);
        assertThat(post("/api/platform/dictItem/Up", up, true)).contains("\"code\":0");

        assertThat(dictApi.getItems(typeCode)).as("停用项不出现在 getItems")
                .extracting(DictItemDTO::itemValue).containsExactly("A");
        assertThat(dictApi.getLabel(typeCode, "B")).as("停用 ≠ 不存在：历史值仍能解析").isEqualTo("标签 B");
        assertThat(dictApi.getLabel(typeCode, "NO_SUCH_VALUE")).as("值未命中返回原值").isEqualTo("NO_SUCH_VALUE");
        assertThat(dictApi.getLabel("it.dict.never-defined", "X")).as("类型不存在也返回原值，不抛")
                .isEqualTo("X");
    }

    @Test
    @DisplayName("内置类型的项不可删（20005）；在用类型删除报 20008；无项的普通类型可删")
    void builtinAndInUseProtection() {
        Long seededTypeId = jdbc().queryForObject(
                "select id from eaio_platform.dict_type where type_code = ?", Long.class, SEEDED_TYPE);
        Integer seededVersion = jdbc().queryForObject(
                "select version from eaio_platform.dict_type where id = ?", Integer.class, seededTypeId);

        Map<String, Object> delType = new LinkedHashMap<>();
        delType.put("id", seededTypeId);
        delType.put("version", seededVersion);
        assertThat(post("/api/platform/dictType/Del", delType, true))
                .as("种子内置类型下仍有项 → 20008（在用比内置更可执行）").contains("\"code\":20008");

        Long seededItemId = jdbc().queryForObject(
                "select id from eaio_platform.dict_item where type_code = ? and item_value = 'SYSTEM'",
                Long.class, SEEDED_TYPE);
        Integer seededItemVersion = jdbc().queryForObject(
                "select version from eaio_platform.dict_item where id = ?", Integer.class, seededItemId);
        Map<String, Object> delItem = new LinkedHashMap<>();
        delItem.put("id", seededItemId);
        delItem.put("version", seededItemVersion);
        assertThat(post("/api/platform/dictItem/Del", delItem, true))
                .as("内置类型下的项不可删 → 20005").contains("\"code\":20005");

        String typeCode = uniqueType("inuse");
        long typeId = createType(typeCode, "IT 在用类型");
        long itemId = createItem(typeCode, "V1", "L1", 10, "ENABLED", false, null);
        Integer typeVersion = jdbc().queryForObject("select version from eaio_platform.dict_type where id = ?",
                Integer.class, typeId);
        Map<String, Object> delInUse = new LinkedHashMap<>();
        delInUse.put("id", typeId);
        delInUse.put("version", typeVersion);
        assertThat(post("/api/platform/dictType/Del", delInUse, true))
                .as("普通类型下仍有项也是 20008").contains("\"code\":20008");

        Integer itemVersion = jdbc().queryForObject("select version from eaio_platform.dict_item where id = ?",
                Integer.class, itemId);
        Map<String, Object> delTheItem = new LinkedHashMap<>();
        delTheItem.put("id", itemId);
        delTheItem.put("version", itemVersion);
        assertThat(post("/api/platform/dictItem/Del", delTheItem, true)).contains("\"code\":0");

        Map<String, Object> delEmptyType = new LinkedHashMap<>();
        delEmptyType.put("id", typeId);
        delEmptyType.put("version", typeVersion);
        assertThat(post("/api/platform/dictType/Del", delEmptyType, true))
                .as("项清空后类型可逻辑删除").contains("\"code\":0");
        assertThatThrownBy(() -> dictApi.getItems(typeCode))
                .as("删除后查项 → 20003").isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(20003));
    }

    @Test
    @DisplayName("类型与项的 CRUD over HTTP：Add/Get/Up/GetPage（含 itemCount、extJson、isDefault 往返）")
    void typeAndItemCrudOverHttp() {
        String typeCode = uniqueType("crud");
        long typeId = createType(typeCode, "IT CRUD 类型");
        String extJson = "{\"color\":\"#409eff\"}";
        createItem(typeCode, "V1", "标签一", 10, "ENABLED", true, extJson);

        Map<String, Object> getType = new LinkedHashMap<>();
        getType.put("id", typeId);
        String typeResponse = post("/api/platform/dictType/Get", getType, false);
        assertThat(typeResponse).contains("\"code\":0").contains(typeCode).contains("\"itemCount\":1");

        Integer typeVersion = jdbc().queryForObject("select version from eaio_platform.dict_type where id = ?",
                Integer.class, typeId);
        Map<String, Object> upType = new LinkedHashMap<>();
        upType.put("typeCode", typeCode);
        upType.put("typeName", "IT CRUD 类型（改名）");
        upType.put("status", "ENABLED");
        upType.put("remark", "集成测试");
        upType.put("version", typeVersion);
        assertThat(post("/api/platform/dictType/Up", upType, true)).contains("\"code\":0").contains("改名");

        Map<String, Object> itemPage = new LinkedHashMap<>();
        itemPage.put("typeCode", typeCode);
        itemPage.put("pageNum", 1);
        itemPage.put("pageSize", 20);
        String itemResponse = post("/api/platform/dictItem/GetPage", itemPage, false);
        assertThat(itemResponse).contains("\"code\":0").contains("\"total\":1");
        Map<String, Object> record = firstRecord(itemResponse);
        assertThat(String.valueOf(record.get("extJson"))).as("extJson 往返：PG 会把 jsonb 规范化，故按内容断言")
                .contains("#409eff").contains("color");
        assertThat(record.get("isDefault")).as("is_default 列（getIsDefault 访问器）").isEqualTo(true);
        assertThat(jdbc().queryForObject("select ext_json from eaio_platform.dict_item where type_code = ?",
                String.class, typeCode)).as("JSONB 列按原样往返（自定义 TypeHandler）").contains("#409eff");

        Map<String, Object> pageTypes = new LinkedHashMap<>();
        pageTypes.put("typeCode", typeCode);
        String typePage = post("/api/platform/dictType/GetPage", pageTypes, false);
        assertThat(typePage).contains("\"code\":0").contains("\"itemCount\":1").contains(typeCode);
    }

    @Test
    @DisplayName("业务码：重复 item_value 20007、类型编码重复 20003、未知类型 20003（含 Refresh）")
    void businessCodes() {
        String typeCode = uniqueType("codes");
        createType(typeCode, "IT 码类型");
        createItem(typeCode, "V1", "L1", 10, "ENABLED", false, null);

        Map<String, Object> duplicate = new LinkedHashMap<>();
        duplicate.put("typeCode", typeCode);
        duplicate.put("itemValue", "V1");
        duplicate.put("itemLabel", "重复");
        assertThat(post("/api/platform/dictItem/Add", duplicate, true)).contains("\"code\":20007");

        Map<String, Object> dupType = new LinkedHashMap<>();
        dupType.put("typeCode", typeCode);
        dupType.put("typeName", "重复类型");
        assertThat(post("/api/platform/dictType/Add", dupType, true))
                .as("5.2 给 dictType/Add 的唯一业务码就是 20003").contains("\"code\":20003");

        Map<String, Object> unknown = new LinkedHashMap<>();
        unknown.put("typeCode", "it.dict.never-defined");
        assertThat(post("/api/platform/dictItem/GetItems", unknown, false)).contains("\"code\":20003");
        assertThat(post("/api/platform/dictType/Refresh", unknown, true))
                .as("Refresh 给不存在的类型 → 20003（5.2）").contains("\"code\":20003");
    }

    @Test
    @DisplayName("写动作缺幂等键即 10001（P1 册 5.6）；读动作不需要幂等键")
    void writeRequiresIdempotencyKey() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("typeCode", uniqueType("idem"));
        body.put("typeName", "IT 幂等类型");

        assertThat(post("/api/platform/dictType/Add", body, false)).contains("\"code\":10001");
        assertThat(post("/api/platform/dictType/Add", body, true)).contains("\"code\":0");
    }

    // ---------------------------------------------------------------- 辅助

    private static String uniqueType(String tag) {
        return "it.dict." + tag + "." + System.nanoTime();
    }

    /** 建类型（HTTP），返回类型 id。 */
    private long createType(String typeCode, String typeName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("typeCode", typeCode);
        body.put("typeName", typeName);
        body.put("status", "ENABLED");
        String response = post("/api/platform/dictType/Add", body, true);
        assertThat(response).contains("\"code\":0");
        return ((Number) data(response).get("id")).longValue();
    }

    /** 建项（HTTP），返回项 id。 */
    private long createItem(String typeCode, String value, String label, int sortNo, String status,
            boolean isDefault, String extJson) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("typeCode", typeCode);
        body.put("itemValue", value);
        body.put("itemLabel", label);
        body.put("sortNo", sortNo);
        body.put("status", status);
        body.put("isDefault", isDefault);
        body.put("extJson", extJson);
        String response = post("/api/platform/dictItem/Add", body, true);
        assertThat(response).contains("\"code\":0");
        return ((Number) data(response).get("id")).longValue();
    }

    /** 写动作默认带幂等键（5.6）；读动作传 {@code false}。 */
    private String post(String path, Map<String, Object> body, boolean idempotent) {
        RestClient.RequestBodySpec spec = client().post().uri(path).contentType(MediaType.APPLICATION_JSON);
        if (idempotent) {
            spec = spec.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        return spec.body(JsonUtils.toJson(body)).retrieve().body(String.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(String response) {
        Map<String, Object> envelope = JsonUtils.fromJson(response, Map.class);
        return (Map<String, Object>) envelope.get("data");
    }

    /** 分页返回体的第一条记录（{@code PageResult.records[0]}）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstRecord(String response) {
        List<Map<String, Object>> records = (List<Map<String, Object>>) data(response).get("records");
        assertThat(records).as("分页结果不为空").isNotEmpty();
        return records.get(0);
    }

    /**
     * 该类型在 Redis 里的键（env 段用通配，避免在测试里复刻 {@code CacheEnv} 的解析规则）。
     * {@code keys} 在测试里用是安全的：这里只有一个实例、键数是常数级。
     */
    private Set<String> redisKeys(String typeCode) {
        return redis.keys("eaio:*:platform:dict:items:" + typeCode);
    }

    /** 轮询等待广播生效（不引 Awaitility：只为一个用例加测试依赖不划算）。 */
    private void awaitLabel(String typeCode, String expected) throws InterruptedException {
        Supplier<String> read = () -> dictApi.getLabel(typeCode, "V1");
        long deadline = System.currentTimeMillis() + 5000L;
        String actual = null;
        while (System.currentTimeMillis() < deadline) {
            actual = read.get();
            if (expected.equals(actual)) {
                return;
            }
            Thread.sleep(50L);
        }
        assertThat(actual).as("广播在 5000ms 内未生效").isEqualTo(expected);
    }
}
