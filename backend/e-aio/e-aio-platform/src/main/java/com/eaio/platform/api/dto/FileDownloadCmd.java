package com.eaio.platform.api.dto;

/**
 * 下载入参（P1 册 5.3 契约 V1）。
 *
 * <p><b>跨模块调用不判权限</b>（P1 册 5.4、HLD 2.4.2"调用方鉴权"）：{@code FileApi.download} 是给业务模块
 * 用的内部出口，调用方自己已经鉴权过。权限判定只发生在 HTTP 面（{@code /platform/file/Download}）。
 *
 * @param fileId     文件 ID（必填）
 * @param operatorId 操作者 ID（可空；只用于留痕，不参与权限判定）
 * @param inline     {@code true} = 浏览器内联展示，{@code false}（默认）= 附件下载
 */
public record FileDownloadCmd(long fileId, Long operatorId, boolean inline) {

    /** 附件下载（默认形态）。 */
    public static FileDownloadCmd attachment(long fileId, Long operatorId) {
        return new FileDownloadCmd(fileId, operatorId, false);
    }
}
