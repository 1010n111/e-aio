package com.eaio.platform.api.dto;

import java.time.Instant;

/**
 * 带外下载链接（P1 册 5.3 契约 V1）。
 *
 * <p>本地盘是 HMAC 时效 token（{@code /api/platform/file/Download?fileId=&exp=&sig=}），S3 是 SDK 的预签名 URL；
 * 两种形态的字符串都放在 {@code url} 里，调用方不必区分。**token 不绕过数据权限**：验证签名与时效之后
 * 仍走同一套可见性判定（3.3.4）。
 *
 * @param fileId      文件 ID
 * @param url         下载链接（同源路径或 S3 预签名 URL）
 * @param expireAt    失效时刻（服务端签发时算出；token 不是一次性、也不可撤销，轮换 secret 会让全部历史链接失效）
 * @param storageType 签发时的存储类型（诊断用）
 */
public record FileUrlDTO(long fileId, String url, Instant expireAt, String storageType) {
}
