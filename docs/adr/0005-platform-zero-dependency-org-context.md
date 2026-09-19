# ADR-0005：platform 不依赖 iam —— 组织上下文用自有端口，环由结构消除

- 状态：已接受
- 日期：2026-09-21
- 适用批次：P1（platform M1–M3、iam M2–M5）
- 相关：[ADR-0002](0002-per-module-schema-and-flyway-instance.md)、04-DD P1-1 批次总册 3.1/3.7、`04-…-P1-2-平台底座.md` 4.5、`04-…-P1-4-iam.md` 3 章

## 背景

`platform` 与 `iam` 之间有一个真实的**取数环**：platform 被 iam 依赖（iam 用参数/字典/缓存/文件/任务），而 platform 的部分能力又需要"当前组织"——参数与字典的**分级覆盖**（系统级 / 组织级 / 用户级）、文件的组织归属与数据权限、定时任务在组织维度上的隔离或系统级运行。

原先的解法是：`platform` 侧以**可选依赖**注入 `com.eaio.iam.api.TenantCtxProvider`，把"接口定义在上游模块、实现由更上游的模块提供"当作破环手段。这个解法有两个问题：

1. **它对编译图不成立**：Modulith 的 `verify()` 判的是**模块依赖图**，不是"谁提供实现"。platform 一旦 `import com.eaio.iam.api.*` 就产生 `platform → iam` 的编译期依赖，而 iam 依赖 platform（`iam → platform`），**成环**。是否被容忍取决于 Modulith 版本与 `@ApplicationModule` 的 allowedDependencies 配置——把"架构不变量"押在框架行为上，本身就不该。
2. **它是不可测的承诺**：批次总册原先把"是否成环"留到 M1 实跑 `ApplicationModules.verify()` 才定案。一个必须靠实测才知道合不合法的依赖方向，等于把设计缺陷推迟到编码期暴露。

## 决策

**platform 的编译期依赖里删掉 iam——不是"可选依赖"，是"零依赖"；环由结构消除，不靠框架容忍。**

1. **显式传参优先**：`platform` 的 `api` 方法对"组织相关"的入参（`orgId`）**一律显式声明**，不隐式读上下文。例：`ParamApi.get(key, orgId)`、`DictApi.getItems(typeCode, orgId)`、`FileApi.getMeta(fileId)`（文件自带 `org_id`）。调用方（含 iam 自己）按自己的上下文传值——**由知道上下文的一侧负责取上下文**。
2. **platform 自有端口**：确需隐式上下文的路径（定时任务、异步 Excel、事件监听），platform 在 `com.eaio.platform.api` 声明只含最小信息的端口：
   ```java
   public interface OrgContextPort {          // 只有"当前组织 id"与"是否系统上下文"两件事
       Optional<Long> currentOrgId();
       boolean isSystemContext();
   }
   ```
   platform 内部提供 **Null 对象默认实现**（`currentOrgId()` 空、`isSystemContext()` true），行为是"无组织上下文 → 只读系统级参数/字典、文件 `org_id=0`、任务按系统上下文运行"，**不抛异常、不阻塞启动**。
3. **装配方注入实现**：`e-aio-app` 是唯一同时依赖两个模块的地方，由它把 iam 的适配器（读 `TenantCtxProvider.currentOrgId()`）注册为 `OrgContextPort` 的 Bean（`@ConditionalOnMissingBean` 保留 Null 默认）。**platform → iam 的边不存在**，装配边只存在于 app。
4. **iam 的数据权限不需要 platform 帮忙**：组织谓词的注入点在 **iam 自己的 `DataPermissionInterceptor`**（业务表同 Schema 谓词 + 对 `eaio_iam.org_node_path` 的只读谓词，见 [ADR-0004](0004-cross-schema-readonly-predicate.md)），与 platform 无关。原先"platform 帮 iam 拿上下文"的假设本身就是多余的。
5. **audit 同构**：`audit` 若需上下文（操作者/组织、系统上下文标记），同样**只声明自己的端口**（由 app 注入），不 import `iam.api`。已落地的 audit 分册按此对齐。
6. **测试要求**：架构测试直接断言 **platform 的编译期依赖里没有 `com.eaio.iam`**（比"运行时看是否报环"更早、更硬）；单测断言 Null 默认实现的降级行为（无上下文 → 系统级读、`org_id=0`）；集成测试断言 app 注入后 `OrgContextPort` 返回真实组织。

## 被否决的备选

- **platform 可选依赖 `iam.api` + `ObjectProvider`**（原方案）：编译期成环，靠 Modulith 容忍；且"可选"意味着两条运行路径都要测，环的判定被推迟到编码期（见背景）。
- **把 `TenantCtx` 放进 common**：common 已冻结 V1、无状态、不承载业务数据；让 common 持有"请求级组织上下文"会把技术底座变成隐式全局状态，并让所有模块都能绕过契约读上下文（不可审计）。
- **iam 反向暴露静态 ThreadLocal 工具类给 platform 直接用**：等价于全局可变状态 + 隐式依赖，测试无法注入，且依赖方向仍然是 platform → iam。
- **把参数/字典的组织分级砍掉（只做系统级）**：直接丢掉 FR-PLT-01 的母子公司差异化配置能力。

## 后果（含代价）

- **platform 的 `api` 签名变长**：组织相关的读写要显式带 `orgId`（调用方多传一个参数）。代价换来确定性：谁在什么上下文下调用的，签名里看得见。
- **platform 少了"隐式便利"**：定时任务/异步任务里想拿"当前组织"要走 `OrgContextPort`（默认系统上下文）；忘记显式传 `orgId` 时行为是"读系统级"，**这是一个更安全但更安静的默认值**——因此要求：凡涉及组织差异的 platform 接口，文档必须写明"不传 `orgId` 时的行为"，并有单测锁定。
- **装配责任集中到 app**：`OrgContextPort` 的实现注入写错（例如两个实现冲突）会在启动期暴露；这是**唯一**的装配点，比原先"每个模块各自可选注入"更好排查。
- **本 ADR 取代**批次总册 3.7 第 5 条的"待 M1 实测"状态：不再需要实测定案，M1 只需按 A7 断言验证边不存在。
