package com.eaio.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;

import com.eaio.common.excel.ExcelKit;
import com.eaio.common.exception.BusinessException;
import com.eaio.platform.api.ExcelExportHandler;
import com.eaio.platform.api.ExcelImportHandler;
import com.eaio.platform.api.ExportContext;
import com.eaio.platform.api.ImportRowContext;
import com.eaio.platform.api.dto.ExcelExportCmd;
import com.eaio.platform.api.dto.ExcelImportCmd;
import com.eaio.platform.api.dto.ExcelTaskDTO;
import com.eaio.platform.api.dto.FileDTO;
import com.eaio.platform.api.dto.FileUploadCmd;
import com.eaio.platform.api.port.OrgContextPort;
import com.eaio.platform.application.ExcelTaskAppService;
import com.eaio.platform.application.FileAppService;
import com.eaio.platform.api.PlatformErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;

/** #23：异步 Excel 任务、错误明细、去重、结果文件与流式导出。 */
@SpringBootTest
public class ExcelTaskIT extends IntegrationTestBase {

    private static final long ORG_ID = 9400L;
    private static final long USER_ID = 9401L;

    @TestConfiguration
    static class ExcelHandlers {
        static final AtomicReference<CountDownLatch> STARTED = new AtomicReference<>(new CountDownLatch(0));
        static final AtomicReference<CountDownLatch> RELEASE = new AtomicReference<>(new CountDownLatch(0));
        static volatile boolean block;

        @Bean
        OrgContextPort orgContextPort() {
            return () -> Optional.of(new OrgContextPort.OrgContext(ORG_ID, USER_ID));
        }

        @Bean
        ExcelImportHandler<ItRow> itImportHandler() {
            return new ExcelImportHandler<>() {
                @Override
                public String bizType() {
                    return "it.excel";
                }

                @Override
                public Class<ItRow> rowType() {
                    return ItRow.class;
                }

                @Override
                public void handleRow(ItRow row, ImportRowContext context) {
                    if (block) {
                        STARTED.get().countDown();
                        try {
                            RELEASE.get().await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("测试导入被中断", e);
                        }
                    }
                    if ("bad".equals(row.getValue())) {
                        throw new IllegalArgumentException("IT 行校验失败");
                    }
                }
            };
        }

        @Bean
        ExcelExportHandler itExportHandler() {
            return new ExcelExportHandler() {
                @Override
                public String bizType() {
                    return "it.excel.export";
                }

                @Override
                public Class<?> rowType() {
                    return ItRow.class;
                }

                @Override
                public java.util.stream.Stream<?> rows(ExportContext context) {
                    return LongStream.range(0, 100_000).mapToObj(index -> new ItRow("row-" + index));
                }

                @Override
                public String fileName() {
                    return "it-export.xlsx";
                }
            };
        }
    }

    public static class ItRow {
        private String value;

        public ItRow() {
        }

        ItRow(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    @Autowired
    private ExcelTaskAppService excel;
    @Autowired
    private FileAppService files;

    @BeforeEach
    void resetHandler() {
        ExcelHandlers.block = false;
        ExcelHandlers.STARTED.set(new CountDownLatch(0));
        ExcelHandlers.RELEASE.set(new CountDownLatch(0));
    }

    @Test
    @DisplayName("导入返回任务号，行错误分页与错误文件可查，同源运行中任务去重且 force 冲突")
    void importErrorsAndDeduplication() throws Exception {
        byte[] workbook = workbook(new ItRow("ok"), new ItRow("bad"), new ItRow("ok-2"));
        FileDTO source = files.upload(new FileUploadCmd("it-import-" + UUID.randomUUID() + ".xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", (long) workbook.length,
                new ByteArrayInputStream(workbook), null, null));

        ExcelHandlers.block = true;
        ExcelHandlers.STARTED.set(new CountDownLatch(1));
        ExcelHandlers.RELEASE.set(new CountDownLatch(1));
        String taskId = excel.submitImport(new ExcelImportCmd(source.id(), "it.excel", "it", false)).taskId();
        boolean started = ExcelHandlers.STARTED.get().await(10, TimeUnit.SECONDS);
        assertThat(started).as("import task status=%s error=%s", excel.task(taskId).status(),
                excel.task(taskId).errorMessage()).isTrue();
        assertThat(excel.submitImport(new ExcelImportCmd(source.id(), "it.excel", "it", false)).deduplicated())
                .isTrue();
        assertThatThrownBy(() -> excel.submitImport(new ExcelImportCmd(source.id(), "it.excel", "it", true)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(PlatformErrorCode.EXCEL_TASK_CONFLICT.getCode()));
        ExcelHandlers.RELEASE.get().countDown();

        ExcelTaskDTO result = awaitTask(taskId);
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.totalRows()).isEqualTo(3);
        assertThat(result.failRows()).isEqualTo(1);
        assertThat(result.deduplicated()).isTrue();
        assertThat(result.errorFileId()).isNotNull();
        assertThat(excel.errors(new com.eaio.platform.api.dto.ExcelTaskErrorQuery(taskId, 1, 20)).getRecords())
                .hasSize(1);
        Resource errorFile = excel.downloadResult(taskId, true);
        assertThat(errorFile.contentLength()).isGreaterThan(0);
    }

    @Test
    @DisplayName("未注册处理器返回 20037；10 万行导出使用流式 writer")
    void exportStreamingAndUnknownHandler() throws Exception {
        assertThatThrownBy(() -> excel.submitImport(new ExcelImportCmd(999999999L, "it.missing", null, false)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(PlatformErrorCode.EXCEL_HANDLER_NOT_REGISTERED.getCode()));
        Assumptions.assumeTrue(Runtime.getRuntime().maxMemory() <= 512L * 1024L * 1024L,
                "在 -Xmx512m 下执行峰值验收");
        AtomicLong peakHeapBytes = new AtomicLong();
        Thread sampler = startHeapSampler(peakHeapBytes);
        try {
            String taskId = excel.submitExport(new ExcelExportCmd("it.excel.export", "it", "{}")).taskId();
            ExcelTaskDTO result = awaitTask(taskId);
            assertThat(result.status()).isEqualTo("SUCCESS");
            assertThat(result.totalRows()).isEqualTo(100_000);
            assertThat(result.resultFileId()).isNotNull();
            assertThat(excel.downloadResult(taskId, false).contentLength()).isGreaterThan(0);
        } finally {
            sampler.interrupt();
            sampler.join(1_000L);
        }
        System.out.printf("PERF excel_100k_heap_peak_mb=%.1f%n",
                peakHeapBytes.get() / (1024D * 1024D));
        assertThat(peakHeapBytes.get()).as("10 万行 Excel 堆峰值")
                .isLessThanOrEqualTo(384L * 1024L * 1024L);
    }

    private ExcelTaskDTO awaitTask(String taskId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        ExcelTaskDTO task;
        do {
            task = excel.task(taskId);
            if (List.of("SUCCESS", "PARTIAL", "FAILED", "CANCELLED").contains(task.status())) {
                return task;
            }
            sleep(50L);
        } while (System.nanoTime() < deadline);
        return excel.task(taskId);
    }

    private static byte[] workbook(ItRow... rows) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ExcelKit.write(output, ItRow.class, List.of(rows), "Sheet1");
            return output.toByteArray();
        } catch (RuntimeException e) {
            throw e;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static Thread startHeapSampler(AtomicLong peakHeapBytes) {
        Thread sampler = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                long used = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
                peakHeapBytes.accumulateAndGet(used, Math::max);
                try {
                    Thread.sleep(5L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "it-heap-sampler");
        sampler.setDaemon(true);
        sampler.start();
        return sampler;
    }
}
