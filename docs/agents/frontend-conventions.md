# 前端规范（Vue3 + Vite + Element Plus）

- 所有请求走 `src/api/request.js` 统一封装：POST + JSON、响应拦截。认证/鉴权失败按 `Result.code`（`10401` / `10403`）跳登录——**后端 HTTP 恒 200，不要按 401/403 判断**。
- API 文件按模块组织 `src/api/<module>/<resource>.js`，按动作词导出 `get` / `getPage` / `add` / `up` / `del`。
- 写接口必须带 `Idempotency-Key` 请求头（键规则见 [api-conventions.md](api-conventions.md)）。
- 复用 RuoYi-Vue3 既有能力（动态路由、权限指令 `v-hasPermi`、字典、水印），不重复造轮子。
- 目录：`src/api/`（含 `request.js`）、`src/auth/`、`src/views/`（P1 起按模块挂载）；`vite.config.js` 开发代理 → 后端，生产独立部署。
- 提交前过 `npm run lint`（ESLint）与 `npm run build`，见 [build-and-test.md](build-and-test.md)。
