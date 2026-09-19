package com.eaio.common.api;

/**
 * e-aio 错误码契约（契约 V1）。
 *
 * <p>实现者可以是 {@link ErrorCode} 枚举（通用段），也可以是各业务模块自定义的错误码枚举
 * （业务段）。业务模块的枚举值必须落在模块登记的错误码段内，由架构规则断言（P0 册 3.2.3）。
 */
public interface BusinessErrorCode {

    /** 业务错误码：0 表示成功。 */
    int getCode();

    /** 提示消息（中文）。 */
    String getMessage();
}
