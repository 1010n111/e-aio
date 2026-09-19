package com.eaio.platform.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 只带事件 ID 的入参（P1 册 5.2 的 {@code {eventId}} 形态）：{@code eventDelivery/Get} 与
 * {@code eventDelivery/Replay} 共用（两个端点的入参逐字相同）。
 *
 * @param eventId 事件 ID（消费侧幂等键，非雪花数字 ID）
 */
public record EventIdCmd(@NotBlank @Size(max = 64) String eventId) {
}
