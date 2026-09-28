package com.eaio.platform.application.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.eaio.common.exception.SystemException;
import com.eaio.platform.api.dto.JobContext;
import com.eaio.platform.application.FileAppService;
import com.eaio.platform.domain.file.FileMetaFile;
import com.eaio.platform.infrastructure.persistence.FileStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 孤儿/软删清理任务的测试（P1 册 3.3.7 的两段式清理；票面验收第 5 条"清理任务可运行"）。
 *
 * <p>三条关键口径各有用例：<b>软删前二次确认仍未被绑定</b>（扫描与软删之间刚被绑定的文件不能删）、
 * <b>物理删的顺序是盘 → 绑定 → 库</b>（盘删失败必须保留库行待重试，否则文件永远没人认领）、
 * <b>分批且不无限循环</b>（积压留给下一轮 cron，不占死调度线程）。
 */
class FileOrphanCleanHandlerTest {

    private FileStore store;
    private FileAppService service;
    private FileOrphanCleanHandler handler;
    private JobContext ctx;

    @BeforeEach
    void setUp() {
        store = mock(FileStore.class);
        service = mock(FileAppService.class);
        FileParams params = mock(FileParams.class);
        when(params.orphanRetainDays()).thenReturn(7);
        when(params.purgeDays()).thenReturn(30);
        handler = new FileOrphanCleanHandler(store, service, params);
        ctx = new JobContext(FileOrphanCleanHandler.CODE, Map.of(), 1L, 1, "trace-1",
                Instant.now().plusSeconds(60));
    }

    @Test
    @DisplayName("处理点编码与种子的 handler_code 逐字一致（不一致时调度器只会记 ERROR 跳过）")
    void codeMatchesSeed() {
        assertThat(handler.code()).isEqualTo("platform.file.orphan.clean");
    }

    @Test
    @DisplayName("未绑定且超期 → 软删；软删超期 → 盘 → 绑定 → 库 三步清完")
    void softDeletesOrphansAndPurgesExpired() {
        FileMetaFile orphan = row(11L, false);
        FileMetaFile expired = row(22L, true);
        when(store.unboundBefore(any(), anyInt())).thenReturn(List.of(orphan));
        when(store.activeBindingCount(11L)).thenReturn(0L);
        when(store.softDelete(any())).thenReturn(1);
        when(store.purgeableBefore(any(), anyInt())).thenReturn(List.of(expired));

        handler.execute(ctx);

        ArgumentCaptor<FileMetaFile> softDeleted = ArgumentCaptor.forClass(FileMetaFile.class);
        ArgumentCaptor<Instant> deletedAt = ArgumentCaptor.forClass(Instant.class);
        verify(service).softDeleteOrphan(softDeleted.capture(), deletedAt.capture());
        assertThat(softDeleted.getValue().getId()).isEqualTo(11L);
        assertThat(deletedAt.getValue()).as("软删时间 = updated_at（清理任务的排序列）").isNotNull();

        verify(service).purgeContent(expired);
        verify(store).purgeBindings(List.of(22L));
        verify(store).purgeById(22L);
    }

    @Test
    @DisplayName("扫描后刚被业务绑定的文件不软删（宁可漏清一轮，不可误删）")
    void skipsFilesBoundAfterScan() {
        when(store.unboundBefore(any(), anyInt())).thenReturn(List.of(row(33L, false)));
        when(store.activeBindingCount(33L)).thenReturn(1L);

        handler.execute(ctx);

        verify(service, never()).softDeleteOrphan(any(), any());
        verify(store, never()).purgeById(anyLong());
    }

    @Test
    @DisplayName("盘删失败：保留库行待下一轮（不删绑定、不删库行），且不影响同一批其他文件")
    void keepsRowWhenStorageDeleteFails() {
        FileMetaFile failing = row(44L, true);
        FileMetaFile healthy = row(55L, true);
        when(store.purgeableBefore(any(), anyInt())).thenReturn(List.of(failing, healthy));
        org.mockito.Mockito.doThrow(new SystemException("磁盘 IO 失败")).when(service).purgeContent(failing);

        handler.execute(ctx);

        verify(store, never()).purgeBindings(List.of(44L));
        verify(store, never()).purgeById(44L);
        verify(service).purgeContent(healthy);
        verify(store).purgeById(55L);
    }

    @Test
    @DisplayName("超时/无候选：立即退出，不做任何写操作（分批循环不会占死调度线程）")
    void exitsEarlyWhenNothingToDoOrExpired() {
        when(store.unboundBefore(any(), anyInt())).thenReturn(List.of());
        when(store.purgeableBefore(any(), anyInt())).thenReturn(List.of());
        handler.execute(ctx);
        verify(service, never()).softDeleteOrphan(any(), any());
        verify(store, never()).purgeById(anyLong());

        JobContext expired = new JobContext(FileOrphanCleanHandler.CODE, Map.of(), 2L, 1, "trace-2",
                Instant.now().minusSeconds(1));
        when(store.unboundBefore(any(), anyInt())).thenReturn(List.of(row(66L, false)));
        handler.execute(expired);
        verify(service, never()).softDeleteOrphan(any(), any());
    }

    /** 造一行候选：{@code softDeleted} 决定它是"待软删的孤儿"还是"待物理删的软删行"。 */
    private static FileMetaFile row(long id, boolean softDeleted) {
        FileMetaFile row = new FileMetaFile();
        row.setId(id);
        row.setOriginalName("orphan-" + id + ".txt");
        row.setSizeBytes(3L);
        row.setStorageType("LOCAL");
        row.setStoragePath("2026/01/01/00/" + id + ".txt");
        row.setCreatedAt(Instant.now().minusSeconds(60L * 60 * 24 * 40));
        row.setVersion(0);
        row.setDeleted(softDeleted);
        return row;
    }
}
