/**
 * Redis 门面（P0 册 4.4 冻结的对外 API / P1-2 册 2.7 补齐的第一批资产）。
 *
 * <p>两个类型，职责不重叠：
 * <ul>
 *   <li>{@link com.eaio.common.redis.RedisKeys}：**键的唯一构造点**，禁止业务侧散落拼串
 *       （键规范 {@code eaio:{env}:{biz}:{id}}）；</li>
 *   <li>{@link com.eaio.common.redis.RedisKit}：读写门面（缓存读写、失效、轻量队列、SETNX 占位）。</li>
 * </ul>
 *
 * <p><b>底座口径</b>：本包只声明 JDK 类型的方法签名（通用能力层零运行时依赖、且
 * {@code ArchitectureTest#commonIsPure} 禁止把 {@code org.springframework.data..} 带进来），
 * Redis 客户端实现由装配层提供。分布式锁与限流器（{@code DistributedLock}/{@code RateLimiter}）
 * 随其首个使用方引入，不在本批范围。
 *
 * <p>本包与 common 其余对外包（{@code api}/{@code exception}/{@code json}/{@code util}/{@code id}）
 * 一样按 {@code @NamedInterface("api")} 显式暴露：未标注 {@code @NamedInterface} 的包会被 Modulith
 * 判为"非暴露类型"，跨模块引用直接构建失败。
 */
@org.springframework.modulith.NamedInterface("api")
package com.eaio.common.redis;
