/**
 * 幂等键：只在用户动作发生时生成一次。
 *
 * - 生成必须用 `crypto.randomUUID()`（浏览器原生，无依赖）；
 * - 键经 `config.headers` 走请求头，**不入 body、不落 localStorage**：
 *   落存储会让"同一次动作的重试"与"下一次动作"拿到同一个键，反而制造假重放。
 */
export function newIdempotencyKey() {
  if (typeof crypto === 'undefined' || typeof crypto.randomUUID !== 'function') {
    throw new Error('当前运行环境不支持 crypto.randomUUID，无法生成幂等键')
  }
  return crypto.randomUUID()
}

/**
 * 为请求挂上幂等键。调用方（业务动作入口）负责生成键并在重试时复用同一个键；
 * 这里只保证"没有键就不发请求"，不从存储或全局状态里偷偷补一个。
 */
export function withIdempotencyKey(config, key) {
  if (!key) {
    throw new Error('缺少幂等键：写操作必须由调用方生成一次并复用')
  }
  return { ...config, headers: { ...config.headers, 'X-Idempotency-Key': key } }
}

/**
 * 同一次提交的键复用规则（api-conventions：重试复用、内容变了换新）。
 *
 * 调用方把上一次的 `{key, signature}` 原样传回来：
 * - 载荷与上次**完全相同**（用户点了重试）→ 复用 `key`，后端按幂等键判重放；
 * - 载荷变了（用户改了内容/换了行）→ 生成新键，这是一次新动作。
 *
 * 键只活在调用方的内存里，不落存储（见文件头）。
 */
export function idempotencyKeyFor(previous, payload) {
  const signature = JSON.stringify(payload ?? null)
  if (previous?.key && previous.signature === signature) {
    return { key: previous.key, signature }
  }
  return { key: newIdempotencyKey(), signature }
}

/**
 * 一次写动作的键生成器：把上面的规则包成"每次提交调一次"的入口，页面不必自己记状态。
 *
 * 用法：`const attempt = keyForSubmit.next(payload)` → `action(path, payload, { idempotencyKey: attempt.key })`；
 * 成功后调 `keyForSubmit.reset()`，下一次动作必然换新键（否则"删完再删同一条"会被后端当重放吃掉）。
 */
export function newIdempotencyScope() {
  let previous = { key: null, signature: null }
  return {
    next(payload) {
      previous = idempotencyKeyFor(previous, payload)
      return previous
    },
    reset() {
      previous = { key: null, signature: null }
    },
  }
}
