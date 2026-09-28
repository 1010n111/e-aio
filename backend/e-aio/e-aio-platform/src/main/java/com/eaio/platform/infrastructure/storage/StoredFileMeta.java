package com.eaio.platform.infrastructure.storage;

import java.time.Instant;

/**
 * 存储适配器的落盘元数据（P1 册 3.3.2 的 {@code StoredFileMeta}，record 落地）。
 *
 * <p>适配器需要的只有"文件名/扩展名/生成路径要用的 ID"三样：字节数由适配器**边写边数**（不信任声明值），
 * 摘要由服务层重算（不信任客户端），两者都不进本对象。
 *
 * @param fileId      文件 ID（雪花；也是磁盘文件名与目录分片的依据）
 * @param extension   扩展名（小写、不含点；已经过白名单校验——适配器不再判断合法性，只负责拼路径）
 * @param contentType 客户端声明的 MIME（S3 适配器写对象元数据用；本地盘忽略）
 * @param createdAt   创建时刻（决定 {@code {yyyy}/{MM}/{dd}} 落盘目录；由服务层传入便于测试与重放）
 */
public record StoredFileMeta(long fileId, String extension, String contentType, Instant createdAt) {
}
