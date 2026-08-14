package com.paytm.wallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record TransferRequest(
    @JsonProperty("to_user")
    @NotBlank(message = "to_user is required")
    String toUser,

    @JsonProperty("amount_paise")
    @NotNull(message = "amount_paise is required")
    @Positive(message = "amount_paise must be strictly positive")
    Long amountPaise,

    @JsonProperty("idempotency_key")
    @NotBlank(message = "idempotency_key is required")
    String idempotencyKey
) {}
