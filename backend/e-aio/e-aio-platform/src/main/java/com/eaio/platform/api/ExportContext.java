package com.eaio.platform.api;

import java.util.Map;

/** Export request facts passed to a lazy business row source. */
public record ExportContext(String taskId, String bizType, String templateCode, String requestJson,
        Map<String, String> parameters) {

    public ExportContext {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
