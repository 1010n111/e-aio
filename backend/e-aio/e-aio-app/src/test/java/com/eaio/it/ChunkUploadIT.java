package com.eaio.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.eaio.common.exception.BusinessException;
import com.eaio.platform.api.dto.FileChunkCmd;
import com.eaio.platform.api.dto.FileChunkResult;
import com.eaio.platform.api.dto.FileDownloadCmd;
import com.eaio.platform.api.dto.FileDTO;
import com.eaio.platform.api.dto.FileMergeCmd;
import com.eaio.platform.api.port.OrgContextPort;
import com.eaio.platform.application.FileAppService;
import com.eaio.platform.application.file.FileSessionExpireHandler;
import com.eaio.platform.infrastructure.persistence.FileStore;
import com.eaio.platform.infrastructure.storage.FilePresignTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/** #22：分片会话在真实 PostgreSQL 与本地盘上的幂等、合并和过期清理。 */
@SpringBootTest
class ChunkUploadIT extends IntegrationTestBase {

    static {
        System.setProperty(FilePresignTokenService.SECRET_ENV, "it-chunk-presign-secret-0123456789abcdef");
        System.setProperty("eaio.file.local-root", "target/it-files");
    }

    private static final long ORG_ID = 9200L;
    private static final long USER_ID = 9201L;

    @TestConfiguration
    static class OrgContextStub {
        static final AtomicReference<OrgContextPort.OrgContext> CURRENT = new AtomicReference<>();

        @Bean
        OrgContextPort orgContextPort() {
            return () -> Optional.ofNullable(CURRENT.get());
        }
    }

    @Autowired
    private FileAppService files;
    @Autowired
    private FileStore store;
    @Autowired
    private FileSessionExpireHandler expireHandler;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void signIn() {
        OrgContextStub.CURRENT.set(new OrgContextPort.OrgContext(ORG_ID, USER_ID));
    }

    @Test
    @DisplayName("乱序分片合并、同片重传与重复合并均幂等，完成后清理分片元数据")
    void unorderedUploadAndIdempotentMerge() throws Exception {
        byte[] content = "abcdefghij".getBytes(StandardCharsets.UTF_8);
        String uploadId = "it-" + UUID.randomUUID();
        String sha = sha256(content);

        upload(uploadId, 2, new byte[] {'i', 'j'}, 3, 4, content.length, sha);
        upload(uploadId, 0, new byte[] {'a', 'b', 'c', 'd'}, null, null, null, null);
        upload(uploadId, 1, new byte[] {'e', 'f', 'g', 'h'}, null, null, null, null);
        FileChunkResult retry = upload(uploadId, 1, new byte[] {'e', 'f', 'g', 'h'}, null, null, null, null);
        assertThat(retry.received()).isEqualTo(3);

        FileDTO merged = files.mergeChunks(new FileMergeCmd(uploadId, null, null));
        assertThat(merged.sha256()).isEqualTo(sha);
        assertThat(merged.sizeBytes()).isEqualTo(content.length);
        try (InputStream in = files.download(FileDownloadCmd.attachment(merged.id(), USER_ID)).getInputStream()) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }

        FileDTO repeated = files.mergeChunks(new FileMergeCmd(uploadId, null, null));
        assertThat(repeated.id()).isEqualTo(merged.id());
        Map<String, Object> session = jdbc.queryForMap(
                "select id, status, file_id from eaio_platform.file_upload_session where upload_id = ?", uploadId);
        assertThat(session.get("status")).isEqualTo("DONE");
        assertThat(session.get("file_id")).isEqualTo(merged.id());
        assertThat(store.countChunks(((Number) session.get("id")).longValue())).isZero();
    }

    @Test
    @DisplayName("缺片明确返回 20013；会话过期后续传与合并都返回 20018，清理任务删除旧会话")
    void missingAndExpiredSessions() {
        String missingId = "it-" + UUID.randomUUID();
        byte[] first = "abc".getBytes(StandardCharsets.UTF_8);
        upload(missingId, 0, first, 2, 3, 6, sha256("abcdef".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> files.mergeChunks(new FileMergeCmd(missingId, null, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(20013));

        String expiredId = "it-" + UUID.randomUUID();
        upload(expiredId, 0, first, 2, 3, 6, null);
        jdbc.update("update eaio_platform.file_upload_session set expire_time = now() - interval '1 second' "
                + "where upload_id = ?", expiredId);
        assertThatThrownBy(() -> upload(expiredId, 1, "def".getBytes(StandardCharsets.UTF_8), null, null, null, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(20018));
        assertThatThrownBy(() -> files.mergeChunks(new FileMergeCmd(expiredId, null, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(20018));

        jdbc.update("update eaio_platform.file_upload_session set status = 'EXPIRED', "
                + "expire_time = now() - interval '8 days' where upload_id = ?", expiredId);
        expireHandler.execute(new com.eaio.platform.api.dto.JobContext(
                FileSessionExpireHandler.CODE, Map.of(), 1L, 1, "it", java.time.Instant.now().plusSeconds(30)));
        assertThat(jdbc.queryForObject("select count(*) from eaio_platform.file_upload_session where upload_id = ?",
                Integer.class, expiredId)).isZero();
    }

    @Test
    @DisplayName("超大分片返回 20013")
    void oversizedChunkReturnsChunkInvalid() {
        String uploadId = "it-" + UUID.randomUUID();
        assertThatThrownBy(() -> upload(uploadId, 0, "abcd".getBytes(StandardCharsets.UTF_8), 2, 3, 6, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(20013));
    }

    private FileChunkResult upload(String uploadId, int index, byte[] bytes, Integer total, Integer size,
            Integer expectedSize, String fileSha) {
        return files.uploadChunk(new FileChunkCmd(uploadId, index, total, size, sha256(bytes),
                total == null ? null : "chunk.txt", expectedSize == null ? null : expectedSize.longValue(), fileSha,
                new ByteArrayInputStream(bytes)));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
