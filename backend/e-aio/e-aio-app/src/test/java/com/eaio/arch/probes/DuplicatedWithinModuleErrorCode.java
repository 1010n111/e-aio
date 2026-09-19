package com.eaio.arch.probes;

import com.eaio.common.api.BusinessErrorCode;

/** 控制组：模块内重号（同一模块两个常量共用一个号码）。 */
public enum DuplicatedWithinModuleErrorCode implements BusinessErrorCode {

    FIRST(20001, "第一个"),
    SECOND(20001, "第二个（重号）");

    private final int code;
    private final String message;

    DuplicatedWithinModuleErrorCode(int code, String message) {
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
