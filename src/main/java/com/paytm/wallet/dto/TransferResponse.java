package com.paytm.wallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

public record TransferResponse(
    @JsonProperty("transfer_id")
    UUID transferId,

    @JsonProperty("new_balance")
    Long newBalance
) {}
