package com.eaio.common.redis;

import com.eaio.common.exception.SystemException;

/**
 * Redis 客户端不可用：连不上、命令失败，或命令结果无法判定（例如 SETNX 未返回布尔值）。
 *
 * <p>刻意与"键不存在"区分：键不存在是正常业务分支（返回 {@code null}/{@code false}），
 * 不可用是故障，必须让调用方看见并显式决策（fail-closed 或降级回源），不得静默当成"没有数据"。
 */
public class RedisUnavailableException extends SystemException {

    public RedisUnavailableException(String message) {
        super(message);
    }

    public RedisUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
