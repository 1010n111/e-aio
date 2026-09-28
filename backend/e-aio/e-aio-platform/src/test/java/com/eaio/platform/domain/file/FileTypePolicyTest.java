package com.eaio.platform.domain.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;

import com.eaio.common.exception.BusinessException;
import com.eaio.platform.api.PlatformErrorCode;
import com.eaio.platform.api.dto.FileUploadCmd;
import com.eaio.platform.application.param.ParamContextProvider;
import com.eaio.platform.application.param.ParamResolver;
import com.eaio.platform.application.param.ResolvedParam;
import com.eaio.platform.domain.param.ParamContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 文件类型策略的测试（P1 册 3.3.9 的前两行 + 3.3.5 的短路顺序）。
 *
 * <p>替身只替"参数中心"这一层：策略本身是纯判断（6.1 的纯逻辑层），参数值由测试直接给定，
 * 于是"白名单拦不拦得住"与"参数中心读得对不对"两件事不会互相掩盖。
 */
class FileTypePolicyTest {

    private ParamResolver params;
    private FileTypePolicy policy;

    @BeforeEach
    void setUp() {
        params = Mockito.mock(ParamResolver.class);
        ParamContextProvider contexts = Mockito.mock(ParamContextProvider.class);
        when(contexts.current()).thenReturn(ParamContext.systemOnly());
        policy = new FileTypePolicy(params, contexts);
    }

    @Test
    @DisplayName("白名单外：.exe/.jsp/.sh 一律 20012（无扩展名同样拒绝）")
    void rejectsExtensionsOutsideWhitelist() {
        allowExtensions("pdf,jpg");
        allowMaxSize(1024L);

        for (String name : new String[] {"virus.exe", "shell.jsp", "run.sh", "noext"}) {
            assertThatThrownBy(() -> policy.validateUpload(cmd(name, 10L)))
                    .as("%s 不在白名单", name)
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getCode())
                            .isEqualTo(PlatformErrorCode.FILE_TYPE_NOT_ALLOWED.getCode()));
        }
    }

    @Test
    @DisplayName("大小写混合 Report.PDF 通过（统一小写比较）")
    void acceptsMixedCaseExtension() {
        allowExtensions("pdf");
        allowMaxSize(1024L);

        assertThat(policy.validateUpload(cmd("Report.PDF", 10L))).isEqualTo("pdf");
    }

    @Test
    @DisplayName("声明大小超限：20011 且不读流（顺序上早于扩展名判定）")
    void rejectsDeclaredSizeBeforeReadingStream() {
        allowExtensions("pdf");
        allowMaxSize(100L);

        assertThatThrownBy(() -> policy.validateUpload(cmd("big.pdf", 101L)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(PlatformErrorCode.FILE_TOO_LARGE.getCode()));
    }

    @Test
    @DisplayName("声明大小未知（-1）：跳过早拒绝，交给流式计数")
    void skipsDeclaredSizeCheckWhenUnknown() {
        allowExtensions("pdf");
        allowMaxSize(100L);

        assertThat(policy.validateUpload(cmd("stream.pdf", FileUploadCmd.SIZE_UNKNOWN))).isEqualTo("pdf");
    }

    @Test
    @DisplayName("参数坏值：白名单空/上限非数字时回落 7.2 默认值，不让上传直接 10500")
    void fallsBackToDefaultsOnBrokenParamValues() {
        when(params.resolveOrDefault(anyString(), anyString(), any(ParamContext.class)))
                .thenAnswer(invocation -> ResolvedParam.ofDefault(invocation.getArgument(0),
                        invocation.getArgument(1)));

        assertThat(policy.maxSizeBytes()).isEqualTo(FileTypePolicy.DEFAULT_MAX_SIZE);
        assertThat(policy.allowedExtensions()).contains("pdf", "jpg", "7z");
        assertThat(policy.allowedExtensions()).doesNotContain("");
    }

    private void allowExtensions(String value) {
        when(params.resolveOrDefault(FileTypePolicy.ALLOWED_EXT_KEY, FileTypePolicy.DEFAULT_ALLOWED_EXT,
                ParamContext.systemOnly()))
                .thenReturn(new ResolvedParam(FileTypePolicy.ALLOWED_EXT_KEY, value, "DB", "SYSTEM", true, null));
    }

    private void allowMaxSize(long value) {
        when(params.resolveOrDefault(FileTypePolicy.MAX_SIZE_KEY, String.valueOf(FileTypePolicy.DEFAULT_MAX_SIZE),
                ParamContext.systemOnly()))
                .thenReturn(new ResolvedParam(FileTypePolicy.MAX_SIZE_KEY, String.valueOf(value), "DB", "SYSTEM",
                        true, null));
    }

    private static FileUploadCmd cmd(String fileName, long size) {
        return new FileUploadCmd(fileName, "application/octet-stream", size,
                new ByteArrayInputStream(new byte[0]), null, null);
    }
}
