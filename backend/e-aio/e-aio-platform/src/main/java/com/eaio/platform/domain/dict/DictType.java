package com.eaio.platform.domain.dict;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

/**
 * 字典类型行（P1 册 3.2.2 的 {@code domain/dict/DictType}；表见 4.3.3）。
 *
 * <p>表名带 Schema 限定，理由同 {@code ParamItem}：应用连接的默认 search_path 是 {@code public}，
 * 而模块表在 {@code eaio_platform}。{@code id} 用 {@code IdType.INPUT}（雪花 ID 由代码生成，禁自增）。
 */
@TableName("eaio_platform.dict_type")
public class DictType {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String typeCode;
    private String typeName;
    private String status;

    /** 平台内置类型：管理页不允许删除（种子里的 4 个类型都是内置）。 */
    private boolean builtin;

    private String remark;
    private Instant createdAt;
    private Long createdBy;
    private Instant updatedAt;
    private Long updatedBy;

    /** 乐观锁（并发改类型）：写路径带 version 条件更新，影响行数为 0 即 10003。 */
    @Version
    private Integer version;

    /** 逻辑删除（{@code uk_dict_type_code} 是 {@code WHERE deleted = false} 的部分唯一索引）。 */
    @TableLogic
    private Boolean deleted;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTypeCode() {
        return typeCode;
    }

    public void setTypeCode(String typeCode) {
        this.typeCode = typeCode;
    }

    public String getTypeName() {
        return typeName;
    }

    public void setTypeName(String typeName) {
        this.typeName = typeName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isBuiltin() {
        return builtin;
    }

    public void setBuiltin(boolean builtin) {
        this.builtin = builtin;
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
