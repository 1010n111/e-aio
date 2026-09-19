# ADR-0003：协议端点例外（OAuth2 / SAML2 / 文件流式下载）

- 状态：已接受
- 日期：2026-09-20
- 适用批次：P1 起（iam 认证、platform 文件下载）
- 相关：[ADR-0001](0001-unified-post-and-always-200-result-contract.md)、`docs/agents/api-conventions.md`、04-DD P1 批次总册 3.5、`04-…-P1-4-iam.md` 7.6 L-2

## 背景

ADR-0001 冻结了"统一 POST + JSON + HTTP 恒 200 + 业务码在 `Result.code`"这条契约，它让前端只写一套错误处理、`10401` 自动跳登录。但 P1 落地的 iam 认证包含 **OAuth2 授权服务器端点**（`/oauth2/authorize`、`/oauth2/token`、`/userinfo`、撤销与内省）与 **SAML2 端点**（metadata / SSO / ACS / SLO），这些端点的请求与响应形态由 RFC 6749/8414、OIDC Core、SAML 2.0 规定：浏览器 `302` 跳转、`application/x-www-form-urlencoded` 请求体、标准错误 JSON（`invalid_grant` 等）与特定 HTTP 状态码。把它们塞进"恒 200 + `Result` 包装"会让标准 SSO 客户端（Keycloak、Azure AD、企业 IdP）无法对接——这不是风格问题，是互操作问题。同类问题也出现在 platform 的文件下载：二进制流 + `Range`/断点续传必须返回真实状态码与 `Content-Length`。

## 决策

**保留 ADR-0001 作为默认契约，为"协议规定形态"的端点开一个封闭白名单例外。**

1. **两种模式**：
   - **常规模式（默认）**：前端与业务接口，一律 POST + JSON + HTTP 恒 200 + `Result<T>`；**新增接口默认落在这里**，不需要任何登记。
   - **协议模式（例外）**：仅下列端点按协议规定实现（方法、Content-Type、状态码、重定向），**不得返回 `Result` 包装**：
     | 端点 | 形态 | 依据 |
     |---|---|---|
     | `/oauth2/authorize` | GET + 302/400（授权码流程） | RFC 6749 §4.1 |
     | `/oauth2/token` | POST + `x-www-form-urlencoded` + 200/400/401 | RFC 6749 §4.1.3、§5.2 |
     | `/oauth2/revoke`、`/oauth2/introspect` | POST + form + 标准响应 | RFC 7009、RFC 7662 |
     | `/.well-known/openid-configuration`、`/oauth2/jwks` | GET + JSON（发现文档） | OIDC Discovery、RFC 8414 |
     | `/userinfo` | GET/POST + Bearer + JSON（按 OIDC，非 `Result`） | OIDC Core §5.3 |
     | `/saml2/metadata`、`/saml2/sso`、`/saml2/acs`、`/saml2/slo` | GET/POST + XML + 302 | SAML 2.0 Web SSO |
     | `/api/platform/file/download`（流式）、`/api/platform/file/chunk`（分片上传，PUT/流） | 二进制 + `Content-Length`/`Range` | HTTP 语义；ADR-0001 无法表达二进制与断点续传 |
2. **封闭清单**：例外以**逐路径登记**落 `docs/agents/api-conventions.md` 的"协议端点例外清单"表；**不在表内的路径一律常规模式**，需要新增例外必须走 Issue 评审 + 更新该表。
3. **前端纪律**：前端请求封装对例外路径**不套** `Result` 解包与 `10401` 跳登录逻辑（协议端点自己处理错）；常规模式仍按 `Result.code` 判定。
4. **过滤链纪律**：`TraceIdFilter` 覆盖全部路径（含例外）；`IdempotencyFilter` 只作用于常规模式的写接口；`ApiResponseAdvice`/`GlobalExceptionHandler` 对例外路径**不介入**（否则会二次包装）。
5. **测试要求**：① 集成测试断言"未登记路径返回恒 200 + `Result`"与"登记的协议端点返回协议规定状态码/Content-Type"；② 断言协议端点不被 `ApiResponseAdvice` 包装；③ SAML/OIDC 至少各有一条与真实 IdP 模拟器（或 `MockMvc` 全流程）的往返用例。

## 被否决的备选

- **严格统一（无例外）**：标准 SSO 客户端无法对接，等于放弃 SSO/LDAP 之外的联邦登录（FR-SEC-03 直接不可实现）。
- **只做 POST + JSON 的 OAuth2 变体**：不是 OAuth2，第三方 IdP 不认；自己实现一套"像 OAuth2 的东西"会把互操作风险留给未来。
- **全局开关（一个配置切整站形态）**：等于把契约变成运行时可变量，前端两套逻辑都要写，回归矩阵翻倍，且"今天 200 明天 302"无法排障。

## 后果（含代价）

- `api-conventions.md` 多一张需要维护的例外表；遗漏登记会导致该端点被 `ApiResponseAdvice` 包装（症状：客户端拿到 `Result` 包着的 `302` 语义），必须有测试兜底。
- 前端请求层出现"两条路径"：常规（解包 + 跳登录）与协议（原样）。代价由封装集中承担，业务代码不得自行分支。
- 文件下载/分片上传的返回体不再统一：前端要用独立的下载/上传封装（不经过 `Result` 解包层）。
- 契约的"恒 200"承诺由此**收窄为"常规模式恒 200"**；ADR-0001 需在下次修订时补一行指向本 ADR。
