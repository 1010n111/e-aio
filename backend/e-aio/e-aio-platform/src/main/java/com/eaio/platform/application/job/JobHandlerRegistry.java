package com.eaio.platform.application.job;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.eaio.common.exception.BusinessException;
import com.eaio.platform.api.JobHandler;
import com.eaio.platform.api.PlatformErrorCode;
import org.springframework.stereotype.Component;

/**
 * 处理点注册表（P1 册 3.4.2 的 {@code JobHandlerRegistry}）：启动期扫描全部 {@link JobHandler} Bean，
 * 建 {@code Map<code, handler>}。
 *
 * <p>三条硬口径：
 * <ol>
 *   <li><b>可执行点只能来自这里</b>——{@code job.handler_code} 只是一个键，解析它的唯一途径是本类。
 *       按 DB 字符串反射调用任意 Bean 方法是明确否决的（3.4.1，RCE 面：能写 {@code job} 表就能执行任意
 *       方法）；</li>
 *   <li><b>重复 code 直接启动失败</b>（{@code IllegalStateException}，消息含 code）：同一处理点被两个
 *       模块/两个 Bean 声明是配置错误，必须在启动期暴露——留到运行期就是"跑的是哪一个全看容器顺序"；</li>
 *   <li><b>{@code require(code)} 未命中抛 20023</b>（保存/启用/手动触发/重试四条路径共用同一判定）。</li>
 * </ol>
 *
 * <p>空集合是**合法**的：无库无任务的空应用（P0 冻结的启动口径）里一个处理点也没有，注册表为空、
 * 调度器不建任何任务——不是错误。
 */
@Component
public class JobHandlerRegistry {

    private final Map<String, JobHandler> handlers;

    public JobHandlerRegistry(List<JobHandler> beans) {
        Map<String, JobHandler> collected = new LinkedHashMap<>();
        for (JobHandler handler : beans) {
            String code = handler.code() == null ? "" : handler.code().trim();
            if (code.isEmpty()) {
                throw new IllegalStateException("JobHandler 的 code() 为空：" + handler.getClass().getName());
            }
            JobHandler previous = collected.putIfAbsent(code, handler);
            if (previous != null) {
                throw new IllegalStateException("JobHandler code 重复：" + code + "（"
                        + previous.getClass().getName() + " 与 " + handler.getClass().getName()
                        + "）；处理点与任务是一对一关系，重复声明无法确定执行哪一个");
            }
        }
        this.handlers = Map.copyOf(collected);
    }

    /** 是否已注册该处理点（启动期跳过坏任务、重投前判定用）。 */
    public boolean contains(String code) {
        return code != null && handlers.containsKey(code.trim());
    }

    /** 取处理点（未注册返回空；调用方自己决定是跳过还是报 20023）。 */
    public Optional<JobHandler> find(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(handlers.get(code.trim()));
    }

    /** 取处理点，未注册抛 20023（3.4.2）。 */
    public JobHandler require(String code) {
        return find(code).orElseThrow(() -> new BusinessException(PlatformErrorCode.JOB_HANDLER_NOT_REGISTERED,
                "任务处理点未注册：" + code + "（可执行点只能来自代码里注册的 JobHandler Bean，3.4.1）"));
    }

    /** 已注册的处理点编码（诊断/测试用）。 */
    public Set<String> codes() {
        return handlers.keySet();
    }
}
