package com.paytm.wallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ErrorResponse(
    String error,
    String message,
    @JsonProperty("correlation_id") String correlationId
) {}
