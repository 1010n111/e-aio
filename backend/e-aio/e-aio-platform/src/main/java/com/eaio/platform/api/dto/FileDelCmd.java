package com.eaio.platform.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 逻辑删除入参（P1 册 5.2 的 {@code /platform/file/Del}，幂等键：是；错误码 20014 20017 10003）。
 *
 * @param fileId  文件 ID（必填）
 * @param version 乐观锁版本（必填；过期即 10003——删除是"我看着这份元数据按的删除"，盲删会吃掉并发改动）
 */
public record FileDelCmd(
        @NotNull Long fileId,
        @NotNull @PositiveOrZero Integer version) {

    /** 便于从分页行构造（管理页的"删除"按钮拿到的就是一行 {@link FileDTO}）。 */
    public static FileDelCmd of(long fileId, int version) {
        return new FileDelCmd(fileId, version);
    }
}
