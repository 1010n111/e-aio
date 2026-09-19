package com.eaio.arch.probes;

import com.eaio.common.api.BusinessErrorCode;

/** 控制组：码落到别的模块段（21000 属 iam 段）。 */
public enum OutOfSegmentErrorCode implements BusinessErrorCode {

    FOREIGN_SEGMENT(21000, "占用了别人的段");

    private final int code;
    private final String message;

    OutOfSegmentErrorCode(int code, String message) {
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
