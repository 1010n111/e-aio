package com.eaio.platform.infrastructure.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaio.common.exception.SystemException;
import com.eaio.platform.api.dto.FileQuery;
import com.eaio.platform.domain.file.FileBinding;
import com.eaio.platform.domain.file.FileMetaFile;
import com.eaio.platform.domain.file.FileChunk;
import com.eaio.platform.domain.file.FileUploadSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 文件中心的持久化门面（P1 册 3.3.2 的分层：application 不直接见 Mapper）。
 *
 * <p>与 {@code DictStore}/{@code ParamStore} 同一口径：Mapper 用 {@link ObjectProvider} 注入，
 * 无数据库时必须在**真去读写时**以 {@code SystemException} 明确失败（10500），而不是装配期炸掉
 * 或静默返回空列表——"空的应用也能启动"与"不允许静默假成功"是两条并存的口径。
 *
 * <p>排序字段走**白名单**（5.3 的分页公共入参）：非法值忽略并 WARN，绝不把调用方给的字符串拼进 SQL。
 *
 * <p>{@code file_binding} **没有 {@code deleted} 列**（P1-3 册 4.2 的统一列例外清单："关系行，解绑即物理删"），
 * 所以绑定相关的查询与删除都没有逻辑删除条件。
 */
@Component
public class FileStore {

    /** 排序列白名单：DTO 字段名 → 实体列引用。 */
    private static final Map<String, SFunction<FileMetaFile, ?>> ORDER_COLUMNS = Map.of(
            "originalName", FileMetaFile::getOriginalName,
            "sizeBytes", FileMetaFile::getSizeBytes,
            "createdAt", FileMetaFile::getCreatedAt,
            "uploaderId", FileMetaFile::getUploaderId,
            "source", FileMetaFile::getSource,
            "id", FileMetaFile::getId);

    private static final Logger log = LoggerFactory.getLogger(FileStore.class);

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final ObjectProvider<FileMapper> files;
    private final ObjectProvider<FileBindingMapper> bindings;
    private final ObjectProvider<FileUploadSessionMapper> sessions;
    private final ObjectProvider<FileChunkMapper> chunks;

    public FileStore(ObjectProvider<FileMapper> files, ObjectProvider<FileBindingMapper> bindings,
            ObjectProvider<FileUploadSessionMapper> sessions, ObjectProvider<FileChunkMapper> chunks) {
        this.files = files;
        this.bindings = bindings;
        this.sessions = sessions;
        this.chunks = chunks;
    }

    // ---------------------------------------------------------------- 文件行

    /** 按 ID 取未删除行（未找到或已软删返回 {@code null}）。 */
    public FileMetaFile rowById(long id) {
        return files().selectById(id);
    }

    /** 新增文件行。 */
    public int insert(FileMetaFile row) {
        return files().insert(row);
    }

    /**
     * 按主键更新（{@code @Version} 乐观锁：版本不匹配返回 0）。
     *
     * <p><b>不要用它来软删</b>：{@code deleted} 是 {@code @TableLogic} 列，MyBatis-Plus 在 {@code updateById}
     * 的 SET 子句里**不会**带上它（实测：UPDATE 正常执行、但 deleted 不变，成了静默无效的软删）。
     * 软删走 {@link #softDelete(FileMetaFile)}。
     */
    public int updateById(FileMetaFile row) {
        return files().updateById(row);
    }

    /**
     * 软删（{@code deleted = true} + {@code updated_at = 软删时间}）：走逻辑删除 API（{@code deleteById}），
     * 它会写 {@code deleted} 列并带上 {@code @Version} 条件。
     *
     * <p>{@code updated_at} 就是清理任务用的"软删时间"（表里没有 {@code deleted_at} 列，4.3.5），
     * 因此调用方必须先把它设好；返回 0 = 行已被别人改过（或已删）。
     */
    public int softDelete(FileMetaFile row) {
        return files().deleteById(row);
    }

    /** 物理删除（仅孤儿清理用；调用前必须先删绑定行，否则外键拦住）。 */
    public int purgeById(long id) {
        return files().purgeById(id);
    }

    /** 分页（管理页）：条件 + 白名单排序；{@code bizType}/{@code bizId} 经绑定表关联过滤。 */
    public IPage<FileMetaFile> page(FileQuery query) {
        FileQuery actual = query == null
                ? new FileQuery(null, null, null, null, null, null, null, null, null, null, null, null)
                : query;
        LambdaQueryWrapper<FileMetaFile> wrapper = new LambdaQueryWrapper<>();
        if (actual.originalName() != null && !actual.originalName().isBlank()) {
            wrapper.like(FileMetaFile::getOriginalName, actual.originalName().trim());
        }
        if (actual.uploaderId() != null) {
            wrapper.eq(FileMetaFile::getUploaderId, actual.uploaderId());
        }
        if (actual.uploaderOrgId() != null) {
            wrapper.eq(FileMetaFile::getUploaderOrgId, actual.uploaderOrgId());
        }
        if (actual.source() != null && !actual.source().isBlank()) {
            wrapper.eq(FileMetaFile::getSource, actual.source().trim().toUpperCase(Locale.ROOT));
        }
        if (actual.createdFrom() != null) {
            wrapper.ge(FileMetaFile::getCreatedAt, actual.createdFrom());
        }
        if (actual.createdTo() != null) {
            wrapper.le(FileMetaFile::getCreatedAt, actual.createdTo());
        }
        applyBizFilter(wrapper, actual);
        if (!applyOrder(wrapper, actual.orderBy(), actual.orderDir())) {
            wrapper.orderByDesc(FileMetaFile::getCreatedAt).orderByDesc(FileMetaFile::getId);
        }
        return files().selectPage(new Page<>(pageNum(actual.pageNum()), pageSize(actual.pageSize())), wrapper);
    }

    /** 候选孤儿（未绑定 + 超期）：由显式 SQL 承担（要跨表 NOT EXISTS，且必须看见软删语义的边界）。 */
    public List<FileMetaFile> unboundBefore(Instant before, int limit) {
        return files().selectUnboundBefore(before, limit);
    }

    /** 仍有未删除绑定的数量（软删前的复核）。 */
    public long activeBindingCount(long fileId) {
        return files().countActiveBindings(fileId);
    }

    /** 软删且超期的行（物理清理候选）。 */
    public List<FileMetaFile> purgeableBefore(Instant before, int limit) {
        return files().selectPurgeableBefore(before, limit);
    }

    // ---------------------------------------------------------------- 绑定行

    /** 绑定（幂等）：返回 true 表示这次真的插入了新行。 */
    public boolean bindIfAbsent(FileBinding binding) {
        return bindings().bindIfAbsent(binding.getId(), binding.getFileId(), binding.getBizType(),
                binding.getBizId(), binding.getCreatedAt(), binding.getCreatedBy()) > 0;
    }

    /** 这批文件里已绑定到该业务对象的 ID。 */
    public Set<Long> boundFileIds(String bizType, long bizId, Collection<Long> fileIds) {
        return Set.copyOf(bindings().selectBoundFileIds(bizType, bizId, fileIds));
    }

    /** 按业务对象反查文件 ID。 */
    public List<Long> fileIdsByBiz(String bizType, long bizId) {
        return bindings().selectFileIdsByBiz(bizType, bizId);
    }

    /** 按 ID 批量取未删除文件（绑定前的存在性校验；一次查完，避免逐行 select）。 */
    public List<FileMetaFile> rowsByIds(Collection<Long> ids) {
        return files().selectBatchIds(ids);
    }

    /** 物理删除一批文件的绑定行（孤儿清理的前置步骤）。 */
    public int purgeBindings(Collection<Long> fileIds) {
        return bindings().purgeByFileIds(fileIds);
    }

    // ---------------------------------------------------------- 分片会话

    public FileUploadSession sessionByUploadId(String uploadId) {
        return sessions().selectByUploadId(uploadId);
    }

    public int insertSession(FileUploadSession session) {
        return sessions().insert(session);
    }

    public int claimMerge(FileUploadSession session, Instant now) {
        return sessions().claimMerge(session.getId(), now);
    }

    public int reopenMerge(FileUploadSession session, Instant now) {
        return sessions().reopenMerge(session.getId(), now);
    }

    public int markSessionDone(FileUploadSession session, long fileId, Instant now) {
        return sessions().markDone(session.getId(), fileId, now);
    }

    public int expireSessions(Instant now) {
        return sessions().expireBefore(now);
    }

    public int expireSession(FileUploadSession session, Instant now) {
        return sessions().expireOne(session.getId(), now);
    }

    public List<FileUploadSession> expiredSessionsBefore(Instant before, int limit) {
        return sessions().selectExpiredBefore(before, limit);
    }

    public int deleteSession(long sessionId) {
        return sessions().deleteById(sessionId);
    }

    public int upsertChunk(FileChunk chunk) {
        return chunks().upsert(chunk);
    }

    public List<FileChunk> chunksFor(long sessionId) {
        return chunks().selectBySessionId(sessionId);
    }

    public int countChunks(long sessionId) {
        return chunks().countBySessionId(sessionId);
    }

    public int deleteChunks(long sessionId) {
        return chunks().deleteBySessionId(sessionId);
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 业务过滤：{@code bizType} + {@code bizId} 成对时经绑定表关联。
     *
     * <p>只给一个按"没给"处理：只给 {@code bizType} 会退化成"该业务类型下任何对象的文件"，那不是调用方想要的。
     * 关联用**两次查询**（先取该业务对象下的 fileId 集合，再让主查询 {@code IN}）而不是 JOIN：绑定表按
     * {@code (biz_type, biz_id)} 有索引，业务对象的附件量级是几十条；JOIN 会让分页 count 也要去重。
     */
    private void applyBizFilter(LambdaQueryWrapper<FileMetaFile> wrapper, FileQuery query) {
        String bizType = query.bizType() == null ? null : query.bizType().trim();
        if (bizType == null || bizType.isEmpty() || query.bizId() == null) {
            return;
        }
        List<Long> ids = fileIdsByBiz(bizType, query.bizId());
        if (ids.isEmpty()) {
            // 没有任何绑定：用恒假条件而不是"跳过条件"，否则会退化成全表分页（越权看到全部文件）
            wrapper.eq(FileMetaFile::getId, -1L);
            return;
        }
        wrapper.in(FileMetaFile::getId, ids);
    }

    /** 应用白名单排序；返回是否真的应用了（未应用时由调用方补默认排序）。 */
    private static boolean applyOrder(LambdaQueryWrapper<FileMetaFile> wrapper, String orderBy, String orderDir) {
        SFunction<FileMetaFile, ?> column = ORDER_COLUMNS.get(orderBy == null ? "" : orderBy.trim());
        if (column == null) {
            if (orderBy != null && !orderBy.isBlank()) {
                log.warn("忽略非白名单排序列 orderBy={}（登记白名单：{}）", orderBy, ORDER_COLUMNS.keySet());
            }
            return false;
        }
        boolean asc = !"desc".equalsIgnoreCase(orderDir == null ? "" : orderDir.trim());
        wrapper.orderBy(true, asc, column);
        return true;
    }

    private static long pageNum(Integer requested) {
        return requested == null || requested < 1 ? 1L : requested;
    }

    private static long pageSize(Integer requested) {
        if (requested == null) {
            return DEFAULT_PAGE_SIZE;
        }
        return requested < 1 || requested > MAX_PAGE_SIZE ? DEFAULT_PAGE_SIZE : requested;
    }

    private FileMapper files() {
        FileMapper mapper = files.getIfAvailable();
        if (mapper == null) {
            throw new SystemException("文件中心不可用：未配置数据库（无 DataSource/FileMapper）。"
                    + "文件元数据读必须连库，不能静默返回空列表");
        }
        return mapper;
    }

    private FileBindingMapper bindings() {
        FileBindingMapper mapper = bindings.getIfAvailable();
        if (mapper == null) {
            throw new SystemException("文件中心不可用：未配置数据库（无 DataSource/FileBindingMapper）");
        }
        return mapper;
    }

    private FileUploadSessionMapper sessions() {
        FileUploadSessionMapper mapper = sessions.getIfAvailable();
        if (mapper == null) {
            throw new SystemException("文件中心不可用：未配置数据库（无 FileUploadSessionMapper）");
        }
        return mapper;
    }

    private FileChunkMapper chunks() {
        FileChunkMapper mapper = chunks.getIfAvailable();
        if (mapper == null) {
            throw new SystemException("文件中心不可用：未配置数据库（无 FileChunkMapper）");
        }
        return mapper;
    }
}
