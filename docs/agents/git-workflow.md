# 协作、提交与许可

- 仓库 `github.com/1010n111/e-aio`，主分支 `main`；Issue 驱动，接受 PR，语义化版本发布。
- **PR 门槛**：CI 全绿（门禁清单见 [build-and-test.md](build-and-test.md)）+ 至少 1 名维护者评审。
- **每轮提交**：每次对话产生代码或文档改动 → 收尾提交一次；一个主题一次提交（多主题拆多次提交），无改动不产生空提交。
- **批次基线 tag**：批次验收通过后打 `v0.<批次>.<序号>`（P0 = `v0.1.0-p0`，验收清单见 P0 册 5.2/5.3）；`NOTICE` 记录 RuoYi 蓝本 tag/commit。
- 提交信息语义化（中文或英文均可）：`docs: ...` / `feat(oa): ...` / `fix(common): ...`。
- 许可证：e-aio 自身 **Apache-2.0**；RuoYi-Vue / RuoYi-Vue3 蓝本为 **MIT**——P0 采用 clean-room 参考重写（不拷贝源码），故 `NOTICE` 只记上游 MIT 许可证名 + 蓝本 tag/commit 溯源；**一旦直接复用上游源码文件，必须在该文件保留其版权头并在 `LICENSE`/`NOTICE` 附上游许可证全文**。
- 提交前自查：无敏感信息（密钥/口令/内网地址）、无版权残留（不从闭源或其他开源工程粘贴未授权代码）。
- `.agents/skills/` 与 `skills-lock.json` 是本地 AI 工具链，已在 `.gitignore` 排除，不进 PR。
