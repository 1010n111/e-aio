package com.eaio.platform.domain.file;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 文件名与扩展名解析的纯函数测试（P1 册 3.3.5 第 2 条的输入侧）。
 *
 * <p>三个容易各自出错的点各有用例：大小写（{@code Report.PDF} 必须通过）、多段扩展名（只认最后一段，
 * 否则 {@code a.pdf.exe} 会以 pdf 的身份过白名单）、路径片段与换行（原始名要进 {@code Content-Disposition}）。
 */
class FileNamesTest {

    @Test
    @DisplayName("扩展名：小写化、取最后一段、无扩展名/畸形返回空")
    void extensionOf() {
        assertThat(FileNames.extensionOf("report.pdf")).isEqualTo("pdf");
        assertThat(FileNames.extensionOf("Report.PDF")).isEqualTo("pdf");
        assertThat(FileNames.extensionOf("archive.tar.gz")).isEqualTo("gz");
        assertThat(FileNames.extensionOf("a.pdf.exe")).isEqualTo("exe");

        assertThat(FileNames.extensionOf("README")).isEmpty();
        assertThat(FileNames.extensionOf("trailing.")).isEmpty();
        assertThat(FileNames.extensionOf(null)).isEmpty();
        assertThat(FileNames.extensionOf("weird.p!df")).as("扩展名含非字母数字字符一律视为无扩展名").isEmpty();
        assertThat(FileNames.extensionOf("long." + "a".repeat(40))).as("超过 32 字符的扩展名落不进列").isEmpty();
    }

    @Test
    @DisplayName("基名：剥掉客户端带上的目录部分与 CR/LF（响应头注入的入口）")
    void baseName() {
        assertThat(FileNames.baseName("C:\\Users\\x\\report.pdf")).isEqualTo("report.pdf");
        assertThat(FileNames.baseName("../../etc/passwd")).isEqualTo("passwd");
        assertThat(FileNames.baseName("  spaced.pdf  ")).isEqualTo("spaced.pdf");
        assertThat(FileNames.baseName("bad\r\nname.pdf")).isEqualTo("bad  name.pdf");
        assertThat(FileNames.baseName(null)).isEmpty();
    }

    @Test
    @DisplayName("落库文件名：截断到 255、空名退化为占位（列是 NOT NULL）")
    void normalizedOriginalName() {
        assertThat(FileNames.normalizedOriginalName("report.pdf")).isEqualTo("report.pdf");

        String longName = "x".repeat(300) + ".pdf";
        assertThat(FileNames.normalizedOriginalName(longName)).hasSize(FileNames.MAX_NAME_LENGTH);

        assertThat(FileNames.normalizedOriginalName("   ")).isEqualTo("unnamed");
        assertThat(FileNames.normalizedOriginalName(null)).isEqualTo("unnamed");
    }
}
