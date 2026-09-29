package com.eaio.platform.application;

import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.IOException;
import java.util.Collections;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.eaio.common.api.ErrorCode;
import com.eaio.common.api.PageResult;
import com.eaio.common.exception.BusinessException;
import com.eaio.common.exception.SystemException;
import com.eaio.common.id.IdGenerator;
import com.eaio.platform.api.PlatformErrorCode;
import com.eaio.platform.api.dto.AuditRecord;
import com.eaio.platform.api.dto.FileBindCmd;
import com.eaio.platform.api.dto.FileChunkCmd;
import com.eaio.platform.api.dto.FileChunkResult;
import com.eaio.platform.api.dto.FileDTO;
import com.eaio.platform.api.dto.FileDelCmd;
import com.eaio.platform.api.dto.FileDownloadCmd;
import com.eaio.platform.api.dto.FileMergeCmd;
import com.eaio.platform.api.dto.FileQuery;
import com.eaio.platform.api.dto.FileUploadCmd;
import com.eaio.platform.api.dto.FileUrlDTO;
import com.eaio.platform.api.port.AuditPort;
import com.eaio.platform.application.event.PlatformEventPublisher;
import com.eaio.platform.application.file.AuditFallbackRecorder;
import com.eaio.platform.application.file.FileAccessGuard;
import com.eaio.platform.application.file.FileDownloadResource;
import com.eaio.platform.application.file.FileDtoMapper;
import com.eaio.platform.application.file.FileParams;
import com.eaio.platform.application.file.FileStorageErrorRecorder;
import com.eaio.platform.application.file.SizeLimitedInputStream;
import com.eaio.platform.domain.file.FileBinding;
import com.eaio.platform.domain.file.FileMetaFile;
import com.eaio.platform.domain.file.FileChunk;
import com.eaio.platform.domain.file.FileUploadSession;
import com.eaio.platform.domain.file.FileNames;
import com.eaio.platform.domain.file.FileTypePolicy;
import com.eaio.platform.events.FileDeletedEvent;
import com.eaio.platform.events.FileUploadedEvent;
import com.eaio.platform.infrastructure.persistence.FileStore;
import com.eaio.platform.infrastructure.storage.FilePresignTokenService;
import com.eaio.platform.infrastructure.storage.FileStorage;
import com.eaio.platform.infrastructure.storage.FileStorageConfig;
import com.eaio.platform.infrastructure.storage.LocalFileStorage;
import com.eaio.platform.infrastructure.storage.StoredFileMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文件中心应用服务（P1 册 3.3.2）：上传/下载/预签名/删除/绑定/分页，REST 面与跨模块 {@code FileApi}
 * 共用同一个服务（裁决 2.4.3）。
 *
 * <p><b>四条硬口径</b>：
 * <ol>
 *   <li><b>信任边界</b>（3.3.5）：声明大小 → 扩展名白名单 → 实际字节数 → 流式超限中断 → 摘要服务端重算。
 *       客户端给的 {@code size}/{@code contentType}/文件名只作记录或前置早拒绝，落库的字节数与摘要一律以
 *       服务端统计为准；</li>
 *   <li><b>可见性只在一处</b>（{@link FileAccessGuard}）：HTTP 面判权限，跨模块 {@code download} 不判
 *       （调用方已鉴权，HLD 2.4.2）；</li>
 *   <li><b>留痕不阻断业务</b>（3.3.6）：审计端口缺席 → 结构化降级日志 + 计数；端口在但写失败 → ERROR +
 *       计数；两种情况都**不回滚**已完成的文件写入。但"拒绝下载"（20017）**必须**留痕——那是安全事件；</li>
 *   <li><b>删除 = 逻辑删除 + 事件</b>（3.3.7）：盘上文件由 {@code platform.file.orphan.clean} 按
 *       {@code purge-days} 物理清理，误删可恢复。</li>
 * </ol>
 *
 * <p><b>协议头处理</b>（3.3.4）不在这里：{@code Content-Disposition}/{@code nosniff}/{@code no-store}
 * 是 Web 层的职责，本类只交出"文件名 + 长度 + 流"（{@link FileDownloadResource}）。
 */
@Service
public class FileAppService {

    /** 来源（DDL 的 CHECK；普通上传、Excel 结果与错误文件分别使用对应值）。 */
    public static final String SOURCE_UPLOAD = "UPLOAD";
    static final String SOURCE_EXPORT = "EXPORT";
    static final String SOURCE_IMPORT_ERROR = "IMPORT_ERROR";

    /** 留痕动作词（平台内动作，audit 侧若要映射自己的字典由适配器负责）。 */
    private static final String ACTION_UPLOAD = "UPLOAD";
    private static final String ACTION_DOWNLOAD = "DOWNLOAD";
    private static final String ACTION_DOWNLOAD_DENIED = "DOWNLOAD_DENIED";
    private static final String ACTION_DELETE = "DELETE";
    private static final String ACTION_BIND = "BIND";
    private static final String ACTION_PURGE = "PURGE";

    /** 留痕结果词。 */
    private static final String RESULT_SUCCESS = "SUCCESS";
    private static final String RESULT_DENIED = "DENIED";
    private static final String RESULT_FAILED = "FAILED";

    private static final String RESOURCE_FILE = "FILE";

    /** 流式拷贝的缓冲区（8KB：与 JDK 的 Files.copy 同量级，够大又不会让内存驻留明显）。 */
    private static final int COPY_BUFFER_SIZE = 8 * 1024;
    private static final int MAX_CHUNK_TOTAL = 10_000;

    private static final Logger log = LoggerFactory.getLogger(FileAppService.class);

    private final FileStore store;
    private final Map<String, FileStorage> storages;
    private final String defaultStorageType;
    private final FileTypePolicy typePolicy;
    private final FileAccessGuard accessGuard;
    private final ObjectProvider<AuditPort> auditPort;
    private final AuditFallbackRecorder auditRecorder;
    private final PlatformEventPublisher publisher;
    private final IdGenerator idGenerator;
    private final LocalFileStorage localFileStorage;
    private final FileDtoMapper dtoMapper;
    private final FileParams fileParams;
    private final FileStorageErrorRecorder storageErrors;

    /** 兼容无 Spring 的文件服务单测；生产装配走带 FileParams 的构造器。 */
    public FileAppService(FileStore store, List<FileStorage> storages,
            FileStorageConfig.EaioFileProperties storageProperties, FileTypePolicy typePolicy,
            FileAccessGuard accessGuard, ObjectProvider<AuditPort> auditPort, AuditFallbackRecorder auditRecorder,
            PlatformEventPublisher publisher, IdGenerator idGenerator, LocalFileStorage localFileStorage,
            FileDtoMapper dtoMapper) {
        this(store, storages, storageProperties, typePolicy, accessGuard, auditPort, auditRecorder, publisher,
                idGenerator, localFileStorage, dtoMapper, null, null);
    }

    @Autowired
    public FileAppService(FileStore store, List<FileStorage> storages,
            FileStorageConfig.EaioFileProperties storageProperties, FileTypePolicy typePolicy,
            FileAccessGuard accessGuard, ObjectProvider<AuditPort> auditPort, AuditFallbackRecorder auditRecorder,
            PlatformEventPublisher publisher, IdGenerator idGenerator, LocalFileStorage localFileStorage,
            FileDtoMapper dtoMapper, FileParams fileParams, FileStorageErrorRecorder storageErrors) {
        this.store = store;
        this.storages = storages.stream().collect(Collectors.toMap(FileStorage::type, storage -> storage));
        this.defaultStorageType = storageProperties.storageTypeOrDefault();
        this.typePolicy = typePolicy;
        this.accessGuard = accessGuard;
        this.auditPort = auditPort;
        this.auditRecorder = auditRecorder;
        this.publisher = publisher;
        this.idGenerator = idGenerator;
        this.localFileStorage = localFileStorage;
        this.dtoMapper = dtoMapper;
        this.fileParams = fileParams;
        this.storageErrors = storageErrors == null ? new FileStorageErrorRecorder() : storageErrors;
        // 新写文件的适配器在构造期就确定：类型没登记（例如配成 S3 而适配器未实现）必须启动即失败，
        // 而不是等第一次上传才 20015（"不允许静默假成功"）
        requireStorage(defaultStorageType);
    }

    // ---------------------------------------------------------------- 上传

    /**
     * 单文件上传（3.3.3 的流程）：校验 → 落盘（边写边计数）→ 服务端重算摘要 → INSERT → 事件 → 留痕。
     *
     * <p><b>事务边界</b>：文件行与事件登记必须原子（5.4：{@code upload} 自己开独立事务），因此整个方法
     * 在一个事务里；事务提交后事件才投递（{@code PlatformEventPublisher} 的 {@code AFTER_COMMIT}）。
     * 留痕在最后一步且**失败不回滚**（3.3.6）——它在这里被 try/catch 包住，异常不会让事务回滚。
     *
     * <p><b>落盘与入库之间的失败都要清文件</b>：校验失败、{@code INSERT} 失败、事件登记失败（都会让事务
     * 回滚）时，盘上刚写下的文件必须删掉——孤儿清理任务是按 {@code file} 表的行做工的，**库里没有行
     * 就没有任何路径能清掉它**（永久磁盘泄漏）。反向的"行在但盘上悬空"由孤儿清理兜底。
     * 唯一兜不住的是**事务提交阶段**才失败：那时候文件已经属于一个已提交的行，交给孤儿清理即可，
     * 不为它加补偿逻辑。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FileDTO upload(FileUploadCmd cmd) {
        return upload(cmd, SOURCE_UPLOAD);
    }

    /** 文件中心内部生成文件的上传入口；不扩大冻结的 {@code FileApi} 契约。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FileDTO upload(FileUploadCmd cmd, String source) {
        if (source == null || !Set.of(SOURCE_UPLOAD, SOURCE_EXPORT, SOURCE_IMPORT_ERROR).contains(source)) {
            throw new IllegalArgumentException("非法文件来源：" + source);
        }
        String extension = typePolicy.validateUpload(cmd);
        long fileId = idGenerator.nextId();
        Instant now = Instant.now();
        StoredFileMeta meta = new StoredFileMeta(fileId, extension, cmd.contentType(), now);
        // 落盘失败（磁盘满/IO）→ SystemException → 20015；此时库里还没有行，没有"回滚 DB 行"要做
        long maxSizeBytes = typePolicy.maxSizeBytes();
        String storagePath;
        try {
            storagePath = storage().store(new SizeLimitedInputStream(cmd.content(), maxSizeBytes), meta);
        } catch (RuntimeException e) {
            throw storageFailure(null, e);
        }
        long sizeBytes;
        String sha256;
        try {
            sizeBytes = sizeOf(storagePath);
            if (sizeBytes == 0L) {
                throw new BusinessException(PlatformErrorCode.FILE_EMPTY, "上传文件为空（0 字节）");
            }
            if (sizeBytes > maxSizeBytes) {
                // 声明值没超、实际超了：流式计数拦下的那一类（3.3.5 第 4 条）
                throw new BusinessException(PlatformErrorCode.FILE_TOO_LARGE,
                        "文件超过大小上限：" + sizeBytes + " > " + maxSizeBytes);
            }
            sha256 = digestOf(storagePath);
        } catch (RuntimeException e) {
            // 落盘成功但校验失败：清掉刚落下的文件，别在盘上留半截（3.3.5 第 4 条）
            deleteQuietly(storagePath, e);
            throw storageFailure(storagePath, e);
        }

        Long operatorId = accessGuard.currentOperatorId();
        long orgId = orgIdOrZero();
        FileMetaFile row = new FileMetaFile();
        row.setId(fileId);
        row.setOriginalName(FileNames.normalizedOriginalName(cmd.fileName()));
        row.setExtension(extension.isEmpty() ? null : extension);
        row.setContentType(cmd.contentType());
        row.setSizeBytes(sizeBytes);
        row.setSha256(sha256);
        row.setStorageType(storage().type());
        row.setStoragePath(storagePath);
        row.setUploaderId(operatorId);
        row.setUploaderOrgId(orgId);
        row.setSource(source);
        row.setCreatedAt(now);
        row.setCreatedBy(operatorId);
        row.setVersion(0);
        row.setDeleted(false);
        try {
            store.insert(row);

            if (cmd.bizType() != null && !cmd.bizType().isBlank() && cmd.bizId() != null) {
                bindInternal(fileId, cmd.bizType(), cmd.bizId(), operatorId);
            }

            publisher.publish(new FileUploadedEvent(idGenerator.nextStr(), Instant.now(), fileId, cmd.bizType(),
                    cmd.bizId(), sizeBytes, sha256, operatorId, orgId));
        } catch (RuntimeException e) {
            // 入库/事件登记失败 → 事务回滚 → 库里不会留下这一行，盘上的文件必须一起清掉（见方法 javadoc）
            deleteQuietly(storagePath, e);
            throw e;
        }
        recordAudit(new AuditRecord(ACTION_UPLOAD, RESOURCE_FILE, fileId, cmd.bizType(), cmd.bizId(),
                operatorId, orgId, RESULT_SUCCESS, sizeBytes, sha256, MDC.get("traceId")));
        log.info("文件上传成功：fileId={} name={} size={} sha256={} storageType={}",
                fileId, row.getOriginalName(), sizeBytes, sha256, row.getStorageType());
        return toDto(row, null);
    }

    /** 上传一个分片；会话由首片创建，重复片用数据库唯一键 UPSERT。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FileChunkResult uploadChunk(FileChunkCmd cmd) {
        validateUploadId(cmd.uploadId());
        if (cmd.content() == null || cmd.chunkSha256() == null) {
            throw chunkInvalid("分片内容与 chunkSha256 必填");
        }
        Instant now = Instant.now();
        FileUploadSession session = store.sessionByUploadId(cmd.uploadId());
        if (session == null) {
            session = createSession(cmd, now);
        } else {
            requireSessionOwner(session);
            ensureSessionOpen(session, now);
            validateSessionShape(session, cmd);
        }
        if (cmd.chunkIndex() < 0 || cmd.chunkIndex() >= session.getChunkTotal()) {
            throw chunkInvalid("chunkIndex 超出范围：" + cmd.chunkIndex());
        }
        String expectedChunkHash = normalizeSha256(cmd.chunkSha256(), "chunkSha256");
        String path;
        try {
            path = localFileStorage.storeChunk(
                    new SizeLimitedInputStream(cmd.content(), session.getChunkSize()),
                    session.getUploadId(), cmd.chunkIndex());
        } catch (BusinessException e) {
            if (e.getCode() == PlatformErrorCode.FILE_TOO_LARGE.getCode()) {
                throw chunkInvalid("分片大小超过上限：" + session.getChunkSize());
            }
            throw e;
        }
        long actualSize;
        String actualHash;
        try (InputStream in = localFileStorage.openChunk(session.getUploadId(), cmd.chunkIndex())) {
            DigestAndSize digest = digestAndSize(in);
            actualSize = digest.size();
            actualHash = digest.sha256();
        } catch (IOException e) {
            localFileStorage.deleteChunk(session.getUploadId(), cmd.chunkIndex());
            throw storageError(new BusinessException(PlatformErrorCode.FILE_STORAGE_ERROR.getCode(), "分片读取失败", e));
        }
        if (actualSize == 0 || actualSize > session.getChunkSize()
                || (cmd.chunkIndex() < session.getChunkTotal() - 1 && actualSize != session.getChunkSize())
                || !expectedChunkHash.equals(actualHash)) {
            localFileStorage.deleteChunk(session.getUploadId(), cmd.chunkIndex());
            throw chunkInvalid("分片大小或摘要不匹配：chunkIndex=" + cmd.chunkIndex());
        }
        FileChunk chunk = new FileChunk();
        chunk.setId(idGenerator.nextId());
        chunk.setSessionId(session.getId());
        chunk.setChunkIndex(cmd.chunkIndex());
        chunk.setChunkSize((int) actualSize);
        chunk.setSha256(actualHash);
        chunk.setStoragePath(path);
        chunk.setCreatedAt(now);
        chunk.setCreatedBy(accessGuard.currentOperatorId());
        store.upsertChunk(chunk);
        return new FileChunkResult(session.getUploadId(), store.countChunks(session.getId()), session.getChunkTotal());
    }

    /** 按序拼接分片并创建普通 file 行；DONE 会话直接返回既有文件。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FileDTO mergeChunks(FileMergeCmd cmd) {
        validateUploadId(cmd.uploadId());
        Instant now = Instant.now();
        FileUploadSession session = store.sessionByUploadId(cmd.uploadId());
        if (session == null) {
            throw sessionMissing(cmd.uploadId());
        }
        requireSessionOwner(session);
        if ("DONE".equals(session.getStatus())) {
            return toDto(requireRow(session.getFileId()), null);
        }
        ensureSessionOpen(session, now);
        if (store.claimMerge(session, now) == 0) {
            FileUploadSession current = store.sessionByUploadId(cmd.uploadId());
            if (current != null && "DONE".equals(current.getStatus())) {
                return toDto(requireRow(current.getFileId()), null);
            }
            throw sessionMissing(cmd.uploadId());
        }
        List<FileChunk> chunks = store.chunksFor(session.getId());
        List<Integer> missing = missingIndexes(session, chunks);
        if (!missing.isEmpty()) {
            store.reopenMerge(session, now);
            throw chunkInvalid("缺少分片：" + missing);
        }
        long total = chunks.stream().mapToLong(chunk -> chunk.getChunkSize()).sum();
        if (total != session.getExpectedSize()) {
            store.reopenMerge(session, now);
            throw chunkInvalid("分片总大小不匹配：" + total + " != " + session.getExpectedSize());
        }
        long fileId = idGenerator.nextId();
        String extension = session.getExtension() == null ? "" : session.getExtension();
        String storagePath = null;
        try {
            List<InputStream> streams = new java.util.ArrayList<>(chunks.size());
            for (FileChunk chunk : chunks) {
                streams.add(localFileStorage.openChunk(session.getUploadId(), chunk.getChunkIndex()));
            }
            try (SequenceInputStream in = new SequenceInputStream(Collections.enumeration(streams))) {
                storagePath = storage().store(new SizeLimitedInputStream(in, session.getExpectedSize()),
                        new StoredFileMeta(fileId, extension, null, now));
            }
            DigestAndSize digest = digestOfWithSize(storagePath);
            if (digest.size() != session.getExpectedSize()
                    || (session.getSha256() != null && !session.getSha256().equalsIgnoreCase(digest.sha256()))) {
                store.reopenMerge(session, now);
                throw chunkInvalid("合并文件摘要或大小不匹配");
            }
            Long operatorId = session.getCreatedBy();
            FileMetaFile row = new FileMetaFile();
            row.setId(fileId);
            row.setOriginalName(session.getFileName());
            row.setExtension(extension.isEmpty() ? null : extension);
            row.setSizeBytes(digest.size());
            row.setSha256(digest.sha256());
            row.setStorageType(storage().type());
            row.setStoragePath(storagePath);
            row.setUploaderId(operatorId);
            row.setUploaderOrgId(orgIdOrZero());
            row.setSource("CHUNK");
            row.setCreatedAt(now);
            row.setCreatedBy(operatorId);
            row.setVersion(0);
            row.setDeleted(false);
            store.insert(row);
            if (cmd.bizType() != null && !cmd.bizType().isBlank() && cmd.bizId() != null) {
                bindInternal(fileId, cmd.bizType(), cmd.bizId(), operatorId);
            }
            publisher.publish(new FileUploadedEvent(idGenerator.nextStr(), Instant.now(), fileId, cmd.bizType(),
                    cmd.bizId(), digest.size(), digest.sha256(), operatorId, row.getUploaderOrgId()));
            if (store.markSessionDone(session, fileId, now) == 0) {
                throw new BusinessException(ErrorCode.DATA_CONFLICT,
                        "分片会话状态已变化，合并结果未提交：uploadId=" + cmd.uploadId());
            }
            for (FileChunk chunk : chunks) {
                localFileStorage.deleteChunk(session.getUploadId(), chunk.getChunkIndex());
            }
            store.deleteChunks(session.getId());
            recordAudit(new AuditRecord(ACTION_UPLOAD, RESOURCE_FILE, fileId, cmd.bizType(), cmd.bizId(),
                    operatorId, row.getUploaderOrgId(), RESULT_SUCCESS, digest.size(), digest.sha256(),
                    MDC.get("traceId")));
            return toDto(row, null);
        } catch (IOException e) {
            store.reopenMerge(session, now);
            BusinessException failure = storageError(new BusinessException(PlatformErrorCode.FILE_STORAGE_ERROR.getCode(),
                    "合并分片时读取文件失败", e));
            if (storagePath != null) {
                deleteQuietly(storagePath, failure);
            }
            throw failure;
        } catch (BusinessException e) {
            store.reopenMerge(session, now);
            if (storagePath != null) {
                deleteQuietly(storagePath, e);
            }
            if (e.getCode() == PlatformErrorCode.FILE_TOO_LARGE.getCode()) {
                throw chunkInvalid("合并文件超过会话大小：" + session.getExpectedSize());
            }
            throw e;
        } catch (RuntimeException e) {
            store.reopenMerge(session, now);
            if (storagePath != null) {
                deleteQuietly(storagePath, e);
            }
            throw e;
        }
    }

    private FileUploadSession createSession(FileChunkCmd cmd, Instant now) {
        if (cmd.fileName() == null || cmd.expectedSize() == null
                || cmd.chunkTotal() == null || cmd.chunkSize() == null) {
            throw chunkInvalid("首片必须携带 fileName、expectedSize、chunkTotal、chunkSize");
        }
        long expected = cmd.expectedSize();
        int total = cmd.chunkTotal();
        int size = cmd.chunkSize();
        long requiredTotal = expected > 0 && size > 0 ? (expected - 1) / size + 1 : 0;
        if (expected <= 0 || total <= 0 || total > MAX_CHUNK_TOTAL || size <= 0
                || total != requiredTotal) {
            throw chunkInvalid("会话分片参数不合法");
        }
        String extension = typePolicy.validateUpload(new FileUploadCmd(cmd.fileName(), null,
                FileUploadCmd.SIZE_UNKNOWN,
                InputStream.nullInputStream(), null, null));
        FileUploadSession session = new FileUploadSession();
        session.setId(idGenerator.nextId());
        session.setUploadId(cmd.uploadId());
        session.setFileName(FileNames.normalizedOriginalName(cmd.fileName()));
        session.setExtension(extension.isEmpty() ? null : extension);
        session.setExpectedSize(expected);
        session.setChunkSize(size);
        session.setChunkTotal(total);
        session.setSha256(cmd.fileSha256() == null ? null : normalizeSha256(cmd.fileSha256(), "fileSha256"));
        session.setStorageType(storage().type());
        session.setStatus("OPEN");
        session.setExpireTime(now.plus(java.time.Duration.ofHours(fileParams == null ? 24 : fileParams.sessionTtlHours())));
        session.setCreatedAt(now);
        session.setCreatedBy(accessGuard.currentOperatorId());
        session.setVersion(0);
        store.insertSession(session);
        return session;
    }

    private void validateSessionShape(FileUploadSession session, FileChunkCmd cmd) {
        if (cmd.chunkTotal() != null && !cmd.chunkTotal().equals(session.getChunkTotal())
                || cmd.chunkSize() != null && !cmd.chunkSize().equals(session.getChunkSize())) {
            throw chunkInvalid("分片参数与会话不一致");
        }
    }

    private void ensureSessionOpen(FileUploadSession session, Instant now) {
        if (!"OPEN".equals(session.getStatus())
                || session.getExpireTime() == null || !now.isBefore(session.getExpireTime())) {
            if (!"DONE".equals(session.getStatus())) {
                store.expireSession(session, now);
            }
            throw sessionMissing(session.getUploadId());
        }
    }

    private void requireSessionOwner(FileUploadSession session) {
        Long owner = session.getCreatedBy();
        Long current = accessGuard.currentOperatorId();
        if (owner == null || current == null || !owner.equals(current)) {
            throw sessionMissing(session.getUploadId());
        }
    }

    private static List<Integer> missingIndexes(FileUploadSession session, List<FileChunk> chunks) {
        Set<Integer> found = chunks.stream().map(FileChunk::getChunkIndex).collect(Collectors.toSet());
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < session.getChunkTotal(); i++) {
            if (!found.contains(i)) {
                missing.add(i);
            }
        }
        return missing;
    }

    private static void validateUploadId(String uploadId) {
        if (uploadId == null || !uploadId.matches("[A-Za-z0-9-]{1,64}")) {
            throw chunkInvalid("uploadId 非法");
        }
    }

    private static String normalizeSha256(String value, String field) {
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw chunkInvalid(field + " 必须是 64 位 SHA-256");
        }
        return normalized;
    }

    private static BusinessException chunkInvalid(String message) {
        return new BusinessException(PlatformErrorCode.FILE_CHUNK_INVALID, message);
    }

    private static BusinessException sessionMissing(String uploadId) {
        return new BusinessException(PlatformErrorCode.FILE_SESSION_NOT_FOUND,
                "分片上传会话不存在或已过期：" + uploadId);
    }

    private DigestAndSize digestOfWithSize(String storagePath) {
        try (InputStream in = storage().open(storagePath)) {
            return digestAndSize(in);
        } catch (IOException e) {
            throw storageError(new BusinessException(PlatformErrorCode.FILE_STORAGE_ERROR.getCode(),
                    "文件存储读写失败：" + storagePath, e));
        }
    }

    private static DigestAndSize digestAndSize(InputStream in) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            long size = 0L;
            int read;
            while ((read = in.read(buffer)) > 0) {
                size += read;
                digest.update(buffer, 0, read);
            }
            return new DigestAndSize(size, HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException e) {
            throw new SystemException("SHA-256 不可用", e);
        }
    }

    private record DigestAndSize(long size, String sha256) {
    }

    // ---------------------------------------------------------------- 读

    /** 元数据；不存在或已逻辑删除抛 20014。 */
    public FileDTO getMeta(long fileId) {
        return toDto(requireRow(fileId), null);
    }

    /** 分页（管理页）；每行的 {@code url} 恒为空（5.3：只有 {@code GetUrl} 填充）。 */
    public PageResult<FileDTO> page(FileQuery query) {
        IPage<FileMetaFile> page = store.page(query);
        List<FileDTO> records = new ArrayList<>(page.getRecords().size());
        for (FileMetaFile row : page.getRecords()) {
            records.add(toDto(row, null));
        }
        return PageResult.from(page.getCurrent(), page.getSize(), page.getTotal(), records);
    }

    /** 下载流（**不判权限**：跨模块调用方已鉴权；HTTP 面先调 {@link #requireVisible}）。 */
    public Resource download(FileDownloadCmd cmd) {
        FileMetaFile row = requireRow(cmd.fileId());
        InputStream stream;
        try {
            stream = storageOf(row).open(row.getStoragePath());
        } catch (RuntimeException e) {
            throw storageFailure(row.getStoragePath(), e);
        }
        return new FileDownloadResource(row.getOriginalName(), row.getSizeBytes() == null ? 0L : row.getSizeBytes(),
                stream);
    }

    /** 带外下载链接（**先判权限，再签 token**，3.3.4）：可见性由调用方在签之前确认。 */
    public FileUrlDTO getUrl(long fileId, int expireSeconds) {
        FileMetaFile row = requireRow(fileId);
        try {
            return storageOf(row).presignGet(row, expireSeconds);
        } catch (RuntimeException e) {
            throw storageFailure(row.getStoragePath(), e);
        }
    }

    // ---------------------------------------------------------------- 写

    /**
     * 逻辑删除：标记 + 事件 + 留痕；物理清理由 {@code platform.file.orphan.clean} 负责（3.3.7）。
     *
     * <p><b>不带版本</b>（5.4 的 {@code FileApi.del(long)} 契约）：跨模块调用方没有"表单里的 version"，
     * 删除的语义就是"这个文件不要了"，重复删除是幂等的无操作。需要乐观锁的 HTTP 面走
     * {@link #del(com.eaio.platform.api.dto.FileDelCmd)}。
     */
    @Transactional
    public void del(long fileId) {
        FileMetaFile row = requireRow(fileId);
        softDelete(row, accessGuard.currentOperatorId());
    }

    /**
     * 带乐观锁的逻辑删除（HTTP 面 {@code /platform/file/Del} 的 {@code {fileId, version}}，5.2）。
     *
     * <p>版本判定放在这里而不是控制器：软删的 SET 与 {@code version} 条件是**同一条 SQL**（{@code @Version}），
     * "先查后比再删"的三步在控制器里是两个事务（TOCTOU 窗口），在服务里是一个事务。两者都拦不住
     * "同一 version 并发删两次"（第二次本就幂等），但校验强度不该因为走 HTTP 还是走 Java 面而不同。
     */
    @Transactional
    public void del(FileDelCmd cmd) {
        FileMetaFile row = requireRow(cmd.fileId());
        if (row.getVersion() == null || !row.getVersion().equals(cmd.version())) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT,
                    "文件已被他人修改（version 过期）：fileId=" + cmd.fileId()
                            + "，当前 version=" + row.getVersion() + "，请求 version=" + cmd.version());
        }
        softDelete(row, accessGuard.currentOperatorId());
    }

    /**
     * 绑定业务对象（幂等：重复绑定不报错，5.4）。
     *
     * <p>每个 {@code fileId} 都要存在且未删除（否则 20014）：绑定一个不存在的文件是调用方的 bug，
     * 静默忽略会让"附件看起来绑上了、其实没有"变成运行期才发现的坑。
     */
    @Transactional
    public void bind(FileBindCmd cmd) {
        Set<Long> fileIds = new LinkedHashSet<>(cmd.fileIds());
        requireRows(fileIds);
        Long operatorId = accessGuard.currentOperatorId();
        int added = 0;
        for (Long fileId : fileIds) {
            if (bindInternal(fileId, cmd.bizType(), cmd.bizId(), operatorId)) {
                added++;
            }
        }
        recordAudit(new AuditRecord(ACTION_BIND, RESOURCE_FILE, null, cmd.bizType(), cmd.bizId(), operatorId,
                orgIdOrZero(), RESULT_SUCCESS, null, null, MDC.get("traceId")));
        log.info("文件绑定完成：bizType={} bizId={} 请求={} 新增={}（其余早已绑定，幂等）",
                cmd.bizType(), cmd.bizId(), fileIds.size(), added);
    }

    // ---------------------------------------------------------------- 可见性（HTTP 面）

    /** 要求当前请求对文件可见（不可见抛 20017）；文件不存在抛 20014，且**失败也要留痕**。 */
    public void requireVisible(long fileId) {
        FileMetaFile row = requireRow(fileId);
        if (accessGuard.canSee(row, FileParams.PERMISSION_DOWNLOAD)) {
            return;
        }
        // 安全事件：谁试图下载了不该下载的文件（3.3.6 明文要求拒绝也要留痕）
        recordAudit(new AuditRecord(ACTION_DOWNLOAD_DENIED, RESOURCE_FILE, fileId, null, null,
                accessGuard.currentOperatorId(), orgIdOrZero(), RESULT_DENIED, row.getSizeBytes(),
                row.getSha256(), MDC.get("traceId")));
        throw new BusinessException(PlatformErrorCode.FILE_ACCESS_DENIED, "无权访问该文件：fileId=" + fileId);
    }

    /**
     * 通过可见性判定后的下载留痕（成功路径；失败路径在 {@link #requireVisible} 里记）。
     */
    public void auditDownload(FileDownloadCmd cmd) {
        FileMetaFile row = requireRow(cmd.fileId());
        Long operatorId = cmd.operatorId() == null ? accessGuard.currentOperatorId() : cmd.operatorId();
        recordAudit(new AuditRecord(ACTION_DOWNLOAD, RESOURCE_FILE, cmd.fileId(), null, null, operatorId,
                orgIdOrZero(), RESULT_SUCCESS, row.getSizeBytes(), row.getSha256(), MDC.get("traceId")));
    }

    /**
     * 物理删除一个文件的盘上内容（**只给清理任务用**，3.3.7）：删失败记 ERROR 并抛出，由调用方决定
     * 是否跳过这一行——"盘上删不掉但库里删了"会留下永久孤儿，必须让调用方看见。
     */
    public void purgeContent(FileMetaFile row) {
        try {
            storageOf(row).delete(row.getStoragePath());
            recordAudit(new AuditRecord(ACTION_PURGE, RESOURCE_FILE, row.getId(), null, null, null, null,
                    RESULT_SUCCESS, row.getSizeBytes(), row.getSha256(), MDC.get("traceId")));
        } catch (RuntimeException e) {
            recordAudit(new AuditRecord(ACTION_PURGE, RESOURCE_FILE, row.getId(), null, null, null, null,
                    RESULT_FAILED, row.getSizeBytes(), row.getSha256(), MDC.get("traceId")));
            throw e;
        }
    }

    /** 降级计数（{@code platform.audit.fallback.count}）：验收与将来的 MonitorApi 都读它。 */
    public long auditFallbackCount() {
        return auditRecorder.fallbackCount();
    }

    /**
     * 校验预签名链接（HTTP 面的 GET 下载路径）：**先比签名，再判时效**（3.3.8），失败一律 20016。
     *
     * <p>{@code exp}/{@code sig} 缺失或畸形也走这里：签名是不可判定的，按"无效"处理（不是"过期"）。
     */
    public void requireValidPresign(long fileId, String exp, String sig) {
        if (localFileStorage.verifyPresign(fileId, exp, sig)
                != FilePresignTokenService.PresignVerification.VALID) {
            throw FilePresignTokenService.invalidSignature();
        }
    }

    // ---------------------------------------------------------------- 内部

    /** 绑定一行（幂等）：已绑定返回 {@code false}。 */
    private boolean bindInternal(long fileId, String bizType, long bizId, Long operatorId) {
        FileBinding binding = new FileBinding();
        binding.setId(idGenerator.nextId());
        binding.setFileId(fileId);
        binding.setBizType(bizType.trim());
        binding.setBizId(bizId);
        binding.setCreatedAt(Instant.now());
        binding.setCreatedBy(operatorId);
        return store.bindIfAbsent(binding);
    }

    /**
     * 软删一行 + 删事件 + 留痕（{@link #del(long)} 与 {@link #del(FileDelCmd)} 共用）。
     *
     * <p>{@code updated_at} 在这里被写成"软删时间"：清理任务按它算 {@code purge-days}（表里没有
     * {@code deleted_at} 列，4.3.5）。写入走 {@code FileStore.softDelete}（逻辑删除 API），
     * 不能用 {@code updateById}——{@code @TableLogic} 列不在后者的 SET 子句里。
     */
    private void softDelete(FileMetaFile row, Long operatorId) {
        if (!softDeleteRow(row, operatorId, Instant.now())) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT,
                    "文件已被他人修改（version 过期）：fileId=" + row.getId());
        }
    }

    /** 孤儿清理使用的软删入口：与人工删除共用事件、审计和事务边界。 */
    @Transactional
    public boolean softDeleteOrphan(FileMetaFile row, Instant deletedAt) {
        return softDeleteRow(row, null, deletedAt);
    }

    private boolean softDeleteRow(FileMetaFile row, Long operatorId, Instant deletedAt) {
        // updated_at 就是清理任务用的"软删时间"（表里没有 deleted_at 列，4.3.5）
        row.setUpdatedAt(deletedAt);
        row.setUpdatedBy(operatorId);
        if (store.softDelete(row) == 0) {
            return false;
        }
        publisher.publish(new FileDeletedEvent(idGenerator.nextStr(), Instant.now(), row.getId(), operatorId));
        recordAudit(new AuditRecord(ACTION_DELETE, RESOURCE_FILE, row.getId(), null, null, operatorId,
                orgIdOrZero(), RESULT_SUCCESS, row.getSizeBytes(), row.getSha256(), MDC.get("traceId")));
        log.info("文件已逻辑删除：fileId={}（物理清理由 platform.file.orphan.clean 按 purge-days 执行）",
                row.getId());
        return true;
    }

    /** 取行并要求存在（{@code @TableLogic} 已把软删行挡在外面）。 */
    private FileMetaFile requireRow(long fileId) {
        FileMetaFile row = store.rowById(fileId);
        if (row == null) {
            throw new BusinessException(PlatformErrorCode.FILE_NOT_FOUND, "文件不存在或已删除：fileId=" + fileId);
        }
        return row;
    }

    /** 批量存在性校验（一次查完，缺哪个报哪个）。 */
    private void requireRows(Set<Long> fileIds) {
        List<FileMetaFile> rows = store.rowsByIds(fileIds);
        if (rows.size() == fileIds.size()) {
            return;
        }
        Set<Long> found = new LinkedHashSet<>();
        for (FileMetaFile row : rows) {
            found.add(row.getId());
        }
        Set<Long> missing = new LinkedHashSet<>(fileIds);
        missing.removeAll(found);
        throw new BusinessException(PlatformErrorCode.FILE_NOT_FOUND, "文件不存在或已删除：fileId=" + missing);
    }

    /** 新写文件用的适配器（构造期已校验类型已登记）。 */
    private FileStorage storage() {
        return requireStorage(defaultStorageType);
    }

    /** 读旧文件用的适配器：**按行**选择（裁决 P1-C5），行上的类型没登记即存储故障。 */
    private FileStorage storageOf(FileMetaFile row) {
        String type = row.getStorageType() == null ? defaultStorageType : row.getStorageType();
        try {
            return requireStorage(type);
        } catch (SystemException e) {
            throw new BusinessException(PlatformErrorCode.FILE_STORAGE_ERROR,
                    "文件存储读写失败：storageType=" + type + " 没有对应的适配器");
        }
    }

    /**
     * 按类型取适配器；未登记即失败（含 {@code S3}：适配器尚未实现，见《实现注记（T9）》）。
     *
     * <p>未登记的类型必须在**最早**的时点炸出来：构造期（新写路径）与读行时（读路径）。
     * 静默回落到本地盘会让"配了 S3 却写到本地"变成一次数据迁移事故。
     */
    private FileStorage requireStorage(String storageType) {
        FileStorage storage = storages.get(storageType);
        if (storage == null) {
            throw new SystemException("未登记的文件存储适配器：storageType=" + storageType
                    + "（已登记：" + storages.keySet() + "；S3 适配器尚未实现）");
        }
        return storage;
    }

    /** 取行上存储的文件大小（服务端统计值；文件被外部删掉时抛 20015）。 */
    private long sizeOf(String storagePath) {
        try (InputStream in = storage().open(storagePath)) {
            long total = 0L;
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) > 0) {
                total += read;
            }
            return total;
        } catch (java.io.IOException e) {
            throw new BusinessException(PlatformErrorCode.FILE_STORAGE_ERROR, "文件存储读写失败：" + storagePath);
        }
    }

    /** 服务端重算 sha256（**不信任客户端**，3.3.3）；文件被外部改动时这里就能发现。 */
    private String digestOf(String storagePath) {
        try (InputStream in = storage().open(storagePath)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.io.IOException e) {
            throw new BusinessException(PlatformErrorCode.FILE_STORAGE_ERROR, "文件存储读写失败：" + storagePath);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法；走到这里说明运行环境被裁剪过
            throw new SystemException("SHA-256 不可用（JDK 算法被裁剪？）", e);
        }
    }

    /** 清理刚落盘的文件；清理失败要留 ERROR（它意味着孤儿文件留在盘上）。 */
    /** 存储适配器的运行期故障统一落到文件错误码；业务错误（如签名不可用）原样透出。 */
    private RuntimeException storageFailure(String storagePath, RuntimeException cause) {
        if (cause instanceof BusinessException) {
            return ((BusinessException) cause).getCode() == PlatformErrorCode.FILE_STORAGE_ERROR.getCode()
                    ? storageError(cause) : cause;
        }
        String suffix = storagePath == null ? "" : "：" + storagePath;
        return storageError(new BusinessException(PlatformErrorCode.FILE_STORAGE_ERROR.getCode(),
                "文件存储读写失败" + suffix, cause));
    }

    private <T extends RuntimeException> T storageError(T error) {
        storageErrors.record();
        return error;
    }

    private void deleteQuietly(String storagePath, RuntimeException cause) {
        try {
            storage().delete(storagePath);
        } catch (RuntimeException cleanupFailure) {
            cause.addSuppressed(cleanupFailure);
            log.error("清理临时文件失败（盘上会留下孤儿，等待孤儿清理）：storagePath={}", storagePath, cleanupFailure);
        }
    }

    /** 组织 ID：无上下文时为 0（"没有组织"是一种事实，不是错误）；{@code uploader_org_id} 因此 NOT NULL。 */
    private long orgIdOrZero() {
        Long orgId = accessGuard.currentOrgId();
        return orgId == null ? 0L : orgId;
    }

    /**
     * 留痕（3.3.6）：端口在就交给它，缺席就降级日志 + 计数。
     *
     * <p>刻意**不抛出**：留痕失败不该回滚文件写入（P1 没有强审计场景），但必须 ERROR + 计数让运维看到。
     */
    private void recordAudit(AuditRecord record) {
        auditRecorder.record(auditPort.getIfAvailable(), record);
    }

    /** 实体 → DTO；字段遗漏由 MapStruct 的 ERROR 策略在编译期拦住。 */
    private FileDTO toDto(FileMetaFile row, String url) {
        return dtoMapper.toDto(row, url);
    }
}
