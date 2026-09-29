package com.eaio.platform.application.file;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.eaio.platform.api.JobHandler;
import com.eaio.platform.api.dto.JobContext;
import com.eaio.platform.domain.file.FileChunk;
import com.eaio.platform.domain.file.FileUploadSession;
import com.eaio.platform.infrastructure.persistence.FileStore;
import com.eaio.platform.infrastructure.storage.LocalFileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 内置分片会话过期任务：先标记过期，再删除分片文件与元数据。 */
@Component
public class FileSessionExpireHandler implements JobHandler {
    public static final String CODE = "platform.file.session.expire";
    private static final int BATCH_SIZE = 500;
    private static final int MAX_BATCHES = 20;
    private static final Logger log = LoggerFactory.getLogger(FileSessionExpireHandler.class);

    private final FileStore store;
    private final LocalFileStorage storage;
    private final FileParams params;

    public FileSessionExpireHandler(FileStore store, LocalFileStorage storage, FileParams params) {
        this.store = store;
        this.storage = storage;
        this.params = params;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public void execute(JobContext ctx) {
        Instant now = Instant.now();
        store.expireSessions(now);
        int cleaned = 0;
        Instant before = now.minus(Duration.ofDays(params.sessionRetainDays()));
        for (int batch = 0; batch < MAX_BATCHES && !ctx.expired(); batch++) {
            List<FileUploadSession> sessions = store.expiredSessionsBefore(before, BATCH_SIZE);
            if (sessions.isEmpty()) {
                break;
            }
            for (FileUploadSession session : sessions) {
                List<FileChunk> chunks = store.chunksFor(session.getId());
                for (FileChunk chunk : chunks) {
                    storage.deleteChunk(session.getUploadId(), chunk.getChunkIndex());
                }
                storage.deleteChunkDirectory(session.getUploadId());
                store.deleteChunks(session.getId());
                store.deleteSession(session.getId());
                cleaned++;
            }
            if (sessions.size() < BATCH_SIZE) {
                break;
            }
        }
        log.info("分片会话过期清理完成：jobCode={} runId={} cleaned={}", ctx.jobCode(), ctx.runId(), cleaned);
    }
}
