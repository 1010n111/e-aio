# Handoff — e-aio P1 批次（P1-3 platform 分册）实施中

生成时间：2026-09-19 19:30（本地）。工作目录 `F:\e-aio`。仓库 `1010n111/e-aio`（Apache-2.0，模块化单体）。

## 0. 一句话状态

P1-3 分册的 **T1–T8 已交付并关票**（#12–#19、#26 已 CLOSED），**#20（T9 文件中心）正在由子代理实现中**，#21–#25 未开工；spec 票 #11 是权威需求源。本轮对话的 goal 工具里有一个活目标「做完P1-3」（round 56/256）——接手时先 `get_goal` 拿 goal_id/revision。

## 1. 权威需求与拆分（不要去别处找需求）

- spec：GitHub issue **#11**（`P1 platform（spec）…`，21000+ 字符，含 Problem/Solution/User Stories/Implementation Decisions/Testing Decisions）。
- 子票：**#12–#25**（T1–T14）+ **#26**（T15 checkstyle）+ **#27**（T16 评审修复）；每张票的 `What to build` / `Acceptance criteria` / `Blocked by` 是验收口径，逐条对照。
- 设计分册：`docs/04-企业级一体化管理系统-e-aio-详细设计说明书-P1-3-platform.md`（2425 行，权威）；批次总册 `…-P1-1-批次总册.md`；底座 `…-P1-2-平台底座.md`；P0 `…-P0-工程地基.md`。
- 约定与门禁：`AGENTS.md` + `docs/agents/*.md`（**评审基准**）。
- 队列与票面状态：`gh issue list --state open`（OPEN = #20–#25 + spec #11/#1）。

## 2. 已完成（提交线，全在本地 main，未推送）

`481da7a`(P0) → `e164951`(T1 #12) → `ac11105`(T15 checkstyle #26) → `b161ffc`/`c38a70d`/`8ce877a`(T3/T4 #14) → `7998eb9`(T2 #13) → `2d7d688`(广播) → `1ba8364`(SECRET 留空) → `0522c60`(参数写页) → `9426350`(T16 后端 #27) → `b302d40`(T5 字典 #17) → `72f7c36`(T16 复跑修复) → `7dcce09`(文档路径同步) → `02d83da`(T7 定时任务 #15) → `7df4346`(T6 缓存 #18) → `a77c6af`(T16 遗留) → `5263427`(T8 事件可靠性 #19)。

**推送决策仍悬空**：`main` 领先 `origin/main` 20+ 个提交，CI/Release **从未跑过**。`docs/agents/git-workflow.md` 要求 PR 门槛=CI 全绿+1 名维护者评审。下一步需用户裁决：① 推 branch 开 PR 让 CI 跑；② 直推 main；③ 继续纯本地。**不要擅自 push。**

## 3. 正在进行：`#20`（T9 文件中心）

- 子代理 id：`3d5f1ef4-4a60-497e-a93f-04713ed927d2`（`send_message` 可续推；`list_agents` 可查活）。
- 已落盘（未提交）：`api/FileApi`+10 DTO+`port/AuditPort`、`domain/file/*`、`application/file/*`（`FileAppService` 491 行）、`infrastructure/storage/*`（`LocalFileStorage`/`FilePresignTokenService`/`FileStorageConfig`）、`infrastructure/persistence/{FileStore,FileMapper,FileBindingMapper}`、`infrastructure/web/FileController`、`events/{FileUploadedEvent,FileDeletedEvent}`、`V4__file_center.sql`、`FileOrphanCleanHandler`、6 个单测类 + `FileRoundTripIT`、前端 `api/platform/file.js`(+test)、`views/platform/file/FileList.vue`、共享层 `frontend/src/api/request.js`（blob/upload 旁路）、`ApiResponseAdvice` 4 行 `Resource` 旁路。
- **我已下的裁决（复审按这个验收）**：分片/合并归 **#22**；V1–V3/V5/V7 一字不改；`file_binding` 去 `deleted`（§4.2 例外清单）；存储类型用属性 `eaio.file.storage-type`（默认 LOCAL，进参数中心的是 `platform.file.local-root`）；S3 本票不做；预签名 `sig=hex(HMAC-SHA256(secret, fileId+"|"+exp))`，secret=`EAIO_FILE_PRESIGN_SECRET`(≥32B)，不可用即启动 ERROR + 一律 20016；可见性=本人/同组织/权限点，拒绝=**20017**（不复用 10403），上下文缺席只允许本人；`FileDTO` 不含 `storagePath`；留痕 logger 逐字 `com.eaio.platform.audit.fallback` + 计数 `platform.audit.fallback.count`；清理任务用种子里已有的 `job_code`（ID 52 会话清理属 #22，本票只登记缺口）。
- **我已提并已落地的一致性意见**：预签名 GET 路径去掉 `@PreAuthorize`（否则 `:get` 用户拿签名链接也 10403）；`FilePresignTokenService.sign()` 补 `secretUsable()` 守卫；`Del` 版本判定下沉 `FileAppService.del(FileDelCmd)`；`store.insert` 失败要清盘上文件。
- **最近一次复核（未闭环）**：`FileAppServiceTest:119-121` 断言硬编码日期路径 `2026/03/04/01/1001.txt`，而路径来自 `Instant.now()` → **必红**，已发修法（改 `matches("\\d{4}/\\d{2}/\\d{2}/01/1001\\.txt")`）；`LocalFileStorageTest` 的固定日期断言是**对的**（该测试显式传 `Instant.parse`）。
- 待它收尾：`FileRoundTripIT` 场景全绿、前端三道门禁、《实现注记（T9）》、逐条验收映射+原始输出+未验证项。

## 4. 接手后立刻要做的（顺序）

1. `list_agents` + `send_message` 给 `3d5f1ef4…` 要进度；等它交付后**逐件只读复核**（口径见第 3 节）。
2. 权威门禁：**必须在隔离工作树跑**（并发子代理会争 `target/`）：
   ```powershell
   git worktree add --detach F:\e-aio-wtNN HEAD
   # 把该票文件 Copy-Item 进去（git ls-files --others --exclude-standard + git diff --name-only 取集合）
   $env:PATH = "C:\Users\11929\AppData\Local\Programs\DockerDesktop\resources\bin;$env:PATH"
   cd F:\e-aio-wtNN\backend\e-aio; mvn -B clean verify
   ```
   当前基线应为：checkstyle 4 模块 0 违规、common 70 / platform 188 / app 74 单测、IT 58 条。
3. 提交（一票一提交，消息用 `git commit -F .tmp-commit-*.txt`，**先删临时文件再 `git add`**——`git add -A` 会把它裹进提交），评论票面逐条验收证据，`gh issue close`。
4. 然后按依赖派 **#21（T12 公告/模板）→ #22（T10 分片）→ #23（T11 Excel）→ #24（T13 监测告警）→ #25（T14 交付收口）**；`#24` blocked by #21，`#25` blocked by #22–#24。
5. 每票结束照例做一次双轴评审（`code-review` skill）+ 修 F 项，再提交。

## 5. 只剩这些活（LIVE TODO）

- [ ] #20 收口（验收 5 条全绿 + 提交 + 关票）
- [ ] #21 公告/模板（派工材料已备：《实现注记》+ §3.8 口径，见下）
- [ ] #22 分片上传（材料已备：§3.3.3 的 637–639、`FileChunkCmd`/`FileMergeCmd`、V4 已交付不动、种子 ID 52 处理点、上限=`chunk-size×10000` 不走 50MB）
- [ ] #23 Excel（Fesod 2.0.2-incubating、异步任务 + `taskId` 轮询、10 万行 `-Xmx512m` 峰值 ≤384MB、≤20000 行整批事务否则 1000 行/批记 PARTIAL）
- [ ] #24 监测告警（**7 条跨票入站义务**必须写进 brief：订阅 `EventDeadLetteredEvent`→`alert(rule_code='event.dead-letter')`、`JobFailedEvent`→`job.failure`（阈值参数 `platform.job.alert.fail-threshold`）、T5 的 `platform.dict.label.miss`、T6 内部计数器、#20 的 `platform.audit.fallback.count`/`platform.file.storage.error`、T3 的 `platform.param.missing`、V9 三条内置规则各有真实消费方）
- [ ] #25 交付收口（`PermissionCodeContractTest` 与 7.3 逐条比对、`ApiContractTest`、跨实例一致性、性能抽验、覆盖率 100%/≥80%）
- [ ] P1-2 册 §3.15 补登 `eaio.cache.<region>.l2-ttl-seconds`（T6 留下的登记表缺口；我此前改过但**未提交**，核对工作树 ` M` 状态）
- [ ] 设计册与实现差异注记的持续维护：每票在对应小节后追加《实现注记（Tn）》行（格式：本册口径 / 落地实现 / 理由）
- [ ] README/AGENTS 等收尾（若有）

## 6. 环境与踩坑（复现成本最高，务必先读）

- **Docker**：Desktop 已装已运行，CLI 不在 PATH → 每条命令前置 `$env:PATH = "C:\Users\11929\AppData\Local\Programs\DockerDesktop\resources\bin;$env:PATH"`；实测 client=server=29.8.0。
- **Maven 本地仓库在工作区外** `D:\DevTools\MAVEN_REPOSITORY`（会话已是 danger-full-access；无审批弹窗，`sandbox_permissions` 一律不要设）。
- **跑 verify 前先杀残留 JVM**（子代理起的 `java -jar …e-aio-app*.jar` 会锁 jar → `Unable to rename …jar.original`）：
  `Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like '*e-aio*target*jar*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }`
- **`mvn -pl <module>` 必须加 `-am`**（common 未 install 到本地库）；`test-compile` 增量不重编旧 `target/test-classes`（症状 `NoSuchMethodError`）→ 删 `target/test-classes`，别 `mvn clean`（会与并发子代理互删）。
- **IT 结束后 fork JVM 30s 才退、被 Surefire 强杀**：已知噪音，已登记 `docs/agents/build-and-test.md`，不是断言失败。
- **PowerShell 陷阱**：函数内 `Write-Output` 的日志会被赋值语句一起捕获（曾把垃圾写进 12 张 issue 正文）→ 日志走 `[Console]::Error.WriteLine()`；写文件用 here-string 单引号；`git add -A` 会拾起 `.tmp-*` 临时文件。
- **本机没有 python**（`python` exit 9009）；YAML 校验用 `F:/e-aio/frontend/node_modules/js-yaml`。
- **前端测试是 vitest**（`npm test`，不是 `node --test`）；三道门禁 = `npm run lint` / `npm test` / `npm run build`。
- **checkstyle 门禁已启用**（`backend/e-aio/config/checkstyle/checkstyle.xml`，绑 `validate`）：行宽 ≤140、无 star import/未用 import、字符串常量在 equals 左侧、`if` 必带大括号、无 tab/行尾空白、文件末尾换行。
- **Modulith 命名接口**：platform 根包 `@ApplicationModule(allowedDependencies = {"common", "common::api"})`——只写 `"common"` 时引用 `com.eaio.common.redis` 会报 “depends on named interface(s) 'common :: api'”。
- **MyBatis-Plus + PG**：`@TableLogic` 默认生成 `deleted = 0`（MySQL 口径）→ 必须配 `logic-delete-value: "true"`/`logic-not-delete-value: "false"`；模板必须带 Schema 限定 `eaio_platform.<table>`。
- **Flyway**：`${user.home}` 会被当自家占位符 → `ModuleFlywayConfig` 已 `.placeholders(Map.of("user.home", "${user.home}"))`；种子用 `R__platform_seed.sql` + `ON CONFLICT DO NOTHING` + 固定 ID 区间（参数 1–10 / 字典类型 21–24 / 字典项 31–45 / 任务 51–56 / 事件参数 61–63 / 模板 71+），**新增票不得占用已用区间**。
- **时区**：pgjdbc 的 URL `TimeZone=UTC`/`options=-c timezone=UTC` 均被静默忽略 → 用 Hikari `connection-init-sql: "SET TIME ZONE 'UTC'"`（P1-3 册 L10 已按实测更正）。
- **IT 基建**：`IntegrationTestBase` 用 **JVM 内单例容器**（不挂 `@Container`——Spring Test 复用/重启 context 时会连已停容器端口）；各 IT 类**共享**库与 Redis，断言不要假设独占空库。
- **写测试的假绿灯**：本轮出现过 6 个单测类 2 分钟落盘、全是真测但其中一条硬编码日期必红——**别只看“文件存在/测试变绿”，要读断言本身**；反例探针必须落在规则真正匹配的包内，否则靠“空作用面”假通过。

## 7. 子代理派工模板（本轮已验证有效）

每票 brief 必含：① 票面 5 条验收原文；② 设计册**行号锚点**（§、DDL、5.2 端点、5.3 DTO、错误码段、7.3 权限点、迁移与种子 ID）；③ 我预先下的裁决（歧义处）；④ 硬约束：五层分包 / Controller 放 `infrastructure/web` / `api` 不含实体与 Mapper / MapStruct `unmappedTargetPolicy=ERROR` / 仓储 `ObjectProvider<Mapper>` 无库可启动 / checkstyle / 跨模块端口缺席 fail-closed / **不得 git** / **不得 `mvn clean|verify`**（只 `compile` 与窄跑 test）；⑤ 要求回报：逐条验收→测试方法映射 + 原始门禁输出 + 未验证项 + 差异登记。
派工后我做的事：只读复核每件产物（读断言、读 SQL、读权限点字符串）→ 提一致性意见 → 权威 verify（隔离工作树）→ 提交 → 关票。

## 8. Suggested skills（接手时用 Skill 工具调用）

- `subagent-driven-development` —— 本项目的执行模型就是「每票一个子代理 + 我方只读复核 + 隔离工作树权威门禁」。
- `code-review` —— 每票收尾的双轴评审（Standards/Spec），本轮用它抓出 1 blocker + 3 high。
- `verification-before-completion` —— 提交/关票前的证据纪律（禁止把“写了配置”说成“已验证”）。
- `using-git-worktrees` —— 并发子代理下的隔离验证工作树。
- `systematic-debugging` —— 处理“IT 偶发红 / 上下文重启连旧端口 / 增量编译 `NoSuchMethodError`”这类症状时用。
- `caveman` —— 本会话开启了省流（caveman 超精简）模式，回复风格按此。

## 9. 不要碰

- `backend/e-aio/e-aio-platform/src/main/resources/db/migration/platform/V1__baseline.sql` 及任何**已发布**迁移（改 checksum 会让 Flyway 校验失败；只新增）。
- `com.eaio.platform.api` 里的 9 个接口签名与 DTO 字段名（契约 V1 冻结；要改先提 Issue 走评审）。
- 通用错误码段 10000/10001/10003/10401/10403/10500/10501/10502（platform 不得重定义）。
- 空号（20009/20019/20026–20029/…）**预留不回收**，不得新分配号。
- `README`/`LICENSE`/`NOTICE` 的知识产权声明（RuoYi MIT 蓝本出处）。
