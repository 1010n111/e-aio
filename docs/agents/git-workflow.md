# 协作、提交与许可

- 仓库 `github.com/1010n111/e-aio`，主分支 `main`；Issue 驱动，接受 PR，语义化版本发布。
- **PR 门槛**：CI 全绿（门禁清单见 [build-and-test.md](build-and-test.md)）+ 至少 1 名维护者评审。
- 提交信息语义化（中文或英文均可）：`docs: ...` / `feat(oa): ...` / `fix(common): ...`，一次提交一个主题。
- 许可证：e-aio 自身 **Apache-2.0**；RuoYi-Vue / RuoYi-Vue3 蓝本为 **MIT**（保留其版权声明与 LICENSE）。
- 提交前自查：无敏感信息（密钥/口令/内网地址）、无版权残留（不从闭源或其他开源工程粘贴未授权代码）。
- `.agents/skills/` 与 `skills-lock.json` 是本地 AI 工具链，已在 `.gitignore` 排除，不进 PR。
