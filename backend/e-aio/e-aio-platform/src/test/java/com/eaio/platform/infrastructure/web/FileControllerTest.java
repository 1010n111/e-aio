package com.eaio.platform.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import com.eaio.common.api.ErrorCode;
import com.eaio.common.api.Result;
import com.eaio.common.exception.BusinessException;
import com.eaio.platform.api.PlatformErrorCode;
import com.eaio.platform.api.dto.FileDelCmd;
import com.eaio.platform.api.dto.FileDTO;
import com.eaio.platform.application.FileAppService;
import com.eaio.platform.application.file.FileDownloadResource;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 文件控制器的入站测试（P1 册 3.3.9 第 5 行 + 3.3.4 的响应头口径）。
 *
 * <p>用 {@code standaloneSetup} 显式装配"控制器 + 统一返回体 + 异常处理"，不起 Spring 容器：
 * 断言的是**契约形状**（HTTP 恒 200 + body code、下载响应头、拒绝时不返回文件内容），与业务实现无关，
 * 因此应用服务用替身。
 *
 * <p>这里刻意**不**断言 {@code @PreAuthorize}（iam 未交付前注解不生效，6.5 第 9 条的权限点契约测试
 * 才是它的守卫）；本类断言的是"判定失败时对外长什么样"——尤其是拒绝下载必须是文件级的 <b>20017</b>
 * 而不是通用的 10403。
 *
 * <p>异常处理用本文件内的最小替身（{@code app} 壳的 {@code GlobalExceptionHandler} 不能被 platform
 * 引用：依赖方向是 app → platform，反过来会让模块编译不过）。它与应用壳的实现同一形状：
 * 业务异常 → 同码 {@code Result}。
 */
class FileControllerTest {

    private static final String DOWNLOAD = "/platform/file/Download";
    private static final String GET_URL = "/platform/file/GetUrl";

    private FileAppService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(FileAppService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new FileController(service))
                .setControllerAdvice(new BusinessFailureAdvice())
                .build();
    }

    @Test
    @DisplayName("下载：响应头四条齐备、Content-Type 取库值、文件名按 RFC 5987 编码")
    void downloadSetsMandatoryHeaders() throws Exception {
        when(service.getMeta(1L)).thenReturn(dto(1L, "年度报告.pdf", "application/pdf"));
        when(service.download(any())).thenReturn(new FileDownloadResource("年度报告.pdf", 3L,
                new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8))));

        mockMvc.perform(post(DOWNLOAD).contentType(MediaType.APPLICATION_JSON).content("{\"fileId\":1}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("Content-Disposition", Matchers.allOf(
                        Matchers.startsWith("attachment; "),
                        Matchers.containsString("filename*=UTF-8''%E5%B9%B4%E5%BA%A6%E6%8A%A5%E5%91%8A.pdf"))))
                .andExpect(content().bytes("abc".getBytes(StandardCharsets.UTF_8)));

        // 判定与留痕都在开流之前：被拒绝的请求不会碰存储
        verify(service).requireVisible(1L);
        verify(service).auditDownload(any());
    }

    @Test
    @DisplayName("下载被拒：20017（文件级）而不是 10403，且不返回文件内容、不打开流")
    void deniedDownloadReturnsFileLevelCode() throws Exception {
        doThrow(new BusinessException(PlatformErrorCode.FILE_ACCESS_DENIED, "无权访问该文件：fileId=1"))
                .when(service).requireVisible(1L);

        mockMvc.perform(post(DOWNLOAD).contentType(MediaType.APPLICATION_JSON).content("{\"fileId\":1}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(PlatformErrorCode.FILE_ACCESS_DENIED.getCode()));

        verify(service, never()).download(any());
    }

    @Test
    @DisplayName("预签名下载：GET 路径校验签名（20016），缺失 exp/sig 同样 20016")
    void presignedDownloadRequiresSignature() throws Exception {
        doThrow(new BusinessException(PlatformErrorCode.FILE_SIGNATURE_INVALID, "下载签名无效或已过期"))
                .when(service).requireValidPresign(anyLong(), any(), any());

        mockMvc.perform(get(DOWNLOAD).param("fileId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(PlatformErrorCode.FILE_SIGNATURE_INVALID.getCode()));
        mockMvc.perform(get(DOWNLOAD).param("fileId", "1").param("exp", "1").param("sig", "ff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(PlatformErrorCode.FILE_SIGNATURE_INVALID.getCode()));
        verify(service, never()).download(any());
    }

    @Test
    @DisplayName("GetUrl：先判可见性再签（判定失败 → 20017，不签发链接）")
    void getUrlChecksVisibilityBeforeSigning() throws Exception {
        doThrow(new BusinessException(PlatformErrorCode.FILE_ACCESS_DENIED, "无权访问该文件：fileId=1"))
                .when(service).requireVisible(1L);

        mockMvc.perform(post(GET_URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":1,\"expireSeconds\":600}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(PlatformErrorCode.FILE_ACCESS_DENIED.getCode()));

        verify(service, never()).getUrl(anyLong(), anyInt());
    }

    @Test
    @DisplayName("Del：版本过期由应用服务判定（10003 原样透出），控制器不再自己比对")
    void deleteDelegatesVersionCheck() throws Exception {
        doThrow(new BusinessException(ErrorCode.DATA_CONFLICT, "文件已被他人修改（version 过期）：fileId=1"))
                .when(service).del(any(FileDelCmd.class));

        mockMvc.perform(post("/platform/file/Del").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":1,\"version\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.DATA_CONFLICT.getCode()));
        verify(service).requireVisible(1L);
    }

    @Test
    @DisplayName("Del：不可见文件返回 20017 且不进入删除服务")
    void deleteChecksVisibilityBeforeMutation() throws Exception {
        doThrow(new BusinessException(PlatformErrorCode.FILE_ACCESS_DENIED, "无权访问该文件：fileId=1"))
                .when(service).requireVisible(1L);

        mockMvc.perform(post("/platform/file/Del").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":1,\"version\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(PlatformErrorCode.FILE_ACCESS_DENIED.getCode()));

        verify(service, never()).del(any(FileDelCmd.class));
    }

    @Test
    @DisplayName("Del：缺 fileId/version 是参数校验失败（10000），不是 10500")
    void deleteValidatesRequiredFields() throws Exception {
        mockMvc.perform(post("/platform/file/Del").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_INVALID.getCode()));
    }

    @Test
    @DisplayName("绑定：bizType 为空/文件列表为空都是 10000（在控制器入参校验就拦下）")
    void bindValidatesRequiredFields() throws Exception {
        mockMvc.perform(post("/platform/file/Bind").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bizType\":\" \",\"bizId\":1,\"fileIds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_INVALID.getCode()));
        verify(service, never()).bind(any());
    }

    private static FileDTO dto(long id, String name, String contentType) {
        return new FileDTO(id, name, "pdf", contentType, 3L, "abc", "LOCAL", 7L, 9L, "UPLOAD",
                java.time.Instant.parse("2026-01-01T00:00:00Z"), 0, null);
    }

    /** 与应用壳 {@code GlobalExceptionHandler} 同形状的最小替身：业务异常 → 同码返回体。 */
    @RestControllerAdvice
    static class BusinessFailureAdvice {

        @ExceptionHandler(BusinessException.class)
        Result<Void> handle(BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }

        @ExceptionHandler({org.springframework.web.bind.MethodArgumentNotValidException.class,
                org.springframework.web.bind.MissingServletRequestParameterException.class,
                org.springframework.http.converter.HttpMessageNotReadableException.class})
        Result<Void> handleBadRequest(Exception e) {
            return Result.fail(ErrorCode.PARAM_INVALID);
        }
    }
}
