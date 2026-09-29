package com.eaio.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import com.eaio.platform.api.ParamApi;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/** #25：可显式开启的性能抽验，真实 PostgreSQL/Redis 上记录验收 P95。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformPerformanceIT extends IntegrationTestBase {

    private static final long PERF_FILE_ID_START = 8_500_000_000_000_000L;
    private static final int PERF_FILE_COUNT = 1_000_000;
    private static final int SAMPLE_COUNT = 40;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ParamApi paramApi;

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("#25 性能抽验：缓存、常规分页与百万行分页")
    void performanceAcceptance() {
        Assumptions.assumeTrue(Boolean.getBoolean("eaio.performance"),
                "性能抽验默认关闭，使用 -Deaio.performance=true 显式执行");

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        removePerfRows(jdbc);
        insertPerfRows(jdbc);
        try {
            long cacheP95 = p95(SAMPLE_COUNT, () -> paramApi.getInt("platform.file.max-size", -1));
            long regularPageP95 = p95(SAMPLE_COUNT, () -> getPage(1));
            long millionPageP95 = p95(SAMPLE_COUNT, () -> getPage(5_000));

            System.out.printf("PERF cache_hit_p95_ms=%d regular_page_p95_ms=%d million_page_p95_ms=%d%n",
                    cacheP95, regularPageP95, millionPageP95);
            assertThat(cacheP95).as("缓存命中 P95").isLessThan(50L);
            assertThat(regularPageP95).as("常规分页 P95").isLessThan(500L);
            assertThat(millionPageP95).as("百万行分页 P95").isLessThan(1_000L);
        } finally {
            removePerfRows(jdbc);
        }
    }

    private void getPage(int pageNum) {
        String body = client().post()
                .uri("/api/platform/file/GetPage")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"pageNum\":" + pageNum
                        + ",\"pageSize\":200,\"orderBy\":\"id\",\"orderDir\":\"ASC\"}")
                .retrieve()
                .body(String.class);
        assertThat(body).contains("\"code\":0");
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private static long p95(int samples, Runnable operation) {
        for (int i = 0; i < 10; i++) {
            operation.run();
        }
        List<Long> elapsedNanos = new ArrayList<>(samples);
        for (int i = 0; i < samples; i++) {
            long started = System.nanoTime();
            operation.run();
            elapsedNanos.add(System.nanoTime() - started);
        }
        elapsedNanos.sort(Long::compareTo);
        int p95Index = Math.min(elapsedNanos.size() - 1, (int) Math.ceil(elapsedNanos.size() * 0.95D) - 1);
        return (elapsedNanos.get(p95Index) + 999_999L) / 1_000_000L;
    }

    private void insertPerfRows(JdbcTemplate jdbc) {
        long started = System.nanoTime();
        jdbc.update("""
                INSERT INTO eaio_platform.file
                    (id, original_name, extension, content_type, size_bytes, sha256, storage_type,
                     storage_path, uploader_id, uploader_org_id, source, created_at, created_by,
                     updated_at, updated_by, version, deleted)
                SELECT ? + g, 'perf-' || g || '.txt', 'txt', 'text/plain', 1, repeat('0', 64), 'LOCAL',
                       'perf/' || g, NULL, 0, 'UPLOAD', now(), 0, NULL, NULL, 0, false
                FROM generate_series(0, 999999) AS g
                """, PERF_FILE_ID_START);
        jdbc.execute("ANALYZE eaio_platform.file");
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        System.out.printf("PERF million_file_rows_inserted=%d elapsed_ms=%d%n", PERF_FILE_COUNT, elapsedMillis);
    }

    private void removePerfRows(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM eaio_platform.file WHERE id >= ? AND id < ?", PERF_FILE_ID_START,
                PERF_FILE_ID_START + PERF_FILE_COUNT);
    }
}
