package com.eaio.arch.probes;

import com.eaio.common.api.BusinessErrorCode;

/** 控制组：与通用段重号（10000 是 {@code ErrorCode.PARAM_INVALID}）。 */
public enum GenericSegmentClashErrorCode implements BusinessErrorCode {

    CLASHES_WITH_GENERIC(10000, "重定义了通用段号码");

    private final int code;
    private final String message;

    GenericSegmentClashErrorCode(int code, String message) {
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
