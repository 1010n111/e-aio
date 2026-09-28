package com.eaio.platform.application.file;

import com.eaio.platform.application.param.ParamResolver;
import com.eaio.platform.domain.param.ParamContext;
import org.springframework.stereotype.Component;

/**
 * 文件相关参数的读取口径（P1 册 7.2 的 {@code platform.file.*} 键）。
 *
 * <p>为什么单独一个类：键字符串与默认值散落在上传、下载、清理任务三处时，任何一处写错都只会静默退回
 * 默认值（把 {@code platform.file.orphan-retain-days} 写成 {@code platform.file.orphan.retain-days}
 * 在参数中心是**另一个键**，永远解析不到）。一处声明、一处默认值（与 {@code JobParams} 同款）。
 *
 * <p><b>三个键在 7.2 有、在种子里没有</b>（{@code session-retain-days}/{@code orphan-retain-days}/
 * {@code purge-days}）：设计册把它们列为参数键，但 {@code R__platform_seed.sql} 的 T4 种子只种了 6 个
 * {@code platform.file.*} 键。本类按 7.2 的键名与默认值读取——**读不到就用默认值**，种子里补上即生效，
 * 不需要改代码（补种子属于 T4 的范围，见《实现注记（T9）》）。
 *
 * <p>读取上下文一律 {@link ParamContext#systemOnly()}：这些都是平台级配置，没有"某组织保留 1 天"的语义。
 */
@Component
public class FileParams {

    /** 未绑定文件多少天后软删（7.2 默认 7；键未种，读默认值）。 */
    public static final String ORPHAN_RETAIN_DAYS = "platform.file.orphan-retain-days";

    /** 软删后多少天物理删除（7.2 默认 30；键未种，读默认值）。 */
    public static final String PURGE_DAYS = "platform.file.purge-days";

    /** 根目录默认值（与种子里的 {@code platform.file.local-root} 一致）。 */
    public static final String DEFAULT_LOCAL_ROOT = "${user.home}/.eaio/files";

    /** 上传可见性判定用的权限点（7.3；HTTP 面的 {@code @PreAuthorize} 与它逐字一致）。 */
    public static final String PERMISSION_DOWNLOAD = "platform:file:download";

    private static final int DEFAULT_ORPHAN_RETAIN_DAYS = 7;
    private static final int DEFAULT_PURGE_DAYS = 30;

    private final ParamResolver params;

    public FileParams(ParamResolver params) {
        this.params = params;
    }

    /** 未绑定文件的软删门槛（天）。 */
    public int orphanRetainDays() {
        return intValue(ORPHAN_RETAIN_DAYS, DEFAULT_ORPHAN_RETAIN_DAYS);
    }

    /** 软删行的物理删除门槛（天）。 */
    public int purgeDays() {
        return intValue(PURGE_DAYS, DEFAULT_PURGE_DAYS);
    }

    private int intValue(String key, int defaultValue) {
        String raw = params.resolveOrDefault(key, String.valueOf(defaultValue), ParamContext.systemOnly()).value();
        try {
            int parsed = Integer.parseInt(raw == null ? "" : raw.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            // 参数中心读的是人填的字符串：坏值回落默认值，不让清理任务直接 10500
            return defaultValue;
        }
    }
}
