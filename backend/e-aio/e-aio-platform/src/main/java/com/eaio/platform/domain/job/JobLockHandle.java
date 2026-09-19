package com.eaio.platform.domain.job;

/**
 * 执行令牌（域内抽象，P1 册 3.4.3 的 ShedLock 锁在领域层的投影）：
 * **拿到它就代表"本实例此刻独占该任务"，跑完必须释放**。
 *
 * <p>为什么要有这层抽象：锁的实现在 {@code infrastructure/scheduler}（ShedLock），而"什么时候该拿锁、
 * 拿到之后谁来放"是 application 层的事（任务体、重投扫描、手动触发三条路径共用同一把锁）。
 * 让 application 直接持有 {@code SimpleLock} 会把基础设施类型漏进用例层，因此只暴露"释放"这一个动作
 * ——框架在 {@code finally} 里放锁，不给调用方"忘了放"的机会。
 *
 * <p>放锁的失败**不抛给业务**（锁到期会自然失效，ShedLock 的 provider 自己重试 10 次）：
 * 一次任务执行不该因为"放锁时数据库抖了一下"被记成失败。
 */
@FunctionalInterface
public interface JobLockHandle {

    /** 释放；重复释放/释放失败只记日志（幂等语义由调用方保证：每个 handle 只放一次）。 */
    void release();
}
