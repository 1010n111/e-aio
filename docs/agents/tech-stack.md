# 技术栈（开源优先，已冻结）

| 层 | 选型 |
|---|---|
| 语言/框架 | Java 21 + Spring Boot 4.1.x + Spring Modulith 2.1.x（配套版本以父 POM `backend/e-aio/pom.xml` 为准） |
| 工具库 | Lombok / Hutool（`cn.hutool:hutool-all` 5.8.x，Mulan PSL v2）/ Bean Validation / MapStruct（经 common 门面，见 [java-conventions.md](java-conventions.md)） |
| JSON | Jackson **3**（`tools.jackson.*`；Boot 4.1.1 经 `spring-boot-starter-jackson` 管到 3.1.5，**不手动指定版本、不引入 3.2.x**；Jackson 2 支持在 Boot 4 已 deprecated）；统一走 `JsonUtils` 多态门面，**不对外暴露 `ObjectMapper` 类型**；脱敏序列化器用 `tools.jackson.databind.ValueSerializer`（勿误用 Jackson 2 同名注解） |
| Excel | Apache Fesod（Apache-2.0）→ `ExcelKit` |
| 缓存/锁 | Redis（Redisson 底层）→ `RedisKit` |
| 持久层 | MyBatis-Plus |
| 数据库迁移 | Flyway 多 Schema |
| 工作流 | Flowable（Apache-2.0）二次封装 |
| 搜索/BI | OpenSearch / Elasticsearch；ClickHouse / StarRocks |
| 系统监测 | Actuator + Prometheus + Grafana |
| OCR | PaddleOCR / Tesseract |
| AI | Spring AI / LangChain4j / LiteLLM 网关 + RAG + pgvector |
| 原生编译 | GraalVM Native Image（可选部署形态，默认 JVM；反射/动态代理组件需 native 配置，P1 验证） |
| 权限 | Spring Security + 自研多级组织权限引擎 |
| API | 统一 POST + JSON（见 [api-conventions.md](api-conventions.md)） |

**选型规则**：优先已冻结选型内的开源实现；引入新依赖前先改本表（单一事实来源），不要在代码里悄悄加。

**版本策略**：Spring Boot / Spring Modulith **补丁级浮动**（BOM 管，`4.1.x` / `2.1.x`；当前记录值 4.1.1 / 2.1.1），其余依赖**精确锁定**（Hutool 5.8.47、archunit 1.5.0、logstash-logback-encoder 9.0、testcontainers 2.0.5、fesod-sheet 2.0.2-incubating）；升级一律走 PR 评审。逐项版本与许可证核实记录见 04-DD P0 册 3.1.2。

**未决项（P1 定）**：定时任务 Quartz / XXL-JOB / Spring Task + ShedLock。（MyBatis-Plus 的 Boot 4 starter 已定：`com.baomidou:mybatis-plus-spring-boot4-starter` 3.5.17）
