package com.eaio.common.api;

/**
 * 通用错误码（契约 V1，P0 册 3.2.3）。
 *
 * <p>分段约定：
 * <ul>
 *   <li>{@code 0}：成功，全系统唯一；</li>
 *   <li>{@code 10000–19999}：通用段（本枚举），空号不回收；</li>
 *   <li>{@code 20000+}：业务段，每模块预留 1000 号（20000 platform / 21000 iam / 22000 audit …），
 *       由各模块自定义枚举实现 {@link BusinessErrorCode}，不在此处登记。</li>
 * </ul>
 */
public enum ErrorCode implements BusinessErrorCode {

    SUCCESS(0, "成功"),

    // ---- 通用段：10000–19999 ----
    PARAM_INVALID(10000, "参数校验失败"),
    PARAM_MISSING(10001, "缺少必要参数"),
    DATA_NOT_FOUND(10002, "数据不存在"),
    DATA_CONFLICT(10003, "数据冲突（版本过期/重复）"),
    UNAUTHORIZED(10401, "未认证或令牌失效"),
    FORBIDDEN(10403, "无权限执行该操作"),
    SYSTEM_ERROR(10500, "系统内部错误"),
    IDEMPOTENT_REPLAY(10501, "重复提交"),
    IDEMPOTENCY_UNAVAILABLE(10502, "幂等校验不可用（Redis 不可用，请稍后重试）");

    /** 通用段起点（含）。 */
    public static final int GENERIC_CODE_MIN = 10000;
    /** 通用段终点（含）。 */
    public static final int GENERIC_CODE_MAX = 19999;
    /** 成功码。 */
    public static final int SUCCESS_CODE = 0;
    /** 业务段起点（含）：每模块预留 1000 号。 */
    public static final int BUSINESS_CODE_MIN = 20000;
    /** 每模块预留给业务错误码的号段大小。 */
    public static final int MODULE_CODE_SEGMENT = 1000;

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
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
