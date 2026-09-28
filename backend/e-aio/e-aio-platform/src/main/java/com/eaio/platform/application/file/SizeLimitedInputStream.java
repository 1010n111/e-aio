package com.eaio.platform.application.file;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import com.eaio.common.exception.BusinessException;
import com.eaio.platform.api.PlatformErrorCode;

/**
 * 上传流的硬上限：只让存储适配器读到上限字节，下一次仍有内容就立即失败。
 *
 * <p>适配器只需要实现 {@code InputStream} 拷贝，不需要知道平台参数；超限由这个共享边界统一处理。
 */
public final class SizeLimitedInputStream extends FilterInputStream {

    private final long maxBytes;
    private long bytesRead;

    public SizeLimitedInputStream(InputStream input, long maxBytes) {
        super(input);
        this.maxBytes = maxBytes;
    }

    @Override
    public int read() throws IOException {
        if (bytesRead == maxBytes) {
            if (super.read() != -1) {
                throw tooLarge();
            }
            return -1;
        }
        int value = super.read();
        if (value != -1) {
            bytesRead++;
        }
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        long remaining = maxBytes - bytesRead;
        if (remaining == 0) {
            return read();
        }
        int allowed = (int) Math.min(remaining, length);
        int count = super.read(buffer, offset, allowed);
        if (count > 0) {
            bytesRead += count;
        }
        return count;
    }

    private static BusinessException tooLarge() {
        return new BusinessException(PlatformErrorCode.FILE_TOO_LARGE, "文件超过大小上限");
    }
}
