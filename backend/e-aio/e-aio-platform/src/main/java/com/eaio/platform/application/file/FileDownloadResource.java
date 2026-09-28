package com.eaio.platform.application.file;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.core.io.AbstractResource;

/**
 * 下载结果（P1 册 5.4：{@code Resource} 含 {@code contentLength} 与文件名；文件路径不在其中）。
 *
 * <p>不直接用 {@code InputStreamResource} 的原因：它**不实现** {@code contentLength()}（返回 -1），
 * 而"不知道长度的附件下载"会让浏览器显示成未知大小、也算不出进度；{@code FileSystemResource} 更好，
 * 但它会把本地绝对路径带到 Web 层，正是 3.3.5 要避免的暴露面（文件系统布局不该渗出存储适配器）。
 * 因此这里只带"存储适配器给出的流 + 库里的元数据"。
 *
 * <p>{@code getFilename()} 返回的是**原始文件名**（已过 {@link com.eaio.platform.domain.file.FileNames}
 * 的基名与换行净化），Web 层据此生成 {@code Content-Disposition}。
 */
public class FileDownloadResource extends AbstractResource {

    private final String filename;
    private final long contentLength;
    private final InputStream stream;

    public FileDownloadResource(String filename, long contentLength, InputStream stream) {
        this.filename = filename;
        this.contentLength = contentLength;
        this.stream = stream;
    }

    @Override
    public String getDescription() {
        return "文件下载流（filename=" + filename + ", contentLength=" + contentLength + "）";
    }

    @Override
    public InputStream getInputStream() {
        return stream;
    }

    @Override
    public String getFilename() {
        return filename;
    }

    @Override
    public long contentLength() {
        return contentLength;
    }

    @Override
    public boolean exists() {
        return true;
    }

    /** 关闭底层流（存储适配器打开的句柄）；调用方负责调用。 */
    public void close() throws IOException {
        stream.close();
    }
}
