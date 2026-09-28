package com.eaio.platform.api.dto;

import java.io.InputStream;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 单文件上传入参（P1 册 5.3 契约 V1）。
 *
 * <p><b>流的关闭责任在调用方</b>：HTTP 层由框架管（{@code MultipartFile} 的生命周期），跨模块调用方自己关。
 * 本对象只承载"一次上传的全部事实"，不做任何校验——校验顺序是 {@link com.eaio.platform.api.PlatformErrorCode}
 * 的 20011 → 20012 → 20010（先声明值早拒绝，再读流，3.3.5）。
 *
 * @param fileName    原始文件名（必填；扩展名从它取，是白名单校验的输入）
 * @param contentType 客户端声明的 MIME（可空；只记录不判定）
 * @param size        客户端声明的字节数（必填；未知传 {@code -1} 表示"流式计数"）
 * @param content     文件内容流（必填；由存储适配器边写边计数）
 * @param bizType     业务类型后缀（可空；给了就顺带绑定，省掉一次 {@code Bind} 往返）
 * @param bizId       业务对象 ID（可空；与 {@code bizType} 同时给才有意义）
 */
public record FileUploadCmd(
        @NotBlank String fileName,
        String contentType,
        @NotNull Long size,
        @NotNull InputStream content,
        String bizType,
        Long bizId) {

    /** 声明大小未知的哨兵值（{@code -1}）：走"边写边计数"路径，超限即中断（3.3.5 第 4 条）。 */
    public static final long SIZE_UNKNOWN = -1L;
}
