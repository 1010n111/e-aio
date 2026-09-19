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
