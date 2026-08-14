package com.paytm.wallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

public record TransferDetailsResponse(
    @JsonProperty("id")
    UUID id,

    @JsonProperty("from_user")
    String fromUser,

    @JsonProperty("to_user")
    String toUser,

    @JsonProperty("amount_paise")
    Long amountPaise,

    @JsonProperty("created_at")
    Instant createdAt
) {}
