package com.eaio.platform.domain.param;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

/**
 * 参数行（P1 册 3.1.2 的 {@code domain/param/ParamItem}）。
 *
 * <p>表名带 Schema 限定：应用连接的默认 search_path 是 {@code public}，而模块表在
 * {@code eaio_platform}（P0 册 3.6 的"每模块一个 Schema"）。多模块并存时 search_path 无法表达
 * "谁的表在谁的 Schema"，所以 DDL 与实体都写全名（P1 册 4.3 的显式 schema 限定）。
 *
 * <p>{@code id} 用 {@code IdType.INPUT}：雪花 ID 由代码生成（P0 册 3.5 禁自增）。
 */
@TableName("eaio_platform.param")
public class ParamItem {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String paramKey;
    private String paramLevel;
    private long ownerId;
    private String paramValue;
    private String valueType;
    private String paramGroup;
    private boolean encrypted;
    private boolean builtin;
    private boolean hotReload;
    private String remark;
    private Instant createdAt;
    private Long createdBy;
    private Instant updatedAt;
    private Long updatedBy;

    /** 乐观锁（并发改值）：写路径带 version 条件更新，影响行数为 0 即 10003。 */
    @Version
    private Integer version;

    /** 逻辑删除（{@code uk_param_key_level_owner} 是 {@code WHERE deleted = false} 的部分唯一索引）。 */
    @TableLogic
    private Boolean deleted;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getParamKey() {
        return paramKey;
    }

    public void setParamKey(String paramKey) {
        this.paramKey = paramKey;
    }

    public String getParamLevel() {
        return paramLevel;
    }

    public void setParamLevel(String paramLevel) {
        this.paramLevel = paramLevel;
    }

    public long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(long ownerId) {
        this.ownerId = ownerId;
    }

    public String getParamValue() {
        return paramValue;
    }

    public void setParamValue(String paramValue) {
        this.paramValue = paramValue;
    }

    public String getValueType() {
        return valueType;
    }

    public void setValueType(String valueType) {
        this.valueType = valueType;
    }

    public String getParamGroup() {
        return paramGroup;
    }

    public void setParamGroup(String paramGroup) {
        this.paramGroup = paramGroup;
    }

    public boolean isEncrypted() {
        return encrypted;
    }

    public void setEncrypted(boolean encrypted) {
        this.encrypted = encrypted;
    }

    public boolean isBuiltin() {
        return builtin;
    }

    public void setBuiltin(boolean builtin) {
        this.builtin = builtin;
    }

    public boolean isHotReload() {
        return hotReload;
    }

    public void setHotReload(boolean hotReload) {
        this.hotReload = hotReload;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Boolean getDeleted() {
        return deleted;
    }

    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
    }
}
