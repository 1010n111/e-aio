package com.eaio.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.eaio.common.api.ErrorCode;
import com.eaio.common.exception.BusinessException;
import com.eaio.common.id.IdGenerator;
import com.eaio.platform.api.PlatformErrorCode;
import com.eaio.platform.api.dto.FileBindCmd;
import com.eaio.platform.api.dto.FileDTO;
import com.eaio.platform.api.dto.FileDelCmd;
import com.eaio.platform.api.dto.FileDownloadCmd;
import com.eaio.platform.api.dto.FileUploadCmd;
import com.eaio.platform.api.port.AuditPort;
import com.eaio.platform.application.event.PlatformEventPublisher;
import com.eaio.platform.application.file.AuditFallbackRecorder;
import com.eaio.platform.application.file.FileAccessGuard;
import com.eaio.platform.application.file.FileDownloadResource;
import com.eaio.platform.application.file.FileDtoMapper;
import com.eaio.platform.application.file.FileParams;
import com.eaio.platform.domain.file.FileMetaFile;
import com.eaio.platform.domain.file.FileTypePolicy;
import com.eaio.platform.events.FileUploadedEvent;
import com.eaio.platform.infrastructure.persistence.FileStore;
import com.eaio.platform.infrastructure.storage.FilePresignTokenService;
import com.eaio.platform.infrastructure.storage.FileStorageConfig;
import com.eaio.platform.infrastructure.storage.FileStorageLocation;
import com.eaio.platform.infrastructure.storage.LocalFileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文件应用服务的测试（P1 册 3.3.9 的后四行 + 3.3.6 的降级口径）。
 *
 * <p>替身边界：仓储（{@link FileStore}）、类型策略、可见性、事件发布都是替身——它们各自有独立测试；
 * 存储适配器用**真实实现 + {@link TempDir}**，因为"上传后能原样读回"必须落在真实文件系统上才有意义。
 *
 * <p>三个核心断言：<b>服务端重算摘要</b>（不信任客户端）、<b>落盘与入库之间失败要清文件</b>（否则永久磁盘
 * 泄漏）、<b>可见性判定失败也要留痕</b>（20017 是安全事件）。
 */
class FileAppServiceTest {

    private static final long FILE_ID = 1001L;

    private FileStore store;
    private FileTypePolicy typePolicy;
    private FileAccessGuard accessGuard;
    private PlatformEventPublisher publisher;
    private AuditFallbackRecorder auditRecorder;
    private ObjectProvider<AuditPort> auditPortProvider;
    private LocalFileStorage storage;
    private FileAppService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp(@TempDir Path root) {
        store = mock(FileStore.class);
        typePolicy = mock(FileTypePolicy.class);
        accessGuard = mock(FileAccessGuard.class);
        publisher = mock(PlatformEventPublisher.class);
        auditRecorder = new AuditFallbackRecorder();
        auditPortProvider = mock(ObjectProvider.class);
        IdGenerator ids = mock(IdGenerator.class);
        when(ids.nextId()).thenReturn(FILE_ID);
        when(ids.nextStr()).thenReturn("evt-1");
        when(typePolicy.maxSizeBytes()).thenReturn(1024L);

        FileStorageLocation location = new FileStorageLocation(root.toString());
        storage = new LocalFileStorage(location, new FilePresignTokenService(
                "0123456789abcdef0123456789abcdef"), new StaticApplicationContext());

        service = new FileAppService(store, List.of(storage),
                new FileStorageConfig.EaioFileProperties(LocalFileStorage.TYPE), typePolicy, accessGuard,
                auditPortProvider, auditRecorder, publisher, ids, storage, Mappers.getMapper(FileDtoMapper.class));
    }

    @Test
    @DisplayName("上传成功：落盘 + 服务端重算 sha256 + 事件登记 + 留痕（未装配 AuditPort 时降级计数 +1）")
    void uploadStoresFileAndRecomputesDigest(@TempDir Path root) throws IOException {
        byte[] content = "hello file center".getBytes(StandardCharsets.UTF_8);
        when(typePolicy.validateUpload(any())).thenReturn("txt");
        when(accessGuard.currentOperatorId()).thenReturn(7L);
        when(accessGuard.currentOrgId()).thenReturn(9L);

        FileDTO dto = service.upload(new FileUploadCmd("报告.txt", "text/plain", (long) content.length,
                new ByteArrayInputStream(content), null, null));

        assertThat(dto.id()).isEqualTo(FILE_ID);
        assertThat(dto.sizeBytes()).isEqualTo(content.length);
        assertThat(dto.sha256()).isEqualTo(sha256(content));
        assertThat(dto.storageType()).isEqualTo(LocalFileStorage.TYPE);
        assertThat(dto.uploaderId()).isEqualTo(7L);
        assertThat(dto.uploaderOrgId()).isEqualTo(9L);

        ArgumentCaptor<FileMetaFile> row = ArgumentCaptor.forClass(FileMetaFile.class);
        verify(store).insert(row.capture());
        assertThat(row.getValue().getStoragePath())
                .as("日期段由上传时刻（UTC）决定，后两段 = fileId%100 / fileId（3.3.2 的布局）")
                .matches("\\d{4}/\\d{2}/\\d{2}/01/1001\\.txt");
        assertThat(row.getValue().getSha256()).isEqualTo(sha256(content));

        ArgumentCaptor<FileUploadedEvent> event = ArgumentCaptor.forClass(FileUploadedEvent.class);
        verify(publisher).publish(event.capture());
        assertThat(event.getValue().fileId()).isEqualTo(FILE_ID);
        assertThat(event.getValue().sha256()).isEqualTo(sha256(content));

        assertThat(auditRecorder.fallbackCount())
                .as("AuditPort 缺席 → 降级日志 + platform.audit.fallback.count")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("上传开启独立事务：不把调用方事务拖长")
    void uploadUsesIndependentTransaction() throws NoSuchMethodException {
        Transactional transactional = FileAppService.class.getMethod("upload", FileUploadCmd.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    @DisplayName("流式上传超限：20011 且盘上不留半截文件（临时文件被删）")
    void uploadRejectsOversizedStreamAndDeletesTempFile(@TempDir Path root) {
        byte[] content = "x".repeat(2000).getBytes(StandardCharsets.UTF_8);
        when(typePolicy.validateUpload(any())).thenReturn("txt");
        when(typePolicy.maxSizeBytes()).thenReturn(1024L);

        assertThatThrownBy(() -> service.upload(new FileUploadCmd("big.txt", "text/plain",
                (long) content.length, new ByteArrayInputStream(content), null, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(PlatformErrorCode.FILE_TOO_LARGE.getCode()));

        assertThat(emptyDirectoryCount(root)).as("校验失败必须清掉刚落下的文件").isZero();
        verify(store, never()).insert(any());
    }

    @Test
    @DisplayName("流式超限在读到上限后的下一字节立即失败，不把整条大流写完")
    void uploadStopsReadingWhenStreamExceedsLimit(@TempDir Path root) {
        AtomicInteger consumed = new AtomicInteger();
        InputStream content = new InputStream() {
            private int index;

            @Override
            public int read() {
                consumed.incrementAndGet();
                return index++ < 2000 ? 'x' : -1;
            }
        };
        when(typePolicy.validateUpload(any())).thenReturn("txt");
        when(typePolicy.maxSizeBytes()).thenReturn(1024L);

        assertThatThrownBy(() -> service.upload(new FileUploadCmd("big.txt", "text/plain", -1L, content, null, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(PlatformErrorCode.FILE_TOO_LARGE.getCode()));

        assertThat(consumed).as("只允许探测上限后的一个字节，不应把整条输入流读完").hasValueLessThan(2000);
        assertThat(emptyDirectoryCount(root)).isZero();
    }

    @Test
    @DisplayName("零字节上传：20010 且盘上不留文件")
    void uploadRejectsZeroByteFile(@TempDir Path root) {
        when(typePolicy.validateUpload(any())).thenReturn("txt");

        assertThatThrownBy(() -> service.upload(new FileUploadCmd("empty.txt", "text/plain", 0L,
                new ByteArrayInputStream(new byte[0]), null, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(PlatformErrorCode.FILE_EMPTY.getCode()));

        assertThat(emptyDirectoryCount(root)).isZero();
    }

    @Test
    @DisplayName("入库失败：事务回滚（库里没有行），盘上文件必须一起清掉——否则永远没人能清理它")
    void uploadDeletesFileWhenInsertFails(@TempDir Path root) {
        when(typePolicy.validateUpload(any())).thenReturn("txt");
        when(store.insert(any())).thenThrow(new IllegalStateException("库不可用"));

        assertThatThrownBy(() -> service.upload(new FileUploadCmd("a.txt", "text/plain", 3L,
                new ByteArrayInputStream(new byte[] {1, 2, 3}), null, null)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(emptyDirectoryCount(root)).isZero();
        verify(publisher, never()).publish(any(FileUploadedEvent.class));
    }

    @Test
    @DisplayName("下载：可见性判定失败 → 20017 + 一条 DENIED 留痕；判定通过 → 流可原样读回")
    void downloadChecksVisibilityAndAuditsDenial() throws IOException {
        byte[] content = "payload".getBytes(StandardCharsets.UTF_8);
        FileMetaFile row = storedRow(content);
        when(store.rowById(FILE_ID)).thenReturn(row);
        when(accessGuard.canSee(row, FileParams.PERMISSION_DOWNLOAD))
                .thenReturn(false);

        assertThatThrownBy(() -> service.requireVisible(FILE_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(PlatformErrorCode.FILE_ACCESS_DENIED.getCode()));
        assertThat(auditRecorder.fallbackCount()).as("拒绝下载也要留痕（3.3.6 的安全事件）").isEqualTo(1L);

        when(accessGuard.canSee(row, FileParams.PERMISSION_DOWNLOAD)).thenReturn(true);
        FileDownloadResource resource = (FileDownloadResource) service.download(
                FileDownloadCmd.attachment(FILE_ID, null));
        assertThat(resource.contentLength()).isEqualTo(content.length);
        assertThat(resource.getInputStream().readAllBytes()).isEqualTo(content);
    }

    @Test
    @DisplayName("存储文件缺失：下载返回文件存储错误 20015，不泄漏成通用 10500")
    void missingStorageContentIsFileStorageError() {
        FileMetaFile row = storedRow("x".getBytes(StandardCharsets.UTF_8));
        row.setStoragePath("2026/03/04/01/missing.txt");
        when(store.rowById(FILE_ID)).thenReturn(row);

        assertThatThrownBy(() -> service.download(FileDownloadCmd.attachment(FILE_ID, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(PlatformErrorCode.FILE_STORAGE_ERROR.getCode()));
    }

    @Test
    @DisplayName("文件不存在/已删除：20014（读取、删除、下载三条路径同一口径）")
    void missingFileIsNotFound() {
        when(store.rowById(99L)).thenReturn(null);

        for (Runnable call : List.<Runnable>of(
                () -> service.getMeta(99L),
                () -> service.requireVisible(99L),
                () -> service.del(99L))) {
            assertThatThrownBy(call::run)
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getCode())
                            .isEqualTo(PlatformErrorCode.FILE_NOT_FOUND.getCode()));
        }
    }

    @Test
    @DisplayName("Del 带版本：version 过期 → 10003 且不落删除；version 一致 → 软删 + 事件")
    void deleteChecksVersion() {
        FileMetaFile row = storedRow("x".getBytes(StandardCharsets.UTF_8));
        row.setVersion(3);
        when(store.rowById(FILE_ID)).thenReturn(row);

        assertThatThrownBy(() -> service.del(new FileDelCmd(FILE_ID, 2)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.DATA_CONFLICT.getCode()));
        verify(store, never()).softDelete(any());

        when(store.softDelete(any())).thenReturn(1);
        service.del(new FileDelCmd(FILE_ID, 3));

        ArgumentCaptor<FileMetaFile> updated = ArgumentCaptor.forClass(FileMetaFile.class);
        verify(store).softDelete(updated.capture());
        assertThat(updated.getValue().getId()).isEqualTo(FILE_ID);
        assertThat(updated.getValue().getUpdatedAt()).as("软删时间就是 updated_at（清理任务的排序列）").isNotNull();
    }

    @Test
    @DisplayName("Bind 幂等：重复绑定不报错，第二次不再插入；文件不存在 → 20014")
    void bindIsIdempotent() {
        FileMetaFile row = storedRow("x".getBytes(StandardCharsets.UTF_8));
        when(store.rowsByIds(any())).thenReturn(List.of(row));
        when(store.bindIfAbsent(any())).thenReturn(true, false);

        FileBindCmd cmd = new FileBindCmd("crm.customer", 88L, List.of(FILE_ID));
        service.bind(cmd);
        service.bind(cmd);

        verify(store, times(2)).bindIfAbsent(any());
        assertThat(auditRecorder.fallbackCount()).as("两次绑定各留一条痕").isEqualTo(2L);

        when(store.rowsByIds(any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.bind(cmd))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(PlatformErrorCode.FILE_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("物理清理：删盘 + 留痕；盘删失败先留 FAILED 再抛（调用方保留库行待重试）")
    void purgeContentDeletesAndAuditsFailure() {
        FileMetaFile row = storedRow("x".getBytes(StandardCharsets.UTF_8));
        service.purgeContent(row);
        assertThat(auditRecorder.fallbackCount()).isEqualTo(1L);

        row.setStoragePath("2026/03/04/01/1001.txt");
        service.purgeContent(row);

        row.setStorageType("S3");
        assertThatThrownBy(() -> service.purgeContent(row)).isInstanceOf(RuntimeException.class);
        assertThat(auditRecorder.fallbackCount()).as("成功与失败都留痕").isEqualTo(3L);
    }

    /** 造一行"盘上真有文件"的元数据（storage 用真实实现，所以路径必须按布局生成）。 */
    private FileMetaFile storedRow(byte[] content) {
        String path = storage.store(new ByteArrayInputStream(content),
                new com.eaio.platform.infrastructure.storage.StoredFileMeta(FILE_ID, "txt", "text/plain",
                        Instant.parse("2026-03-04T05:06:07Z")));
        FileMetaFile row = new FileMetaFile();
        row.setId(FILE_ID);
        row.setOriginalName("报告.txt");
        row.setExtension("txt");
        row.setContentType("text/plain");
        row.setSizeBytes((long) content.length);
        row.setStorageType(LocalFileStorage.TYPE);
        row.setStoragePath(path);
        row.setSource(FileAppService.SOURCE_UPLOAD);
        row.setCreatedAt(Instant.parse("2026-03-04T05:06:07Z"));
        row.setVersion(0);
        row.setDeleted(false);
        return row;
    }

    private static long emptyDirectoryCount(Path root) {
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 未使用的桩方法占位（避免静态分析报未使用导入）。 */
    private static String unused(String value) {
        return value == null ? "" : value;
    }
}
