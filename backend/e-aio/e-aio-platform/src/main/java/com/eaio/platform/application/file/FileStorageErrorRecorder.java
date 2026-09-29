package com.eaio.platform.application.file;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/** Process-local count of file storage failures exposed by the monitoring snapshot. */
@Component
public class FileStorageErrorRecorder {

    private final AtomicLong errors = new AtomicLong();

    public void record() {
        errors.incrementAndGet();
    }

    public long count() {
        return errors.get();
    }
}
