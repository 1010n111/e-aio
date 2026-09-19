package com.eaio.platform.domain.job;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 任务参数的取值约束（P1 册 4.7，纯函数）。
 *
 * <p>4.7 逐字要求：{@code params_json} **拒绝含 {@code password}/{@code secret}/{@code token}/{@code key}
 * 的键名**。理由不是"看起来脏"，而是任务参数会被写进 {@code job_run} 的相邻日志、管理页、运维导出：
 * 一旦允许放密钥，密钥就会从"配置"变成"到处都在的系统数据"，轮换时无处可查。
 *
 * <p>判定是**大小写无关的子串匹配**（{@code apiKey}/{@code API_TOKEN}/{@code dbPassword} 都要拦住），
 * 宁可误伤（{@code monkey} 这种含 {@code key} 的键名）也不放过——误伤只让人改个键名，
 * 漏放则是密钥落库（4.7 的代价取舍）。
 */
public final class JobParamRules {

    /** 拒绝的键名片段（小写比较）。 */
    private static final List<String> SECRET_MARKERS = List.of("password", "secret", "token", "key");

    private JobParamRules() {
    }

    /** 返回第一个含密钥语义的键名（没有则空）；调用方据此报 10000。 */
    public static Optional<String> firstSecretKey(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return Optional.empty();
        }
        for (String key : params.keySet()) {
            if (key == null) {
                continue;
            }
            String normalized = key.toLowerCase(Locale.ROOT);
            for (String marker : SECRET_MARKERS) {
                if (normalized.contains(marker)) {
                    return Optional.of(key);
                }
            }
        }
        return Optional.empty();
    }
}
