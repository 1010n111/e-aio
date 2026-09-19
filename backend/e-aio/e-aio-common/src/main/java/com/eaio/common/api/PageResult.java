package com.eaio.common.api;

import java.util.List;

/**
 * 统一分页结果（契约 V1，P0 册 3.2.2）。
 *
 * <p><b>纯净性约束</b>：不依赖任何持久层类型（不引用 MyBatis-Plus {@code IPage} 等），
 * 由各模块在自身 {@code api} 层适配转换（P0 册 4.1）。
 *
 * <p>分页查询统一返回 {@code Result<PageResult<T>>}。
 *
 * @param <T> 当前页数据类型
 */
public class PageResult<T> {

    /** 总记录数。 */
    private long total;

    /** 当前页（从 1 开始）。 */
    private long pageNum;

    /** 页大小。 */
    private long pageSize;

    /** 当前页数据。 */
    private List<T> records;

    public PageResult() {
    }

    public PageResult(long pageNum, long pageSize, long total, List<T> records) {
        this.pageNum = pageNum;
        this.pageSize = pageSize;
        this.total = total;
        this.records = records;
    }

    /** 静态工厂：推荐入口，参数顺序符合调用习惯。 */
    public static <T> PageResult<T> from(long pageNum, long pageSize, long total, List<T> records) {
        return new PageResult<>(pageNum, pageSize, total, records);
    }

    /** 空页。 */
    public static <T> PageResult<T> empty(long pageNum, long pageSize) {
        return new PageResult<>(pageNum, pageSize, 0L, List.of());
    }

    /** 总页数；页大小为非正数时返回 0（避免除零）。 */
    public long pages() {
        if (pageSize <= 0) {
            return 0L;
        }
        return (total + pageSize - 1) / pageSize;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public long getPageNum() {
        return pageNum;
    }

    public void setPageNum(long pageNum) {
        this.pageNum = pageNum;
    }

    public long getPageSize() {
        return pageSize;
    }

    public void setPageSize(long pageSize) {
        this.pageSize = pageSize;
    }

    public List<T> getRecords() {
        return records;
    }

    public void setRecords(List<T> records) {
        this.records = records;
    }
}
