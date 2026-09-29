package com.eaio.platform.api;

/** Facts available to a business import handler for one row. */
public record ImportRowContext(String taskId, long rowNum, boolean cancelled) {
}
