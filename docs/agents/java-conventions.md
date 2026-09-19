# Java / common 编码规范

## common 门面（强制）

业务模块**不直接散用第三方工具类**，一律走 common 门面：`ExcelKit`（Apache Fesod）、`RedisKit`（Redisson）、`DateUtils` / `JsonUtils` / `BeanUtils` / `TreeUtils` / `SensitiveUtils` / `ConvertUtils` …

## 编码约定

| 项 | 约定 |
|---|---|
| 依赖注入 | 构造器注入（`@RequiredArgsConstructor`）；不用字段注入 |
| Lombok | `@Data`（简单 DTO）/ `@Builder`（不可变 DTO）/ `@Slf4j` |
| 校验 | Controller 入参 `@Validated`；自定义约束 `@EnumValid` / `@Mobile` / `@IdCard` / `@Money`（common.validation） |
| DTO 映射 | **跨模块契约转换一律 MapStruct**（`componentModel="spring"`、`unmappedTargetPolicy=ERROR`，编译期报错）；模块内非性能敏感的低频拷贝可用 common `BeanUtils` |
| 主键 | `IdGenerator`（雪花，long）统一生成 |
| JSON | 统一 `JsonUtils`（Jackson 3，Boot 4 管理）；多态 API，业务模块不自建 ObjectMapper、不引用 Jackson 类型 |
| 敏感数据 | 存储原文，展示层经 `@Sensitive` 脱敏（手机/证件/银行卡/邮箱） |
| 日志 | 结构化 JSON 日志（ts/level/traceId/module/msg）；不打印敏感字段 |

> 表结构、字段与删除语义见 [database.md](database.md)；接口契约见 [api-conventions.md](api-conventions.md)。
