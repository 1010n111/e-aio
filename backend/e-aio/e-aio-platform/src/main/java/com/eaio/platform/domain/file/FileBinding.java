package com.eaio.platform.domain.file;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 文件与业务对象的绑定行（P1 册 4.3.8）。
 *
 * <p>{@code bizType} 是业务类型后缀（如 {@code crm.customer}）：platform **不解释它的含义**，只做索引
 * ——平台表不被业务字段污染（3.3.7 的取舍）。
 *
 * <p><b>本表没有 {@code deleted}/{@code updated_at}/{@code version} 列</b>：P1-3 册 4.2 的统一列例外清单
 * 明确把 {@code file_binding} 列为"关系行，解绑即物理删"。该条比批次总册 3.5 的通用口径更具体，按
 * "更具体者优先"适用例外；P1 也没有解绑接口，逻辑删除列只会是死列。
 */
@TableName("eaio_platform.file_binding")
public class FileBinding {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long fileId;
    private String bizType;
    private Long bizId;
    private Instant createdAt;
    private Long createdBy;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getFileId() {
        return fileId;
    }

    public void setFileId(Long fileId) {
        this.fileId = fileId;
    }

    public String getBizType() {
        return bizType;
    }

    public void setBizType(String bizType) {
        this.bizType = bizType;
    }

    public Long getBizId() {
        return bizId;
    }

    public void setBizId(Long bizId) {
        this.bizId = bizId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }
}
