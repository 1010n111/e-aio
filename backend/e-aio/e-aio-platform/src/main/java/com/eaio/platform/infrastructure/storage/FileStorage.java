package com.eaio.platform.infrastructure.storage;

import java.io.InputStream;

import com.eaio.platform.api.dto.FileUrlDTO;
import com.eaio.platform.domain.file.FileMetaFile;

/**
 * 文件存储 SPI（P1 册 3.3.2）：**模块内接口**，不进 {@code com.eaio.platform.api}——业务模块只认
 * {@code FileApi}，换存储是平台自己的事（HLD 11.3：历史文件不失效靠按行路由，见裁决 P1-C5）。
 *
 * <p>实现只有两个：{@link LocalFileStorage}（默认，零依赖）。S3 适配器（{@code S3FileStorage}）是
 * 可选项，本票**未实现**（需 {@code minio}/{@code s3} 客户端依赖 + {@code s3} profile，见《实现注记（T9）》）：
 * 契约里 {@code storage_type} 已经有 {@code S3} 这个值（DDL 的 CHECK 允许），但没有任何行会写成它。
 *
 * <p>三条实现约定：
 * <ol>
 *   <li>{@link #store} 返回的是**相对存储路径**（不含根目录）：库里的 {@code storage_path} 不该绑定某台
 *       机器的绝对路径，换机器/换挂载点只需改根目录配置；</li>
 *   <li>{@link #open} 返回的流由**调用方**关闭；</li>
 *   <li>所有实现必须在落盘/读盘前把路径限制在自己的根目录内（{@link LocalFileStorage} 做
 *       {@code realpath} 前缀校验）：{@code storage_path} 是库里的字符串，篡改它不该变成任意文件读写。</li>
 * </ol>
 */
public interface FileStorage {

    /** 适配器类型（与 {@code file.storage_type} 的取值逐字一致：{@code LOCAL}/{@code S3}）。 */
    String type();

    /**
     * 流式落盘并返回相对存储路径；实现负责边写边计数（超限由调用方中断 + 删临时文件）。
     *
     * @throws com.eaio.common.exception.SystemException 磁盘/IO 失败（由服务层转 20015）
     */
    String store(InputStream in, StoredFileMeta meta);

    /** 打开已落盘文件的流（**调用方负责关闭**）；文件不存在时抛异常（服务层转 20015）。 */
    InputStream open(String storagePath);

    /** 删除（幂等：不存在即成功）。 */
    void delete(String storagePath);

    /** 是否存在（孤儿清理与诊断用）。 */
    boolean exists(String storagePath);

    /** 生成带外下载链接（本地盘为 HMAC 时效 token，S3 为 SDK 预签名 URL）；不判权限（3.3.4）。 */
    FileUrlDTO presignGet(FileMetaFile meta, int expireSeconds);
}
