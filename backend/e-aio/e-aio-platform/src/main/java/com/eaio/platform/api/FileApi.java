package com.eaio.platform.api;

import java.util.List;

import com.eaio.common.api.PageResult;
import com.eaio.platform.api.dto.FileBindCmd;
import com.eaio.platform.api.dto.FileChunkCmd;
import com.eaio.platform.api.dto.FileChunkResult;
import com.eaio.platform.api.dto.FileDTO;
import com.eaio.platform.api.dto.FileDownloadCmd;
import com.eaio.platform.api.dto.FileQuery;
import com.eaio.platform.api.dto.FileUploadCmd;
import com.eaio.platform.api.dto.FileUrlDTO;
import com.eaio.platform.api.dto.FileMergeCmd;
import org.springframework.core.io.Resource;

/**
 * 统一文件能力（P1 册 5.4 契约 V1，冻结）。
 *
 * <p><b>上传/下载是流式的</b>：调用方负责关闭流（HTTP 面由框架管）；本接口**不做权限判定**——跨模块调用
 * 意味着"调用方已经鉴权"（HLD 2.4.2）。权限判定只发生在 HTTP 面（{@code /platform/file/Download}，
 * 失败码 20017）。
 *
 * <p><b>事务语义（契约的一部分）</b>：{@link #upload(FileUploadCmd)} 与将来的 {@code mergeChunks}
 * **自己开启独立事务**（文件行与事件登记必须原子，且不应把调用方事务拖长），其余方法不开启事务（5.4）。
 * 所有方法**同步返回**。
 *
 * <p>分片上传的会话由服务端管理，重复分片覆盖写，重复合并返回原文件。
 */
public interface FileApi {

    /*
     * Transaction clarification: upload (and future mergeChunks) uses an independent transaction.
     * Other write methods do not use REQUIRES_NEW; the application service may use REQUIRED to keep
     * multi-step writes and event registration atomic while still joining a caller transaction.
     */

    /** 单文件上传；耗时与文件大小成正比（50MB ≈ 1–3s，本地盘）。自己开启事务。 */
    FileDTO upload(FileUploadCmd cmd);

    /** 上传一个分片；首片创建会话，重复片幂等覆盖。 */
    FileChunkResult uploadChunk(FileChunkCmd cmd);

    /** 合并分片；缺片报 20013，过期会话报 20018。 */
    FileDTO mergeChunks(FileMergeCmd cmd);

    /** 下载：返回 {@link Resource}（含 contentLength 与文件名），调用方负责关闭；{@code inline} 控制展示方式。 */
    Resource download(FileDownloadCmd cmd);

    /** 带外下载链接：本地盘为 HMAC 时效 token，S3 为预签名 URL；**不绕过数据权限**（HTTP 面仍会判定）。 */
    FileUrlDTO getUrl(long fileId, int expireSeconds);

    /** 元数据；文件不存在或已逻辑删除抛 20014。 */
    FileDTO getMeta(long fileId);

    /** 逻辑删除（乐观锁由 HTTP 面的 {@code {fileId, version}} 承担）；物理清理由 {@code platform.file.orphan.clean} 负责。 */
    void del(long fileId);

    /** 绑定业务对象（幂等：重复绑定不报错）。 */
    void bind(String bizType, long bizId, List<Long> fileIds);

    /** 分页（管理页）；{@code bizType}+{@code bizId} 经 {@code file_binding} 关联过滤。 */
    PageResult<FileDTO> getPage(FileQuery query);

    /** 入参对象兼容入口；跨模块冻结签名是上面的三参数方法。 */
    default void bind(FileBindCmd cmd) {
        bind(cmd.bizType(), cmd.bizId(), cmd.fileIds());
    }
}
