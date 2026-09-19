package com.eaio.platform.api;

import com.eaio.common.api.BusinessErrorCode;

/**
 * 平台模块错误码表（P1 册 7.1，段 {@code 20000–20999}，本册占用 45 个号）。
 *
 * <p>三条硬口径：
 * <ul>
 *   <li><b>只在自己的段内</b>：通用段（10000/10001/10003/10401/10403/10500/10501/10502）不得重定义；</li>
 *   <li><b>空号不回收</b>：删除的码保留空洞（20009/20019/20026–20029/…），新码从段尾追加——
 *       码复用会污染历史日志与前端映射；</li>
 *   <li><b>段与重号由架构测试强制</b>：{@code ArchitectureTest#moduleErrorCodesWithinSegment}
 *       扫描本枚举的每个常量（P1 册 6.3）。</li>
 * </ul>
 *
 * <p>放 {@code api} 包：错误码是调用方判断失败原因的契约，与接口同属可见面（P1 册 2.3 裁决 P1-C2
 * 允许 enum 出现在本包）。
 */
public enum PlatformErrorCode implements BusinessErrorCode {

    // ---- 参数配置中心 20001–20008 ----
    PARAM_NOT_FOUND(20001, "参数不存在"),
    PARAM_TYPE_MISMATCH(20002, "参数值类型不匹配"),
    DICT_TYPE_NOT_FOUND(20003, "字典类型不存在"),
    PARAM_DUPLICATED(20004, "参数键在该级别已存在"),
    PARAM_BUILTIN_READONLY(20005, "系统内置参数不可删除"),
    PARAM_SCOPE_INVALID(20006, "参数归属非法"),
    DICT_ITEM_DUPLICATED(20007, "字典项值重复"),
    DICT_TYPE_IN_USE(20008, "字典类型下仍有字典项"),

    // ---- 文件中心 20010–20018 ----
    FILE_EMPTY(20010, "上传文件为空"),
    FILE_TOO_LARGE(20011, "文件超过大小上限"),
    FILE_TYPE_NOT_ALLOWED(20012, "文件类型不在白名单"),
    FILE_CHUNK_INVALID(20013, "分片校验失败"),
    FILE_NOT_FOUND(20014, "文件不存在"),
    FILE_STORAGE_ERROR(20015, "文件存储读写失败"),
    FILE_SIGNATURE_INVALID(20016, "下载签名无效或已过期"),
    FILE_ACCESS_DENIED(20017, "无权访问该文件"),
    FILE_SESSION_NOT_FOUND(20018, "分片上传会话不存在或已过期"),

    // ---- 定时任务 20020–20025 ----
    JOB_NOT_FOUND(20020, "任务不存在"),
    JOB_RUNNING(20021, "任务正在执行"),
    JOB_CRON_INVALID(20022, "Cron 表达式非法"),
    JOB_HANDLER_NOT_REGISTERED(20023, "任务处理器未注册"),
    JOB_DISABLED(20024, "任务已停用"),
    JOB_TIMEOUT(20025, "任务执行超时"),

    // ---- Excel 导入导出 20030–20037 ----
    EXCEL_PARSE_FAILED(20030, "Excel 解析失败"),
    EXCEL_ROW_INVALID(20031, "Excel 行校验失败"),
    EXCEL_TEMPLATE_MISMATCH(20032, "Excel 模板不匹配"),
    EXCEL_ROW_LIMIT_EXCEEDED(20033, "超出单次导入/导出行数上限"),
    EXCEL_TASK_CONFLICT(20034, "已存在相同导入任务"),
    EXCEL_TASK_NOT_FOUND(20035, "异步任务不存在或已过期"),
    EXCEL_QUEUE_FULL(20036, "Excel 任务队列已满"),
    EXCEL_HANDLER_NOT_REGISTERED(20037, "导入处理器未注册"),

    // ---- 通知模板与公告 20040–20045 ----
    NOTIFY_TEMPLATE_NOT_FOUND(20040, "通知模板不存在"),
    NOTIFY_TEMPLATE_CODE_DUPLICATED(20041, "模板编码已存在"),
    NOTIFY_VARIABLE_MISSING(20042, "模板变量缺失"),
    NOTIFY_RENDER_FAILED(20043, "模板渲染失败"),
    NOTICE_NOT_FOUND(20044, "公告不存在"),
    NOTICE_PUBLISH_INVALID(20045, "公告发布参数非法"),

    // ---- 缓存 20050–20051 ----
    CACHE_UNAVAILABLE(20050, "缓存服务不可用"),
    CACHE_REGION_UNKNOWN(20051, "未登记的缓存区"),

    // ---- 告警 20060–20062 ----
    ALERT_RULE_NOT_FOUND(20060, "告警规则不存在"),
    ALERT_RULE_INVALID(20061, "告警规则非法"),
    ALERT_NOT_FOUND(20062, "告警记录不存在"),

    // ---- 事件投递 20070–20071 ----
    EVENT_DELIVERY_NOT_FOUND(20070, "事件投递记录不存在"),
    EVENT_REPLAY_NOT_ALLOWED(20071, "该事件状态不允许重放"),

    // ---- 系统监测 20080 ----
    MONITOR_UNAVAILABLE(20080, "监测数据不可用");

    private final int code;
    private final String message;

    PlatformErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public int getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
