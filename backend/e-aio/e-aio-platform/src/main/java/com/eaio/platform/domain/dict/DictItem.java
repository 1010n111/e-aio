package com.eaio.platform.domain.dict;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.eaio.platform.infrastructure.persistence.JsonbStringTypeHandler;

/**
 * 字典项行（P1 册 3.2.2 的 {@code domain/dict/DictItem}；表见 4.3.4）。
 *
 * <p>两处映射细节：
 * <ol>
 *   <li>{@code is_default} 列名与 Java 属性名不同名（属性 {@code isDefault} 的 JavaBean 访问器是
 *       {@code getIsDefault()}），显式 {@code @TableField("is_default")} 钉死映射，不靠命名推导；</li>
 *   <li>{@code ext_json} 是 **JSONB**，必须带 {@code JsonbStringTypeHandler} + {@code autoResultMap}：
 *       普通 String 绑定会让 PG 报"column is of type jsonb but expression is of type character varying"。</li>
 * </ol>
 */
@TableName(value = "eaio_platform.dict_item", autoResultMap = true)
public class DictItem {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String typeCode;
    private String itemValue;
    private String itemLabel;
    private int sortNo;
    private String status;

    @TableField("is_default")
    private boolean isDefault;

    /** 前端渲染扩展（颜色/图标等）；无结构校验（3.2.4），原样进 JSONB。 */
    @TableField(value = "ext_json", typeHandler = JsonbStringTypeHandler.class)
    private String extJson;

    private String remark;
    private Instant createdAt;
    private Long createdBy;
    private Instant updatedAt;
    private Long updatedBy;

    @Version
    private Integer version;

    /** 逻辑删除（{@code uk_dict_item_type_value} 是 {@code WHERE deleted = false} 的部分唯一索引）。 */
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

    public String getItemValue() {
        return itemValue;
    }

    public void setItemValue(String itemValue) {
        this.itemValue = itemValue;
    }

    public String getItemLabel() {
        return itemLabel;
    }

    public void setItemLabel(String itemLabel) {
        this.itemLabel = itemLabel;
    }

    public int getSortNo() {
        return sortNo;
    }

    public void setSortNo(int sortNo) {
        this.sortNo = sortNo;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean getIsDefault() {
        return isDefault;
    }

    public void setIsDefault(boolean isDefault) {
        this.isDefault = isDefault;
    }

    public String getExtJson() {
        return extJson;
    }

    public void setExtJson(String extJson) {
        this.extJson = extJson;
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
