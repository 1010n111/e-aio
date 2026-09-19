# 技术栈（开源优先，已冻结）

| 层 | 选型 |
|---|---|
| 语言/框架 | Java 21 + Spring Boot 4.1.x + Spring Modulith 2.1.x（配套版本以父 POM `backend/e-aio/pom.xml` 为准） |
| 工具库 | Lombok / Hutool / Bean Validation / MapStruct（经 common 门面，见 [java-conventions.md](java-conventions.md)） |
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

**未决项（P1 定）**：定时任务 Quartz / XXL-JOB / Spring Task + ShedLock；MyBatis-Plus 对应 Spring Boot 4 的 starter 版本。
