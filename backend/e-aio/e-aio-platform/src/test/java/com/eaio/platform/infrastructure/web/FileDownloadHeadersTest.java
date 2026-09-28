package com.eaio.platform.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * 下载响应头的测试（P1 册 3.3.4：防响应头注入 + 防缓存泄漏 + 禁止 MIME 嗅探）。
 *
 * <p>重点是"文件名是用户提供的输入"：CR/LF 注入、引号截断、超长文件名三类都要有用例。
 */
class FileDownloadHeadersTest {

    @Test
    @DisplayName("中文文件名：percent-encoded 后无损，同时给可打印 ASCII 兜底")
    void encodesChineseFilename() {
        String header = FileDownloadHeaders.contentDisposition("年度报告.pdf");

        assertThat(header).startsWith("attachment; filename=\"");
        assertThat(header).contains("filename*=UTF-8''");
        assertThat(header).contains("%E5%B9%B4%E5%BA%A6%E6%8A%A5%E5%91%8A.pdf");
        assertThat(header).as("头里绝不能出现裸的控制字符").doesNotContain("\r").doesNotContain("\n");
    }

    @Test
    @DisplayName("CR/LF 注入：换行被替换，响应头保持单行")
    void stripsLineBreaks() {
        String header = FileDownloadHeaders.contentDisposition("evil\r\nX-Injected: 1.pdf");

        assertThat(header).doesNotContain("\r").doesNotContain("\n");
        assertThat(header).contains("X-Injected: 1.pdf").as("滤掉的是控制字符，不是内容");
        assertThat(header).doesNotContain("\u2028").doesNotContain("\u2029");
    }

    @Test
    @DisplayName("引号与反斜杠：净化成单引号/下划线，不截断 filename=\"...\"")
    void neutralizesQuotesAndBackslashes() {
        String header = FileDownloadHeaders.contentDisposition("a\"b\\c.pdf");

        assertThat(header).startsWith("attachment; filename=\"a'b_c.pdf\"");
    }

    @Test
    @DisplayName("超长与空文件名：截到 200 字符、空名退化为 download")
    void truncatesAndDefaults() {
        String longName = "n".repeat(500) + ".pdf";
        assertThat(FileDownloadHeaders.sanitize(longName)).hasSize(FileDownloadHeaders.MAX_FILENAME_LENGTH);
        assertThat(FileDownloadHeaders.contentDisposition(null)).contains("filename=\"download\"");
        assertThat(FileDownloadHeaders.contentDisposition("   ")).contains("filename=\"download\"");
    }

    @Test
    @DisplayName("四条响应头齐备：Content-Type 取库值、nosniff、private no-store、attachment")
    void setsAllMandatoryHeaders() {
        HttpHeaders headers = FileDownloadHeaders.of("application/pdf", "a.pdf");

        assertThat(headers.getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo("application/pdf");
        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("private, no-store");
        assertThat(headers.getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment; ");
    }

    @Test
    @DisplayName("库里的类型为空时兜底二进制，绝不回落 JSON（前端按 Content-Type 分辨成败）")
    void fallsBackToOctetStream() {
        assertThat(FileDownloadHeaders.safeContentType(null))
                .isEqualTo(FileDownloadHeaders.FALLBACK_CONTENT_TYPE);
        assertThat(FileDownloadHeaders.safeContentType("  ")).isEqualTo("application/octet-stream");
        assertThat(FileDownloadHeaders.safeContentType(" text/plain ")).isEqualTo("text/plain");
    }
}
