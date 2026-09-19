package com.eaio.platform.api;

import com.eaio.platform.api.dto.JobContext;

/**
 * 任务处理点（P1 册 3.4.2）：业务模块实现本接口并声明为 Bean，注册表在启动期收集它们
 * ——**可执行点的唯一来源就是这里**（DB 里的字符串永远不会被执行，3.4.1）。
 *
 * <p><b>实现约定</b>：
 * <ol>
 *   <li>{@link #code()} 必须与 {@code job.handler_code} 逐字一致；同一 code 注册两次 = 配置错误，
 *       启动直接失败（{@code IllegalStateException}，消息含 code），不留到运行期才发现；</li>
 *   <li>{@link #execute(JobContext)} 抛异常 = 本次执行失败，由框架记 {@code job_run(FAILED)} 并按指数
 *       退避重投（3.4.4）——处理点不需要自己 try/catch，也不要自己重试；</li>
 *   <li><b>超时是中断语义</b>：到 {@link JobContext#deadline()} 时执行线程被 {@code interrupt}，
 *       状态记 {@code TIMEOUT}。长循环处理点应每 N 次迭代检查一次
 *       {@code Thread.currentThread().isInterrupted() || ctx.expired()} 并主动退出，
 *       否则只能等中断落在可中断点上（这是处理点的责任，框架替代不了）；</li>
 *   <li>处理点不碰 {@code job}/{@code job_run} 表：元数据与运行事实由框架维护（同一个任务被多实例并发
 *       调度时的互斥也由框架的 ShedLock 负责）。</li>
 * </ol>
 */
public interface JobHandler {

    /** 处理点编码（与 {@code job.handler_code} 一致）。 */
    String code();

    /** 执行一次；抛异常即失败，返回即成功。 */
    void execute(JobContext ctx);
}
