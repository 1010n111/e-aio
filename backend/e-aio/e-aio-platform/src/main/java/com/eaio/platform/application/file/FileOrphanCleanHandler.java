package com.eaio.platform.application.file;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.eaio.platform.api.JobHandler;
import com.eaio.platform.api.dto.JobContext;
import com.eaio.platform.application.FileAppService;
import com.eaio.platform.domain.file.FileMetaFile;
import com.eaio.platform.infrastructure.persistence.FileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 内置任务 {@code platform.file.orphan.clean} 的处理点（P1 册 3.3.7、3.4.5 第 2 行；种子里 cron 为
 * {@code 0 30 3 * * *}，即每天 03:30）。
 *
 * <p>两件事，顺序不可换：
 * <ol>
 *   <li><b>软删未绑定的孤儿</b>：没有 {@code file_binding} 行、且创建时间超过
 *       {@code platform.file.orphan-retain-days}（默认 7 天）的文件 → 逻辑删除。7 天的意义是"上传完还没来得及
 *       绑定"的正常流程不该被清掉（用户上传附件到一半去倒水）；</li>
 *   <li><b>物理删超期的软删行</b>：软删时间超过 {@code platform.file.purge-days}（默认 30 天）→ 删盘上内容 +
 *       删绑定行 + 删库行。先软删再物理删的两段式是"误删可恢复"的全部依据（3.3.7 的取舍）。</li>
 * </ol>
 *
 * <p><b>分批且不无限循环</b>：每批 {@value #BATCH_SIZE} 行、单次最多 {@value #MAX_BATCHES} 批。积压巨大时
 * 剩下的留给明天，绝不在一个任务里把调度线程占死（与 {@code JobLogCleanHandler} 同款）。每批之间检查
 * {@link JobContext#expired()}：超时中断是"另一线程 cancel(true)"，长循环主动退出才是处理点的责任。
 *
 * <p><b>单行失败不炸整批</b>：某行的盘上内容删不掉（IO）时记 ERROR 并保留库行——下一轮会再试。
 * "<b>盘上删不掉但库里删了</b>"才是真孤儿（永远没人再认领），所以物理删的**顺序是盘 → 库**。
 */
@Component
public class FileOrphanCleanHandler implements JobHandler {

    /** 处理点编码（与 {@code job.handler_code}、种子 ID 53 逐字一致）。 */
    public static final String CODE = "platform.file.orphan.clean";

    /** 每批行数：批太大一次 DELETE 撑 WAL，批太小往返多。 */
    private static final int BATCH_SIZE = 500;

    /** 单次运行最多处理多少批（500 × 20 = 1 万行）；积压留给下一轮 cron。 */
    private static final int MAX_BATCHES = 20;

    private static final Logger log = LoggerFactory.getLogger(FileOrphanCleanHandler.class);

    private final FileStore store;
    private final FileAppService service;
    private final FileParams params;

    public FileOrphanCleanHandler(FileStore store, FileAppService service, FileParams params) {
        this.store = store;
        this.service = service;
        this.params = params;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public void execute(JobContext ctx) {
        Instant now = Instant.now();
        int softDeleted = softDeleteOrphans(ctx, now);
        int purged = purgeExpired(ctx, now);
        log.info("孤儿文件清理完成：jobCode={} runId={} 软删={} 物理删={}（门槛：未绑定 {} 天软删 / 软删 {} 天物理删）",
                ctx.jobCode(), ctx.runId(), softDeleted, purged, params.orphanRetainDays(), params.purgeDays());
    }

    /** 第 1 步：未绑定且超过保留期的文件 → 逻辑删除。 */
    private int softDeleteOrphans(JobContext ctx, Instant now) {
        Instant before = now.minus(Duration.ofDays(params.orphanRetainDays()));
        int deleted = 0;
        for (int batch = 0; batch < MAX_BATCHES && !ctx.expired(); batch++) {
            List<FileMetaFile> candidates = store.unboundBefore(before, BATCH_SIZE);
            if (candidates.isEmpty()) {
                break;
            }
            for (FileMetaFile row : candidates) {
                // 扫描与软删之间可能刚被业务绑定：软删前再确认一次"仍然没有绑定"（宁可漏清一轮，不可误删）
                if (store.activeBindingCount(row.getId()) > 0) {
                    continue;
                }
                try {
                    // 软删时间 = updated_at（清理任务按它算 purge-days）；服务层同时登记事件与审计。
                    if (service.softDeleteOrphan(row, now)) {
                        deleted++;
                        log.info("孤儿文件已软删（未绑定且超过 {} 天）：fileId={}", params.orphanRetainDays(), row.getId());
                    }
                } catch (RuntimeException e) {
                    log.error("孤儿文件软删失败，保留库行待下一轮重试：fileId={}", row.getId(), e);
                }
            }
            if (candidates.size() < BATCH_SIZE) {
                break;
            }
        }
        return deleted;
    }

    /** 第 2 步：软删超过 {@code purge-days} 的行 → 删盘 + 删绑定 + 删库行。 */
    private int purgeExpired(JobContext ctx, Instant now) {
        Instant before = now.minus(Duration.ofDays(params.purgeDays()));
        int purged = 0;
        for (int batch = 0; batch < MAX_BATCHES && !ctx.expired(); batch++) {
            List<FileMetaFile> candidates = store.purgeableBefore(before, BATCH_SIZE);
            if (candidates.isEmpty()) {
                break;
            }
            int handled = 0;
            for (FileMetaFile row : candidates) {
                if (!purgeRow(row)) {
                    continue;
                }
                handled++;
                purged++;
            }
            if (handled < BATCH_SIZE) {
                break;
            }
        }
        return purged;
    }

    /** 物理删一行：盘 → 绑定 → 库（顺序见类注释）；盘删失败即放弃这一行（下一轮再试）。 */
    private boolean purgeRow(FileMetaFile row) {
        try {
            service.purgeContent(row);
        } catch (RuntimeException e) {
            log.error("删除盘上文件失败，保留库行待下一轮重试：fileId={} storagePath={}",
                    row.getId(), row.getStoragePath(), e);
            return false;
        }
        store.purgeBindings(List.of(row.getId()));
        if (store.purgeById(row.getId()) == 0) {
            // 并发下另一个实例已经删过这一行：不是错误（物理清理幂等）
            log.debug("库行已被其他实例删除：fileId={}", row.getId());
        }
        return true;
    }
}
